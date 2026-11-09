package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlin.math.abs

internal class HomeViewingStandAudit(private val test:Instrumentation,private val output:File) {
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,condition:()->Boolean){val start=SystemClock.elapsedRealtime()
        while(!condition()){check(SystemClock.elapsedRealtime()-start<30000){"Timeout $label"};Thread.sleep(70)}}
    private fun root():AccessibilityNodeInfo?{test.uiAutomation.clearCache();return test.uiAutomation.rootInActiveWindow}
    private fun find(label:String,node:AccessibilityNodeInfo?=root()):AccessibilityNodeInfo?{
        if(node==null)return null
        if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it};return null
    }
    private fun scroll(node:AccessibilityNodeInfo?,action:Int):Boolean {
        if(node==null)return false
        if(node.isScrollable && node.performAction(action))return true
        for(i in 0 until node.childCount)if(scroll(node.getChild(i),action))return true
        return false
    }
    private fun reveal(label:String){
        await("window"){root()!=null}
        fun ready():Boolean {
            var node=find(label) ?: return false
            if(!node.isVisibleToUser)return false
            if(label=="打开观景台"){
                while(!node.isClickable)node=node.parent ?: return false
                val area=Rect();node.getBoundsInScreen(area)
                return area.height()>=51*test.targetContext.resources.displayMetrics.density
            }
            return true
        }
        for(action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            repeat(14){if(ready())return;scroll(root(),action);test.waitForIdleSync();Thread.sleep(130)}
        error("Cannot reach $label")
    }
    private fun click(label:String){reveal(label);var node=checkNotNull(find(label))
        while(!node.isClickable)node=node.parent?:error("Not clickable $label")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync();Thread.sleep(450)}
    private fun bounds(label:String):Rect=Rect().also{checkNotNull(find(label)).getBoundsInScreen(it)}
    private fun back(){
        val time=SystemClock.uptimeMillis()
        check(test.uiAutomation.injectInputEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true))
        check(test.uiAutomation.injectInputEvent(KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true))
        Thread.sleep(450)
    }
    private fun capture(name:String){Thread.sleep(350);test.waitForIdleSync()
        checkNotNull(test.uiAutomation.takeScreenshot()).also{bitmap->
            File(output,"home_stand_$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    private fun drag(rect:Rect){
        val start=SystemClock.uptimeMillis()
        for(step in 0..20){val action=if(step==0)MotionEvent.ACTION_DOWN else if(step==20)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,rect.exactCenterX()+step*3f,rect.exactCenterY()+step*2f,0)
            test.uiAutomation.injectInputEvent(e,true);e.recycle();Thread.sleep(16)};Thread.sleep(350)
    }
    fun run():String{
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        main{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        await("engine"){vm.engine!=null && !vm.busy}
        val enabled=vm.smartAutoEnabled
        val files=mutableListOf<File>()
        try {
            reveal("打开观景台");capture("empty_home")
            click("打开观景台");await("empty stand"){find("返回首页")!=null}
            capture("empty_canvas")
            click("导入照片")
            await("viewing picker"){find("Cancel")!=null || find("取消")!=null}
            back();await("cancel returns stand"){find("返回首页")!=null}
            check(vm.photos.isEmpty() && vm.viewingPhotos.isEmpty())
            back();reveal("打开观景台")
            val decoded=test.context.assets.open("13mb.jpg").use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=8})}!!
            val upright=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply{postRotate(90f)},true);decoded.recycle()
            for(i in 1..3){val file=File(output,"home-stand-$i.jpg");files+=file
                file.outputStream().use{upright.compress(Bitmap.CompressFormat.JPEG,93,it)}};upright.recycle()
            val uris=files.map(Uri::fromFile)
            main{vm.smartAutoEnabled=false;vm.openPhotos(uris.take(2));vm.loadViewingPhotos(uris.drop(2))}
            await("photo ready"){!vm.busy && vm.hasPhoto && !vm.isImportingViewingPhotos}
            reveal("打开观景台");capture("session_home")
            val active=vm.currentUri;val params=vm.params.toJson().toString()
            click("打开观景台");await("photos"){find("查看第 1 张 home-stand-1.jpg")!=null}
            check(vm.currentUri==active && vm.params.toJson().toString()==params)
            capture("session_canvas")
            val label="查看第 1 张 home-stand-1.jpg"
            drag(bounds(label))
            val placed=bounds(label);val wall=bounds("照片墙")
            val relativeX=placed.left-wall.left;val relativeY=placed.top-wall.top
            click("返回首页");click("打开观景台")
            val restored=bounds(label);val restoredWall=bounds("照片墙")
            check(abs(restored.left-restoredWall.left-relativeX)<3 && abs(restored.top-restoredWall.top-relativeY)<3)
            click("返回首页");reveal("继续显影");click("继续显影");click("观景台")
            await("editor stand"){find(label)!=null}
            val shared=bounds(label);val sharedWall=bounds("照片墙")
            check(abs(shared.left-sharedWall.left-relativeX)<3 && abs(shared.top-sharedWall.top-relativeY)<3){"Home/editor lost shared canvas placement"}
            click("返回暗房");click("返回首页");click("打开观景台");click(label)
            click("照片操作");click("在暗房编辑")
            await("edit from home stand"){find("照片预览")!=null && find("返回首页")!=null}
            check(vm.currentUri==active)
            return "Empty/session home entry, independent viewing picker cancellation, return/back, current edits preserved, independent+session photos, shared placement across home/editor entry, and edit-photo navigation passed"
        }finally{main{vm.smartAutoEnabled=enabled;activity.finish()};test.waitForIdleSync()}
    }
}
