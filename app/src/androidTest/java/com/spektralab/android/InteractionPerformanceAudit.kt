package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.CurveChannel
import com.spektralab.android.core.color.ToneCurve
import com.spektralab.android.core.color.ToneCurves
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.Grade
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Same-input, same-native-build measurements; never reduce image size or quality to improve timings. */
internal class InteractionPerformanceAudit(
    private val test: Instrumentation,
    private val output: File,
    private val label: String,
) {
    fun run(): String {
        val report = JSONObject().put("label", label).put("preview", preview()).put("stock", stocks())
        File(output, "interaction-perf-" + label.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
            .writeText(report.toString(2))
        return report.toString()
    }

    private fun preview(): JSONArray {
        val width = 1600
        val height = 1067
        val input = ShortArray(width * height * 4) { (it * 373 + 17).toShort() }
        val pixels = IntArray(width * height)
        val cases = JSONArray()
        val curve = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .63f))
        for (name in listOf("neutral", "legacy-tone", "active-curve")) {
            val grade = Grade(1f, 1f, 1f, 1f, if (name == "legacy-tone") .08f else 0f, 0f,
                1f, 0f, 0f, 0f, 0f, FloatArray(3), FloatArray(3), FloatArray(3),
                curves = if (name == "active-curve") curve else ToneCurves.IDENTITY)
            ColorPipeline.applyGradeToBitmap(input, width, height, grade).first.recycle()
            val times = mutableListOf<Double>()
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val collections = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            var hash: Int? = null
            repeat(5) {
                val start = SystemClock.elapsedRealtimeNanos()
                val (bitmap, histogram) = ColorPipeline.applyGradeToBitmap(input, width, height, grade)
                times += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                check(histogram.r.sum() == width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                bitmap.recycle()
                val next = pixels.contentHashCode()
                check(hash == null || hash == next)
                hash = next
            }
            val afterAllocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val afterCollections = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            cases.put(JSONObject().put("case", name).put("width", width).put("height", height)
                .put("ms", JSONArray(times)).put("median_ms", times.sorted()[2]).put("pixels_hash", hash)
                .put("allocated_bytes", if (allocated != null && afterAllocated != null) afterAllocated - allocated else JSONObject.NULL)
                .put("gc_count", if (collections != null && afterCollections != null) afterCollections - collections else JSONObject.NULL))
        }
        return cases
    }

    private fun stocks(): JSONObject {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val file = File(output, "stock-perf-source.png")
        try {
            await { vm.engine != null && !vm.busy }
            val width = 1600; val height = 1200
            val fixture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            fixture.setPixels(IntArray(width * height) { i ->
                0xff000000.toInt() or ((i % width * 255 / width) shl 16) or
                    ((i / width * 255 / height) shl 8) or 90
            }, 0, width, 0, 0, width, height)
            file.outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fixture.recycle()
            val uri = Uri.fromFile(file)
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.hasPhoto && !vm.busy && vm.rendered != null && vm.renderTaskState.active == null }
            val measurements = JSONArray()
            repeat(3) { round ->
                for (id in listOf("kodak_gold_200", "fujifilm_pro_400h", "kodak_ektar_100", "kodak_portra_400")) {
                    check(vm.films.any { it.id == id })
                    val previous = vm.lastResult
                    var mainMs = 0.0
                    val start = SystemClock.elapsedRealtimeNanos()
                    test.runOnMainSync {
                        val mainStart = SystemClock.elapsedRealtimeNanos()
                        vm.selectFilm(id)
                        mainMs = (SystemClock.elapsedRealtimeNanos() - mainStart) / 1e6
                    }
                    await { vm.lastResult !== previous && vm.renderTaskState.active == null && vm.renderTaskState.waiting.isEmpty() }
                    check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                    test.waitForIdleSync()
                    measurements.put(JSONObject().put("round", round).put("film", id).put("main_ms", mainMs)
                        .put("end_to_end_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                        .put("native_ms", vm.lastResult!!.elapsedMs))
                }
            }
            val before = vm.lastResult
            val rapidStart = SystemClock.elapsedRealtimeNanos()
            for (id in listOf("kodak_gold_200", "fujifilm_pro_400h", "kodak_ektar_100")) {
                test.runOnMainSync { vm.selectFilm(id) }
                SystemClock.sleep(25)
            }
            await { vm.lastResult !== before && vm.renderTaskState.active == null && vm.renderTaskState.waiting.isEmpty() }
            check(vm.params.filmId == "kodak_ektar_100" && vm.errorMessage == null)
            return JSONObject().put("preview_width", vm.lastResult!!.width).put("preview_height", vm.lastResult!!.height)
                .put("measurements", measurements).put("rapid_ms", (SystemClock.elapsedRealtimeNanos() - rapidStart) / 1e6)
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Interaction performance audit timed out" }
            SystemClock.sleep(10)
        }
    }
}
