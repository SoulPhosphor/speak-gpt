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
    USER_PAUSED("paused by the user"),
    SETTING_OFF("Index Conversations was turned off"),
    MODEL_UNAVAILABLE("the on-device search model is unavailable"),
    REBUILD_FAILED("the conversation index could not be rebuilt"),
    APP_OPENED("the app was opened"),
    TIME_LIMIT("Android's time limit for background data work was reached"),
    TIME_USED_UP_AT_START("Android did not allow it to start: the time limit for background data work is used up"),
    BACKGROUND_START_NOT_ALLOWED("Android did not allow it to start from the background"),
    UNEXPECTED_FAILURE("an unexpected failure");

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
}

/** What the background service does next. */
sealed interface BackgroundIndexingStep {
    data class Show(val content: BackgroundIndexingContent) : BackgroundIndexingStep
    data class Stop(val reason: BackgroundStopReason) : BackgroundIndexingStep
}

enum class BackgroundStartDecision {
    /** A catch-up with work left runs and is not paused: the service starts. */
    START,

    /** Nothing to continue (no catch-up, paused, off, no model, a failed rebuild). */
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
     * When the app leaves the screen: only an active, unpaused catch-up with
     * work left (shown by the header icon), or a rebuild that is followed by
     * one, is continued. The ordinary update of a changed chat is not.
     */
    fun startDecision(
        display: IndexingDisplay,
        conditions: IndexingConditions,
        paused: Boolean,
        userWorkRunning: Boolean
    ): BackgroundStartDecision {
        if (paused || !conditions.settingOn || conditions.recoveryFailed || conditions.modelUnavailable) {
            return BackgroundStartDecision.NOT_NEEDED
        }
        val work = conditions.rebuilding || display is IndexingDisplay.Running
        return when {
            !work -> BackgroundStartDecision.NOT_NEEDED
            userWorkRunning -> BackgroundStartDecision.YIELD
            else -> BackgroundStartDecision.START
        }
    }

    /** While the service runs: what the notification shows, or why the service stops. */
    fun next(
        display: IndexingDisplay,
        conditions: IndexingConditions,
        paused: Boolean,
        appVisible: Boolean
    ): BackgroundIndexingStep = when {
        appVisible -> BackgroundIndexingStep.Stop(BackgroundStopReason.APP_OPENED)
        paused -> BackgroundIndexingStep.Stop(BackgroundStopReason.USER_PAUSED)
        conditions.recoveryFailed -> BackgroundIndexingStep.Stop(BackgroundStopReason.REBUILD_FAILED)
        !conditions.settingOn -> BackgroundIndexingStep.Stop(BackgroundStopReason.SETTING_OFF)
        conditions.rebuilding -> BackgroundIndexingStep.Show(BackgroundIndexingContent.Rebuilding)
        conditions.modelUnavailable -> BackgroundIndexingStep.Stop(BackgroundStopReason.MODEL_UNAVAILABLE)
        display is IndexingDisplay.Running -> BackgroundIndexingStep.Show(
            display.numbers?.let { BackgroundIndexingContent.Percent(it.percent) } ?: BackgroundIndexingContent.Counting
        )
        else -> BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED)
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
}

/** The approved diagnostic lines of the background service (Owner, 2026-11-24, Step 13). No content is ever written. */
object BackgroundIndexingLog {
    const val STARTED = "Conversation indexing background service started."

    fun stopped(reason: BackgroundStopReason) =
        "Conversation indexing background service stopped: ${reason.text}. Completed progress retained."

    fun failed(cause: String, effect: String, retry: String) =
        "Conversation indexing background service failed: $cause. Effect: $effect. Retry: $retry."

    const val EFFECT_NOT_STARTED = "indexing did not continue after the app was closed; finished work is kept"
    const val EFFECT_STOPPED = "indexing stopped after the app was closed; finished work is kept"
    const val RETRY = "indexing continues when the app is opened, and continuing after the app is closed is tried again the next time it is closed while indexing runs"
}

/**
 * Holds indexing while the app is closed without its background service
 * (Step 13): after Android's time limit, a refused start, an unexpected
 * failure, or while work the user started runs. The coordinator and the
 * vector worker treat it like a pause at their next safe point, so finished
 * work is kept and nothing runs without the notification. It is not the
 * user's pause: it is never saved, and opening the app releases it.
 *
 * Apart from that, a catch-up that would start while the app is closed and
 * no service runs (a restore finishing in the background) waits for the
 * next opening; the ordinary update of a changed chat does not.
 */
class BackgroundIndexingHold {
    @Volatile var held: Boolean = false
        private set

    /** Set by the controller: the app is closed and no indexing service runs. */
    @Volatile internal var catchUpsWait: Boolean = false

    @Volatile private var catchUpWaiting = false

    fun hold() { held = true }

    /** Asked by the coordinator before a catch-up; true when it must wait for the app, which is then remembered. */
    fun catchUpMustWait(): Boolean {
        if (!catchUpsWait) return false
        catchUpWaiting = true
        return true
    }

    /** True when indexing was held or a catch-up waited, so the app continues it. */
    fun release(): Boolean {
        val was = held || catchUpWaiting
        held = false
        catchUpWaiting = false
        catchUpsWait = false
        return was
    }
}

/** What the controller does in the app. */
interface BackgroundIndexingPlatform {
    fun display(): IndexingDisplay
    fun conditions(): IndexingConditions
    fun userPaused(): Boolean
    /** Whether a data-sync service the user started is running. */
    fun userWorkRunning(): Boolean
    /** Asks Android to start the service; throws what Android throws. */
    fun startService()
    /** Asks a running or starting service to stop for [reason]. */
    fun stopService(reason: BackgroundStopReason)
    fun showStoppedNotice()
    fun clearStoppedNotice()
    /** The app opened after a hold: indexing continues from where it stopped. */
    fun resumeIndexing()
    fun logcat(message: String)
    fun errorLog(message: String)
}

/**
 * After-close indexing (Owner, 2026-11-24, Step 13). The service is started
 * only while the app leaves the screen, which Android allows, never later; a
 * catch-up that starts with the app already in the background (a restore
 * finishing) keeps its work for the next opening. Every stop that is not the
 * work being done or the user's own choice holds indexing until the app
 * opens. All calls come from the main thread.
 */
class BackgroundIndexingController(
    private val platform: BackgroundIndexingPlatform,
    val hold: BackgroundIndexingHold,
    private val classify: (Throwable) -> BackgroundStopReason = BackgroundIndexingRule::startFailure
) {
    /** The cause of the last unexpected failure written to the Error Log, so an ongoing failure is written once. */
    private var lastFailure: String? = null

    @Volatile var appVisible: Boolean = false
        private set

    /** Whether a service start was asked for and has not stopped yet. */
    @Volatile var serviceActive: Boolean = false
        private set(value) {
            field = value
            gateCatchUps()
        }

    /** A catch-up may start without the service only while a screen of the app is visible. */
    private fun gateCatchUps() {
        hold.catchUpsWait = !appVisible && !serviceActive
    }

    /** Why the service last stopped or did not start; null before the first time. */
    @Volatile var lastStop: BackgroundStopReason? = null
        private set

    init {
        // A process starts with no screen visible (Android may start it for background work):
        // catch-ups wait until a screen opens.
        gateCatchUps()
    }

    /** The last screen of the app left (not a rotation). */
    fun appLeft() {
        appVisible = false
        gateCatchUps()
        val decision = try {
            BackgroundIndexingRule.startDecision(
                platform.display(), platform.conditions(), platform.userPaused(), platform.userWorkRunning()
            )
        } catch (e: Exception) {
            stoppedUnexpectedly(e, BackgroundIndexingLog.EFFECT_NOT_STARTED)
            return
        }
        when (decision) {
            BackgroundStartDecision.NOT_NEEDED -> Unit
            BackgroundStartDecision.YIELD -> hold.hold()
            BackgroundStartDecision.START -> startService()
        }
    }

    /** Asks Android for the service; a refusal is handled at once. */
    fun startService() {
        try {
            serviceActive = true
            platform.startService()
        } catch (e: Exception) {
            startFailed(e)
        }
    }

    /** A screen of the app started after none was visible. */
    fun appOpened() {
        appVisible = true
        gateCatchUps()
        if (serviceActive) platform.stopService(BackgroundStopReason.APP_OPENED)
        platform.clearStoppedNotice()
        if (hold.release()) platform.resumeIndexing()
    }

    /** The service called startForeground successfully. */
    fun serviceStarted() {
        lastFailure = null
        platform.logcat(BackgroundIndexingLog.STARTED)
    }

    /** Android refused the start, either when asked or in the service's own startForeground. */
    fun startFailed(e: Throwable) {
        serviceActive = false
        val reason = classify(e)
        lastStop = reason
        if (reason == BackgroundStopReason.UNEXPECTED_FAILURE) {
            stoppedUnexpectedly(e, BackgroundIndexingLog.EFFECT_NOT_STARTED)
            return
        }
        // Opened again meanwhile: indexing simply continues in the app.
        if (appVisible) return
        hold.hold()
        platform.logcat(BackgroundIndexingLog.stopped(reason))
        platform.showStoppedNotice()
    }

    /** The service stopped for [reason]; an Android limit holds indexing and explains it. */
    fun serviceStopped(reason: BackgroundStopReason) {
        serviceActive = false
        lastStop = reason
        platform.logcat(BackgroundIndexingLog.stopped(reason))
        if (reason.isAndroidLimit && !appVisible) {
            hold.hold()
            platform.showStoppedNotice()
        }
    }

    /** The running service failed unexpectedly: indexing waits for the app to open. */
    fun serviceFailed(e: Throwable) {
        serviceActive = false
        stoppedUnexpectedly(e, BackgroundIndexingLog.EFFECT_STOPPED)
    }

    private fun stoppedUnexpectedly(e: Throwable, effect: String) {
        lastStop = BackgroundStopReason.UNEXPECTED_FAILURE
        if (!appVisible) hold.hold()
        platform.logcat(BackgroundIndexingLog.stopped(BackgroundStopReason.UNEXPECTED_FAILURE))
        val cause = ConversationIndexErrorLog.cause(e)
        if (cause != lastFailure) {
            lastFailure = cause
            platform.errorLog(BackgroundIndexingLog.failed(cause, effect, BackgroundIndexingLog.RETRY))
        }
    }
}
