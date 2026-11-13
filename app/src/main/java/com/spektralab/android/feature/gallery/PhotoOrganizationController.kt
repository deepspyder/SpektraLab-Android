package com.spektralab.android.feature.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.R
import com.spektralab.android.core.model.PhotoAlbum
import com.spektralab.android.core.model.PhotoLabel
import com.spektralab.android.core.model.PhotoOrganization
import com.spektralab.android.data.library.SavedPhoto
import com.spektralab.android.data.local.EditStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drafts stay local until Save. All catalog work uses IO; edits and engine are never touched. */
internal class PhotoOrganizationController(private val context: Context, private val scope: CoroutineScope, private val onChanged: () -> Unit) {
    var albums by mutableStateOf<List<PhotoAlbum>>(emptyList())
        internal set
    var target by mutableStateOf<SavedPhoto?>(null)
        private set
    var draft by mutableStateOf(PhotoOrganization())
        private set
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var albumManager by mutableStateOf(false)
        private set
    private var expected = PhotoOrganization()
    val hasChanges: Boolean get() = draft != expected

    fun open(photo: SavedPhoto) = action {
        val (marks, collections) = withContext(Dispatchers.IO) {
            EditStore.photoOrganization(context, photo.assetId) to EditStore.photoAlbums(context)
        }
        expected = marks; draft = marks; albums = collections; target = photo
    }

    fun rating(value: Int) { if (!pending) draft = draft.copy(rating = value) }
    fun label(value: PhotoLabel) { if (!pending) draft = draft.copy(label = value) }
    fun album(id: String, selected: Boolean) {
        if (!pending) draft = draft.copy(albums = if (selected) draft.albums + id else draft.albums - id)
    }
    fun close() { if (!pending) { target = null; albumManager = false; error = null } }
    fun dismissError() { error = null }

    fun save() {
        val photo = target ?: return
        val update = draft
        val before = expected
        action {
            withContext(Dispatchers.IO) { EditStore.savePhotoOrganization(context, photo.assetId, before, update) }
            target = null
            onChanged()
        }
    }

    fun manageAlbums() = action {
        albums = withContext(Dispatchers.IO) { EditStore.photoAlbums(context) }
        albumManager = true
    }
    fun createAlbum(name: String, onCreated: () -> Unit) = changeAlbum(onCreated) { EditStore.createPhotoAlbum(context, name) }
    fun renameAlbum(id: String, name: String, onSaved: () -> Unit) = changeAlbum(onSaved) { EditStore.renamePhotoAlbum(context, id, name) }
    fun deleteAlbum(id: String, onDeleted: () -> Unit) = changeAlbum(onDeleted) { EditStore.deletePhotoAlbum(context, id) }

    private fun changeAlbum(onSuccess: () -> Unit, mutation: () -> Unit) = action {
        albums = withContext(Dispatchers.IO) { mutation(); EditStore.photoAlbums(context) }
        onSuccess()
        onChanged()
    }

    private fun action(block: suspend () -> Unit) {
        if (pending) return
        pending = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = generateSequence<Throwable>(error) { it.cause }.last().message
                this@PhotoOrganizationController.error = context.getString(when (reason) {
                    "INVALID_ALBUM_NAME" -> R.string.library_album_invalid
                    "ALBUM_NAME_EXISTS" -> R.string.library_album_duplicate
                    "ALBUM_LIMIT_REACHED" -> R.string.library_album_limit
                    "ALBUM_MISSING", "ORGANIZATION_CHANGED", "EDIT_MISSING" -> R.string.library_organization_stale
                    else -> R.string.library_organization_error
                })
            } finally { pending = false }
        }
    }
}
