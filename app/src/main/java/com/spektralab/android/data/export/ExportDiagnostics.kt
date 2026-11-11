package com.spektralab.android.data.export

import android.os.Debug
import android.os.SystemClock
import android.util.Log

/** Stage timings for device reports; no photo paths, pixels or metadata are logged. */
internal inline fun <T> measureExport(stage: String, operation: () -> T): T {
    val started = SystemClock.elapsedRealtime()
    var completed = false
    try {
        return operation().also { completed = true }
    } finally {
        val runtime = Runtime.getRuntime()
        Log.i("SpkExport", "stage=$stage elapsed_ms=${SystemClock.elapsedRealtime() - started} " +
            "completed=$completed managed_bytes=${runtime.totalMemory() - runtime.freeMemory()} " +
            "native_bytes=${Debug.getNativeHeapAllocatedSize()}")
    }
}
