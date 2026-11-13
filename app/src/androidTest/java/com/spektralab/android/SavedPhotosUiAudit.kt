package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.gallery.SavedPhotosController
import com.spektralab.android.feature.gallery.SavedPhotosScreen
import com.spektralab.android.ui.theme.TheiaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

internal class SavedPhotosUiAudit(private val test:Instrumentation,private val output:File) {
    fun run(layoutOnly:Boolean=false):String {
        val context=test.targetContext
        val source=File(output,"saved-gallery-colour.png")
        val missing=File(output,"saved-gallery-missing.png")
        val bitmap=Bitmap.createBitmap(512,384,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(512*384){i->0xff000000.toInt() or ((i%512/2) shl 16) or ((i/512*255/384) shl 8) or 90},0,512,0,0,512,384)
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val uri=Uri.fromFile(source)
        // A no-op save intentionally keeps its old list position. Promote this owned fixture
        // with a real edit so repeated runs remain independent of other suites' history.
        EditStore.save(context,Uri.fromFile(missing),EditorParams().apply{brightness=8f})
        EditStore.save(context,Uri.fromFile(missing),EditorParams().apply{brightness=7f})
        EditStore.save(context,uri,EditorParams().apply{brightness=18f;grainActive=false;glareActive=false})
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        try {
            await{vm.engine!=null && !vm.busy}
            await{find("照片图库")!=null}
            capture("saved-photos-home.png")
            click("照片图库")
            await{find("继续编辑：saved-gallery-colour.png")!=null && !vm.savedPhotos.loading}
            await{vm.savedPhotos.photos.firstOrNull{it.uri==uri.toString()}?.assetId in vm.savedPhotos.thumbnails}
            capture("saved-photos-list.png")
            check(vm.lastResult==null && !vm.hasPhoto)
            click("继续编辑：saved-gallery-missing.png")
            await{vm.savedPhotos.sourceErrors.isNotEmpty()}
            reveal("继续编辑：saved-gallery-missing.png")
            capture("saved-photos-missing.png")
            check(EditStore.load(context,Uri.fromFile(missing))!!.brightness==7f)
            check(!vm.hasPhoto && vm.errorMessage==null)
            if(layoutOnly)return "Home entry, saved rows, missing-source retained and readable layout; gallery browsing starts no engine render"
            click("继续编辑：saved-gallery-colour.png")
            idle(vm)
            check(vm.currentUri==uri && vm.params.brightness==18f)
            val frame=vm.lastResult
            test.runOnMainSync{vm.params.brightness=29f;vm.scheduleGrade()}
            idle(vm);check(vm.lastResult===frame)
            test.runOnMainSync{vm.undo()};idle(vm);check(vm.params.brightness==18f)
            test.runOnMainSync{vm.redo()};idle(vm);check(vm.params.brightness==29f)
            click("返回首页")
            click("照片图库")
            await{!vm.savedPhotos.loading && find("继续编辑：saved-gallery-colour.png")!=null}
            val before=vm.lastResult
            click("刷新照片图库")
            await{!vm.savedPhotos.loading}
            check(vm.lastResult===before && vm.params.brightness==29f)
            capture("saved-photos-return.png")
            click("继续编辑：saved-gallery-colour.png")
            idle(vm);check(vm.params.brightness==29f)
            emptyState(activity)
            return "Home saved-gallery real entry/open/refresh/back/reopen; missing original retains edits and doesn't decode; grade-only changes preserve native frame; undo/redo and DB-backed latest adjustment recovered"
        }catch(error:Throwable){runCatching{capture("saved-photos-failure.png")};throw error}
        finally{test.runOnMainSync{activity.finish()}}
    }

    private fun emptyState(activity: MainActivity) {
        val directory = File(output, "saved-empty-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(test.targetContext) {
            override fun getFilesDir() = directory
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SavedPhotosController(isolated, scope)
        var imported = false
        var returned = false
        try {
            test.runOnMainSync {
                activity.setContent {
                    TheiaTheme {
                        SavedPhotosScreen(controller, true, { returned = true }, { imported = true }, { error("Empty library opened a source") })
                    }
                }
            }
            val emptyLabel = test.targetContext.getString(R.string.photos_empty)
            await { !controller.loading && find(emptyLabel) != null }
            capture("saved-photos-empty.png")
            click(test.targetContext.getString(R.string.photos_import))
            check(imported)
            click(test.targetContext.getString(R.string.photos_back))
            check(returned && controller.photos.isEmpty())
        } finally {
            scope.cancel()
            EditStore.closeForTests(isolated)
        }
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
        capture("saved-photos-failure.png")
        error("Missing saved-photo control: $label")
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
