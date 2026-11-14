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
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.EditorScreen
import com.spektralab.android.feature.library.FilmStockScreen
import com.spektralab.android.feature.lighttable.rememberFilmCanvasState
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/** Actual production editor and stock picker, mounted with owned photo fixtures. */
internal class CineStillUiAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var vm: EditorViewModel
    private val context get() = test.targetContext
    private fun main(action: () -> Unit) = test.runOnMainSync { action() }
    private fun fixture(name: String, salt: Int): Uri {
        val file=File(output,name)
        val bitmap=CineStillAudit.fixture(salt=salt)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
        val uri=Uri.fromFile(file)
        EditStore.save(context,uri,EditorParams().apply { grainActive=false;glareActive=false;autoExposure=false })
        return uri
    }

    fun run(layoutOnly: Boolean): String {
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val created=mutableListOf<Uri>()
        main { vm=ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            await("engine") { vm.engine!=null && !vm.busy }
            val first=fixture("f14-owned-night.png",0)
            val second=fixture("f14-owned-colour.png",8)
            main { vm.openPhotos(listOf(first)) };idle()
            var library by mutableStateOf(true)
            main { activity.setContent { TheiaTheme {
                val canvas=rememberFilmCanvasState()
                if(library) FilmStockScreen(vm,onBack={library=false})
                else EditorScreen(vm,{}, {library=true}, {library=true},canvas,0)
            } } }
            click(FilmFilter.Cinema.label)
            reveal("CineStill 800T",strip=true)
            click("CineStill 800T")
            idle()
            check(vm.params.filmId=="cinestill_800t" && vm.params.halationModelVersion==2)
            capture("f14-catalogue.png")
            click("应用胶片与相纸")
            click(EditorTab.Print.label)
            click("乳剂与暗房效应")
            reveal(context.getString(R.string.halation_headroom),range=true)
            var backing=reveal(context.getString(R.string.halation_keep_backing))
            while (!backing.isCheckable) backing=checkNotNull(backing.parent) { "Missing backing switch semantics" }
            check(!backing.isEnabled && !backing.isChecked) { "CineStill incorrectly offers a retained backing" }
            captureFocused("f14-no-backing.png", context.getString(R.string.halation_keep_backing))
            captureFocused("f14-model-choice.png", context.getString(R.string.halation_back_reflection))
            reveal(context.getString(R.string.halation_headroom),range=true)
            capture("f14-back-reflection.png")
            reveal(context.getString(R.string.halation_headroom_hint))
            capture("f14-headroom-help.png")
            captureFocused("f14-headroom-control.png", context.getString(R.string.halation_headroom))
            captureFocused("f14-headroom-explanation.png", context.getString(R.string.halation_headroom_hint))
            val range=reveal(context.getString(R.string.halation_headroom),range=true)
            check(range.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,6f) }))
            idle();check(vm.params.halationHeadroomEv==6f)
            check(EditStore.load(context,first)!!.halationHeadroomEv==6f)
            main { vm.undo() };idle();check(vm.params.halationHeadroomEv==4f)
            main { vm.redo() };idle();check(vm.params.halationHeadroomEv==6f)
            click(context.getString(R.string.halation_classic));idle()
            check(vm.params.halationModelVersion==1 && vm.params.halationHeadroomEv==6f)
            check(find(context.getString(R.string.halation_headroom),range=true)==null)
            reveal(context.getString(R.string.halation_classic))
            capture("f14-classic.png")
            click(context.getString(R.string.halation_back_reflection));idle()
            check(vm.params.halationModelVersion==2 && vm.params.halationHeadroomEv==6f)
            click(context.getString(R.string.halation_enabled));idle()
            check(!vm.params.halationActive)
            reveal(context.getString(R.string.halation_headroom),range=true)
            check(!find(context.getString(R.string.halation_headroom),range=true)!!.isEnabled)
            capture("f14-disabled.png")
            captureFocused("f14-disabled-model.png", context.getString(R.string.halation_back_reflection))
            if(layoutOnly) return "Production stock picker and editor: CineStill auto-opt-in, no-backing truth, live headroom, undo/redo, classic roundtrip and disabled state"
            click(context.getString(R.string.halation_enabled));idle()
            main { vm.addPhotos(listOf(second),openSelected=true) };idle()
            check(vm.params.halationModelVersion==1 && vm.currentUri==second)
            main { vm.selectPhoto(0) };idle()
            check(vm.params.halationModelVersion==2 && vm.params.halationHeadroomEv==6f)
            val template=vm.captureEditTemplate(EditGroup.DEFAULT)
            val transfer=runBlocking { vm.previewEditTransfer(template,listOf(second)) }
            main { vm.applyEditTransfer(transfer) }
            await("transfer") { !vm.isApplyingEdits };idle()
            check(EditStore.load(context,second)!!.let { it.halationModelVersion==2 && it.halationHeadroomEv==6f })
            for(format in listOf("PNG","JPEG","TIFF")) {
                val single=linkedMapOf<String,String>()
                for(index in 0..1) {
                    main { vm.selectPhoto(index) };idle()
                    val before=published()
                    main { vm.exportFormat=format;vm.exportRes=0;vm.exportColorSpace="sRGB";vm.jpegQuality=100;vm.export() }
                    await("single $format") { !vm.isExporting && !vm.busy }
                    check(vm.errorMessage==null) { vm.errorMessage.orEmpty() }
                    val uri=(published()-before).single();created+=uri
                    single[vm.currentUri.toString()]=rasterHash(uri,format)
                }
                val before=published()
                main { vm.selectAllBatchExportPhotos(true);vm.batchExport() }
                await("batch $format") { !vm.isBatchExporting && !vm.busy }
                check(vm.errorMessage==null) { vm.errorMessage.orEmpty() }
                val batch=(published()-before).also { created+=it }
                check(batch.size==2)
                check(batch.map { rasterHash(it,format) }.sorted()==single.values.sorted()) { "$format batch changed CineStill pixels or selected the wrong version" }
            }
            main { vm.selectPhoto(0) };idle()
            check(vm.params.halationModelVersion==2 && vm.params.halationHeadroomEv==6f)
            return "CineStill actual card selection; physical headroom and history; no-backing/disabled/classic truth; per-photo reopen and group transfer; PNG/JPEG100/TIFF16 single and batch pixel-exact, full source dimensions retained"
        } catch(failure:Throwable) {
            File(output,"f14-ui-failure.txt").writeText(failure.stackTraceToString())
            runCatching { capture("f14-ui-failure.png") };throw failure
        } finally {
            created.forEach { runCatching { context.contentResolver.delete(it,null,null) } }
            main { activity.finish() }
        }
    }

    fun raw(): String {
        val original=File(output,"perf-z6ii.NEF");check(original.isFile)
        val source=File(output,"f14-owned-z6ii.NEF").also { original.copyTo(it,overwrite=true) }
        val uri=Uri.fromFile(source)
        EditStore.save(context,uri,EditorParams().apply {
            filmId="cinestill_800t";paperId="kodak_2383";halationModelVersion=2;halationHeadroomEv=4f
            grainActive=false;glareActive=false
        })
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val created=mutableListOf<Uri>();val hashes=mutableListOf<String>();val times=mutableListOf<Long>();var totalBytes=0L
        main { vm=ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            await("engine") { vm.engine!=null && !vm.busy }
            main { vm.openPhotos(listOf(uri)) };idle()
            repeat(2) {
                val before=published();val start=SystemClock.elapsedRealtime()
                main { vm.exportFormat="TIFF";vm.exportRes=0;vm.exportColorSpace="sRGB";vm.export() }
                await("24MP CineStill") { !vm.isExporting && !vm.busy }
                check(vm.errorMessage==null) { vm.errorMessage.orEmpty() }
                val file=(published()-before).single();created+=file
                val digest=MessageDigest.getInstance("SHA-256")
                totalBytes=0L
                context.contentResolver.openInputStream(file)!!.use { stream ->
                    val chunk=ByteArray(64*1024)
                    while(true) { val n=stream.read(chunk);if(n<0)break;digest.update(chunk,0,n);totalBytes+=n }
                }
                hashes+=digest.digest().joinToString("") { b->"%02x".format(b) };times+=SystemClock.elapsedRealtime()-start
                verifyFullTiff(file)
            }
            check(hashes[0]==hashes[1]) { "Cached full-size CineStill output changed pixels" }
            check(vm.params.filmId=="cinestill_800t" && vm.params.halationModelVersion==2 && vm.currentIsRaw)
            File(output,"f14-raw-report.json").writeText(org.json.JSONObject().put("sha256",hashes.first())
                .put("times_ms",org.json.JSONArray(times)).put("bytes",totalBytes).put("width",6064).put("height",4040).toString(2))
            return "6064x4040 CineStill back-reflection/headroom RAW TIFF16; $totalBytes bytes; first and cached SHA256 ${hashes.first()} identical; timings $times; grain/glare disabled for deterministic optics"
        } finally { created.forEach { runCatching { context.contentResolver.delete(it,null,null) } };main { activity.finish() } }
    }

    private fun verifyFullTiff(uri:Uri) {
        context.contentResolver.openFileDescriptor(uri,"r")!!.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
                val channel=stream.channel
                fun read(at:Long,count:Int):ByteBuffer {
                    val buffer=ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
                    channel.position(at)
                    while(buffer.hasRemaining())check(channel.read(buffer)>0)
                    buffer.flip();return buffer
                }
                val header=read(0,8)
                check(header.getShort(0).toInt()==0x4949 && header.getShort(2).toInt()==42)
                val offset=header.getInt(4).toLong() and 0xffffffffL
                val count=read(offset,2).getShort(0).toInt() and 65535
                val directory=read(offset+2,count*12)
                val entries=(0 until count).associate { i->(directory.getShort(i*12).toInt() and 65535) to i*12 }
                fun values(tag:Int):List<Long> {
                    val at=entries[tag]!!;val type=directory.getShort(at+2).toInt();val n=directory.getInt(at+4)
                    val bytes=if(type==3)2 else 4
                    val data=if(n*bytes<=4)directory.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(at+8) }.slice().order(ByteOrder.LITTLE_ENDIAN)
                        else read(directory.getInt(at+8).toLong() and 0xffffffffL,n*bytes)
                    return (0 until n).map { if(type==3)(data.getShort(it*bytes).toInt() and 65535).toLong() else data.getInt(it*bytes).toLong() and 0xffffffffL }
                }
                check(values(256).single()==6064L && values(257).single()==4040L)
                check(values(258)==listOf(16L,16L,16L) && values(259).single()==1L && values(277).single()==3L)
                check(values(279).sum()==6064L*4040L*6L) { "Full-resolution TIFF raster was truncated" }
            }
        }
    }

    private fun rasterHash(uri:Uri,format:String):String {
        val bytes=context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val digest=MessageDigest.getInstance("SHA-256")
        if(format=="TIFF") {
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(b.getShort(0).toInt()==0x4949 && b.getShort(2).toInt()==42)
            val ifd=b.getInt(4);val count=b.getShort(ifd).toInt() and 65535
            val entries=(0 until count).associate { i->val at=ifd+2+i*12;(b.getShort(at).toInt() and 65535) to at }
            fun values(tag:Int):List<Int> {
                val at=entries[tag]!!;val type=b.getShort(at+2).toInt();val n=b.getInt(at+4)
                val size=if(type==3)2 else 4;val offset=if(n*size<=4)at+8 else b.getInt(at+8)
                return (0 until n).map { if(type==3)b.getShort(offset+it*size).toInt() and 65535 else b.getInt(offset+it*size) }
            }
            check(values(256).single()==600 && values(257).single()==400)
            check(values(258)==listOf(16,16,16) && values(259).single()==1)
            values(273).zip(values(279)).forEach { (offset,length)->digest.update(bytes,offset,length) }
        } else {
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Output did not decode")
            try {
                check(bitmap.width==600 && bitmap.height==400)
                val data=ByteBuffer.allocate(bitmap.byteCount);bitmap.copyPixelsToBuffer(data);digest.update(data.array())
            } finally { bitmap.recycle() }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun published():Set<Uri> = buildSet {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.IS_PENDING}=0",null,null)?.use { c->while(c.moveToNext())add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,c.getLong(0))) }
    }
    private fun root():AccessibilityNodeInfo? { test.uiAutomation.clearCache();return test.uiAutomation.rootInActiveWindow }
    private fun find(label:String,node:AccessibilityNodeInfo?=root(),range:Boolean=false):AccessibilityNodeInfo? {
        if(node==null)return null
        if((node.text?.toString()==label || node.contentDescription?.toString()==label)&&(!range || node.rangeInfo!=null))return node
        for(i in 0 until node.childCount)find(label,node.getChild(i),range)?.let { return it }
        return null
    }
    private fun reveal(label:String,range:Boolean=false,strip:Boolean=false):AccessibilityNodeInfo {
        for(direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) repeat(24) {
            find(label,range=range)?.let { node ->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync();SystemClock.sleep(80)
                val current=find(label,range=range);val bounds=Rect().also { current?.getBoundsInScreen(it) }
                if(current?.isVisibleToUser==true && bounds.height()>0)return current
            }
            fun scroll(node:AccessibilityNodeInfo?):Boolean {
                if(node==null)return false
                if(node.isScrollable && node.performAction(direction))return true
                for(i in 0 until node.childCount)if(scroll(node.getChild(i)))return true
                return false
            }
            if (strip) find("胶片列表")?.performAction(direction) else {
                // In landscape the photo canvas precedes the parameter scroller.
                // Scope to the actual control's ancestors instead of panning the image.
                var anchor = listOf("乳剂与暗房效应", context.getString(R.string.halation_model),
                    context.getString(R.string.halation_enabled), context.getString(R.string.halation_classic),
                    context.getString(R.string.halation_headroom_hint)).firstNotNullOfOrNull { find(it) }
                var moved = false
                while (anchor != null && !moved) {
                    if (anchor.isScrollable) moved = anchor.performAction(direction)
                    anchor = anchor.parent
                }
                if (!moved) scroll(root())
            }
            SystemClock.sleep(80)
        }
        error("Missing F14 control: $label")
    }
    private fun click(label:String) {
        var node=reveal(label)
        while(!node.isClickable)node=node.parent ?: error(label)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { label }
        test.waitForIdleSync();SystemClock.sleep(180)
    }
    private fun capture(name:String) {
        test.waitForIdleSync();SystemClock.sleep(350)
        val bitmap=test.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(output,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    /** Centre one real control in a short scroller; a minimum bring-into-view
     * can leave a label clipped by the material strip after its animation. */
    private fun captureFocused(name: String, label: String) {
        repeat(3) {
            val node = reveal(label)
            SystemClock.sleep(400)
            var scroll: AccessibilityNodeInfo? = find(label)?.parent
            while (scroll != null && !scroll.isScrollable) scroll = scroll.parent
            if (scroll == null) return@repeat
            val viewport = Rect().also(scroll::getBoundsInScreen)
            val bounds = Rect().also { (find(label) ?: node).getBoundsInScreen(it) }
            val delta = bounds.centerY() - viewport.centerY()
            if (kotlin.math.abs(delta) < 12) return@repeat
            val startY = viewport.centerY().toFloat()
            val endY = (startY - delta).coerceIn(viewport.top + 8f, viewport.bottom - 8f)
            val x = viewport.right - 18f
            val down = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val time = down + step * 20L
                val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN
                    12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val event = android.view.MotionEvent.obtain(down, time, action, x, startY + (endY - startY) * step / 12f, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            test.waitForIdleSync(); SystemClock.sleep(500)
        }
        capture(name)
    }
    private fun idle()=await("editor idle") {
        vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting && !vm.isApplyingEdits &&
            vm.lastResult!=null && vm.renderTaskState.active==null &&
            listOf("renderJob","wbJob","frameRenderJob","cameraWbJob").none { name ->
                (EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible=true }.get(vm) as? Job)?.isActive==true
            }
    }
    private fun await(label:String,condition:()->Boolean) {
        val start=SystemClock.elapsedRealtime()
        while(!condition()) { check(SystemClock.elapsedRealtime()-start<120000) { "F14 timeout: $label" };SystemClock.sleep(15) }
        test.waitForIdleSync()
    }
}
