package com.spektralab.android.engine

/** Called synchronously at LibRaw progress boundaries; implementations must not block. */
fun interface RawDecodeCancellation {
    fun isCancelled(): Boolean
}
