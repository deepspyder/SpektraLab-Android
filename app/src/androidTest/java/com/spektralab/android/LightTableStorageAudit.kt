package com.spektralab.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.saveable.SaverScope
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.LightTableProjectStore
import com.spektralab.android.data.library.SourceProbe
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.cache.LightTablePreviews
import com.spektralab.android.feature.lighttable.FilmCanvasState
import java.io.File
import java.util.UUID
import kotlin.math.abs
import org.json.JSONObject

internal object LightTableStorageAudit {
    fun performance(output: File): String {
        val base = scene().items.first()
        val scene = LightTableScene(List(200) { i -> base.copy(id = UUID.randomUUID().toString(), x = (i % 10) * 180f, y = (i / 10) * 140f) })
        val samples = mutableListOf<Double>()
        LightTableProjectStore(File(output, "table-timing-${System.nanoTime()}")).use { store ->
            var expected = store.save(null, "200照片布局", scene)
            repeat(40) { i ->
                val start = System.nanoTime()
                expected = store.save(expected, expected.name, scene.copy(panX = i.toFloat()))
                samples += (System.nanoTime() - start) / 1_000_000.0
            }
            check(store.read(expected.id).scene.items.size == 200)
        }
        val sorted = samples.sorted()
        return JSONObject().put("placements", 200).put("payload_bytes", scene.toJson().toByteArray().size)
            .put("save_ms", org.json.JSONArray(samples)).put("median_ms", sorted[sorted.size / 2])
            .put("p95_ms", sorted[(sorted.size * .95).toInt()]).put("scope", "emulator metadata IO; not render/frame or phone timing").toString()
    }
    fun sourceReferences(context: Context, output: File): String {
        val root = File(output, "table-bindings-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = File(root, "private").apply { mkdirs() } }
        val file = File(root, "source.png")
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffb16b45.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val before = file.readBytes()
        val uri = Uri.fromFile(file)
        val plain = TableSource(uri.toString(), file.name)
        check(EditStore.tableSource(isolated, plain).assetId == null && EditStore.savedPhotoCount(isolated) == 0L)
        EditStore.save(isolated, uri, EditorParams().apply { brightness = 18f })
        val photo = EditStore.savedPhotos(isolated).single()
        val bound = EditStore.tableSource(isolated, plain)
        check(bound.assetId == photo.assetId && bound.variantId == photo.variantId && bound.editRevision == photo.revision)
        val version = EditStore.createEditVersion(isolated, EditStore.editVersions(isolated, photo.assetId).first(), "另一版")
        val copy = EditStore.tableSource(isolated, plain.copy(uri = version.editUri.toString()))
        check(copy.assetId == bound.assetId && copy.variantId == version.id && copy.variantId != bound.variantId)
        EditStore.save(isolated, version.editUri, EditorParams().apply { brightness = 29f })
        check(EditStore.resolveTableSource(isolated, copy).editRevision > copy.editRevision)
        check(EditStore.load(isolated, uri)!!.brightness == 18f)
        EditStore.checkSavedSource(isolated, photo, SourceProbe.inspect(isolated, uri))
        val moved = File(root, "moved.png").apply { writeBytes(before) }
        EditStore.relinkSavedSource(isolated, EditStore.savedPhoto(isolated, photo.assetId)!!, SourceProbe.inspect(isolated, Uri.fromFile(moved)))
        check(EditStore.resolveTableSource(isolated, bound).uri == Uri.fromFile(moved).toString())
        check(EditStore.resolveTableSource(isolated, copy).uri == version.editUri.toString())
        EditStore.deleteEditVersion(isolated, EditStore.editVersion(isolated, version.id))
        check(runCatching { EditStore.resolveTableSource(isolated, copy) }.isFailure)
        check(file.readBytes().contentEquals(before) && moved.readBytes().contentEquals(before))
        val cache = LightTablePreviews(File(root, "cache"))
        val key = cache.put(bitmap)
        val small = checkNotNull(cache.read(key)); val detail = checkNotNull(cache.read(key, 640))
        check(maxOf(small.width, small.height) <= 192 && detail.width == 600 && detail.height == 400)
        check(detail.getPixel(10, 10) == bitmap.getPixel(10, 10))
        small.recycle(); detail.recycle(); bitmap.recycle()
        File(root, "cache/$key.png").writeBytes(byteArrayOf(1, 2, 3))
        check(cache.read(key) == null && runCatching { cache.read("../source") }.isFailure)
        EditStore.closeForTests(isolated)
        return "Unedited URI not adopted as synthetic edit; stable asset/default/copy IDs and revisions, source relink preserved default and virtual references, deleted version refused without changing original; bounded small/detail PNG previews exact decoded samples, corrupt/path-invalid cache rejected passed"
    }
    fun scene() = LightTableScene(listOf(
        TablePlacement(UUID.randomUUID().toString(), TableSource("file:///missing/original.NEF", "Original.NEF"), 12f, -8f, 160f, 100f, 27f),
        TablePlacement(UUID.randomUUID().toString(), TableSource("file:///missing/original.NEF", "Original.NEF"), 185f, 12f, 80f, 50f, -90f),
    ), 1.25f, -12f, 30f, false, true)

    fun run(context: Context, output: File): String {
        val root = File(output, "table-storage-${System.nanoTime()}")
        val scene = scene()
        check(LightTableScene.fromJson(scene.toJson()) == scene)
        check(scene.items[0].source == scene.items[1].source && scene.items[0].id != scene.items[1].id)
        check(runCatching { LightTableScene.fromJson(JSONObject(scene.toJson()).put("version", 99).toString()) }.isFailure)
        check(runCatching { scene.copy(items = scene.items + scene.items[0]).validated() }.isFailure)
        check(runCatching { scene.copy(zoom = Float.NaN).validated() }.isFailure)
        check(runCatching { scene.copy(items = scene.items.map { it.copy(width = 0f) }).validated() }.isFailure)
        lateinit var original: LightTableProject
        LightTableProjectStore(root).use { store ->
            original = store.save(null, "午后光线", scene)
            check(store.read(original.id) == original)
            check(store.save(original, original.name, scene).revision == original.revision)
            check(runCatching { store.save(null, "午后光线", scene) }.isFailure)
            store.checkpoint = { if (it == LightTableProjectStore.Checkpoint.WRITTEN) error("Injected interruption") }
            check(runCatching { store.save(original, "失败改名", scene.copy(lightOn = true)) }.isFailure)
            check(store.read(original.id) == original)
            check(runCatching { store.delete(original) }.isFailure)
            check(store.read(original.id) == original)
            store.checkpoint = {}
            val changed = store.save(original, "蓝调时刻", scene.copy(lightOn = true))
            check(changed.revision == original.revision + 1 && changed.id == original.id)
            check(runCatching { store.save(original, "过期覆盖", scene) }.isFailure)
            check(runCatching { store.delete(original) }.isFailure)
            original = changed
        }
        LightTableProjectStore(root).use { store ->
            check(store.read(original.id) == original)
            val other = store.save(null, "另一张桌子", scene)
            store.delete(original)
            check(store.list().single().id == other.id && store.read(other.id).scene == scene)
        }
        // A future database or payload remains on disk and cannot be rewritten as defaults.
        val file = File(root, "lighttable.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        check(runCatching { LightTableProjectStore(root).close() }.isFailure)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { check(it.version == 99); it.version = 1
            it.execSQL("UPDATE projects SET payload=?", arrayOf(JSONObject(scene.toJson()).put("version", 99).toString())) }
        LightTableProjectStore(root).use { store ->
            check(store.list().size == 1)
            check(runCatching { store.read(store.list().single().id) }.isFailure)
        }
        return "Metadata-only scene JSON and DB roundtrip, duplicate same-source placements, no-op revisions, unique names, stale CAS, write/delete rollback, close/reopen, safe delete, missing source preservation and future DB/payload refusal passed"
    }

    fun geometry(): String {
        val square = TableGeometry.corners(0f, 0f, 100f, 100f, 45f)
        check(TableGeometry.contains(square, TablePoint(50f, -10f)))
        check(!TableGeometry.contains(square, TablePoint(-20f, -20f)))
        check(abs(TableGeometry.area(square) - 10000f) < .02f)
        // Independent frozen intersection expectation for two axis-aligned50x50rectangles.
        val a = TableGeometry.corners(0f, 0f, 50f, 50f, 0f)
        val b = TableGeometry.corners(25f, 20f, 50f, 50f, 0f)
        check(abs(TableGeometry.area(TableGeometry.intersect(a, b)) - 750f) < .001f)
        for (angle in listOf(-135f, -90f, -27f, 0f, 45f, 90f, 179f)) {
            val center = TablePoint(50f, 50f)
            val point = TableGeometry.rotate(TablePoint(10f, 20f), center, angle)
            val inverse = TableGeometry.rotate(point, center, -angle)
            check(abs(inverse.x - 10f) < .001f && abs(inverse.y - 20f) < .001f)
        }
        val moving = TableGeometry.corners(105f, 0f, 100f, 60f, 0f)
        val fixed = TableGeometry.corners(0f, 0f, 100f, 60f, 0f)
        check(TableGeometry.join(moving, fixed, 8f)?.delta == TablePoint(-5f, 0f))
        val center = TablePoint(0f, 0f)
        val rotatedMoving = moving.map { TableGeometry.rotate(it, center, 31f) }
        val rotatedFixed = fixed.map { TableGeometry.rotate(it, center, 31f) }
        val delta = checkNotNull(TableGeometry.join(rotatedMoving, rotatedFixed, 8f)).delta
        val expected = TableGeometry.rotate(TablePoint(-5f, 0f), center, 31f)
        check(abs(delta.x - expected.x) < .001f && abs(delta.y - expected.y) < .001f)
        check(TableGeometry.join(fixed, fixed, 8f) == null)
        val scene = scene()
        val state = FilmCanvasState(); state.restore(scene)
        val id = scene.items.first().id
        state.beginGesture(); repeat(50) { state.movePhoto(id, Offset(12f, -8f), Offset(1f, 0f)) }; state.finishGesture()
        check(state.scene().items.first { it.id == id }.x == 62f)
        state.undo(); check(state.scene() == scene); check(!state.canUndo)
        state.redo(); check(state.scene().items.first { it.id == id }.x == 62f)
        state.undo(); state.beginGesture(); state.movePhoto(id, Offset.Zero, Offset(99f, 99f)); state.finishGesture(true)
        check(state.scene() == scene)
        state.duplicate(id); check(state.members.size == 3 && state.members.last().source == scene.items.first().source)
        state.undo(); check(state.scene() == scene)
        state.rotate(id, 60f); state.layer(id, true); check(state.scene().items.last().id == id)
        state.undo(); state.undo(); check(state.scene() == scene)
        state.width(id, 80f); check(state.scene().items.first().height == 50f); state.undo(); check(state.scene() == scene)
        state.changeUnits(false); state.undo(); check(state.scene() == scene)
        state.remove(id); state.undo(); check(state.scene() == scene)
        val draft = FilmCanvasState()
        draft.rotate("legacy-source", 35f); draft.toggleLight()
        val saved = with(FilmCanvasState.Saver) { object : SaverScope { override fun canBeSaved(value: Any) = true }.save(draft) }!!
        val restored = FilmCanvasState.Saver.restore(saved)!!
        check(restored.rotations["legacy-source"] == 35f && !restored.lightOn)
        return "Independent geometry oracles, inverse rotations, polygon hit/overlap and rotated edge join;50drag updates become one undo step, real cancel restores, independent duplicate/remove/layer/rotation/mm-width and undo/redo preserve source/ratio passed"
    }
}
