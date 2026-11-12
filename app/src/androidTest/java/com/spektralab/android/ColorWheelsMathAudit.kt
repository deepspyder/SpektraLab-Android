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

internal object ColorWheelsMathAudit {
    private val mixed = ChannelMixer(MixerRow(25f, -10f, 15f), MixerRow(-15f, 20f, 10f), MixerRow(15f, -20f, -10f))
    private fun examples(): List<ColorWheels> = buildList {
        for (zone in WheelZone.entries) {
            for (hue in listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f)) {
                add(ColorWheels.NEUTRAL.with(zone, WheelAdjustment(hue, 70f, 0f)))
            }
            for (light in listOf(-100f, -20f, 35f, 100f)) add(ColorWheels.NEUTRAL.with(zone, WheelAdjustment(0f, 0f, light)))
        }
        add(ColorWheels(WheelAdjustment(20f, 15f, 12f), WheelAdjustment(220f, 42f, -20f),
            WheelAdjustment(320f, 25f, 8f), WheelAdjustment(55f, 35f, 20f)))
        add(ColorWheels(WheelAdjustment(0f, 100f, -100f), WheelAdjustment(120f, 100f, 100f),
            WheelAdjustment(240f, 100f, -100f), WheelAdjustment(60f, 100f, 100f)))
    }

    fun timings(): String {
        val width = 1024; val height = 768
        val input = ShortArray(width * height * 4) { (it * 373 + 17).toShort() }
        val cases = mutableListOf("neutral" to ColorWheels.NEUTRAL)
        for (zone in WheelZone.entries) {
            cases += "${zone.key}.hue" to ColorWheels.NEUTRAL.with(zone, WheelAdjustment(210f, 30f, 0f))
            cases += "${zone.key}.strength" to ColorWheels.NEUTRAL.with(zone, WheelAdjustment(40f, 60f, 0f))
            cases += "${zone.key}.luminance" to ColorWheels.NEUTRAL.with(zone, WheelAdjustment(0f, 0f, 35f))
        }
        val report = org.json.JSONArray()
        for ((name, wheels) in cases) {
            val g = grade(wheels)
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
                if (hash != null) check(current == hash)
                hash = current
                check(histogram.r.sum() == width * height)
            }
            report.put(JSONObject().put("parameter", name).put("median_ms", samples.sorted()[1])
                .put("samples_ms", org.json.JSONArray(samples)).put("pixel_hash", hash))
        }
        return JSONObject().put("width", width).put("height", height).put("parameters", report).toString()
    }

    fun run(): String {
        val neutral = ColorWheels.NEUTRAL
        check(!EditorParams().toJson().has("color_wheels"))
        check(neutral.isNeutral && neutral.isDefault)
        val untouched = floatArrayOf(-0f, -2f, 1e-32f)
        val bits = untouched.map { it.toRawBits() }
        neutral.apply(untouched, 1f, 0f, 0f)
        check(untouched.map { it.toRawBits() } == bits)
        val angleOnly = neutral.with(WheelZone.SHADOWS, WheelAdjustment(215f))
        check(angleOnly.isNeutral && !angleOnly.isDefault)
        check(EditorParams.fromJson(EditorParams().apply { colorWheels = angleOnly }.toJson()).colorWheels == angleOnly)
        var samples = 0
        for (wheels in examples()) {
            check(ColorWheels.fromJson(wheels.toJson()) == wheels)
            val params = EditorParams().apply { colorWheels = wheels }
            check(params.copy().colorWheels == wheels)
            check(EditSnapshot.capture(params).toParams().colorWheels == wheels)
            val target = EditorParams().apply { cropX = .2f; cropW = .6f }
            target.copySettingsFrom(params)
            check(target.colorWheels == wheels && target.cropX == .2f && target.cropW == .6f)
            for (i in 0..257) {
                val original = floatArrayOf(i / 257f, .31f, .83f)
                val actual = original.clone()
                val ls = i / 257f; val ws = (1f-ls)*(1f-ls); val wh = ls*ls; val wm = maxOf(1f-ws-wh,0f)
                wheels.apply(actual, ws, wm, wh)
                val weights = listOf(1.0, ws.toDouble(), wm.toDouble(), wh.toDouble())
                for (channel in 0..2) {
                    var offset = 0.0; var lift = 0.0
                    WheelZone.entries.forEachIndexed { index, zone ->
                        val v = wheels[zone]
                        val phase = when(channel) { 1 -> -2*Math.PI/3; 2 -> 2*Math.PI/3; else -> 0.0 }
                        offset += kotlin.math.cos(v.hue*Math.PI/180+phase)*v.strength/100*.25*weights[index]
                        lift += v.luminance/100.0*.25*weights[index]
                    }
                    check(abs(actual[channel] - ((original[channel] + offset)*(1+lift)).toFloat()) < 4e-7f)
                    samples++
                }
            }
        }
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 361f)) check(runCatching { WheelAdjustment(hue=v) }.isFailure)
        check(runCatching { WheelAdjustment(strength=101f) }.isFailure)
        check(runCatching { WheelAdjustment(luminance= -101f) }.isFailure)
        for (raw in listOf("{\"version\":99,\"keep\":true}", "[]", "\"invalid\"", "null",
            neutral.toJson().put("master", JSONObject().put("hue", "20").put("strength", 0).put("luminance", 0)).toString())) {
            val value = JSONTokener(raw).nextValue()
            val loaded = EditorParams.fromJson(JSONObject().put("color_wheels", value))
            check(loaded.unreadableColorWheels != null && loaded.colorWheels.isNeutral)
            check(loaded.copy().toJson().get("color_wheels").toString() == value.toString())
        }
        val colors = ColorAdjustments.NEUTRAL.withVibrance(24f).with(HslColor.BLUE, HslBand(28f, -17f, 13f))
        val curves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .61f))
        val input = ShortArray(97 * 39 * 4) { (it * 373 + 17).toShort() }
        // Both pre-wheel uniform layouts must retain their exact native output.
        val legacyUniforms = floatArrayOf(1.03f, .98f, 1f, Math.pow(1.1f.toDouble(), 1.0 / 2.2).toFloat(),
            .08f, -.03f, 1.08f, -.06f, .04f, .01f, -.01f, .01f, -.01f, 0f, 0f, 0f, 0f, 0f, 0f, .01f,
            0f, .5f, .7f, 0f, 0f)
        val expectedLegacy = FloatArray(97 * 32 * 3)
        grade(neutral, colors).nativeRows(input, 0, 97, 39, 0, 32, expectedLegacy)
        for (uniforms in listOf(colors.uniforms(), colors.uniforms() + ChannelMixer.IDENTITY.uniforms())) {
            val actualLegacy = FloatArray(expectedLegacy.size)
            NativeCurveGrade.apply(input, 0, 97, 39, 0, 32, legacyUniforms, ToneCurves.IDENTITY.table(),
                uniforms, null, null, actualLegacy)
            check(actualLegacy.contentEquals(expectedLegacy)) { "Legacy native layout changed: ${uniforms.size}" }
        }
        var maximum = 0f
        for (wheels in examples()) for (post in listOf(false, true)) {
            val cs = if (post) colors else ColorAdjustments.NEUTRAL
            val tc = if (post) curves else ToneCurves.IDENTITY
            val grade = grade(wheels, cs, tc)
            val reference = reference(wheels, cs, tc)
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
            check(maximum < 4e-6f) { "Wheels native/reference error: $maximum" }
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
            check(a.contentEquals(b)) { "Neutral wheels changed legacy Float: $i" }
        }
        return "Four-zone algebra=$samples samples; all 12 controls and extrema; legacy 26/36 native layouts exact; serialization/invalid/future/copy/geometry; independent composite reference max=$maximum; native strips, P3/sRGB previews and TIFF match; 196608 legacy Float samples exact"
    }

    private fun grade(wheels: ColorWheels, colors: ColorAdjustments = ColorAdjustments.NEUTRAL,
                      curves: ToneCurves = ToneCurves.IDENTITY) = Grade(
        1.03f, .98f, 1f, 1.1f, .08f, -.03f, 1.08f, -.06f, .04f, .01f, -.01f,
        floatArrayOf(.01f, -.01f, 0f), FloatArray(3), floatArrayOf(0f, 0f, .01f),
        0f, .5f, .7f, curves, colors, if(wheels.isNeutral) ChannelMixer.IDENTITY else mixed, wheels,
    )

    private fun reference(wheels: ColorWheels, colors: ColorAdjustments, curves: ToneCurves) = ColorWheelsGradeReference(
        1.03f, .98f, 1f, 1.1f, .08f, -.03f, 1.08f, -.06f, .04f, .01f, -.01f,
        floatArrayOf(.01f, -.01f, 0f), FloatArray(3), floatArrayOf(0f, 0f, .01f),
        0f, .5f, .7f, curves, colors, if(wheels.isNeutral) ChannelMixer.IDENTITY else mixed, wheels,
    )
}
