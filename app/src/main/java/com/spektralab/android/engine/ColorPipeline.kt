package com.spektralab.android.engine

import com.spektralab.android.core.color.ToneCurves
import com.spektralab.android.core.color.ColorAdjustments
import android.graphics.Bitmap
import android.graphics.ColorSpace
import kotlin.math.pow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 色彩管线辅助。
 *
 * 输入：照片解码成 **线性 sRGB float**，以 `input_color_space = "sRGB"` 交给引擎。
 * 输出：会话以 `output_color_space = "sRGB"` 打开，引擎返回 sRGB 伽马编码的 rgba16。
 *
 * Layer 2 调色在**编码空间**（sRGB 0..1）做，与桌面 adjustments.ts 的 layer2Tone
 * 顺序一致：白平衡 → 曝光 → 对比度/亮度 → 高光/阴影 → 黑点/白点 → 饱和度 → 色彩平衡。
 */
object ColorPipeline {

    private val srgb8ToLinear = FloatArray(256) { srgbToLinear(it) }
    // The exact existing Float quantizer, for every possible U16 value (no interpolation).
    private val srgb16To8 = IntArray(65536) { ((it / 65535f) * 255f + .5f).toInt().coerceIn(0, 255) }

    /** sRGB 8bit 通道（0..255）→ 线性 float。 */
    fun srgbToLinear(c: Int): Float {
        val s = (c and 0xFF) / 255f
        return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    /** 把 ARGB_8888 Bitmap 解码成线性 sRGB float（RGB 三通道，紧凑排列）。 */
    fun bitmapToLinearSrgb(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val c = px[i]
            out[i * 3 + 0] = srgb8ToLinear[(c shr 16) and 0xFF]
            out[i * 3 + 1] = srgb8ToLinear[(c shr 8) and 0xFF]
            out[i * 3 + 2] = srgb8ToLinear[c and 0xFF]
        }
        return out
    }

    /** 把引擎的 rgba16（sRGB 伽马编码）过一遍 Layer 2 调色，转成显示用 ARGB。 */
    fun applyGrade(rgba16: ShortArray, width: Int, height: Int, grade: Grade, sourceColorSpace: String = "sRGB"): IntArray {
        return applyGradeImpl(rgba16, width, height, grade, sourceColorSpace, histogram = false).argb
    }

    fun applyGradeAndHistogram(rgba16: ShortArray, width: Int, height: Int, grade: Grade,
                               sourceColorSpace: String = "sRGB"): GradedFrame {
        return applyGradeImpl(rgba16, width, height, grade, sourceColorSpace, histogram = true)
    }

    /** Keep large proof/preview conversion bounded: no full-frame managed ARGB copy. */
    fun applyGradeToBitmap(rgba16: ShortArray, width: Int, height: Int, grade: Grade,
                           sourceColorSpace: String = "sRGB", checkCancelled: () -> Unit = {}): Pair<Bitmap, Histogram> {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val rows = minOf(32, height)
            val strip = IntArray(width * rows)
            val histogram = Histogram(IntArray(256), IntArray(256), IntArray(256))
            val pixel = FloatArray(3)
            val native = if (grade.usesNativeGrade) FloatArray(width * rows * 3) else null
            val transform = if (sourceColorSpace == "Display P3") {
                ColorSpace.connect(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), ColorSpace.get(ColorSpace.Named.SRGB))
            } else null
            val passthrough = grade.isIdentity && transform == null
            var y = 0
            while (y < height) {
                checkCancelled()
                val count = minOf(rows, height - y)
                if (native != null) grade.nativeRows(rgba16, y * width * 4, width, height, y, count, native)
                for (i in 0 until width * count) {
                    val index = (y * width + i) * 4
                    if (passthrough) {
                        val r = srgb16To8[rgba16[index].toInt() and 0xFFFF]
                        val g = srgb16To8[rgba16[index + 1].toInt() and 0xFFFF]
                        val b = srgb16To8[rgba16[index + 2].toInt() and 0xFFFF]
                        strip[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        histogram.r[r]++; histogram.g[g]++; histogram.b[b]++
                        continue
                    }
                    if (native != null) {
                        pixel[0] = native[i * 3]; pixel[1] = native[i * 3 + 1]; pixel[2] = native[i * 3 + 2]
                    } else grade.gradeAt(rgba16[index], rgba16[index + 1], rgba16[index + 2], pixel,
                        i % width, y + i / width, width, height)
                    transform?.transform(pixel)
                    val r = (pixel[0] * 255f + .5f).toInt().coerceIn(0, 255)
                    val g = (pixel[1] * 255f + .5f).toInt().coerceIn(0, 255)
                    val b = (pixel[2] * 255f + .5f).toInt().coerceIn(0, 255)
                    strip[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    histogram.r[r]++; histogram.g[g]++; histogram.b[b]++
                }
                bitmap.setPixels(strip, 0, width, 0, y, width, count)
                y += count
            }
            return bitmap to histogram
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun applyGradeImpl(rgba16: ShortArray, width: Int, height: Int, grade: Grade,
                               sourceColorSpace: String, histogram: Boolean): GradedFrame {
        val n = width * height
        val argb = IntArray(n)
        val histR = if (histogram) IntArray(256) else null
        val histG = if (histogram) IntArray(256) else null
        val histB = if (histogram) IntArray(256) else null
        val out = FloatArray(3)
        val displayTransform = if (sourceColorSpace == "Display P3")
            ColorSpace.connect(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), ColorSpace.get(ColorSpace.Named.SRGB)) else null
        for (i in 0 until n) {
            grade.gradeAt(rgba16[i * 4 + 0], rgba16[i * 4 + 1], rgba16[i * 4 + 2], out, i % width, i / width, width, height)
            displayTransform?.transform(out)
            val r = (out[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (out[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (out[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            histR?.let { it[r]++; histG!![g]++; histB!![b]++ }
        }
        val counts = if (histogram) Histogram(histR!!, histG!!, histB!!) else Histogram(IntArray(0), IntArray(0), IntArray(0))
        return GradedFrame(argb, counts)
    }

    /** 从引擎的 rgba16（sRGB 伽马编码）算 RGB 直方图，每通道 256 bins。 */
    fun computeHistogram(rgba16: ShortArray, width: Int, height: Int): Histogram {
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        val n = width * height
        for (i in 0 until n) {
            r[(rgba16[i * 4 + 0].toInt() and 0xFFFF) shr 8]++
            g[(rgba16[i * 4 + 1].toInt() and 0xFFFF) shr 8]++
            b[(rgba16[i * 4 + 2].toInt() and 0xFFFF) shr 8]++
        }
        return Histogram(r, g, b)
    }

    /** Count the displayed, graded pixels, matching the canvas rather than the ungraded engine buffer. */
    fun computeHistogram(bitmap: Bitmap): Histogram {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val r = IntArray(256); val g = IntArray(256); val b = IntArray(256)
        for (pixel in pixels) {
            r[(pixel ushr 16) and 255]++
            g[(pixel ushr 8) and 255]++
            b[pixel and 255]++
        }
        return Histogram(r, g, b)
    }

    // ---- ProPhoto（D50，线性）→ sRGB（D65，编码）----
    // 三个标准矩阵：ProPhoto→XYZ(D50)，Bradford D50→D65，XYZ(D65)→线性 sRGB。
    // 合成一个 3x3 供 RAW 解码出的线性 ProPhoto 转显示位图（crop 覆盖层/缩略图）。
    private val proPhotoToLinearSrgb: FloatArray by lazy {
        val prophotoToXyz = arrayOf(
            floatArrayOf(0.7976749f, 0.1351917f, 0.0313534f),
            floatArrayOf(0.2880402f, 0.7118741f, 0.0000857f),
            floatArrayOf(0.0f, 0.0f, 0.8252100f),
        )
        val bradford = arrayOf(
            floatArrayOf(0.9555766f, -0.0230393f, 0.0631636f),
            floatArrayOf(-0.0282895f, 1.0099416f, 0.0210077f),
            floatArrayOf(0.0122982f, -0.0204830f, 1.3299098f),
        )
        val xyzToSrgb = arrayOf(
            floatArrayOf(3.2404542f, -1.5371385f, -0.4985314f),
            floatArrayOf(-0.9692660f, 1.8760108f, 0.0415560f),
            floatArrayOf(0.0556434f, -0.2040259f, 1.0572252f),
        )
        fun mul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
            val r = Array(3) { FloatArray(3) }
            for (i in 0..2) for (j in 0..2) for (k in 0..2) r[i][j] += a[i][k] * b[k][j]
            return r
        }
        val m = mul(mul(xyzToSrgb, bradford), prophotoToXyz)
        FloatArray(9) { i -> m[i / 3][i % 3] }
    }

    /** 线性 ProPhoto float RGB → sRGB 编码的 ARGB_8888（供 RAW 源图显示）。 */
    fun proPhotoLinearToSrgbArgb(linear: FloatArray, width: Int, height: Int): IntArray {
        val m = proPhotoToLinearSrgb
        val n = width * height
        val argb = IntArray(n)
        for (i in 0 until n) {
            val r = linear[i * 3 + 0]
            val g = linear[i * 3 + 1]
            val b = linear[i * 3 + 2]
            val sr = (m[0] * r + m[1] * g + m[2] * b).coerceIn(0f, 1f)
            val sg = (m[3] * r + m[4] * g + m[5] * b).coerceIn(0f, 1f)
            val sb = (m[6] * r + m[7] * g + m[8] * b).coerceIn(0f, 1f)
            argb[i] = (0xFF shl 24) or
                (encodeSrgb(sr) shl 16) or (encodeSrgb(sg) shl 8) or encodeSrgb(sb)
        }
        return argb
    }

    private fun encodeSrgb(v: Float): Int {
        val e = if (v <= 0.0031308f) v * 12.92f else 1.055f * Math.pow(v.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        return (e.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}

/**
 * Layer 2 调色参数（编码空间 0..1）。系数与桌面 adjustments.ts 的 layer2Tone 一致。
 * 结果写进调用方传入的 [out]（0..1），避免逐像素分配。
 */
class Grade(
    private val wbR: Float, private val wbG: Float, private val wbB: Float,
    private val exposureGain: Float,
    private val contrast: Float,
    private val brightness: Float,
    private val saturation: Float,
    private val highlights: Float,
    private val shadows: Float,
    private val blackPoint: Float,
    private val whitePoint: Float,
    private val cbShadow: FloatArray,
    private val cbMid: FloatArray,
    private val cbHigh: FloatArray,
    private val vignetteAmount: Float = 0f,
    private val vignetteSize: Float = .5f,
    private val vignetteFeather: Float = .7f,
    private val curves: ToneCurves = ToneCurves.IDENTITY,
    private val colors: ColorAdjustments = ColorAdjustments.NEUTRAL,
    private val mixer: com.spektralab.android.core.color.ChannelMixer = com.spektralab.android.core.color.ChannelMixer.IDENTITY,
    private val wheels: com.spektralab.android.core.color.ColorWheels = com.spektralab.android.core.color.ColorWheels.NEUTRAL,
) {
    private val midGrey = 0.4614f  // SRGB_MID_GREY（desktop adjustments.ts）
    private val encodedExposureGain = Math.pow(exposureGain.toDouble(), 1.0 / 2.2).toFloat()
    private val contrastGain = 1f + contrast * 1.2f
    private val pointSpan = (1f - blackPoint - whitePoint).coerceAtLeast(.05f)
    private val balanceNeutral = cbShadow.all { it == 0f } && cbMid.all { it == 0f } && cbHigh.all { it == 0f }
    // Brightness was evaluating three double-precision powers per pixel. All inputs here
    // are U16: memoize the unchanged channel operations at every discrete input value.
    private val brightnessChannels = run {
        if (brightness == 0f || usesNativeGrade) null else Array(3) { channel ->
            val wb = when (channel) { 0 -> wbR; 1 -> wbG; else -> wbB }
            FloatArray(65536) { sample ->
                var value = (sample / 65535f) * wb
                if (encodedExposureGain != 1f) value *= encodedExposureGain
                if (contrast != 0f) value = (value - midGrey) * (1f + contrast * 1.2f) + midGrey
                powAtLeast0(value, 1f / (1f + brightness * .8f))
            }
        }
    }
    private val vignetteGains = if (vignetteAmount > 0f) FloatArray(1025) { index ->
        val t = index / 1024f
        val weight = t * t * (3f - 2f * t)
        Math.pow(2.0, (-2.5f * vignetteAmount.coerceIn(0f, 1f) * weight / 2.2f).toDouble()).toFloat()
    } else null
    private val identity = wbR == 1f && wbG == 1f && wbB == 1f && encodedExposureGain == 1f && contrast == 0f &&
        brightness == 0f && highlights == 0f && shadows == 0f && blackPoint == 0f && whitePoint == 0f &&
        saturation == 1f && cbShadow.all { it == 0f } && cbMid.all { it == 0f } && cbHigh.all { it == 0f }

    /** Neutral Layer 2 can pass the existing 16-bit samples through exactly. */
    internal val isIdentity: Boolean get() = identity && vignetteGains == null && curves.isIdentity && colors.isNeutral && mixer.isNeutral && wheels.isNeutral
    internal val usesNativeGrade: Boolean get() = !curves.isIdentity || !colors.isNeutral || !mixer.isNeutral || !wheels.isNeutral
    private val colorUniforms by lazy { colors.uniforms() + mixer.uniforms() + wheels.uniforms() }
    private val curveTable by lazy { curves.table() }
    private val nativeUniforms by lazy { floatArrayOf(wbR, wbG, wbB, encodedExposureGain, contrast, brightness, saturation,
        highlights, shadows, blackPoint, whitePoint, *cbShadow, *cbMid, *cbHigh,
        vignetteAmount, vignetteSize, vignetteFeather, if (curves.isIdentity) 0f else 1f,
        if (identity && colors.isNeutral && mixer.isNeutral && wheels.isNeutral) 1f else 0f) }
    private val nativeBrightness by lazy {
        if (brightness == 0f) null else ByteBuffer.allocateDirect(3 * 65536 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).also { NativeCurveGrade.prepareBrightness(nativeUniforms, it) }
    }

    internal fun nativeRows(input: ShortArray, offset: Int, width: Int, height: Int, y: Int, rows: Int, output: FloatArray) {
        check(usesNativeGrade)
        NativeCurveGrade.apply(input, offset, width, height, y, rows, nativeUniforms, curveTable, colorUniforms,
            vignetteGains, nativeBrightness, output)
    }

    private fun curve(x: Float, row: Int): Float {
        val at = x.coerceIn(0f, 1f) * (ToneCurves.TABLE_SIZE - 1)
        val low = at.toInt(); val high = minOf(low + 1, ToneCurves.TABLE_SIZE - 1); val f = at - low
        return curveTable[row * ToneCurves.TABLE_SIZE + low] * (1f - f) + curveTable[row * ToneCurves.TABLE_SIZE + high] * f
    }

    private fun applyCurves(out: FloatArray) {
        if (curves.isIdentity) return
        val lum = luma(out[0], out[1], out[2]); val mapped = curve(lum, 1)
        val gain = if (lum > 1e-4f) mapped / lum else 1f
        for (channel in 0..2) out[channel] = curve(curve((out[channel] * gain).coerceIn(0f, 1f), 0), channel + 2)
    }

    /** 把 sRGB 编码的 16bit 像素调色，结果（0..1）写进 [out][0..2]。 */
    fun grade(r16: Short, g16: Short, b16: Short, out: FloatArray) {
        var r = (r16.toInt() and 0xFFFF) / 65535f
        var g = (g16.toInt() and 0xFFFF) / 65535f
        var b = (b16.toInt() and 0xFFFF) / 65535f

        if (identity && colors.isNeutral && mixer.isNeutral && wheels.isNeutral) {
            out[0] = r; out[1] = g; out[2] = b
            applyCurves(out)
            return
        }

        val channels = brightnessChannels
        if (channels != null) {
            r = channels[0][r16.toInt() and 0xFFFF]
            g = channels[1][g16.toInt() and 0xFFFF]
            b = channels[2][b16.toInt() and 0xFFFF]
        } else {
            r *= wbR; g *= wbG; b *= wbB
            if (encodedExposureGain != 1f) {
                r *= encodedExposureGain; g *= encodedExposureGain; b *= encodedExposureGain
            }
            if (contrast != 0f) {
                val k = contrastGain
                r = (r - midGrey) * k + midGrey
                g = (g - midGrey) * k + midGrey
                b = (b - midGrey) * k + midGrey
            }
            if (brightness != 0f) {
                val e = 1f / (1f + brightness * 0.8f)
                r = powAtLeast0(r, e); g = powAtLeast0(g, e); b = powAtLeast0(b, e)
            }
        }
        val l0 = if (shadows != 0f || highlights != 0f) tonePosition(luma(r, g, b), midGrey) else 0f
        if (shadows != 0f) {
            val w = (1f - l0) * (1f - l0)
            r += shadows * 0.25f * w * (1f - r)
            g += shadows * 0.25f * w * (1f - g)
            b += shadows * 0.25f * w * (1f - b)
        }
        if (highlights != 0f) {
            val w = l0 * l0
            val k = highlights * 0.25f * w
            if (highlights > 0f) {
                r += k * (1f - r); g += k * (1f - g); b += k * (1f - b)
            } else {
                r += k * r; g += k * g; b += k * b
            }
        }
        val span = pointSpan
        r = (r - blackPoint) / span
        g = (g - blackPoint) / span
        b = (b - blackPoint) / span
        val lum = luma(r, g, b)
        r = lum + (r - lum) * saturation
        g = lum + (g - lum) * saturation
        b = lum + (b - lum) * saturation
        var balanceLuma = lum
        if (!colors.isNeutral) {
            out[0] = r; out[1] = g; out[2] = b
            colors.apply(out)
            r = out[0]; g = out[1]; b = out[2]
            balanceLuma = luma(r, g, b)
        }
        if (!mixer.isNeutral) {
            out[0] = r; out[1] = g; out[2] = b
            mixer.apply(out)
            r = out[0]; g = out[1]; b = out[2]
            balanceLuma = luma(r, g, b)
        }
        if (!wheels.isNeutral) {
            val ls = tonePosition(balanceLuma, midGrey).coerceIn(0f, 1f)
            val ws = (1f - ls) * (1f - ls); val wh = ls * ls; val wm = (1f - ws - wh).coerceAtLeast(0f)
            out[0] = r + cbShadow[0] * ws + cbMid[0] * wm + cbHigh[0] * wh
            out[1] = g + cbShadow[1] * ws + cbMid[1] * wm + cbHigh[1] * wh
            out[2] = b + cbShadow[2] * ws + cbMid[2] * wm + cbHigh[2] * wh
            wheels.apply(out, ws, wm, wh)
            for (c in 0..2) out[c] = out[c].coerceIn(0f, 1f)
            applyCurves(out)
            return
        }
        if (balanceNeutral) {
            out[0] = (r + 0f).coerceIn(0f, 1f)
            out[1] = (g + 0f).coerceIn(0f, 1f)
            out[2] = (b + 0f).coerceIn(0f, 1f)
            applyCurves(out)
            return
        }
        val ls = tonePosition(balanceLuma, midGrey).coerceIn(0f, 1f)
        val wS = (1f - ls) * (1f - ls)
        val wH = ls * ls
        val wM = (1f - wS - wH).coerceAtLeast(0f)
        out[0] = (r + cbShadow[0] * wS + cbMid[0] * wM + cbHigh[0] * wH).coerceIn(0f, 1f)
        out[1] = (g + cbShadow[1] * wS + cbMid[1] * wM + cbHigh[1] * wH).coerceIn(0f, 1f)
        out[2] = (b + cbShadow[2] * wS + cbMid[2] * wM + cbHigh[2] * wH).coerceIn(0f, 1f)
        applyCurves(out)
    }

    /** Output-relative coordinates make preview, JPEG/PNG and strip-written TIFF agree. */
    fun gradeAt(r16: Short, g16: Short, b16: Short, out: FloatArray, x: Int, y: Int, width: Int, height: Int) {
        grade(r16, g16, b16, out)
        val gains = vignetteGains ?: return
        val nx = (2f * x + 1f) / width - 1f
        val ny = (2f * y + 1f) / height - 1f
        val inner = .08f + vignetteSize.coerceIn(0f, 1f) * .85f
        val innerSquared = inner * inner
        val span = (.08f + vignetteFeather.coerceIn(0f, 1f) * 1.7f)
        val t = ((nx * nx + ny * ny - innerSquared) / span).coerceIn(0f, 1f)
        val gain = gains[(t * 1024f + .5f).toInt()]
        out[0] *= gain; out[1] *= gain; out[2] *= gain
    }

    private fun powAtLeast0(v: Float, e: Float): Float =
        Math.pow(v.coerceAtLeast(0f).toDouble(), e.toDouble()).toFloat()

    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun tonePosition(l: Float, mid: Float): Float {
        val m = mid.coerceIn(0.001f, 0.999f)
        return if (l <= m) 0.5f * l / m else 0.5f + 0.5f * (l - m) / (1f - m)
    }
}

/** RGB 直方图，每通道 256 bins。 */
class Histogram(val r: IntArray, val g: IntArray, val b: IntArray)

data class GradedFrame(val argb: IntArray, val histogram: Histogram?)
