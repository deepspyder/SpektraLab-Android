package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

internal class CurveAudit(private val test: Instrumentation, private val output: File) {
    fun capturePhoto(): String {
        releasePointers()
        val context = test.targetContext
        val source = File(output, "perf-z6ii.NEF")
        check(source.isFile)
        val uri = Uri.fromFile(source)
        val previous = EditStore.load(context, uri)
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            await { vm.engine != null && !vm.busy }
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.hasPhoto && !vm.busy && vm.rendered != null }
            click("继续显影"); click("调色"); click("编辑曲线")
            await { find("RGB曲线图") != null }
            val base = vm.lastResult
            val original = vm.rendered
            test.runOnMainSync {
                vm.beginCurveEdit()
                vm.changeCurves(ToneCurves.IDENTITY.with(CurveChannel.RGB,
                    ToneCurve.IDENTITY.add(.25f, .2f).add(.75f, .8f)))
                vm.finishCurveEdit()
            }
            await { vm.rendered !== original }
            test.waitForIdleSync(); SystemClock.sleep(250)
            fun shot(name: String) {
                val bitmap = test.uiAutomation.takeScreenshot()!!
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            click("精细调整输入 / 输出")
            click("选择下一个控制点")
            shot("curves-real-precision.png")
            click("收起精细调整")
            shot("curves-real-photo.png")
            click("展开照片预览")
            shot("curves-real-expanded.png")
            check(vm.lastResult === base) { "Layout inspection redeveloped the film" }
            return "Real NEF curve workspace, precision and expanded photo screenshots captured without native re-render"
        } finally {
            releasePointers()
            previous?.let { EditStore.save(context, uri, it) }
            test.runOnMainSync { activity.finish() }
        }
    }

    fun run(): String {
        mathAndNative()
        editor()
        return "Five-channel math/native/output parity; explicit curve workspace; blank swipe unchanged, tap-add/point-drag/cancel; history/copy/save/reset and JPEG/PNG/TIFF exports"
    }

    private fun grade(curves: ToneCurves, adjusted: Boolean) =
        Grade(
            if (adjusted) 1.04f else 1f,
            if (adjusted) .98f else 1f,
            1f,
            if (adjusted) 1.2f else 1f,
            if (adjusted) .1f else 0f,
            if (adjusted) .07f else 0f,
            if (adjusted) 1.1f else 1f,
            if (adjusted) -.03f else 0f,
            if (adjusted) .05f else 0f,
            if (adjusted) .01f else 0f,
            if (adjusted) -.01f else 0f,
            if (adjusted) floatArrayOf(.01f, -.01f, 0f) else FloatArray(3),
            if (adjusted) floatArrayOf(0f, .01f, 0f) else FloatArray(3),
            FloatArray(3),
            if (adjusted) .6f else 0f,
            .5f,
            .7f,
            curves,
        )

    private fun mathAndNative() {
        val neutral = EditorParams().toJson()
        check(!neutral.has("tone_curves") && EditorParams.fromJson(neutral).toneCurves.isIdentity)
        val future = org.json.JSONObject().put("version", 99).put("keep", "unrecognized")
        val savedFuture = EditorParams.fromJson(org.json.JSONObject().put("tone_curves", future))
        check(
            savedFuture.unreadableToneCurves != null &&
                savedFuture.copy().toJson().getJSONObject("tone_curves").getString("keep") ==
                    "unrecognized"
        )
        val identity = ToneCurve.IDENTITY
        for (i in 0..65535) check(identity.evaluate(i / 65535f) == i / 65535f)
        val linear = ToneCurve.of(listOf(CurvePoint(0f, .1f), CurvePoint(1f, .9f)))
        for (i in 0..1000) check(abs(linear.evaluate(i / 1000f) - (.1f + .8f * i / 1000f)) < 2e-7f)
        val shaped = identity.add(.2f, .1f).add(.5f, .7f).add(.8f, .8f)
        var previous = 0f
        for (i in 0..4096) {
            val v = shaped.evaluate(i / 4096f)
            check(v in 0f..1f && v >= previous - 1e-6f)
            previous = v
        }
        check(shaped.move(2, 1f, .4f).points[2].x < shaped.points[3].x)
        check(shaped.remove(0) == shaped && shaped.remove(shaped.points.lastIndex) == shaped)
        check(
            runCatching { ToneCurve.of(listOf(CurvePoint(0f, 0f), CurvePoint(0f, 1f))) }.isFailure
        )
        check(runCatching { identity.add(Float.NaN, 0f) }.isFailure)
        var curves =
            ToneCurves.IDENTITY.with(CurveChannel.LUMA, shaped)
                .with(CurveChannel.RGB, linear)
                .with(CurveChannel.BLUE, identity.add(.5f, .65f))
        for (channel in CurveChannel.entries) {
            val value = ToneCurves.IDENTITY.with(channel, linear)
            check(!value.isIdentity && ToneCurves.fromJson(value.toJson()) == value)
            val params =
                EditorParams().apply {
                    toneCurves = value
                    cropX = .1f
                    cropW = .8f
                }
            check(params.copy().toneCurves == value)
            val target =
                EditorParams().apply {
                    cropX = .2f
                    cropW = .6f
                }
            target.copySettingsFrom(params)
            check(target.toneCurves == value && target.cropX == .2f)
        }
        val w = 257
        val h = 39
        val rgba = ShortArray(w * h * 4) { i -> (i * 373 + 17).toShort() }
        for (adjusted in listOf(false, true)) {
            val g = grade(curves, adjusted)
            val reference = FloatArray(3)
            val strip = FloatArray(w * 32 * 3)
            var y = 0
            var maxError = 0f
            while (y < h) {
                val rows = minOf(32, h - y)
                g.nativeRows(rgba, y * w * 4, w, h, y, rows, strip)
                for (i in 0 until w * rows) {
                    val at = ((y * w) + i) * 4
                    g.gradeAt(
                        rgba[at],
                        rgba[at + 1],
                        rgba[at + 2],
                        reference,
                        i % w,
                        y + i / w,
                        w,
                        h,
                    )
                    for (c in 0..2) maxError = maxOf(maxError, abs(reference[c] - strip[i * 3 + c]))
                }
                y += rows
            }
            check(maxError < 2e-6f) { "Native/reference curve difference $maxError" }
            val bytes =
                ByteArrayOutputStream()
                    .also { TiffWriter.writeRgba16(rgba, w, h, g, null, it) }
                    .toByteArray()
            check(bytes.size > w * h * 6)
            val tiff = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val ifd = tiff.getInt(4)
            val n = tiff.getShort(ifd).toInt() and 65535
            val offset =
                (0 until n)
                    .map { ifd + 2 + it * 12 }
                    .first { (tiff.getShort(it).toInt() and 65535) == 273 }
                    .let { tiff.getInt(it + 8) }
            for (i in 0 until w * h) {
                g.gradeAt(
                    rgba[i * 4],
                    rgba[i * 4 + 1],
                    rgba[i * 4 + 2],
                    reference,
                    i % w,
                    i / w,
                    w,
                    h,
                )
                for (c in 0..2) check(
                    abs(
                        (tiff.getShort(offset + (i * 3 + c) * 2).toInt() and 65535) -
                            (reference[c] * 65535f + .5f).toInt().coerceIn(0, 65535)
                    ) <= 1
                )
            }
            for (space in listOf("sRGB", "Display P3")) {
                val old = ColorPipeline.applyGradeAndHistogram(rgba, w, h, g, space)
                val (bitmap, hist) = ColorPipeline.applyGradeToBitmap(rgba, w, h, g, space)
                try {
                    val actual = IntArray(w * h)
                    bitmap.getPixels(actual, 0, w, 0, 0, w, h)
                    check(
                        actual.indices.all { i ->
                            (0..2).all { c ->
                                abs(
                                    ((actual[i] shr (c * 8)) and 255) -
                                        ((old.argb[i] shr (c * 8)) and 255)
                                ) <= 1
                            }
                        }
                    )
                    check(hist.r.sum() == w * h && hist.g.sum() == w * h && hist.b.sum() == w * h)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }

    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label)
            return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let {
            return it
        }
        return null
    }

    private fun click(label: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
        repeat(12) {
            if (find(label) != null) return@repeat
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (
                    node.isScrollable &&
                        node.performAction(direction)
                )
                    return true
                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                return false
            }
            if (!scroll(root())) return@repeat
            SystemClock.sleep(100)
        }
        }
        await { find(label) != null }
        var n = find(label)!!
        while (!n.isClickable) n = n.parent ?: error(label)
        check(n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync()
        SystemClock.sleep(300)
    }

    private fun editor() {
        releasePointers()
        val context = test.targetContext
        val activity =
            test.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val file = File(output, "curve-audit-source.png")
        val resolver = context.contentResolver
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids(): Set<Long> = buildSet {
            resolver.query(media, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
        val before = ids()
        try {
            await { vm.engine != null && !vm.busy }
            val fixture = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            for (y in 0 until 384) for (x in 0 until 512) fixture.setPixel(
                x,
                y,
                0xff000000.toInt() or ((x / 2) shl 16) or ((y * 255 / 383) shl 8) or 90,
            )
            file.outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fixture.recycle()
            val uri = Uri.fromFile(file)
            EditStore.save(
                context,
                uri,
                EditorParams().apply {
                    grainActive = false
                    glareActive = false
                },
            )
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.hasPhoto && !vm.busy && vm.rendered != null }
            click("继续显影")
            click("调色")
            check(find("RGB曲线图") == null) { "Curve plot intercepts the ordinary grade panel" }
            click("编辑曲线")
            await { find("RGB曲线图") != null }
            var rect = Rect().also { find("RGB曲线图")!!.getBoundsInScreen(it) }
            val result = vm.lastResult
            val original = vm.rendered
            var start = SystemClock.uptimeMillis()
            fun pointer(action: Int, x: Float, y: Float) {
                if (action == MotionEvent.ACTION_DOWN) start = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {
                    check(test.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
            val photo = Rect().also { find("照片预览")!!.getBoundsInScreen(it) }
            val screen = Rect().also { root()!!.getBoundsInScreen(it) }
            val footer = Rect().also { find("曲线复位选项")!!.getBoundsInScreen(it) }
            check(footer.bottom <= screen.bottom - 16f * context.resources.displayMetrics.density) {
                "Curve actions overlap the system navigation area: " + footer
            }
            if (screen.width() < screen.height() && context.resources.configuration.screenWidthDp < 840) {
                check(photo.height() > screen.height() * .40f && photo.width() > screen.width() * .88f) {
                    "Curve preview is too small: photo=" + photo + " screen=" + screen
                }
            } else check(photo.width() > screen.width() * .50f) { "Wide curve layout gives too little room to the photograph" }
            pointer(MotionEvent.ACTION_DOWN, photo.exactCenterX(), photo.exactCenterY())
            test.waitForIdleSync()
            await { find("原图 · 松手恢复") != null }
            pointer(MotionEvent.ACTION_UP, photo.exactCenterX(), photo.exactCenterY())
            test.waitForIdleSync()
            await { find("按住查看原图") != null }
            check(vm.lastResult === result)
            // A blank swipe is navigation, never a curve edit or history operation.
            pointer(MotionEvent.ACTION_DOWN, rect.left + rect.width() * .12f, rect.top + rect.height() * .15f)
            pointer(MotionEvent.ACTION_MOVE, rect.left + rect.width() * .12f, rect.top + rect.height() * .35f)
            pointer(MotionEvent.ACTION_UP, rect.left + rect.width() * .12f, rect.top + rect.height() * .35f)
            test.waitForIdleSync()
            check(vm.params.toneCurves.isDefault && EditStore.load(context, uri)!!.toneCurves.isDefault)
            find("RGB曲线图")!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            test.waitForIdleSync()
            SystemClock.sleep(200)
            rect = Rect().also { find("RGB曲线图")!!.getBoundsInScreen(it) }
            val inset = 18f * context.resources.displayMetrics.density
            val anchorX = rect.left + inset + (rect.width() - 2 * inset) * .25f
            val anchorY = rect.top + inset + (rect.height() - 2 * inset) * .75f
            pointer(MotionEvent.ACTION_DOWN, anchorX, anchorY)
            pointer(MotionEvent.ACTION_MOVE, anchorX, anchorY - rect.height() * .2f)
            pointer(MotionEvent.ACTION_UP, anchorX, anchorY - rect.height() * .2f)
            await { !vm.params.toneCurves.isIdentity }
            check(vm.params.toneCurves[CurveChannel.RGB].points.size == 3 && vm.lastResult === result) {
                "A tonal guide must support a single drag without redeveloping the film"
            }
            click("撤销曲线编辑")
            await { vm.params.toneCurves.isDefault }
            // A tap and a drag are separate edits; scaled viewports need not map the tap to exact x=y.
            pointer(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
            pointer(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
            test.waitForIdleSync()
            check(vm.params.toneCurves[CurveChannel.RGB].points.size == 3)
            val afterTap = vm.params.toneCurves
            pointer(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
            pointer(MotionEvent.ACTION_MOVE, rect.exactCenterX(), rect.top + rect.height() * .3f)
            pointer(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.top + rect.height() * .3f)
            await { !vm.params.toneCurves.isIdentity && vm.rendered !== original }
            check(vm.lastResult === result) { "Curve caused a film render" }
            val edited = vm.params.toneCurves
            check(EditStore.load(context, uri)!!.toneCurves == edited)
            // Actual pointer cancellation must restore the starting curve and sidecar.
            pointer(
                MotionEvent.ACTION_DOWN,
                rect.exactCenterX(),
                rect.top + rect.height() * .3f,
            )
            pointer(
                MotionEvent.ACTION_MOVE,
                rect.exactCenterX(),
                rect.top + rect.height() * .5f,
            )
            pointer(
                MotionEvent.ACTION_CANCEL,
                rect.exactCenterX(),
                rect.top + rect.height() * .5f,
            )
            test.waitForIdleSync()
            SystemClock.sleep(200)
            check(vm.params.toneCurves == edited) {
                "Cancel mismatch: expected=${edited.toJson()} actual=${vm.params.toneCurves.toJson()}"
            }
            test.runOnMainSync { vm.undo() }
            await { vm.params.toneCurves == afterTap && !vm.busy }
            check(vm.lastResult === result) { "Curve undo redeveloped the film" }
            test.runOnMainSync { vm.redo() }
            await { vm.params.toneCurves == edited && !vm.busy }
            check(vm.lastResult === result) { "Curve redo redeveloped the film" }
            test.runOnMainSync {
                vm.beginCurveEdit()
                vm.changeCurves(ToneCurves.IDENTITY)
                vm.finishCurveEdit(cancel = true)
            }
            check(vm.params.toneCurves == edited)
            val previewBeforePrecision = Rect().also { find("照片预览")!!.getBoundsInScreen(it) }
            click("精细调整输入 / 输出")
            check(find("曲线输出") != null && find("RGB曲线图") == null)
            val previewInPrecision = Rect().also { find("照片预览")!!.getBoundsInScreen(it) }
            check(previewInPrecision == previewBeforePrecision) { "Precision controls shrank the photo preview" }
            fun slider(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString() == "曲线输出" && node.rangeInfo != null) return node
                for (index in 0 until node.childCount) slider(node.getChild(index))?.let { return it }
                return null
            }
            val precisionSlider = slider(root()) ?: error("Curve output slider is not accessible")
            val value = (precisionSlider.rangeInfo.current + .04f).coerceIn(0f, 1f)
            check(precisionSlider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
            await { vm.params.toneCurves != edited }
            check(vm.lastResult === result) { "Precision adjustment redeveloped the film" }
            click("撤销曲线编辑")
            await { vm.params.toneCurves == edited }
            val precisionShot = test.uiAutomation.takeScreenshot()!!
            File(output, "curves-precision.png").outputStream().use { precisionShot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            precisionShot.recycle()
            click("收起精细调整")
            click("展开照片预览")
            check(find("RGB曲线图") == null)
            val expandedPhoto = Rect().also { find("照片预览")!!.getBoundsInScreen(it) }
            check(expandedPhoto.width() > previewBeforePrecision.width() || expandedPhoto.height() > previewBeforePrecision.height())
            check(vm.params.toneCurves == edited && vm.lastResult === result)
            click("返回曲线调整")
            await { find("RGB曲线图") != null }
            check(vm.params.toneCurves == edited)
            for ((label, plot) in
                listOf(
                    "明度" to "明度曲线图",
                    "红" to "红曲线图",
                    "绿" to "绿曲线图",
                    "蓝" to "蓝曲线图",
                    "RGB" to "RGB曲线图",
                )) {
                click(label)
                await { find(plot) != null }
                if (label == "蓝") {
                    click("展开照片预览"); click("返回曲线调整")
                    await { find("蓝曲线图") != null }
                }
            }
            for (format in listOf("PNG", "JPEG", "TIFF")) {
                val seen = ids()
                test.runOnMainSync {
                    vm.exportFormat = format
                    vm.exportColorSpace = "sRGB"
                    vm.exportRes = 512
                    vm.exportKeepCapture = false
                    vm.exportKeepLocation = false
                    vm.export()
                }
                await { !vm.isExporting && vm.renderTaskState.active == null }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                val added = ids() - seen
                check(added.size == 1)
                val saved = android.content.ContentUris.withAppendedId(media, added.single())
                val bytes = resolver.openInputStream(saved)!!.use { it.readBytes() }
                check(bytes.isNotEmpty())
                if (format != "TIFF")
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        check(bmp.width == 512 && bmp.height == 384)
                        bmp.recycle()
                    } ?: error("Invalid output")
            }
            test.waitForIdleSync()
            SystemClock.sleep(500)
            find("RGB曲线图")!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            test.waitForIdleSync()
            SystemClock.sleep(250)
            val shot = test.uiAutomation.takeScreenshot()!!
            File(output, "curves-editor.png").outputStream().use {
                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            shot.recycle()
            click("曲线复位选项")
            click("复位此通道")
            await { vm.params.toneCurves.isIdentity }
            test.runOnMainSync {
                vm.params.unreadableToneCurves = "{\"version\":99,\"keep\":true}"
                vm.export()
            }
            check(!vm.isExporting && vm.errorMessage != null)
            test.runOnMainSync {
                vm.dismissError()
                vm.params.unreadableToneCurves = null
            }
            click("关闭曲线")
            await { find("编辑曲线") != null }
            check(find("RGB曲线图") == null && vm.params.toneCurves.isDefault)
        } finally {
            releasePointers()
            (ids() - before).forEach {
                resolver.delete(android.content.ContentUris.withAppendedId(media, it), null, null)
            }
            test.runOnMainSync { activity.finish() }
        }
    }

    /** Failed gesture audits must not leave Android's injected pointer pressed for the next run. */
    private fun releasePointers() {
        val time = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        cancel.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { test.uiAutomation.injectInputEvent(cancel, true) } finally { cancel.recycle() }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Curve audit timed out" }
            SystemClock.sleep(30)
        }
    }
}
