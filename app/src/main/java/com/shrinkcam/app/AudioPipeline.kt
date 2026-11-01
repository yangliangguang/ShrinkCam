package com.shrinkcam.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频独立管线：**先分离、后合并**。
 *
 * 完整的四段式：
 *
 *   ┌── 1. 分离 ──┐   ┌── 2. 解码 ──┐   ┌── 3. 重编码 ──┐   ┌── 4. 合并 ──┐
 *   │ 取出音轨包  │ → │ 包 → PCM    │ → │ PCM → AAC    │ → │ 音轨并入视频 │
 *   └────────────┘   └─────────────┘   └──────────────┘   └────────────┘
 *
 * 为什么必须拆开：旧实现把音频编码塞在视频渲染主循环里，音频能不能编完
 * 完全取决于视频进度。视频一结束主循环就停，音频队列里剩下的样本直接被丢
 * —— 这就是「首尾音频丢失」的根因，靠加超时/补零都只是打补丁。
 *
 * 拆开之后，音频自己跑完整条链路：源文件读到底 → 解码到底 → 编码到底，
 * 全程不受视频快慢影响，天然不会截断。产物是一个独立的 .m4a 中间文件，
 * 最后由 [Mp4Merger] 把两条轨合并。
 */
class AudioPipeline(private val ctx: Context) {

    companion object {
        private const val TAG = "AudioPipeline"
        /** AAC 帧长固定 1024 样本 */
        private const val AAC_FRAME = 1024
    }

    /** 音频处理结果 */
    data class Output(
        val file: File,
        val sampleRate: Int,
        val channels: Int,
        val durationUs: Long,
        val encodedFrames: Long
    )

    /**
     * 把源视频的音频轨分离出来，重编码为低码率 AAC，写入独立文件。
     *
     * @param src 源视频
     * @param outFile 输出 .m4a
     * @param bitrate 目标音频码率（bps）
     * @param maxSeconds 时长上限（微秒），仅用于安全兜底，正常不会触发
     * @return 音频产物；源无音轨或不可编码时返回 null
     */
    fun extract(
        src: Uri,
        outFile: File,
        bitrate: Int,
        maxSeconds: Long = 0L,
        /** true：源为 AAC 时直接复制码流，不做任何压缩（音质=原片） */
        passthrough: Boolean = true
    ): Output? {
        if (outFile.exists()) outFile.delete()

        // ---------- 阶段 1：分离音轨 ----------
        val extractor = MediaExtractor()
        try {
            ctx.contentResolver.openFileDescriptor(src, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "open source failed", t)
            extractor.release()
            return null
        }

        var audioTrack = -1
        var srcFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/") && audioTrack < 0) {
                audioTrack = i
                srcFormat = f
            }
        }
        if (audioTrack < 0 || srcFormat == null) {
            Log.i(TAG, "no audio track, skip")
            extractor.release()
            return null
        }

        val srcRate = srcFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 44100)
        val srcCh = srcFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)
            ?: MediaFormat.MIMETYPE_AUDIO_AAC

        // ---------- 阶段 0：直通（首选）----------
        // 源是 AAC 就直接复制码流：不解码、不重采样、不重编码，
        // 音质与原片完全一致，速度只受 IO 限制。任何一步失败自动降级重编码。
        if (passthrough && srcMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)) {
            val out = passthroughCopy(extractor, audioTrack, srcFormat, outFile)
            if (out != null) {
                extractor.release()
                Log.i(
                    TAG, "audio passthrough: ${out.durationUs / 1000}ms, " +
                        "${outFile.length() / 1024}KB"
                )
                return out
            }
            Log.w(TAG, "passthrough failed, fallback to re-encode")
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        // 码率低时压成单声道 + 24kHz，体积最省；高码率跟源采样率走，少一次重采样
        val outCh = if (bitrate <= 64000) 1 else minOf(srcCh, 2)
        val outRate = if (bitrate <= 64000) 24000 else minOf(srcRate, 48000)

        val decoder = try {
            MediaCodec.createDecoderByType(srcMime).apply {
                configure(srcFormat, null, null, 0)
                start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decoder init failed", t)
            extractor.release()
            return null
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            // ---------- 阶段 3：重编码 ----------
            val af = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, outRate, outCh
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                // 给足输入缓冲：AAC 编码器单次常要求 >=16KB
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(
                outFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            extractor.selectTrack(audioTrack)

            // ---------- 阶段 2+3 主循环：解码 -> 重采样 -> 编码 ----------
            val feed = PcmFeeder(
                extractor = extractor,
                decoder = decoder,
                srcRate = srcRate,
                srcChannels = srcCh,
                outRate = outRate,
                outChannels = outCh
            )

            val bufInfo = MediaCodec.BufferInfo()
            var muxTrack = -1
            var muxStarted = false
            var inputDone = false
            var outputDone = false
            var lastPts = -1L
            var encodedFrames = 0L

            // 单纯跑到底：没有任何"和视频赛跑"的超时逻辑
            var idleRounds = 0
            while (!outputDone) {
                // --- 送 PCM ---
                if (!inputDone) {
                    val ok = feed.feedAll(encoder)
                    if (ok) {
                        idleRounds = 0
                    } else {
                        // 源真的读完了，补零对齐到整帧后投 EOS
                        if (feed.sourceExhausted) {
                            feed.flushTail(encoder)
                            val eosIdx = encoder.dequeueInputBuffer(100_000)
                            if (eosIdx >= 0) {
                                encoder.queueInputBuffer(
                                    eosIdx, 0, 0, feed.ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                                Log.i(
                                    TAG, "audio input done: ${feed.ptsUs / 1000}ms, " +
                                        "${feed.framesSent} frames"
                                )
                            }
                        } else {
                            idleRounds++
                            if (idleRounds > 2000) {
                                Log.w(TAG, "audio feed stalled, force EOS")
                                inputDone = true
                            }
                            Thread.sleep(1)
                        }
                    }
                }

                // --- 取编码输出 ---
                var progressed = false
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(
                        bufInfo, if (inputDone) 5_000 else 0
                    )
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxStarted) {
                                muxTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxStarted = true
                            }
                        }
                        idx >= 0 -> {
                            progressed = true
                            if (bufInfo.size > 0 &&
                                (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                val ob = encoder.getOutputBuffer(idx)
                                if (ob != null && muxStarted) {
                                    // PTS 必须严格递增
                                    val pts = if (bufInfo.presentationTimeUs > lastPts)
                                        bufInfo.presentationTimeUs else lastPts + 1000L
                                    lastPts = pts
                                    bufInfo.presentationTimeUs = pts
                                    ob.position(bufInfo.offset)
                                    ob.limit(bufInfo.offset + bufInfo.size)
                                    muxer.writeSampleData(muxTrack, ob, bufInfo)
                                    encodedFrames++
                                }
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        else -> break
                    }
                }
                if (!progressed && inputDone && !outputDone) {
                    // 输入已结束但还没等到 EOS 标记，继续排空
                    idleRounds++
                    if (idleRounds > 3000) {
                        Log.w(TAG, "audio output drain timeout")
                        outputDone = true
                    }
                }
                if (maxSeconds > 0 && feed.ptsUs > maxSeconds * 2) {
                    Log.w(TAG, "audio exceeded max duration, stop")
                    outputDone = true
                }
            }

            if (muxStarted) {
                try {
                    muxer.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "mux stop", t)
                }
            }
            val duration = feed.ptsUs
            Log.i(
                TAG, "audio done: ${duration / 1000}ms, ${outFile.length()} bytes, " +
                    "$encodedFrames AAC packets"
            )
            return Output(outFile, outRate, outCh, duration, encodedFrames)
        } catch (t: Throwable) {
            Log.w(TAG, "audio pipeline failed", t)
            outFile.delete()
            return null
        } finally {
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { decoder.stop() } catch (_: Throwable) {}
            try { decoder.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 直通复制：把源 AAC 包原样写入独立 .m4a，不做任何解码/重编码。
     * 时间戳做单调化处理。返回 null 表示失败（调用方降级重编码）。
     */
    private fun passthroughCopy(
        extractor: MediaExtractor,
        track: Int,
        srcFormat: MediaFormat,
        outFile: File
    ): Output? {
        var muxer: MediaMuxer? = null
        try {
            extractor.selectTrack(track)
            muxer = MediaMuxer(
                outFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val outTrack = muxer.addTrack(srcFormat)
            muxer.start()

            val buf = ByteBuffer.allocateDirect(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastPts = -1L
            var firstPts = -1L
            var packets = 0L
            var durationUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION))
                srcFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts < 0) {
                    extractor.advance(); continue
                }
                if (firstPts < 0) firstPts = pts
                // 关键：首包归零 + 单调化。
                // 源录像的音频首包 PTS 经常不是 0（相机有前置偏移），而视频
                // 画面的时间轴是从 0 起的均匀网格。如果不归零，合并后音频整体
                // 后移几百毫秒，播放器做音画同步时会丢帧/等待 -> 成片卡顿。
                val rel = pts - firstPts
                val outPts = if (rel > lastPts) rel else lastPts + 1L
                lastPts = outPts
                info.offset = 0
                info.size = size
                info.presentationTimeUs = outPts
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                buf.position(0)
                muxer.writeSampleData(outTrack, buf, info)
                packets++
                extractor.advance()
            }
            try {
                muxer.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "passthrough mux stop", t)
                return null
            }
            if (durationUs <= 0L) durationUs = lastPts
            if (packets == 0L || outFile.length() == 0L) return null
            return Output(
                file = outFile,
                sampleRate = srcFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 44100),
                channels = srcFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 2),
                durationUs = durationUs,
                encodedFrames = packets
            )
        } catch (t: Throwable) {
            Log.w(TAG, "passthrough copy failed", t)
            return null
        } finally {
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }

    /**
     * PCM 重采样器：把解码出的源 PCM 转成目标采样率/声道数，按整帧喂给编码器。
     *
     * 关键点：
     *  - 源 PCM 缓冲**追加**而非覆盖（解码器一次只吐 1024 帧）
     *  - 输出样本先进持久队列，凑满 1024 帧才交给编码器，余量留到下一轮
     *  - 降采样用箱式平均（自带低通抗混叠）
     */
    private class PcmFeeder(
        private val extractor: MediaExtractor,
        private val decoder: MediaCodec,
        private val srcRate: Int,
        private val srcChannels: Int,
        private val outRate: Int,
        private val outChannels: Int
    ) {
        private var srcBuf: ShortArray = ShortArray(0)
        private var srcPos = 0
        private var decoderInputDone = false
        private var decoderOutputDone = false

        /** 输出样本持久队列（交织） */
        private val outQueue = ArrayList<Short>(AAC_FRAME * 8)

        private var resampleAcc = 0.0
        private var poolL = 0.0
        private var poolR = 0.0
        private var poolN = 0

        var framesSent = 0L
            private set
        private var tailFlushed = false

        val sourceExhausted: Boolean
            get() = decoderOutputDone && srcPos >= srcBuf.size
        val ptsUs: Long get() = framesSent * 1_000_000L / outRate

        private fun appendPcm(ob: ByteBuffer, offset: Int, size: Int) {
            val n = size / 2
            if (n <= 0) return
            val chunk = ShortArray(n)
            ob.position(offset)
            ob.limit(offset + size)
            ob.order(ByteOrder.nativeOrder()).asShortBuffer().get(chunk)
            val remain = srcBuf.size - srcPos
            if (remain > 0) {
                val merged = ShortArray(remain + n)
                System.arraycopy(srcBuf, srcPos, merged, 0, remain)
                System.arraycopy(chunk, 0, merged, remain, n)
                srcBuf = merged
            } else {
                srcBuf = chunk
            }
            srcPos = 0
        }

        /** 推进解码器，把新 PCM 追加进源缓冲 */
        private fun pump(): Boolean {
            if (decoderOutputDone) return false
            val info = MediaCodec.BufferInfo()

            if (!decoderInputDone) {
                val inIdx = decoder.dequeueInputBuffer(0)
                if (inIdx >= 0) {
                    val ib = decoder.getInputBuffer(inIdx)
                    if (ib == null) {
                        decoderInputDone = true
                    } else {
                        ib.clear()
                        val size = extractor.readSampleData(ib, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            var got = false
            var rounds = 0
            while (rounds++ < 64) {
                val oi = decoder.dequeueOutputBuffer(info, 0)
                when {
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> return got
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    oi >= 0 -> {
                        if (info.size > 0) {
                            decoder.getOutputBuffer(oi)?.let {
                                appendPcm(it, info.offset, info.size)
                                got = true
                            }
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(oi, false)
                        if (eos) {
                            decoderOutputDone = true
                            return got
                        }
                    }
                    else -> return got
                }
            }
            return got
        }

        /** 把源缓冲里的样本全部转成输出队列（不限量） */
        private fun convertAll() {
            while (true) {
                if (srcPos >= srcBuf.size) {
                    if (decoderOutputDone) return
                    if (!pump()) return
                    if (srcPos >= srcBuf.size) return
                    continue
                }
                val l: Float
                val r: Float
                if (srcChannels >= 2 && srcPos + 1 < srcBuf.size) {
                    l = srcBuf[srcPos].toFloat()
                    r = srcBuf[srcPos + 1].toFloat()
                } else {
                    l = srcBuf[srcPos].toFloat()
                    r = l
                }
                srcPos += srcChannels

                if (outRate < srcRate) {
                    poolL += l
                    poolR += r
                    poolN++
                    resampleAcc += outRate.toDouble()
                    if (resampleAcc < srcRate) continue
                    resampleAcc -= srcRate
                    val k = poolN.coerceAtLeast(1)
                    push((poolL / k).toFloat(), (poolR / k).toFloat())
                    poolL = 0.0
                    poolR = 0.0
                    poolN = 0
                } else {
                    push(l, r)
                }
            }
        }

        private fun push(l: Float, r: Float) {
            if (outChannels == 1) {
                outQueue.add(((l + r) * 0.5f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                outQueue.add(l.toInt().coerceIn(-32768, 32767).toShort())
                outQueue.add(r.toInt().coerceIn(-32768, 32767).toShort())
            }
        }

        /** 把队列里能凑成整帧的样本送进编码器；返回是否送出了数据 */
        fun feedAll(codec: MediaCodec): Boolean {
            convertAll()
            val frameSamples = outChannels
            var sent = false
            while (outQueue.size / frameSamples >= AAC_FRAME) {
                val inIdx = codec.dequeueInputBuffer(50_000)
                if (inIdx < 0) break
                val ib = codec.getInputBuffer(inIdx) ?: break
                ib.clear()
                val capFrames = ib.capacity() / (2 * frameSamples)
                val n = minOf(capFrames, outQueue.size / frameSamples)
                if (n <= 0) break

                val bb = ib.order(ByteOrder.nativeOrder())
                for (i in 0 until n * frameSamples) bb.putShort(outQueue[i])
                outQueue.subList(0, n * frameSamples).clear()

                codec.queueInputBuffer(inIdx, 0, n * 2 * frameSamples, ptsUs, 0)
                framesSent += n
                sent = true
            }
            return sent
        }

        /** 把队列余量补齐到 1024 帧整数倍后送出（仅在源彻底读完后调用一次） */
        fun flushTail(codec: MediaCodec) {
            if (tailFlushed) return
            tailFlushed = true
            convertAll()
            val frameSamples = outChannels
            val have = outQueue.size / frameSamples
            if (have == 0) return

            // 尾部 64 帧淡出，避免硬切爆音
            val fade = minOf(64, have)
            for (k in 0 until fade) {
                val idx = (have - 1 - k) * frameSamples
                val g = k.toFloat() / fade
                for (ch in 0 until frameSamples) {
                    outQueue[idx + ch] = (outQueue[idx + ch] * g).toInt().toShort()
                }
            }
            // 补零到整帧
            val rem = ((framesSent + have) % AAC_FRAME).toInt()
            val pad = if (rem == 0) 0 else AAC_FRAME - rem
            repeat(pad * frameSamples) { outQueue.add(0) }
            Log.i(TAG, "tail: $have frames + $pad padded")

            // 分批全部送出
            while (outQueue.isNotEmpty()) {
                val inIdx = codec.dequeueInputBuffer(200_000)
                if (inIdx < 0) break
                val ib = codec.getInputBuffer(inIdx) ?: break
                ib.clear()
                val capFrames = ib.capacity() / (2 * frameSamples)
                val n = minOf(capFrames, outQueue.size / frameSamples)
                if (n <= 0) break
                val bb = ib.order(ByteOrder.nativeOrder())
                for (i in 0 until n * frameSamples) bb.putShort(outQueue[i])
                outQueue.subList(0, n * frameSamples).clear()
                codec.queueInputBuffer(inIdx, 0, n * 2 * frameSamples, ptsUs, 0)
                framesSent += n
            }
        }
    }
}
