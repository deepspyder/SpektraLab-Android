package com.spektralab.android.feature.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.R
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.data.library.SavedPhoto
import com.spektralab.android.data.local.EditStore
import kotlinx.coroutines.*

/** Selection owns metadata snapshots, never decoded photos or an engine session. */
internal class PhotoSelectionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val canStart: () -> Boolean,
    private val refresh: () -> Unit,
) {
    var active by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Map<String, SavedPhoto>>(emptyMap())
        private set
    var pending by mutableStateOf(false)
        private set
    var confirming by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var committing = false

    fun begin(photo: SavedPhoto? = null) {
        if (pending || !canStart()) return
        active = true
        error = null
        message = null
        if (photo != null) selected = selected + (photo.assetId to photo)
    }

    fun toggle(photo: SavedPhoto) {
        if (pending || !canStart()) return
        if (!active) begin()
        selected = if (photo.assetId in selected) selected - photo.assetId else selected + (photo.assetId to photo)
    }

    fun clear() {
        if (pending) return
        selected = emptyMap()
        active = false
        confirming = false
        error = null
    }

    fun selectAll(filter: PhotoLibraryFilter) {
        if (pending || !canStart()) return
        active = true
        pending = true
        error = null
        job = scope.launch {
            try {
                val matches = withContext(Dispatchers.IO) { EditStore.selectablePhotos(context, filter) }
                selected = matches.associateBy { it.assetId }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val reason = generateSequence<Throwable>(failure) { it.cause }.last().message
                error = context.getString(if (reason == "PHOTO_SELECTION_TOO_LARGE")
                    R.string.photos_selection_limit else R.string.photos_selection_load_error)
            } finally { pending = false }
        }
    }

    fun requestRemoval() {
        if (!pending && canStart() && selected.isNotEmpty()) confirming = true
    }

    fun addToAlbum(albumId: String, albumName: String) {
        if (pending || !canStart() || selected.isEmpty()) return
        val expected = selected.values.map { it.assetId }
        pending = true; error = null
        job = scope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) { EditStore.addPhotosToAlbum(context, expected, albumId) }
                selected = emptyMap(); active = false
                message = context.getString(R.string.photos_added_to_album, albumName, expected.size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = context.getString(R.string.library_organization_error) }
            finally { pending = false; refresh() }
        }
    }

    fun dismissRemoval() { if (!pending) confirming = false }

    fun remove() {
        if (pending || !confirming || !canStart() || selected.isEmpty()) return
        val expected = selected.values.toList()
        pending = true
        committing = true
        error = null
        job = scope.launch {
            try {
                // Once accepted, let the one SQLite transaction finish even if the screen closes.
                withContext(NonCancellable + Dispatchers.IO) { EditStore.hidePhotos(context, expected) }
                selected = emptyMap()
                active = false
                message = context.getString(R.string.photos_removed, expected.size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                selected = emptyMap()
                error = context.getString(R.string.photos_remove_error)
            }
            finally {
                pending = false
                committing = false
                confirming = false
                refresh()
            }
        }
    }

    fun leave() {
        if (committing) return
        job?.cancel()
        job = null
        pending = false
        clear()
    }
}
