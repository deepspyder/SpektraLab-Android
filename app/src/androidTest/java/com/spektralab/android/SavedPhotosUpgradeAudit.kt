package com.spektralab.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import java.io.File
import java.security.MessageDigest

/** Seed runs with the F06 APK; verify runs after covering it with the F07 APK. */
internal class SavedPhotosUpgradeAudit(private val test: Instrumentation, private val output: File) {
    private val source get()=File(output,"saved-upgrade-source.png")
    private fun key(uri:Uri)="uri-"+MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        .joinToString(""){"%02x".format(it)}.take(16)
    fun seed():String {
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val uri=Uri.fromFile(source)
        EditStore.save(test.targetContext,uri,EditorParams().apply{brightness=23f;cropX=.1f;cropW=.8f})
        val legacy=File(test.targetContext.filesDir,"sidecars/${key(uri)}.json")
        check(legacy.isFile && legacy.readText().contains("brightness")) {"Seed requires pre-library F06 APK"}
        File(output,"saved-upgrade-legacy.txt").writeText(legacy.readText())
        return "F06 APK wrote real old-format sidecar with brightness23 and crop .1/.8; source and snapshot retained"
    }
    fun verify():String {
        val uri=Uri.fromFile(source)
        val raw=File(output,"saved-upgrade-legacy.txt").readText()
        val legacy=File(test.targetContext.filesDir,"sidecars/${key(uri)}.json")
        val params=EditStore.load(test.targetContext,uri)!!
        check(params.brightness==23f && params.cropX==.1f && params.cropW==.8f)
        val row=EditStore.savedPhotos(test.targetContext).first{it.uri==uri.toString()}
        EditStore.save(test.targetContext,uri,params.apply{brightness=31f})
        EditStore.closeForTests(test.targetContext)
        check(EditStore.load(test.targetContext,uri)!!.brightness==31f)
        check(EditStore.savedPhotos(test.targetContext).first{it.uri==uri.toString()}.assetId==row.assetId)
        check(legacy.readText()==raw)
        return "Actual F06-to-F07 cover install preserves source, brightness/crop, one migrated identity; updated save stays in DB after close/reopen, old sidecar byte-exact"
    }
}
