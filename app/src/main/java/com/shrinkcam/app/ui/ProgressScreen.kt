package com.shrinkcam.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shrinkcam.app.Fmt
import com.shrinkcam.app.MainViewModel

@Composable
fun ProgressScreen(vm: MainViewModel) {
    val job = vm.pending
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { vm.progress },
                modifier = Modifier.size(160.dp),
                color = Accent,
                trackColor = Color(0xFFE5E5EA),
                strokeWidth = 8.dp
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${(vm.progress * 100f).toInt()}%",
                    color = TextMain,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light
                )
                Text(vm.progressText, color = TextSub, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(26.dp))
        Text(
            if (vm.passText.isNotBlank()) vm.passText else "正在重新编码…",
            color = TextMain,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        job?.let {
            Text(
                "${it.quality.label} · ${it.info.displayW}×${it.info.displayH} · ${Fmt.duration(it.info.durationUs)}",
                color = TextSub, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("硬编码进行中，请保持屏幕常亮", color = TextSub, fontSize = 12.sp)

        Spacer(Modifier.height(40.dp))
        Box(
            Modifier
                .width(160.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
                .clickable { vm.cancelCompress() },
            contentAlignment = Alignment.Center
        ) {
            Text("取消", color = Warn, fontSize = 16.sp, fontWeight = FontWeight.Normal)
        }
    }
}
