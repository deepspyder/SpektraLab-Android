package com.spektralab.android

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.DisplayP3Icc
import com.spektralab.android.core.geometry.computeCenteredCrop
import com.spektralab.android.core.geometry.computeCenteredCropByAspect
import com.spektralab.android.core.geometry.overscanGateAspect
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportRecipe
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.local.RecipeStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.editorOptionLabel
import com.spektralab.android.ui.components.captureClock
import com.spektralab.android.ui.components.captureReadouts
import com.spektralab.android.ui.components.sourcePhotoInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/** Explicit opt-in device audit. Uses the packaged engine and real Android decoders/writers. */
class FunctionalAuditInstrumentation : Instrumentation() {
    private val rows = JSONArray()
    private lateinit var output: File
    private var compareOnly = false
    private var progressOnly = false
    private var exportOnly = false
    private var exportActionsOnly = false
    private var largeExportOnly = false
    private var assetBatchOnly = false
    private var largeExportResolution = 0
    private var largeExportInput = "repro-large-3072x4096.jpg"
    private var largeExportFormat = "JPEG"
    private var largeExportEdges = false
    private var exportMemoryOnly = false
    private var galleryOnly = false
    private var themeOnly = false
    private var batchSelectionOnly = false
    private var stockLibraryOnly = false
    private var editingFeedbackOnly = false
    private var smartDevelopOnly = false
    private var canvasJoiningOnly = false
    private var homeViewingStandOnly = false
    private var exportLayoutOnly = false
    private var theiaBrandOnly = false
    private var holdOriginalOnly = false
    private var nefImportOnly = false
    private var fullExportPerfOnly = false
    private var rawInputAccessOnly = false
    private var rawCancellationOnly = false
    private var exportEncodingOnly = false
    private var renderFoundationOnly = false
    private var renderSchedulingOnly = false
    private var exportCacheOnly = false
    private var fullSizeCacheOnly = false
    private val w = 512
    private val h = 384
    private val input = FloatArray(w * h * 3) { i ->
        val x = (i / 3) % w; val y = i / 3 / w
        when (i % 3) { 0 -> 0.01f + x.toFloat() / w; 1 -> 0.01f + y.toFloat() / h; else -> 0.18f }
    }
    override fun onCreate(arguments: Bundle?) {
        val audit = arguments?.getString("audit")
        smartDevelopOnly = audit == "smart-develop"
        canvasJoiningOnly = audit == "canvas-joining"
        homeViewingStandOnly = audit == "home-viewing-stand"
        exportLayoutOnly = audit == "export-layout"
        theiaBrandOnly = audit == "theia-brand"
        holdOriginalOnly = audit == "hold-original"
        nefImportOnly = audit == "nef-import"
        fullExportPerfOnly = audit == "full-export-perf"
        rawInputAccessOnly = audit == "raw-input-access"
        rawCancellationOnly = audit == "raw-cancellation"
        exportEncodingOnly = audit == "export-encoding"
        renderFoundationOnly = audit == "render-foundation"
        renderSchedulingOnly = audit == "render-scheduling"
        exportCacheOnly = audit == "export-cache"
        fullSizeCacheOnly = audit == "export-cache-raw"
        editingFeedbackOnly = audit == "editing-feedback"
        importSelectionOnly = audit == "import-selection"
        refactorOnly = audit == "ui-refactor" || audit == "ui-layout" || audit == "photo-stage" || audit == "home-ui"
        homeUiOnly = audit == "home-ui"
        photoStageOnly = audit == "photo-stage"
        layoutOnly = audit == "ui-layout"
        nativeOnly = audit == "native"
        effectsOnly = audit == "effect-baselines"
        reviewImageAsset = arguments?.getString("reviewImage")
        reviewImageSample = arguments?.getString("reviewSample")?.toIntOrNull() ?: 16
        compareOnly = audit == "compare"
        progressOnly = audit == "progress"
        exportOnly = audit == "export"
        exportActionsOnly = audit == "export-actions"
        largeExportOnly = audit == "large-export"
        assetBatchOnly = audit == "assets-batch"
        exportMemoryOnly = audit == "export-memory"
        galleryOnly = audit == "gallery"
        themeOnly = audit == "theme"
        batchSelectionOnly = audit == "batch-selection"
        stockLibraryOnly = audit == "stock-library"
        largeExportResolution = arguments?.getString("exportRes")?.toIntOrNull() ?: 0
        largeExportInput = arguments?.getString("input") ?: largeExportInput
        largeExportFormat = arguments?.getString("format") ?: largeExportFormat
        largeExportEdges = arguments?.getString("edges") == "true"
        super.onCreate(arguments); start()
    }
    private var refactorOnly = false
    private var importSelectionOnly = false
    private var photoStageOnly = false
    private var homeUiOnly = false
    private var layoutOnly = false
    private var nativeOnly = false
    private var effectsOnly = false
    private var reviewImageAsset: String? = null
    private var reviewImageSample = 16

    override fun onStart() {
        output = File(targetContext.getExternalFilesDir(null), "functional-audit").apply { mkdirs() }
        if (renderSchedulingOnly || exportCacheOnly || fullSizeCacheOnly) {
            if (renderSchedulingOnly) case("render.priority_state_and_admission") { RenderSchedulingAudit.run() }
            if (exportCacheOnly) case("export.production_cache") { ExportCacheAudit(this).run() }
            if (fullSizeCacheOnly) case("export.full_size_raw_cache") { FullSizeCacheAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (renderFoundationOnly) {
            case("render.snapshots_transactions_and_exports") { RenderFoundationAudit(this).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (exportEncodingOnly) {
            case("export.encoding_and_atomic_publication") { ExportEncodingAudit(this).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (rawCancellationOnly) {
            case("raw.cancel_full_size_decode") { RawCancellationAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (rawInputAccessOnly) {
            case("raw.file_descriptor_and_pipe") { RawInputAccessAudit(this).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (fullExportPerfOnly) {
            case("export.full_resolution_policy") { FullResolutionExportAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (nefImportOnly) {
            case("raw.nikon_z6ii_camera_files") { NefImportAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (holdOriginalOnly) {
            case("preview.hold_original_and_restore") { HoldOriginalAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if(theiaBrandOnly){
            case("brand.theia_and_upgrade_compatibility"){TheiaBrandAudit(this,output).run()}
            val failed=(0 until rows.length()).count{rows.getJSONObject(it).optString("status")=="FAIL"}
            finish(if(failed==0)Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply{putString("stream",rows.toString(2))})
            return
        }
        if(exportLayoutOnly){
            case("export.advanced_layout"){ExportLayoutAudit(this,output).run()}
            val failed=(0 until rows.length()).count{rows.getJSONObject(it).optString("status")=="FAIL"}
            finish(if(failed==0)Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply{putString("stream",rows.toString(2))})
            return
        }
        if(homeViewingStandOnly){
            case("home.viewing_stand_entry"){HomeViewingStandAudit(this,output).run()}
            val failed=(0 until rows.length()).count{rows.getJSONObject(it).optString("status")=="FAIL"}
            finish(if(failed==0)Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply{putString("stream",rows.toString(2))})
            return
        }
        if(canvasJoiningOnly){
            case("gallery.snap_and_translucent_overlap"){CanvasJoiningAudit(this,output).run()}
            val failed=(0 until rows.length()).count{rows.getJSONObject(it).optString("status")=="FAIL"}
            finish(if(failed==0)Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply{putString("stream",rows.toString(2))})
            return
        }
        if(smartDevelopOnly){
            case("smart.per_photo_import_and_styles"){SmartDevelopAudit(this,output).run()}
            val failed=(0 until rows.length()).count{rows.getJSONObject(it).optString("status")=="FAIL"}
            finish(if(failed==0)Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply{putString("stream",rows.toString(2))})
            return
        }
        if (editingFeedbackOnly) {
            case("editing.crop_metadata_vignette") { EditingFeedbackAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (stockLibraryOnly) {
            case("ui.stock_library_and_paper_pairing") { StockLibraryAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (batchSelectionOnly) {
            case("export.selected_photos_and_selection_ui") { BatchExportAudit(this, output).run() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (themeOnly) {
            case("ui.theme.neutral_home_and_darkroom") { ThemeAudit(this, output).run() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (importSelectionOnly) {
            case("import.selection_and_async_state") { PhotoImportAudit(this, output).run() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (galleryOnly) {
            galleryAudit()
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (effectsOnly) {
            targetedEffectAudit()
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (nativeOnly) {
            nativeAudit()
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (refactorOnly) {
            case("ui.refactor_workflow") { refactoredUiAudit() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (progressOnly) {
            case("progress.live_snapshot_is_thread_safe_monotonic") { isolatedProgressAudit() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply {putString("stream", rows.toString(2))})
            return
        }
        if (exportOnly) {
            case("export.progress_phases_and_completion") { isolatedExportProgressAudit() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply {putString("stream", rows.toString(2))})
            return
        }
        if (exportActionsOnly) {
            case("export.quick_actions_tiff_and_session_restore") { exportActionAudit() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (largeExportOnly) {
            case("export.$largeExportInput.$largeExportFormat.res_$largeExportResolution.edges_$largeExportEdges") { isolatedLargeExportAudit() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (assetBatchOnly) {
            case("export.assets_jpeg_and_raw.full.batch") { assetBatchExportAudit() }
            writeReport()
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (exportMemoryOnly) {
            case("export.native_input_output_and_tiff_parity") { nativeExportParityAudit() }
            val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", rows.toString(2)) })
            return
        }
        if (compareOnly) {
            case("ui.before_after") { comparisonAudit() }
            finish(Activity.RESULT_OK, Bundle().apply {putString("stream", rows.toString(2))})
            return
        }
        case("color.p3_icc") {
            val icc=DisplayP3Icc.generate();File(output,"DisplayP3.icc").writeBytes(icc)
            val b=ByteBuffer.wrap(icc).order(ByteOrder.BIG_ENDIAN);check(b.getInt(0)==icc.size)
            val tags=mutableMapOf<String,Int>()
            repeat(b.getInt(128)){i->val off=132+i*12;val name=String(icc,off,4,Charsets.US_ASCII);val start=b.getInt(off+4);check(start%4==0);tags[name]=start}
            for(tag in listOf("rTRC","gTRC","bTRC")){val off=tags[tag]!!;check(String(icc,off,4,Charsets.US_ASCII)=="curv");check(b.getInt(off+8)>=256)}
            for(c in 0..2){val sum=listOf("rXYZ","gXYZ","bXYZ").sumOf{b.getInt(tags[it]!!+8+c*4)/65536.0};val white=b.getInt(tags["wtpt"]!!+8+c*4)/65536.0;check(abs(sum-white)<0.0001)}
            "${icc.size} bytes; v2 curves, aligned tags, colorants sum to D50"
        }
        try { nativeAudit(); editorAudit(); uiAudit() } catch (t: Throwable) { case("audit.unhandled") { throw t } }
        writeReport()
        val failed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "FAIL" }
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "\n${rows.length()} cases; $failed failed. Report: ${output.absolutePath}/results.json\n") })
    }
    private fun case(name: String, body: () -> String) {
        val start = SystemClock.elapsedRealtime()
        val row = JSONObject().put("case", name)
        try { row.put("status", "PASS").put("detail", body()) }
        catch (t: Throwable) { row.put("status", "FAIL").put("detail", "${t.javaClass.simpleName}: ${t.message}"); Log.e("SpkAudit", name, t) }
        row.put("ms", SystemClock.elapsedRealtime() - start); rows.put(row)
        Log.i("SpkAudit", row.toString()); writeReport()
        sendStatus(0, Bundle().apply { putString("stream", "${row.getString("status")} $name: ${row.optString("detail")}\n") })
    }
    private fun writeReport() { File(output, "results.json").writeText(rows.toString(2)) }
    private fun valid(r: SpkResult): String {
        check(r.width > 0 && r.height > 0 && r.rgba16.size == r.width * r.height * 4)
        val rgb = r.rgba16.filterIndexed { i, _ -> i % 4 != 3 }.map { it.toInt() and 65535 }
        check(rgb.maxOrNull()!! > rgb.minOrNull()!!) { "flat/empty output" }
        return "${r.width}x${r.height}; reprint=${r.reprint}; engine=${r.elapsedMs}ms; hash=${r.rgba16.contentHashCode()}"
    }
    private fun isolatedProgressAudit(): String {
        val width=1024;val height=768
        val pixels=FloatArray(width*height*3){i->when(i%3){0->((i/3)%width)/width.toFloat();1->(i/3/width)/height.toFloat();else->.2f}}
        EngineClient.create(targetContext).use { engine ->
            engine.open(pixels,width,height,3,JSONObject(EditorParams().openJson(width,height)).put("glare_active",false).toString())
            val before=engine.progressSnapshot().progressId
            val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val future=pool.submit<SpkResult>{engine.render("full")}
                var renderId=before;var previous=-1f;var peak=0f;var samples=0
                val started=SystemClock.elapsedRealtime()
                while(!future.isDone) {
                    val state=engine.progressSnapshot()
                    if(state.progressId!=before) {
                        if(state.progressId!=renderId){renderId=state.progressId;previous=-1f}
                        check(state.percent>=previous){"progress moved backwards: $previous -> ${state.percent}"}
                        previous=state.percent;peak=maxOf(peak,state.percent);samples++
                    }
                    check(SystemClock.elapsedRealtime()-started<30000){"full render timed out"}
                    Thread.sleep(3)
                }
                valid(future.get(2,TimeUnit.SECONDS))
                val completed=engine.progressSnapshot()
                check(completed.done&&completed.percent==100f&&samples>=2&&peak>0f) {
                    "live snapshots=$samples peak=$peak done=${completed.done} percent=${completed.percent}"
                }
                return "$samples concurrent snapshots; monotonic to 100%"
            } finally {pool.shutdownNow();engine.closeSession()}
        }
    }
    private fun isolatedExportProgressAudit(): String {
        val app=targetContext.applicationContext as android.app.Application
        val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        runOnMainSync{vm=androidx.lifecycle.ViewModelProvider(activity)[EditorViewModel::class.java]}
        await{vm.engine!=null&&!vm.busy}
        val width=768;val height=512
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width*height){i->-0x1000000 or ((i%width*255/width) shl 16) or ((i/width*255/height) shl 8) or 90},0,width,0,0,width,height)
        val imageFile=File(output,"export-progress.jpg")
        imageFile.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,92,it)}
        bitmap.recycle()
        val uri=Uri.fromFile(imageFile)
        EditStore.save(app,uri,EditorParams())
        runOnMainSync{vm.openPhotos(listOf(uri))}
        await{vm.lastResult!=null&&!vm.busy}
        runOnMainSync{vm.exportFormat="JPEG";vm.exportRes=0;vm.dismissError();vm.export()}
        val phases=linkedSetOf<String>();val values=mutableListOf<Float>();val levels=linkedSetOf<Float>();var progressShot=false
        val started=SystemClock.elapsedRealtime()
        val latest=java.util.concurrent.atomic.AtomicReference(0f)
        val sampler=Executors.newSingleThreadExecutor()
        val sampling=sampler.submit {
            var active=true
            while(active) {
                runOnMainSync {
                    active=vm.isExporting
                    vm.renderProgress?.let{value->values+=value;levels+=value;latest.set(value);check(value in 0f..100f);phases+=vm.renderStage}
                }
                check(SystemClock.elapsedRealtime()-started<60000){"export progress timed out"}
                Thread.sleep(4)
            }
        }
        try {
            while(!sampling.isDone) {
                if(!progressShot&&latest.get()>=20f){
                    uiAutomation.takeScreenshot()?.let{screen->File(output,"export-progress-running.png").outputStream().use{out->screen.compress(Bitmap.CompressFormat.PNG,100,out)};screen.recycle()}
                    progressShot=true
                }
                Thread.sleep(8)
            }
            sampling.get(2,TimeUnit.SECONDS)
        } finally {sampler.shutdownNow()}
        check(vm.errorMessage==null){vm.errorMessage!!}
        check(values.isNotEmpty()&&values.zipWithNext().all{it.second>=it.first}){"progress missing or went backwards"}
        check(levels.size>=3){"progress did not visibly advance: $levels"}
        check(values.last()==100f){"export never reported completion: $levels"}
        check(vm.log.contains("导出成功")){"the progress UI closed before export completion was recorded"}
        check(vm.renderProgress==null&&vm.renderStage.isEmpty()){"progress state was left active after export completed"}
        val missingStages=listOf("解码","冲印").filter{required->phases.none{it.contains(required)}}
        check(missingStages.isEmpty()){"missing stages=$missingStages; observed=$phases"}
        val savedBeforeCancel=vm.log.windowed("导出成功".length).count{it=="导出成功"}
        runOnMainSync {vm.export();vm.cancelRender()}
        await{!vm.isExporting}
        check(vm.errorMessage==null&&vm.renderProgress==null&&vm.renderStage.isEmpty()){"cancel left an error or progress overlay"}
        check(vm.log.contains("导出已取消")&&vm.log.windowed("导出成功".length).count{it=="导出成功"}==savedBeforeCancel)
        val second=File(output,"batch-progress.jpg");imageFile.copyTo(second,true)
        runOnMainSync{vm.openPhotos(listOf(uri,Uri.fromFile(second)))};await{vm.hasPhoto&&!vm.busy&&vm.lastResult!=null}
        runOnMainSync{vm.batchExport();vm.cancelRender()};await{!vm.isBatchExporting}
        check(vm.errorMessage==null&&vm.renderProgress==null&&vm.renderStage.isEmpty()&&vm.log.contains("批量导出已取消"))
        val held=File(output,"export-progress-held.jpg");check(imageFile.renameTo(held))
        try {
            runOnMainSync{vm.export()};await{!vm.isExporting}
            check(vm.errorMessage!=null&&vm.renderProgress==null&&vm.renderStage.isEmpty()){"failure left progress active"}
        } finally {check(held.renameTo(imageFile));runOnMainSync{vm.dismissError()}}
        return "${values.size} samples, ${levels.size} levels, ${values.first()}→${values.last()}%; single/batch cancellation and missing-file cleanup passed; stages=${phases.joinToString()}"
    }

    private fun exportActionAudit(): String {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }

        val width = 3600
        val height = 2700
        val source = File(output, "export-actions-source.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    row[x] = -0x1000000 or ((x * 255 / width) shl 16) or
                        ((y * 255 / height) shl 8) or ((x + y) % 255)
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
        } finally {
            bitmap.recycle()
        }

        val uri = Uri.fromFile(source)
        EditStore.save(targetContext.applicationContext, uri, EditorParams().apply {
            grainActive = false
            glareActive = false
        })
        runOnMainSync {
            vm.dismissError()
            vm.exportFormat = "TIFF"
            vm.exportColorSpace = "Display P3"
            vm.exportRes = 1280
            vm.openPhotos(listOf(uri))
        }
        await(90000) { vm.errorMessage != null || vm.lastResult != null && !vm.busy }
        check(vm.errorMessage == null) { "export action source failed to load: ${vm.errorMessage}" }
        check(max(vm.sourceBitmap!!.width, vm.sourceBitmap!!.height) <= 2048) { "interactive source preview exceeded 2048px" }

        fun awaitAction(name: String) {
            await(180000) { !vm.isExporting }
            check(vm.renderProgress == null && vm.renderStage.isEmpty()) { "$name left export progress active" }
            check(vm.errorMessage == null) { "$name failed: ${vm.errorMessage}; log=${vm.log.takeLast(1000)}" }
        }
        fun restoredInteractiveSession(name: String) {
            val outputSpace = JSONObject(vm.engine!!.getParams()).optString("output_color_space")
            check(outputSpace == "sRGB") { "$name did not restore the interactive session color space: $outputSpace" }
        }

        val createdOutputs = mutableListOf<Uri>()
        try {
        val galleryBeforeProof = targetContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null, null,
        )?.use { it.count } ?: 0
        runOnMainSync { vm.softProof() }
        awaitAction("soft proof")
        val proof = vm.lastResult ?: error("soft proof produced no preview")
        check(max(proof.width, proof.height) == 1280) { "soft proof ignored the selected 1280px export size: ${proof.width}x${proof.height}" }
        val galleryAfterProof = targetContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null, null,
        )?.use { it.count } ?: 0
        check(galleryAfterProof == galleryBeforeProof) { "soft proof unexpectedly saved an image" }
        restoredInteractiveSession("soft proof")

        runOnMainSync { vm.previewTier() }
        awaitAction("3400px preview")
        val highDefinition = vm.lastResult ?: error("high-definition preview produced no result")
        check(max(highDefinition.width, highDefinition.height) == 3400) {
            "high-definition preview did not decode and render at 3400px: ${highDefinition.width}x${highDefinition.height}"
        }
        restoredInteractiveSession("high-definition preview")

        val beforeEdit = vm.lastResult
        runOnMainSync {
            vm.params.exposureEv = .35f
            vm.scheduleRender()
        }
        await(90000) { vm.errorMessage != null || vm.lastResult !== beforeEdit && !vm.busy }
        check(vm.errorMessage == null && vm.lastResult !== beforeEdit) {
            "editor could not render after the high-definition preview: ${vm.errorMessage}"
        }
        val afterEdit = vm.lastResult
        runOnMainSync {
            vm.params.exposureEv = 0f
            vm.scheduleRender()
        }
        await(90000) { vm.errorMessage != null || !vm.busy && vm.lastResult !== afterEdit }
        check(vm.errorMessage == null) { "editor failed to restore test parameters: ${vm.errorMessage}" }

        fun outputUri(logPrefix: String): Uri {
            val line = vm.log.lineSequence().lastOrNull { it.startsWith(logPrefix) }
                ?: error("Missing output log for $logPrefix: ${vm.log.takeLast(1200)}")
            val location = line.substringAfter("→ ").trim().removePrefix("相册: ")
            check(location.startsWith("content://")) { "expected a MediaStore URI for $logPrefix, got $location" }
            return Uri.parse(location).also { createdOutputs.add(it) }
        }
        data class TiffInfo(val width: Int, val height: Int, val bits: List<Int>, val compression: Int,
                            val samples: Int, val hasIcc: Boolean)
        fun inspectTiff(outputUri: Uri): TiffInfo {
            // Only IFD/ICC metadata is inspected here. Reading the entire TIFF
            // while deliberately retaining a 3400px proof defeats the low-heap test.
            val bytes = targetContext.contentResolver.openInputStream(outputUri)?.use { input ->
                val header = ByteArray(64 * 1024)
                var used = 0
                while (used < header.size) {
                    val count = input.read(header, used, header.size - used)
                    if (count < 0) break
                    used += count
                }
                header.copyOf(used)
            }
                ?: error("cannot read TIFF $outputUri")
            check(bytes.size >= 16 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) { "TIFF is not little-endian" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check((buffer.getShort(2).toInt() and 0xffff) == 42) { "invalid TIFF magic" }
            val ifd = buffer.getInt(4)
            check(ifd >= 8 && ifd + 2 <= bytes.size) { "invalid TIFF IFD offset $ifd" }
            val count = buffer.getShort(ifd).toInt() and 0xffff
            check(ifd + 2 + count * 12 <= bytes.size) { "truncated TIFF IFD" }
            val entries = (0 until count).associate { index ->
                val offset = ifd + 2 + index * 12
                (buffer.getShort(offset).toInt() and 0xffff) to offset
            }
            fun scalar(tag: Int): Long {
                val offset = entries[tag] ?: error("TIFF tag $tag missing")
                val type = buffer.getShort(offset + 2).toInt() and 0xffff
                val itemCount = buffer.getInt(offset + 4).toLong() and 0xffffffffL
                check(itemCount == 1L) { "TIFF tag $tag is not scalar" }
                return when (type) {
                    3 -> (buffer.getShort(offset + 8).toInt() and 0xffff).toLong()
                    4 -> buffer.getInt(offset + 8).toLong() and 0xffffffffL
                    else -> error("unsupported TIFF scalar type $type for tag $tag")
                }
            }
            val bitsOffset = entries[258] ?: error("BitsPerSample tag missing")
            val bitCount = buffer.getInt(bitsOffset + 4)
            check(bitCount == 3) { "RGB TIFF should declare three channel bit depths, got $bitCount" }
            val bitOffset = buffer.getInt(bitsOffset + 8)
            check(bitOffset >= 0 && bitOffset + 6 <= bytes.size) { "BitsPerSample offset outside TIFF" }
            val bits = (0..2).map { buffer.getShort(bitOffset + it * 2).toInt() and 0xffff }
            val iccEntry = entries[34675]
            val hasIcc = iccEntry != null && (buffer.getInt(iccEntry + 4).toLong() and 0xffffffffL) > 0
            targetContext.contentResolver.query(outputUri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)?.use {
                check(it.moveToFirst() && it.getLong(0) == scalar(273) + scalar(279)) { "TIFF pixel payload is incomplete" }
            } ?: error("cannot query TIFF size")
            return TiffInfo(scalar(256).toInt(), scalar(257).toInt(), bits,
                scalar(259).toInt(), scalar(277).toInt(), hasIcc)
        }
        fun assertPublished(uri: Uri, expectedNamePart: String) {
            targetContext.contentResolver.query(uri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING), null, null, null
            )?.use { cursor ->
                check(cursor.moveToFirst()) { "published TIFF is missing from MediaStore" }
                check(cursor.getString(0).contains(expectedNamePart)) { "unexpected TIFF filename: ${cursor.getString(0)}" }
                check(cursor.getInt(1) == 0) { "TIFF remained pending in MediaStore" }
            } ?: error("cannot query published TIFF")
        }

        val frameBeforeSave = vm.lastResult
        val bitmapBeforeSave = vm.rendered
        runOnMainSync { vm.exportDi() }
        awaitAction("DI TIFF")
        val diUri = outputUri("DI 导出:")
        val diInfo = inspectTiff(diUri)
        check(diInfo.width == 1280 && diInfo.height == 960) { "DI TIFF has wrong dimensions: $diInfo" }
        check(diInfo.bits == listOf(16, 16, 16) && diInfo.samples == 3 && diInfo.compression == 1 && !diInfo.hasIcc) {
            "DI TIFF format is wrong: $diInfo"
        }
        assertPublished(diUri, "Theia_DI_")
        restoredInteractiveSession("DI TIFF")

        runOnMainSync { vm.export() }
        awaitAction("16-bit TIFF export")
        val tiffUri = outputUri("导出成功:")
        val tiffInfo = inspectTiff(tiffUri)
        check(tiffInfo.width == 1280 && tiffInfo.height == 960) { "export TIFF has wrong dimensions: $tiffInfo" }
        check(tiffInfo.bits == listOf(16, 16, 16) && tiffInfo.samples == 3 && tiffInfo.compression == 1 && tiffInfo.hasIcc) {
            "16-bit P3 TIFF format/profile is wrong: $tiffInfo"
        }
        assertPublished(tiffUri, "Theia_")
        restoredInteractiveSession("TIFF export")
        check(vm.lastResult === frameBeforeSave && vm.rendered === bitmapBeforeSave) {
            "Saving triggered a redundant preview render"
        }

        return "Soft proof followed the selected resolution; HD preview reopened the 3600px source; editor session recovered after all actions; DI and P3 TIFF headers, bit depth, dimensions, ICC and MediaStore publication verified."
        } finally {
            createdOutputs.forEach { targetContext.contentResolver.delete(it, null, null) }
            runOnMainSync { activity.finish() }
            source.delete()
        }
    }

    private fun isolatedLargeExportAudit(): String {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        val imageFile = File(output, largeExportInput)
        check(imageFile.isFile) { "push $largeExportInput into $output before running this audit" }
        val uri = Uri.fromFile(imageFile)
        EditStore.save(targetContext.applicationContext, uri, EditorParams().apply {
            overscanActive = largeExportEdges
            if (overscanActive) {
                val isRawInput = RawDecoder.isRaw(targetContext, uri, imageFile.name)
                val width = if (isRawInput) 5200 else 3072
                val height = if (isRawInput) 3900 else 4096
                val crop = computeCenteredCropByAspect(overscanGateAspect("135"), width, height)
                cropX = crop[0]; cropY = crop[1]; cropW = crop[2]; cropH = crop[3]
            }
        })
        runOnMainSync {
            vm.dismissError()
            vm.exportFormat = largeExportFormat
            vm.exportColorSpace = if (largeExportFormat == "TIFF") "Display P3" else "sRGB"
            vm.exportRes = largeExportResolution
            vm.openPhotos(listOf(uri))
        }
        await(180000) { vm.errorMessage != null || vm.lastResult != null && !vm.busy }
        check(vm.errorMessage == null) { "image import failed: ${vm.errorMessage}" }
        val expectsRaw = RawDecoder.isRaw(targetContext, uri, imageFile.name)
        check(vm.currentIsRaw == expectsRaw) { "RAW detection mismatch: extension=${imageFile.name}, currentIsRaw=${vm.currentIsRaw}" }
        if (expectsRaw) {
            val preview = vm.sourceBitmap ?: error("RAW preview bitmap was not created")
            check(max(preview.width, preview.height) <= 2048) { "RAW editor preview exceeds 2048px: ${preview.width}x${preview.height}" }
        }
        val before = vm.engine!!.memoryReport()
        val savedBefore = targetContext.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0
        runOnMainSync { vm.export() }
        await(240000) { !vm.isExporting }
        val error = vm.errorMessage
        val log = vm.log.takeLast(1200)
        val after = vm.engine?.memoryReport() ?: "engine unavailable"
        runOnMainSync { activity.finish() }
        check(error == null) { "export failed: $error; log=$log; memory_before=$before; memory_after=$after" }
        check(log.contains("导出成功")) { "export did not complete; log=$log" }
        val savedAfter = targetContext.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0
        check(savedAfter > savedBefore) { "no output image was saved" }
        val savedUri = Uri.parse(log.substringAfterLast("相册: ").lineSequence().first().trim())
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (largeExportFormat != "TIFF") {
            targetContext.contentResolver.openInputStream(savedUri).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            val expected = Regex("导出成功: ([0-9]+)×([0-9]+)").find(log)!!
            check(bounds.outWidth == expected.groupValues[1].toInt() && bounds.outHeight == expected.groupValues[2].toInt())
        }
        return "resolution=$largeExportResolution; model=${android.os.Build.MODEL}; log=$log; memory_before=$before; memory_after=$after"
    }

    private fun assetBatchExportAudit(): String {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        val jpeg = File(output, "batch-assets-13mb.jpg")
        val raw = File(output, "batch-assets-50mp.tiff")
        context.assets.open("13mb.jpg").use { input -> jpeg.outputStream().use { input.copyTo(it) } }
        context.assets.open("xiaomi-dng.tiff").use { input -> raw.outputStream().use { input.copyTo(it) } }
        val uris = listOf(Uri.fromFile(jpeg), Uri.fromFile(raw))
        uris.forEach { EditStore.save(targetContext.applicationContext, it, EditorParams()) }
        runOnMainSync {
            vm.dismissError()
            vm.exportFormat = "JPEG"
            vm.exportRes = 0
            vm.openPhotos(uris)
        }
        await(240000) { vm.errorMessage != null || vm.lastResult != null && !vm.busy }
        check(vm.errorMessage == null) { "JPEG preview import failed: ${vm.errorMessage}" }
        val countBefore = targetContext.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0
        runOnMainSync { vm.batchExport() }
        await(600000) { !vm.isBatchExporting }
        val error = vm.errorMessage
        val log = vm.log.takeLast(5000)
        runOnMainSync { activity.finish() }
        check(error == null) { "full-resolution asset batch failed: $error; log=$log" }
        check(log.contains("批量导出 2/2")) { "both asset outputs were not reported: $log" }
        val countAfter = targetContext.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0
        check(countAfter >= countBefore + 2) { "asset batch did not save two gallery outputs ($countBefore -> $countAfter)" }
        return "8192x6144 JPEG and 50MP DNG TIFF both exported at original resolution in one batch on ${android.os.Build.MODEL}; log=$log"
    }

    private fun nativeExportParityAudit(): String {
        val width = 72; val height = 53 // partial final 32-row strip
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { i ->
            -0x1000000 or ((i % width * 255 / width) shl 16) or ((i / width * 255 / height) shl 8) or 97
        }, 0, width, 0, 0, width, height)
        try {
            EngineClient.create(targetContext).use { engine ->
                val params = EditorParams().apply { grainActive = false; glareActive = false }
                val json = params.openJson(width, height)
                engine.open(ColorPipeline.bitmapToLinearSrgb(bitmap), width, height, 3, json)
                val expected = engine.render("full")
                engine.openBitmap(bitmap, json)
                engine.renderForExport().use { actual ->
                    check(actual.width == expected.width && actual.height == expected.height)
                    val pixels = ShortArray(actual.width * actual.height * 4)
                    actual.readRows(0, actual.height, pixels)
                    val difference = pixels.indices.maxOf { kotlin.math.abs((pixels[it].toInt() and 65535) - (expected.rgba16[it].toInt() and 65535)) }
                    check(difference <= 1) { "native bitmap input differs by $difference/65535" }
                    val grade = Grade(1.02f, .99f, .98f, 1.2f, .1f, .05f, 1.1f, -.1f, .1f, 0f, 0f,
                        floatArrayOf(.01f, 0f, 0f), FloatArray(3), FloatArray(3))
                    val expectedTiff = java.io.ByteArrayOutputStream().also {
                        TiffWriter.writeRgba16(pixels, actual.width, actual.height, grade, null, it)
                    }.toByteArray()
                    val actualTiff = java.io.ByteArrayOutputStream().also { TiffWriter.writeNative(actual, grade, null, it) }.toByteArray()
                    check(expectedTiff.contentEquals(actualTiff)) { "strip TIFF changed pixels or tags" }
                    var stripeChecks = 0
                    val cancellation = runCatching {
                        TiffWriter.writeRgba16(pixels, actual.width, actual.height, null, null,
                            java.io.ByteArrayOutputStream(), checkCancelled = {
                            stripeChecks++
                            if (stripeChecks == 2) error("cancel sentinel")
                        })
                    }.exceptionOrNull()
                    check(cancellation?.message == "cancel sentinel" && stripeChecks == 2) {
                        "TIFF writer did not honor cancellation between strips"
                    }
                    // Native results must remain readable after the GPU session closes.
                    engine.closeSession()
                    actual.readRows(actual.height - 1, 1, ShortArray(actual.width * 4))
                }
                bitmap.setPixels(IntArray(width * height) { i ->
                    (128 shl 24) or ((i % width * 255 / width) shl 16) or ((i / width * 255 / height) shl 8) or 97
                }, 0, width, 0, 0, width, height)
                engine.open(ColorPipeline.bitmapToLinearSrgb(bitmap), width, height, 3, json)
                val translucentExpected = engine.render("full")
                engine.openBitmap(bitmap, json)
                engine.renderForExport().use { actual ->
                    val pixels = ShortArray(translucentExpected.rgba16.size)
                    actual.readRows(0, actual.height, pixels)
                    val difference = pixels.indices.maxOf { kotlin.math.abs((pixels[it].toInt() and 65535) - (translucentExpected.rgba16[it].toInt() and 65535)) }
                    check(difference <= 1) { "premultiplied bitmap conversion differs by $difference/65535" }
                }
            }
            val file = File(output, "small-export-parity.dng")
            context.assets.open("raw.dng").use { source -> file.outputStream().use { source.copyTo(it) } }
            val uri = Uri.fromFile(file)
            val old = RawDecoder.decode(targetContext, uri, halfSize = true)
            EngineClient.create(targetContext).use { engine ->
                val json = EditorParams().apply { grainActive = false; glareActive = false }
                    .openJson(old.width, old.height, inputColorSpace = "ProPhoto RGB")
                engine.open(old.linearProPhoto, old.width, old.height, 3, json)
                val expected = engine.render("full")
                RawDecoder.decodeForExport(targetContext, uri, max(old.width, old.height)).use { raw ->
                    check(raw.width == old.width && raw.height == old.height)
                    engine.openRaw(raw, json)
                }
                engine.renderForExport().use { actual ->
                    val pixels = ShortArray(expected.rgba16.size)
                    actual.readRows(0, actual.height, pixels)
                    check(pixels.contentEquals(expected.rgba16)) { "native RAW changed decoded pixels" }
                }
                RawDecoder.decodeForExport(targetContext, uri, 384).use { raw ->
                    raw.resize(384)
                    check(maxOf(raw.width, raw.height) == 384) { "RAW was undersized by half demosaic" }
                }
            }
            return "bitmap input parity <=1/65535; RAW pixels exact; partial-strip TIFF bytes exact; native result survives closed session; requested RAW dimensions preserved"
        } finally { bitmap.recycle() }
    }
    private fun mae(a: SpkResult, b: SpkResult): Double {
        check(a.width == b.width && a.height == b.height)
        var sum = 0.0
        for (i in a.rgba16.indices) if (i % 4 != 3) sum += abs((a.rgba16[i].toInt() and 65535) - (b.rgba16[i].toInt() and 65535))
        return sum / (a.width * a.height * 3) / 65535
    }
    private fun image(name: String, r: SpkResult) {
        val pixels = IntArray(r.width * r.height) { i -> -0x1000000 or
            (((r.rgba16[i*4].toInt() and 65535) shr 8) shl 16) or
            (((r.rgba16[i*4+1].toInt() and 65535) shr 8) shl 8) or ((r.rgba16[i*4+2].toInt() and 65535) shr 8) }
        val bmp = Bitmap.createBitmap(pixels, r.width, r.height, Bitmap.Config.ARGB_8888)
        File(output, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
        val bytes = ByteBuffer.allocate(r.rgba16.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        r.rgba16.forEach { bytes.putShort(it) }; File(output, "$name.rgba16").writeBytes(bytes.array())
    }
    private fun targetedEffectAudit() {
        val width = 192; val height = 128
        val brightScene = FloatArray(width * height * 3) { index ->
            val x = index / 3 % width; val y = index / 3 / width
            if (x in 78..113 && y in 45..82) 16f else .008f + .025f * x / width
        }
        EngineClient.create(targetContext).use { engine ->
            val parameters = EditorParams().apply {
                autoExposure = false; grainActive = false
                glareActive = false; halationActive = true; halationAmount = 4f
            }
            val base = JSONObject(parameters.openJson(width, height))
            fun render(changes: JSONObject): SpkResult {
                engine.open(brightScene, width, height, 3, changes.toString())
                return engine.render("full")
            }
            fun changed(key: String, value: Any) = JSONObject(base.toString()).put(key, value)
            case("diagnostic.glare_active_with_active_baseline") {
                val on = render(changed("glare_active", true).put("glare_amount", 20.0))
                val off = render(changed("glare_active", false).put("glare_amount", 20.0))
                val difference = mae(on, off)
                check(difference > 0) { "Glare toggle did not affect active bright fixture" }
                "active/off MAE=$difference; ${if (difference > 0) "old failure used an already-disabled baseline" else "no-op reproduced on bright active fixture; existing engine behavior"}"
            }
            case("diagnostic.glare_amount_with_active_baseline") {
                val low = render(changed("glare_active", true).put("glare_amount", 1.0))
                val high = render(changed("glare_active", true).put("glare_amount", 20.0))
                val difference = mae(low, high)
                check(difference > 0) { "Glare amount did not affect active bright fixture" }
                "amount 1/20 MAE=$difference; ${if (difference > 0) "old failure kept glare disabled" else "no-op reproduced with glare enabled; existing engine behavior"}"
            }
            case("diagnostic.halation_boost_with_bright_fixture") {
                val on = render(base)
                val off = render(changed("halation_active", false))
                val boosted = render(changed("halation_boost_ev", 4.0))
                val activeDifference = mae(on, off)
                val boostDifference = mae(on, boosted)
                check(activeDifference > 0 && boostDifference > 0) { "Halation or boost did not affect bright fixture" }
                "halation on/off MAE=$activeDifference; boost 0/4 MAE=$boostDifference; ${if (boostDifference > 0) "effect needs bright highlight stimulus" else if (activeDifference > 0) "boost is a pre-existing engine no-op on active bright fixture; UI still forwards unchanged value" else "fixture inconclusive for halation; core unchanged"}"
            }
            case("effect.extended_dynamic_range.bright_fixture") {
                val off = render(changed("extended_dynamic_range", false))
                val on = render(changed("extended_dynamic_range", true))
                val difference = mae(off, on)
                check(difference > 0) { "EDR did not change the high dynamic range fixture" }
                "dark-to-bright scene MAE=$difference; low-range ramp does not exercise EDR"
            }
        }
    }

    private fun nativeAudit() {
        val catalog = StockCatalog.load(targetContext)
        EngineClient.create(targetContext).use { e ->
            File(output,"capabilities.json").writeText(e.capabilities())
            File(output,"schema.json").writeText(e.paramsSchema())
            File(output,"input.f32").writeBytes(ByteBuffer.allocate(input.size*4).order(ByteOrder.LITTLE_ENDIAN).apply { input.forEach { putFloat(it) } }.array())
            fun base() = JSONObject(EditorParams().openJson(w,h)).put("glare_active",false)
            fun render(p: JSONObject = base(), tier: String = "full"): SpkResult {
                if(p.optBoolean("overscan_active")) {
                    val crop=computeCenteredCropByAspect(overscanGateAspect(p.optString("overscan_format","135")),w,h)
                    listOf("x","y","w","h").forEachIndexed {i,key->p.put("geometry_crop_$key",crop[i].toDouble())}
                }
                e.open(input,w,h,3,p.toString()); return e.render(tier)
            }
            lateinit var baseline: SpkResult
            case("engine.initialize_and_first_render") { baseline=render(); image("baseline",baseline); valid(baseline) }
            case("schema.android_keys") {
                val fields=JSONObject(e.paramsSchema()).getJSONArray("fields")
                val names=(0 until fields.length()).map { fields.getJSONObject(it).getString("name") }.toSet()
                val unknown=base().keys().asSequence().filter { it !in names }.toList()
                check(unknown.isEmpty()) { "Unknown: $unknown" }; "${base().length()} wire keys accepted"
            }
            for (tier in listOf("live","preview","full")) case("render.tier.$tier") { valid(render(tier=tier)) }
            case("render.determinism_without_glare") { val a=render();val b=render();check(a.rgba16.contentEquals(b.rgba16)); "bit-identical" }
            for (film in catalog.first) case("stock.film.${film.id}") {
                val paper = film.targetPrint?.takeIf { id -> catalog.second.any { it.id==id } }
                    ?: catalog.second.first { it.monochrome==film.monochrome }.id
                val p=EditorParams().apply { filmId=film.id; paperId=paper }
                valid(render(JSONObject(p.openJson(w,h)).put("glare_active",false)))
            }
            for (paper in catalog.second) case("stock.paper.${paper.id}") {
                val p=EditorParams().apply { paperId=paper.id; if(paper.monochrome) filmId="kodak_tri_x_400" }
                valid(render(JSONObject(p.openJson(w,h)).put("glare_active",false)))
            }
            val effects=listOf(
                "exposure_compensation_ev" to 2.0,"print_exposure" to 0.5,"m_filter_shift" to 0.5,"y_filter_shift" to 0.5,
                "grain_active" to false,"grain_amount" to 0.0,"grain_amount" to 2.0,"grain_sublayers_active" to false,
                "halation_active" to false,"halation_amount" to 4.0,"halation_scatter_amount" to 0.2,
                "antihalation_removed" to true,"dir_couplers_active" to false,"dir_couplers_amount" to 1.7,
                "preflash_exposure" to 0.02,
                "geometry_flip_h" to true,"geometry_flip_v" to true,"geometry_rotation_deg" to 12.0,
                "camera_filter" to "w25")
            for ((key,value) in effects) case("effect.$key.$value") {
                val r=render(base().put(key,value)); val d=mae(baseline,r); check(d>0.0) { "no pixels changed" }
                if(key.startsWith("grain")) image("${key}_$value",r)
                "${valid(r)}; MAE=$d"
            }
            for (method in listOf("balanced","center","protect_highlights","protect_shadows"))
                case("metering.$method") { valid(render(base().put("auto_exposure_method",method))) }
            for(filter in listOf("w8","w11","w12","w15","w21","w25","w47","w58"))case("camera_filter.$filter") {val r=render(base().put("camera_filter",filter));check(mae(baseline,r)>0);valid(r)}
            for (format in listOf(18,36,56,84)) case("grain.film_format_${format}mm") {
                val r=render(base().put("film_format_mm",format)); image("grain_${format}mm",r); valid(r)
            }
            for (film in listOf("kodak_tri_x_400","ilford_hp5_plus_400","kodak_tmax_100","fujifilm_neopan_acros_100_ii"))
                case("grain.windows_calibration.$film") {
                    val p=EditorParams().apply {filmId=film; paperId="ilford_multigrade_iv_rc"}
                    val json=JSONObject(p.openJson(w,h)); check(!json.getBoolean("grain_sublayers_active"))
                    check(abs(json.getDouble("grain_amount")-EditorParams.ownGrain(film))<1e-6);json.toString()
                }
            case("render.print_delta_reuses_negative") { render();e.setParams("{\"print_exposure\":0.8}");val r=e.render("full");check(r.reprint);valid(r) }
            case("render.shoot_delta_rebuilds_negative") { render();e.setParams("{\"grain_amount\":1.5}");val r=e.render("full");check(!r.reprint);valid(r) }
            for (target in listOf("exposure","filter_pack","both")) case("solve.$target") { render();JSONObject(e.solve(target)).toString() }
            case("latitude.measure") { render();JSONObject(e.sceneLatitude()).toString() }
            case("latitude.apply") { valid(render(base().put("scene_latitude_active",true).put("scene_latitude_highlight_room",1.5).put("scene_latitude_shadow_room",1.0))) }
            for (format in listOf("135","135_half","135_xpan","120_645","120_6x6","120_6x7","120_6x8","120_6x9","120_6x12","120_6x17"))
                case("overscan.format.$format") { val r=render(base().put("overscan_active",true).put("overscan_format",format));check(r.width!=w||r.height!=h);image("edge_$format",r);valid(r) }
            case("overscan.portrait_gate_orientation") {
                val pw = 384; val ph = 512
                val portrait = FloatArray(pw * ph * 3) { i ->
                    when (i % 3) {
                        0 -> ((i / 3) % pw) / pw.toFloat()
                        1 -> (i / 3 / pw) / ph.toFloat()
                        else -> .2f
                    }
                }
                val crop = computeCenteredCropByAspect(overscanGateAspect("135", pw, ph), pw, ph)
                check(overscanGateAspect("135", pw, ph) < 1f)
                check(crop[3] > .99f && crop[2] < .9f) { "portrait gate must preserve height" }
                val p = EditorParams().apply {
                    overscanActive = true
                    overscanFormat = "135"
                    cropX = crop[0]; cropY = crop[1]; cropW = crop[2]; cropH = crop[3]
                }
                e.open(portrait, pw, ph, 3, p.openJson(pw, ph))
                val r = e.render("full")
                check(r.height > r.width) { "portrait film edge should remain vertical: ${r.width}x${r.height}" }
                "135 gate uses portrait ratio ${overscanGateAspect("135", pw, ph)} and preserves vertical frame space"
            }
            for ((key,values) in listOf("overscan_mode" to listOf("strip","filed"),"overscan_gate" to listOf("square","rounded","eared","shouldered","kicked"),"overscan_holes" to listOf("black","white"),"overscan_carrier" to listOf("none","black","open")))
                for(value in values)case("overscan.$key.$value") { valid(render(base().put("overscan_active",true).put(key,value))) }
            for((key,value)in listOf("overscan_fog" to 2.0,"overscan_leaks" to 2.0,"overscan_f_number" to 16,"overscan_frame_number" to 42,"overscan_camera_seed" to 200,"overscan_frame_seed" to 300,"overscan_edge_text" to "SPEKTRA TEST"))
                case("overscan.$key") { valid(render(base().put("overscan_active",true).put(key,value))) }
            for (style in listOf("lcd","dots","data")) for(corner in listOf("br","bl","tr","tl"))
                case("date.$style.$corner") {
                    val p=base().put("date_imprint_active",true).put("date_imprint_style",style).put("date_imprint_corner",corner)
                        .put("date_imprint_text",if(style=="data")"1/250 F5.6 ISO400" else "'26 10 30")
                    if(style=="data") p.put("overscan_active",true)
                    val off=render(JSONObject(p.toString()).put("date_imprint_active",false))
                    val r=render(p);check(mae(off,r)>0.0);image("date_${style}_$corner",r);valid(r)
                }
            case("date.rebate") { valid(render(base().put("overscan_active",true).put("date_imprint_active",true).put("date_imprint_text","'26 10 30").put("date_imprint_placement","rebate"))) }
            for(key in listOf("date_imprint_size","date_imprint_ev","date_imprint_inset_x","date_imprint_inset_y"))
                case("date.$key") {valid(render(base().put("date_imprint_active",true).put("date_imprint_text","'26 10 30").put(key,2.0)))}
            case("geometry.crop") {val r=render(base().put("geometry_crop_w",0.5));check(r.width<baseline.width);valid(r)}
            case("geometry.quarter_turn") {val r=render(base().put("geometry_quarter_turns",1));check(r.width==h&&r.height==w);valid(r)}
            for(aspect in listOf("原始","1:1","4:3","3:2","16:9"))case("geometry.preset.$aspect") {
                val p=EditorParams();val c=computeCenteredCrop(aspect,w,h);p.cropX=c[0];p.cropY=c[1];p.cropW=c[2];p.cropH=c[3]
                val r=render(JSONObject(p.openJson(w,h)).put("glare_active",false));valid(r)
            }
            case("preview.paper_lut") {render();valid(e.previewStockLut("kodak_portra_endura","live"))}
            case("export.digital_intermediate") {
                render()
                e.renderDigitalIntermediate().use { result ->
                    check(result.width > 0 && result.height > 0)
                    val rows = ShortArray(result.width * minOf(32, result.height) * 4)
                    result.readRows(0, minOf(32, result.height), rows)
                    check(rows.filterIndexed { index, _ -> index % 4 != 3 }.distinct().size > 1) {
                        "DI result has no varying RGB pixels"
                    }
                    "${result.width}x${result.height} native 16-bit rows are readable"
                }
            }
            case("export.normalized_density") {render();valid(e.exportDi())}
            case("color.output_transform") {JSONObject(e.outputTransform("ProPhoto RGB","sRGB")).toString()}
            case("engine.memory_report") {JSONObject(e.memoryReport()).toString()}
            case("engine.cancel_idle_and_recover") {render();e.cancel();valid(render())}
        }
        // These effects need an active baseline and bright highlights rather than the low-range ramp above.
        targetedEffectAudit()
    }
    private fun await(timeout: Long = 60000, predicate: () -> Boolean) {
        val start=SystemClock.elapsedRealtime()
        while(!predicate()) {check(SystemClock.elapsedRealtime()-start<timeout){"timeout after ${timeout}ms"};Thread.sleep(30)}
    }
    private fun comparisonAudit(): String {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val frameDurations = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frameDurations += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        activity.window.addOnFrameMetricsAvailableListener(frameListener, Handler(Looper.getMainLooper()))
        lateinit var vm: EditorViewModel
        runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        val file = File(output, "compare-source.jpg")
        val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(w*h) {i -> -0x1000000 or ((i%w*255/w) shl 16) or ((i/w*255/h) shl 8) or 90},0,w,0,0,w,h)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        EditStore.save(targetContext,Uri.fromFile(file),EditorParams())
        runOnMainSync { vm.openPhotos(listOf(Uri.fromFile(file))) }
        await { vm.rendered != null && !vm.busy };Thread.sleep(700)
        fun node(label:String):AccessibilityNodeInfo? {
            if(android.os.Build.VERSION.SDK_INT>=34)uiAutomation.clearCache()
            fun find(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {if(n==null)return null;if(n.contentDescription?.toString()?.split(", ")?.contains(label)==true||n.text?.toString()==label)return n;for(i in 0 until n.childCount){val r=find(n.getChild(i));if(r!=null)return r};return null}
            return find(uiAutomation.rootInActiveWindow)
        }
        fun click(label:String) {var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("Not clickable $label");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(350)}
        fun capture(name:String) {val bmp=uiAutomation.takeScreenshot()!!;File(output,"compare_$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
        capture("design_shoot")
        for((tab,section) in listOf("暗房" to "放大机","调色" to "白平衡","导出" to "配方","拍摄" to "胶片与相纸")) {
            click(tab);await{node(section)!=null}
            capture("design_${tab}")
        }
        click("调色");Thread.sleep(300)
        val scroll=uiAutomation.rootInActiveWindow
        val gradePanel=scroll?.let { findScrollable(it) }
        repeat(8) { gradePanel?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(100) }
        val recentBitmap=vm.rendered
        repeat(14){step->runOnMainSync {vm.params.gradeExposure=(step+1)/14f;vm.scheduleGrade()};Thread.sleep(16)}
        await { vm.rendered != null && vm.rendered !== recentBitmap }
        Thread.sleep(250)
        val renderOnlyFrames=frameDurations.drop((frameDurations.size-12).coerceAtLeast(0)).sorted()
        val renderP95=renderOnlyFrames.getOrNull((renderOnlyFrames.size*.95).toInt().coerceAtMost(renderOnlyFrames.lastIndex))?.div(1_000_000.0)?:0.0
        File(output,"grade-scrub-frame-metrics.json").writeText(JSONObject().put("frames",renderOnlyFrames.size).put("p95_ms",renderP95).toString(2))
        capture("design_grade_controls")
        repeat(8) { gradePanel?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);Thread.sleep(100) }
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);Thread.sleep(500);capture("design_landscape")
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);Thread.sleep(350)
        capture("before_click")
        await { node("前后对比") != null }
        click("前后对比");capture("half");await {node("前后对比分界线")!=null}
        val rect=Rect();node("前后对比分界线")!!.getBoundsInScreen(rect)
        val time=SystemClock.uptimeMillis()
        fun touch(action:Int,dt:Long,x:Float){val e=MotionEvent.obtain(time,time+dt,action,x,rect.exactCenterY(),0);check(uiAutomation.injectInputEvent(e,true));e.recycle()}
        touch(MotionEvent.ACTION_DOWN,0,rect.exactCenterX())
        repeat(8){touch(MotionEvent.ACTION_MOVE,(it+1)*25L,rect.exactCenterX()+rect.width()*.035f*(it+1))}
        touch(MotionEvent.ACTION_UP,250,rect.left+rect.width()*.78f);Thread.sleep(250)
        capture("dragged");check(node("前后对比分界线")!!.rangeInfo.current>.7f) {"Divider did not move: ${node("前后对比分界线")!!.rangeInfo.current}"}
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);Thread.sleep(600);capture("landscape")
        click("关闭前后对比");capture("closed");check(node("前后对比分界线")==null) {"Divider still visible after closing"}
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);Thread.sleep(400)
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        activity.window.removeOnFrameMetricsAvailableListener(frameListener)
        val frames=frameDurations.sorted()
        val median=frames.getOrNull(frames.size/2)?.div(1_000_000.0)?:0.0
        val p95=frames.getOrNull((frames.size*.95).toInt().coerceAtMost(frames.lastIndex))?.div(1_000_000.0)?:0.0
        val slow=frames.count{it>16_666_667}/frames.size.coerceAtLeast(1).toDouble()
        val perf="frames=${frames.size}, median=${"%.2f".format(median)}ms, p95=${"%.2f".format(p95)}ms, over16.7ms=${"%.1f".format(slow*100)}%"
        File(output,"ui-frame-metrics.json").writeText(JSONObject().put("count",frames.size).put("median_ms",median).put("p95_ms",p95).put("over_16_7ms_pct",slow*100).toString(2))
        return "$perf; divider enabled, dragged to 78%, survives landscape, then disabled"
    }
    private fun findScrollable(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
        if(node.isScrollable)return node
        for(i in 0 until node.childCount) {
            val child=node.getChild(i)?:continue
            val found=findScrollable(child)
            if(found!=null)return found
        }
        return null
    }
    private fun galleryAudit() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        fun nodes(): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            return uiAutomation.rootInActiveWindow
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun tree(): String {
            val lines = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                lines += (node.text ?: "").toString() + " [" + node.contentDescription + "]"
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(nodes())
            return lines.joinToString("\n")
        }
        fun click(label: String) {
            await(10000) { find(nodes(), label) != null }
            var node = find(nodes(), label) ?: error("Missing: $label\n" + tree())
            while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
            check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForIdleSync(); Thread.sleep(450)
        }
        fun bounds(label: String): Rect = Rect().also {
            (find(nodes(), label) ?: error("Missing: $label\n" + tree())).getBoundsInScreen(it)
        }
        fun capture(name: String) {
            uiAutomation.waitForIdle(250, 5000)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "gallery_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            File(output, "gallery_$name.txt").writeText(tree())
        }
        fun pointer(action: Int, x: Float, y: Float, start: Long) {
            val motion = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(motion, true)); motion.recycle()
        }
        fun gesture(x1: Float, y1: Float, x2: Float, y2: Float) {
            val start = android.os.SystemClock.uptimeMillis()
            pointer(0, x1, y1, start)
            for (step in 1..18) {
                pointer(2, x1 + (x2 - x1) * step / 18, y1 + (y2 - y1) * step / 18, start)
                Thread.sleep(16)
            }
            pointer(1, x2, y2, start); Thread.sleep(650)
        }
        fun sharePreview(expected: Bitmap) {
            val directory = File(targetContext.cacheDir, "lighttable")
            val previous = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
            click("照片操作"); click("分享预览图")
            await(10000) {
                val root = nodes()
                root != null && root.packageName?.toString() != targetContext.packageName &&
                    directory.listFiles()?.any { it.name !in previous } == true
            }
            val file = directory.listFiles()!!.first { it.name !in previous }
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            check(options.outWidth == expected.width && options.outHeight == expected.height)
            val start = android.os.SystemClock.uptimeMillis()
            uiAutomation.injectInputEvent(android.view.KeyEvent(start, start, 0, android.view.KeyEvent.KEYCODE_BACK, 0), true)
            uiAutomation.injectInputEvent(android.view.KeyEvent(start, android.os.SystemClock.uptimeMillis(), 1, android.view.KeyEvent.KEYCODE_BACK, 0), true)
            await { tree().contains("关闭沉浸式观片") }
        }
        val fixture = context.assets.open("13mb.jpg").use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
        } ?: error("Missing gallery photo fixture")
        val files = (1..12).map { index ->
            File(output, "gallery-$index.jpg").also { file ->
                val matrix = android.graphics.Matrix().apply { if (index % 4 == 0) postRotate(90f) }
                val cropW = if (index % 4 == 2) minOf(fixture.width, fixture.height) else fixture.width
                val cropH = when (index % 4) { 2 -> cropW; 3 -> (fixture.height / 4).coerceAtLeast(8); else -> fixture.height }
                val transformed = Bitmap.createBitmap(fixture, (fixture.width - cropW) / 2, (fixture.height - cropH) / 2, cropW, cropH, matrix, true)
                val variant = checkNotNull(transformed.copy(Bitmap.Config.ARGB_8888, true))
                if (transformed !== fixture) transformed.recycle()
                // Four edge markers prove that Fit preserves every corner for mixed ratios.
                if (index in 2..4) {
                    val painter = android.graphics.Canvas(variant)
                    val paint = android.graphics.Paint()
                    val side = minOf(variant.width, variant.height) * .24f
                    val markerColors = intArrayOf(android.graphics.Color.RED, android.graphics.Color.GREEN,
                        android.graphics.Color.BLUE, android.graphics.Color.YELLOW)
                    val corners = listOf(0f to 0f, (variant.width - side) to 0f,
                        0f to (variant.height - side), (variant.width - side) to (variant.height - side))
                    corners.forEachIndexed { marker, (x, y) ->
                        paint.color = markerColors[marker]; painter.drawRect(x, y, x + side, y + side, paint)
                    }
                }
                file.outputStream().use { check(variant.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                if (variant !== fixture) variant.recycle()
                EditStore.save(targetContext, Uri.fromFile(file), EditorParams().apply {
                    filmId = when (index % 3) { 1 -> "kodak_gold_200"; 2 -> "fujifilm_velvia_100"; else -> "ilford_hp5_plus_400" }
                    overscanFormat = if (index == 6) "120_6x6" else "135"
                })
            }
        }
        fixture.recycle()
        case("ui.gallery.contact_sheet_viewer_and_loupe") {
          try {
            val uris = files.map(Uri::fromFile)
            runOnMainSync {
                vm.loadViewingPhotos(uris)
                check(vm.isImportingViewingPhotos)
                vm.openPhotos(uris.take(1))
            }
            await(30000) { vm.viewingPhotos.size == 12 && !vm.isImportingViewingPhotos && !vm.busy && vm.rendered != null }
            click("继续显影"); click("观景台")
            await { tree().contains("照片墙") && tree().contains("查看第 4 张 gallery-4.jpg") }
            check(!tree().contains("底片长廊") && !tree().contains("展厅"))
            val first = bounds("查看第 1 张 gallery-1.jpg")
            check(!tree().contains("每条最多") && !tree().contains("片边")) { "Synthetic film chrome remained" }
            val secondPhoto = bounds("查看第 2 张 gallery-2.jpg")
            check(first.width() != secondPhoto.width() || first.height() != secondPhoto.height()) { "Photos were forced into identical frames" }
            Thread.sleep(700); capture("contact_sheet")
            val fittedPixels = checkNotNull(uiAutomation.takeScreenshot())
            try {
                for (index in 2..4) {
                    val area = bounds("查看第 $index 张 gallery-$index.jpg")
                    val seen = BooleanArray(4)
                    for (y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(fittedPixels.height))
                        for (x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(fittedPixels.width)) {
                            val pixel = fittedPixels.getPixel(x, y)
                            val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel)
                            if (r > 170 && g < 95 && b < 95) seen[0] = true
                            if (g > 150 && r < 95 && b < 95) seen[1] = true
                            if (b > 150 && r < 95 && g < 125) seen[2] = true
                            if (r > 170 && g > 150 && b < 95) seen[3] = true
                        }
                    check(seen.all { it }) { "Frame $index cropped one or more corner markers: ${seen.toList()}" }
                }
            } finally { fittedPixels.recycle() }
            // A grouped visual index must still open the original source photo.
            val originalFirst = bounds("查看第 1 张 gallery-1.jpg")
            val secondUnmoved = bounds("查看第 2 张 gallery-2.jpg")
            fun regionHash(area: Rect): Long {
                val image = checkNotNull(uiAutomation.takeScreenshot())
                var hash = 19L
                for (y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(image.height) step 5)
                    for (x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(image.width) step 5)
                        hash = hash * 31 + image.getPixel(x, y)
                image.recycle(); return hash
            }
            val firstBeforeMove = regionHash(originalFirst)
            gesture(originalFirst.exactCenterX(), originalFirst.exactCenterY(), originalFirst.exactCenterX(), originalFirst.exactCenterY() + 70)
            check(regionHash(originalFirst) != firstBeforeMove) { "Dragging a photo did not move its pixels" }
            // A new upper overlap may change composited pixels; the neighbor's geometry stays independent.
            check(bounds("查看第 2 张 gallery-2.jpg") == secondUnmoved) { "Dragging one photo moved another" }
            capture("photo_moved")
            click("查看第 7 张 gallery-7.jpg")
            await { tree().contains("07 / 12") && tree().contains("沉浸照片 gallery-7.jpg") }
            click("关闭沉浸式观片")
            val before = uiAutomation.takeScreenshot()!!
            val bright = before.getPixel(first.centerX(), first.centerY()); before.recycle()
            click("关闭灯光"); await { tree().contains("打开灯光") }
            runOnMainSync { check(activity.window.attributes.screenBrightness == .2f) }
            val after = uiAutomation.takeScreenshot()!!
            val dim = after.getPixel(first.centerX(), first.centerY()); after.recycle()
            check(android.graphics.Color.red(dim) < android.graphics.Color.red(bright)) { "Lamp did not dim the photo" }
            capture("light_off"); click("打开灯光")
            runOnMainSync { check(activity.window.attributes.screenBrightness == .9f) }
            click("放大画布")
            check(find(nodes(), "照片墙")!!.stateDescription.toString().contains("125%"))
            val wall = bounds("照片墙")
            fun wallPixels(): Long {
                val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                var hash = 17L
                for (y in wall.top.coerceAtLeast(0) until wall.bottom.coerceAtMost(screenshot.height) step 8)
                    for (x in wall.left.coerceAtLeast(0) until wall.right.coerceAtMost(screenshot.width) step 8)
                        hash = hash * 31 + screenshot.getPixel(x, y)
                screenshot.recycle(); return hash
            }
            val beforePanPixels = wallPixels()
            // Drag empty table space, independently of dragging a photograph.
            gesture(wall.right - 25f, wall.bottom - 100f, wall.right - 100f, wall.bottom - 180f)
            check(wallPixels() != beforePanPixels) { "Canvas pan did not move rendered pixels; wall=$wall" }
            capture("canvas_zoom_pan")
            click("重置画布")
            check(find(nodes(), "照片墙")!!.stateDescription.toString().contains("100%"))
            // Real two-pointer gesture; unlike the zoom button this proves centroid handling.
            fun pinch(factor: Float) {
                val cx = wall.exactCenterX(); val cy = wall.top + wall.height() * .3f
                val start = android.os.SystemClock.uptimeMillis()
                val properties = Array(2) { i -> android.view.MotionEvent.PointerProperties().apply { id = i; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
                fun inject(action: Int, count: Int, radius: Float) {
                    val coordinates = Array(count) { i -> android.view.MotionEvent.PointerCoords().apply {
                        x = cx + (if (i == 0) -radius else radius); y = cy; pressure = 1f; size = 1f
                    } }
                    val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, count,
                        properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    check(uiAutomation.injectInputEvent(event, true)); event.recycle()
                }
                inject(android.view.MotionEvent.ACTION_DOWN, 1, 65f)
                inject(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 65f)
                for (step in 1..18) { inject(android.view.MotionEvent.ACTION_MOVE, 2, 65f * (1 + (factor - 1) * step / 18)); Thread.sleep(16) }
                inject(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 65f * factor)
                inject(android.view.MotionEvent.ACTION_UP, 1, 65f * factor); Thread.sleep(500)
            }
            pinch(1.8f)
            check(!find(nodes(), "照片墙")!!.stateDescription.toString().endsWith("缩放 100%")) { "Pinch did not zoom" }
            capture("canvas_pinch")
            click("重置画布")
            val previewsBeforeZoom = vm.galleryPreviews.keys.toSet()
            val resultBeforeZoom = vm.lastResult
            val developedBeforeZoom = vm.developedPreviews[uris.first()]
            pinch(7f) // Reach the 600% cap: fitted width now exceeds a 640px thumbnail.
            await(15000) { vm.galleryPreviews.keys.any { it !in previewsBeforeZoom } }
            check(vm.galleryPreviews.size <= 6) { "Zoom detail cache exceeded its limit" }
            check(vm.lastResult === resultBeforeZoom && vm.developedPreviews[uris.first()] === developedBeforeZoom) {
                "Wall refinement changed the active edit"
            }
            capture("canvas_detail")
            click("重置画布")
            click("打开放大镜"); await { tree().contains("拖动放大镜") }
            val lens = bounds("拖动放大镜")
            gesture(lens.exactCenterX(), lens.exactCenterY(), lens.exactCenterX() + 100f, lens.exactCenterY() + 80f)
            val moved = bounds("拖动放大镜")
            check(moved.centerX() != lens.centerX() || moved.centerY() != lens.centerY()) { "Loupe did not move" }
            capture("wall_loupe"); click("关闭放大镜")
            click("查看第 2 张 gallery-2.jpg")
            await { tree().contains("关闭沉浸式观片") && tree().contains("02 / 12") }
            capture("viewer_second")
            sharePreview(vm.galleryPreviews[uris[1]] ?: vm.viewingPhotos.first { it.first == uris[1].toString() }.second)
            click("下一帧"); capture("viewer_next"); await { tree().contains("03 / 12") }
            click("上一帧"); click("上一帧"); await { tree().contains("01 / 12") }
            sharePreview(vm.rendered!!)
            click("第 2 幅作品"); await { tree().contains("02 / 12") }
            click("上一帧"); await { tree().contains("01 / 12") }
            val image = bounds("沉浸照片 gallery-1.jpg")
            gesture(image.left + image.width() * .8f, image.exactCenterY(), image.left + image.width() * .2f, image.exactCenterY())
            await { tree().contains("02 / 12") }; capture("viewer_swipe")
            click("打开放大镜"); await { tree().contains("拖动放大镜") }; capture("viewer_loupe"); click("关闭放大镜")
            val second = bounds("沉浸照片 gallery-2.jpg")
            repeat(2) {
                val time = android.os.SystemClock.uptimeMillis()
                pointer(0, second.exactCenterX(), second.exactCenterY(), time)
                pointer(1, second.exactCenterX(), second.exactCenterY(), time)
                Thread.sleep(70)
            }
            await { tree().contains("还原 220%") }; capture("viewer_zoom")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { !tree().contains("还原 220%") && tree().contains("关闭沉浸式观片") }
            click("照片操作"); click("隐藏照片信息")
            check(find(nodes(), "gallery-2.jpg") == null)
            click("关闭沉浸式观片"); await { tree().contains("照片墙") }
            runOnMainSync { vm.loadViewingPhotos(uris) }
            await { !vm.isImportingViewingPhotos }; check(vm.viewingPhotos.size == 12)
            uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            Thread.sleep(900); capture("landscape")
            uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            Thread.sleep(900)
            click("返回暗房"); await { tree().contains("胶片与相纸") }
            check(files.all(File::exists)) { "Viewing operation deleted source photos" }
            "Unframed free canvas; intact square/panorama/portrait corner markers; independent photo drag and blank-table pan; source-correct click; on-demand zoom detail without changing edits; real pinch/reset; lamp pixels and window brightness; spatial movable loupe; arrow, thumbnail and swipe paging; double-tap zoom and Back; info; rotation; dedup and editor return passed"
          } catch (error: Throwable) {
            capture("failure")
            throw IllegalStateException(error.message + "\n" + tree(), error)
          }
        }
        case("ui.gallery.reimport_replaces_detail_cache") {
            val file = File(output, "cache-refresh.jpg")
            val uri = Uri.fromFile(file)
            fun writeImage(width: Int, height: Int, color: Int) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                bitmap.recycle()
            }
            try {
                writeImage(80, 40, android.graphics.Color.RED)
                runOnMainSync { vm.loadViewingPhotos(listOf(uri)) }
                await { !vm.isImportingViewingPhotos }
                runOnMainSync { vm.loadGalleryPreview(uri) }
                await { vm.galleryPreviews[uri] != null }
                check(vm.galleryPreviews[uri]!!.width == 80)
                runOnMainSync { vm.removeViewingPhoto(uri.toString()) }
                writeImage(40, 80, android.graphics.Color.BLUE)
                runOnMainSync { vm.loadViewingPhotos(listOf(uri)) }
                await { !vm.isImportingViewingPhotos }
                runOnMainSync { vm.loadGalleryPreview(uri) }
                await(10000) { vm.galleryPreviews[uri] != null }
                check(vm.galleryPreviews[uri]!!.width == 40 && vm.galleryPreviews[uri]!!.height == 80) {
                    "Removed and reimported photo reused stale detail pixels"
                }
                "same URI with changed source is decoded afresh after removal"
            } finally {
                runOnMainSync { vm.removeViewingPhoto(uri.toString()) }
            }
        }
        case("ui.gallery.removing_middle_frame_updates_selection") {
            try {
                click("观景台")
                click("查看第 2 张 gallery-2.jpg")
                await { tree().contains("02 / 12") }
                click("照片操作"); click("从观景台移除")
                await { tree().contains("02 / 11") && tree().contains("沉浸照片 gallery-3.jpg") }
                click("关闭沉浸式观片")
                await { find(nodes(), "查看第 2 张 gallery-3.jpg") != null }
                capture("middle_removed")
                await(5000) { find(nodes(), "查看第 2 张 gallery-3.jpg")?.stateDescription?.toString()?.contains("当前照片") == true }
                "viewer and wall selection follow the next photo when removing the middle frame"
            } finally {
                if (find(nodes(), "关闭沉浸式观片") != null) click("关闭沉浸式观片")
                if (find(nodes(), "返回暗房") != null) click("返回暗房")
            }
        }
        case("ui.gallery.unreadable_viewer_has_recovery") {
            val missingFile = File(output, "missing-viewer.jpg")
            if (missingFile.exists()) check(missingFile.delete())
            val missing = Uri.fromFile(missingFile)
            try {
                runOnMainSync { vm.addPhotos(listOf(missing), openSelected = false) }
                click("观景台")
                click("查看第 2 张 missing-viewer.jpg")
                await(8000) { tree().contains("无法读取照片") && tree().contains("重试读取照片") }
                capture("unreadable")
                val recovered = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
                recovered.eraseColor(android.graphics.Color.CYAN)
                missingFile.outputStream().use { check(recovered.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                recovered.recycle()
                click("重试读取照片")
                await(10000) { !tree().contains("无法读取照片") && tree().contains("沉浸照片 missing-viewer.jpg") }
                capture("unreadable_recovered")
                "unreadable sources show a retry action and recover when the source becomes available"
            } finally {
                if (find(nodes(), "关闭沉浸式观片") != null) click("关闭沉浸式观片")
                if (find(nodes(), "返回暗房") != null) click("返回暗房")
                runOnMainSync { vm.removePhotoFromSession(missing) }
                if (missingFile.exists()) check(missingFile.delete())
            }
        }
        case("ui.gallery.empty_import_cancel_failure_and_remove") {
          try {
            runOnMainSync {
                vm.viewingPhotos.map { it.first }.forEach(vm::removeViewingPhoto)
                vm.photos.toList().forEach(vm::removePhotoFromSession)
            }
            await { !vm.busy }
            click("观景台")
            await { tree().contains("把照片，放在光下") }
            capture("empty")
            click("导入照片")
            await(15000) { find(nodes(), "Cancel") != null }
            click("Cancel")
            await { tree().contains("把照片，放在光下") }
            val invalid = Uri.fromFile(File(output, "missing-import-" + System.nanoTime() + ".jpg"))
            runOnMainSync { vm.loadViewingPhotos(listOf(invalid)) }
            await { !vm.isImportingViewingPhotos }
            check(vm.viewingPhotos.isEmpty() && vm.viewingImportCompleted == 1)
            runOnMainSync { vm.loadViewingPhotos(listOf(Uri.fromFile(files[1]))) }
            await { !vm.isImportingViewingPhotos && tree().contains("查看第 1 张 gallery-2.jpg") }
            click("查看第 1 张 gallery-2.jpg")
            await { tree().contains("01 / 01") }
            capture("single_viewer")
            click("照片操作"); click("从观景台移除")
            await { tree().contains("把照片，放在光下") }
            check(files[1].exists() && vm.viewingPhotos.isEmpty())
            capture("empty_after_remove")
            val transparentFile = File(output, "transparent-original.png")
            val transparentBitmap = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888)
            val pngCanvas = android.graphics.Canvas(transparentBitmap)
            pngCanvas.drawRect(20f, 40f, 60f, 120f, android.graphics.Paint().apply { color = android.graphics.Color.CYAN })
            transparentFile.outputStream().use { check(transparentBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            transparentBitmap.recycle()
            val originalPngBytes = transparentFile.readBytes()
            val transparentUri = Uri.fromFile(transparentFile)
            runOnMainSync { vm.loadViewingPhotos(listOf(transparentUri)) }
            await { !vm.isImportingViewingPhotos && find(nodes(), "查看第 1 张 transparent-original.png")?.stateDescription?.toString()?.contains("80 × 160") == true }
            val transparentBounds = bounds("查看第 1 张 transparent-original.png")
            val alphaScreen = checkNotNull(uiAutomation.takeScreenshot())
            try {
                val corner = alphaScreen.getPixel(transparentBounds.left + 3, transparentBounds.top + 3)
                check(android.graphics.Color.red(corner) > 180 && android.graphics.Color.green(corner) > 180) { "PNG transparency became an opaque border" }
            } finally { alphaScreen.recycle() }
            capture("transparent_png")
            click("查看第 1 张 transparent-original.png")
            await { tree().contains("PNG · 80 × 160") }
            check(transparentFile.readBytes().contentEquals(originalPngBytes)) { "Viewing rewrote original PNG" }
            click("关闭沉浸式观片")
            runOnMainSync { vm.removeViewingPhoto(transparentUri.toString()) }
            runOnMainSync { vm.loadViewingPhotos(files.map(Uri::fromFile)) }
            await { !vm.isImportingViewingPhotos && vm.viewingPhotos.size == 12 }
            "empty UI, picker cancellation, failed import recovery, single-frame boundary, original PNG format/dimensions/alpha and untouched source bytes passed"
          } catch (error: Throwable) {
            capture("edge_failure")
            throw IllegalStateException(error.message + "\n" + tree(), error)
          }
        }
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        runOnMainSync { activity.finish() }
    }


    private fun uiAudit() {
        val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        runOnMainSync { vm=ViewModelProvider(activity)[EditorViewModel::class.java] }
        await {vm.engine!=null&&!vm.busy};Thread.sleep(800)
        fun tree():String {
            if(android.os.Build.VERSION.SDK_INT>=34) uiAutomation.clearCache()
            val lines=mutableListOf<String>()
            fun visit(n:AccessibilityNodeInfo?) {if(n==null)return;lines+="${n.className}: ${n.text ?: ""} [${n.contentDescription ?: ""}] click=${n.isClickable} scroll=${n.isScrollable}";for(i in 0 until n.childCount)visit(n.getChild(i))}
            visit(uiAutomation.rootInActiveWindow);return lines.joinToString("\n")
        }
        fun click(label:String) {
            if(android.os.Build.VERSION.SDK_INT>=34) uiAutomation.clearCache()
            fun locate(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(n==null)return null
                if(n.text?.toString()==label||n.contentDescription?.toString()==label) {
                    var candidate:AccessibilityNodeInfo?=n
                    while(candidate!=null) {if(candidate.isClickable)return candidate;candidate=candidate.parent}
                }
                for(i in 0 until n.childCount){val child=locate(n.getChild(i));if(child!=null)return child};return null
            }
            val node=locate(uiAutomation.rootInActiveWindow)?:error("UI element not clickable: $label\n${tree()}")
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));waitForIdleSync();Thread.sleep(500)
        }
        fun capture(name:String) {
            uiAutomation.takeScreenshot()?.let {bmp->File(output,"ui_$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
            File(output,"ui_$name.txt").writeText(tree())
        }
        case("ui.launch"){capture("launch");check(tree().contains("Theia"));"main activity visible, no debugger dialog"}
        case("ui.test_image_import"){click("更多操作");click("测试图");await{vm.lastResult!=null};capture("import");check(vm.rendered!=null);"test image rendered via real menu"}
        case("ui.gallery_entry") {
            val standFile = File(output, "stand-a.jpg")
            val standBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            standBitmap.eraseColor(android.graphics.Color.rgb(120, 100, 70))
            standFile.outputStream().use { check(standBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            standBitmap.recycle()
            runOnMainSync { vm.loadViewingPhotos(listOf(Uri.fromFile(standFile))) }
            await { !vm.isImportingViewingPhotos && vm.viewingPhotos.isNotEmpty() }
            click("观景台"); await { tree().contains("照片墙") }
            capture("gallery_contact_sheet")
            click("关闭灯光"); await { tree().contains("打开灯光") }
            click("打开灯光"); click("打开放大镜"); await { tree().contains("拖动放大镜") }
            click("关闭放大镜"); click("返回暗房"); await { tree().contains("胶片与相纸") }
            "new contact sheet entry, lighting, loupe and editor return passed"
        }


        for((tab,section) in listOf("拍摄" to "胶片与相纸","暗房" to "放大机","调色" to "白平衡","导出" to "配方"))case("ui.tab.$tab"){if(!tree().contains(section))click(tab);await{tree().contains(section)};capture(tab);"tab content verified: $section"}
        case("ui.grain_toggle"){
            click("暗房");await{tree().contains("放大机")}
            var visible=false
            repeat(12){if(tree().contains("颗粒"))visible=true else {
                fun scroll(n:AccessibilityNodeInfo?):Boolean {if(n==null)return false;if(n.isScrollable&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;for(i in 0 until n.childCount)if(scroll(n.getChild(i)))return true;return false}
                check(scroll(uiAutomation.rootInActiveWindow));Thread.sleep(100)
            }}
            check(visible);val old=vm.lastResult;click("颗粒");await{vm.lastResult!==old||vm.errorMessage!=null}
            check(vm.errorMessage==null){vm.errorMessage!!};capture("grain_toggle");"grain control triggered native render"
        }
        case("ui.system_picker_import") {
            val name="bmw-f-450-rm-sport-2048x2048-27531.jpg"
            click("导入照片")
            await(15000) {
                val pkg = uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                pkg != activity.packageName && tree().isNotBlank()
            }
            capture("picker")
            val old=vm.lastResult
            try {
                // Photo Picker exposes date-based content descriptions, while
                // the legacy document fallback exposes the filename. Accept
                // either surface and select the first visible image tile.
                fun findImageTile(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                    if(n==null)return null
                    val d=n.contentDescription?.toString().orEmpty().lowercase()
                    val text=n.text?.toString().orEmpty().lowercase()
                    if(n.isClickable && (d.contains("photo taken") || d.contains("preview the file") || text.contains(name.lowercase()))) return n
                    for(i in 0 until n.childCount){val found=findImageTile(n.getChild(i));if(found!=null)return found}
                    return null
                }
                val tile=findImageTile(uiAutomation.rootInActiveWindow)
                if(tile!=null) { check(tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)); waitForIdleSync(); Thread.sleep(300) }
                if (uiAutomation.rootInActiveWindow?.packageName?.toString() == activity.packageName) {
                    await{vm.currentUri?.lastPathSegment?.contains("bmw-f-450-rm-sport-2048x2048-27531") == true && vm.lastResult !== old && !vm.busy}
                    check(vm.errorMessage==null){vm.errorMessage!!};capture("picker_imported")
                    return@case "selected the real JPEG $name using the system picker; decode and render returned to the editor"
                }
                fun openLabel(n:AccessibilityNodeInfo?):String? {
                    if(n==null)return null
                    val label=n.text?.toString()?.lowercase().orEmpty()
                    if(n.isClickable && (
                        label=="open" || label.startsWith("open ") || label=="打开" ||
                        label.startsWith("选择") || label=="done" || label=="add" ||
                        label=="select" || label=="完成" || label=="添加"
                    )) return n.text?.toString() ?: n.contentDescription?.toString()
                    for(i in 0 until n.childCount){val found=openLabel(n.getChild(i));if(found!=null)return found}
                    return null
                }
                val confirm=openLabel(uiAutomation.rootInActiveWindow)
                if (confirm != null) click(confirm)
                await(15000){vm.lastResult!==old&&vm.hasPhoto&&vm.sourceBitmap!=null&&!vm.busy}
                check(vm.errorMessage==null){vm.errorMessage!!};capture("picker_imported");"selected the real JPEG $name in Documents UI and confirmed Open"
            }
            finally {repeat(4){if(uiAutomation.rootInActiveWindow?.packageName?.toString()!=targetContext.packageName){sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(250)}}}
        }
        case("ui.crop_drag_outside_boundary") {
            // Test-owned file keeps the crop gesture independent of the system picker's outcome.
            val old=vm.lastResult;runOnMainSync{vm.dismissError();vm.openPhotos(listOf(Uri.fromFile(File(output,"fixture.jpg"))))};await{vm.lastResult!==old&&!vm.busy}
            click("调色");await{tree().contains("白平衡")}
            var found=false
            repeat(10){if(tree().contains("自由裁剪"))found=true else {
                fun scroll(n:AccessibilityNodeInfo?):Boolean {if(n==null)return false;if(n.isScrollable&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;for(i in 0 until n.childCount)if(scroll(n.getChild(i)))return true;return false}
                check(scroll(uiAutomation.rootInActiveWindow));Thread.sleep(200)
            }}
            check(found);click("自由裁剪");check(vm.isCropping)
            if(android.os.Build.VERSION.SDK_INT>=34)uiAutomation.clearCache()
            fun locate(n:AccessibilityNodeInfo?):AccessibilityNodeInfo?{if(n==null)return null;if(n.contentDescription?.toString()=="照片预览")return n;for(i in 0 until n.childCount){val r=locate(n.getChild(i));if(r!=null)return r};return null}
            val rect=Rect();locate(uiAutomation.rootInActiveWindow)!!.getBoundsInScreen(rect)
            val source=vm.sourceBitmap!!;val scale=minOf(rect.width().toFloat()/source.width,rect.height().toFloat()/source.height)
            val x=rect.exactCenterX()+source.width*scale/2-3;val y=rect.exactCenterY()+source.height*scale/2-3
            val time=SystemClock.uptimeMillis()
            fun event(action:Int,t:Long,xx:Float,yy:Float){val e=MotionEvent.obtain(time,t,action,xx,yy,0);check(uiAutomation.injectInputEvent(e,true));e.recycle()}
            event(MotionEvent.ACTION_DOWN,time,x,y)
            repeat(8){i->event(MotionEvent.ACTION_MOVE,time+(i+1)*20,x,y+(i+1)*15)}
            event(MotionEvent.ACTION_UP,time+200,x,y+120);Thread.sleep(300)
            check(vm.params.cropW in 0.1f..1f&&vm.params.cropH in 0.1f..1f);capture("crop_boundary")
            click("完成裁剪");await{!vm.busy&&vm.lastResult!=null};"dragged beyond photo bounds, committed the crop, and returned to the editor"
        }
        case("ui.rotation_landscape"){uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);Thread.sleep(700);capture("landscape");check(activity.resources.configuration.orientation==2);"landscape layout visible"}
        case("ui.rotation_portrait"){uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);Thread.sleep(700);capture("portrait");"portrait restored"}
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
    }
    private fun refactoredUiAudit(): String {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        fun nodes(): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            return uiAutomation.rootInActiveWindow
        }
        fun find(n: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) find(n.getChild(i), label)?.let { return it }
            return null
        }
        fun click(label: String) {
            sendStatus(0, Bundle().apply { putString("stream", "UI: $label\n") })
            // Stock choices are virtualized; scroll the library before asserting a missing item.
            if (find(nodes(), label) == null && (vm.films + vm.papers).any { it.name == label }) {
                val listLabel = if (find(nodes(), "相纸列表") != null) "相纸列表" else "胶片列表"
                for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    for (step in 0 until 12) {
                        if (find(nodes(), label) != null) break
                        val list = find(nodes(), listLabel) ?: break
                        if (!list.performAction(direction)) break
                        waitForIdleSync(); Thread.sleep(200)
                    }
                    if (find(nodes(), label) != null) break
                }
            }
            try {
                await(10000) { find(nodes(), label) != null }
            } catch (error: Throwable) {
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "refactor_failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                throw IllegalStateException("Missing UI control after waiting: $label", error)
            }
            var node = find(nodes(), label) ?: error("Missing UI control: $label")
            while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
            check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled: $label" }
            waitForIdleSync(); Thread.sleep(300)
        }
        fun scroll(n: AccessibilityNodeInfo?, action: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD): Boolean {
            if (n == null) return false
            if (n.isScrollable && n.performAction(action)) return true
            for (i in 0 until n.childCount) if (scroll(n.getChild(i), action)) return true
            return false
        }
        fun scrollToTop() {
            repeat(25) {
                if (!scroll(nodes(), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
                Thread.sleep(100)
            }
        }
        fun reveal(label: String) {
            repeat(25) {
                find(nodes(), label)?.let { node ->
                    var target = node
                    while (!target.isClickable && target.parent != null) target = target.parent
                    target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    waitForIdleSync(); Thread.sleep(150)
                    return
                }
                check(scroll(nodes())) { "Cannot find $label after scrolling" }
                Thread.sleep(100)
            }
            error("Control not reachable: $label")
        }
        fun capture(name: String) {
            waitForIdleSync()
            uiAutomation.waitForIdle(250, 5000)
            Thread.sleep(if (homeUiOnly) 900 else 160)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "refactor_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun verifyAdjustmentPreview() {
            if (find(nodes(), "全屏预览") == null) return // Expanded layouts retain the side-by-side workspace.
            val result = vm.lastResult
            fun bounds(label: String): android.graphics.Rect = android.graphics.Rect().also {
                (find(nodes(), label) ?: error("Missing $label")).getBoundsInScreen(it)
            }
            val originalHeight = bounds("照片预览").height()
            check(originalHeight >= 190 * activity.resources.displayMetrics.density) { "Adjustment preview is too small" }
            capture("preview_default")
            click("前后对比"); capture("preview_default_compare"); click("关闭前后对比")
            click("收起参数")
            check(bounds("照片预览").height() > originalHeight + 60 * activity.resources.displayMetrics.density) {
                "Collapsing controls did not release space to the photo"
            }
            capture("preview_expanded")
            click("前后对比")
            val divider = find(nodes(), "前后对比分界线") ?: error("Missing comparison divider")
            check(divider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .72f)
            }))
            capture("preview_compare")
            click("全屏预览")
            check(find(nodes(), "关闭全屏预览") != null)
            check(find(nodes(), "前后对比分界线") != null)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val metrics = activity.windowManager.currentWindowMetrics
                val nav = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
                check(bounds("全屏预览说明").bottom <= metrics.bounds.bottom - nav) {
                    "Fullscreen caption ${bounds("全屏预览说明")} is below safe bottom ${metrics.bounds.bottom - nav}"
                }
            }
            capture("preview_fullscreen")
            click("关闭全屏预览")
            check(find(nodes(), "展开参数") != null) { "Fullscreen close lost the collapsed panel state" }
            click("全屏预览")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { find(nodes(), "关闭全屏预览") == null }
            check(find(nodes(), "展开参数") != null && find(nodes(), "前后对比分界线") != null) {
                "System Back lost the panel or comparison state"
            }
            click("关闭前后对比")
            click("展开参数")
            val dragBounds = bounds("调整预览大小")
            val dragTime = SystemClock.uptimeMillis()
            fun dragTouch(action: Int, delay: Long, y: Float) {
                val event = MotionEvent.obtain(dragTime, dragTime + delay, action, dragBounds.exactCenterX(), y, 0)
                check(uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            dragTouch(MotionEvent.ACTION_DOWN, 0, dragBounds.exactCenterY())
            repeat(8) { step ->
                dragTouch(MotionEvent.ACTION_MOVE, (step + 1) * 25L,
                    dragBounds.exactCenterY() - (step + 1) * 12 * activity.resources.displayMetrics.density)
            }
            dragTouch(MotionEvent.ACTION_UP, 225, dragBounds.exactCenterY() - 96 * activity.resources.displayMetrics.density)
            waitForIdleSync(); Thread.sleep(200)
            check(bounds("照片预览").height() < originalHeight) { "Resizing did not change the split" }
            check((find(nodes(), "调整预览大小") ?: error("Missing resize control")).performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .5f)
                }))
            check(vm.lastResult === result) { "Preview layout changes triggered an image render" }
        }
        await { find(nodes(), "更多操作") != null }
        if (!vm.hasPhoto) capture("home_empty")
        val image = reviewImageAsset?.let { asset ->
            val decoded = context.assets.open(asset).use { BitmapFactory.decodeStream(it, null,
                BitmapFactory.Options().apply { inSampleSize = reviewImageSample }) } ?: error("Cannot decode review image")
            val exif = context.assets.open(asset).use { ExifInterface(it) }
            val transform = android.graphics.Matrix().apply {
                postRotate(exif.rotationDegrees.toFloat())
                if (exif.isFlipped) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } ?: Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(256 * 192) { i -> -0x1000000 or (((i % 256) * 255 / 256) shl 16) or (((i / 256) * 255 / 192) shl 8) or 70 }, 0, 256, 0, 0, 256, 192)
        }
        val files = (1..if (homeUiOnly) 3 else 2).map { index ->
          val png = homeUiOnly && index == 2
          File(output, "ui-refactor-$index.${if (png) "png" else "jpg"}").also { file ->
            val fixture = if (png) Bitmap.createBitmap(image, 0, 0, image.width, image.height,
                android.graphics.Matrix().apply { postRotate(90f) }, true) else image
            file.outputStream().use { fixture.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
            if (fixture !== image) fixture.recycle()
            if (homeUiOnly && index == 1) {
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "35/1")
                    setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
                    setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.002")
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:12 16:42:00")
                    saveAttributes()
                }
            }
            EditStore.save(targetContext, Uri.fromFile(file), EditorParams())
          }
        }
        image.recycle()
        runOnMainSync { vm.openPhotos(files.map(Uri::fromFile)) }
        await { vm.hasPhoto && !vm.busy && vm.lastResult != null }
        await { find(nodes(), "让光，慢慢显影。") != null }
        capture("home")
        if (homeUiOnly) {
            val sourceFormats = mapOf("a.JPEG" to "JPG", "a.png" to "PNG", "a.TIF" to "TIFF", "a.HEIC" to "HEIC",
                "a.webp" to "WEBP", "a.avif" to "AVIF", "a.DNG" to "RAW", "a.NEF" to "RAW", "unknown" to "照片")
            sourceFormats.forEach { (name, label) -> check(sourcePhotoInfo(name).format == label) }
            check(sourcePhotoInfo("a.jpg", "image/png").format == "PNG")
            check(sourcePhotoInfo("a.dng").detail == "RAW · DNG")
            val expectedCapture = listOf("35 mm", "f/2.8", "1/500 s", "ISO 200")
            check(captureReadouts(vm.photoInfo) == expectedCapture) { "Incorrect EXIF readouts: ${vm.photoInfo}" }
            check(captureClock(vm.photoInfo) == "16:42")
            fun metadata(): AccessibilityNodeInfo = find(nodes(), "首页照片参数") ?: error("Missing home metadata")
            fun checkHomeFormat(label: String) {
                reveal("首页照片参数")
                await { find(nodes(), "首页照片参数")?.let { find(it, label) != null } == true }
            }
            fun returnHome() {
                waitForIdleSync()
                uiAutomation.waitForIdle(250, 5000)
                await {
                    var back = find(nodes(), "返回首页")
                    while (back != null && !back.isClickable) back = back.parent
                    back?.isEnabled == true && !vm.busy
                }
                click("返回首页")
                await { find(nodes(), "Theia") != null && find(nodes(), "返回首页") == null }
                scrollToTop()
            }
            reveal("当前会话")
            await { vm.photos.take(3).all { vm.developedPreviews[it] != null || vm.thumbnails[it] != null } }
            reveal("编辑会话照片 1")
            var tile = find(nodes(), "编辑会话照片 1") ?: error("Missing session thumbnail")
            while (!tile.isClickable) tile = tile.parent ?: error("Session thumbnail is not actionable")
            val tileBounds = Rect().also(tile::getBoundsInScreen)
            val density = activity.resources.displayMetrics.density
            check(abs(tileBounds.height() - 84 * density) <= 2) { "Thumbnail has a detached footer: $tileBounds" }
            if (android.os.Build.VERSION.SDK_INT >= 30) check(tile.stateDescription?.toString() == "编辑中 · JPG") {
                "Incorrect current-photo announcement: ${tile.stateDescription}"
            }
            capture("home_session")
            scrollToTop(); checkHomeFormat("JPG")
            expectedCapture.forEach { check(find(metadata(), it) != null) { "Home missing $it" } }
            runOnMainSync { vm.exportFormat = "TIFF" }
            checkHomeFormat("JPG") // The source format must not follow export settings.
            val sameResult = vm.lastResult
            click("继续显影")
            await { find(nodes(), "照片参数信息") != null }
            check(vm.lastResult === sameResult) { "Opening the hero triggered rendering" }
            expectedCapture.forEach { check(find(find(nodes(), "照片参数信息"), it) != null) { "Editor missing $it" } }
            capture("home_editor_metadata")
            runOnMainSync { vm.params.exposureEv = .75f; vm.scheduleRender() }
            await { vm.lastResult !== sameResult && !vm.busy }
            returnHome()
            reveal("编辑会话照片 2"); click("编辑会话照片 2")
            await { vm.currentIndex == 1 && !vm.busy && vm.lastResult != null }
            capture("home_editor_png")
            returnHome()
            scrollToTop(); checkHomeFormat("PNG")
            check(captureReadouts(vm.photoInfo).isEmpty()) { "Missing PNG EXIF was invented" }
            capture("home_png")
            reveal("编辑会话照片 2")
            var selectedPng = find(nodes(), "编辑会话照片 2") ?: error("Missing PNG thumbnail")
            while (!selectedPng.isClickable) selectedPng = selectedPng.parent ?: error("PNG thumbnail is not actionable")
            if (android.os.Build.VERSION.SDK_INT >= 30) check(selectedPng.stateDescription?.toString() == "编辑中 · PNG") {
                "Incorrect PNG announcement: ${selectedPng.stateDescription}"
            }
            reveal("编辑会话照片 1"); click("编辑会话照片 1")
            await { vm.currentIndex == 0 && !vm.busy && vm.lastResult != null }
            check(abs(vm.params.exposureEv - .75f) < .001f) { "Home navigation lost saved edits" }
            returnHome(); scrollToTop(); checkHomeFormat("JPG")
            capture("home_restored")
            reveal("添加照片"); click("添加照片")
            await {
                nodes()?.let { it.packageName?.toString() != targetContext.packageName } == true
            }
            uiAutomation.waitForIdle(250, 5000)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { find(nodes(), "Theia") != null }
            check(vm.photos.size == 3 && vm.currentIndex == 0)
            val extras = (4..14).map { index -> File(output, "home-$index.jpg").also { files[0].copyTo(it, overwrite = true) } }
            runOnMainSync { vm.addPhotos(extras.map(Uri::fromFile)); vm.selectPhoto(13) }
            await { !vm.busy && vm.currentIndex == 13 && find(nodes(), "编辑会话照片 14") != null }
            reveal("编辑会话照片 14")
            await {
                vm.photos.indices.filter { find(nodes(), "编辑会话照片 ${it + 1}")?.isVisibleToUser == true }
                    .all { vm.developedPreviews[vm.photos[it]] != null || vm.thumbnails[vm.photos[it]] != null }
            }
            capture("home_many")
            runOnMainSync { vm.openPhotos(listOf(Uri.fromFile(files[0]))) }
            await { !vm.busy && vm.hasPhoto }
            scrollToTop(); reveal("当前会话"); capture("home_single")
            runOnMainSync { vm.removeCurrentPhoto() }
            await { !vm.hasPhoto && find(nodes(), "从一张照片开始") != null }
            scrollToTop(); capture("home_empty_final")
            runOnMainSync { vm.openPhotos(files.map(Uri::fromFile)) }
            await { !vm.busy && vm.hasPhoto }
            scrollToTop(); capture("home_final")
            check(vm.errorMessage == null)
            return "Home: full-image 84dp session tiles, editing state and source formats, JPEG EXIF, missing PNG metadata, source/export independence, editor readouts, edit restoration, picker cancel, 14-photo auto-scroll, single and empty states passed."
        }
        reveal("继续显影")
        click("继续显影")
        await { find(nodes(), "胶片与相纸") != null }
        capture("editor")
        val beforeNavigation = vm.lastResult
        if (photoStageOnly) {
            fun bounds(label: String): Rect = Rect().also {
                (find(nodes(), label) ?: error("Missing $label")).getBoundsInScreen(it)
            }
            fun openStage() {
                click("管理照片工作台")
                await { find(nodes(), "关闭展示台") != null }
                Thread.sleep(400)
            }
            fun control(label: String): AccessibilityNodeInfo {
                var node = find(nodes(), label) ?: error("Missing $label")
                while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
                return node
            }
            fun checkTarget(label: String) {
                val node = control(label)
                val rect = Rect().also(node::getBoundsInScreen)
                val min = 48 * activity.resources.displayMetrics.density - 1
                check(rect.width() >= min && rect.height() >= min) { "$label touch target is $rect" }
            }
            fun checkStageMetadata() {
                fun hasEditClaim(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    val text = node.text?.toString().orEmpty()
                    if ("未调整" in text || "已修改" in text) return true
                    return (0 until node.childCount).any { hasEditClaim(node.getChild(it)) }
                }
                check(!hasEditClaim(nodes())) { "Stage inferred edit history from the session dirty flag" }
                check(find(nodes(), "${vm.currentIndex + 1} / ${vm.photos.size}") != null)
            }
            // The queue is visible on arrival; switching never requires the sheet.
            await { find(nodes(), "编辑照片 2") != null }
            checkTarget("管理照片工作台"); checkTarget("添加编辑照片"); checkTarget("编辑照片 2")
            val topStrip = bounds("顶部照片工作台")
            val preview = bounds("照片预览")
            check(topStrip.bottom <= preview.top + 1) { "Top workbench overlaps the photo preview" }
            check(find(nodes(), "关闭展示台") == null)
            capture("top_queue")
            click("编辑照片 2")
            await { vm.currentIndex == 1 && !vm.busy }
            click("编辑照片 1")
            await { vm.currentIndex == 0 && !vm.busy }
            val afterDirectSwitch = vm.lastResult
            click("放印")
            await { find(nodes(), "编辑照片 2") != null }
            capture("top_queue_print")
            check(vm.lastResult === afterDirectSwitch) { "Opening print controls rerendered the photo" }
            click("调色")
            await { find(nodes(), "编辑照片 2") != null }
            capture("top_queue_grade")
            click("拍摄")
            openStage()
            checkStageMetadata() // Untouched photo.
            capture("stage_two_photos")
            check(vm.lastResult === afterDirectSwitch) { "Opening the stage triggered a render" }
            listOf("添加照片", "关闭展示台", "上一张", "下一张", "照片操作").forEach(::checkTarget)
            val currentBounds = bounds("展示台照片 1")
            check(currentBounds.width() >= 48 * activity.resources.displayMetrics.density - 1 &&
                currentBounds.height() >= 48 * activity.resources.displayMetrics.density - 1)
            check(!control("上一张").isEnabled) { "Previous should be disabled at the first photo" }
            // Android does not expose ACTION_CLICK on an already selected radio item.
            val currentTapTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(currentTapTime, SystemClock.uptimeMillis(), action,
                    currentBounds.exactCenterX(), currentBounds.exactCenterY(), 0)
                check(uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
            waitForIdleSync(); Thread.sleep(200)
            check(vm.lastResult === afterDirectSwitch) { "Tapping the current photo triggered a render" }
            click("照片操作"); capture("stage_menu")
            click("照片信息"); capture("stage_info"); click("关闭")
            check(vm.lastResult === afterDirectSwitch) { "Inspecting photo information triggered a render" }
            runOnMainSync { vm.params.exposureEv = 1.25f; vm.scheduleRender() }
            await { !vm.busy && vm.lastResult !== afterDirectSwitch }
            checkStageMetadata() // Currently edited photo.
            click("展示台照片 2")
            await { vm.currentIndex == 1 && !vm.busy && vm.lastResult != null }
            check(abs(EditStore.load(targetContext, Uri.fromFile(files[0]))!!.exposureEv - 1.25f) < .001f)
            check(!control("下一张").isEnabled) { "Next should be disabled at the last photo" }
            click("上一张")
            await { vm.currentIndex == 0 && !vm.busy }
            check(abs(vm.params.exposureEv - 1.25f) < .001f) { "Switching lost the first photo edit" }
            checkStageMetadata() // Saved edits restored after switching back.
            capture("stage_restored_edit")
            val beforePicker = vm.lastResult
            click("添加照片")
            Thread.sleep(600)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { find(nodes(), "关闭展示台") != null }
            check(vm.photos.size == 2 && vm.currentIndex == 0 && vm.lastResult === beforePicker)
            val extras = (3..24).map { index ->
                File(output, "stage-$index-photo-with-a-long-file-name.jpg").also { files[0].copyTo(it, overwrite = true) }
            }
            runOnMainSync { vm.addPhotos(extras.map(Uri::fromFile)) }
            check(vm.lastResult === beforePicker && vm.currentIndex == 0)
            runOnMainSync { vm.selectPhoto(23) }
            await { vm.currentIndex == 23 && !vm.busy && find(nodes(), "展示台照片 24") != null }
            Thread.sleep(500)
            val selectedBounds = bounds("展示台照片 24")
            check(selectedBounds.left >= 0 && selectedBounds.right <= activity.resources.displayMetrics.widthPixels) {
                "Selected photo did not scroll into view: $selectedBounds"
            }
            await(15000) {
                vm.photos.indices.filter { find(nodes(), "展示台照片 ${it + 1}")?.isVisibleToUser == true }
                    .all { index -> vm.developedPreviews[vm.photos[index]] != null || vm.thumbnails[vm.photos[index]] != null }
            }
            capture("stage_many_photos")
            click("关闭展示台")
            await { find(nodes(), "编辑照片 24") != null }
            val topSelected = bounds("编辑照片 24")
            check(topSelected.left >= 0 && topSelected.right <= activity.resources.displayMetrics.widthPixels) {
                "Top queue did not keep the active photo visible"
            }
            capture("top_queue_many")
            val beforeTopPicker = vm.lastResult
            click("添加编辑照片")
            Thread.sleep(600)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { find(nodes(), "编辑照片 24") != null }
            check(vm.photos.size == 24 && vm.currentIndex == 23 && vm.lastResult === beforeTopPicker)
            openStage()
            click("照片操作"); click("从展示台移除")
            await { vm.photos.size == 23 && !vm.busy }
            check(extras.last().exists()) { "Removing a stage entry deleted the source file" }
            runOnMainSync { vm.openPhotos(listOf(Uri.fromFile(files[0]))) }
            await { !vm.busy && vm.currentUri == Uri.fromFile(files[0]) }
            capture("stage_single_photo")
            click("照片操作"); click("从展示台移除")
            await { vm.photos.isEmpty() && !vm.hasPhoto }
            capture("stage_empty")
            check(files[0].exists() && find(nodes(), "添加照片") != null)
            click("关闭展示台")
            check(find(nodes(), "关闭展示台") == null)
            runOnMainSync { vm.openPhotos(files.map(Uri::fromFile)) }
            await { !vm.busy && vm.hasPhoto }
            openStage()
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { find(nodes(), "关闭展示台") == null }
            openStage()
            capture("stage_final")
            check(vm.errorMessage == null)
            return "Top queue: visible without sheet, direct switching, print/grade persistence, no preview overlap, 24-photo auto-scroll. Management: selection, compact navigation, metadata, picker cancel, add without rerender, edit preservation, auto-scroll across 24 photos, long names, removal without source deletion, single/empty states, explicit close and system Back passed."
        }
        if (layoutOnly) {
            if (find(nodes(), "全部调整") != null) click("全部调整")
            verifyAdjustmentPreview()
            capture("shoot")
            click("胶片与相纸")
            val firstFilm = vm.films.first()
            val beforeFilm = vm.lastResult
            click(firstFilm.name)
            await { vm.params.filmId == firstFilm.id && vm.lastResult !== beforeFilm }
            capture("film_stocks")
            click("应用胶片与相纸")
            click("放印")
            capture("print")
            if (find(nodes(), "全屏预览") != null) {
                click("放大机")
                click("前后对比")
                capture("print_overview_compare")
                click("关闭前后对比")
                click("放大机")
            }
            click("调色")
            capture("grade")
            reveal("几何与裁剪"); click("几何与裁剪")
            reveal("自由裁剪"); click("自由裁剪")
            check(vm.isCropping)
            capture("crop")
            click("完成裁剪")
            await { !vm.busy && !vm.isCropping }
            click("导出")
            capture("export")
            check(vm.errorMessage == null)
            return "Responsive UI screenshots captured; film selection, grouped controls, crop and export reachable."
        }
        click("全部调整")
        check(vm.lastResult === beforeNavigation) { "Opening controls triggered a render" }
        verifyAdjustmentPreview()
        reveal("测光方法")
        var meteringNode = find(nodes(), "测光方法") ?: error("Missing metering selector")
        while (!meteringNode.isClickable) meteringNode = meteringNode.parent ?: error("Metering selector is not clickable")
        val meteringBounds = android.graphics.Rect().also(meteringNode::getBoundsInScreen)
        check(meteringBounds.height() >= 48 * activity.resources.displayMetrics.density - 1) { "Selector target below 48dp" }
        if (android.os.Build.VERSION.SDK_INT >= 30) check(meteringNode.stateDescription?.toString() == "均衡测光") {
            "Metering state announcement: ${meteringNode.stateDescription}"
        }
        click("测光方法"); click("保护高光")
        await { vm.params.autoExposureMethod == "protect_highlights" }
        await { EditStore.load(targetContext, vm.currentUri!!)?.autoExposureMethod == "protect_highlights" }
        check(editorOptionLabel(vm.params.autoExposureMethod) == "保护高光")
        click("测光方法"); click("均衡测光")
        await { EditStore.load(targetContext, vm.currentUri!!)?.autoExposureMethod == "balanced" }
        capture("shoot")
        click("返回大图预览")
        click("1 / 2")
        Thread.sleep(700)
        capture("tray_open")
        await { find(nodes(), "下一张") != null }
        click("下一张")
        await { vm.currentIndex == 1 && !vm.busy }
        click("照片操作"); click("照片信息"); click("关闭")
        capture("photo_tray")
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); Thread.sleep(300)
        click("胶片与相纸")
        capture("film_stocks")
        click("黑白")
        val bw = vm.films.first { StockCatalog.isMonochrome(it.id) }
        click(bw.name)
        await { vm.params.filmId == bw.id && vm.lastResult != null }
        check(StockCatalog.canPrint(vm.params.filmId, vm.params.paperId))
        click("应用胶片与相纸")
        check(find(nodes(), "全部调整") != null) { "Editor layout state was not restored" }
        click("前后对比")
        check(find(nodes(), "前后对比分界线") != null)
        click("关闭前后对比")
        click("更多操作"); click("裁剪照片"); click("完成裁剪")
        await { !vm.busy && !vm.isCropping }
        click("放印")
        check(find(nodes(), "放大机") != null)
        reveal("乳剂与暗房效应")
        click("乳剂与暗房效应")
        reveal("颗粒")
        val beforeGrain = vm.lastResult
        click("颗粒")
        await { vm.lastResult !== beforeGrain || vm.errorMessage != null }
        check(vm.errorMessage == null) { vm.errorMessage!! }
        val beforePrint = vm.lastResult
        runOnMainSync { vm.params.printExposure = 1.1f; vm.scheduleRender() }
        await { vm.lastResult !== beforePrint }
        check(!vm.lastResult!!.rgba16.contentEquals(beforePrint!!.rgba16)) { "Print exposure did not change the output" }
        val printReusedNegative = vm.lastResult!!.reprint
        capture("print")
        click("调色")
        val negative = vm.lastResult
        val beforeGrade = vm.rendered
        runOnMainSync { vm.params.temperature = .4f; vm.scheduleGrade() }
        await { vm.rendered !== beforeGrade }
        check(vm.lastResult === negative) { "Layer 2 rebuilt the engine result" }
        capture("grade")
        click("导出")
        capture("export")
        runOnMainSync { vm.exportRes = 2048 }
        for (format in listOf("JPEG", "PNG", "TIFF")) {
            scrollToTop()
            click(format)
            check(vm.exportFormat == format)
            val previousExports = "导出成功".toRegex().findAll(vm.log).count()
            reveal("导出当前照片"); click("导出当前照片")
            if (vm.isExporting) {
                await(3000) { find(nodes(), "取消导出") != null || !vm.isExporting }
                if (vm.isExporting) {
                    check(find(nodes(), "导出中") != null) { "Export progress did not remain on the export page" }
                    uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(output, "refactor_export_progress.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
            await(90000) { !vm.isExporting }
            check(vm.errorMessage == null) { vm.errorMessage!! }
            check("导出成功".toRegex().findAll(vm.log).count() > previousExports) { "$format export did not finish" }
        }
        scrollToTop(); click("JPEG")
        click("批量导出 · 2")
        capture("export_batch")
        reveal("导出本次会话 (2 张)"); click("导出本次会话 (2 张)")
        if (vm.isBatchExporting) {
            await(3000) { find(nodes(), "取消导出") != null || !vm.isBatchExporting }
            if (vm.isBatchExporting) {
                check(find(nodes(), "导出中") != null)
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "refactor_export_batch_progress.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
        await(90000) { !vm.isBatchExporting }
        check(vm.errorMessage == null) { vm.errorMessage!! }
        check(vm.log.contains("批量导出 2/2"))
        click("返回暗房")
        check(find(nodes(), "白平衡") != null) { "Export back lost Grade tab" }
        click("返回大图预览")
        click("返回首页")
        check(find(nodes(), "继续显影") != null)
        return "Home/editor/tray/film+paper/compare/crop/print/grade/JPEG+PNG+TIFF/batch/back passed; opening controls did not render; print changed output (existing full parameter delta reprint=$printReusedNegative); Layer 2 reused the cached engine result."
    }

    private fun editorAudit() {
        val app=targetContext.applicationContext as Application
        lateinit var vm: EditorViewModel
        runOnMainSync {vm=EditorViewModel(app)}
        await {vm.engine!=null && !vm.busy && vm.films.isNotEmpty()}
        val fixture=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {setPixels(IntArray(w*h){i -> -0x1000000 or ((i%w*255/w) shl 16) or ((i/w*255/h) shl 8) or 64},0,w,0,0,w,h)}
        val files=listOf("fixture.jpg" to Bitmap.CompressFormat.JPEG,"fixture.png" to Bitmap.CompressFormat.PNG,"fixture.webp" to Bitmap.CompressFormat.WEBP)
            .map {(name,fmt)->File(output,name).also {it.outputStream().use {out->fixture.compress(fmt,95,out)}}}
        val rotated=File(output,"rotated.jpg").also {files[0].copyTo(it,true);ExifInterface(it).apply {setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}}
        (files+rotated).forEach {EditStore.save(app,Uri.fromFile(it),EditorParams())}
        fun open(file: File) {runOnMainSync {vm.dismissError();vm.openPhotos(listOf(Uri.fromFile(file)))};await {vm.errorMessage!=null||vm.lastResult!=null};check(vm.errorMessage==null){vm.errorMessage!!};check(vm.rendered!=null)}
        for(file in files)case("import.${file.extension}"){open(file);"${vm.rendered!!.width}x${vm.rendered!!.height}; ${vm.log.takeLast(300)}"}
        case("import.exif_orientation"){open(rotated);check(vm.sourceBitmap!!.width==h&&vm.sourceBitmap!!.height==w);"orientation applied"}
        val dng=File(output,"fixture.dng").also { context.assets.open("audit.dng").use {input->it.outputStream().use {out->input.copyTo(out)}} }
        EditStore.save(app,Uri.fromFile(dng),EditorParams())
        case("import.raw_dng"){open(dng);check(vm.currentIsRaw);"${vm.sourceBitmap!!.width}x${vm.sourceBitmap!!.height}; LibRaw synthetic Bayer DNG"}
        case("raw.custom_white_balance"){val old=vm.lastResult;runOnMainSync{vm.params.cameraWbCustom=true;vm.params.cameraWbKelvin=7000f;vm.scheduleCameraWb()};await{vm.errorMessage!=null||vm.lastResult!==old};check(vm.errorMessage==null){vm.errorMessage!!};"custom WB redecoded"}
        case("raw.restore_as_shot"){val old=vm.lastResult;runOnMainSync{vm.resetCameraWb()};await{vm.errorMessage!=null||vm.lastResult!==old};check(vm.errorMessage==null){vm.errorMessage!!};"cached matrix with as-shot redecoded"}
        case("raw.original_export"){runOnMainSync{vm.exportRes=0;vm.export()};await{!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage!!};vm.log.takeLast(300)}
        open(files[0])
        fun changed(action:()->Unit) {val old=vm.lastResult;runOnMainSync(action);await {vm.errorMessage!=null||vm.lastResult!==old};check(vm.errorMessage==null){vm.errorMessage!!}}
        case("history.undo_redo_undo") {
            Thread.sleep(400);changed {vm.params.exposureEv=1f;vm.scheduleRender()};Thread.sleep(400)
            changed {vm.params.exposureEv=2f;vm.scheduleRender()}
            changed {vm.undo()};check(vm.params.exposureEv==1f)
            changed {vm.redo()};check(vm.params.exposureEv==2f)
            changed {vm.undo()};check(vm.params.exposureEv==1f);"1 -> 2 -> undo 1 -> redo 2 -> undo 1"
        }
        case("state.sidecar_restore"){val value=vm.params.exposureEv;open(files[1]);open(files[0]);check(vm.params.exposureEv==value);"restored EV=$value"}
        case("stock.monochrome_pair"){changed {vm.selectFilm("kodak_tri_x_400")};check(vm.params.paperId=="ilford_multigrade_iv_rc");"${vm.params.paperId}"}
        case("stock.positive_scan"){changed {vm.selectFilm("fujifilm_velvia_100")};check(JSONObject(vm.params.deltaJson(w,h)).getBoolean("scan_film"));"direct scan enabled"}
        case("stock.film_groups") {
            val groups = StockCatalog.filmGroups(vm.films)
            check(groups.map { it.first } == listOf("正片", "负片"))
            check(groups[0].second.all { it.type == "positive" })
            check(groups[1].second.none { it.type == "positive" })
            check(groups.sumOf { it.second.size } == vm.films.size)
            "${groups[0].second.size} positive / ${groups[1].second.size} negative"
        }
        case("stock.edge_text_auto") {
            runOnMainSync {
                vm.params.filmId = "kodak_gold_200"
                vm.params.overscanFormat = "135"
            }
            check(JSONObject(vm.params.deltaJson(w, h)).getString("overscan_edge_text") == "KODAK GB 200-7")
            runOnMainSync { vm.params.overscanFormat = "120_6x6" }
            check(JSONObject(vm.params.deltaJson(w, h)).getString("overscan_edge_text") == "KODAK 200")
            "resolved from stock + gauge; manual sidecar text ignored"
        }
        open(files[0]);changed {vm.resetAll()}
        case("geometry.crop_commit") {
            val before = vm.lastResult
            runOnMainSync {
                vm.params.cropX = 0.25f; vm.params.cropY = 0.1f
                vm.params.cropW = 0.5f; vm.params.cropH = 0.8f
                vm.isCropping = true
                vm.finishCrop()
            }
            await { vm.lastResult !== before && !vm.isCropping }
            check(vm.lastResult!!.width < w || vm.lastResult!!.height < h)
            "crop committed and re-rendered at ${vm.lastResult!!.width}x${vm.lastResult!!.height}"
        }
        val gradeFields=listOf("temperature","tint","gradeExposure","contrast","brightness","highlights","shadows","saturation","blackPoint","whitePoint","cbShadowTemp","cbShadowTint","cbMidTemp","cbMidTint","cbHighTemp","cbHighTint")
        for(field in gradeFields)case("grade.$field") {
            changed {vm.resetAll()}
            val before=vm.rendered!!;val oldHash=IntArray(before.width*before.height).also{before.getPixels(it,0,before.width,0,0,before.width,before.height)}.contentHashCode()
            val setter=vm.params.javaClass.methods.first{it.name=="set"+field.replaceFirstChar{c->c.uppercaseChar()}}
            runOnMainSync {setter.invoke(vm.params,if(field=="temperature"||field=="tint")0.5f else if(field=="gradeExposure")1f else 40f);vm.scheduleGrade()}
            await {vm.rendered!==before};val bmp=vm.rendered!!;val pix=IntArray(bmp.width*bmp.height);bmp.getPixels(pix,0,bmp.width,0,0,bmp.width,bmp.height)
            check(pix.contentHashCode()!=oldHash){"no visual change"};check(vm.histogram!!.r.contentEquals(ColorPipeline.computeHistogram(bmp).r));"pixels and histogram updated"
        }
        for(format in listOf("JPEG","PNG","TIFF"))case("editor.export.$format") {
            runOnMainSync {vm.dismissError();vm.exportFormat=format;vm.exportRes=2048;vm.export()};await { !vm.isExporting };check(vm.errorMessage==null){vm.errorMessage!!};check(vm.renderProgress==null&&vm.renderStage.isEmpty());vm.log.takeLast(300)
        }
        case("editor.export.TIFF_DisplayP3") {
            changed{vm.resetAll()};runOnMainSync{vm.dismissError();vm.exportFormat="TIFF";vm.exportColorSpace="Display P3";vm.export()}
            await{!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage!!};vm.log.takeLast(300)
        }
        case("editor.softproof"){val old=vm.lastResult;runOnMainSync{vm.dismissError();vm.softProof()};await{!vm.isExporting};check(vm.lastResult!==old);check(vm.errorMessage==null){vm.errorMessage!!};"proof rendered"}
        case("editor.softproof_p3_display_transform") {
            val raw=vm.lastResult!!.toArgb();val bmp=vm.rendered!!;val shown=IntArray(bmp.width*bmp.height)
            bmp.getPixels(shown,0,bmp.width,0,0,bmp.width,bmp.height)
            check(!raw.contentEquals(shown)){"P3 was displayed as sRGB without conversion"};"P3 values transformed to sRGB display"
        }
        case("editor.preview"){val old=vm.lastResult;runOnMainSync{vm.previewTier()};await{!vm.isExporting};check(vm.lastResult!==old);"preview rendered"}
        case("editor.recipe_roundtrip") {val recipe=ExportRecipe("Audit", "JPEG",2048,"sRGB",87);runOnMainSync{vm.applyRecipe(recipe)};check(vm.jpegQuality==87&&vm.exportRes==2048);"recipe applied"}
        case("editor.recipe_persistence") {
            val before=RecipeStore.load(app);val name="QA-${System.currentTimeMillis()}"
            runOnMainSync{vm.saveRecipe(name)};check(RecipeStore.load(app).any{it.name==name&&it.jpegQuality==87})
            RecipeStore.save(app,before);"saved and reloaded; original recipe list restored"
        }
        case("editor.sync_preserves_geometry") {
            val target=EditorParams().apply{cropX=0.25f;cropW=0.5f;quarterTurns=1;flipH=true}
            EditStore.save(app,Uri.fromFile(files[1]),target)
            runOnMainSync{vm.openPhotos(files.map{Uri.fromFile(it)})};await{vm.lastResult!=null&&!vm.busy}
            changed{vm.params.grainAmount=1.25f;vm.params.exposureEv=1f;vm.scheduleRender()}
            runOnMainSync{vm.syncSettingsToOtherPhotos()}
            val saved=EditStore.load(app,Uri.fromFile(files[1]))!!
            check(saved.cropX==0.25f&&saved.cropW==0.5f&&saved.quarterTurns==1&&saved.flipH&&saved.grainAmount==1.25f)
            "grain/exposure synced; target crop/rotation/flip retained"
        }
        case("editor.batch_export_and_selection_lock") {
            val uri=vm.currentUri
            runOnMainSync{vm.exportFormat="JPEG";vm.exportRes=2048;vm.batchExport();vm.selectPhoto(1)}
            check(vm.currentUri==uri){"selection changed during batch"};await(120000){!vm.isBatchExporting}
            check(vm.errorMessage==null){vm.errorMessage!!};check(vm.log.contains("批量导出 3/3"));check(vm.currentUri==uri)
            vm.log.takeLast(450)
        }
        case("editor.di_file_export") {runOnMainSync{vm.dismissError();vm.exportDi()};await{!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage!!};vm.log.takeLast(250)}
        case("editor.hd_preview_reopens_source") {
            val large=File(output,"large.png")
            val bitmap=Bitmap.createScaledBitmap(fixture,4000,240,true)
            large.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            EditStore.save(app,Uri.fromFile(large),EditorParams());open(large);check(max(vm.sourceBitmap!!.width,vm.sourceBitmap!!.height)==2048)
            runOnMainSync{vm.previewTier()};await{!vm.isExporting};check(max(vm.rendered!!.width,vm.rendered!!.height)==3400)
            "2048px interactive input replaced by actual 3400px source"
        }
        runOnMainSync {vm.release()};Thread.sleep(300)
    }
}
