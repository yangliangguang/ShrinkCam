package com.shrinkcam.app.ui

import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shrinkcam.app.Fmt
import com.shrinkcam.app.MainViewModel
import com.shrinkcam.app.Quality
import com.shrinkcam.app.SizeMode
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: MainViewModel, onRecord: () -> Unit, onPickGallery: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(Accent, AccentDim))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Compress, null, tint = Bg, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("压缩相机", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(vm.engineLabel, color = TextSub, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(22.dp))

        // 录 / 选
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(
                title = "拍视频",
                subtitle = "录完自动压缩",
                icon = { Icon(Icons.Filled.Videocam, null, tint = Bg, modifier = Modifier.size(30.dp)) },
                filled = true,
                modifier = Modifier.weight(1f),
                onClick = onRecord
            )
            BigAction(
                title = "从相册选",
                subtitle = "已有视频压缩",
                icon = { Icon(Icons.Filled.PhotoLibrary, null, tint = Accent, modifier = Modifier.size(28.dp)) },
                filled = false,
                modifier = Modifier.weight(1f),
                onClick = onPickGallery
            )
        }

        val pending = vm.pending
        if (pending != null) {
            Spacer(Modifier.height(20.dp))
            SelectedCard(vm)
        }

        Spacer(Modifier.height(20.dp))
        Text("压缩强度", color = TextSub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Quality.entries.forEach { q ->
                QualityRow(
                    q = q,
                    selected = vm.quality == q,
                    onClick = { vm.applyQuality(q) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SizePicker(vm)

        Spacer(Modifier.height(20.dp))
        AudioPicker(vm)

        Spacer(Modifier.height(20.dp))
        TargetPicker(vm)

        vm.error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Warn, fontSize = 13.sp)
        }

        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (pending == null) "先录一段视频吧" else "开始压缩",
            enabled = pending != null,
            onClick = vm::startCompress
        )

        if (vm.history.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text("最近记录", color = TextSub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            vm.history.take(5).forEach { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Accent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(Fmt.size(d.job.info.sizeBytes), color = TextSub, fontSize = 12.sp)
                    Text(" → ", color = TextSub, fontSize = 12.sp)
                    Text(Fmt.size(d.outSize), color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(Fmt.ratio(d.job.info.sizeBytes, d.outSize), color = Accent, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun BigAction(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (filled) Brush.linearGradient(listOf(Accent, AccentDim)) else Brush.linearGradient(listOf(Card, CardHi)))
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            icon()
            Column {
                Text(title, color = if (filled) Bg else TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = if (filled) Bg.copy(alpha = 0.75f) else TextSub, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SelectedCard(vm: MainViewModel) {
    val job = vm.pending ?: return
    val estimate = vm.estimate(job)
    val src = job.info.sizeBytes
    val est = estimate.estimateBytes
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(96.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                VideoThumb(job.sourceUri)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, color = TextMain, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${job.info.displayW}×${job.info.displayH} · ${Fmt.duration(job.info.durationUs)} · ${Fmt.size(src)}",
                    color = TextSub, fontSize = 11.sp
                )
                Text(
                    "预计 ${Fmt.size(est)} · 省 ${Fmt.percent(1 - est.toDouble() / src.coerceAtLeast(1))}",
                    color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
            }
            Icon(
                Icons.Filled.Close, null, tint = TextSub,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { vm.clearPending() }
            )
        }
    }
}

@Composable
private fun VideoThumb(uri: Uri) {
    val ctx = LocalContext.current
    val frame = androidx.compose.runtime.remember(uri) {
        try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(ctx, uri)
            val bmp = mmr.getFrameAtTime(200_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            mmr.release()
            bmp?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
    if (frame != null) {
        androidx.compose.foundation.Image(
            bitmap = frame,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Icon(Icons.Filled.Videocam, null, tint = TextSub, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QualityRow(q: Quality, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) CardHi else Card)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) Accent else CardHi),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Bg)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(q.label, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(q.desc, color = TextSub, fontSize = 11.sp)
        }
        if (q == Quality.EXTREME) {
            Text(
                "最省",
                color = Bg,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun SizePicker(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分辨率", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val job = vm.pending
            val preview = if (job != null) {
                val p = vm.estimate(job)
                "${p.outWidth}×${p.outHeight}"
            } else null
            Text(
                preview ?: vm.sizeMode.label,
                color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "方向始终与原片一致，不会被旋转或翻转",
            color = TextSub, fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardHi)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SizeMode.entries.forEach { mode ->
                val sel = vm.sizeMode == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (sel) Accent else Color.Transparent)
                        .clickable { vm.applySizeMode(mode) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.label,
                        color = if (sel) Bg else TextSub,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioPicker(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音频", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (!vm.keepAudio) "已关闭（无声）" else "原音直通",
                color = if (vm.keepAudio) Accent else Warn,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (vm.keepAudio) "声音原样保留不压缩，音质与原片完全一致"
            else "成片将完全没有声音轨道",
            color = TextSub, fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardHi)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(true, false).forEach { keep ->
                val sel = vm.keepAudio == keep
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (sel) Accent else Color.Transparent)
                        .clickable { vm.applyKeepAudio(keep) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (keep) "保留原声" else "关闭音频",
                        color = if (sel) Bg else TextSub,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetPicker(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("目标体积", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.targetMb <= 0f) "不限（码率优先）" else "≈ ${vm.targetMb.roundToInt()} MB",
                color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("设定后会自动重编码一轮校准，把成片误差压到 5% 以内", color = TextSub, fontSize = 11.sp)
        Slider(
            value = vm.targetMb,
            onValueChange = { vm.applyTargetMb(it) },
            valueRange = 0f..50f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = CardHi
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0f, 3f, 8f, 20f, 50f).forEach { mb ->
                val sel = vm.targetMb == mb
                Text(
                    if (mb == 0f) "不限" else "${mb.roundToInt()}M",
                    color = if (sel) Bg else TextSub,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Accent else CardHi)
                        .clickable { vm.applyTargetMb(mb) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Brush.linearGradient(listOf(Accent, AccentDim)) else Brush.linearGradient(listOf(Card, Card)))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Bg else TextSub,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
