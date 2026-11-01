package com.shrinkcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0F1720)
val Card = Color(0xFF18232E)
val CardHi = Color(0xFF20303E)
val Accent = Color(0xFF22D3A8)
val AccentDim = Color(0xFF1AA383)
val Warn = Color(0xFFFF8A5B)
val TextMain = Color(0xFFF2F6F9)
val TextSub = Color(0xFF9DB0BF)

private val scheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04140F),
    secondary = AccentDim,
    background = Bg,
    onBackground = TextMain,
    surface = Card,
    onSurface = TextMain,
    surfaceVariant = CardHi,
    onSurfaceVariant = TextSub,
    error = Warn
)

@Composable
fun ShrinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
