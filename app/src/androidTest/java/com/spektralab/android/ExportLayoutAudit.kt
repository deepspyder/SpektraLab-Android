package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File

/** Layout regression for the advanced-settings header beside the export quick actions. */
internal class ExportLayoutAudit(private val test: Instrumentation, private val output: File) {
    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.contentDescription?.toString()==label || node.text?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it}
        return null
    }
    private fun await(label:String, ready:()->Boolean){val start=SystemClock.elapsedRealtime()
        while(!ready()){check(SystemClock.elapsedRealtime()-start<30000){"Timeout $label"};Thread.sleep(60)}}
    private fun scroll(node:AccessibilityNodeInfo?,action:Int):Boolean {
        if(node==null)return false
        if(node.isScrollable && node.performAction(action))return true
        for(i in 0 until node.childCount)if(scroll(node.getChild(i),action))return true
        return false
    }
    private fun reveal(label:String){
        for(action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))repeat(18){
            if(find(label)?.isVisibleToUser==true)return
            scroll(root(),action);test.waitForIdleSync();Thread.sleep(100)
        }
        error("Missing $label")
    }
    private fun click(label:String){reveal(label);var node=checkNotNull(find(label))
        while(!node.isClickable)node=node.parent?:error("Not clickable $label")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync();Thread.sleep(350)}
    private fun bounds(label:String)=Rect().also{checkNotNull(find(label)).getBoundsInScreen(it)}
    private fun capture(name:String){Thread.sleep(200);test.waitForIdleSync()
        checkNotNull(test.uiAutomation.takeScreenshot()).also { bitmap ->
            File(output,"export_layout_$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
    fun run():String {
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        await("engine"){vm.engine!=null && !vm.busy}
        click("更多操作");click("测试图")
        await("image"){vm.hasPhoto && !vm.busy}
        click("导出")
        val action="导出 DI (16-bit TIFF) · 16 位中间文件"
        reveal(action)
        // Reveal lower controls completely; the header remains above them and can scroll as a unit.
        scroll(root(),AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(200)
        val advanced=bounds("高级设置");val quick=bounds(action)
        if(!advanced.isEmpty){
            check(quick.top-advanced.bottom>=11*test.targetContext.resources.displayMetrics.density){"Header overlaps quick actions: $advanced / $quick"}
        }
        capture("collapsed")
        click("高级设置");await("expanded contents"){find("位深")!=null}
        reveal("位深");capture("expanded")
        reveal("保存配方");capture("recipe")
        reveal(action);capture("quick_actions")
        click("高级设置");await("collapsed contents"){find("文件命名")==null}
        reveal(action);capture("recollapsed")
        check(find("文件命名")==null){"Collapsed settings remained composed"}
        check(find("导出当前照片")?.isVisibleToUser==true){"Pinned export button disappeared"}
        test.runOnMainSync{activity.finish()}
        return "Advanced settings and quick-action regions separated; expand/collapse, scrolling, recipe access and pinned export button passed"
    }
}
