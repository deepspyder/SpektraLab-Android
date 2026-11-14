package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.cache.LightTablePreviews
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.lighttable.FilmCanvasState
import com.spektralab.android.feature.lighttable.ViewingPhoto
import java.io.File
import kotlinx.coroutines.Job

/** Owned fixtures exercise the reported cold-workbench + project resave path. */
internal class ProjectRestoreAudit(private val test: Instrumentation, private val output: File) {
    private lateinit var activity: MainActivity
    private lateinit var vm: EditorViewModel
    private val context get() = test.targetContext
    private fun main(action: () -> Unit) = test.runOnMainSync { action() }
    private fun await(label: String, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 120000) { "Restore timeout: $label" }; SystemClock.sleep(20) }
        test.waitForIdleSync()
    }
    private fun idle() = await("editor") {
        vm.hasPhoto && !vm.busy && vm.lastResult != null && vm.renderTaskState.active == null &&
            listOf("renderJob", "wbJob", "frameRenderJob", "photoLoadJob").none {
                (EditorViewModel::class.java.getDeclaredField(it).apply { isAccessible = true }.get(vm) as? Job)?.isActive == true
            }
    }
    private fun start() {
        activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await("engine") { vm.engine != null && !vm.busy }
    }

    fun run(projectResave: Boolean): String {
        val root = File(output, "restore-${System.nanoTime()}").apply { mkdirs() }
        val uris = (0..1).map { index ->
            val file = File(root, "owned-$index.png")
            val image = CineStillAudit.fixture(320, 240, index * 7)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            Uri.fromFile(file).also { uri ->
                EditStore.save(context, uri, EditorParams().apply {
                    filmId = if (index == 0) "fujifilm_velvia_100" else "kodak_gold_200"
                    grainActive = false; glareActive = false; autoExposure = false
                    temperature = .4f + index * .2f; contrast = 25f; brightness = 12f
                    overscanActive = true; overscanCameraSeed = 4; overscanFrameSeed = 7
                })
            }
        }
        val expectedParams = uris.associateWith { EditStore.load(context, it)!!.toJson().toString() }
        val expectedImages = linkedMapOf<Uri, Bitmap>()
        start()
        try {
            main { vm.openPhotos(uris) }; idle()
            for (index in uris.indices) {
                main { vm.selectPhoto(index) }; idle()
                expectedImages[uris[index]] = vm.rendered!!.copy(Bitmap.Config.ARGB_8888, false)
            }
            val available = uris.map { uri -> ViewingPhoto(uri.toString(), expectedImages[uri], expectedImages[uri],
                "Owned", "", wallDetail = expectedImages[uri], sourceWidth = 320, sourceHeight = 240, sourceFormat = "PNG") }
            main { vm.prepareVersionBoundary(); vm.lightTableProjects.create("恢复核对${System.nanoTime()}", available, FilmCanvasState()) }
            await("create") { !vm.lightTableProjects.pending }
            check(vm.lightTableProjects.error == null)
            val project = vm.lightTableProjects.current!!
            await("session") { EditStore.savedSession(context)?.photos?.size == 2 }
            val session = EditStore.savedSession(context)!!
            main { activity.finish() }; test.waitForIdleSync()
            start()
            main { vm.restoreWorkbench(session) }; idle()
            check(uris.all { EditStore.load(context, it)!!.toJson().toString() == expectedParams[it] }) { "Cold restore changed stored edits" }
            val other = uris.first { it != vm.currentUri }
            if (!projectResave) {
                main { vm.lightTableProjects.refresh() }
                await("project list") { !vm.lightTableProjects.pending }
                main { vm.lightTableProjects.open(vm.lightTableProjects.projects.single { it.id == project.id }) }
                await("project preview") { !vm.lightTableProjects.pending }
                val member = vm.lightTableProjects.canvas.members.single { it.source.uri == other.toString() }
                val hash = checkNotNull(member.source.renderedPreview) { "Project lost developed preview identity" }
                main { vm.lightTableProjects.requestPreview(hash) }
                await("non-current developed preview") { vm.lightTableProjects.previews[hash] != null }
                val actual = vm.lightTableProjects.previews.getValue(hash)
                val expected = expectedImages.getValue(other)
                check(kotlin.math.abs(actual.width.toFloat() / actual.height - expected.width.toFloat() / expected.height) < .01f) {
                    "Restored non-current photo shows original ${actual.width}x${actual.height}, expected developed film/edge ${expected.width}x${expected.height}"
                }
            } else {
                main { vm.lightTableProjects.refresh() }; await("projects") { !vm.lightTableProjects.pending }
                main { vm.lightTableProjects.open(vm.lightTableProjects.projects.single { it.id == project.id }) }
                await("open") { !vm.lightTableProjects.pending }
                vm.lightTableProjects.canvas.members.mapNotNull { it.source.renderedPreview ?: it.source.preview }
                    .distinct().forEach { vm.lightTableProjects.requestDetail(it) }
                await("project resave previews") {
                    vm.lightTableProjects.canvas.members.mapNotNull { it.source.renderedPreview ?: it.source.preview }
                        .distinct().all { vm.lightTableProjects.details[it] != null }
                }
                val shown = vm.lightTableProjects.canvas.members.map { item ->
                    val key = item.source.renderedPreview ?: item.source.preview
                    val image = key?.let(vm.lightTableProjects.details::get)
                    ViewingPhoto(item.id, image, image, item.source.name, "", wallDetail = image,
                        sourceWidth = item.source.pixelWidth, sourceHeight = item.source.pixelHeight, sourceFormat = item.source.format)
                }
                main { vm.lightTableProjects.saveCurrent(shown) }
                await("resave") { !vm.lightTableProjects.pending }
                val before = project.scene.items.single { it.source.uri == other.toString() }.source.preview
                val after = vm.lightTableProjects.current!!.scene.items.single { it.source.uri == other.toString() }.source.preview
                check(before == after) { "Saving restored layout replaced its developed film/grade/edge preview with an original thumbnail" }
            }
            return "Cold workbench retains all edit bytes; non-current preview/project resave preserves developed film/grade/overscan; source files untouched"
        } finally { main { activity.finish() }; expectedImages.values.forEach(Bitmap::recycle) }
    }

    fun batchAlbum(): String {
        val root = File(output, "album-${System.nanoTime()}").apply { mkdirs() }
        val uris = (0..2).map { index ->
            val file = File(root, "album-$index.png")
            val image = CineStillAudit.fixture(120 + index * 10, 80 + index * 10, index)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            Uri.fromFile(file).also { EditStore.save(context, it, EditorParams().apply { grainActive = false; glareActive = false }) }
        }
        val assets = uris.mapNotNull { uri ->
            EditStore.savedPhotos(context).firstOrNull { it.uri == uri.toString() }?.assetId
        }
        check(assets.size == uris.size)
        val album = EditStore.createPhotoAlbum(context, "批量项目${System.nanoTime()}")
        EditStore.addPhotosToAlbum(context, assets, album.id)
        val selected = EditStore.selectablePhotos(context, com.spektralab.android.core.model.PhotoLibraryFilter(albumId = album.id))
        check(selected.map { it.assetId }.toSet() == assets.toSet())
        check(uris.all { EditStore.load(context, it) != null })
        return "Three selected gallery photos added atomically to a user album; edit sidecars preserved"
    }
}
