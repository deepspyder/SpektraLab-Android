package com.spektralab.android.data.library

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.spektralab.android.core.model.*
import java.io.Closeable
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** T06 sandbox only. No application singleton, startup migration, or image bytes in this database. */
internal class LibraryPrototypeRepository private constructor(private val db: SQLiteDatabase) : Closeable {
    enum class Checkpoint { BEFORE_RECORD, RECORD_WRITTEN, RECORD_COMMITTED, SESSION_WRITTEN, SESSION_COMMITTED }
    data class Counts(val assets: Long, val locations: Long, val variants: Long, val migrations: Long, val sessions: Long)

    @Synchronized
    fun migrate(archive: LegacyMigrationArchive, checkpoint: (Checkpoint, String) -> Unit = { _, _ -> }): List<MigrationRecord> {
        archive.verify()
        val bindings = archive.bindings.groupBy { it.legacyKey }
        for (entry in archive.entries) {
            checkpoint(Checkpoint.BEFORE_RECORD, entry.key)
            transaction {
                val previous = migration(entry.key)
                val candidates = bindings[entry.key].orEmpty().map { it.source }.distinctBy { it.sourceKey to it.revision }
                val outcome = when {
                    previous?.state == MigrationState.MIGRATED -> {
                        // Re-running old backups never overwrites newer variant edits.
                        val boundAsset = defaultVariant(previous.variantId!!).assetId
                        val boundKey = scalarString("SELECT source_key FROM assets WHERE asset_id=?", arrayOf(boundAsset))!!
                        if (previous.payloadSha256 != entry.sha256 || candidates.any { it.sourceKey != boundKey } ||
                            candidates.filter { it.verified }.any { it.revision != scalarString("SELECT source_revision FROM assets WHERE asset_id=?", arrayOf(boundAsset)) })
                            MigrationRecord(entry.key, MigrationState.SOURCE_CHANGED, entry.sha256, previous.variantId)
                        else previous
                    }
                    previous != null && previous.payloadSha256 != entry.sha256 ->
                        MigrationRecord(entry.key, MigrationState.SOURCE_CHANGED, entry.sha256, previous.variantId)
                    entry.bytes > LegacyMigrationArchive.MAX_EDIT_BYTES -> MigrationRecord(entry.key, MigrationState.INVALID, entry.sha256, null)
                    candidates.size > 1 -> MigrationRecord(entry.key, MigrationState.AMBIGUOUS, entry.sha256, null)
                    candidates.singleOrNull()?.verified != true -> MigrationRecord(entry.key, MigrationState.PENDING_SOURCE, entry.sha256, null)
                    else -> importEntry(archive, entry, candidates.single())
                }
                if (previous?.state != MigrationState.MIGRATED && outcome.state != MigrationState.SOURCE_CHANGED) {
                    db.insertWithOnConflict("migrations", null, values("legacy_key" to entry.key, "payload_sha" to entry.sha256,
                        "state" to outcome.state.name, "variant_id" to outcome.variantId), SQLiteDatabase.CONFLICT_REPLACE)
                }
                if (outcome.state == MigrationState.MIGRATED) db.update("session_items", values("variant_id" to outcome.variantId),
                    "legacy_key=? AND variant_id IS NULL", arrayOf(entry.key))
                db.insertWithOnConflict("migration_attempts", null, values("archive_id" to archive.id, "legacy_key" to entry.key,
                    "payload_sha" to entry.sha256, "state" to outcome.state.name, "variant_id" to outcome.variantId,
                    "backup_path" to File(archive.directory, entry.relativePath).canonicalPath), SQLiteDatabase.CONFLICT_REPLACE)
                checkpoint(Checkpoint.RECORD_WRITTEN, entry.key)
            }
            checkpoint(Checkpoint.RECORD_COMMITTED, entry.key)
        }
        transaction {
            db.insertWithOnConflict("sessions", null, values("session_id" to archive.id,
                "current_position" to archive.currentPosition), SQLiteDatabase.CONFLICT_IGNORE)
            archive.sessionKeys.forEachIndexed { index, key ->
                val variant = migration(key)?.takeIf { it.state == MigrationState.MIGRATED }?.variantId
                db.insertWithOnConflict("session_items", null, values("session_id" to archive.id, "position" to index,
                    "legacy_key" to key, "variant_id" to variant), SQLiteDatabase.CONFLICT_IGNORE)
                // A previously unresolved slot may be filled later, retaining order/current index.
                if (variant != null) db.update("session_items", values("variant_id" to variant),
                    "session_id=? AND position=? AND variant_id IS NULL", arrayOf(archive.id, index.toString()))
            }
            checkpoint(Checkpoint.SESSION_WRITTEN, archive.id)
        }
        checkpoint(Checkpoint.SESSION_COMMITTED, archive.id)
        return attempts(archive.id)
    }

    private fun importEntry(archive: LegacyMigrationArchive, entry: LegacyMigrationArchive.Entry, source: SourceEvidence): MigrationRecord {
        val raw = archive.payload(entry)
        val json = try { JSONObject(raw) } catch (_: Exception) {
            return MigrationRecord(entry.key, MigrationState.INVALID, entry.sha256, null)
        }
        val canonical = try { CanonicalJson.encode(json) } catch (_: Exception) {
            return MigrationRecord(entry.key, MigrationState.INVALID, entry.sha256, null)
        }
        val support = support(json)
        // Only the same provider/document identity aliases an existing asset, never equal hashes.
        var assetId = scalarString("SELECT asset_id FROM locations WHERE source_key=?", arrayOf(source.sourceKey))
        if (assetId != null) {
            val revision = scalarString("SELECT source_revision FROM assets WHERE asset_id=?", arrayOf(assetId))
            if (revision != source.revision) return MigrationRecord(entry.key, MigrationState.SOURCE_CHANGED, entry.sha256, null)
            val existing = scalarString("SELECT variant_id FROM variants WHERE asset_id=? AND is_default=1", arrayOf(assetId))
            // Two legacy records for one URI may conflict. Keep both backups pending, never guess.
            if (existing != null && variant(existing)!!.paramsJson != canonical)
                return MigrationRecord(entry.key, MigrationState.AMBIGUOUS, entry.sha256, null)
        } else {
            assetId = UUID.randomUUID().toString()
            db.insertOrThrow("assets", null, values("asset_id" to assetId, "source_key" to source.sourceKey,
                "source_revision" to source.revision, "mime" to source.mime, "byte_size" to source.size,
                "width" to source.width, "height" to source.height, "orientation" to source.orientation, "sha256" to source.sha256))
        }
        insertLocation(assetId, source)
        var variantId = scalarString("SELECT variant_id FROM variants WHERE asset_id=? AND is_default=1", arrayOf(assetId))
        if (variantId == null) {
            variantId = UUID.randomUUID().toString()
            db.insertOrThrow("variants", null, values("variant_id" to variantId, "asset_id" to assetId, "is_default" to 1,
                "params_json" to canonical, "revision" to 1L, "edit_schema_version" to 1,
                "render_version" to if (support == LibraryEditSupport.LEGACY) LEGACY_RENDER else "preserved-unknown",
                "support" to support.name))
        }
        return MigrationRecord(entry.key, MigrationState.MIGRATED, entry.sha256, variantId)
    }

    @Synchronized
    fun variant(id: String): LibraryVariant? = db.rawQuery("SELECT * FROM variants WHERE variant_id=?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else LibraryVariant(it.string("variant_id"), it.string("asset_id"), it.int("is_default") == 1,
            it.string("params_json"), it.long("revision"), it.int("edit_schema_version"), it.string("render_version"),
            LibraryEditSupport.valueOf(it.string("support")))
    }

    private fun defaultVariant(id: String) = checkNotNull(variant(id))

    /** One optimistic writer; stale windows and virtual copies cannot clobber each other. */
    @Synchronized
    fun saveVariant(id: String, expectedRevision: Long, paramsJson: String): LibraryVariant = transaction {
        val old = checkNotNull(variant(id))
        check(old.revision == expectedRevision) { "STALE_VARIANT" }
        check(old.support == LibraryEditSupport.LEGACY) { "UNSUPPORTED_EDIT_READ_ONLY" }
        val json = JSONObject(paramsJson)
        check(support(json) == LibraryEditSupport.LEGACY) { "UNSUPPORTED_EDIT_READ_ONLY" }
        val canonical = CanonicalJson.encode(json)
        check(db.update("variants", values("params_json" to canonical, "revision" to expectedRevision + 1),
            "variant_id=? AND revision=?", arrayOf(id, expectedRevision.toString())) == 1) { "STALE_VARIANT" }
        checkNotNull(variant(id))
    }

    @Synchronized
    fun virtualCopy(id: String, expectedRevision: Long): LibraryVariant = transaction {
        val old = checkNotNull(variant(id))
        check(old.revision == expectedRevision)
        val copyId = UUID.randomUUID().toString()
        db.insertOrThrow("variants", null, values("variant_id" to copyId, "asset_id" to old.assetId, "is_default" to 0,
            "params_json" to old.paramsJson, "revision" to 1L, "edit_schema_version" to old.editSchemaVersion,
            "render_version" to old.renderVersion, "support" to old.support.name))
        checkNotNull(variant(copyId))
    }

    /** Explicit relocation requires a full known fingerprint plus matching type and dimensions. */
    @Synchronized
    fun relocate(assetId: String, source: SourceEvidence) = transaction {
        require(source.verified && source.width > 0 && source.height > 0) { "RELOCATION_UNVERIFIED" }
        db.rawQuery("SELECT * FROM assets WHERE asset_id=?", arrayOf(assetId)).use { old ->
            check(old.moveToFirst())
            check(old.string("sha256") == source.sha256 && old.long("byte_size") == source.size &&
                old.string("mime") == source.mime && old.int("width") == source.width &&
                old.int("height") == source.height && old.int("orientation") == source.orientation) { "RELOCATION_MISMATCH" }
        }
        val owner = scalarString("SELECT asset_id FROM locations WHERE source_key=?", arrayOf(source.sourceKey))
        check(owner == null || owner == assetId) { "LOCATION_BELONGS_TO_OTHER_ASSET" }
        insertLocation(assetId, source)
    }

    private fun insertLocation(assetId: String, source: SourceEvidence) {
        val owner = scalarString("SELECT asset_id FROM locations WHERE source_key=?", arrayOf(source.sourceKey))
        check(owner == null || owner == assetId)
        db.insertWithOnConflict("locations", null, values("source_key" to source.sourceKey, "asset_id" to assetId,
            "uri" to source.uri, "authority" to source.authority, "document_id" to source.documentId,
            "availability" to source.availability.name, "sha256" to source.sha256), SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun markAvailability(sourceKey: String, availability: SourceAvailability) {
        require(availability != SourceAvailability.AVAILABLE) { "RECHECK_SOURCE_BEFORE_RESTORING" }
        db.update("locations", values("availability" to availability.name), "source_key=?", arrayOf(sourceKey))
    }

    @Synchronized
    fun attempts(archiveId: String): List<MigrationRecord> = db.rawQuery(
        "SELECT * FROM migration_attempts WHERE archive_id=? ORDER BY legacy_key", arrayOf(archiveId)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(MigrationRecord(cursor.string("legacy_key"),
            MigrationState.valueOf(cursor.string("state")), cursor.string("payload_sha"), cursor.optional("variant_id"))) }
    }

    @Synchronized
    fun session(id: String): MigratedSession? {
        val current = db.rawQuery("SELECT current_position FROM sessions WHERE session_id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) return null
            if (it.isNull(0)) null else it.getInt(0)
        }
        val items = db.rawQuery("SELECT legacy_key,variant_id FROM session_items WHERE session_id=? ORDER BY position", arrayOf(id)).use {
            buildList { while (it.moveToNext()) add(it.getString(0) to if (it.isNull(1)) null else it.getString(1)) }
        }
        return MigratedSession(id, current, items)
    }

    @Synchronized
    fun counts() = Counts(count("assets"), count("locations"), count("variants"), count("migrations"), count("sessions"))

    @Synchronized
    fun migratedVariant(legacyKey: String): LibraryVariant? = migration(legacyKey)
        ?.takeIf { it.state == MigrationState.MIGRATED }?.variantId?.let(::variant)
    private fun count(table: String): Long = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
    private fun migration(key: String): MigrationRecord? = db.rawQuery("SELECT * FROM migrations WHERE legacy_key=?", arrayOf(key)).use {
        if (!it.moveToFirst()) null else MigrationRecord(key, MigrationState.valueOf(it.string("state")), it.string("payload_sha"), it.optional("variant_id"))
    }
    private fun scalarString(sql: String, args: Array<String>): String? = db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun <T> transaction(body: () -> T): T {
        db.beginTransaction()
        try { val result = body(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    @Synchronized override fun close() = db.close()

    companion object {
        const val LEGACY_RENDER = "android-legacy-v1"
        fun openSandbox(directory: File): LibraryPrototypeRepository {
            check(directory.isDirectory || directory.mkdirs())
            val database = SQLiteDatabase.openOrCreateDatabase(File(directory, "library-prototype.db"), null)
            try {
                database.setForeignKeyConstraintsEnabled(true)
                require(database.version in 0..1) { "UNSUPPORTED_DATABASE_VERSION" }
                val repo = LibraryPrototypeRepository(database)
                if (database.version == 0) repo.transaction {
                    schema.forEach(database::execSQL)
                    database.version = 1
                }
                return repo
            } catch (error: Throwable) { database.close(); throw error }
        }
        private val schema = listOf(
            "CREATE TABLE assets(asset_id TEXT PRIMARY KEY,source_key TEXT NOT NULL UNIQUE,source_revision TEXT NOT NULL,mime TEXT NOT NULL,byte_size INTEGER NOT NULL CHECK(byte_size>0),width INTEGER NOT NULL,height INTEGER NOT NULL,orientation INTEGER NOT NULL,sha256 TEXT NOT NULL)",
            "CREATE TABLE locations(source_key TEXT PRIMARY KEY,asset_id TEXT NOT NULL REFERENCES assets(asset_id),uri TEXT NOT NULL,authority TEXT NOT NULL,document_id TEXT,availability TEXT NOT NULL,sha256 TEXT NOT NULL)",
            "CREATE TABLE variants(variant_id TEXT PRIMARY KEY,asset_id TEXT NOT NULL REFERENCES assets(asset_id),is_default INTEGER NOT NULL CHECK(is_default IN (0,1)),params_json TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),edit_schema_version INTEGER NOT NULL,render_version TEXT NOT NULL,support TEXT NOT NULL)",
            "CREATE UNIQUE INDEX one_default_variant ON variants(asset_id) WHERE is_default=1",
            "CREATE TABLE migrations(legacy_key TEXT PRIMARY KEY,payload_sha TEXT NOT NULL,state TEXT NOT NULL,variant_id TEXT REFERENCES variants(variant_id))",
            "CREATE TABLE migration_attempts(archive_id TEXT NOT NULL,legacy_key TEXT NOT NULL,payload_sha TEXT NOT NULL,state TEXT NOT NULL,variant_id TEXT REFERENCES variants(variant_id),backup_path TEXT NOT NULL,PRIMARY KEY(archive_id,legacy_key))",
            "CREATE TABLE sessions(session_id TEXT PRIMARY KEY,current_position INTEGER)",
            "CREATE TABLE session_items(session_id TEXT NOT NULL REFERENCES sessions(session_id),position INTEGER NOT NULL,legacy_key TEXT NOT NULL,variant_id TEXT REFERENCES variants(variant_id),PRIMARY KEY(session_id,position))",
        )
        private fun support(json: JSONObject): LibraryEditSupport {
            val known = EditorParams().toJson().keys().asSequence().toSet() +
                setOf("tone_curves", "color_adjustments", "channel_mixer", "color_wheels")
            if (json.keys().asSequence().any { it !in known }) return LibraryEditSupport.PRESERVED_READ_ONLY
            return try {
                val defaults = EditorParams().toJson()
                for (key in json.keys()) {
                    if (!defaults.has(key)) continue
                    val value = json.get(key)
                    check(when (defaults.get(key)) {
                        is Number -> value is Number && value.toDouble().isFinite()
                        is Boolean -> value is Boolean
                        is String -> value is String
                        else -> false
                    })
                }
                val parsed = EditorParams.fromJson(json)
                if (parsed.unreadableToneCurves != null || parsed.unreadableColorAdjustments != null ||
                    parsed.unreadableChannelMixer != null || parsed.unreadableColorWheels != null) LibraryEditSupport.PRESERVED_READ_ONLY
                else LibraryEditSupport.LEGACY
            } catch (_: Exception) { LibraryEditSupport.PRESERVED_READ_ONLY }
        }
        private fun values(vararg entries: Pair<String, Any?>) = ContentValues().apply { entries.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                else -> error("Unsupported database value")
            }
        } }
        private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
        private fun Cursor.optional(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
        private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
        private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
    }
}
