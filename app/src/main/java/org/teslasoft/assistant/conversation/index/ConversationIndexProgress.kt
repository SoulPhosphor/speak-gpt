/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One chat's indexed messages for the current model (Step 12). */
data class MessageProgress(
    /** Messages with passages. */
    val messages: Int,
    /** Of those, the messages whose every passage's vector is made or failed for good. */
    val finished: Int
)

interface ConversationIndexMessageProgress {
    /** One entry per chat with passages for [vectorModel] at [vectorDimensions]. */
    fun messageProgress(vectorModel: String, vectorDimensions: Int): Map<String, MessageProgress>
}

/** A chat the index does not describe yet, counted by reading it: its version and messages. */
data class CountedChat(val version: String, val indexable: Int, val unidentified: Int)

/** The numbers in the progress pop-up (G.16a). */
data class IndexingNumbers(val percent: Int, val indexedChats: Int, val totalChats: Int)

/**
 * The progress pop-up's numbers (G.16a; Owner, 2026-11-24, Step 12).
 *
 *  - **Messages:** every message that can be indexed, in chats that can be
 *    indexed now. A chat that needs opening first (G28) or has nothing to
 *    index is left out. A chat the index describes as stored now is counted
 *    from the index; any other chat from the count made by reading it.
 *  - **Processed:** a message of a chat the index describes as stored now,
 *    whose every passage's vector is made or failed for good. A failed
 *    vector is never retried, so it is processed, though its chat is never
 *    counted as indexed. A waiting vector, or a chat waiting to be compared
 *    or refreshed, is not processed.
 *  - **Conversations:** indexed by Step 11's definition
 *    ([ConversationIndexCounting]), out of the chats whose messages are counted.
 *
 * A chat the index does not describe and that was not counted (made or
 * renamed while indexing ran) is left out until its update reads it.
 */
object IndexingProgressMath {

    fun numbers(
        liveChats: Collection<String>,
        summaries: Map<String, ChatIndexSummary>,
        messages: Map<String, MessageProgress>,
        counted: Map<String, CountedChat>,
        chatVersion: (String) -> String,
        settingsKey: String
    ): IndexingNumbers {
        var total = 0L
        var done = 0L
        var chats = 0
        for (chatId in liveChats.toSet()) {
            val summary = summaries[chatId]
            val described = summary != null && !summary.needsRefresh &&
                summary.describedVersion == ConversationIndexCoordinator.describedVersion(chatVersion(chatId), settingsKey)
            if (described) {
                if (summary!!.unidentifiedMessages > 0) continue
                val progress = messages[chatId] ?: continue
                if (progress.messages == 0) continue
                total += progress.messages
                done += progress.finished
            } else {
                val count = counted[chatId] ?: continue
                if (count.unidentified > 0 || count.indexable == 0) continue
                total += count.indexable
            }
            chats++
        }
        val indexed = ConversationIndexCounting.count(liveChats, summaries, chatVersion, settingsKey).indexed
        val percent = if (total == 0L) 100 else (done * 100 / total).toInt()
        return IndexingNumbers(percent, indexed, chats)
    }
}

/** What the chat header's indexing icon and its pop-up show (G.16a). */
sealed interface IndexingDisplay {
    data object Hidden : IndexingDisplay

    /** Indexing runs; [numbers] is null while the messages are being counted. */
    data class Running(val numbers: IndexingNumbers?) : IndexingDisplay

    /** Paused by the user; [numbers] is null when it was paused before the count finished. */
    data class Paused(val numbers: IndexingNumbers?) : IndexingDisplay
}

/** The Step 11 conditions that hide the icon. */
data class IndexingConditions(
    val settingOn: Boolean,
    val rebuilding: Boolean,
    val recoveryFailed: Boolean,
    val modelUnavailable: Boolean
)

/**
 * When the icon shows (Owner, 2026-11-24, Step 12): never while Index
 * Conversations is off, an index rebuild runs or failed, or the model is
 * unavailable, whose own notices and status cover them; otherwise while the
 * user's pause is on, and while a catch-up of work that remains runs. The
 * ordinary update of a changed chat is not a catch-up.
 */
object IndexingDisplayRule {
    fun display(
        conditions: IndexingConditions,
        paused: Boolean,
        catchingUp: Boolean,
        numbers: IndexingNumbers?
    ): IndexingDisplay = when {
        !conditions.settingOn || conditions.rebuilding || conditions.recoveryFailed || conditions.modelUnavailable ->
            IndexingDisplay.Hidden
        paused -> IndexingDisplay.Paused(numbers)
        catchingUp -> IndexingDisplay.Running(numbers)
        else -> IndexingDisplay.Hidden
    }
}

/** What the coordinator tells the progress while it catches up (Step 12). Called on the index's thread. */
interface IndexProgressEvents {
    /**
     * A catch-up of [chats] (newest first) starts, cutting with [settingsKey].
     * Returns the chats whose messages are counted by reading them, in order.
     */
    fun catchUpStarted(chats: List<String>, settingsKey: String): List<String> = emptyList()

    /** [chatId] was read at [version]; [messages] is null when it could not be read. */
    fun chatRead(chatId: String, version: String, messages: List<IndexSourceMessage>?) {}

    /** Every queued chat was counted and compared; a vector pass is requested next. */
    fun catchUpDrained() {}

    /** The catch-up stopped before it finished: the model is unavailable, or the index was deleted. */
    fun catchUpStopped() {}

    companion object {
        val NONE = object : IndexProgressEvents {}
    }
}

/** The work a catch-up starts with. */
data class IndexWork(
    /** The chats the index does not describe as stored now, newest first, each with its version now. */
    val notDescribed: Map<String, String>,
    val waitingVectors: Int
)

/** Which chats a catch-up finds work in (Step 12). */
object IndexWorkFinder {
    /**
     * The chats among [chats] the index does not describe as stored now
     * (none recorded, a refresh pending, or another version or cutting
     * settings), in the order given, with their versions now.
     */
    fun work(
        chats: List<String>,
        summaries: Map<String, ChatIndexSummary>,
        waitingVectors: Int,
        chatVersion: (String) -> String,
        settingsKey: String
    ): IndexWork {
        val notDescribed = LinkedHashMap<String, String>()
        for (chatId in chats) {
            val version = chatVersion(chatId)
            val summary = summaries[chatId]
            if (summary == null || summary.needsRefresh ||
                summary.describedVersion != ConversationIndexCoordinator.describedVersion(version, settingsKey)
            ) notDescribed[chatId] = version
        }
        return IndexWork(notDescribed, waitingVectors)
    }
}

/** What the progress reads from the index and the app. */
interface IndexProgressSource {
    /** Null when the index cannot be read now. */
    fun work(chats: List<String>, settingsKey: String): IndexWork?

    /** The numbers now, with [counted] for the chats the index does not describe; null when they cannot be read. */
    fun numbers(counted: Map<String, CountedChat>): IndexingNumbers?

    fun conditions(): IndexingConditions
}

/** The numbers kept with the user's pause, so a paused pop-up shows them after a restart. */
interface SavedIndexingProgress {
    fun read(): IndexingNumbers?
    fun write(numbers: IndexingNumbers?)
}

/**
 * The indexing progress the chat header's icon and pop-up show (G.16a,
 * Step 12), kept apart from any screen so changing chats never restarts it.
 *
 * A catch-up that finds work left (chats the index does not describe as
 * stored now, or waiting vectors) shows the icon. The chats not described
 * are counted first by reading them, one per task on the index's thread; a
 * chat counted earlier at the same version is not read again. The numbers
 * are read from the index on that thread when counting finishes, when the
 * user pauses, and when a screen asks while the pop-up is open. The
 * catch-up ends when a vector pass that started after every queued chat was
 * handled finishes.
 *
 * Pause is the vector worker's saved pause: the coordinator and the worker
 * stop at their next safe point. The numbers at that moment are saved with
 * it, so a paused pop-up shows them after a restart; paused before counting
 * finished, it shows none. Resume clears them; the caller then starts a
 * catch-up, which counts only what changed.
 */
class IndexProgressTracker(
    private val source: IndexProgressSource,
    private val pause: EmbeddingPauseState,
    private val saved: SavedIndexingProgress,
    /** The index's thread, where the numbers are read. */
    private val runner: IndexTaskRunner
) : IndexProgressEvents {

    private val lock = Any()
    private val publishLock = Any()
    private var catchingUp = false
    /** Changes with every catch-up, so a result for an older one is thrown away. */
    private var generation = 0L
    private var drained = false

    /**
     * Step 13: whether the vector passes stopped on a failure with none asked
     * for since ([IndexVectorPasses.isStoppedOnFailure]). Set once by the runtime.
     */
    @Volatile var vectorsStoppedOnFailure: () -> Boolean = { false }
    private val pending = LinkedHashSet<String>()
    private val counted = HashMap<String, CountedChat>()
    private var latest: IndexingNumbers? = null
    private val refreshScheduled = AtomicBoolean(false)
    private val refreshWanted = AtomicBoolean(false)

    private val state = MutableStateFlow<IndexingDisplay>(IndexingDisplay.Hidden)
    val display: StateFlow<IndexingDisplay> = state

    val isPaused: Boolean get() = pause.paused

    /**
     * Whether the catch-up cannot go on by itself (Step 13): every queued chat
     * was handled, so nothing will ask for another vector pass, and the last
     * pass stopped on a failure.
     */
    val isStalled: Boolean get() = synchronized(lock) { catchingUp && drained } && vectorsStoppedOnFailure()

    /** The Step 11 conditions the display is worked out from. */
    fun conditions(): IndexingConditions = source.conditions()

    override fun catchUpStarted(chats: List<String>, settingsKey: String): List<String> {
        val work = try { source.work(chats, settingsKey) } catch (_: Exception) { null }
        val toCount = synchronized(lock) {
            generation++
            drained = false
            pending.clear()
            catchingUp = work != null && (work.notDescribed.isNotEmpty() || work.waitingVectors > 0)
            if (!catchingUp || work == null) {
                counted.clear()
                latest = null
                emptyList()
            } else {
                counted.keys.retainAll(work.notDescribed.keys)
                work.notDescribed.filter { (chatId, version) -> counted[chatId]?.version != version }.keys.toList()
                    .also {
                        pending.addAll(it)
                        // Numbers from before stay shown only while nothing needs counting; they are read again at once.
                        if (it.isNotEmpty()) latest = null
                    }
            }
        }
        publish()
        if (toCount.isEmpty()) refresh()
        return toCount
    }

    override fun chatRead(chatId: String, version: String, messages: List<IndexSourceMessage>?) {
        val countingFinished = synchronized(lock) {
            if (!catchingUp) return
            counted[chatId] = CountedChat(
                version,
                messages?.let(IndexEligibility::indexedMessages) ?: 0,
                messages?.let(IndexEligibility::unidentified) ?: 0
            )
            pending.remove(chatId) && pending.isEmpty()
        }
        if (countingFinished) {
            publish()
            refresh()
        }
    }

    override fun catchUpDrained() {
        synchronized(lock) { if (catchingUp) drained = true }
    }

    override fun catchUpStopped() {
        synchronized(lock) { endCatchUp() }
        publish()
    }

    /**
     * A vector pass starts. Returns the catch-up it can finish: one whose
     * queued chats were all handled before the pass started; otherwise null.
     */
    fun vectorPassStarting(): Long? = synchronized(lock) { if (catchingUp && drained) generation else null }

    /** A vector pass found nothing more waiting; [token] is what [vectorPassStarting] returned. */
    fun vectorPassFinished(token: Long?) {
        val ended = synchronized(lock) {
            if (token != null && token == generation && catchingUp && drained) {
                endCatchUp()
                true
            } else false
        }
        if (ended) {
            saved.write(null)
            publish()
        }
    }

    /** The user paused: work stops at its next safe point; the numbers now are kept with the pause. */
    fun pause() {
        pause.paused = true
        val known = synchronized(lock) { catchingUp && pending.isEmpty() }
        if (!known) saved.write(null)
        publish()
        if (known) refresh()
    }

    /**
     * A vector pass stopped for the pause. A vector being read when Pause was
     * pressed is stored only now, on the vector thread, so the numbers kept
     * with the pause are read again to include it.
     */
    fun vectorPassPaused() {
        val known = synchronized(lock) { catchingUp && pending.isEmpty() }
        if (pause.paused && known) refresh()
    }

    /** The user resumed; the caller starts a catch-up, which counts only what changed. */
    fun resume() {
        pause.paused = false
        saved.write(null)
        publish()
    }

    /** Index Conversations was turned on, or the index was deleted: the pause and the progress start over. */
    fun reset() {
        if (pause.paused) pause.paused = false
        synchronized(lock) { endCatchUp() }
        saved.write(null)
        publish()
    }

    /** Reads the numbers again on the index's thread; only while they can be known. */
    fun refresh() {
        refreshWanted.set(true)
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        if (!refreshScheduled.compareAndSet(false, true)) return
        try {
            runner.run(0) { refreshNumbers() }
        } catch (_: Exception) {
            // Not handed over: a later request schedules it again.
            refreshScheduled.set(false)
        }
    }

    /** Works out what to show now; any thread. One at a time, so an older answer never replaces a newer one. */
    fun publish() = synchronized(publishLock) {
        val conditions = try {
            source.conditions()
        } catch (_: Exception) {
            IndexingConditions(settingOn = false, rebuilding = false, recoveryFailed = false, modelUnavailable = false)
        }
        val paused = pause.paused
        val (active, numbers) = synchronized(lock) {
            catchingUp to if (catchingUp && pending.isEmpty()) latest else null
        }
        // After a restart while paused nothing is counted yet: the numbers saved with the pause are shown.
        val shown = if (paused && !active) saved.read() else numbers
        state.value = IndexingDisplayRule.display(conditions, paused, active, shown)
    }

    private fun endCatchUp() {
        catchingUp = false
        drained = false
        pending.clear()
        counted.clear()
        latest = null
    }

    private fun refreshNumbers() {
        try {
            while (refreshWanted.getAndSet(false)) {
                val (from, snapshot) = synchronized(lock) {
                    if (!catchingUp || pending.isNotEmpty()) null to null else generation to HashMap(counted)
                }
                if (from == null || snapshot == null) continue
                val numbers = try { source.numbers(snapshot) } catch (_: Exception) { null } ?: continue
                val current = synchronized(lock) {
                    // A catch-up that started, or counting that resumed, meanwhile makes these numbers obsolete.
                    (generation == from && catchingUp && pending.isEmpty()).also { if (it) latest = numbers }
                }
                if (!current) continue
                if (pause.paused) saved.write(numbers)
                publish()
            }
        } finally {
            refreshScheduled.set(false)
            if (refreshWanted.get()) scheduleRefresh()
        }
    }
}
