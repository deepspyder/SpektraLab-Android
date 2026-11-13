package com.spektralab.android.feature.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.R
import com.spektralab.android.data.library.SavedPhoto
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.library.SourceProbe
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.core.model.SourceEvidence
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Metadata loads and at most two bounded thumbnail reads; never starts the film engine. */
internal class SavedPhotosController(private val context: Context, private val scope: CoroutineScope) {
    var photos by mutableStateOf<List<SavedPhoto>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sourceErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var thumbnails by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set
    var savedSession by mutableStateOf<SavedEditSession?>(null)
        private set
    var sessionError by mutableStateOf<String?>(null)
        private set
    var sourceMessage by mutableStateOf<String?>(null)
        private set
    var checkingAsset by mutableStateOf<String?>(null)
        private set
    var committingSource by mutableStateOf(false)
        private set
    var pendingRelink: SavedPhoto? = null
        private set
    private var sourceJob: Job? = null
    private val reads = Semaphore(2)
    private var load: Job? = null
    private var opening = false
    private val thumbJobs = mutableMapOf<String, Job>()
    private val visibleThumbnails = mutableSetOf<String>()

    fun refresh() = fetch(false)
    fun more() { if (hasMore && !loading) fetch(true) }
    private fun fetch(append: Boolean) {
        if (sourceJob?.isActive == true || opening) return
        load?.cancel()
        loading = true; error = null
        val offset = if (append) photos.size else 0
        load = scope.launch {
            try {
                val (page, session) = withContext(Dispatchers.IO) {
                    EditStore.savedPhotos(context, offset) to runCatching { EditStore.savedSession(context) }
                }
                photos = if (append) (photos + page).distinctBy { it.assetId } else page
                savedSession = session.getOrNull()
                sessionError = if (session.isFailure) context.getString(R.string.photos_session_load_error) else null
                hasMore = page.size == 60
                sourceErrors = emptyMap()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = context.getString(R.string.photos_load_error) }
            finally { if (load === currentCoroutineContext()[Job]) loading = false }
        }
    }

    fun thumbnail(photo: SavedPhoto) {
        visibleThumbnails += photo.assetId
        if (thumbnails.containsKey(photo.assetId) || thumbJobs[photo.assetId]?.isActive == true) return
        thumbJobs[photo.assetId] = scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { reads.withPermit { decode(Uri.parse(photo.uri)) } }
                if (bitmap != null) {
                    val next = LinkedHashMap(thumbnails)
                    next[photo.assetId] = bitmap
                    // Detached bitmaps may still belong to a Compose draw; let GC own their release.
                    thumbnails = trimThumbnails(next)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Opening a row reports actionable permission/missing errors. */ }
            finally { if (thumbJobs[photo.assetId] === currentCoroutineContext()[Job]) thumbJobs.remove(photo.assetId) }
        }
    }
    fun cancelThumbnail(id: String) {
        visibleThumbnails -= id
        thumbJobs.remove(id)?.cancel()
        thumbnails = trimThumbnails(LinkedHashMap(thumbnails))
    }

    private fun trimThumbnails(next: LinkedHashMap<String, Bitmap>): Map<String, Bitmap> {
        // A tablet can compose more than twelve rows. Never evict an on-screen bitmap:
        // its row's effect would otherwise keep showing a placeholder until recomposed.
        while (next.size > 12) {
            val retired = next.keys.firstOrNull { it !in visibleThumbnails } ?: break
            next.remove(retired)
        }
        return next
    }

    fun open(photo: SavedPhoto, onReady: (Uri) -> Unit) {
        if (loading) return
        load?.cancel()
        loading = true
        opening = true
        load = scope.launch {
            try {
                val uri = Uri.parse(photo.uri)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.close() ?: error("SOURCE_MISSING")
                    val latest = EditStore.savedPhoto(context, photo.assetId) ?: error("EDIT_MISSING")
                    check(latest.uri == photo.uri) { "SOURCE_RELINKED" }
                    if (latest.sourceSha256 != null) {
                        val job = currentCoroutineContext()
                        val source = SourceProbe.inspect(context, uri) { job.ensureActive() }
                        job.ensureActive()
                        EditStore.checkSavedSource(context, latest, source)
                    }
                    EditStore.load(context, uri) ?: error("EDIT_MISSING")
                }
                sourceErrors = sourceErrors - photo.assetId
                onReady(uri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { sourceErrors = sourceErrors + (photo.assetId to sourceFailure(error)) }
            finally {
                if (load === currentCoroutineContext()[Job]) {
                    opening = false
                    loading = false
                }
            }
        }
    }

    fun prepareRelink(photo: SavedPhoto) {
        if (!loading) pendingRelink = photo
    }

    fun cancelRelinkPicker() { pendingRelink = null }

    fun leave() {
        val abandoned = load
        load = null
        opening = false
        abandoned?.cancel()
        if (checkingAsset == null) loading = false
        cancelSourceCheck()
    }

    fun cancelSourceCheck() {
        if (committingSource) return
        val abandoned = sourceJob
        sourceJob = null
        checkingAsset = null
        if (abandoned != null) loading = false
        abandoned?.cancel()
    }

    fun checkSource(photo: SavedPhoto) = sourceOperation(photo) {
        val evidence = inspect(Uri.parse(photo.uri))
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            committingSource = true
            withContext(Dispatchers.IO) { EditStore.checkSavedSource(context, photo, evidence) }
            replaceRow(photo.assetId)
            sourceMessage = context.getString(R.string.photos_source_checked)
        }
    }

    fun relink(uri: Uri, onRelink: (Uri, Uri) -> Unit, persistentAccess: Boolean = true) {
        val photo = pendingRelink ?: run {
            error = context.getString(R.string.photos_source_selection_expired)
            return
        }
        pendingRelink = null
        if (sourceJob?.isActive == true || opening) return
        // A document picker can resume the screen and start a refresh before its result arrives.
        // The explicit selection owns this operation; do not silently drop it behind that refresh.
        load?.cancel()
        loading = false
        sourceOperation(photo) {
            val evidence = inspect(uri)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                committingSource = true
                withContext(Dispatchers.IO) { EditStore.relinkSavedSource(context, photo, evidence) }
                onRelink(Uri.parse(photo.uri), uri)
                cancelThumbnail(photo.assetId)
                thumbnails = thumbnails - photo.assetId
                replaceRow(photo.assetId)
                photos.firstOrNull { it.assetId == photo.assetId }?.let(::thumbnail)
                savedSession = withContext(Dispatchers.IO) { EditStore.savedSession(context) }
                sourceMessage = context.getString(if (persistentAccess) R.string.photos_source_relinked else R.string.photos_source_temporary)
            }
        }
    }

    fun restoreSession(onRestore: (SavedEditSession) -> Unit) {
        if (loading) return
        load?.cancel()
        loading = true
        load = scope.launch {
            try {
                val session = withContext(Dispatchers.IO) { EditStore.savedSession(context) }
                savedSession = session
                if (session != null && session.photos.isNotEmpty()) onRestore(session)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = context.getString(R.string.photos_session_load_error) }
            finally { if (load === currentCoroutineContext()[Job]) loading = false }
        }
    }

    private fun sourceOperation(photo: SavedPhoto, action: suspend () -> Unit) {
        if (loading) return
        load?.cancel()
        loading = true
        checkingAsset = photo.assetId
        sourceMessage = null
        sourceJob = scope.launch {
            try {
                action()
                sourceErrors = sourceErrors - photo.assetId
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { sourceErrors = sourceErrors + (photo.assetId to sourceFailure(error)) }
            finally {
                if (sourceJob === currentCoroutineContext()[Job]) {
                    loading = false
                    checkingAsset = null
                    committingSource = false
                }
            }
        }
    }

    private suspend fun inspect(uri: Uri): SourceEvidence = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        SourceProbe.inspect(context, uri) { job.ensureActive() }
    }

    private suspend fun replaceRow(assetId: String) {
        val row = withContext(Dispatchers.IO) { EditStore.savedPhoto(context, assetId) } ?: error("EDIT_MISSING")
        photos = photos.map { if (it.assetId == assetId) row else it }
    }

    private fun sourceFailure(error: Exception): String {
        val reason = generateSequence<Throwable>(error) { it.cause }.last().message
        return context.getString(when (reason) {
            "SOURCE_CONTENT_CHANGED" -> R.string.photos_source_changed
            "SOURCE_BASELINE_MISSING" -> R.string.photos_source_baseline_missing
            "SOURCE_ALREADY_REGISTERED" -> R.string.photos_source_conflict
            "EDIT_TARGET_CHANGED", "SOURCE_RELINKED" -> R.string.photos_source_stale
            else -> R.string.photos_source_error
        })
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 384) inSampleSize *= 2
            }
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        // RAW embedded thumbnail only; a gallery scroll never demosaics an original RAW.
        val embedded = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).thumbnail } ?: return null
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(embedded, 0, embedded.size, header)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(header.outWidth, header.outHeight) / inSampleSize > 384) inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(embedded, 0, embedded.size, options)
    }
}
