package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.RawDecodeCancellation
import com.spektralab.android.engine.StockCatalog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Separates file decoding and original display from intentional film/print processing. */
internal class ImageQualityAudit(private val test: Instrumentation, private val output: File) {
    fun originals(): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        val automatic = vm.smartAutoEnabled
        val report = JSONArray()
        val failures = mutableListOf<String>()
        try {
            test.runOnMainSync { vm.smartAutoEnabled = false }
            val swatches = IntArray(384 * 256) { i ->
                val x = i % 384
                if (i / 384 < 128) Color.rgb(x * 255 / 383, x * 255 / 383, x * 255 / 383)
                else listOf(Color.RED, Color.GREEN, Color.BLUE, Color.rgb(230, 140, 60))[x / 96]
            }
            for ((space, format) in listOf(
                ColorSpace.Named.SRGB to Bitmap.CompressFormat.PNG,
                ColorSpace.Named.SRGB to Bitmap.CompressFormat.JPEG,
                ColorSpace.Named.DISPLAY_P3 to Bitmap.CompressFormat.PNG,
                ColorSpace.Named.DISPLAY_P3 to Bitmap.CompressFormat.JPEG,
            )) {
                val file = File(output, "quality-${space.name}-${System.nanoTime()}.${if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"}")
                val image = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888, false, ColorSpace.get(space))
                image.setPixels(swatches, 0, 384, 0, 0, 384, 256)
                if (space == ColorSpace.Named.DISPLAY_P3) image.eraseColor(Color.pack(1f, .2f, .05f, 1f, ColorSpace.get(space)))
                file.outputStream().use { image.compress(format, 100, it) }
                image.recycle()
                val uri = Uri.fromFile(file)
                EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
                val reference = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                test.runOnMainSync { vm.openPhotos(listOf(uri)) }
                await { vm.currentUri == uri && vm.hasPhoto && !vm.busy && vm.rendered != null }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                val original = requireNotNull(vm.sourceBitmap)
                val error = maxError(reference, original)
                report.put(JSONObject().put("file", "${space.name}/$format").put("referenceSpace", reference.colorSpace?.name)
                    .put("actualSpace", original.colorSpace?.name).put("maxSrgbError", error))
                if (error > 1) failures += "Original ${space.name} changed sRGB samples by $error"
                if (space == ColorSpace.Named.DISPLAY_P3 && original.colorSpace != reference.colorSpace)
                    failures += "Original Display P3 was reduced to ${original.colorSpace?.name}"
                if (space == ColorSpace.Named.DISPLAY_P3) {
                    val expectedColor = reference.getColor(128, 192).convert(ColorSpace.get(space))
                    val actualColor = original.getColor(128, 192).convert(ColorSpace.get(space))
                    val nativeError = (0..2).maxOf { abs(expectedColor.components[it] - actualColor.components[it]) }
                    report.put(JSONObject().put("file", "${space.name}/$format").put("maxNativeColorError", nativeError))
                    if (nativeError > .00001f) failures += "Display P3 native components changed by $nativeError"
                }
                reference.recycle()
                file.delete()
            }
            val sourceNef = File(output, "perf-z6ii.NEF")
            check(sourceNef.isFile) { "Real Z6II fixture missing" }
            val nef = File(output, "quality-owned-${System.nanoTime()}.NEF")
            sourceNef.copyTo(nef)
            val uri = Uri.fromFile(nef)
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
            val exif = ExifInterface(nef)
            val embedded = exif.thumbnailBytes
            report.put(JSONObject().put("file", "Z6II").put("embeddedBytes", embedded?.size ?: 0)
                .put("orientation", exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)))
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.currentUri == uri && vm.hasPhoto && !vm.busy && vm.rendered != null }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            requireNotNull(vm.sourceBitmap).let { image ->
                File(output, "quality-raw-original.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                report.put(JSONObject().put("rawSourceWidth", image.width).put("rawSourceHeight", image.height)
                    .put("rawSourceMean", mean(image)))
            }
            // Extracted independently from the NEF TIFF preview IFD, not from production preview code.
            val referenceFile = File(output, "quality-camera-independent.jpg")
            check(referenceFile.isFile) { "Independent NEF camera reference missing" }
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(referenceFile)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = 2048.0 / maxOf(info.size.width, info.size.height)
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }.let { camera ->
                File(output, "quality-raw-camera.png").outputStream().use { camera.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val actual = requireNotNull(vm.sourceBitmap)
                val ratio = mean(actual) / mean(camera)
                val rasterError = maxError(camera, actual)
                report.put(JSONObject().put("cameraWidth", camera.width).put("cameraHeight", camera.height)
                    .put("cameraMean", mean(camera)).put("linearPreviewToCameraMean", ratio).put("maxCameraPixelError", rasterError))
                File(output, "quality-original-report.json").writeText(report.toString(2))
                camera.recycle()
                if (ratio !in .95..1.05) failures += "RAW original differs from camera preview; luma ratio=$ratio"
                if (rasterError > 1) failures += "RAW original differs from independent camera pixels by $rasterError"
            }
            val beforeOriginal = vm.sourceBitmap
            test.runOnMainSync {
                vm.params.cameraWbCustom = true
                vm.params.cameraWbKelvin = 6800f
                vm.scheduleCameraWb()
            }
            await { !vm.busy && vm.renderTaskState.active == null }
            SystemClock.sleep(800)
            await { !vm.busy && vm.renderTaskState.active == null }
            check(vm.sourceBitmap === beforeOriginal) { "White balance changed the original reference" }
            val fallback = File(output, "quality-no-preview-${System.nanoTime()}.dng")
            test.context.assets.open("audit.dng").use { input -> fallback.outputStream().use(input::copyTo) }
            val fallbackUri = Uri.fromFile(fallback)
            val neverCancel = RawDecodeCancellation { false }
            check(RawDecoder.cameraPreview(context, fallbackUri, neverCancel) == null) { "Fallback fixture unexpectedly has a JPEG" }
            val display = requireNotNull(RawDecoder.displayPreview(context, fallbackUri, 256, neverCancel))
            check(maxOf(display.width, display.height) <= 256 && mean(display).isFinite())
            report.put(JSONObject().put("fallback", "LibRaw as-shot sRGB display").put("size", "${display.width}x${display.height}"))
            display.recycle()
            val cancelled = runCatching { RawDecoder.cameraPreview(context, fallbackUri, RawDecodeCancellation { true }) }.exceptionOrNull()
            check(cancelled != null) { "RAW camera reference ignored cancellation" }
            fallback.delete()
            nef.delete()
            File(output, "quality-original-report.json").writeText(report.toString(2))
            check(failures.isEmpty()) { failures.joinToString("; ") }
            return report.toString()
        } finally {
            File(output, "quality-original-report.json").writeText(report.toString(2))
            test.runOnMainSync { vm.smartAutoEnabled = automatic; activity.finish() }
        }
    }

    /** Visit every film and every compatible paper with fixed input; compare raw U16 files across APKs. */
    fun materials(): String {
        val context = test.targetContext
        val (films, papers) = StockCatalog.load(context)
        val pairs = linkedSetOf<Pair<String, String>>()
        for (film in films) {
            val paper = papers.firstOrNull { it.id == film.targetPrint && StockCatalog.canPrint(film.id, it.id) }
                ?: papers.first { StockCatalog.canPrint(film.id, it.id) }
            pairs += film.id to paper.id
        }
        for (paper in papers) pairs += (if (paper.monochrome) "kodak_tri_x_400" else "kodak_portra_400") to paper.id
        val folder = File(output, "quality-materials").apply { mkdirs() }
        val width = 128; val height = 96
        val input = FloatArray(width * height * 3) { i ->
            val pixel = i / 3; val x = pixel % width; val y = pixel / width
            when (i % 3) {
                0 -> .002f + x / (width - 1f) * .95f
                1 -> .002f + y / (height - 1f) * .85f
                else -> .015f + ((x + y) % 37) / 37f * .65f
            }
        }
        val report = JSONArray()
        EngineClient.create(context).use { engine ->
            for ((film, paper) in pairs) {
                val p = EditorParams().apply {
                    filmId = film; paperId = paper
                    grainActive = false; glareActive = false; autoExposure = false
                }
                engine.open(input, width, height, 3, p.openJson(width, height))
                val frame = engine.render("full")
                check(frame.width > 0 && frame.height > 0)
                val bytes = ByteBuffer.allocate(frame.rgba16.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                frame.rgba16.forEach(bytes::putShort)
                File(folder, "$film--$paper.rgba16").writeBytes(bytes.array())
                val bitmap = ColorPipeline.applyGradeToBitmap(frame.rgba16, frame.width, frame.height,
                    com.spektralab.android.engine.Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
                        FloatArray(3), FloatArray(3), FloatArray(3))).first
                try {
                    check(mean(bitmap) > 0.005) { "Empty/dark material output $film/$paper" }
                    File(folder, "$film--$paper.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    report.put(JSONObject().put("film", film).put("paper", paper).put("width", frame.width)
                        .put("height", frame.height).put("mean", mean(bitmap)).put("rgba16Hash", frame.rgba16.contentHashCode()))
                } finally { bitmap.recycle() }
            }
        }
        File(folder, "report.json").writeText(report.toString(2))
        return "${films.size} films, ${papers.size} papers, ${pairs.size} compatible deterministic U16 reference renders; no skipped profile"
    }

    private fun mean(image: Bitmap): Double {
        var sum = 0.0
        var count = 0
        for (y in 0 until image.height step maxOf(1, image.height / 160)) {
            for (x in 0 until image.width step maxOf(1, image.width / 160)) {
                val c = image.getPixel(x, y)
                sum += (.2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c)) / 255
                count++
            }
        }
        return sum / count
    }

    private fun maxError(expected: Bitmap, actual: Bitmap): Int {
        check(expected.width == actual.width && expected.height == actual.height)
        var error = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val a = expected.getPixel(x, y); val b = actual.getPixel(x, y)
            for (shift in listOf(0, 8, 16)) error = maxOf(error, abs((a ushr shift and 255) - (b ushr shift and 255)))
        }
        return error
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 120000) { "Image quality audit timeout" }
            SystemClock.sleep(30)
        }
    }
}
