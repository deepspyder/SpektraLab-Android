package com.spektralab.android.core.color

import org.json.JSONObject

enum class MixerChannel(val key: String) { RED("red"), GREEN("green"), BLUE("blue") }

/** Offsets in percent from the identity row, matching upstream MixerRow. */
data class MixerRow(val red: Float = 0f, val green: Float = 0f, val blue: Float = 0f) {
    init { require(listOf(red, green, blue).all { it.isFinite() && it in -100f..100f }) }

    val isNeutral: Boolean get() = red == 0f && green == 0f && blue == 0f

    operator fun get(input: MixerChannel): Float = when (input) {
        MixerChannel.RED -> red
        MixerChannel.GREEN -> green
        MixerChannel.BLUE -> blue
    }

    fun with(input: MixerChannel, value: Float): MixerRow = when (input) {
        MixerChannel.RED -> copy(red = value)
        MixerChannel.GREEN -> copy(green = value)
        MixerChannel.BLUE -> copy(blue = value)
    }
}

/**
 * Version 1: row-major RGB matrix on encoded scan values, after HSL and before
 * legacy colour balance. No row normalisation or intermediate clamp. Identity
 * skips all arithmetic, preserving the existing colour pipeline to the bit.
 */
data class ChannelMixer(
    val red: MixerRow = MixerRow(),
    val green: MixerRow = MixerRow(),
    val blue: MixerRow = MixerRow(),
) {
    val isNeutral: Boolean = red.isNeutral && green.isNeutral && blue.isNeutral
    private val matrix = FloatArray(9) { at ->
        val output = MixerChannel.entries[at / 3]
        val input = MixerChannel.entries[at % 3]
        this[output][input] / 100f + if (output == input) 1f else 0f
    }

    operator fun get(output: MixerChannel): MixerRow = when (output) {
        MixerChannel.RED -> red
        MixerChannel.GREEN -> green
        MixerChannel.BLUE -> blue
    }

    fun with(output: MixerChannel, row: MixerRow): ChannelMixer = when (output) {
        MixerChannel.RED -> copy(red = row)
        MixerChannel.GREEN -> copy(green = row)
        MixerChannel.BLUE -> copy(blue = row)
    }

    /** Active flag followed by the nine coefficients; no mutable state escapes. */
    fun uniforms(): FloatArray = floatArrayOf(if (isNeutral) 0f else 1f, *matrix)

    fun apply(rgb: FloatArray) {
        if (isNeutral) return
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        rgb[0] = matrix[0] * r + matrix[1] * g + matrix[2] * b
        rgb[1] = matrix[3] * r + matrix[4] * g + matrix[5] * b
        rgb[2] = matrix[6] * r + matrix[7] * g + matrix[8] * b
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).apply {
        MixerChannel.entries.forEach { output ->
            put(output.key, JSONObject().apply {
                MixerChannel.entries.forEach { input -> put(input.key, this@ChannelMixer[output][input].toDouble()) }
            })
        }
    }

    companion object {
        val IDENTITY = ChannelMixer()

        fun fromJson(json: JSONObject): ChannelMixer {
            require(json.get("version") is Number && json.getDouble("version") == 1.0) {
                "Unsupported channel mixer version"
            }
            fun row(output: MixerChannel): MixerRow {
                val data = json.getJSONObject(output.key)
                fun value(input: MixerChannel): Float {
                    val raw = data.get(input.key)
                    require(raw is Number) { "Invalid channel contribution" }
                    val number = raw.toDouble()
                    require(number.isFinite() && number in -100.0..100.0) { "Invalid channel contribution" }
                    return number.toFloat()
                }
                return MixerRow(value(MixerChannel.RED), value(MixerChannel.GREEN), value(MixerChannel.BLUE))
            }
            return ChannelMixer(row(MixerChannel.RED), row(MixerChannel.GREEN), row(MixerChannel.BLUE))
        }
    }
}
