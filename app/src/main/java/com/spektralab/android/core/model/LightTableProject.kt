package com.spektralab.android.core.model

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Metadata only. Previews are disposable, bounded files; originals and edits remain independent. */
internal data class TableSource(
    val uri: String,
    val name: String,
    val assetId: String? = null,
    val variantId: String? = null,
    val editRevision: Long = 0,
    val preview: String? = null,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
    val format: String = "",
    /** Developed wall frame, separate from the fallback source thumbnail. */
    val renderedPreview: String? = null,
)

internal data class TablePlacement(
    val id: String,
    val source: TableSource,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
)

/** List order is back to front. Coordinates are scene units, never display pixels or dp. */
internal data class LightTableScene(
    val items: List<TablePlacement> = emptyList(),
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val lightOn: Boolean = true,
    val millimeters: Boolean = false,
) {
    fun validated(): LightTableScene = apply {
        require(items.size <= 200 && items.map { it.id }.distinct().size == items.size) { "TABLE_CAPACITY" }
        require(zoom.isFinite() && zoom in .35f..6f)
        require(listOf(panX, panY).all { it.isFinite() && kotlin.math.abs(it) <= 1_000_000f })
        items.forEach { item ->
            require(UUID.fromString(item.id).toString() == item.id)
            require(listOf(item.x, item.y).all { it.isFinite() && kotlin.math.abs(it) <= 1_000_000f })
            require(listOf(item.width, item.height).all { it.isFinite() && it in .01f..10000f })
            require(item.rotation.isFinite() && item.rotation in -180f..180f)
            val source = item.source
            require(source.uri.length in 1..8192 && source.name.length <= 1024 && source.format.length <= 64)
            require(source.uri.startsWith("content://") || source.uri.startsWith("file://"))
            require((source.assetId == null) == (source.variantId == null) && source.editRevision >= 0)
            source.assetId?.let { require(UUID.fromString(it).toString() == it) }
            source.variantId?.let { require(UUID.fromString(it).toString() == it) }
            source.preview?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
            source.renderedPreview?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
            require(source.pixelWidth >= 0 && source.pixelHeight >= 0)
        }
    }

    fun toJson(): String {
        validated()
        return JSONObject().put("version", 1).put("zoom", zoom).put("pan_x", panX).put("pan_y", panY)
            .put("light", lightOn).put("millimeters", millimeters).put("items", JSONArray().apply {
                items.forEach { item -> put(JSONObject().apply {
                    put("id", item.id); put("x", item.x); put("y", item.y)
                    put("width", item.width); put("height", item.height); put("rotation", item.rotation)
                    put("source", JSONObject().apply {
                        val source = item.source
                        put("uri", source.uri); put("name", source.name)
                        put("asset", source.assetId); put("variant", source.variantId)
                        put("revision", source.editRevision); put("preview", source.preview)
                        put("pixel_width", source.pixelWidth); put("pixel_height", source.pixelHeight)
                        put("format", source.format)
                        put("rendered_preview", source.renderedPreview)
                    })
                }) }
            }).toString()
    }

    companion object {
        fun fromJson(raw: String): LightTableScene {
            require(raw.length <= 2 * 1024 * 1024) { "TABLE_CAPACITY" }
            val json = JSONObject(raw)
            require(json.get("version") is Number && json.getDouble("version") == 1.0) { "TABLE_FUTURE_VERSION" }
            fun JSONObject.known(vararg keys: String) {
                require(this.keys().asSequence().all { it in keys }) { "TABLE_FUTURE_VERSION" }
            }
            json.known("version", "zoom", "pan_x", "pan_y", "light", "millimeters", "items")
            val items = json.getJSONArray("items")
            require(items.length() <= 200) { "TABLE_CAPACITY" }
            fun JSONObject.number(name: String): Float {
                require(get(name) is Number) { "TABLE_INVALID_DATA" }
                return getDouble(name).toFloat()
            }
            return LightTableScene(List(items.length()) { index ->
                val item = items.getJSONObject(index)
                val source = item.getJSONObject("source")
                item.known("id", "x", "y", "width", "height", "rotation", "source")
                source.known("uri", "name", "asset", "variant", "revision", "preview", "rendered_preview", "pixel_width", "pixel_height", "format")
                TablePlacement(item.getString("id"), TableSource(
                    source.getString("uri"), source.getString("name"),
                    source.optString("asset").takeIf(String::isNotEmpty), source.optString("variant").takeIf(String::isNotEmpty),
                    source.getLong("revision"), source.optString("preview").takeIf(String::isNotEmpty),
                    source.getInt("pixel_width"), source.getInt("pixel_height"), source.getString("format"),
                    source.optString("rendered_preview").takeIf(String::isNotEmpty),
                ), item.number("x"), item.number("y"), item.number("width"), item.number("height"), item.number("rotation"))
            }, json.number("zoom"), json.number("pan_x"), json.number("pan_y"), json.getBoolean("light"), json.getBoolean("millimeters"))
                .validated()
        }
    }
}

internal data class LightTableProject(val id: String, val name: String, val revision: Long, val scene: LightTableScene)
internal data class TableProjectSummary(val id: String, val name: String, val revision: Long, val updatedAt: Long)
