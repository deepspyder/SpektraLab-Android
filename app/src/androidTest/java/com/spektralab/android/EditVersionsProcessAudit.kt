package com.spektralab.android

import android.net.Uri
import android.os.Process
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.*
import java.io.File

internal class EditVersionsProcessAudit(private val root: File) {
    fun terminate(mode: String): Nothing {
        val dir = File(root, mode)
        check(!dir.exists()); check(dir.mkdirs())
        val after = mode.endsWith("after")
        val deletion = mode.startsWith("delete")
        fun kill(phase: String): Nothing {
            File(dir, "checkpoint.txt").outputStream().use { it.write(phase.toByteArray()); it.fd.sync() }
            Process.killProcess(Process.myPid()); Thread.sleep(1000); error("Kill missed")
        }
        SavedPhotoRepository.open(dir).use { repo ->
            val source = Uri.parse("content://audit/original.nef")
            repo.save(source, "{\"brightness\":18}", "original.nef")
            val default = repo.versions.list(repo.list(60, 0).single().assetId).first()
            if (deletion) {
                val copy = repo.versions.create(default, "副本")
                repo.saveSession(SavedEditSession(listOf(source.toString(), copy.editUri.toString()), copy.editUri.toString(), setOf(copy.editUri.toString())))
                repo.versions.checkpoint = { if (it == if (after) EditVersionRepository.Checkpoint.COMMITTED else EditVersionRepository.Checkpoint.DELETED) kill(it.name) }
                repo.deleteVersion(copy)
            } else {
                repo.versions.checkpoint = { if (it == if (after) EditVersionRepository.Checkpoint.COMMITTED else EditVersionRepository.Checkpoint.CREATED) kill(it.name) }
                repo.versions.create(default, "副本")
            }
        }
        error("Version process checkpoint missed")
    }
    fun verify(): String {
        for (mode in listOf("create-during", "create-after", "delete-during", "delete-after")) {
            val dir = File(root, mode)
            check(File(dir, "checkpoint.txt").isFile)
            SavedPhotoRepository.open(dir).use { repo ->
                val row = repo.list(60, 0).single()
                val count = if (mode == "create-during" || mode == "delete-after") 1 else 2
                val versions = repo.versions.list(row.assetId)
                check(versions.size == count && repo.count() == 1L && row.revision == 1L)
                check(repo.read(Uri.parse(row.uri)) == "{\"brightness\":18}")
                if (mode.startsWith("delete")) {
                    val session = repo.session()!!
                    check(session.photos.size == count)
                    if (mode == "delete-after") check(session.selected.isEmpty() && session.current == row.uri)
                    else check(versions.last().editUri.toString() == session.current)
                }
            }
        }
        return "Four real process deaths before/after virtual-create and delete+session commit: whole rollback or whole durable result, no extra assets/versions, default payload/ID/revision preserved, deleted selected slot pruned atomically"
    }
}
