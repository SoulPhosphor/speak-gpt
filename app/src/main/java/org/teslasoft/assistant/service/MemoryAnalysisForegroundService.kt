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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistFailure
import org.teslasoft.assistant.ui.activities.MemoryAssistantActivity
import java.util.concurrent.atomic.AtomicBoolean

/** What the Memory Assistant screen renders for the service-owned run.
 *  Running carries the latest progress (null until the first conversation
 *  starts); Finished carries the terminal outcome and is consumed (nulled)
 *  by the screen that displays it — a fresh visit never replays it (owner
 *  behavior rule: Complete! is not sticky across visits). */
sealed interface MemoryAnalysisState {
    data class Running(val progress: Archivist.Progress?) : MemoryAnalysisState
    data class Finished(val outcome: Archivist.RunOutcome) : MemoryAnalysisState
}

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

        val state = MutableStateFlow<MemoryAnalysisState?>(null)

        /**
         * Launch an analysis run inside the service. [analysisType] is the
         * Memory Analysis Type the run should create suggestions for (Step
         * 1.7): "associative" (saved-memory drafts) or "lorebook" (lore book
         * entry suggestions). Returns false when the platform refuses to start
         * the service — in that case no run began, no rows were claimed, and
         * the caller must show a durable failure (never fall back to an
         * Activity-owned run).
         */
        fun start(context: Context, rerunOfRunId: String?, analysisType: String): Boolean {
            val intent = Intent(context, MemoryAnalysisForegroundService::class.java)
                .putExtra(EXTRA_RERUN_ID, rerunOfRunId)
                .putExtra(EXTRA_ANALYSIS_TYPE, analysisType)
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
    private val runActive = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

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
            state.value = MemoryAnalysisState.Finished(serviceFailureOutcome(e))
            stopSelf()
            return START_NOT_STICKY
        }

        // One run per service lifetime: a second start command while a run is
        // live is ignored here (the screen's guard and the Archivist's
        // durable one-run gate both already prevent it — this keeps a
        // redundant command from ever spawning a second coroutine or
        // stopping the live one's foreground state).
        if (runActive.compareAndSet(false, true)) {
            acquireWakeLock()
            val rerunOfRunId = intent?.getStringExtra(EXTRA_RERUN_ID)
            val analysisType = intent?.getStringExtra(EXTRA_ANALYSIS_TYPE) ?: "associative"
            state.value = MemoryAnalysisState.Running(null)
            scope.launch {
                val onProgress: (Archivist.Progress) -> Unit = { p ->
                    state.value = MemoryAnalysisState.Running(p)
                    updateNotification(p)
                }
                val appContext = applicationContext
                val outcome = try {
                    if (rerunOfRunId == null) Archivist.analyze(appContext, analysisType, onProgress)
                    else Archivist.rerun(appContext, rerunOfRunId, analysisType, onProgress)
                } catch (e: Exception) {
                    Archivist.RunOutcome(
                        null, 0, 0, 0, 0, emptyList(),
                        outcome = "full_failed",
                        failureReason = ArchivistFailure.classify(e),
                        error = e.message
                    )
                }
                // "already_running" should be unreachable from here (the
                // gates above), but if it ever happens the LIVE run owns the
                // state — don't clobber it with a losing start's outcome.
                if (outcome.outcome != "already_running") {
                    state.value = MemoryAnalysisState.Finished(outcome)
                }
                stopSelf()
            }
        }
        // NOT sticky: a resurrected service with no run in flight would hold
        // a notification for nothing — recovery is the durable reconcile.
        return START_NOT_STICKY
    }

    /** Android 15+ can time out dataSync services. Cancel the run cleanly —
     *  the Archivist's interruption path keeps filed drafts, releases
     *  claims, and records the run as interrupted — then stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        try {
            MemoryLog.logAlways(applicationContext, "Archivist", "warn",
                "analysis service timed out (system dataSync limit) — run interrupted; " +
                    "unfinished conversations remain available to analyze again")
        } catch (_: Throwable) { /* best effort */ }
        scope.cancel()
        stopSelf()
    }

    private fun serviceFailureOutcome(e: Exception): Archivist.RunOutcome =
        Archivist.RunOutcome(
            null, 0, 0, 0, 0, emptyList(),
            outcome = "full_failed",
            failureReason = ArchivistFailure.UNKNOWN,
            error = "analysis service could not start: ${e.message}"
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

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.memory_analysis_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            description = getString(R.string.memory_analysis_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    /** Progress counts and status only — never chat text or proposed-memory
     *  content (§10 1g). */
    private fun buildNotification(progress: Archivist.Progress?): Notification {
        val openIntent = Intent(this, MemoryAssistantActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(getString(R.string.memory_analysis_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (progress == null || progress.overallCount <= 0) {
            // Fixed total not yet known: indeterminate bar, no percentage
            // (owner rule: never invent a percentage before the total exists).
            builder.setProgress(0, 0, true)
        } else {
            // Determinate bar with "X% complete": completed conversations over
            // the fixed total the run sealed. The conversation in flight is not
            // yet complete, so the bar reaches 100% only after the run finishes.
            val completed = (progress.overallIndex - 1).coerceAtLeast(0)
            val percent = (completed * 100 / progress.overallCount).coerceIn(0, 100)
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
        runActive.set(false)
        super.onDestroy()
    }
}
