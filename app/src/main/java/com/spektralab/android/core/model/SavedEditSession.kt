package com.spektralab.android.core.model

import org.json.JSONArray
import org.json.JSONObject

/** A default-edit workbench, containing references only, never decoded image data or drafts. */
internal data class SavedEditSession(
    val photos: List<String>,
    val current: String?,
    val selected: Set<String>,
) {
    init {
        require(photos.size <= 500) { "WORKBENCH_TOO_LARGE" }
        require(photos.distinct().size == photos.size)
        require(photos.all { it.isNotBlank() && it.length <= 16384 })
        require(if (photos.isEmpty()) current == null else current in photos)
        require(selected.all { it in photos })
    }

    fun remap(resolve: (String) -> String): SavedEditSession {
        val mapped = photos.associateWith(resolve)
        return SavedEditSession(
            photos.map { mapped.getValue(it) }.distinct(),
            current?.let(mapped::getValue),
            selected.mapTo(linkedSetOf()) { mapped.getValue(it) },
        )
    }

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("photos", JSONArray(photos))
        .put("current", current ?: JSONObject.NULL)
        .put("selected", JSONArray(photos.filter { it in selected }))
        .toString()

    companion object {
        fun fromJson(raw: String): SavedEditSession {
            require(raw.length <= 2 * 1024 * 1024)
            val json = JSONObject(raw)
            require(json.get("version") is Number && json.getDouble("version") == 1.0)
            // Unknown session fields must not disappear in the next ordinary save.
            require(json.keys().asSequence().toSet() == setOf("version", "photos", "current", "selected"))
            fun strings(key: String): List<String> {
                val array = json.getJSONArray(key)
                require(array.length() <= 500)
                return (0 until array.length()).map { array.get(it).also { value -> require(value is String) } as String }
            }
            val current = if (json.isNull("current")) null else json.get("current").also { require(it is String) } as String
            val selected = strings("selected")
            require(selected.distinct().size == selected.size)
            return SavedEditSession(strings("photos"), current, selected.toSet())
        }
    }
}
