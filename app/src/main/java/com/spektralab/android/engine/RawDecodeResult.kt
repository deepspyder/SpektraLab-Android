package com.spektralab.android.engine

/**
 * 一次 RAW 解码的结果：线性 ProPhoto float RGB（0..1，紧凑、行优先、首行为顶）+ 相机元数据。
 * 像素已应用相机/as-shot 白平衡（LibRaw use_camera_wb=1）。
 */
class RawDecodeResult(
    val width: Int,
    val height: Int,
    val linearProPhoto: FloatArray,
    val make: String,
    val model: String,
    val lens: String,         // 镜头型号（EXIF）
    val focal: Float,         // 当前焦距 mm
    val camMul: FloatArray,   // 4：R, G, B, G2 白平衡乘数
    val camXyz: FloatArray,   // 9：XYZ -> camera 矩阵（3x3 行主序）
)
