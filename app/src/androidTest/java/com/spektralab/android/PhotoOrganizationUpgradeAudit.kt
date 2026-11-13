package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.net.Uri
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.PhotoLabel
import com.spektralab.android.data.local.EditStore
import java.io.File
import org.json.JSONObject

/** Seed is deliberately limited to methods present in the preceding v2 production APK. */
internal class PhotoOrganizationUpgradeAudit(private val test: Instrumentation, private val output: File) {
    private fun isolated(root: File) = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    fun seed(): String {
        val root = File(output, "organization-cover-${System.nanoTime()}").apply { mkdirs() }
        val context = isolated(root)
        val uri = Uri.fromFile(File(output, "organization-upgrade-${System.nanoTime()}.nef"))
        EditStore.save(context, uri, EditorParams().apply { brightness = 23f; cropX = .1f; cropW = .8f })
        val row = EditStore.savedPhotos(context).first { it.uri == uri.toString() }
        File(output, "organization-upgrade.json").writeText(JSONObject().put("root", root.path).put("uri", uri.toString()).put("asset", row.assetId)
            .put("variant", row.variantId).put("revision", row.revision).toString())
        EditStore.closeForTests(context)
        return "Previous production v2 APK wrote stable asset/variant/revision and brightness/crop for actual cover upgrade"
    }
    fun verify(): String {
        val json = JSONObject(File(output, "organization-upgrade.json").readText())
        val context = isolated(File(json.getString("root")))
        val uri = Uri.parse(json.getString("uri"))
        val row = EditStore.savedPhoto(context, json.getString("asset"))!!
        check(row.variantId == json.getString("variant") && row.revision == json.getLong("revision"))
        check(row.rating == 0 && row.label == PhotoLabel.NONE)
        check(EditStore.load(context, uri)!!.let { it.brightness == 23f && it.cropX == .1f && it.cropW == .8f })
        val album = EditStore.createPhotoAlbum(context, "升级验证 ${System.nanoTime()}")
        val before = EditStore.photoOrganization(context, row.assetId)
        EditStore.savePhotoOrganization(context, row.assetId, before, before.copy(rating = 5, label = PhotoLabel.GREEN, albums = setOf(album.id)))
        EditStore.closeForTests(context)
        check(EditStore.photoOrganization(context, row.assetId).let { it.rating == 5 && it.label == PhotoLabel.GREEN && album.id in it.albums })
        check(EditStore.savedPhoto(context, row.assetId)!!.revision == row.revision && EditStore.load(context, uri)!!.brightness == 23f)
        EditStore.deletePhotoAlbum(context, album.id)
        return "Actual v2-to-v3 APK cover upgrade preserves IDs/edit revision/brightness/crop; new rating/label/membership survive close/reopen without changing edits"
    }
}
