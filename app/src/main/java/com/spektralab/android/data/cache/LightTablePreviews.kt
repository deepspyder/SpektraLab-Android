package com.spektralab.android.data.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Disposable 640px display previews. Never used as inputs for photo or project export. */
internal class LightTablePreviews(private val directory: File) {
    fun put(bitmap: Bitmap): String {
        check(!bitmap.isRecycled)
        val edge = max(bitmap.width, bitmap.height)
        val small = if (edge > 640) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * 640f / edge).roundToInt().coerceAtLeast(1),
            (bitmap.height * 640f / edge).roundToInt().coerceAtLeast(1), true) else bitmap
        val bytes = try { ByteArrayOutputStream().use { stream ->
            check(small.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
        } } finally { if (small !== bitmap) small.recycle() }
        require(bytes.size <= 2 * 1024 * 1024) { "TABLE_CAPACITY" }
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(directory.isDirectory || directory.mkdirs())
        val file = file(key)
        if (file.isFile) return key
        val entries = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.png")) }.sortedBy(File::lastModified)
        var total = entries.sumOf(File::length)
        for (entry in entries) {
            if (total + bytes.size <= 128L * 1024 * 1024) break
            val length = entry.length()
            if (entry.delete()) total -= length
        }
        check(total + bytes.size <= 128L * 1024 * 1024) { "TABLE_PREVIEW_CAPACITY" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
        return key
    }

    fun read(key: String, maxEdge: Int = 192): Bitmap? {
        require(maxEdge in 1..640)
        val file = file(key)
        if (!file.isFile || file.length() > 2L * 1024 * 1024) return null
        file.setLastModified(System.currentTimeMillis())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..640 || bounds.outHeight !in 1..640) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.png")
    }
}
