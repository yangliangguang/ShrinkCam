package com.shrinkcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shrinkcam.app.Done
import com.shrinkcam.app.Fmt
import com.shrinkcam.app.MainViewModel
import java.util.Locale

@Composable
fun ResultScreen(vm: MainViewModel) {
    val done = vm.current ?: return
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowBack, null, tint = TextMain,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { vm.backHome() }
            )
            Spacer(Modifier.width(12.dp))
            Text("压缩完成", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "再次拍摄",
                color = Accent, fontSize = 13.sp,
                modifier = Modifier.clickable {
                    vm.backHome()
                    vm.clearPending()
                }
            )
        }

        Spacer(Modifier.height(18.dp))

        // 大数字：节省比例
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF17372F), Color(0xFF132A28))))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("体积减少", color = TextSub, fontSize = 12.sp)
            Text(
                Fmt.percent(1 - done.outSize.toDouble() / done.job.info.sizeBytes.coerceAtLeast(1)),
                color = Accent,
                fontSize = 46.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.size(done.job.info.sizeBytes), color = TextSub, fontSize = 15.sp)
                Text("  →  ", color = TextSub, fontSize = 15.sp)
                Text(Fmt.size(done.outSize), color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "压缩比 ${Fmt.ratio(done.job.info.sizeBytes, done.outSize)} · 省下 ${Fmt.size(done.job.info.sizeBytes - done.outSize)}",
                color = TextSub, fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))
        ComparePlayer(done, replaySignal = vm.replaySignal)

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoTile("分辨率", "${done.plan.displayW}×${done.plan.displayH}", Modifier.weight(1f))
            InfoTile("码率", Fmt.kbps(done.plan.videoBitrate), Modifier.weight(1f))
            InfoTile("编码", if (done.plan.useHevc) "H.265" else "H.264", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoTile("帧率", "${done.plan.outFps} fps", Modifier.weight(1f))
            InfoTile("耗时", String.format(Locale.US, "%.1fs", done.elapsedMs / 1000f), Modifier.weight(1f))
            InfoTile("校准", if (done.passes > 1) "${done.passes} 轮" else "1 轮", Modifier.weight(1f))
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("分享", Icons.Filled.Share, true, Modifier.weight(1f)) { vm.share(done) }
            ActionButton("播放", Icons.Filled.PlayArrow, false, Modifier.weight(1f)) {
                vm.replaySignal += 1
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "已保存到相册 Movies/压缩相机",
            color = TextSub, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextSub, fontSize = 10.sp)
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (primary) Brush.linearGradient(listOf(Accent, AccentDim)) else Brush.linearGradient(listOf(Card, CardHi)))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (primary) Bg else Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = if (primary) Bg else TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 原片 / 压缩后 一键切换播放，直观对比画质；支持手动旋转预览 */
@Composable
private fun ComparePlayer(done: Done, replaySignal: Int) {
    var showOriginal by remember { mutableStateOf(false) }
    /** 预览旋转角度：0/90/180/270，仅影响预览，不改文件 */
    var previewRotation by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = false
        }
    }
    val target = if (showOriginal) done.job.sourceUri else android.net.Uri.fromFile(done.file)
    androidx.compose.runtime.LaunchedEffect(target) {
        player.setMediaItem(MediaItem.fromUri(target))
        player.prepare()
        player.playWhenReady = true   // 结果页直接自动播放，无需跳转外部播放器
        // 切换源时把预览角度复位，避免沿用上一段的方向
        previewRotation = 0
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    // 点"播放"时在 App 内从头重播，不跳外部播放器
    androidx.compose.runtime.LaunchedEffect(replaySignal) {
        if (replaySignal > 0) {
            player.seekTo(0)
            player.playWhenReady = true
        }
    }

    // 画幅按"预览朝向"决定：成片本身带源旋转元数据，播放器会自动转正，
    // 所以基准用显示方向；再叠加用户手动预览旋转。
    val rotatedSwap = previewRotation == 90 || previewRotation == 270
    val baseLandscape = done.plan.displayW >= done.plan.displayH
    val landscape = if (rotatedSwap) !baseLandscape else baseLandscape

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = true
                        controllerShowTimeoutMs = 4000
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        this.player = player
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (landscape) 16f / 9f else 9f / 16f)
                    .rotatePreview(previewRotation)
            )
            // 右上角：旋转按钮，点一次转 90°
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable {
                        previewRotation = (previewRotation + 90) % 360
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.RotateRight, null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
                .padding(4.dp)
        ) {
            Seg(
                "压缩后 ${Fmt.size(done.outSize)}", !showOriginal, Modifier.weight(1f)
            ) { showOriginal = false }
            Seg(
                "原片 ${Fmt.size(done.job.info.sizeBytes)}", showOriginal, Modifier.weight(1f)
            ) { showOriginal = true }
        }
        if (previewRotation != 0) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "预览已旋转 ${previewRotation}°",
                    color = TextSub, fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "复位",
                    color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardHi)
                        .clickable { previewRotation = 0 }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** 用 rotationZ 做画面旋转，不改动视频数据 */
private fun Modifier.rotatePreview(degrees: Int): Modifier =
    if (degrees == 0) this
    else this.graphicsLayer(rotationZ = degrees.toFloat())

@Composable
private fun Seg(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) CardHi else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Accent else TextSub,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
