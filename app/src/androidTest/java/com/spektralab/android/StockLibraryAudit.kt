package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.library.stockArtworkResource
import java.io.File

internal class StockLibraryAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var vm: EditorViewModel
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(label: String, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < 30000) { "Timeout $label; ${vm.errorMessage}" }
            Thread.sleep(40)
        }
    }
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(label, node.getChild(index))?.let { return it }
        return null
    }
    private fun click(label: String) {
        // Filter choices are horizontal lazy items; exercise their actual scrolling semantics.
        if (find(label) == null) {
            val listLabel = if (label in listOf("全部", "彩色负片", "正片", "黑白", "电影胶片")) "胶片分类"
                else if (find("相纸列表") != null) "相纸列表" else "胶片列表"
            for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                for (step in 0 until 12) {
                    if (find(label) != null) break
                    val list = find(listLabel) ?: break
                    if (!list.performAction(direction)) break
                    test.waitForIdleSync()
                    Thread.sleep(200)
                }
                if (find(label) != null) break
            }
        }
        await(label) { find(label) != null }
        var node = checkNotNull(find(label))
        while (!node.isClickable) node = node.parent ?: error("Not clickable $label")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled $label" }
        test.waitForIdleSync()
        Thread.sleep(300)
    }
    private fun capture(name: String) {
        test.waitForIdleSync()
        test.uiAutomation.waitForIdle(250, 5000)
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        File(output, "stock_library_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun assertNoSearch(node: AccessibilityNodeInfo? = root()) {
        if (node == null) return
        check(!node.isEditable) { "The removed search input is still present" }
        check(!node.contentDescription?.toString().orEmpty().contains("搜索")) { "Search action remains" }
        for (i in 0 until node.childCount) assertNoSearch(node.getChild(i))
    }
    fun run(): String {
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            await("home") { vm.engine != null && !vm.busy && vm.films.size == 25 && find("更多操作") != null }
            check(vm.films.single { it.id == "cinestill_800t" }.derivedFrom == "kodak_vision3_500t")
            // Every film and paper uses a bounded transparent resource, including former placeholders.
            (vm.films + vm.papers).forEach { stock ->
                val resource = checkNotNull(stockArtworkResource(stock.id)) { "Missing product image ${stock.id}" }
                val bitmap = checkNotNull(BitmapFactory.decodeResource(test.targetContext.resources, resource))
                check(bitmap.width == 384 && bitmap.height == 448) { "Unexpected runtime size ${stock.id}" }
                check(bitmap.hasAlpha() && android.graphics.Color.alpha(bitmap.getPixel(0, 0)) == 0) {
                    "Artwork has an opaque background ${stock.id}"
                }
                bitmap.recycle()
            }
            val input = File(output, "stock-filter-preview.jpg")
            val sample = test.context.assets.open("13mb.jpg").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
            } ?: error("Cannot decode preview fixture")
            val upright = Bitmap.createBitmap(sample, 0, 0, sample.width, sample.height,
                android.graphics.Matrix().apply { postRotate(90f) }, true)
            input.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (upright !== sample) upright.recycle()
            sample.recycle()
            val uri = android.net.Uri.fromFile(input)
            EditStore.save(test.targetContext, uri, EditorParams().apply { grainActive = false })
            main { vm.openPhotos(listOf(uri)) }
            await("source photo") { !vm.busy && vm.hasPhoto && vm.lastResult != null }
            click("继续显影")
            await("editor") { vm.lastResult != null && !vm.busy && find("胶片与相纸") != null }
            click("胶片与相纸")
            await("catalogue") { find("胶片列表") != null }
            assertNoSearch()
            check(find("胶片实时预览") != null) { "Photo preview is missing from the filter workspace" }
            val beforeCompare = vm.lastResult
            click("前后对比")
            await("original comparison") { find("前后对比分界线") != null && find("关闭前后对比") != null }
            check(find("Before · 原图") != null && find("After · 冲印") != null)
            click("关闭前后对比")
            check(vm.lastResult === beforeCompare) { "Comparing rerendered the photo" }
            capture("films")
            val beforeFilm = vm.lastResult
            click("Kodak Gold 200")
            await("film applied") { vm.params.filmId == "kodak_gold_200" && vm.lastResult !== beforeFilm }
            click("相纸")
            await("paper catalogue") { find("相纸列表") != null }
            assertNoSearch()
            capture("papers")
            val beforePaper = vm.lastResult
            click("Fujifilm Crystal Archive Type II")
            await("paper applied") { vm.params.paperId == "fujifilm_crystal_archive_typeii" && vm.lastResult !== beforePaper }
            capture("paper_selected")
            click("胶片库"); click("正片")
            capture("positive")
            click("Fujifilm Provia 100F")
            await("positive selection") { vm.params.filmId == "fujifilm_provia_100f" }
            click("黑白")
            capture("monochrome")
            click("Ilford HP5 Plus 400")
            await("monochrome pairing") { vm.params.filmId == "ilford_hp5_plus_400" && vm.params.paperId == "ilford_multigrade_iv_rc" }
            click("相纸")
            await("black and white papers") { find("1 款兼容材料") != null }
            check(find("Fujifilm Crystal Archive Type II") == null) { "Incompatible color paper remains in list" }
            capture("monochrome_paper")
            click("应用胶片与相纸")
            await("editor return") { find("胶片列表") == null && find("相纸列表") == null }
            click("相纸")
            await("direct paper entry") { find("相纸列表") != null }
            capture("paper_entry")
            click("图片来源")
            await("source credits") { find("关闭") != null }
            capture("credits")
            click("关闭"); click("返回暗房")
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            return "Photo-first preview and comparison; horizontal film/paper scrolling and selection renders; ${vm.films.size + vm.papers.size} transparent stock resources including CineStill; compatibility, direct entry and Done/back passed"
        } finally { main { activity.finish() } }
    }
}
