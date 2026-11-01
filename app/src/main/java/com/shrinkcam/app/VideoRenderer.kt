package com.shrinkcam.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * 视频重采样：解码器输出 -> GL 纹理 -> 缩放到编码器 Surface。
 *
 * 方向策略（唯一一套，不做任何"双重旋转"）：
 *  1. 解码器拿到的是**未旋转的编码帧**（解码格式里抹掉了旋转字段）
 *  2. 旋转在 GL 里**烘焙进像素**：输出像素就是"人眼看到的方向"
 *  3. 容器**不写** orientationHint（plan.rotation 恒为 0）
 *
 * 这样无论播放器读不读 tkhd 矩阵，成片方向都与原片一致。
 * 关键细节：OSD/相机竖拍视频常常是 1080×1920 编码 + 90° 旋转，
 * 若按 1920×1080 做宽高比适配，画面会被横向压扁；所以宽高比适配
 * 必须用**旋转后的显示尺寸**，缩放矩阵放在旋转之后叠加。
 */
class VideoRenderer(
    private val encoder: MediaCodec,
    private val encoderSurface: Surface,
    private val srcFormat: MediaFormat,
    private val extractor: android.media.MediaExtractor,
    private val srcW: Int,
    private val srcH: Int,
    private val outW: Int,
    private val outH: Int,
    private val outFps: Int,
    private val durationUs: Long,
    /** 源的显示旋转（0/90/180/270），渲染时把内容转正后写进像素 */
    private val rotation: Int
) : Runnable {

    @Volatile
    var presentationUs: Long = 0
        private set

    @Volatile
    private var decoder: MediaCodec? = null

    /** 渲染线程内的致命异常，主循环检测到线程退出后读取并上报 */
    @Volatile
    var fatal: Throwable? = null
        private set

    /** 已成功渲染的帧数（用于诊断） */
    @Volatile
    var renderedFrames: Long = 0L
        private set

    override fun run() {
        var dec: MediaCodec? = null
        var fb: FrameBuffer? = null
        try {
            val mime = srcFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            dec = MediaCodec.createDecoderByType(mime)
            decoder = dec

            fb = FrameBuffer(encoderSurface).apply { setup() }
            // 防止大码率 I 帧超过解码器输入缓冲导致 readSampleData 抛
            // IllegalArgumentException（"buffer is too small"），把输入上限提到 4MB。
            // srcFormat 是 getTrackFormat 返回的独立实例，直接修改无副作用。
            runCatching {
                val cur = if (srcFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    srcFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                if (cur < 4 * 1024 * 1024) {
                    srcFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                }
            }
            // 直接使用源轨道格式配置解码器：厂商解码器依赖 srcFormat 里的
            // KEY_FRAME_RATE / KEY_COLOR_STANDARD / csd 等键，手动重建格式会
            // 导致部分机型 configure 失败、一帧都出不来。
            // 旋转不在解码器上做，方向由容器元数据表达（原封不动策略）。
            dec.configure(srcFormat, fb.inputSurface, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            val frameStepUs = 1_000_000L / outFps.coerceAtLeast(1)
            var lastSlot = -1L
            var outputDone = false
            var inputDone = false
            var rendered = 0L
            var lastPts = 0L
            var firstSrcUs = -1L

            while (!outputDone) {
                // ---- 送帧 ----
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            outputDone = true
                            break
                        }
                        inBuf.clear()
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(
                                inIdx, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            lastPts = extractor.sampleTime
                            dec.queueInputBuffer(inIdx, 0, size, max(0L, lastPts), 0)
                            extractor.advance()
                        }
                    }
                }

                // ---- 取帧并渲染 ----
                val idx = dec.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val pts = info.presentationTimeUs
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    // 量化网格判定：把源帧 PTS 归到 1/fps 网格桶，桶号前进了才渲染。
                    // 旧逻辑用「与上一渲染帧的 PTS 差 >= frameStep - 2ms」，对
                    // 可变帧率录像（间隔 16ms/50ms 抖动）会大量误丢帧 —— 每丢一帧
                    // 输出时间轴就少一格，总时长跟着缩水（实测 8.37s 缩到 5.2s）。
                    // 桶号方案：恒定帧率全保留，降帧率时按桶丢弃，时长始终对齐源。
                    val slot = (pts + frameStepUs / 2) / frameStepUs
                    val shouldRender = isEos || lastSlot < 0L || slot > lastSlot
                    if (shouldRender && info.size >= 0 && !isEos) {
                        dec.releaseOutputBuffer(idx, true)
                        if (fb.awaitFrame()) {
                            if (firstSrcUs < 0) firstSrcUs = pts
                            // 编码时间轴直接用源 PTS 的网格位置：与送帧判定同源，
                            // 丢帧只发生在桶内，不会造成时长偏移
                            val outUs = slot * frameStepUs
                            fb.drawFrame(outUs, srcW, srcH, outW, outH, encoderSurface, rotation)
                            lastSlot = slot
                            presentationUs = (pts - firstSrcUs).coerceAtLeast(0L)
                            rendered++
                            renderedFrames = rendered
                        }
                    } else {
                        dec.releaseOutputBuffer(idx, false)
                    }
                    if (isEos) outputDone = true
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 忽略，尺寸以调用方传入的 srcW/srcH 为准
                }
            }
            Log.i(TAG, "render finished, frames=$rendered, rotation=$rotation")
        } catch (t: Throwable) {
            Log.e(TAG, "render failed", t)
            fatal = t
        } finally {
            try {
                dec?.stop()
            } catch (_: Throwable) {
            }
            try {
                dec?.release()
            } catch (_: Throwable) {
            }
            try {
                fb?.release()
            } catch (_: Throwable) {
            }
            try {
                // 触发编码器 EOS，让 muxer 循环能正常收尾
                encoder.signalEndOfInputStream()
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        try {
            decoder?.stop()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "VideoRenderer"
    }
}

/**
 * 核心 GL 层：窗口 EGL + SurfaceTexture 外部纹理 + 全屏四边形绘制。
 * 用"解码器输出到 Surface"配合 SurfaceTexture 是最稳的组合，各厂商实现都一致。
 */
class FrameBuffer(private val targetSurface: Surface) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var st: android.graphics.SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set
    private val stMatrix = FloatArray(16)
    private var posLoc = 0
    private var texLoc = 0
    private var matLoc = 0
    /** 场景变换（旋转 + 宽高比），几何参数不变时复用 */
    private val sceneMatrix = FloatArray(16)
    private var lastGeomKey = -1

    private val vertex = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = aTextureCoord.xy;
        }
    """.trimIndent()

    private val fragment = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    /** 顶点：两个三角形拼成的矩形，NDC 坐标 */
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val texCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            position(0)
        }

    fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw RuntimeException("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: throw RuntimeException("no EGLConfig")

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        surface = EGL14.eglCreateWindowSurface(
            display, config, targetSurface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        checkEgl()
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw RuntimeException("eglMakeCurrent failed")
        }

        program = buildProgram(vertex, fragment)
        posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        matLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        st = android.graphics.SurfaceTexture(textureId).apply { setOnFrameAvailableListener { } }
        inputSurface = Surface(st)
        android.opengl.Matrix.setIdentityM(stMatrix, 0)
        android.opengl.Matrix.setIdentityM(sceneMatrix, 0)
    }

    /** 等待解码器把一帧渲染进 SurfaceTexture */
    fun awaitFrame(): Boolean {
        val s = st ?: return false
        return try {
            s.updateTexImage()
            s.getTransformMatrix(stMatrix)
            true
        } catch (t: Throwable) {
            Log.w("FrameBuffer", "updateTexImage failed", t)
            false
        }
    }

    /**
     * 场景矩阵：纯等比缩放，**不做任何旋转**。
     *
     * 方向策略是「原封不动」：输出像素保持源编码坐标系，朝向信息完全交给
     * 容器的旋转元数据（MediaMuxer.setOrientationHint）。这样：
     *   - 竖拍源（1080x1920 + 90°）输出仍是 1080x1920 像素 + 90° 元数据
     *   - 横拍源（1920x1080 + 0°）输出仍是 1920x1080 像素 + 0° 元数据
     * 成片朝向与原片严格一致，不存在"像素转了、元数据也转了"的双重旋转。
     *
     * 因为不旋转，宽高比直接用 srcW/srcH 对 outW/outH，等比收缩留黑边。
     */
    private fun updateSceneMatrix(srcW: Int, srcH: Int, outW: Int, outH: Int, rotation: Int) {
        val key = (srcW * 31 + srcH) * 31 + (outW * 31 + outH) * 7 + rotation
        if (key == lastGeomKey) return
        lastGeomKey = key

        val m = sceneMatrix
        android.opengl.Matrix.setIdentityM(m, 0)
        val srcRatio = srcW.toFloat().coerceAtLeast(1f) / srcH.toFloat().coerceAtLeast(1f)
        val outRatio = outW.toFloat() / outH.toFloat()
        var sx = 1f
        var sy = 1f
        if (srcRatio > outRatio) sy = outRatio / srcRatio else sx = srcRatio / outRatio
        android.opengl.Matrix.scaleM(m, 0, sx, sy, 1f)
    }

    fun drawFrame(
        ptsUs: Long,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        target: Surface,
        rotation: Int
    ) {
        EGL14.eglMakeCurrent(display, surface, surface, context)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        updateSceneMatrix(srcW, srcH, outW, outH, rotation)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(matLoc, 1, false, sceneMatrix, 0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)

        EGLExt.eglPresentationTimeANDROID(display, surface, ptsUs * 1000)
        if (!EGL14.eglSwapBuffers(display, surface)) {
            Log.w("FrameBuffer", "eglSwapBuffers failed ${EGL14.eglGetError()}")
        }
    }

    fun release() {
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        } catch (_: Throwable) {
        }
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        try {
            st?.release()
        } catch (_: Throwable) {
        }
    }

    private fun checkEgl() {
        val e = EGL14.eglGetError()
        if (e != EGL14.EGL_SUCCESS) throw RuntimeException("EGL error $e")
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("link failed: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("compile failed: $log")
        }
        return s
    }
}
