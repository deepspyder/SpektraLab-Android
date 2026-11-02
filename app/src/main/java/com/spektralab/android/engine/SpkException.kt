package com.spektralab.android.engine

/**
 * 引擎调用失败。消息来自 `spk_last_error`（JNI 桥接层在失败时抛出）。
 */
class SpkException(message: String) : Exception(message)

data class SpkProgress(val progressId: String, val percent: Float, val stage: String, val done: Boolean, val cancelled: Boolean)
