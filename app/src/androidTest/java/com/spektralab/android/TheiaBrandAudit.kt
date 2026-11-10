package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.app.LaunchSplash
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.about.UPSTREAM_PROJECT_URL
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File

/** Checks the new display identity while exercising the unchanged install/data identity. */
internal class TheiaBrandAudit(private val test:Instrumentation,private val output:File) {
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,condition:()->Boolean){val start=SystemClock.elapsedRealtime()
        while(!condition()){check(SystemClock.elapsedRealtime()-start<45000){"Timeout $label"};Thread.sleep(60)}}
    private fun root():AccessibilityNodeInfo?{test.uiAutomation.clearCache();return test.uiAutomation.rootInActiveWindow}
    private fun find(label:String,node:AccessibilityNodeInfo?=root()):AccessibilityNodeInfo?{
        if(node==null)return null
        if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it};return null
    }
    private fun scroll(node:AccessibilityNodeInfo?,action:Int):Boolean{
        if(node==null)return false
        if(node.isScrollable && node.performAction(action))return true
        for(i in 0 until node.childCount)if(scroll(node.getChild(i),action))return true;return false
    }
    private fun click(label:String){
        await("window"){root()!=null}
        for(action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){
            repeat(15){
                var node=find(label)
                if(node?.isVisibleToUser==true){
                    while(node!=null && !node.isClickable)node=node.parent
                    if(node!=null){check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        test.waitForIdleSync();Thread.sleep(350);return}
                }
                scroll(root(),action);Thread.sleep(100)
            }
        }
        error("Cannot click $label")
    }
    private fun reveal(label:String){
        for(action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){
            repeat(15){
                if(find(label)?.isVisibleToUser==true)return
                scroll(root(),action);test.waitForIdleSync();Thread.sleep(100)
            }
        }
        error("Cannot reveal $label")
    }
    private fun capture(name:String){Thread.sleep(300);test.waitForIdleSync()
        checkNotNull(test.uiAutomation.takeScreenshot()).also{bitmap->
            File(output,"theia_$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    private fun outputs():Set<Uri> = buildSet {
        test.targetContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),"${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",arrayOf("Theia_%"),null)?.use{
            while(it.moveToNext())add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it.getLong(0)))
        }
    }
    fun run():String {
        val context=test.targetContext
        check(context.packageName=="com.spektralab.android")
        check(context.applicationInfo.loadLabel(context.packageManager).toString()=="Theia")
        check(context.getString(R.string.app_name)=="Theia")
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        main{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        val auto=vm.smartAutoEnabled;val before=outputs()
        try {
            await("home"){!vm.busy && find("更多操作")!=null}
            capture("home")
            reveal("打开观景台")
            click("更多操作");click("关于");capture("about")
            reveal(UPSTREAM_PROJECT_URL);capture("about_attribution");check(find("SpektraLab")==null)
            click("返回编辑器")
            val decoded=test.context.assets.open("13mb.jpg").use{
                BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=16})}!!
            val photo=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply{postRotate(90f)},true);decoded.recycle()
            val source=File(output,"theia-legacy-edited.jpg")
            source.outputStream().use{photo.compress(Bitmap.CompressFormat.JPEG,92,it)};photo.recycle()
            val uri=Uri.fromFile(source)
            val legacy=EditorParams().apply{filmId="kodak_gold_200";paperId="kodak_ektacolor_edge";exposureEv=.25f;grainActive=false;glareActive=false}
            EditStore.save(context,uri,legacy)
            main{vm.openPhotos(listOf(uri))}
            await("legacy edit restore"){!vm.busy && vm.hasPhoto && vm.currentUri==uri}
            check(vm.params.exposureEv==.25f && vm.params.filmId==legacy.filmId)
            check(EditStore.load(context,uri)?.exposureEv==.25f)
            repeat(15){scroll(root(),AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);test.waitForIdleSync()}
            capture("session")
            click("继续显影");click("导出")
            click("高级设置");reveal("Theia_时间戳.jpg")
            check(find("Theia_时间戳.jpg")!=null){"Export naming did not use Theia"}
            capture("export")
            // Capture before exporting so queued system completion toasts cannot cover the component.
            main{activity.setContent{TheiaTheme{LaunchSplash()}}}
            await("splash"){find("Theia")!=null};capture("splash")
            for(format in listOf("JPEG","PNG","TIFF","DI")){
                val previous=outputs()
                main{vm.exportRes=128;vm.exportColorSpace="Display P3"
                    if(format=="DI")vm.exportDi()else{vm.exportFormat=format;vm.export()}}
                await("$format export"){!vm.isExporting}
                check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
                val created=(outputs()-previous).single()
                context.contentResolver.query(created,arrayOf(MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.RELATIVE_PATH,MediaStore.Images.Media.SIZE),null,null,null)?.use{
                    check(it.moveToFirst());check(it.getString(0).startsWith(if(format=="DI")"Theia_DI_" else "Theia_"))
                    check(it.getString(1)=="Pictures/Theia/");check(it.getLong(2)>0)
                }?:error("Missing exported media")
            }
            return "Theia launcher label, home, splash, about/upstream attribution and export settings passed; unchanged application id and legacy sidecar restored; JPEG/PNG/P3 TIFF/DI outputs use Theia names in Pictures/Theia"
        }finally{
            main{vm.smartAutoEnabled=auto;activity.finish()}
            (outputs()-before).forEach{context.contentResolver.delete(it,null,null)}
        }
    }
}
