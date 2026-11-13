package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.util.UUID

internal class TableOutputPlainAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val context = test.targetContext
        val file = File(output,"unedited-project-${System.nanoTime()}.png")
        val source = Bitmap.createBitmap(1600,1000,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            android.graphics.Canvas(this).drawRect(100f,100f,1500f,900f,android.graphics.Paint().apply{color=0xffba6038.toInt()})
        }
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it) }; source.recycle()
        val original = file.readBytes()
        val uri = Uri.fromFile(file)
        val photoCount = EditStore.savedPhotoCount(context)
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids()=context.contentResolver.query(media,arrayOf("_id"),"_display_name LIKE ?",arrayOf("Theia_Project_%"),null)!!.use { c -> buildSet{while(c.moveToNext())add(c.getLong(0))} }
        val before=ids()
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        await{vm.engine!=null&&!vm.busy}
        try {
            val item=TablePlacement(UUID.randomUUID().toString(),TableSource(uri.toString(),file.name),0f,0f,200f,125f)
            val request=TableExportRequest(LightTableScene(listOf(item,item.copy(id=UUID.randomUUID().toString(),x=220f,y=20f))),
                TableExportOptions(mode=TableOutputMode.CONTACT,longEdge=1600,transparent=true),null)
            var result:Result<String>?=null
            test.runOnMainSync{check(vm.exportTableProject(request){result=it})}
            await{!vm.isExporting&&result!=null}
            check(result!!.isSuccess){result!!.exceptionOrNull().toString()}
            val added=ids()-before;check(added.size==1)
            val outputUri=ContentUris.withAppendedId(media,added.single())
            val bitmap=context.contentResolver.openInputStream(outputUri)!!.use{BitmapFactory.decodeStream(it)}!!
            val plan=request.plan()
            try {
                fun pixel(x:Float,y:Float)=bitmap.getPixel((plan.offsetX+x*plan.scale).toInt(),(plan.offsetY+y*plan.scale).toInt())
                check(pixel(100f,62f)==0xffba6038.toInt()&&pixel(320f,82f)==0xffba6038.toInt())
                check(Color.alpha(pixel(3f,3f))==0&&Color.alpha(bitmap.getPixel(0,0))==0)
            } finally {bitmap.recycle()}
            check(EditStore.savedPhotoCount(context)==photoCount && EditStore.load(context,uri)==null)
            check(vm.photos.isEmpty()&&!vm.hasPhoto&&vm.lastResult==null&&file.readBytes().contentEquals(original))
            return "Unedited alphaPNG from original source and duplicate placement, exact opaque center/transparent source+canvas pixels, one published file, no film/default edit/library record/native result created, source bytes and empty editor retained passed"
        } finally {
            (ids()-before).forEach{context.contentResolver.delete(ContentUris.withAppendedId(media,it),null,null)}
            test.runOnMainSync{activity.finish()}
        }
    }
    private fun await(condition:()->Boolean){val start=SystemClock.elapsedRealtime()
        while(!condition()){check(SystemClock.elapsedRealtime()-start<90000){"Plain project output timed out"};SystemClock.sleep(30)}
    }
}
