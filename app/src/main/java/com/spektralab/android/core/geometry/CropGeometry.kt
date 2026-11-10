package com.spektralab.android.core.geometry


/**
 * 按目标宽高比计算源图内的**居中**裁剪矩形，返回 [x, y, w, h]（归一化，左上原点）。
 * "原始" 返回全图。供「裁剪预设」下拉使用；自由拖拽则直接改 cropX/Y/W/H。
 */
fun computeCenteredCrop(aspect: String, srcW: Int, srcH: Int): FloatArray {
    if (aspect == "原始" || srcW <= 0 || srcH <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
    val target = when (aspect) {
        "1:1" -> 1f
        "4:3" -> 4f / 3f
        "3:2" -> 3f / 2f
        "16:9" -> 16f / 9f
        "2.39:1" -> 2.39f
        "1.85:1" -> 1.85f
        else -> return floatArrayOf(0f, 0f, 1f, 1f)
    }
    return computeCenteredCropByAspect(target, srcW, srcH)
}

internal fun cropAspectRatio(aspect: String): Float? = when (aspect) {
    "1:1" -> 1f
    "4:3" -> 4f / 3f
    "3:2" -> 3f / 2f
    "16:9" -> 16f / 9f
    "2.39:1" -> 2.39f
    "1.85:1" -> 1.85f
    else -> null
}

/** 按数值宽高比计算居中裁剪（归一化）。 */
fun computeCenteredCropByAspect(target: Float, srcW: Int, srcH: Int): FloatArray {
    if (srcW <= 0 || srcH <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
    val srcAspect = srcW.toFloat() / srcH.toFloat()
    return if (srcAspect > target) {
        // 源更宽 → 裁掉左右
        val w = (target / srcAspect).coerceAtMost(1f)
        floatArrayOf((1f - w) / 2f, 0f, w, 1f)
    } else {
        // 源更高 → 裁掉上下
        val h = (srcAspect / target).coerceAtMost(1f)
        floatArrayOf(0f, (1f - h) / 2f, 1f, h)
    }
}

/** 片边画幅的门径宽高比（横持基准；竖图由重载函数取倒数）。 */
/**
 * Gate ratio in the source image's orientation.
 *
 * Windows treats a film gate as long-edge/short-edge, then rotates the
 * rendered canvas when the source frame is upright. Android used to always
 * return the landscape ratio here, which made a portrait source crop to a
 * landscape gate before the engine ever saw it. Keep the crop in source
 * coordinates: a portrait source gets the reciprocal ratio, while the native
 * engine receives the resulting portrait frame and lays the film vertically.
 */
fun overscanGateAspect(format: String, srcW: Int, srcH: Int): Float {
    val landscapeGate = overscanGateAspect(format)
    return if (srcW > 0 && srcH > srcW) 1f / landscapeGate else landscapeGate
}

fun overscanGateAspect(format: String): Float = when (format) {
    "135" -> 36.25f / 24.3f
    "135_half" -> 24f / 18f
    "135_xpan" -> 65f / 24f
    "120_645" -> 56f / 41.5f
    "120_6x6" -> 1f         // 56×56
    "120_6x7" -> 69.5f / 56f
    "120_6x8" -> 76f / 56f
    "120_6x9" -> 84f / 56f
    "120_6x12" -> 112f / 56f
    "120_6x17" -> 168f / 56f
    else -> 36.25f / 24.3f
}
