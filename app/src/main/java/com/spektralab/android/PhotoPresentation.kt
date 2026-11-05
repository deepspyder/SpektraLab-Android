package com.spektralab.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

internal data class SourcePhotoInfo(val name: String, val format: String, val detail: String)

private val rawExtensions = setOf("DNG", "NEF", "NRW", "ARW", "SRF", "SR2", "CR2", "CR3", "CRW", "RAF", "ORF", "RW2", "PEF", "SRW", "RAW", "RWL", "3FR", "IIQ")

/** Source identity is independent of the selected export format. */
internal fun sourcePhotoInfo(name: String, mime: String? = null, raw: Boolean = false): SourcePhotoInfo {
    val extension = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val mediaType = mime?.substringBefore(';')?.lowercase(Locale.ROOT)
    val mimeFormat = when (mediaType) {
        "image/jpeg", "image/jpg" -> "JPG"
        "image/png" -> "PNG"
        "image/tiff", "image/x-tiff" -> "TIFF"
        "image/heic", "image/heic-sequence" -> "HEIC"
        "image/heif", "image/heif-sequence" -> "HEIF"
        "image/webp" -> "WEBP"
        "image/avif" -> "AVIF"
        else -> null
    }
    val format = when {
        raw || extension in rawExtensions || mediaType?.contains("raw") == true || mediaType?.contains("dng") == true -> "RAW"
        mimeFormat != null -> mimeFormat
        extension in setOf("JPG", "JPEG", "JPE") -> "JPG"
        extension in setOf("TIF", "TIFF") -> "TIFF"
        extension in setOf("PNG", "HEIC", "HEIF", "WEBP", "AVIF") -> extension
        else -> "照片"
    }
    return SourcePhotoInfo(name, format, if (format == "RAW" && extension in rawExtensions) "RAW · $extension" else format)
}

@Composable
internal fun rememberSourcePhotoInfo(uri: Uri?, knownName: String? = null, raw: Boolean = false): SourcePhotoInfo {
    val context = LocalContext.current
    val initial = remember(uri, knownName, raw) {
        sourcePhotoInfo(knownName ?: uri?.lastPathSegment ?: "测试图", raw = raw)
    }
    val resolved by produceState(initial, uri, knownName, raw) {
        value = initial
        if (uri != null) value = withContext(Dispatchers.IO) {
            val name = knownName ?: runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
                }
            }.getOrNull() ?: initial.name
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            sourcePhotoInfo(name, mime, raw)
        }
    }
    return resolved
}

private fun cameraNumber(value: String): String {
    val parts = value.split('/')
    val number = if (parts.size == 2) {
        val divisor = parts[1].toDoubleOrNull()
        if (divisor != null && divisor != 0.0) parts[0].toDoubleOrNull()?.div(divisor) else null
    } else value.toDoubleOrNull()
    return when {
        number == null || !number.isFinite() -> value
        number == number.toLong().toDouble() -> number.toLong().toString()
        else -> number.toString()
    }
}

internal fun captureReadouts(info: List<Pair<String, String>>): List<String> {
    val values = info.toMap()
    return buildList {
        values["焦距（毫米）"]?.let { add("${cameraNumber(it)} mm") }
        values["光圈"]?.let { add("f/${cameraNumber(it)}") }
        values["快门时间（秒）"]?.let { value ->
            val seconds = value.toDoubleOrNull()
            add(when {
                '/' in value -> "$value s"
                seconds != null && seconds > 0 && seconds < 1 -> "1/${(1.0 / seconds).roundToInt()} s"
                else -> "${cameraNumber(value)} s"
            })
        }
        values["ISO"]?.let { add("ISO $it") }
    }
}

internal fun captureClock(info: List<Pair<String, String>>): String? = info.firstOrNull { it.first == "拍摄时间" }?.second
    ?.let { Regex("(?: |T)([0-2][0-9]:[0-5][0-9])").find(it)?.groupValues?.get(1) }
