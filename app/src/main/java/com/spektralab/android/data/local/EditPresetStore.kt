package com.spektralab.android.data.local

import android.content.Context
import android.util.AtomicFile
import com.spektralab.android.core.model.EditPreset
import com.spektralab.android.core.model.EditTemplate
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Edit templates deliberately live outside export recipes. Unsupported records survive edits. */
internal object EditPresetStore {
    data class Catalog(val presets: List<EditPreset>, val unsupported: Int)
    private const val FILE = "edit-presets.json"

    private fun read(context: Context): JSONObject {
        val file = AtomicFile(File(context.filesDir, FILE))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return JSONObject().put("version", 1).put("presets", JSONArray())
        return JSONObject(file.openRead().bufferedReader().use { it.readText() }).also {
            require(it.get("version") is Number && it.getDouble("version") == 1.0)
            it.getJSONArray("presets")
        }
    }

    @Synchronized
    fun load(context: Context): Catalog {
        val entries = read(context).getJSONArray("presets")
        val supported = (0 until entries.length()).mapNotNull { index ->
            runCatching { EditPreset.fromJson(entries.getJSONObject(index)) }.getOrNull()
        }
        require(supported.map { it.id }.distinct().size == supported.size)
        require(supported.map { EditPreset.nameKey(it.name) }.distinct().size == supported.size)
        return Catalog(supported, entries.length() - supported.size)
    }

    @Synchronized
    fun save(context: Context, name: String, template: EditTemplate, replaceId: String? = null): Catalog {
        val normalized = EditPreset.normalizedName(name)
        val supported = load(context).presets
        require(supported.none { it.id != replaceId && EditPreset.nameKey(it.name) == EditPreset.nameKey(normalized) })
        if (replaceId != null) require(supported.any { it.id == replaceId })
        val preset = EditPreset(replaceId ?: UUID.randomUUID().toString(), normalized, template)
        rewrite(context, replaceId, preset)
        return load(context)
    }

    @Synchronized
    fun delete(context: Context, id: String): Catalog {
        require(load(context).presets.any { it.id == id })
        rewrite(context, id, null)
        return load(context)
    }

    private fun rewrite(context: Context, removeId: String?, insert: EditPreset?) {
        val root = read(context)
        val old = root.getJSONArray("presets")
        val entries = JSONArray()
        for (index in 0 until old.length()) {
            val value = old.get(index)
            // Remove only readable records, never an unknown entry that happens to share an id.
            val parsed = runCatching { EditPreset.fromJson(value as JSONObject) }.getOrNull()
            if (parsed == null || parsed.id != removeId) entries.put(value)
        }
        insert?.let { entries.put(it.toJson()) }
        root.put("presets", entries)
        writeAtomicJson(File(context.filesDir, FILE), root.toString())
    }
}
