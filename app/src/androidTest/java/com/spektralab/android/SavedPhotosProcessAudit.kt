package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.os.Process
import com.spektralab.android.data.library.SavedPhotoRepository
import java.io.File

internal class SavedPhotosProcessAudit(private val test: Instrumentation,private val output:File) {
    fun terminate(afterCommit:Boolean):Nothing {
        val root=File(output,"saved-process-${if(afterCommit)"after" else "during"}")
        check(!root.exists())
        root.mkdirs()
        val phase=if(afterCommit)SavedPhotoRepository.Checkpoint.IMPORT_COMMITTED else SavedPhotoRepository.Checkpoint.IMPORT_WRITTEN
        SavedPhotoRepository.open(root).use {repo->
            repo.checkpoint={if(it==phase){
                File(root,"checkpoint.txt").outputStream().use{stream->stream.write(phase.name.toByteArray());stream.fd.sync()}
                Process.killProcess(Process.myPid());Thread.sleep(1000);error("Kill failed")
            }}
            repo.adopt(Uri.parse("content://audit/saved-source"),"media-79","{\"brightness\":23}","source.png")
        }
        error("Checkpoint missed")
    }
    fun verify():String {
        for(mode in listOf("during","after")) {
            val dir=File(output,"saved-process-$mode")
            val expected=if(mode=="after")"IMPORT_COMMITTED" else "IMPORT_WRITTEN"
            check(File(dir,"checkpoint.txt").readText()==expected)
            SavedPhotoRepository.open(dir).use{repo->
                check(repo.count()==if(mode=="after")1L else 0L)
                val uri=Uri.parse("content://audit/saved-source")
                repeat(2){repo.adopt(uri,"media-79","{\"brightness\":23}","source.png")}
                check(repo.count()==1L && repo.read(uri)=="{\"brightness\":23}")
                check(File(dir,"legacy-backups").listFiles()!!.single().readText()==repo.read(uri))
            }
        }
        return "Production SQLite schema: actual process death before/after adoption commit, rollback or resume with one saved photo and immutable backup, two replays stable"
    }
}
