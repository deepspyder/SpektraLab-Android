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

private val SpektraColors = darkColorScheme(
    primary = Color(0xFFD2B783), onPrimary = Color(0xFF211D16),
    primaryContainer = Color(0xFF383329), onPrimaryContainer = Color(0xFFF0DFC0),
    secondary = Color(0xFFBCC6D2), onSecondary = Color(0xFF20252B),
    secondaryContainer = Color(0xFF30353B), onSecondaryContainer = Color(0xFFE1E6ED),
    background = Color(0xFF121315), onBackground = Color(0xFFF0EFEB),
    surface = Color(0xFF1C1D20), onSurface = Color(0xFFF0EFEB),
    surfaceVariant = Color(0xFF292B2F), onSurfaceVariant = Color(0xFFB8BABF),
    outline = Color(0xFF81848B), outlineVariant = Color(0xFF383B40),
    surfaceContainer = Color(0xFF232529), surfaceContainerHigh = Color(0xFF2C2E33),
    surfaceContainerLow = Color(0xFF191A1D), surfaceContainerLowest = Color(0xFF0E0F11),
    error = Color(0xFFFFA69A), onError = Color(0xFF32110C),
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
