package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File

internal class StockInteractionAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        fun ids(): Set<Long> = buildSet {
            resolver.query(media, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
        val before = ids()
        try {
            await { vm.engine != null && !vm.busy }
            val file = File(output, "stock-interaction.png")
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
            val initial = vm.lastResult
            // The native film request captures a pre-edit snapshot; the latest post grade must win.
            test.runOnMainSync {
                vm.selectFilm("kodak_gold_200")
                vm.params.contrast = 80f
                vm.scheduleGrade()
            }
            await { vm.lastResult !== initial && vm.renderTaskState.active == null && vm.renderTaskState.waiting.isEmpty() }
            test.waitForIdleSync()
            SystemClock.sleep(250)
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            check(EditStore.load(context, uri)!!.contrast == 80f) { "A film render saved an outdated post grade" }
            val result = vm.lastResult!!
            val expectedGrade = Grade(1f, 1f, 1f, 1f, .8f, 0f, 1f, 0f, 0f, 0f, 0f,
                FloatArray(3), FloatArray(3), FloatArray(3))
            val expected = ColorPipeline.applyGradeToBitmap(result.rgba16, result.width, result.height, expectedGrade).first
            try {
                val actual = IntArray(result.width * result.height)
                val reference = IntArray(actual.size)
                vm.rendered!!.getPixels(actual, 0, result.width, 0, 0, result.width, result.height)
                expected.getPixels(reference, 0, result.width, 0, 0, result.width, result.height)
                check(actual.contentEquals(reference)) { "A film render overwrote the latest post grade" }
            } finally { expected.recycle() }
            val selectedFilm = vm.params.filmId
            val selectedPaper = vm.params.paperId
            val settled = vm.lastResult
            val completed = vm.renderTaskState.completed
            test.runOnMainSync {
                vm.selectFilm(selectedFilm)
                vm.selectPaper(selectedPaper)
                vm.selectFilm("__missing_material__")
                vm.selectPaper("__missing_material__")
            }
            SystemClock.sleep(350)
            test.waitForIdleSync()
            check(vm.params.filmId == selectedFilm && vm.params.paperId == selectedPaper)
            check(vm.lastResult === settled && vm.renderTaskState.completed == completed) { "No-op material selection rendered again" }
            val alternate = vm.papers.firstOrNull { it.id != selectedPaper && StockCatalog.canPrint(selectedFilm, it.id) }
            if (alternate != null) {
                val renderCount = vm.log.windowed("render:".length).count { it == "render:" }
                test.runOnMainSync { vm.selectPaper(alternate.id) }
                await { vm.log.windowed("render:".length).count { it == "render:" } > renderCount &&
                    vm.renderTaskState.active == null && vm.renderTaskState.waiting.isEmpty() }
                check(vm.errorMessage == null && vm.params.paperId == alternate.id)
            }
            val filmBeforeExport = vm.params.filmId
            val paperBeforeExport = vm.params.paperId
            test.runOnMainSync {
                vm.exportFormat = "TIFF"; vm.exportRes = 512
                vm.exportKeepCapture = false; vm.exportKeepLocation = false
                vm.export()
                vm.selectFilm("kodak_portra_400")
                vm.selectPaper(selectedPaper)
            }
            check(vm.params.filmId == filmBeforeExport && vm.params.paperId == paperBeforeExport) { "Material mutated during export" }
            await { !vm.isExporting && vm.renderTaskState.active == null }
            test.waitForIdleSync()
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            // Starting an export before the coalesced worker's first frame must not lose its final input.
            val beforeInterrupted = vm.lastResult
            test.runOnMainSync {
                vm.params.exposureEv = .25f; vm.scheduleRender()
                vm.params.exposureEv = .75f; vm.scheduleRender()
                vm.params.brightness = 25f; vm.scheduleGrade()
                vm.export()
            }
            await { !vm.isExporting && vm.lastResult !== beforeInterrupted &&
                vm.renderTaskState.active == null && vm.renderTaskState.waiting.isEmpty() }
            test.waitForIdleSync(); SystemClock.sleep(200)
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val resumed = vm.lastResult!!
            val full = vm.engine!!.renderFull("live")
            check(resumed.rgba16.contentEquals(full.rgba16)) { "Export lost the queued capture edit" }
            val resumedGrade = EditorViewModel::class.java.getDeclaredMethod("grade", EditorParams::class.java)
                .apply { isAccessible = true }.invoke(vm, vm.params.copy()) as Grade
            val expectedPixels = ColorPipeline.applyGrade(full.rgba16, full.width, full.height, resumedGrade)
            val actualPixels = IntArray(expectedPixels.size)
            vm.rendered!!.getPixels(actualPixels, 0, full.width, 0, 0, full.width, full.height)
            check(expectedPixels.contentEquals(actualPixels)) { "Export lost the queued post edit" }
            test.runOnMainSync {
                vm.openFrame(FloatArray(600 * 400 * 3) { .18f }, 600, 400)
                vm.cancelRender()
            }
            await { !vm.busy && vm.renderTaskState.active == null }
            return "Latest grade survives film render; no-op/invalid material skips; paper settles; export locks edits and resumes pending capture/post preview; initial-frame cancellation clears busy"
        } finally {
            (ids() - before).forEach { resolver.delete(android.content.ContentUris.withAppendedId(media, it), null, null) }
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Stock interaction audit timed out" }
            SystemClock.sleep(15)
        }
    }
}
