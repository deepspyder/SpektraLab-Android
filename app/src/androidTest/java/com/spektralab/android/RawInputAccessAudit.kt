package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import androidx.core.content.FileProvider
import com.spektralab.android.engine.RawDecoder
import java.io.File

/** Local, seekable content and non-seekable provider inputs must produce identical decoded samples. */
internal class RawInputAccessAudit(private val test: Instrumentation) {
    fun run(): String {
        val context = test.targetContext
        val directory = File(context.cacheDir, "lighttable").apply { mkdirs() }
        val file = File.createTempFile("raw-access-", ".dng", directory)
        try {
            test.context.assets.open("audit.dng").use { source -> file.outputStream().use { source.copyTo(it) } }
            val fileUri = Uri.fromFile(file)
            val descriptorUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val pipeUri = Uri.parse("content://com.spektralab.android.test.raw-pipe/audit.dng")
            val expected = RawDecoder.decode(context, fileUri, halfSize = false)
            val matrix = RawDecoder.readCameraMatrix(context, fileUri)
            check(matrix == null || matrix.contentEquals(expected.camXyz))
            for (uri in listOf(descriptorUri, pipeUri)) {
                val actual = RawDecoder.decode(context, uri, halfSize = false)
                check(actual.width == expected.width && actual.height == expected.height)
                check(actual.linearProPhoto.contentEquals(expected.linearProPhoto)) { "RAW access path changed pixels" }
                check(actual.camXyz.contentEquals(expected.camXyz))
            }
            val exportReference = RawDecoder.decodeForExport(context, fileUri, 128).use { it.copySmallLinearRgb() }
            for (uri in listOf(descriptorUri, pipeUri)) {
                RawDecoder.decodeForExport(context, uri, 128).use {
                    check(it.copySmallLinearRgb().contentEquals(exportReference))
                }
            }
            check(context.cacheDir.listFiles().orEmpty().none {
                it.name.startsWith("raw_input_") || it.name.startsWith("raw_export_")
            }) { "Fallback RAW staging file leaked" }
            return "File, seekable content descriptor and non-seekable pipe produce exact preview/export pixels; staging cleanup passed"
        } finally {
            file.delete()
        }
    }
}
