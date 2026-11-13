package com.spektralab.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.data.library.SourceProbe
import com.spektralab.android.data.local.EditStore
import java.io.File
import org.json.JSONObject

internal class SourceRecoveryUpgradeAudit(private val test: Instrumentation, private val output: File) {
    fun seed(): String {
        val source = File(output, "source-cover-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff965b38.toInt())
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = Uri.fromFile(source)
        EditStore.save(test.targetContext, uri, EditorParams().apply { brightness = 11f; cropX = .2f; cropW = .7f })
        val row = EditStore.savedPhotos(test.targetContext).first { it.uri == uri.toString() }
        File(output, "source-cover.json").writeText(JSONObject().put("uri", uri.toString()).put("asset", row.assetId)
            .put("variant", row.variantId).put("revision", row.revision).toString())
        EditStore.closeForTests(test.targetContext)
        return "Old F07 APK wrote v1 production DB; stable asset/variant/revision and brightness/crop recorded for cover install"
    }

    fun verify(): String {
        val context = test.targetContext
        val seed = JSONObject(File(output, "source-cover.json").readText())
        val uri = Uri.parse(seed.getString("uri"))
        val params = EditStore.load(context, uri)!!
        check(params.brightness == 11f && params.cropX == .2f && params.cropW == .7f)
        val row = EditStore.savedPhoto(context, seed.getString("asset"))!!
        check(row.variantId == seed.getString("variant") && row.revision == seed.getLong("revision"))
        EditStore.checkSavedSource(context, row, SourceProbe.inspect(context, uri))
        EditStore.saveSession(context, SavedEditSession(listOf(uri.toString()), uri.toString(), emptySet()))
        EditStore.closeForTests(context)
        check(EditStore.savedPhoto(context, row.assetId)!!.sourceSha256 != null)
        check(EditStore.savedSession(context)!!.selected.isEmpty())
        check(EditStore.load(context, uri)!!.brightness == 11f)
        return "Actual F07 v1-to-v2 cover install kept stable IDs/revision and brightness/crop; new source baseline and workbench survive DB close/reopen"
    }
}
