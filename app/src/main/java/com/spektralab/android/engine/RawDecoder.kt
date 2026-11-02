package com.spektralab.android.engine

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * RAW 解码（LibRaw，JNI）。
 *
 * LibRaw 的 open_file 只接受文件系统路径，而 Android 的照片以 content:// URI 提供，
 * 所以先把 URI 拷到 cacheDir 的临时文件，解码后立即删除。
 * 解码输出线性 ProPhoto float RGB（已应用相机白平衡），以 input_color_space="ProPhoto RGB" 交给引擎。
 */
object RawDecoder {

    init { System.loadLibrary("spektralab") }

    private external fun nativeDecodeRaw(path: String, halfSize: Boolean, userMul: FloatArray?): RawDecodeResult

    private external fun nativeApplyLensCorrection(
        rgb: FloatArray, width: Int, height: Int,
        distModel: String, terms: FloatArray, vigK: FloatArray, tcaVr: Float, tcaVb: Float,
    ): Boolean

    /** 在已解码的线性 ProPhoto float RGB 上就地做镜头校正（畸变 + 暗角 + 简易 TCA）。 */
    fun applyLensCorrection(rgb: FloatArray, width: Int, height: Int, profile: LensProfile): Boolean =
        nativeApplyLensCorrection(
            rgb, width, height,
            profile.distModel, profile.distTerms, profile.vigK, profile.tcaVr, profile.tcaVb,
        )

    private val RAW_EXTENSIONS = setOf(
        "cr2", "cr3", "nef", "nrw", "arw", "dng", "raf", "orf", "rw2", "pef",
        "srw", "rwl", "3fr", "erf", "mef", "mos", "raw", "x3f", "kdc", "dcr", "sr2",
    )

    /** 按文件名（小写）判断是否是受支持的 RAW。 */
    fun isRaw(displayName: String?): Boolean {
        if (displayName == null) return false
        val dot = displayName.lastIndexOf('.')
        if (dot < 0) return false
        return displayName.substring(dot + 1).lowercase() in RAW_EXTENSIONS
    }

    /** 解码一个 content URI 指向的 RAW。[halfSize] 用半分辨率（预览省内存）。
     *  [userMul] 非空时用它替代相机 as-shot 白平衡（R, G, B, G2）。失败抛 [SpkException]。 */
    fun decode(context: Context, uri: Uri, halfSize: Boolean, userMul: FloatArray? = null): RawDecodeResult {
        val tmp = File(context.cacheDir, "raw_input_${System.nanoTime()}.raw")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw SpkException("无法读取 RAW 文件")
            input.use { source -> tmp.outputStream().use { target -> source.copyTo(target) } }
            return nativeDecodeRaw(tmp.absolutePath, halfSize, userMul)
        } finally {
            tmp.delete()
        }
    }
}
