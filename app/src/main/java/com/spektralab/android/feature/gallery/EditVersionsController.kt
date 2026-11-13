package com.spektralab.android.feature.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.R
import com.spektralab.android.core.model.EditVersion
import com.spektralab.android.data.library.SavedPhoto
import com.spektralab.android.data.local.EditStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EditVersionsController(private val context: Context, private val scope: CoroutineScope) {
    var photo by mutableStateOf<SavedPhoto?>(null); private set
    var versions by mutableStateOf<List<EditVersion>>(emptyList()); private set
    var pending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var visit = 0L

    fun open(photo: SavedPhoto) {
      if (pending) return
      val visit = ++this.visit
      action {
        val (current, versions) = withContext(Dispatchers.IO) {
            (EditStore.savedPhoto(context, photo.assetId) ?: error("EDIT_MISSING")) to EditStore.editVersions(context, photo.assetId)
        }
        if (visit == this.visit) { this.photo = current; this.versions = versions }
      }
    }
    fun close() { if (!pending) { photo = null; versions = emptyList(); error = null } }
    fun dismissError() { error = null }
    fun leave() { visit++; photo = null; versions = emptyList(); error = null }
    fun create(source: EditVersion, name: String, onDone: () -> Unit) = action {
        withContext(Dispatchers.IO) { EditStore.createEditVersion(context, source, name) }
        reload(); onDone()
    }
    fun rename(source: EditVersion, name: String, onDone: () -> Unit) = action {
        withContext(Dispatchers.IO) { EditStore.renameEditVersion(context, source, name) }
        reload(); onDone()
    }
    fun delete(source: EditVersion, inUse: () -> Boolean, onDone: () -> Unit) = action {
        check(!inUse()) { "EDIT_VERSION_IN_USE" }
        withContext(Dispatchers.IO) { EditStore.deleteEditVersion(context, source) }
        reload(); onDone()
    }
    private suspend fun reload() {
        val id = photo?.assetId ?: return
        versions = withContext(Dispatchers.IO) { EditStore.editVersions(context, id) }
    }
    private fun action(block: suspend () -> Unit) {
        if (pending) return
        pending = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = generateSequence<Throwable>(error) { it.cause }.last().message
                this@EditVersionsController.error = context.getString(when (reason) {
                    "EDIT_VERSION_IN_USE" -> R.string.versions_in_use
                    "INVALID_ALBUM_NAME" -> R.string.versions_invalid_name
                    "EDIT_VERSION_NAME_EXISTS" -> R.string.versions_duplicate_name
                    "EDIT_VERSION_LIMIT" -> R.string.versions_limit
                    "EDIT_VERSION_CHANGED", "EDIT_VERSION_MISSING", "EDIT_MISSING" -> R.string.versions_stale
                    else -> R.string.versions_error
                })
            } finally { pending = false }
        }
    }
}
