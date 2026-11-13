package com.spektralab.android

import com.spektralab.android.core.color.*
import com.spektralab.android.engine.*
import java.util.concurrent.CancellationException

internal object DisplayStripAudit {
    fun run(): String {
        val width = 257
        val height = 259 // Non-square and a partial final strip.
        val input = ShortArray(width * height * 4) { (it * 373 + 17).toShort() }
        var samples = 0L
        val cases = buildList {
            add(grade())
            for (i in 0..16) for (sign in listOf(-1f, 1f)) add(grade(i, sign))
            add(grade(18, 1f))
            add(grade(19, 1f))
        }
        for (grade in cases) {
            val expected = IntArray(width * height)
            val rgb = FloatArray(3)
            val native = if (grade.usesNativeGrade) FloatArray(width * 32 * 3) else null
            for (y in 0 until height step 32) {
                val rows = minOf(32, height - y)
                native?.let { grade.nativeRows(input, y * width * 4, width, height, y, rows, it) }
                for (i in 0 until width * rows) {
                    val p = y * width + i
                    if (native != null) for (c in 0..2) rgb[c] = native[i * 3 + c]
                    else grade.gradeAt(input[p * 4], input[p * 4 + 1], input[p * 4 + 2], rgb,
                        p % width, p / width, width, height)
                    val q = rgb.map { (it * 255f + .5f).toInt().coerceIn(0, 255) }
                    expected[p] = 0xff000000.toInt() or (q[0] shl 16) or (q[1] shl 8) or q[2]
                }
            }
            val (bitmap, histogram) = ColorPipeline.applyGradeToBitmap(input, width, height, grade)
            try {
                val actual = IntArray(width * height)
                bitmap.getPixels(actual, 0, width, 0, 0, width, height)
                check(actual.contentEquals(expected)) { "Packed pixels changed, case ${cases.indexOf(grade)}; first ${actual.indices.first { actual[it] != expected[it] }}" }
                val counts = ColorPipeline.computeHistogram(bitmap)
                check(histogram.r.contentEquals(counts.r) && histogram.g.contentEquals(counts.g) && histogram.b.contentEquals(counts.b))
                samples += actual.size * 3L
            } finally { bitmap.recycle() }
        }
        var strips = 0
        check(runCatching { ColorPipeline.applyGradeToBitmap(input, width, height, grade()) {
            if (++strips == 3) throw CancellationException("owned cancellation")
        } }.exceptionOrNull() is CancellationException)
        val wideWidth = 35003
        val wideHeight = 33
        val wideInput = ShortArray(wideWidth * wideHeight * 4) { (it * 373 + 17).toShort() }
        val wideGrade = grade(3, 1f)
        val expectedWide = ColorPipeline.applyGrade(wideInput, wideWidth, wideHeight, wideGrade)
        val wide = ColorPipeline.applyGradeToBitmap(wideInput, wideWidth, wideHeight, wideGrade).first
        try {
            val actual = IntArray(wideWidth * wideHeight)
            wide.getPixels(actual, 0, wideWidth, 0, 0, wideWidth, wideHeight)
            check(actual.contentEquals(expectedWide)) { "Wide panorama strip changed pixels" }
            samples += actual.size * 3L
        } finally { wide.recycle() }
        for (bins in listOf(0, 767)) check(runCatching {
            NativeCurveGrade.applyArgb(input, 0, width, height, 0, 1, FloatArray(25), FloatArray(5125),
                FloatArray(53), null, null, IntArray(width), IntArray(bins), true)
        }.exceptionOrNull() is IllegalArgumentException)
        val nonfinite = FloatArray(25) { Float.NaN }.apply { this[23] = 0f; this[24] = 0f }
        val safePixels = IntArray(width)
        val safeBins = IntArray(768)
        NativeCurveGrade.applyArgb(input, 0, width, height, 0, 1, nonfinite, FloatArray(5125),
            FloatArray(53), null, null, safePixels, safeBins, false)
        check(safePixels.all { it == 0xff000000.toInt() } && safeBins[0] == width &&
            safeBins[256] == width && safeBins[512] == width) { "Nonfinite output escaped safe quantization" }
        return "$samples packed RGB samples and exact histograms match the prior scalar/native paths; 257x259 partial strips; bounded cancellation"
    }

    private fun grade(index: Int = -1, sign: Float = 1f): Grade {
        fun amount(i: Int, value: Float) = if (index == i) sign * value else 0f
        return Grade(1f + amount(0, .18f), 1f + amount(1, .12f), 1f,
            if (index == 2) 2f else 1f, amount(3, .8f), amount(4, .8f), 1f + amount(5, .8f),
            amount(6, .8f), amount(7, .8f), amount(8, .2f), amount(9, .2f),
            floatArrayOf(amount(10, .18f), amount(11, .12f), 0f),
            floatArrayOf(amount(12, .18f), amount(13, .12f), 0f),
            floatArrayOf(amount(14, .18f), amount(15, .12f), 0f),
            if (index == 16) .7f else 0f, .35f, .65f,
            if (index == 18) ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .63f)) else ToneCurves.IDENTITY,
            if (index == 19) ColorAdjustments.NEUTRAL.withVibrance(30f).with(HslColor.RED, HslBand(20f, -15f, 10f)) else ColorAdjustments.NEUTRAL)
    }
}
