package com.spektralab.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.TableComposition
import com.spektralab.android.data.export.TableExportSession
import com.spektralab.android.data.local.EditStore
import java.io.File
import java.util.UUID
import kotlin.math.abs

internal object TableOutputCoreAudit {
    private fun item(uri: String, x: Float, y: Float, rotation: Float = 0f) = TablePlacement(UUID.randomUUID().toString(),
        TableSource(uri, "fixture.png"), x, y, 100f, 100f, rotation)
    private fun request(items: List<TablePlacement>, options: TableExportOptions) =
        TableExportRequest(LightTableScene(items), options, TableBox(0f,0f,100f,100f), 18f)

    fun geometry(): String {
        val a = item("file:///a.png", -70f, -40f, 45f)
        val b = item("file:///b.png", 210f, 90f)
        val base = TableExportOptions(mode = TableOutputMode.CONTACT, title = "", longEdge = 1600)
        val plan = request(listOf(a, b), base).plan()
        check(plan.width == 1600 && plan.height in 64..1600)
        check(plan.region.left < -70f && plan.items.size == 2)
        val square = request(listOf(a, b), base.copy(paper = TablePaper.SQUARE)).plan()
        check(square.width == 1600 && square.height == 1600)
        val a4 = request(listOf(a,b), base.copy(paper = TablePaper.A4_PORTRAIT)).plan()
        check(a4.height == 1600 && abs(a4.width / a4.height.toFloat() - .70710677f) < .001f)
        val cropped = request(listOf(a,b), base.copy(viewport = true)).plan()
        check(cropped.items.single().id == a.id && cropped.region == TableBox(0f,0f,100f,100f))
        val emptyCorner=request(listOf(item("file:///corner.png",0f,0f,45f)),base.copy(viewport=true)).copy(viewport=TableBox(-20f,-20f,-15f,-15f))
        check(runCatching{emptyCorner.plan()}.isFailure)
        val transformedViewport = request(listOf(a,b), base).copy(scene = LightTableScene(listOf(a,b), 3f,200f,-100f)).plan()
        check(transformedViewport.width == plan.width && transformedViewport.height == plan.height && transformedViewport.offsetX == plan.offsetX)
        check(runCatching { request(emptyList(),base).plan() }.isFailure)
        check(runCatching { request(listOf(a),base.copy(format = TableOutputFormat.JPEG, transparent = true)).plan() }.isFailure)
        check(runCatching { request(listOf(a),base.copy(longEdge = 10000)).plan() }.isFailure)
        check(runCatching { request(List(200){a.copy(id=UUID.randomUUID().toString())},base).plan() }.isFailure)
        check(runCatching { request(listOf(a,b.copy(x=900000f)),base).plan() }.isFailure)
        return "Rotated union/negative positions, all-vs-viewport contributing items, square/A4 exact pixels, view zoom/pan independence, empty/transparency/edge/span/overdraw capacity rejection passed"
    }

    fun pixels(): String {
        val red = Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(230,80,55)) }
        val blue = Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(45,98,220)) }
        val a=item("file:///red.png",0f,0f);val b=item("file:///blue.png",40f,20f)
        val options=TableExportOptions(mode=TableOutputMode.TABLE,longEdge=1600,transparent=true,shadows=false)
        fun sample(image: Bitmap, plan: TableExportPlan, x: Float, y: Float) = image.getPixel((plan.offsetX+(x-plan.region.left)*plan.scale).toInt(),(plan.offsetY+(y-plan.region.top)*plan.scale).toInt())
        try {
            val plan=request(listOf(a,b),options).plan()
            val output=TableComposition.render(plan,Typeface.DEFAULT){if(it.id==a.id)red else blue}
            try {
                check(Color.alpha(output.getPixel(0,0))==0)
                val lower=sample(output,plan,20f,20f);check(abs(Color.red(lower)-230)<=1 && abs(Color.blue(lower)-55)<=1)
                val overlap=sample(output,plan,70f,70f)
                check(abs(Color.red(overlap)-(230*.42+45*.58))<3 && abs(Color.blue(overlap)-(55*.42+220*.58))<3)
                val upper=sample(output,plan,125f,90f);check(abs(Color.blue(upper)-220)<=1 && Color.alpha(upper)==255)
            } finally {output.recycle()}
            val contact=request(listOf(a,b),options.copy(mode=TableOutputMode.CONTACT)).plan()
            TableComposition.render(contact,Typeface.DEFAULT){if(it.id==a.id)red else blue}.also { output ->
                check(Color.blue(sample(output,contact,70f,70f))==220);output.recycle()
            }
            val diamond=request(listOf(a.copy(rotation=45f)),options.copy(mode=TableOutputMode.CONTACT)).plan()
            TableComposition.render(diamond,Typeface.DEFAULT){red}.also { output ->
                check(Color.alpha(sample(output,diamond,-15f,-15f))==0)
                check(Color.red(sample(output,diamond,50f,50f))==230);output.recycle()
            }
            val dark=request(listOf(a),options).copy(scene=LightTableScene(listOf(a),lightOn=false)).plan()
            TableComposition.render(dark,Typeface.DEFAULT){red}.also { output ->
                check(abs(Color.red(sample(output,dark,50f,50f))-230*.075)<2);output.recycle()
            }
            val plain=request(listOf(a),options.copy(mode=TableOutputMode.CONTACT)).plan()
            val far=request(listOf(a.copy(x=900000f,y=-900000f)),options.copy(mode=TableOutputMode.CONTACT)).plan()
            val left=TableComposition.render(plain,Typeface.DEFAULT){red}
            val right=TableComposition.render(far,Typeface.DEFAULT){red}
            check(left.sameAs(right));left.recycle();right.recycle()
        } finally {red.recycle();blue.recycle()}
        return "Independent sample oracles for unrotated pixels, rotated clear corners, exact alpha exterior, front/back order, table-only58percent overlap transmission and night luminance passed; no screenshot/thumbnail reference"
    }

    fun safety(context: Context, output: File): String {
        val root=File(output,"table-output-core-${System.nanoTime()}").apply{mkdirs()}
        val isolated=object:ContextWrapper(context){
            override fun getFilesDir()=File(root,"private").apply{mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
        }
        val image=Bitmap.createBitmap(1800,1800,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff945e36.toInt())}
        val source=File(root,"source.png");source.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        val uri=Uri.fromFile(source)
        EditStore.save(isolated,uri,EditorParams().apply{brightness=18f;grainActive=false;glareActive=false})
        val bound=EditStore.tableSource(isolated,TableSource(uri.toString(),source.name))
        val scene=LightTableScene(listOf(item(uri.toString(),0f,0f).copy(source=bound)))
        val request=TableExportRequest(scene,TableExportOptions(mode=TableOutputMode.CONTACT,longEdge=1600),null)
        fun ids():Set<Long> = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),null,null,null)!!.use { c ->
            buildSet{while(c.moveToNext())add(c.getLong(0))}
        }
        val before=ids();val bytes=source.readBytes()
        try {
            TableExportSession.prepare(isolated,request,{}, {_,_->}).use { session ->
                val insufficient=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
                check(runCatching{session.stage(session.inputs.single(),insufficient)}.isFailure)
                insufficient.recycle()
                session.stage(session.inputs.single(),image)
                session.checkpoint={if(it==TableExportSession.Checkpoint.DESTINATION_OPENED)error("Injected writer failure")}
                check(runCatching{session.publish{error("Unexpected commit")}}.isFailure)
            }
            check(ids()==before)
            check(isolated.cacheDir.listFiles().orEmpty().none{it.name.startsWith("table-export-")})
            var cancel=false
            TableExportSession.prepare(isolated,request,{check(!cancel){"cancelled"}}, {_,_->}).use { session ->
                session.stage(session.inputs.single(),image);cancel=true
                check(runCatching{session.publish{}}.isFailure)
            }
            check(ids()==before)
            TableExportSession.prepare(isolated,request,{}, {_,_->}).use { session ->
                session.stage(session.inputs.single(),image)
                EditStore.save(isolated,uri,EditorParams().apply{brightness=29f;grainActive=false;glareActive=false})
                check(runCatching{session.publish{}}.isFailure)
            }
            check(runCatching{TableExportSession.prepare(isolated,request,{}, {_,_->})}.isFailure)
            TableExportSession.prepare(isolated,request.copy(options=request.options.copy(latestEdits=true)),{}, {_,_->}).use { session ->
                check(session.inputs.single().params!!.toParams().brightness==29f)
                check(session.inputs.single().source.editRevision>bound.editRevision)
                session.stage(session.inputs.single(),image)
                source.appendBytes(byteArrayOf(1));check(runCatching{session.publish{}}.isFailure)
            }
            source.writeBytes(bytes)
            check(ids()==before)
            val missing=request.copy(scene=scene.copy(items=scene.items.map{it.copy(source=TableSource("file:///missing.png","missing.png"))}))
            check(runCatching{TableExportSession.prepare(isolated,missing,{}, {_,_->})}.isFailure)
            check(source.readBytes().contentEquals(bytes) && EditStore.load(isolated,uri)!!.brightness==29f)
        } finally {image.recycle();EditStore.closeForTests(isolated)}
        return "Frozen source/edit references, stale revision refusal and explicit latest snapshot, changed parameter/content rejection, missing source, actual MediaStore pending-row failure cleanup, cancellation, own temporary files cleanup, original/edit preservation passed"
    }
}
