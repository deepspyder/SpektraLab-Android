package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File

/** Different scenes, real import hooks and persistent settings are tested together. */
internal class SmartDevelopAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var vm: EditorViewModel
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,condition:()->Boolean){
        val start=SystemClock.elapsedRealtime()
        while(!condition()){check(SystemClock.elapsedRealtime()-start<45000){"Timeout $label: ${vm.errorMessage}"};Thread.sleep(50)}
    }
    private fun find(label:String,node:AccessibilityNodeInfo?=root()):AccessibilityNodeInfo?{
        if(node==null)return null
        if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it}
        return null
    }
    private fun root():AccessibilityNodeInfo?{
        if(Build.VERSION.SDK_INT>=34)test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun reveal(label:String){
        await("window"){root()!=null}
        fun scroll(node:AccessibilityNodeInfo?,action:Int):Boolean{
            if(node==null)return false
            if(node.isScrollable && node.performAction(action))return true
            for(i in 0 until node.childCount)if(scroll(node.getChild(i),action))return true
            return false
        }
        for(action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){
            repeat(12){
                if(find(label)?.isVisibleToUser==true)return
                scroll(root(),action)
                test.waitForIdleSync();Thread.sleep(150)
            }
        }
        error("Control not reachable by scrolling: $label")
    }
    private fun click(label:String){
        reveal(label);var node=checkNotNull(find(label))
        while(!node.isClickable)node=node.parent?:error("Not clickable $label")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Disabled $label"}
        test.waitForIdleSync();Thread.sleep(300)
    }
    private fun screenshot(name:String){
        Thread.sleep(650)
        test.waitForIdleSync();test.uiAutomation.waitForIdle(200,5000)
        checkNotNull(test.uiAutomation.takeScreenshot()).also { bitmap ->
            File(output,"smart_$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    private fun fixture(name:String,night:Boolean):Uri{
        val file=File(output,"$name-${System.nanoTime()}.png")
        val bitmap=Bitmap.createBitmap(360,240,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(360*240){i ->
            val x=i%360;val y=i/360
            if(night)Color.rgb(20+x/24,10+y/25,8+x/40)
            else Color.rgb(40+x/4,85+y/2,90+x/3)
        }
        bitmap.setPixels(pixels,0,360,0,0,360,240)
        file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        return Uri.fromFile(file)
    }
    private fun snapshots(uris:List<Uri>)=uris.map{checkNotNull(EditStore.load(test.targetContext,it))}
    private fun outputs():Set<Uri> = buildSet {
        test.targetContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),"${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",arrayOf("SpektraLab_%"),null)?.use {
            while(it.moveToNext())add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it.getLong(0)))
        }
    }
    fun run():String{
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        val oldEnabled=vm.smartAutoEnabled;val oldStyle=vm.smartStyle
        val beforeOutputs=outputs()
        try{
            await("engine"){vm.engine!=null && !vm.busy}
            main{vm.smartAutoEnabled=true;vm.smartStyle=SmartDevelopStyle.Cinema}
            reveal("选择智能显影风格")
            screenshot("home")
            click("选择智能显影风格")
            for(style in SmartDevelopStyle.entries){
                click("显影风格：${style.title}")
                check(vm.smartStyle==style)
                val prefs=test.targetContext.getSharedPreferences("smart_develop",0)
                check(prefs.getString("style","")==style.id)
            }
            click("显影风格：电影浓郁");screenshot("styles")
            click("导入后自动显影");check(!vm.smartAutoEnabled)
            click("导入后自动显影");check(vm.smartAutoEnabled)
            click("关闭智能显影")
            val day=fixture("smart-day",false);val night=fixture("smart-night",true);val edited=fixture("smart-edited",false)
            val originalEdited=EditorParams().apply{filmId="kodak_gold_200";paperId="kodak_ektacolor_edge";exposureEv=.75f;cropW=.7f;cropH=.8f;cropX=.2f}
            EditStore.save(test.targetContext,edited,originalEdited)
            main{vm.openPhotos(listOf(day,night,edited))}
            await("queue"){!vm.smartImportRunning && vm.smartImportCompleted==3 && !vm.busy && vm.hasPhoto}
            check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
            val params=snapshots(listOf(day,night,edited))
            check(params[0].smartDevelopStyle=="cinema" && params[1].smartDevelopStyle=="cinema")
            check(params[0].filmId!=params[1].filmId){"Day and low light received the same film"}
            check(params[1].grainAmount<params[0].grainAmount){"Low light grain was not restrained"}
            check(params[0].cropW==1f && params[1].cropW==1f)
            check(params[2].toJson().toString()==originalEdited.toJson().toString()){"Import overwrote existing edits"}
            check(vm.params.smartDevelopStyle=="cinema")
            check(vm.canUndo){"Automatic import did not provide undo"}
            click("继续显影");screenshot("automatic")
            val originalResult=vm.lastResult
            main{vm.undo()};await("undo"){vm.params.smartDevelopStyle.isEmpty() && vm.lastResult!==originalResult}
            main{vm.redo()};await("redo"){vm.params.smartDevelopStyle=="cinema"}
            main{vm.selectPhoto(1)};await("night"){!vm.busy && vm.currentUri==night && vm.hasPhoto}
            check(vm.params.filmId==params[1].filmId)
            screenshot("low_light")
            click("智能显影");await("result sheet"){find("显影风格：自然耐看")!=null};screenshot("result")
            click("显影风格：轻柔复古");click("应用到当前照片")
            await("vintage apply"){!vm.busy && vm.params.smartDevelopStyle=="vintage"}
            check(vm.params.contrast<params[1].contrast)
            val vintage=vm.params.copy()
            main{vm.selectPhoto(2)};await("edited"){!vm.busy && vm.currentUri==edited && vm.hasPhoto}
            check(vm.params.exposureEv==.75f && vm.params.cropX==.2f)
            click("智能显影");click("显影风格：自然耐看");click("应用到当前照片")
            await("natural apply"){!vm.busy && vm.params.smartDevelopStyle=="natural"}
            check(vm.params.cropW==.7f && vm.params.cropX==.2f){"Style changed geometry"}
            check(vm.params.vignetteAmount==0f && vm.params.saturation>vintage.saturation)
            screenshot("natural")
            main{vm.undo()};await("manual undo"){vm.params.smartDevelopStyle.isEmpty() && vm.params.exposureEv==.75f}
            val noAuto=fixture("smart-disabled",true)
            main{vm.smartAutoEnabled=false;vm.addPhotos(listOf(noAuto),openSelected=true)}
            await("manual import"){!vm.busy && vm.currentUri==noAuto && vm.hasPhoto}
            check(vm.params.smartDevelopStyle.isEmpty())
            // RAW analysis uses a bounded small native preview, not a full managed RGB frame.
            val raw=File(output,"smart-raw-${System.nanoTime()}.dng")
            test.context.assets.open("audit.dng").use{input->raw.outputStream().use{input.copyTo(it)}}
            main{vm.smartAutoEnabled=true;vm.smartStyle=SmartDevelopStyle.Cinema;vm.addPhotos(listOf(Uri.fromFile(raw)),openSelected=true)}
            await("RAW automatic"){!vm.busy && !vm.smartImportRunning && vm.currentIsRaw && vm.hasPhoto}
            check(vm.params.smartDevelopStyle=="cinema"){"RAW analysis was skipped"}
            val rawParams=vm.params.copy()
            check(rawParams.cropW==1f && rawParams.quarterTurns==0)
            val beforeRace=fixture("smart-race",false);val final=fixture("smart-final",true)
            main{vm.openPhotos(listOf(beforeRace));vm.openPhotos(listOf(final))}
            await("latest import wins"){!vm.busy && !vm.smartImportRunning && vm.currentUri==final && vm.hasPhoto}
            val expected=checkNotNull(EditStore.load(test.targetContext,final))
            check(vm.params.filmId==expected.filmId && vm.photos==listOf(final))
            val pending=fixture("smart-pending-export",false)
            main{
                vm.smartAutoEnabled=false;vm.addPhotos(listOf(pending))
                vm.smartAutoEnabled=true;vm.exportFormat="PNG";vm.exportRes=128;vm.batchExport()
            }
            await("batch prepares pending photo"){!vm.isBatchExporting}
            check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
            check(EditStore.load(test.targetContext,pending)?.smartDevelopStyle=="cinema")
            check(vm.currentUri==final && vm.params.filmId==expected.filmId){"Batch changed active photo or edit"}
            check((outputs()-beforeOutputs).size==2){"Expected two smart-developed batch exports"}
            // Inspect the three styles on an actual existing photo fixture, not only gradients.
            val photoFile=File(output,"smart-photo-${System.nanoTime()}.jpg")
            val sample=test.context.assets.open("13mb.jpg").use { input ->
                android.graphics.BitmapFactory.decodeStream(input,null,android.graphics.BitmapFactory.Options().apply{inSampleSize=16})
            }?:error("Missing photo fixture")
            val upright=Bitmap.createBitmap(sample,0,0,sample.width,sample.height,android.graphics.Matrix().apply{postRotate(90f)},true)
            photoFile.outputStream().use{upright.compress(Bitmap.CompressFormat.JPEG,95,it)}
            if(upright!==sample)upright.recycle();sample.recycle()
            main{vm.openPhotos(listOf(Uri.fromFile(photoFile)))}
            await("real photo"){!vm.busy && !vm.smartImportRunning && vm.hasPhoto}
            screenshot("photo_cinema")
            val hashes=mutableSetOf<Int>()
            fun pixelsHash():Int {
                val bitmap=checkNotNull(vm.rendered);val pixels=IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);return pixels.contentHashCode()
            }
            hashes.add(pixelsHash())
            for(style in listOf(SmartDevelopStyle.Natural,SmartDevelopStyle.Vintage)){
                val before=vm.rendered
                main{vm.smartStyle=style;vm.applySmartDevelop()}
                await("real ${style.id}"){!vm.busy && vm.rendered!==before && vm.params.smartDevelopStyle==style.id}
                hashes.add(pixelsHash());screenshot("photo_${style.id}")
            }
            check(hashes.size==3){"Three styles rendered identical photo pixels"}
            // Verify provenance is sidecar-only, not leaked into native wire parameters.
            val wire=org.json.JSONObject(vm.params.deltaJson(360,240))
            check(!wire.has("smart_develop_style") && !wire.has("smart_develop_summary"))
            return "Three styles accessible and persistent; per-photo daylight/lowlight film+paper+tone, saved edits preserved, import/manual undo and geometry, disable option, bounded RAW analysis, import cancellation identity, pending-photo batch export, real-photo three-style pixel differences and sidecar-only provenance passed"
        }catch(error:Throwable){runCatching{screenshot("failure")};throw IllegalStateException(error.stackTraceToString(),error)}
        finally{
            main{vm.smartAutoEnabled=oldEnabled;vm.smartStyle=oldStyle;activity.finish()}
            (outputs()-beforeOutputs).forEach{test.targetContext.contentResolver.delete(it,null,null)}
        }
    }
}
