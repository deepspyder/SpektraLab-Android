package com.spektralab.android.core.model

/** Frozen at submission, including decode-side choices and metadata access. */
internal data class ExportOptions(
    val format: String,
    val resolution: Int,
    val colorSpace: String,
    val jpegQuality: Int,
    val keepCapture: Boolean,
    val keepLocation: Boolean,
    val correctLens: Boolean,
) {
    val outputColorSpace: String
        get() = if (format == "TIFF") colorSpace else "sRGB"
}
