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
 * What the coordinator reports while it works (Step 11): the settings,
 * notices and Error Log follow from these. Each is called on the index's
 * background thread and must not throw.
 */
interface IndexEvents {
    /** No updater could be made: the model is missing or its tokenizer cannot load. Indexing waits. */
    fun modelUnavailable(cause: Exception?) {}

    /** A chat's update or upkeep failed; it stays marked for a refresh. */
    fun chatFailed(chatId: String, cause: Exception) {}

    /** A chat was compared and the index describes it as stored. */
    fun chatIndexed(chatId: String) {}

    /** An operation not tied to one chat failed. */
    fun indexFailed(cause: Exception) {}

    companion object {
        val NONE = object : IndexEvents {}
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
    private val events: IndexEvents = IndexEvents.NONE,
    /**
     * Recovers a damaged index (G.13a, Step 11); true when the index can be
     * used again and should be caught up. [requestedByUser]: the user turned
     * the setting on after a failed recovery.
     */
    private val recover: (requestedByUser: Boolean) -> Boolean = { false },
    /** The user's pause (Step 12): nothing is counted, compared or cut while it is on. */
    private val isPaused: () -> Boolean = { false },
    private val progress: IndexProgressEvents = IndexProgressEvents.NONE,
    /**
     * Step 13: whether indexing may run now ([BackgroundIndexingPermit]). While
     * it may not (the app closed without its background service), nothing is
     * counted, compared, cut or asked of the vector worker; every queue is kept,
     * and [mayRunAgain] continues it.
     */
    private val mayRun: () -> Boolean = { true },
    private val changeWaitMillis: Long = CHANGE_WAIT_MILLIS,
    private val catchUpPauseMillis: Long = CATCH_UP_PAUSE_MILLIS,
    /** Milliseconds on a clock that only moves forward. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val lock = Any()
    /** Each changed chat, with when it last changed. */
    private val changed = LinkedHashMap<String, Long>()
    private val catchUp = ArrayDeque<String>()
    /** Chats whose messages are counted, by reading them, before the catch-up compares them (Step 12). */
    private val toCount = ArrayDeque<String>()
    private val staleRetries = HashMap<String, Int>()
    private val drainScheduled = AtomicBoolean(false)
    /**
     * Chats deleted or renamed away whose removal from the index has not run
     * yet (Step 13): an update of one of them stopped by the closing is never
     * put back. Guarded by [lock]; each leaves when its removal has run.
     */
    private val removed = HashSet<String>()

    /** A catch-up asked for while indexing could not run (Step 13). Read and written on the index's thread only. */
    private var catchUpWaiting = false
    /** A recovery asked for while indexing could not run, and whether the user asked for it; index's thread only. */
    private var recoveryWaiting: Boolean? = null

    /** The chat was saved: a new message, a finished reply, an edit, a deletion, hiding or showing, another version, or IDs given on opening. */
    fun chatChanged(chatId: String) {
        if (chatId.isBlank()) return
        synchronized(lock) {
            removed.remove(chatId)
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
            removed.addAll(ids)
            ids.forEach { changed.remove(it) }
            catchUp.removeAll(ids)
        }
        runner.run(0) {
            ids.forEach { id -> guarded(id) { maintenance().deleteChat(id) } }
            synchronized(lock) { removed.removeAll(ids) }
        }
    }

    /** A rename changed the chat's ID: its entries move to the new ID and are checked again (G.5a rule 9). */
    fun chatMoved(fromChatId: String, toChatId: String) {
        if (fromChatId.isBlank() || toChatId.isBlank() || fromChatId == toChatId) return
        synchronized(lock) {
            removed.add(fromChatId)
            changed.remove(fromChatId)
            catchUp.remove(fromChatId)
        }
        runner.run(0) {
            val moved = guarded(fromChatId) { maintenance().moveChat(fromChatId, toChatId) }
            if (!moved) guarded(fromChatId) { maintenance().deleteChat(fromChatId) }
            synchronized(lock) { removed.remove(fromChatId) }
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
            guarded(null) { maintenance().markAllForRefresh() }
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

    /**
     * The user deletes the index (G.12): everything derived is removed; the
     * chats are untouched. [onDone] runs on the index's thread with null
     * when the deletion committed, or what made it fail. Without
     * [whenIndexExists] there is no index to open, so none is created.
     */
    fun deleteIndex(whenIndexExists: Boolean = true, onDone: (Exception?) -> Unit = {}) {
        synchronized(lock) {
            changed.clear()
            catchUp.clear()
            toCount.clear()
            staleRetries.clear()
        }
        report { progress.catchUpStopped() }
        runner.run(0) {
            catchUpWaiting = false
            val failure = try {
                if (whenIndexExists) maintenance().deleteEverything()
                null
            } catch (e: Exception) {
                e
            }
            try { onDone(failure) } catch (_: Exception) { }
        }
    }

    /**
     * Recovers a damaged index on the index's thread, then catches up every
     * chat when the index can be used again (G.13a, Step 11).
     */
    fun recoverIndex(requestedByUser: Boolean = false) {
        runner.run(0) { recoverNow(requestedByUser) }
    }

    /** While indexing may not run (Step 13) the recovery waits, still marked as rebuilding, for [mayRunAgain]. */
    private fun recoverNow(requestedByUser: Boolean) {
        if (!mayRun()) {
            recoveryWaiting = (recoveryWaiting ?: false) || requestedByUser
            return
        }
        recoveryWaiting = null
        val usable = try { recover(requestedByUser) } catch (_: Exception) { false }
        // A recovery that could not run now (postponed) asks for vectors, so a catch-up stopped for it
        // either goes on or is seen to stall rather than waiting with nothing asked for.
        if (usable) catchUpAll() else if (isOn()) requestVectorsSafely()
    }

    /** The setting was turned on: existing chats are caught up, newest first. */
    fun turnedOn() {
        runner.run(0) { catchUpAll() }
    }

    /**
     * The user resumed (Step 12): existing chats are caught up again. Chats a
     * pause left partly compared are compared again from where they stand;
     * what is already indexed is not written or read by the model again.
     */
    fun resumed() {
        runner.run(0) { catchUpAll() }
    }

    /** Step 13: whether chats wait to be counted or compared, each of which asks for vectors once it is. */
    fun hasQueuedWork(): Boolean = synchronized(lock) { changed.isNotEmpty() || catchUp.isNotEmpty() || toCount.isNotEmpty() }

    /**
     * Indexing may run again (Step 13): a catch-up that waited starts now;
     * otherwise the queued work and the waiting vectors continue. Handed to the
     * index's thread, after any task that found it could not run, so none is missed.
     */
    fun mayRunAgain() {
        runner.run(0) {
            recoveryWaiting?.let { recoverNow(it) }
            if (catchUpWaiting) catchUpAll()
            // Whatever else waited continues too, even when the catch-up could not start now.
            scheduleDrain(0)
            if (isOn() && !isPaused() && mayRun()) requestVectorsSafely()
        }
    }

    /**
     * Removes the entries of chats that are gone, even while paused, then
     * queues every chat; the chats the index does not describe are counted
     * first (Step 12).
     */
    private fun catchUpAll() {
        if (!isOn()) return
        val chats = try { source.chatIdsNewestFirst() } catch (_: Exception) { null } ?: return
        val live = chats.toSet()
        val gone = try {
            maintenance().indexedChatIds() - live
        } catch (e: Exception) {
            report { events.indexFailed(e) }
            emptySet()
        }
        gone.forEach { id -> guarded(id) { maintenance().deleteChat(id) } }
        // Step 13: chats that are gone leave the index at once; counting and comparing wait.
        if (!mayRun()) {
            // Nothing else is read, queued or changed, and the model is not loaded; [mayRunAgain] starts this catch-up.
            catchUpWaiting = true
            return
        }
        catchUpWaiting = false
        val updater = if (chats.isEmpty()) null else try {
            updaterFor().also { if (it == null) report { events.modelUnavailable(null) } }
        } catch (e: Exception) {
            report { events.modelUnavailable(e) }
            null
        }
        if (isPaused()) {
            // Resuming starts the catch-up again; nothing waits in the queues meanwhile. The model
            // is still checked, so a removed or returning model is noticed while paused: a vector
            // pass loads it and stops at once, reading nothing.
            synchronized(lock) {
                catchUp.clear()
                toCount.clear()
            }
            if (updater != null) requestVectorsSafely()
            return
        }
        if (chats.isNotEmpty() && updater == null) {
            // Nothing can be counted or cut without the model: indexing waits, as in [drain].
            stopCatchUp()
            return
        }
        val counting = if (updater == null) emptyList() else {
            try { progress.catchUpStarted(chats, updater.settingsKey) } catch (_: Exception) { emptyList() }
        }
        synchronized(lock) {
            catchUp.clear()
            catchUp.addAll(chats)
            toCount.clear()
            toCount.addAll(counting)
        }
        scheduleDrain(0)
    }

    private fun stopCatchUp() {
        synchronized(lock) {
            changed.clear()
            catchUp.clear()
            toCount.clear()
        }
        report { progress.catchUpStopped() }
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
        // While paused nothing is counted, compared or cut; resuming starts a catch-up.
        if (!isOn() || isPaused()) return
        // Step 13: while indexing may not run, every queue is kept for [mayRunAgain].
        if (!mayRun()) return
        val now = clock()
        var wait: Long? = null
        val next = synchronized(lock) {
            val ready = changed.entries.firstOrNull { now - it.value >= changeWaitMillis }?.key
            if (ready != null) {
                changed.remove(ready)
                ready to Work.CHANGED
            } else {
                wait = changed.values.minOfOrNull { changeWaitMillis - (now - it) }
                toCount.removeFirstOrNull()?.let { it to Work.COUNT } ?: run {
                    // A chat still waiting out its changes (a streaming reply) is compared when they settle.
                    var item = catchUp.removeFirstOrNull()
                    while (item != null && item in changed) item = catchUp.removeFirstOrNull()
                    item?.let { it to Work.CATCH_UP }
                }
            }
        }
        if (next == null) {
            val remaining = wait
            if (remaining != null) scheduleDrain(remaining.coerceAtLeast(1)) else drained()
            return
        }
        val (chatId, work) = next
        if (work == Work.COUNT) {
            count(chatId)
            scheduleDrain(0)
            return
        }
        val catchingUp = work == Work.CATCH_UP
        val result = updateChat(chatId, catchingUp)
        if (result == ChatResult.NO_MODEL) {
            // Nothing can be cut without the model: indexing waits. The chats are
            // compared again by their stored version once the model works.
            stopCatchUp()
            return
        }
        if (result == ChatResult.WROTE && !catchingUp) requestVectorsSafely()
        val more = synchronized(lock) { changed.isNotEmpty() || catchUp.isNotEmpty() || toCount.isNotEmpty() }
        // Only a chat that was actually compared is followed by a pause.
        val pause = if (catchingUp && result != ChatResult.SKIPPED) catchUpPauseMillis else 0L
        if (more) scheduleDrain(pause) else drained()
    }

    private enum class Work { CHANGED, COUNT, CATCH_UP }

    /** Every queued chat was handled: the vectors come next. */
    private fun drained() {
        report { progress.catchUpDrained() }
        requestVectorsSafely()
    }

    /**
     * Counts a chat's messages for the progress by reading it (Step 12);
     * writes nothing. Every chat queued for counting is reported, even one
     * that cannot be read, so the count always finishes.
     */
    private fun count(chatId: String) {
        val read = try { source.read(chatId) } catch (_: Exception) { IndexChatSource.Read.Unavailable }
        report {
            when (read) {
                is IndexChatSource.Read.Messages -> progress.chatRead(chatId, read.version, read.messages)
                IndexChatSource.Read.Unavailable -> {
                    val version = try { source.chatVersion(chatId) } catch (_: Exception) { "" }
                    progress.chatRead(chatId, version, null)
                }
            }
        }
    }

    private enum class ChatResult { SKIPPED, COMPARED, WROTE, NO_MODEL }

    private fun updateChat(chatId: String, catchingUp: Boolean): ChatResult = try {
        val updater = try {
            updaterFor()
        } catch (e: Exception) {
            report { events.modelUnavailable(e) }
            return ChatResult.NO_MODEL
        } ?: run {
            report { events.modelUnavailable(null) }
            return ChatResult.NO_MODEL
        }
        val settings = updater.settingsKey
        // Catching up, a chat whose index already describes it as stored now is
        // left alone without reading its history: the version check is cheap.
        val upToDate = catchingUp && storage().indexState(chatId)?.let { state ->
            !state.needsRefresh && state.describedVersion == describedVersion(source.chatVersion(chatId), settings)
        } == true
        if (upToDate) ChatResult.SKIPPED else when (val read = source.read(chatId)) {
            IndexChatSource.Read.Unavailable -> ChatResult.SKIPPED
            is IndexChatSource.Read.Messages -> {
                report { progress.chatRead(chatId, read.version, read.messages) }
                val described = describedVersion(read.version, settings)
                when (val outcome = updater.update(
                    chatId, described, read.messages,
                    {
                        // A change reported since the read counts at once, before it reaches the disk.
                        if (source.changeCount(chatId) != read.changeCount) null
                        else describedVersion(source.chatVersion(chatId), settings)
                    },
                    // Closing the app without the background service (Step 13) stops at the same point as a pause.
                    { !isOn() || isPaused() || !mayRun() }
                )) {
                    is ConversationIndexUpdater.Outcome.Updated -> {
                        synchronized(lock) { staleRetries.remove(chatId) }
                        report { events.chatIndexed(chatId) }
                        if (outcome.written + outcome.restamped + outcome.removed > 0) ChatResult.WROTE
                        else ChatResult.COMPARED
                    }
                    ConversationIndexUpdater.Outcome.Stale -> {
                        retryAfterChange(chatId)
                        ChatResult.COMPARED
                    }
                    ConversationIndexUpdater.Outcome.Cancelled -> {
                        // Stopped by the closing, not by the user or the setting: the chat is compared
                        // again first when indexing may run, from where its update stopped. The permit is
                        // not read again here, so an opening in between never drops the chat.
                        if (isOn() && !isPaused()) requeue(chatId, catchingUp)
                        ChatResult.COMPARED
                    }
                    is ConversationIndexUpdater.Outcome.Failed -> {
                        report { events.chatFailed(chatId, outcome.cause) }
                        ChatResult.COMPARED
                    }
                }
            }
        }
    } catch (e: Exception) {
        report { events.chatFailed(chatId, e) }
        ChatResult.COMPARED
    }

    /** Puts a chat whose update the closing stopped back at the front of its queue. */
    private fun requeue(chatId: String, catchingUp: Boolean) {
        synchronized(lock) {
            // Deleted or renamed away meanwhile: its removal runs next and nothing of it comes back.
            if (chatId in removed) return
            if (catchingUp) {
                if (chatId !in catchUp) catchUp.addFirst(chatId)
            } else if (chatId !in changed) {
                // Ready at once: its changes had already settled.
                changed[chatId] = clock() - changeWaitMillis
            }
        }
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

    /** Runs [block]; a failure is reported for [chatId], or for the index when null. */
    private inline fun guarded(chatId: String?, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        report { if (chatId != null) events.chatFailed(chatId, e) else events.indexFailed(e) }
        false
    }

    private inline fun report(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
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
    /** Guards [wanted], [scheduled], [requests] and [stopAt] together. */
    private val lock = Any()
    private var wanted = false
    private var scheduled = false
    private var requests = 0L
    private var stopAt = NOT_STOPPED
    private var stoppedBy: VectorPassFailure? = null

    /** Step 13: how many passes have been asked for so far. */
    val requestCount: Long get() = synchronized(lock) { requests }

    /**
     * Step 13: when the passes stopped because the last one failed (or could
     * not start) with none asked for since, the [requestCount] at that moment;
     * otherwise [NOT_STOPPED]. Set and cleared under the same lock as the
     * requests, so it never shows a stop while a pass is asked for.
     */
    val stoppedOnFailureAt: Long get() = synchronized(lock) { stopAt }

    /** Step 13: what the failure that stopped the passes was, while [stoppedOnFailureAt] is set. */
    val stopFailure: VectorPassFailure? get() = synchronized(lock) { if (stopAt == NOT_STOPPED) null else stoppedBy }

    fun request() {
        val start = synchronized(lock) {
            wanted = true
            requests++
            stopAt = NOT_STOPPED
            stoppedBy = null
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) schedule()
    }

    private fun schedule() {
        try {
            runner.run(0) { loop() }
        } catch (e: Exception) {
            synchronized(lock) { scheduled = false }
            throw e
        }
    }

    private fun takeWanted(): Boolean = synchronized(lock) { wanted.also { wanted = false } }

    private fun loop() {
        var failed = false
        var failure: VectorPassFailure? = null
        try {
            while (takeWanted()) {
                failed = false
                failure = null
                if (!isOn()) break
                // Counted as failed until the pass returns normally, so an Error (out of memory) is a failure too.
                failed = true
                failure = VectorPassFailure(VectorPassFailure.Stage.VECTORS, "it ended without returning")
                val outcome = try {
                    runPass()
                } catch (e: Exception) {
                    failure = VectorPassFailure(VectorPassFailure.Stage.VECTORS, ConversationIndexErrorLog.cause(e))
                    null
                } catch (t: Throwable) {
                    failure = VectorPassFailure(VectorPassFailure.Stage.VECTORS, ConversationIndexErrorLog.cause(t))
                    throw t
                }
                failed = outcome == null || outcome is PassageEmbeddingWorker.Outcome.Interrupted
                when {
                    outcome is PassageEmbeddingWorker.Outcome.Interrupted ->
                        failure = VectorPassFailure(VectorPassFailure.Stage.VECTORS, ConversationIndexErrorLog.cause(outcome.cause))
                    // A pass that could not start: the model or the index could not be prepared.
                    outcome == null && failure?.cause == "it ended without returning" -> failure = VectorPassFailure(
                        VectorPassFailure.Stage.PREPARING, "the on-device search model or the index could not be prepared"
                    )
                }
                if (outcome == null) break
                // A paused, interrupted or busy pass is not repeated now; the next request tries again.
                if (outcome !is PassageEmbeddingWorker.Outcome.Finished) break
            }
        } finally {
            val again = synchronized(lock) {
                if (wanted) true else {
                    scheduled = false
                    stopAt = if (failed) requests else NOT_STOPPED
                    stoppedBy = if (failed) failure else null
                    false
                }
            }
            if (again) schedule()
        }
    }

    companion object {
        const val NOT_STOPPED = -1L
    }
}

/** What stopped the vector passes (Step 13), for the background service's log. Holds no message content. */
data class VectorPassFailure(val stage: Stage, val cause: String) {
    enum class Stage(val text: String) {
        PREPARING("preparing the on-device search model and the index"),
        VECTORS("making search vectors")
    }
}
