package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportRecipe
import com.spektralab.android.data.export.ExportMetadata
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.Grade
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import kotlin.math.abs

/** Real gestures and independently read output files, not an implementation-mirroring unit test. */
internal class EditingFeedbackAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var vm: EditorViewModel
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 30000) { "Timeout: $label; ${vm.errorMessage}" }; Thread.sleep(50) }
    }
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        var node = checkNotNull(find(label))
        while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled: $label" }
        test.waitForIdleSync(); Thread.sleep(350)
    }
    private fun reveal(label: String) {
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
            return false
        }
        repeat(20) {
            find(label)?.let { node ->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync()
                if (find(label)?.isVisibleToUser == true) return
            }
            check(scroll(root())) { "Cannot reveal $label" }
            test.waitForIdleSync(); Thread.sleep(100)
        }
        error("Missing control: $label")
    }
    private fun capture(name: String) {
        test.waitForIdleSync(); test.uiAutomation.waitForIdle(200, 5000)
        checkNotNull(test.uiAutomation.takeScreenshot()).also { bitmap ->
            File(output, "feedback_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun drag(x: Float, y: Float, dx: Float, dy: Float) {
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(test.uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..20) { event(MotionEvent.ACTION_MOVE, x + dx * i / 20, y + dy * i / 20); Thread.sleep(20) }
        event(MotionEvent.ACTION_UP, x + dx, y + dy); Thread.sleep(300)
    }
    private fun picture(name: String, make: String, latitude: Double): File {
        val file = File(output, name)
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(480 * 320) { i -> Color.rgb(70 + i % 480 / 3, 50 + i / 480 / 2, 90) }, 0, 480, 0, 0, 480, 320)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) }; bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, make); setAttribute(ExifInterface.TAG_MODEL, "Synthetic audit camera")
            setAttribute(ExifInterface.TAG_LENS_MODEL, "Audit 35mm")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2025:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008"); setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
            setLatLong(latitude, 116.4); setAltitude(35.5); saveAttributes()
        }
        return file
    }
    private fun checkMetadata(exif: ExifInterface, capture: Boolean, location: Boolean, make: String = "Audit A", latitude: Double = 39.9) {
        check((exif.getAttribute(ExifInterface.TAG_MAKE) != null) == capture) { "Capture flag not respected: ${exif.getAttribute(ExifInterface.TAG_MAKE)}" }
        check((exif.latLong != null) == location) { "GPS flag not respected: ${exif.latLong?.toList()}" }
        if (capture) {
            check(exif.getAttribute(ExifInterface.TAG_MAKE) == make)
            check(exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0) == 200)
            check(abs(exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0) - 1.0 / 125) < .0001)
        }
        if (location) check(abs(exif.latLong!![0] - latitude) < .0001)
        check(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in listOf(0, 1))
    }
    private fun directMetadata(source: File) {
        val rgba = ShortArray(32 * 24 * 4) { if (it % 4 == 3) (-1).toShort() else 32000.toShort() }
        for (format in listOf("JPEG", "PNG", "TIFF")) for (capture in listOf(false, true)) for (gps in listOf(false, true)) {
            val meta = ExportMetadata.from(ExifInterface(source), capture, gps)
            val file = File(output, "metadata_${format}_${capture}_$gps.${format.lowercase()}")
            if (format == "TIFF") file.outputStream().use { TiffWriter.writeRgba16(rgba, 32, 24, null, null, it, metadata = meta) }
            else {
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                file.outputStream().use { check(bitmap.compress(if (format == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)) }
                bitmap.recycle(); meta.attach(file, 32, 24)
            }
            try { checkMetadata(ExifInterface(file), capture, gps) }
            catch (error: Throwable) { throw IllegalStateException("$format capture=$capture GPS=$gps: ${error.stackTraceToString()}", error) }
        }
    }
    private fun vignetteMath() {
        fun grade(amount: Float) = Grade(1f,1f,1f,1f,0f,0f,1f,0f,0f,0f,0f,
            FloatArray(3),FloatArray(3),FloatArray(3),amount,.5f,.7f)
        val rgba = ShortArray(80 * 60 * 4) { if (it % 4 == 3) (-1).toShort() else 40000.toShort() }
        val plain = ColorPipeline.applyGrade(rgba,80,60,grade(0f))
        val shaded = ColorPipeline.applyGrade(rgba,80,60,grade(.8f))
        check(plain[30*80+40] == shaded[30*80+40]) { "Vignette altered centre" }
        check(Color.red(shaded[0]) < Color.red(plain[0]) - 30)
        check(abs(Color.red(shaded[0])-Color.red(shaded.last())) <= 1)
        val tiff = File(output,"vignette_parity.tiff")
        tiff.outputStream().use { TiffWriter.writeRgba16(rgba,80,60,grade(.8f),null,it) }
        val bytes=tiff.readBytes(); val b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val ifd=b.getInt(4);val count=b.getShort(ifd).toInt() and 65535
        val pixelOffset=(0 until count).map { ifd+2+it*12 }.first { (b.getShort(it).toInt() and 65535)==273 }.let { b.getInt(it+8) }
        for (index in listOf(0,40,30*80+40,80*60-1)) {
            val tiffRed=(b.getShort(pixelOffset+index*6).toInt() and 65535)/257
            check(abs(tiffRed-Color.red(shaded[index])) <= 1) { "TIFF vignette differs from preview" }
        }
    }
    private fun outputs(): List<Uri> = buildList {
        test.targetContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("Theia_%"), null)?.use {
            while(it.moveToNext()) add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it.getLong(0)))
        }
    }
    fun run(): String {
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm=ViewModelProvider(activity)[EditorViewModel::class.java] }
        val oldCapture=vm.exportKeepCapture; val oldGps=vm.exportKeepLocation
        val originalOutputs=outputs().toSet()
        try {
            await("engine") { vm.engine!=null && !vm.busy }
            val source=picture("feedback-source.jpg","Audit A",39.9)
            directMetadata(source); vignetteMath()
            val uri=Uri.fromFile(source)
            EditStore.save(test.targetContext,uri,EditorParams())
            main { vm.openPhotos(listOf(uri)) }
            await("open") { vm.hasPhoto && !vm.busy }
            click("继续显影")
            main { vm.beginCrop(); vm.params.cropX=.2f;vm.params.cropY=.2f;vm.params.cropW=.5f;vm.params.cropH=.5f }
            await("crop") { find("完成裁剪")!=null }
            fun display(): Rect {
                await("crop preview visible") { find("照片预览")!=null }
                val box=Rect().also { checkNotNull(find("照片预览")).getBoundsInScreen(it) }
                val aspect=vm.sourceBitmap!!.width.toFloat()/vm.sourceBitmap!!.height
                val w=minOf(box.width().toFloat(),box.height()*aspect);val h=w/aspect
                return Rect((box.centerX()-w/2).toInt(),(box.centerY()-h/2).toInt(),(box.centerX()+w/2).toInt(),(box.centerY()+h/2).toInt())
            }
            var d=display();val startX=vm.params.cropX
            drag(d.left+d.width()*.45f,d.top+d.height()*.45f,d.width()*.16f,d.height()*.1f)
            check(vm.params.cropX > startX+.09f) { "Crop move did not accumulate: ${vm.params.cropX}" }
            capture("crop_free_moved")
            click("比例");click("2.39:1")
            d=display();val oldY=vm.params.cropY
            drag(d.exactCenterX(),d.exactCenterY(),0f,d.height()*.12f)
            check(vm.params.cropY > oldY+.06f) { "Locked preset stayed centred" }
            val oldW=vm.params.cropW
            val inside=4*activity.resources.displayMetrics.density
            drag(d.left+(vm.params.cropX+vm.params.cropW)*d.width()-inside,d.top+(vm.params.cropY+vm.params.cropH)*d.height()-inside,-d.width()*.12f,-d.height()*.08f)
            check(vm.params.cropW<oldW-.04f) { "Corner resize did not shrink: $oldW -> ${vm.params.cropW}" }
            check(abs(vm.params.cropW/vm.params.cropH*1.5f-2.39f)<.01f)
            capture("crop_cinema239")
            click("比例");click("1.85:1")
            check(abs(vm.params.cropW/vm.params.cropH*1.5f-1.85f)<.01f)
            capture("crop_cinema185");click("取消")
            check(vm.params.cropX==0f && vm.params.cropW==1f) { "Crop cancellation did not restore" }
            val beforeCrop=vm.rendered
            main { vm.beginCrop() };click("比例");click("2.39:1");click("完成裁剪")
            await("crop rendered") { vm.rendered!==beforeCrop && vm.rendered!=null }
            check(abs(vm.rendered!!.width.toFloat()/vm.rendered!!.height-2.39f)<.03f) { "Committed dimensions ${vm.rendered!!.width}x${vm.rendered!!.height}" }
            val beforeReset=vm.rendered
            main { vm.resetCropSelection();vm.scheduleRender() };await("reset render") { vm.rendered!==beforeReset }
            click("调色");reveal("暗角强度")
            val baseline=vm.rendered!!
            main { vm.params.vignetteAmount=75f; vm.scheduleGrade() }
            await("vignette applied") { vm.rendered!==baseline }
            capture("vignette")
            check(EditorParams.fromJson(vm.params.toJson()).vignetteAmount==75f)
            main { vm.undo() };await("undo") { vm.params.vignetteAmount==0f }
            main { vm.redo() };await("redo") { vm.params.vignetteAmount==75f }
            main { vm.exportKeepCapture=true;vm.exportKeepLocation=false }
            click("导出");capture("export_metadata")
            click("保留拍摄信息");click("保留位置信息")
            // Exercise all three production file writers and re-read their published EXIF.
            for ((index,format) in listOf("JPEG","PNG","TIFF").withIndex()) {
                val before=outputs().toSet();val capture=index!=1;val gps=index!=0
                main { vm.exportKeepCapture=capture;vm.exportKeepLocation=gps;vm.exportFormat=format;vm.exportRes=256;vm.export() }
                await("export $format") { !vm.isExporting }
                check(vm.errorMessage==null) { vm.errorMessage.orEmpty() }
                val created=(outputs().toSet()-before).single()
                test.targetContext.contentResolver.openInputStream(created)!!.use { checkMetadata(ExifInterface(it),capture,gps) }
            }
            val second=picture("feedback-second.jpg","Audit B",31.2);val uriB=Uri.fromFile(second)
            EditStore.save(test.targetContext,uriB,EditorParams())
            main { vm.addPhotos(listOf(uriB)); vm.exportFormat="JPEG";vm.exportKeepCapture=true;vm.exportKeepLocation=true;vm.exportRes=128 }
            val beforeBatch=outputs().toSet();main { vm.batchExport() };await("batch") { !vm.isBatchExporting }
            check(vm.errorMessage==null)
            val metadata=(outputs().toSet()-beforeBatch).map { out -> test.targetContext.contentResolver.openInputStream(out)!!.use { ExifInterface(it) } }
            check(metadata.size==2 && metadata.map { it.getAttribute(ExifInterface.TAG_MAKE) }.toSet()==setOf("Audit A","Audit B"))
            metadata.forEach { exif -> val a=exif.getAttribute(ExifInterface.TAG_MAKE)=="Audit A";checkMetadata(exif,true,true,if(a) "Audit A" else "Audit B",if(a)39.9 else 31.2) }
            val recipe=ExportRecipe("Privacy audit","TIFF",256,"sRGB",95,false,true)
            main { vm.applyRecipe(recipe) };check(!vm.exportKeepCapture && vm.exportKeepLocation)
            check(source.exists() && second.exists())
            return "Crop cumulative move, locked ratio resize, 2.39/1.85 presets, cancel/commit; vignette centre/corners, TIFF parity, persistence/undo; 12 format/metadata combinations, real JPEG/PNG/TIFF exports, per-photo batch EXIF/GPS and recipe flags passed"
        } catch (error: Throwable) {
            runCatching { capture("failure") }
            throw IllegalStateException(error.stackTraceToString(), error)
        } finally {
            main { vm.exportKeepCapture=oldCapture;vm.exportKeepLocation=oldGps;activity.finish() }
            (outputs().toSet()-originalOutputs).forEach { test.targetContext.contentResolver.delete(it,null,null) }
        }
    }
}
