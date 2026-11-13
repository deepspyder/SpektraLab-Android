package com.spektralab.android.data.export

import android.graphics.Bitmap
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source

/** The same final U16/grade-to-8-bit quantizer used by ordinary photo PNG/JPEG output. */
internal object Rgba16Bitmap {
    fun convert(result: Rgba16Source, grade: Grade, checkCancelled: () -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
        try {
            val rows = minOf(32, result.height)
            val strip = ShortArray(result.width * rows * 4)
            val argb = IntArray(result.width * rows)
            val pixel = FloatArray(3)
            val native = if (grade.usesNativeGrade) FloatArray(result.width * rows * 3) else null
            var y = 0
            while (y < result.height) {
                checkCancelled()
                val count = minOf(rows, result.height - y)
                result.readRows(y, count, strip)
                if (native != null) grade.nativeRows(strip, 0, result.width, result.height, y, count, native)
                if (grade.isIdentity) {
                    for (i in 0 until result.width * count) {
                        val r = ((strip[i * 4].toInt() and 65535) * 255 + 32767) / 65535
                        val g = ((strip[i * 4 + 1].toInt() and 65535) * 255 + 32767) / 65535
                        val b = ((strip[i * 4 + 2].toInt() and 65535) * 255 + 32767) / 65535
                        argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                } else {
                    for (i in 0 until result.width * count) {
                        if (native != null) {
                            pixel[0] = native[i * 3]; pixel[1] = native[i * 3 + 1]; pixel[2] = native[i * 3 + 2]
                        } else grade.gradeAt(strip[i * 4], strip[i * 4 + 1], strip[i * 4 + 2], pixel,
                            i % result.width, y + i / result.width, result.width, result.height)
                        val r = (pixel[0] * 255f + .5f).toInt().coerceIn(0, 255)
                        val g = (pixel[1] * 255f + .5f).toInt().coerceIn(0, 255)
                        val b = (pixel[2] * 255f + .5f).toInt().coerceIn(0, 255)
                        argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                bitmap.setPixels(argb, 0, result.width, 0, y, result.width, count)
                y += count
            }
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}
