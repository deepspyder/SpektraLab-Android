package com.spektralab.android.engine

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes complete session transactions, including their suspension points. A single-parallelism
 * dispatcher alone allows another task in when its owner switches to Main/IO. Transactions must not
 * recursively acquire this queue. Progress/cancel remain the client's explicitly thread-safe
 * out-of-band calls.
 */
internal class EngineTaskQueue {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private val mutex = Mutex()
    private val sequence = AtomicLong()

    data class Task(val id: Long, val kind: String)

    @Volatile
    var active: Task? = null
        private set

    suspend fun <T> run(kind: String, block: suspend () -> T): T =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            active = Task(sequence.incrementAndGet(), kind)
            try {
                block()
            } finally {
                active = null
            }
        }

    suspend fun <T> onEngine(kind: String = "preview", block: suspend () -> T): T =
        run(kind) { withContext(dispatcher) { block() } }
}
