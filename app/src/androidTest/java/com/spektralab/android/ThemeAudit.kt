package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File

/** Tests rendered pixels, so a green cast from inherited surfaces is caught too. */
internal class ThemeAudit(private val instrumentation: Instrumentation, private val output: File) {
    private val automation get() = instrumentation.uiAutomation
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return automation.rootInActiveWindow
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < 20000) { "Timed out: $label" }
            Thread.sleep(50)
        }
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        var target = checkNotNull(find(label))
        while (!target.isClickable) target = target.parent ?: error("Not clickable: $label")
        check(target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled: $label" }
        instrumentation.waitForIdleSync()
        Thread.sleep(450)
    }
    private fun neutral(name: String, bitmap: Bitmap, x: Int, y: Int) {
        val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
        check(Color.red(pixel) == Color.green(pixel) && Color.green(pixel) == Color.blue(pixel)) {
            "$name has a color cast: " + Integer.toHexString(pixel)
        }
    }
    private fun bounds(label: String): Rect = Rect().also { checkNotNull(find(label)).getBoundsInScreen(it) }
    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        Thread.sleep(450)
    }
    private fun capture(name: String, checks: (Bitmap) -> Unit = {}) {
        instrumentation.waitForIdleSync()
        automation.waitForIdle(200, 5000)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            File(output, "theme_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            checks(bitmap)
        } finally { bitmap.recycle() }
    }
    fun run(): String {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        instrumentation.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            await("home") { vm.engine != null && !vm.busy && find("更多操作") != null }
            capture("home") { neutral("Home background", it, 8, it.height / 2) }
            click("更多操作")
            capture("home_menu")
            click("测试图")
            await("render") { !vm.busy && vm.rendered != null && find("胶片与相纸") != null }
            capture("editor") {
                neutral("navigation", it, 8, bounds("调色").centerY())
            }
            click("放印")
            await("print controls") { find("放大机") != null }
            capture("darkroom") {
                neutral("darkroom header", it, 8, bounds("暗房调整").centerY())
                neutral("darkroom panel", it, 8, bounds("放大机").centerY())
                neutral("darkroom navigation", it, 8, bounds("调色").centerY())
            }
            click("调色")
            capture("grade") { neutral("grade header", it, 8, bounds("暗房调整").centerY()) }
            click("导出")
            capture("export") { neutral("export header", it, 8, bounds("导出照片").centerY()) }
            click("观景台")
            await("contact sheet") { find("返回暗房") != null }
            capture("gallery")
            click("关闭灯光")
            capture("gallery_light_off")
            click("返回暗房")
            await("export restored") { find("导出照片") != null }
            capture("returned_from_gallery") { neutral("restored editor", it, 8, bounds("导出照片").centerY()) }
            back()
            await("editor restored") { find("暗房调整") != null }
            back()
            await("compact editor restored") { find("全部调整") != null }
            back()
            await("home restored") { find("让光，慢慢显影。") != null }
            capture("home_returned") { neutral("returned Home", it, 8, it.height / 2) }
            return "Home, darkroom controls and navigation render neutral pixels; menu, grade, export and gallery return verified"
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
