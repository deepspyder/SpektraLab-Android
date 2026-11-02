package com.spektralab.android.engine

/**
 * 一次渲染的结果。对应 C ABI 的 `spk_result`。
 *
 * `rgba16` 是 RGBA 顺序、每通道 16bit 无符号的紧致像素（宽度 × 高度 × 4 个值，
 * 行内已跳过 `rowStridePx` 的对齐填充）。像素处于引擎的工作色彩空间
 * （默认 ProPhoto RGB，CCTF 编码）——显示前必须经过输出变换。
 */
class SpkResult(
    val rgba16: ShortArray,
    val width: Int,
    val height: Int,
    val rowStridePx: Int,
    val elapsedMs: Double,
    val reprint: Boolean,
    val negativeWasCached: Boolean,
    val progressId: String,
) {
    /** rgba16 存的是有符号 short，这里按无符号 16bit 还原成 0..65535。 */
    inline fun channelAt(index: Int): Int = rgba16[index].toInt() and 0xFFFF

    /**
     * 转成 Android 可显示的 ARGB_8888 IntArray（16bit → 8bit）。
     *
     * 当会话以 `output_color_space = "sRGB"` 打开时，rgba16 已是 sRGB 伽马编码，
     * 这里 `>>8` 即为正确显示。若会话输出 ProPhoto（默认工作空间），则需要先经
     * [EngineClient.outputTransform] 做 ProPhoto → sRGB 转换，不能直接用本方法。
     */
    fun toArgb(): IntArray {
        val n = width * height
        val argb = IntArray(n)
        for (i in 0 until n) {
            val r = channelAt(i * 4 + 0) shr 8
            val g = channelAt(i * 4 + 1) shr 8
            val b = channelAt(i * 4 + 2) shr 8
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return argb
    }
}
