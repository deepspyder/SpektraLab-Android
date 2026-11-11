package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.provider.MediaStore
import com.spektralab.android.data.export.ExportPublisher
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.Grade
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exhaustive 16-bit quantization and atomic-publication checks for the optimized writer. */
internal class ExportEncodingAudit(private val test: Instrumentation) {
    fun run(): String {
        val grade = Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
            FloatArray(3), FloatArray(3), FloatArray(3))
        check(grade.isIdentity)
        val width = 257
        val height = 257 // exercise a partial final 32-row strip as well as every 16-bit value
        val pixels = ShortArray(width * height * 4)
        val expected = FloatArray(3)
        for (value in 0..65535) {
            grade.gradeAt(value.toShort(), value.toShort(), value.toShort(), expected, 0, 0, 1, 1)
            check((expected[0] * 65535f + .5f).toInt() == value)
            check((expected[0] * 255f + .5f).toInt() == (value * 255 + 32767) / 65535)
        }
        for (pixel in 0 until width * height) {
            pixels[pixel * 4] = pixel.toShort()
            pixels[pixel * 4 + 1] = (65535 - pixel).toShort()
            pixels[pixel * 4 + 2] = (pixel * 17).toShort()
            pixels[pixel * 4 + 3] = (pixel % 13).toShort()
        }
        val bytes = ByteArrayOutputStream().also {
            TiffWriter.writeRgba16(pixels, width, height, grade, null, it)
        }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = buffer.getInt(4)
        val entries = buffer.getShort(ifd).toInt() and 65535
        var offset = -1
        for (entry in 0 until entries) {
            val position = ifd + 2 + entry * 12
            if ((buffer.getShort(position).toInt() and 65535) == 273) offset = buffer.getInt(position + 8)
        }
        check(offset > 0 && bytes.size == offset + width * height * 6)
        for (pixel in 0 until width * height) for (channel in 0..2) {
            check(buffer.getShort(offset + (pixel * 3 + channel) * 2) == pixels[pixel * 4 + channel])
        }
        val adjusted = Grade(1.04f, .98f, .96f, 1.2f, .15f, .1f, 1.1f, -.1f, .12f, .02f, .01f,
            floatArrayOf(.01f, -.01f, .02f), FloatArray(3), floatArrayOf(.02f, .01f, -.01f),
            .6f, .5f, .7f)
        for (colorSpace in listOf("sRGB", "Display P3")) for (adjustment in listOf(grade, adjusted)) {
            val reference = ColorPipeline.applyGradeAndHistogram(pixels, width, height, adjustment, colorSpace)
            val (bitmap, histogram) = ColorPipeline.applyGradeToBitmap(pixels, width, height, adjustment, colorSpace)
            try {
                val actual = IntArray(width * height)
                bitmap.getPixels(actual, 0, width, 0, 0, width, height)
                check(actual.contentEquals(reference.argb)) { "Strip preview conversion changed a pixel" }
                check(histogram.r.contentEquals(reference.histogram!!.r))
                check(histogram.g.contentEquals(reference.histogram.g))
                check(histogram.b.contentEquals(reference.histogram.b))
            } finally {
                bitmap.recycle()
            }
        }

        val resolver = test.targetContext.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun auditOutputs(): Set<Uri> = buildSet {
            resolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("Theia_Audit_stream_%"), null)?.use { cursor ->
                while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
        }
        val before = auditOutputs()
        var commits = 0
        var cancelled = false
        val publisher = ExportPublisher(test.targetContext,
            { check(!cancelled) { "cancel sentinel" } }, { commits++ })
        try {
            val result = publisher.write("Theia_Audit_stream_${System.nanoTime()}.tiff", "image/tiff") { it.write(bytes) }
            val uri = Uri.parse(result.removePrefix("相册: "))
            val actual = resolver.openInputStream(uri)?.use { it.readBytes() }
            check(actual != null && actual.contentEquals(bytes))
            check(commits == 1)
            val failed = runCatching {
                publisher.write("Theia_Audit_stream_failed.tiff", "image/tiff") {
                    it.write(bytes, 0, 16)
                    error("encoder sentinel")
                }
            }.exceptionOrNull()
            check(failed?.message == "encoder sentinel" && commits == 1)
            val cancelledWrite = runCatching {
                publisher.write("Theia_Audit_stream_cancelled.tiff", "image/tiff") {
                    it.write(bytes, 0, 16)
                    cancelled = true
                }
            }.exceptionOrNull()
            check(cancelledWrite?.message == "cancel sentinel" && commits == 1)
            val remainingIds = (auditOutputs() - before).map(android.content.ContentUris::parseId).toSet()
            check(remainingIds == setOf(android.content.ContentUris.parseId(uri))) {
                "An incomplete MediaStore entry survived"
            }
            resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            } ?: error("Published file disappeared")
        } finally {
            (auditOutputs() - before).forEach { resolver.delete(it, null, null) }
        }
        return "All 65536 identity samples retain exact TIFF/8-bit quantization; RGB strips match; direct publication commits once and deletes failed/cancelled entries"
    }
}
