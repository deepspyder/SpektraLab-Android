package com.spektralab.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SpektraTokens {
    val background = Color(0xFF151615)
    val surface = Color(0xFF1B1D1A)
    val surfaceLow = Color(0xFF191A18)
    val surfaceMid = Color(0xFF242622)
    val surfaceHigh = Color(0xFF2D2F2A)
    val paper = Color(0xFFF2EEE6)
    val paperMuted = Color(0xFFB8B2A8)
    val amber = Color(0xFFE5AA55)
    val amberDeep = Color(0xFF4A351D)
    val line = Color(0xFF41433D)
}

private val SpektraColors = darkColorScheme(
    primary = SpektraTokens.amber, onPrimary = Color(0xFF261A0C),
    primaryContainer = SpektraTokens.amberDeep, onPrimaryContainer = Color(0xFFFFE1B6),
    secondary = Color(0xFFC8BCA5), onSecondary = Color(0xFF28231C),
    secondaryContainer = Color(0xFF38352E), onSecondaryContainer = Color(0xFFE7DECF),
    background = SpektraTokens.background, onBackground = SpektraTokens.paper,
    surface = SpektraTokens.surface, onSurface = SpektraTokens.paper,
    surfaceVariant = SpektraTokens.surfaceMid, onSurfaceVariant = SpektraTokens.paperMuted,
    outline = Color(0xFF88877F), outlineVariant = SpektraTokens.line,
    surfaceContainer = SpektraTokens.surfaceMid, surfaceContainerHigh = SpektraTokens.surfaceHigh,
    surfaceContainerLow = SpektraTokens.surfaceLow, surfaceContainerLowest = Color(0xFF10110F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF32110C),
)

@Composable
fun SpektraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpektraColors,
        shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(10.dp)),
        typography = Typography(
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
        ), content = content)
}
