package com.spektralab.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.local.EditStore
import java.io.File
import java.io.IOException
import org.json.JSONObject

internal object SavedPhotoStorageAudit {
    fun run(context: Context, output: File): String {
        val root = File(output, "saved-storage-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val a = Uri.parse("content://com.spektralab.android.test.migration-a/a/42")
        val b = Uri.parse("content://com.spektralab.android.test.migration-b/b/42")
        val oldKey = EditStore.migrationKey(isolated, a)
        val raw = "{\n\"brightness\":17,\"crop_x\":0.1,\"crop_w\":0.8,\"future_extension\":{\"keep\":true}}"
        val old = File(root, "sidecars/$oldKey.json").apply { parentFile!!.mkdirs(); writeText(raw) }
        check(EditStore.load(isolated, a)!!.brightness == 17f)
        check(old.readText() == raw)
        check(runCatching { EditStore.load(isolated, b) }.isFailure) { "Aliased media id inherited another source's edits" }
        check(old.readText() == raw)
        val first = EditStore.savedPhotos(isolated).single()
        val p = EditStore.load(isolated, a)!!.copy().apply { brightness = 28f }
        EditStore.save(isolated, a, p)
        check(EditStore.load(isolated, a)!!.brightness == 28f && old.readText() == raw)
        check(EditStore.load(isolated, a)!!.toJson().getJSONObject("future_extension").getBoolean("keep"))
        EditStore.closeForTests(isolated)
        check(EditStore.load(isolated, a)!!.brightness == 28f)
        check(EditStore.savedPhotos(isolated).single().assetId == first.assetId)
        old.writeText("{\"brightness\":-50}")
        check(EditStore.load(isolated, a)!!.brightness == 28f) { "Old backup overrode the database" }
        val plain = Uri.fromFile(File(root, "plain.png"))
        check(EditStore.load(isolated, plain) == null)
        EditStore.save(isolated, plain, EditorParams())
        val beforeA = EditStore.loadForTransfer(isolated, a)
        val beforeB = EditStore.loadForTransfer(isolated, plain)
        EditStore.save(isolated, plain, EditorParams().apply { contrast = 12f })
        check(runCatching { EditStore.saveBatch(isolated, listOf(
            EditStore.BatchEdit(a, beforeA.json, p.copy().apply { brightness = 40f }.toJson().toString()),
            EditStore.BatchEdit(plain, beforeB.json, EditorParams().toJson().toString()))) }.isFailure)
        check(EditStore.load(isolated, a)!!.brightness == 28f)
        val currentB = EditStore.loadForTransfer(isolated, plain)
        EditStore.saveBatch(isolated, listOf(EditStore.BatchEdit(a, beforeA.json, p.copy().apply { brightness = 40f }.toJson().toString()),
            EditStore.BatchEdit(plain, currentB.json, EditorParams().apply { brightness = 30f }.toJson().toString())))
        check(EditStore.load(isolated, a)!!.brightness == 40f && EditStore.load(isolated, plain)!!.brightness == 30f)
        check(EditStore.saveIfAbsent(isolated, plain, EditorParams()).brightness == 30f)
        check(File(root,"photo-library/legacy-backups").listFiles()!!.any { it.readText() == raw })

        val brokenUri = Uri.fromFile(File(root,"broken.png"))
        val broken = File(root,"sidecars/${EditStore.migrationKey(isolated,brokenUri)}.json").apply { writeText("{broken") }
        check(runCatching { EditStore.load(isolated,brokenUri) }.isFailure)
        EditStore.save(isolated,brokenUri,EditorParams())
        check(broken.readText()=="{broken" && EditStore.storageError.value != null)
        EditStore.dismissStorageError()
        val futureUri = Uri.fromFile(File(root,"future.png"))
        val future = "{\"schema_version\":99,\"brightness\":13}"
        File(root,"sidecars/${EditStore.migrationKey(isolated,futureUri)}.json").writeText(future)
        check(runCatching { EditStore.load(isolated,futureUri) }.isFailure)
        EditStore.save(isolated,futureUri,EditorParams())
        check(EditStore.loadForTransfer(isolated,futureUri).json==future)
        EditStore.dismissStorageError()
        EditStore.closeForTests(isolated)

        val typedUri=Uri.fromFile(File(root,"wrong-type.png"))
        val typedFile=File(root,"sidecars/${EditStore.migrationKey(isolated,typedUri)}.json").apply{writeText("{\"brightness\":\"invalid\"}")}
        check(runCatching{EditStore.load(isolated,typedUri)}.isFailure)
        EditStore.save(isolated,typedUri,EditorParams())
        check(typedFile.readText()=="{\"brightness\":\"invalid\"}" && EditStore.storageError.value!=null)
        EditStore.dismissStorageError()

        val pages=File(root,"pages")
        SavedPhotoRepository.open(pages).use{repo->
            repeat(133){repo.save(Uri.parse("content://audit/pages/$it"),"{}","$it.png")}
            val all=(0..2).flatMap{repo.list(60,it*60)}
            check(all.size==133 && all.map{it.assetId}.distinct().size==133 && repo.count()==133L)
            val first=all.first()
            repo.save(Uri.parse(first.uri),"{}",first.name)
            check(repo.list(60,0).first().revision==first.revision)
        }

        val corruptDir=File(root,"corrupt-db").apply{mkdirs()}
        val corruptFile=File(corruptDir,"saved-photos.db").apply{writeText("This is retained corrupt SQLite evidence.")}
        val corruptBefore=corruptFile.readBytes()
        check(runCatching{SavedPhotoRepository.open(corruptDir)}.isFailure)
        check(corruptFile.readBytes().contentEquals(corruptBefore))

        // Durable boundary failures on the same schema used by production.
        for (checkpoint in listOf(SavedPhotoRepository.Checkpoint.BACKUP_READY,SavedPhotoRepository.Checkpoint.IMPORT_WRITTEN,
            SavedPhotoRepository.Checkpoint.IMPORT_COMMITTED)) {
            val dir=File(root,checkpoint.name)
            SavedPhotoRepository.open(dir).use { repo ->
                repo.checkpoint={if(it==checkpoint)throw IOException("simulated interruption")}
                check(runCatching {repo.adopt(a,"media-42",raw,"source.png")}.isFailure)
                check(repo.count()==if(checkpoint==SavedPhotoRepository.Checkpoint.IMPORT_COMMITTED)1L else 0L)
            }
            SavedPhotoRepository.open(dir).use { repo ->
                repo.adopt(a,"media-42",raw,"source.png");repo.adopt(a,"media-42",raw,"source.png")
                check(repo.count()==1L && repo.read(a)==raw)
                repo.save(plain,"{\"brightness\":1}","second.png")
                repo.checkpoint={if(it==SavedPhotoRepository.Checkpoint.BATCH_WRITTEN)throw IOException("simulated batch stop")}
                check(runCatching {repo.saveBatch(listOf(SavedPhotoRepository.PendingWrite(a,raw,"{}"),
                    SavedPhotoRepository.PendingWrite(plain,"{\"brightness\":1}","{}")),emptyMap())}.isFailure)
                check(repo.read(a)==raw && repo.read(plain)=="{\"brightness\":1}")
            }
        }
        val futureDir=File(root,"future-db").apply {mkdirs()}
        SQLiteDatabase.openOrCreateDatabase(File(futureDir,"saved-photos.db"),null).use { it.version=99;it.execSQL("CREATE TABLE marker(value TEXT)") }
        check(runCatching{SavedPhotoRepository.open(futureDir)}.isFailure)
        SQLiteDatabase.openDatabase(File(futureDir,"saved-photos.db").path,null,SQLiteDatabase.OPEN_READONLY).use {
            check(it.version==99);it.rawQuery("SELECT COUNT(*) FROM marker",null).close()
        }
        return "Production facade: immutable legacy backup, one-authority save/reopen, same-media-id provider collision blocked, unknown fields preserved, corrupt/future records not overwritten; batch stale rejection/all-or-none, late smart suggestion; 3 migration failure boundaries and batch rollback; future DB refused"
    }
}
