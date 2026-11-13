package com.spektralab.android.data.local

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.spektralab.android.core.model.EditorParams
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Single persistence facade for editor, import analysis and export snapshots.
 * Resolved records live only in saved-photos.db; old sidecars remain untouched backups.
 */
object EditStore {
    private const val DIR = "sidecars"
    data class StoredEdit(val params: EditorParams, val json: String?)
    data class BatchEdit(val uri: Uri, val expectedJson: String?, val updatedJson: String)
    class StorageException(cause: Throwable) : IllegalStateException("编辑记录无法读取或保存，原有数据已保留，请重试。", cause)
    private val handles = linkedMapOf<String, com.spektralab.android.data.library.SavedPhotoRepository>()
    private val failure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val storageError: kotlinx.coroutines.flow.StateFlow<String?> = failure
    fun dismissStorageError() { failure.value = null }

    private fun directory(context: Context) = File(context.filesDir, DIR)
    private fun recover(context: Context) = EditBatchJournal(directory(context)).recover()
    private fun repository(context: Context): com.spektralab.android.data.library.SavedPhotoRepository {
        val directory = File(context.filesDir, "photo-library").canonicalFile
        handles[directory.path]?.let { return it }
        val repository = com.spektralab.android.data.library.SavedPhotoRepository.open(directory)
        // Tests use isolated ContextWrapper roots. Bound all handles, not one DB per fixture forever.
        if (handles.size >= 8) handles.remove(handles.keys.first())?.close()
        handles[directory.path] = repository
        return repository
    }
    private fun name(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    private fun readLegacy(file: File): String? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        check(maxOf(file.length(), File(file.path + ".bak").length()) <= 2L * 1024 * 1024) { "EDIT_TOO_LARGE" }
        val committed = File(file.path + ".bak").takeIf { it.exists() } ?: file
        return committed.readText()
    }
    private fun read(context: Context, uri: Uri): String? {
        val repo = repository(context)
        repo.read(uri)?.let { return it }
        recover(context)
        val key = stableKey(context, uri)
        val raw = readLegacy(File(directory(context), "$key.json"))
        return repo.adopt(uri, key, raw, if (raw == null) "" else name(context, uri))
    }
    private fun <T> checked(action: () -> T): T = try { action() } catch (error: Exception) {
        throw if (error is StorageException) error else StorageException(error)
    }

    @Synchronized
    fun load(context: Context, uri: Uri): EditorParams? = checked {
        read(context, uri)?.let { raw ->
            val json = JSONObject(raw)
            val defaults = EditorParams().toJson()
            for (key in json.keys()) if (defaults.has(key)) {
                val value = json.get(key)
                check(when (defaults.get(key)) {
                    is Number -> value is Number && value.toDouble().isFinite()
                    is Boolean -> value is Boolean
                    is String -> value is String
                    else -> false
                }) { "INVALID_EDIT_TYPE" }
            }
            for (version in listOf("schema_version", "gradeVersion", "grade_version", "colorPipelineVersion", "color_pipeline_version"))
                check(!json.has(version) || json.optDouble(version, Double.NaN) == 1.0) { "FUTURE_EDIT_READ_ONLY" }
            EditorParams.fromJson(json)
        }
    }

    @Synchronized
    fun save(context: Context, uri: Uri, params: EditorParams) {
        try {
            val current = read(context, uri) // Back up and claim legacy data before replacing parameters.
            val repo = repository(context)
            repo.save(uri, params.toJson().toString(), if (current == null) name(context, uri) else "")
        } catch (error: Exception) {
            android.util.Log.e("EditStore", "Saved edit write failed; prior record retained", error)
            failure.value = context.getString(com.spektralab.android.R.string.photos_storage_failure)
        }
    }

    @Synchronized
    fun loadForTransfer(context: Context, uri: Uri): StoredEdit = checked {
        val text = read(context, uri)
        StoredEdit(text?.let { EditorParams.fromJson(JSONObject(it)) } ?: EditorParams(), text)
    }

    @Synchronized
    fun saveIfAbsent(context: Context, uri: Uri, suggestion: EditorParams): EditorParams = checked {
        val stored = loadForTransfer(context, uri)
        if (stored.json != null) return@checked stored.params
        repository(context).save(uri, suggestion.toJson().toString(), name(context, uri))
        suggestion
    }

    @Synchronized
    fun saveBatch(context: Context, edits: List<BatchEdit>) = checked {
        val owned = EditorParams().toJson().keys().asSequence().toSet() +
            setOf("tone_curves", "color_adjustments", "channel_mixer", "color_wheels")
        val writes = edits.map { edit ->
            val current = read(context, edit.uri)
            check(current == edit.expectedJson) { "EDIT_TARGET_CHANGED" }
            val merged = current?.let(::JSONObject) ?: JSONObject()
            owned.forEach(merged::remove)
            val updated = JSONObject(edit.updatedJson)
            updated.keys().forEach { merged.put(it, updated.get(it)) }
            com.spektralab.android.data.library.SavedPhotoRepository.PendingWrite(edit.uri, current, merged.toString())
        }
        val names = writes.filter { it.expected == null }.associate { it.uri to name(context, it.uri) }
        repository(context).saveBatch(writes, names)
    }

    @Synchronized
    internal fun savedPhotos(context: Context, offset: Int = 0) = checked { repository(context).list(60, offset) }
    @Synchronized
    internal fun savedPhotoCount(context: Context) = checked { repository(context).count() }
    @Synchronized
    internal fun photoLibraryPage(context: Context, filter: com.spektralab.android.core.model.PhotoLibraryFilter, offset: Int) =
        checked { repository(context).page(filter, offset) }
    @Synchronized
    internal fun photoOrganization(context: Context, assetId: String) = checked { repository(context).organization.read(assetId) }
    @Synchronized
    internal fun savePhotoOrganization(context: Context, assetId: String, expected: com.spektralab.android.core.model.PhotoOrganization,
                                      updated: com.spektralab.android.core.model.PhotoOrganization) =
        checked { repository(context).organization.save(assetId, expected, updated) }
    @Synchronized
    internal fun photoAlbums(context: Context) = checked { repository(context).organization.albums() }
    @Synchronized
    internal fun createPhotoAlbum(context: Context, name: String) = checked { repository(context).organization.createAlbum(name) }
    @Synchronized
    internal fun renamePhotoAlbum(context: Context, id: String, name: String) = checked { repository(context).organization.renameAlbum(id, name) }
    @Synchronized
    internal fun deletePhotoAlbum(context: Context, id: String) = checked { repository(context).organization.deleteAlbum(id) }
    @Synchronized
    internal fun savedPhoto(context: Context, assetId: String) = checked { repository(context).find(assetId) }
    @Synchronized
    internal fun checkSavedSource(context: Context, photo: com.spektralab.android.data.library.SavedPhoto,
                                  source: com.spektralab.android.core.model.SourceEvidence) = checked {
        repository(context).checkSource(photo, source)
    }
    @Synchronized
    internal fun relinkSavedSource(context: Context, photo: com.spektralab.android.data.library.SavedPhoto,
                                   source: com.spektralab.android.core.model.SourceEvidence) = checked {
        repository(context).relink(photo, source)
    }
    @Synchronized
    internal fun savedSession(context: Context) = checked { repository(context).session() }
    @Synchronized
    internal fun saveSession(context: Context, session: com.spektralab.android.core.model.SavedEditSession) {
        try {
            repository(context).saveSession(session)
        } catch (error: Exception) {
            reportSessionFailure(context, error)
        }
    }
    @Synchronized
    internal fun saveSessionIfCurrent(context: Context, session: com.spektralab.android.core.model.SavedEditSession,
                                     isCurrent: () -> Boolean) {
        if (isCurrent()) saveSession(context, session)
    }
    internal fun reportSessionFailure(context: Context, error: Exception) {
        android.util.Log.e("EditStore", "Workbench save failed; prior session retained", error)
        failure.value = context.getString(if (error.message == "WORKBENCH_TOO_LARGE")
            com.spektralab.android.R.string.photos_session_capacity_error else com.spektralab.android.R.string.photos_session_save_error)
    }
    @Synchronized
    internal fun closeForTests(context: Context) {
        handles.remove(File(context.filesDir, "photo-library").canonicalPath)?.close()
    }

    private fun stableKey(context: Context, uri: Uri): String {
        val id = resolveMediaId(context, uri)
        return if (id != null && id > 0) "media-$id" else "uri-" + hash16(uri.toString())
    }

    /** Read-only adapter for explicit sandbox capture; does not recover or alter live records. */
    @Synchronized
    internal fun migrationKey(context: Context, uri: Uri): String = stableKey(context, uri)

    /** Explicit T06 sandbox entry; independent of the production saved-photo database. */
    @Synchronized
    internal fun captureMigrationCopy(context: Context, snapshots: File,
        bindings: List<com.spektralab.android.core.model.LegacySourceBinding>, session: List<String>, currentPosition: Int?) =
        com.spektralab.android.data.library.LegacyMigrationArchive.capture(directory(context), snapshots, bindings, session, currentPosition)

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
