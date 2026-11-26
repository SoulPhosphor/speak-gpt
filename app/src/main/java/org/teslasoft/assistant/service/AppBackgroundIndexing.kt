/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.service

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import org.teslasoft.assistant.conversation.index.BackgroundIndexingController
import org.teslasoft.assistant.conversation.index.BackgroundIndexingPlatform
import org.teslasoft.assistant.conversation.index.BackgroundStopReason
import org.teslasoft.assistant.conversation.index.IndexingLogSink
import org.teslasoft.assistant.conversation.index.IndexingLogText
import org.teslasoft.assistant.conversation.index.IndexingNotificationAccess
import org.teslasoft.assistant.conversation.index.ConversationIndexRuntime
import org.teslasoft.assistant.conversation.index.IndexingConditions
import org.teslasoft.assistant.conversation.index.IndexingDisplay

/**
 * Follows whether any screen of the app is visible and hands the moments the
 * app is left and opened to [BackgroundIndexingController] (Step 13). The
 * service may be started only while the app leaves the screen, which Android
 * allows; a rotation is not leaving the app.
 */
object AppBackgroundIndexing : Application.ActivityLifecycleCallbacks {

    /** Data-sync services the user starts, which background indexing yields to (Owner, 2026-11-24). */
    private val userWork = setOf(
        ImageGenerationForegroundService::class.java.name,
        RestoreForegroundService::class.java.name,
        SummarizerForegroundService::class.java.name,
        MemoryAnalysisForegroundService::class.java.name
    )

    @Volatile private var instance: BackgroundIndexingController? = null

    /** Tests: what the leave decision reads instead of the app's indexing progress and conditions. */
    @Volatile internal var displayForTest: IndexingDisplay? = null
    @Volatile internal var conditionsForTest: IndexingConditions? = null
    @Volatile internal var pendingForTest: Boolean? = null
    private var startedScreens = 0

    fun register(app: Application) {
        controller(app)
        app.registerActivityLifecycleCallbacks(this)
        // A service run that never recorded its stop (the process ended while it ran) is written once.
        guarded { previousRunEnded(app) }
    }

    private const val RUN_MARK_STORE = "conversation_indexing_service"
    private const val RUN_MARK_KEY = "service_run_going"

    private fun runMarks(context: Context) = context.applicationContext.getSharedPreferences(RUN_MARK_STORE, Context.MODE_PRIVATE)

    internal fun previousRunEnded(app: Context) {
        val marks = runMarks(app)
        if (!marks.getBoolean(RUN_MARK_KEY, false)) return
        marks.edit().remove(RUN_MARK_KEY).commit()
        sink(app).error("warning", IndexingLogText.PREVIOUS_SESSION_ENDED)
    }

    /** The runtime's one sink, so every Indexing Log write shares its lock. */
    private fun sink(context: Context): IndexingLogSink = ConversationIndexRuntime.logSink(context)

    /** A reply is still being written, or finished moments ago and its last save may still be on its way. */
    private const val REPLY_GRACE_MILLIS = 15_000L
    @Volatile private var replySeenAt = 0L

    fun controller(context: Context): BackgroundIndexingController =
        instance ?: synchronized(this) {
            instance ?: BackgroundIndexingController(Platform(context.applicationContext), ConversationIndexRuntime.backgroundPermit)
                .also { instance = it }
        }

    override fun onActivityStarted(activity: Activity) {
        if (startedScreens++ == 0) guarded { controller(activity).appOpened() }
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedScreens == 0) return
        if (--startedScreens == 0 && !activity.isChangingConfigurations) {
            guarded { ConversationIndexRuntime.writeSummary(activity, IndexingLogText.SummaryScope.APP_OPEN) }
            guarded { controller(activity).appLeft() }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    /** The index is derived; nothing here may reach a screen. */
    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }

    private class Platform(private val app: Context) : BackgroundIndexingPlatform {

        override fun display(): IndexingDisplay = displayForTest
            ?: ConversationIndexRuntime.progressIfCreated()?.display?.value ?: IndexingDisplay.Hidden

        override fun conditions(): IndexingConditions = conditionsForTest
            ?: ConversationIndexRuntime.progressIfCreated()?.conditions()
                ?: IndexingConditions(settingOn = false, rebuilding = false, recoveryFailed = false, modelUnavailable = false)

        override fun userPaused(): Boolean = ConversationIndexRuntime.progressIfCreated()?.isPaused ?: false

        override fun stalled(): Boolean = ConversationIndexRuntime.progressIfCreated()?.isStalled == true ||
            ConversationIndexRuntime.updateStalledSince(serviceRunRequests)

        override fun stallFailure() = ConversationIndexRuntime.progressIfCreated()?.stallFailure
            ?: ConversationIndexRuntime.vectorStopFailure()

        override fun userWorkRunning(): Boolean = runningServices(app).any { it in userWork }

        override fun pendingWork(): Boolean = waitingWork(app)

        override fun notificationsAllowed(): Boolean =
            ConversationIndexingForegroundService.notificationAccess(app) == IndexingNotificationAccess.ALLOWED

        override fun startService() = ConversationIndexingForegroundService.start(app)

        override fun stopService(reason: BackgroundStopReason) = ConversationIndexingForegroundService.requestStop(reason)

        override fun showStoppedNotice() = ConversationIndexingForegroundService.showPausedNotice(app)

        override fun clearStoppedNotice() = ConversationIndexingForegroundService.clearPausedNotice(app)

        override fun activityLog(message: String) = sink(app).activity(message)

        override fun detailedLog(message: String) = sink(app).detailed(message)

        override fun errorLog(message: String, cause: Throwable?) =
            sink(app).error("error", message, cause, ConversationIndexRuntime.stateForLog(app))

        override fun warningLog(message: String) = sink(app).error("warning", message, null, ConversationIndexRuntime.stateForLog(app))

        /** The vector request count when the latest service run started (updates stall only after it). */
        @Volatile private var serviceRunRequests = Long.MAX_VALUE

        override fun serviceRunMarked(running: Boolean) {
            if (running) serviceRunRequests = ConversationIndexRuntime.vectorStallBaseline()
            try {
                val edit = runMarks(app).edit()
                if (running) edit.putBoolean(RUN_MARK_KEY, true) else edit.remove(RUN_MARK_KEY)
                edit.commit()
            } catch (_: Exception) { }
        }
    }

    /** This app's running services, by class name. */
    private fun runningServices(app: Context): List<String> = try {
        val manager = app.getSystemService(ActivityManager::class.java)
        // Lists only this app's own services.
        @Suppress("DEPRECATION")
        manager?.getRunningServices(Int.MAX_VALUE).orEmpty()
            .filter { it.service.packageName == app.packageName }
            .map { it.service.className }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Whether indexing work waits or is going besides a catch-up (Owner,
     * 2026-11-25): the coordinator's queues and updates, a waiting recovery,
     * vector passes, and a reply still being written, which is followed for a
     * short while after it ends so its last save is not missed. Bounded: a
     * reply that never saves adds at most that short while.
     */
    fun waitingWork(context: Context): Boolean {
        pendingForTest?.let { return it }
        val app = context.applicationContext
        if (ConversationIndexRuntime.hasPendingWork()) return true
        val now = android.os.SystemClock.elapsedRealtime()
        // Only a reply's text being written; reading a reply aloud uses the same keep-alive and is not work for indexing.
        if (GenerationForegroundService.isWritingReply) {
            replySeenAt = now
            return true
        }
        return replySeenAt != 0L && now - replySeenAt < REPLY_GRACE_MILLIS
    }
}
