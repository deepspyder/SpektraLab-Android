package com.spektralab.android.feature.lighttable

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.*
import com.spektralab.android.R
import com.spektralab.android.core.model.LightTableProject
import com.spektralab.android.core.model.LightTableScene
import com.spektralab.android.core.model.TablePlacement
import com.spektralab.android.core.model.TableProjectSummary
import com.spektralab.android.core.model.TableSource
import com.spektralab.android.data.cache.LightTablePreviews
import com.spektralab.android.data.library.LightTableProjectStore
import com.spektralab.android.data.local.EditStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** UI owns temporary scene history; durable writes are explicit snapshots on IO. */
internal class LightTableProjectsController(private val context: Context, private val scope: CoroutineScope) {
    val canvas = FilmCanvasState()
    var current by mutableStateOf<LightTableProject?>(null); private set
    var projects by mutableStateOf<List<TableProjectSummary>>(emptyList()); private set
    var pending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var manager by mutableStateOf(false)
    var previews by mutableStateOf<Map<String, Bitmap>>(emptyMap()); private set
    var details by mutableStateOf<Map<String, Bitmap>>(emptyMap()); private set
    var sources by mutableStateOf<Map<String, TableSource>>(emptyMap()); private set
    var sourceErrors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var generation by mutableLongStateOf(0); private set
    private var visit = 0L
    private val reads = Semaphore(2)
    private val jobs = mutableMapOf<String, Job>()
    private var visible = emptySet<String>()
    private val previewStore get() = LightTablePreviews(File(context.cacheDir, "lighttable-previews"))
    private fun store() = LightTableProjectStore(File(context.filesDir, "lighttable-projects"))
    val dirty get() = current?.let { canvas.scene() != it.scene } ?: false

    fun dismissError() { error = null }
    fun discardChanges() { if (!pending) current?.let { canvas.restore(it.scene) } }
    fun showProjects() { manager = true; refresh() }
    fun refresh() = action { projects = withContext(Dispatchers.IO) { store().use { it.list() } } }
    fun open(summary: TableProjectSummary) = action {
        val project = withContext(Dispatchers.IO) { store().use { it.read(summary.id).also { row ->
            check(row.revision == summary.revision) { "TABLE_CHANGED" }
        } } }
        visit++; jobs.values.forEach(Job::cancel); jobs.clear(); previews = emptyMap(); details = emptyMap()
        current = project; canvas.restore(project.scene); manager = false; generation++
        resolveSources(project.scene.items)
    }
    fun close() {
        if (pending) return
        visit++; jobs.values.forEach(Job::cancel); jobs.clear()
        current = null; previews = emptyMap(); details = emptyMap(); sources = emptyMap(); sourceErrors = emptyMap(); message = null
    }

    fun captureDraft(photos: List<ViewingPhoto>, draft: FilmCanvasState): LightTableScene {
        val frames = photoWallLayout(photos, draft)
        return LightTableScene(frames.map { frame ->
            val photo = photos[frame.index]
            val point = draft.photoPositions[photo.key] ?: frame.bounds.topLeft
            TablePlacement(UUID.randomUUID().toString(), TableSource(photo.key, photo.name,
                pixelWidth = photo.sourceWidth, pixelHeight = photo.sourceHeight, format = photo.sourceFormat),
                point.x, point.y, frame.bounds.width, frame.bounds.height, frame.rotation)
        }, draft.zoom, draft.pan.x / draft.viewportFit, draft.pan.y / draft.viewportFit, draft.lightOn, draft.millimeters)
    }
    fun create(name: String, photos: List<ViewingPhoto>, draft: FilmCanvasState, onDone: () -> Unit = {}) {
        val scene = captureDraft(photos, draft)
        save(null, name, scene, photos.associateBy { it.key }, onDone) { saved ->
            current = saved; canvas.restore(saved.scene); manager = false; sources = emptyMap(); sourceErrors = emptyMap()
        }
    }

    fun saveCurrent(available: List<ViewingPhoto>, name: String? = null, onDone: () -> Unit = {}) {
        val expected = current ?: return
        save(expected, name ?: expected.name, canvas.scene(), available.associateBy { it.key }, onDone) { saved -> current = saved }
    }
    fun saveAs(name: String, available: List<ViewingPhoto>, onDone: () -> Unit) =
        save(null, name, canvas.scene(), available.associateBy { it.key }, onDone) { saved -> current = saved }

    private fun save(expected: LightTableProject?, name: String, scene: LightTableScene,
        available: Map<String, ViewingPhoto>, completion: () -> Unit, onStored: (LightTableProject) -> Unit) = action(completion) {
        val saved = withContext(Dispatchers.IO) {
            val captured = mutableMapOf<String, TableSource>()
            val items = scene.items.map { item ->
                val source = captured.getOrPut(item.source.uri) {
                    val live = available[item.source.uri]
                    if (live == null) item.source else {
                        val bitmap = live.wallDetail ?: live.detail ?: live.thumbnail
                        EditStore.tableSource(context, item.source).copy(preview = bitmap?.takeUnless { it.isRecycled }?.let(previewStore::put)
                            ?: item.source.preview)
                    }
                }
                item.copy(source = source)
            }
            store().use { it.save(expected, name, scene.copy(items = items)) }
        }
        // Updating metadata does not reset the current layout or its undo history.
        canvas.replaceSources(saved.scene.items.associate { it.id to it.source })
        onStored(saved); message = context.getString(R.string.table_saved)
        projects = withContext(Dispatchers.IO) { store().use { it.list() } }
    }

    fun rename(summary: TableProjectSummary, name: String, onDone: () -> Unit) = action(onDone) {
        val saved = withContext(Dispatchers.IO) { store().use { store ->
            val expected = store.read(summary.id)
            check(expected.revision == summary.revision) { "TABLE_CHANGED" }
            store.save(expected, name, expected.scene)
        } }
        if (current?.id == saved.id) current = saved
        projects = withContext(Dispatchers.IO) { store().use { it.list() } }
    }
    fun delete(summary: TableProjectSummary, onDone: () -> Unit) = action(onDone) {
        check(current?.id != summary.id) { "TABLE_IN_USE" }
        withContext(Dispatchers.IO) { store().use { store ->
            val expected = store.read(summary.id)
            check(expected.revision == summary.revision) { "TABLE_CHANGED" }; store.delete(expected)
        } }
        projects = withContext(Dispatchers.IO) { store().use { it.list() } }
    }

    fun add(photos: List<ViewingPhoto>) = action {
        val existing = canvas.scene()
        check(existing.items.size + photos.size <= 200) { "TABLE_CAPACITY" }
        val items = withContext(Dispatchers.IO) { photos.mapIndexed { index, photo ->
            val bitmap = photo.detail ?: photo.thumbnail
            val ratio = if (bitmap != null) bitmap.width.toFloat() / bitmap.height else
                if (photo.sourceWidth > 0 && photo.sourceHeight > 0) photo.sourceWidth.toFloat() / photo.sourceHeight else 1f
            val source = EditStore.tableSource(context, TableSource(photo.key, photo.name,
                pixelWidth = photo.sourceWidth, pixelHeight = photo.sourceHeight, format = photo.sourceFormat))
                .copy(preview = bitmap?.takeUnless { it.isRecycled }?.let(previewStore::put))
            TablePlacement(UUID.randomUUID().toString(), source, 20f + index % 3 * 180f,
                (existing.items.maxOfOrNull { it.y + it.height } ?: 0f) + 20f + index / 3 * 190f, 160f, 160f / ratio)
        } }
        canvas.add(items); resolveSources(canvas.scene().items)
    }

    private suspend fun resolveSources(items: List<TablePlacement>) {
        val resolved = withContext(Dispatchers.IO) { items.distinctBy { it.source.uri }.associate { item ->
            item.source.uri to runCatching { EditStore.resolveTableSource(context, item.source) }
        } }
        sources = resolved.mapNotNull { (uri, value) -> value.getOrNull()?.let { uri to it } }.toMap()
        sourceErrors = items.mapNotNull { item ->
            val value = resolved[item.source.uri]?.getOrNull()
            val reason = when {
                value == null -> R.string.table_source_missing
                value.editRevision != item.source.editRevision -> R.string.table_preview_stale
                else -> null
            }
            reason?.let { item.id to context.getString(it) }
        }.toMap()
    }

    fun visibleItems(ids: List<String>) {
        visible = canvas.members.filter { it.id in ids }.mapNotNull { it.source.preview }.toSet()
        previews = trim(LinkedHashMap(previews))
    }
    private fun trim(next: LinkedHashMap<String, Bitmap>): Map<String, Bitmap> {
        while (next.size > 16) next.keys.firstOrNull { it !in visible }?.let(next::remove) ?: break
        return next
    }
    fun requestPreview(key: String) {
        if (previews.containsKey(key) || jobs[key]?.isActive == true) return
        val token = visit
        jobs[key] = scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { reads.withPermit { previewStore.read(key) } }
                if (token == visit && bitmap != null) {
                    val next = LinkedHashMap(previews); next[key] = bitmap
                    // <=192px per image: visible rows plus sixteen warm entries, bounded by200items.
                    previews = trim(next)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing cache remains an explicit preview placeholder. */ }
            finally { if (jobs[key] === currentCoroutineContext()[Job]) jobs.remove(key) }
        }
    }
    fun requestDetail(key: String) {
        val request = "detail:$key"
        if (details.containsKey(key) || jobs[request]?.isActive == true) return
        val token = visit
        jobs[request] = scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { reads.withPermit { previewStore.read(key, 640) } }
                if (token == visit && bitmap != null) {
                    val next = LinkedHashMap(details); next[key] = bitmap
                    while (next.size > 4) next.remove(next.keys.first())
                    details = next
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the small valid preview when a disposable detail is absent. */ }
            finally { if (jobs[request] === currentCoroutineContext()[Job]) jobs.remove(request) }
        }
    }

    fun editItem(key: String, onOpen: (Uri) -> Unit) {
        var ready: Uri? = null
        action(onDone = { ready?.let(onOpen) }) {
            val source = canvas.members.first { it.id == key }.source
            ready = withContext(Dispatchers.IO) {
                val resolved = EditStore.resolveTableSource(context, source)
                Uri.parse(resolved.uri).also { uri ->
                    val readable = try {
                        context.contentResolver.openAssetFileDescriptor(EditStore.editSourceUri(context, uri), "r")?.use { true } ?: false
                    } catch (_: java.io.FileNotFoundException) { false }
                    catch (_: SecurityException) { false }
                    check(readable) { "TABLE_SOURCE_MISSING" }
                }
            }
        }
    }

    private fun action(onDone: () -> Unit = {}, block: suspend () -> Unit) {
        if (pending) return
        pending = true; error = null
        scope.launch {
            var completed = false
            try { block(); completed = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val reason = generateSequence<Throwable>(failure) { it.cause }.last().message
                error = context.getString(when (reason) {
                    "TABLE_NAME_EXISTS" -> R.string.table_duplicate_name
                    "INVALID_ALBUM_NAME" -> R.string.table_invalid_name
                    "TABLE_CHANGED", "TABLE_MISSING" -> R.string.table_changed
                    "TABLE_CAPACITY", "TABLE_PREVIEW_CAPACITY" -> R.string.table_capacity
                    "TABLE_FUTURE_VERSION" -> R.string.table_future
                    "TABLE_IN_USE" -> R.string.table_in_use
                    "TABLE_SOURCE_MISSING", "EDIT_MISSING", "EDIT_VERSION_MISSING" -> R.string.table_source_missing
                    else -> R.string.table_storage_error
                })
            } finally { pending = false }
            if (completed) onDone()
        }
    }
}
