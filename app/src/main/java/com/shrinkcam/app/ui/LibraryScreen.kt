package com.shrinkcam.app.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shrinkcam.app.Fmt
import com.shrinkcam.app.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 复用 Theme.kt 的 iOS 浅色色板
private val LibBg = Bg
private val CardBg = Card

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/** 压缩成品缩略图（MediaMetadataRetriever，取首帧附近） */
@Composable
private fun Thumb(file: File, modifier: Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(file.absolutePath)
                    r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
    val b = bmp
    Box(modifier, contentAlignment = Alignment.Center) {
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Videocam, null, tint = TextSub, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun FullPlayer(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // key 保证换片时重建 VideoView
            key(file.absolutePath) {
                AndroidView(
                    factory = {
                        VideoView(it).apply {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                it, "${it.packageName}.fileprovider", file
                            )
                            setVideoURI(uri)
                            setOnPreparedListener { mp -> mp.isLooping = false; start() }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("返回", color = Color.White, fontSize = 17.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibRow(
    item: MainViewModel.LibItem,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.10f) else CardBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 选择圈 / 缩略图
        Box(Modifier.size(64.dp)) {
            Thumb(
                item.file,
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A2D33))
            )
            if (selectMode) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    null,
                    tint = if (selected) Accent else Color(0xFFFFFFFF),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .background(Color(0x88000000), CircleShape)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(item.name, color = TextMain, fontSize = 15.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                "${Fmt.size(item.size)} · ${dateFmt.format(Date(item.lastMod))}",
                color = TextSub, fontSize = 12.sp
            )
        }

        // 播放
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, "播放", tint = Accent, modifier = Modifier.size(26.dp))
        }
        // 分享
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, "分享", tint = TextSub, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun LibraryScreen(vm: MainViewModel) {
    val items = vm.libItems
    var playing by remember { mutableStateOf<File?>(null) }

    // 删除确认
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(LibBg)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.backHome() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.ArrowBack, "返回", tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(3.dp))
                Text("返回", color = Accent, fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.libSelectMode) "已选 ${vm.libSelected.size} 项"
                else "视频管理",
                color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (vm.libSelectMode) {
                Text(
                    "全选", color = Accent, fontSize = 17.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.selectAllLib() }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                )
            } else {
                Text("", fontSize = 17.sp, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }

        // 批量操作条
        if (vm.libSelectMode && vm.libSelected.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { vm.shareLib(items.filter { vm.libSelected.contains(it.name) }) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("分享 ${vm.libSelected.size}")
                }
                Button(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Warn),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除 ${vm.libSelected.size}")
                }
            }
        }

        // 列表
        if (items.isEmpty() && !vm.libLoading) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Videocam, null, tint = TextSub, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("还没有压缩过的视频", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("压缩完成后会自动出现在这里", color = TextSub, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(items, key = { it.name }) { item ->
                    val selected = vm.libSelected.contains(item.name)
                    LibRow(
                        item = item,
                        selected = selected,
                        selectMode = vm.libSelectMode,
                        onClick = {
                            if (vm.libSelectMode) vm.toggleLibSelect(item.name)
                            else playing = item.file
                        },
                        onLongClick = {
                            if (!vm.libSelectMode) vm.enterLibSelectMode(item.name)
                            else vm.toggleLibSelect(item.name)
                        },
                        onShare = { vm.shareLib(listOf(item)) },
                        onPlay = { playing = item.file }
                    )
                }
            }
        }
    }

    // 全屏播放
    playing?.let { f ->
        if (f.exists()) {
            FullPlayer(file = f, onDismiss = { playing = null })
        } else {
            playing = null
        }
    }

    // 删除确认
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除视频", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "将删除选中的 ${vm.libSelected.size} 个视频（含相册「压缩相机」里的同一条），删除后无法恢复。",
                    color = TextMain, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelectedLib()
                }) { Text("删除", color = Warn, fontSize = 17.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Accent, fontSize = 17.sp) }
            },
            containerColor = Card
        )
    }
}
