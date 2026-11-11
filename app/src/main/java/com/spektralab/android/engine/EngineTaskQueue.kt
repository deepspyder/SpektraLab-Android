package com.spektralab.android.engine

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    enum class Priority {
        PREFETCH,
        EXPORT,
        INTERACTIVE,
        CLEANUP,
    }

    data class Snapshot(
        val active: Task? = null,
        val waiting: List<Task> = emptyList(),
        val completed: Long = 0,
        val cancelled: Long = 0,
        val failed: Long = 0,
    )

    private class Ticket(
        val task: Task,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Any()
    private val sequence = AtomicLong()
    private val pending = mutableListOf<Ticket>()
    private val state = MutableStateFlow(Snapshot())
    private var owner: Ticket? = null
    private var priorityStreak = 0
    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    data class Task(val id: Long, val kind: String, val priority: Priority)

    val active: Task?
        get() = state.value.active

    suspend fun <T> run(
        kind: String,
        priority: Priority = priorityFor(kind),
        block: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val ticket = Ticket(Task(sequence.incrementAndGet(), kind, priority))
        synchronized(lock) {
            pending += ticket
            advance()
        }
        var outcome = 0
        try {
            ticket.ready.await()
            currentCoroutineContext().ensureActive()
            return block().also { outcome = 1 }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            outcome = 2
            throw cancelled
        } finally {
            synchronized(lock) {
                pending.remove(ticket)
                if (owner === ticket) owner = null
                state.value =
                    state.value.copy(
                        completed = state.value.completed + if (outcome == 1) 1 else 0,
                        cancelled = state.value.cancelled + if (outcome == 2) 1 else 0,
                        failed = state.value.failed + if (outcome == 0) 1 else 0,
                    )
                advance()
            }
        }
    }

    suspend fun <T> onEngine(
        kind: String = "preview",
        priority: Priority = priorityFor(kind),
        block: suspend () -> T,
    ): T = run(kind, priority) { withContext(dispatcher) { block() } }

    /** Priority changes waiting order only; never preempt an active GPU transaction. */
    private fun advance() {
        var start: Ticket? = null
        if (owner == null && pending.isNotEmpty()) {
            val oldest = pending.minBy { it.task.id }
            val next =
                if (priorityStreak >= 8) oldest
                else
                    pending.maxWith(
                        compareBy<Ticket> { it.task.priority.ordinal }
                            .thenByDescending { it.task.id }
                    )
            priorityStreak = if (next === oldest) 0 else priorityStreak + 1
            pending.remove(next)
            owner = next
            start = next
        }
        state.value =
            state.value.copy(active = owner?.task, waiting = pending.map { it.task }.toList())
        start?.ready?.complete(Unit)
    }

    private companion object {
        fun priorityFor(kind: String) =
            when (kind) {
                "close",
                "release" -> Priority.CLEANUP
                "export",
                "batch-export",
                "export-action" -> Priority.EXPORT
                "thumbnail",
                "prefetch" -> Priority.PREFETCH
                else -> Priority.INTERACTIVE
            }
    }
}
