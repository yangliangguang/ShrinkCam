package com.shrinkcam.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 音视频合并器：把独立的视频文件 + 独立音频文件合成一个 MP4。
 *
 * 这一步只做**转封装（remux）**，不解码不重编码，所以非常快
 * （只受存储 IO 限制）。两条轨各自的样本按原时间戳依次写入新容器。
 *
 * 合并时做两件事：
 *  1. 视频轨沿用源文件的旋转元数据，保证成片方向和原片一致
 *  2. 音频若比视频长，截断到视频长度；若短，直接结束（尾部自然静音），
 *     不会出现"音轨超出画面"的怪异感
 */
object Mp4Merger {

    private const val TAG = "Mp4Merger"

    /**
     * @param videoFile 已编码好的无声视频
     * @param audioFile 已编码好的独立音频（可为 null，表示输出无声）
     * @param outFile 合并结果
     * @param rotation 写入容器的旋转角
     * @return 是否成功；失败时 outFile 会被清理
     */
    fun merge(
        videoFile: File,
        audioFile: File?,
        outFile: File,
        rotation: Int = 0
    ): Boolean {
        if (outFile.exists()) outFile.delete()

        val vEx = MediaExtractor()
        var aEx: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            vEx.setDataSource(videoFile.absolutePath)
            var vTrack = -1
            for (i in 0 until vEx.trackCount) {
                val m = vEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    vTrack = i; break
                }
            }
            if (vTrack < 0) {
                Log.w(TAG, "no video track in ${videoFile.name}")
                return false
            }
            vEx.selectTrack(vTrack)
            val vFormat = vEx.getTrackFormat(vTrack)

            var aTrack = -1
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                aEx = MediaExtractor()
                aEx.setDataSource(audioFile.absolutePath)
                for (i in 0 until aEx.trackCount) {
                    val m = aEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("audio/")) {
                        aTrack = i; break
                    }
                }
                if (aTrack < 0) {
                    Log.w(TAG, "audio file has no audio track, ignore")
                    aEx.release(); aEx = null
                } else {
                    aEx.selectTrack(aTrack)
                }
            }

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)

            val outVideoTrack = muxer.addTrack(vFormat)
            var outAudioTrack = -1
            if (aEx != null && aTrack >= 0) {
                outAudioTrack = muxer.addTrack(aEx.getTrackFormat(aTrack))
            }
            muxer.start()

            // ---- 先写音频轨，拿到音频精确结束时间 ----
            // 音频完整复制，绝不截断（之前按视频容器时长裁音频尾部，
            // 真机上视频时长普遍略短，导致尾部声音被裁）。
            var audioEndUs = -1L
            if (aEx != null && aTrack >= 0 && outAudioTrack >= 0) {
                audioEndUs = copyTrack(aEx, outAudioTrack, muxer, limitUs = Long.MAX_VALUE).lastPts
            }

            // ---- 再写视频轨，并把最后一帧延长到音频结束 ----
            // 视频编码输出的时长普遍比音频短零点几秒（帧网格取整、编码器
            // 尾部丢帧）。若直接收尾，画面提前结束、声音还在播，
            // 用户看到"声音正常但画面停了"。
            // 处理：把视频最后一帧再写一次，pts 对齐音频结束点 ——
            // 画面定格在最后一帧直到声音播完，观感自然。
            val vRes = copyTrack(vEx, outVideoTrack, muxer, limitUs = Long.MAX_VALUE)
            val extendThresholdUs = 250_000L   // 差值太小就不折腾，播放器无感
            if (audioEndUs > vRes.lastPts + extendThresholdUs && vRes.lastFrame != null) {
                val buf = ByteBuffer.allocateDirect(vRes.lastFrame.size)
                buf.put(vRes.lastFrame)
                buf.position(0)
                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = vRes.lastFrame.size
                    presentationTimeUs = audioEndUs - 1L
                    flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                try {
                    muxer.writeSampleData(outVideoTrack, buf, info)
                    Log.i(
                        TAG, "video tail extended: last frame at ${vRes.lastPts / 1000}ms" +
                            " -> held until ${audioEndUs / 1000}ms"
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "extend last frame failed", t)
                }
            }

            try {
                muxer.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "mux stop", t)
                return false
            }
            Log.i(TAG, "merged: ${outFile.length()} bytes, audio=${aEx != null}")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "merge failed", t)
            outFile.delete()
            return false
        } finally {
            try { muxer?.release() } catch (_: Throwable) {}
            try { vEx.release() } catch (_: Throwable) {}
            try { aEx?.release() } catch (_: Throwable) {}
        }
    }

    /** copyTrack 的结果：轨的最后时间戳 + 最后一帧的完整数据（用于延长画面） */
    private class CopyResult(
        val lastPts: Long,
        val lastFrame: ByteArray?
    )

    /** 逐样本把一个轨从 extractor 搬到 muxer，并记录最后一帧 */
    private fun copyTrack(
        ex: MediaExtractor,
        outTrack: Int,
        muxer: MediaMuxer,
        limitUs: Long
    ): CopyResult {
        val buf = ByteBuffer.allocateDirect(512 * 1024)
        val info = MediaCodec.BufferInfo()
        var lastPts = -1L
        var lastFrame: ByteArray? = null
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val pts = ex.sampleTime
            if (pts < 0) {
                ex.advance(); continue
            }
            if (limitUs != Long.MAX_VALUE && pts > limitUs) break

            info.offset = 0
            info.size = size
            // 时间戳必须严格递增，否则 MediaMuxer 抛异常。
            // 补偿量用最小步长 1µs，最大限度保留原始帧间隔。
            val outPts = if (pts > lastPts) pts else lastPts + 1L
            lastPts = outPts
            info.presentationTimeUs = outPts
            val isSync = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            info.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            // 保存最后一帧数据，供"画面定格到音频结束"使用
            lastFrame = ByteArray(size).also { bf ->
                buf.position(0)
                buf.limit(size)
                buf.get(bf)
                buf.clear()
            }
            buf.position(0)
            buf.limit(buf.capacity())
            muxer.writeSampleData(outTrack, buf, info)
            ex.advance()
        }
        return CopyResult(lastPts, lastFrame)
    }
}
