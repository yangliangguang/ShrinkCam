package com.shrinkcam.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * iOS 风格浅色色板。
 * 参照 iOS 系统色：
 *   systemGroupedBackground  #F2F2F7  页面底色
 *   secondarySystemGrouped   #FFFFFF  卡片（分组列表）
 *   systemBlue               #007AFF  强调色
 *   systemRed                #FF3B30  危险/关闭
 *   label                    #000000  主文字
 *   secondaryLabel           #8E8E93  次文字
 *   separator                #E5E5EA  分隔线
 * 圆角统一用 12~16dp（iOS 卡片习惯），不使用渐变/霓虹色。
 */
val Bg = Color(0xFFF2F2F7)
val Card = Color(0xFFFFFFFF)
val CardHi = Color(0xFFEDEDF2)
val Accent = Color(0xFF007AFF)
val AccentDim = Color(0xFF0062CC)
val Warn = Color(0xFFFF3B30)
val TextMain = Color(0xFF000000)
val TextSub = Color(0xFF8E8E93)
val Separator = Color(0xFFE5E5EA)
val Green = Color(0xFF34C759)

private val scheme: ColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentDim,
    background = Bg,
    onBackground = TextMain,
    surface = Card,
    onSurface = TextMain,
    surfaceVariant = CardHi,
    onSurfaceVariant = TextSub,
    error = Warn,
    outline = Separator
)

@Composable
fun ShrinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
