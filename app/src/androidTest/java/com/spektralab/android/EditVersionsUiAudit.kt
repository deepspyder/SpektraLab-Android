package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import kotlinx.coroutines.Job

internal class EditVersionsUiAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var activity: MainActivity
    private lateinit var vm: EditorViewModel
    fun run(layoutOnly: Boolean = false): String {
        val context=test.targetContext
        val source=File(output,"versions-source-${System.nanoTime()}.png")
        val bitmap=Bitmap.createBitmap(384,256,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(384*256){i->0xff000000.toInt() or ((i%384*255/384) shl 16) or ((i/384) shl 8) or 90},0,384,0,0,384,256)
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val uri=Uri.fromFile(source)
        val sourceBytes=source.readBytes()
        EditStore.save(context,uri,EditorParams().apply{brightness=18f;grainActive=false;glareActive=false})
        val photo=EditStore.savedPhotos(context).first{it.uri==uri.toString()}
        val name="暖调"
        val renamed="金色时刻"
        start()
        try {
            test.runOnMainSync{vm.openPhotos(listOf(uri))};idle()
            click("继续显影");val original=vm.lastResult
            click("返回首页");click("照片图库")
            await{!vm.savedPhotos.loading}
            click("原片操作：${source.name}");click("编辑版本")
            await{vm.savedPhotos.versions.photo!=null && !vm.savedPhotos.versions.pending}
            capture("versions-list.png")
            click("新建虚拟副本");setText(name);capture("versions-create.png");click("取消")
            check(EditStore.editVersions(context,photo.assetId).size==1)
            click("新建虚拟副本");setText(name);click("保存")
            await{!vm.savedPhotos.versions.pending && vm.savedPhotos.versions.versions.size==2}
            val copy=EditStore.editVersions(context,photo.assetId).first{!it.isDefault}
            check(vm.lastResult===original && source.readBytes().contentEquals(sourceBytes))
            capture("versions-created.png")
            click("版本操作：$name");click("重命名版本");setText(renamed);capture("versions-rename.png");click("保存")
            await{!vm.savedPhotos.versions.pending && vm.savedPhotos.versions.versions.any{it.name==renamed}}
            check(EditStore.editVersions(context,photo.assetId).first{!it.isDefault}.editUri==copy.editUri)
            if(layoutOnly)return "Native edit version list/create cancel/create/rename; no source copy or render while managing; default+virtual identities; readable native controls across device profiles"
            click(renamed);idle()
            check(vm.photos==listOf(uri,copy.editUri) && vm.currentUri==copy.editUri && vm.params.brightness==18f)
            context.contentResolver.openInputStream(copy.editUri)!!.use{check(it.readBytes().contentEquals(sourceBytes))}
            context.contentResolver.openFileDescriptor(copy.editUri,"r")!!.use{check(it.statSize==sourceBytes.size.toLong())}
            check(runCatching{context.contentResolver.openOutputStream(copy.editUri)}.isFailure)
            val provider = context.packageManager.resolveContentProvider(EditVersionUri.AUTHORITY, 0)!!
            check(!provider.exported && !provider.grantUriPermissions)
            test.runOnMainSync{vm.params.brightness=29f;vm.scheduleGrade()};idle()
            check(EditStore.load(context,uri)!!.brightness==18f && EditStore.load(context,copy.editUri)!!.brightness==29f)
            test.runOnMainSync{vm.undo()};idle();check(vm.params.brightness==18f)
            test.runOnMainSync{vm.redo()};idle();check(vm.params.brightness==29f)
            test.runOnMainSync{vm.selectPhoto(0)};idle();check(vm.params.brightness==18f)
            test.runOnMainSync{vm.selectPhoto(1)};idle();check(vm.params.brightness==29f)
            capture("versions-editor.png")
            await{EditStore.savedSession(context)?.photos==listOf(uri,copy.editUri).map(Uri::toString)}
            val collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            fun ids():Set<Long> = buildSet{context.contentResolver.query(collection,arrayOf(MediaStore.Images.Media._ID),null,null,null)?.use{while(it.moveToNext())add(it.getLong(0))}}
            fun pixels(id:Long):IntArray{
                val b=context.contentResolver.openInputStream(ContentUris.withAppendedId(collection,id))!!.use{BitmapFactory.decodeStream(it)}!!
                check(b.width==384&&b.height==256)
                return IntArray(384*256).also{b.getPixels(it,0,384,0,0,384,256);b.recycle()}
            }
            test.runOnMainSync{vm.exportFormat="PNG";vm.exportRes=0;vm.exportColorSpace="sRGB";vm.exportKeepCapture=false;vm.exportKeepLocation=false}
            val before=ids()
            try{
                test.runOnMainSync{vm.export()};idle();check(vm.errorMessage==null)
                val copyPixels=pixels((ids()-before).single())
                val seen=ids()
                test.runOnMainSync{vm.selectPhoto(0)};idle()
                test.runOnMainSync{vm.export()};idle();check(vm.errorMessage==null)
                val defaultPixels=pixels((ids()-seen).single())
                check(!copyPixels.contentEquals(defaultPixels))
                test.runOnMainSync{vm.selectAllBatchExportPhotos(true)}
                val batchSeen=ids();test.runOnMainSync{vm.batchExport()};idle()
                val batch=(ids()-batchSeen).map(::pixels)
                check(batch.size==2 && batch.any{it.contentEquals(copyPixels)} && batch.any{it.contentEquals(defaultPixels)})
                for(format in listOf("JPEG","TIFF")){
                    val previous=ids();test.runOnMainSync{vm.exportFormat=format;vm.batchExport()};idle();check(vm.errorMessage==null && (ids()-previous).size==2)
                }
            } finally{(ids()-before).forEach{context.contentResolver.delete(ContentUris.withAppendedId(collection,it),null,null)}}
            // Direct grouped transfer uses private edit references as independent CAS targets.
            val beforeDefault=EditStore.loadForTransfer(context,uri)
            val beforeCopy=EditStore.loadForTransfer(context,copy.editUri)
            EditStore.saveBatch(context,listOf(EditStore.BatchEdit(copy.editUri,beforeCopy.json,
                beforeCopy.params.copy().apply{brightness=31f}.toJson().toString())))
            check(EditStore.loadForTransfer(context,uri).json==beforeDefault.json)
            test.runOnMainSync {
                vm.selectAllBatchExportPhotos(false)
                vm.setBatchExportPhotoSelected(copy.editUri,true)
            }
            await { EditStore.savedSession(context)?.let { it.photos==listOf(uri,copy.editUri).map(Uri::toString) &&
                it.selected==setOf(copy.editUri.toString()) } == true }
            test.runOnMainSync { activity.finish() };test.waitForIdleSync()
            start()
            await { find("照片图库")!=null }
            check(vm.photos.isEmpty() && !vm.hasPhoto)
            click("照片图库");await { !vm.savedPhotos.loading }
            click("恢复工作台");idle()
            check(vm.photos==listOf(uri,copy.editUri) && vm.currentUri==uri && vm.batchExportSelection==setOf(copy.editUri))
            test.runOnMainSync { vm.selectPhoto(1) };idle();check(vm.params.brightness==31f)
            test.runOnMainSync { vm.selectPhoto(0) };idle();check(vm.params.brightness==18f)
            click("返回首页");click("照片图库");await{!vm.savedPhotos.loading}
            click("原片操作：${source.name}");click("编辑版本");await{!vm.savedPhotos.versions.pending}
            click("版本操作：$renamed");click("删除虚拟副本");capture("versions-delete.png");click("删除")
            await{!vm.savedPhotos.versions.pending && vm.savedPhotos.versions.error!=null}
            check(EditStore.editVersions(context,photo.assetId).size==2)
            capture("versions-in-use.png");click("取消")
            test.runOnMainSync{vm.removePhotoFromSession(copy.editUri)};idle()
            await{EditStore.savedSession(context)?.photos==listOf(uri.toString())}
            click("关闭编辑版本")
            click("原片操作：${source.name}");click("编辑版本");await{!vm.savedPhotos.versions.pending}
            click("版本操作：$renamed");click("删除虚拟副本");click("删除")
            await{!vm.savedPhotos.versions.pending && vm.savedPhotos.versions.versions.size==1}
            check(runCatching{EditStore.load(context,copy.editUri)}.isFailure)
            check(source.readBytes().contentEquals(sourceBytes) && EditStore.load(context,uri)!!.brightness==18f)
            capture("versions-deleted.png")
            return "Real create/cancel/rename/open virtual version joins source and copy in one workbench; independent save/switch/undo/redo and selection; private provider exact input/descriptor and write rejection; actual full-size PNG distinct per-version output, both batch PNGs pixel-exact to single references, JPEG/TIFF batch; grouped transfer isolates default, in-use deletion refusal then safe delete, source/default preserved"
        }catch(error:Throwable){runCatching{capture("versions-failure.png")};throw error}
        finally{test.runOnMainSync{activity.finish()}}
    }
    private fun setText(value: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isEditable)return node
            for(i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
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
