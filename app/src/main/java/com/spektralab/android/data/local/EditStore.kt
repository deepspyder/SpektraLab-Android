package com.spektralab.android.data.local

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.spektralab.android.core.model.EditorParams
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 编辑参数持久化（sidecar）。每个照片一个 JSON，存在应用私有目录 filesDir/sidecars/。
 *
 * key 优先用 MediaStore 的 `_ID`（照片在媒体库里的稳定行号，重选同一张照片不变），
 * 解析不到时退化为 URI 字符串的 SHA-256 前 16 位。这样重选照片能恢复上次编辑。
 */
object EditStore {
    private const val DIR = "sidecars"

    fun load(context: Context, uri: Uri): EditorParams? {
        val file = File(context.filesDir, "$DIR/${stableKey(context, uri)}.json")
        if (!file.exists()) return null
        return try {
            EditorParams.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, uri: Uri, params: EditorParams) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${stableKey(context, uri)}.json")
        try {
            file.writeText(params.toJson().toString())
        } catch (e: Exception) {
            // 写失败（存储满等）不阻塞编辑，静默忽略。
        }
    }

    private fun stableKey(context: Context, uri: Uri): String {
        val id = resolveMediaId(context, uri)
        return if (id != null && id > 0) "media-$id" else "uri-" + hash16(uri.toString())
    }

    private fun resolveMediaId(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst() && c.columnCount > 0) c.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun hash16(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
