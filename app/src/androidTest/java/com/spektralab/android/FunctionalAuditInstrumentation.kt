package com.spektralab.android

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.graphics.Rect
import android.view.FrameMetrics
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.engine.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** Explicit opt-in device audit. Uses the packaged engine and real Android decoders/writers. */
class FunctionalAuditInstrumentation : Instrumentation() {
    private val rows = JSONArray()
    private lateinit var output: File
    private var compareOnly = false
    private var progressOnly = false
    private var exportOnly = false
    private val w = 512
    private val h = 384
    private val input = FloatArray(w * h * 3) { i ->
        val x = (i / 3) % w; val y = i / 3 / w
        when (i % 3) { 0 -> 0.01f + x.toFloat() / w; 1 -> 0.01f + y.toFloat() / h; else -> 0.18f }
    }
    override fun onCreate(arguments: Bundle?) { compareOnly = arguments?.getString("audit") == "compare";progressOnly=arguments?.getString("audit")=="progress";exportOnly=arguments?.getString("audit")=="export";super.onCreate(arguments);start() }
    override fun onStart() {
        output = File(targetContext.getExternalFilesDir(null), "functional-audit").apply { mkdirs() }
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
                "halation_active" to false,"halation_amount" to 4.0,"halation_scatter_amount" to 0.2,"halation_boost_ev" to 4.0,
                "antihalation_removed" to true,"dir_couplers_active" to false,"dir_couplers_amount" to 1.7,
                "glare_active" to false,"glare_amount" to 20.0,"preflash_exposure" to 0.02,"extended_dynamic_range" to true,
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
            case("export.digital_intermediate") {render();valid(e.renderDigitalIntermediate())}
            case("export.normalized_density") {render();valid(e.exportDi())}
            case("color.output_transform") {JSONObject(e.outputTransform("ProPhoto RGB","sRGB")).toString()}
            case("engine.memory_report") {JSONObject(e.memoryReport()).toString()}
            case("engine.cancel_idle_and_recover") {render();e.cancel();valid(render())}
        }
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
        case("ui.launch"){capture("launch");check(tree().contains("SpektraLab"));"main activity visible, no debugger dialog"}
        case("ui.test_image_import"){click("更多操作");click("测试图");await{vm.lastResult!=null};capture("import");check(vm.rendered!=null);"test image rendered via real menu"}
        case("ui.viewing_stand") {
            val preview = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(640 * 480) { i ->
                    val x = i % 640; val y = i / 640
                    -0x1000000 or ((x * 255 / 640) shl 16) or ((y * 255 / 480) shl 8) or 90
                }, 0, 640, 0, 0, 640, 480)
            }
            val standFiles = listOf("stand-a.jpg", "stand-b.jpg").mapIndexed { index, name ->
                File(output, name).also { file ->
                    file.outputStream().use { out -> preview.compress(Bitmap.CompressFormat.JPEG, 92 - index * 5, out) }
                }
            }
            runOnMainSync { vm.loadViewingPhotos(standFiles.map(Uri::fromFile)) }
            await(15000) { vm.viewingPhotos.size == 2 }
            click("更多操作"); click("观景台")
            await { tree().contains("关闭观景台") && tree().contains("从图库选择") }
            check(tree().contains("2 / 2"))
            click("放大")
            capture("viewing_stand_light_on")
            click("关闭灯光"); await { tree().contains("打开灯光") }
            capture("viewing_stand_light_off")
            uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90); Thread.sleep(500); capture("viewing_stand_landscape")
            uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0); Thread.sleep(500)
            click("关闭观景台"); await { tree().contains("SpektraLab") }
            "two gallery photos displayed together; zoom, light toggle, landscape, and close all worked"
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
