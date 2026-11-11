package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real touch events on the production preview, including cancellation and sibling controls. */
internal class HoldOriginalAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var vm: EditorViewModel
    private lateinit var activity: MainActivity
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(label: String, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < 30000) { "Timeout $label; ${vm.errorMessage}" }
            Thread.sleep(40)
        }
    }
    private fun root(): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(label, node.getChild(index))?.let { return it }
        return null
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        var node = checkNotNull(find(label))
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        test.waitForIdleSync()
        Thread.sleep(350)
    }
    private fun bounds(label: String) = Rect().also { checkNotNull(find(label)).getBoundsInScreen(it) }
    private fun preview() = find("照片预览") ?: find("全屏照片预览")
    private fun previewBounds() = Rect().also { checkNotNull(preview()).getBoundsInScreen(it) }
    private fun state() = preview()?.stateDescription?.toString()
    private fun pointer(action: Int, rect: Rect, start: Long) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
            rect.exactCenterX(), rect.exactCenterY(), 0)
        try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun screenshot(name: String): Bitmap {
        test.waitForIdleSync()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val presented = CountDownLatch(1)
            main {
                val decor = activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { presented.countDown() }
                decor.invalidate()
            }
            check(presented.await(5, TimeUnit.SECONDS)) { "Frame was not committed for $name" }
        }
        val image = checkNotNull(test.uiAutomation.takeScreenshot())
        File(output, "hold_original_$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }
    private fun sample(image: Bitmap, rect: Rect) = IntArray(3) { channel ->
        val shift = 16 - channel * 8
        var sum = 0
        for (y in -3..3) for (x in -3..3) sum += (image.getPixel(rect.centerX() + x, rect.centerY() + y) shr shift) and 255
        sum / 49
    }
    private fun holdRound(name: String, cancel: Boolean = false) {
        await("effect") { state() == "效果图" }
        val rect = previewBounds()
        val result = vm.lastResult
        val rendered = vm.rendered
        val sourceBefore = vm.sourceBitmap
        val params = vm.params.toJson().toString()
        val before = screenshot("${name}_effect")
        val start = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, rect, start)
        try {
            await("original") { state() == "原图" }
            Thread.sleep(650)
            val original = screenshot("${name}_pressed")
            val first = sample(before, rect)
            val second = sample(original, rect)
            check(first.indices.sumOf { abs(first[it] - second[it]) } > 20) { "Press did not change displayed pixels" }
            // The held frame uses the already decoded source, independently fitted to the preview.
            val source = checkNotNull(vm.sourceBitmap)
            val scale = minOf(rect.width().toFloat() / source.width, rect.height().toFloat() / source.height)
            val fitted = Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(),
                (source.height * scale).roundToInt(), true)
            val expected = sample(fitted, Rect(0, 0, fitted.width, fitted.height))
            if (fitted !== source) fitted.recycle()
            check(second.indices.sumOf { abs(second[it] - expected[it]) } < 45) {
                "Held pixels ${second.toList()} do not match fitted source ${expected.toList()}"
            }
            original.recycle()
            check(vm.lastResult === result && vm.rendered === rendered && vm.sourceBitmap === sourceBefore && vm.params.toJson().toString() == params) {
                "Holding rebuilt the render or modified edit parameters"
            }
        } finally {
            pointer(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, rect, start)
        }
        await("restored") { state() == "效果图" }
        Thread.sleep(250)
        val restored = screenshot("${name}_released")
        check(sample(before, rect).contentEquals(sample(restored, rect))) {
            "Release did not restore effect pixels: ${sample(before, rect).toList()} vs ${sample(restored, rect).toList()}"
        }
        check(vm.lastResult === result && vm.rendered === rendered)
        before.recycle(); restored.recycle()
    }

    fun run(): String {
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val auto = vm.smartAutoEnabled
        try {
            await("home") { !vm.busy && find("更多操作") != null }
            val decoded = test.context.assets.open("13mb.jpg").use {
                checkNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16 }))
            }
            val photo = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { postRotate(90f) }, true)
            if (photo !== decoded) decoded.recycle()
            val file = File(output, "hold-original-source.png")
            file.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
            photo.recycle()
            val uri = Uri.fromFile(file)
            val params = EditorParams().apply {
                grainActive = false; glareActive = false; gradeExposure = -.9f; saturation = -65f
            }
            EditStore.save(test.targetContext, uri, params)
            main { vm.smartAutoEnabled = false; vm.openPhotos(listOf(uri)) }
            await("photo") { !vm.busy && vm.hasPhoto && vm.rendered != null }
            click("继续显影")
            holdRound("compact")
            if (find("全部调整") != null) click("全部调整")
            holdRound("adjustment")
            holdRound("cancel", cancel = true)

            // The split toggle remains clickable and owns its horizontal drag gesture.
            click("前后对比")
            await("split") { state() == "分割对比" }
            val divider = bounds("前后对比分界线")
            val start = SystemClock.uptimeMillis()
            pointer(MotionEvent.ACTION_DOWN, divider, start)
            try { Thread.sleep(500); check(state() == "分割对比") } finally { pointer(MotionEvent.ACTION_UP, divider, start) }
            check(find("前后对比分界线")!!.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .7f) }))
            screenshot("split").recycle()
            click("关闭前后对比")

            // Fullscreen uses the same production preview; the overlay buttons are not swallowed.
            if (find("全屏预览") != null) {
                click("全屏预览")
                holdRound("fullscreen")
                click("关闭全屏预览")
            }
            // Cancelling when the composable leaves the screen must not leave a stuck original.
            val rect = previewBounds()
            val leaving = SystemClock.uptimeMillis()
            pointer(MotionEvent.ACTION_DOWN, rect, leaving)
            await("hold before removal") { state() == "原图" }
            main { vm.beginCrop() }
            pointer(MotionEvent.ACTION_CANCEL, rect, leaving)
            await("crop") { find("完成裁剪") != null }
            check(find("原图 · 松手恢复") == null)
            screenshot("crop").recycle()
            main { vm.cancelCrop() }
            await("crop exited") { !vm.isCropping && state() == "效果图" }
            holdRound("after_crop")
            return "Press shows cached source pixels; release/cancel restore effect; compact/adjustment/fullscreen work; split controls and crop remain intact; no render, decode or edit-parameter changes"
        } finally {
            main { vm.smartAutoEnabled = auto; activity.finish() }
        }
    }
}
