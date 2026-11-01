package com.shrinkcam.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 自研压缩流水线：MediaExtractor 解封装 -> MediaCodec 硬编（H.265 优先）-> MediaMuxer 封装。
 *
 * 相比 ffmpeg 动辄 20~50MB 的 so 体积，这条路 0 依赖、走厂商硬编、速度快、功耗低。
 * 为了把体积压到最小，做了这些事：
 *  1. 按"目标体积"反算码率（体积优先模式），并留出容器/音频开销
 *  2. 分辨率/帧率分级下采样，人眼不敏感的区域省下来的比特全给画面主体
 *  3. 优先 HEVC，同码率下比 H.264 省约 40%
 *  4. 首轮编码后按实测体积做一次自适应校准，直接把误差压到 ±5%
 *  5. 音频重编码为单声道低码率 AAC，长视频省下可观体积
 */
class VideoCompressor(private val ctx: Context) {

    data class Progress(val percent: Int, val message: String, val passText: String = "")

    class Result(
        val file: File,
        val plan: CompressPlan,
        val elapsedMs: Long,
        val passes: Int
    )

    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    /**
     * @param src 源视频
     * @param info 源信息
     * @param quality 档位
     * @param targetBytes 目标体积；<=0 表示由档位自动决定
     * @param outFile 输出文件
     * @param sizeMode 分辨率策略
     * @param keepAudio 是否保留音频；false 时输出无声视频
     */
    fun compress(
        src: Uri,
        info: VideoInfo,
        quality: Quality,
        targetBytes: Long,
        outFile: File,
        sizeMode: SizeMode = SizeMode.SCALE,
        keepAudio: Boolean = true,
        onProgress: (Progress) -> Unit
    ): Result {
        cancelled = false
        val started = System.currentTimeMillis()
        var plan = CompressPlan.make(info, quality, targetBytes, sizeMode = sizeMode, keepAudio = keepAudio)
        onProgress(Progress(0, "分析视频", "计划 ${plan.outWidth}×${plan.outHeight} · ${plan.videoBitrate / 1000}kbps · ${plan.codecName}"))

        // ================= 第 1 步：先分离音频，独立跑完整条链路 =================
        // 音频不再和视频共用主循环，彻底摆脱"视频结束了音频还没编完"的时序问题。
        val audioFile = File(outFile.parentFile, outFile.nameWithoutExtension + ".audio.m4a")
        var audioOut: AudioPipeline.Output? = null
        if (keepAudio && plan.audioBitrate > 0 && info.hasAudio) {
            onProgress(Progress(2, "分离音频", "音频独立处理，不与视频抢时序"))
            audioOut = try {
                AudioPipeline(ctx).extract(src, audioFile, plan.audioBitrate)
            } catch (t: Throwable) {
                Log.w(TAG, "audio pipeline failed, continue without audio", t)
                null
            }
            if (audioOut != null) {
                Log.i(TAG, "audio ok: ${audioOut.durationUs / 1000}ms, " +
                    "${audioFile.length() / 1024}KB")
            } else {
                Log.w(TAG, "audio extraction returned null, output will be silent")
            }
        }
        if (cancelled) {
            audioFile.delete()
            throw CancelledException()
        }

        // ================= 第 2 步：只编码视频画面 =================
        var passes = 1
        val videoPart = File(outFile.parentFile, outFile.nameWithoutExtension + ".video.mp4")
        val videoPlan = plan.copy(audioBitrate = 0)
        runPass(src, info, videoPlan, videoPart, onProgress)

        // ---- 自适应校准：目标体积模式下，偏差超过 8% 就再跑一轮，最多两轮 ----
        if (targetBytes > 0 && !cancelled) {
            val actual = videoPart.length() + (audioFile.length().takeIf { audioOut != null } ?: 0L)
            val diff = (actual - targetBytes).toDouble() / targetBytes.toDouble()
            if (diff > 0.08 || diff < -0.20) {
                val factor = (targetBytes.toDouble() / actual.toDouble()).coerceIn(0.45, 1.6).toFloat()
                val plan2 = CompressPlan.make(
                    info, quality, targetBytes, factor = 1f,
                    sizeMode = sizeMode, keepAudio = keepAudio
                ).let {
                    it.copy(
                        videoBitrate = (it.videoBitrate * factor).toInt().coerceAtLeast(60_000),
                        estimateBytes = targetBytes,
                        audioBitrate = 0
                    )
                }
                if (plan2.videoBitrate < videoPlan.videoBitrate || diff > 0.08) {
                    passes = 2
                    onProgress(Progress(0, "体积校准中", "第一轮 ${Fmt.size(actual)}，正在校准到 ${Fmt.size(targetBytes)}"))
                    plan = plan2.copy(
                        rotation = plan.rotation,
                        outWidth = plan.outWidth,
                        outHeight = plan.outHeight,
                        outFps = plan.outFps
                    )
                    runPass(src, info, plan.copy(audioBitrate = 0), videoPart, onProgress, passBase = 0, passNo = 2)
                }
            }
        }

        if (cancelled) {
            videoPart.delete(); audioFile.delete()
            throw CancelledException()
        }

        // ================= 第 3 步：合并音视频 =================
        onProgress(Progress(98, "合并音视频", if (audioOut != null) "音轨并入画面" else "无声输出"))
        val ok = Mp4Merger.merge(
            videoFile = videoPart,
            audioFile = if (audioOut != null) audioFile else null,
            outFile = outFile,
            rotation = plan.rotation
        )
        videoPart.delete()
        audioFile.delete()

        if (!ok) throw IllegalStateException("音视频合并失败，请重试")

        onProgress(Progress(100, "完成", Fmt.size(outFile.length())))
        // 对外汇报的 plan 保留真实音频码率，便于 UI 展示
        val reportPlan = plan.copy(audioBitrate = if (audioOut != null) plan.audioBitrate else 0)
        return Result(outFile, reportPlan, System.currentTimeMillis() - started, passes)
    }

    class CancelledException : RuntimeException("已取消")

    // ------------------------------------------------------------------ 单轮编码

    private fun runPass(
        src: Uri,
        info: VideoInfo,
        plan: CompressPlan,
        out: File,
        onProgress: (Progress) -> Unit,
        passBase: Int = 0,
        passNo: Int = 1
    ) {
        if (out.exists()) out.delete()
        val extractor = MediaExtractor()
        ctx.contentResolver.openFileDescriptor(src, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
        }

        var videoTrack = -1
        var srcVideoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/") && videoTrack < 0) {
                videoTrack = i
                srcVideoFormat = f
            }
        }
        if (videoTrack < 0 || srcVideoFormat == null) {
            extractor.release()
            throw IllegalStateException("找不到视频轨道")
        }
        // 关键：选中视频轨，否则 readSampleData 拿不到任何数据
        extractor.selectTrack(videoTrack)

        val srcW = srcVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = srcVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val srcRotation = info.rotation
        val mime = if (plan.useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        fun buildFormat(codecMime: String): MediaFormat =
            MediaFormat.createVideoFormat(codecMime, plan.outWidth, plan.outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, plan.outFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                // 编码器只接收 GL Surface，不需要 MAX_INPUT_SIZE；留空交给 codec 默认
            }

        // 能力校验：确认某 mime 的编码器真的支持目标分辨率与 Surface 输入
        fun encoderSupports(codecMime: String): Boolean = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
                c.isEncoder && c.supportedTypes.any { it.equals(codecMime, true) } &&
                    c.getCapabilitiesForType(codecMime).videoCapabilities
                        .isSizeSupported(plan.outWidth, plan.outHeight)
            }
        } catch (_: Throwable) {
            false
        }

        // HEVC 不可用/初始化失败 -> 自动降级 AVC，绝不硬抛
        var effectiveMime = mime
        if (!encoderSupports(effectiveMime)) {
            effectiveMime = MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val outFormat = buildFormat(effectiveMime)

        var videoEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null
        try {
            var encSurface: android.view.Surface? = null
            fun openEncoder(codecMime: String, fmt: MediaFormat): MediaCodec {
                val enc = MediaCodec.createEncoderByType(codecMime)
                return try {
                    enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val sf = enc.createInputSurface()
                    enc.start()
                    encSurface = sf
                    enc
                } catch (t: Throwable) {
                    try { enc.release() } catch (_: Throwable) {}
                    throw t
                }
            }
            var encReady: MediaCodec? = null
            try {
                encReady = openEncoder(effectiveMime, outFormat)
            } catch (t: Throwable) {
                Log.w(TAG, "encoder init failed for $effectiveMime, fallback to AVC", t)
                encSurface = null
                if (effectiveMime != MediaFormat.MIMETYPE_VIDEO_AVC) {
                    effectiveMime = MediaFormat.MIMETYPE_VIDEO_AVC
                    encReady = openEncoder(effectiveMime, buildFormat(effectiveMime))
                } else {
                    throw t
                }
            }
            videoEncoder = encReady!!
            inputSurface = encSurface

            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            // 方向原封不动：像素不做任何旋转（保持源编码坐标系），
            // 朝向信息写进容器元数据，成片方向与原片严格一致。
            if (plan.rotation != 0) {
                mux.setOrientationHint(plan.rotation)
            }

            val renderer = VideoRenderer(
                encoder = videoEncoder,
                encoderSurface = inputSurface!!,
                srcFormat = srcVideoFormat,
                extractor = extractor,
                srcW = srcW,
                srcH = srcH,
                outW = plan.outWidth,
                outH = plan.outHeight,
                outFps = plan.outFps,
                durationUs = info.durationUs,
                rotation = srcRotation
            )
            val renderThread = Thread(renderer, "video-render")
            renderThread.start()
            var renderThreadStarted = true

            // ---- 封装循环（纯视频；音频由 AudioPipeline 独立处理后合并）----
            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrackIndex = -1
            var muxerStarted = false
            var videoDone = false
            var lastProgress = -1L
            val totalUs = info.durationUs.coerceAtLeast(1)
            var videoDoneAt = -1L
            var lastVideoActivityAt = System.currentTimeMillis()

            while (!videoDone && !cancelled) {
                // 兜底 1：渲染线程退出。区分两种情况：
                //  a) 真崩溃（fatal != null）-> 立即抛真实异常
                //  b) 正常完成（渲染完 EOS 已 signalEndOfInputStream）->
                //     继续等编码器吐完剩余数据，不能在这里误杀！
                //     （1080p 编码慢，EOS flush 需要时间，之前在这里
                //      判 "线程死了且视频没完" 会把正常收尾当成崩溃，
                //      这就是"保持原尺寸必失败、等比缩小正常"的根因：
                //      720p 编码快窗口小，1080p 窗口必现。）
                if (renderThreadStarted && !videoDone && !renderThread.isAlive) {
                    val cause = renderer.fatal
                    if (cause != null) {
                        Log.w(TAG, "render thread crashed", cause)
                        throw IllegalStateException(
                            "视频渲染线程异常退出（已渲染 ${renderer.renderedFrames} 帧，" +
                                "进度 ${renderer.presentationUs / 1000}ms）" +
                                "：${cause.javaClass.simpleName}: ${cause.message}",
                            cause
                        )
                    }
                    // 正常完成，落到底部继续 drain 编码器；若编码器 hang 死
                    // 会由兜底 3（20 秒无产出）强制收尾
                }
                // 兜底 3：视频轨超过 20s 没有任何产出（编码器 hang 死），强制收尾
                if (!videoDone) {
                    val now = System.currentTimeMillis()
                    if (lastVideoActivityAt > 0 && now - lastVideoActivityAt > 20_000) {
                        Log.w(TAG, "video encoder stalled 20s, force finalize")
                        // 视频没编完就退出，产出会是坏文件，明确报错而不是静默保存
                        if (!cancelled) {
                            throw IllegalStateException(
                                "视频编码器停滞（已渲染 ${renderer.renderedFrames} 帧，" +
                                    "进度 ${renderer.presentationUs / 1000}ms，20 秒无输出），请重试或改用等比缩小"
                            )
                        }
                        break
                    }
                }
                // 视频
                if (!videoDone) {
                    val idx = videoEncoder.dequeueOutputBuffer(bufferInfo, 4_000)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            lastVideoActivityAt = System.currentTimeMillis()
                            if (!muxerStarted) {
                                videoTrackIndex = mux.addTrack(videoEncoder.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        }
                        idx >= 0 -> {
                            lastVideoActivityAt = System.currentTimeMillis()
                            val buf = videoEncoder.getOutputBuffer(idx)
                            if (buf != null && bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                if (!muxerStarted) {
                                    if (videoTrackIndex < 0) videoTrackIndex = mux.addTrack(videoEncoder.outputFormat)
                                    mux.start()
                                    muxerStarted = true
                                }
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                mux.writeSampleData(videoTrackIndex, buf, bufferInfo)
                            }
                            videoEncoder.releaseOutputBuffer(idx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                videoDone = true
                                videoDoneAt = System.currentTimeMillis()
                            }
                        }
                    }
                }

                val pct = (renderer.presentationUs * 100 / totalUs).coerceIn(0, 99)
                if (pct / 5 != lastProgress / 5) {
                    lastProgress = pct
                    onProgress(
                        Progress(
                            ((passBase + pct) * 0.98).roundToInt().coerceIn(0, 99),
                            "正在压缩",
                            if (passNo > 1) "第 2 轮校准 · $pct%" else "$pct%"
                        )
                    )
                }
            }

            renderThread.join(6_000)

            if (muxerStarted) {
                try {
                    mux.stop()
                } catch (e: Throwable) {
                    Log.w(TAG, "mux stop", e)
                }
            }
            if (cancelled) throw CancelledException()
        } finally {
            try {
                videoEncoder?.stop()
            } catch (_: Throwable) {
            }
            try {
                videoEncoder?.release()
            } catch (_: Throwable) {
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            try {
                inputSurface?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    companion object {
        private const val TAG = "VideoCompressor"
    }
}

fun MediaFormat.optInt(key: String, def: Int): Int =
    if (containsKey(key)) try {
        getInteger(key)
    } catch (_: Throwable) {
        def
    } else def
