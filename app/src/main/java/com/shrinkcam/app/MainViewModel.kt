package com.shrinkcam.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class Screen { HOME, COMPRESSING, RESULT }

data class Job(
    val id: String,
    val name: String,
    val sourceUri: Uri,
    val info: VideoInfo,
    val quality: Quality,
    val targetBytes: Long,
    val sizeMode: SizeMode = SizeMode.SCALE,
    /** 是否保留音频；false 表示输出无声视频 */
    val keepAudio: Boolean = true
)

data class Done(
    val job: Job,
    val file: File,
    val outSize: Long,
    val plan: CompressPlan,
    val elapsedMs: Long,
    val passes: Int,
    val uri: Uri
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var screen by mutableStateOf(Screen.HOME)
        private set

    var quality by mutableStateOf(Quality.EXTREME)
        private set
    var targetMb by mutableStateOf(0f)          // 0 表示不限制
        private set
    /** 分辨率策略：等比缩小 / 保持原尺寸 */
    var sizeMode by mutableStateOf(SizeMode.SCALE)
        private set
    /** 是否保留音频（关闭后输出无声视频，体积更小） */
    var keepAudio by mutableStateOf(true)
        private set
    var pending by mutableStateOf<Job?>(null)
    var progress by mutableStateOf(0f)
    var progressText by mutableStateOf("")
    var passText by mutableStateOf("")
    var current by mutableStateOf<Done?>(null)
    var error by mutableStateOf<String?>(null)
    var history = mutableStateListOf<Done>()
    var engineLabel by mutableStateOf("")
    /** 结果页"播放"按钮信号，用于在 App 内重播 */
    var replaySignal by mutableStateOf(0)

    private val cancelFlag = AtomicBoolean(false)
    private var compressor: VideoCompressor? = null

    init {
        engineLabel = if (Support.hevcEncoderAvailable()) "H.265 硬编码引擎就绪" else "H.264 硬编码引擎就绪"
    }

    /** 用户从相机/相册回来 */
    fun onVideoPicked(uri: Uri, name: String? = null) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            error = null
            try {
                val info = withContext(Dispatchers.IO) {
                    val i = MediaProbe.probe(ctx, uri)
                    val rot = MediaProbe.readRotationFromFile(copyToCache(ctx, uri, "probe.mp4"))
                    if (i.rotation == 0 && rot != 0) i.copy(rotation = rot) else i
                }
                if (info.width <= 0 || info.height <= 0) {
                    error = "无法读取该视频，请换个文件试试"
                    return@launch
                }
                pending = Job(
                    id = UUID.randomUUID().toString(),
                    name = name ?: MediaProbe.displayName(ctx, uri),
                    sourceUri = uri,
                    info = info,
                    quality = quality,
                    targetBytes = if (targetMb > 0f) (targetMb * 1024f * 1024f).toLong() else 0L,
                    sizeMode = sizeMode,
                    keepAudio = keepAudio
                )
                screen = Screen.HOME
            } catch (t: Throwable) {
                error = "读取视频失败：${t.message}"
            }
        }
    }

    fun applyQuality(q: Quality) {
        quality = q
        pending = pending?.copy(quality = q)
    }

    fun applyTargetMb(mb: Float) {
        targetMb = mb
        pending = pending?.copy(targetBytes = if (mb > 0f) (mb * 1024f * 1024f).toLong() else 0L)
    }

    fun applySizeMode(mode: SizeMode) {
        sizeMode = mode
        pending = pending?.copy(sizeMode = mode)
    }

    fun applyKeepAudio(v: Boolean) {
        keepAudio = v
        pending = pending?.copy(keepAudio = v)
    }

    /** 估算输出体积，用于 UI 预览 */
    fun estimate(job: Job): CompressPlan =
        CompressPlan.make(
            job.info, job.quality, job.targetBytes,
            sizeMode = job.sizeMode, keepAudio = job.keepAudio
        )

    fun clearPending() {
        pending = null
        error = null
    }

    fun startCompress() {
        val job = pending ?: return
        val ctx = getApplication<Application>()
        cancelFlag.set(false)
        screen = Screen.COMPRESSING
        progress = 0f
        progressText = "准备中…"
        passText = ""
        error = null

        viewModelScope.launch(Dispatchers.IO) {
            val outDir = File(ctx.filesDir, "videos").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val q = when (job.quality) {
                Quality.EXTREME -> "x"
                Quality.BALANCED -> "b"
                Quality.CLEAR -> "c"
            }
            val outFile = File(outDir, "SHRINK_${stamp}_$q.mp4")
            val comp = VideoCompressor(ctx)
            compressor = comp
            try {
                val result = comp.compress(
                    job.sourceUri, job.info, job.quality, job.targetBytes, outFile,
                    sizeMode = job.sizeMode,
                    keepAudio = job.keepAudio
                ) { p ->
                    viewModelScope.launch {
                        progress = p.percent / 100f
                        progressText = p.message
                        passText = p.passText
                    }
                }
                val savedUri = MediaSaver.saveToGallery(ctx, result.file, outFile.name)
                    ?: Uri.fromFile(result.file)
                val done = Done(
                    job = job,
                    file = result.file,
                    outSize = result.file.length(),
                    plan = result.plan,
                    elapsedMs = result.elapsedMs,
                    passes = result.passes,
                    uri = savedUri
                )
                withContext(Dispatchers.Main) {
                    current = done
                    history.add(0, done)
                    screen = Screen.RESULT
                }
            } catch (c: VideoCompressor.CancelledException) {
                withContext(Dispatchers.Main) {
                    screen = Screen.HOME
                    progress = 0f
                    error = "已取消"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    screen = Screen.HOME
                    progress = 0f
                    fun fmt(e: Throwable, depth: Int): String {
                        val st = e.stackTrace.take(8).joinToString("\n") { "    at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.lineNumber})" }
                        var msg = "压缩失败[${e.javaClass.simpleName}]: ${e.message ?: ""}\n$st"
                        var c = e.cause
                        var d = 0
                        while (c != null && d < 2) {
                            val cst = c.stackTrace.take(8).joinToString("\n") { "    at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.lineNumber})" }
                            msg += "\n  Caused by[${c.javaClass.simpleName}]: ${c.message ?: ""}\n$cst"
                            c = c.cause; d++
                        }
                        return msg
                    }
                    error = fmt(t, 0)
                }
            } finally {
                compressor = null
            }
        }
    }

    fun cancelCompress() {
        cancelFlag.set(true)
        compressor?.cancel()
    }

    fun backHome() {
        screen = Screen.HOME
        current = null
    }

    fun share(done: Done) {
        val ctx = getApplication<Application>()
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", done.file
            )
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "分享压缩后的视频").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            error = "分享失败：${t.message}"
        }
    }

    fun play(done: Done) {
        val ctx = getApplication<Application>()
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", done.file
            )
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (t: Throwable) {
            error = "没有可用的播放器"
        }
    }

    private fun copyToCache(ctx: android.content.Context, uri: Uri, name: String): File {
        val f = File(ctx.cacheDir, name)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            }
        } catch (_: Throwable) {
        }
        return f
    }
}
