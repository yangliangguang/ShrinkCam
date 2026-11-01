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
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.backHome() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.ArrowBack, null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(3.dp))
                Text("返回", color = Accent, fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("压缩完成", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "再次拍摄",
                color = Accent, fontSize = 17.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        vm.backHome()
                        vm.clearPending()
                    }
                    .padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        // 大数字：节省比例
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Card)
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("体积减少", color = TextSub, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                Fmt.percent(1 - done.outSize.toDouble() / done.job.info.sizeBytes.coerceAtLeast(1)),
                color = Green,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.size(done.job.info.sizeBytes), color = TextSub, fontSize = 15.sp)
                Text(" → ", color = TextSub, fontSize = 15.sp)
                Text(Fmt.size(done.outSize), color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(0.5.dp).background(Separator))
            Spacer(Modifier.height(6.dp))
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
            color = TextSub, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .padding(vertical = 11.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(1.dp))
        Text(label, color = TextSub, fontSize = 11.sp)
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
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Accent else Card)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (primary) Color.White else Accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = if (primary) Color.White else Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
                .clip(RoundedCornerShape(16.dp))
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
                .clip(RoundedCornerShape(9.dp))
                .background(CardHi)
                .padding(2.dp)
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
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Card else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) TextMain else TextSub,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
