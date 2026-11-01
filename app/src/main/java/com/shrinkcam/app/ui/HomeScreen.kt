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
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
fun HomeScreen(vm: MainViewModel, onRecord: () -> Unit, onPickGallery: () -> Unit, onLibrary: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        // iOS 大标题导航栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("压缩相机", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(vm.engineLabel, color = TextSub, fontSize = 13.sp)
            }
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Compress, null, tint = Color.White,
                    modifier = Modifier
                        .size(19.dp)
                        .clickable { onLibrary() }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // 录 / 选 —— iOS 双卡片
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(
                title = "拍视频",
                subtitle = "录完自动压缩",
                icon = {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                },
                filled = false,
                modifier = Modifier.weight(1f),
                onClick = onRecord
            )
            BigAction(
                title = "从相册选",
                subtitle = "已有视频压缩",
                icon = {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Green),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                },
                filled = false,
                modifier = Modifier.weight(1f),
                onClick = onPickGallery
            )
        }

        Spacer(Modifier.height(12.dp))
        // 视频管理入口（iOS 分组列表行）
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
                .clickable { onLibrary() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF5856D6)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.VideoLibrary, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("视频管理", color = TextMain, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("查看 · 分享 · 删除", color = TextSub, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ArrowForward, null, tint = Color(0xFFC7C7CC), modifier = Modifier.size(16.dp))
        }

        val pending = vm.pending
        if (pending != null) {
            Spacer(Modifier.height(20.dp))
            SelectedCard(vm)
        }

        Spacer(Modifier.height(22.dp))
        Text("压缩强度", color = TextSub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(7.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
        ) {
            Quality.entries.forEachIndexed { i, q ->
                QualityRow(
                    q = q,
                    selected = vm.quality == q,
                    onClick = { vm.applyQuality(q) },
                    showDivider = i != Quality.entries.lastIndex
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SizePicker(vm)

        Spacer(Modifier.height(12.dp))
        AudioPicker(vm)

        Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(22.dp))
            Text("最近记录", color = TextSub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(7.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Card)
            ) {
                vm.history.take(5).forEachIndexed { i, d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(Fmt.size(d.job.info.sizeBytes), color = TextSub, fontSize = 14.sp)
                        Text(" → ", color = TextSub, fontSize = 14.sp)
                        Text(Fmt.size(d.outSize), color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(Fmt.ratio(d.job.info.sizeBytes, d.outSize), color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    if (i != minOf(4, vm.history.size - 1)) {
                        Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(Separator))
                    }
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
            .height(104.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) Brush.linearGradient(listOf(Accent, AccentDim)) else Brush.linearGradient(listOf(Card, Card)))
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            icon()
            Column {
                Text(title, color = if (filled) Color.White else TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = if (filled) Color.White.copy(alpha = 0.8f) else TextSub, fontSize = 12.sp)
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
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(96.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                VideoThumb(job.sourceUri)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, color = TextMain, fontSize = 15.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${job.info.displayW}×${job.info.displayH} · ${Fmt.duration(job.info.durationUs)} · ${Fmt.size(src)}",
                    color = TextSub, fontSize = 12.sp
                )
                Text(
                    "预计 ${Fmt.size(est)} · 省 ${Fmt.percent(1 - est.toDouble() / src.coerceAtLeast(1))}",
                    color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(CardHi)
                    .clickable { vm.clearPending() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close, null, tint = TextSub,
                    modifier = Modifier.size(13.dp)
                )
            }
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
private fun QualityRow(
    q: Quality,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Column(Modifier.clickable { onClick() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(q.label, color = TextMain, fontSize = 16.sp)
                    if (q == Quality.EXTREME) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "最省",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Warn)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(q.desc, color = TextSub, fontSize = 12.sp)
            }
            // iOS 勾选
            if (selected) {
                Icon(
                    Icons.Filled.Check, null, tint = Accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp)
                    .height(0.5.dp)
                    .background(Separator)
            )
        }
    }
}

@Composable
private fun SizePicker(vm: MainViewModel) {
    val job = vm.pending
    val preview = if (job != null) {
        val p = vm.estimate(job)
        "${p.outWidth}×${p.outHeight}"
    } else null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
    ) {
        // iOS 表单行：标题 | 值
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("分辨率", color = TextMain, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(
                preview ?: vm.sizeMode.label,
                color = TextSub, fontSize = 15.sp
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(Separator))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            IosSegmented(
                options = SizeMode.entries.map { it.label },
                selectedIndex = SizeMode.entries.indexOf(vm.sizeMode),
                onSelect = { vm.applySizeMode(SizeMode.entries[it]) }
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "方向始终与原片一致，不会被旋转或翻转",
                color = TextSub, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun AudioPicker(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("音频", color = TextMain, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (!vm.keepAudio) "已关闭" else "原音直通",
                color = if (vm.keepAudio) TextSub else Warn,
                fontSize = 15.sp
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(Separator))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            IosSegmented(
                options = listOf("保留原声", "关闭音频"),
                selectedIndex = if (vm.keepAudio) 0 else 1,
                onSelect = { vm.applyKeepAudio(it == 0) }
            )
            Spacer(Modifier.height(7.dp))
            Text(
                if (vm.keepAudio) "声音原样保留不压缩，音质与原片完全一致"
                else "成片将完全没有声音轨道",
                color = TextSub, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TargetPicker(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("目标体积", color = TextMain, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.targetMb <= 0f) "不限" else "≈ ${vm.targetMb.roundToInt()} MB",
                color = TextSub, fontSize = 15.sp
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(Separator))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Slider(
                value = vm.targetMb,
                onValueChange = { vm.applyTargetMb(it) },
                valueRange = 0f..50f,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Separator
                )
            )
            Row(
                Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0f, 3f, 8f, 20f, 50f).forEach { mb ->
                    val sel = vm.targetMb == mb
                    Text(
                        if (mb == 0f) "不限" else "${mb.roundToInt()}M",
                        color = if (sel) Color.White else TextSub,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (sel) Accent else CardHi)
                            .clickable { vm.applyTargetMb(mb) }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "设定后会自动重编码一轮校准，把成片误差压到 5% 以内",
                color = TextSub, fontSize = 12.sp
            )
        }
    }
}

/** iOS 分段控件（UISegmentedControl 外观） */
@Composable
fun IosSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(CardHi)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (sel) Card else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (sel) TextMain else TextSub,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
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
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Accent else CardHi)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color.White else TextSub,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
