package com.shrinkcam.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 验证 MP4 tkhd 矩阵 -> 旋转角度的解析。
 *
 * 矩阵布局（3x3，列主序）：
 *   | a  b  u |
 *   | c  d  v |
 *   旋转部分 [a,b; c,d] 对应 | cosθ  sinθ |
 *                            | -sinθ cosθ |
 * 因此 a = cosθ，c = -sinθ。
 */
class RotationParseTest {

    private fun int32(v: Float): Int = (v * 65536f).toInt()

    /** 构造一个只含 tkhd 的最小 MP4 结构，便于验证解析逻辑 */
    private fun buildMp4WithTkhd(rotation: Int): ByteArray {
        val cos = when (rotation) {
            0 -> 1f
            90 -> 0f
            180 -> -1f
            270 -> 0f
            else -> 1f
        }
        val sin = when (rotation) {
            0 -> 0f
            90 -> 1f
            180 -> 0f
            270 -> -1f
            else -> 0f
        }
        // tkhd 内容：version(1)+flags(3) + v0 的 20 字节 + 36 字节矩阵
        val tkhdBody = ByteBuffer.allocate(4 + 20 + 36).order(ByteOrder.BIG_ENDIAN)
        tkhdBody.put(0)                       // version 0
        tkhdBody.put(byteArrayOf(0, 0, 0))    // flags
        tkhdBody.put(ByteArray(20))           // creation/modification/trackId/... 占位
        // 矩阵：a, b, u, c, d, v, x, y, w（16.16 定点）
        tkhdBody.putInt(int32(cos))           // a
        tkhdBody.putInt(int32(sin))           // b
        tkhdBody.putInt(0)                    // u
        tkhdBody.putInt(int32(-sin))          // c = -sinθ
        tkhdBody.putInt(int32(cos))           // d
        tkhdBody.putInt(0)                    // v
        tkhdBody.putInt(0)                    // x
        tkhdBody.putInt(0)                    // y
        tkhdBody.putInt(0x40000000)           // w = 1.0

        val tkhdContent = tkhdBody.array()
        val tkhdSize = 8 + tkhdContent.size

        // 组装：moov > trak > tkhd
        val trakHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + tkhdSize).put("trak".toByteArray()).array()
        val tkhdHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(tkhdSize).put("tkhd".toByteArray()).array()
        val moovHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + trakHeader.size + tkhdHeader.size + tkhdContent.size)
            .put("moov".toByteArray()).array()

        return moovHeader + trakHeader + tkhdHeader + tkhdContent
    }

    private fun parseRotation(bytes: ByteArray): Int {
        val f = java.io.File.createTempFile("rot", ".mp4")
        try {
            f.writeBytes(bytes)
            return MediaProbe.readRotationFromFile(f)
        } finally {
            f.delete()
        }
    }

    @Test
    fun identity_matrix_is_zero() {
        assertEquals(0, parseRotation(buildMp4WithTkhd(0)))
    }

    @Test
    fun rotate_90() {
        assertEquals(90, parseRotation(buildMp4WithTkhd(90)))
    }

    @Test
    fun rotate_180() {
        assertEquals(180, parseRotation(buildMp4WithTkhd(180)))
    }

    @Test
    fun rotate_270() {
        assertEquals(270, parseRotation(buildMp4WithTkhd(270)))
    }

    @Test
    fun malformed_data_returns_zero() {
        assertEquals(0, parseRotation(ByteArray(12)))
    }
}
