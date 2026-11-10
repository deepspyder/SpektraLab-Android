package com.spektralab.android

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Regression audit using contrasting real files and the application's registered picker callbacks. */
internal class PhotoImportAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var activity: MainActivity
    private lateinit var vm: EditorViewModel
    private val steps = JSONArray()
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun waitUntil(timeout: Long = 60000, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < timeout) { "Timeout; uri=${vm.currentUri}; busy=${vm.busy}; error=${vm.errorMessage}" }
            Thread.sleep(25)
        }
    }
    private fun nodes(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun click(label: String) {
        waitUntil(15000) { find(nodes(), label) != null }
        var node = find(nodes(), label) ?: error("Missing $label")
        while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled: $label" }
        test.waitForIdleSync()
        Thread.sleep(250)
    }
    private fun capture(name: String) {
        test.waitForIdleSync()
        test.uiAutomation.waitForIdle(250, 5000)
        Thread.sleep(600)
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "import_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun step(name: String, body: () -> Unit) {
        val start = SystemClock.elapsedRealtime()
        body()
        steps.put(JSONObject().put("name", name).put("status", "PASS").put("ms", SystemClock.elapsedRealtime() - start))
        File(output, "import-steps.json").writeText(steps.toString(2))
        test.sendStatus(0, Bundle().apply { putString("stream", "PASS import: $name\n") })
    }
    private fun ready(uri: Uri) {
        waitUntil { vm.errorMessage != null || (vm.currentUri == uri && vm.hasPhoto && !vm.busy && vm.lastResult != null) }
        check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
        test.waitForIdleSync()
    }
    private fun fixture(name: String, width: Int, height: Int, channel: Int, exposure: Float = 0f): Uri {
        val file = File(output, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { i ->
            val intensity = 70 + (i % width) * 170 / width
            when (channel) {
                0 -> Color.rgb(intensity, 25 + (i / width) * 25 / height, 20)
                1 -> Color.rgb(20, intensity, 25)
                else -> Color.rgb(25, 20, intensity)
            }
        }, 0, width, 0, 0, width, height)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file).also { uri ->
            EditStore.save(test.targetContext, uri, EditorParams().apply { exposureEv = exposure; grainActive = false })
        }
    }
    private fun assertSource(uri: Uri, width: Int, height: Int, channel: Int) {
        ready(uri)
        val source = vm.sourceBitmap ?: error("Missing source for $uri")
        check(source.width == width && source.height == height) { "Wrong source geometry ${source.width}x${source.height}" }
        val pixel = source.getPixel(width / 2, height / 2)
        val components = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        check(components[channel] > components.filterIndexed { index, _ -> index != channel }.max() + 70) {
            "Previous photo pixels remained: $components, expected channel $channel"
        }
        check(vm.lastResult!!.width == vm.rendered!!.width && vm.lastResult!!.height == vm.rendered!!.height)
        check(vm.photoInfo.first { it.first == "文件名" }.second == uri.lastPathSegment)
    }
    /** Exercise the actual registered launcher callback without depending on cloud/gallery picker contents. */
    private fun deliverPickerSelection(uris: List<Uri>) {
        waitUntil(15000) { nodes()?.packageName?.toString()?.let { it != test.targetContext.packageName } == true }
        val state = Bundle()
        main { activity.activityResultRegistry.onSaveInstanceState(state) }
        val launched = state.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS") ?: error("Missing launched picker: ${state.keySet()}")
        val keys = state.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS") ?: error("Missing registry keys")
        val codes = state.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS") ?: error("Missing registry codes")
        val requestCode = codes[keys.indexOf(launched.single())]
        val result = Intent().apply {
            data = uris.first()
            if (uris.size > 1) clipData = ClipData.newRawUri("Regression selections", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        main { check(activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, result)) }
        test.uiAutomation.waitForIdle(250, 5000)
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil(15000) { nodes()?.packageName?.toString() == test.targetContext.packageName }
    }

    fun run(): String {
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        waitUntil { vm.engine != null && !vm.busy && vm.films.isNotEmpty() }
        waitUntil { find(nodes(), "Theia") != null }
        val red = fixture("import-red.png", 384, 256, 0, .75f)
        val blue = fixture("import-blue.png", 256, 384, 2, 1.25f)
        val green = fixture("import-green.png", 320, 240, 1, -.5f)
        step("home_add_opens_selected_photo") {
            main { vm.openPhotos(listOf(red)) }; assertSource(red, 384, 256, 0)
            click("添加照片"); deliverPickerSelection(listOf(blue))
            assertSource(blue, 256, 384, 2)
            check(vm.photos == listOf(red, blue) && vm.currentIndex == 1)
            waitUntil { find(nodes(), "照片参数信息") != null }
            capture("home_added_blue")
        }
        step("editor_add_opens_selection_and_closes_tray") {
            click("管理照片工作台"); click("添加照片"); deliverPickerSelection(listOf(green, red))
            assertSource(green, 320, 240, 1)
            check(vm.photos == listOf(red, blue, green) && vm.currentIndex == 2)
            check(find(nodes(), "关闭展示台") == null)
            capture("editor_added_green")
        }
        step("replacement_import_restores_the_correct_edit") {
            click("更多操作"); click("导入照片"); deliverPickerSelection(listOf(red))
            assertSource(red, 384, 256, 0)
            check(vm.photos == listOf(red) && vm.currentIndex == 0 && abs(vm.params.exposureEv - .75f) < .001f)
        }
        step("empty_duplicate_and_queue_only_imports_do_not_render") {
            val result = vm.lastResult
            main {
                vm.addPhotos(emptyList(), openSelected = true)
                vm.addPhotos(listOf(red, red), openSelected = true)
                vm.addPhotos(listOf(blue, green))
            }
            check(vm.lastResult === result && vm.currentUri == red && vm.photos == listOf(red, blue, green))
            main { vm.addPhotos(listOf(blue, blue), openSelected = true) }
            assertSource(blue, 256, 384, 2)
            check(vm.photos.size == 3 && vm.currentIndex == 1)
        }
        step("rapid_A_B_A_preserves_each_photos_sidecar") {
            val field = EditorViewModel::class.java.getDeclaredField("engineDispatcher").apply { isAccessible = true }
            val dispatcher = field.get(vm) as CoroutineDispatcher
            val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
            val blocker = CoroutineScope(SupervisorJob() + dispatcher).launch { entered.countDown(); check(unblock.await(10, TimeUnit.SECONDS)) }
            check(entered.await(5, TimeUnit.SECONDS))
            try {
                main { repeat(12) { vm.selectPhoto(0); vm.selectPhoto(1) }; vm.selectPhoto(0) }
                check(vm.rendered == null && vm.lastResult == null && vm.currentUri == red && vm.busy)
            } finally { unblock.countDown() }
            waitUntil { blocker.isCompleted }
            assertSource(red, 384, 256, 0)
            check(abs(EditStore.load(test.targetContext, blue)!!.exposureEv - 1.25f) < .001f)
            check(abs(vm.params.exposureEv - .75f) < .001f)
        }
        step("pending_render_and_grade_cannot_publish_to_new_photo") {
            repeat(6) {
                main {
                    vm.params.exposureEv = .5f; vm.scheduleRender()
                    vm.params.temperature = .2f; vm.scheduleGrade()
                    vm.selectPhoto(2)
                }
                assertSource(green, 320, 240, 1)
                val result = vm.lastResult; val bitmap = vm.rendered
                Thread.sleep(200)
                check(vm.lastResult === result && vm.rendered === bitmap && vm.errorMessage == null && !vm.busy)
                main { vm.selectPhoto(0) }; assertSource(red, 384, 256, 0)
            }
        }
        step("inflight_decode_and_errors_do_not_replace_latest_selection") {
            repeat(6) {
                main { vm.openPhotos(listOf(red, blue, green)) }
                Thread.sleep(8)
                main { vm.selectPhoto(1) }
                Thread.sleep(8)
                main { vm.selectPhoto(2) }
                assertSource(green, 320, 240, 1)
                Thread.sleep(150)
                assertSource(green, 320, 240, 1)
            }
            main { vm.openPhotos(listOf(Uri.fromFile(File(output, "nonexistent-import.png")))); vm.openPhotos(listOf(blue)) }
            assertSource(blue, 256, 384, 2)
            Thread.sleep(250); check(vm.errorMessage == null)
        }
        step("RAW_white_balance_work_does_not_leak_to_bitmap_import") {
            val file = File(output, "import-switch.dng")
            test.context.assets.open("import-switch.dng").use { input -> file.outputStream().use(input::copyTo) }
            val raw = Uri.fromFile(file)
            EditStore.save(test.targetContext, raw, EditorParams().apply { grainActive = false })
            main { vm.openPhotos(listOf(raw, blue)) }; ready(raw); check(vm.currentIsRaw)
            val before = vm.lastResult
            main { vm.params.cameraWbCustom = true; vm.params.cameraWbKelvin = 7000f; vm.scheduleCameraWb() }
            waitUntil { vm.errorMessage != null || (vm.lastResult !== before && !vm.busy) }
            check(vm.errorMessage == null && vm.currentIsRaw)
            main { vm.resetCameraWb(); vm.selectPhoto(1) }
            assertSource(blue, 256, 384, 2)
            Thread.sleep(500)
            check(!vm.currentIsRaw && vm.errorMessage == null && vm.matchedLens == null)
        }
        step("thumbnail_requests_coalesce_and_replaced_queue_releases_cache") {
            main { vm.openPhotos(listOf(red, blue, green)) }
            ready(red)
            main { repeat(20) { vm.photos.forEach(vm::loadThumbnail) } }
            waitUntil { vm.photos.all { it in vm.thumbnails } }
            val references = vm.thumbnails.toMap()
            val result = vm.lastResult
            main { repeat(20) { vm.photos.forEach(vm::loadThumbnail) } }
            check(vm.lastResult === result && references.all { (uri, bitmap) -> vm.thumbnails[uri] === bitmap })
            main { vm.openPhotos(listOf(green)) }; assertSource(green, 320, 240, 1)
            Thread.sleep(300)
            check(vm.thumbnails.keys.all { it == green } && vm.developedPreviews.keys.all { it == green })
            val jobField = EditorViewModel::class.java.getDeclaredField("thumbnailJobs").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val jobs = jobField.get(vm) as Map<Uri, *>
            check(jobs.keys.all { it == green }) { "Thumbnail jobs retained entries from the replaced queue: ${jobs.keys}" }
        }
        step("failed_import_is_clear_and_next_import_recovers") {
            main { vm.openPhotos(listOf(Uri.fromFile(File(output, "missing-final.png")))) }
            waitUntil { vm.errorMessage != null && !vm.busy }
            check(!vm.hasPhoto && vm.rendered == null && vm.lastResult == null)
            main { vm.openPhotos(listOf(blue)) }; assertSource(blue, 256, 384, 2)
            capture("recovered_blue")
        }
        return "${steps.length()} import/state regression groups passed; distinct source pixels, picker callbacks, rapid switching, RAW, sidecars and cache reuse verified."
    }
}
