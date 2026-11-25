/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

/**
 * Why the indexing background service stopped or did not start (Step 13).
 * [text] is the {actual reason} of the approved Logcat line; each cause is
 * named for what it is, and none is called damage.
 */
enum class BackgroundStopReason(val text: String) {
    CATCH_UP_ENDED("the catch-up has no work left"),
    UPDATES_DONE("the waiting updates are finished"),
    NOTIFICATIONS_OFF("notifications are turned off"),
    USER_PAUSED("paused by the user"),
    SETTING_OFF("Index Conversations was turned off"),
    MODEL_UNAVAILABLE("the on-device search model is unavailable"),
    REBUILD_FAILED("the conversation index could not be rebuilt"),
    APP_OPENED("the app was opened"),
    NOT_CONTINUED("indexing is not being continued after the app was closed"),
    INDEXING_FAILED("an error stopped processing"),
    USER_WORK_RUNNING("work the user started is running; indexing continues when the app is opened"),
    TIME_LIMIT("Android's time limit for background data work was reached"),
    TIME_USED_UP_AT_START("Android did not allow it to start: the time limit for background data work is used up"),
    BACKGROUND_START_NOT_ALLOWED("Android did not allow it to start from the background"),
    UNEXPECTED_FAILURE("an unexpected failure"),
    UNEXPECTED_FAILURE_AT_START("an unexpected failure kept it from starting");

    /** Android's own limits, which the approved "Open the app to continue" notification explains. */
    val isAndroidLimit: Boolean
        get() = this == TIME_LIMIT || this == TIME_USED_UP_AT_START || this == BACKGROUND_START_NOT_ALLOWED
}

/** What the indexing notification shows (Owner, 2026-11-24, Step 13). */
sealed interface BackgroundIndexingContent {
    /** "Counting messages to index…" with a moving bar and no percentage. */
    data object Counting : BackgroundIndexingContent

    /** "Rebuilding conversation index…" with a moving bar and no percentage. */
    data object Rebuilding : BackgroundIndexingContent

    /** "42% done. Tap to open." with the bar filled to [percent]. */
    data class Percent(val percent: Int) : BackgroundIndexingContent

    /**
     * "Updating conversation index…" with a moving bar: ordinary updates that
     * have no meaningful percentage (Owner, 2026-11-25). Android may hold its
     * notification back for a short update; indexing is never delayed for that.
     */
    data object Updating : BackgroundIndexingContent
}

/** What the background service does next. */
sealed interface BackgroundIndexingStep {
    data class Show(val content: BackgroundIndexingContent) : BackgroundIndexingStep
    data class Stop(val reason: BackgroundStopReason) : BackgroundIndexingStep
}

enum class BackgroundStartDecision {
    /** Indexing work waits and is not paused: the service starts. */
    START,

    /** Nothing to continue (no work waiting, paused, off, no model, a failed rebuild, notifications off). */
    NOT_NEEDED,

    /** Work the user started (image generation, restore, Summarizer, Memory Assistant analysis) runs: indexing waits for the app to open. */
    YIELD
}

/**
 * The choices of after-close indexing (Owner, 2026-11-24, Step 13), as plain
 * functions so they are tested on the JVM. They use the Step 12 progress
 * ([IndexingDisplay]) and the Step 11 conditions, the same state the header
 * icon and pop-up show.
 */
object BackgroundIndexingRule {

    /**
     * When the app leaves the screen, all legitimate waiting indexing work is
     * continued by the service (Owner, 2026-11-25): a catch-up with work left,
     * a rebuild or a waiting recovery, and [pendingWork] (a changed chat, an
     * update part-way through, search vectors still to make, a reply still
     * being written). Not while paused, off, without the model, after a failed
     * rebuild, stalled, or with notifications turned off; work the user
     * started comes first.
     */
    fun startDecision(
        display: IndexingDisplay,
        conditions: IndexingConditions,
        paused: Boolean,
        userWorkRunning: Boolean,
        /** A catch-up that cannot go on by itself is not continued: the service would stop at once. */
        stalled: Boolean = false,
        pendingWork: Boolean = false,
        /** Without a visible notification, indexing waits for the app (Owner, 2026-11-25). */
        notificationsAllowed: Boolean = true
    ): BackgroundStartDecision {
        if (paused || stalled || !conditions.settingOn || conditions.recoveryFailed || conditions.modelUnavailable) {
            return BackgroundStartDecision.NOT_NEEDED
        }
        val work = conditions.rebuilding || display is IndexingDisplay.Running || pendingWork
        return when {
            !work -> BackgroundStartDecision.NOT_NEEDED
            userWorkRunning -> BackgroundStartDecision.YIELD
            !notificationsAllowed -> BackgroundStartDecision.NOT_NEEDED
            else -> BackgroundStartDecision.START
        }
    }

    /**
     * Why waiting work was not continued when the app was left, for the
     * Indexing Log; null when nothing waited or the service starts.
     */
    fun notContinuedReason(
        display: IndexingDisplay,
        conditions: IndexingConditions,
        paused: Boolean,
        userWorkRunning: Boolean,
        stalled: Boolean,
        pendingWork: Boolean,
        notificationsAllowed: Boolean
    ): String? {
        val work = conditions.rebuilding || display is IndexingDisplay.Running || pendingWork
        if (!work) return null
        return when {
            paused -> "paused"
            !conditions.settingOn || conditions.recoveryFailed -> "turned off"
            conditions.modelUnavailable -> "model unavailable"
            stalled -> "stopped after an error"
            userWorkRunning -> "work you started is running"
            !notificationsAllowed -> "notifications are turned off"
            else -> null
        }
    }

    /** While the service runs: what the notification shows, or why the service stops. */
    fun next(
        display: IndexingDisplay,
        conditions: IndexingConditions,
        paused: Boolean,
        appVisible: Boolean,
        /** The catch-up's last vector pass failed and nothing will start another by itself. */
        stalled: Boolean = false,
        /** Whether indexing is being continued by the service; false when a later leave decided against it. */
        continued: Boolean = true,
        /** Ordinary waiting work besides a catch-up ([startDecision]). */
        pendingWork: Boolean = false,
        /** Without a visible notification the service stops and indexing waits for the app. */
        notificationsAllowed: Boolean = true,
        /** A catch-up ran during this service run, so its end is reported as the catch-up's. */
        hadCatchUp: Boolean = true
    ): BackgroundIndexingStep = when {
        appVisible -> BackgroundIndexingStep.Stop(BackgroundStopReason.APP_OPENED)
        // A service whose start arrived after indexing was closed never runs with nothing to show for it.
        !continued -> BackgroundIndexingStep.Stop(BackgroundStopReason.NOT_CONTINUED)
        paused -> BackgroundIndexingStep.Stop(BackgroundStopReason.USER_PAUSED)
        conditions.recoveryFailed -> BackgroundIndexingStep.Stop(BackgroundStopReason.REBUILD_FAILED)
        !conditions.settingOn -> BackgroundIndexingStep.Stop(BackgroundStopReason.SETTING_OFF)
        !notificationsAllowed -> BackgroundIndexingStep.Stop(BackgroundStopReason.NOTIFICATIONS_OFF)
        conditions.rebuilding -> BackgroundIndexingStep.Show(BackgroundIndexingContent.Rebuilding)
        conditions.modelUnavailable -> BackgroundIndexingStep.Stop(BackgroundStopReason.MODEL_UNAVAILABLE)
        // Work that cannot go on by itself never keeps the service and the phone awake.
        stalled -> BackgroundIndexingStep.Stop(BackgroundStopReason.INDEXING_FAILED)
        display is IndexingDisplay.Running -> BackgroundIndexingStep.Show(
            display.numbers?.let { BackgroundIndexingContent.Percent(it.percent) } ?: BackgroundIndexingContent.Counting
        )
        pendingWork -> BackgroundIndexingStep.Show(BackgroundIndexingContent.Updating)
        hadCatchUp -> BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED)
        else -> BackgroundIndexingStep.Stop(BackgroundStopReason.UPDATES_DONE)
    }

    /**
     * Why starting the service failed. Android refuses a data-sync service
     * with `ForegroundServiceStartNotAllowedException`: when the day's time is
     * used up ("Time limit already exhausted…") or when the app may not start
     * one from the background. Anything else is unexpected.
     */
    fun startFailure(e: Throwable): BackgroundStopReason = startFailure(e.javaClass.name, e.message)

    fun startFailure(exceptionType: String, message: String?): BackgroundStopReason {
        if (exceptionType != START_NOT_ALLOWED) return BackgroundStopReason.UNEXPECTED_FAILURE
        val text = message.orEmpty().lowercase()
        return if ("time limit" in text || "exhausted" in text) BackgroundStopReason.TIME_USED_UP_AT_START
        else BackgroundStopReason.BACKGROUND_START_NOT_ALLOWED
    }

    const val START_NOT_ALLOWED = "android.app.ForegroundServiceStartNotAllowedException"

    /** NotificationCompat.FOREGROUND_SERVICE_DEFAULT and FOREGROUND_SERVICE_IMMEDIATE. */
    const val NOTIFICATION_TIMING_DEFAULT = 0
    const val NOTIFICATION_TIMING_IMMEDIATE = 1

    /**
     * A catch-up, counting or rebuild shows at once. An ordinary update uses
     * Android's usual timing, which may hold the notification back while the
     * work is short (Owner, 2026-11-25); indexing itself is never delayed.
     */
    fun foregroundBehavior(content: BackgroundIndexingContent?): Int =
        if (content == BackgroundIndexingContent.Updating) NOTIFICATION_TIMING_DEFAULT else NOTIFICATION_TIMING_IMMEDIATE
}

/** The approved diagnostic lines of the background service (Owner, 2026-11-24, Step 13). No content is ever written. */
object BackgroundIndexingLog {
    const val STARTED = "Conversation indexing background service started."

    fun stopped(reason: BackgroundStopReason) =
        "Conversation indexing background service stopped: ${reason.text}. Completed progress retained."

    /**
     * The stop when indexing cannot go on in this session (Owner, 2026-11-25):
     * the stage and the actual cause; the unfinished work is kept and continued
     * the next time the app is opened, unless indexing is paused or turned off.
     */
    fun processingStopped(failure: VectorPassFailure?) =
        "Conversation Indexing: Processing stopped after an error it cannot recover from in this session, " +
            "while ${failure?.stage?.text ?: "making search vectors"}. " +
            "Cause: ${failure?.cause ?: "not recorded"}. " +
            "The unfinished work is kept and continues the next time the app is opened, " +
            "unless indexing is paused or turned off."

    fun failed(cause: String, effect: String, retry: String) =
        "Conversation indexing background service failed: $cause. Effect: $effect. Retry: $retry."

    const val EFFECT_NOT_STARTED = "indexing did not continue after the app was closed; finished work is kept"
    const val EFFECT_STOPPED = "indexing stopped after the app was closed; finished work is kept"
    const val RETRY = "indexing continues when the app is opened, and continuing after the app is closed is tried again the next time it is closed while indexing runs"
}

/**
 * Whether conversation indexing may run now (Step 13): the one owner of that
 * state. Indexing runs while a screen of the app is visible ([State.FOREGROUND])
 * or while its background service continues it ([State.SERVICE]). While the app
 * is closed without the service ([State.CLOSED]; also how a process starts,
 * before any screen) nothing is counted, compared, cut or read: the coordinator
 * and the vector worker stop at their next safe point and keep every queue, so
 * no work is ever dropped. Whenever the state becomes one that may run again
 * after an indexing thread was refused, [onMayRunAgain] is called once,
 * outside the lock, and continues whatever waited. This is not the user's pause, which stays separate and saved.
 */
class BackgroundIndexingPermit {
    enum class State { FOREGROUND, SERVICE, CLOSED }

    private val lock = Any()
    @Volatile private var state = State.CLOSED
    @Volatile private var onMayRunAgain: () -> Unit = {}
    /** An indexing thread was refused since indexing last could run, so something waits. Guarded by [lock]. */
    private var refused = false

    val current: State get() = state

    /**
     * Checked by the indexing threads at every safe point. A refusal is
     * remembered in the same locked step that a change of state reads it, so
     * waiting work is always continued, and nothing is started when nothing waited.
     */
    fun mayRun(): Boolean {
        if (state != State.CLOSED) return true
        return synchronized(lock) {
            if (state == State.CLOSED) { refused = true; false } else true
        }
    }

    fun setOnMayRunAgain(listener: () -> Unit) { onMayRunAgain = listener }

    /** The app's screen became visible. */
    fun foreground() = moveTo(State.FOREGROUND)

    /** The app is closed and its background service continues indexing. */
    fun service() = moveTo(State.SERVICE)

    /**
     * The app is closed and nothing continues indexing. [waiting]: work is
     * known to wait (a catch-up is going), so it is continued when indexing may
     * run again even if no indexing thread asks meanwhile (a stalled catch-up).
     */
    fun closed(waiting: Boolean = false) {
        synchronized(lock) {
            state = State.CLOSED
            if (waiting) refused = true
        }
    }

    /**
     * The background service stopped or could not start: indexing waits, unless
     * a screen opened meanwhile. Returns the state before, so a caller can tell
     * whether the service was what let indexing run. [waiting] as for [closed].
     */
    fun closedUnlessForeground(waiting: Boolean = false): State = synchronized(lock) {
        val was = state
        if (was != State.FOREGROUND) {
            state = State.CLOSED
            if (waiting) refused = true
        }
        was
    }

    private fun moveTo(next: State) {
        val mayRunAgain = synchronized(lock) {
            val was = state
            state = next
            (was == State.CLOSED && next != State.CLOSED && refused).also { if (it) refused = false }
        }
        if (mayRunAgain) onMayRunAgain()
    }
}

/** What the controller does in the app. */
interface BackgroundIndexingPlatform {
    fun display(): IndexingDisplay
    fun conditions(): IndexingConditions
    fun userPaused(): Boolean
    /** Whether the catch-up cannot go on by itself ([IndexProgressTracker.isStalled]). */
    fun stalled(): Boolean = false
    /** What stopped a stalled catch-up ([IndexProgressTracker.stallFailure]). */
    fun stallFailure(): VectorPassFailure? = null
    /** Whether a data-sync service the user started is running. */
    fun userWorkRunning(): Boolean
    /** Ordinary waiting indexing work besides a catch-up ([BackgroundIndexingRule.startDecision]). */
    fun pendingWork(): Boolean = false
    /** Whether the indexing notification can be seen: the app's notifications and its channel are on. */
    fun notificationsAllowed(): Boolean = true
    /** Asks Android to start the service; throws what Android throws. */
    fun startService()
    /** Asks a running or starting service to stop for [reason]. */
    fun stopService(reason: BackgroundStopReason)
    fun showStoppedNotice()
    fun clearStoppedNotice()
    /** Indexing Log, while Indexing Activity is on; never Logcat. */
    fun activityLog(message: String)
    /** Indexing Log, while Detailed Indexing Diagnostics is on. */
    fun detailedLog(message: String) {}
    /** Error Log, always: an actual failure. */
    fun errorLog(message: String, cause: Throwable? = null)
    /** Error Log, always: an Android limit that left work waiting, which is not an error. */
    fun warningLog(message: String) {}
    /** Remembers that a service run is going, so one that ends without a stop is found at the next start. */
    fun serviceRunMarked(running: Boolean) {}
}

/**
 * After-close indexing (Owner, 2026-11-24, Step 13). Turns the app's
 * visibility and the service's life into [BackgroundIndexingPermit] states,
 * and shows the notice and writes the logs. The service is asked for only
 * while the app leaves the screen, which Android allows, never later; the
 * decision is made while indexing may still run, so a running catch-up is
 * never stopped before its service is asked for. All calls come from the
 * main thread.
 */
class BackgroundIndexingController(
    private val platform: BackgroundIndexingPlatform,
    val permit: BackgroundIndexingPermit,
    private val classify: (Throwable) -> BackgroundStopReason = BackgroundIndexingRule::startFailure
) {
    /** The cause of the last unexpected failure written to the Error Log, so an ongoing failure is written once. */
    private var lastFailure: String? = null

    /** Whether a screen of the app is visible (read by the service on the index's thread). */
    val appVisible: Boolean get() = permit.current == BackgroundIndexingPermit.State.FOREGROUND

    /** Whether the background service is what lets indexing run now (read by the service). */
    val continuedByService: Boolean get() = permit.current == BackgroundIndexingPermit.State.SERVICE

    /** Whether a service start was asked for and has not stopped yet. Main thread. */
    var serviceActive: Boolean = false
        private set

    /** Why the service last stopped or did not start; null before the first time. */
    @Volatile var lastStop: BackgroundStopReason? = null
        private set

    /** The last screen of the app left (not a rotation). */
    fun appLeft() {
        var notContinued: String? = null
        var state = IndexingLogText.State.NOTHING
        var paused = false
        var userWork = false
        val decision = try {
            val display = platform.display()
            val conditions = platform.conditions()
            paused = platform.userPaused()
            userWork = platform.userWorkRunning()
            val stalled = platform.stalled()
            val pending = platform.pendingWork()
            val notifications = platform.notificationsAllowed()
            state = when {
                conditions.rebuilding -> IndexingLogText.State.REBUILDING
                display is IndexingDisplay.Running -> IndexingLogText.State.CATCH_UP
                pending -> IndexingLogText.State.UPDATE
                else -> IndexingLogText.State.NOTHING
            }
            notContinued = BackgroundIndexingRule.notContinuedReason(
                display, conditions, paused, userWork, stalled, pending, notifications
            )
            BackgroundIndexingRule.startDecision(display, conditions, paused, userWork, stalled, pending, notifications)
        } catch (e: Exception) {
            permit.closed(waiting = true)
            stoppedUnexpectedly(e, BackgroundIndexingLog.EFFECT_NOT_STARTED)
            return
        }
        if (state != IndexingLogText.State.NOTHING) {
            platform.detailedLog(IndexingLogText.leaveDecision(decision == BackgroundStartDecision.START, state, paused, userWork))
        }
        when (decision) {
            BackgroundStartDecision.START -> {
                permit.service()
                startService()
            }
            // Work the user started comes first; nothing else to continue: indexing waits for the app.
            BackgroundStartDecision.YIELD, BackgroundStartDecision.NOT_NEEDED -> {
                permit.closed(waiting = workGoing())
                notContinued?.let { platform.activityLog(IndexingLogText.notContinued(it)) }
                // A start asked for on an earlier leave and not yet stopped ends with this leave's reason.
                if (serviceActive) platform.stopService(notStartedReason(decision))
            }
        }
    }

    private fun stallFailureSafely(): VectorPassFailure? = try { platform.stallFailure() } catch (_: Exception) { null }

    /** Whether indexing work is going or waits, so the next opening must continue it whatever else happens meanwhile. */
    private fun workGoing(): Boolean = try {
        // Work stopped by a failure still waits: the next opening asks for it again.
        platform.display() is IndexingDisplay.Running || platform.conditions().rebuilding || platform.pendingWork() ||
            platform.stalled()
    } catch (_: Exception) {
        true
    }

    private fun notStartedReason(decision: BackgroundStartDecision): BackgroundStopReason =
        if (decision == BackgroundStartDecision.YIELD) BackgroundStopReason.USER_WORK_RUNNING
        else (BackgroundIndexingRule.next(
            platform.display(), platform.conditions(), platform.userPaused(), appVisible = false, stalled = platform.stalled(),
            pendingWork = platform.pendingWork(), notificationsAllowed = platform.notificationsAllowed()
        ) as? BackgroundIndexingStep.Stop)?.reason ?: BackgroundStopReason.CATCH_UP_ENDED

    /** Asks Android for the service; a refusal is handled at once. */
    fun startService() {
        serviceActive = true
        try {
            platform.startService()
        } catch (e: Exception) {
            startFailed(e)
        }
    }

    /** A screen of the app started after none was visible: indexing continues in the app. */
    fun appOpened() {
        permit.foreground()
        if (serviceActive) platform.stopService(BackgroundStopReason.APP_OPENED)
        platform.clearStoppedNotice()
    }

    /** The service called startForeground successfully. */
    fun serviceStarted() {
        platform.serviceRunMarked(true)
        platform.activityLog(BackgroundIndexingLog.STARTED)
    }

    /** Android refused the start, either when asked or in the service's own startForeground. */
    fun startFailed(e: Throwable) {
        serviceActive = false
        val reason = classify(e)
        lastStop = reason
        val was = permit.closedUnlessForeground(waiting = workGoing())
        if (reason == BackgroundStopReason.UNEXPECTED_FAILURE) {
            stoppedUnexpectedly(e, BackgroundIndexingLog.EFFECT_NOT_STARTED)
            if (was == BackgroundIndexingPermit.State.SERVICE) waitingForTheApp(showNotice = true)
            return
        }
        // Opened again meanwhile, or a later leave decided not to continue: no work was interrupted.
        if (was != BackgroundIndexingPermit.State.SERVICE) {
            platform.activityLog(IndexingLogText.stoppedByAndroidLimitNoInterruption(reason))
            return
        }
        // Work waits because of Android's limit: not an app error, but kept in the Error Log to explain it.
        platform.warningLog(IndexingLogText.pausedByAndroidLimit(reason, ConversationIndexErrorLog.cause(e)))
        waitingForTheApp(showNotice = true)
    }

    /** The service stopped for [reason]; an Android limit while the app is closed is explained. */
    fun serviceStopped(reason: BackgroundStopReason) {
        serviceActive = false
        lastStop = reason
        platform.serviceRunMarked(false)
        // A run that ended normally: a failure seen again later is a new one.
        lastFailure = null
        val was = permit.closedUnlessForeground(waiting = workGoing())
        val interrupted = was == BackgroundIndexingPermit.State.SERVICE && workGoingSafely()
        when {
            reason == BackgroundStopReason.INDEXING_FAILED ->
                platform.errorLog(BackgroundIndexingLog.processingStopped(stallFailureSafely()))
            reason.isAndroidLimit && interrupted -> platform.warningLog(IndexingLogText.pausedByAndroidLimit(reason, null))
            reason.isAndroidLimit -> platform.activityLog(IndexingLogText.stoppedByAndroidLimitNoInterruption(reason))
            else -> platform.activityLog(BackgroundIndexingLog.stopped(reason))
        }
        if (reason.isAndroidLimit && was == BackgroundIndexingPermit.State.SERVICE) waitingForTheApp(showNotice = true)
        else if (interrupted && reason != BackgroundStopReason.APP_OPENED) waitingForTheApp(showNotice = false)
    }

    /** The running service failed unexpectedly: indexing waits for the app to open. */
    fun serviceFailed(e: Throwable) {
        serviceActive = false
        platform.serviceRunMarked(false)
        val was = permit.closedUnlessForeground(waiting = workGoing())
        stoppedUnexpectedly(e, BackgroundIndexingLog.EFFECT_STOPPED)
        if (was == BackgroundIndexingPermit.State.SERVICE) waitingForTheApp(showNotice = true)
    }

    /** Work was left when the service ended: the next opening continues it. */
    private fun waitingForTheApp(showNotice: Boolean) {
        if (!workGoingSafely()) return
        platform.activityLog(IndexingLogText.WAITING_FOR_APP)
        if (showNotice) platform.showStoppedNotice()
    }

    private fun workGoingSafely(): Boolean = try {
        platform.display() is IndexingDisplay.Running || platform.conditions().rebuilding || platform.pendingWork() ||
            platform.stalled()
    } catch (_: Exception) {
        false
    }

    private fun stoppedUnexpectedly(e: Throwable, effect: String) {
        lastStop = BackgroundStopReason.UNEXPECTED_FAILURE
        val reason = if (effect == BackgroundIndexingLog.EFFECT_NOT_STARTED) BackgroundStopReason.UNEXPECTED_FAILURE_AT_START
        else BackgroundStopReason.UNEXPECTED_FAILURE
        platform.activityLog(BackgroundIndexingLog.stopped(reason))
        val cause = ConversationIndexErrorLog.cause(e)
        if (cause != lastFailure) {
            lastFailure = cause
            platform.errorLog(BackgroundIndexingLog.failed(cause, effect, BackgroundIndexingLog.RETRY), e)
        }
    }
}

/**
 * Whether the indexing notification can be seen (Owner, 2026-11-25). Without
 * it, background indexing waits for the app; indexing in the app is unchanged.
 */
enum class IndexingNotificationAccess {
    ALLOWED,
    /** The app's notifications are turned off (Android 13 and later: the permission is denied). */
    APP_OFF,
    /** Only the Indexing Conversations notifications are turned off. */
    CHANNEL_OFF;

    companion object {
        /** [channelImportanceNone]: the channel exists and is set to none; a channel not made yet is allowed. */
        fun of(appNotificationsEnabled: Boolean, channelImportanceNone: Boolean): IndexingNotificationAccess = when {
            !appNotificationsEnabled -> APP_OFF
            channelImportanceNone -> CHANNEL_OFF
            else -> ALLOWED
        }
    }
}
