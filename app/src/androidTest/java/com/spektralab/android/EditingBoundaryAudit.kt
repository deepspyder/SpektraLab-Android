package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.CurveChannel
import com.spektralab.android.core.color.ToneCurve
import com.spektralab.android.core.color.ToneCurves
import com.spektralab.android.core.geometry.cropAspectRatio
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.Grade
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import kotlinx.coroutines.Job
import kotlin.math.abs

/** Cross-feature boundaries that ordinary single-control happy paths do not exercise. */
internal class EditingBoundaryAudit(private val test: Instrumentation, private val output: File) {
    fun batchResume(native: Boolean, cancel: Boolean): String = withPhoto("batch-$native-$cancel") { vm, _ ->
        val resolver = test.targetContext.contentResolver
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids(): Set<Long> = buildSet {
            resolver.query(media, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
        val before = ids()
        val oldResult = vm.lastResult
        val oldBitmap = vm.rendered
        try {
            test.runOnMainSync {
                vm.exportFormat = "PNG"
                vm.exportRes = 512
                vm.exportKeepCapture = false
                vm.exportKeepLocation = false
                if (native) {
                    vm.params.exposureEv = .75f
                    vm.scheduleRender()
                }
                vm.params.brightness = 25f
                vm.scheduleGrade()
                vm.batchExport()
                check(vm.isBatchExporting) { "Batch did not start" }
                if (cancel) vm.cancelRender()
            }
            await { !vm.isBatchExporting }
            idle(vm)
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            check(vm.rendered !== oldBitmap) { "Batch completion lost the pending post preview" }
            if (native) check(vm.lastResult !== oldResult) { "Batch completion lost the pending capture preview" }
            val full = vm.engine!!.renderFull("live")
            check(vm.lastResult!!.rgba16.contentEquals(full.rgba16)) { "Resumed preview does not match full native rendering" }
            val grade = EditorViewModel::class.java.getDeclaredMethod("grade", EditorParams::class.java)
                .apply { isAccessible = true }.invoke(vm, vm.params.copy()) as Grade
            val expected = ColorPipeline.applyGrade(full.rgba16, full.width, full.height, grade)
            val actual = IntArray(expected.size)
            vm.rendered!!.getPixels(actual, 0, full.width, 0, 0, full.width, full.height)
            check(actual.contentEquals(expected)) { "Batch resumed an outdated post grade" }
            "Pending capture/post preview resumes after batch completion/cancellation with exact full-render pixels"
        } finally {
            (ids() - before).forEach { resolver.delete(ContentUris.withAppendedId(media, it), null, null) }
        }
    }

    fun cancelPostEdit(curve: Boolean): String = withPhoto(if (curve) "curve-cancel" else "color-cancel") { vm, uri ->
        val initial = vm.params.copy()
        test.runOnMainSync {
            // A previous native request can save while the next post gesture is in progress.
            vm.params.exposureEv = .4f
            vm.scheduleRender()
            if (curve) {
                vm.beginCurveEdit()
                vm.changeCurves(ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .7f)))
            } else {
                vm.beginColorEdit()
                vm.changeColors(vm.params.colorAdjustments.withVibrance(45f))
            }
        }
        idle(vm)
        val duringGesture = EditStore.load(test.targetContext, uri) ?: error("Missing sidecar during gesture")
        check(duringGesture.toneCurves == initial.toneCurves && duringGesture.colorAdjustments == initial.colorAdjustments) {
            "Background save persisted an uncommitted post gesture"
        }
        test.runOnMainSync {
            if (curve) vm.finishCurveEdit(cancel = true) else vm.finishColorEdit(cancel = true)
        }
        idle(vm)
        val saved = EditStore.load(test.targetContext, uri) ?: error("Missing sidecar")
        check(vm.params.toneCurves == initial.toneCurves && vm.params.colorAdjustments == initial.colorAdjustments)
        check(saved.toneCurves == initial.toneCurves && saved.colorAdjustments == initial.colorAdjustments) {
            "Cancelled post gesture survived in the sidecar after an overlapping native save"
        }
        check(saved.exposureEv == .4f) { "Cancellation discarded the independent exposure edit" }
        test.runOnMainSync { vm.undo() }
        idle(vm)
        check(vm.params.exposureEv == initial.exposureEv && vm.params.toneCurves == initial.toneCurves &&
            vm.params.colorAdjustments == initial.colorAdjustments) { "Cancelled gesture leaked into undo history" }
        test.runOnMainSync { vm.redo() }
        idle(vm)
        check(vm.params.exposureEv == .4f && vm.params.toneCurves == initial.toneCurves &&
            vm.params.colorAdjustments == initial.colorAdjustments) { "Cancelled gesture leaked into redo history" }
        test.runOnMainSync { vm.openPhoto(uri) }
        idle(vm)
        check(vm.params.toneCurves == initial.toneCurves && vm.params.colorAdjustments == initial.colorAdjustments)
        test.runOnMainSync {
            if (curve) {
                vm.beginCurveEdit()
                vm.changeCurves(ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .6f)))
                vm.finishCurveEdit()
            } else {
                vm.beginColorEdit()
                vm.changeColors(vm.params.colorAdjustments.withVibrance(20f))
                vm.finishColorEdit()
            }
        }
        idle(vm)
        val committed = EditStore.load(test.targetContext, uri) ?: error("Missing committed edit")
        check(committed.toneCurves == vm.params.toneCurves && committed.colorAdjustments == vm.params.colorAdjustments)
        "Draft absent from background saves, cancel, undo/redo and reopen; released gesture saves normally"
    }

    fun smallCrop(): String = withPhoto("small-crop") { vm, _ ->
        val width = .04f
        val height = width * 4f / 3f / 2.39f
        test.runOnMainSync {
            vm.beginCrop()
            vm.cropAspect = "2.39:1"
            vm.params.cropX = .35f
            vm.params.cropY = .4f
            vm.params.cropW = width
            vm.params.cropH = height
            vm.finishCrop()
        }
        check(abs(vm.params.cropW - width) < 1e-6f && abs(vm.params.cropH - height) < 1e-6f) {
            "Finishing enlarged a valid small selection and changed its locked aspect"
        }
        idle(vm)
        check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
        "Small 2.39:1 selection retains exact normalized bounds on commit"
    }

    fun cropHistory(): String = withPhoto("crop-history") { vm, _ ->
        fun crop(aspect: String, width: Float, height: Float) {
            test.runOnMainSync {
                vm.beginCrop()
                vm.cropAspect = aspect
                vm.params.cropX = .1f
                vm.params.cropY = .1f
                vm.params.cropW = width
                vm.params.cropH = height
                vm.finishCrop()
            }
            idle(vm)
        }
        crop("2.39:1", .8f, .8f * 4f / 3f / 2.39f)
        SystemClock.sleep(400)
        crop("1:1", .6f, .8f)
        test.runOnMainSync { vm.undo() }
        idle(vm)
        check(abs(vm.params.cropW / vm.params.cropH * 4f / 3f - 2.39f) < .001f)
        val undoLock = cropAspectRatio(vm.cropAspect)
        check(undoLock == null || abs(undoLock - 2.39f) < .001f) {
            "Undo restored cinema crop coordinates but left the square aspect lock"
        }
        test.runOnMainSync { vm.redo() }
        idle(vm)
        val redoLock = cropAspectRatio(vm.cropAspect)
        check(redoLock == null || abs(redoLock - 1f) < .001f)
        "Undo/redo keeps aspect-lock UI consistent with restored crop geometry"
    }

    private fun withPhoto(name: String, action: (EditorViewModel, Uri) -> String): String {
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val file = File(output, "editing-boundary-$name.png")
        try {
            await { vm.engine != null && !vm.busy }
            val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(IntArray(512 * 384) { i ->
                0xff000000.toInt() or ((i % 512 / 2) shl 16) or ((i / 512 * 255 / 383) shl 8) or 90
            }, 0, 512, 0, 0, 512, 384)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val uri = Uri.fromFile(file)
            EditStore.save(test.targetContext, uri, EditorParams().apply { grainActive = false; glareActive = false })
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            idle(vm)
            return action(vm, uri)
        } finally {
            test.runOnMainSync { vm.cancelRender(); activity.finish() }
        }
    }

    private fun idle(vm: EditorViewModel) = await {
        vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting &&
            vm.rendered != null && vm.renderTaskState.active == null &&
            vm.renderTaskState.waiting.isEmpty() && listOf("renderJob", "wbJob", "frameRenderJob", "photoLoadJob")
                .none { name ->
                    (EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as? Job)?.isActive == true
                }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Editing boundary audit timed out" }
            SystemClock.sleep(10)
        }
    }
}
