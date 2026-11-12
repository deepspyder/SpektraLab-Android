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

internal class ChannelMixerAudit(private val test: Instrumentation, private val output: File) {
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
        val file = File(output, "mixer-source.png")
        val second = File(output, "mixer-second.png")
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
            click("通道混合")
            reveal(context.getString(R.string.mixer_output))
            capture("mixer-context.png")
            reveal("红色输入", range = true)
            check(vm.lastResult === frame && vm.rendered === bitmap) { "Opening mixer rendered a new frame" }
            capture("mixer-neutral.png")
            if (layoutOnly) {
                for (name in listOf("绿色", "蓝色", "红色")) {
                    click(name)
                    check(reveal("蓝色输入", range = true).isEnabled)
                }
                reveal("还原全部通道")
                capture("mixer-layout.png")
                return "Mixer controls reachable with active photo; selectors, all inputs and resets visible; opening/selecting does not redevelop"
            }
            val start = Rect().also { reveal("绿色输入", true).getBoundsInScreen(it) }
            pointer(start, cancel = false)
            idle(vm)
            val first = vm.params.channelMixer
            check(!first.isNeutral && vm.rendered !== bitmap && vm.lastResult === frame)
            check(EditStore.load(context, uri)!!.channelMixer == first)
            pointer(Rect().also { reveal("绿色输入", true).getBoundsInScreen(it) }, cancel = true)
            idle(vm)
            check(vm.params.channelMixer == first && EditStore.load(context, uri)!!.channelMixer == first) {
                "Cancel changed mixer: first=${first.toJson()} current=${vm.params.channelMixer.toJson()} saved=${EditStore.load(context, uri)!!.channelMixer.toJson()}"
            }
            test.runOnMainSync { vm.undo() }
            idle(vm)
            check(vm.params.channelMixer.isNeutral && vm.lastResult === frame)
            test.runOnMainSync { vm.redo() }
            idle(vm)
            check(vm.params.channelMixer == first && vm.lastResult === frame)
            click("绿色")
            val slider = reveal("蓝色输入", true)
            check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -25f)
            }))
            test.runOnMainSync { vm.finishMixerEdit() }
            idle(vm)
            check(vm.params.channelMixer[MixerChannel.GREEN].blue == -25f)
            check(vm.params.channelMixer[MixerChannel.RED] == first[MixerChannel.RED])
            click("还原蓝色输入")
            idle(vm)
            check(vm.params.channelMixer == first)
            click("红色")
            capture("mixer-edited.png")

            // Pending gestures stay out of both save and parameter-copy snapshots.
            test.runOnMainSync {
                vm.beginMixerEdit()
                vm.changeMixer(first.with(MixerChannel.BLUE, MixerRow(red = 30f)))
                vm.params.brightness = 8f
                vm.scheduleGrade()
            }
            check(EditStore.load(context, uri)!!.channelMixer == first)
            test.runOnMainSync { vm.finishMixerEdit(cancel = true); vm.params.brightness = 0f; vm.scheduleGrade() }
            idle(vm)
            check(vm.params.channelMixer == first)
            var finalDrag = first
            val dragStart = SystemClock.elapsedRealtime()
            test.runOnMainSync { vm.beginMixerEdit() }
            repeat(24) { step ->
                finalDrag = first.with(MixerChannel.BLUE, MixerRow(red = step - 12f))
                test.runOnMainSync { vm.changeMixer(finalDrag) }
                SystemClock.sleep(12)
            }
            test.runOnMainSync { vm.finishMixerEdit() }
            idle(vm)
            check(vm.lastResult === frame && vm.params.channelMixer == finalDrag)
            val dragImage = vm.rendered!!
            val dragPixels = IntArray(dragImage.width * dragImage.height)
            dragImage.getPixels(dragPixels, 0, dragImage.width, 0, 0, dragImage.width, dragImage.height)
            check(dragPixels.contentEquals(ColorPipeline.applyGrade(frame!!.rgba16, frame.width, frame.height, neutralGrade(finalDrag)))) {
                "Continuous mixer edits did not publish the latest exact frame"
            }
            File(output, "mixer-drag-ms.txt").writeText((SystemClock.elapsedRealtime() - dragStart).toString())
            test.runOnMainSync {
                vm.beginMixerEdit(); vm.changeMixer(first); vm.finishMixerEdit()
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
            val firstPng = pixels(export("PNG"))
            check(firstPng.contentEquals(expected)) { "Actual PNG lost mixer pixels" }
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
            val secondMixer = ChannelMixer(blue = MixerRow(red = 40f, blue = -20f))
            EditStore.save(context, secondUri, EditorParams().apply {
                grainActive = false; glareActive = false; channelMixer = secondMixer
            })
            test.runOnMainSync { vm.openPhotos(listOf(uri, secondUri)) }
            idle(vm)
            check(vm.params.channelMixer == first)
            test.runOnMainSync { vm.selectPhoto(1) }
            idle(vm)
            check(vm.params.channelMixer == secondMixer)
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
            check(EditStore.load(context, secondUri)!!.channelMixer == first)
            // Switching away during a live gesture restores the committed edit.
            test.runOnMainSync {
                vm.beginMixerEdit(); vm.changeMixer(ChannelMixer.IDENTITY)
                vm.selectPhoto(1)
            }
            idle(vm)
            check(EditStore.load(context, uri)!!.channelMixer == first)
            check(vm.params.channelMixer == first)
            click("还原当前通道")
            idle(vm)
            // Selection survived switching: red row is the only non-neutral row.
            check(vm.params.channelMixer.isNeutral)
            test.runOnMainSync {
                vm.params.unreadableChannelMixer = "{\"version\":99,\"keep\":true}"
                vm.scheduleGrade()
            }
            idle(vm)
            reveal(context.getString(R.string.mixer_unsupported))
            capture("mixer-unsupported.png")
            test.runOnMainSync { vm.export() }
            check(!vm.isExporting && vm.errorMessage != null)
            test.runOnMainSync { vm.dismissError() }
            click("还原全部通道")
            idle(vm)
            check(vm.params.unreadableChannelMixer == null)
            test.runOnMainSync { vm.undo() }
            idle(vm)
            check(vm.params.unreadableChannelMixer != null) { "Reset destroyed preserved future data" }
            test.runOnMainSync { vm.redo() }
            idle(vm)
            check(vm.params.unreadableChannelMixer == null)
        } finally {
            (ids() - before).forEach { resolver.delete(ContentUris.withAppendedId(media, it), null, null) }
            test.runOnMainSync { activity.finish() }
        }
        return "Real drag/cancel and accessibility, selector/no-redevelop, reset/undo/redo, committed sidecar and switching, copy, exact single/batch PNG, JPEG/TIFF and preserved future data; screenshots captured"
    }

    private fun neutralGrade(mixer: ChannelMixer) = Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
        FloatArray(3), FloatArray(3), FloatArray(3), mixer = mixer)

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
                    return find(label, range = range) ?: it
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
        capture("mixer-failure.png")
        error("Missing mixer control: $label")
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
            check(SystemClock.elapsedRealtime() - start < 90000) { "Mixer audit timed out" }
            SystemClock.sleep(20)
        }
    }
}
