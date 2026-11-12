package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
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

/** Real production touch, split pixels and lifecycle checks on the stock picker. */
internal class StockComparisonAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var vm: EditorViewModel
    private lateinit var activity: MainActivity
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 30000) { "Stock comparison timed out: $label / " + vm.errorMessage }
            SystemClock.sleep(30)
        }
    }
    private fun find(label: String, parent: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (parent == null) return null
        if (parent.text?.toString() == label || parent.contentDescription?.toString() == label) return parent
        for (i in 0 until parent.childCount) find(label, parent.getChild(i))?.let { return it }
        return null
    }
    private fun root(): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        var node = checkNotNull(find(label))
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { label }
        test.waitForIdleSync()
        SystemClock.sleep(150)
    }
    private fun state() = find("胶片实时预览")?.stateDescription?.toString()
    private fun bounds() = Rect().also { checkNotNull(find("胶片实时预览")).getBoundsInScreen(it) }
    private fun pointer(action: Int, start: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
        try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun capture(name: String): Bitmap {
        test.waitForIdleSync()
        val presented = CountDownLatch(1)
        main {
            activity.window.decorView.apply {
                viewTreeObserver.registerFrameCommitCallback { presented.countDown() }
                invalidate()
            }
        }
        check(presented.await(5, TimeUnit.SECONDS))
        return checkNotNull(test.uiAutomation.takeScreenshot()).also { bitmap ->
            File(output, "stock_comparison_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun sample(bitmap: Bitmap, rect: Rect) = IntArray(3) { channel ->
        var sum = 0
        for (dy in -3..3) for (dx in -3..3) {
            sum += (bitmap.getPixel((rect.left + rect.width() * .4f).toInt() + dx, rect.centerY() + dy) shr (16 - channel * 8)) and 255
        }
        sum / 49
    }
    private fun difference(a: IntArray, b: IntArray) = a.indices.sumOf { abs(a[it] - b[it]) }
    private fun hold(name: String, cancel: Boolean): Pair<IntArray, IntArray> {
        await("effect") { state() == "冲印效果" }
        val rect = bounds()
        val before = capture(name + "_effect")
        val printed = sample(before, rect)
        before.recycle()
        val source = vm.sourceBitmap
        val rendered = vm.rendered
        val result = vm.lastResult
        val completed = vm.renderTaskState.completed
        val params = vm.params.toJson().toString()
        val saved = EditStore.load(test.targetContext, vm.currentUri!!)!!.toJson().toString()
        val start = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, start, rect.exactCenterX(), rect.exactCenterY())
        val original: IntArray
        try {
            await("held original") { state() == "原图" }
            SystemClock.sleep(550)
            val held = capture(name + "_held")
            original = sample(held, rect)
            held.recycle()
            check(difference(printed, original) > 25) { "Hold did not display original pixels" }
        } finally {
            pointer(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, start, rect.exactCenterX(), rect.exactCenterY())
        }
        await("restore") { state() == "冲印效果" }
        val released = capture(name + "_restored")
        check(difference(sample(released, rect), printed) == 0) { "Release/cancel did not restore printed pixels" }
        released.recycle()
        check(vm.sourceBitmap === source && vm.rendered === rendered && vm.lastResult === result &&
            vm.renderTaskState.completed == completed && vm.params.toJson().toString() == params)
        check(EditStore.load(test.targetContext, vm.currentUri!!)!!.toJson().toString() == saved) { "Hold saved an edit" }
        return original to printed
    }
    private fun setSplit(value: Float) {
        val divider = checkNotNull(find("前后对比分界线"))
        check(divider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }))
        test.waitForIdleSync()
    }
    private fun split(name: String, original: IntArray, printed: IntArray) {
        val result = vm.lastResult
        val rendered = vm.rendered
        val completed = vm.renderTaskState.completed
        click("前后对比")
        await("split") { state() == "分割对比" && find("Before · 原图") != null && find("After · 冲印") != null }
        val rect = bounds()
        setSplit(.75f)
        val left = capture(name + "_original_side")
        check(difference(sample(left, rect), original) < 12) { "Original side is not original pixels" }
        left.recycle()
        setSplit(.25f)
        val right = capture(name + "_printed_side")
        check(difference(sample(right, rect), printed) < 12) { "Printed side is not the complete latest print" }
        right.recycle()
        // A hold in split mode must never steal the divider's gesture.
        val start = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, start, rect.centerX().toFloat(), rect.centerY().toFloat())
        try {
            SystemClock.sleep(550)
            check(state() == "分割对比")
            pointer(MotionEvent.ACTION_MOVE, start, rect.left + rect.width() * .7f, rect.centerY().toFloat())
        } finally {
            pointer(MotionEvent.ACTION_UP, start, rect.left + rect.width() * .7f, rect.centerY().toFloat())
        }
        await("divider dragged") { (find("前后对比分界线")?.rangeInfo?.current ?: 0f) > .6f }
        click("关闭前后对比")
        await("effect after split") { state() == "冲印效果" && find("前后对比分界线") == null }
        check(vm.lastResult === result && vm.rendered === rendered && vm.renderTaskState.completed == completed) {
            "Comparison decoded or rendered another frame"
        }
    }

    fun run(): String {
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val auto = vm.smartAutoEnabled
        try {
            await("engine") { vm.engine != null && !vm.busy }
            val uris = (0..1).map { index ->
                val image = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                image.setPixels(IntArray(800 * 600) { i ->
                    0xff000000.toInt() or (((i % 800 * 200 / 800) + 30) shl 16) or
                        (((i / 800 * 180 / 600) + 30) shl 8) or (70 + index * 80)
                }, 0, 800, 0, 0, 800, 600)
                val file = File(output, "stock-comparison-$index.png")
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                Uri.fromFile(file).also { uri ->
                    EditStore.save(test.targetContext, uri, EditorParams().apply {
                        grainActive = false
                        glareActive = false
                        gradeExposure = -1f
                        saturation = -65f
                        // Different before/after aspect ratios must each fit without stretching.
                        cropX = .1f; cropW = .7f
                    })
                }
            }
            main { vm.smartAutoEnabled = false; vm.openPhotos(uris) }
            await("photo") { vm.hasPhoto && !vm.busy && vm.rendered != null }
            click("继续显影")
            click("胶片与相纸")
            await("stock picker") { find("胶片列表") != null }
            val (original, printed) = hold("film", cancel = false)
            hold("film_cancel", cancel = true)
            split("film", original, printed)
            click("相纸")
            await("paper picker") { find("相纸列表") != null }
            val paper = hold("paper", cancel = true)
            split("paper", paper.first, paper.second)
            click("前后对比")
            val old = vm.lastResult
            main { vm.selectPaper("fujifilm_crystal_archive_typeii") }
            await("paper in split") { vm.lastResult !== old && vm.renderTaskState.active == null }
            check(state() == "分割对比") { "Paper selection lost comparison mode" }
            click("关闭前后对比")
            val newer = hold("paper_changed", cancel = false)
            split("paper_changed", newer.first, newer.second)
            click("前后对比")
            main { vm.selectPhoto(1) }
            await("new photo resets split") { !vm.busy && vm.currentUri == uris[1] && state() == "冲印效果" }
            val rect = bounds()
            val start = SystemClock.uptimeMillis()
            pointer(MotionEvent.ACTION_DOWN, start, rect.exactCenterX(), rect.exactCenterY())
            await("hold before switching") { state() == "原图" }
            main { vm.selectPhoto(0) }
            pointer(MotionEvent.ACTION_CANCEL, start, rect.exactCenterX(), rect.exactCenterY())
            await("new photo resets hold") { !vm.busy && vm.currentUri == uris[0] && state() == "冲印效果" }
            click("应用胶片与相纸")
            click("相纸")
            await("direct paper entry") { find("相纸列表") != null && state() == "冲印效果" }
            hold("paper_reentry", cancel = false)
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            return "Film/paper hold and release/cancel restore exact pixels; split shows fitted source and complete print; divider drag/accessibility and overlay buttons work; paper updates latest print, photo changes clear split/hold; direct paper reentry; no render/decode/save for comparison"
        } finally {
            main { vm.smartAutoEnabled = auto; activity.finish() }
        }
    }
}
