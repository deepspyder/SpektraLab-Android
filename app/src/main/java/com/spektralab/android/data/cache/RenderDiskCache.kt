package com.spektralab.android.data.cache

import com.spektralab.android.engine.Rgba16Source
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Lossless, random-access RGBA16 storage. This is a storage contract, not yet a production
 * cache-admission policy: callers must supply a fully versioned key and use one cache owner per
 * directory. Leased entries cannot be replaced or evicted. Scratch files are never readers.
 */
internal class RenderDiskCache(private val directory: File, private val maxBytes: Long) {
    private val leases = mutableMapOf<String, Int>()

    init {
        require(maxBytes >= HEADER_BYTES)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create render cache" }
        directory.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach(File::delete)
        trim(0)
    }

    /** A failed/cancelled writer leaves the previous complete entry intact. */
    fun put(key: String, source: Rgba16Source, checkCancelled: () -> Unit = {}): Boolean {
        validateKey(key)
        val bytes = payloadBytes(source.width, source.height)
        if (bytes > maxBytes - HEADER_BYTES) return false
        val pending = File.createTempFile("render-", ".partial", directory)
        try {
            val crc = CRC32()
            RandomAccessFile(pending, "rw").use { output ->
                output.seek(HEADER_BYTES)
                val rows = stripRows(source.width, source.height)
                val pixels = ShortArray(source.width * rows * 4)
                val encoded = ByteArray(pixels.size * 2)
                var y = 0
                while (y < source.height) {
                    checkCancelled()
                    val count = minOf(rows, source.height - y)
                    source.readRows(y, count, pixels)
                    val samples = source.width * count * 4
                    for (i in 0 until samples) {
                        val sample = pixels[i].toInt()
                        encoded[i * 2] = sample.toByte()
                        encoded[i * 2 + 1] = (sample ushr 8).toByte()
                    }
                    output.write(encoded, 0, samples * 2)
                    crc.update(encoded, 0, samples * 2)
                    y += count
                }
                checkCancelled()
                output.seek(0)
                output.writeLong(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(source.width)
                output.writeInt(source.height)
                output.writeLong(bytes)
                output.writeLong(crc.value)
                output.write(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                output.fd.sync()
            }
            synchronized(this) {
                checkCancelled()
                if (leases.getOrDefault(key, 0) > 0) return false
                val destination = entry(key)
                // Account for replacement before evicting; never remove the old value on failure.
                val additional = pending.length() - destination.length()
                if (!trim(additional.coerceAtLeast(0), except = destination)) return false
                check(pending.renameTo(destination)) { "Cannot publish render cache" }
                destination.setLastModified(System.currentTimeMillis())
            }
            return true
        } finally {
            pending.delete()
        }
    }

    /** Validate both header and payload before handing any cached pixels to a caller. */
    @Synchronized
    fun acquire(key: String, checkCancelled: () -> Unit = {}): Lease? {
        validateKey(key)
        checkCancelled()
        val file = entry(key)
        if (!file.isFile) return null
        val input = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return null
        try {
            check(input.readLong() == MAGIC && input.readInt() == VERSION)
            val width = input.readInt()
            val height = input.readInt()
            val bytes = input.readLong()
            val checksum = input.readLong()
            val storedKey = ByteArray(32).also(input::readFully)
            check(
                storedKey.contentEquals(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            )
            check(
                bytes == payloadBytes(width, height) &&
                    bytes <= maxBytes - HEADER_BYTES &&
                    file.length() == HEADER_BYTES + bytes
            )
            val crc = CRC32()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                try {
                    checkCancelled()
                } catch (cancelled: Throwable) {
                    input.close()
                    throw CacheReadCancelled(cancelled)
                }
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
            check(crc.value == checksum)
            leases[key] = leases.getOrDefault(key, 0) + 1
            file.setLastModified(System.currentTimeMillis())
            return Lease(key, width, height, input)
        } catch (error: Exception) {
            input.close()
            if (error is CacheReadCancelled) throw error.cause!!
            if (leases.getOrDefault(key, 0) == 0) file.delete()
            return null
        }
    }

    @Synchronized
    fun clear() {
        entries()
            .filter { leases.getOrDefault(it.nameWithoutExtension, 0) == 0 }
            .forEach(File::delete)
    }

    inner class Lease
    internal constructor(
        private val key: String,
        override val width: Int,
        override val height: Int,
        private val input: RandomAccessFile,
    ) : Rgba16Source, Closeable {
        private var closed = false
        private val maxRows = stripRows(width, height)
        private val buffer = ByteArray(width * maxRows * 8)

        @Synchronized
        override fun readRows(y: Int, rows: Int, destination: ShortArray) {
            check(!closed) { "Render cache lease is closed" }
            require(
                y >= 0 &&
                    rows > 0 &&
                    y.toLong() + rows <= height &&
                    destination.size.toLong() >= width.toLong() * rows * 4
            )
            input.seek(HEADER_BYTES + y.toLong() * width * 8)
            var remaining = rows
            var offset = 0
            while (remaining > 0) {
                val count = minOf(maxRows, remaining)
                val samples = width * count * 4
                input.readFully(buffer, 0, samples * 2)
                for (i in 0 until samples) {
                    destination[offset++] =
                        ((buffer[i * 2].toInt() and 255) or
                                ((buffer[i * 2 + 1].toInt() and 255) shl 8))
                            .toShort()
                }
                remaining -= count
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            try {
                input.close()
            } finally {
                synchronized(this@RenderDiskCache) {
                    val references = leases.getValue(key) - 1
                    if (references == 0) leases.remove(key) else leases[key] = references
                    trim(0)
                }
            }
        }
    }

    private fun entry(key: String) = File(directory, "$key.rgba16")

    private fun entries(): List<File> =
        directory
            .listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.rgba16")) }
            .orEmpty()

    private fun trim(additional: Long, except: File? = null): Boolean {
        val files = entries()
        var used = files.sumOf(File::length)
        for (file in files.sortedBy(File::lastModified)) {
            if (used <= maxBytes - additional) break
            if (file == except || leases.getOrDefault(file.nameWithoutExtension, 0) > 0) continue
            val length = file.length()
            if (file.delete()) used -= length
        }
        return used <= maxBytes - additional
    }

    private fun validateKey(key: String) = require(key.matches(Regex("[0-9a-f]{64}")))

    private fun payloadBytes(width: Int, height: Int): Long {
        require(width > 0 && height > 0 && width <= MAX_STRIP_BYTES / 8)
        val pixels = width.toLong() * height
        require(pixels <= (Long.MAX_VALUE - HEADER_BYTES) / 8)
        return pixels * 8
    }

    private companion object {
        const val MAX_STRIP_BYTES = 1024 * 1024
        const val MAGIC = 0x5448494152474231L // THIARGB1
        const val VERSION = 1
        const val HEADER_BYTES = 68L

        fun stripRows(width: Int, height: Int) = minOf(32, height, MAX_STRIP_BYTES / (width * 8))
    }

    private class CacheReadCancelled(cause: Throwable) : RuntimeException(cause)
}
