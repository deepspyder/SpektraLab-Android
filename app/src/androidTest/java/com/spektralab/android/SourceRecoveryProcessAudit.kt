package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.os.Process
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.library.SourceProbe
import java.io.File

internal class SourceRecoveryProcessAudit(private val test: Instrumentation, private val root: File) {
    fun terminate(afterCommit: Boolean): Nothing {
        val directory = File(root, if (afterCommit) "after" else "during")
        check(!directory.exists()); check(directory.mkdirs())
        val original = File(directory, "original.raw").apply { writeBytes(ByteArray(1024) { (it % 251).toByte() }) }
        val moved = File(directory, "moved.raw").apply { writeBytes(original.readBytes()) }
        val oldUri = Uri.fromFile(original)
        val phase = if (afterCommit) SavedPhotoRepository.Checkpoint.SOURCE_RELINK_COMMITTED else SavedPhotoRepository.Checkpoint.SOURCE_RELINKED
        SavedPhotoRepository.open(File(directory, "database")).use { repo ->
            repo.save(oldUri, "{\"brightness\":18,\"future_extension\":true}", "original.raw")
            repo.checkSource(repo.list(60, 0).single(), SourceProbe.inspect(test.targetContext, oldUri))
            repo.saveSession(SavedEditSession(listOf(oldUri.toString(), "content://audit/offline"), oldUri.toString(), emptySet()))
            repo.checkpoint = { if (it == phase) {
                File(directory, "checkpoint.txt").outputStream().use { stream ->
                    stream.write(phase.name.toByteArray()); stream.fd.sync()
                }
                Process.killProcess(Process.myPid())
                Thread.sleep(1000)
                error("Process kill failed")
            } }
            repo.relink(repo.list(60, 0).single(), SourceProbe.inspect(test.targetContext, Uri.fromFile(moved)))
        }
        error("Relink checkpoint missed")
    }

    fun verify(): String {
        for (mode in listOf("during", "after")) {
            val directory = File(root, mode)
            val original = Uri.fromFile(File(directory, "original.raw"))
            val moved = Uri.fromFile(File(directory, "moved.raw"))
            val expected = if (mode == "after") moved else original
            val marker = if (mode == "after") "SOURCE_RELINK_COMMITTED" else "SOURCE_RELINKED"
            check(File(directory, "checkpoint.txt").readText() == marker)
            SavedPhotoRepository.open(File(directory, "database")).use { repo ->
                val row = repo.list(60, 0).single()
                check(row.uri == expected.toString() && row.revision == 1L && row.sourceSha256 != null)
                check(repo.session()!!.photos == listOf(expected.toString(), "content://audit/offline"))
                check(repo.session()!!.current == expected.toString() && repo.session()!!.selected.isEmpty())
                if (mode == "after") check(runCatching { repo.read(original) }.isFailure)
                val evidence = SourceProbe.inspect(test.targetContext, moved)
                repeat(2) { repo.relink(repo.find(row.assetId)!!, evidence) }
                check(repo.count() == 1L && repo.list(60, 0).single().assetId == row.assetId)
                check(repo.read(moved) == "{\"brightness\":18,\"future_extension\":true}")
                check(repo.session()!!.photos.first() == moved.toString())
            }
        }
        return "Real process death immediately before and after source/session transaction commit: complete rollback or complete relocation, stable IDs/params/revision, offline and empty-selection slots retained; two retries idempotent"
    }
}
