package com.spektralab.android.feature.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spektralab.android.core.color.DisplayP3Icc
import com.spektralab.android.core.develop.SmartDevelop
import com.spektralab.android.core.develop.SmartDevelopStyle
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditGroup
import com.spektralab.android.core.model.EditTemplate
import com.spektralab.android.core.model.EditTransferProblem
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.R
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportRecipe
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.export.ExportMetadata
import com.spektralab.android.data.export.ExportPublisher
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.export.measureExport
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.local.RecipeStore
import com.spektralab.android.engine.CameraWhiteBalance
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.EngineTaskQueue
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Histogram
import com.spektralab.android.engine.LensProfile
import com.spektralab.android.engine.LensProfileStore
import com.spektralab.android.engine.RenderAdmission
import com.spektralab.android.engine.Rgba16Source
import com.spektralab.android.engine.NativeRenderResult
import com.spektralab.android.engine.RawDecodeCancellation
import com.spektralab.android.engine.RawDecodeResult
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.SpkException
import com.spektralab.android.engine.SpkResult
import com.spektralab.android.engine.Stock
import com.spektralab.android.engine.StockCatalog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 编辑器状态与引擎句柄的宿主。用 [AndroidViewModel] 承载，跨配置变更（旋转屏幕）存活：
 * - 引擎句柄只在 onCleared() 释放，旋转不会泄漏 native 句柄、也不会重复创建；
 * - 参数/渲染结果/源图都留在这里，旋转后直接复用，不重新解码。
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private class ExportCancelled : Exception()
    @Volatile private var exportCancelRequested = false
    @Volatile private var tableNativeRendering = false

    private fun checkExportCancellation() {
        if (exportCancelRequested || cleared) throw ExportCancelled()
    }

    private data class PendingFrame(
        val pixels: FloatArray,
        val width: Int,
        val height: Int,
        val inputColorSpace: String,
        val photo: Long,
        val revision: Long,
        val edit: EditSnapshot,
    )

    private val app get() = getApplication<Application>()

    internal val savedPhotos by lazy { com.spektralab.android.feature.gallery.SavedPhotosController(app, viewModelScope) }
    internal val lightTableProjects by lazy { com.spektralab.android.feature.lighttable.LightTableProjectsController(app, viewModelScope) }
    internal val tableOutput by lazy { com.spektralab.android.feature.lighttable.TableOutputController(app, viewModelScope) }
    internal fun prepareVersionBoundary() {
        if (!busy && !isExporting && !isBatchExporting && !isApplyingEdits) saveCurrentSidecar()
    }
    private val sessionPersistence by lazy { EditSessionPersistence(app, viewModelScope) }
    private var workbenchChanged = false

    private fun persistWorkbench() {
        workbenchChanged = true
        sessionPersistence.save(photos.map(Uri::toString),
            photos.getOrNull(currentIndex)?.toString(), batchExportSelection.mapTo(linkedSetOf(), Uri::toString))
    }

    internal fun restoreWorkbench(session: SavedEditSession) {
        if (busy || isExporting || isBatchExporting || isApplyingEdits || session.photos.isEmpty()) return
        saveCurrentSidecar()
        photos = session.photos.map(Uri::parse)
        currentIndex = session.photos.indexOf(session.current).coerceAtLeast(0)
        batchExportSelection = session.selected.mapTo(linkedSetOf(), Uri::parse)
        retainThumbnailRequests(photos.toSet())
        thumbnails = thumbnails.filterKeys { it in photos }
        developedPreviews = developedPreviews.filterKeys { it in photos }
        galleryPreviews = galleryPreviews.filterKeys { it in photos }
        openPhoto(photos[currentIndex])
        persistWorkbench()
    }

    /** Relocation has already proved byte equality. Keep the in-memory edit and its history. */
    internal fun relinkWorkbenchSource(previous: Uri, replacement: Uri) {
        if (previous == replacement) return
        photos = photos.map { if (it == previous) replacement else it }.distinct()
        batchExportSelection = batchExportSelection.mapTo(linkedSetOf()) { if (it == previous) replacement else it }
        undoHistory.remove(previous.toString())?.let { undoHistory[replacement.toString()] = it }
        redoHistory.remove(previous.toString())?.let { redoHistory[replacement.toString()] = it }
        if (currentUri == previous) currentUri = replacement
        if (paramsUri == previous) paramsUri = replacement
        currentIndex = currentUri?.let(photos::indexOf)?.coerceAtLeast(0) ?: 0
        retainThumbnailRequests(photos.toSet())
        thumbnails = thumbnails - previous
        developedPreviews = developedPreviews - previous
        discardGalleryPreview(previous)
        lastHistoryKey = null
        refreshHistoryButtons()
        // The transaction already remapped the durable workbench. Avoid writing an empty,
        // unrelated in-memory session over it when relinking a historical library entry.
        if (photos.isNotEmpty()) persistWorkbench()
    }
    internal fun retrySavedEdit() {
        if (busy || isExporting || isBatchExporting || isApplyingEdits) return
        EditStore.dismissStorageError()
        currentUri?.takeIf { it == paramsUri }?.let { EditStore.save(app, it, committedParams()) }
        if (workbenchChanged) persistWorkbench()
    }
    internal val editPresets by lazy { EditPresetController(app, viewModelScope) }
    internal var isApplyingEdits by mutableStateOf(false)
        private set
    internal var editTransferError by mutableStateOf<String?>(null)
        private set
    internal var editTransferRevision by mutableStateOf(0)
        private set
    private var previewWorkRunning by mutableStateOf(false)
    internal val canTransferEdits: Boolean get() = hasPhoto && !busy && !isExporting && !isBatchExporting &&
        !isApplyingEdits && !isCropping && !previewWorkRunning

    var params by mutableStateOf(EditorParams())
        private set
    var engine by mutableStateOf<EngineClient?>(null)
        private set
    var rendered by mutableStateOf<Bitmap?>(null)
    var lastResult by mutableStateOf<SpkResult?>(null)
    var histogram by mutableStateOf<Histogram?>(null)
        private set
    var sourceBitmap by mutableStateOf<Bitmap?>(null)
    var films by mutableStateOf<List<Stock>>(emptyList())
        private set
    var papers by mutableStateOf<List<Stock>>(emptyList())
        private set
    var hasPhoto by mutableStateOf(false)
        private set
    var isCropping by mutableStateOf(false)
    var cropAspect by mutableStateOf("原始")
    var isExporting by mutableStateOf(false)
        private set
    var isExportCommitCompleted by mutableStateOf(false)
        private set
    var exportFormat by mutableStateOf("JPEG")
    var exportRes by mutableStateOf(2560)
    var exportColorSpace by mutableStateOf("sRGB")
    var jpegQuality by mutableStateOf(95)
    private val exportPreferences = app.getSharedPreferences("export_metadata", android.content.Context.MODE_PRIVATE)
    private var keepCaptureState by mutableStateOf(exportPreferences.getBoolean("capture", true))
    private var keepLocationState by mutableStateOf(exportPreferences.getBoolean("location", false))
    var exportKeepCapture: Boolean
        get() = keepCaptureState
        set(value) { keepCaptureState = value; exportPreferences.edit().putBoolean("capture", value).apply() }
    var exportKeepLocation: Boolean
        get() = keepLocationState
        set(value) { keepLocationState = value; exportPreferences.edit().putBoolean("location", value).apply() }
    var recipes by mutableStateOf(RecipeStore.load(app))
        private set
    private val smartPreferences = app.getSharedPreferences("smart_develop", android.content.Context.MODE_PRIVATE)
    private var smartAutoState by mutableStateOf(smartPreferences.getBoolean("automatic", true))
    private var smartStyleState by mutableStateOf(SmartDevelopStyle.from(smartPreferences.getString("style", "cinema").orEmpty()))
    internal var smartAutoEnabled: Boolean
        get() = smartAutoState
        set(value) {
            smartAutoState=value;smartPreferences.edit().putBoolean("automatic",value).apply()
            if(!value){smartQueueGeneration++;smartQueueJob?.cancel();smartImportRunning=false}
        }
    internal var smartStyle: SmartDevelopStyle
        get() = smartStyleState
        set(value) { smartStyleState=value;smartPreferences.edit().putString("style",value.id).apply() }
    var smartImportRunning by mutableStateOf(false); private set
    var smartImportCompleted by mutableStateOf(0); private set
    var smartImportTotal by mutableStateOf(0); private set
    var smartMessage by mutableStateOf(""); private set
    private var smartQueueJob: Job? = null
    private var smartApplyJob: Job? = null
    private var smartQueueGeneration = 0L
    private val smartAnalysisMutex = kotlinx.coroutines.sync.Mutex()
    private val freshlySmartDeveloped = mutableSetOf<Uri>()
    var log by mutableStateOf("（未创建引擎）\n")
        private set
    var currentUri by mutableStateOf<Uri?>(null)
        private set
    var photoInfo by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var photos by mutableStateOf<List<Uri>>(emptyList())
        private set
    var batchExportSelection by mutableStateOf<Set<Uri>>(emptySet())
        private set
    val selectedBatchExportPhotos: List<Uri>
        get() = photos.filter { it in batchExportSelection }
    var currentIndex by mutableStateOf(0)
        private set
    var thumbnails by mutableStateOf<Map<Uri, Bitmap>>(emptyMap())
        private set
    /** Small, already-developed previews used by the viewing stand. */
    var developedPreviews by mutableStateOf<Map<Uri, Bitmap>>(emptyMap())
        private set
    /** Bounded, decoded previews for the Gallery's immersive viewer. */
    var galleryPreviews by mutableStateOf<Map<Uri, Bitmap>>(emptyMap())
        private set
    var galleryPreviewErrors by mutableStateOf<Map<Uri, String>>(emptyMap())
        private set
    var viewingPhotos by mutableStateOf<List<Pair<String, Bitmap>>>(emptyList())
        private set
    private var viewingImportRequests by mutableStateOf(0)
    private val viewingImportMutex = kotlinx.coroutines.sync.Mutex()
    val isImportingViewingPhotos: Boolean get() = viewingImportRequests > 0
    var viewingImportCompleted by mutableStateOf(0)
        private set
    var viewingImportTotal by mutableStateOf(0)
        private set
    var isBatchExporting by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var renderProgress by mutableStateOf<Float?>(null)   // 0..100，null = 无进行中渲染
        private set
    var renderStage by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // 场景宽容度测量结果（P0-3）
    var latitudeReadout by mutableStateOf<String?>(null)
        private set
    var latitudeCanApply by mutableStateOf(false)
        private set
    private var latitudeDelta: JSONObject? = null

    private var previewLinear: FloatArray? = null
    private var previewW = 0
    private var previewH = 0
    private var previewInputColorSpace = "sRGB"
    private var resultColorSpace = "sRGB"
    var currentIsRaw by mutableStateOf(false)
        private set
    var lensCorrectionActive by mutableStateOf(true)
        private set
    var matchedLens by mutableStateOf<String?>(null)
        private set
    private var matchedLensProfile: LensProfile? = null
    private var sourceExif: ExifInterface? = null
    private var rawCamXyz: FloatArray? = null
    private val undoHistory = mutableMapOf<String, MutableList<String>>()
    private val redoHistory = mutableMapOf<String, MutableList<String>>()
    private var suppressHistory = false
    private var lastHistoryKey: String? = null
    private var lastHistoryEditMs = 0L
    private var renderJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }
    private var photoLoadJob: Job? = null
    private var frameRenderJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }
    private var stockPreviewJob: Job? = null
    private var developedPreviewJob: Job? = null
    private val thumbnailJobs = mutableMapOf<Uri, Job>()
    private val galleryPreviewJobs = mutableMapOf<Uri, Job>()
    private val galleryPreviewDecodeMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var photoGeneration = 0L
    private var paramsUri: Uri? = null
    @Volatile private var cleared = false
    private var wbJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }
    private data class PendingPreview(
        val result: SpkResult,
        val colorSpace: String,
        val photo: Long,
        val revision: Long,
        val renderRequest: Long? = null,
    )
    private var pendingPreview: PendingPreview? = null
    private var gradeGeneration = 0L
    private var gradeRequest = 0L
    private var renderRequest = 0L
    private var publishedRenderRequest = 0L
    @Volatile private var renderGeneration = 0L
    private var cameraWbJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }

    /** Job completion must notify Compose even when no new bitmap/state follows it. */
    private fun trackPreviewWork(job: Job?) {
        fun refresh() {
            previewWorkRunning = renderJob?.isActive == true || frameRenderJob?.isActive == true ||
                wbJob?.isActive == true || cameraWbJob?.isActive == true
        }
        refresh()
        job?.invokeOnCompletion { viewModelScope.launch(Dispatchers.Main.immediate) { refresh() } }
    }
    private var engineCreateJob: Job? = null
    private var pendingFrame: PendingFrame? = null
    private var cropEditStart: FloatArray? = null
    // One engine/session owns mutable native state. Decode/open/render/export transactions must not overlap.
    private val engineTasks = EngineTaskQueue()
    private val renderCache by lazy { ExportRenderCache.get(app.applicationContext) }
    internal val exportCacheStats get() = renderCache.stats
    internal val renderTaskState get() = engineTasks.snapshot.value
    private val engineDispatcher = engineTasks.dispatcher

    init {
        viewModelScope.launch {
            val (f, p) = StockCatalog.load(app)
            films = f
            papers = p
        }
        createEngine()
    }

    fun append(s: String) {
        val line = (if (s.length > 2048) s.take(2047) + "…" else s) + "\n"
        log = (log + line).takeLast(32 * 1024)
    }

    fun dismissError() { errorMessage = null }

    // 日期后背用的当前日期（lcd 风格：'YY M D，月/日无前导零）。
    fun currentDateText(): String {
        val cal = java.util.Calendar.getInstance()
        return "'" + (cal.get(java.util.Calendar.YEAR) % 100) + " " +
               (cal.get(java.util.Calendar.MONTH) + 1) + " " +
               cal.get(java.util.Calendar.DAY_OF_MONTH)
    }

    // 错误：弹窗提示 + 记日志。
    private fun fail(msg: String) {
        errorMessage = msg
        append(msg)
    }

    // 轻提示（导出完成等），不打断操作。
    private fun toast(msg: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 当前打开照片的源图尺寸（供裁剪预设按宽高比计算居中裁剪）。
    fun previewWidth(): Int = previewW
    fun previewHeight(): Int = previewH

    fun restoreUri(uriString: String?) {
        if (!uriString.isNullOrEmpty()) currentUri = Uri.parse(uriString)
    }

    fun persistReadPermission(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Providers that do not offer persistable grants can still be used in this session.
        }
    }

    // 重置全部编辑参数到默认（保留当前照片）。
    fun resetAll() {
        if (isApplyingEdits) return
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        params = EditorParams()
        cropAspect = "原始"
        scheduleRender()
    }

    private fun historyKey(uri: Uri? = currentUri): String = uri?.toString() ?: "session"
    /** A live gesture is preview-only until release, even if another operation saves. */
    private fun committedParams(): EditorParams {
        if (curveStart == null && colorStart == null && mixerStart == null && wheelStart == null) return params
        return params.copy().apply {
            curveStart?.let {
                toneCurves = it
                unreadableToneCurves = curveStartUnreadable
            }
            colorStart?.let {
                colorAdjustments = it
                unreadableColorAdjustments = colorStartUnreadable
            }
            wheelStart?.let {
                colorWheels = it
                unreadableColorWheels = wheelStartUnreadable
            }
            mixerStart?.let {
                channelMixer = it
                unreadableChannelMixer = mixerStartUnreadable
            }
        }
    }

    private fun historySnapshot(): String = committedParams().toJson().toString()

    private fun refreshHistoryButtons() {
        val key = historyKey()
        canUndo = (undoHistory[key]?.size ?: 0) > 1
        canRedo = !redoHistory[key].isNullOrEmpty()
    }

    private fun recordHistory() {
        if (suppressHistory) return
        val key = historyKey()
        val snapshot = historySnapshot()
        val history = undoHistory.getOrPut(key) { mutableListOf(snapshot) }
        if (history.lastOrNull() == snapshot) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastHistoryKey == key && now - lastHistoryEditMs < 350 && history.size > 1) {
            history[history.lastIndex] = snapshot
            redoHistory[key]?.clear()
            lastHistoryEditMs = now
            refreshHistoryButtons()
            return
        }
        history += snapshot
        if (history.size > 64) history.removeAt(0)
        redoHistory[key]?.clear()
        lastHistoryKey = key
        lastHistoryEditMs = now
        refreshHistoryButtons()
    }

    private fun seedHistory(uri: Uri, initial: EditorParams) {
        val key = historyKey(uri)
        val snapshot = initial.toJson().toString()
        if (undoHistory[key]?.lastOrNull() != snapshot) {
            undoHistory[key] = if (freshlySmartDeveloped.remove(uri))
                mutableListOf(EditorParams().toJson().toString(), snapshot) else mutableListOf(snapshot)
            redoHistory.remove(key)
        }
        lastHistoryKey = null
        refreshHistoryButtons()
    }

    fun undo() = restoreHistory(redo = false)
    fun redo() = restoreHistory(redo = true)

    private fun restoreHistory(redo: Boolean) {
        if (isApplyingEdits) return
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        val key = historyKey()
        val undo = undoHistory[key] ?: return
        val redoStack = redoHistory.getOrPut(key) { mutableListOf() }
        if (redo && redoStack.isEmpty()) return
        if (!redo && undo.size <= 1) return
        val previous = params.copy()
        val current = historySnapshot()
        if (redo) {
            val restored = redoStack.removeAt(redoStack.lastIndex)
            undo += restored
            params = EditorParams.fromJson(JSONObject(restored))
        } else {
            redoStack += current
            undo.removeAt(undo.lastIndex)
            params = EditorParams.fromJson(JSONObject(undo.last()))
        }
        if (previous.cropX != params.cropX || previous.cropY != params.cropY ||
            previous.cropW != params.cropW || previous.cropH != params.cropH) {
            cropAspect = if (params.cropW >= 1f && params.cropH >= 1f) "原始" else "自定义"
        }
        currentUri?.let { EditStore.save(app, it, committedParams()) }
        refreshHistoryButtons()
        lastHistoryKey = null
        suppressHistory = true
        if (currentIsRaw && currentUri != null &&
            (previous.cameraWbCustom != params.cameraWbCustom || previous.cameraWbKelvin != params.cameraWbKelvin || previous.cameraWbTint != params.cameraWbTint)) {
            scheduleCameraWb()
        }
        if (previous.deltaJson(previewW, previewH) == params.deltaJson(previewW, previewH) &&
            previous.cameraWbCustom == params.cameraWbCustom && previous.cameraWbKelvin == params.cameraWbKelvin &&
            previous.cameraWbTint == params.cameraWbTint) scheduleGrade()
        else scheduleRender()
        suppressHistory = false
    }

    fun syncSettingsToOtherPhotos() {
        if (isApplyingEdits) return
        val uri = currentUri ?: return
        val source = committedParams().copy()
        var count = 0
        photos.filter { it != uri }.forEach { targetUri ->
            val target = EditStore.load(app, targetUri) ?: EditorParams()
            target.copySettingsFrom(source)
            EditStore.save(app, targetUri, target)
            seedHistory(targetUri, target)
            count++
        }
        toast("已同步到 $count 张照片（保留各自裁剪）")
    }

    internal fun captureEditTemplate(groups: Set<EditGroup>): EditTemplate = EditTemplate.capture(committedParams(), groups)

    internal suspend fun previewEditTransfer(template: EditTemplate, targets: List<Uri>): EditTransferPreview {
        editTransferError = null
        val allowed = photos.toSet()
        val selected = currentUri
        val current = EditSnapshot.capture(committedParams())
        val catalog = films.toList() to papers.toList()
        val rows = withContext(Dispatchers.IO) {
            targets.distinct().filter { it in allowed }.map { uri ->
                val name = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
                try {
                    app.contentResolver.openAssetFileDescriptor(uri, "r")?.close()
                        ?: error("SOURCE_UNAVAILABLE")
                    val stored = EditStore.loadForTransfer(app, uri)
                    val before = if (uri == selected) current else EditSnapshot.capture(stored.params)
                    val raw = EditGroup.DECODE in template.groups && RawDecoder.isRaw(app, uri, name)
                    val result = template.applyTo(before.toParams(), raw, catalog.first, catalog.second)
                    val problem = result.problem?.let {
                        app.getString(when (it) {
                            EditTransferProblem.UNKNOWN_FILM -> R.string.edit_unknown_film
                            EditTransferProblem.UNKNOWN_PAPER -> R.string.edit_unknown_paper
                            EditTransferProblem.NO_COMPATIBLE_PAPER -> R.string.edit_no_paper
                            EditTransferProblem.INCOMPATIBLE_MATERIAL -> R.string.edit_bad_pair
                        })
                    }
                    EditTransferTarget(uri, name, before, stored.json, result.params?.let(EditSnapshot::capture), result.notices, problem,
                        catalog.second.firstOrNull { it.id == result.params?.paperId }?.name)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    EditTransferTarget(uri, name, EditSnapshot.capture(EditorParams()), null, null,
                        problem = app.getString(R.string.edit_source_unavailable))
                }
            }
        }
        return EditTransferPreview(template, rows)
    }

    internal fun applyEditTransfer(preview: EditTransferPreview) {
        if (!canTransferEdits || preview.changes.isEmpty()) return
        val changes = preview.changes
        if (changes.any { it.uri !in photos } || changes.firstOrNull { it.uri == currentUri }?.let {
                it.before != EditSnapshot.capture(committedParams())
            } == true) {
            editTransferError = app.getString(R.string.edit_targets_changed)
            return
        }
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        editTransferError = null
        isApplyingEdits = true
        busy = true
        renderStage = app.getString(R.string.edit_applying)
        renderProgress = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    changes.forEach { change ->
                        app.contentResolver.openAssetFileDescriptor(change.uri, "r")?.close()
                            ?: error("SOURCE_UNAVAILABLE")
                    }
                    EditStore.saveBatch(app, changes.map { EditStore.BatchEdit(it.uri, it.expectedJson, it.after!!.json) })
                }
                for (change in changes) {
                    val key = historyKey(change.uri)
                    val before = change.before.toParams().toJson().toString()
                    val after = change.after!!.toParams().toJson().toString()
                    val history = undoHistory.getOrPut(key) { mutableListOf(before) }
                    if (history.lastOrNull() != before) history += before
                    if (history.lastOrNull() != after) history += after
                    while (history.size > 64) history.removeAt(0)
                    redoHistory.remove(key)
                    freshlySmartDeveloped.remove(change.uri)
                    developedPreviews = developedPreviews - change.uri
                    discardGalleryPreview(change.uri)
                }
                lastHistoryKey = null
                val currentChange = changes.firstOrNull { it.uri == currentUri }
                busy = false
                isApplyingEdits = false
                if (currentChange != null) {
                    val previous = params
                    params = currentChange.after!!.toParams()
                    cropAspect = if (params.cropW >= 1f && params.cropH >= 1f) "原始" else "自定义"
                    dirty = true
                    suppressHistory = true
                    try {
                        val cameraChanged = previous.cameraWbCustom != params.cameraWbCustom ||
                            previous.cameraWbKelvin != params.cameraWbKelvin || previous.cameraWbTint != params.cameraWbTint
                        when {
                            currentIsRaw && cameraChanged -> scheduleCameraWb()
                            previous.deltaJson(previewW, previewH) == params.deltaJson(previewW, previewH) -> scheduleGrade(commit = false)
                            else -> scheduleRender()
                        }
                    } finally { suppressHistory = false }
                }
                refreshHistoryButtons()
                editTransferRevision++
                toast(app.getString(R.string.edit_applied_count, changes.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                editTransferError = app.getString(R.string.edit_apply_error)
            } finally {
                if (isApplyingEdits) busy = false
                isApplyingEdits = false
            }
        }
    }

    // 新照片（无 sidecar）时重置几何/裁剪。
    fun resetGeometry() {
        if (isApplyingEdits) return
        params.cropX = 0f; params.cropY = 0f; params.cropW = 1f; params.cropH = 1f
        params.quarterTurns = 0; params.flipH = false; params.flipV = false; params.rotationDeg = 0f
        cropAspect = "原始"
    }

    /** Enter the dedicated crop workspace and remember the pre-edit frame. */
    fun beginCrop() {
        if (isApplyingEdits) return
        if (isCropping || sourceBitmap == null || params.overscanActive) return
        cropEditStart = floatArrayOf(params.cropX, params.cropY, params.cropW, params.cropH)
        isCropping = true
    }

    /** Discard the current crop gesture and restore the frame on entry. */
    fun cancelCrop() {
        val start = cropEditStart
        if (start != null && start.size == 4) {
            params.cropX = start[0]; params.cropY = start[1]
            params.cropW = start[2]; params.cropH = start[3]
            cropAspect = if (start[2] >= 1f && start[3] >= 1f) "原始" else "自定义"
        }
        cropEditStart = null
        isCropping = false
    }

    fun resetCropSelection() {
        params.cropX = 0f; params.cropY = 0f; params.cropW = 1f; params.cropH = 1f
        cropAspect = "原始"
    }

    /** Commit the crop overlay as a bounded geometry edit and re-render it. */
    fun finishCrop() {
        if (!isCropping) return
        // Match the overlay's 2% minimum; a second 10% clamp changed valid selections.
        params.cropW = params.cropW.coerceIn(0.02f, 1f)
        params.cropH = params.cropH.coerceIn(0.02f, 1f)
        params.cropX = params.cropX.coerceIn(0f, 1f - params.cropW)
        params.cropY = params.cropY.coerceIn(0f, 1f - params.cropH)
        isCropping = false
        cropEditStart = null
        scheduleRender()
    }

    // Layer 2 调色参数（应用侧）：白平衡 + 曝光 + 对比度/亮度 + 高光/阴影 + 黑点/白点 + 饱和度 + 色彩平衡。
    private fun grade(p: EditorParams = params): Grade {
        fun zone(temp: Float, tint: Float) = floatArrayOf(0.18f * temp, -0.12f * tint, -0.18f * temp)
        return Grade(
            1f + p.temperature * 0.18f,                      // Windows layer2Uniforms
            1f - p.tint * 0.12f,
            1f - p.temperature * 0.18f,
            Math.pow(2.0, p.gradeExposure.toDouble()).toFloat(),  // exposureGain
            p.contrast / 100f,
            p.brightness / 100f,
            1f + p.saturation / 100f,
            p.highlights / 100f,
            p.shadows / 100f,
            p.blackPoint / 100f,
            p.whitePoint / 100f,
            zone(p.cbShadowTemp / 100f, p.cbShadowTint / 100f),
            zone(p.cbMidTemp / 100f, p.cbMidTint / 100f),
            zone(p.cbHighTemp / 100f, p.cbHighTint / 100f),
            p.vignetteAmount / 100f, p.vignetteSize / 100f, p.vignetteFeather / 100f, p.toneCurves, p.colorAdjustments,
            p.channelMixer, p.colorWheels,
        )
    }

    // 把渲染结果（rgba16）过一遍 Layer 2 调色，转成显示用 Bitmap。
    private fun convertToBitmap(result: SpkResult, editParams: EditorParams = params, colorSpace: String = "sRGB"): Bitmap {
        return ColorPipeline.applyGradeToBitmap(result.rgba16, result.width, result.height,
            grade(editParams), colorSpace).first
    }

    private fun convertPreview(result: SpkResult, editParams: EditorParams, colorSpace: String, checkCancelled: () -> Unit = {}): Pair<Bitmap, Histogram> {
        return ColorPipeline.applyGradeToBitmap(result.rgba16, result.width, result.height,
            grade(editParams), colorSpace, checkCancelled)
    }

    // 更新渲染结果 + 直方图 + 显示位图（一次渲染落地后调用）。
    private suspend fun updateResult(
        result: SpkResult,
        colorSpace: String = "sRGB",
        photo: Long = photoGeneration,
        revision: Long = renderGeneration,
        editParams: EditorParams = params.copy(),
        request: Long? = null,
    ) {
        val pending = PendingPreview(result, colorSpace, photo, revision, request)
        val grading = withContext(Dispatchers.Main.immediate) {
            if (photo != photoGeneration || revision != renderGeneration) return@withContext null
            // Make the new base available to post edits that arrive while it is being converted.
            // A film request's earlier snapshot must not overwrite a newer curve or colour edit.
            wbJob?.cancel()
            val generation = ++gradeGeneration
            pendingPreview = pending
            generation to if (isExporting || isBatchExporting) editParams else params.copy()
        } ?: return
        var computed: Bitmap? = null
        var published = false
        try {
            val (bitmap, counts) = withContext(Dispatchers.Default) {
                val task = currentCoroutineContext()
                convertPreview(result, grading.second, colorSpace) { task.ensureActive() }
                    .also { computed = it.first }
            }
            withContext(Dispatchers.Main.immediate) {
                if (photo == photoGeneration && revision == renderGeneration &&
                    grading.first == gradeGeneration && pendingPreview === pending) {
                    resultColorSpace = colorSpace
                    rendered = bitmap
                    histogram = counts
                    lastResult = result
                    pending.renderRequest?.let { publishedRenderRequest = it }
                    pendingPreview = null
                    rememberDevelopedPreview(bitmap)
                    published = true
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (pendingPreview === pending) pendingPreview = null
            }
            throw error
        } finally {
            if (!published) computed?.recycle()
        }
    }

    private fun rememberDevelopedPreview(bitmap: Bitmap) {
        val uri = currentUri ?: return
        val photo = photoGeneration
        developedPreviewJob?.cancel()
        developedPreviewJob = viewModelScope.launch {
            // The working canvas publishes every completed frame. Its tray thumbnail can wait
            // for a brief pause instead of scaling another bitmap on every slider frame.
            delay(120)
            val preview = withContext(Dispatchers.Default) {
                val edge = max(bitmap.width, bitmap.height)
                if (edge > 960) {
                    val scale = 960f / edge
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                } else bitmap
            }
            if (photo == photoGeneration && uri == currentUri && uri in photos && rendered === bitmap) {
                developedPreviews = developedPreviews + (uri to preview)
            }
        }
    }

    /** Add gallery photos to the independent viewing-stand queue. */
    fun loadViewingPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewingImportRequests += 1
        viewModelScope.launch {
            try {
                viewingImportMutex.lock()
                try {
                    val existing = viewingPhotos.map { it.first }.toSet()
                    val pending = uris.distinct().filter { it.toString() !in existing }
                    viewingImportTotal = pending.size
                    viewingImportCompleted = 0
                    var failed = 0
                    for (uri in pending) {
                        val bitmap = try {
                            withContext(Dispatchers.IO) { decodeSampled(uri, 640) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed += 1
                            null
                        }
                        if (bitmap != null) {
                            if (cleared || viewingPhotos.any { it.first == uri.toString() }) bitmap.recycle()
                            else viewingPhotos = viewingPhotos + (uri.toString() to bitmap)
                        }
                        viewingImportCompleted += 1
                    }
                    if (failed > 0) toast("有 $failed 张照片无法读取，请重新选择")
                } finally {
                    viewingImportMutex.unlock()
                }
            } finally {
                viewingImportRequests -= 1
            }
        }
    }

    /** Remove only the viewing-stand entry; the original photo is untouched. */
    fun removeViewingPhoto(key: String) {
        discardGalleryPreview(Uri.parse(key))
        viewingPhotos = viewingPhotos.filterNot { it.first == key }
    }

    private fun discardGalleryPreview(uri: Uri) {
        galleryPreviewJobs.remove(uri)?.cancel()
        galleryPreviews = galleryPreviews - uri
        galleryPreviewErrors = galleryPreviewErrors - uri
    }

    /** Decode a detail-sized preview on demand without opening an editor render session. */
    fun loadGalleryPreview(uri: Uri) {
        if (galleryPreviews.containsKey(uri) || galleryPreviewJobs[uri]?.isActive == true) return
        if (uri !in photos && viewingPhotos.none { it.first == uri.toString() }) return
        galleryPreviewErrors = galleryPreviewErrors - uri
        val request = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    galleryPreviewDecodeMutex.withLock { decodeSampled(uri, 1400) }
                }
                if (cleared || (uri !in photos && viewingPhotos.none { it.first == uri.toString() })) {
                    bitmap.recycle()
                } else if (!galleryPreviews.containsKey(uri)) {
                    val next = LinkedHashMap(galleryPreviews)
                    next[uri] = bitmap
                    val evicted = next.keys.take((next.size - GALLERY_PREVIEW_CACHE_SIZE).coerceAtLeast(0))
                    evicted.forEach(next::remove)
                    galleryPreviews = next
                } else {
                    bitmap.recycle()
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                if (!cleared && (uri in photos || viewingPhotos.any { it.first == uri.toString() })) {
                    galleryPreviewErrors = galleryPreviewErrors + (uri to "原照片可能已移走，或访问权限已失效。")
                }
            } finally {
                if (galleryPreviewJobs[uri] === currentCoroutineContext()[Job]) galleryPreviewJobs.remove(uri)
            }
        }
        galleryPreviewJobs[uri] = request
    }

    /** The wall requests at most four visible frames, in order of proximity.
     * Await each existing preview request instead of launching a decode per frame.
     * Leaving or moving the viewport cancels the remaining sequence.
     */
    suspend fun loadGalleryWallDetails(uris: List<Uri>) {
        for (uri in uris.distinct().take(4)) {
            if (!currentCoroutineContext().isActive) return
            if (uri == currentUri || developedPreviews.containsKey(uri)) continue
            loadGalleryPreview(uri)
            galleryPreviewJobs[uri]?.join()
        }
    }

    // Layer 2 调色只重转换缓存的渲染结果，不触发引擎重渲染。
    fun scheduleGrade(commit: Boolean = true) {
        if (isApplyingEdits) return
        if (!hasPhoto || isExporting || isBatchExporting) return
        if (commit) recordHistory()
        dirty = true
        if (commit) currentUri?.let { EditStore.save(app, it, committedParams()) }
        gradeRequest++
        if (wbJob?.isActive == true) return
        wbJob = viewModelScope.launch {
            // One worker finishes a frame while input replaces the next request. A trailing
            // debounce cancelled every frame during continuous drags, starving the preview.
            do {
                delay(16)
                if (!hasPhoto || isExporting || isBatchExporting) return@launch
                val request = gradeRequest
                val pending = pendingPreview?.takeIf { it.photo == photoGeneration && it.revision == renderGeneration }
                val result = pending?.result ?: lastResult ?: return@launch
                val editParams = EditSnapshot.capture(params).toParams()
                val colorSpace = pending?.colorSpace ?: resultColorSpace
                val generation = ++gradeGeneration
                val photo = photoGeneration
                val revision = renderGeneration
                var computed: Bitmap? = null
                var published = false
                try {
                    val (bitmap, counts) = withContext(Dispatchers.Default) {
                        val task = currentCoroutineContext()
                        convertPreview(result, editParams, colorSpace) { task.ensureActive() }.also { computed = it.first }
                    }
                    if (photo == photoGeneration && revision == renderGeneration && generation == gradeGeneration &&
                        !isExporting && !isBatchExporting &&
                        (lastResult === result || pendingPreview?.result === result)) {
                        resultColorSpace = colorSpace
                        rendered = bitmap
                        histogram = counts
                        lastResult = result
                        pending?.renderRequest?.let { publishedRenderRequest = it }
                        if (pendingPreview?.result === result) pendingPreview = null
                        rememberDevelopedPreview(bitmap)
                        published = true
                    }
                } finally {
                    if (!published) computed?.recycle()
                }
            } while (request != gradeRequest)
        }
    }

    private var wheelStart: com.spektralab.android.core.color.ColorWheels? = null
    private var wheelStartUnreadable: String? = null

    fun beginWheelEdit() {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        if (wheelStart == null) {
            wheelStart = params.colorWheels
            wheelStartUnreadable = params.unreadableColorWheels
            lastHistoryKey = null
        }
    }

    fun changeWheels(wheels: com.spektralab.android.core.color.ColorWheels) {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        params.colorWheels = wheels
        params.unreadableColorWheels = null
        scheduleGrade(commit = false)
    }

    fun finishWheelEdit(cancel: Boolean = false) {
        val start = wheelStart ?: return
        wheelStart = null
        if (cancel) {
            params.colorWheels = start
            params.unreadableColorWheels = wheelStartUnreadable
        }
        scheduleGrade(commit = !cancel && (params.colorWheels != start ||
            params.unreadableColorWheels != wheelStartUnreadable))
        wheelStartUnreadable = null
    }

    private var mixerStart: com.spektralab.android.core.color.ChannelMixer? = null
    private var mixerStartUnreadable: String? = null

    fun beginMixerEdit() {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        if (mixerStart == null) {
            mixerStart = params.channelMixer
            mixerStartUnreadable = params.unreadableChannelMixer
            lastHistoryKey = null
        }
    }

    fun changeMixer(mixer: com.spektralab.android.core.color.ChannelMixer) {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        params.channelMixer = mixer
        params.unreadableChannelMixer = null
        scheduleGrade(commit = false)
    }

    fun finishMixerEdit(cancel: Boolean = false) {
        val start = mixerStart ?: return
        mixerStart = null
        if (cancel) {
            params.channelMixer = start
            params.unreadableChannelMixer = mixerStartUnreadable
        }
        scheduleGrade(commit = !cancel && (params.channelMixer != start ||
            params.unreadableChannelMixer != mixerStartUnreadable))
        mixerStartUnreadable = null
    }

    private var colorStart: com.spektralab.android.core.color.ColorAdjustments? = null
    private var colorStartUnreadable: String? = null

    fun beginColorEdit() {
        if (colorStart == null) {
            colorStart = params.colorAdjustments
            colorStartUnreadable = params.unreadableColorAdjustments
            lastHistoryKey = null
        }
    }

    fun changeColors(colors: com.spektralab.android.core.color.ColorAdjustments) {
        if (busy || isExporting || isBatchExporting) return
        params.colorAdjustments = colors
        params.unreadableColorAdjustments = null
        scheduleGrade(commit = false)
    }

    fun finishColorEdit(cancel: Boolean = false) {
        val start = colorStart ?: return
        colorStart = null
        if (cancel) {
            params.colorAdjustments = start
            params.unreadableColorAdjustments = colorStartUnreadable
        }
        scheduleGrade(commit = !cancel && (params.colorAdjustments != start ||
            params.unreadableColorAdjustments != colorStartUnreadable))
        colorStartUnreadable = null
    }

    private var curveStartUnreadable: String? = null
    private var curveStart: com.spektralab.android.core.color.ToneCurves? = null

    fun beginCurveEdit() {
        if (curveStart == null) {
            curveStart = params.toneCurves
            curveStartUnreadable = params.unreadableToneCurves
            lastHistoryKey = null
        }
    }

    fun changeCurves(curves: com.spektralab.android.core.color.ToneCurves) {
        if (isExporting || isBatchExporting) return
        params.toneCurves = curves
        params.unreadableToneCurves = null
        scheduleGrade(commit = false)
    }

    fun finishCurveEdit(cancel: Boolean = false) {
        val start = curveStart ?: return
        curveStart = null
        if (cancel) { params.toneCurves = start; params.unreadableToneCurves = curveStartUnreadable }
        scheduleGrade(commit = !cancel && (params.toneCurves != start || params.unreadableToneCurves != curveStartUnreadable))
        curveStartUnreadable = null
    }

    // 相机白平衡（解码侧，仅 RAW）：Kelvin/tint 变化后去抖重新解码 + 重开会话。
    fun scheduleCameraWb() {
        if (!currentIsRaw || !hasPhoto) return
        recordHistory()
        currentUri?.let { EditStore.save(app, it, committedParams()) }
        val uri = currentUri ?: return
        val photo = photoGeneration
        val editParams = EditSnapshot.capture(params).toParams()
        cameraWbJob?.cancel()
        cameraWbJob = viewModelScope.launch {
            delay(300)
            engineTasks.onEngine {
                if (photo != photoGeneration) return@onEngine
                try {
                    decodeRawAndOpen(uri, photo, editParams)
                    append("RAW 白平衡: ${if (params.cameraWbCustom) "${params.cameraWbKelvin.toInt()}K / tint ${params.cameraWbTint.toInt()}" else "as-shot"}")
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        if (photo == photoGeneration) fail("${ex::class.simpleName}: ${ex.message}")
                    }
                }
            }
        }
    }

    // 恢复 as-shot 白平衡。
    fun resetCameraWb() {
        params.cameraWbCustom = false
        scheduleCameraWb()
    }

    // 切换镜头校正（命中镜头配置时）。重新解码 + 应用/撤销 + 重开。
    fun toggleLensCorrection() {
        if (matchedLensProfile == null) {
            fail("未找到这支镜头的校正配置，已保留原始画面")
            return
        }
        lensCorrectionActive = !lensCorrectionActive
        val uri = currentUri ?: return
        if (!currentIsRaw || !hasPhoto) return
        val photo = photoGeneration
        val editParams = EditSnapshot.capture(params).toParams()
        cameraWbJob?.cancel()
        cameraWbJob = viewModelScope.launch {
            engineTasks.onEngine {
                if (photo != photoGeneration) return@onEngine
                try {
                    decodeRawAndOpen(uri, photo, editParams)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        if (photo == photoGeneration) fail("${ex::class.simpleName}: ${ex.message}")
                    }
                }
            }
        }
    }

    // 解码 RAW：应用自定义白平衡（若开启）+ 镜头校正（若命中且开启）。
    private suspend fun decodeRaw(uri: Uri, editParams: EditorParams = params, halfSize: Boolean = true, photo: Long = photoGeneration): RawDecodeResult {
        val cachedXyz = if (uri == currentUri && photo == photoGeneration) rawCamXyz else null
        val correctLens = lensCorrectionActive
        val task = currentCoroutineContext()
        val cancellation = RawDecodeCancellation { cleared || photo != photoGeneration || !task.isActive }
        val headerXyz = cachedXyz ?: if (editParams.cameraWbCustom) {
            RawDecoder.readCameraMatrix(app, uri, cancellation)
        } else null
        val initial = if (headerXyz == null) {
            RawDecoder.decode(app, uri, halfSize = halfSize, cancellation = cancellation)
        } else null
        val cameraXyz = headerXyz ?: initial!!.camXyz
        val userMul = if (editParams.cameraWbCustom) {
            val mul = FloatArray(4)
            if (CameraWhiteBalance.multipliersFor(cameraXyz, editParams.cameraWbKelvin.toDouble(), editParams.cameraWbTint.toDouble(), mul)) mul else null
        } else null
        val raw = if (userMul != null) {
            RawDecoder.decode(app, uri, halfSize = halfSize, userMul = userMul, cancellation = cancellation)
        } else initial ?: RawDecoder.decode(app, uri, halfSize = halfSize, cancellation = cancellation)
        LensProfileStore.load(app)
        val lensProfile = LensProfileStore.match(raw.make, raw.lens, raw.focal)
        withContext(Dispatchers.Main.immediate) {
            if (uri == currentUri && photo == photoGeneration) {
                rawCamXyz = raw.camXyz
                matchedLensProfile = lensProfile
                matchedLens = lensProfile?.model
            }
        }
        if (correctLens && lensProfile != null) {
            RawDecoder.applyLensCorrection(raw.linearProPhoto, raw.width, raw.height, lensProfile)
        }
        return raw
    }

    // Native export input avoids full-frame Java arrays without reducing the requested resolution.
    private fun openExportSource(e: EngineClient, uri: Uri, editParams: EditorParams, size: Int,
                                 colorSpace: String, options: ExportOptions) {
        sourceExif = readExif(uri, options.keepLocation)
        // The interactive preview remains on screen, but its GPU session need not overlap source decoding.
        e.closeSession()
        if (RawDecoder.isRaw(app, uri, queryDisplayName(uri))) {
            val cancellation = RawDecodeCancellation { cleared || exportCancelRequested }
            val cachedXyz = (if (uri == currentUri) rawCamXyz else null) ?: if (editParams.cameraWbCustom) {
                RawDecoder.readCameraMatrix(app, uri, cancellation)
            } else null
            fun multipliers(xyz: FloatArray?): FloatArray? {
                if (!editParams.cameraWbCustom || xyz == null) return null
                val mul = FloatArray(4)
                return if (CameraWhiteBalance.multipliersFor(xyz, editParams.cameraWbKelvin.toDouble(),
                    editParams.cameraWbTint.toDouble(), mul)) mul else null
            }
            var raw = measureExport("raw_decode") {
                RawDecoder.decodeForExport(app, uri, size, multipliers(cachedXyz), cancellation)
            }
            try {
                if (editParams.cameraWbCustom && cachedXyz == null) {
                    val mul = multipliers(raw.camXyz)
                    if (mul != null) {
                        raw.close()
                        raw = RawDecoder.decodeForExport(app, uri, size, mul, cancellation)
                    }
                }
                LensProfileStore.load(app)
                val profile = LensProfileStore.match(raw.make, raw.lens, raw.focal)
                if (options.correctLens && profile != null) measureExport("raw_lens_correction") { raw.correctLens(profile) }
                raw.resize(size)
                checkExportCancellation()
                measureExport("engine_input") {
                    e.openRaw(raw, editParams.openJson(raw.width, raw.height, colorSpace, "ProPhoto RGB"))
                }
            } finally { raw.close() }
        } else {
            val bitmap = measureExport("image_decode") { decodeBitmapForExport(uri, size) }
            try {
                checkExportCancellation()
                e.openBitmap(bitmap, editParams.openJson(bitmap.width, bitmap.height, colorSpace))
            } finally { bitmap.recycle() }
        }
        android.util.Log.i("SpkExport", "input opened; ART limit=${Runtime.getRuntime().maxMemory()}; ${e.memoryReport()}")
    }

    // 解码 RAW → 显示源图 → 开会话。
    private suspend fun decodeRawAndOpen(uri: Uri, photo: Long = photoGeneration, editParams: EditorParams = params.copy()) {
        val raw = decodeRaw(uri, editParams, photo = photo)
        if (photo != photoGeneration || currentUri != uri) return
        val task = currentCoroutineContext()
        // Hold/split comparison shows the camera's display rendition, not untonemapped sensor RGB.
        // Keep it stable across white-balance and lens edits; only the film input follows those edits.
        val original = sourceBitmap ?: com.spektralab.android.data.image.OriginalPreview.raw(app, uri,
            RawDecodeCancellation { cleared || photo != photoGeneration || !task.isActive }) ?: run {
            val argb = ColorPipeline.proPhotoLinearToSrgbArgb(raw.linearProPhoto, raw.width, raw.height)
            Bitmap.createBitmap(argb, raw.width, raw.height, Bitmap.Config.ARGB_8888)
        }
        withContext(Dispatchers.Main.immediate) {
            if (photo != photoGeneration || currentUri != uri) {
                if (original !== sourceBitmap) original.recycle()
                return@withContext
            }
            sourceBitmap = original
            append("RAW 解码: ${raw.width}×${raw.height} · ${raw.make} ${raw.model}" +
                (matchedLens?.let { " · 镜头校正: $it" } ?: ""))
            openFrame(raw.linearProPhoto, raw.width, raw.height, "ProPhoto RGB")
        }
    }

    // Keep one render in flight; coalesce continuous input into the next complete frame.
    fun scheduleRender() = scheduleRender(immediate = false)

    private fun scheduleRender(immediate: Boolean) {
        if (isApplyingEdits) return
        if (isExporting || isBatchExporting) return
        recordHistory()
        val e = engine ?: return
        if (!hasPhoto) return
        dirty = true
        renderRequest++
        if (immediate) {
            renderJob?.cancel()
            e.cancel()
        } else if (renderJob?.isActive == true) return
        renderJob = viewModelScope.launch {
            do {
                if (!immediate) delay(16)
                if (!hasPhoto || isExporting || isBatchExporting) return@launch
                val request = renderRequest
                val generation = ++renderGeneration
                pendingPreview = null
                val photo = photoGeneration
                val uri = currentUri
                val editParams = EditSnapshot.capture(params).toParams()
                val delta = editParams.deltaJson(previewW, previewH)
                stockPreviewJob?.cancel()
                wbJob?.cancel()
                gradeGeneration++
                engineTasks.onEngine {
                    if (photo != photoGeneration || generation != renderGeneration) return@onEngine
                    try {
                        e.setChangedParams(delta)
                        // UI post edits may be newer than this debounced native request. Keep saves
                        // ordered with those edits instead of writing the older render snapshot.
                        withContext(Dispatchers.Main.immediate) {
                            if (photo == photoGeneration && generation == renderGeneration && uri == currentUri) {
                                uri?.let { EditStore.save(app, it, committedParams()) }
                            }
                        }
                        val r = e.render("live")
                        if (photo != photoGeneration || generation != renderGeneration) return@onEngine
                        withContext(Dispatchers.Main.immediate) { stockPreviewJob?.cancel() }
                        updateResult(r, photo = photo, revision = generation, editParams = editParams, request = request)
                        append("render: ${r.width}×${r.height}  ${r.elapsedMs}ms  reprint=${r.reprint}")
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (_: OutOfMemoryError) {
                        withContext(Dispatchers.Main.immediate) {
                            if (photo == photoGeneration && generation == renderGeneration) {
                                fail("编辑预览内存不足，已保留当前画面。请重新打开当前照片后重试")
                            }
                        }
                    } catch (ex: Exception) {
                        withContext(Dispatchers.Main.immediate) {
                            if (photo == photoGeneration && generation == renderGeneration &&
                                ex.message?.contains("cancel", ignoreCase = true) != true) fail("${ex::class.simpleName}: ${ex.message}")
                        }
                    }
                }
            } while (request != renderRequest)
        }
    }

    // 相纸切换：先用打印 LUT 给近似预览（~10ms），再排程真正的 reprint
    // （LUT 无法表示曝光/滤色/glare，只作快速反馈）。
    fun selectFilm(stockId: String) {
        if (busy || isExporting || isBatchExporting) return
        val film = films.firstOrNull { it.id == stockId } ?: return
        if (stockId == params.filmId && papers.any { it.id == params.paperId } &&
            StockCatalog.canPrint(stockId, params.paperId)) return
        val paper = if (StockCatalog.canPrint(stockId, params.paperId) && papers.any { it.id == params.paperId })
            params.paperId else papers.firstOrNull { it.id == film.targetPrint && StockCatalog.canPrint(stockId, it.id) }?.id
                ?: papers.firstOrNull { StockCatalog.canPrint(stockId, it.id) }?.id ?: return
        params.filmId = stockId
        params.paperId = paper
        scheduleRender(immediate = true)
    }

    fun selectPaper(stockId: String) {
        if (busy || isExporting || isBatchExporting || stockId == params.paperId ||
            papers.none { it.id == stockId } || !StockCatalog.canPrint(params.filmId, stockId)) return
        params.paperId = stockId
        val e = engine ?: return
        if (!hasPhoto) return
        dirty = true
        scheduleRender()
        val photo = photoGeneration
        val revision = renderGeneration
        val editParams = EditSnapshot.capture(params).toParams()
        stockPreviewJob?.cancel()
        stockPreviewJob = viewModelScope.launch {
            engineTasks.onEngine {
                if (photo != photoGeneration || revision != renderGeneration) return@onEngine
                try {
                    val r = e.previewStockLut(stockId, "live")
                    updateResult(r, photo = photo, revision = revision, editParams = editParams)
                } catch (ex: SpkException) {
                    // 胶片不配对时 LUT 预览可能被拒；忽略，等 reprint。
                }
            }
        }
    }

    // 一键配光：solve("both") 求曝光 + 放大机中性滤色包。
    // 引擎默认 auto_exposure=true + neutral_print_filters_from_database=true，
    // 曝光与中性滤色包本就自动解析；这里只重新测光并把用户手动 shift 归零后重印
    // （与桌面版 solveNow 一致：不把求解 EV 写回 exposure_compensation_ev，避免双重曝光）。
    fun solve() = runNativeAnalysis("metering", { solve("both") }) { root ->
        // 在主线程写 Compose 状态：手动 shift 归零；曝光/中性滤色由引擎自动解析。
        val sp = root.optJSONObject("solved_params")
        val ev = sp?.optDouble("exposure_compensation_ev", 0.0) ?: 0.0
        val m = sp?.optDouble("m_filter_neutral", 0.0) ?: 0.0
        val y = sp?.optDouble("y_filter_neutral", 0.0) ?: 0.0
        params.mFilterShift = 0f
        params.yFilterShift = 0f
        append("配光: 测光 EV %+.2f · 中性滤色 M %.0f / Y %.0f".format(ev, m, y))
        scheduleRender()
    }
    // 测量场景宽容度：spk_scene_latitude 返回 medium/scene/suggested/fit。
    // 展示建议回拉量，并在 fit 有效时保存 params_delta 供「应用」提交。
    fun measureLatitude() {
        if (!nativeAnalysisAllowed()) return
        latitudeDelta = null
        latitudeCanApply = false
        runNativeAnalysis("latitude", { sceneLatitude(null) }) { root ->
            val suggested = root.optJSONObject("suggested")
            val scene = root.optJSONObject("scene")
            val fit = root.optJSONObject("fit")
            val hi = suggested?.optDouble("highlight_pull_back", 0.0) ?: 0.0
            val sh = suggested?.optDouble("shadow_pull_back", 0.0) ?: 0.0
            val p1 = scene?.optDouble("p1", 0.0) ?: 0.0
            val p99 = scene?.optDouble("p99", 0.0) ?: 0.0
            val valid = fit?.optBoolean("valid", false) ?: false
            val delta = fit?.optJSONObject("params_delta")
            latitudeDelta = if (valid) delta else null
            latitudeCanApply = valid && delta != null
            latitudeReadout = "高光回拉 %+.2f stop · 阴影回拉 %+.2f stop · 场景 p1 %.1f / p99 %.1f".format(hi, sh, p1, p99)
            if (!valid) {
                val issue = fit?.optJSONArray("issues")?.optJSONObject(0)?.optString("message")
                if (!issue.isNullOrEmpty()) latitudeReadout = "$latitudeReadout\n拟合失败: $issue"
            }
            append(latitudeReadout!!)
        }
    }

    private fun nativeAnalysisAllowed() = hasPhoto && !busy && !isExporting && !isBatchExporting && !isApplyingEdits

    /** Bind read-only native analysis to the exact photo and committed settings that requested it. */
    private fun runNativeAnalysis(kind: String, query: EngineClient.() -> String, apply: (JSONObject) -> Unit) {
        val target = engine ?: return
        if (!nativeAnalysisAllowed()) return
        val photo = photoGeneration
        val uri = currentUri
        val snapshot = EditSnapshot.capture(committedParams())
        val width = previewW
        val height = previewH
        busy = true
        viewModelScope.launch {
            try {
                val reply = engineTasks.onEngine(kind) {
                    if (photo != photoGeneration || uri != currentUri) return@onEngine null
                    // A slider's latest request may still be queued when analysis is tapped.
                    target.setChangedParams(snapshot.toParams().deltaJson(width, height))
                    target.query()
                } ?: return@launch
                if (photo == photoGeneration && uri == currentUri && !isExporting && !isBatchExporting &&
                    snapshot == EditSnapshot.capture(committedParams())) apply(JSONObject(reply))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (photo == photoGeneration && uri == currentUri) fail("${failure::class.simpleName}: ${failure.message}")
            } finally {
                // A previous photo must never clear the new photo loader's busy state.
                if (photo == photoGeneration && uri == currentUri) busy = false
            }
        }
    }

    // 应用测量得到的场景置位（fit.params_delta → EditorParams → set_params + 重显影）。
    fun applyLatitude() {
        val delta = latitudeDelta ?: return
        if (!nativeAnalysisAllowed()) return
        params.sceneLatitudeActive = delta.optBoolean("scene_latitude_active", false)
        params.sceneLatitudeNorm = delta.optString("scene_latitude_norm", "power")
        params.sceneLatitudeHighlightKnee = delta.optDouble("scene_latitude_highlight_knee", 2.0).toFloat()
        params.sceneLatitudeHighlightRoom = delta.optDouble("scene_latitude_highlight_room", 0.0).toFloat()
        params.sceneLatitudeShadowKnee = delta.optDouble("scene_latitude_shadow_knee", -2.0).toFloat()
        params.sceneLatitudeShadowRoom = delta.optDouble("scene_latitude_shadow_room", 0.0).toFloat()
        params.sceneLatitudeRolloff = delta.optDouble("scene_latitude_rolloff", 2.0).toFloat()
        params.sceneLatitudeMaxLift = delta.optDouble("scene_latitude_max_lift", 4.0).toFloat()
        latitudeDelta = null
        latitudeCanApply = false
        scheduleRender()
    }

    fun openFrame(linear: FloatArray, w: Int, h: Int, inputColorSpace: String = "sRGB") {
        previewLinear = linear
        previewW = w
        previewH = h
        previewInputColorSpace = inputColorSpace
        isCropping = false
        dirty = false
        val frame = PendingFrame(linear, w, h, inputColorSpace, photoGeneration, ++renderGeneration, EditSnapshot.capture(params))
        val currentEngine = engine
        if (currentEngine == null) {
            pendingFrame = frame
            if (engineCreateJob?.isActive != true) createEngine()
            return
        }
        renderFrame(currentEngine, frame)
    }

    private fun renderFrame(targetEngine: EngineClient, frame: PendingFrame) {
        if (frame.photo != photoGeneration || frame.revision != renderGeneration) return
        val editParams = frame.edit.toParams()
        busy = true
        frameRenderJob?.cancel()
        frameRenderJob = viewModelScope.launch {
            try {
                engineTasks.onEngine {
                    if (frame.photo != photoGeneration || frame.revision != renderGeneration) return@onEngine
                    targetEngine.open(
                        frame.pixels,
                        frame.width,
                        frame.height,
                        3,
                        editParams.openJson(frame.width, frame.height, inputColorSpace = frame.inputColorSpace),
                    )
                    if (frame.photo != photoGeneration || frame.revision != renderGeneration) return@onEngine
                    val r = targetEngine.render("live")
                    updateResult(r, photo = frame.photo, revision = frame.revision, editParams = editParams)
                    withContext(Dispatchers.Main.immediate) {
                        if (frame.photo == photoGeneration && frame.revision == renderGeneration) {
                            hasPhoto = true
                            append("渲染: ${r.width}×${r.height}  ${r.elapsedMs}ms")
                        }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (frame.photo == photoGeneration && frame.revision == renderGeneration) fail("${ex::class.simpleName}: ${ex.message}")
            } finally {
                if (frame.photo == photoGeneration && frame.revision == renderGeneration) busy = false
            }
        }
    }

    // 取消当前进行中的渲染（跨线程安全，渲染在下个节点边界回退并返回 cancelled）。
    fun cancelRender() {
        if (isApplyingEdits) return
        if (isExportCommitCompleted) return
        if (isExporting || isBatchExporting) {
            if (isExporting && (renderProgress ?: 0f) >= 100f) return
            exportCancelRequested = true
            renderStage = "正在取消…"
        } else {
            renderJob?.cancel()
            wbJob?.cancel()
            stockPreviewJob?.cancel()
            pendingPreview = null
        }
        engine?.cancel()
    }

    // 应用一条导出配方。
    fun applyRecipe(recipe: ExportRecipe) {
        exportFormat = recipe.format
        exportRes = recipe.res
        exportColorSpace = recipe.colorSpace
        jpegQuality = recipe.jpegQuality.coerceIn(50, 100)
        exportKeepCapture = recipe.keepCapture
        exportKeepLocation = recipe.keepLocation
    }

    // 把当前导出设置保存为命名配方（同名覆盖）。
    fun saveRecipe(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val recipe = ExportRecipe(trimmed, exportFormat, exportRes, exportColorSpace, jpegQuality, exportKeepCapture, exportKeepLocation)
        recipes = recipes.filter { it.name != trimmed } + recipe
        RecipeStore.save(app, recipes)
        toast("已保存配方: $trimmed")
    }

    // 以导出分辨率重新解码原图 → 全分辨率渲染 → 存盘 → 恢复预览会话。
    // 渲染期间并发轮询进度，支持取消。
    private data class InterruptedPreview(val photo: Long, val render: Boolean, val grade: Boolean)

    private fun pendingPreviewWork() = InterruptedPreview(
        photoGeneration,
        renderJob?.isActive == true && publishedRenderRequest != renderRequest,
        wbJob?.isActive == true,
    )

    private fun resumePreview(work: InterruptedPreview) {
        if (cleared || work.photo != photoGeneration) return
        if (work.render) scheduleRender() else if (work.grade) scheduleGrade(commit = false)
    }

    fun export() {
        val e = engine ?: return
        if (!hasPhoto || isExporting || isBatchExporting || busy) return
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        if (params.unreadableColorWheels != null) { fail(app.getString(com.spektralab.android.R.string.wheels_unsupported)); return }
        if (params.unreadableChannelMixer != null) { fail(app.getString(com.spektralab.android.R.string.mixer_unsupported)); return }
        if (params.unreadableColorAdjustments != null) { fail(app.getString(com.spektralab.android.R.string.color_unsupported)); return }
        if (params.unreadableToneCurves != null) { fail(app.getString(com.spektralab.android.R.string.curve_unsupported)); return }
        val interruptedPreview = pendingPreviewWork()
        isExporting = true
        exportCancelRequested = false
        isExportCommitCompleted = false
        val uri = currentUri
        val exportParams = EditSnapshot.capture(params).toParams()
        val options = captureExportOptions()
        val outputCs = options.outputColorSpace
        val outputSize = options.resolution
        renderProgress = 0f
        renderStage = "准备导出"
        viewModelScope.launch {
            engineTasks.run("export") {
                var poller: Job? = null
                var needsRestore = false
                var cacheLease: com.spektralab.android.data.cache.RenderDiskCache.Lease? = null
                try {
                    renderStage = "解码原图"
                    val cachedRequest = withContext(Dispatchers.IO) {
                        uri?.let { renderCache.prepare(it, exportParams, options, ::checkExportCancellation) }
                    }
                    withContext(Dispatchers.IO) {
                        // Assign before the dispatcher handoff so cancellation cannot lose a lease.
                        cacheLease = cachedRequest?.let { renderCache.acquire(it, ::checkExportCancellation) }
                    }
                    val cached = cacheLease
                    var dimensions = ""
                    val path = if (cached != null) {
                        cached.use { result ->
                            RenderAdmission.check(app)
                            dimensions = "${result.width}×${result.height}"
                            sourceExif = withContext(Dispatchers.IO) { uri?.let { readExif(it, options.keepLocation) } }
                            renderProgress = 96f
                            renderStage = "编码并保存文件"
                            android.util.Log.i("SpkCache", "export cache hit ${result.width}x${result.height}")
                            withContext(Dispatchers.IO) { saveNativeResult(result, System.currentTimeMillis().toString(), exportParams, options) }
                        }
                    } else {
                        withContext(engineDispatcher) {
                            RenderAdmission.check(app)
                            needsRestore = true
                            if (uri != null) openExportSource(e, uri, exportParams, outputSize, outputCs, options)
                        }
                        checkExportCancellation()
                        renderProgress = 8f
                        renderStage = "胶片冲印与相纸扫描"
                        val previousProgressId = withContext(engineDispatcher) { e.progressSnapshot().progressId }
                        poller = launch(Dispatchers.IO) {
                            while (isActive) {
                                val progress = runCatching { e.progressSnapshot() }.getOrNull()
                                if (exportCancelRequested) e.cancel()
                                if (progress != null && progress.progressId != previousProgressId) withContext(Dispatchers.Main.immediate) {
                                    renderProgress = max(renderProgress ?: 8f, 8f + progress.percent * .86f).coerceAtMost(94f)
                                    if (progress.done && !exportCancelRequested) renderStage = "准备保存"
                                }
                                delay(120)
                            }
                        }
                        withContext(engineDispatcher) {
                            measureExport("render") { e.renderExportOnce() }.use { result ->
                                poller?.cancelAndJoin()
                                poller = null
                                checkExportCancellation()
                                android.util.Log.i("SpkExport", "render complete ${result.width}x${result.height}; ${e.memoryReport()}")
                                e.closeSession()
                                dimensions = "${result.width}×${result.height}"
                                if (cachedRequest != null && uri != null) {
                                    withContext(Dispatchers.IO) { renderCache.store(cachedRequest, uri, result, ::checkExportCancellation) }
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    renderProgress = 96f
                                    renderStage = "编码并保存文件"
                                }
                                withContext(Dispatchers.IO) { saveNativeResult(result, System.currentTimeMillis().toString(), exportParams, options) }
                            }
                        }
                    }
                    checkExportCancellation()
                    append("导出成功: $dimensions → $path")
                    toast("已导出到相册")
                    renderProgress = 100f
                    renderStage = "导出完成"
                    delay(250)
                } catch (_: ExportCancelled) {
                    append("导出已取消")
                } catch (_: OutOfMemoryError) {
                    fail("导出内存不足，请关闭其他应用或选择较小导出尺寸")
                } catch (ex: SpkException) {
                    if (ex.message?.contains("cancel", ignoreCase = true) == true) append("导出已取消")
                    else {
                        android.util.Log.e("SpkExport", "render/export failed; ${runCatching { e.memoryReport() }.getOrDefault("memory unavailable")}", ex)
                        fail("SpkException: ${ex.message}")
                    }
                } catch (ex: Exception) {
                    fail("${ex::class.simpleName}: ${ex.message}")
                } finally {
                    cacheLease?.close()
                    poller?.cancelAndJoin()
                    val pl = previewLinear
                    if (pl != null && needsRestore) {
                        try {
                            withContext(engineDispatcher) {
                                sourceExif = uri?.let(::readExif)
                                e.open(pl, previewW, previewH, 3, params.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace))
                                // Keep the approved frame; rebuild the negative lazily on the next edit.
                            }
                        } catch (_: OutOfMemoryError) {
                            append("恢复编辑会话时内存不足，请重新打开当前照片")
                        } catch (ex: Exception) {
                            append("恢复预览失败: ${ex.message}")
                        }
                    }
                    renderProgress = null
                    renderStage = ""
                    exportCancelRequested = false
                    isExportCommitCompleted = false
                    isExporting = false
                    resumePreview(interruptedPreview)
                }
            }
        }
    }

    internal fun cancelTableProjectExport() {
        if (!isExporting || isExportCommitCompleted) return
        exportCancelRequested = true
        renderStage = app.getString(com.spektralab.android.R.string.table_output_cancelling)
        if (tableNativeRendering) engine?.cancel()
    }

    /** Uses the same serialized owner, decoder, grade and cache as ordinary photo output. */
    internal fun exportTableProject(request: com.spektralab.android.core.model.TableExportRequest,
        onFinished: (Result<String>) -> Unit): Boolean {
        val e = engine ?: return false
        if (busy || isExporting || isBatchExporting || isApplyingEdits) return false
        prepareVersionBoundary()
        val interrupted = pendingPreviewWork()
        val activeUri = currentUri
        val activeParams = EditSnapshot.capture(committedParams()).toParams()
        val correctLens = lensCorrectionActive
        isExporting = true; exportCancelRequested = false; isExportCommitCompleted = false
        renderProgress = 0f; renderStage = app.getString(com.spektralab.android.R.string.table_output_preparing)
        viewModelScope.launch {
            engineTasks.run("export") {
                var session: com.spektralab.android.data.export.TableExportSession? = null
                var needsRestore = false
                var outcome: Result<String>? = null
                try {
                    withContext(Dispatchers.IO) {
                        session = com.spektralab.android.data.export.TableExportSession.prepare(app, request, ::checkExportCancellation) { index, total ->
                            renderStage = app.getString(com.spektralab.android.R.string.table_output_checking, index + 1, total)
                        }
                    }
                    val transaction = requireNotNull(session)
                    transaction.inputs.forEachIndexed { index, input ->
                        checkExportCancellation()
                        renderStage = app.getString(com.spektralab.android.R.string.table_output_rendering, index + 1, transaction.inputs.size)
                        renderProgress = index * 80f / transaction.inputs.size
                        var bitmap: Bitmap? = null
                        try {
                            if (input.params == null) {
                                withContext(Dispatchers.IO) {
                                    RenderAdmission.check(app)
                                    bitmap = decodeBitmapForExport(Uri.parse(input.source.uri), input.requestedEdge)
                                }
                            } else {
                                val p = input.params.toParams()
                                val geometry = JSONObject(p.openJson(input.evidence.width.coerceAtLeast(1), input.evidence.height.coerceAtLeast(1)))
                                val crop = minOf(geometry.optDouble("geometry_crop_w", 1.0), geometry.optDouble("geometry_crop_h", 1.0)).coerceAtLeast(.01)
                                val resolution = kotlin.math.ceil(input.requestedEdge / crop).toInt().coerceAtLeast(1)
                                check(resolution <= com.spektralab.android.data.export.TableExportSession.MAX_INPUT_EDGE) { "TABLE_EXPORT_CAPACITY" }
                                val options = ExportOptions("PNG", resolution, "sRGB", 100, false, false, correctLens)
                                val uri = Uri.parse(input.source.uri)
                                val cacheRequest = withContext(Dispatchers.IO) { renderCache.prepare(uri, p, options, ::checkExportCancellation) }
                                var lease: com.spektralab.android.data.cache.RenderDiskCache.Lease? = null
                                try {
                                    withContext(Dispatchers.IO) { lease = cacheRequest?.let { renderCache.acquire(it, ::checkExportCancellation) } }
                                    val cached = lease
                                    if (cached != null) withContext(Dispatchers.IO) {
                                        com.spektralab.android.data.export.TableExportSession.checkSourceSize(cached.width, cached.height)
                                        bitmap = com.spektralab.android.data.export.Rgba16Bitmap.convert(cached, grade(p), ::checkExportCancellation)
                                    } else withContext(engineDispatcher) {
                                        RenderAdmission.check(app); needsRestore = true
                                        openExportSource(e, uri, p, resolution, "sRGB", options)
                                        tableNativeRendering = true
                                        try { e.renderExportOnce().use { result ->
                                            checkExportCancellation(); e.closeSession()
                                            withContext(Dispatchers.IO) {
                                                if (cacheRequest != null) renderCache.store(cacheRequest, uri, result, ::checkExportCancellation)
                                                com.spektralab.android.data.export.TableExportSession.checkSourceSize(result.width, result.height)
                                                bitmap = com.spektralab.android.data.export.Rgba16Bitmap.convert(result, grade(p), ::checkExportCancellation)
                                            }
                                        } } finally { tableNativeRendering = false }
                                    }
                                } finally { lease?.close() }
                            }
                            withContext(Dispatchers.IO) { transaction.stage(input, requireNotNull(bitmap)) }
                        } finally { bitmap?.recycle() }
                    }
                    renderProgress = 85f; renderStage = app.getString(com.spektralab.android.R.string.table_output_composing)
                    val path = withContext(Dispatchers.IO) { transaction.publish {
                        isExportCommitCompleted = true; exportCancelRequested = false
                    } }
                    outcome = Result.success(path)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    if (failure !is Exception && failure !is OutOfMemoryError) throw failure
                    outcome = Result.failure(failure)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { session?.close() }
                    if (needsRestore && !cleared) withContext(NonCancellable + engineDispatcher) {
                        runCatching {
                            e.closeSession()
                            previewLinear?.let { pl -> e.open(pl, previewW, previewH, 3,
                                activeParams.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace)) }
                            sourceExif = activeUri?.let(::readExif)
                        }.onFailure { android.util.Log.e("TableExport", "Preview session restore failed", it) }
                    }
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        renderProgress = null; renderStage = ""; exportCancelRequested = false
                        isExportCommitCompleted = false; isExporting = false
                        if (!cleared) {
                            outcome?.let(onFinished)
                            resumePreview(interrupted)
                        }
                    }
                }
            }
        }
        return true
    }

    fun createEngine() {
        if (engine != null) return
        if (engineCreateJob?.isActive == true) return
        busy = true
        engineCreateJob = viewModelScope.launch {
            engineTasks.onEngine {
                try {
                    val created = EngineClient.create(app)
                    if (cleared) { created.close(); return@onEngine }
                    withContext(Dispatchers.Main.immediate) {
                        engine = created
                        append("引擎创建成功 · ${EngineClient.buildInfo()}")
                        val waitingFrame = pendingFrame
                        pendingFrame = null
                        if (waitingFrame != null) renderFrame(created, waitingFrame)
                        else if (photoLoadJob?.isActive != true) busy = false
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    fail("${ex::class.simpleName}: ${ex.message}")
                    busy = false
                }
            }
        }
    }

    /** Invalidate before changing identity; cancellation alone cannot stop synchronous native work. */
    private fun invalidatePhotoWork(): Long {
        wheelStart?.let {
            params.colorWheels = it
            params.unreadableColorWheels = wheelStartUnreadable
        }
        wheelStart = null
        wheelStartUnreadable = null
        mixerStart?.let {
            params.channelMixer = it
            params.unreadableChannelMixer = mixerStartUnreadable
        }
        mixerStart = null
        mixerStartUnreadable = null
        colorStart?.let {
            params.colorAdjustments = it
            params.unreadableColorAdjustments = colorStartUnreadable
        }
        colorStart = null
        colorStartUnreadable = null
        curveStart?.let { params.toneCurves = it }
        curveStart = null
        smartApplyJob?.cancel()
        photoGeneration++
        renderGeneration++
        gradeGeneration++
        photoLoadJob?.cancel()
        pendingPreview = null
        frameRenderJob?.cancel()
        renderJob?.cancel()
        wbJob?.cancel()
        cameraWbJob?.cancel()
        stockPreviewJob?.cancel()
        developedPreviewJob?.cancel()
        pendingFrame = null
        engine?.cancel()
        return photoGeneration
    }

    private fun clearPhotoState() {
        paramsUri = null
        photoInfo = emptyList()
        rawCamXyz = null
        sourceExif = null
        sourceBitmap = null
        rendered = null
        lastResult = null
        histogram = null
        previewLinear = null
        previewW = 0
        previewH = 0
        currentIsRaw = false
        matchedLensProfile = null
        matchedLens = null
        latitudeReadout = null
        latitudeDelta = null
        latitudeCanApply = false
        isCropping = false
        cropEditStart = null
        hasPhoto = false
        canUndo = false
        canRedo = false
        dirty = false
        errorMessage = null
    }

    private fun retainThumbnailRequests(uris: Set<Uri>) {
        thumbnailJobs.keys.filterNot(uris::contains).forEach { uri ->
            thumbnailJobs.remove(uri)?.cancel()
        }
    }

    fun openPhoto(uri: Uri) {
        if (isApplyingEdits) return
        if (isExporting || isBatchExporting) return
        val photo = invalidatePhotoWork()
        currentUri = uri
        smartMessage=""
        clearPhotoState()
        params = EditorParams()
        busy = true
        val importStyle=smartStyle
        val autoDevelop=smartAutoEnabled
        photoLoadJob = viewModelScope.launch {
            try {
                engineTasks.onEngine {
                    if (photo != photoGeneration) return@onEngine
                    val saved = if(autoDevelop) ensureSmartParams(uri, importStyle, true) else EditStore.load(app, uri)
                    val name = queryDisplayName(uri)
                    val exif = readExif(uri)
                    val imageBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, imageBounds) } }
                    val isRaw = RawDecoder.isRaw(app, uri, name)
                    withContext(Dispatchers.Main.immediate) metadata@{
                        if (photo != photoGeneration) return@metadata
                        photoInfo = buildList {
                            add("文件名" to (name ?: "未知"))
                            val width = (exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0).takeIf { it > 0 } ?: imageBounds.outWidth
                            val height = (exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0).takeIf { it > 0 } ?: imageBounds.outHeight
                            if (width > 0 && height > 0) add("原始尺寸" to "$width × $height")
                            listOf("相机" to ExifInterface.TAG_MODEL, "镜头" to ExifInterface.TAG_LENS_MODEL,
                                "拍摄时间" to ExifInterface.TAG_DATETIME_ORIGINAL, "光圈" to ExifInterface.TAG_F_NUMBER,
                                "快门时间（秒）" to ExifInterface.TAG_EXPOSURE_TIME, "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                                "焦距（毫米）" to ExifInterface.TAG_FOCAL_LENGTH).forEach { (label, tag) ->
                                exif?.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { add(label to it) }
                            }
                        }
                        if (saved != null) {
                            params = saved
                            cropAspect = if (saved.cropW >= 1f && saved.cropH >= 1f) "原始" else "自定义"
                            append("已恢复上次编辑")
                        } else {
                            params = EditorParams()
                            resetGeometry()
                        }
                        seedHistory(uri, params.copy())
                        paramsUri = uri
                        sourceExif = exif
                        currentIsRaw = isRaw
                    }
                    if (photo != photoGeneration) return@onEngine
                    if (isRaw) {
                        decodeRawAndOpen(uri, photo)
                    } else {
                        val bmp = decodeSampled(uri, preserveColorSpace = true)
                        if (photo != photoGeneration) { bmp.recycle(); return@onEngine }
                        val linear = ColorPipeline.bitmapToLinearSrgb(bmp)
                        if (photo != photoGeneration) { bmp.recycle(); return@onEngine }
                        withContext(Dispatchers.Main.immediate) publish@{
                            if (photo != photoGeneration) { bmp.recycle(); return@publish }
                            sourceBitmap = bmp
                            append("解码: ${bmp.width}×${bmp.height}")
                            openFrame(linear, bmp.width, bmp.height)
                        }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: OutOfMemoryError) {
                if (photo == photoGeneration) {
                    fail("照片过大，当前设备内存不足以生成预览。请关闭其他应用后重试，或选择较小尺寸导出")
                    busy = false
                }
            } catch (ex: Exception) {
                if (photo == photoGeneration) { fail("${ex::class.simpleName}: ${ex.message}"); busy = false }
            }
        }
    }

    private fun decodeSmartPreview(uri: Uri): Bitmap {
        if (RawDecoder.isRaw(app, uri, queryDisplayName(uri))) {
            com.spektralab.android.data.image.OriginalPreview.raw(app, uri,
                RawDecodeCancellation { cleared || Thread.currentThread().isInterrupted }, 320)?.let { return it }
            RawDecoder.decodeForExport(app,uri,320).use { raw ->
                val argb=ColorPipeline.proPhotoLinearToSrgbArgb(raw.copySmallLinearRgb(),raw.width,raw.height)
                return Bitmap.createBitmap(argb,raw.width,raw.height,Bitmap.Config.ARGB_8888)
            }
        }
        return decodeSampled(uri,320)
    }

    /** No renderer/session work here. The import queue and first-photo load share this lock. */
    private suspend fun ensureSmartParams(uri: Uri, style: SmartDevelopStyle, enabled: Boolean): EditorParams? {
        if(!enabled)return null
        return try {
            smartAnalysisMutex.withLock {
                withContext(Dispatchers.IO) { EditStore.load(app,uri) }?.let { return@withLock it }
                val catalog=withContext(Dispatchers.Main.immediate) { films.toList() to papers.toList() }
                if(catalog.first.isEmpty() || catalog.second.isEmpty())return@withLock null
                val bitmap=withContext(Dispatchers.IO) { decodeSmartPreview(uri) }
                val suggestion=try {
                    withContext(Dispatchers.Default) {
                        SmartDevelop.analyze(bitmap)?.let { SmartDevelop.recommend(EditorParams(),it,style,catalog.first,catalog.second) }
                    }
                } finally { bitmap.recycle() }
                if(suggestion==null)return@withLock null
                if(!currentCoroutineContext().isActive)return@withLock null
                val committed=withContext(Dispatchers.IO) {
                    // A manual/imported sidecar wins even if it appeared during analysis.
                    EditStore.saveIfAbsent(app, uri, suggestion)
                }
                if(committed===suggestion)withContext(Dispatchers.Main.immediate) { freshlySmartDeveloped.add(uri) }
                committed
            }
        } catch(cancelled:CancellationException){ throw cancelled }
        catch(error:Exception){
            if (error is EditStore.StorageException) throw error
            android.util.Log.w("SmartDevelop","Image analysis skipped; original editing remains available",error)
            null
        }
        catch(error:OutOfMemoryError){
            android.util.Log.w("SmartDevelop","Analysis memory budget unavailable; using manual settings")
            null
        }
    }

    private fun queueSmartDevelop(uris: List<Uri>) {
        if(!smartAutoEnabled || uris.isEmpty())return
        smartQueueJob?.cancel()
        val generation=++smartQueueGeneration
        val style=smartStyle
        smartImportTotal=uris.size;smartImportCompleted=0;smartImportRunning=true
        smartQueueJob=viewModelScope.launch {
            try {
                for(uri in uris){
                    if(uri !in photos)continue
                    try {
                        ensureSmartParams(uri,style,true)
                    } catch (error: EditStore.StorageException) {
                        // The foreground open reports the error. A retired/corrupt record must
                        // neither become defaults nor escape this background launch and crash.
                        android.util.Log.w("SmartDevelop", "Saved edit unavailable; automatic analysis preserved it", error)
                    }
                    if(generation==smartQueueGeneration)smartImportCompleted++
                }
            } finally { if(generation==smartQueueGeneration)smartImportRunning=false }
        }
    }

    internal fun applySmartDevelop() {
        if(busy || isExporting || isBatchExporting || !hasPhoto)return
        val bitmap=sourceBitmap ?: return
        val photo=photoGeneration
        val style=smartStyle
        val base=params.copy()
        val catalog=films.toList() to papers.toList()
        busy=true;smartMessage="正在分析当前照片…"
        smartApplyJob?.cancel()
        smartApplyJob=viewModelScope.launch {
            try {
                val result=withContext(Dispatchers.Default) {
                    SmartDevelop.analyze(bitmap)?.let { SmartDevelop.recommend(base,it,style,catalog.first,catalog.second) }
                }
                if(photo!=photoGeneration)return@launch
                if(result==null){smartMessage="这张照片暂时无法分析，可继续手动调整";return@launch}
                params=result
                smartMessage="已应用${style.title} · ${result.smartDevelopSummary}"
                scheduleRender()
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){if(photo==photoGeneration)smartMessage="暂时无法完成分析，可继续手动调整"}
            finally { if(photo==photoGeneration)busy=false }
        }
    }

    /** Picker imports explicitly restore hidden library entries; session navigation does not. */
    fun importPhotos(uris: List<Uri>, addToSession: Boolean = false) {
        if (uris.isEmpty() || busy || isApplyingEdits || isExporting || isBatchExporting) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { EditStore.restoreImportedPhotos(app, uris) }
                if (addToSession) addPhotos(uris, openSelected = true) else openPhotos(uris)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(app.getString(com.spektralab.android.R.string.photos_import_restore_error)) }
        }
    }

    // 打开多张照片（图库），第一张为当前。
    fun openPhotos(uris: List<Uri>) {
        if (isApplyingEdits) return
        if (uris.isEmpty() || isBatchExporting || isExporting) return
        saveCurrentSidecar()
        photos = uris.distinct()
        batchExportSelection = photos.toSet()
        val retained = photos.toSet()
        retainThumbnailRequests(retained)
        thumbnails = thumbnails.filterKeys { it in retained }
        developedPreviews = developedPreviews.filterKeys { it in retained }
        galleryPreviews = galleryPreviews.filterKeys { it in retained }
        currentIndex = 0
        queueSmartDevelop(photos)
        openPhoto(photos[0])
        persistWorkbench()
    }

    /** Queue-only callers can retain the edit; picker callers explicitly open the selected photo. */
    fun addPhotos(uris: List<Uri>, openSelected: Boolean = false) {
        if (isApplyingEdits) return
        if (uris.isEmpty() || isBatchExporting || isExporting) return
        saveCurrentSidecar()
        val merged = (photos + uris).distinct()
        val added = merged.filterNot { it in photos }
        val selected = currentUri
        photos = merged
        queueSmartDevelop(added)
        batchExportSelection = batchExportSelection.intersect(merged.toSet()) + added
        currentIndex = selected?.let { merged.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        if (openSelected) {
            selectPhoto(merged.indexOf(uris.first()))
        } else if (selected == null) openPhoto(merged.first())
        persistWorkbench()
    }

    // 切换到图库中的第 [index] 张。
    fun selectPhoto(index: Int) {
        if (isApplyingEdits) return
        if (index !in photos.indices || isBatchExporting || isExporting) return
        if (index == currentIndex && currentUri == photos[index] && (hasPhoto || photoLoadJob?.isActive == true)) return
        saveCurrentSidecar()
        currentIndex = index
        openPhoto(photos[index])
        persistWorkbench()
    }

    fun previousPhoto() {
        if (currentIndex > 0) selectPhoto(currentIndex - 1)
    }

    fun nextPhoto() {
        if (currentIndex < photos.lastIndex) selectPhoto(currentIndex + 1)
    }

    fun removeCurrentPhoto() {
        if (busy || isExporting || isBatchExporting || currentIndex !in photos.indices) return
        saveCurrentSidecar()
        invalidatePhotoWork()
        val removed = photos[currentIndex]
        val remaining = photos.filterIndexed { index, _ -> index != currentIndex }
        retainThumbnailRequests(remaining.toSet())
        thumbnails = thumbnails - removed
        developedPreviews = developedPreviews - removed
        discardGalleryPreview(removed)
        photos = remaining
        batchExportSelection = batchExportSelection - removed
        if (remaining.isNotEmpty()) {
            currentIndex = currentIndex.coerceAtMost(remaining.lastIndex)
            openPhoto(remaining[currentIndex])
        } else {
            currentUri = null
            currentIndex = 0
            clearPhotoState()
            busy = false
            viewModelScope.launch { engineTasks.onEngine { engine?.closeSession() } }
        }
        persistWorkbench()
    }

    /** Remove a photo from the temporary edit session without touching its source file. */
    fun removePhotoFromSession(uri: Uri) {
        if (busy || isExporting || isBatchExporting) return
        val index = photos.indexOf(uri)
        if (index < 0) return
        if (index == currentIndex) {
            removeCurrentPhoto()
            return
        }
        val remaining = photos.filterNot { it == uri }
        retainThumbnailRequests(remaining.toSet())
        thumbnails = thumbnails - uri
        developedPreviews = developedPreviews - uri
        discardGalleryPreview(uri)
        photos = remaining
        batchExportSelection = batchExportSelection - uri
        if (index < currentIndex) currentIndex -= 1
        persistWorkbench()
        toast("已从当前展厅移除；原始照片未删除")
    }

    // 切换前把当前照片的编辑落盘（避免 120ms 去抖未到就切换而丢编辑）。
    private fun saveCurrentSidecar() {
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        currentUri?.takeIf { it == paramsUri }?.let { EditStore.save(app, it, params) }
    }

    // 懒加载单个缩略图（滚到才解码）。
    fun loadThumbnail(uri: Uri) {
        if (uri !in photos || thumbnails.containsKey(uri) || thumbnailJobs[uri]?.isActive == true) return
        val request = viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { decodeSampled(uri, 200) }
                if (uri in photos && !thumbnails.containsKey(uri)) thumbnails = thumbnails + (uri to bmp)
                else bmp.recycle()
            } catch (ex: CancellationException) {
                throw ex
            } catch (e: Exception) {
                // 忽略单个缩略图失败
            } finally {
                if (thumbnailJobs[uri] === currentCoroutineContext()[Job]) thumbnailJobs.remove(uri)
            }
        }
        thumbnailJobs[uri] = request
    }

    fun setBatchExportPhotoSelected(uri: Uri, selected: Boolean) {
        if (busy || isExporting || isBatchExporting || uri !in photos) return
        batchExportSelection = if (selected) batchExportSelection + uri else batchExportSelection - uri
        persistWorkbench()
    }

    fun selectAllBatchExportPhotos(selected: Boolean) {
        if (busy || isExporting || isBatchExporting) return
        batchExportSelection = if (selected) photos.toSet() else emptySet()
        persistWorkbench()
    }

    // Snapshot selected session photos in queue order; preserve each photo's own edits.
    fun batchExport() {
        val e = engine ?: return
        if (busy || isBatchExporting || isExporting) return
        val batchPhotos = selectedBatchExportPhotos
        if (batchPhotos.isEmpty()) return
        val options = captureExportOptions()
        val batchSmartStyle = smartStyle
        val batchAutoDevelop = smartAutoEnabled
        val interruptedPreview = pendingPreviewWork()
        isBatchExporting = true
        exportCancelRequested = false
        isExportCommitCompleted = false
        renderProgress = 0f
        renderStage = "准备批量导出"
        saveCurrentSidecar()
        val activeUri = currentUri
        val activeParams = EditSnapshot.capture(params).toParams()
        val batchEdits = batchPhotos.associateWith { uri ->
            (if (uri == activeUri) activeParams else EditStore.load(app, uri))?.let(EditSnapshot::capture)
        }
        viewModelScope.launch {
            engineTasks.onEngine("batch-export") {
                val batchTs = System.currentTimeMillis()
                var done = 0
                try {
                    val outputCs = options.outputColorSpace
                    // Resolve missing smart edits before any output; later sidecar writes cannot change this batch.
                    val edits = batchPhotos.associateWith { uri ->
                        batchEdits[uri] ?: EditSnapshot.capture(
                            ensureSmartParams(uri, batchSmartStyle, batchAutoDevelop) ?: EditorParams()
                        )
                    }
                    for ((i, uri) in batchPhotos.withIndex()) {
                        checkExportCancellation()
                        isExportCommitCompleted = false
                        withContext(Dispatchers.Main.immediate) {
                            renderStage = "第 ${i + 1}/${batchPhotos.size} 张：胶片冲印与相纸扫描"
                        }
                        val photoParams = edits.getValue(uri).toParams()
                        check(photoParams.unreadableColorWheels == null) { app.getString(com.spektralab.android.R.string.wheels_unsupported) }
                        check(photoParams.unreadableChannelMixer == null) { app.getString(com.spektralab.android.R.string.mixer_unsupported) }
                        check(photoParams.unreadableColorAdjustments == null) { app.getString(com.spektralab.android.R.string.color_unsupported) }
                        check(photoParams.unreadableToneCurves == null) { app.getString(com.spektralab.android.R.string.curve_unsupported) }
                        val request = renderCache.prepare(uri, photoParams, options, ::checkExportCancellation)
                        val cached = request?.let { renderCache.acquire(it, ::checkExportCancellation) }
                        var dimensions = ""
                        val path = if (cached != null) {
                            cached.use { r ->
                                RenderAdmission.check(app)
                                checkExportCancellation()
                                sourceExif = readExif(uri, options.keepLocation)
                                dimensions = "${r.width}×${r.height}"
                                android.util.Log.i("SpkCache", "batch cache hit ${r.width}x${r.height}")
                                saveNativeResult(r, "$batchTs-$i", photoParams, options)
                            }
                        } else {
                            RenderAdmission.check(app)
                            openExportSource(e, uri, photoParams, options.resolution, outputCs, options)
                            checkExportCancellation()
                            measureExport("render") { e.renderExportOnce() }.use { r ->
                                checkExportCancellation()
                                e.closeSession()
                                dimensions = "${r.width}×${r.height}"
                                if (request != null) renderCache.store(request, uri, r, ::checkExportCancellation)
                                saveNativeResult(r, "$batchTs-$i", photoParams, options)
                            }
                        }
                        done++
                        withContext(Dispatchers.Main.immediate) {
                            renderProgress = done.toFloat() / batchPhotos.size * 100f
                            append("批量导出 $done/${batchPhotos.size}: $dimensions → $path")
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        toast("批量导出完成 ($done 张)")
                    }
                } catch (_: ExportCancelled) {
                    withContext(Dispatchers.Main.immediate) { append("批量导出已取消") }
                } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main.immediate) { fail("导出内存不足，请关闭其他应用或选择较小导出尺寸") }
                } catch (ex: SpkException) {
                    withContext(Dispatchers.Main.immediate) {
                        if (ex.message?.contains("cancel", ignoreCase = true) == true) append("批量导出已取消")
                        else fail("SpkException: ${ex.message}")
                    }
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main.immediate) { fail("${ex::class.simpleName}: ${ex.message}") }
                } finally {
                    // 恢复当前照片预览。
                    val pl = previewLinear
                    if (pl != null) {
                        try {
                            withContext(Dispatchers.Main.immediate) { params = activeParams }
                            sourceExif = activeUri?.let(::readExif)
                            e.open(pl, previewW, previewH, 3, activeParams.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace))
                        } catch (_: OutOfMemoryError) {
                            withContext(Dispatchers.Main.immediate) { append("恢复编辑会话时内存不足，请重新打开当前照片") }
                        } catch (ex: Exception) {
                            withContext(Dispatchers.Main.immediate) { append("恢复预览失败: ${ex.message}") }
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        isBatchExporting = false
                        renderProgress = null
                        renderStage = ""
                        exportCancelRequested = false
                        isExportCommitCompleted = false
                        resumePreview(interruptedPreview)
                    }
                }
            }
        }
    }

    private fun runExportAction(
        label: String,
        maxLongEdge: Int,
        outputColorSpace: String,
        action: suspend (EngineClient, EditorParams, Long, Long, ExportOptions) -> Unit,
    ) {
        val e = engine ?: return
        if (!hasPhoto || busy || isExporting || isBatchExporting) return

        val uri = currentUri
        val editParams = EditSnapshot.capture(params).toParams()
        val options = captureExportOptions()
        val photo = photoGeneration
        val revision = renderGeneration
        val previewPixels = previewLinear
        val previewWidth = previewW
        val previewHeight = previewH
        val previewInputSpace = previewInputColorSpace
        val interruptedPreview = pendingPreviewWork()
        isExporting = true
        exportCancelRequested = false
        isExportCommitCompleted = false
        renderProgress = 0f
        renderStage = "准备$label"

        viewModelScope.launch {
            engineTasks.run("export-action") {
                var progressJob: Job? = null
                try {
                    renderStage = "读取原片"
                    withContext(engineDispatcher) {
                        if (uri != null) {
                            RenderAdmission.check(app)
                            openExportSource(e, uri, editParams, maxLongEdge, outputColorSpace, options)
                        } else {
                            e.setParams(JSONObject().put("output_color_space", outputColorSpace).toString())
                        }
                    }
                    checkExportCancellation()
                    renderProgress = 8f
                    renderStage = "正在$label"
                    val previousProgressId = withContext(engineDispatcher) { e.progressSnapshot().progressId }
                    progressJob = launch(Dispatchers.IO) {
                        while (isActive) {
                            val progress = runCatching { e.progressSnapshot() }.getOrNull()
                            if (exportCancelRequested) e.cancel(progress?.progressId)
                            if (progress != null && progress.progressId != previousProgressId) {
                                withContext(Dispatchers.Main.immediate) {
                                    renderProgress = max(renderProgress ?: 8f, 8f + progress.percent * .86f).coerceAtMost(94f)
                                    if (progress.done && !exportCancelRequested) renderStage = "完成渲染"
                                }
                            }
                            delay(120)
                        }
                    }

                    action(e, editParams, photo, revision, options)
                    checkExportCancellation()
                    renderProgress = 100f
                    renderStage = "$label 完成"
                    delay(250)
                } catch (_: ExportCancelled) {
                    append("$label 已取消")
                } catch (ex: CancellationException) {
                    throw ex
                } catch (_: OutOfMemoryError) {
                    fail("$label 内存不足，请选择较小尺寸后重试")
                } catch (ex: SpkException) {
                    if (exportCancelRequested || ex.message?.contains("cancel", ignoreCase = true) == true) {
                        append("$label 已取消")
                    } else {
                        fail("SpkException: ${ex.message}")
                    }
                } catch (ex: Exception) {
                    if (exportCancelRequested) append("$label 已取消")
                    else fail("${ex::class.simpleName}: ${ex.message}")
                } finally {
                    withContext(NonCancellable) {
                        try {
                            progressJob?.cancelAndJoin()
                            if (previewPixels != null && photo == photoGeneration) {
                                renderStage = "恢复编辑预览"
                                withContext(engineDispatcher) {
                                    e.open(
                                        previewPixels,
                                        previewWidth,
                                        previewHeight,
                                        3,
                                        editParams.openJson(previewWidth, previewHeight, inputColorSpace = previewInputSpace),
                                    )
                                }
                            }
                        } catch (_: OutOfMemoryError) {
                            fail("恢复编辑会话时内存不足，请重新打开当前照片")
                        } catch (ex: Exception) {
                            fail("恢复编辑预览失败: ${ex.message}")
                        } finally {
                            renderProgress = null
                            renderStage = ""
                            exportCancelRequested = false
                            isExportCommitCompleted = false
                            isExporting = false
                            resumePreview(interruptedPreview)
                        }
                    }
                }
            }
        }
    }

    // DI TIFF：按导出分辨率读取原片，输出 Cineon log 16-bit，不套 Layer 2、不嵌 ICC。
    fun exportDi() {
        runExportAction("DI TIFF", exportRes, "sRGB") { e, _, _, _, options ->
            val result = withContext(engineDispatcher) { e.renderDigitalIntermediate() }
            try {
                checkExportCancellation()
                renderProgress = 96f
                renderStage = "写入 DI TIFF"
                val path = withContext(Dispatchers.IO) {
                    saveDiTiff(result, System.currentTimeMillis().toString(), options)
                }
                append("DI 导出: ${result.width}×${result.height} → $path")
                toast("DI 已导出")
            } finally {
                result.close()
            }
        }
    }

    // 软打样：按导出尺寸/色彩空间重渲染并显示，不保存文件。
    fun softProof() {
        val outputCs = if (exportFormat == "TIFF") exportColorSpace else "sRGB"
        val previewLongEdge = if (exportRes <= 0) 3400 else exportRes.coerceAtMost(3400)
        runExportAction("软打样", previewLongEdge, outputCs) { e, editParams, photo, revision, _ ->
            val result = withContext(engineDispatcher) { e.render("full") }
            checkExportCancellation()
            updateResult(result, outputCs, photo, revision, editParams)
            append("软打样: ${result.width}×${result.height}  ${result.elapsedMs}ms")
        }
    }

    // 高清预览：通过原片解码器直接限制到 3400px，避免 RAW 全尺寸 managed float buffer。
    fun previewTier() {
        val outputCs = if (exportFormat == "TIFF") exportColorSpace else "sRGB"
        runExportAction("高清预览", 3400, outputCs) { e, editParams, photo, revision, _ ->
            val result = withContext(engineDispatcher) { e.render("preview") }
            checkExportCancellation()
            updateResult(result, outputCs, photo, revision, editParams)
            append("高清预览: ${result.width}×${result.height}  ${result.elapsedMs}ms")
        }
    }

    fun openTestImage() {
        if (isExporting || isBatchExporting) return
        smartQueueGeneration++;smartQueueJob?.cancel();smartImportRunning=false
        invalidatePhotoWork()
        clearPhotoState()
        currentUri = null
        retainThumbnailRequests(emptySet())
        rawCamXyz = null
        sourceBitmap = null
        sourceExif = null
        photos = emptyList()
        batchExportSelection = emptySet()
        thumbnails = emptyMap()
        developedPreviews = emptyMap()
        galleryPreviews = emptyMap()
        currentIndex = 0
        resetGeometry()
        val w = 512; val h = 512
        val px = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            px[i + 0] = x / (w - 1f)
            px[i + 1] = y / (h - 1f)
            px[i + 2] = 0.5f
        }
        openFrame(px, w, h)
    }

    fun release() {
        viewModelScope.launch {
            engineTasks.onEngine {
                engine?.close()
                engine = null
                rendered = null
                sourceBitmap = null
                hasPhoto = false
                isCropping = false
                append("已释放")
            }
        }
    }

    private fun captureExportOptions() = ExportOptions(
        exportFormat, exportRes, exportColorSpace, jpegQuality,
        exportKeepCapture, exportKeepLocation, lensCorrectionActive,
    )

    private fun saveBitmap(bitmap: Bitmap, suffix: String, options: ExportOptions): String {
        val ext = if (options.format == "PNG") "png" else "jpg"
        val mime = if (options.format == "PNG") "image/png" else "image/jpeg"
        val name = "Theia_$suffix.$ext"
        val fmt = if (options.format == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

        // 先压缩到临时文件（JPEG 需先落盘才能附加 EXIF）。
        val tmp = File(app.cacheDir, "export_${System.nanoTime()}.$ext")
        try {
            FileOutputStream(tmp).use { fos ->
                check(bitmap.compress(fmt, options.jpegQuality.coerceIn(1, 100), fos)) { "图片编码失败" }
            }
            ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation).attach(tmp, bitmap.width, bitmap.height)
            return publishExport(tmp, name, mime)
        } finally {
            tmp.delete()
        }
    }

    // 按当前格式导出渲染结果（TIFF 走 16-bit，其余走 8-bit JPEG/PNG）。
    private fun saveNativeResult(result: Rgba16Source, suffix: String, editParams: EditorParams, options: ExportOptions): String {
        return measureExport("encode_publish_${options.format}") { encodeNativeResult(result, suffix, editParams, options) }
    }

    private fun encodeNativeResult(result: Rgba16Source, suffix: String, editParams: EditorParams, options: ExportOptions): String {
        if (options.format == "TIFF") {
            val icc = if (options.colorSpace == "Display P3") DisplayP3Icc.generate() else readSrgbIcc()
            return exportPublisher().write("Theia_$suffix.tiff", "image/tiff") {
                TiffWriter.writeNative(result, grade(editParams), icc, it, ::checkExportCancellation,
                    ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation))
            }
        }
        val bitmap = com.spektralab.android.data.export.Rgba16Bitmap.convert(result, grade(editParams), ::checkExportCancellation)
        try { return saveBitmap(bitmap, suffix, options) } finally { bitmap.recycle() }
    }

    private fun exportPublisher() = ExportPublisher(app, ::checkExportCancellation) {
        exportCancelRequested = false
        isExportCommitCompleted = true
    }

    private fun publishExport(tmp: File, name: String, mime: String): String =
        exportPublisher().write(name, mime) { destination ->
            tmp.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkExportCancellation()
                    val count = source.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                }
            }
        }

    private fun saveResult(result: SpkResult, suffix: String, editParams: EditorParams = params,
                           options: ExportOptions = captureExportOptions()): String {
        return if (options.format == "TIFF") {
            saveTiff(result, suffix, editParams, options)
        } else {
            saveBitmap(convertToBitmap(result, editParams), suffix, options)
        }
    }

    private fun saveTiff(result: SpkResult, suffix: String, editParams: EditorParams, options: ExportOptions): String {
        val g = grade(editParams)
        val icc = if (options.colorSpace == "Display P3") DisplayP3Icc.generate() else readSrgbIcc()
        return exportPublisher().write("Theia_$suffix.tiff", "image/tiff") { out ->
            TiffWriter.writeRgba16(result.rgba16, result.width, result.height, g, icc, out, ::checkExportCancellation,
                ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation))
        }
    }

    // DI TIFF：无调色、无 ICC（设备 RGB 密度）。
    private fun saveDiTiff(result: NativeRenderResult, suffix: String, options: ExportOptions): String {
        val name = "Theia_DI_$suffix.tiff"
        return exportPublisher().write(name, "image/tiff") { out ->
            TiffWriter.writeNative(
                result,
                grade = null,
                icc = null,
                out = out,
                checkCancelled = ::checkExportCancellation,
                metadata = ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation),
            )
        }
    }

    private fun readSrgbIcc(): ByteArray? = try {
        app.assets.open("engine/resources/io/sRGB.icc").use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    // 取 MediaStore 的 DISPLAY_NAME（文件名），用于判断是否 RAW。
    private fun queryDisplayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    // 读取源 EXIF（仅 JPEG/TIFF/DNG；RAW 抛异常时返回 null）。
    private fun readExif(uri: Uri, keepLocation: Boolean = exportKeepLocation): ExifInterface? {
        val source = runCatching { EditStore.editSourceUri(app, uri) }.getOrNull() ?: return null
        if (Build.VERSION.SDK_INT >= 29 && keepLocation && source.authority == "media" &&
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { app.contentResolver.openInputStream(MediaStore.setRequireOriginal(source))?.use(::ExifInterface) }
                .getOrNull()?.let { return it }
        }
        return runCatching { app.contentResolver.openInputStream(source)?.use(::ExifInterface) }.getOrNull()
    }

    private fun decodeSampled(uri: Uri, maxLongEdge: Int = 2048, preserveColorSpace: Boolean = false): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (!preserveColorSpace) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val edge = max(info.size.width, info.size.height)
                if (edge > maxLongEdge) {
                    val scale = maxLongEdge.toDouble() / edge
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        if (!preserveColorSpace) opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        val bitmap = app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("无法解码此图片，请选择有效的 JPEG、PNG、WebP 或支持的 RAW 文件")
        val orientation = readExif(uri)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun decodeBitmapForExport(uri: Uri, maxLongEdge: Int): Bitmap {
        val bitmap = decodeSampled(uri, if (maxLongEdge == 0) Int.MAX_VALUE else maxLongEdge)
        val edge = max(bitmap.width, bitmap.height)
        if (maxLongEdge == 0 || edge <= maxLongEdge) return bitmap
        val scale = maxLongEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    private fun resizeRaw(raw: RawDecodeResult, maxLongEdge: Int): RawDecodeResult {
        val edge = max(raw.width, raw.height)
        if (maxLongEdge == 0 || edge <= maxLongEdge) return raw
        val scale = maxLongEdge.toDouble() / edge
        val width = (raw.width * scale).roundToInt().coerceAtLeast(1)
        val height = (raw.height * scale).roundToInt().coerceAtLeast(1)
        val output = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val sy = ((y + 0.5) * raw.height / height - 0.5).coerceIn(0.0, (raw.height - 1).toDouble())
            val y0 = sy.toInt(); val y1 = (y0 + 1).coerceAtMost(raw.height - 1); val fy = sy - y0
            for (x in 0 until width) {
                val sx = ((x + 0.5) * raw.width / width - 0.5).coerceIn(0.0, (raw.width - 1).toDouble())
                val x0 = sx.toInt(); val x1 = (x0 + 1).coerceAtMost(raw.width - 1); val fx = sx - x0
                val dst = (y * width + x) * 3
                for (c in 0..2) {
                    val a = raw.linearProPhoto[(y0 * raw.width + x0) * 3 + c]
                    val b = raw.linearProPhoto[(y0 * raw.width + x1) * 3 + c]
                    val d = raw.linearProPhoto[(y1 * raw.width + x0) * 3 + c]
                    val e = raw.linearProPhoto[(y1 * raw.width + x1) * 3 + c]
                    output[dst + c] = ((a * (1.0 - fx) + b * fx) * (1.0 - fy) + (d * (1.0 - fx) + e * fx) * fy).toFloat()
                }
            }
        }
        return RawDecodeResult(width, height, output, raw.make, raw.model, raw.lens, raw.focal, raw.camMul, raw.camXyz)
    }

    override fun onCleared() {
        smartQueueJob?.cancel();smartApplyJob?.cancel()
        cleared = true
        invalidatePhotoWork()
        galleryPreviewJobs.values.forEach(Job::cancel)
        galleryPreviewJobs.clear()
        (thumbnails.values + developedPreviews.values + galleryPreviews.values + viewingPhotos.map { it.second })
            .distinctBy(System::identityHashCode)
            .forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        renderJob?.cancel()
        wbJob?.cancel()
        cameraWbJob?.cancel()
        val client = engine
        engine = null
        if (client != null) {
            CoroutineScope(SupervisorJob() + engineDispatcher).launch { engineTasks.onEngine("close") { client.close() } }
        }
        super.onCleared()
    }

    private companion object {
        const val GALLERY_PREVIEW_CACHE_SIZE = 6
    }
}
