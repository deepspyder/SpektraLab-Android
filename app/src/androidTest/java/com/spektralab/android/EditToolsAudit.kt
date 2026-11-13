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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.engine.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class EditToolsAudit(private val test: Instrumentation, private val output: File) {
    private val groupLabels = listOf("胶片与相纸", "RAW 解码白平衡", "拍摄与胶片效果", "印放", "光线", "曲线", "颜色", "暗角", "构图与旋转", "片边与日期样式")
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
        val mediaBefore = ids()
        val sourceParams = EditorParams().apply {
            grainActive = false; glareActive = false; brightness = 18f
            colorWheels = ColorWheels(shadows = WheelAdjustment(220f, 25f, -8f))
            toneCurves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .62f))
        }
        val target = EditorParams().apply { grainActive = false; glareActive = false; brightness = -4f; cropX = .1f; cropW = .8f }
        val other = EditorParams().apply { grainActive = false; glareActive = false; contrast = -8f; cropH = .7f }
        val files = (0..2).map { File(output, "edit-tools-$it.png") }
        val uris = files.map(Uri::fromFile)
        val name = "F06 Audit ${System.nanoTime()}"
        var createdId: String? = null
        try {
            await { vm.engine != null && !vm.busy }
            val fixture = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            for (y in 0 until 384) for (x in 0 until 512) fixture.setPixel(x, y,
                0xff000000.toInt() or ((x / 2) shl 16) or ((y * 255 / 383) shl 8) or 90)
            files.forEach { it.outputStream().use { stream -> check(fixture.compress(Bitmap.CompressFormat.PNG, 100, stream)) } }
            fixture.recycle()
            listOf(sourceParams, target, other).forEachIndexed { index, params ->
                val json = params.toJson()
                if (index == 0) json.put("future_extension", org.json.JSONObject().put("keep", true))
                EditStore.save(context, uris[index], EditorParams.fromJson(json))
            }
            test.runOnMainSync { vm.openPhotos(uris) }
            idle(vm)
            click("继续显影")
            val frame = vm.lastResult
            val image = vm.rendered
            click("更多操作")
            capture("edit-tools-entry.png")
            click("复制编辑设置")
            reveal("胶片与相纸")
            capture("edit-tools-groups.png")
            reveal("构图与旋转")
            capture("edit-tools-groups-bottom.png")
            check(vm.lastResult === frame && vm.rendered === image)
            if (layoutOnly) {
                click("下一步：选择照片")
                await { find("应用到 2 张照片")?.isEnabled == true }
                capture("edit-tools-targets.png")
                click("关闭编辑工具")
                click("更多操作"); click("编辑预设")
                await { vm.editPresets.ready && !vm.editPresets.pending }
                capture("edit-tools-presets.png")
                click("将当前调整保存为预设")
                reveal("预设名称")
                capture("edit-tools-preset-save.png")
                check(vm.lastResult === frame && vm.rendered === image)
                return "Copy group choices, targets/notices/pinned apply, preset empty/list and naming form reachable; opening/browsing does not render/decode/save"
            }
            fun chooseGroups(wanted: Set<EditGroup>) {
                for (group in EditGroup.DEFAULT) if (group !in wanted) click(groupLabels[group.ordinal])
                for (group in wanted) if (group !in EditGroup.DEFAULT) click(groupLabels[group.ordinal])
            }
            val chosen = setOf(EditGroup.LIGHT, EditGroup.COLOR, EditGroup.CURVES)
            chooseGroups(chosen)
            click("下一步：选择照片")
            await { find("应用到 2 张照片")?.isEnabled == true }
            click("目标照片 3")
            await { find("应用到 1 张照片")?.isEnabled == true }
            capture("edit-tools-targets.png")
            click("应用到 1 张照片")
            await { !vm.isApplyingEdits && find("关闭编辑工具") == null }
            idle(vm)
            check(vm.rendered === image && vm.lastResult === frame)
            val copied = EditStore.load(context, uris[1])!!
            check(copied.brightness == 18f && copied.colorWheels == sourceParams.colorWheels && copied.toneCurves == sourceParams.toneCurves)
            check(copied.cropX == .1f && copied.cropW == .8f && copied.grainActive == target.grainActive)
            check(!copied.toJson().has("future_extension"))
            check(EditSnapshot.capture(EditStore.load(context, uris[2])!!) == EditSnapshot.capture(other))
            test.runOnMainSync { vm.selectPhoto(1) }
            idle(vm)
            test.runOnMainSync { vm.undo() }
            idle(vm)
            check(EditSnapshot.capture(vm.params) == EditSnapshot.capture(target)) { "Copy lost target undo history" }
            test.runOnMainSync { vm.redo() }
            idle(vm)
            check(vm.params.brightness == 18f && vm.params.cropX == .1f)
            test.runOnMainSync { vm.selectPhoto(0) }
            idle(vm)
            test.runOnMainSync {
                vm.beginWheelEdit(); vm.changeWheels(ColorWheels(master = WheelAdjustment(120f, 60f, 10f)))
                val committed = vm.captureEditTemplate(setOf(EditGroup.COLOR)).toJson().getJSONObject("values")
                check(ColorWheels.fromJson(committed.getJSONObject("color_wheels")) == sourceParams.colorWheels)
                vm.finishWheelEdit(cancel = true)
            }
            idle(vm)
            val preflight = runBlocking { withContext(Dispatchers.Main) {
                vm.previewEditTransfer(EditTemplate.capture(EditorParams().apply { brightness = 33f }, setOf(EditGroup.LIGHT)), uris.drop(1))
            } }
            val firstBefore = EditSnapshot.capture(EditStore.load(context, uris[1])!!)
            test.runOnMainSync {
                vm.params.brightness = 19f
                vm.scheduleGrade()
                check(!vm.canTransferEdits)
                vm.applyEditTransfer(preflight)
                check(!vm.isApplyingEdits) { "Transfer raced a pending preview save" }
                vm.params.brightness = 18f
                vm.scheduleGrade()
            }
            idle(vm)
            check(EditSnapshot.capture(EditStore.load(context, uris[1])!!) == firstBefore)
            EditStore.save(context, uris[2], other.copy().apply { contrast = 13f })
            test.runOnMainSync { vm.applyEditTransfer(preflight) }
            await { !vm.isApplyingEdits && vm.editTransferError != null }
            check(EditSnapshot.capture(EditStore.load(context, uris[1])!!) == firstBefore)
            check(EditStore.load(context, uris[2])!!.contrast == 13f)
            test.runOnMainSync { vm.params.brightness = 28f; vm.scheduleGrade() }
            idle(vm)
            click("更多操作"); click("编辑预设")
            await { vm.editPresets.ready && !vm.editPresets.pending }
            capture("edit-tools-presets.png")
            click("将当前调整保存为预设")
            chooseGroups(chosen)
            setText(name)
            capture("edit-tools-preset-save.png")
            click("保存")
            await { vm.editPresets.items.any { it.name == name } && find(name) != null }
            createdId = vm.editPresets.items.first { it.name == name }.id
            capture("edit-tools-preset-list.png")
            click("重命名预设：$name")
            val renamed = "$name renamed"
            setText(renamed); click("保存")
            await { vm.editPresets.items.any { it.id == createdId && it.name == renamed } && find(renamed) != null }
            click(renamed)
            await { find("应用到 0 张照片") != null }
            val disabledLabel = reveal("应用到 0 张照片")
            val ancestors = generateSequence(disabledLabel) { it.parent }.toList()
            File(output, "edit-tools-disabled-semantics.txt").writeText(ancestors.joinToString("\n") {
                "${it.className} text=${it.text} enabled=${it.isEnabled} clickable=${it.isClickable} actions=${it.actionList}"
            })
            check(ancestors.any { !it.isEnabled }) { "No-change Apply button must remain disabled" }
            click("目标照片 1"); click("目标照片 2")
            await { find("应用到 1 张照片")?.isEnabled == true }
            click("应用到 1 张照片")
            await { !vm.isApplyingEdits && find("关闭编辑工具") == null }
            check(EditStore.load(context, uris[1])!!.brightness == 28f && EditStore.load(context, uris[1])!!.cropX == .1f)
            test.runOnMainSync { vm.params.brightness = 5f; vm.scheduleGrade() }
            idle(vm)
            val currentFrame = vm.lastResult
            click("更多操作"); click("编辑预设"); click(renamed)
            await { find("应用到 1 张照片")?.isEnabled == true }
            click("应用到 1 张照片")
            await { !vm.isApplyingEdits && find("关闭编辑工具") == null }
            idle(vm)
            check(vm.params.brightness == 28f && vm.lastResult === currentFrame)
            test.runOnMainSync { vm.undo() }
            idle(vm); check(vm.params.brightness == 5f && vm.lastResult === currentFrame)
            test.runOnMainSync { vm.redo() }
            idle(vm); check(vm.params.brightness == 28f)
            check(EditStore.load(context, uris[0])!!.toJson().getJSONObject("future_extension").getBoolean("keep"))
            val seen = ids()
            test.runOnMainSync { vm.exportFormat = "PNG"; vm.exportRes = 512; vm.exportColorSpace = "sRGB"; vm.export() }
            idle(vm)
            check(vm.errorMessage == null)
            val saved = ContentUris.withAppendedId(media, (ids() - seen).single())
            val decoded = resolver.openInputStream(saved)!!.use { BitmapFactory.decodeStream(it) }!!
            check(decoded.width == 512 && decoded.height == 384)
            val actual = IntArray(512 * 384)
            decoded.getPixels(actual, 0, 512, 0, 0, 512, 384); decoded.recycle()
            val g = Grade(1f, 1f, 1f, 1f, 0f, .28f, 1f, 0f, 0f, 0f, 0f, FloatArray(3), FloatArray(3), FloatArray(3),
                curves = sourceParams.toneCurves, wheels = sourceParams.colorWheels)
            check(actual.contentEquals(ColorPipeline.applyGrade(currentFrame!!.rgba16, 512, 384, g))) { "Applied preset PNG pixels differ" }
            click("更多操作"); click("编辑预设"); click("删除预设：$renamed")
            capture("edit-tools-delete.png")
            click("取消")
            check(vm.editPresets.items.any { it.id == createdId })
            click("删除预设：$renamed"); click("删除")
            await { !vm.editPresets.pending && vm.editPresets.items.none { it.id == createdId } }
            createdId = null
        } catch (error: Throwable) {
            runCatching { capture("edit-presets-failure.png") }
            throw error
        } finally {
            createdId?.let { id ->
                test.runOnMainSync { vm.editPresets.delete(id) }
                await { !vm.editPresets.pending }
            }
            (ids() - mediaBefore).forEach { resolver.delete(ContentUris.withAppendedId(media, it), null, null) }
            test.runOnMainSync { activity.finish() }
        }
        return "Real copy group/target selection, untouched unchecked target and geometry, per-photo undo/redo, draft exclusion, stale all-or-none rejection; named preset create/rename/apply/delete/cancel; current post-only refresh preserves native frame, exact actual PNG export passed"
    }

    private fun setText(value: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (index in 0 until node.childCount) editable(node.getChild(index))?.let { return it }
            return null
        }
        await { editable(root()) != null }
        val node = editable(root()) ?: error("No preset name editor")
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        test.waitForIdleSync(); SystemClock.sleep(150)
    }

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
                    if (current?.isVisibleToUser == true && bounds.height() > 0) return current
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
        capture("edit-presets-failure.png")
        error("Missing edit tool: $label")
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
            check(SystemClock.elapsedRealtime() - start < 90000) { "Edit tools audit timed out" }
            SystemClock.sleep(20)
        }
    }
}
