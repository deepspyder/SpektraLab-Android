package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Runs unchanged against F06 and F07. Initialization/migration is outside steady save samples. */
internal object SavedStoreLatencyAudit {
    fun run(test: Instrumentation, output: File, virtual: Boolean = false): String {
        var uri=Uri.fromFile(File(output,"saved-latency-${System.nanoTime()}.png"))
        val params=EditorParams()
        test.runOnMainSync { EditStore.save(test.targetContext,uri,params) }
        if (virtual) {
            val photo = EditStore.savedPhotos(test.targetContext).first { it.uri == uri.toString() }
            uri = EditStore.createEditVersion(test.targetContext, EditStore.editVersions(test.targetContext, photo.assetId).first(), "保存计时").editUri
        }
        val ms=mutableListOf<Double>()
        repeat(120){index->
            test.runOnMainSync {
                params.contrast=(index%60).toFloat()
                val start=SystemClock.elapsedRealtimeNanos()
                EditStore.save(test.targetContext,uri,params)
                ms+=(SystemClock.elapsedRealtimeNanos()-start)/1e6
            }
        }
        check(EditStore.load(test.targetContext,uri)!!.contrast==59f)
        return JSONObject().put("samples",JSONArray(ms)).put("median_ms",ms.sorted()[60])
            .put("p95_ms",ms.sorted()[113]).put("saved_contrast",59).toString()
    }
}
