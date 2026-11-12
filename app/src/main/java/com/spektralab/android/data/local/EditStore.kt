package com.spektralab.android.data.local

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import com.spektralab.android.core.model.EditorParams
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 编辑参数持久化（sidecar）。每个照片一个 JSON，存在应用私有目录 filesDir/sidecars/。
 *
 * key 优先用 MediaStore 的 `_ID`（照片在媒体库里的稳定行号，重选同一张照片不变），
 * 解析不到时退化为 URI 字符串的 SHA-256 前 16 位。这样重选照片能恢复上次编辑。
 */
object EditStore {
    private const val DIR = "sidecars"

    data class StoredEdit(val params: EditorParams, val json: String?)
    data class BatchEdit(val uri: Uri, val expectedJson: String?, val updatedJson: String)

    private fun directory(context: Context) = File(context.filesDir, DIR)
    private fun recover(context: Context) = EditBatchJournal(directory(context)).recover()
    private fun read(file: File): String? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.openRead().bufferedReader().use { it.readText() }
    }

    @Synchronized
    fun load(context: Context, uri: Uri): EditorParams? {
        recover(context)
        val file = File(context.filesDir, "$DIR/${stableKey(context, uri)}.json")
        return try {
            read(file)?.let { EditorParams.fromJson(JSONObject(it)) }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun save(context: Context, uri: Uri, params: EditorParams) {
        try {
            recover(context)
            // Keep ordinary live-edit saves on their existing fast path. Durable fsync is
            // reserved for an explicit batch, never inserted into every slider frame.
            val dir = directory(context).apply { mkdirs() }
            File(dir, "${stableKey(context, uri)}.json").writeText(params.toJson().toString())
        } catch (e: Exception) {
            // 写失败（存储满等）不阻塞编辑，静默忽略。
        }
    }

    /** A broken existing sidecar must never become a default target during bulk copy. */
    @Synchronized
    fun loadForTransfer(context: Context, uri: Uri): StoredEdit {
        recover(context)
        val text = read(File(directory(context), "${stableKey(context, uri)}.json"))
        return StoredEdit(text?.let { EditorParams.fromJson(JSONObject(it)) } ?: EditorParams(), text)
    }

    /** Import analysis may finish after a manual batch; its suggestion must not win that race. */
    @Synchronized
    fun saveIfAbsent(context: Context, uri: Uri, suggestion: EditorParams): EditorParams {
        val stored = loadForTransfer(context, uri)
        if (stored.json != null) return stored.params
        save(context, uri, suggestion)
        return suggestion
    }

    /** Validate every target before the durable commit point. Recovery is idempotent roll-forward. */
    @Synchronized
    fun saveBatch(context: Context, edits: List<BatchEdit>) {
        recover(context)
        val entries = linkedMapOf<String, String>()
        val owned = EditorParams().toJson().keys().asSequence().toSet() +
            setOf("tone_curves", "color_adjustments", "channel_mixer", "color_wheels")
        for (edit in edits) {
            val key = stableKey(context, edit.uri)
            check(read(File(directory(context), "$key.json")) == edit.expectedJson) { "EDIT_TARGET_CHANGED" }
            val merged = edit.expectedJson?.let(::JSONObject) ?: JSONObject()
            owned.forEach(merged::remove)
            val updated = JSONObject(edit.updatedJson)
            updated.keys().forEach { merged.put(it, updated.get(it)) }
            val payload = merged.toString()
            check(entries[key] == null || entries[key] == payload) { "EDIT_TARGET_ALIAS" }
            entries[key] = payload
        }
        EditBatchJournal(directory(context)).commit(entries)
    }

    private fun stableKey(context: Context, uri: Uri): String {
        val id = resolveMediaId(context, uri)
        return if (id != null && id > 0) "media-$id" else "uri-" + hash16(uri.toString())
    }

    private fun resolveMediaId(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst() && c.columnCount > 0) c.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun hash16(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
