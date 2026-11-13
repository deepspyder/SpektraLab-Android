package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.library.LightTableProjectStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.lighttable.GalleryScreen
import com.spektralab.android.feature.lighttable.rememberFilmCanvasState
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import kotlin.math.abs

internal class TableOutputUiAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var activity: MainActivity
    private lateinit var vm: EditorViewModel
    fun run(layoutOnly: Boolean = false): String {
        val context=test.targetContext
        val media=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids()=context.contentResolver.query(media,arrayOf("_id"),"_display_name LIKE ?",arrayOf("Theia_Project_%"),null)!!.use{c->buildSet{while(c.moveToNext())add(c.getLong(0))}}
        val before=ids()
        val files=(0..1).map { index -> File(output,"project-output-${System.nanoTime()}-$index.png").also { file ->
            val image=Bitmap.createBitmap(1600,1000,Bitmap.Config.ARGB_8888)
            val strip=IntArray(1600*1000){i->Color.rgb(i%1600*255/1600,i/1600*255/1000,if(index==0)65 else 200)}
            image.setPixels(strip,0,1600,0,0,1600,1000)
            file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        } }
        val uris=files.map(Uri::fromFile)
        val bytes=files.map(File::readBytes)
        files.forEachIndexed { index,_ -> EditStore.save(context,uris[index],EditorParams().apply {
            brightness=if(index==0)18f else -7f;grainActive=false;glareActive=false
        }) }
        activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        await{vm.engine!=null&&!vm.busy}
        val automatic = vm.smartAutoEnabled
        var projectId: String? = null
        try {
            test.runOnMainSync{vm.smartAutoEnabled=false;vm.openPhotos(uris)};idle()
            val frame=vm.lastResult;val params=EditSnapshot.capture(vm.params);val selection=vm.batchExportSelection
            test.runOnMainSync { activity.setContent { TheiaTheme {
                GalleryScreen(vm,onImport={},onBackToDarkroom={},onRemovePhoto={},onEditPhoto={},canvasState=rememberFilmCanvasState())
            } } }
            await{find("导出观景台")!=null}
            capture("output-entry.png")
            click("保存项目")
            fun editable(node:AccessibilityNodeInfo?):AccessibilityNodeInfo?{
                if(node==null)return null
                if(node.isEditable)return node
                for(i in 0 until node.childCount)editable(node.getChild(i))?.let{return it};return null
            }
            await{editable(root())!=null}
            check(editable(root())!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"项目输出 ${System.nanoTime()}")
            }))
            test.waitForIdleSync();click("保存项目")
            await{vm.lightTableProjects.current!=null&&!vm.lightTableProjects.pending}
            projectId=vm.lightTableProjects.current!!.id
            val savedProject=vm.lightTableProjects.current!!
            click("导出观景台");ready();capture("output-poster.png")
            click("照片印样");click("1600 px");click("方形");click("照片编号");ready();capture("output-contact.png")
            check(vm.lastResult===frame && EditSnapshot.capture(vm.params)==params)
            click("含观景台");click("透明背景");ready();capture("output-transparent.png")
            val jpeg=find("JPEG 高品质")!!
            check(generateSequence(jpeg){it.parent}.any{!it.isEnabled})
            if(layoutOnly)return "Native output entry, layout previews/modes, paper/size/transparent PNG controls, disabled JPEG transparency and scrollable footer; choices never render or mutate photo/project"
            var old=ids()
            click("生成并保存");done()
            val pngUri=ContentUris.withAppendedId(media,(ids()-old).single())
            context.contentResolver.openInputStream(pngUri)!!.use { input -> File(output,"project-output-transparent.png").outputStream().use { input.copyTo(it) } }
            context.contentResolver.openInputStream(pngUri)!!.use { stream ->
                val image=BitmapFactory.decodeStream(stream)!!
                check(image.width==1600&&image.height==1600&&Color.alpha(image.getPixel(0,0))==0)
                image.recycle()
            }
            capture("output-saved.png")
            check(vm.currentUri==uris[0] && vm.batchExportSelection==selection && EditSnapshot.capture(vm.params)==params && vm.lastResult===frame)
            // A changed source cannot silently use a viewing preview or an old snapshot.
            val updated=EditStore.load(context,uris[1])!!.apply{brightness=37f}
            EditStore.save(context,uris[1],updated)
            old=ids();click("生成并保存");done(expectError=true)
            check(ids()==old && vm.tableOutput.error==context.getString(R.string.table_output_stale))
            capture("output-stale.png")
            click("使用最新编辑");click("透明背景");click("JPEG 高品质");ready()
            old=ids();click("生成并保存");done()
            val jpegUri=ContentUris.withAppendedId(media,(ids()-old).single())
            val header=context.contentResolver.openInputStream(jpegUri)!!.use{byteArrayOf(it.read().toByte(),it.read().toByte())}
            check(header.contentEquals(byteArrayOf(0xff.toByte(),0xd8.toByte())))
            click("海报");click("显示日期");click("A4 竖向");ready();capture("output-date.png")
            old=ids();click("生成并保存");done()
            val poster=ContentUris.withAppendedId(media,(ids()-old).single())
            context.contentResolver.openInputStream(poster)!!.use { input -> File(output,"project-output-poster.jpg").outputStream().use { input.copyTo(it) } }
            context.contentResolver.openInputStream(poster)!!.use { stream ->
                val image=BitmapFactory.decodeStream(stream)!!
                check(image.height==1600&&abs(image.width/1600f-.70710677f)<.001f);image.recycle()
            }
            // Cancellation before source preparation completes publishes nothing and remains retryable.
            click("当前可见区域");ready();capture("output-viewport.png");old=ids()
            test.runOnMainSync { vm.tableOutput.submit(vm);vm.cancelTableProjectExport() }
            done(expectError=true)
            check(ids()==old && vm.tableOutput.error==context.getString(R.string.table_output_cancelled))
            capture("output-cancelled.png")
            click("关闭导出设置")
            check(vm.currentUri==uris[0] && vm.lastResult===frame && EditSnapshot.capture(vm.params)==params)
            test.runOnMainSync{vm.params.brightness=26f;vm.scheduleGrade()};idle()
            test.runOnMainSync{vm.undo()};idle();check(vm.params.brightness==18f)
            check(files.indices.all{files[it].readBytes().contentEquals(bytes[it])})
            check(context.cacheDir.listFiles().orEmpty().none{it.name.startsWith("table-export-")})
            check(vm.lightTableProjects.current==savedProject && vm.lightTableProjects.canvas.scene()==savedProject.scene)
            return "Actual PNG alpha exterior/exact1600square, JPEG signature/A4 dimensions, poster/date/numbers/viewport, strict stale rejection then explicit latest rendering, cancellation without publication/temp files, original bytes/current parameters/frame/selection preserved and subsequent edit/undo passed"
        } catch(failure:Throwable){runCatching{capture("output-failure.png")};throw failure}
        finally {
            (ids()-before).forEach{runCatching{context.contentResolver.delete(ContentUris.withAppendedId(media,it),null,null)}}
            test.runOnMainSync{vm.smartAutoEnabled=automatic;activity.finish()}
            projectId?.let { id -> LightTableProjectStore(File(context.filesDir,"lighttable-projects")).use { it.delete(it.read(id)) } }
        }
    }
    private fun ready()=await{!vm.tableOutput.preparing&&!vm.tableOutput.previewPending&&vm.tableOutput.preview!=null&&vm.tableOutput.error==null}
    private fun done(expectError:Boolean=false){
        await{!vm.isExporting&&(vm.tableOutput.error!=null||vm.tableOutput.result!=null)}
        check((vm.tableOutput.error!=null)==expectError){"Unexpected project output result: ${vm.tableOutput.error}"}
        test.waitForIdleSync()
    }
    private fun idle()=await{vm.hasPhoto&&!vm.busy&&!vm.isExporting&&vm.rendered!=null&&vm.renderTaskState.active==null}
    private fun root():AccessibilityNodeInfo?{test.uiAutomation.clearCache();return test.uiAutomation.rootInActiveWindow}
    private fun find(label:String,node:AccessibilityNodeInfo?=root()):AccessibilityNodeInfo?{
        if(node==null)return null
        if(node.text?.toString()==label||node.contentDescription?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it};return null
    }
    private fun click(label:String){
        fun action(node:AccessibilityNodeInfo?):AccessibilityNodeInfo?{
            if(node==null)return null
            if(node.text?.toString()==label||node.contentDescription?.toString()==label){
                var parent:AccessibilityNodeInfo?=node
                while(parent!=null&&!parent.isClickable)parent=parent.parent
                if(parent!=null)return node
            }
            for(i in 0 until node.childCount)action(node.getChild(i))?.let{return it};return null
        }
        for(direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))repeat(24){
            action(root())?.let{found->
                found.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);test.waitForIdleSync();SystemClock.sleep(100)
                var node=action(root())?:found
                val bounds=Rect().also(node::getBoundsInScreen)
                if(node.isVisibleToUser&&bounds.height()>0){
                    while(!node.isClickable)node=node.parent?:error(label)
                    check(node.isEnabled&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){label}
                    test.waitForIdleSync();SystemClock.sleep(200);return
                }
            }
            fun scroll(node:AccessibilityNodeInfo?):Boolean{
                if(node==null)return false
                if(node.isScrollable&&node.performAction(direction))return true
                for(i in 0 until node.childCount)if(scroll(node.getChild(i)))return true;return false
            }
            scroll(root());SystemClock.sleep(80)
        }
        error("Missing project export control $label")
    }
    private fun capture(name:String){test.waitForIdleSync();SystemClock.sleep(1000)
        if(name!="output-entry.png") repeat(40) { if(find("生成并保存")==null) SystemClock.sleep(50) }
        val image=checkNotNull(test.uiAutomation.takeScreenshot())
        try {
            File(output,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            if(name!="output-entry.png"){
                val footer=find("生成并保存")?:error("Output footer missing in $name")
                var button=footer
                while(!button.isClickable)button=button.parent?:error("Output footer has no action")
                val bounds=Rect().also(button::getBoundsInScreen)
                check(button.isVisibleToUser&&bounds.bottom<=image.height&&bounds.height()>0){"Output footer outside window $bounds"}
            }
            File(output,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        } finally { image.recycle() }
    }
    private fun await(condition:()->Boolean){val start=SystemClock.elapsedRealtime()
        while(!condition()){check(SystemClock.elapsedRealtime()-start<90000){"Project output timeout"};SystemClock.sleep(30)}
    }
}
