package com.spektralab.android.data.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.spektralab.android.core.model.CanonicalJson
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.core.model.SourceEvidence
import com.spektralab.android.core.model.PhotoLabel
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.core.model.EditVersionUri
import com.spektralab.android.data.local.writeAtomicJson
import java.io.Closeable
import java.io.File
import java.util.UUID
import org.json.JSONObject

internal data class SavedPhoto(val assetId: String, val variantId: String, val uri: String, val name: String,
    val revision: Long, val updatedAt: Long, val sourceSha256: String? = null, val sourceSize: Long? = null,
    val rating: Int = 0, val label: PhotoLabel = PhotoLabel.NONE)

internal data class PhotoLibraryPage(val photos: List<SavedPhoto>, val albums: List<com.spektralab.android.core.model.PhotoAlbum>,
    val matchingCount: Long, val totalCount: Long)

/** Production default-variant store. All calls are serialized by the EditStore facade. */
internal class SavedPhotoRepository private constructor(private val directory: File, private val db: SQLiteDatabase) : Closeable {
    val organization = PhotoOrganizationRepository(db)
    val versions = EditVersionRepository(db) { checkEditable(it) }
    data class PendingWrite(val uri: Uri, val expected: String?, val json: String)
    enum class Checkpoint { BACKUP_READY, IMPORT_WRITTEN, IMPORT_COMMITTED, BATCH_WRITTEN, SOURCE_RELINKED, SOURCE_RELINK_COMMITTED }
    internal var checkpoint: (Checkpoint) -> Unit = {}
    private val validated = object : LinkedHashMap<String, Boolean>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 8
    }
    private val sourceKeys = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    private fun key(uri: Uri): String {
        val source = uri.toString()
        return sourceKeys.getOrPut(source) { CanonicalJson.fingerprint("uri-v1:$source") }
    }

    fun read(uri: Uri): String? {
        if (EditVersionUri.owns(uri)) return versions.find(EditVersionUri.id(uri)).paramsJson
        val result = db.rawQuery("SELECT e.params FROM edits e JOIN assets a USING(asset_id) WHERE a.source_key=?",
            arrayOf(key(uri))).use { if (it.moveToFirst()) it.getString(0) else null }
        if (result == null) check(resolveLocation(uri.toString()) == uri.toString()) { "SOURCE_RELINKED" }
        return result
    }

    fun exists(uri: Uri): Boolean = read(uri) != null

    /** Only an actually opened URI can claim an ambiguous old key; a second URI never inherits it. */
    fun adopt(uri: Uri, legacyKey: String, raw: String?, name: String): String? {
        require(LegacyMigrationArchive.validKey(legacyKey))
        read(uri)?.let { return it }
        if (raw == null) return null
        require(raw.toByteArray(Charsets.UTF_8).size <= LegacyMigrationArchive.MAX_EDIT_BYTES) { "EDIT_TOO_LARGE" }
        JSONObject(raw) // A broken record must not become defaults or be overwritten by analysis.
        val digest = CanonicalJson.fingerprint(raw)
        val backup = File(directory, "legacy-backups/$legacyKey-$digest.json")
        if (!backup.exists()) writeAtomicJson(backup, raw)
        check(backup.readText() == raw) { "BACKUP_CHANGED" }
        checkpoint(Checkpoint.BACKUP_READY)
        transaction {
            read(uri)?.let { return@transaction }
            db.rawQuery("SELECT source_key FROM legacy_claims WHERE legacy_key=?", arrayOf(legacyKey)).use {
                check(!it.moveToFirst() || it.getString(0) == key(uri)) { "LEGACY_SOURCE_AMBIGUOUS" }
            }
            insert(uri, raw, name)
            db.insertWithOnConflict("legacy_claims", null, values("legacy_key" to legacyKey, "source_key" to key(uri),
                "backup" to backup.name, "sha256" to digest), SQLiteDatabase.CONFLICT_ABORT)
            checkpoint(Checkpoint.IMPORT_WRITTEN)
        }
        checkpoint(Checkpoint.IMPORT_COMMITTED)
        return read(uri)
    }

    fun save(uri: Uri, json: String, name: String) = transaction {
        val incoming = JSONObject(json)
        val current = read(uri)
        checkEditable(current)
        if (current == json) return@transaction
        if (current == null) insert(uri, json, name) else update(uri, json)
        // Incoming values are checked once; the exact serialized value can be reused next frame.
        rememberValidation(json, incoming)
    }

    fun saveBatch(writes: List<PendingWrite>, names: Map<Uri, String>) = transaction {
        check(writes.map { key(it.uri) }.distinct().size == writes.size) { "EDIT_TARGET_ALIAS" }
        writes.forEach { check(read(it.uri) == it.expected) { "EDIT_TARGET_CHANGED" }; checkEditable(it.expected); JSONObject(it.json) }
        writes.forEach { write ->
            if (write.expected == null) insert(write.uri, write.json, names[write.uri].orEmpty()) else update(write.uri, write.json)
        }
        checkpoint(Checkpoint.BATCH_WRITTEN)
    }

    fun list(limit: Int, offset: Int, filter: PhotoLibraryFilter = PhotoLibraryFilter()): List<SavedPhoto> {
        require(limit in 1..100 && offset >= 0)
        val (where, args) = visibleSelection(filter)
        return db.rawQuery("$PHOTO_COLUMNS$where ORDER BY e.updated_at DESC,a.asset_id LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString())).use { cursor -> buildList {
            while (cursor.moveToNext()) add(photo(cursor))
        } }
    }
    fun count(): Long = db.rawQuery("SELECT COUNT(*) FROM edits e WHERE NOT EXISTS(SELECT 1 FROM hidden_photos h WHERE h.asset_id=e.asset_id)", null).use { it.moveToFirst(); it.getLong(0) }
    fun matchingCount(filter: PhotoLibraryFilter): Long {
        val (where, args) = visibleSelection(filter)
        return db.rawQuery("SELECT COUNT(*) FROM assets a JOIN edits e USING(asset_id) LEFT JOIN photo_marks m USING(asset_id)$where", args)
            .use { it.moveToFirst(); it.getLong(0) }
    }
    fun page(filter: PhotoLibraryFilter, offset: Int) = transaction {
        val albums = db.rawQuery("SELECT a.album_id,a.name,COUNT(member.asset_id) FROM albums a LEFT JOIN album_members member ON member.album_id=a.album_id AND NOT EXISTS(SELECT 1 FROM hidden_photos h WHERE h.asset_id=member.asset_id) GROUP BY a.album_id ORDER BY a.created_at,a.album_id", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(com.spektralab.android.core.model.PhotoAlbum(cursor.getString(0), cursor.getString(1), cursor.getLong(2))) } }
        PhotoLibraryPage(list(60, offset, filter), albums, matchingCount(filter), count())
    }

    private fun visibleSelection(filter: PhotoLibraryFilter): Pair<String, Array<String>> {
        val (where, args) = PhotoOrganizationRepository.selection(filter)
        val join = if (where.isBlank()) " WHERE " else "$where AND "
        return "${join}NOT EXISTS(SELECT 1 FROM hidden_photos h WHERE h.asset_id=a.asset_id)" to args
    }

    /** One metadata snapshot, including entries beyond the currently loaded page. */
    fun selectable(filter: PhotoLibraryFilter): List<SavedPhoto> = transaction {
        val count = matchingCount(filter)
        require(count <= 5000) { "PHOTO_SELECTION_TOO_LARGE" }
        buildList { while (size < count) addAll(list(100, size, filter)) }
    }

    /** Hide atomically; originals, versions, albums and workbench references stay intact. */
    fun hide(expected: List<SavedPhoto>) = transaction {
        require(expected.isNotEmpty() && expected.size <= 5000)
        require(expected.map { it.assetId }.distinct().size == expected.size)
        expected.forEach { photo ->
            val actual = find(photo.assetId)
            check(actual != null && actual.uri == photo.uri && actual.variantId == photo.variantId &&
                actual.revision == photo.revision) { "EDIT_TARGET_CHANGED" }
            check(db.rawQuery("SELECT 1 FROM hidden_photos WHERE asset_id=?", arrayOf(photo.assetId))
                .use { !it.moveToFirst() }) { "EDIT_TARGET_CHANGED" }
        }
        expected.forEach { db.insertOrThrow("hidden_photos", null, values("asset_id" to it.assetId)) }
    }

    /** Only an explicit import restores library visibility; ordinary autosaves never do. */
    fun restoreImported(uris: List<Uri>) = transaction {
        uris.distinct().forEach { uri ->
            if (!EditVersionUri.owns(uri)) findByUri(uri)?.let {
                db.delete("hidden_photos", "asset_id=?", arrayOf(it.assetId))
            }
        }
    }

    fun find(assetId: String): SavedPhoto? = db.rawQuery("$PHOTO_COLUMNS WHERE a.asset_id=?", arrayOf(assetId)).use {
        if (it.moveToFirst()) photo(it) else null
    }
    fun findByUri(uri: Uri): SavedPhoto? = db.rawQuery("$PHOTO_COLUMNS WHERE a.source_key=?", arrayOf(key(uri))).use {
        if (it.moveToFirst()) photo(it) else null
    }

    fun deleteVersion(expected: com.spektralab.android.core.model.EditVersion) = versions.delete(expected) { removed ->
        session()?.let { session ->
            writeSession(session.without(setOf(removed)))
        }
    }

    private fun current(expected: SavedPhoto): SavedPhoto {
        val actual = find(expected.assetId) ?: error("EDIT_MISSING")
        check(actual.uri == expected.uri && actual.revision == expected.revision) { "EDIT_TARGET_CHANGED" }
        checkEditable(read(Uri.parse(actual.uri)))
        return actual
    }

    /** The first explicit check establishes today's baseline; it makes no claim about older bytes. */
    fun checkSource(expected: SavedPhoto, source: SourceEvidence) = transaction {
        val actual = current(expected)
        require(source.verified && source.uri == actual.uri) { "SOURCE_UNAVAILABLE" }
        check(actual.sourceSha256 == null || matches(actual, source)) { "SOURCE_CONTENT_CHANGED" }
        db.insertWithOnConflict("source_checks", null, values("asset_id" to actual.assetId,
            "sha256" to source.sha256!!, "bytes" to source.size), SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun relink(expected: SavedPhoto, source: SourceEvidence) {
      transaction {
        val actual = current(expected)
        require(source.verified) { "SOURCE_UNAVAILABLE" }
        if (source.uri == actual.uri) {
            checkSource(actual, source)
            return@transaction
        }
        check(actual.sourceSha256 != null) { "SOURCE_BASELINE_MISSING" }
        check(matches(actual, source)) { "SOURCE_CONTENT_CHANGED" }
        val target = Uri.parse(source.uri)
        db.rawQuery("SELECT asset_id FROM assets WHERE source_key=?", arrayOf(key(target))).use {
            check(!it.moveToFirst()) { "SOURCE_ALREADY_REGISTERED" }
        }
        val workbench = session()
        check(workbench == null || source.uri !in workbench.photos) { "SOURCE_ALREADY_REGISTERED" }
        // Keep retired locations as tombstones so stale background writes cannot resurrect sidecars.
        db.insertWithOnConflict("retired_sources", null, values("source_key" to key(Uri.parse(actual.uri)),
            "asset_id" to actual.assetId), SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("UPDATE assets SET source_key=?,uri=?,name=? WHERE asset_id=?",
            arrayOf(key(target), source.uri, source.displayName, actual.assetId))
        workbench?.let { writeSession(it.remap(::resolveLocation)) }
        checkpoint(Checkpoint.SOURCE_RELINKED)
      }
      checkpoint(Checkpoint.SOURCE_RELINK_COMMITTED)
    }

    fun session(): SavedEditSession? = db.rawQuery("SELECT payload FROM workbench WHERE id='default'", null).use {
        if (it.moveToFirst()) SavedEditSession.fromJson(it.getString(0)) else null
    }

    fun saveSession(snapshot: SavedEditSession) = transaction {
        session() // An unreadable/future session remains untouched rather than being silently reset.
        writeSession(snapshot.remap(::resolveLocation))
    }

    private fun writeSession(session: SavedEditSession) {
        val removed = session.photos.filterTo(mutableSetOf()) {
            val uri = Uri.parse(it)
            EditVersionUri.owns(uri) && versions.deleted(EditVersionUri.id(uri))
        }
        val json = session.without(removed).toJson()
        require(json.length <= 2 * 1024 * 1024) { "WORKBENCH_TOO_LARGE" }
        val unchanged = db.rawQuery("SELECT payload FROM workbench WHERE id='default'", null).use {
            it.moveToFirst() && it.getString(0) == json
        }
        if (!unchanged) db.insertWithOnConflict("workbench", null, values("id" to "default", "payload" to json), SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun resolveLocation(uri: String): String = db.rawQuery(
        "SELECT a.uri FROM retired_sources r JOIN assets a USING(asset_id) WHERE r.source_key=?",
        arrayOf(key(Uri.parse(uri))),
    ).use { if (it.moveToFirst()) it.getString(0) else uri }

    private fun matches(photo: SavedPhoto, source: SourceEvidence): Boolean =
        photo.sourceSha256 == source.sha256 && photo.sourceSize == source.size

    private fun photo(cursor: android.database.Cursor) = SavedPhoto(
        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
        cursor.getLong(4), cursor.getLong(5), cursor.getString(6),
        if (cursor.isNull(7)) null else cursor.getLong(7),
        cursor.getInt(8), PhotoLabel.fromId(cursor.getString(9)),
    )

    private fun insert(uri: Uri, json: String, name: String) {
        val assetId = UUID.randomUUID().toString()
        db.insertOrThrow("assets", null, values("asset_id" to assetId, "source_key" to key(uri), "uri" to uri.toString(),
            "name" to name.ifBlank { uri.lastPathSegment.orEmpty() }))
        db.insertOrThrow("edits", null, values("variant_id" to UUID.randomUUID().toString(), "asset_id" to assetId,
            "params" to json, "revision" to 1L, "updated_at" to System.currentTimeMillis(), "render_version" to "android-legacy-v1"))
    }
    private fun update(uri: Uri, json: String) {
        if (EditVersionUri.owns(uri)) { versions.save(EditVersionUri.id(uri), json); return }
        db.execSQL("UPDATE edits SET params=?,revision=revision+1,updated_at=? WHERE asset_id=(SELECT asset_id FROM assets WHERE source_key=?)",
            arrayOf<Any>(json, System.currentTimeMillis(), key(uri)))
    }
    private fun checkEditable(raw: String?) {
        if (raw == null) return
        if (validated[raw] == true) return
        validate(JSONObject(raw))
        validated[raw] = true
    }
    private fun rememberValidation(raw: String, json: JSONObject) {
        // Do not reject preservation of future opaque records here. Their next mutation is blocked.
        if (runCatching { validate(json) }.isSuccess) validated[raw] = true
    }
    private fun validate(json: JSONObject) {
        com.spektralab.android.core.model.HalationModel.requireReadable(json)
        for (key in json.keys()) if (knownTypes.containsKey(key)) {
            val value = json.get(key)
            check(when (knownTypes[key]) {
                "number" -> value is Number && value.toDouble().isFinite()
                "boolean" -> value is Boolean
                "string" -> value is String
                else -> false
            }) { "INVALID_EDIT_TYPE" }
        }
        for (version in listOf("schema_version", "gradeVersion", "grade_version", "colorPipelineVersion", "color_pipeline_version"))
            check(!json.has(version) || json.optDouble(version, Double.NaN) == 1.0) { "FUTURE_EDIT_READ_ONLY" }
    }
    private fun <T> transaction(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    override fun close() = db.close()

    companion object {
        private const val PHOTO_COLUMNS = "SELECT a.asset_id,e.variant_id,a.uri,a.name,e.revision,e.updated_at,s.sha256,s.bytes,COALESCE(m.rating,0),COALESCE(m.label,'none') FROM assets a JOIN edits e USING(asset_id) LEFT JOIN source_checks s USING(asset_id) LEFT JOIN photo_marks m USING(asset_id)"
        private val knownTypes by lazy {
            val defaults = com.spektralab.android.core.model.EditorParams().toJson()
            defaults.keys().asSequence().associateWith { when (defaults.get(it)) {
                is Number -> "number"; is Boolean -> "boolean"; else -> "string"
            } }
        }
        fun open(directory: File): SavedPhotoRepository {
            check(directory.isDirectory || directory.mkdirs())
            val db = SQLiteDatabase.openDatabase(File(directory, "saved-photos.db").absolutePath, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                android.database.DatabaseErrorHandler {
                    // Android's default handler deletes a corrupt database. Keep it for recovery.
                    android.util.Log.e("SavedPhotos", "Database corruption detected; files retained")
                })
            try {
                db.setForeignKeyConstraintsEnabled(true)
                require(db.version in 0..5) { "UNSUPPORTED_SAVED_PHOTOS_VERSION" }
                val repository = SavedPhotoRepository(directory, db)
                if (db.version == 0) repository.transaction {
                    db.execSQL("CREATE TABLE assets(asset_id TEXT PRIMARY KEY,source_key TEXT UNIQUE NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE edits(variant_id TEXT PRIMARY KEY,asset_id TEXT UNIQUE NOT NULL REFERENCES assets(asset_id),params TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),updated_at INTEGER NOT NULL,render_version TEXT NOT NULL)")
                    db.execSQL("CREATE INDEX edit_updated ON edits(updated_at DESC)")
                    db.execSQL("CREATE TABLE legacy_claims(legacy_key TEXT PRIMARY KEY,source_key TEXT NOT NULL,backup TEXT NOT NULL,sha256 TEXT NOT NULL)")
                    db.version = 1
                }
                if (db.version == 1) repository.transaction {
                    db.execSQL("CREATE TABLE source_checks(asset_id TEXT PRIMARY KEY REFERENCES assets(asset_id),sha256 TEXT NOT NULL,bytes INTEGER NOT NULL CHECK(bytes>0))")
                    db.execSQL("CREATE TABLE retired_sources(source_key TEXT PRIMARY KEY,asset_id TEXT NOT NULL REFERENCES assets(asset_id))")
                    db.execSQL("CREATE TABLE workbench(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
                    db.version = 2
                }
                if (db.version == 2) repository.transaction {
                    PhotoOrganizationRepository.createSchema(db)
                    db.version = 3
                }
                if (db.version == 3) repository.transaction {
                    EditVersionRepository.createSchema(db)
                    db.version = 4
                }
                if (db.version == 4) repository.transaction {
                    db.execSQL("CREATE TABLE hidden_photos(asset_id TEXT PRIMARY KEY REFERENCES assets(asset_id))")
                    db.version = 5
                }
                return repository
            } catch (error: Throwable) { db.close(); throw error }
        }
        private fun values(vararg entries: Pair<String, Any>) = ContentValues().apply { entries.forEach { (name, value) ->
            when (value) { is String -> put(name, value); is Long -> put(name, value); else -> error("Invalid column value") }
        } }
    }
}
