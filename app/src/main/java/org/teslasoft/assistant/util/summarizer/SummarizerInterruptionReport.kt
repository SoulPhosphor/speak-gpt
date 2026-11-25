/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import android.content.Context
import org.teslasoft.assistant.conversation.index.SavedChatIndexSource
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.service.DataSyncTimeoutLog

/**
 * At start-up, reports each Summarizer operation the previous app session
 * left unfinished (Owner, 2026-11-25): once in the Error Log and, for a chat
 * that still exists, once in its Summarizer Errors with the usual badge. The
 * cause is never stated, because it is not known. Off the main thread.
 */
object SummarizerInterruptionReport {

    private const val TAG = "SummarizerService"

    fun errorLogLine(interrupted: SummarizerRunRecords.Interrupted): String =
        "Summarizer Service: ${DataSyncTimeoutLog.summarizerKind(interrupted.kind.name)} for chat " +
            "${interrupted.chatId} did not finish. The previous app session ended while it was running. " +
            "Cause unknown. " +
            if (interrupted.kind == SummarizerController.OperationKind.SUMMARIZING) {
                "Finished sections were kept; the unfinished section was not saved."
            } else {
                "Nothing from it was saved."
            }

    /** The chat's Summarizer Errors with the interruption added as a new entry. */
    fun withInterruption(
        current: List<SummarizerErrorEntry>,
        kind: SummarizerController.OperationKind,
        timestamp: Long,
        model: String
    ): List<SummarizerErrorEntry> {
        val recorded = SummarizerErrorLog.record(
            current, "", SummarizerErrorCategory.INTERRUPTED, timestamp, "Not Reported", model, null
        ).entries
        return recorded.mapIndexed { i, entry -> if (i == 0) entry.copy(operation = kind.name) else entry }
    }

    fun reportAtStartup(context: Context) {
        val app = context.applicationContext
        val interrupted = SummarizerRunRecords.takeInterrupted(
            app,
            savedMark = { chatId, kind -> Preferences.getPreferences(app, chatId).getSummarizerRunSaved(kind.name) },
            liveChats = { SavedChatIndexSource(app).chatIdsNewestFirst()?.toSet() }
        )
        if (interrupted.isEmpty()) return
        val live = SavedChatIndexSource(app).chatIdsNewestFirst()?.toSet().orEmpty()
        for (item in interrupted) {
            try {
                Logger.log(app, "crash", TAG, "warning", errorLogLine(item))
            } catch (_: Throwable) { }
            if (item.chatId !in live) continue
            try {
                val prefs = Preferences.getPreferences(app, item.chatId)
                val entries = withInterruption(
                    SummarizerErrorLog.fromJson(prefs.getSummarizerErrors()),
                    item.kind, System.currentTimeMillis(), prefs.getSummarizerModel()
                )
                prefs.setSummarizerErrors(SummarizerErrorLog.toJson(entries))
                // A compaction or summary leaves the automatic summary behind, as its failures do.
                if (item.kind != SummarizerController.OperationKind.OUTLINING) {
                    prefs.setSummarizerEpisode(SummarizerErrorCategory.INTERRUPTED.name)
                }
                prefs.setSummarizerErrorsUnseen(true)
            } catch (_: Exception) { /* the Error Log entry stands */ }
        }
    }
}
