package com.spektralab.android.engine

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore

/** At most one extra display worker app-wide. Bitmap writes stay on the calling worker. */
internal object DisplayStripRenderer {
    private val available = Semaphore(1)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "TheiaDisplayStrip").apply { isDaemon = true }
    }

    fun render(input: ShortArray, width: Int, height: Int, grade: Grade, bitmap: Bitmap,
               checkCancelled: () -> Unit): Histogram {
        val rows = minOf(32, height, (1024 * 1024 / width).coerceAtLeast(1))
        val parallel = Runtime.getRuntime().availableProcessors() > 1 && width.toLong() * height >= 1_000_000 &&
            !grade.isIdentity && available.tryAcquire()
        var pending: Future<*>? = null
        try {
            val pixels = IntArray(width * rows)
            val bins = IntArray(768)
            val nextPixels = if (parallel) IntArray(pixels.size) else null
            val nextBins = if (parallel) IntArray(768) else null
            var y = 0
            while (y < height) {
                checkCancelled()
                val count = minOf(rows, height - y)
                val nextY = y + count
                val nextCount = minOf(rows, height - nextY)
                if (parallel && nextCount > 0) {
                    pending = worker.submit {
                        grade.nativeArgbRows(input, nextY * width * 4, width, height, nextY, nextCount,
                            nextPixels!!, nextBins!!)
                    }
                }
                grade.nativeArgbRows(input, y * width * 4, width, height, y, count, pixels, bins)
                bitmap.setPixels(pixels, 0, width, 0, y, width, count)
                y = nextY
                pending?.let { future ->
                    // Finish the bounded read before scratch buffers can be reused or released.
                    try { future.get() }
                    catch (failure: java.util.concurrent.ExecutionException) { throw failure.cause ?: failure }
                    pending = null
                    checkCancelled()
                    bitmap.setPixels(nextPixels!!, 0, width, 0, nextY, width, nextCount)
                    y += nextCount
                }
            }
            if (nextBins != null) for (i in bins.indices) bins[i] += nextBins[i]
            return Histogram(bins.copyOfRange(0, 256), bins.copyOfRange(256, 512), bins.copyOfRange(512, 768))
        } finally {
            // Cancellation never leaves a worker reading a caller-owned input after return.
            runCatching { pending?.get() }
            if (parallel) available.release()
        }
    }
}
