package org.teslasoft.assistant.conversation.reply

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One reply's ordered, coalescing persistence owner. Screens never own its retries.
 * [write] checks [wanted] inside the storage transaction, after acquiring its lock. */
internal class ReplyWriteQueue<T>(
    private val scope: CoroutineScope,
    private val retryDelay: Long = 250L,
    private val write: suspend (snapshot: T, wanted: () -> Boolean) -> Result
) {
    enum class Result { SAVED, RETRY, GONE }
    private class Pending<T>(val value: T)
    private val lock = Any()
    private var pending: Pending<T>? = null
    private var running = false
    private var retired = false
    @Volatile var lastFailure: Exception? = null
        private set
    private var drained = CompletableDeferred<Unit>().apply { complete(Unit) }

    fun submit(value: T) {
        val start = synchronized(lock) {
            if (retired) return
            if (pending == null) drained = CompletableDeferred()
            pending = Pending(value)
            if (running) false else { running = true; true }
        }
        if (start) scope.launch { drain() }
    }

    suspend fun awaitSaved() {
        while (true) {
            val wait = synchronized(lock) { if (pending == null || retired) null else drained } ?: return
            wait.await()
        }
    }

    fun retire() {
        synchronized(lock) {
            retired = true
            pending = null
            drained.complete(Unit)
        }
    }

    private suspend fun drain() {
        var backoff = retryDelay
        while (true) {
            val current = synchronized(lock) {
                pending.takeUnless { retired }.also { if (it == null) running = false }
            } ?: return
            val result = try {
                write(current.value) { synchronized(lock) { !retired && pending === current } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                Result.RETRY
            }
            if (result == Result.SAVED) lastFailure = null
            val retry = synchronized(lock) {
                if (result == Result.GONE && pending === current) retired = true
                if (retired || (result == Result.SAVED && pending === current)) {
                    pending = null
                    drained.complete(Unit)
                }
                result == Result.RETRY && pending === current && !retired
            }
            if (retry) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            } else backoff = retryDelay
        }
    }
}
