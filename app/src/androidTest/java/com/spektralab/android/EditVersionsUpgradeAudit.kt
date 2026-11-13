package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.net.Uri
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import java.io.File
import org.json.JSONObject

internal class EditVersionsUpgradeAudit(private val test: Instrumentation, private val output: File) {
    private fun isolated(root: File) = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    fun seed(): String {
        val root = File(output, "versions-cover-${System.nanoTime()}").apply { mkdirs() }
        val context = isolated(root)
        val uri = Uri.fromFile(File(root, "original.nef"))
        EditStore.save(context, uri, EditorParams().apply { brightness = 23f; cropX = .1f; cropW = .8f })
        val row = EditStore.savedPhotos(context).single()
        val album = EditStore.createPhotoAlbum(context, "收藏")
        EditStore.savePhotoOrganization(context, row.assetId, PhotoOrganization(), PhotoOrganization(4, PhotoLabel.BLUE, setOf(album.id)))
        EditStore.saveSession(context, SavedEditSession(listOf(uri.toString()), uri.toString(), emptySet()))
        File(output, "versions-cover.json").writeText(JSONObject().put("root", root.path).put("uri", uri.toString()).put("asset", row.assetId)
            .put("variant", row.variantId).put("revision", row.revision).put("album", album.id).toString())
        EditStore.closeForTests(context)
        return "Previous production v3 APK wrote real isolated default edit, stable IDs, album/rating/colour and workbench for cover upgrade"
    }
    fun verify(): String {
        val seed = JSONObject(File(output, "versions-cover.json").readText())
        val context = isolated(File(seed.getString("root")))
        val uri = Uri.parse(seed.getString("uri"))
        val row = EditStore.savedPhoto(context, seed.getString("asset"))!!
        check(row.variantId == seed.getString("variant") && row.revision == seed.getLong("revision"))
        check(EditStore.load(context, uri)!!.let { it.brightness == 23f && it.cropX == .1f && it.cropW == .8f })
        check(EditStore.photoOrganization(context, row.assetId).let { it.rating == 4 && it.label == PhotoLabel.BLUE && seed.getString("album") in it.albums })
        check(EditStore.savedSession(context)!!.current == uri.toString())
        val copy = EditStore.createEditVersion(context, EditStore.editVersions(context, row.assetId).first(), "升级副本")
        EditStore.save(context, copy.editUri, EditStore.load(context, copy.editUri)!!.copy().apply { brightness = 35f })
        EditStore.saveSession(context, SavedEditSession(listOf(uri.toString(), copy.editUri.toString()), copy.editUri.toString(), setOf(copy.editUri.toString())))
        EditStore.closeForTests(context)
        check(EditStore.load(context, uri)!!.brightness == 23f && EditStore.load(context, copy.editUri)!!.brightness == 35f)
        check(EditStore.savedSession(context)!!.current == copy.editUri.toString() && EditStore.savedPhoto(context, row.assetId)!!.revision == row.revision)
        return "Actual v3-to-v4 APK cover upgrade keeps default IDs/revision/params, albums/marks/session; independent virtual edits and same-source workbench survive close/reopen"
    }
}
