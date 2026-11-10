package com.spektralab.android.feature.editor.crop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

private enum class CropDrag { NONE, MOVE, TL, TR, BL, BR }

/**
 * 自由拖拽裁剪覆盖层：显示原图 + 裁剪框。拖动框内=移动，拖动四角=缩放。
 * [cropX]/[cropY]/[cropW]/[cropH] 与回调均为归一化（左上原点，0..1）。
 */
@Composable
fun CropOverlay(
    bitmap: Bitmap,
    cropX: Float,
    cropY: Float,
    cropW: Float,
    cropH: Float,
    onCropChange: (x: Float, y: Float, w: Float, h: Float) -> Unit,
    modifier: Modifier = Modifier,
    lockedAspect: Float? = null,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf(CropDrag.NONE) }
    var startCrop by remember { mutableStateOf(FloatArray(4)) }

    // 让手势 lambda 始终读到最新的 crop 值（否则 pointerInput 闭包会捕获旧值）。
    val latestCrop by rememberUpdatedState(floatArrayOf(cropX, cropY, cropW, cropH))
    val latestChange by rememberUpdatedState(onCropChange)
    val latestAspect by rememberUpdatedState(lockedAspect)

    val imgAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

    // ContentScale.Fit 显示时图像在盒内的实际矩形（可能带黑边）。
    val disp = remember(boxSize, imgAspect) {
        val bw = boxSize.width.toFloat()
        val bh = boxSize.height.toFloat()
        if (bw <= 0f || bh <= 0f) Rect.Zero
        else if (bw / bh > imgAspect) {
            val h = bh
            val w = bh * imgAspect
            Rect((bw - w) / 2f, 0f, (bw + w) / 2f, bh)
        } else {
            val w = bw
            val h = bw / imgAspect
            Rect(0f, (bh - h) / 2f, bw, (bh + h) / 2f)
        }
    }

    fun cropRectPx(x: Float, y: Float, w: Float, h: Float): Rect {
        val left = disp.left + x * disp.width
        val top = disp.top + y * disp.height
        return Rect(left, top, left + w * disp.width, top + h * disp.height)
    }

    Box(
        modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(boxSize, bitmap.width, bitmap.height) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (disp.width <= 0f || disp.height <= 0f) {
                            drag = CropDrag.NONE
                            return@detectDragGestures
                        }
                        val c = latestCrop
                        startCrop = c.copyOf()
                        val r = cropRectPx(c[0], c[1], c[2], c[3])
                        val t = 28.dp.toPx()
                        val px = offset.x
                        val py = offset.y
                        drag = when {
                            near(px, py, r.left, r.top, t) -> CropDrag.TL
                            near(px, py, r.right, r.top, t) -> CropDrag.TR
                            near(px, py, r.left, r.bottom, t) -> CropDrag.BL
                            near(px, py, r.right, r.bottom, t) -> CropDrag.BR
                            px in r.left..r.right && py in r.top..r.bottom -> CropDrag.MOVE
                            else -> CropDrag.NONE
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (drag == CropDrag.NONE) return@detectDragGestures
                        val dx = amount.x / disp.width
                        val dy = amount.y / disp.height
                        var x = startCrop[0]; var y = startCrop[1]
                        var w = startCrop[2]; var h = startCrop[3]
                        when (drag) {
                            CropDrag.MOVE -> { x += dx; y += dy }
                            CropDrag.TL -> { x += dx; y += dy; w -= dx; h -= dy }
                            CropDrag.TR -> { y += dy; w += dx; h -= dy }
                            CropDrag.BL -> { x += dx; w -= dx; h += dy }
                            CropDrag.BR -> { w += dx; h += dy }
                            CropDrag.NONE -> {}
                        }
                        val ratio = latestAspect?.takeIf { it > 0f }?.let { it / imgAspect }
                        if (ratio != null && drag != CropDrag.MOVE) {
                            val left = drag == CropDrag.TL || drag == CropDrag.BL
                            val top = drag == CropDrag.TL || drag == CropDrag.TR
                            val anchorX = if (left) startCrop[0] + startCrop[2] else startCrop[0]
                            val anchorY = if (top) startCrop[1] + startCrop[3] else startCrop[1]
                            val proposedW = if (abs(dx) >= abs(dy * ratio)) w else h * ratio
                            val limit = min(if (left) anchorX else 1f - anchorX,
                                (if (top) anchorY else 1f - anchorY) * ratio).coerceAtLeast(.02f)
                            w = proposedW.coerceIn(min(.1f, limit), limit)
                            h = w / ratio
                            x = if (left) anchorX - w else anchorX
                            y = if (top) anchorY - h else anchorY
                        }
                        val minW = 0.02f
                        val minH = 0.02f
                        // Dragging a handle outside the photo can exceed 1.0; clamp size
                        // before deriving the allowed origin, otherwise coerceIn(0, 1-w) throws.
                        w = w.coerceIn(minW, 1f)
                        h = h.coerceIn(minH, 1f)
                        x = x.coerceIn(0f, 1f - w)
                        y = y.coerceIn(0f, 1f - h)
                        // Drag amounts are incremental. Advance the origin each time.
                        startCrop = floatArrayOf(x, y, w, h)
                        latestChange(x, y, w, h)
                    },
                    onDragCancel = { drag = CropDrag.NONE },
                    onDragEnd = { drag = CropDrag.NONE }
                )
            }
    ) {
        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

        Canvas(Modifier.fillMaxSize()) {
            val r = cropRectPx(cropX, cropY, cropW, cropH)
            val dim = Color.Black.copy(alpha = 0.55f)
            // 上下左右四条暗边（裁剪框外变暗）
            drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, r.top))
            drawRect(dim, topLeft = Offset(0f, r.bottom), size = Size(size.width, size.height - r.bottom))
            drawRect(dim, topLeft = Offset(0f, r.top), size = Size(r.left, r.height))
            drawRect(dim, topLeft = Offset(r.right, r.top), size = Size(size.width - r.right, r.height))
            // 边框
            drawRect(Color.White, topLeft = Offset(r.left, r.top), size = Size(r.width, r.height), style = Stroke(2.dp.toPx()))
            // 四角 L 形手柄：不再用会溢出画布的白色圆点，避免裁剪界面看起来错位。
            val handle = 18.dp.toPx()
            val weight = 3.dp.toPx()
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                drawLine(Color.White, Offset(x, y), Offset(x + sx * handle, y), weight, StrokeCap.Round)
                drawLine(Color.White, Offset(x, y), Offset(x, y + sy * handle), weight, StrokeCap.Round)
            }
            corner(r.left, r.top, 1f, 1f)
            corner(r.right, r.top, -1f, 1f)
            corner(r.left, r.bottom, 1f, -1f)
            corner(r.right, r.bottom, -1f, -1f)
        }
    }
}

private fun near(px: Float, py: Float, cx: Float, cy: Float, t: Float): Boolean =
    abs(px - cx) <= t && abs(py - cy) <= t
