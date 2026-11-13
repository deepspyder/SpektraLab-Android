package com.spektralab.android.core.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class TableOutputMode { POSTER, CONTACT, TABLE }
internal enum class TablePaper { LAYOUT, A4_PORTRAIT, A4_LANDSCAPE, SQUARE }
internal enum class TableOutputFormat { PNG, JPEG }
internal data class TableBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun valid() = listOf(left, top, right, bottom).all(Float::isFinite) && width > 0f && height > 0f
    fun overlaps(other: TableBox) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun expanded(amount: Float) = TableBox(left - amount, top - amount, right + amount, bottom + amount)
    companion object {
        fun around(points: List<TablePoint>) = TableBox(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        fun union(boxes: List<TableBox>) = TableBox(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }
}

internal data class TableExportOptions(
    val mode: TableOutputMode = TableOutputMode.POSTER,
    val paper: TablePaper = TablePaper.LAYOUT,
    val format: TableOutputFormat = TableOutputFormat.PNG,
    val longEdge: Int = 2400,
    val transparent: Boolean = false,
    val title: String = "",
    val date: String = "",
    val numbered: Boolean = false,
    val shadows: Boolean = true,
    val latestEdits: Boolean = false,
    val viewport: Boolean = false,
) {
    fun validated() = apply {
        require(longEdge == 1600 || longEdge == 2400) { "TABLE_EXPORT_CAPACITY" }
        require(!transparent || format == TableOutputFormat.PNG) { "TABLE_EXPORT_TRANSPARENCY" }
        require(title.isEmpty() || AlbumNames.normalize(title) == title) { "TABLE_EXPORT_TITLE" }
        require(date.length <= 32 && date.none(Char::isISOControl)) { "TABLE_EXPORT_TITLE" }
    }
}

internal data class TableExportRequest(
    val scene: LightTableScene,
    val options: TableExportOptions,
    val viewport: TableBox?,
    val transmissionFade: Float = 18f,
) {
    fun plan(): TableExportPlan {
        scene.validated(); options.validated()
        require(transmissionFade.isFinite() && transmissionFade > 0f)
        check(scene.items.isNotEmpty()) { "TABLE_EXPORT_EMPTY" }
        val polygons = scene.items.associate { it.id to TableGeometry.corners(it.x, it.y, it.width, it.height, it.rotation) }
        val region = if (options.viewport) requireNotNull(viewport).also { require(it.valid()) { "TABLE_EXPORT_EMPTY" } }
            else TableBox.union(polygons.values.map(TableBox::around))
        check(region.valid()) { "TABLE_EXPORT_SPAN" }
        val shifted = polygons.mapValues { (_, points) -> points.map { TablePoint(it.x - region.left, it.y - region.top) } }
        val clip = TableGeometry.corners(0f, 0f, region.width, region.height, 0f)
        val items = scene.items.filter { item ->
            val polygon = polygons.getValue(item.id)
            TableBox.around(polygon).overlaps(region) && (!options.viewport ||
                TableGeometry.area(TableGeometry.intersect(shifted.getValue(item.id), clip)) > .000001f)
        }
        check(items.isNotEmpty()) { "TABLE_EXPORT_EMPTY" }
        val span = max(region.width, region.height)
        val numbering = if (options.numbered) span * .024f else 0f
        val bleed = if (options.mode == TableOutputMode.TABLE && options.shadows) span * .025f else 0f
        val margin = span * .035f + bleed
        val content = if (options.viewport) region else region.copy(bottom = region.bottom + numbering).expanded(bleed)
        val heading = if (options.mode == TableOutputMode.POSTER && (options.title.isNotEmpty() || options.date.isNotEmpty())) span * .14f else 0f
        val naturalW = content.width + 2 * margin
        val naturalH = content.height + 2 * margin + heading
        val aspect = when (options.paper) {
            TablePaper.LAYOUT -> naturalW / naturalH
            TablePaper.A4_PORTRAIT -> 1f / sqrt(2f)
            TablePaper.A4_LANDSCAPE -> sqrt(2f)
            TablePaper.SQUARE -> 1f
        }
        val width = if (aspect >= 1) options.longEdge else (options.longEdge * aspect).roundToInt()
        val height = if (aspect >= 1) (options.longEdge / aspect).roundToInt() else options.longEdge
        check(width >= 64 && height >= 64 && width.toLong() * height <= MAX_PIXELS) { "TABLE_EXPORT_SPAN" }
        val scale = min(width / naturalW, height / naturalH)
        check(items.any { max(it.width, it.height) * scale >= 8f }) { "TABLE_EXPORT_SPAN" }
        check(items.sumOf { (it.width * it.height * scale * scale).toDouble() } <= 96_000_000.0) { "TABLE_EXPORT_CAPACITY" }
        return TableExportPlan(this, items, region, width, height, scale,
            (width - content.width * scale) / 2f - (content.left - region.left) * scale,
            (height - (content.height + heading) * scale) / 2f + heading * scale - (content.top - region.top) * scale,
            margin, heading, span)
    }
    companion object { const val MAX_PIXELS = 6_000_000L }
}

internal data class TableExportPlan(
    val request: TableExportRequest,
    val items: List<TablePlacement>,
    val region: TableBox,
    val width: Int,
    val height: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val margin: Float,
    val heading: Float,
    val span: Float,
) {
    fun sourceEdge(uri: String) = items.filter { it.source.uri == uri }
        .maxOf { (max(it.width, it.height) * scale).roundToInt().coerceAtLeast(1) + 2 }
    val rgbaBytes get() = width.toLong() * height * 4
}

/** Shared viewing/export transmission law; fade is frozen in scene units at export submission. */
internal object TableMaterial {
    const val DAY = 0xFFF3F0E8.toInt()
    const val NIGHT = 0xFF171819.toInt()
    const val PAPER = 0xFFF1EEE7.toInt()
    const val INK = 0xFF242321.toInt()
    fun brightness(light: Boolean) = if (light) 1f else .075f
    fun transmission(depth: Float, fade: Float) = 1f - .42f * (depth / fade).coerceIn(0f, 1f)
}
