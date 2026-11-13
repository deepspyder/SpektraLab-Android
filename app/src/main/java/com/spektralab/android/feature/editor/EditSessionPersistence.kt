package com.spektralab.android.feature.editor

import android.content.Context
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.data.local.EditStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Serialize structural changes off the main thread; a newer pending snapshot supersedes an older one. */
internal class EditSessionPersistence(context: Context, scope: CoroutineScope) {
    private data class Pending(val generation: Long, val photos: List<String>, val current: String?, val selected: Set<String>)
    private val pending = Channel<Pending>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in pending) withContext(Dispatchers.IO) {
                if (latest.get() == request.generation) {
                    try {
                        val session = SavedEditSession(request.photos, request.current, request.selected)
                        EditStore.saveSessionIfCurrent(context, session) { latest.get() == request.generation }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        EditStore.reportSessionFailure(context, error)
                    }
                }
            }
        }
    }

    fun save(photos: List<String>, current: String?, selected: Set<String>) {
        pending.trySend(Pending(latest.incrementAndGet(), photos, current, selected))
    }

    companion object {
        // Shared across activity/ViewModel recreation: a retired writer cannot overwrite a newer queue.
        private val latest = AtomicLong()
    }
}
