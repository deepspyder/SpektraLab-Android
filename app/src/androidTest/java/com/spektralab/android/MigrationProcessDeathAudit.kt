package com.spektralab.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import com.spektralab.android.core.model.LegacySourceBinding
import com.spektralab.android.core.model.MigrationState
import com.spektralab.android.data.library.LegacyMigrationArchive
import com.spektralab.android.data.library.LibraryPrototypeRepository
import com.spektralab.android.data.library.SourceProbe
import java.io.File

/** Explicit test-only process termination. The killed process has no production migration entry. */
internal class MigrationProcessDeathAudit(private val test: Instrumentation, private val output: File) {
    private val key = "uri-7777777777777777"

    fun terminate(afterCommit: Boolean): Nothing {
        val root = File(output, "process-migration-${if (afterCommit) "after" else "during"}")
        check(!root.exists()) { "Use fresh evidence directories for destructive process audit" }
        val legacy = File(root, "legacy").apply { mkdirs() }
        File(legacy, "$key.json").writeText("{\"brightness\":23,\"crop_w\":0.8}")
        val image = File(root, "source.png")
        Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { bitmap ->
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val source = SourceProbe.inspect(test.targetContext, Uri.fromFile(image))
        val archive = LegacyMigrationArchive.capture(legacy, File(root, "archives"), listOf(LegacySourceBinding(key, source)), listOf(key), 0)
        File(root, "archive-id.txt").writeText(archive.id)
        val checkpoint = if (afterCommit) LibraryPrototypeRepository.Checkpoint.RECORD_COMMITTED else LibraryPrototypeRepository.Checkpoint.RECORD_WRITTEN
        LibraryPrototypeRepository.openSandbox(File(root, "db")).use { repo ->
            repo.migrate(archive) { at, _ ->
                if (at == checkpoint) {
                    File(root, "checkpoint.txt").outputStream().use { stream ->
                        stream.write(at.name.toByteArray()); stream.fd.sync()
                    }
                    Process.killProcess(Process.myPid())
                    Thread.sleep(1000)
                    error("Process termination failed")
                }
            }
        }
        error("Process death checkpoint not reached")
    }

    fun verify(): String {
        for (mode in listOf("during", "after")) {
            val root = File(output, "process-migration-$mode")
            val archive = LegacyMigrationArchive.open(File(root, "archives/${File(root, "archive-id.txt").readText()}"))
            val expectedCheckpoint = if (mode == "during") "RECORD_WRITTEN" else "RECORD_COMMITTED"
            check(File(root, "checkpoint.txt").readText() == expectedCheckpoint)
            val before = LegacyMigrationArchive.sha256(File(root, "legacy/$key.json"))
            LibraryPrototypeRepository.openSandbox(File(root, "db")).use { repo ->
                check(repo.counts().assets == if (mode == "during") 0L else 1L)
                repeat(2) { check(repo.migrate(archive).single().state == MigrationState.MIGRATED) }
                check(repo.counts() == LibraryPrototypeRepository.Counts(1,1,1,1,1))
                check(repo.session(archive.id)!!.slots.single().second == repo.migratedVariant(key)!!.id)
            }
            check(LegacyMigrationArchive.sha256(File(root, "legacy/$key.json")) == before)
        }
        return "Actual target-process death inside uncommitted transaction rolls back; after committed record resumes without duplicate asset/variant/session; source, legacy copy and archive remain readable"
    }
}
