package com.spektralab.android.data.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** Encode to an unpublished destination, commit once, and remove incomplete files on failure. */
internal class ExportPublisher(
    private val context: Context,
    private val checkCancelled: () -> Unit,
    private val onCommitted: () -> Unit,
) {
    fun write(name: String, mime: String, encode: (OutputStream) -> Unit): String {
        checkCancelled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Theia")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.insert(collection, values)?.let { uri ->
                try {
                    val stream = resolver.openOutputStream(uri) ?: error("无法写入相册文件")
                    stream.use(encode)
                    checkCancelled()
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) > 0) { "无法完成相册文件" }
                    onCommitted()
                    return "相册: $uri"
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            }
        }

        val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val destination = File(directory, name)
        val pending = File(directory, "$name.pending")
        try {
            pending.outputStream().use(encode)
            checkCancelled()
            check(pending.renameTo(destination)) { "无法完成相册文件" }
            onCommitted()
            return destination.absolutePath
        } finally {
            pending.delete()
        }
    }
}
