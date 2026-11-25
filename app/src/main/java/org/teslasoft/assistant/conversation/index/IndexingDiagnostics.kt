/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.teslasoft.assistant.preferences.Logger

/**
 * Conversation indexing's own diagnostics (Owner, 2026-11-25), kept apart
 * from Logcat, which is never written for indexing:
 *
 * - Failures always go to the Error Log; Indexing Error Details (on by
 *   default) only adds the chain of causes and the indexing state to them.
 * - Indexing Activity (off by default) records meaningful changes of state in
 *   the Indexing Log of the Log Cabin.
 * - Detailed Indexing Diagnostics (off by default) adds the leave decision and
 *   aggregated summaries there. Nothing is written per message, per vector or
 *   by polling.
 *
 * The Indexing Log has its own Maximum Logs Saved and Maximum Days Saved.
 */
object IndexingLogSettings {
    const val ERROR_DETAILS = "indexing_error_details"
    const val ACTIVITY = "indexing_activity_logging"
    const val DETAILED = "indexing_detailed_diagnostics"
    const val MAX_ENTRIES = "indexing_log_max_entries"
    const val MAX_DAYS = "indexing_log_max_days"

    const val ERROR_DETAILS_DEFAULT = true
    const val ACTIVITY_DEFAULT = false
    const val DETAILED_DEFAULT = false

    /** The app's global settings file, where the Logging Preferences switches are saved. */
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun errorDetails(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(ERROR_DETAILS, ERROR_DETAILS_DEFAULT) }.getOrDefault(ERROR_DETAILS_DEFAULT)

    fun activity(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(ACTIVITY, ACTIVITY_DEFAULT) }.getOrDefault(ACTIVITY_DEFAULT)

    fun detailed(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(DETAILED, DETAILED_DEFAULT) }.getOrDefault(DETAILED_DEFAULT)
}

/** The approved wording of the Indexing Log and of the new Error Log entries. No conversation text is ever written. */
object IndexingLogText {

    // --- Error Log ---------------------------------------------------------------

    fun vectorPreparationFailed(cause: String, retry: String) =
        "Conversation indexing could not prepare the on-device search model or the index: $cause. " +
            "Effect: waiting search vectors were not made. Retry: $retry."

    const val PREPARATION_RETRY = "the next time search vectors are asked for, while indexing is on"

    /** An Android limit left work waiting: the notice explains it, and the Error Log keeps what happened. */
    fun pausedByAndroidLimit(reason: BackgroundStopReason, exception: String?) =
        "Conversation indexing paused by an Android limit: ${reason.text}${exception?.let { ": $it" }.orEmpty()}. " +
            "This is an Android limit, not an app error. Unfinished work is waiting and continues when the app is opened. " +
            "Completed progress retained."

    const val PREVIOUS_SESSION_ENDED =
        "Previous indexing session ended without recording a normal stop. Cause unknown. Completed progress retained."

    fun wakeLockFailed(cause: String) =
        "Conversation indexing could not keep the phone awake: $cause. " +
            "Effect: indexing may stop while the screen is off until the app is opened."

    /** The extra detail Indexing Error Details adds. */
    fun details(causes: String?, state: String?): String = buildString {
        causes?.let { append(" Chain of causes: ").append(it).append('.') }
        state?.let { append(" Indexing state: ").append(it).append('.') }
    }

    /** Each distinct cause down an exception's chain, without stack frames. */
    fun causeChain(error: Throwable?): String? {
        if (error == null) return null
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val parts = ArrayList<String>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && parts.size < 6) {
            parts += "${current.javaClass.name}: ${current.message?.ifBlank { null } ?: "no message"}"
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    // --- Indexing Log: activity ---------------------------------------------------

    fun stoppedByAndroidLimitNoInterruption(reason: BackgroundStopReason) =
        "Conversation indexing background service stopped by an Android limit: ${reason.text}. No work was interrupted."

    enum class StartCause(val text: String) {
        TURNED_ON("turned on"),
        APP_STARTED("app started with work left"),
        MODEL_CHANGED("model changed"),
        RESTORED("backup restored"),
        REBUILT("index rebuilt"),
        RESUMED("resumed")
    }

    fun catchUpStarted(cause: StartCause, chats: Int) =
        "Conversation indexing started: ${cause.text}. $chats conversations to check."

    const val CATCH_UP_FINISHED = "Conversation indexing finished: no work left."
    const val PAUSED = "Conversation indexing paused by the user."
    const val RESUMED = "Conversation indexing resumed by the user."
    const val TURNED_ON = "Index Conversations turned on."
    const val TURNED_OFF = "Index Conversations turned off."
    const val DELETED = "Conversation index deleted."

    fun notContinued(reason: String) = "Conversation indexing not continued after the app was closed: $reason."

    const val WAITING_FOR_APP = "Conversation indexing waiting for the app to open: work was left when the app closed."
    const val CONTINUED_ON_OPENING = "Conversation indexing continued when the app was opened."

    // --- Indexing Log: detailed ------------------------------------------------------

    enum class State(val text: String) {
        CATCH_UP("catch-up running"),
        UPDATE("ordinary update"),
        REBUILDING("rebuilding"),
        NOTHING("nothing waiting")
    }

    fun leaveDecision(started: Boolean, state: State, paused: Boolean, userWork: Boolean) =
        "Leaving the app: ${if (started) "started the background service" else "did not start it"}. " +
            "State: ${state.text}; paused ${yesNo(paused)}; work you started running ${yesNo(userWork)}."

    enum class SummaryScope(val text: String) {
        CATCH_UP("catch-up"),
        SERVICE_RUN("background service run"),
        APP_OPEN("updates while the app was open")
    }

    fun summary(scope: SummaryScope, totals: IndexingTotals.Snapshot, waiting: Int?) =
        "Conversation indexing summary (${scope.text}): ${totals.compared} chats compared, " +
            "${totals.written} messages written, ${totals.made} vectors made, ${totals.failed} failed, " +
            "${waiting?.toString() ?: "not known"} waiting, ${totals.passes} vector passes, ${duration(totals.millis)}."

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    internal fun duration(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }
}

/**
 * Counts what indexing did since the last summary, for Detailed Indexing
 * Diagnostics; counting costs nothing and writes nothing. [take] returns the
 * counts and starts over, or null when nothing ran.
 */
class IndexingTotals(private val clock: () -> Long = System::currentTimeMillis) {
    data class Snapshot(val compared: Int, val written: Int, val made: Int, val failed: Int, val passes: Int, val millis: Long)

    private val compared = AtomicInteger()
    private val written = AtomicInteger()
    private val made = AtomicInteger()
    private val failed = AtomicInteger()
    private val passes = AtomicInteger()
    private val since = AtomicLong(clock())

    fun chatCompared(messagesWritten: Int) {
        compared.incrementAndGet()
        written.addAndGet(messagesWritten.coerceAtLeast(0))
    }

    fun vectorPass(madeNow: Int, failedNow: Int) {
        passes.incrementAndGet()
        made.addAndGet(madeNow.coerceAtLeast(0))
        failed.addAndGet(failedNow.coerceAtLeast(0))
    }

    @Synchronized
    fun take(): Snapshot? {
        val snapshot = Snapshot(
            compared.getAndSet(0), written.getAndSet(0), made.getAndSet(0), failed.getAndSet(0), passes.getAndSet(0),
            clock() - since.getAndSet(clock())
        )
        return if (snapshot.compared == 0 && snapshot.passes == 0) null else snapshot
    }
}

/**
 * Where indexing writes its diagnostics. [error] always writes to the Error
 * Log; [activity] and [detailed] write to the Indexing Log only while their
 * switch is on, and never anywhere else.
 */
interface IndexingLogSink {
    fun error(level: String, message: String, cause: Throwable? = null, state: String? = null)
    fun activity(message: String)
    fun detailed(message: String)
    val detailedOn: Boolean

    companion object {
        val NONE = object : IndexingLogSink {
            override fun error(level: String, message: String, cause: Throwable?, state: String?) {}
            override fun activity(message: String) {}
            override fun detailed(message: String) {}
            override val detailedOn = false
        }
    }
}

/** The app's sink: the Error Log ("crash") and the Indexing Log ("indexing") of the Log Cabin. */
class AppIndexingLogSink(context: Context) : IndexingLogSink {
    private val app = context.applicationContext

    override fun error(level: String, message: String, cause: Throwable?, state: String?) {
        val text = if (IndexingLogSettings.errorDetails(app)) {
            message + IndexingLogText.details(IndexingLogText.causeChain(cause), state)
        } else message
        write("crash", level, text)
    }

    override fun activity(message: String) {
        if (IndexingLogSettings.activity(app)) write(Logger.INDEXING_LOG_TYPE, "info", message)
    }

    override fun detailed(message: String) {
        if (IndexingLogSettings.detailed(app)) write(Logger.INDEXING_LOG_TYPE, "debug", message)
    }

    override val detailedOn: Boolean get() = IndexingLogSettings.detailed(app)

    private fun write(type: String, level: String, message: String) {
        // The Error Log shares the lock the rest of indexing writes it under.
        if (type == "crash") return ConversationIndexRuntime.log(app, level, message)
        try {
            // One lock for the whole Indexing Log, whoever writes it: each write reads and replaces the log.
            synchronized(INDEXING_LOG_LOCK) { Logger.log(app, type, ConversationIndexErrorLog.TAG, level, message) }
        } catch (_: Throwable) { }
    }

    private companion object {
        val INDEXING_LOG_LOCK = Any()
    }
}
