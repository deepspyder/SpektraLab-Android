package com.spektralab.android.core.model


/** 一条导出配方：格式 + 长边分辨率 + 色彩空间。 */
data class ExportRecipe(
    val name: String,
    val format: String,      // "JPEG" / "PNG" / "TIFF"
    val res: Int,            // 0 = 原始；其它值为长边像素
    val colorSpace: String,  // "sRGB" / "Display P3"
    val jpegQuality: Int = 95,
    val keepCapture: Boolean = true,
    val keepLocation: Boolean = false,
)
