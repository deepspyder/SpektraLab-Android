package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.content.Context
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.LensProfileStore
import com.spektralab.android.engine.NativeRenderResult
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.StockCatalog
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** No cache hit: decode, correct, upload and render the real camera frame at full size. */
internal class ExportEngineAudit(
    private val test: Instrumentation,
    private val output: File,
    private val input: String,
    private val effects: Boolean,
    private val label: String,
) {
    fun run(): String {
        val source = File(output, input)
        check(source.isFile) { "Missing explicit camera fixture: $input" }
        val report = JSONObject().put("label", label).put("input", input).put("default_effects", effects)
        val start = now()
        val destination = File(output, "engine-export-audit.tiff")
        EngineClient.create(test.targetContext).use { engine ->
            try {
                val params = EditorParams().apply {
                    if (!effects) { grainActive = false; glareActive = false }
                }
                val decodeStart = now()
                RawDecoder.decodeForExport(test.targetContext, Uri.fromFile(source), 0).use { raw ->
                    report.put("decode_ms", now() - decodeStart).put("width", raw.width).put("height", raw.height)
                    LensProfileStore.load(test.targetContext)
                    val profile = LensProfileStore.match(raw.make, raw.lens, raw.focal)
                    val lensStart = now()
                    if (profile != null) raw.correctLens(profile)
                    report.put("lens_ms", now() - lensStart).put("lens_applied", profile != null)
                    val openStart = now()
                    engine.openRaw(raw, params.openJson(raw.width, raw.height, "sRGB", "ProPhoto RGB"))
                    report.put("open_ms", now() - openStart)
                }
                val renderStart = now()
                val once = EngineClient::class.java.methods.firstOrNull { it.name == "renderExportOnce" && it.parameterCount == 0 }
                val rendered = if (once != null) once.invoke(engine) as NativeRenderResult else engine.renderForExport()
                rendered.use { image ->
                    report.put("render_ms", now() - renderStart).put("one_shot", once != null)
                        .put("memory_after_render", JSONObject(engine.memoryReport())).put("pss_after_render_kb", Debug.getPss())
                    engine.closeSession()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val grade = Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
                        FloatArray(3), FloatArray(3), FloatArray(3))
                    val encodeStart = now()
                    DigestOutputStream(destination.outputStream().buffered(), digest).use {
                        TiffWriter.writeNative(image, grade, null, it)
                    }
                    report.put("encode_ms", now() - encodeStart).put("bytes", destination.length())
                        .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                    // The result must remain usable even after closing its GPU session.
                    image.readRows(image.height - 1, 1, ShortArray(image.width * 4))
                }
                report.put("total_ms", now() - start).put("status", "PASS")
                File(output, "engine-export-$label.json").writeText(report.toString(2))
                return report.toString()
            } finally {
                destination.delete()
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e6

    companion object {
        fun parity(context: Context): String {
            val width = 384
            val height = 257
            val input = FloatArray(width * height * 3) { i ->
                when (i % 3) {
                    0 -> (i / 3 % width + 1f) / width
                    1 -> (i / 3 / width + 1f) / height
                    else -> .18f
                }
            }
            val (films, papers) = StockCatalog.load(context)
            val cases = films.map { film ->
                EditorParams().apply {
                    filmId = film.id
                    paperId = film.targetPrint?.takeIf { StockCatalog.canPrint(film.id, it) }
                        ?: papers.first { StockCatalog.canPrint(film.id, it.id) }.id
                    grainActive = false
                    glareActive = false
                }
            }.toMutableList()
            papers.filter { StockCatalog.canPrint("kodak_portra_400", it.id) }.forEach { paper ->
                cases += EditorParams().apply { paperId = paper.id; grainActive = false; glareActive = false }
            }
            cases += EditorParams().apply {
                grainActive = false; glareActive = false
                cropX = .1f; cropY = .15f; cropW = .7f; cropH = .65f; quarterTurns = 1; flipH = true
            }
            cases += EditorParams().apply {
                grainActive = false; glareActive = false
                overscanActive = true; dateImprintActive = true; dateImprintText = "'26 11 12"
            }
            cases += EditorParams().apply {
                autoExposure = false; grainActive = false; glareActive = false
            }
            cases += EditorParams().apply {
                autoExposure = false; grainActive = false; glareActive = false
                cropX = .1f; cropY = .15f; cropW = .7f; cropH = .65f
            }
            EngineClient.create(context).use { engine ->
                for ((index, params) in cases.withIndex()) {
                    val json = params.openJson(width, height)
                    engine.open(input, width, height, 3, json)
                    val expected = engine.render("full")
                    engine.open(input, width, height, 3, json)
                    engine.renderExportOnce().use { actual ->
                        check(actual.width == expected.width && actual.height == expected.height)
                        val pixels = ShortArray(actual.width * actual.height * 4)
                        actual.readRows(0, actual.height, pixels)
                        check(pixels.contentEquals(expected.rgba16)) { "One-shot changed U16 pixels: $index / " + params.filmId }
                        val memory = JSONObject(engine.memoryReport()).getJSONObject("persistent")
                        check(memory.getJSONArray("sessions").length() == 0) { "Export session remained live" }
                        // The output has its own storage after session destruction.
                        actual.readRows(actual.height - 1, 1, ShortArray(actual.width * 4))
                    }
                    engine.open(input, width, height, 3, json)
                    engine.render("full")
                    check(JSONObject(engine.memoryReport()).getJSONObject("persistent").getInt("negatives") == 1) {
                        "Export execution scope leaked into interactive rendering"
                    }
                }
                val grain = EditorParams().apply { glareActive = false }
                // Fresh pipelines start with the same RNG state; do not invent an unsupported wire key.
                val freshSessionParams = grain.openJson(width, height)
                engine.open(input, width, height, 3, freshSessionParams)
                val expected = engine.render("full")
                engine.open(input, width, height, 3, freshSessionParams)
                engine.renderExportOnce().use { actual ->
                    val pixels = ShortArray(expected.rgba16.size)
                    actual.readRows(0, actual.height, pixels)
                    check(pixels.contentEquals(expected.rgba16)) { "One-shot changed fresh-session grain" }
                }
                // A consumed session may also arrive with a cached negative.
                // Releasing its tier owners before print must preserve that
                // negative and its already-realised grain through readback.
                engine.open(input, width, height, 3, freshSessionParams)
                val cached = engine.render("full")
                engine.renderExportOnce().use { actual ->
                    val pixels = ShortArray(cached.rgba16.size)
                    actual.readRows(0, actual.height, pixels)
                    check(pixels.contentEquals(cached.rgba16)) { "Source release invalidated cached negative" }
                    check(JSONObject(engine.memoryReport()).getJSONObject("persistent")
                        .getJSONArray("sessions").length() == 0)
                }
            }
            return "All film families and compatible papers, geometry, overscan/date, fresh-session grain and cached-negative consumption match full U16 pixels; output survives closed session and later editor still caches its negative; cases=" + (cases.size + 2)
        }

        fun cancellation(context: Context): String {
            val width = 2048
            val height = 1536
            EngineClient.create(context).use { engine ->
                val params = EditorParams()
                engine.open(FloatArray(width * height * 3) { .18f }, width, height, 3, params.openJson(width, height))
                val previousId = engine.progressSnapshot().progressId
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val result = worker.submit<Throwable?> {
                        runCatching { engine.renderExportOnce().close() }.exceptionOrNull()
                    }
                    val start = SystemClock.elapsedRealtime()
                    var requested = false
                    while (!result.isDone && SystemClock.elapsedRealtime() - start < 10000) {
                        val progress = runCatching { engine.progressSnapshot() }.getOrNull()
                        if (progress != null && progress.progressId != previousId && !progress.done) {
                            engine.cancel(progress.progressId)
                            requested = true
                            break
                        }
                        SystemClock.sleep(2)
                    }
                    check(requested) { "Export finished before the test could exercise cancellation" }
                    val error = result.get(30, TimeUnit.SECONDS)
                    check(error?.message?.contains("cancel", ignoreCase = true) == true) {
                        "One-shot cancellation was not acknowledged: $error"
                    }
                    check(JSONObject(engine.memoryReport()).getJSONObject("persistent").getJSONArray("sessions").length() == 0)
                    engine.open(FloatArray(320 * 240 * 3) { .18f }, 320, 240, 3,
                        params.openJson(320, 240))
                    engine.render("full")
                    check(JSONObject(engine.memoryReport()).getJSONObject("persistent").getInt("negatives") == 1)
                    return "In-flight one-shot render cancels, closes its session and restores ordinary negative caching on the next open"
                } finally {
                    worker.shutdownNow()
                }
            }
        }
    }
}
