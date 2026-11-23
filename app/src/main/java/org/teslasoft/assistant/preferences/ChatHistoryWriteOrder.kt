/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Orders a chat's whole-history saves when one of them runs off the main
 * thread (a message deletion), using the existing guarded history save.
 *
 * Every save claims a number in the order the chat screen asks for it. A save
 * writes only when its number is newer than the last save that succeeded; the
 * check, the write and the record of success happen together under
 * [ChatPreferences.CHAT_LIST_LOCK], the lock every history write already
 * holds. An older snapshot therefore can never replace a newer conversation.
 *
 * A background save that fails is kept and retried until it, or any newer
 * save, succeeds. While it is kept, a history read first tries it again and,
 * if storage still refuses it, returns the kept snapshot, so a reopened chat
 * never shows messages whose removal was confirmed.
 */
class ChatHistoryWriteOrder internal constructor(
    private val scheduler: ScheduledExecutorService?,
    private val retryDelayMs: (attempt: Int) -> Long = ::defaultRetryDelayMs
) {
    /** Writes one chat's complete history; true when it was saved. */
    fun interface Writer {
        fun write(chatId: String, messages: List<HashMap<String, Any>>, bookmarkChanged: Boolean): Boolean
    }

    private class State {
        var claimed = 0L
        var written = 0L
        var pending: Pending? = null
    }

    private class Pending(
        val seq: Long,
        val messages: List<HashMap<String, Any>>,
        val bookmarkChanged: Boolean,
        val writer: Writer
    ) {
        var attempts = 0
        var scheduled = false
    }

    private val stateLock = Any()
    // A history read made from inside a write on the same thread reads storage
    // as it is instead of starting the kept write again.
    private val writingOnThisThread = ThreadLocal<Boolean>()
    private val states = HashMap<String, State>()

    private fun state(chatId: String): State = states.getOrPut(chatId) { State() }

    /** The next save number for [chatId]. Never blocks on a running write. */
    fun claim(chatId: String): Long = synchronized(stateLock) {
        val s = state(chatId)
        s.claimed += 1
        s.claimed
    }

    /**
     * Runs [write] for save [seq] unless a newer save already succeeded.
     * Returns null when skipped; otherwise the writer's result.
     */
    fun <T> writeIfNewest(chatId: String, seq: Long, succeeded: (T) -> Boolean, write: () -> T): T? =
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val newer = synchronized(stateLock) { state(chatId).written >= seq }
            if (newer) return@synchronized null
            val outer = writingOnThisThread.get() == true
            writingOnThisThread.set(true)
            val result = try { write() } finally { if (!outer) writingOnThisThread.remove() }
            if (succeeded(result)) synchronized(stateLock) {
                val s = state(chatId)
                if (seq > s.written) s.written = seq
                if ((s.pending?.seq ?: Long.MAX_VALUE) <= seq) s.pending = null
            }
            result
        }

    /**
     * Saves [messages] (a snapshot the caller no longer changes) as save [seq]
     * without blocking the caller, retrying until it or a newer save succeeds.
     */
    fun saveInBackground(
        chatId: String,
        seq: Long,
        messages: List<HashMap<String, Any>>,
        bookmarkChanged: Boolean,
        writer: Writer
    ) {
        val pending = Pending(seq, messages, bookmarkChanged, writer)
        synchronized(stateLock) {
            val s = state(chatId)
            if (s.written >= seq) return
            val current = s.pending
            // A newer kept snapshot already includes this one's changes.
            if (current != null && current.seq > seq) return
            // An older kept snapshot keeps its bookmark flag for the replacement.
            s.pending = if (current != null && current.bookmarkChanged && !bookmarkChanged)
                Pending(seq, messages, true, writer) else pending
        }
        schedule(chatId, 0L)
    }

    private fun schedule(chatId: String, delayMs: Long) {
        val executor = scheduler
        synchronized(stateLock) {
            val pending = states[chatId]?.pending ?: return
            if (pending.scheduled) return
            pending.scheduled = true
        }
        if (executor == null) {
            attempt(chatId)
            return
        }
        try {
            executor.schedule({ attempt(chatId) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            synchronized(stateLock) { states[chatId]?.pending?.scheduled = false }
        }
    }

    /** One attempt to write the kept snapshot; reschedules after a failure. */
    private fun attempt(chatId: String) {
        val pending = synchronized(stateLock) {
            states[chatId]?.pending?.also { it.scheduled = false }
        } ?: return
        val saved = tryWrite(chatId, pending)
        if (saved) return
        val delay = synchronized(stateLock) {
            if (states[chatId]?.pending !== pending) return
            pending.attempts += 1
            retryDelayMs(pending.attempts)
        }
        if (scheduler != null) schedule(chatId, delay)
    }

    private fun tryWrite(chatId: String, pending: Pending): Boolean {
        val outcome = try {
            writeIfNewest(chatId, pending.seq, { it }) {
                pending.writer.write(chatId, pending.messages, pending.bookmarkChanged)
            }
        } catch (_: Exception) {
            false
        }
        // Skipped means a newer save already succeeded.
        return outcome ?: true
    }

    /**
     * Called before a history read. Tries a kept snapshot once more; when
     * storage still refuses it, returns a copy of it for the reader to use.
     */
    fun beforeRead(chatId: String): ArrayList<HashMap<String, Any>>? {
        if (writingOnThisThread.get() == true) return null
        val pending = synchronized(stateLock) { states[chatId]?.pending } ?: return null
        if (tryWrite(chatId, pending)) return null
        return synchronized(stateLock) {
            states[chatId]?.pending?.messages?.mapTo(ArrayList()) { HashMap(it) }
        }
    }

    /** True while a snapshot for [chatId] has not been saved yet. */
    fun hasPending(chatId: String): Boolean = synchronized(stateLock) { states[chatId]?.pending != null }

    /** The chats were deleted: nothing kept for them may be written again. */
    fun discard(chatIds: Collection<String>) = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        synchronized(stateLock) { chatIds.forEach { states.remove(it) } }
    }

    /** The whole chat set was replaced; every kept snapshot is obsolete. */
    fun discardAll() = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        synchronized(stateLock) { states.clear() }
    }

    /** A legacy chat moved to a new id; its order and kept snapshot follow it. */
    fun rename(oldId: String, newId: String) = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        if (oldId == newId) return@synchronized
        synchronized(stateLock) {
            states.remove(oldId)?.let { moved ->
                // An attempt queued under the old id finds nothing to write.
                moved.pending?.scheduled = false
                states[newId] = moved
            }
        }
        schedule(newId, 0L)
    }

    /** Test hook: one attempt for [chatId] right now. */
    internal fun attemptNow(chatId: String) = attempt(chatId)

    companion object {
        private const val FIRST_RETRY_MS = 250L
        private const val MAX_RETRY_MS = 30_000L

        internal fun defaultRetryDelayMs(attempt: Int): Long =
            (FIRST_RETRY_MS shl (attempt - 1).coerceIn(0, 7)).coerceAtMost(MAX_RETRY_MS)

        /** A copy the caller can keep changing without affecting the saved
         *  snapshot: rows, and any nested maps or lists, are copied. */
        fun snapshotOf(rows: List<Map<String, Any>>): List<HashMap<String, Any>> =
            rows.map { row -> HashMap<String, Any>(row.size).also { copy -> row.forEach { (k, v) -> copy[k] = copyValue(v) } } }

        private fun copyValue(value: Any): Any = when (value) {
            is Map<*, *> -> LinkedHashMap<Any?, Any?>(value.size).also { copy ->
                value.forEach { (k, v) -> copy[k] = v?.let(::copyValue) }
            }
            is List<*> -> ArrayList<Any?>(value.size).also { copy -> value.forEach { copy.add(it?.let(::copyValue)) } }
            else -> value
        }

        val shared: ChatHistoryWriteOrder by lazy {
            ChatHistoryWriteOrder(
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "chat-history-save").apply { isDaemon = true }
                }
            )
        }

        /** The production writer: the guarded history save, committed. */
        fun historyWriter(context: Context): Writer {
            val app = context.applicationContext
            return Writer { chatId, messages, bookmarkChanged ->
                ChatPreferences.getChatPreferences().saveChatHistory(
                    app, chatId, messages, synchronous = true, bookmarkChanged = bookmarkChanged
                ) == ChatStorageHealth.WriteOutcome.OK
            }
        }
    }
}
