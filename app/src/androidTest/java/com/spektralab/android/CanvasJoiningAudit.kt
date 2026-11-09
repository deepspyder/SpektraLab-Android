package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.io.File
import kotlin.math.abs

/** Real touch gestures and screenshot pixel checks on the production viewing-stand component. */
internal class CanvasJoiningAudit(private val test: Instrumentation, private val output: File) {
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String, condition:()->Boolean){
        val start=SystemClock.elapsedRealtime()
        while(!condition()){check(SystemClock.elapsedRealtime()-start<20000){"Timeout $label"};Thread.sleep(60)}
    }
    private fun root():AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun find(label:String,node:AccessibilityNodeInfo?=root()):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.contentDescription?.toString()==label || node.text?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it}
        return null
    }
    private fun bounds(label:String):android.graphics.Rect {
        await(label){find(label)!=null}
        return android.graphics.Rect().also{checkNotNull(find(label)).getBoundsInScreen(it)}
    }
    private fun click(label:String){
        await(label){find(label)!=null};var node=checkNotNull(find(label))
        while(!node.isClickable)node=node.parent?:error("Not clickable $label")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync();Thread.sleep(400)
    }
    private fun capture(name:String,settle:Long=350):Bitmap {
        Thread.sleep(settle);test.waitForIdleSync()
        return checkNotNull(test.uiAutomation.takeScreenshot()).also { bitmap ->
            File(output,"join_$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    private fun drag(from:Offset,to:Offset,hold:(()->Unit)?=null){
        val start=SystemClock.uptimeMillis()
        fun inject(action:Int,point:Offset){
            val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,point.x,point.y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            check(test.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN,from)
        for(step in 1..20){inject(MotionEvent.ACTION_MOVE,from+(to-from)*(step/20f));Thread.sleep(16)}
        hold?.invoke()
        inject(MotionEvent.ACTION_UP,to)
        Thread.sleep(400)
    }
    private fun rect(left:Float,top:Float,width:Float=100f,height:Float=60f)=Rect(left,top,left+width,top+height)
    private fun geometryChecks(){
        val base=WallFrame("a",rect(0f,0f,200f,120f))
        val cases=listOf(rect(205f,8f),rect(-105f,5f),rect(7f,126f),rect(9f,-67f))
        for((index,bounds) in cases.withIndex()){
            val join=checkNotNull(findWallJoin(WallFrame("b",bounds),listOf(base),18f))
            val snapped=Rect(join.position,bounds.size)
            check(when(index){0->snapped.left==200f;1->snapped.right==0f;2->snapped.top==120f;else->snapped.bottom==0f})
        }
        check(findWallJoin(WallFrame("b",rect(60f,30f)),listOf(base),18f)==null){"Deep overlap snapped away"}
        check(findWallJoin(WallFrame("b",rect(500f,0f)),listOf(base),18f)==null)
        check(findWallJoin(base,listOf(base),18f)==null)
        for(scale in listOf(.35f,1f,6f)){
            check(findWallJoin(WallFrame("b",rect(200f+9f/scale,4f)),listOf(base),18f/scale)!=null)
            check(findWallJoin(WallFrame("b",rect(200f+22f/scale,4f)),listOf(base),18f/scale)==null)
        }
        val state=FilmCanvasState()
        val join=checkNotNull(findWallJoin(WallFrame("b",cases[0]),listOf(base),18f))
        state.movePhoto("b",cases[0].topLeft,Offset.Zero);state.previewJoin(join);state.endDrag(true)
        check(state.position("b",Offset.Zero)==cases[0].topLeft)
        state.joinFraction=.5f
        check((state.position("b",Offset.Zero)-join.position).getDistance()>0)
        val saved=with(FilmCanvasState.Saver){object:SaverScope{override fun canBeSaved(value:Any)=true}.save(state)}
        val restored=checkNotNull(FilmCanvasState.Saver.restore(checkNotNull(saved)))
        check(restored.photoPositions["b"]==join.position && restored.joinMotion==null)
        val visible=state.position("b",Offset.Zero)
        state.beginDrag("b");check(state.position("b",Offset.Zero)==visible && state.joinMotion==null)
        state.movePhoto("b",visible,Offset(90f,20f));state.endDrag(false)
        check(state.photoPositions["b"]==visible+Offset(90f,20f))
        check(wallOverlaps(rect(50f,20f),listOf(base.bounds)).single().width==100f)
        check(wallOverlaps(rect(300f,0f),listOf(base.bounds)).isEmpty())
    }
    fun run():String {
        geometryChecks()
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val red=Bitmap.createBitmap(480,320,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(230,80,55))}
        val blue=Bitmap.createBitmap(480,320,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(45,98,220))}
        fun photo(key:String,bitmap:Bitmap)=ViewingPhoto(key,bitmap,bitmap,"$key.jpg",sourceWidth=bitmap.width,sourceHeight=bitmap.height,sourceFormat="JPG")
        val photos=mutableStateOf(listOf(photo("a",red),photo("b",blue)))
        val owned=mutableListOf(red,blue)
        val aLabel="查看第 1 张 a.jpg";val bLabel="查看第 2 张 b.jpg"
        try {
            main { activity.setContent { SpektraTheme {
                ViewingStand(photos.value,importing=false,importCompleted=0,importTotal=0,actionsEnabled=true,
                    onLoadThumbnail={},onLoadDetail={},onLoadWallDetails={},
                    onImport={},onRemove={},onEdit={},onBack={})
            } } }
            await("stand"){find(aLabel)!=null && find(bLabel)!=null}
            // On short landscape windows, make room for two vertical photographs before dragging.
            var zoomSteps=0
            while(bounds("照片墙").height()<bounds(aLabel).height()*2.8f && zoomSteps<4){
                click("缩小画布");zoomSteps++
            }
            capture("start").recycle()
            fun zoom()=find("照片墙")!!.rangeInfo.current
            fun scale()=(bounds("照片墙").width()-24*test.targetContext.resources.displayMetrics.density)/600f*zoom()
            fun dragTo(left:Float,top:Float,hold:(()->Unit)?=null){
                val b=bounds(bLabel)
                drag(Offset(b.exactCenterX(),b.exactCenterY()),Offset(b.exactCenterX()+left-b.left,b.exactCenterY()+top-b.top),hold)
            }
            val a=bounds(aLabel);val s=scale()
            dragTo(a.right+8*s,a.top+6*s){
                await("snap preview"){find("松手拼接")!=null};capture("preview").recycle()
            }
            val joined=bounds(bLabel)
            check(abs(joined.left-a.right)<3 && abs(joined.top-a.top)<3){"Horizontal snap: $a / $joined"}
            capture("horizontal").recycle()
            dragTo(a.left+55*s,a.top+25*s){capture("overlap_drag").recycle()}
            val stacked=bounds(bLabel)
            check(abs(stacked.left-(a.left+55*s))<4 && abs(stacked.top-(a.top+25*s))<4){"Deliberate overlap snapped away"}
            check(find(bLabel)?.stateDescription.toString().contains("叠片透光"))
            val pixels=capture("overlap")
            val shared=pixels.getPixel((a.left+110*s).toInt(),(a.top+65*s).toInt())
            val upper=pixels.getPixel((stacked.right-15*s).toInt(),(stacked.top+15*s).toInt())
            check(Color.red(shared) in 95..155 && Color.blue(shared) in 125..180){"Overlap not translucent: $shared"}
            check(abs(Color.red(upper)-45)<8 && abs(Color.blue(upper)-220)<8){"Non-overlap changed opacity: $upper"}
            pixels.recycle()
            // The loupe must own a drag even when its centre starts over a joined/stacked photo.
            val fixedA=bounds(aLabel);val fixedB=bounds(bLabel)
            click("打开放大镜")
            val lensStart=bounds("拖动放大镜");val wall=bounds("照片墙")
            val blank=Offset(wall.right-lensStart.width()/2f-16f,wall.bottom-lensStart.height()/2f-16f)
            drag(Offset(lensStart.exactCenterX(),lensStart.exactCenterY()),blank)
            check(bounds(aLabel)==fixedA && bounds(bLabel)==fixedB){"Loupe drag moved underlying photos"}
            val lensOnBlank=bounds("拖动放大镜")
            check(lensOnBlank.centerX()>lensStart.centerX()+20){"Loupe cannot leave the photograph"}
            drag(Offset(lensOnBlank.exactCenterX(),lensOnBlank.exactCenterY()),
                Offset(fixedB.exactCenterX(),fixedB.exactCenterY()))
            val lensOnPhoto=bounds("拖动放大镜")
            drag(Offset(lensOnPhoto.exactCenterX(),lensOnPhoto.exactCenterY()),blank)
            check(bounds(aLabel)==fixedA && bounds(bLabel)==fixedB){"Repeated loupe drag altered photo positions"}
            capture("loupe_free").recycle();click("关闭放大镜")
            click("关闭灯光");capture("dark_overlap").recycle();click("打开灯光")
            dragTo(a.left+245*s,a.top+25*s)
            check(!find(bLabel)?.stateDescription.toString().contains("叠片透光"))
            dragTo(a.left+7*s,a.bottom+8*s)
            val vertical=bounds(bLabel)
            check(abs(vertical.top-a.bottom)<3 && abs(vertical.left-a.left)<3){"Vertical snap failed: $a / $vertical"}
            capture("vertical").recycle()
            val previousZoom=zoom();click("放大画布");check(abs(zoom()-previousZoom*1.25f)<.01f)
            click("重置画布");val preserved=bounds(bLabel)
            val resetA=bounds(aLabel)
            check(abs(preserved.left-resetA.left)<3 && abs(preserved.top-resetA.bottom)<3)
            repeat(zoomSteps){click("缩小画布")}

            // Actual local photo crops for a production-screen visual review, not generated art.
            val decoded=test.context.assets.open("13mb.jpg").use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=8})}!!
            val upright=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply{postRotate(90f)},true)
            decoded.recycle();owned.add(upright)
            val crop=Bitmap.createBitmap(upright,0,upright.height/4,upright.width,upright.height/2);owned.add(crop)
            main { photos.value=listOf(photo("a",crop),photo("b",upright)) }
            Thread.sleep(300)
            val realA=bounds(aLabel);val realS=scale()
            dragTo(realA.right+8*realS,realA.top+6*realS)
            capture("photo_join").recycle()
            dragTo(realA.left+80*realS,realA.top+35*realS)
            capture("photo_overlap").recycle()
            click("关闭灯光");capture("photo_dark").recycle();click("打开灯光")
            // The same floating tool must not pan the image inside a zoomed immersive viewer.
            click(aLabel);await("viewer"){find("沉浸照片 a.jpg")!=null}
            val view=bounds("沉浸照片 a.jpg");val center=Offset(view.exactCenterX(),view.exactCenterY())
            fun tapPoint(){
                val time=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                    val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,center.x,center.y,0)
                    event.source=InputDevice.SOURCE_TOUCHSCREEN;test.uiAutomation.injectInputEvent(event,true);event.recycle()
                }
            }
            tapPoint();Thread.sleep(80);tapPoint();Thread.sleep(400)
            await("viewer zoom"){find("还原 220%")!=null}
            click("打开放大镜")
            val viewerLensBefore=bounds("拖动放大镜")
            val expectedLensSize=(128*test.targetContext.resources.displayMetrics.density).toInt()
            check(abs(viewerLensBefore.width()-expectedLensSize)<3 && abs(viewerLensBefore.height()-expectedLensSize)<3){
                "Viewer loupe was clipped by a wrong coordinate origin: $viewerLensBefore"
            }
            val viewerBefore=capture("viewer_loupe_before")
            drag(Offset(viewerLensBefore.exactCenterX(),viewerLensBefore.exactCenterY()),
                Offset(viewerLensBefore.exactCenterX()+view.width()*.18f,viewerLensBefore.exactCenterY()+view.height()*.12f))
            val viewerLensAfter=bounds("拖动放大镜");check(viewerLensBefore!=viewerLensAfter)
            val viewerAfter=capture("viewer_loupe_after")
            var checked=0
            for(y in view.top+20 until view.bottom-20 step 13)for(x in view.left+20 until view.right-20 step 13){
                // Exclude the loupe's soft elevation shadow as well as its circular content.
                val padding=(48*test.targetContext.resources.displayMetrics.density).toInt()
                fun near(rect:android.graphics.Rect)=x in rect.left-padding..rect.right+padding && y in rect.top-padding..rect.bottom+padding
                if(!near(viewerLensBefore) && !near(viewerLensAfter)){
                    check(viewerBefore.getPixel(x,y)==viewerAfter.getPixel(x,y)){"Loupe drag panned the zoomed viewer"};checked++
                }
            }
            viewerBefore.recycle();viewerAfter.recycle();check(checked>40)
            click("关闭放大镜");click("关闭沉浸式观片")
            return "Four-edge joining, overlap alpha, lamp and zoom/reset passed; loupe repeatedly leaves/returns to stacked photos without moving either photo; zoomed viewer loupe moves while image pixels remain fixed"
        } finally {
            main { activity.finish() };test.waitForIdleSync();Thread.sleep(200)
            owned.forEach{if(!it.isRecycled)it.recycle()}
        }
    }
}
