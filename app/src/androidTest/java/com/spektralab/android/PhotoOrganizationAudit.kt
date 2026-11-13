package com.spektralab.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.SavedPhotoRepository
import java.io.File
import java.io.IOException

internal object PhotoOrganizationAudit {
    fun run(context: Context, output: File): String {
        val root = File(output, "organization-${System.nanoTime()}").apply { mkdirs() }
        val source = Uri.parse("content://audit/original")
        val params = "{\"brightness\":18,\"crop_x\":0.1,\"future_extension\":{\"keep\":true}}"
        // Upgrade an actual v2 database, without depending on the new schema initializer.
        SQLiteDatabase.openOrCreateDatabase(File(root, "saved-photos.db"), null).use { db ->
            db.execSQL("CREATE TABLE assets(asset_id TEXT PRIMARY KEY,source_key TEXT UNIQUE NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE edits(variant_id TEXT PRIMARY KEY,asset_id TEXT UNIQUE NOT NULL REFERENCES assets(asset_id),params TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),updated_at INTEGER NOT NULL,render_version TEXT NOT NULL)")
            db.execSQL("CREATE INDEX edit_updated ON edits(updated_at DESC)")
            db.execSQL("CREATE TABLE legacy_claims(legacy_key TEXT PRIMARY KEY,source_key TEXT NOT NULL,backup TEXT NOT NULL,sha256 TEXT NOT NULL)")
            db.execSQL("CREATE TABLE source_checks(asset_id TEXT PRIMARY KEY REFERENCES assets(asset_id),sha256 TEXT NOT NULL,bytes INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE retired_sources(source_key TEXT PRIMARY KEY,asset_id TEXT NOT NULL REFERENCES assets(asset_id))")
            db.execSQL("CREATE TABLE workbench(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO assets VALUES('asset-v2',?,?, 'original.nef')", arrayOf(CanonicalJson.fingerprint("uri-v1:$source"), source.toString()))
            db.execSQL("INSERT INTO edits VALUES('variant-v2','asset-v2',?,7,123,'android-legacy-v1')", arrayOf(params))
            db.version = 2
        }
        var marked: PhotoOrganization
        var albumId: String
        var timings: String
        SavedPhotoRepository.open(root).use { repo ->
            val before = repo.list(60, 0).single()
            check(before.assetId == "asset-v2" && before.variantId == "variant-v2" && before.revision == 7L)
            check(before.rating == 0 && before.label == PhotoLabel.NONE && repo.organization.read(before.assetId) == PhotoOrganization())
            val album = repo.organization.createAlbum("  周末光影  ")
            albumId = album.id
            check(album.name == "周末光影")
            repo.organization.createAlbum("é")
            check(runCatching { repo.organization.createAlbum("e\u0301") }.isFailure)
            repo.organization.createAlbum("Travel")
            check(runCatching { repo.organization.createAlbum("TRAVEL") }.isFailure)
            for (name in listOf("", "   ", "a\nb", "x".repeat(41))) check(runCatching { repo.organization.createAlbum(name) }.isFailure)
            val marks = repo.organization.read(before.assetId)
            val update = marks.copy(rating = 4, label = PhotoLabel.BLUE, albums = setOf(album.id))
            repo.organization.beforeCommit = { throw IOException("transaction interruption") }
            check(runCatching { repo.organization.save(before.assetId, marks, update) }.isFailure)
            check(repo.organization.read(before.assetId) == marks && repo.organization.albums().first { it.id == album.id }.count == 0L)
            repo.organization.beforeCommit = {}
            repo.organization.save(before.assetId, marks, update)
            marked = repo.organization.read(before.assetId)
            check(marked == update.copy(revision = 1) && repo.organization.albums().first { it.id == album.id }.count == 1L)
            check(runCatching { repo.organization.save(before.assetId, marks, marks.copy(rating = 2)) }.isFailure)
            repo.organization.save(before.assetId, marked, marked)
            check(repo.organization.read(before.assetId).revision == 1L)
            check(repo.find(before.assetId)!!.let { it.revision == before.revision && it.updatedAt == before.updatedAt && it.rating == 4 && it.label == PhotoLabel.BLUE })
            check(repo.read(source) == params)
            val session = SavedEditSession(listOf(source.toString()), source.toString(), emptySet())
            repo.saveSession(session)
            check(repo.list(60, 0, PhotoLibraryFilter(album.id, 4, PhotoLabel.BLUE)).single().assetId == before.assetId)
            check(repo.list(60, 0, PhotoLibraryFilter(album.id, 5)).isEmpty())
            check(repo.matchingCount(PhotoLibraryFilter(label = PhotoLabel.NONE)) == 0L)
            check(repo.matchingCount(PhotoLibraryFilter(unratedOnly = true)) == 0L)
            val baseline = SourceEvidence(source.toString(), "unused", "audit", null, null, "original.nef", "application/octet-stream", 1234, 100, 80, 1, "a".repeat(64), SourceAvailability.AVAILABLE)
            repo.checkSource(repo.find(before.assetId)!!, baseline)
            repo.relink(repo.find(before.assetId)!!, baseline.copy(uri = "content://audit/moved"))
            check(repo.organization.read(before.assetId) == marked && repo.list(60, 0, PhotoLibraryFilter(album.id)).single().uri == "content://audit/moved")
            check(repo.session()!!.current == "content://audit/moved")
            val epoch = repo.find(before.assetId)!!
            repo.save(Uri.parse(epoch.uri), params.replace("18", "29"), "")
            check(repo.organization.read(before.assetId) == marked)
            check(repo.find(before.assetId)!!.revision == 8L && repo.find(before.assetId)!!.rating == 4)
            repo.organization.renameAlbum(album.id, "新相册名")
            check(repo.organization.albums().first { it.id == album.id }.name == "新相册名")
            repo.organization.beforeCommit = { throw IOException("delete rollback") }
            check(runCatching { repo.organization.deleteAlbum(album.id) }.isFailure)
            check(repo.organization.read(before.assetId) == marked)
            repo.organization.beforeCommit = {}

            // Independent full-catalog oracle for filtering and every page; no image decode.
            val expected = mutableMapOf<String, PhotoOrganization>()
            val fixtureStart = SystemClock.elapsedRealtime()
            repeat(600) { index ->
                val uri = Uri.parse("content://audit/bulk/$index")
                repo.save(uri, "{}", "$index.nef")
                val row = repo.findByUri(uri)!!
                check(row.uri == uri.toString())
                val value = PhotoOrganization(index % 6, PhotoLabel.entries[index % PhotoLabel.entries.size],
                    if (index % 2 == 0) setOf(album.id) else emptySet())
                if (value != PhotoOrganization()) repo.organization.save(row.assetId, PhotoOrganization(), value)
                expected[row.assetId] = repo.organization.read(row.assetId)
            }
            expected[before.assetId] = marked
            val filters = listOf(PhotoLibraryFilter(), PhotoLibraryFilter(album.id), PhotoLibraryFilter(minimumRating = 3),
                PhotoLibraryFilter(label = PhotoLabel.NONE), PhotoLibraryFilter(unratedOnly = true), PhotoLibraryFilter(album.id, 4, PhotoLabel.BLUE))
            val times = mutableListOf<Double>()
            for (filter in filters) {
                val matches = expected.filterValues { (filter.albumId == null || filter.albumId in it.albums) && it.rating >= filter.minimumRating &&
                    (filter.label == null || filter.label == it.label) && (!filter.unratedOnly || it.rating == 0) }.keys
                val start = System.nanoTime()
                val page = repo.page(filter, 0)
                times += (System.nanoTime() - start) / 1_000_000.0
                val all = buildList { var offset = 0; while (true) { val p = repo.list(60, offset, filter); addAll(p); if (p.size < 60) break; offset += 60 } }
                check(all.map { it.assetId }.toSet() == matches && all.size == matches.size && all.distinctBy { it.assetId }.size == all.size)
                check(page.matchingCount == matches.size.toLong() && page.totalCount == expected.size.toLong())
            }
            check(repo.page(PhotoLibraryFilter(albumId = "'; DROP TABLE assets;--"), 0).photos.isEmpty())
            check(repo.count() == 601L)
            timings = "600 metadata fixtures ${SystemClock.elapsedRealtime() - fixtureStart}ms; six first-page queries ${times}ms"
            repo.organization.deleteAlbum(albumId)
            check(repo.organization.read(before.assetId).let { it.rating == 4 && it.label == PhotoLabel.BLUE && it.albums.isEmpty() })
            check(repo.read(Uri.parse("content://audit/moved"))!!.contains("future_extension") && repo.session()!!.current == "content://audit/moved")
        }
        SavedPhotoRepository.open(root).use { repo ->
            check(repo.find("asset-v2")!!.let { it.variantId == "variant-v2" && it.revision == 8L && it.rating == 4 && it.label == PhotoLabel.BLUE })
            check(repo.organization.albums().none { it.id == albumId })
        }
        return "Real v2-to-v3 upgrade, names/Unicode/duplicate checks; atomic marks/memberships and deletion rollback, stale CAS/no-op; editing/source-relink/workbench unaffected, delete album retains marks/edits; 601-row independent filter oracle and pagination, bound SQL args, close/reopen. $timings"
    }
}
