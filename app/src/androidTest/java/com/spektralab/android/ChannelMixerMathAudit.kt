package com.spektralab.android

import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.engine.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.json.JSONObject
import org.json.JSONTokener

internal object ChannelMixerMathAudit {
    private val swap = ChannelMixer(MixerRow(-100f, 0f, 100f), MixerRow(), MixerRow(100f, 0f, -100f))

    fun timings(): String {
        val width = 1024; val height = 768
        val input = ShortArray(width * height * 4) { (it * 373 + 17).toShort() }
        val cases = mutableListOf("neutral" to ChannelMixer.IDENTITY)
        for (output in MixerChannel.entries) for (channel in MixerChannel.entries) {
            cases += "${output.key}.${channel.key}" to ChannelMixer.IDENTITY.with(output, MixerRow().with(channel, 25f))
        }
        val report = org.json.JSONArray()
        for ((name, mixer) in cases) {
            val g = grade(mixer)
            ColorPipeline.applyGradeToBitmap(input, width, height, g).first.recycle()
            val samples = mutableListOf<Double>()
            var hash: Int? = null
            repeat(3) {
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                val (bitmap, histogram) = ColorPipeline.applyGradeToBitmap(input, width, height, g)
                samples += (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1e6
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                bitmap.recycle()
                val current = pixels.contentHashCode()
                check(hash == null || hash == current)
                hash = current
                check(histogram.r.sum() == width * height)
            }
            report.put(JSONObject().put("parameter", name).put("median_ms", samples.sorted()[1])
                .put("samples_ms", org.json.JSONArray(samples)).put("pixel_hash", hash))
        }
        return JSONObject().put("width", width).put("height", height).put("parameters", report).toString()
    }

    fun run(): String {
        val neutral = ChannelMixer.IDENTITY
        check(!EditorParams().toJson().has("channel_mixer"))
        val untouched = floatArrayOf(-0f, -2f, 1e-32f)
        val bits = untouched.map { it.toRawBits() }
        neutral.apply(untouched)
        check(untouched.map { it.toRawBits() } == bits)
        var samples = 0
        for (output in MixerChannel.entries) for (input in MixerChannel.entries) {
            for (offset in listOf(-100f, -35f, 45f, 100f)) {
                val mixer = neutral.with(output, MixerRow().with(input, offset))
                check(ChannelMixer.fromJson(mixer.toJson()) == mixer)
                val params = EditorParams().apply { channelMixer = mixer }
                check(params.copy().channelMixer == mixer)
                check(EditSnapshot.capture(params).toParams().channelMixer == mixer)
                val target = EditorParams().apply { cropX = .2f; cropW = .6f }
                target.copySettingsFrom(params)
                check(target.channelMixer == mixer && target.cropX == .2f && target.cropW == .6f)
                for (i in 0..257) {
                    val original = floatArrayOf(i / 257f, .31f, .83f)
                    val actual = original.clone()
                    mixer.apply(actual)
                    val expected = original.clone()
                    // A single offset changes only its selected output, by the
                    // selected input times a percentage. No matrix helper used.
                    expected[output.ordinal] = original[output.ordinal] + original[input.ordinal] * (offset / 100f)
                    check((0..2).all { abs(actual[it] - expected[it]) < 3e-7f })
                    samples += 3
                }
            }
        }
        for (i in 0..65535) {
            val input = floatArrayOf(i / 65535f, .37f, (65535 - i) / 65535f)
            val r = input[0]; val b = input[2]
            swap.apply(input)
            check(input[0] == b && input[1] == .37f && input[2] == r)
        }
        check(runCatching { MixerRow(Float.NaN) }.isFailure)
        check(runCatching { MixerRow(101f) }.isFailure)
        for (raw in listOf("{\"version\":99,\"keep\":true}", "[]", "\"invalid\"", "null",
            neutral.toJson().put("red", JSONObject().put("red", 101).put("green", 0).put("blue", 0)).toString())) {
            val value = JSONTokener(raw).nextValue()
            val loaded = EditorParams.fromJson(JSONObject().put("channel_mixer", value))
            check(loaded.unreadableChannelMixer != null && loaded.channelMixer.isNeutral)
            check(loaded.copy().toJson().get("channel_mixer").toString() == value.toString())
        }
        val colors = ColorAdjustments.NEUTRAL.withVibrance(24f).with(HslColor.BLUE, HslBand(28f, -17f, 13f))
        val curves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .61f))
        val input = ShortArray(97 * 39 * 4) { (it * 373 + 17).toShort() }
        var maximum = 0f
        val mixes = listOf(swap, ChannelMixer(MixerRow(50f, -30f, 20f), MixerRow(-25f, 35f, 40f), MixerRow(40f, -30f, -25f)),
            ChannelMixer(MixerRow(-100f, -100f, -100f), MixerRow(100f, 100f, 100f), MixerRow(100f, -100f, -100f)))
        for (mixer in mixes) for (post in listOf(false, true)) {
            val cs = if (post) colors else ColorAdjustments.NEUTRAL
            val tc = if (post) curves else ToneCurves.IDENTITY
            val grade = grade(mixer, cs, tc)
            val reference = reference(mixer, cs, tc)
            check(grade.usesNativeGrade && !grade.isIdentity)
            val expected = FloatArray(3)
            val actual = FloatArray(97 * 32 * 3)
            var y = 0
            while (y < 39) {
                val rows = minOf(32, 39 - y)
                grade.nativeRows(input, y * 97 * 4, 97, 39, y, rows, actual)
                for (i in 0 until 97 * rows) {
                    val at = (y * 97 + i) * 4
                    reference.gradeAt(input[at], input[at + 1], input[at + 2], expected, i % 97, y + i / 97, 97, 39)
                    for (c in 0..2) maximum = maxOf(maximum, abs(actual[i * 3 + c] - expected[c]))
                }
                y += rows
            }
            check(maximum < 4e-6f) { "Mixer native/reference error: $maximum" }
            for (space in listOf("sRGB", "Display P3")) {
                val expectedFrame = ColorPipeline.applyGradeAndHistogram(input, 97, 39, grade, space)
                val (bitmap, histogram) = ColorPipeline.applyGradeToBitmap(input, 97, 39, grade, space)
                val pixels = IntArray(97 * 39)
                bitmap.getPixels(pixels, 0, 97, 0, 0, 97, 39)
                bitmap.recycle()
                check(histogram.r.sum() == pixels.size)
                check(pixels.indices.all { i -> (0..2).all { c ->
                    abs(((pixels[i] shr (8 * c)) and 255) - ((expectedFrame.argb[i] shr (8 * c)) and 255)) <= 1
                } })
            }
            val bytes = ByteArrayOutputStream().also { TiffWriter.writeRgba16(input, 97, 39, grade, null, it) }.toByteArray()
            val tiff = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val ifd = tiff.getInt(4)
            val offset = (0 until (tiff.getShort(ifd).toInt() and 65535)).map { ifd + 2 + it * 12 }
                .first { (tiff.getShort(it).toInt() and 65535) == 273 }.let { tiff.getInt(it + 8) }
            for (i in 0 until 97 * 39) {
                reference.gradeAt(input[i * 4], input[i * 4 + 1], input[i * 4 + 2], expected, i % 97, i / 97, 97, 39)
                for (c in 0..2) check(abs((tiff.getShort(offset + (i * 3 + c) * 2).toInt() and 65535) -
                    (expected[c] * 65535f + .5f).toInt().coerceIn(0, 65535)) <= 1)
            }
        }
        // Neutral rows do not activate the native path or add arithmetic to old
        // scalar output. Check every U16 input against the frozen old pipeline.
        val legacy = ScalarGradeReference(1.03f, .98f, 1f, 1.1f, .08f, -.03f, 1.08f, -.06f, .04f, .01f, -.01f,
            floatArrayOf(.01f, -.01f, 0f), FloatArray(3), floatArrayOf(0f, 0f, .01f))
        val current = grade(neutral)
        check(!current.usesNativeGrade)
        val a = FloatArray(3); val b = FloatArray(3)
        for (i in 0..65535) {
            legacy.grade(i.toShort(), (i * 373).toShort(), (65535 - i).toShort(), a)
            current.grade(i.toShort(), (i * 373).toShort(), (65535 - i).toShort(), b)
            check(a.contentEquals(b)) { "Neutral mixer changed legacy Float: $i" }
        }
        return "Nine coefficients algebra=$samples samples; swap exhaustive U16; serialization/invalid/future/copy/geometry; independent composite reference max=$maximum; native strips, P3/sRGB previews and TIFF match; 196608 legacy Float samples exact"
    }

    private fun grade(mixer: ChannelMixer, colors: ColorAdjustments = ColorAdjustments.NEUTRAL,
                      curves: ToneCurves = ToneCurves.IDENTITY) = Grade(
        1.03f, .98f, 1f, 1.1f, .08f, -.03f, 1.08f, -.06f, .04f, .01f, -.01f,
        floatArrayOf(.01f, -.01f, 0f), FloatArray(3), floatArrayOf(0f, 0f, .01f),
        0f, .5f, .7f, curves, colors, mixer,
    )

    private fun reference(mixer: ChannelMixer, colors: ColorAdjustments, curves: ToneCurves) = ChannelMixerGradeReference(
        1.03f, .98f, 1f, 1.1f, .08f, -.03f, 1.08f, -.06f, .04f, .01f, -.01f,
        floatArrayOf(.01f, -.01f, 0f), FloatArray(3), floatArrayOf(0f, 0f, .01f),
        0f, .5f, .7f, curves, colors, mixer,
    )
}
