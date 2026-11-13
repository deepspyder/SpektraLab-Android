package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal class TableOutputRawAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val context = test.targetContext
        val source = File(output, "perf-z6ii.NEF")
        check(source.isFile)
        val uri = Uri.fromFile(source)
        val previous = EditStore.load(context, uri)
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val resolver = context.contentResolver
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids(): Set<Long> = resolver.query(media, arrayOf("_id"), null, null, null)!!.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
        val before = ids()
        var version: EditVersion? = null
        try {
            await { vm.engine != null && !vm.busy }
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false; brightness = 18f })
            val row = EditStore.savedPhotos(context).first { it.uri == uri.toString() }
            version = EditStore.createEditVersion(context, EditStore.editVersions(context, row.assetId).first(), "项目RAW ${System.nanoTime()}")
            test.runOnMainSync { vm.openPhotos(listOf(version.editUri)) }
            await { vm.hasPhoto && !vm.busy && vm.renderTaskState.active == null && vm.lastResult != null }
            val frame = vm.lastResult
            val params = EditSnapshot.capture(vm.params)
            val sourceRef = EditStore.tableSource(context, TableSource(version.editUri.toString(), source.name))
            val item = TablePlacement(UUID.randomUUID().toString(), sourceRef, 0f, 0f, 200f, 200f * 4040f / 6064f, 0f)
            val request = TableExportRequest(LightTableScene(listOf(item)), TableExportOptions(
                mode = TableOutputMode.CONTACT, transparent = true, longEdge = 1600), null)
            val plan = request.plan()
            val resolution = plan.sourceEdge(sourceRef.uri)
            // Existing ordinary photo PNG is an independent reference for decode, render, grade and quantization.
            var seen = ids()
            test.runOnMainSync { vm.exportFormat = "PNG"; vm.exportRes = resolution; vm.exportColorSpace = "sRGB"; vm.exportKeepCapture = false; vm.exportKeepLocation = false; vm.export() }
            await { !vm.isExporting && vm.renderTaskState.active == null }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val referenceUri = ContentUris.withAppendedId(media, (ids() - seen).single())
            val reference = resolver.openInputStream(referenceUri)!!.use { BitmapFactory.decodeStream(it) }!!
            seen = ids()
            var outcome: Result<String>? = null
            val stats = vm.exportCacheStats
            val start = SystemClock.elapsedRealtime()
            test.runOnMainSync { check(vm.exportTableProject(request) { outcome = it }) }
            await { !vm.isExporting && outcome != null }
            check(outcome!!.isSuccess) { outcome!!.exceptionOrNull().toString() }
            val firstMs = SystemClock.elapsedRealtime() - start
            android.util.Log.i("SpkAudit", "RAW project sourceEdge=$resolution reference=${reference.width}x${reference.height} output=${plan.width}x${plan.height}")
            val actualUri = ContentUris.withAppendedId(media, (ids() - seen).single())
            val actual = resolver.openInputStream(actualUri)!!.use { BitmapFactory.decodeStream(it) }!!
            try {
                check(actual.width == plan.width && actual.height == plan.height)
                val left = plan.offsetX; val top = plan.offsetY
                val fitted = com.spektralab.android.data.export.TableComposition.fit(reference.width, reference.height, item)
                val w = fitted.width() * plan.scale; val h = fitted.height() * plan.scale
                var maxError = 0
                // Independent centre-coordinate bilinear sampler, away from transformed edge antialiasing.
                for (iy in 1..12) for (ix in 1..16) {
                    val x = (left + fitted.left * plan.scale + w * ix / 17).roundToInt()
                    val y = (top + fitted.top * plan.scale + h * iy / 13).roundToInt()
                    val sx = ((x + .5f - left - fitted.left * plan.scale) / w * reference.width - .5f).coerceIn(0f, reference.width - 1f)
                    val sy = ((y + .5f - top - fitted.top * plan.scale) / h * reference.height - .5f).coerceIn(0f, reference.height - 1f)
                    val x0 = sx.toInt(); val y0 = sy.toInt(); val dx = sx - x0; val dy = sy - y0
                    val pixels = intArrayOf(reference.getPixel(x0,y0),reference.getPixel(minOf(x0+1,reference.width-1),y0),
                        reference.getPixel(x0,minOf(y0+1,reference.height-1)),reference.getPixel(minOf(x0+1,reference.width-1),minOf(y0+1,reference.height-1)))
                    val value = actual.getPixel(x,y)
                    for (shift in listOf(0,8,16)) {
                        val expected = ((pixels[0] ushr shift and 255)*(1-dx)*(1-dy)+(pixels[1] ushr shift and 255)*dx*(1-dy)+
                            (pixels[2] ushr shift and 255)*(1-dx)*dy+(pixels[3] ushr shift and 255)*dx*dy).roundToInt()
                        maxError = max(maxError, kotlin.math.abs((value ushr shift and 255)-expected))
                    }
                }
                // Android's filtered raster uses discrete coefficients. Its exact whole-image
                // oracle is a direct one-image draw, independent of TableComposition/layout code.
                val expectedImage = Bitmap.createBitmap(plan.width,plan.height,Bitmap.Config.ARGB_8888)
                Canvas(expectedImage).drawBitmap(reference,null,RectF(left+fitted.left*plan.scale,top+fitted.top*plan.scale,
                    left+fitted.right*plan.scale,top+fitted.bottom*plan.scale),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                val expectedRow=IntArray(plan.width);val actualRow=IntArray(plan.width)
                var rasterError=0
                var interiorError=0
                var worst=""
                for(y in 0 until plan.height){
                    expectedImage.getPixels(expectedRow,0,plan.width,0,y,plan.width,1)
                    actual.getPixels(actualRow,0,plan.width,0,y,plan.width,1)
                    for(x in expectedRow.indices)for(shift in listOf(0,8,16,24)) {
                        val difference=kotlin.math.abs((actualRow[x] ushr shift and 255)-(expectedRow[x] ushr shift and 255))
                        if(difference>rasterError){rasterError=difference;worst="x=$x y=$y channel=$shift expected=${expectedRow[x]} actual=${actualRow[x]}"}
                        if((actualRow[x] ushr 24)==255&&(expectedRow[x] ushr 24)==255)interiorError=max(interiorError,difference)
                    }
                }
                expectedImage.recycle()
                if(rasterError>1){
                    File(output,"raw-project-reference.png").outputStream().use{reference.compress(Bitmap.CompressFormat.PNG,100,it)}
                    File(output,"raw-project-actual.png").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                check(rasterError<=1) { "RAW project raster=$rasterError interior=$interiorError; float=$maxError; $worst; cache=$stats -> ${vm.exportCacheStats}" }
                check(vm.currentUri == version.editUri && EditSnapshot.capture(vm.params) == params && vm.lastResult === frame)
                return "Real Z6II NEF virtual-version project $resolution source-edge to ${actual.width}x${actual.height} PNG; whole-image channels vs independent one-image Android raster of ordinary photo PNG maxerror=$rasterError; float-bilinear diagnostic192samples maxerror=$maxError; ms=$firstMs; cache=$stats -> ${vm.exportCacheStats}; editor frame/params/source retained"
            } finally { reference.recycle(); actual.recycle() }
        } finally {
            (ids() - before).forEach { resolver.delete(ContentUris.withAppendedId(media, it), null, null) }
            test.runOnMainSync { activity.finish() }
            version?.let { EditStore.deleteEditVersion(context, EditStore.editVersion(context, it.id)) }
            previous?.let { EditStore.save(context, uri, it) }
        }
    }
    private fun await(condition: () -> Boolean) { val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 180000) { "RAW project output timeout" }; SystemClock.sleep(30) }
    }
}
