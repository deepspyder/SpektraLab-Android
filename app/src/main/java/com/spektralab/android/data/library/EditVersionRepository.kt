package com.spektralab.android.data.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.spektralab.android.core.model.AlbumNames
import com.spektralab.android.core.model.EditVersion
import com.spektralab.android.core.model.EditVersionUri
import java.util.UUID
import org.json.JSONObject

/** Production virtual-edit rows. Default records retain their original table and IDs. */
internal class EditVersionRepository(private val db: SQLiteDatabase, private val validate: (String) -> Unit) {
    enum class Checkpoint { CREATED, DELETED, COMMITTED }
    internal var checkpoint: (Checkpoint) -> Unit = {}

    fun list(assetId: String): List<EditVersion> {
        val default = db.rawQuery("SELECT e.variant_id,a.asset_id,a.uri,a.name,e.params,e.revision FROM assets a JOIN edits e USING(asset_id) WHERE a.asset_id=?",
            arrayOf(assetId)).use { c ->
            check(c.moveToFirst()) { "EDIT_MISSING" }
            EditVersion(c.getString(0), c.getString(1), "", true, c.getString(2), c.getString(3), c.getString(4), c.getLong(5))
        }
        return listOf(default) + db.rawQuery("$COPY_COLUMNS WHERE v.asset_id=? AND v.deleted=0 ORDER BY v.created_at,v.version_id", arrayOf(assetId)).use { c ->
            buildList { while (c.moveToNext()) add(copy(c)) }
        }
    }

    fun find(id: String): EditVersion = db.rawQuery("$COPY_COLUMNS WHERE v.version_id=? AND v.deleted=0", arrayOf(id)).use {
        check(it.moveToFirst()) { "EDIT_VERSION_MISSING" }; copy(it)
    }
    fun deleted(id: String): Boolean = db.rawQuery("SELECT deleted FROM virtual_edits WHERE version_id=?", arrayOf(id)).use {
        check(it.moveToFirst()) { "EDIT_VERSION_MISSING" }; it.getInt(0) == 1
    }

    fun create(expected: EditVersion, name: String): EditVersion = transaction {
        val current = current(expected)
        validate(current.paramsJson)
        val title = AlbumNames.normalize(name)
        check(list(current.assetId).size <= 30) { "EDIT_VERSION_LIMIT" }
        ensureName(current.assetId, title)
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("virtual_edits", null, ContentValues().apply {
            put("version_id", id); put("asset_id", current.assetId); put("name", title); put("name_key", AlbumNames.key(title))
            put("params", current.paramsJson); put("revision", 1L); put("created_at", System.currentTimeMillis()); put("deleted", 0)
        })
        checkpoint(Checkpoint.CREATED)
        find(id)
    }.also { checkpoint(Checkpoint.COMMITTED) }

    fun rename(expected: EditVersion, name: String) = transaction {
        val current = current(expected)
        check(!current.isDefault) { "DEFAULT_VERSION_PROTECTED" }
        val title = AlbumNames.normalize(name)
        ensureName(current.assetId, title, current.id)
        if (title != current.name) db.execSQL("UPDATE virtual_edits SET name=?,name_key=?,revision=revision+1 WHERE version_id=?",
            arrayOf(title, AlbumNames.key(title), current.id))
    }

    /** Keep the payload as a tombstone. Old in-flight saves can never recreate a deleted version. */
    fun delete(expected: EditVersion, updateSession: (String) -> Unit) = transaction {
        val current = current(expected)
        check(!current.isDefault) { "DEFAULT_VERSION_PROTECTED" }
        db.execSQL("UPDATE virtual_edits SET deleted=1,revision=revision+1 WHERE version_id=?", arrayOf(current.id))
        updateSession(current.editUri.toString())
        checkpoint(Checkpoint.DELETED)
    }.also { checkpoint(Checkpoint.COMMITTED) }

    fun save(id: String, json: String) {
        JSONObject(json)
        val current = find(id)
        validate(current.paramsJson)
        if (current.paramsJson == json) return
        db.execSQL("UPDATE virtual_edits SET params=?,revision=revision+1 WHERE version_id=? AND deleted=0", arrayOf(json, id))
    }

    private fun current(expected: EditVersion): EditVersion {
        val actual = if (expected.isDefault) list(expected.assetId).first() else find(expected.id)
        check(actual.id == expected.id && actual.assetId == expected.assetId && actual.sourceUri == expected.sourceUri &&
            actual.revision == expected.revision && actual.paramsJson == expected.paramsJson) { "EDIT_VERSION_CHANGED" }
        return actual
    }
    private fun ensureName(assetId: String, name: String, except: String? = null) {
        db.rawQuery("SELECT version_id FROM virtual_edits WHERE asset_id=? AND name_key=? AND deleted=0",
            arrayOf(assetId, AlbumNames.key(name))).use { check(!it.moveToFirst() || it.getString(0) == except) { "EDIT_VERSION_NAME_EXISTS" } }
    }
    private fun copy(c: android.database.Cursor) = EditVersion(c.getString(0), c.getString(1), c.getString(2), false,
        c.getString(3), c.getString(4), c.getString(5), c.getLong(6))
    private fun <T> transaction(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    companion object {
        private const val COPY_COLUMNS = "SELECT v.version_id,v.asset_id,v.name,a.uri,a.name,v.params,v.revision FROM virtual_edits v JOIN assets a USING(asset_id)"
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE virtual_edits(version_id TEXT PRIMARY KEY,asset_id TEXT NOT NULL REFERENCES assets(asset_id),name TEXT NOT NULL,name_key TEXT NOT NULL,params TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),created_at INTEGER NOT NULL,deleted INTEGER NOT NULL CHECK(deleted IN (0,1)))")
            db.execSQL("CREATE INDEX virtual_asset ON virtual_edits(asset_id,deleted)")
            db.execSQL("CREATE UNIQUE INDEX virtual_names ON virtual_edits(asset_id,name_key) WHERE deleted=0")
        }
    }
}
