package com.spektralab.android.data.export

import android.graphics.*
import com.spektralab.android.core.model.*
import kotlin.math.min
import kotlin.math.roundToInt

/** Software composition of the planned scene, shared by small layout previews and formal output. */
internal object TableComposition {
    fun render(plan: TableExportPlan, font: Typeface, check: () -> Unit = {},
        image: (TablePlacement) -> Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val options = plan.request.options
            val table = options.mode == TableOutputMode.TABLE
            val light = !table || plan.request.scene.lightOn
            if (!options.transparent) {
                if (table) {
                    canvas.drawColor(if (light) TableMaterial.DAY else TableMaterial.NIGHT)
                    val paint = Paint().apply {
                        shader = RadialGradient(plan.width * .5f, plan.height * .4f, maxOf(plan.width, plan.height) * .85f,
                            if (light) intArrayOf(0xFFFFFFF5.toInt(), 0xFFF0ECE2.toInt()) else intArrayOf(0xFF22201D.toInt(), 0xFF121416.toInt()), null, Shader.TileMode.CLAMP)
                    }
                    canvas.drawRect(0f, 0f, plan.width.toFloat(), plan.height.toFloat(), paint)
                } else canvas.drawColor(if (options.mode == TableOutputMode.POSTER) TableMaterial.PAPER else Color.WHITE)
            }
            drawHeading(canvas, plan, font)
            canvas.translate(plan.offsetX, plan.offsetY); canvas.scale(plan.scale, plan.scale)
            val clip = canvas.save()
            if (options.viewport) canvas.clipRect(0f, 0f, plan.region.width, plan.region.height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (table && !light) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                .075f,0f,0f,0f,0f, 0f,.075f,0f,0f,0f, 0f,0f,.075f,0f,0f, 0f,0f,0f,1f,0f)))
            val lower = mutableListOf<List<TablePoint>>()
            plan.items.forEachIndexed { index, item ->
                check()
                val source = image(item)
                // Normalize first: distant canvas coordinates must not cancel large Float products.
                val local = item.copy(x = item.x - plan.region.left, y = item.y - plan.region.top)
                val destination = fit(source?.width ?: item.source.pixelWidth, source?.height ?: item.source.pixelHeight, local)
                val polygon = TableGeometry.corners(destination.left, destination.top, destination.width(), destination.height(), item.rotation)
                val overlaps = if (table) lower.mapNotNull { other -> TableGeometry.intersect(polygon, other).takeIf { TableGeometry.area(it) > .001f } } else emptyList()
                val overlapping = Path().apply { overlaps.forEach { addPolygon(it) } }
                if (table && options.shadows) {
                    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (options.transparent) Color.argb(24, 0, 0, 0) else Color.argb(18, 0, 0, 0)
                        setShadowLayer(plan.span * .009f, plan.span * .002f, plan.span * .005f, Color.argb(50, 0, 0, 0))
                    }
                    canvas.drawPath(Path().apply { addPolygon(polygon) }, shadow)
                }
                fun draw(alpha: Int) {
                    val saved = canvas.save()
                    // Express the final image rectangle once in device pixels. Letting a second
                    // canvas scale fold into bitmap sampling can select different filter weights.
                    canvas.setMatrix(Matrix())
                    val pixels = RectF(plan.offsetX + destination.left * plan.scale, plan.offsetY + destination.top * plan.scale,
                        plan.offsetX + destination.right * plan.scale, plan.offsetY + destination.bottom * plan.scale)
                    canvas.rotate(item.rotation, pixels.centerX(), pixels.centerY())
                    paint.alpha = alpha
                    if (source != null && !source.isRecycled) canvas.drawBitmap(source, null, pixels, paint)
                    else canvas.drawRect(pixels, Paint().apply { color = 0xFFD9D4CB.toInt() })
                    canvas.restoreToCount(saved)
                }
                if (overlaps.isEmpty()) draw(255) else {
                    val outside = canvas.save(); canvas.clipOutPath(overlapping); draw(255); canvas.restoreToCount(outside)
                    val inside = canvas.save(); canvas.clipPath(overlapping)
                    val depth = overlaps.maxOf { points -> TableBox.around(points).let { min(it.width, it.height) } }
                    draw((255 * TableMaterial.transmission(depth, plan.request.transmissionFade)).roundToInt())
                    canvas.restoreToCount(inside)
                }
                lower += polygon
                if (options.numbered) {
                    val bounds = TableBox.around(polygon)
                    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = font; color = if (light) TableMaterial.INK else Color.WHITE
                        textSize = plan.span * .018f; textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText((index + 1).toString().padStart(2, '0'), (bounds.left + bounds.right) / 2,
                        bounds.bottom + text.textSize * 1.25f, text)
                }
            }
            canvas.restoreToCount(clip)
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    /** Fit rather than stretch if a newer crop changes the source aspect ratio. */
    fun fit(width: Int, height: Int, item: TablePlacement): RectF {
        if (width <= 0 || height <= 0) return RectF(item.x, item.y, item.x + item.width, item.y + item.height)
        val scale = min(item.width / width, item.height / height)
        val w = width * scale; val h = height * scale
        return RectF(item.x + (item.width - w) / 2, item.y + (item.height - h) / 2,
            item.x + (item.width + w) / 2, item.y + (item.height + h) / 2)
    }

    private fun Path.addPolygon(points: List<TablePoint>) {
        if (points.isEmpty()) return
        moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) }; close()
    }

    private fun drawHeading(canvas: Canvas, plan: TableExportPlan, font: Typeface) {
        val options = plan.request.options
        if (plan.heading == 0f) return
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font; color = TableMaterial.INK; textSize = plan.span * plan.scale * .047f
        }
        val left = plan.offsetX
        val top = plan.offsetY - plan.heading * plan.scale
        if (options.title.isNotEmpty()) {
            val available = plan.width - 2 * left.coerceAtLeast(0f)
            while (text.measureText(options.title) > available && text.textSize > 8f) text.textSize *= .95f
            canvas.drawText(options.title, left, top + text.textSize, text)
        }
        if (options.date.isNotEmpty()) {
            text.textSize = plan.span * plan.scale * .021f
            text.color = 0xFF68645D.toInt()
            canvas.drawText(options.date, left, top + plan.heading * plan.scale * .75f, text)
        }
    }
}
