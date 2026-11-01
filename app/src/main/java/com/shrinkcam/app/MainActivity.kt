package com.shrinkcam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.shrinkcam.app.ui.HomeScreen
import com.shrinkcam.app.ui.ProgressScreen
import com.shrinkcam.app.ui.ResultScreen
import com.shrinkcam.app.ui.ShrinkTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShrinkTheme { Root(vm) }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) vm.onVideoPicked(uri)
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.onVideoPicked(uri) }

    val legacyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> res.data?.data?.let { vm.onVideoPicked(it) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakeVideo()
    ) { _ ->
        // TakeVideo 返回的 Bitmap 可能为 null，录制成功与否以文件是否落盘为准
        val f = cameraFile
        cameraTarget = null
        cameraFile = null
        if (f != null && f.exists() && f.length() > 0) {
            vm.onVideoPicked(Uri.fromFile(f), "录制_${System.currentTimeMillis()}.mp4")
        }
    }

    fun launchCamera() {
        val dir = File(ctx.cacheDir, "record").apply { mkdirs() }
        val f = File(dir, "record_tmp.mp4")
        if (f.exists()) f.delete()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        cameraFile = f
        cameraTarget = uri
        cameraLauncher.launch(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) launchCamera()
    }

    val openCamera: () -> Unit = {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) launchCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    val openGallery: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } else {
            val i = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
                type = "video/*"
            }
            legacyPicker.launch(i)
        }
    }

    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm = vm, onRecord = openCamera, onPickGallery = openGallery)
        Screen.COMPRESSING -> ProgressScreen(vm)
        Screen.RESULT -> ResultScreen(vm)
    }
}
