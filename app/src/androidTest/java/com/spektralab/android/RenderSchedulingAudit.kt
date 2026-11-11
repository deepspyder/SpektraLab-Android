package com.spektralab.android

import com.spektralab.android.engine.EngineTaskQueue
import com.spektralab.android.engine.RenderAdmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal object RenderSchedulingAudit {
    fun run(): String = runBlocking {
        withTimeout(10000) {
            val queue = EngineTaskQueue()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val running = async {
                queue.run("active") {
                    entered.complete(Unit)
                    resume.await()
                    events += "active"
                }
            }
            entered.await()
            val low = async { queue.run("prefetch") { events += "low" } }
            val export = async { queue.run("export") { events += "export" } }
            val first = async { queue.run("preview") { events += "preview1" } }
            val cancelled = async { queue.run("preview") { error("cancelled ticket ran") } }
            val second = async { queue.run("preview") { events += "preview2" } }
            delay(30)
            check(queue.snapshot.value.waiting.size == 5)
            cancelled.cancelAndJoin()
            check(queue.snapshot.value.waiting.size == 4)
            check(events.isEmpty()) { "Priority preempted the active transaction" }
            resume.complete(Unit)
            awaitAll(running, low, export, first, second)
            check(events == listOf("active", "preview1", "preview2", "export", "low")) { events }
            check(
                queue.snapshot.value.let {
                    it.active == null &&
                        it.waiting.isEmpty() &&
                        it.cancelled == 1L &&
                        it.completed == 5L
                }
            )

            events.clear()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = async {
                queue.run("active") {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            val oldest = async { queue.run("prefetch") { events += "oldest" } }
            val urgent =
                (0..11).map { index -> async { queue.run("preview") { events += "p$index" } } }
            delay(30)
            release.complete(Unit)
            owner.await()
            oldest.await()
            urgent.awaitAll()
            check(events.indexOf("oldest") <= 8) { "Low priority starved: $events" }
            runCatching { queue.run("failure") { error("expected") } }
            queue.run("recovery") { check(queue.active?.kind == "recovery") }
            check(queue.snapshot.value.failed == 1L)
            check(RenderAdmission.reason(RenderAdmission.Memory(1_000, 100, false)) == null)
            check(RenderAdmission.reason(RenderAdmission.Memory(100, 100, false)) != null)
            check(RenderAdmission.reason(RenderAdmission.Memory(1_000, 100, true)) != null)
            "Priority/FIFO/anti-starvation; active transaction not preempted; cancelled tickets removed; state counters; resource pressure gate"
        }
    }
}
