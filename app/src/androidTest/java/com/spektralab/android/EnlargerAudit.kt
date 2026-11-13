package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.panels.PrintPanel
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject

/** Real print controls plus independent full-render and asynchronous-boundary references. */
internal class EnlargerAudit(private val test: Instrumentation, private val output: File) {
    fun native(): String {
        val w = 192; val h = 128
        val input = FloatArray(w * h * 3) { i ->
            val x = i / 3 % w; val y = i / 3 / w
            if (x in 78..113 && y in 45..82) 16f else .008f + .3f * (x + y) / (w + h)
        }
        val base = EditorParams().apply { grainActive = false; glareActive = false; autoExposure = false }
        val changes = linkedMapOf<String, EditorParams.() -> Unit>(
            "exposure.min" to { printExposure = .25f }, "exposure.bright" to { printExposure = .5f },
            "exposure.dark" to { printExposure = 2f }, "exposure.max" to { printExposure = 4f },
            "yellow.min" to { yFilterShift = -1f }, "yellow.max" to { yFilterShift = 1f },
            "magenta.min" to { mFilterShift = -1f }, "magenta.max" to { mFilterShift = 1f },
            "combined" to { printExposure = .8f; yFilterShift = .3f; mFilterShift = -.2f },
            "preflash" to { preflashExposure = .02f }, "edr" to { extendedDynamicRange = true },
            "paper" to { paperId = "fujifilm_crystal_archive_typeii" },
        )
        val records = JSONArray()
        EngineClient.create(test.targetContext).use { engine ->
            fun mean(data: ShortArray): Double = data.indices.filter { it % 4 < 3 }.sumOf { (data[it].toInt() and 65535).toDouble() } / (data.size / 4 * 3)
            engine.open(input, w, h, 3, base.openJson(w, h))
            val original = engine.render("full")
            val means = mutableMapOf<String, Double>()
            for ((name, edit) in changes) {
                engine.open(input, w, h, 3, base.openJson(w, h)); engine.render("full")
                val params = base.copy().apply(edit)
                val start = SystemClock.elapsedRealtime()
                engine.setChangedParams(params.deltaJson(w, h))
                val reprint = engine.render("full")
                val ms = SystemClock.elapsedRealtime() - start
                check(reprint.reprint && reprint.negativeWasCached) { "$name rebuilt the film negative" }
                engine.open(input, w, h, 3, params.openJson(w, h))
                val reference = engine.renderFull("full")
                check(reprint.rgba16.contentEquals(reference.rgba16)) { "$name differs from independent fresh full render" }
                check(!reprint.rgba16.contentEquals(original.rgba16)) { "$name has no effect on the active HDR fixture" }
                means[name] = mean(reprint.rgba16)
                records.put(JSONObject().put("control", name).put("reprint_ms", ms).put("mean_u16", means[name]))
            }
            check(means.getValue("exposure.bright") > means.getValue("exposure.dark")) { "Print exposure direction is reversed" }
        }
        File(output, "enlarger-native.json").writeText(records.toString(2))
        return "12 print cases: exposure .25..4, Y/M -40..40CC, combined, preflash, EDR, paper; every cached reprint equals a fresh full U16 render and changes pixels; lower print exposure is brighter"
    }

    fun controls(): String = withPhoto("controls") { vm, _, activity ->
        test.runOnMainSync { activity.setContent { TheiaTheme { Surface {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { PrintPanel(vm) }
        } } } }
        await { find("打印曝光(越小越亮)") != null }
        fun slider(label: String, value: Float) {
            fun range(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.rangeInfo != null && node.contentDescription?.toString() == label) return node
                for (i in 0 until node.childCount) range(node.getChild(i))?.let { return it }
                return null
            }
            val node = requireNotNull(range(root())) { "Missing slider range: $label" }
            check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
            })) { "Slider action failed: $label" }
            idle(vm)
            SystemClock.sleep(400)
        }
        slider("打印曝光(越小越亮)", .5f)
        val brighter = vm.lastResult!!.rgba16.clone()
        slider("打印曝光(越小越亮)", 2f)
        check(!vm.lastResult!!.rgba16.contentEquals(brighter))
        slider("滤镜 Y(黄/蓝)", .25f)
        slider("滤镜 M(品红/绿)", -.3f)
        check(vm.params.printExposure == 2f && vm.params.yFilterShift == .25f && vm.params.mFilterShift == -.3f)
        val edited = EditSnapshot.capture(vm.params)
        test.runOnMainSync { vm.undo() }; idle(vm)
        check(vm.params.mFilterShift == 0f && vm.params.yFilterShift == .25f)
        test.runOnMainSync { vm.redo() }; idle(vm)
        check(EditSnapshot.capture(vm.params) == edited)
        test.runOnMainSync { vm.selectPaper("fujifilm_crystal_archive_typeii") }; idle(vm)
        val params = vm.params.copy()
        EngineClient.create(test.targetContext).use { reference ->
            val source = requireNotNull(vm.sourceBitmap)
            reference.open(ColorPipeline.bitmapToLinearSrgb(source), source.width, source.height, 3, params.openJson(source.width, source.height))
            check(vm.lastResult!!.rgba16.contentEquals(reference.render("live").rgba16)) { "Approximate paper LUT replaced final print" }
        }
        val uri = requireNotNull(vm.currentUri)
        check(EditSnapshot.capture(requireNotNull(EditStore.load(test.targetContext, uri))) == EditSnapshot.capture(params))
        test.runOnMainSync { vm.openPhoto(uri) }; idle(vm)
        check(EditSnapshot.capture(vm.params) == EditSnapshot.capture(params))
        test.runOnMainSync { vm.solve() }; idle(vm)
        check(vm.params.mFilterShift == 0f && vm.params.yFilterShift == 0f && vm.params.printExposure == 2f)
        check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
        capture("controls")
        click("乳剂与暗房效应")
        reveal("扩展动态范围")
        check(toggle("扩展动态范围").isEnabled)
        click("扩展动态范围"); idle(vm)
        check(vm.params.extendedDynamicRange)
        test.runOnMainSync { vm.selectFilm("kodak_tri_x_400") }; idle(vm)
        reveal(test.targetContext.getString(R.string.print_edr_unavailable))
        check(!toggle("扩展动态范围").isEnabled && !toggle("扩展动态范围").isChecked)
        toggle("扩展动态范围").performAction(AccessibilityNodeInfo.ACTION_CLICK)
        check(vm.params.extendedDynamicRange && !JSONObject(vm.params.deltaJson(640, 480)).getBoolean("extended_dynamic_range"))
        capture("edr-bw")
        val positive = vm.films.first { it.type == "positive" }.id
        test.runOnMainSync { vm.selectFilm(positive) }; idle(vm)
        reveal(test.targetContext.getString(R.string.print_edr_unavailable))
        check(!toggle("扩展动态范围").isEnabled && !toggle("扩展动态范围").isChecked)
        capture("edr-positive")
        test.runOnMainSync { vm.selectFilm("kodak_portra_400") }; idle(vm)
        reveal("扩展动态范围")
        check(toggle("扩展动态范围").isEnabled && toggle("扩展动态范围").isChecked)
        check(vm.params.extendedDynamicRange && JSONObject(vm.params.deltaJson(640, 480)).getBoolean("extended_dynamic_range"))
        capture("edr-color")
        "Production exposure/Y/M change pixels, undo/redo, exact final paper print, saved/reopened values, auto-balance reset and disabled/non-clickable BW/positive EDR with retained color preference passed"
    }

    fun boundaries(): String = withPhoto("boundaries") { vm, uri, _ ->
        val resolver = test.targetContext.contentResolver
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids() = resolver.query(media, arrayOf("_id"), "_display_name LIKE ?", arrayOf("Theia_%"), null)!!.use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
        val before = ids()
        val failures = mutableListOf<String>()
        try {
            var exportGuard = false
            test.runOnMainSync {
                vm.exportFormat = "PNG"; vm.exportRes = 384; vm.export()
                check(vm.isExporting)
                vm.solve(); vm.measureLatitude()
                exportGuard = !vm.busy
            }
            idle(vm)
            if (!exportGuard) failures += "Auto-balance/latitude started a second analysis while export owned the engine"
            test.runOnMainSync { vm.measureLatitude(); vm.openPhoto(uri) }
            idle(vm)
            if (vm.latitudeReadout != null || vm.latitudeCanApply) failures += "Late latitude result crossed the photo boundary"
            check(failures.isEmpty()) { failures.joinToString("; ") }
            "Export blocks automatic native analysis; late scene-latitude result cannot attach to a reopened photo"
        } finally {
            (ids() - before).forEach { resolver.delete(ContentUris.withAppendedId(media, it), null, null) }
        }
    }

    private fun <T> withPhoto(label: String, block: (EditorViewModel, Uri, MainActivity) -> T): T {
        val file = File(output, "enlarger-$label-${System.nanoTime()}.png")
        val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        image.setPixels(IntArray(640 * 480) { i -> -0x1000000 or ((i % 640 * 240 / 640) shl 16) or ((i / 640 * 235 / 480) shl 8) or 87 }, 0, 640, 0, 0, 640, 480)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        val uri = Uri.fromFile(file)
        EditStore.save(test.targetContext, uri, EditorParams().apply { grainActive = false; glareActive = false })
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        val auto = vm.smartAutoEnabled
        try {
            test.runOnMainSync { vm.smartAutoEnabled = false; vm.openPhotos(listOf(uri)) }; idle(vm)
            return block(vm, uri, activity)
        } finally { test.runOnMainSync { vm.smartAutoEnabled = auto; activity.finish() } }
    }

    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun toggle(label: String): AccessibilityNodeInfo {
        var node = requireNotNull(find(label))
        while (!node.isCheckable) node = requireNotNull(node.parent) { "Missing switch semantics: $label" }
        return node
    }
    private fun click(label: String) {
        reveal(label)
        var node = requireNotNull(find(label))
        while (!node.isClickable) node = requireNotNull(node.parent)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        test.waitForIdleSync(); SystemClock.sleep(150)
    }
    private fun reveal(label: String) {
        repeat(30) {
            find(label)?.let { node ->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync(); SystemClock.sleep(80)
                if (find(label)?.isVisibleToUser == true) return
            }
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                return false
            }
            scroll(root()); SystemClock.sleep(60)
        }
        error("Control not visible: $label")
    }
    private fun capture(name: String) {
        test.waitForIdleSync(); SystemClock.sleep(350)
        val image = requireNotNull(test.uiAutomation.takeScreenshot())
        try { File(output, "enlarger-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
    private fun idle(vm: EditorViewModel) = await {
        vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting && vm.lastResult != null && vm.renderTaskState.active == null &&
            listOf("renderJob", "wbJob", "cameraWbJob", "photoLoadJob", "stockPreviewJob").none { name ->
                val field = EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(vm) as? Job)?.isActive == true
            }
    }
    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 90000) { "Enlarger audit timeout" }; SystemClock.sleep(25) }
    }
}
