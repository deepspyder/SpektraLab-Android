package com.spektralab.android.feature.lighttable

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.core.content.res.ResourcesCompat
import com.spektralab.android.R
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.TableComposition
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import kotlinx.coroutines.*

/** Export choices and layout preview live independently of saved projects and photo export recipes. */
internal class TableOutputController(private val context: Context, private val scope: CoroutineScope) {
    var shown by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    var options by mutableStateOf(TableExportOptions()); private set
    var request by mutableStateOf<TableExportRequest?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var previewPending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<String?>(null); private set
    private var previewJob: Job? = null
    private var visit = 0L
    private var images = emptyMap<String, Bitmap>()

    fun open(scene: LightTableScene, viewport: TableBox?, fade: Float, title: String, images: Map<String, Bitmap>, bind: Boolean) {
        if (preparing) return
        visit++; val token = visit
        shown = true; preparing = true; preview = null; error = null; result = null
        options = TableExportOptions(title = title)
        this.images = images
        scope.launch {
            try {
                val frozen = LightTableScene.fromJson(scene.toJson())
                val captured = if (!bind) frozen else withContext(Dispatchers.IO) {
                    val sources = frozen.items.map { it.source }.distinctBy { it.uri }.associate { it.uri to EditStore.tableSource(context, it) }
                    frozen.copy(items = frozen.items.map { it.copy(source = sources.getValue(it.source.uri)) })
                }
                if (token == visit) { request = TableExportRequest(captured, options, viewport, fade); update(options) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == visit) error = describe(failure) }
            finally { if (token == visit) preparing = false }
        }
    }
    fun close() {
        visit++; previewJob?.cancel(); shown = false; request = null; preview = null
        images = emptyMap(); error = null; result = null; preparing = false; previewPending = false
    }
    fun update(value: TableExportOptions) {
        options = value; error = null; result = null
        val source = request ?: return
        request = source.copy(options = value)
        previewJob?.cancel(); previewPending = true
        val token = visit
        previewJob = scope.launch {
            var made: Bitmap? = null
            try {
                delay(160)
                val planned = request!!.plan()
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    val factor = minOf(480f / planned.width, 480f / planned.height)
                    val small = planned.copy(width = (planned.width * factor).toInt().coerceAtLeast(1), height = (planned.height * factor).toInt().coerceAtLeast(1),
                        scale = planned.scale * factor, offsetX = planned.offsetX * factor, offsetY = planned.offsetY * factor)
                    made = TableComposition.render(small, requireNotNull(ResourcesCompat.getFont(context, R.font.resource_han_rounded_regular)),
                        { job.ensureActive() }) { images[it.id] }
                }
                if (token == visit && isActive) { preview = made; made = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == visit) error = describe(failure) }
            finally { made?.recycle(); if (previewJob === currentCoroutineContext()[Job]) previewPending = false }
        }
    }
    fun submit(vm: EditorViewModel) {
        if (preparing || vm.isExporting) return
        val captured = request ?: return
        error = null; result = null
        val started = vm.exportTableProject(captured) { outcome ->
            outcome.fold(onSuccess = { result = it }, onFailure = {
                android.util.Log.w("TableOutput", "Project output did not commit", it)
                error = describe(it)
            })
        }
        if (!started) error = context.getString(R.string.table_output_busy)
    }

    private fun describe(failure: Throwable): String {
        val reasons = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toSet()
        val resource = when {
            failure is OutOfMemoryError || "TABLE_EXPORT_CAPACITY" in reasons -> R.string.table_output_capacity
            "TABLE_EXPORT_STALE" in reasons -> R.string.table_output_stale
            "TABLE_EXPORT_CHANGED" in reasons || "SOURCE_CONTENT_CHANGED" in reasons || "SOURCE_RELINKED" in reasons -> R.string.table_output_changed
            "TABLE_EXPORT_SOURCE" in reasons || "EDIT_VERSION_MISSING" in reasons || "EDIT_MISSING" in reasons -> R.string.table_source_missing
            "TABLE_EXPORT_DETAIL" in reasons -> R.string.table_output_detail
            "TABLE_EXPORT_DISK" in reasons -> R.string.table_output_disk
            "TABLE_EXPORT_EMPTY" in reasons -> R.string.table_output_empty
            "TABLE_EXPORT_SPAN" in reasons -> R.string.table_output_span
            "TABLE_EXPORT_RAW_UNEDITED" in reasons -> R.string.table_output_raw_unedited
            "TABLE_EXPORT_PRECISION" in reasons -> R.string.table_output_precision
            "TABLE_EXPORT_UNSUPPORTED" in reasons -> R.string.table_output_unsupported
            "TABLE_EXPORT_TITLE" in reasons -> R.string.table_output_title_error
            failure.javaClass.simpleName == "ExportCancelled" || reasons.any { it.contains("cancel", true) } -> R.string.table_output_cancelled
            else -> R.string.table_output_failure
        }
        return context.getString(resource)
    }
}
