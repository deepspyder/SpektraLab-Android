package com.spektralab.android.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.spektralab.android.core.model.EditVersion
import com.spektralab.android.core.model.EditVersionUri
import com.spektralab.android.data.local.EditStore
import java.io.FileNotFoundException
import java.net.URLConnection

/** App-private, read-only source routing. Returns the original descriptor, never copies image bytes. */
class EditVersionProvider : ContentProvider() {
    override fun onCreate() = true
    private fun version(uri: Uri): EditVersion = EditStore.editVersion(requireNotNull(context), EditVersionUri.id(uri))
    private fun source(uri: Uri) = Uri.parse(version(uri).sourceUri).also {
        check(it.scheme in setOf("content", "file") && !EditVersionUri.owns(it)) { "INVALID_SOURCE_REFERENCE" }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("Read only")
        return requireNotNull(context).contentResolver.openFileDescriptor(source(uri), "r")
    }
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("Read only")
        return requireNotNull(context).contentResolver.openAssetFileDescriptor(source(uri), "r")
    }
    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("Read only")
        signal?.throwIfCanceled()
        return requireNotNull(context).contentResolver.openAssetFileDescriptor(source(uri), "r", signal)
    }
    override fun getType(uri: Uri): String? {
        val v = version(uri)
        return requireNotNull(context).contentResolver.getType(Uri.parse(v.sourceUri))
            ?: URLConnection.guessContentTypeFromName(v.sourceName) ?: "application/octet-stream"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sortOrder: String?): Cursor {
        val v = version(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        var bytes: Long? = null
        if (OpenableColumns.SIZE in columns) runCatching {
            requireNotNull(context).contentResolver.query(Uri.parse(v.sourceUri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) bytes = it.getLong(0)
            }
        }
        val extension = v.sourceName.substringAfterLast('.', "")
        val name = if (extension.isEmpty()) "${v.sourceName} · ${v.name}" else
            "${v.sourceName.substringBeforeLast('.')} · ${v.name}.$extension"
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> name
            OpenableColumns.SIZE -> bytes
            else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException("Read only")
}
