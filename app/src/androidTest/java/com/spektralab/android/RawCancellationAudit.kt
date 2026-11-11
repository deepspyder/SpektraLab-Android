package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import com.spektralab.android.engine.RawDecodeCancellation
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.SpkException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cancels a real full-resolution camera decode while LibRaw is processing it. */
internal class RawCancellationAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val source = File(output, "perf-z6ii.NEF")
        check(source.isFile)
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (export in listOf(false, true)) {
                val requested = AtomicBoolean(false)
                val entered = CountDownLatch(1)
                val cancellation = RawDecodeCancellation { entered.countDown(); requested.get() }
                val future = executor.submit<Throwable?> {
                    runCatching {
                        if (export) {
                            RawDecoder.decodeForExport(test.targetContext, Uri.fromFile(source), 0,
                                cancellation = cancellation).close()
                        } else {
                            RawDecoder.decode(test.targetContext, Uri.fromFile(source), false,
                                cancellation = cancellation)
                        }
                    }.exceptionOrNull()
                }
                check(entered.await(5, TimeUnit.SECONDS))
                Thread.sleep(200)
                check(!future.isDone) { "Camera fixture finished before the cancellation test" }
                val started = SystemClock.elapsedRealtime()
                requested.set(true)
                val error = future.get(8, TimeUnit.SECONDS)
                check(error is SpkException && error.message.orEmpty().contains("cancel", true)) { "$error" }
                check(SystemClock.elapsedRealtime() - started < 5000) { "LibRaw did not react to cancellation" }
            }
            return "Real full-resolution preview and export decoding stop at LibRaw progress boundaries after cancellation"
        } finally {
            executor.shutdownNow()
        }
    }
}
