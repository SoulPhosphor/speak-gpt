/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistFailure
import org.teslasoft.assistant.ui.activities.MemoryAssistantActivity

/** What the Memory Assistant screen renders for the service-owned run.
 *  Running carries the latest progress (null until the first conversation
 *  starts); Finished carries the terminal outcome and is consumed (nulled)
 *  by the screen that displays it — a fresh visit never replays it (owner
 *  behavior rule: Complete! is not sticky across visits). */
sealed interface MemoryAnalysisState {
    data class Running(val progress: Archivist.Progress?) : MemoryAnalysisState
    data class Finished(val outcome: Archivist.RunOutcome) : MemoryAnalysisState
}

/** A scan waiting with its progress kept (Stage 6A): [paused] by the user,
 *  or interrupted (app closed, phone off, a system stop) and resuming after
 *  the next chat reply. [percent] is the last completed percentage. */
data class SuspendedMemoryScan(val runId: String, val percent: Int, val paused: Boolean)

/**
 * Foreground service that OWNS the Memory Assistant analysis run (external-
 * memory counterplan §4(a), Step 1.3): the run must survive Activity
 * destruction, app switching, and the screen turning off, so its coroutine
 * lives here — not in the screen. Declared dataSync (this is background data
 * processing, not media playback; the generation keep-alive's mediaPlayback
 * type is deliberately NOT reused). The screen launches the run through
 * [start] and observes [state]; it may be destroyed freely without
 * cancelling anything. A notification tap returns to Memory Assistant.
 *
 * If the service cannot start, [start] returns false and NOTHING has
 * happened: no rows are claimed and no Activity-owned fallback run begins —
 * the caller shows a durable failure and the work stays retryable (§10 1e).
 * Process/service death recovery is the store's durable reconcile, not this
 * class. Notification wording follows the approved progress pattern
 * (Memory System/plan_one_page.md): the title "Analyzing Conversations",
 * indeterminate progress until the run's fixed total is known, then a
 * determinate bar with "X% complete".
 */
class MemoryAnalysisForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "memory_analysis_channel"
        private const val NOTIFICATION_ID = 9923
        private const val WAKE_LOCK_TAG = "PhosphorShines:MemoryAnalysis"
        private const val EXTRA_RERUN_ID = "rerunOfRunId"
        private const val EXTRA_ANALYSIS_TYPE = "analysisType"
        private const val EXTRA_CHAT_ID = "chatId"
        private const val ACTION_CANCEL = "org.teslasoft.assistant.action.CANCEL_ANALYSIS"
        private const val ACTION_PAUSE = "org.teslasoft.assistant.action.PAUSE_ANALYSIS"
        private const val ACTION_RESUME = "org.teslasoft.assistant.action.RESUME_ANALYSIS"
        private const val EXTRA_RESUME_RUN_ID = "resumeRunId"
        private const val EXTRA_RESCAN = "rescanUnmigrated"
        /** The paused scan's own notification (Stage 6A question 4). It is
         *  an ordinary notification: dismissing it does not cancel the scan,
         *  which stays available from the Memory Scanner Tool and the chat
         *  header. */
        private const val PAUSED_NOTIFICATION_ID = 9924

        val state = MutableStateFlow<MemoryAnalysisState?>(null)

        /** The newest paused or interrupted scan, or null. Kept separately
         *  from [state] so it outlives a consumed outcome. */
        val suspended = MutableStateFlow<SuspendedMemoryScan?>(null)

        /** True while a pause came from the user (the Pause buttons). */
        @Volatile
        private var pauseRequested = false

        /** True while a cancel came from the user (the Cancel button), false for
         *  a system-driven stop (the Android 15+ dataSync timeout). It lets the
         *  finished state distinguish "you cancelled it" (no error) from
         *  "the system cancelled it" (Analysis Cancelled). */
        @Volatile
        private var userCancelRequested = false

        /** True when the in-process stop was the Android 15+ dataSync runtime
         *  limit (onTimeout), so the screen can say "Analysis Time Limit
         *  Reached" rather than the generic "Analysis Stopped Early". */
        @Volatile
        private var timeLimitReached = false

        /**
         * Ask the running analysis to stop at the user's request. Delivered as a
         * service command so it reaches the live service instance; the run's own
         * cancellation path keeps any filed drafts and releases its claims. A
         * no-op when nothing is running.
         */
        fun requestCancel(context: Context) {
            val intent = Intent(context, MemoryAnalysisForegroundService::class.java)
                .setAction(ACTION_CANCEL)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) { /* nothing running to cancel */ }
        }

        /** Pause the live scan at once (owner decision, Stage 6A question
         *  5): completed sections and checkpoints are kept; the section in
         *  progress is sent again after Resume. A no-op when nothing runs. */
        fun requestPause(context: Context) {
            startCommand(context, Intent(context, MemoryAnalysisForegroundService::class.java)
                .setAction(ACTION_PAUSE))
        }

        /** Resume [runId] (or the newest suspended scan) from its checkpoint. */
        fun requestResume(context: Context, runId: String? = null): Boolean =
            startCommand(context, Intent(context, MemoryAnalysisForegroundService::class.java)
                .setAction(ACTION_RESUME)
                .putExtra(EXTRA_RESUME_RUN_ID, runId))

        /** The automatic complete rescan of conversations whose earlier
         *  progress could not be migrated (Stage 6A question 1). */
        fun startUnmigratedRescan(context: Context): Boolean =
            startCommand(context, Intent(context, MemoryAnalysisForegroundService::class.java)
                .putExtra(EXTRA_RESCAN, true)
                .putExtra(EXTRA_ANALYSIS_TYPE, "associative"))

        /**
         * Reads which scan is suspended and shows or clears the paused
         * notification to match. Called when the app opens (so a paused
         * scan's notification returns after a restart, without any boot
         * permission), and whenever a run ends. Worker thread only.
         */
        fun restoreSuspendedNotification(context: Context) {
            val app = context.applicationContext
            val run = try {
                Archivist.suspendedRun(app)
            } catch (_: Exception) {
                null
            }
            val current = run?.let {
                SuspendedMemoryScan(
                    it.runId, it.progressPercent ?: 0,
                    it.suspended == org.teslasoft.assistant.preferences.memory.MemoryStore.SUSPENDED_PAUSED
                )
            }
            suspended.value = current
            try {
                val nm = app.getSystemService(NotificationManager::class.java) ?: return
                if (current?.paused == true) {
                    createChannel(app)
                    nm.notify(PAUSED_NOTIFICATION_ID, pausedNotification(app, current))
                } else {
                    nm.cancel(PAUSED_NOTIFICATION_ID)
                }
            } catch (_: Exception) { /* the scan's state matters more than its notice */ }
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.memory_analysis_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                description = context.getString(R.string.memory_analysis_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }

        private fun openToolIntent(context: Context): PendingIntent {
            val openIntent = Intent(context, MemoryAssistantActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MemoryAnalysisForegroundService::class.java).setAction(action)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        /** "Memory Scanner Paused", the last percentage, and Resume. */
        private fun pausedNotification(context: Context, scan: SuspendedMemoryScan): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(context.getString(R.string.memory_analysis_notification_paused_title))
                .setContentText(context.getString(R.string.memory_analysis_notification_progress, scan.percent))
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openToolIntent(context))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .addAction(0, context.getString(R.string.memory_scan_resume), serviceAction(context, ACTION_RESUME, 2))
                .build()

        /**
         * Launch an analysis run inside the service. [analysisType] is the
         * Memory Analysis Type the run should create suggestions for (Step
         * 1.7): "associative" (saved-memory drafts) or "lorebook" (lore book
         * entry suggestions). Returns false when the platform refuses to start
         * the service — in that case no run began, no rows were claimed, and
         * the caller must show a durable failure (never fall back to an
         * Activity-owned run).
         */
        fun start(context: Context, rerunOfRunId: String?, analysisType: String): Boolean =
            startCommand(context, Intent(context, MemoryAnalysisForegroundService::class.java)
                .putExtra(EXTRA_RERUN_ID, rerunOfRunId)
                .putExtra(EXTRA_ANALYSIS_TYPE, analysisType))

        /**
         * Scan for Memories on one chat (owner design, Nov 18 2026). With no
         * run live, the chat's not-yet-scanned part is scanned now. While
         * another run is live, that run finishes the conversation it is on,
         * takes this chat next, then returns to its own work. Same failure
         * contract as [start].
         */
        fun startChatScan(context: Context, chatId: String, analysisType: String): Boolean =
            startCommand(context, Intent(context, MemoryAnalysisForegroundService::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_ANALYSIS_TYPE, analysisType))

        private fun startCommand(context: Context, intent: Intent): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                try {
                    MemoryLog.logAlways(context.applicationContext, "Archivist", "error",
                        "analysis service refused to start: ${e.javaClass.simpleName}: ${e.message} — " +
                            "no run began, no rows were claimed; retry remains available")
                } catch (_: Throwable) { /* logging must never mask the failure */ }
                false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Guards [runActive] together with the hand-over of waiting chat scans,
     *  so a chat scan asked for as the run ends is never dropped. */
    private val runLock = Any()
    private var runActive = false
    /** The newest start command's id, read and written under [runLock]. */
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var runJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(null))
            }
        } catch (e: Exception) {
            // startForeground itself refused: same contract as a start()
            // failure — no run, no claims, durable trace, retry available.
            try {
                MemoryLog.logAlways(applicationContext, "Archivist", "error",
                    "startForeground failed: ${e.javaClass.simpleName}: ${e.message} — " +
                        "no run began, no rows were claimed; retry remains available")
            } catch (_: Throwable) { /* best effort */ }
            state.value = MemoryAnalysisState.Finished(
                serviceFailureOutcome(e, intent?.getStringExtra(EXTRA_ANALYSIS_TYPE) ?: "associative")
            )
            stopSelf()
            return START_NOT_STICKY
        }

        // Cancel (the Memory Scanner Tool's Cancel button). A live run is
        // stopped; its own stop path discards the unfinished progress and
        // delivers the finished state. A paused or interrupted scan is
        // cancelled the same way without starting it again (owner decision,
        // Stage 6A question 6).
        if (intent?.action == ACTION_CANCEL) {
            synchronized(runLock) { lastStartId = startId }
            userCancelRequested = true
            val job = runJob
            if (job != null && job.isActive) {
                job.cancel()
            } else {
                val waiting = suspended.value?.runId
                scope.launch {
                    if (waiting != null && Archivist.cancelSuspended(applicationContext, waiting)) {
                        state.value = MemoryAnalysisState.Finished(
                            Archivist.RunOutcome(
                                waiting, 0, 0, 0, 0, emptyList(), outcome = "cancelled_user"
                            )
                        )
                    }
                    restoreSuspendedNotification(applicationContext)
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }

        // Pause (the notification, the Memory Scanner Tool and the chat
        // header): the live run stops at once and keeps everything.
        if (intent?.action == ACTION_PAUSE) {
            synchronized(runLock) { lastStartId = startId }
            val job = runJob
            if (job != null && job.isActive) {
                pauseRequested = true
                job.cancel()
            } else {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // One run per service lifetime: a second start command while a run is
        // live is ignored here (the screen's guard and the Archivist's
        // durable one-run gate both already prevent it).
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val resuming = intent?.action == ACTION_RESUME
        val startsRun = synchronized(runLock) {
            lastStartId = startId
            if (runActive) {
                // A chat scan asked for during a live run waits for that run
                // to reach its next conversation.
                if (chatId != null) {
                    Archivist.requestChatScan(Archivist.ChatScanRequest(
                        chatId, intent.getStringExtra(EXTRA_ANALYSIS_TYPE) ?: "associative"
                    ))
                }
                false
            } else {
                runActive = true
                true
            }
        }
        if (startsRun) {
            acquireWakeLock()
            userCancelRequested = false
            pauseRequested = false
            timeLimitReached = false
            val rerunOfRunId = intent?.getStringExtra(EXTRA_RERUN_ID)
            val analysisType = intent?.getStringExtra(EXTRA_ANALYSIS_TYPE) ?: "associative"
            val resumeRunId = intent?.getStringExtra(EXTRA_RESUME_RUN_ID)
            val rescan = intent?.getBooleanExtra(EXTRA_RESCAN, false) == true
            state.value = MemoryAnalysisState.Running(null)
            try {
                getSystemService(NotificationManager::class.java)?.cancel(PAUSED_NOTIFICATION_ID)
            } catch (_: Exception) { /* best effort */ }
            runJob = scope.launch {
                val onProgress: (Archivist.Progress) -> Unit = { p ->
                    state.value = MemoryAnalysisState.Running(p)
                    updateNotification(p)
                }
                val stopKind: () -> Archivist.StopKind = {
                    when {
                        userCancelRequested -> Archivist.StopKind.CANCEL
                        pauseRequested -> Archivist.StopKind.PAUSE
                        // Android's time limit and other system stops keep
                        // the scan's progress (Stage 6A question 8).
                        else -> Archivist.StopKind.INTERRUPT
                    }
                }
                val appContext = applicationContext
                var outcome = try {
                    when {
                        resuming -> {
                            val runId = resumeRunId ?: Archivist.suspendedRun(appContext)?.runId
                            if (runId == null) {
                                Archivist.RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "nothing")
                            } else {
                                Archivist.resume(appContext, runId, onProgress, stopKind)
                            }
                        }
                        rescan -> Archivist.rescanUnmigratedChats(appContext, onProgress, stopKind)
                        chatId != null -> Archivist.analyzeChat(appContext, chatId, analysisType, onProgress, stopKind)
                        rerunOfRunId == null -> Archivist.analyze(appContext, analysisType, onProgress, stopKind)
                        else -> Archivist.rerun(appContext, rerunOfRunId, analysisType, onProgress, stopKind)
                    }
                } catch (e: Exception) {
                    Archivist.RunOutcome(
                        null, 0, 0, 0, 0, emptyList(),
                        outcome = "full_failed",
                        failureReason = ArchivistFailure.classify(e),
                        error = e.message,
                        // Carry the requested analysis type through this service-
                        // level failure (Fix #3): a Lorebook run that dies here
                        // must not read back as Associative.
                        analysisType = analysisType
                    )
                }
                // The Archivist reports an in-process stop as the generic
                // "cancelled". Refine it (owner rulings, Aug 1 2026): the user's
                // Cancel is a neutral stop, the Android 15+ dataSync runtime
                // limit is its own state, and anything else stays the generic
                // system stop. The refined value is persisted back onto the
                // durable run record so the Recent Memory Analysis history keeps
                // the distinction.
                if (outcome.outcome == "cancelled") {
                    val refined = when {
                        userCancelRequested -> "cancelled_user"
                        timeLimitReached -> "stopped_time_limit"
                        else -> "cancelled"
                    }
                    if (refined != "cancelled") {
                        outcome = outcome.copy(outcome = refined)
                        outcome.runId?.let { id ->
                            try {
                                // A user cancel or runtime-limit stop is neutral,
                                // so the engine's "interrupted" failure reason is
                                // cleared.
                                MemoryStore.getInstance(appContext)
                                    .updateArchivistRunOutcome(id, refined, null)
                            } catch (e: Exception) {
                                try {
                                    MemoryLog.log(appContext, "Archivist", "error",
                                        "refined stop outcome write failed: ${e.message}")
                                } catch (_: Throwable) { /* best effort */ }
                            }
                        }
                    }
                }
                // Chat scans the run took between its conversations get the
                // same stop refinement on their own records.
                refineStoppedChatScans(appContext, outcome.stoppedChatScanRunIds)
                // "already_running" should be unreachable from here; if it ever
                // happens the LIVE run owns the state. A paused run has no
                // outcome to show: the screens read the suspended scan instead.
                when (outcome.outcome) {
                    "already_running" -> Unit
                    "paused" -> state.value = null
                    else -> state.value = MemoryAnalysisState.Finished(outcome)
                }
                // Chat scans that arrived too late for the run to take them
                // between its conversations run now, one after another. A
                // Cancel, Pause or system stop drops them along with the run.
                var stopId = 0
                while (true) {
                    val next = synchronized(runLock) {
                        val request = if (isActive && !userCancelRequested && !pauseRequested && !timeLimitReached) {
                            Archivist.pollChatScanRequest()
                        } else {
                            Archivist.clearChatScanRequests()
                            null
                        }
                        if (request == null) {
                            runActive = false
                            stopId = lastStartId
                        }
                        request
                    } ?: break
                    state.value = MemoryAnalysisState.Running(null)
                    val chatOutcome = try {
                        Archivist.analyzeChat(appContext, next.chatId, next.analysisType, onProgress, stopKind)
                    } catch (e: Exception) {
                        Archivist.RunOutcome(
                            null, 0, 0, 0, 0, emptyList(),
                            outcome = "full_failed",
                            failureReason = ArchivistFailure.classify(e),
                            error = e.message,
                            analysisType = next.analysisType
                        )
                    }
                    refineStoppedChatScans(appContext, chatOutcome.stoppedChatScanRunIds)
                    val refinedChatOutcome = refineHandedOverStop(appContext, chatOutcome)
                    when (refinedChatOutcome.outcome) {
                        "already_running" -> Unit
                        "paused" -> state.value = null
                        else -> state.value = MemoryAnalysisState.Finished(refinedChatOutcome)
                    }
                }
                restoreSuspendedNotification(appContext)
                // An automatic backup that came due during the scan waited for
                // it; let it run now that the scan is over or paused.
                org.teslasoft.assistant.preferences.backup.AutoBackupScheduling
                    .runAfterMemoryScan(appContext)
                // Only the latest start may stop the service, so a start that
                // arrives after the hand-over above keeps it running.
                stopSelf(stopId)
            }
        }
        // NOT sticky: a resurrected service with no run in flight would hold
        // a notification for nothing — recovery is the durable reconcile.
        return START_NOT_STICKY
    }

    /**
     * The same stop refinement as the main run applies (user Cancel, runtime
     * limit, or a generic system stop), for a chat scan handed over at the end
     * of a run. The refined value is also written to its durable run record.
     */
    private fun refineStoppedChatScans(appContext: Context, runIds: List<String>) {
        val refined = when {
            userCancelRequested -> "cancelled_user"
            timeLimitReached -> "stopped_time_limit"
            else -> return
        }
        for (id in runIds) {
            try {
                MemoryStore.getInstance(appContext).updateArchivistRunOutcome(id, refined, null)
            } catch (_: Exception) { /* the parent run's outcome is unaffected */ }
        }
    }

    private fun refineHandedOverStop(appContext: Context, outcome: Archivist.RunOutcome): Archivist.RunOutcome {
        if (outcome.outcome != "cancelled") return outcome
        val refined = when {
            userCancelRequested -> "cancelled_user"
            timeLimitReached -> "stopped_time_limit"
            else -> return outcome
        }
        outcome.runId?.let { id ->
            try {
                MemoryStore.getInstance(appContext).updateArchivistRunOutcome(id, refined, null)
            } catch (_: Exception) { /* the outcome shown on screen is still refined */ }
        }
        return outcome.copy(outcome = refined)
    }

    /** Android 15+ can time out dataSync services. Cancel the run cleanly —
     *  the Archivist's interruption path keeps filed drafts, releases
     *  claims, and records the run as interrupted — then stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        try {
            MemoryLog.logAlways(applicationContext, "Archivist", "warn",
                "analysis service timed out (system dataSync limit) — run stopped at the " +
                    "runtime limit; unfinished conversations remain available to analyze again")
        } catch (_: Throwable) { /* best effort */ }
        // This stop is specifically the runtime limit, not a generic system
        // stop, so the screen can say "Analysis Time Limit Reached".
        timeLimitReached = true
        scope.cancel()
        stopSelf()
    }

    private fun serviceFailureOutcome(e: Exception, analysisType: String): Archivist.RunOutcome =
        Archivist.RunOutcome(
            null, 0, 0, 0, 0, emptyList(),
            outcome = "full_failed",
            failureReason = ArchivistFailure.UNKNOWN,
            error = "analysis service could not start: ${e.message}",
            // Carry the requested analysis type through the startForeground
            // failure (Fix #3): never default this path to Associative.
            analysisType = analysisType
        )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Safety cap: a large multi-batch analysis can genuinely run
            // long, but a stuck run must not pin the CPU forever. If the cap
            // ever releases mid-run, the run continues at normal background
            // priority and the durable reconcile covers a resulting death.
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { /* ignore */ }
        wakeLock = null
    }

    private fun createChannelIfNeeded() = createChannel(this)

    /** Progress counts and status only — never chat text or proposed-memory
     *  content (§10 1g). */
    private fun buildNotification(progress: Archivist.Progress?): Notification {
        val pi = openToolIntent(this)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(getString(R.string.memory_analysis_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // Pause (Stage 6A): stops the scan at once and keeps its progress.
        builder.addAction(0, getString(R.string.memory_scan_pause), serviceAction(this, ACTION_PAUSE, 1))
        if (progress == null || progress.overallCount <= 0) {
            // Fixed total not yet known: indeterminate bar, no percentage
            // (owner rule: never invent a percentage before the total exists).
            builder.setProgress(0, 0, true)
        } else {
            // Determinate bar with "X% complete": finished conversations over
            // the run's total, or finished sections when the run is one chat
            // (Archivist.Progress.percent). Work in flight never counts.
            val percent = progress.percent()
            builder.setContentText(getString(R.string.memory_analysis_notification_progress, percent))
            builder.setProgress(100, percent, false)
        }
        return builder.build()
    }

    private fun updateNotification(progress: Archivist.Progress) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification(progress))
        } catch (_: Exception) { /* the run matters more than the counter */ }
    }

    override fun onDestroy() {
        // Service teardown cancels the run coroutine; the Archivist's
        // cancellation path records the interruption, keeps filed drafts,
        // and releases claims. A normal completion already finished before
        // stopSelf, so cancel() is a no-op there.
        scope.cancel()
        releaseWakeLock()
        synchronized(runLock) { runActive = false }
        super.onDestroy()
    }
}
