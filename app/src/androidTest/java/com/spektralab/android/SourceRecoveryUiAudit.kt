package com.spektralab.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import kotlinx.coroutines.Job

internal class SourceRecoveryUiAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var activity: MainActivity
    private lateinit var vm: EditorViewModel

    fun run(layoutOnly: Boolean = false): String {
        val context = test.targetContext
        val root = File(output, "source-ui-${System.nanoTime()}").apply { mkdirs() }
        val source = File(root, "source-recovery-original.png")
        val second = File(root, "source-recovery-second.png")
        val missing = File(root, "source-recovery-offline.png")
        val moved = File(root, "source-recovery-moved.png")
        val bitmap = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(384 * 256) { i -> 0xff000000.toInt() or ((i % 384 * 255 / 384) shl 16) or ((i / 384) shl 8) or 90 }, 0, 384, 0, 0, 384, 256)
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.eraseColor(0xff638677.toInt())
        second.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val a = Uri.fromFile(source)
        val b = Uri.fromFile(second)
        val offline = Uri.fromFile(missing)
        val replacement = Uri.fromFile(moved)
        EditStore.save(context, a, EditorParams().apply { brightness = 18f; grainActive = false; glareActive = false; cropX = .1f; cropW = .8f })
        EditStore.save(context, b, EditorParams().apply { brightness = -7f; grainActive = false; glareActive = false })
        EditStore.save(context, offline, EditorParams().apply { brightness = 3f })
        val first = EditStore.savedPhotos(context).first { it.uri == a.toString() }
        start()
        try {
            test.runOnMainSync { vm.openPhotos(listOf(a, b, offline)) }
            idle()
            test.runOnMainSync {
                vm.selectAllBatchExportPhotos(false)
                vm.setBatchExportPhotoSelected(b, true)
                vm.setBatchExportPhotoSelected(offline, true)
                vm.selectPhoto(1)
            }
            idle()
            test.runOnMainSync { vm.params.brightness = 9f; vm.scheduleGrade() }
            idle()
            val expected = SavedEditSession(listOf(a, b, offline).map(Uri::toString), b.toString(), setOf(b.toString(), offline.toString()))
            await { EditStore.savedSession(context) == expected }
            test.runOnMainSync { activity.finish() }
            test.waitForIdleSync()
            start()
            await { find("照片图库") != null }
            check(vm.photos.isEmpty() && !vm.hasPhoto) { "Startup automatically decoded the old workbench" }
            click("照片图库")
            await { !vm.savedPhotos.loading && vm.savedPhotos.savedSession == expected }
            capture("source-recovery-session.png")
            click("恢复工作台")
            idle()
            check(vm.photos == listOf(a, b, offline) && vm.currentUri == b && vm.currentIndex == 1)
            check(vm.batchExportSelection == setOf(b, offline) && vm.params.brightness == 9f)
            capture("source-recovery-restored.png")
            click("返回首页"); click("照片图库")
            await { !vm.savedPhotos.loading }
            click("原片操作：${source.name}")
            capture("source-recovery-menu.png")
            click("核对原片")
            capture("source-recovery-confirm.png")
            click("取消")
            check(EditStore.savedPhoto(context, first.assetId)!!.sourceSha256 == null)
            var unwantedOpen = false
            test.runOnMainSync {
                val photo = vm.savedPhotos.photos.first { it.assetId == first.assetId }
                vm.savedPhotos.checkSource(photo)
                vm.savedPhotos.cancelSourceCheck()
                check(!vm.savedPhotos.loading && vm.savedPhotos.checkingAsset == null)
                vm.savedPhotos.open(photo) { unwantedOpen = true }
                vm.savedPhotos.leave()
                check(!vm.savedPhotos.loading)
                vm.savedPhotos.refresh()
                check(vm.savedPhotos.loading)
            }
            await { !vm.savedPhotos.loading }
            check(!unwantedOpen && EditStore.savedPhoto(context, first.assetId)!!.sourceSha256 == null)
            click("原片操作：${source.name}"); click("核对原片"); click("开始核对")
            await { !vm.savedPhotos.loading && EditStore.savedPhoto(context, first.assetId)!!.sourceSha256 != null }
            val native = vm.lastResult
            capture("source-recovery-checked.png")
            if (layoutOnly) return "Native session banner, source menu, baseline disclosure, cancel and verified state; large-font/tablet/landscape layout; no render during checking"

            pickSource(source.name, null)
            check(EditStore.savedPhoto(context, first.assetId)!!.uri == a.toString())
            pickSource(source.name, b)
            await { !vm.savedPhotos.loading && vm.savedPhotos.sourceErrors[first.assetId] != null }
            capture("source-recovery-rejected.png")
            check(EditStore.savedPhoto(context, first.assetId)!!.uri == a.toString() && vm.lastResult === native)
            test.runOnMainSync {
                val photo = vm.savedPhotos.photos.first { it.assetId == first.assetId }
                vm.savedPhotos.prepareRelink(photo)
                vm.savedPhotos.refresh()
                check(vm.savedPhotos.loading)
                vm.savedPhotos.relink(b, vm::relinkWorkbenchSource)
                check(vm.savedPhotos.checkingAsset == first.assetId) { "Picker result lost behind resume refresh" }
            }
            await { !vm.savedPhotos.loading }
            check(EditStore.savedPhoto(context, first.assetId)!!.uri == a.toString())
            check(source.renameTo(moved))
            pickSource(source.name, replacement)
            await { !vm.savedPhotos.loading && EditStore.savedPhoto(context, first.assetId)!!.uri == replacement.toString() }
            val after = EditStore.savedPhoto(context, first.assetId)!!
            check(after.variantId == first.variantId && EditStore.load(context, replacement)!!.brightness == 18f)
            check(vm.photos == listOf(replacement, b, offline) && vm.batchExportSelection == setOf(b, offline))
            check(vm.currentUri == b && vm.lastResult === native && vm.params.brightness == 9f)
            await { EditStore.savedSession(context)!!.photos == listOf(replacement, b, offline).map(Uri::toString) }
            await { after.assetId in vm.savedPhotos.thumbnails }
            capture("source-recovery-relinked.png")
            click("继续编辑：${moved.name}")
            idle()
            check(vm.currentUri == replacement && vm.params.brightness == 18f && vm.params.cropX == .1f && vm.params.cropW == .8f)
            test.runOnMainSync { vm.params.brightness = 29f; vm.scheduleGrade() }
            idle()
            test.runOnMainSync { vm.undo() }; idle(); check(vm.params.brightness == 18f)
            test.runOnMainSync { vm.redo() }; idle(); check(vm.params.brightness == 29f)
            check(EditStore.load(context, replacement)!!.brightness == 29f)
            check(runCatching { EditStore.load(context, a) }.isFailure)
            val automatic = vm.smartAutoEnabled
            try {
                test.runOnMainSync { vm.smartAutoEnabled = true; vm.openPhotos(listOf(a, b)) }
                await { !vm.busy && vm.errorMessage != null && !vm.smartImportRunning }
                check(vm.smartImportCompleted == 2 && EditStore.savedPhoto(context, first.assetId)!!.uri == replacement.toString())
                test.runOnMainSync { vm.dismissError(); vm.selectPhoto(1) }
                idle()
                check(vm.currentUri == b && vm.params.brightness == 9f)
            } finally { test.runOnMainSync { vm.smartAutoEnabled = automatic } }
            return "Real activity recreation retains ordered/offline slots, current photo, export checks and committed edits without startup decode; actual root restore/menu/confirm/cancel; OpenDocument result callback cancellation/wrong source/exact moved source; source ID/history/geometry preserved, workbench remapped, no render while relinking; later undo/redo and save use new URI. Picker results intercepted, not a cloud-vendor acceptance."
        } catch (error: Throwable) {
            runCatching { capture("source-recovery-failure.png") }
            throw error
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun start() {
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
    }

    private fun pickSource(name: String, result: Uri?) {
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = test.addMonitor(filter, Instrumentation.ActivityResult(
            if (result == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            result?.let { Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
        ), true)
        try {
            click("原片操作：$name"); click("重新关联原片")
            await { monitor.hits == 1 }
            await { vm.savedPhotos.pendingRelink == null }
        } finally { test.removeMonitor(monitor) }
    }

    private fun idle() = await {
        vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting && vm.rendered != null &&
            vm.renderTaskState.active == null && listOf("renderJob", "wbJob").none { name ->
                val field = EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(vm) as? Job)?.isActive == true
            }
    }

    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(label, node.getChild(index))?.let { return it }
        return null
    }
    private fun click(label: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(24) {
                find(label)?.let { found ->
                    found.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    test.waitForIdleSync(); SystemClock.sleep(100)
                    var node = find(label) ?: found
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (node.isVisibleToUser && bounds.height() > 0) {
                        while (!node.isClickable) node = node.parent ?: error(label)
                        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { label }
                        test.waitForIdleSync(); SystemClock.sleep(150)
                        return
                    }
                }
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isScrollable && node.performAction(direction)) return true
                    for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
                    return false
                }
                scroll(root()); SystemClock.sleep(100)
            }
        }
        error("Missing source-recovery action: $label")
    }
    private fun capture(name: String) {
        test.waitForIdleSync(); SystemClock.sleep(250)
        val image = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Source recovery timed out" }
            SystemClock.sleep(20)
        }
    }
}
