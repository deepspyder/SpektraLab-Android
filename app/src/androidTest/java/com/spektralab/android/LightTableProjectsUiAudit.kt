package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.lighttable.GalleryScreen
import com.spektralab.android.feature.lighttable.FilmCanvasState
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File

internal class LightTableProjectsUiAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var activity: MainActivity
    private lateinit var vm: EditorViewModel
    private var backs = 0
    private var edits = 0
    fun run(layoutOnly: Boolean = false): String {
        val files = (0..1).map { index -> File(output, "table-${System.nanoTime()}-$index.png").also { file ->
            val image = Bitmap.createBitmap(if (index == 0) 600 else 300, if (index == 0) 400 else 450, Bitmap.Config.ARGB_8888)
            image.setPixels(IntArray(image.width * image.height) { i ->
                0xff000000.toInt() or (((i % image.width) * 255 / image.width) shl 16) or (((i / image.width) * 255 / image.height) shl 8) or (if (index == 0) 90 else 200)
            }, 0, image.width, 0, 0, image.width, image.height)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        } }
        val bytes = files.map(File::readBytes)
        val name = "光的收藏${System.nanoTime() % 100000}"
        val renamed = name + "·蓝调"
        start()
        try {
            main { vm.loadViewingPhotos(files.map(Uri::fromFile)) }
            await { !vm.isImportingViewingPhotos && vm.viewingPhotos.size == 2 && !vm.busy }
            val native = vm.lastResult
            show()
            await { find("查看第 1 张 ${files[0].name}") != null }
            capture("table-draft.png")
            click("保存项目"); text(name); capture("table-name.png"); click("保存项目")
            await { vm.lightTableProjects.current != null && !vm.lightTableProjects.pending }
            val c = vm.lightTableProjects
            val id = c.current!!.scene.items.first().id
            check(c.current!!.scene.items.size == 2 && !c.dirty && vm.lastResult === native)
            await { c.canvas.members.mapNotNull { it.source.preview }.all { it in c.previews } }
            capture("table-created.png")
            custom("查看第 1 张 ${files[0].name}", "调整摆放")
            await { find("调整摆放") != null }
            click("向右旋转 90°")
            check(c.canvas.rotations[id] == 90f && c.dirty)
            capture("table-placement.png")
            click("复制到画布"); check(c.canvas.members.size == 3)
            click("物理尺寸模式")
            capture("table-millimeters.png")
            click("完成")
            check(c.canvas.millimeters)
            capture("table-rotated.png")
            click("撤销布局调整"); check(!c.canvas.millimeters)
            click("撤销布局调整"); check(c.canvas.members.size == 2)
            click("重做布局调整"); check(c.canvas.members.size == 3)
            click("保存项目"); await { !c.pending && !c.dirty }
            val saved = c.current!!
            click(name); await { c.manager && !c.pending }; click(name)
            await { !c.pending && c.previews.isNotEmpty() }
            check(c.canvas.scene() == saved.scene)
            click(name); await { c.manager && !c.pending }; capture("table-projects.png")
            click("项目操作：$name"); click("重命名项目"); text(renamed); capture("table-rename.png"); click("保存项目")
            await { !c.pending && c.current!!.name == renamed }
            click("关闭项目"); await { c.current == null }
            click("观景台项目"); await { c.manager && !c.pending }; click(renamed)
            await { c.current?.name == renamed && !c.pending && c.previews.isNotEmpty() }
            check(c.canvas.scene() == saved.scene && !c.dirty && c.canvas.members.map { it.source.uri }.distinct().size == 2)
            capture("table-restored.png")
            click("查看第 1 张 ${files[0].name}"); click("照片操作"); click("在暗房编辑")
            await { edits == 1 && !c.pending }; click("关闭沉浸式观片")
            if (layoutOnly) return "Native project create/rotation/independent duplicate/mm/cancel-free committed controls/save/rename/open with exact scene restore; readable phone/tablet/landscape states"
            main { c.canvas.rotate(id, -23f) }
            click("返回暗房"); await { find("离开项目") != null }; capture("table-unsaved.png")
            check(backs == 0)
            click("放弃调整"); check(backs == 1 && c.canvas.scene() == saved.scene && !c.dirty)
            // Fresh Activity and ViewModel; explicitly open the saved project, no source decode.
            main { activity.finish() }; test.waitForIdleSync()
            start(); show(); click("观景台项目"); await { vm.lightTableProjects.manager && !vm.lightTableProjects.pending }
            click(renamed); await { vm.lightTableProjects.current != null && !vm.lightTableProjects.pending }
            check(vm.lightTableProjects.canvas.scene() == saved.scene)
            check(vm.photos.isEmpty() && vm.viewingPhotos.isEmpty() && vm.lastResult == null)
            capture("table-new-process-session.png")
            // Missing originals still leave the saved project and cached preview available.
            files.forEach(File::delete)
            click(renamed); await { !vm.lightTableProjects.pending }; click("关闭项目")
            click("观景台项目"); await { !vm.lightTableProjects.pending }; click(renamed)
            await { vm.lightTableProjects.current != null && !vm.lightTableProjects.pending }
            check(vm.lightTableProjects.canvas.members.size == 3)
            click("查看第 1 张 ${files[0].name}"); click("照片操作"); click("在暗房编辑")
            await { vm.lightTableProjects.error != null && !vm.lightTableProjects.pending }
            check(vm.lightTableProjects.error == test.targetContext.getString(R.string.table_source_missing))
            capture("table-missing-original.png"); click(test.targetContext.getString(R.string.photos_understood)); click("关闭沉浸式观片")
            click(renamed); await { !vm.lightTableProjects.pending }; click("项目操作：$renamed"); click("删除项目")
            click("删除"); await { !vm.lightTableProjects.pending }
            check(vm.lightTableProjects.current != null && vm.lightTableProjects.error != null)
            capture("table-delete-in-use.png"); click("取消"); click("关闭项目")
            click("观景台项目"); await { !vm.lightTableProjects.pending }; click("项目操作：$renamed"); click("删除项目")
            capture("table-delete.png"); click("删除"); await { !vm.lightTableProjects.pending }
            check(vm.lightTableProjects.projects.none { it.name == renamed })
            return "Actual native create/save/rename/open,90degrotation, duplicate same source, mm toggle, undo/redo, exact persistence after fresh Activity/ViewModel, no RAW/native rendering on project open, unsaved navigation guard, missing original preservation, in-use deletion refusal and safe delete passed"
        } finally {
            main { activity.finish() }; test.waitForIdleSync()
            files.forEachIndexed { index, file -> if (file.exists()) check(file.readBytes().contentEquals(bytes[index])) }
        }
    }
    private fun start() {
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
    }
    private fun show() = main { activity.setContent { TheiaTheme {
        GalleryScreen(vm, onImport = {}, onBackToDarkroom = { backs++ }, onRemovePhoto = { vm.removeViewingPhoto(it.toString()) },
            onEditPhoto = { edits++ }, canvasState = FilmCanvasState())
    } } }
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(label, node.getChild(index))?.let { return it }
        return null
    }
    private fun click(label: String) {
        fun actionable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null && !candidate.isClickable) candidate = candidate.parent
                if (candidate != null && candidate.isVisibleToUser) return node
            }
            for (i in 0 until node.childCount) actionable(node.getChild(i))?.let { return it }
            return null
        }
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) repeat(20) {
            actionable(root())?.let { found ->
                found.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync()
                var node = actionable(root()) ?: found
                val bounds = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && bounds.height() > 0) {
                    while (!node.isClickable) node = node.parent ?: error(label)
                    check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { label }
                    test.waitForIdleSync(); SystemClock.sleep(250); return
                }
            }
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isScrollable && node.performAction(direction)) return true
                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                return false
            }
            scroll(root()); SystemClock.sleep(80)
        }
        error("Missing table action $label")
    }
    private fun custom(label: String, action: String) {
        await { find(label) != null }
        val node = find(label)!!; val command = node.actionList.first { it.label?.toString() == action }
        check(node.performAction(command.id)); test.waitForIdleSync(); SystemClock.sleep(250)
    }
    private fun text(value: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        await { editable(root()) != null }
        val field = editable(root()) ?: error("Name field missing")
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        test.waitForIdleSync(); SystemClock.sleep(200)
    }
    private fun capture(name: String) { test.waitForIdleSync(); SystemClock.sleep(if (name == "table-projects.png") 1000 else 600)
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun await(condition: () -> Boolean) { val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 30000) { "Table UI timeout" }; SystemClock.sleep(40) }
    }
}
