package com.spektralab.android.feature.export

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ExportProgressBar(value: Float, color: Color, track: Color) {
    val animated by animateFloatAsState(value.coerceIn(0f, 1f), animationSpec = tween(160), label = "export-progress")
    Canvas(Modifier.fillMaxWidth().height(4.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
        contentDescription = "导出进度"
    }) {
        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
        if (animated > 0f) drawLine(color, Offset(0f, size.height / 2), Offset(size.width * animated, size.height / 2), size.height, StrokeCap.Round)
    }
}
