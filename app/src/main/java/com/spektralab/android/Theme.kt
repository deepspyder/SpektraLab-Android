package com.spektralab.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object TheiaTokens {
    // Keep the darkroom neutral so the UI never adds a green cast around a photo.
    val background = Color(0xFF151515)
    val surface = Color(0xFF1C1C1C)
    val surfaceLow = Color(0xFF191919)
    val surfaceMid = Color(0xFF252525)
    val surfaceHigh = Color(0xFF2E2E2E)
    val paper = Color(0xFFF2EEE6)
    val paperMuted = Color(0xFFB8B2A8)
    val amber = Color(0xFFE5AA55)
    val amberDeep = Color(0xFF4A351D)
    val line = Color(0xFF424242)
}

private val TheiaColors = darkColorScheme(
    primary = TheiaTokens.amber, onPrimary = Color(0xFF261A0C),
    primaryContainer = TheiaTokens.amberDeep, onPrimaryContainer = Color(0xFFFFE1B6),
    secondary = Color(0xFFC8BCA5), onSecondary = Color(0xFF28231C),
    secondaryContainer = Color(0xFF38352E), onSecondaryContainer = Color(0xFFE7DECF),
    background = TheiaTokens.background, onBackground = TheiaTokens.paper,
    surface = TheiaTokens.surface, onSurface = TheiaTokens.paper,
    surfaceVariant = TheiaTokens.surfaceMid, onSurfaceVariant = TheiaTokens.paperMuted,
    outline = Color(0xFF888888), outlineVariant = TheiaTokens.line,
    surfaceTint = TheiaTokens.surface,
    surfaceDim = TheiaTokens.background, surfaceBright = Color(0xFF3A3A3A),
    surfaceContainer = TheiaTokens.surfaceMid, surfaceContainerHigh = TheiaTokens.surfaceHigh,
    surfaceContainerLow = TheiaTokens.surfaceLow, surfaceContainerLowest = Color(0xFF101010),
    surfaceContainerHighest = Color(0xFF363636),
    error = Color(0xFFFFB4AB), onError = Color(0xFF32110C),
)

// Bundle Chinese, Latin and digits together so every device uses the same rounded face.
internal val SpektraFontFamily = FontFamily(
    Font(R.font.resource_han_rounded_regular, FontWeight.Normal),
    Font(R.font.resource_han_rounded_medium, FontWeight.Medium),
    Font(R.font.resource_han_rounded_bold, FontWeight.Bold),
)

private val TheiaTypography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
).let { defaults ->
    // Cover all Material roles, including defaults used by dialogs, fields and navigation.
    defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = SpektraFontFamily),
        displayMedium = defaults.displayMedium.copy(fontFamily = SpektraFontFamily),
        displaySmall = defaults.displaySmall.copy(fontFamily = SpektraFontFamily),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = SpektraFontFamily),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = SpektraFontFamily),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = SpektraFontFamily),
        titleLarge = defaults.titleLarge.copy(fontFamily = SpektraFontFamily),
        titleMedium = defaults.titleMedium.copy(fontFamily = SpektraFontFamily),
        titleSmall = defaults.titleSmall.copy(fontFamily = SpektraFontFamily),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = SpektraFontFamily),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = SpektraFontFamily),
        bodySmall = defaults.bodySmall.copy(fontFamily = SpektraFontFamily),
        labelLarge = defaults.labelLarge.copy(fontFamily = SpektraFontFamily),
        labelMedium = defaults.labelMedium.copy(fontFamily = SpektraFontFamily),
        labelSmall = defaults.labelSmall.copy(fontFamily = SpektraFontFamily),
    )
}

/** Shared contours keep controls, photo frames and floating surfaces in one family. */
internal object TheiaShapes {
    val badge = RoundedCornerShape(6.dp)
    val compact = RoundedCornerShape(8.dp)
    val control = RoundedCornerShape(12.dp)
    val thumbnail = RoundedCornerShape(10.dp)
    val thumbnailInset = RoundedCornerShape(7.dp)
    val panel = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val material = Shapes(extraSmall = compact, small = control, medium = panel,
        large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp))
}

@Composable
fun TheiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TheiaColors,
        shapes = TheiaShapes.material,
        typography = TheiaTypography, content = content)
}
