package com.spektralab.android

import android.net.Uri
import android.os.Process
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.SavedPhotoRepository
import java.io.File

internal class PhotoOrganizationProcessAudit(private val directory: File) {
    fun terminate(afterCommit: Boolean): Nothing {
        check(!directory.exists()); check(directory.mkdirs())
        SavedPhotoRepository.open(directory).use { repo ->
            val uri = Uri.parse("content://audit/organization-process")
            repo.save(uri, "{\"brightness\":18}", "sample.nef")
            val photo = repo.list(60, 0).single()
            val album = repo.organization.createAlbum("进程恢复")
            repo.saveSession(SavedEditSession(listOf(uri.toString()), uri.toString(), emptySet()))
            fun kill(phase: String): Nothing {
                File(directory, "checkpoint.txt").outputStream().use { it.write(phase.toByteArray()); it.fd.sync() }
                Process.killProcess(Process.myPid()); Thread.sleep(1000); error("Process kill missed")
            }
            if (!afterCommit) repo.organization.beforeCommit = { kill("BEFORE_COMMIT") }
            repo.organization.save(photo.assetId, PhotoOrganization(), PhotoOrganization(4, PhotoLabel.BLUE, setOf(album.id)))
            kill("AFTER_COMMIT")
        }
    }
    fun verify(): String {
        for (mode in listOf("during", "after")) {
            val dir = File(directory, mode)
            check(File(dir, "checkpoint.txt").readText() == if (mode == "after") "AFTER_COMMIT" else "BEFORE_COMMIT")
            SavedPhotoRepository.open(dir).use { repo ->
                val photo = repo.list(60, 0).single()
                val album = repo.organization.albums().single()
                val expected = if (mode == "after") PhotoOrganization(4, PhotoLabel.BLUE, setOf(album.id), 1) else PhotoOrganization()
                check(repo.organization.read(photo.assetId) == expected)
                check(album.count == if (mode == "after") 1L else 0L)
                check(repo.read(Uri.parse(photo.uri)) == "{\"brightness\":18}" && photo.revision == 1L)
                check(repo.session()!!.selected.isEmpty() && repo.session()!!.current == photo.uri)
            }
        }
        return "Real process termination before and after marks/membership commit: all rollback or all durable, unchanged edits/revision/source/workbench, no partial album membership"
    }
}
