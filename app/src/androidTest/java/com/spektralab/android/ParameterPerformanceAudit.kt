package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in same-resolution timings. Fixture resets and hashing are outside timed sections. */
internal class ParameterPerformanceAudit(private val test: Instrumentation, private val output: File, private val label: String) {
    private data class Change(val name: String, val edit: EditorParams.() -> Unit)
    private val width = 1600
    private val height = 1200
    private val gradeMethod = EditorViewModel::class.java.getDeclaredMethod("grade", EditorParams::class.java).apply { isAccessible = true }

    fun run(): String {
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            await { vm.engine != null && !vm.busy }
            val report = JSONObject().put("label", label).put("width", width).put("height", height)
                .put("post", post(vm)).put("native", native()).put("interactive", interactive(vm))
                .put("brightness_exact_samples", if (label.startsWith("before")) 0 else brightnessParity())
                .put("scalar_exact_samples", scalarParity(vm)).put("raw", raw(vm))
            File(output, "parameter-perf-" + label + ".json").writeText(report.toString(2))
            return report.toString()
        } finally { test.runOnMainSync { activity.finish() } }
    }

    private fun postCases(): List<Change> = buildList {
        add(Change("neutral") {})
        mapOf("temperature" to .4f, "tint" to .3f, "gradeExposure" to .6f, "contrast" to 30f,
            "brightness" to 20f, "highlights" to -30f, "shadows" to 35f, "saturation" to 25f,
            "blackPoint" to 8f, "whitePoint" to 9f, "cbShadowTemp" to 30f, "cbShadowTint" to -25f,
            "cbMidTemp" to 30f, "cbMidTint" to -25f, "cbHighTemp" to 30f, "cbHighTint" to -25f).forEach { (field, value) ->
            add(Change(field) { javaClass.getMethod("set" + field.replaceFirstChar { it.uppercaseChar() }, Float::class.javaPrimitiveType).invoke(this, value) })
        }
        add(Change("vignetteAmount") { vignetteAmount = 40f })
        add(Change("vignetteSize") { vignetteAmount = 40f; vignetteSize = 25f })
        add(Change("vignetteFeather") { vignetteAmount = 40f; vignetteFeather = 40f })
        add(Change("vibrance") { colorAdjustments = ColorAdjustments.NEUTRAL.withVibrance(35f) })
        CurveChannel.entries.forEach { channel -> add(Change("curve." + channel.key) {
            toneCurves = ToneCurves.IDENTITY.with(channel, ToneCurve.IDENTITY.add(.5f, .62f))
        }) }
        HslColor.entries.forEach { color -> listOf("hue", "saturation", "luminance").forEach { axis ->
            add(Change("hsl." + color.name + "." + axis) { colorAdjustments = ColorAdjustments.NEUTRAL.with(color, when (axis) {
                "hue" -> HslBand(hue = 30f)
                "saturation" -> HslBand(saturation = 30f)
                else -> HslBand(luminance = 30f)
            }) })
        } }
        add(Change("combined.legacy") { temperature = .3f; contrast = 25f; brightness = 15f; shadows = 20f;
            saturation = 20f; cbShadowTemp = -15f; vignetteAmount = 25f })
        add(Change("combined.curve-color") { contrast = 25f; brightness = 15f; shadows = 20f;
            toneCurves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .6f));
            colorAdjustments = ColorAdjustments.NEUTRAL.withVibrance(25f).with(HslColor.RED, HslBand(20f, 10f, -10f)) })
    }

    private fun post(vm: EditorViewModel): JSONArray {
        val input = ShortArray(width * height * 4) { (it * 373 + 17).toShort() }
        return JSONArray().apply { postCases().forEach { change ->
            val grade = gradeMethod.invoke(vm, EditorParams().apply(change.edit)) as Grade
            ColorPipeline.applyGradeToBitmap(input, width, height, grade).first.recycle()
            val times = mutableListOf<Double>()
            val freshTimes = mutableListOf<Double>()
            var hash = ""
            repeat(3) {
                val start = now()
                val (bitmap, histogram) = ColorPipeline.applyGradeToBitmap(input, width, height, grade)
                times += now() - start
                check(histogram.r.sum() == width * height && histogram.g.sum() == width * height && histogram.b.sum() == width * height)
                val next = pixelsHash(bitmap)
                check(hash.isEmpty() || hash == next); hash = next; bitmap.recycle()
            }
            if (change.name == "brightness" || change.name.startsWith("combined.")) repeat(3) {
                val start = now()
                val fresh = gradeMethod.invoke(vm, EditorParams().apply(change.edit)) as Grade
                val bitmap = ColorPipeline.applyGradeToBitmap(input, width, height, fresh).first
                freshTimes += now() - start
                check(pixelsHash(bitmap) == hash); bitmap.recycle()
            }
            put(JSONObject().put("parameter", change.name).put("ms", JSONArray(times))
                .put("fresh_grade_ms", JSONArray(freshTimes))
                .put("median_ms", times.sorted()[1]).put("pixels_sha256", hash))
        } }
    }

    private fun nativeCases(): List<Change> = buildList {
        val (films, papers) = StockCatalog.load(test.targetContext)
        for (film in films) {
            val paper = papers.firstOrNull { it.id == film.targetPrint && StockCatalog.canPrint(film.id, it.id) }
                ?: papers.first { StockCatalog.canPrint(film.id, it.id) }
            add(Change("film." + film.id) { filmId = film.id; paperId = paper.id })
        }
        for (paper in papers) {
            val film = films.firstOrNull { it.id == "kodak_portra_400" && StockCatalog.canPrint(it.id, paper.id) }
                ?: films.first { StockCatalog.canPrint(it.id, paper.id) }
            add(Change("paper." + paper.id) { filmId = film.id; paperId = paper.id })
        }
        add(Change("exposureEv") { exposureEv = .5f })
        add(Change("autoExposure") { autoExposure = false })
        add(Change("autoExposureMethod") { autoExposureMethod = "center" })
        add(Change("filmFormatMm") { filmFormatMm = 56f })
        add(Change("cameraFilter") { cameraFilter = "w8" })
        add(Change("printExposure") { printExposure = 1.2f })
        add(Change("yFilterShift") { yFilterShift = .2f })
        add(Change("mFilterShift") { mFilterShift = -.2f })
        add(Change("grainActive") { grainActive = false })
        add(Change("grainAmount") { grainAmount = 1.3f })
        add(Change("grainLayered") { grainLayered = false })
        add(Change("halationActive") { halationActive = false })
        add(Change("halationAmount") { halationAmount = 1.5f })
        add(Change("halationScatterAmount") { halationScatterAmount = .6f })
        add(Change("halationBoostEv") { halationBoostEv = 2f })
        add(Change("antihalationRemoved") { antihalationRemoved = true })
        add(Change("dirCouplersActive") { dirCouplersActive = false })
        add(Change("dirCouplersAmount") { dirCouplersAmount = .6f })
        add(Change("glareActive") { glareActive = false })
        add(Change("glareAmount") { glareAmount = 1.5f })
        add(Change("preflashExposure") { preflashExposure = .01f })
        add(Change("extendedDynamicRange") { extendedDynamicRange = true })
        add(Change("quarterTurns") { quarterTurns = 1 })
        add(Change("flipH") { flipH = true })
        add(Change("flipV") { flipV = true })
        add(Change("rotationDeg") { rotationDeg = 3f })
        add(Change("crop") { cropX = .1f; cropY = .05f; cropW = .8f; cropH = .9f })
        listOf("overscanActive", "overscanFormat", "overscanMode", "overscanGate", "overscanHoles",
            "overscanCarrier", "overscanFog", "overscanLeaks", "overscanFNumber", "overscanFrameNumber",
            "overscanCameraSeed", "overscanFrameSeed").forEach { field -> add(Change(field) {
            overscanActive = true
            when (field) {
                "overscanFormat" -> overscanFormat = "135_xpan"
                "overscanMode" -> overscanMode = "filed"
                "overscanGate" -> overscanGate = "rounded"
                "overscanHoles" -> overscanHoles = "black"
                "overscanCarrier" -> overscanCarrier = "black"
                "overscanFog" -> overscanFog = 2f
                "overscanLeaks" -> overscanLeaks = 1f
                "overscanFNumber" -> overscanFNumber = 2.8f
                "overscanFrameNumber" -> overscanFrameNumber = 12
                "overscanCameraSeed" -> overscanCameraSeed = 23
                "overscanFrameSeed" -> overscanFrameSeed = 42
            }
        }) }
        listOf("dateImprintActive", "dateImprintStyle", "dateImprintPlacement", "dateImprintCorner",
            "dateImprintSize", "dateImprintEv", "dateImprintText", "dateImprintDataText").forEach { field -> add(Change(field) {
            dateImprintActive = true; dateImprintText = "2026 11 12"
            when (field) {
                "dateImprintStyle" -> dateImprintStyle = "dots"
                "dateImprintPlacement" -> { overscanActive = true; dateImprintPlacement = "rebate" }
                "dateImprintCorner" -> dateImprintCorner = "tl"
                "dateImprintSize" -> dateImprintSize = 1.4f
                "dateImprintEv" -> dateImprintEv = 4f
                "dateImprintText" -> dateImprintText = "THEIA"
                "dateImprintDataText" -> { overscanActive = true; dateImprintStyle = "data"; dateImprintDataText = "50MM F2.8" }
            }
        }) }
        add(Change("sceneLatitude") { sceneLatitudeActive = true; sceneLatitudeHighlightRoom = 1f; sceneLatitudeShadowRoom = 1f })
        mapOf("sceneLatitudeHighlightKnee" to 1.5f, "sceneLatitudeHighlightRoom" to 2f,
            "sceneLatitudeShadowKnee" to -1.5f, "sceneLatitudeShadowRoom" to 2f,
            "sceneLatitudeRolloff" to 3f, "sceneLatitudeMaxLift" to 3f).forEach { (field, value) -> add(Change(field) {
                sceneLatitudeActive = true; sceneLatitudeHighlightRoom = 1f; sceneLatitudeShadowRoom = 1f
                javaClass.getMethod("set" + field.replaceFirstChar { it.uppercaseChar() }, Float::class.javaPrimitiveType).invoke(this, value)
            })
        }
        add(Change("sceneLatitudeNorm") { sceneLatitudeActive = true; sceneLatitudeHighlightRoom = 1f;
            sceneLatitudeShadowRoom = 1f; sceneLatitudeNorm = "y" })
    }

    private fun native(): JSONArray {
        val fixture = fixture()
        return EngineClient.create(test.targetContext).use { engine ->
            val base = EditorParams() // Preserve real defaults: grain, halation, DIR and glare.
            engine.openBitmap(fixture, base.openJson(width, height)); fixture.recycle(); engine.render("live")
            JSONArray().apply { nativeCases().forEach { change ->
                val times = mutableListOf<Double>(); val nativeTimes = mutableListOf<Double>()
                var result: SpkResult? = null
                repeat(3) {
                    engine.setParams(base.deltaJson(width, height)); engine.render("live")
                    val p = base.copy().apply(change.edit)
                    val start = now()
                    if (label.startsWith("before")) engine.setParams(p.deltaJson(width, height))
                    else engine.setChangedParams(p.deltaJson(width, height))
                    result = engine.render("live")
                    times += now() - start; nativeTimes += result!!.elapsedMs
                }
                put(JSONObject().put("parameter", change.name).put("ms", JSONArray(times))
                    .put("median_ms", times.sorted()[1]).put("native_ms", JSONArray(nativeTimes))
                    .put("reprint", result!!.reprint).put("width", result!!.width).put("height", result!!.height))
            } }
        }
    }

    private fun interactive(vm: EditorViewModel): JSONArray {
        val source = File(output, "parameter-perf-source.png")
        fixture().also { bitmap -> source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        val uri = Uri.fromFile(source)
        EditStore.save(test.targetContext, uri, EditorParams())
        test.runOnMainSync { vm.openPhotos(listOf(uri)) }
        await { vm.hasPhoto && !vm.busy && vm.rendered != null && vm.renderTaskState.active == null }
        val cases = JSONArray()
        for (name in listOf("contrast", "brightness", "curve", "printExposure", "exposureEv")) {
            var prior = vm.rendered
            val start = now(); var firstMs = -1.0; var frames = 0
            repeat(30) { step ->
                test.runOnMainSync {
                    val fraction = (step + 1) / 30f
                    when (name) {
                        "contrast" -> vm.params.contrast = 50f * fraction
                        "brightness" -> vm.params.brightness = 30f * fraction
                        "curve" -> vm.params.toneCurves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .5f + .15f * fraction))
                        "printExposure" -> vm.params.printExposure = 1f + .5f * fraction
                        "exposureEv" -> vm.params.exposureEv = fraction
                    }
                    if (name in listOf("printExposure", "exposureEv")) vm.scheduleRender() else vm.scheduleGrade()
                }
                SystemClock.sleep(32)
                if (vm.rendered !== prior) { prior = vm.rendered; frames++; if (firstMs < 0) firstMs = now() - start }
            }
            val duringFrames = frames; val release = now()
            await { !workerActive(vm, "wbJob") && !workerActive(vm, "renderJob") &&
                vm.renderTaskState.active == null && vm.renderTaskState.waiting.isEmpty() }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }; test.waitForIdleSync()
            val releaseMs = now() - release
            val totalMs = now() - start
            val reference = ColorPipeline.applyGrade(vm.lastResult!!.rgba16, vm.lastResult!!.width, vm.lastResult!!.height,
                gradeMethod.invoke(vm, vm.params.copy()) as Grade)
            val actual = IntArray(vm.rendered!!.width * vm.rendered!!.height)
            vm.rendered!!.getPixels(actual, 0, vm.rendered!!.width, 0, 0, vm.rendered!!.width, vm.rendered!!.height)
            check(reference.contentEquals(actual)) { "Final drag frame does not match latest parameters: " + name }
            check(EditStore.load(test.targetContext, uri)?.toJson().toString() == vm.params.toJson().toString()) {
                "Final drag sidecar differs: " + name
            }
            cases.put(JSONObject().put("parameter", name).put("first_during_drag_ms", firstMs)
                .put("frames_during_drag", duringFrames).put("after_release_ms", releaseMs).put("total_ms", totalMs)
                .put("pixels_sha256", pixelsHash(vm.rendered!!)))
        }
        if (!label.startsWith("before")) {
        val beforeCancel = vm.rendered
        test.runOnMainSync {
            vm.params.exposureEv = .25f
            vm.scheduleRender()
            vm.params.exposureEv = .5f
            vm.scheduleRender()
            vm.cancelRender()
        }
        await { !workerActive(vm, "renderJob") && vm.renderTaskState.active == null }
        SystemClock.sleep(250)
        check(vm.rendered === beforeCancel) { "Cancelled conflated input restarted its worker" }
        }
        return cases
    }

    private fun fixture(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(width * height) { i -> 0xff000000.toInt() or ((i % width * 255 / width) shl 16) or
            ((i / width * 255 / height) shl 8) or 90 }, 0, width, 0, 0, width, height)
    }
    private fun raw(vm: EditorViewModel): JSONArray {
        val source = File(output, "perf-z6ii.NEF")
        check(source.isFile) { "Install the existing Z6II fixture before running the full parameter audit" }
        val uri = Uri.fromFile(source)
        EditStore.save(test.targetContext, uri, EditorParams())
        test.runOnMainSync { vm.openPhotos(listOf(uri)) }
        await { vm.hasPhoto && !vm.busy && vm.currentUri == uri && vm.currentIsRaw &&
            vm.renderTaskState.active == null && !workerActive(vm, "frameRenderJob") }
        return JSONArray().apply {
            for (field in listOf("cameraWbCustom", "cameraWbKelvin", "cameraWbTint")) {
                val times = mutableListOf<Double>()
                repeat(3) { iteration ->
                    val start = now()
                    val previous = vm.lastResult
                    test.runOnMainSync {
                        vm.params.cameraWbCustom = true
                        when (field) {
                            "cameraWbKelvin" -> vm.params.cameraWbKelvin = 5600f + iteration * 100f
                            "cameraWbTint" -> vm.params.cameraWbTint = 10f + iteration * 5f
                        }
                        vm.scheduleCameraWb()
                    }
                    await { vm.lastResult !== previous && !workerActive(vm, "cameraWbJob") &&
                        !workerActive(vm, "frameRenderJob") && !vm.busy && vm.renderTaskState.active == null }
                    check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                    times += now() - start
                }
                put(JSONObject().put("parameter", field).put("end_to_end_ms", JSONArray(times))
                    .put("median_ms", times.sorted()[1]).put("width", vm.lastResult!!.width).put("height", vm.lastResult!!.height))
            }
            if (vm.matchedLens != null) {
                val start = now(); val previous = vm.lastResult
                test.runOnMainSync { vm.toggleLensCorrection() }
                await { vm.lastResult !== previous && !workerActive(vm, "cameraWbJob") &&
                    !workerActive(vm, "frameRenderJob") && vm.renderTaskState.active == null }
                put(JSONObject().put("parameter", "lensCorrection").put("end_to_end_ms", now() - start))
            } else put(JSONObject().put("parameter", "lensCorrection").put("status", "NO_MATCHING_PROFILE"))
        }
    }
    private fun brightnessParity(): Int {
        val input = ShortArray(256 * 256 * 4) { at ->
            val pixel = at / 4
            when (at % 4) { 0 -> pixel; 1 -> pixel * 373 + 17; 2 -> 65535 - pixel; else -> 65535 }.toShort()
        }
        val curves = ToneCurves.IDENTITY.table()
        val colors = ColorAdjustments.NEUTRAL.uniforms()
        var checked = 0
        for (brightness in listOf(-1f, -.3f, .2f, 1f)) for (contrast in listOf(-1f, .7f)) {
            val uniforms = floatArrayOf(.91f, 1.07f, 1.18f, 1.3f, contrast, brightness, 1.1f,
                -.2f, .3f, .04f, .05f, .01f, -.02f, .03f, .04f, -.03f, .02f,
                -.03f, .02f, -.01f, 0f, .5f, .7f, 0f, 0f)
            val table = ByteBuffer.allocateDirect(3 * 65536 * 4).order(ByteOrder.nativeOrder())
            NativeCurveGrade.prepareBrightness(uniforms, table)
            val expected = FloatArray(256 * 32 * 3); val actual = FloatArray(expected.size)
            for (y in 0 until 256 step 32) {
                NativeCurveGrade.apply(input, y * 256 * 4, 256, 256, y, 32, uniforms, curves, colors, null, null, expected)
                NativeCurveGrade.apply(input, y * 256 * 4, 256, 256, y, 32, uniforms, curves, colors, null, table, actual)
                check(expected.contentEquals(actual)) { "Native brightness memoization changed Float samples" }
                checked += actual.size
            }
        }
        return checked
    }
    private fun scalarParity(vm: EditorViewModel): Int {
        var checked = 0
        val expected = FloatArray(3); val actual = FloatArray(3)
        for (change in postCases()) {
            val p = EditorParams().apply(change.edit)
            fun zone(temp: Float, tint: Float) = floatArrayOf(.18f * (temp / 100f), -.12f * (tint / 100f), -.18f * (temp / 100f))
            val reference = ScalarGradeReference(1f + p.temperature * .18f, 1f - p.tint * .12f, 1f - p.temperature * .18f,
                Math.pow(2.0, p.gradeExposure.toDouble()).toFloat(), p.contrast / 100f, p.brightness / 100f,
                1f + p.saturation / 100f, p.highlights / 100f, p.shadows / 100f, p.blackPoint / 100f, p.whitePoint / 100f,
                zone(p.cbShadowTemp, p.cbShadowTint), zone(p.cbMidTemp, p.cbMidTint), zone(p.cbHighTemp, p.cbHighTint),
                p.vignetteAmount / 100f, p.vignetteSize / 100f, p.vignetteFeather / 100f, p.toneCurves, p.colorAdjustments)
            val current = gradeMethod.invoke(vm, p) as Grade
            for (sample in 0 until 65536) {
                val r = sample.toShort(); val g = (sample * 373 + 17).toShort(); val b = (65535 - sample).toShort()
                reference.gradeAt(r, g, b, expected, sample % 256, sample / 256, 256, 256)
                current.gradeAt(r, g, b, actual, sample % 256, sample / 256, 256, 256)
                check(expected.contentEquals(actual)) { "Scalar Float samples changed: " + change.name + " / " + sample }
                checked += 3
            }
        }
        return checked
    }
    private fun pixelsHash(bitmap: Bitmap): String {
        val bytes = ByteBuffer.allocate(bitmap.byteCount); bitmap.copyPixelsToBuffer(bytes)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e6
    private fun workerActive(vm: EditorViewModel, name: String): Boolean =
        (EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as? Job)?.isActive == true
    private fun await(condition: () -> Boolean) {
        val start = now()
        while (!condition()) { check(now() - start < 90000) { "Parameter audit timed out" }; SystemClock.sleep(5) }
    }
}
