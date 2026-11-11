package com.spektralab.android.data.export

import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.NativeRenderResult
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简 16-bit RGB TIFF 写入器：小端、无压缩、可嵌 ICC profile。
 * 输入是紧凑排列的 RGB16（每像素 3 个 short），行优先、首行为顶。
 */
object TiffWriter {

    fun writeRgba16(
        rgba16: ShortArray,
        width: Int,
        height: Int,
        grade: Grade?,
        icc: ByteArray?,
        out: OutputStream,
        checkCancelled: () -> Unit = {},
        metadata: ExportMetadata = ExportMetadata.EMPTY,
    ) {
        writeRows(width, height, grade, icc, out, checkCancelled, metadata) { y, rows, destination ->
            rgba16.copyInto(destination, 0, y * width * 4, (y + rows) * width * 4)
        }
    }

    fun writeNative(
        result: NativeRenderResult,
        grade: Grade?,
        icc: ByteArray?,
        out: OutputStream,
        checkCancelled: () -> Unit = {},
        metadata: ExportMetadata = ExportMetadata.EMPTY,
    ) {
        writeRows(result.width, result.height, grade, icc, out, checkCancelled, metadata, result::readRows)
    }

    private fun writeRows(width: Int, height: Int, grade: Grade?, icc: ByteArray?, out: OutputStream,
                          checkCancelled: () -> Unit,
                          metadata: ExportMetadata,
                          read: (Int, Int, ShortArray) -> Unit) {
        val n = width * height
        val pixelBytes = n * 3 * 2

        val rootFields = metadata.rootFields()
        val exifFields = metadata.exifFields()
        val gpsFields = metadata.gpsFields()
        val entryCount = (if (icc != null) 14 else 13) + rootFields.size +
            (if (exifFields.isNotEmpty()) 1 else 0) + (if (gpsFields.isNotEmpty()) 1 else 0)
        val ifdSize = 2 + entryCount * 12 + 4
        val extraOffset = 8 + ifdSize
        val bitsOffset = extraOffset
        val xresOffset = bitsOffset + 6
        val yresOffset = xresOffset + 8
        val iccOffset = yresOffset + 8
        val metadataStart = iccOffset + (icc?.size ?: 0)
        val payload = java.io.ByteArrayOutputStream()
        fun store(bytes: ByteArray): Int {
            if ((metadataStart + payload.size()) % 2 != 0) payload.write(0)
            val offset = metadataStart + payload.size()
            payload.write(bytes); return offset
        }
        fun fieldValue(field: ExportMetadata.Field): Int = if (field.bytes.size > 4) store(field.bytes)
            else ByteBuffer.wrap(field.bytes.copyOf(4)).order(ByteOrder.LITTLE_ENDIAN).int
        fun directory(fields: List<ExportMetadata.Field>): Int {
            val dir = ByteBuffer.allocate(2 + fields.size * 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
            dir.putShort(fields.size.toShort())
            fields.sortedBy { it.id }.forEach { entry(dir, it.id, it.type, it.count, fieldValue(it)) }
            dir.putInt(0)
            return store(dir.array())
        }
        val extraEntries = rootFields.map { field -> intArrayOf(field.id, field.type, field.count, fieldValue(field)) }.toMutableList()
        if (exifFields.isNotEmpty()) extraEntries.add(intArrayOf(34665, 4, 1, directory(exifFields)))
        if (gpsFields.isNotEmpty()) extraEntries.add(intArrayOf(34853, 4, 1, directory(gpsFields)))
        if ((metadataStart + payload.size()) % 2 != 0) payload.write(0)
        val pixelOffset = metadataStart + payload.size()

        val head = ByteBuffer.allocate(iccOffset).order(ByteOrder.LITTLE_ENDIAN)

        // ---- 头部 ----
        head.put('I'.code.toByte()).put('I'.code.toByte())  // "II" 小端
        head.putShort(42)                                    // TIFF magic
        head.putInt(8)                                       // 第一个 IFD 的偏移

        // ---- IFD ----
        head.putShort(entryCount.toShort())
        val entries = mutableListOf(
            intArrayOf(256,4,1,width), intArrayOf(257,4,1,height), intArrayOf(258,3,3,bitsOffset),
            intArrayOf(259,3,1,1), intArrayOf(262,3,1,2), intArrayOf(273,4,1,pixelOffset),
            intArrayOf(277,3,1,3), intArrayOf(278,4,1,height), intArrayOf(279,4,1,pixelBytes),
            intArrayOf(282,5,1,xresOffset), intArrayOf(283,5,1,yresOffset),
            intArrayOf(284,3,1,1), intArrayOf(296,3,1,2))
        if (icc != null) entries.add(intArrayOf(34675,7,icc.size,iccOffset))
        entries.addAll(extraEntries)
        entries.sortedBy { it[0] }.forEach { entry(head, it[0], it[1], it[2], it[3]) }
        head.putInt(0)                                    // 无下一个 IFD

        // ---- 额外数据（BitsPerSample / XRes / YRes）----
        head.putShort(16).putShort(16).putShort(16)
        head.putInt(72).putInt(1)
        head.putInt(72).putInt(1)

        out.write(head.array(), 0, iccOffset)
        if (icc != null) out.write(icc)
        out.write(payload.toByteArray())

        // Neutral/DI output needs neither float grading nor per-sample ByteBuffer calls.
        // A 16-bit sample round-trips through identity Grade unchanged; preserve it directly.
        val outputGrade = grade?.takeUnless { it.isIdentity }
        if (outputGrade == null) {
            val rows = minOf(32, height)
            val strip = ShortArray(width * rows * 4)
            val encoded = ByteArray(width * rows * 6)
            var y = 0
            while (y < height) {
                checkCancelled()
                val count = minOf(rows, height - y)
                read(y, count, strip)
                var destination = 0
                for (pixel in 0 until width * count) {
                    val source = pixel * 4
                    for (channel in 0..2) {
                        val sample = strip[source + channel].toInt()
                        encoded[destination++] = sample.toByte()
                        encoded[destination++] = (sample ushr 8).toByte()
                    }
                }
                out.write(encoded, 0, destination)
                y += count
            }
            checkCancelled()
            return
        }

        // ---- 像素数据（分块写，避免一次性大缓冲）----
        val chunk = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        val px = FloatArray(3)
        val rows = minOf(32, height)
        val strip = ShortArray(width * rows * 4)
        var y = 0
        while (y < height) {
            checkCancelled()
            val stripRows = minOf(rows, height - y)
            read(y, stripRows, strip)
            for (i in 0 until width * stripRows) {
                val index = i * 4
                outputGrade.gradeAt(strip[index], strip[index + 1], strip[index + 2], px,
                    i % width, y + i / width, width, height)
                chunk.putShort(toU16(px[0]))
                chunk.putShort(toU16(px[1]))
                chunk.putShort(toU16(px[2]))
                if (chunk.remaining() < 6) {
                    out.write(chunk.array(), 0, chunk.position())
                    chunk.clear()
                }
            }
            y += stripRows
        }
        checkCancelled()
        if (chunk.position() > 0) out.write(chunk.array(), 0, chunk.position())
    }

    private fun toU16(v: Float): Short = (v * 65535f + 0.5f).toInt().coerceIn(0, 65535).toShort()

    private fun entry(buf: ByteBuffer, tag: Int, type: Int, count: Int, value: Int) {
        buf.putShort(tag.toShort())
        buf.putShort(type.toShort())
        buf.putInt(count)
        if (type == 3 && count == 1) {
            // SHORT 内联值：占 4 字节字段的低 2 字节
            buf.putShort(value.toShort())
            buf.putShort(0)
        } else {
            // LONG 内联值，或指向额外数据的偏移
            buf.putInt(value)
        }
    }
}
