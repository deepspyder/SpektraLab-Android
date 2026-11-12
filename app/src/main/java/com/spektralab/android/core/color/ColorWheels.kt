package com.spektralab.android.core.color

import kotlin.math.cos
import org.json.JSONObject

enum class WheelZone(val key: String) {
    MASTER("master"), SHADOWS("shadows"), MIDTONES("midtones"), HIGHLIGHTS("highlights")
}

/** UI units: hue in degrees, strength 0..100, luminance -100..100. */
data class WheelAdjustment(val hue: Float = 0f, val strength: Float = 0f, val luminance: Float = 0f) {
    init {
        require(hue.isFinite() && hue in 0f..360f)
        require(strength.isFinite() && strength in 0f..100f)
        require(luminance.isFinite() && luminance in -100f..100f)
    }
    val isNeutral: Boolean get() = strength == 0f && luminance == 0f
    val isDefault: Boolean get() = hue == 0f && isNeutral
}

/** Version 1 follows upstream ColorZone offsets and mid-grey zone weights.
 * Legacy temp/tint remains separate and is summed before the new luminance gain.
 * A neutral wheel set performs no arithmetic, preserving old documents exactly.
 */
data class ColorWheels(
    val master: WheelAdjustment = WheelAdjustment(),
    val shadows: WheelAdjustment = WheelAdjustment(),
    val midtones: WheelAdjustment = WheelAdjustment(),
    val highlights: WheelAdjustment = WheelAdjustment(),
) {
    val isNeutral = WheelZone.entries.all { this[it].isNeutral }
    val isDefault = WheelZone.entries.all { this[it].isDefault }
    private val offsets = FloatArray(12) { at ->
        val adjustment = this[WheelZone.entries[at / 3]]
        val angle = adjustment.hue.toDouble() * Math.PI / 180.0
        val phase = when (at % 3) { 1 -> -2.0 * Math.PI / 3.0; 2 -> 2.0 * Math.PI / 3.0; else -> 0.0 }
        cos(angle + phase).toFloat() * (adjustment.strength / 100f * .25f)
    }
    private val light = FloatArray(4) { this[WheelZone.entries[it]].luminance / 100f * .25f }

    operator fun get(zone: WheelZone): WheelAdjustment = when (zone) {
        WheelZone.MASTER -> master
        WheelZone.SHADOWS -> shadows
        WheelZone.MIDTONES -> midtones
        WheelZone.HIGHLIGHTS -> highlights
    }

    fun with(zone: WheelZone, value: WheelAdjustment): ColorWheels = when (zone) {
        WheelZone.MASTER -> copy(master = value)
        WheelZone.SHADOWS -> copy(shadows = value)
        WheelZone.MIDTONES -> copy(midtones = value)
        WheelZone.HIGHLIGHTS -> copy(highlights = value)
    }

    fun uniforms(): FloatArray = floatArrayOf(if (isNeutral) 0f else 1f, *offsets, *light)

    /** Input already includes legacy colour balance, weights come from its input luma. */
    fun apply(rgb: FloatArray, shadowWeight: Float, midWeight: Float, highWeight: Float) {
        if (isNeutral) return
        val gain = 1f + light[0] + light[1] * shadowWeight + light[2] * midWeight + light[3] * highWeight
        for (channel in 0..2) {
            rgb[channel] = (rgb[channel] + offsets[channel] + offsets[3 + channel] * shadowWeight +
                offsets[6 + channel] * midWeight + offsets[9 + channel] * highWeight) * gain
        }
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).apply {
        WheelZone.entries.forEach { zone ->
            val value = this@ColorWheels[zone]
            put(zone.key, JSONObject().put("hue", value.hue.toDouble()).put("strength", value.strength.toDouble())
                .put("luminance", value.luminance.toDouble()))
        }
    }

    companion object {
        val NEUTRAL = ColorWheels()
        fun fromJson(json: JSONObject): ColorWheels {
            require(json.get("version") is Number && json.getDouble("version") == 1.0)
            fun zone(zone: WheelZone): WheelAdjustment {
                val data = json.getJSONObject(zone.key)
                fun number(key: String, range: ClosedFloatingPointRange<Double>): Float {
                    val raw = data.get(key)
                    require(raw is Number)
                    val value = raw.toDouble()
                    require(value.isFinite() && value in range)
                    return value.toFloat()
                }
                return WheelAdjustment(number("hue", 0.0..360.0), number("strength", 0.0..100.0),
                    number("luminance", -100.0..100.0))
            }
            return ColorWheels(zone(WheelZone.MASTER), zone(WheelZone.SHADOWS), zone(WheelZone.MIDTONES), zone(WheelZone.HIGHLIGHTS))
        }
    }
}
