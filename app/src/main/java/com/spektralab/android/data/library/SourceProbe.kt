package com.spektralab.android.data.library

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.core.model.CanonicalJson
import com.spektralab.android.core.model.SourceAvailability
import com.spektralab.android.core.model.SourceEvidence
import java.io.FileNotFoundException
import java.net.URLConnection
import java.security.MessageDigest
import org.json.JSONObject

/** Explicit prototype inspection, never called from import/preview. Streams source bytes in 64KiB. */
internal object SourceProbe {
    fun inspect(context: Context, uri: Uri, checkpoint: () -> Unit = {}): SourceEvidence {
        require(uri.scheme in setOf("content", "file"))
        val authority = uri.authority.orEmpty()
        val document = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null
        }.getOrNull()
        val identity = JSONObject().put("version", 1).put("authority", authority).apply {
            if (document != null) put("document", document) else put("uri", uri.toString())
        }
        val key = CanonicalJson.fingerprint(CanonicalJson.encode(identity))
        var name = uri.lastPathSegment.orEmpty()
        var mediaId: Long? = null
        var declaredSize: Long? = null
        var width = 0; var height = 0; var orientation = 1
        var mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, MediaStore.MediaColumns._ID),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fun at(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }
                    at(OpenableColumns.DISPLAY_NAME)?.let { name = cursor.getString(it) }
                    at(OpenableColumns.SIZE)?.let { declaredSize = cursor.getLong(it).takeIf { size -> size >= 0 } }
                    at(MediaStore.MediaColumns._ID)?.let { mediaId = cursor.getLong(it).takeIf { id -> id > 0 } }
                }
            }
        } catch (_: Exception) { /* Opening the source, not a provider query, proves readability. */ }
        mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: URLConnection.guessContentTypeFromName(name) ?: mime
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val status = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkpoint()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    bytes += count
                }
            } ?: throw FileNotFoundException()
            if (bytes == 0L || declaredSize?.let { it != bytes } == true) SourceAvailability.UNAVAILABLE
            else SourceAvailability.AVAILABLE
        } catch (denied: SecurityException) { SourceAvailability.PERMISSION_DENIED
        } catch (_: FileNotFoundException) { SourceAvailability.MISSING
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: java.io.IOException) { SourceAvailability.UNAVAILABLE }
        if (status == SourceAvailability.AVAILABLE) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    width = bounds.outWidth.coerceAtLeast(0); height = bounds.outHeight.coerceAtLeast(0)
                }
            }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    if (width == 0) width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).coerceAtLeast(0)
                    if (height == 0) height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).coerceAtLeast(0)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).takeIf { it in 1..8 } ?: 1
                }
            }
        }
        return SourceEvidence(uri.toString(), key, authority, document, mediaId, name, mime, bytes, width, height,
            orientation, if (status == SourceAvailability.AVAILABLE) digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } else null, status)
    }
}
