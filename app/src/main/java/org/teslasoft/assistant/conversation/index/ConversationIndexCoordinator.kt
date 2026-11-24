/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the index's background tasks one at a time, in the order they were handed in. */
fun interface IndexTaskRunner {
    fun run(delayMillis: Long, task: () -> Unit)

    companion object {
        fun on(executor: ScheduledExecutorService) = IndexTaskRunner { delay, task ->
            executor.schedule({ task() }, delay, TimeUnit.MILLISECONDS)
        }
    }
}

/**
 * Keeps the conversation index in step with the chats (memory development
 * plan G.12, Step 10). The hookups only report what happened; everything
 * here runs on one background thread, a chat at a time, so no chat operation
 * waits for the index (G.5a rule 10).
 *
 *  - A changed chat is compared with the index message by message by the
 *    updater (G.5a rule 4), after a short wait that gathers a burst of saves.
 *  - Existing chats are caught up in the background, newest first, a chat at
 *    a time, after any reported change (G.12, "Existing chats, first time").
 *    A chat whose index already describes the chat as stored now, under the
 *    current cutting settings and model, is not touched again.
 *  - Messages without permanent IDs are not indexed; a chat not opened
 *    since IDs were introduced has no passages until it is opened (G.5a rule 7).
 *  - After updates, a vector pass is requested (Step 9).
 *
 * An index failure is never a chat error: a chat that cannot be read or
 * updated is left marked for a refresh and tried again on the next change
 * or the next start.
 */
class ConversationIndexCoordinator(
    private val source: IndexChatSource,
    /** Opened on the background thread only. */
    private val storage: () -> ConversationIndexStorage,
    private val maintenance: () -> ConversationIndexMaintenance,
    /** The updater for the installed model, or null when no model is installed. */
    private val updaterFor: () -> ConversationIndexUpdater?,
    private val requestVectors: () -> Unit,
    private val runner: IndexTaskRunner,
    private val isOn: () -> Boolean = { true },
    private val changeWaitMillis: Long = CHANGE_WAIT_MILLIS,
    private val catchUpPauseMillis: Long = CATCH_UP_PAUSE_MILLIS,
    /** Milliseconds on a clock that only moves forward. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val lock = Any()
    /** Each changed chat, with when it last changed. */
    private val changed = LinkedHashMap<String, Long>()
    private val catchUp = ArrayDeque<String>()
    private val staleRetries = HashMap<String, Int>()
    private val drainScheduled = AtomicBoolean(false)

    /** The chat was saved: a new message, a finished reply, an edit, a deletion, hiding or showing, another version, or IDs given on opening. */
    fun chatChanged(chatId: String) {
        if (chatId.isBlank()) return
        synchronized(lock) {
            changed.remove(chatId)
            changed[chatId] = clock()
        }
        scheduleDrain(changeWaitMillis)
    }

    /** The chats were deleted: everything derived from them goes (G.12). */
    fun chatsDeleted(chatIds: Collection<String>) {
        val ids = chatIds.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return
        synchronized(lock) {
            ids.forEach { changed.remove(it) }
            catchUp.removeAll(ids)
        }
        runner.run(0) { guarded { ids.forEach { maintenance().deleteChat(it) } } }
    }

    /** A rename changed the chat's ID: its entries move to the new ID and are checked again (G.5a rule 9). */
    fun chatMoved(fromChatId: String, toChatId: String) {
        if (fromChatId.isBlank() || toChatId.isBlank() || fromChatId == toChatId) return
        synchronized(lock) {
            changed.remove(fromChatId)
            catchUp.remove(fromChatId)
        }
        runner.run(0) {
            val moved = guarded { maintenance().moveChat(fromChatId, toChatId) }
            if (!moved) guarded { maintenance().deleteChat(fromChatId) }
            chatChanged(toChatId)
        }
    }

    /**
     * A backup was restored or merged into the chats: every chat is checked
     * message by message (G.5a rule 8), and entries of chats that are gone
     * are removed.
     */
    fun chatSetReplaced() {
        runner.run(0) {
            guarded { maintenance().markAllForRefresh() }
            catchUpAll()
        }
    }

    /** The embedding model changed: every chat is re-cut and its vectors remade in the background. */
    fun embeddingModelChanged() {
        runner.run(0) { catchUpAll() }
    }

    /** The app started: existing chats are caught up in the background, newest first. */
    fun appStarted() {
        runner.run(0) { catchUpAll() }
    }

    /** The user deletes the index (G.12): everything derived is removed; the chats are untouched. */
    fun deleteIndex() {
        synchronized(lock) {
            changed.clear()
            catchUp.clear()
            staleRetries.clear()
        }
        runner.run(0) { guarded { maintenance().deleteEverything() } }
    }

    private fun catchUpAll() {
        if (!isOn()) return
        val chats = try { source.chatIdsNewestFirst() } catch (_: Exception) { null } ?: return
        val live = chats.toSet()
        val gone = try { maintenance().indexedChatIds() - live } catch (_: Exception) { emptySet() }
        gone.forEach { guarded { maintenance().deleteChat(it) } }
        synchronized(lock) {
            catchUp.clear()
            catchUp.addAll(chats)
        }
        scheduleDrain(0)
    }

    private fun scheduleDrain(delayMillis: Long) {
        if (drainScheduled.compareAndSet(false, true)) runner.run(delayMillis) { drain() }
    }

    /**
     * One chat per task, so other tasks (a deletion, a rename) are never held
     * up behind a long catch-up. A changed chat waits until it has had no
     * change for [changeWaitMillis], so a reply that is saved every few
     * seconds while it streams is compared once, after it finishes.
     */
    private fun drain() {
        drainScheduled.set(false)
        if (!isOn()) return
        val now = clock()
        var wait: Long? = null
        val next = synchronized(lock) {
            val ready = changed.entries.firstOrNull { now - it.value >= changeWaitMillis }?.key
            if (ready != null) {
                changed.remove(ready)
                ready to false
            } else {
                wait = changed.values.minOfOrNull { changeWaitMillis - (now - it) }
                // A chat still waiting out its changes (a streaming reply) is compared when they settle.
                var item = catchUp.removeFirstOrNull()
                while (item != null && item in changed) item = catchUp.removeFirstOrNull()
                item?.let { it to true }
            }
        }
        if (next == null) {
            val remaining = wait
            if (remaining != null) scheduleDrain(remaining.coerceAtLeast(1)) else requestVectorsSafely()
            return
        }
        val (chatId, catchingUp) = next
        val result = updateChat(chatId, catchingUp)
        if (result == ChatResult.WROTE && !catchingUp) requestVectorsSafely()
        val more = synchronized(lock) { changed.isNotEmpty() || catchUp.isNotEmpty() }
        // Only a chat that was actually compared is followed by a pause.
        val pause = if (catchingUp && result != ChatResult.SKIPPED) catchUpPauseMillis else 0L
        if (more) scheduleDrain(pause) else requestVectorsSafely()
    }

    private enum class ChatResult { SKIPPED, COMPARED, WROTE }

    private fun updateChat(chatId: String, catchingUp: Boolean): ChatResult = try {
        val updater = updaterFor() ?: return ChatResult.SKIPPED
        val settings = updater.settingsKey
        // Catching up, a chat whose index already describes it as stored now is
        // left alone without reading its history: the version check is cheap.
        val upToDate = catchingUp && storage().indexState(chatId)?.let { state ->
            !state.needsRefresh && state.describedVersion == describedVersion(source.chatVersion(chatId), settings)
        } == true
        if (upToDate) ChatResult.SKIPPED else when (val read = source.read(chatId)) {
            IndexChatSource.Read.Unavailable -> ChatResult.SKIPPED
            is IndexChatSource.Read.Messages -> {
                val described = describedVersion(read.version, settings)
                when (val outcome = updater.update(
                    chatId, described, read.messages,
                    {
                        // A change reported since the read counts at once, before it reaches the disk.
                        if (source.changeCount(chatId) != read.changeCount) null
                        else describedVersion(source.chatVersion(chatId), settings)
                    },
                    { !isOn() }
                )) {
                    is ConversationIndexUpdater.Outcome.Updated -> {
                        synchronized(lock) { staleRetries.remove(chatId) }
                        if (outcome.written + outcome.restamped + outcome.removed > 0) ChatResult.WROTE
                        else ChatResult.COMPARED
                    }
                    ConversationIndexUpdater.Outcome.Stale -> {
                        retryAfterChange(chatId)
                        ChatResult.COMPARED
                    }
                    ConversationIndexUpdater.Outcome.Cancelled, is ConversationIndexUpdater.Outcome.Failed ->
                        ChatResult.COMPARED
                }
            }
        }
    } catch (_: Exception) {
        ChatResult.COMPARED
    }

    /** The chat changed during its update; it is tried again a few times, then left marked for a refresh. */
    private fun retryAfterChange(chatId: String) {
        val again = synchronized(lock) {
            val tries = (staleRetries[chatId] ?: 0) + 1
            if (tries > STALE_RETRIES) {
                staleRetries.remove(chatId)
                false
            } else {
                staleRetries[chatId] = tries
                true
            }
        }
        if (again) chatChanged(chatId)
    }

    private fun requestVectorsSafely() {
        try { requestVectors() } catch (_: Exception) { }
    }

    private inline fun guarded(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        /**
         * How long a chat must go without a change before it is compared: longer
         * than the chat screen's two-second saves while a reply streams, so
         * those saves and the save when it finishes become one update.
         */
        const val CHANGE_WAIT_MILLIS = 3_000L

        /** Between two existing chats while catching up, so the work is spread out. */
        const val CATCH_UP_PAUSE_MILLIS = 250L

        const val STALE_RETRIES = 3

        /** The version an index describes: the chat as stored, and how it was cut. */
        fun describedVersion(chatVersion: String, settingsKey: String): String = "$chatVersion|$settingsKey"
    }
}

/**
 * Asks the vector worker for passes on its own background thread, one at a
 * time. A request made while a pass runs starts one more pass after it, so
 * work queued meanwhile is never missed.
 */
class IndexVectorPasses(
    private val runPass: () -> PassageEmbeddingWorker.Outcome?,
    private val runner: IndexTaskRunner,
    private val isOn: () -> Boolean = { true }
) {
    private val wanted = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)

    fun request() {
        wanted.set(true)
        if (scheduled.compareAndSet(false, true)) runner.run(0) { loop() }
    }

    private fun loop() {
        try {
            while (wanted.getAndSet(false)) {
                if (!isOn()) break
                val outcome = try { runPass() } catch (_: Exception) { null } ?: break
                // A paused, interrupted or busy pass is not repeated now; the next request tries again.
                if (outcome !is PassageEmbeddingWorker.Outcome.Finished) break
            }
        } finally {
            scheduled.set(false)
            if (wanted.get() && scheduled.compareAndSet(false, true)) runner.run(0) { loop() }
        }
    }
}
