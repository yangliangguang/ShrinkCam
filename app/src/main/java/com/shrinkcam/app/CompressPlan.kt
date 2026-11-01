package com.shrinkcam.app

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

enum class Quality(val label: String, val desc: String) {
    /** 极限：体积最小，画面降到 720p 级、低码率，适合微信发送/记录 */
    EXTREME("极限压缩", "720p · 体积最小 · 发微信首选"),

    /** 均衡：1080p 中等码率，肉眼可接受 */
    BALANCED("均衡", "1080p · 体积与画质折中"),

    /** 清晰：1080p 较高码率，接近原片 */
    CLEAR("清晰", "1080p · 画质优先");
}

/** 分辨率处理策略 */
enum class SizeMode(val label: String, val desc: String) {
    /** 按档位等比缩小（省体积优先） */
    SCALE("等比缩小", "按档位缩小短边，体积更小"),

    /** 保持源分辨率，只压码率 */
    ORIGINAL("保持原尺寸", "分辨率不变，只降码率")
}

/**
 * 压缩计划：所有参数在开始编码前算好，保证输出体积可控。
 *
 * 方向策略为「原封不动」：
 *  - 像素**不做任何旋转**，保持源视频编码坐标系
 *  - 输出宽高比 = 源编码宽高比
 *  - 容器写入源的旋转元数据（setOrientationHint）
 * 这样成片朝向与源完全一致，不存在"转正后又转一次"的风险。
 */
data class CompressPlan(
    val outWidth: Int,
    val outHeight: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val outFps: Int,
    val useHevc: Boolean,
    /** 要写入 MP4 容器的旋转角（0/90/180/270），与源一致 */
    val rotation: Int,
    val estimateBytes: Long
) {
    val codecName: String get() = if (useHevc) "H.265 / HEVC" else "H.264 / AVC"

    /** 显示方向上的宽高（供 UI 展示） */
    val displayW: Int get() = if (rotation == 90 || rotation == 270) outHeight else outWidth
    val displayH: Int get() = if (rotation == 90 || rotation == 270) outWidth else outHeight

    companion object {
        /** 各档位的分辨率上限（短边）与像素/帧 预算 */
        private fun caps(q: Quality): Triple<Int, Float, Int> = when (q) {
            // 短边上限, 每帧像素位数预算(bit per pixel), 最低码率(kbps)
            Quality.EXTREME -> Triple(720, 0.055f, 180)
            Quality.BALANCED -> Triple(1080, 0.10f, 700)
            Quality.CLEAR -> Triple(1080, 0.17f, 1600)
        }

        /**
         * 计算压缩计划。
         * @param info 源视频信息
         * @param quality 档位
         * @param targetBytes 目标体积（<=0 表示不限）
         * @param factor 码率系数，用于自适应校准（首轮 1.0）
         * @param sizeMode 分辨率策略：等比缩小 / 保持原尺寸
         * @param keepAudio 是否保留音频；false 时输出为无声视频（audioBitrate = 0）
         */
        fun make(
            info: VideoInfo,
            quality: Quality,
            targetBytes: Long,
            factor: Float = 1f,
            sizeMode: SizeMode = SizeMode.SCALE,
            keepAudio: Boolean = true
        ): CompressPlan {
            val (shortCap, bpp, minKbps) = caps(quality)

            // 源编码坐标系的宽高：像素不旋转，所以直接用 info.width/height
            val srcEncW = info.width.coerceAtLeast(2)
            val srcEncH = info.height.coerceAtLeast(2)
            val srcShort = minOf(srcEncW, srcEncH)
            val srcLong = maxOf(srcEncW, srcEncH)

            var wEven: Int
            var hEven: Int
            if (sizeMode == SizeMode.ORIGINAL) {
                // 保持原尺寸：宽高与源编码坐标系完全一致
                wEven = MediaProbe.even(srcEncW)
                hEven = MediaProbe.even(srcEncH)
            } else {
                val short = minOf(srcShort, shortCap)
                val ratio = short.toFloat() / srcShort
                val longOut = MediaProbe.even((srcLong * ratio).toInt()).coerceAtLeast(2)
                val s = MediaProbe.even(short)
                // 轴序跟随源编码坐标系，横拍出横、竖拍出竖
                val sourceLandscape = srcEncW >= srcEncH
                wEven = if (sourceLandscape) longOut else s
                hEven = if (sourceLandscape) s else longOut
            }

            val fps = when {
                info.frameRate <= 0f -> 30
                quality == Quality.EXTREME -> 24
                info.frameRate > 40f -> 30
                else -> info.frameRate.toInt().coerceIn(15, 30)
            }

            val pixelsPerSec = wEven.toLong() * hEven.toLong() * fps
            var vKbps = (pixelsPerSec * bpp / 1000f * factor).toInt()
            vKbps = vKbps.coerceAtLeast(minKbps)

            // 目标体积约束：留出音频与容器开销
            val durationSec = (info.durationUs / 1_000_000.0).coerceAtLeast(0.1)
            // 音频直通：保留声音时音频原样复制不压缩。
            // 源音频码率未单独探测，按常见录像 128kbps 估算，误差只影响
            // 目标体积模式的视频码率分配，可由校准轮纠正。
            val audioKbps = if (keepAudio && info.hasAudio) 128 else 0
            if (targetBytes > 0) {
                val totalKbps = (targetBytes * 8.0 / durationSec / 1000.0) * 0.94
                val v = totalKbps.toInt() - audioKbps
                vKbps = minOf(vKbps, v.coerceAtLeast(30))
            }
            // 不允许超过源码率的 85%，避免"越压越大"
            if (info.bitrate > 0) {
                vKbps = minOf(vKbps, (info.bitrate * 0.85f / 1000f).toInt().coerceAtLeast(100))
            }
            vKbps = vKbps.coerceIn(60, 40_000)
            val aKbps = audioKbps

            val hevc = Support.hevcEncoderAvailable()
            val estimate = ((vKbps + aKbps).toLong() * 1000L / 8L * durationSec.toLong()).coerceAtLeast(1L)

            // 方向原封不动：像素不旋转，容器沿用源的旋转元数据
            val keepRotation = info.rotation

            return CompressPlan(
                outWidth = wEven, outHeight = hEven,
                videoBitrate = vKbps * 1000,
                audioBitrate = aKbps * 1000,
                outFps = fps,
                useHevc = hevc,
                rotation = keepRotation,
                estimateBytes = estimate
            )
        }
    }
}

object Support {
    private var hevc: Boolean? = null

    fun hevcEncoderAvailable(): Boolean = hevc ?: synchronized(this) {
        hevc ?: run {
            val ok = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
                    c.isEncoder && c.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
                }
            } catch (_: Throwable) {
                false
            }
            hevc = ok
            ok
        }
    }

    fun hevcPlaybackSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
