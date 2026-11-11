package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal class ColorAudit(private val test: Instrumentation, private val output: File) {
    private fun grade(colors: ColorAdjustments, adjusted: Boolean = false, curves: ToneCurves = ToneCurves.IDENTITY) = Grade(
        1.03f, .98f, 1f, 1.1f, .08f, -.03f, 1.08f, -.06f, .04f, .01f, -.01f,
        floatArrayOf(.01f, -.01f, 0f), FloatArray(3), floatArrayOf(0f, 0f, .01f),
        if (adjusted) .6f else 0f, .5f, .7f, curves, colors,
    )

    fun mathAndNative(): String {
        val neutral = ColorAdjustments.NEUTRAL
        check(!EditorParams().toJson().has("color_adjustments"))
        for (i in 0..1000) {
            val rgb = floatArrayOf(i / 1000f, .3f, .8f)
            val copy = rgb.clone()
            neutral.apply(rgb)
            check(rgb.contentEquals(copy))
        }
        for (v in listOf(-100f, 100f)) {
            val grey = floatArrayOf(.4f, .4f, .4f)
            neutral.withVibrance(v).apply(grey)
            check(grey.all { abs(it - .4f) < 1e-7f })
            val saturated = floatArrayOf(1f, 0f, 0f)
            neutral.withVibrance(v).apply(saturated)
            check(saturated.contentEquals(floatArrayOf(1f, 0f, 0f)))
        }
        val soft = floatArrayOf(.6f, .4f, .4f)
        val negative = soft.clone()
        neutral.withVibrance(100f).apply(soft)
        neutral.withVibrance(-100f).apply(negative)
        check(soft[0] - soft[1] > .2f && negative[0] - negative[1] < .2f)
        check(runCatching { HslBand(Float.NaN) }.isFailure)
        check(runCatching { neutral.withVibrance(101f) }.isFailure)
        var all = neutral.withVibrance(37f)
        for (color in HslColor.entries) {
            val next = neutral.with(color, HslBand(35f, -42f, 27f))
            check(ColorAdjustments.fromJson(next.toJson()) == next)
            val params = EditorParams().apply { colorAdjustments = next; cropX = .1f; cropW = .8f }
            check(params.copy().colorAdjustments == next)
            val target = EditorParams().apply { cropX = .2f; cropW = .6f }
            target.copySettingsFrom(params)
            check(target.colorAdjustments == next && target.cropX == .2f && target.cropW == .6f)
            val grey = floatArrayOf(.5f, .5f, .5f)
            next.apply(grey)
            check(grey.all { it == .5f })
            all = all.with(color, HslBand(if (color.ordinal % 2 == 0) 47f else -32f, 24f, -19f))
        }
        for (raw in listOf(
            "{\"version\":99,\"keep\":true}", "[]", "\"invalid\"", "null",
            neutral.toJson().put("vibrance", 500).toString(),
        )) {
            val value = org.json.JSONTokener(raw).nextValue()
            val saved = EditorParams.fromJson(org.json.JSONObject().put("color_adjustments", value))
            check(saved.unreadableColorAdjustments != null)
            check(saved.copy().toJson().get("color_adjustments").toString() == value.toString())
        }
        // A single red band is continuous on both sides of the 0/360-degree seam.
        val red = neutral.with(HslColor.RED, HslBand(50f, 50f, 50f))
        val left = floatArrayOf(.8f, .200001f, .2f)
        val right = floatArrayOf(.8f, .2f, .200001f)
        red.apply(left); red.apply(right)
        check((0..2).all { abs(left[it] - right[it]) < 5e-6f })
        val curve = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .63f))
        val w = 97; val h = 39
        val rgba = ShortArray(w * h * 4) { (it * 373 + 17).toShort() }
        var maximum = 0f
        for (colors in listOf(neutral.withVibrance(-100f), neutral.withVibrance(100f), all,
            neutral.with(HslColor.RED, HslBand(-100f, -100f, 100f)),
            neutral.with(HslColor.BLUE, HslBand(100f, 100f, -100f)))) {
            for (curves in listOf(ToneCurves.IDENTITY, curve)) {
                val g = grade(colors, true, curves)
                check(g.usesNativeGrade)
                val reference = FloatArray(3)
                val strip = FloatArray(w * 32 * 3)
                var y = 0
                while (y < h) {
                    val rows = minOf(32, h - y)
                    g.nativeRows(rgba, y * w * 4, w, h, y, rows, strip)
                    for (i in 0 until w * rows) {
                        val at = (y * w + i) * 4
                        g.gradeAt(rgba[at], rgba[at + 1], rgba[at + 2], reference, i % w, y + i / w, w, h)
                        for (c in 0..2) maximum = maxOf(maximum, abs(reference[c] - strip[i * 3 + c]))
                    }
                    y += rows
                }
                check(maximum < 4e-6f) { "Native/reference color error $maximum" }
                val bytes = ByteArrayOutputStream().also { TiffWriter.writeRgba16(rgba, w, h, g, null, it) }.toByteArray()
                val tiff = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val ifd = tiff.getInt(4)
                val offset = (0 until (tiff.getShort(ifd).toInt() and 65535)).map { ifd + 2 + it * 12 }
                    .first { (tiff.getShort(it).toInt() and 65535) == 273 }.let { tiff.getInt(it + 8) }
                for (i in 0 until w * h) {
                    g.gradeAt(rgba[i * 4], rgba[i * 4 + 1], rgba[i * 4 + 2], reference, i % w, i / w, w, h)
                    for (c in 0..2) check(abs((tiff.getShort(offset + (i * 3 + c) * 2).toInt() and 65535) -
                        (reference[c] * 65535f + .5f).toInt().coerceIn(0, 65535)) <= 1)
                }
                for (space in listOf("sRGB", "Display P3")) {
                    val referenceFrame = ColorPipeline.applyGradeAndHistogram(rgba, w, h, g, space)
                    val (bitmap, histogram) = ColorPipeline.applyGradeToBitmap(rgba, w, h, g, space)
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()
                    check(histogram.r.sum() == w * h)
                    check(pixels.indices.all { i -> (0..2).all { c ->
                        abs(((pixels[i] shr (c * 8)) and 255) - ((referenceFrame.argb[i] shr (c * 8)) and 255)) <= 1
                    } })
                }
            }
        }
        check(!grade(neutral).usesNativeGrade)
        return "Eight-band roundtrip/copy/geometry, grey/saturation/extremes, hue seam, malformed/future data, native/reference max=$maximum, 16-bit TIFF and sRGB/P3 parity"
    }

    fun editor(): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val resolver = context.contentResolver
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids(): Set<Long> = buildSet {
            resolver.query(media, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
        val before = ids()
        val file = File(output, "color-audit-source.png")
        try {
            await { vm.engine != null && !vm.busy }
            val fixture = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            for (y in 0 until 384) for (x in 0 until 512) fixture.setPixel(x, y,
                0xff000000.toInt() or ((x / 2) shl 16) or ((y * 255 / 383) shl 8) or 90)
            file.outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fixture.recycle()
            val uri = Uri.fromFile(file)
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.hasPhoto && !vm.busy && vm.rendered != null }
            click("继续显影"); click("调色"); click("色彩混合")
            val result = vm.lastResult
            val original = vm.rendered
            val slider = reveal("自然饱和度", true)
            val bounds = Rect().also(slider::getBoundsInScreen)
            pointer(bounds, false)
            await { vm.params.colorAdjustments.vibrance != 0f && vm.rendered !== original }
            val edited = vm.params.colorAdjustments
            check(vm.lastResult === result) { "Color edit redeveloped film" }
            check(EditStore.load(context, uri)!!.colorAdjustments == edited)
            val cancelBounds = Rect().also { reveal("自然饱和度", true).getBoundsInScreen(it) }
            pointer(cancelBounds, true)
            SystemClock.sleep(250)
            check(vm.params.colorAdjustments == edited) { "Color slider cancellation committed a partial edit" }
            check(EditStore.load(context, uri)!!.colorAdjustments == edited)
            test.runOnMainSync { vm.undo() }
            await { vm.params.colorAdjustments.isNeutral }
            check(vm.lastResult === result)
            test.runOnMainSync { vm.redo() }
            await { vm.params.colorAdjustments == edited }
            check(vm.lastResult === result)
            for (name in listOf("橙色", "黄色", "绿色", "青色", "蓝色", "紫色", "洋红", "红色")) {
                click(name)
                check(reveal("$name · 色相", true).isEnabled)
            }
            val hue = reveal("红色 · 色相", true)
            check(hue.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 45f)
            }))
            await { vm.params.colorAdjustments[HslColor.RED].hue == 45f }
            test.runOnMainSync { vm.finishColorEdit() }
            check(EditStore.load(context, uri)!!.colorAdjustments[HslColor.RED].hue == 45f)
            var firstPng = 0
            fun pixelHash(uri: Uri): Int {
                val bitmap = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: error("Unreadable PNG")
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                return pixels.contentHashCode()
            }
            for (format in listOf("PNG", "JPEG", "TIFF")) {
                val seen = ids()
                test.runOnMainSync {
                    vm.exportFormat = format; vm.exportColorSpace = "sRGB"; vm.exportRes = 512
                    vm.exportKeepCapture = false; vm.exportKeepLocation = false; vm.export()
                }
                await { !vm.isExporting && vm.renderTaskState.active == null }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                val added = ids() - seen
                check(added.size == 1)
                val saved = android.content.ContentUris.withAppendedId(media, added.single())
                if (format == "PNG") firstPng = pixelHash(saved)
                val bytes = resolver.openInputStream(saved)!!.use { it.readBytes() }
                check(bytes.isNotEmpty())
                if (format != "TIFF") {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid output")
                    check(bitmap.width == 512 && bitmap.height == 384); bitmap.recycle()
                }
            }
            val firstColors = vm.params.colorAdjustments
            val secondFile = File(output, "color-audit-second.png")
            file.copyTo(secondFile, overwrite = true)
            val secondUri = Uri.fromFile(secondFile)
            val secondColors = ColorAdjustments.NEUTRAL.withVibrance(-65f).with(HslColor.RED, HslBand(-40f, -25f, -60f))
            EditStore.save(context, secondUri, EditorParams().apply {
                grainActive = false; glareActive = false; colorAdjustments = secondColors
            })
            test.runOnMainSync { vm.openPhotos(listOf(uri, secondUri)) }
            await { !vm.busy && vm.currentUri == uri && vm.hasPhoto }
            check(vm.params.colorAdjustments == firstColors)
            test.runOnMainSync { vm.selectPhoto(1) }
            await { !vm.busy && vm.currentUri == secondUri && vm.hasPhoto }
            check(vm.params.colorAdjustments == secondColors)
            val secondSeen = ids()
            test.runOnMainSync { vm.exportFormat = "PNG"; vm.export() }
            await { !vm.isExporting && vm.renderTaskState.active == null }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val secondPng = pixelHash(android.content.ContentUris.withAppendedId(media, (ids() - secondSeen).single()))
            check(secondPng != firstPng) { "Distinct color edits produced identical pixels" }
            test.runOnMainSync { vm.selectPhoto(0) }
            await { !vm.busy && vm.currentUri == uri && vm.hasPhoto }
            check(vm.params.colorAdjustments == firstColors)
            val batchSeen = ids()
            test.runOnMainSync { vm.selectAllBatchExportPhotos(true); vm.batchExport() }
            await { !vm.isBatchExporting && vm.renderTaskState.active == null }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val batch = ids() - batchSeen
            check(batch.size == 2)
            check(batch.map { pixelHash(android.content.ContentUris.withAppendedId(media, it)) }.toSet() == setOf(firstPng, secondPng)) {
                "Batch lost per-photo color edits"
            }
            check(vm.params.colorAdjustments == firstColors)
            reveal("自然饱和度", true)
            SystemClock.sleep(500)
            val screenshot = test.uiAutomation.takeScreenshot()!!
            File(output, "color-editor.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            click("复位此颜色")
            await { vm.params.colorAdjustments[HslColor.RED].isNeutral }
            check(vm.params.colorAdjustments.vibrance == edited.vibrance)
            click("复位全部色彩")
            await { vm.params.colorAdjustments.isNeutral }
            test.runOnMainSync {
                vm.params.unreadableColorAdjustments = "{\"version\":99}"
                vm.export()
            }
            check(!vm.isExporting && vm.errorMessage != null)
            test.runOnMainSync { vm.dismissError(); vm.params.unreadableColorAdjustments = null }
        } finally {
            (ids() - before).forEach { resolver.delete(android.content.ContentUris.withAppendedId(media, it), null, null) }
            test.runOnMainSync { activity.finish() }
        }
        return "Real slider drag/cancel, eight color selectors, accessibility adjustment, sidecar/undo/redo without redevelop, per-photo switching, exact single/batch PNG pixels, reset and actual JPEG/PNG/TIFF exports"
    }

    private fun pointer(rect: Rect, cancel: Boolean) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, rect.exactCenterY(), 0)
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        val end = rect.left + rect.width() * if (cancel) .2f else .75f
        send(MotionEvent.ACTION_DOWN, rect.exactCenterX())
        send(MotionEvent.ACTION_MOVE, end)
        SystemClock.sleep(100)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
    }

    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun find(label: String, node: AccessibilityNodeInfo? = root(), range: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        if ((node.text?.toString() == label || node.contentDescription?.toString() == label) && (!range || node.rangeInfo != null)) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i), range)?.let { return it }
        return null
    }
    private fun reveal(label: String, range: Boolean = false): AccessibilityNodeInfo {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
        repeat(20) {
            find(label, range = range)?.let { node ->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync(); SystemClock.sleep(150)
                return find(label, range = range) ?: node
            }
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isScrollable && node.performAction(direction)) return true
                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                return false
            }
            scroll(root()); SystemClock.sleep(100)
        }
        }
        fun dump(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null) return ""
            return " ".repeat(depth) + node.toString() + '\n' +
                (0 until node.childCount).joinToString("") { dump(node.getChild(it), depth + 1) }
        }
        File(output, "color-failure-tree.txt").writeText(dump(root()))
        test.uiAutomation.takeScreenshot()?.let { shot ->
            File(output, "color-failure.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
        error("Missing color control: $label")
    }
    private fun click(label: String) {
        var node = reveal(label)
        while (!node.isClickable) node = node.parent ?: error(label)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { label }
        test.waitForIdleSync(); SystemClock.sleep(200)
    }
    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Color audit timed out" }
            SystemClock.sleep(25)
        }
    }
}
