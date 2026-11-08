package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File

/** Checks the actual UI and published image dimensions, not just the selection collection. */
internal class BatchExportAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var vm: EditorViewModel
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(label: String, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < 60000) { "Timeout: $label; error=${vm.errorMessage}" }
            Thread.sleep(30)
        }
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(label, node.getChild(index))?.let { return it }
        return null
    }
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun button(label: String): AccessibilityNodeInfo {
        var node = checkNotNull(find(label)) { "Missing $label" }
        while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
        return node
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        val node = button(label)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled: $label" }
        test.waitForIdleSync()
        Thread.sleep(250)
    }
    private fun capture(name: String) {
        test.waitForIdleSync()
        test.uiAutomation.waitForIdle(250, 5000)
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        File(output, "batch_selection_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun fixture(name: String, width: Int, height: Int, channel: Int): Uri {
        val file = File(output, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            val values = intArrayOf(40 + x * 100 / width, 40 + y * 100 / height, 70)
            values[channel] = 180 + x * 60 / width
            android.graphics.Color.rgb(values[0], values[1], values[2])
        }, 0, width, 0, 0, width, height)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file).also { uri ->
            EditStore.save(test.targetContext, uri, EditorParams().apply { grainActive = false })
        }
    }
    private fun published(): Map<Long, Pair<Int, Int>> = buildMap {
        test.targetContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getInt(1) to cursor.getInt(2))
        }
    }
    private fun outputDimensions(before: Set<Long>): List<Pair<Int, Int>> {
        return (published().keys - before).map { id ->
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            test.targetContext.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            bounds.outWidth to bounds.outHeight
        }
    }

    fun run(): String {
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            await("engine") { vm.engine != null && !vm.busy }
            val first = fixture("batch-red.png", 96, 64, 0)
            val second = fixture("batch-green.png", 80, 60, 1)
            val third = fixture("batch-blue.png", 64, 96, 2)
            main { vm.exportFormat = "PNG"; vm.exportRes = 0; vm.openPhotos(listOf(first, second, third)) }
            await("open") { vm.errorMessage != null || vm.hasPhoto && !vm.busy }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            check(vm.selectedBatchExportPhotos == listOf(first, second, third))
            click("继续显影")
            click("导出")
            click("批量导出 · 3")
            val current = vm.currentUri
            val preview = vm.rendered
            click("导出照片 2：batch-green.png")
            check(vm.selectedBatchExportPhotos == listOf(first, third))
            check(vm.currentUri == current && vm.rendered === preview) { "Export selection changed the editor" }
            click("单张导出"); click("批量导出 · 3")
            check(vm.selectedBatchExportPhotos == listOf(first, third)) { "Mode switch lost selection" }
            await("thumbnails") { vm.thumbnails.size == 3 }
            capture("two_of_three")
            val before = published().keys
            click("导出所选照片 (2 张)")
            await("selected batch") { !vm.isBatchExporting }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val dimensions = outputDimensions(before)
            check(dimensions.toSet() == setOf(96 to 64, 64 to 96) && dimensions.size == 2) {
                "Expected only first and third images, got $dimensions"
            }
            check(vm.currentUri == current && !vm.busy) { "Batch did not restore the current photo" }

            click("清空")
            check(vm.selectedBatchExportPhotos.isEmpty())
            check(!button("导出所选照片 (0 张)").isEnabled) { "Empty selection can export" }
            capture("none")
            val emptyBefore = published().keys
            main { vm.batchExport(); check(!vm.isBatchExporting) }
            Thread.sleep(200)
            check(published().keys == emptyBefore) { "Empty selection published an image" }
            click("全选")
            check(vm.selectedBatchExportPhotos.size == 3)

            // An unreadable, unselected queue item must never be opened by the exporter.
            val missing = Uri.fromFile(File(output, "unselected-missing.png"))
            main {
                vm.selectAllBatchExportPhotos(false)
                vm.setBatchExportPhotoSelected(third, true)
                vm.addPhotos(listOf(missing))
                vm.setBatchExportPhotoSelected(missing, false)
            }
            val singleBefore = published().keys
            main {
                vm.batchExport()
                check(vm.isBatchExporting)
                vm.selectAllBatchExportPhotos(true)
                vm.setBatchExportPhotoSelected(third, false)
                vm.removePhotoFromSession(third)
                check(vm.selectedBatchExportPhotos == listOf(third)) { "Running batch selection was mutable" }
            }
            await("single selected") { !vm.isBatchExporting }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            check(outputDimensions(singleBefore) == listOf(64 to 96)) { "Single selection exported extra images" }
            main {
                vm.setBatchExportPhotoSelected(second, true)
                vm.removePhotoFromSession(second)
                check(second !in vm.batchExportSelection)
                vm.openPhotos(listOf(first))
            }
            await("replace queue") { !vm.busy }
            check(vm.selectedBatchExportPhotos == listOf(first))
            main {
                vm.selectAllBatchExportPhotos(false)
                vm.addPhotos(listOf(first))
                check(vm.selectedBatchExportPhotos.isEmpty()) { "Duplicate import reset a deselection" }
                vm.addPhotos(listOf(second, third))
                check(vm.selectedBatchExportPhotos == listOf(second, third))
            }
            return "UI selection, all/none, mode persistence, real subset dimensions (2/3 and 1/4), ignored unreadable input, export locking and queue changes passed"
        } finally {
            main { activity.finish() }
        }
    }
}
