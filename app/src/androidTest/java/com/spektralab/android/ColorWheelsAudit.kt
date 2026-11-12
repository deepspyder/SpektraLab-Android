package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.Grade
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File

internal class ColorWheelsAudit(private val test: Instrumentation, private val output: File) {
    fun run(layoutOnly: Boolean = false): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val resolver = context.contentResolver
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids(): Set<Long> = buildSet {
            resolver.query(media, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
        val before = ids()
        val file = File(output, "wheels-source.png")
        val second = File(output, "wheels-second.png")
        try {
            await { vm.engine != null && !vm.busy }
            val fixture = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            for (y in 0 until 384) for (x in 0 until 512) fixture.setPixel(x, y,
                0xff000000.toInt() or ((x / 2) shl 16) or ((y * 255 / 383) shl 8) or 90)
            file.outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fixture.recycle()
            val uri = Uri.fromFile(file)
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            idle(vm)
            click("继续显影"); click("调色")
            val frame = vm.lastResult
            val bitmap = vm.rendered
            click("色轮")
            reveal("色轮")
            capture("wheels-context.png")
            reveal("色轮：整体")
            capture("wheels-selectors.png")
            reveal("色彩圆盘")
            check(vm.lastResult === frame && vm.rendered === bitmap) { "Opening wheels rendered a new frame" }
            capture("wheels-neutral.png")
            if (layoutOnly) {
                for (name in listOf("色轮：阴影", "色轮：中间调", "色轮：高光", "色轮：整体")) {
                    click(name)
                    check(reveal("分区明度", range = true).isEnabled)
                }
                reveal("还原全部色轮")
                capture("wheels-layout.png")
                return "Wheel controls reachable with active photo; selectors, all inputs and resets visible; opening/selecting does not redevelop"
            }
            val start = Rect().also { reveal("色彩圆盘").getBoundsInScreen(it) }
            pointer(start, cancel = false)
            idle(vm)
            val first = vm.params.colorWheels
            check(!first.isNeutral && vm.rendered !== bitmap && vm.lastResult === frame)
            check(EditStore.load(context, uri)!!.colorWheels == first)
            pointer(Rect().also { reveal("色彩圆盘").getBoundsInScreen(it) }, cancel = true)
            idle(vm)
            check(vm.params.colorWheels == first && EditStore.load(context, uri)!!.colorWheels == first) {
                "Cancel changed wheels: first=${first.toJson()} current=${vm.params.colorWheels.toJson()} saved=${EditStore.load(context, uri)!!.colorWheels.toJson()}"
            }
            pointer(Rect().also { reveal("着色强度", true).getBoundsInScreen(it) }, cancel = true)
            idle(vm)
            check(vm.params.colorWheels == first)
            test.runOnMainSync { vm.undo() }
            idle(vm)
            check(vm.params.colorWheels.isNeutral && vm.lastResult === frame)
            test.runOnMainSync { vm.redo() }
            idle(vm)
            check(vm.params.colorWheels == first && vm.lastResult === frame)
            for (zone in WheelZone.entries) {
                click(listOf("色轮：整体", "色轮：阴影", "色轮：中间调", "色轮：高光")[zone.ordinal])
                for ((label, value) in listOf("色轮色相" to 180f, "着色强度" to 20f, "分区明度" to -12f)) {
                    val control = reveal(label, true)
                    check(control.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                        putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
                    }))
                    test.runOnMainSync { vm.finishWheelEdit() }
                    idle(vm)
                    val adjustment = vm.params.colorWheels[zone]
                    check(when (label) { "色轮色相" -> adjustment.hue; "着色强度" -> adjustment.strength; else -> adjustment.luminance } == value)
                    check(vm.lastResult === frame && EditStore.load(context, uri)!!.colorWheels == vm.params.colorWheels)
                }
            }
            test.runOnMainSync { vm.beginWheelEdit(); vm.changeWheels(first); vm.finishWheelEdit() }
            idle(vm)
            click("色轮：阴影")
            // Hue-only selection is saved without changing pixels at zero strength.
            val hue = reveal("色轮色相", true)
            check(hue.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 215f)
            }))
            test.runOnMainSync { vm.finishWheelEdit() }
            idle(vm)
            check(vm.params.colorWheels.shadows.hue == 215f && vm.params.colorWheels.shadows.isNeutral)
            check(EditStore.load(context, uri)!!.colorWheels.shadows.hue == 215f)
            val slider = reveal("分区明度", true)
            check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -25f)
            }))
            test.runOnMainSync { vm.finishWheelEdit() }
            idle(vm)
            check(vm.params.colorWheels.shadows.luminance == -25f)
            check(vm.params.colorWheels.master == first.master)
            click("还原当前分区")
            idle(vm)
            check(vm.params.colorWheels == first)
            click("色轮：整体")
            reveal("色彩圆盘")
            capture("wheels-edited.png")
            reveal("分区明度", range = true)
            capture("wheels-controls.png")

            // Pending gestures stay out of both save and parameter-copy snapshots.
            test.runOnMainSync {
                vm.beginWheelEdit()
                vm.changeWheels(first.with(WheelZone.HIGHLIGHTS, WheelAdjustment(80f, 30f, 8f)))
                vm.params.brightness = 8f
                vm.scheduleGrade()
            }
            check(EditStore.load(context, uri)!!.colorWheels == first)
            test.runOnMainSync { vm.finishWheelEdit(cancel = true); vm.params.brightness = 0f; vm.scheduleGrade() }
            idle(vm)
            check(vm.params.colorWheels == first)
            var finalDrag = first
            val dragStart = SystemClock.elapsedRealtime()
            test.runOnMainSync { vm.beginWheelEdit() }
            repeat(24) { step ->
                finalDrag = first.with(WheelZone.MASTER, WheelAdjustment(step * 12f, 30f, step - 12f))
                test.runOnMainSync { vm.changeWheels(finalDrag) }
                SystemClock.sleep(12)
            }
            test.runOnMainSync { vm.finishWheelEdit() }
            idle(vm)
            check(vm.lastResult === frame && vm.params.colorWheels == finalDrag)
            val dragImage = vm.rendered!!
            val dragPixels = IntArray(dragImage.width * dragImage.height)
            dragImage.getPixels(dragPixels, 0, dragImage.width, 0, 0, dragImage.width, dragImage.height)
            check(dragPixels.contentEquals(ColorPipeline.applyGrade(frame!!.rgba16, frame.width, frame.height, neutralGrade(finalDrag)))) {
                "Continuous wheels edits did not publish the latest exact frame"
            }
            File(output, "wheels-drag-ms.txt").writeText((SystemClock.elapsedRealtime() - dragStart).toString())
            test.runOnMainSync {
                vm.beginWheelEdit(); vm.changeWheels(first); vm.finishWheelEdit()
            }
            idle(vm)

            fun pixels(saved: Uri): IntArray {
                val decoded = resolver.openInputStream(saved)!!.use { BitmapFactory.decodeStream(it) } ?: error("Invalid PNG")
                check(decoded.width == 512 && decoded.height == 384)
                return IntArray(decoded.width * decoded.height).also {
                    decoded.getPixels(it, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                    decoded.recycle()
                }
            }
            fun export(format: String): Uri {
                val seen = ids()
                test.runOnMainSync {
                    vm.exportFormat = format; vm.exportColorSpace = "sRGB"; vm.exportRes = 512
                    vm.exportKeepCapture = false; vm.exportKeepLocation = false; vm.export()
                }
                idle(vm)
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                return ContentUris.withAppendedId(media, (ids() - seen).single())
            }
            val native = vm.lastResult!!
            val expected = ColorPipeline.applyGrade(native.rgba16, native.width, native.height, neutralGrade(first))
            test.runOnMainSync { vm.beginWheelEdit(); vm.changeWheels(ColorWheels.NEUTRAL) }
            val firstPng = pixels(export("PNG"))
            check(vm.params.colorWheels == first) { "Export included uncommitted wheel gesture" }
            check(firstPng.contentEquals(expected)) { "Actual PNG lost wheels pixels" }
            for (format in listOf("JPEG", "TIFF")) {
                val saved = export(format)
                val bytes = resolver.openInputStream(saved)!!.use { it.readBytes() }
                check(bytes.isNotEmpty())
                if (format == "JPEG") {
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid JPEG")
                    check(decoded.width == 512 && decoded.height == 384)
                    decoded.recycle()
                } else check(bytes[0] == 73.toByte() && bytes[1] == 73.toByte())
            }
            file.copyTo(second, overwrite = true)
            val secondUri = Uri.fromFile(second)
            val secondWheel = ColorWheels(shadows = WheelAdjustment(220f, 40f, -20f))
            EditStore.save(context, secondUri, EditorParams().apply {
                grainActive = false; glareActive = false; colorWheels = secondWheel
            })
            test.runOnMainSync { vm.openPhotos(listOf(uri, secondUri)) }
            idle(vm)
            check(vm.params.colorWheels == first)
            test.runOnMainSync { vm.selectPhoto(1) }
            idle(vm)
            check(vm.params.colorWheels == secondWheel)
            val secondPng = pixels(export("PNG"))
            check(!secondPng.contentEquals(firstPng))
            test.runOnMainSync { vm.selectPhoto(0) }
            idle(vm)
            val seen = ids()
            test.runOnMainSync { vm.exportFormat = "PNG"; vm.selectAllBatchExportPhotos(true); vm.batchExport() }
            idle(vm)
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val batch = (ids() - seen).map { pixels(ContentUris.withAppendedId(media, it)) }
            check(batch.size == 2 && batch.any { it.contentEquals(firstPng) } && batch.any { it.contentEquals(secondPng) })
            test.runOnMainSync { vm.syncSettingsToOtherPhotos() }
            check(EditStore.load(context, secondUri)!!.colorWheels == first)
            // Switching away during a live gesture restores the committed edit.
            test.runOnMainSync {
                vm.beginWheelEdit(); vm.changeWheels(ColorWheels.NEUTRAL)
                vm.selectPhoto(1)
            }
            idle(vm)
            check(EditStore.load(context, uri)!!.colorWheels == first)
            check(vm.params.colorWheels == first)
            click("还原当前分区")
            idle(vm)
            // The master selector survives photo switching.
            check(vm.params.colorWheels.isNeutral)
            test.runOnMainSync {
                vm.params.unreadableColorWheels = "{\"version\":99,\"keep\":true}"
                vm.scheduleGrade()
            }
            idle(vm)
            reveal(context.getString(R.string.wheels_unsupported))
            reveal("还原全部色轮")
            SystemClock.sleep(2500) // Let the previous batch-export toast leave the recovery evidence.
            capture("wheels-unsupported.png")
            test.runOnMainSync { vm.export() }
            check(!vm.isExporting && vm.errorMessage != null)
            test.runOnMainSync { vm.dismissError() }
            click("还原全部色轮")
            idle(vm)
            check(vm.params.unreadableColorWheels == null)
            test.runOnMainSync { vm.undo() }
            idle(vm)
            check(vm.params.unreadableColorWheels != null) { "Reset destroyed preserved future data" }
            test.runOnMainSync { vm.redo() }
            idle(vm)
            check(vm.params.unreadableColorWheels == null)
        } finally {
            (ids() - before).forEach { resolver.delete(ContentUris.withAppendedId(media, it), null, null) }
            test.runOnMainSync { activity.finish() }
        }
        return "Real drag/cancel and accessibility, selector/no-redevelop, reset/undo/redo, committed sidecar and switching, copy, exact single/batch PNG, JPEG/TIFF and preserved future data; screenshots captured"
    }

    private fun neutralGrade(wheels: ColorWheels) = Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
        FloatArray(3), FloatArray(3), FloatArray(3), wheels = wheels)

    private fun idle(vm: EditorViewModel) = await {
        vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting && vm.renderTaskState.active == null &&
            vm.rendered != null &&
            listOf("renderJob", "wbJob").none { name ->
                val field = EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(vm) as? kotlinx.coroutines.Job)?.isActive == true
            }
    }

    private fun capture(name: String) {
        test.waitForIdleSync(); SystemClock.sleep(200)
        val image = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun pointer(rect: Rect, cancel: Boolean) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, rect.exactCenterY(), 0)
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        val end = rect.left + rect.width() * if (cancel) .2f else .7f
        send(MotionEvent.ACTION_DOWN, rect.exactCenterX())
        send(MotionEvent.ACTION_MOVE, end)
        SystemClock.sleep(80)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
    }

    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun find(label: String, node: AccessibilityNodeInfo? = root(), range: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        if ((node.text?.toString() == label || node.contentDescription?.toString() == label) && (!range || node.rangeInfo != null)) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i), range)?.let { return it }
        return null
    }

    private fun reveal(label: String, range: Boolean = false): AccessibilityNodeInfo {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(20) {
                find(label, range = range)?.let {
                    it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    test.waitForIdleSync(); SystemClock.sleep(100)
                    val current = find(label, range = range)
                    val bounds = Rect().also { current?.getBoundsInScreen(it) }
                    val config = test.targetContext.resources.configuration
                    val minHeight = if (label == "色彩圆盘")
                        (if (config.screenHeightDp < 500) 126 else 190) * test.targetContext.resources.displayMetrics.density else 1f
                    if (current?.isVisibleToUser == true && bounds.height() >= minHeight) return current
                }
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isScrollable && node.performAction(direction)) return true
                    for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                    return false
                }
                scroll(root()); SystemClock.sleep(100)
            }
        }
        capture("wheels-failure.png")
        error("Missing wheels control: $label")
    }

    private fun click(label: String) {
        var node = reveal(label)
        while (!node.isClickable) node = node.parent ?: error(label)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { label }
        test.waitForIdleSync(); SystemClock.sleep(150)
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Wheel audit timed out" }
            SystemClock.sleep(20)
        }
    }
}
