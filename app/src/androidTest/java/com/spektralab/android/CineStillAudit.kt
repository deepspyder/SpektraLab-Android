package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** F14 acceptance. Its catalogue check was red on the previous production host. */
internal class CineStillAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val (films, papers) = StockCatalog.load(test.targetContext)
        val cine = films.singleOrNull { it.id == "cinestill_800t" }
        check(cine != null) { "F14 missing: CineStill 800T is not in the production catalogue" }
        val base = films.single { it.id == "kodak_vision3_500t" }
        check(cine.derivedFrom == base.id && cine.antihalation == "no" && cine.targetPrint == base.targetPrint)
        check(!cine.monochrome && cine.type == base.type && cine.stage == base.stage)
        val source = fixture()
        val report = JSONObject()
        try {
            EngineClient.create(test.targetContext).use { engine ->
                fun params(film: String = cine.id, version: Int = 2, headroom: Float = 4f) = EditorParams().apply {
                    filmId = film; paperId = cine.targetPrint!!
                    grainActive = false; glareActive = false; autoExposure = false
                    halationModelVersion = version; halationHeadroomEv = headroom
                }
                fun render(p: EditorParams): SpkResult {
                    engine.openBitmap(source, p.openJson(source.width, source.height))
                    return engine.render("full")
                }
                val off = render(params().apply { halationActive = false })
                val baseOff = render(params(base.id).apply { halationActive = false })
                check(off.rgba16.contentEquals(baseOff.rgba16)) { "Alias changed measured emulsion data or neutral pack" }
                val physical = render(params())
                val removed = render(params(base.id).apply { antihalationRemoved = true })
                check(physical.rgba16.contentEquals(removed.rgba16)) { "CineStill and the same emulsion without backing disagree" }
                val zero = render(params(headroom = 0f))
                val classic = render(params(version = 1))
                check(!zero.rgba16.contentEquals(physical.rgba16)) { "Headroom has no effect on a clipped lamp" }
                check(!classic.rgba16.contentEquals(zero.rgba16)) { "Back-reflection still uses the classic Gaussian preset" }
                val backed = render(params("kodak_portra_400", version = 1))
                val backedModern = render(params("kodak_portra_400", headroom = 8f))
                check(backed.rgba16.contentEquals(backedModern.rgba16)) { "Backed film was changed by the new model" }
                val disabled = render(params(headroom = 8f).apply { halationActive = false })
                check(disabled.rgba16.contentEquals(off.rgba16)) { "Disabled optics retained highlight headroom" }
                val noReturn = render(params(headroom = 8f).apply { halationAmount = 0f; halationScatterAmount = 0f })
                check(noReturn.rgba16.contentEquals(off.rgba16)) { "Zero returned light retained headroom" }
                engine.openBitmap(source, params().openJson(source.width, source.height))
                val start = engine.render("full")
                engine.setChangedParams(params(version = 1).deltaJson(source.width, source.height))
                check(engine.render("full").rgba16.contentEquals(classic.rgba16)) { "Preset was stale after switching to classic" }
                engine.setChangedParams(params().deltaJson(source.width, source.height))
                check(engine.render("full").rgba16.contentEquals(start.rgba16)) { "Preset was stale after switching back" }
                for (invalid in listOf(0, 3, 1.5, -1)) {
                    val before = engine.getParams()
                    check(runCatching { engine.setParams(JSONObject().put("halation_model_version", invalid).toString()) }.isFailure)
                    check(engine.getParams() == before) { "Invalid version partially changed the session" }
                }
                for (invalid in listOf(-1, 9, "4", true)) {
                    val before = engine.getParams()
                    check(runCatching { engine.setParams(JSONObject().put("halation_headroom_ev", invalid).toString()) }.isFailure)
                    check(engine.getParams() == before)
                }
                for ((name, frame) in listOf("classic" to classic, "back-reflection-zero" to zero,
                        "back-reflection-four" to physical, "disabled" to off)) {
                    val bitmap = ColorPipeline.applyGradeToBitmap(frame.rgba16, frame.width, frame.height,
                        Grade(1f,1f,1f,1f,0f,0f,1f,0f,0f,0f,0f,FloatArray(3),FloatArray(3),FloatArray(3))).first
                    try { File(output,"f14-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                    finally { bitmap.recycle() }
                    report.put(name, hash(frame.rgba16))
                }
                File(output,"f14-native-params.json").writeText(engine.getParams())
            }
            state(films, papers)
            ExportEngineAudit.cancellation(test.targetContext, EditorParams().apply {
                filmId = cine.id; paperId = cine.targetPrint!!
                halationModelVersion = 2; halationHeadroomEv = 4f
            })
            File(output,"f14-native-report.json").writeText(report.toString(2))
        } finally { source.recycle() }
        return "Inherited Vision3 arrays/neutral pack exact; backing-removed emulsion equals CineStill; physical/classic/headroom active; retained backing/off/zero exact; live preset refresh and eight invalid atomic deltas; legacy edits/templates/history/snapshots/storage/future protection; in-flight physical export cancellation/reopen passed"
    }

    fun timings(): String {
        val source = fixture(1600, 1200)
        val rows = org.json.JSONArray()
        val changes = linkedMapOf<String, EditorParams.(Int) -> Unit>(
            "model" to { i -> halationModelVersion = if (i % 2 == 0) 1 else 2 },
            "headroom" to { i -> halationHeadroomEv = 1f + i },
            "area" to { i -> halationAmount = .8f + i * .2f },
            "scatter" to { i -> halationScatterAmount = .03f + i * .03f },
            "boost" to { i -> halationBoostEv = .5f + i * .25f },
            "film" to { i ->
                filmId = if (i % 2 == 0) "kodak_portra_400" else "cinestill_800t"
                paperId = if (i % 2 == 0) "kodak_endura_premier" else "kodak_2383"
            },
        )
        try {
            EngineClient.create(test.targetContext).use { engine ->
                for ((name, change) in changes) {
                    val p = EditorParams().apply {
                        filmId = "cinestill_800t"; paperId = "kodak_2383"
                        halationModelVersion = 2; halationHeadroomEv = 4f
                        grainActive = false; glareActive = false; autoExposure = false
                    }
                    engine.openBitmap(source, p.openJson(source.width, source.height))
                    engine.render("full")
                    val times = mutableListOf<Double>()
                    repeat(4) { i ->
                        p.change(i)
                        val start = android.os.SystemClock.elapsedRealtimeNanos()
                        engine.setChangedParams(p.deltaJson(source.width, source.height))
                        val frame = engine.render("full")
                        val ms = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1e6
                        check(frame.width == source.width && frame.height == source.height)
                        if (i > 0) times += ms
                    }
                    rows.put(JSONObject().put("case", name).put("times_ms", org.json.JSONArray(times))
                        .put("median_ms", times.sorted()[1]))
                }
            }
            File(output, "f14-parameter-timings.json").writeText(JSONObject()
                .put("width", source.width).put("height", source.height).put("rows", rows).toString(2))
            return "1600x1200 physical optics; model/headroom/area/scatter/boost/film, one warmup plus three measured updates per control; dimensions and precision retained"
        } finally { source.recycle() }
    }

    private fun state(films: List<Stock>, papers: List<Stock>) {
        val modern = EditorParams().apply {
            filmId="cinestill_800t";paperId=films.single { it.id==filmId }.targetPrint!!
            halationModelVersion=2;halationHeadroomEv=6f
        }
        check(EditSnapshot.capture(modern).toParams().toJson().toString() == modern.toJson().toString())
        check(modern.copy().halationHeadroomEv==6f && modern.copy().halationModelVersion==2)
        check(EditorParams().apply { copySettingsFrom(modern) }.halationModelVersion==2)
        val legacyJson = modern.toJson().apply { remove("halation_model_version");remove("halation_headroom_ev") }
        val legacy = EditorParams.fromJson(legacyJson)
        check(legacy.halationModelVersion==1 && legacy.halationHeadroomEv==4f)
        val template = EditTemplate.capture(modern,setOf(EditGroup.FILM))
        check(template.applyTo(EditorParams(),false,films,papers).params!!.let { it.halationModelVersion==2 && it.halationHeadroomEv==6f })
        val oldTemplate = template.toJson().apply { getJSONObject("values").remove("halation_model_version");getJSONObject("values").remove("halation_headroom_ev") }
        check(EditTemplate.fromJson(oldTemplate).applyTo(modern,false,films,papers).params!!.let { it.halationModelVersion==1 && it.halationHeadroomEv==4f })
        val materialOnly = EditTemplate.capture(modern,setOf(EditGroup.MATERIAL)).applyTo(EditorParams(),false,films,papers).params!!
        check(materialOnly.halationModelVersion==1) { "Material-only transfer leaked an effect setting" }
        val root=File(output,"f14-store-${System.nanoTime()}").apply { mkdirs() }
        val context=object:ContextWrapper(test.targetContext) { override fun getFilesDir()=root }
        val uri=Uri.fromFile(File(root,"owned-source.png"))
        try {
            EditStore.save(context,uri,modern)
            check(EditStore.load(context,uri)!!.let { it.halationModelVersion==2 && it.halationHeadroomEv==6f })
            EditStore.closeForTests(context)
            check(EditStore.load(context,uri)!!.halationModelVersion==2)
        } finally { EditStore.closeForTests(context) }
        SavedPhotoRepository.open(File(root,"future")).use { repo ->
            val future=modern.toJson().put("halation_model_version",3).toString()
            repo.save(uri,future,"Future")
            check(runCatching { repo.save(uri,modern.toJson().toString(),"Future") }.isFailure)
            check(repo.read(uri)==future)
        }
        for (bad in listOf(0,3,1.5,"2",true)) {
            val json=modern.toJson().put("halation_model_version",bad)
            check(runCatching { HalationModel.requireReadable(json) }.isFailure)
            check(runCatching { EditorParams.fromJson(json) }.isFailure)
            val badTemplate=template.toJson().apply { getJSONObject("values").put("halation_model_version",bad) }
            check(runCatching { EditTemplate.fromJson(badTemplate) }.isFailure)
        }
        for (bad in listOf(-1, 9, "4", true, JSONObject.NULL)) {
            check(runCatching { EditorParams.fromJson(modern.toJson().put("halation_headroom_ev", bad)) }.isFailure)
        }
    }

    companion object {
        fun fixture(width: Int=600,height: Int=400,salt: Int=0): Bitmap =
            Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(width*height) { i ->
                    val x=i%width;val y=i/width
                    when {
                        x in width*46/100..width*48/100 && y in height/4..height*3/4 -> 0xffffffff.toInt()
                        x in width/5..width*22/100 && y in height/3..height*2/3 -> 0xffff4530.toInt()
                        x in width*7/10..width*73/100 && y in height/3..height*2/3 -> 0xff40b0ff.toInt()
                        else -> 0xff000000.toInt() or (((x+salt)%17+10) shl 16) or (((y+salt)%13+10) shl 8) or 20
                    }
                },0,width,0,0,width,height)
            }
        private fun hash(samples: ShortArray): String {
            val digest=MessageDigest.getInstance("SHA-256")
            samples.forEach { digest.update((it.toInt() and 255).toByte());digest.update((it.toInt() ushr 8).toByte()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
