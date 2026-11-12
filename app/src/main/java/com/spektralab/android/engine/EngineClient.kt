package com.spektralab.android.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONObject

/**
 * 引擎客户端：Kotlin 侧对 `spk_engine.h` C ABI 的封装。
 *
 * 原生指针以 [Long] 在 JNI 两侧传递（`reinterpret_cast` 回 `spk_engine*` /
 * `spk_session*`）。所有 native 方法都声明为 `private external`，由
 * `jni_bridge.cpp` 实现。
 *
 * 调用约定：
 * - 会失败的调用抛 [SpkException]（消息来自 `spk_last_error`）；
 * - 渲染返回 [SpkResult]，调用方用完即弃（像素已拷贝进 `ShortArray`）。
 */
class EngineClient private constructor(
    private var enginePtr: Long,
) : Closeable {

    private var sessionPtr: Long = 0L
    // cancel() is the only JNI session call made from outside the serialized engine dispatcher.
    private val sessionLifetime = ReentrantReadWriteLock(true)

    // ---- 引擎自省 ---------------------------------------------------------

    fun capabilities(): String = nativeCapabilities(enginePtr)
    fun paramsSchema(): String = nativeParamsSchema(enginePtr)
    fun memoryReport(): String = nativeMemoryReport(enginePtr)
    fun printLutCatalog(): String = nativePrintLutCatalog(enginePtr)
    fun lastError(): String = nativeLastError(enginePtr)

    fun warmUp(filmStock: String, printStock: String): String =
        nativeWarmUp(enginePtr, filmStock, printStock)

    // ---- 会话 -------------------------------------------------------------

    /**
     * 打开一帧。像素是线性 float（ProPhoto RGB），紧凑排列，行优先、首行为顶。
     * 已有会话时自动释放。
     */
    fun open(data: FloatArray, width: Int, height: Int, channels: Int, paramsJson: String? = null): Long {
        check(enginePtr != 0L) { "engine not created" }
        closeSession()
        val opened = nativeOpen(enginePtr, data, width, height, channels, paramsJson)
        sessionLifetime.write { sessionPtr = opened }
        return sessionPtr
    }

    fun openBitmap(bitmap: Bitmap, paramsJson: String): Long {
        check(enginePtr != 0L)
        closeSession()
        val opened = nativeOpenBitmap(enginePtr, bitmap, paramsJson)
        sessionLifetime.write { sessionPtr = opened }
        return sessionPtr
    }

    fun openRaw(image: NativeRawImage, paramsJson: String): Long {
        check(enginePtr != 0L && image.handle != 0L)
        closeSession()
        val opened = nativeOpenRaw(enginePtr, image.handle, paramsJson)
        sessionLifetime.write { sessionPtr = opened }
        return sessionPtr
    }

    fun renderForExport(): NativeRenderResult = requireSession { nativeRenderForExport(enginePtr, sessionPtr) }

    fun closeSession() {
        sessionLifetime.write {
            releaseSessionLocked()
        }
    }

    private fun releaseSessionLocked() {
        val session = sessionPtr
        if (session != 0L) {
            sessionPtr = 0L
            nativeSessionRelease(session)
        }
    }

    fun setParams(deltaJson: String): String =
        requireSession { nativeSetParams(enginePtr, sessionPtr, deltaJson) }

    /** Apply a UI snapshot without making unchanged capture/material fields invalidate the negative.
     * Read native state inside the session transaction, so solve/open/export restores cannot leave
     * a stale host-side parameter cache. Low-level setParams keeps its explicit delta semantics.
     */
    fun setChangedParams(snapshotJson: String): String = requireSession {
        val requested = runCatching { JSONObject(snapshotJson) }.getOrNull()
            ?: return@requireSession nativeSetParams(enginePtr, sessionPtr, snapshotJson)
        val current = JSONObject(nativeGetParams(enginePtr, sessionPtr))
        val changed = JSONObject()
        requested.keys().forEach { key ->
            val value = requested.get(key)
            val previous = current.opt(key)
            val equal = when {
                value is Number && previous is Number -> value.toDouble() == previous.toDouble()
                value is String && previous is String -> value == previous
                value is Boolean && previous is Boolean -> value == previous
                else -> false // Preserve validation of unknown, null or incorrectly typed fields.
            }
            if (!equal) changed.put(key, value)
        }
        nativeSetParams(enginePtr, sessionPtr, changed.toString())
    }

    fun getParams(): String =
        requireSession { nativeGetParams(enginePtr, sessionPtr) }

    /** [target] 为 "exposure"、"filter_pack" 或 "both"。 */
    fun solve(target: String): String =
        requireSession { nativeSolve(enginePtr, sessionPtr, target) }

    fun sceneLatitude(requestJson: String? = null): String =
        requireSession { nativeSceneLatitude(enginePtr, sessionPtr, requestJson) }

    fun overscanGeometry(): String =
        requireSession { nativeOverscanGeometry(enginePtr, sessionPtr) }

    // ---- 渲染 -------------------------------------------------------------

    /** 从缓存负片重印（打印侧改动走这条路，交互滑杆的快路径）。 */
    fun render(tier: String): SpkResult =
        requireSession { nativeRender(enginePtr, sessionPtr, tier) }

    /** 全量渲染（拍摄侧改动走这条路，重建负片）。 */
    fun renderFull(tier: String): SpkResult =
        requireSession { nativeRenderFull(enginePtr, sessionPtr, tier) }

    fun previewStockLut(printStock: String, tier: String): SpkResult =
        requireSession { nativePreviewStockLut(enginePtr, sessionPtr, printStock, tier) }

    fun exportDi(printStock: String? = null): SpkResult =
        requireSession { nativeExportDi(enginePtr, sessionPtr, printStock) }

    fun renderDigitalIntermediate(): NativeRenderResult =
        requireSession { nativeRenderDigitalIntermediate(enginePtr, sessionPtr) }

    // ---- 控制 / 色彩 ------------------------------------------------------

    /**
     * Cancel may run across threads, but it must not race the native session destructor.
     * The read lock lets it run beside an active render; closeSession/close take the write lock.
     */
    fun cancel(progressId: String? = null) = sessionLifetime.read {
        val session = sessionPtr
        if (session != 0L) nativeCancel(session, progressId)
    }

    /** 读取当前（或指定 [progressId]）渲染的进度。返回 JSON（含 pct/stage）。 */
    fun progress(progressId: String? = null): String = sessionLifetime.read {
        check(enginePtr != 0L) { "engine not created" }
        nativeProgress(enginePtr, sessionPtr, progressId)
    }

    fun progressSnapshot(): SpkProgress {
        val reply = org.json.JSONObject(progress())
        return SpkProgress(reply.optString("progress_id"), reply.optDouble("pct", 0.0).toFloat().coerceIn(0f, 100f),
            reply.optString("stage", "rendering"), reply.optBoolean("done", false), reply.optBoolean("cancelled", false))
    }

    /** 取工作空间 → 目标空间的输出变换（CAM16 色域映射 + TRC），用于导出与显示。 */
    fun outputTransform(srcCs: String, dstCs: String, gamutCompressJson: String? = null): String =
        nativeOutputTransform(enginePtr, srcCs, dstCs, gamutCompressJson)

    // ---- 生命周期 ---------------------------------------------------------

    override fun close() {
        sessionLifetime.write {
            releaseSessionLocked()
            val engine = enginePtr
            if (engine != 0L) {
                enginePtr = 0L
                nativeEngineDestroy(engine)
            }
        }
    }

    private inline fun <T> requireSession(block: () -> T): T {
        check(enginePtr != 0L) { "engine not created" }
        check(sessionPtr != 0L) { "no session open" }
        return block()
    }

    // ---- native 声明（实例方法，JNI 第二参是 jobject） ---------------------

    private external fun nativeCapabilities(enginePtr: Long): String
    private external fun nativeParamsSchema(enginePtr: Long): String
    private external fun nativeMemoryReport(enginePtr: Long): String
    private external fun nativePrintLutCatalog(enginePtr: Long): String
    private external fun nativeLastError(enginePtr: Long): String
    private external fun nativeWarmUp(enginePtr: Long, filmStock: String, printStock: String): String
    private external fun nativeEngineDestroy(enginePtr: Long)
    private external fun nativeOpen(
        enginePtr: Long,
        data: FloatArray,
        width: Int,
        height: Int,
        channels: Int,
        paramsJson: String?,
    ): Long
    private external fun nativeSessionRelease(sessionPtr: Long)
    private external fun nativeOpenBitmap(enginePtr: Long, bitmap: Bitmap, paramsJson: String): Long
    private external fun nativeOpenRaw(enginePtr: Long, pixels: Long, paramsJson: String): Long
    private external fun nativeRenderForExport(enginePtr: Long, sessionPtr: Long): NativeRenderResult
    private external fun nativeSetParams(enginePtr: Long, sessionPtr: Long, deltaJson: String): String
    private external fun nativeGetParams(enginePtr: Long, sessionPtr: Long): String
    private external fun nativeSolve(enginePtr: Long, sessionPtr: Long, target: String): String
    private external fun nativeSceneLatitude(enginePtr: Long, sessionPtr: Long, requestJson: String?): String
    private external fun nativeOverscanGeometry(enginePtr: Long, sessionPtr: Long): String
    private external fun nativeRender(enginePtr: Long, sessionPtr: Long, tier: String): SpkResult
    private external fun nativeRenderFull(enginePtr: Long, sessionPtr: Long, tier: String): SpkResult
    private external fun nativePreviewStockLut(enginePtr: Long, sessionPtr: Long, printStock: String, tier: String): SpkResult
    private external fun nativeExportDi(enginePtr: Long, sessionPtr: Long, printStock: String?): SpkResult
    private external fun nativeRenderDigitalIntermediate(enginePtr: Long, sessionPtr: Long): NativeRenderResult
    private external fun nativeCancel(sessionPtr: Long, progressId: String?)
    private external fun nativeProgress(enginePtr: Long, sessionPtr: Long, progressId: String?): String
    private external fun nativeOutputTransform(
        enginePtr: Long,
        srcCs: String,
        dstCs: String,
        gamutCompressJson: String?,
    ): String

    companion object {
        private const val TAG = "EngineClient"

        init {
            System.loadLibrary("spektralab")
        }

        // ---- native 声明（静态方法，JNI 第二参是 jclass） -----------------
        // @JvmStatic 让它们成为 EngineClient 的静态方法，JNI 符号才会是
        // Java_..._EngineClient_nativeEngineCreate（否则会变成 ..._EngineClient$Companion_...）。

        @JvmStatic
        private external fun nativeEngineCreate(resourcesDir: String): Long

        @JvmStatic
        private external fun nativeBuildInfo(): String

        fun buildInfo(): String = nativeBuildInfo()

        /**
         * 创建引擎。先把 `assets/engine/` 解压到 `filesDir/engine/`（引擎需要
         * 文件系统路径），再把该目录传给 `spk_engine_create`。
         */
        fun create(context: Context): EngineClient {
            val dir = prepareResources(context)
            val ptr = nativeEngineCreate(dir)
            check(ptr != 0L) { "spk_engine_create failed" }
            Log.i(TAG, "engine created, resources=$dir")
            return EngineClient(ptr)
        }

        private fun prepareResources(context: Context): String {
            val target = File(context.filesDir, "engine").apply { mkdirs() }
            copyAssets(context.assets, "engine", target)
            // 引擎的 resources_dir 直接包含 profiles/、spektrafilm_constants.bin、vulkan/ 等，
            // 对应 assets/engine/resources/ 这一层。
            return File(target, "resources").absolutePath
        }

        private fun copyAssets(assets: AssetManager, srcPath: String, dest: File) {
            val children = assets.list(srcPath) ?: return
            for (name in children) {
                val childSrc = if (srcPath.isEmpty()) name else "$srcPath/$name"
                val childDest = File(dest, name)
                if (assets.list(childSrc).isNullOrEmpty()) {
                    // 是文件
                    assets.open(childSrc).use { input ->
                        childDest.parentFile?.mkdirs()
                        childDest.outputStream().use { output -> input.copyTo(output) }
                    }
                } else {
                    copyAssets(assets, childSrc, childDest)
                }
            }
        }
    }
}
