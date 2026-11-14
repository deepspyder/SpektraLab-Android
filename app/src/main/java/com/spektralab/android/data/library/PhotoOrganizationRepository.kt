package com.spektralab.android.data.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.spektralab.android.core.model.AlbumNames
import com.spektralab.android.core.model.PhotoAlbum
import com.spektralab.android.core.model.PhotoLabel
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.core.model.PhotoOrganization
import java.util.UUID

/** Metadata only, serialized by EditStore. No edit payload, source stream or renderer access. */
internal class PhotoOrganizationRepository(private val db: SQLiteDatabase) {
    internal var beforeCommit: () -> Unit = {}

    fun albums(): List<PhotoAlbum> = db.rawQuery(
        "SELECT a.album_id,a.name,COUNT(m.asset_id) FROM albums a LEFT JOIN album_members m USING(album_id) GROUP BY a.album_id ORDER BY a.created_at,a.album_id", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(PhotoAlbum(cursor.getString(0), cursor.getString(1), cursor.getLong(2))) } }

    fun createAlbum(name: String): PhotoAlbum = transaction {
        val normalized = AlbumNames.normalize(name)
        val nameKey = AlbumNames.key(normalized)
        ensureUniqueName(nameKey)
        check(albums().size < 200) { "ALBUM_LIMIT_REACHED" }
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("albums", null, ContentValues().apply {
            put("album_id", id); put("name", normalized); put("name_key", nameKey); put("created_at", System.currentTimeMillis())
        })
        PhotoAlbum(id, normalized, 0)
    }

    fun renameAlbum(id: String, name: String) = transaction {
        val normalized = AlbumNames.normalize(name)
        ensureAlbum(id)
        ensureUniqueName(AlbumNames.key(normalized), id)
        db.execSQL("UPDATE albums SET name=?,name_key=? WHERE album_id=?", arrayOf(normalized, AlbumNames.key(normalized), id))
    }

    /** Only memberships are removed; the source, edit, marks and workbench are untouched. */
    fun deleteAlbum(id: String) = transaction {
        ensureAlbum(id)
        db.delete("album_members", "album_id=?", arrayOf(id))
        db.delete("albums", "album_id=?", arrayOf(id))
    }

    fun read(assetId: String): PhotoOrganization {
        ensureAsset(assetId)
        val membership = db.rawQuery("SELECT album_id FROM album_members WHERE asset_id=?", arrayOf(assetId)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return db.rawQuery("SELECT rating,label,revision FROM photo_marks WHERE asset_id=?", arrayOf(assetId)).use {
            if (it.moveToFirst()) PhotoOrganization(it.getInt(0), PhotoLabel.fromId(it.getString(1)), membership, it.getLong(2))
            else PhotoOrganization(albums = membership)
        }
    }

    fun save(assetId: String, expected: PhotoOrganization, updated: PhotoOrganization) = transaction {
        val current = read(assetId)
        check(current == expected) { "ORGANIZATION_CHANGED" }
        updated.albums.forEach(::ensureAlbum)
        if (current.rating == updated.rating && current.label == updated.label && current.albums == updated.albums) return@transaction
        db.insertWithOnConflict("photo_marks", null, ContentValues().apply {
            put("asset_id", assetId); put("rating", updated.rating); put("label", updated.label.id); put("revision", current.revision + 1)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        (current.albums - updated.albums).forEach { db.delete("album_members", "album_id=? AND asset_id=?", arrayOf(it, assetId)) }
        (updated.albums - current.albums).forEach { albumId ->
            db.insertOrThrow("album_members", null, ContentValues().apply { put("album_id", albumId); put("asset_id", assetId) })
        }
    }

    /** Add a selected set to one existing album atomically; edit payloads stay untouched. */
    fun addToAlbum(assetIds: List<String>, albumId: String) = transaction {
        ensureAlbum(albumId)
        assetIds.distinct().forEach { assetId ->
            ensureAsset(assetId)
            db.insertWithOnConflict("album_members", null, ContentValues().apply {
                put("album_id", albumId); put("asset_id", assetId)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun ensureUniqueName(key: String, except: String? = null) {
        db.rawQuery("SELECT album_id FROM albums WHERE name_key=?", arrayOf(key)).use {
            check(!it.moveToFirst() || it.getString(0) == except) { "ALBUM_NAME_EXISTS" }
        }
    }
    private fun ensureAlbum(id: String) {
        db.rawQuery("SELECT 1 FROM albums WHERE album_id=?", arrayOf(id)).use { check(it.moveToFirst()) { "ALBUM_MISSING" } }
    }
    private fun ensureAsset(id: String) {
        db.rawQuery("SELECT 1 FROM assets WHERE asset_id=?", arrayOf(id)).use { check(it.moveToFirst()) { "EDIT_MISSING" } }
    }
    private fun <T> transaction(action: () -> T): T {
        db.beginTransactionNonExclusive()
        try { val result = action(); beforeCommit(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }

    companion object {
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE photo_marks(asset_id TEXT PRIMARY KEY REFERENCES assets(asset_id),rating INTEGER NOT NULL CHECK(rating BETWEEN 0 AND 5),label TEXT NOT NULL CHECK(label IN ('none','red','yellow','green','blue','purple')),revision INTEGER NOT NULL CHECK(revision>0))")
            db.execSQL("CREATE INDEX marks_rating ON photo_marks(rating)")
            db.execSQL("CREATE INDEX marks_label ON photo_marks(label)")
            db.execSQL("CREATE TABLE albums(album_id TEXT PRIMARY KEY,name TEXT NOT NULL,name_key TEXT UNIQUE NOT NULL,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE album_members(album_id TEXT NOT NULL REFERENCES albums(album_id),asset_id TEXT NOT NULL REFERENCES assets(asset_id),PRIMARY KEY(album_id,asset_id))")
            db.execSQL("CREATE INDEX memberships_asset ON album_members(asset_id)")
        }

        fun selection(filter: PhotoLibraryFilter): Pair<String, Array<String>> {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            filter.albumId?.let {
                clauses += "EXISTS(SELECT 1 FROM album_members member WHERE member.asset_id=a.asset_id AND member.album_id=?)"; args += it
            }
            if (filter.minimumRating > 0) { clauses += "COALESCE(m.rating,0)>=CAST(? AS INTEGER)"; args += filter.minimumRating.toString() }
            if (filter.unratedOnly) clauses += "COALESCE(m.rating,0)=0"
            filter.label?.let { clauses += "COALESCE(m.label,'none')=?"; args += it.id }
            return (if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")) to args.toTypedArray()
        }
    }
}
