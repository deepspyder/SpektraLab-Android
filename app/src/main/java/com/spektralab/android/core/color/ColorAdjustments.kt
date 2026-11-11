package com.spektralab.android.core.color

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

enum class HslColor(val key: String, val centre: Float) {
    RED("red", 0f), ORANGE("orange", 30f), YELLOW("yellow", 60f), GREEN("green", 120f),
    AQUA("aqua", 180f), BLUE("blue", 240f), PURPLE("purple", 270f), MAGENTA("magenta", 300f),
}

/** UI units are -100..100; +/-100 hue means +/-30 degrees, as in the upstream model. */
data class HslBand(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    init {
        require(listOf(hue, saturation, luminance).all { it.isFinite() && it in -100f..100f })
    }

    val isNeutral: Boolean get() = hue == 0f && saturation == 0f && luminance == 0f
}

/** Version 1 follows upstream Layer2Kernel's chroma-plane HSL and adaptive saturation. */
class ColorAdjustments private constructor(val vibrance: Float, private val bands: List<HslBand>) {
    init { require(vibrance.isFinite() && vibrance in -100f..100f) }

    operator fun get(color: HslColor): HslBand = bands[color.ordinal]
    val isHslNeutral: Boolean get() = bands.all { it.isNeutral }
    val isNeutral: Boolean get() = vibrance == 0f && isHslNeutral

    fun withVibrance(value: Float): ColorAdjustments = ColorAdjustments(value, bands)
    fun with(color: HslColor, band: HslBand): ColorAdjustments =
        ColorAdjustments(vibrance, bands.mapIndexed { i, old -> if (i == color.ordinal) band else old })

    /** vibrance, HSL flag, then eight hues in radians, saturations and luminances. */
    fun uniforms(): FloatArray = FloatArray(26).also { out ->
        out[0] = vibrance / 100f
        out[1] = if (isHslNeutral) 0f else 1f
        bands.forEachIndexed { i, band ->
            out[2 + i] = band.hue * (Math.PI.toFloat() / 600f)
            out[10 + i] = band.saturation / 100f
            out[18 + i] = band.luminance / 100f
        }
    }

    private val values by lazy { uniforms() }

    /** Allocation-free CPU reference. Production previews and exports use the native strip pass. */
    fun apply(rgb: FloatArray) {
        if (isNeutral) return
        var r = rgb[0]; var g = rgb[1]; var b = rgb[2]
        if (vibrance != 0f) {
            val high = maxOf(r, g, b)
            val low = minOf(r, g, b)
            val saturation = if (high > 1e-4f) ((high - low) / high).coerceIn(0f, 1f) else 0f
            val luma = luma(r, g, b)
            val scale = (1f + values[0] * (1f - saturation)).coerceAtLeast(0f)
            r = luma + (r - luma) * scale
            g = luma + (g - luma) * scale
            b = luma + (b - luma) * scale
        }
        if (!isHslNeutral) {
            val luma = luma(r, g, b)
            val dr = r - luma; val dg = g - luma; val db = b - luma
            val x = dr - .5f * (dg + db)
            val y = .8660254f * (dg - db)
            val chroma = sqrt(x * x + y * y)
            if (chroma >= 1e-6f) {
                var hue = atan2(y, x) * 57.29577951f
                if (hue < 0f) hue += 360f
                var i = 0
                while (i < 7 && hue >= HslColor.entries[i + 1].centre) i++
                val next = (i + 1) % 8
                val end = if (i == 7) 360f else HslColor.entries[next].centre
                var t = ((hue - HslColor.entries[i].centre) / (end - HslColor.entries[i].centre)).coerceIn(0f, 1f)
                t *= t * (3f - 2f * t)
                fun mix(offset: Int) = values[offset + i] * (1f - t) + values[offset + next] * t
                val turn = mix(2)
                val scale = (1f + mix(10)).coerceAtLeast(0f)
                val cs = cos(turn); val sn = sin(turn)
                val qx = (x * cs - y * sn) * scale
                val qy = (x * sn + y * cs) * scale
                val q = qy / .8660254f
                val s = -2f * .2126f * qx - (.7152f - .0722f) * q
                val weight = (chroma / .2f).coerceIn(0f, 1f)
                val lift = 1f + .5f * mix(18) * weight * weight * (3f - 2f * weight)
                r = (luma + qx + .5f * s) * lift
                g = (luma + .5f * (s + q)) * lift
                b = (luma + .5f * (s - q)) * lift
            }
        }
        rgb[0] = r; rgb[1] = g; rgb[2] = b
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("vibrance", vibrance.toDouble()).apply {
        HslColor.entries.forEach { color ->
            val band = this@ColorAdjustments[color]
            put(color.key, JSONObject().put("hue", band.hue.toDouble())
                .put("saturation", band.saturation.toDouble()).put("luminance", band.luminance.toDouble()))
        }
    }

    override fun equals(other: Any?): Boolean = other is ColorAdjustments && vibrance == other.vibrance && bands == other.bands
    override fun hashCode(): Int = 31 * vibrance.hashCode() + bands.hashCode()

    companion object {
        val NEUTRAL = ColorAdjustments(0f, List(8) { HslBand() })

        fun fromJson(json: JSONObject): ColorAdjustments {
            require(json.getInt("version") == 1) { "Unsupported color adjustment version" }
            return ColorAdjustments(json.getDouble("vibrance").toFloat(), HslColor.entries.map { color ->
                val band = json.getJSONObject(color.key)
                HslBand(band.getDouble("hue").toFloat(), band.getDouble("saturation").toFloat(),
                    band.getDouble("luminance").toFloat())
            })
        }

        private fun luma(r: Float, g: Float, b: Float) = .2126f * r + .7152f * g + .0722f * b
    }
}
