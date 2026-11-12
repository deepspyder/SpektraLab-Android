package com.spektralab.android.engine

import com.spektralab.android.core.color.ToneCurves
import com.spektralab.android.core.color.ColorAdjustments

/** Frozen legacy scalar pipeline extended only with the independently evaluated row matrix. */
internal class ChannelMixerGradeReference(
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
    private val mixer: com.spektralab.android.core.color.ChannelMixer,
) {
    private val midGrey = 0.4614f  // SRGB_MID_GREY（desktop adjustments.ts）
    private val encodedExposureGain = Math.pow(exposureGain.toDouble(), 1.0 / 2.2).toFloat()
    private val vignetteGains = if (vignetteAmount > 0f) FloatArray(1025) { index ->
        val t = index / 1024f
        val weight = t * t * (3f - 2f * t)
        Math.pow(2.0, (-2.5f * vignetteAmount.coerceIn(0f, 1f) * weight / 2.2f).toDouble()).toFloat()
    } else null
    private val identity = wbR == 1f && wbG == 1f && wbB == 1f && encodedExposureGain == 1f && contrast == 0f &&
        brightness == 0f && highlights == 0f && shadows == 0f && blackPoint == 0f && whitePoint == 0f &&
        saturation == 1f && cbShadow.all { it == 0f } && cbMid.all { it == 0f } && cbHigh.all { it == 0f }

    /** Neutral Layer 2 can pass the existing 16-bit samples through exactly. */
    internal val isIdentity: Boolean get() = identity && vignetteGains == null && curves.isIdentity && colors.isNeutral
    internal val usesNativeGrade: Boolean get() = !curves.isIdentity || !colors.isNeutral
    private val colorUniforms by lazy { colors.uniforms() }
    private val curveTable by lazy { curves.table() }
    private val nativeUniforms by lazy { floatArrayOf(wbR, wbG, wbB, encodedExposureGain, contrast, brightness, saturation,
        highlights, shadows, blackPoint, whitePoint, *cbShadow, *cbMid, *cbHigh,
        vignetteAmount, vignetteSize, vignetteFeather, if (curves.isIdentity) 0f else 1f, if (identity && colors.isNeutral && mixer.isNeutral) 1f else 0f) }

    internal fun nativeRows(input: ShortArray, offset: Int, width: Int, height: Int, y: Int, rows: Int, output: FloatArray) {
        check(usesNativeGrade)
        NativeCurveGrade.apply(input, offset, width, height, y, rows, nativeUniforms, curveTable, colorUniforms, vignetteGains, null, output)
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

        if (identity && colors.isNeutral && mixer.isNeutral) {
            out[0] = r; out[1] = g; out[2] = b
            applyCurves(out)
            return
        }

        r *= wbR; g *= wbG; b *= wbB
        if (encodedExposureGain != 1f) {
            r *= encodedExposureGain; g *= encodedExposureGain; b *= encodedExposureGain
        }
        if (contrast != 0f) {
            val k = 1f + contrast * 1.2f
            r = (r - midGrey) * k + midGrey
            g = (g - midGrey) * k + midGrey
            b = (b - midGrey) * k + midGrey
        }
        if (brightness != 0f) {
            val e = 1f / (1f + brightness * 0.8f)
            r = powAtLeast0(r, e); g = powAtLeast0(g, e); b = powAtLeast0(b, e)
        }
        val l0 = tonePosition(luma(r, g, b), midGrey)
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
        val span = (1f - blackPoint - whitePoint).coerceAtLeast(0.05f)
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
            val original = floatArrayOf(r, g, b)
            val mixed = FloatArray(3)
            for (row in com.spektralab.android.core.color.MixerChannel.entries) {
                val weights = FloatArray(3) { col ->
                    val input = com.spektralab.android.core.color.MixerChannel.entries[col]
                    mixer[row][input] / 100f + if (row.ordinal == col) 1f else 0f
                }
                mixed[row.ordinal] = weights[0] * original[0] + weights[1] * original[1] + weights[2] * original[2]
            }
            r = mixed[0]; g = mixed[1]; b = mixed[2]
            balanceLuma = luma(r, g, b)
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
