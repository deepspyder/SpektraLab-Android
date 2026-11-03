package com.spektralab.android.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RAW 解码（LibRaw，JNI）。
 *
 * LibRaw 的 open_file 只接受文件系统路径，而 Android 的照片以 content:// URI 提供，
 * 所以先把 URI 拷到 cacheDir 的临时文件，解码后立即删除。
 * 解码输出线性 ProPhoto float RGB（已应用相机白平衡），以 input_color_space="ProPhoto RGB" 交给引擎。
 */
object RawDecoder {

    private const val DNG_VERSION_TAG = 50706

    init { System.loadLibrary("spektralab") }

    private external fun nativeDecodeRaw(path: String, halfSize: Boolean, userMul: FloatArray?): RawDecodeResult
    private external fun nativeDecodeForExport(path: String, maxLongEdge: Int, userMul: FloatArray?): NativeRawImage

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

    /** Also recognize DNG data mislabeled with .tif/.tiff by inspecting only its TIFF IFD header. */
    fun isRaw(context: Context, uri: Uri, displayName: String?): Boolean {
        if (isRaw(displayName)) return true
        val extension = displayName?.substringAfterLast('.', "")?.lowercase()
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (extension !in setOf("tif", "tiff")) return false
        return try {
            val header = ByteArray(64 * 1024)
            val count = context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0
                while (total < header.size) {
                    val read = input.read(header, total, header.size - total)
                    if (read <= 0) break
                    total += read
                }
                total
            } ?: return false
            if (count < 8) return false
            val order = when {
                header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> return false
            }
            val buffer = ByteBuffer.wrap(header, 0, count).order(order)
            if ((buffer.getShort(2).toInt() and 0xffff) != 42) return false
            val ifd = buffer.getInt(4).toLong() and 0xffff_ffffL
            if (ifd + 2 > count) return false
            val entries = buffer.getShort(ifd.toInt()).toInt() and 0xffff
            if (ifd + 2L + entries * 12L > count) return false
            (0 until entries).any { index ->
                (buffer.getShort((ifd + 2L + index * 12L).toInt()).toInt() and 0xffff) == DNG_VERSION_TAG
            }
        } catch (_: Exception) { false }
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

    fun decodeForExport(context: Context, uri: Uri, maxLongEdge: Int, userMul: FloatArray? = null): NativeRawImage {
        val tmp = File(context.cacheDir, "raw_export_${System.nanoTime()}.raw")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw SpkException("无法读取 RAW 文件")
            input.use { source -> tmp.outputStream().use { target -> source.copyTo(target) } }
            return nativeDecodeForExport(tmp.absolutePath, maxLongEdge, userMul)
        } finally { tmp.delete() }
    }
}
