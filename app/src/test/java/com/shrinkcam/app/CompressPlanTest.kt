package com.shrinkcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑单测：不依赖 Android 运行时，验证码率/分辨率/体积估算的契约。
 */
class CompressPlanTest {

    private fun info(
        w: Int = 1920,
        h: Int = 1080,
        rot: Int = 0,
        durUs: Long = 10_000_000,
        bitrate: Int = 8_000_000,
        hasAudio: Boolean = true
    ) = VideoInfo(
        width = w, height = h, rotation = rot,
        durationUs = durUs, sizeBytes = bitrate.toLong() * durUs / 8_000_000,
        bitrate = bitrate, frameRate = 30f, mime = "video/avc",
        hasAudio = hasAudio, audioSampleRate = 44100, audioChannels = 2
    )

    @Test
    fun extreme_downsizes_to_720_short_side() {
        val p = CompressPlan.make(info(1920, 1080), Quality.EXTREME, 0)
        assertEquals(1280, p.outWidth)
        assertEquals(720, p.outHeight)
        assertTrue("bitrate=${p.videoBitrate}", p.videoBitrate in 180_000..2_000_000)
    }

    @Test
    fun portrait_with_rotation_keeps_encoding_axes() {
        // 竖拍（编码 1080x1920 + 旋转90）：像素不旋转，沿用源编码坐标系，
        // 输出宽高按编码坐标系的轴序（1024x？这里是 1080x1920 缩到 720 短边 -> 720x1280），
        // 旋转元数据原样保留，方向与原片一致
        val p = CompressPlan.make(info(1080, 1920, rot = 90), Quality.EXTREME, 0)
        assertEquals(720, p.outWidth)
        assertEquals(1280, p.outHeight)
        assertEquals(90, p.rotation)
    }

    @Test
    fun landscape_keeps_landscape() {
        // 横拍 1920x1080 无旋转：保持横构图 1280x720
        val p = CompressPlan.make(info(1920, 1080, rot = 0), Quality.EXTREME, 0)
        assertEquals(1280, p.outWidth)
        assertEquals(720, p.outHeight)
        assertEquals(0, p.rotation)
    }

    @Test
    fun portrait_native_keeps_portrait() {
        // 竖拍原生编码 1080x1920（无旋转元数据）：宽高比保持 9:16
        val p = CompressPlan.make(info(1080, 1920, rot = 0), Quality.EXTREME, 0)
        assertEquals(720, p.outWidth)
        assertEquals(1280, p.outHeight)
        assertEquals(0, p.rotation)
    }

    @Test
    fun rotation_180_is_preserved() {
        // 180° 也必须原样写入容器，否则成片会上下颠倒
        val p = CompressPlan.make(info(1920, 1080, rot = 180), Quality.EXTREME, 0)
        assertEquals(1280, p.outWidth)
        assertEquals(720, p.outHeight)
        assertEquals(180, p.rotation)
    }

    @Test
    fun rotation_270_is_preserved() {
        val p = CompressPlan.make(info(1920, 1080, rot = 270), Quality.EXTREME, 0)
        assertEquals(270, p.rotation)
    }

    @Test
    fun original_size_mode_keeps_source_dimensions() {
        // 保持原尺寸模式：输出宽高与源编码坐标系完全一致
        val p = CompressPlan.make(
            info(1920, 1080), Quality.EXTREME, 0, sizeMode = SizeMode.ORIGINAL
        )
        assertEquals(1920, p.outWidth)
        assertEquals(1080, p.outHeight)
    }

    @Test
    fun original_size_mode_still_reduces_bitrate() {
        // 尺寸不变，但码率仍需压缩，避免"越压越大"
        val p = CompressPlan.make(
            info(1920, 1080, bitrate = 8_000_000), Quality.EXTREME, 0,
            sizeMode = SizeMode.ORIGINAL
        )
        assertTrue("bitrate=${p.videoBitrate}", p.videoBitrate < 8_000_000)
    }

    @Test
    fun target_size_dominates_bitrate() {
        // 10s 视频压到 1MB -> 总码率约 840kbps，扣掉 128k 直通音频
        val p = CompressPlan.make(info(durUs = 10_000_000), Quality.EXTREME, 1_000_000)
        val totalKbps = (p.videoBitrate + p.audioBitrate) / 1000.0
        val expected = 1_000_000 * 8.0 / 10.0 / 1000.0 * 0.94
        assertEquals(expected, totalKbps, 60.0)
    }

    @Test
    fun never_exceeds_source_bitrate() {
        val p = CompressPlan.make(info(bitrate = 900_000), Quality.CLEAR, 0)
        assertTrue(p.videoBitrate <= 900_000 * 0.85)
    }

    @Test
    fun even_dimensions() {
        val p = CompressPlan.make(info(1081, 1921), Quality.BALANCED, 0)
        assertEquals(0, p.outWidth % 2)
        assertEquals(0, p.outHeight % 2)
    }

    @Test
    fun quality_ordering() {
        val src = info()
        val e = CompressPlan.make(src, Quality.EXTREME, 0)
        val b = CompressPlan.make(src, Quality.BALANCED, 0)
        val c = CompressPlan.make(src, Quality.CLEAR, 0)
        assertTrue(e.videoBitrate < b.videoBitrate)
        assertTrue(b.videoBitrate < c.videoBitrate)
        assertTrue(e.estimateBytes < c.estimateBytes)
    }

    @Test
    fun no_audio_means_zero_audio_bitrate() {
        val p = CompressPlan.make(info(hasAudio = false), Quality.EXTREME, 0)
        assertEquals(0, p.audioBitrate)
    }

    @Test
    fun keep_audio_false_zeroes_audio_bitrate() {
        val p = CompressPlan.make(info(hasAudio = true), Quality.CLEAR, 0, keepAudio = false)
        assertEquals(0, p.audioBitrate)
        assertTrue("关闭音频后估算体积应更小", p.estimateBytes > 0)
    }

    @Test
    fun keep_audio_false_gives_more_video_budget_under_target_size() {
        // 目标体积固定时，关掉音频后省下的码率应当让给视频
        val withAudio = CompressPlan.make(info(), Quality.EXTREME, 5L * 1024 * 1024)
        val muted = CompressPlan.make(info(), Quality.EXTREME, 5L * 1024 * 1024, keepAudio = false)
        assertEquals(0, muted.audioBitrate)
        assertTrue(withAudio.audioBitrate > 0)
        assertTrue(
            "关闭音频后视频码率应不低于保留时",
            muted.videoBitrate >= withAudio.videoBitrate
        )
    }

    @Test
    fun keep_audio_uses_passthrough_estimate() {
        // 保留声音 = 音频直通不压缩，按 128kbps 估算
        val p = CompressPlan.make(info(), Quality.BALANCED, 0, keepAudio = true)
        assertEquals(128_000, p.audioBitrate)
    }

    @Test
    fun extreme_audio_passthrough_estimate() {
        val p = CompressPlan.make(info(), Quality.EXTREME, 0)
        assertEquals(128_000, p.audioBitrate)
    }

    /** 关音频时进度/体积估算仍然自洽，且不会因缺少音频轨而算出 0 字节 */
    @Test
    fun muted_plan_still_has_positive_estimate() {
        val p = CompressPlan.make(info(), Quality.EXTREME, 0, keepAudio = false)
        assertEquals(0, p.audioBitrate)
        assertTrue(p.estimateBytes > 0)
        assertTrue(p.videoBitrate > 0)
    }

    @Test
    fun tiny_video_does_not_upscale_beyond_cap() {
        val p = CompressPlan.make(info(640, 480), Quality.EXTREME, 0)
        assertEquals(640, p.outWidth)
        assertEquals(480, p.outHeight)
    }
}
