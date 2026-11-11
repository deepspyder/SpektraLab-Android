package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.RawDecoder
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Full-size execution-policy comparison; camera files and generated TIFFs stay outside the APK. */
internal class FullResolutionExportAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val source = File(output, "perf-z6ii.NEF")
        check(source.isFile) { "Push perf-z6ii.NEF into $output first" }
        val results = JSONArray()
        val referenceFile = File(output, "full-export-reference.sha256")
        var reference: String? = referenceFile.takeIf { it.isFile }?.readText()?.trim()
        for (rows in listOf(0, 256, 512)) {
            val entry = JSONObject().put("strip_rows", rows)
            val started = SystemClock.elapsedRealtime()
            val engine = EngineClient.create(test.targetContext)
            val destination = File(output, "full-export-policy-$rows.tiff")
            try {
                val params = EditorParams()
                val decodeStarted = SystemClock.elapsedRealtime()
                RawDecoder.decodeForExport(test.targetContext, Uri.fromFile(source), 0).use { raw ->
                    entry.put("decode_ms", SystemClock.elapsedRealtime() - decodeStarted)
                    entry.put("width", raw.width).put("height", raw.height)
                    val json = JSONObject(params.openJson(raw.width, raw.height, "sRGB", "ProPhoto RGB"))
                        .put("striped", rows > 0).put("strip_rows", rows)
                    val openStarted = SystemClock.elapsedRealtime()
                    engine.openRaw(raw, json.toString())
                    entry.put("open_ms", SystemClock.elapsedRealtime() - openStarted)
                }
                val renderStarted = SystemClock.elapsedRealtime()
                engine.renderForExport().use { image ->
                    entry.put("render_ms", SystemClock.elapsedRealtime() - renderStarted)
                    entry.put("memory_after_render", JSONObject(engine.memoryReport()))
                    entry.put("process_pss_kb", Debug.getPss())
                    engine.closeSession()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val grade = Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
                        FloatArray(3), FloatArray(3), FloatArray(3))
                    val writeStarted = SystemClock.elapsedRealtime()
                    DigestOutputStream(destination.outputStream().buffered(), digest).use {
                        TiffWriter.writeNative(image, grade, null, it)
                    }
                    val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                    entry.put("write_ms", SystemClock.elapsedRealtime() - writeStarted)
                    entry.put("sha256", checksum).put("bytes", destination.length())
                    if (reference == null) reference = checksum
                    entry.put("matches_whole_frame", reference == checksum)
                    check(reference == checksum) { "Execution policy changed the full-size TIFF pixels or tags" }
                }
                entry.put("total_ms", SystemClock.elapsedRealtime() - started)
                entry.put("status", "PASS")
            } finally {
                engine.close()
                destination.delete()
            }
            results.put(entry)
            File(output, "full-export-policies.json").writeText(results.toString(2))
            test.sendStatus(0, android.os.Bundle().apply { putString("stream", "$entry\n") })
        }
        return results.toString()
    }
}
