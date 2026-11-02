package com.spektralab.android

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Clip two independently fitted, complete frames; never resize either half as the divider moves. */
@Composable
fun BeforeAfterPreview(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var split by remember(before) { mutableFloatStateOf(0.5f) }
    val original = remember(before) { before.asImageBitmap() }
    val processed = remember(after) { after.asImageBitmap() }
    val dividerShade = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .8f)
    val dividerLine = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "前后对比分界线"
            progressBarRangeInfo = ProgressBarRangeInfo(split, 0f..1f)
            setProgress { split = it.coerceIn(0f, 1f); true }
        }.pointerInput(before) {
            detectDragGestures(
                onDragStart = { split = (it.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) },
                onDrag = { change, _ ->
                    change.consume()
                    split = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
            )
        }) {
            fun fit(image: androidx.compose.ui.graphics.ImageBitmap) {
                val scale = minOf(size.width / image.width, size.height / image.height)
                val width = (image.width * scale).roundToInt().coerceAtLeast(1)
                val height = (image.height * scale).roundToInt().coerceAtLeast(1)
                drawImage(image, dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
                    dstSize = IntSize(width, height))
            }
            val x = size.width * split
            clipRect(right = x) { fit(original) }
            clipRect(left = x) { fit(processed) }
            drawLine(dividerShade, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
            drawLine(dividerLine, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            val handle = Offset(x.coerceIn(18.dp.toPx(), (size.width - 18.dp.toPx()).coerceAtLeast(18.dp.toPx())), size.height / 2)
            drawCircle(Color.White, 18.dp.toPx(), handle)
            for (dx in listOf(-4f, 4f)) drawLine(accent, handle + Offset(dx.dp.toPx(), -6.dp.toPx()),
                handle + Offset(dx.dp.toPx(), 6.dp.toPx()), 2.dp.toPx())
        }
        Text("Before · 原图", Modifier.align(Alignment.BottomStart).padding(10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .92f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
        Text("After · 冲印", Modifier.align(Alignment.BottomEnd).padding(10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .92f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
    }
}
