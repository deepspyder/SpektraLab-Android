package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.SpkException
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Optional camera fixtures are pushed to externalFilesDir/nef-regression, never bundled. */
internal class NefImportAudit(private val test: Instrumentation, private val output: File) {
    private fun await(label: String, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Timeout $label" }
            Thread.sleep(50)
        }
    }

    fun run(): String {
        val context = test.targetContext
        val directory = File(context.getExternalFilesDir(null), "nef-regression")
        val sources = directory.listFiles { file -> file.extension.equals("nef", ignoreCase = true) }
            .orEmpty().sortedBy { it.name }
        check(sources.size == 6) { "Push the six documented Nikon Z 6 II camera fixtures first" }
        val results = JSONArray()
        val failures = mutableListOf<String>()
        for (source in sources) {
            val row = JSONObject().put("file", source.name)
            try {
                val uri = Uri.fromFile(source)
                check(RawDecoder.isRaw(context, uri, source.name))
                val preview = RawDecoder.decode(context, uri, halfSize = true)
                val headerMatrix = RawDecoder.readCameraMatrix(context, uri)
                check(headerMatrix != null && headerMatrix.contentEquals(preview.camXyz)) {
                    "Header-only camera matrix differs from the processed Nikon metadata"
                }
                check(preview.width > 2000 && preview.height > 1000)
                check(preview.make.contains("NIKON", ignoreCase = true))
                check(preview.linearProPhoto.size == preview.width * preview.height * 3)
                check(preview.linearProPhoto.all { it.isFinite() })
                row.put("camera", preview.model).put("preview", "${preview.width}x${preview.height}")
                RawDecoder.decodeForExport(context, uri, 320).use { image ->
                    check(maxOf(image.width, image.height) <= 320)
                    check(image.copySmallLinearRgb().all { it.isFinite() })
                    row.put("export", "${image.width}x${image.height}")
                }
                row.put("status", "PASS")
            } catch (error: Exception) {
                row.put("status", "FAIL").put("error", error.toString())
                failures += source.name
            }
            results.put(row)
            File(output, "nef-camera-results.json").writeText(results.toString(2))
            test.sendStatus(0, android.os.Bundle().apply { putString("stream", "$row\n") })
        }
        check(failures.isEmpty()) { "NEF decode failed: ${failures.joinToString()}" }

        // A recoverable decoder warning must not turn into acceptance of an incomplete transfer.
        val truncated = File(directory, "truncated.raw")
        try {
            sources[1].inputStream().use { input ->
                truncated.outputStream().use { output ->
                    val bytes = ByteArray(1024 * 1024)
                    val read = input.read(bytes)
                    output.write(bytes, 0, read)
                }
            }
            check(runCatching { RawDecoder.decode(context, Uri.fromFile(truncated), true) }
                .exceptionOrNull() is SpkException) { "Truncated NEF was accepted" }
            check(runCatching { RawDecoder.decodeForExport(context, Uri.fromFile(truncated), 320) }
                .exceptionOrNull() is SpkException) { "Truncated NEF export was accepted" }
        } finally {
            truncated.delete()
        }

        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val auto = vm.smartAutoEnabled
        try {
            await("engine") { !vm.busy && vm.engine != null }
            for (source in sources.take(2)) {
                val uri = Uri.fromFile(source)
                EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
                test.runOnMainSync { vm.smartAutoEnabled = false; vm.openPhotos(listOf(uri)) }
                await("camera import") { !vm.busy }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                check(vm.currentIsRaw && vm.hasPhoto && vm.rendered != null)
                check(vm.currentUri == uri)
                test.waitForIdleSync()
                test.uiAutomation.takeScreenshot()?.let { image ->
                    File(output, "nef-${source.nameWithoutExtension}.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
            }
        } finally {
            test.runOnMainSync { vm.smartAutoEnabled = auto; activity.finish() }
        }
        check(context.cacheDir.listFiles().orEmpty().none {
            it.name.startsWith("raw_input_") || it.name.startsWith("raw_export_")
        }) { "RAW staging file leaked" }
        return "Six Nikon Z 6 II 12/14-bit NEFs decode for preview/export; lossless/lossy camera imports render; truncated files rejected and temporary files removed"
    }
}
