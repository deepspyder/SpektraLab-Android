package com.spektralab.android.data.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.spektralab.android.core.model.AlbumNames
import com.spektralab.android.core.model.LightTableProject
import com.spektralab.android.core.model.LightTableScene
import com.spektralab.android.core.model.TableProjectSummary
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Separate, metadata-only project store. No render session or original-file ownership. */
internal class LightTableProjectStore(directory: File) : Closeable {
    enum class Checkpoint { WRITTEN, COMMITTED }
    internal var checkpoint: (Checkpoint) -> Unit = {}
    private val db: SQLiteDatabase

    init {
        check(directory.isDirectory || directory.mkdirs())
        db = SQLiteDatabase.openDatabase(File(directory, "lighttable.db").path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            android.database.DatabaseErrorHandler { android.util.Log.e("LightTable", "Damaged project database retained") })
        try {
            require(db.version in 0..1) { "TABLE_FUTURE_VERSION" }
            if (db.version == 0) transaction {
                db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,name_key TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL CHECK(revision>0),updated_at INTEGER NOT NULL,payload TEXT NOT NULL)")
                db.version = 1
            }
        } catch (error: Throwable) { db.close(); throw error }
    }

    fun list(): List<TableProjectSummary> = db.rawQuery("SELECT id,name,revision,updated_at FROM projects ORDER BY updated_at DESC,id", null).use { c ->
        buildList { while (c.moveToNext()) add(TableProjectSummary(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))) }
    }

    fun read(id: String): LightTableProject = db.rawQuery("SELECT name,revision,payload FROM projects WHERE id=?", arrayOf(id)).use {
        check(it.moveToFirst()) { "TABLE_MISSING" }
        LightTableProject(id, it.getString(0), it.getLong(1), LightTableScene.fromJson(it.getString(2)))
    }

    fun save(expected: LightTableProject?, name: String, scene: LightTableScene): LightTableProject {
        val title = AlbumNames.normalize(name)
        val payload = scene.toJson()
        require(payload.length <= 2 * 1024 * 1024) { "TABLE_CAPACITY" }
        val result = transaction {
            if (expected == null) check(list().size < 100) { "TABLE_CAPACITY" } else check(read(expected.id) == expected) { "TABLE_CHANGED" }
            val id = expected?.id ?: UUID.randomUUID().toString()
            db.rawQuery("SELECT id FROM projects WHERE name_key=?", arrayOf(AlbumNames.key(title))).use {
                check(!it.moveToFirst() || it.getString(0) == id) { "TABLE_NAME_EXISTS" }
            }
            val revision = (expected?.revision ?: 0) + 1
            if (expected?.name == title && expected.scene == scene) return@transaction expected
            val values = ContentValues().apply {
                put("id", id); put("name", title); put("name_key", AlbumNames.key(title))
                put("revision", revision); put("updated_at", System.currentTimeMillis()); put("payload", payload)
            }
            if (expected == null) db.insertOrThrow("projects", null, values)
            else check(db.update("projects", values, "id=? AND revision=?", arrayOf(id, expected.revision.toString())) == 1) { "TABLE_CHANGED" }
            checkpoint(Checkpoint.WRITTEN)
            LightTableProject(id, title, revision, scene)
        }
        checkpoint(Checkpoint.COMMITTED)
        return result
    }

    fun delete(expected: LightTableProject) = transaction {
        check(read(expected.id) == expected) { "TABLE_CHANGED" }
        check(db.delete("projects", "id=? AND revision=?", arrayOf(expected.id, expected.revision.toString())) == 1)
        checkpoint(Checkpoint.WRITTEN)
    }.also { checkpoint(Checkpoint.COMMITTED) }

    private fun <T> transaction(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }

    override fun close() = db.close()
}
