package com.spektralab.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.EditVersionRepository
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.local.EditStore
import java.io.File
import java.io.IOException
import org.json.JSONObject

internal object EditVersionStorageAudit {
    fun run(context: Context, output: File): String {
        val root = File(output, "versions-storage-${System.nanoTime()}").apply { mkdirs() }
        val uri = Uri.parse("content://audit/source.nef")
        val raw = "{\"brightness\":18,\"crop_x\":0.1,\"future_extension\":{\"keep\":true}}"
        var asset = ""
        SavedPhotoRepository.open(root).use { repo ->
            repo.save(uri, raw, "source.nef"); asset = repo.findByUri(uri)!!.assetId
            repo.organization.save(asset, PhotoOrganization(), PhotoOrganization(4, PhotoLabel.BLUE))
            repo.saveSession(SavedEditSession(listOf(uri.toString()), uri.toString(), emptySet()))
        }
        // Remove both later additions to recreate the exact preceding v3 schema.
        SQLiteDatabase.openDatabase(File(root, "saved-photos.db").path, null, 0).use { db ->
            db.execSQL("DROP TABLE hidden_photos"); db.execSQL("DROP TABLE virtual_edits"); db.version = 3
        }
        var copyId = ""
        SavedPhotoRepository.open(root).use { repo ->
            val before = repo.find(asset)!!
            val default = repo.versions.list(asset).single()
            check(default.isDefault && default.id == before.variantId && default.paramsJson == raw)
            repo.versions.checkpoint = { if (it == EditVersionRepository.Checkpoint.CREATED) throw IOException("before commit") }
            check(runCatching { repo.versions.create(default, "回滚") }.isFailure)
            check(repo.versions.list(asset).size == 1)
            repo.versions.checkpoint = {}
            val copy = repo.versions.create(default, " 暖调 ")
            copyId = copy.id
            check(copy.name == "暖调" && copy.paramsJson == raw && copy.sourceUri == default.sourceUri && copy.assetId == asset && !copy.isDefault)
            check(repo.count() == 1L && repo.find(asset) == before)
            check(repo.organization.read(asset).rating == 4)
            check(runCatching { repo.versions.create(default, "暖调") }.isFailure)
            check(runCatching { repo.versions.create(default, "\n") }.isFailure)
            val updated = JSONObject(raw).put("brightness", 29).toString()
            repo.save(copy.editUri, updated, "")
            check(repo.read(uri) == raw && repo.read(copy.editUri) == updated)
            check(runCatching { repo.versions.rename(copy, "过期") }.isFailure)
            val current = repo.versions.find(copy.id)
            repo.versions.rename(current, "金色时刻")
            check(repo.versions.find(copy.id).editUri == copy.editUri)
            val clone = repo.versions.create(repo.versions.find(copy.id), "另一版")
            check(clone.paramsJson == updated)
            val originalDefault = repo.read(uri)
            val originalCopy = repo.read(copy.editUri)
            repo.save(copy.editUri, JSONObject(updated).put("brightness", 30).toString(), "")
            check(runCatching { repo.saveBatch(listOf(SavedPhotoRepository.PendingWrite(uri, originalDefault, "{}"),
                SavedPhotoRepository.PendingWrite(copy.editUri, originalCopy, "{}")), emptyMap()) }.isFailure)
            check(repo.read(uri) == raw)
            repo.saveBatch(listOf(SavedPhotoRepository.PendingWrite(uri, raw, "{\"brightness\":6}"),
                SavedPhotoRepository.PendingWrite(copy.editUri, repo.read(copy.editUri), "{\"brightness\":7}")), emptyMap())
            check(repo.read(uri) == "{\"brightness\":6}" && repo.read(copy.editUri) == "{\"brightness\":7}" && repo.read(clone.editUri) == updated)
            val session = SavedEditSession(listOf(uri.toString(), copy.editUri.toString(), clone.editUri.toString()),
                copy.editUri.toString(), setOf(copy.editUri.toString(), clone.editUri.toString()))
            repo.saveSession(session)
            check(repo.session() == session)
            val baseline = SourceEvidence(uri.toString(), "x", "audit", null, null, "source.nef", "application/octet-stream", 123,
                100, 80, 1, "a".repeat(64), SourceAvailability.AVAILABLE)
            repo.checkSource(repo.find(asset)!!, baseline)
            repo.relink(repo.find(asset)!!, baseline.copy(uri = "content://audit/moved.nef"))
            check(repo.versions.find(copy.id).sourceUri == "content://audit/moved.nef" && repo.versions.find(copy.id).editUri == copy.editUri)
            check(repo.session()!!.photos == listOf("content://audit/moved.nef", copy.editUri.toString(), clone.editUri.toString()))
            check(runCatching { repo.deleteVersion(repo.versions.list(asset).first()) }.isFailure)
            val deleting = repo.versions.find(copy.id)
            repo.versions.checkpoint = { if (it == EditVersionRepository.Checkpoint.DELETED) throw IOException("delete rollback") }
            val beforeSession = repo.session()
            check(runCatching { repo.deleteVersion(deleting) }.isFailure)
            check(repo.versions.find(copy.id) == deleting && repo.session() == beforeSession)
            repo.versions.checkpoint = {}
            repo.deleteVersion(deleting)
            check(repo.versions.list(asset).size == 2 && runCatching { repo.read(copy.editUri) }.isFailure)
            check(repo.session()!!.photos == listOf("content://audit/moved.nef", clone.editUri.toString()))
            repo.saveSession(session) // Late old snapshot cannot resurrect the deleted slot.
            check(copy.editUri.toString() !in repo.session()!!.photos)
            check(runCatching { repo.save(copy.editUri, "{}", "") }.isFailure)
            check(repo.read(clone.editUri) == updated && repo.organization.read(asset).rating == 4)
            // Unknown pipeline data is preserved and cannot become a writable compatible version.
            repo.save(clone.editUri, "{\"schema_version\":99,\"opaque\":true}", "")
            check(runCatching { repo.versions.create(repo.versions.find(clone.id), "future") }.isFailure)
        }
        SavedPhotoRepository.open(root).use { repo ->
            check(repo.count() == 1L && repo.versions.list(asset).size == 2)
            check(runCatching { repo.versions.find(copyId) }.isFailure)
        }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = File(root, "facade").apply { mkdirs() } }
        EditStore.save(isolated, uri, EditorParams().apply { brightness = 18f })
        val row = EditStore.savedPhotos(isolated).single()
        val version = EditStore.createEditVersion(isolated, EditStore.editVersions(isolated, row.assetId).first(), "测试")
        check(EditStore.load(isolated, version.editUri)!!.brightness == 18f)
        EditStore.save(isolated, version.editUri, EditorParams().apply { brightness = 35f })
        check(EditStore.load(isolated, uri)!!.brightness == 18f && EditStore.saveIfAbsent(isolated, version.editUri, EditorParams()).brightness == 35f)
        check(EditStore.editSourceUri(isolated, version.editUri) == uri)
        EditStore.closeForTests(isolated)
        for (invalid in listOf(version.editUri.buildUpon().appendQueryParameter("anything", "1").build(),
            Uri.parse("content://${EditVersionUri.AUTHORITY}/copies/invalid"), Uri.parse("file://${EditVersionUri.AUTHORITY}/copies/${version.id}"))) {
            check(runCatching { EditStore.load(isolated, invalid) }.isFailure)
        }
        return "Real v3-to-v4 upgrade preserves default IDs/edits/organization/session; copy/clone/name/stale-CAS/rollback; independent default+copy atomic batch, source relink keeps stable version URI, delete tombstone and session rollback/pruning/late-write protection; future payload preserved/read-only, facade load/save/smart-ifAbsent and malformed-reference rejection"
    }
}
