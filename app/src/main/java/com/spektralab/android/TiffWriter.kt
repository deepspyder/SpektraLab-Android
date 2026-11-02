package com.spektralab.android

import com.spektralab.android.engine.Grade
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
    ) {
        val n = width * height
        val pixelBytes = n * 3 * 2

        val entryCount = if (icc != null) 14 else 13
        val ifdSize = 2 + entryCount * 12 + 4
        val extraOffset = 8 + ifdSize
        val bitsOffset = extraOffset
        val xresOffset = bitsOffset + 6
        val yresOffset = xresOffset + 8
        val iccOffset = yresOffset + 8
        val pixelOffset = iccOffset + (icc?.size ?: 0)

        val head = ByteBuffer.allocate(iccOffset).order(ByteOrder.LITTLE_ENDIAN)

        // ---- 头部 ----
        head.put('I'.code.toByte()).put('I'.code.toByte())  // "II" 小端
        head.putShort(42)                                    // TIFF magic
        head.putInt(8)                                       // 第一个 IFD 的偏移

        // ---- IFD ----
        head.putShort(entryCount.toShort())
        entry(head, 256, 4, 1, width)                    // ImageWidth
        entry(head, 257, 4, 1, height)                   // ImageLength
        entry(head, 258, 3, 3, bitsOffset)               // BitsPerSample = 16,16,16
        entry(head, 259, 3, 1, 1)                        // Compression = none
        entry(head, 262, 3, 1, 2)                        // PhotometricInterpretation = RGB
        entry(head, 273, 4, 1, pixelOffset)              // StripOffsets
        entry(head, 277, 3, 1, 3)                        // SamplesPerPixel = 3
        entry(head, 278, 4, 1, height)                   // RowsPerStrip
        entry(head, 279, 4, 1, pixelBytes)               // StripByteCounts
        entry(head, 282, 5, 1, xresOffset)               // XResolution
        entry(head, 283, 5, 1, yresOffset)               // YResolution
        entry(head, 284, 3, 1, 1)                        // PlanarConfiguration = chunky
        entry(head, 296, 3, 1, 2)                        // ResolutionUnit = inch
        if (icc != null) entry(head, 34675, 7, icc.size, iccOffset)  // ICC profile
        head.putInt(0)                                    // 无下一个 IFD

        // ---- 额外数据（BitsPerSample / XRes / YRes）----
        head.putShort(16).putShort(16).putShort(16)
        head.putInt(72).putInt(1)
        head.putInt(72).putInt(1)

        out.write(head.array(), 0, iccOffset)
        if (icc != null) out.write(icc)

        // ---- 像素数据（分块写，避免一次性大缓冲）----
        val chunk = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        val px = FloatArray(3)
        for (i in 0 until n) {
            if (grade != null) {
                grade.grade(rgba16[i * 4 + 0], rgba16[i * 4 + 1], rgba16[i * 4 + 2], px)
                chunk.putShort(toU16(px[0]))
                chunk.putShort(toU16(px[1]))
                chunk.putShort(toU16(px[2]))
            } else {
                // 无调色透传（DI 导出：设备 RGB 密度，不套 Layer 2）
                chunk.putShort(rgba16[i * 4 + 0])
                chunk.putShort(rgba16[i * 4 + 1])
                chunk.putShort(rgba16[i * 4 + 2])
            }
            if (chunk.remaining() < 6) {
                out.write(chunk.array(), 0, chunk.position())
                chunk.clear()
            }
        }
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
