/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.preferences.memory.librarian.Librarian

/** The result of the conversation index's line in Check Database Integrity (G.13a, Step 11). */
data class IndexCheckResult(val status: Status, val detail: String?) {
    enum class Status { OK, UNCHECKED, REBUILDING, REPAIR_FAILED }
}

/**
 * The app's conversation index while it runs (Step 11): the coordinator and
 * vector passes the hookups use, the Index Conversations controls, the
 * status numbers, the database check, automatic recovery, the ongoing
 * conditions behind the notices, and the Error Log entries. Everything here
 * runs off the main thread except the calls that only hand work over.
 */
object ConversationIndexRuntime {

    @Volatile private var instance: ConversationIndexCoordinator? = null

    /** Created on first use, so nothing is started until something needs the index. */
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "conversation-index-upkeep").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    /** Vector passes run on their own thread, so a long pass never holds up the upkeep of the chats. */
    private val vectorExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "conversation-index-vectors").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    fun coordinator(context: Context): ConversationIndexCoordinator =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    @Volatile private var progressInstance: IndexProgressTracker? = null

    /**
     * Whether indexing may run now (Step 13): while a screen is visible or the
     * background service runs. When it may run again, waiting work continues.
     */
    val backgroundPermit = BackgroundIndexingPermit().apply {
        setOnMayRunAgain { instance?.mayRunAgain() }
    }

    /** The progress when something already created it; null otherwise, so asking creates nothing. */
    fun progressIfCreated(): IndexProgressTracker? = progressInstance

    /** Runs [task] on the index's thread, after the work handed to it before (Step 13). */
    fun onIndexThread(task: () -> Unit) {
        IndexTaskRunner.on(executor).run(0, task)
    }

    /** The indexing progress the chat header shows (Step 12). Creating it starts no work. */
    fun progress(context: Context): IndexProgressTracker =
        progressInstance ?: synchronized(this) {
            progressInstance ?: buildProgress(context.applicationContext).also { progressInstance = it }
        }

    private fun buildProgress(app: Context): IndexProgressTracker {
        val tracker = IndexProgressTracker(
            source = AppIndexProgressSource(app, SavedChatIndexSource(app)) { e ->
                damageFound(app, ConversationIndexErrorLog.cause(e))
            },
            pause = SavedEmbeddingPauseState(app),
            saved = SavedIndexingProgressPrefs(app),
            runner = IndexTaskRunner.on(executor)
        )
        ConversationIndexConditions.addListener { tracker.publish() }
        tracker.publish()
        return tracker
    }

    /** Pause in the progress pop-up: counting, comparing and vectors stop at their next safe point; finished work is kept. */
    fun pause(context: Context) {
        progress(context).pause()
    }

    /** Resume in the progress pop-up: the catch-up continues; finished work is not done again. */
    fun resume(context: Context) {
        val app = context.applicationContext
        progress(app).resume()
        coordinator(app).resumed()
    }

    /** Indexing runs: the setting is on and no failed recovery is waiting for the user. */
    private fun isOn(app: Context): Boolean =
        ConversationIndexSetting.isOn(app) && !ConversationIndexConditions.recoveryFailed(app)

    /** Creating it opens nothing; the store and the model are opened on the background threads. */
    private fun build(app: Context): ConversationIndexCoordinator {
        ConversationIndexDamage.listener = { cause -> damageFound(app, ConversationIndexErrorLog.cause(cause)) }
        val source = SavedChatIndexSource(app)
        val vectors = IndexVectorPasses(
            runPass = { runVectorPass(app, source) },
            runner = IndexTaskRunner.on(vectorExecutor),
            // A pass while paused only loads the model, so its availability is known, and stops at once.
            // While the app is closed without the background service nothing is loaded or read.
            isOn = { isOn(app) && backgroundPermit.mayRun() }
        )
        return ConversationIndexCoordinator(
            source = source,
            storage = { ConversationIndexStore.get(app) },
            maintenance = { ConversationIndexStore.get(app) },
            updaterFor = {
                SearchTextReader.forInstalledModel(app)?.let { reader ->
                    ConversationIndexUpdater(
                        ConversationIndexStore.get(app), reader,
                        ConversationIndexSettings.TARGET_TOKENS.coerceAtMost(reader.hardLimit),
                        ConversationIndexSettings.OVERLAP_WORDS
                    )
                }
            },
            requestVectors = vectors::request,
            runner = IndexTaskRunner.on(executor),
            isOn = { isOn(app) },
            events = Events(app),
            recover = { requestedByUser -> recover(app, requestedByUser) },
            isPaused = { progress(app).isPaused },
            progress = progress(app),
            mayRun = backgroundPermit::mayRun
        )
    }

    // --- The controls of RAG Active Memory Settings -----------------------

    /**
     * Index Conversations was turned on (the caller has checked that a model
     * is installed). After a failed or postponed recovery this is a new
     * recovery attempt; otherwise existing chats are caught up.
     */
    fun turnOn(context: Context) {
        val app = context.applicationContext
        // Turning indexing on deliberately clears the user's pause (Owner, 2026-11-24, Step 12).
        progress(app).reset()
        ConversationIndexSetting.set(app, true)
        val failed = ConversationIndexConditions.recoveryFailed(app)
        if (failed || ConversationIndexConditions.recoveryPending(app)) {
            startRecovery(app, requestedByUser = failed)
        } else {
            coordinator(app).turnedOn()
        }
    }

    /** The app started with the setting on: a postponed recovery runs first, then existing chats are caught up. */
    fun appStarted(context: Context) {
        val app = context.applicationContext
        if (ConversationIndexConditions.recoveryPending(app)) startRecovery(app, requestedByUser = false)
        else coordinator(app).appStarted()
    }

    /** Index Conversations was turned off: indexing stops at the next safe point; the index is kept. */
    fun turnOff(context: Context) {
        ConversationIndexSetting.set(context.applicationContext, false)
    }

    /**
     * Delete Conversation Index (G.16a): the setting is turned off, so nothing
     * is rebuilt, and everything derived is removed; the chats are not read or
     * changed. The setting is off when this returns; [onDone] runs off the
     * main thread with whether the index is gone. A damaged index that cannot
     * run the deletion has its files removed, and a damaged file kept aside by
     * an unfinished recovery is removed too, so nothing deleted comes back.
     */
    fun deleteIndex(context: Context, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        ConversationIndexSetting.set(app, false)
        // Deleting the index clears the user's pause and its progress (Owner, 2026-11-24, Step 12).
        progress(app).reset()
        coordinator(app).deleteIndex(whenIndexExists = ConversationIndexStore.exists(app)) { failure ->
            val gone = (failure == null || (IndexFailures.isDamage(failure) && removeDamagedFiles(app))) &&
                removeSetAside(app)
            onDone(gone)
        }
    }

    /** Removes a damaged file an unfinished recovery kept aside; true when none is left. */
    private fun removeSetAside(app: Context): Boolean = try {
        ConversationIndexAccess.exclusive { AppIndexRecoveryFiles(app).discardDamaged() }
        !AppIndexRecoveryFiles(app).hasDamaged()
    } catch (_: Exception) {
        false
    }

    private fun removeDamagedFiles(app: Context): Boolean = try {
        ConversationIndexAccess.exclusive {
            val files = AppIndexRecoveryFiles(app)
            files.setDamagedAside()
            files.discardDamaged()
        }
        !ConversationIndexStore.exists(app)
    } catch (_: Exception) {
        false
    }

    /**
     * The status line's numbers, read from the index (G.16a, G28). Null when
     * they cannot be read now: the chat list or the index is unavailable.
     * Off the main thread. Opens nothing that does not exist yet.
     */
    fun readCounts(context: Context): ConversationIndexCounts? {
        val app = context.applicationContext
        val source = SavedChatIndexSource(app)
        val live = source.chatIdsNewestFirst() ?: return null
        if (!ConversationIndexStore.exists(app)) return ConversationIndexCounts(0, 0)
        return try {
            val model = EmbeddingModelStorage.activeModel(app)
            val settingsKey = model?.let {
                ConversationIndexUpdater.settingsKey(
                    ConversationIndexSettings.TARGET_TOKENS.coerceAtMost(it.storedTextMaxTokens),
                    ConversationIndexSettings.OVERLAP_WORDS,
                    SearchTextReader.keyFor(it)
                )
            }
            val summaries = ConversationIndexAccess.shared {
                ConversationIndexStore.get(app).chatSummaries(model?.embeddingTag ?: "", model?.dimensions ?: 0)
            }
            ConversationIndexCounting.count(live, summaries, source::chatVersion, settingsKey)
        } catch (e: Exception) {
            if (IndexFailures.isDamage(e)) damageFound(app, ConversationIndexErrorLog.cause(e))
            null
        }
    }

    /**
     * The conversation index's line in Check Database Integrity (G.13a,
     * Owner 2026-11-24). Damage found here starts the automatic recovery, so
     * the line then reads rebuilding. Off the main thread.
     */
    fun checkIntegrity(context: Context): IndexCheckResult {
        val app = context.applicationContext
        if (ConversationIndexConditions.rebuilding || ConversationIndexAccess.recovering) {
            return IndexCheckResult(IndexCheckResult.Status.REBUILDING, null)
        }
        if (ConversationIndexConditions.recoveryFailed(app)) {
            return IndexCheckResult(IndexCheckResult.Status.REPAIR_FAILED, null)
        }
        // No index has been made, so there is nothing that could be damaged.
        if (!ConversationIndexStore.exists(app)) return IndexCheckResult(IndexCheckResult.Status.OK, null)
        return try {
            val problem = ConversationIndexAccess.shared { ConversationIndexStore.get(app).integrityProblem() }
            if (problem == null) {
                IndexCheckResult(IndexCheckResult.Status.OK, null)
            } else {
                damageFound(app, "integrity check: $problem")
                IndexCheckResult(IndexCheckResult.Status.REBUILDING, problem)
            }
        } catch (e: Exception) {
            val cause = ConversationIndexErrorLog.cause(e)
            if (IndexFailures.isDamage(e)) {
                damageFound(app, cause)
                IndexCheckResult(IndexCheckResult.Status.REBUILDING, cause)
            } else {
                IndexCheckResult(IndexCheckResult.Status.UNCHECKED, cause)
            }
        }
    }

    // --- Automatic recovery (G.13a) ------------------------------------------

    private val recoveryScheduled = AtomicBoolean(false)
    private val automaticRecoveries = AtomicInteger(0)

    /** Damage was found: one recovery is started, however many places report it. */
    private fun damageFound(app: Context, cause: String) {
        // A recovery already waiting for the next start is not started again for every failing chat.
        if (recoveryScheduled.get() || ConversationIndexConditions.recoveryPending(app)) return
        log(app, "error", ConversationIndexErrorLog.damageDetected(cause))
        startRecovery(app, requestedByUser = false)
    }

    private fun startRecovery(app: Context, requestedByUser: Boolean) {
        if (!recoveryScheduled.compareAndSet(false, true)) return
        ConversationIndexConditions.rebuilding = true
        coordinator(app).recoverIndex(requestedByUser)
    }

    /** Runs on the index's thread. True when the index can be used and should be caught up. */
    private fun recover(app: Context, requestedByUser: Boolean): Boolean {
        val wasOn = ConversationIndexSetting.isOn(app)
        try {
            if (!requestedByUser && automaticRecoveries.incrementAndGet() > MAX_AUTOMATIC_RECOVERIES) {
                fail(app, wasOn, "the index was damaged again after $MAX_AUTOMATIC_RECOVERIES automatic rebuilds while the app was open")
                return false
            }
            val result = ConversationIndexAccess.exclusive { IndexRecovery(AppIndexRecoveryFiles(app)).recover() }
            // Damage reported by the replaced database is no longer current.
            ConversationIndexDamage.take()
            return when (result) {
                is IndexRecovery.Result.Rebuilt -> {
                    // Every chat in the list is caught up when indexing is on; nothing is queued when it is off.
                    val queued = if (ConversationIndexSetting.isOn(app)) {
                        SavedChatIndexSource(app).chatIdsNewestFirst()?.size ?: 0
                    } else 0
                    log(app, "info", ConversationIndexErrorLog.rebuildCompleted(queued, result.retained, result.notRead))
                    ConversationIndexConditions.setRecoveryPending(app, false)
                    ConversationIndexConditions.clearRecoveryFailed(app)
                    true
                }
                is IndexRecovery.Result.Postponed -> {
                    if (requestedByUser) {
                        fail(app, wasOn, result.cause)
                    } else {
                        // Nothing was removed; the recovery runs again at the next start, whose outcome is logged.
                        ConversationIndexConditions.setRecoveryPending(app, true)
                    }
                    false
                }
                is IndexRecovery.Result.Failed -> {
                    fail(app, wasOn, ConversationIndexErrorLog.cause(result.cause))
                    false
                }
            }
        } finally {
            recoveryScheduled.set(false)
            ConversationIndexConditions.rebuilding = false
        }
    }

    private fun fail(app: Context, wasOn: Boolean, cause: String) {
        log(app, "error", ConversationIndexErrorLog.rebuildFailed(cause))
        ConversationIndexSetting.set(app, false)
        ConversationIndexConditions.setRecoveryPending(app, false)
        ConversationIndexConditions.setRecoveryFailed(app, notice = wasOn)
    }

    // --- Vector passes --------------------------------------------------------

    private fun runVectorPass(app: Context, source: SavedChatIndexSource): PassageEmbeddingWorker.Outcome? {
        val events = Events(app)
        return ConversationIndexAccess.shared {
            val worker = try {
                PassageEmbeddingWorker.forInstalledModel(app, source)
            } catch (e: Exception) {
                if (IndexFailures.isDamage(e)) damageFound(app, ConversationIndexErrorLog.cause(e))
                progress(app).vectorPassFailed()
                return@shared null
            }
            if (worker == null) {
                events.modelUnavailable(null)
                progress(app).vectorPassFailed()
                return@shared null
            }
            // The model loaded and passed its check: a paused condition is over.
            ConversationIndexConditions.clearModelProblem(app)
            val catchUp = progress(app).vectorPassStarting()
            val outcome = worker.runPass { !isOn(app) || ConversationIndexAccess.recovering || !backgroundPermit.mayRun() }
            when (outcome) {
                is PassageEmbeddingWorker.Outcome.Finished -> progress(app).vectorPassFinished(catchUp)
                is PassageEmbeddingWorker.Outcome.Paused -> progress(app).vectorPassPaused()
                is PassageEmbeddingWorker.Outcome.Interrupted -> progress(app).vectorPassFailed()
                else -> Unit
            }
            reporter(app).vectorPass(outcome, worker.tokenLimit) {
                try {
                    ConversationIndexStore.get(app).waitingPassages(worker.vectorModel, worker.vectorDimensions)
                } catch (_: Exception) {
                    null
                }
            }
            outcome
        }
    }

    @Volatile private var reporterInstance: ConversationIndexReporter? = null

    private fun reporter(app: Context): ConversationIndexReporter =
        reporterInstance ?: synchronized(this) {
            reporterInstance ?: ConversationIndexReporter(
                write = { level, message -> log(app, level, message) },
                damageFound = { cause -> damageFound(app, cause) }
            ).also { reporterInstance = it }
        }

    private class Events(private val app: Context) : IndexEvents {

        override fun modelUnavailable(cause: Exception?) {
            val active = EmbeddingModelStorage.activeModel(app)
            val problem = if (active == null) IndexModelProblem.REMOVED else IndexModelProblem.CANNOT_LOAD
            val detail = cause?.let { ConversationIndexErrorLog.cause(it) }
                ?: if (active == null) "no embedding model is installed"
                else Librarian.getInstance(app).modelProblem ?: "the installed model ${active.id} could not be loaded"
            reporter(app).modelUnavailable(ConversationIndexConditions.setModelProblem(app, problem), detail)
        }

        override fun chatFailed(chatId: String, cause: Exception) = reporter(app).chatFailed(chatId, cause)

        override fun chatIndexed(chatId: String) = reporter(app).chatIndexed(chatId)

        override fun indexFailed(cause: Exception) = reporter(app).indexFailed(cause)
    }

    private val logLock = Any()

    internal fun log(app: Context, level: String, message: String) {
        try {
            synchronized(logLock) { Logger.log(app, "crash", ConversationIndexErrorLog.TAG, level, message) }
        } catch (_: Exception) { }
    }

    /** Automatic rebuilds allowed while the app is open; damage after that stops indexing instead of looping. */
    private const val MAX_AUTOMATIC_RECOVERIES = 2
}
