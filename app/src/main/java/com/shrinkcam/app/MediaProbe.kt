package com.shrinkcam.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 视频元信息 */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val sizeBytes: Long,
    val bitrate: Int,
    val frameRate: Float,
    val mime: String,
    val hasAudio: Boolean,
    val audioSampleRate: Int,
    val audioChannels: Int
) {
    /** 显示方向上的宽高（已考虑旋转） */
    val displayW: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayH: Int get() = if (rotation == 90 || rotation == 270) width else height
}

object MediaProbe {

    /** 读取视频轨道与 EXIF 旋转信息 */
    fun probe(ctx: Context, uri: Uri): VideoInfo {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(ctx, uri)
        fun meta(key: Int): String? = retriever.extractMetadata(key)

        var w = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        var rotation = 0   // 只信 MP4 容器矩阵，下面统一计算
        val durationMs = meta(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val bitrate = meta(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        val fpsRaw = meta(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        val hasAudio = meta(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        var frameworkRotation = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0

        // 宽高与旋转：以 MediaExtractor 的编码坐标系 + MP4 tkhd 矩阵为准
        var audioSampleRate = 0
        var audioChannels = 0
        var mime = "video/avc"
        try {
            val ex = android.media.MediaExtractor()
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> ex.setDataSource(pfd.fileDescriptor) }
            var sawVideo = false
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val m = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    if (!sawVideo) {
                        sawVideo = true
                        w = fmt.getInteger(android.media.MediaFormat.KEY_WIDTH)
                        h = fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                        mime = m

                    }
                } else if (m.startsWith("audio/")) {
                    if (fmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                        audioSampleRate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (fmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                        audioChannels = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            ex.release()
        } catch (t: Throwable) {
            Log.w("MediaProbe", "extractor probe failed", t)
        }
        try {
            retriever.release()
        } catch (_: Throwable) {
        }

        // 旋转角度来源优先级：
        //   1. 系统框架 MetadataRetriever（厂商最了解自家相机的写入习惯，鸿蒙/华为系以此为准）
        //   2. 框架给不出（返回 0 或缺失）时，再解析 MP4 tkhd 矩阵兜底
        // 之所以把框架放第一位：矩阵的符号约定在不同设备/编码器间并不统一，
        // 用错约定就会出现"倒置 180°"这类问题。
        val matrixRot = readRotationFromFile(copyToTemp(ctx, uri))
        rotation = when {
            frameworkRotation != 0 -> frameworkRotation
            matrixRot != 0 -> matrixRot
            else -> 0
        }
        Log.i(
            "MediaProbe",
            "rotation: framework=$frameworkRotation matrix=$matrixRot => $rotation (${w}x$h)"
        )

        val durationUs = durationMs * 1000
        val size = querySize(ctx, uri)
        val computed = if (durationMs > 0 && size > 0) ((size * 8_000L) / durationMs).toInt() else bitrate
        return VideoInfo(
            width = w, height = h, rotation = rotation,
            durationUs = durationUs, sizeBytes = size,
            bitrate = if (bitrate > 0) bitrate else computed,
            frameRate = fpsRaw ?: 30f,
            mime = mime, hasAudio = hasAudio,
            audioSampleRate = if (audioSampleRate > 0) audioSampleRate else 44100,
            audioChannels = if (audioChannels > 0) audioChannels else 2
        )
    }

    fun querySize(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { return it.statSize }
        } catch (_: Throwable) {
        }
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (_: Throwable) {
        }
        return 0L
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) return c.getString(i)
                }
            }
        } catch (_: Throwable) {
        }
        return "video_${System.currentTimeMillis()}.mp4"
    }

    /** 从 MP4 盒子中读取真正的旋转（tkhd matrix），部分机型 MetadataRetriever 会给 0 */
    fun readRotationFromFile(file: File): Int {
        return try {
            val data = file.readBytes()
            var offset = 0
            while (offset + 8 <= data.size) {
                val size = readInt(data, offset).toLong() and 0xFFFFFFFFL
                val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
                if (size < 8) break
                if (type == "moov" || type == "trak") {
                    val res = findRotation(data, offset + 8, offset + size.toInt())
                    if (res != 0) return res
                }
                offset += size.toInt()
            }
            0
        } catch (_: Throwable) {
            0
        }
    }

    private fun findRotation(d: ByteArray, from: Int, to: Int): Int {
        var off = from
        val end = min(to, d.size)
        while (off + 8 <= end) {
            val size = readInt(d, off).toLong() and 0xFFFFFFFFL
            if (size < 8L) break
            val type = String(d, off + 4, 4, Charsets.ISO_8859_1)
            val body = off + 8
            if (type == "tkhd") {
                // tkhd: version(1)+flags(3) -> v0: 20 bytes, v1: 32 bytes 之后是 16.16 定点矩阵
                val version = d[body].toInt()
                val matOff = if (version == 1) body + 4 + 32 else body + 4 + 20
                if (matOff + 36 <= end) {
                    val a = readInt(d, matOff)
                    val c = readInt(d, matOff + 12)
                    val aF = a / 65536f
                    val cF = c / 65536f
                    return matrixToDegrees(aF, cF)
                }
            }
            if (type == "moov" || type == "trak" || type == "mdia") {
                val r = findRotation(d, body, off + size.toInt())
                if (r != 0) return r
            }
            off += size.toInt()
        }
        return 0
    }

    /**
     * tkhd 矩阵 -> 旋转角度（0/90/180/270，顺时针为正）。
     *
     * MP4 的变换矩阵是 3x3，旋转部分按列主序布局为：
     *   | a  b  u |
     *   | c  d  v |
     * 其中 [a, b; c, d] 对应二维旋转矩阵：
     *   | cosθ  sinθ |
     *   | -sinθ cosθ |
     * 因此 a = cosθ，c = -sinθ。
     *
     * 逐一推导：
     *   θ=0   -> a=1,  c=0
     *   θ=90  -> a=0,  c=-1
     *   θ=180 -> a=-1, c=0
     *   θ=270 -> a=0,  c=1
     */
    private fun matrixToDegrees(a: Float, c: Float): Int {
        val eps = 0.01f
        if (abs(a - 1f) < eps && abs(c) < eps) return 0
        if (abs(a) < eps && abs(c + 1f) < eps) return 90
        if (abs(a + 1f) < eps && abs(c) < eps) return 180
        if (abs(a) < eps && abs(c - 1f) < eps) return 270
        // 兜底：按 atan2(-c, a) 求角度，结果归一到 [0,360)
        val deg = Math.toDegrees(kotlin.math.atan2((-c).toDouble(), a.toDouble())).roundToInt()
        return ((deg % 360) + 360) % 360
    }

    private fun readInt(d: ByteArray, off: Int): Int {
        if (off + 4 > d.size) return 0
        return ByteBuffer.wrap(d, off, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    fun even(v: Int): Int = max(2, v - (v % 2))

    /** 把 Uri 复制到临时文件，用于解析 MP4 盒子（tkhd 矩阵） */
    private fun copyToTemp(ctx: Context, uri: Uri): File {
        if (uri.scheme == "file") {
            uri.path?.let { p -> File(p).takeIf { it.exists() }?.let { return it } }
        }
        val f = File(ctx.cacheDir, "probe_${System.currentTimeMillis()}.mp4")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            }
        } catch (_: Throwable) {
            return File("/nonexistent")
        }
        return f
    }
}
