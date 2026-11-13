package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.gallery.*
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import kotlinx.coroutines.*

internal class PhotoRemovalAudit(private val test: Instrumentation, private val output: File) {
    companion object {
        fun storage(output: File): String {
            val directory = File(output, "removal-storage-${System.nanoTime()}")
            val originals = (0 until 130).map { i -> File(output, "removal-original-$i.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, i.toByte())) } }
            val uris = originals.map(Uri::fromFile)
            val raw = EditorParams().apply { brightness = 17f }.toJson().toString()
            lateinit var first: com.spektralab.android.data.library.SavedPhoto
            lateinit var copy: EditVersion
            SavedPhotoRepository.open(directory).use { repo ->
                uris.forEachIndexed { i, uri -> repo.save(uri, raw, "Photo $i") }
                first = repo.findByUri(uris.first())!!
                val album = repo.organization.createAlbum("Selected")
                repo.organization.save(first.assetId, repo.organization.read(first.assetId), PhotoOrganization(rating = 5, albums = setOf(album.id)))
                copy = repo.versions.create(repo.versions.list(first.assetId).first(), "Copy")
                repo.saveSession(SavedEditSession(listOf(uris.first().toString(), copy.editUri.toString()),
                    uris.first().toString(), setOf(uris.first().toString())))
            }
            // Reopen an actual v4 file through the production v4 -> v5 migration.
            SQLiteDatabase.openDatabase(File(directory, "saved-photos.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE hidden_photos"); it.version = 4
            }
            SavedPhotoRepository.open(directory).use { repo ->
                check(repo.count() == 130L && repo.find(first.assetId)!!.variantId == first.variantId)
                check(repo.versions.find(copy.id) == copy)
                val all = repo.selectable(PhotoLibraryFilter())
                check(all.size == 130 && repo.page(PhotoLibraryFilter(), 0).photos.size == 60)
                val stale = all.last().copy(revision = all.last().revision + 1)
                check(runCatching { repo.hide(all.dropLast(1) + stale) }.isFailure)
                check(repo.count() == 130L) { "Stale batch partially committed" }
                SQLiteDatabase.openDatabase(File(directory, "saved-photos.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("CREATE TRIGGER owned_remove_failure BEFORE INSERT ON hidden_photos WHEN NEW.asset_id='${all.last().assetId}' BEGIN SELECT RAISE(ABORT,'owned fault'); END")
                }
                check(runCatching { repo.hide(all) }.isFailure)
                check(repo.count() == 130L) { "Write fault partially committed" }
                SQLiteDatabase.openDatabase(File(directory, "saved-photos.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER owned_remove_failure") }
                val filtered = repo.selectable(PhotoLibraryFilter(minimumRating = 5))
                check(filtered.map { it.assetId } == listOf(first.assetId))
                repo.hide(filtered)
                check(repo.count() == 129L && repo.matchingCount(PhotoLibraryFilter(minimumRating = 5)) == 0L)
                check(repo.read(uris.first()) == raw && repo.versions.find(copy.id) == copy)
                check(repo.session()!!.photos == listOf(uris.first().toString(), copy.editUri.toString()))
                check(repo.organization.read(first.assetId).rating == 5)
                repo.save(uris.first(), EditorParams().apply { brightness = 23f }.toJson().toString(), "Photo 0")
                check(repo.count() == 129L) { "Autosave resurrected a hidden row" }
                repo.restoreImported(listOf(uris.first()))
                check(repo.count() == 130L && repo.find(first.assetId)!!.revision == first.revision + 1)
                repo.hide(repo.selectable(PhotoLibraryFilter()))
                check(repo.count() == 0L && repo.list(60, 0).isEmpty())
                check(repo.session()!!.current == uris.first().toString())
                uris.forEach { check(repo.read(it) != null) }
            }
            SavedPhotoRepository.open(directory).use { repo ->
                check(repo.count() == 0L && repo.versions.find(copy.id) == copy)
                repo.restoreImported(uris)
                check(repo.count() == 130L)
            }
            originals.forEachIndexed { i, file -> check(file.readBytes().contentEquals(byteArrayOf(1, 2, 3, i.toByte()))) }
            return "130 entries; v4 migration; filtered/paginated all-selection; stale batch rollback; retained originals, edits, versions and album marks; autosave cannot resurrect; explicit reimport and reopen verified"
        }
    }

    fun ui(): String {
        val directory = File(output, "removal-ui-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = directory }
        val source = File(directory, "Coastal-evening-long-photo-name-2026.jpg")
        val bitmap = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff596e7b.toInt()) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        val uris = (0 until 65).map { Uri.fromFile(File(directory, "photo-$it.jpg")) }
        uris.forEach { EditStore.save(context, it, EditorParams()) }
        val uri = Uri.fromFile(source)
        EditStore.save(context, uri, EditorParams().apply { brightness = 19f })
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SavedPhotosController(context, scope)
        try {
            test.runOnMainSync { activity.setContent { TheiaTheme {
                SavedPhotosScreen(controller, true, {}, {}, { error("Selection opened a photo") })
            } } }
            await { controller.photos.isNotEmpty() && !controller.loading }
            capture("photo-selection-default.png")
            click(test.targetContext.getString(R.string.photos_select))
            await { controller.selection.active }
            click(test.targetContext.getString(R.string.photos_select_photo, source.name))
            check(controller.selection.selected.size == 1)
            capture("photo-selection-selected.png")
            click(test.targetContext.getString(R.string.photos_remove_count, 1))
            await { controller.selection.confirming }
            capture("photo-selection-confirm.png")
            click(test.targetContext.getString(R.string.library_cancel))
            check(!controller.selection.confirming && controller.selection.selected.size == 1)
            click(test.targetContext.getString(R.string.photos_select_all, 66L))
            await { !controller.selection.pending && controller.selection.selected.size == 66 }
            capture("photo-selection-all.png")
            click(test.targetContext.getString(R.string.photos_remove_count, 66))
            click(test.targetContext.getString(R.string.photos_remove))
            await { !controller.selection.pending && !controller.loading && controller.photos.isEmpty() }
            capture("photo-selection-empty.png")
            check(source.exists() && EditStore.load(context, uri)!!.brightness == 19f) { "Removal touched the original or saved brightness" }
            EditStore.restoreImportedPhotos(context, listOf(uri))
            test.runOnMainSync { controller.refresh() }
            await { !controller.loading && controller.photos.size == 1 }
            val label = test.targetContext.getString(R.string.photos_open, source.name)
            await { longPressTarget(label) != null }
            check(longPressTarget(label)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) { "Row rejected accessible long-press" }
            await { controller.selection.selected.size == 1 }
            test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await { !controller.selection.active }
            check(controller.selection.selected.isEmpty())
            return "Native selection entry/toggle/cancel/66-item all across pagination/removal/empty/reimport/long-press/system Back; originals and edits retained; no source decode or engine render for selection"
        } catch (failure: Throwable) {
            File(output, "photo-selection-failure.txt").writeText(failure.stackTraceToString())
            capture("photo-selection-failure.png")
            throw failure
        } finally {
            test.runOnMainSync { activity.finish() }; scope.cancel(); EditStore.closeForTests(context)
        }
    }

    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun longPressTarget(label: String): AccessibilityNodeInfo? {
        var node = find(label) ?: return null
        while (!node.isLongClickable) node = node.parent ?: return null
        return node
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(label: String) {
        // Dialog windows and their accessibility roots can settle after Compose is idle.
        await { find(label) != null }
        test.uiAutomation.clearCache()
        var node = find(label) ?: error("Missing control $label")
        while (!node.isClickable) node = node.parent ?: error(label)
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { label }
        test.waitForIdleSync(); SystemClock.sleep(150)
    }
    private fun capture(name: String) {
        test.waitForIdleSync(); SystemClock.sleep(200)
        val image = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 30000) { "Selection timed out" }; SystemClock.sleep(20) }
        test.waitForIdleSync(); test.uiAutomation.clearCache()
    }
}
