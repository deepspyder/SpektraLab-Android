package com.spektralab.android.data.local

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Metadata-only roll-forward journal. The caller serializes all access to this directory. */
internal class EditBatchJournal(private val directory: File, private val checkpoint: (Int) -> Unit = {}) {
    private val journal get() = AtomicFile(File(directory, "pending-edit-batch.json"))

    fun recover() {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return
        val data = JSONObject(journal.openRead().bufferedReader().use { it.readText() })
        require(data.getInt("version") == 1)
        val entries = data.getJSONArray("entries")
        val validated = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            val key = item.getString("key")
            require(key.matches(Regex("(?:media-[1-9][0-9]*|uri-[0-9a-f]{16})")))
            key to item.getJSONObject("params").toString()
        }
        require(validated.map { it.first }.distinct().size == validated.size)
        for ((index, entry) in validated.withIndex()) {
            writeAtomicJson(File(directory, entry.first + ".json"), entry.second)
            checkpoint(index + 1)
        }
        journal.delete()
    }

    fun commit(entries: Map<String, String>) {
        recover()
        if (entries.isEmpty()) return
        check(directory.isDirectory || directory.mkdirs())
        val data = JSONObject().put("version", 1).put("entries", JSONArray().apply {
            entries.forEach { (key, value) ->
                require(key.matches(Regex("(?:media-[1-9][0-9]*|uri-[0-9a-f]{16})")))
                put(JSONObject().put("key", key).put("params", JSONObject(value)))
            }
        })
        writeAtomicJson(journal.baseFile, data.toString())
        checkpoint(0)
        recover()
    }
}

internal fun writeAtomicJson(file: File, json: String) {
    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(json.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    }
}
