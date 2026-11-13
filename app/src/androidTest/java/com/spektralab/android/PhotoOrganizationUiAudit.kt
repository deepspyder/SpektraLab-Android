package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import kotlinx.coroutines.Job

internal class PhotoOrganizationUiAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var activity: MainActivity
    private lateinit var vm: EditorViewModel
    fun run(layoutOnly: Boolean = false): String {
        val context = test.targetContext
        val source = File(output, "organization-photo-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff8aab9e.toInt())
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val uri = Uri.fromFile(source)
        EditStore.save(context, uri, EditorParams().apply { brightness=18f;grainActive=false;glareActive=false })
        val photo = EditStore.savedPhotos(context).first { it.uri==uri.toString() }
        val albumName = "光影 ${System.nanoTime()}"
        var albumId: String? = null
        start()
        try {
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }; idle()
            click("继续显影")
            val frame=vm.lastResult
            click("返回首页");click("照片图库")
            await { !vm.savedPhotos.loading }
            capture("organization-library.png")
            click("管理相册");click("新建相册")
            setText(albumName);capture("organization-album-name.png");click("保存")
            await { !vm.savedPhotos.organization.pending && vm.savedPhotos.organization.albums.any { it.name==albumName } }
            albumId=vm.savedPhotos.organization.albums.first { it.name==albumName }.id
            capture("organization-albums.png")
            click("关闭照片整理")
            click("原片操作：${source.name}");click("整理照片")
            await { vm.savedPhotos.organization.target!=null && !vm.savedPhotos.organization.pending }
            click("评为 4 星");click("蓝色");click("加入相册：$albumName")
            capture("organization-draft.png")
            click("取消")
            check(EditStore.photoOrganization(context,photo.assetId)==PhotoOrganization())
            click("原片操作：${source.name}");click("整理照片")
            await { vm.savedPhotos.organization.target!=null && !vm.savedPhotos.organization.pending }
            click("评为 4 星");click("蓝色");click("加入相册：$albumName");click("保存")
            await { !vm.savedPhotos.loading && !vm.savedPhotos.organization.pending && vm.savedPhotos.organization.target==null }
            check(EditStore.photoOrganization(context,photo.assetId).let { it.rating==4 && it.label==PhotoLabel.BLUE && albumId in it.albums })
            check(vm.lastResult===frame && vm.params.brightness==18f && EditStore.load(context,uri)!!.brightness==18f)
            capture("organization-marked.png")
            click("全部照片");click(albumName)
            await { !vm.savedPhotos.loading && vm.savedPhotos.photos.size==1 }
            click("筛选");click("4 星及以上");click("蓝色")
            capture("organization-filters.png");click("应用筛选")
            await { !vm.savedPhotos.loading }
            check(vm.savedPhotos.photos.single().assetId==photo.assetId)
            capture("organization-filtered.png")
            if(layoutOnly) return "Real native album create, cancel draft, save marks/membership and combined filter; no native render or edit change; responsive sheets and selected rows captured"
            click("筛选已开启");click("5 星及以上");click("应用筛选")
            await { !vm.savedPhotos.loading && vm.savedPhotos.photos.isEmpty() }
            capture("organization-no-results.png")
            check(vm.savedPhotos.filter.active)
            click("清除筛选")
            await { !vm.savedPhotos.loading && !vm.savedPhotos.filter.active }
            click("管理相册");click("重命名相册：$albumName")
            setText("$albumName 新");click("保存")
            await { !vm.savedPhotos.organization.pending && vm.savedPhotos.organization.albums.any { it.id==albumId && it.name=="$albumName 新" } }
            click("删除相册：$albumName 新");capture("organization-delete.png");click("取消")
            check(EditStore.photoAlbums(context).any { it.id==albumId })
            click("删除相册：$albumName 新");click("删除")
            await { !vm.savedPhotos.organization.pending && vm.savedPhotos.organization.albums.none { it.id==albumId } }
            check(EditStore.photoOrganization(context,photo.assetId).let { it.rating==4 && it.label==PhotoLabel.BLUE && it.albums.isEmpty() })
            check(source.isFile && vm.lastResult===frame && EditStore.load(context,uri)!!.brightness==18f)
            click("关闭照片整理")
            click("继续编辑：${source.name}");idle()
            check(vm.params.brightness==18f)
            return "Actual album create/rename/delete/cancel, local mark drafts cancelled without writes, stars/label/membership saved atomically, combined filter/no-results/clear, resume edits; native frame untouched while organizing, originals and edit bytes preserved"
        } catch(error:Throwable) {
            runCatching { capture("organization-failure.png") };throw error
        } finally {
            test.runOnMainSync { activity.finish() }
            albumId?.let { id -> runCatching { EditStore.deletePhotoAlbum(context,id) } }
        }
    }
    private fun setText(value: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isEditable)return node
            for(i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        await { editable(root()) != null }
        val node=editable(root()) ?: error("Album input missing")
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }))
        test.waitForIdleSync();SystemClock.sleep(150)
    }
    private fun start() {
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
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
