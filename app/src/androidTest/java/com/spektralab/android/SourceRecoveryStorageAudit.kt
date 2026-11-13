package com.spektralab.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.library.SourceProbe
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditSessionPersistence
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.json.JSONObject

internal object SourceRecoveryStorageAudit {
    fun run(context: Context, output: File): String {
        val root = File(output, "source-recovery-storage-${System.nanoTime()}").apply { mkdirs() }
        val source = File(root, "original.png")
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xffa44c28.toInt())
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        // Same valid PNG pixels and byte count; only trailing bytes differ.
        source.appendBytes(byteArrayOf(1, 2, 3, 4))
        val moved = File(root, "moved.png").apply { writeBytes(source.readBytes()) }
        val other = File(root, "other.png").apply { writeBytes(source.readBytes().also { it[it.lastIndex] = 5 }) }
        val registered = File(root, "registered.png").apply { writeBytes(source.readBytes()) }
        val originalUri = Uri.fromFile(source)
        val movedUri = Uri.fromFile(moved)
        val unavailable = "content://audit/offline/73"
        val probe = SourceProbe.inspect(context, originalUri)
        val correct = SourceProbe.inspect(context, movedUri)
        val incorrect = SourceProbe.inspect(context, Uri.fromFile(other))
        check(probe.verified && correct.sha256 == probe.sha256 && incorrect.size == probe.size && incorrect.sha256 != probe.sha256)
        check(runCatching { SourceProbe.inspect(context, originalUri) { throw CancellationException("stop") } }.exceptionOrNull() is CancellationException)

        val dir = File(root, "production-v1").apply { mkdirs() }
        val json = "{\"brightness\":18,\"crop_x\":0.1,\"crop_w\":0.8,\"future_extension\":{\"keep\":true}}"
        // Upgrade a real v1 schema, with stable IDs, revisions and opaque fields.
        SQLiteDatabase.openOrCreateDatabase(File(dir, "saved-photos.db"), null).use { db ->
            db.execSQL("CREATE TABLE assets(asset_id TEXT PRIMARY KEY,source_key TEXT UNIQUE NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE edits(variant_id TEXT PRIMARY KEY,asset_id TEXT UNIQUE NOT NULL REFERENCES assets(asset_id),params TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),updated_at INTEGER NOT NULL,render_version TEXT NOT NULL)")
            db.execSQL("CREATE INDEX edit_updated ON edits(updated_at DESC)")
            db.execSQL("CREATE TABLE legacy_claims(legacy_key TEXT PRIMARY KEY,source_key TEXT NOT NULL,backup TEXT NOT NULL,sha256 TEXT NOT NULL)")
            val key = com.spektralab.android.core.model.CanonicalJson.fingerprint("uri-v1:$originalUri")
            db.execSQL("INSERT INTO assets VALUES('asset-v1',?,?, 'original.png')", arrayOf(key, originalUri.toString()))
            db.execSQL("INSERT INTO edits VALUES('variant-v1','asset-v1',?,7,1234,'android-legacy-v1')", arrayOf(json))
            db.version = 1
        }
        SavedPhotoRepository.open(dir).use { repo ->
            var photo = repo.list(60, 0).single()
            check(photo.assetId == "asset-v1" && photo.variantId == "variant-v1" && photo.revision == 7L && repo.read(originalUri) == json)
            check(runCatching { repo.relink(photo, correct) }.isFailure) { "Unverified record guessed a moved file" }
            repo.checkSource(photo, probe)
            photo = repo.find(photo.assetId)!!
            check(photo.sourceSha256 == probe.sha256 && photo.sourceSize == probe.size && photo.revision == 7L)
            check(runCatching { repo.relink(photo, incorrect) }.isFailure)
            repo.save(Uri.fromFile(registered), "{\"brightness\":-20}", "registered.png")
            check(runCatching { repo.relink(photo, SourceProbe.inspect(context, Uri.fromFile(registered))) }.isFailure)
            val session = SavedEditSession(listOf(originalUri.toString(), unavailable), unavailable, emptySet())
            repo.saveSession(session)
            repo.checkpoint = { if (it == SavedPhotoRepository.Checkpoint.SOURCE_RELINKED) throw IOException("before commit") }
            check(runCatching { repo.relink(photo, correct) }.isFailure)
            check(repo.find(photo.assetId)!!.uri == originalUri.toString() && repo.session() == session && repo.read(originalUri) == json)
            repo.checkpoint = {}
            repo.save(originalUri, JSONObject(json).put("brightness", 24).toString(), "")
            check(runCatching { repo.relink(photo, correct) }.isFailure) { "Stale revision accepted" }
            photo = repo.find(photo.assetId)!!
            val before = repo.read(originalUri)
            repo.relink(photo, correct)
            val after = repo.find(photo.assetId)!!
            check(after.uri == movedUri.toString() && after.variantId == photo.variantId && after.revision == photo.revision)
            check(repo.read(movedUri) == before && repo.count() == 2L)
            check(repo.session() == session.remap { if (it == originalUri.toString()) movedUri.toString() else it })
            repo.saveSession(session) // A queued old-session write is normalized after relocation.
            check(repo.session()!!.photos.first() == movedUri.toString())
            check(runCatching { repo.read(originalUri) }.isFailure)
            check(runCatching { repo.adopt(originalUri, "media-73", "{}", "old.png") }.isFailure)
            check(runCatching { repo.save(originalUri, "{}", "old.png") }.isFailure)
            check(runCatching { repo.saveBatch(listOf(SavedPhotoRepository.PendingWrite(originalUri, before, "{}")), emptyMap()) }.isFailure)
            val changed = incorrect.copy(uri = movedUri.toString())
            check(runCatching { repo.checkSource(after, changed) }.isFailure)
            repo.relink(after, correct) // Reauthorizing the same URI is idempotent.
            check(repo.read(movedUri) == before && repo.find(photo.assetId)!!.sourceSha256 == probe.sha256)
        }
        SavedPhotoRepository.open(dir).use { repo ->
            check(repo.find("asset-v1")!!.uri == movedUri.toString() && repo.find("asset-v1")!!.revision == 8L)
            check(repo.session()!!.photos == listOf(movedUri.toString(), unavailable) && repo.session()!!.current == unavailable)
            check(repo.session()!!.selected.isEmpty())
        }
        val future = "{\"version\":99,\"keep\":true}"
        SQLiteDatabase.openDatabase(File(dir, "saved-photos.db").path, null, 0).use { db ->
            check(db.version == 2)
            db.execSQL("UPDATE workbench SET payload=?", arrayOf(future))
        }
        SavedPhotoRepository.open(dir).use { repo ->
            check(runCatching { repo.session() }.isFailure)
            check(runCatching { repo.saveSession(SavedEditSession(emptyList(), null, emptySet())) }.isFailure)
            check(runCatching { repo.relink(repo.find("asset-v1")!!, probe) }.isFailure)
            check(repo.find("asset-v1")!!.uri == movedUri.toString())
        }
        SQLiteDatabase.openDatabase(File(dir, "saved-photos.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT payload FROM workbench", null).use { check(it.moveToFirst() && it.getString(0) == future) }
        }
        val valid = SavedEditSession(listOf("content://audit/a", "content://audit/b"), "content://audit/b", setOf("content://audit/a"))
        check(SavedEditSession.fromJson(valid.toJson()) == valid)
        for (invalid in listOf(
            JSONObject(valid.toJson()).put("version", 99),
            JSONObject(valid.toJson()).put("future", true),
            JSONObject(valid.toJson()).put("current", "content://audit/other"),
            JSONObject(valid.toJson()).put("selected", org.json.JSONArray(listOf("content://audit/other"))),
        )) check(runCatching { SavedEditSession.fromJson(invalid.toString()) }.isFailure)

        val queuedRoot = File(root, "queued").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = queuedRoot }
        EditStore.saveSession(isolated, valid)
        EditStore.saveSessionIfCurrent(isolated, SavedEditSession(emptyList(), null, emptySet())) { false }
        check(EditStore.savedSession(isolated) == valid)
        val gate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit { gate.await() }
        val dispatcher = executor.asCoroutineDispatcher()
        val olderScope = CoroutineScope(SupervisorJob() + dispatcher)
        val newerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val older = EditSessionPersistence(isolated, olderScope)
            val newer = EditSessionPersistence(isolated, newerScope)
            older.save(valid.photos, valid.current, valid.selected)
            val newest = SavedEditSession(listOf("content://audit/new"), "content://audit/new", emptySet())
            newer.save(newest.photos, newest.current, newest.selected)
            val start = android.os.SystemClock.elapsedRealtime()
            while (EditStore.savedSession(isolated) != newest) {
                check(android.os.SystemClock.elapsedRealtime() - start < 10000)
                Thread.sleep(10)
            }
            gate.countDown()
            executor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(50)
            check(EditStore.savedSession(isolated) == newest) { "Retired ViewModel overwrote the new workbench" }
            val oversized = (0..500).map { "content://audit/large/$it" }
            newer.save(oversized, oversized.first(), emptySet())
            val failedAt = android.os.SystemClock.elapsedRealtime()
            while (EditStore.storageError.value == null) {
                check(android.os.SystemClock.elapsedRealtime() - failedAt < 10000)
                Thread.sleep(10)
            }
            check(EditStore.savedSession(isolated) == newest) { "Oversized workbench replaced the previous record" }
            EditStore.dismissStorageError()
        } finally {
            gate.countDown(); olderScope.cancel(); newerScope.cancel(); dispatcher.close()
            EditStore.closeForTests(isolated)
        }
        return "Real v1-to-v2 schema upgrade; explicit full-byte baseline; same-size different bytes rejected; unknown baseline, registered target and stale revision refused; atomic source/session rollback; stable asset/variant/revision and opaque edits; retired URI blocks stale writes; queued session normalization; offline order/current/empty selection and future session preserved; source cancellation and retired ViewModel writer ordering passed"
    }
}
