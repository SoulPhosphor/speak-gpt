/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.service

import android.content.Context
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts memory scanner work that waits for a finished chat reply (Stage
 * 6A requirements and owner decisions 1 and 8):
 *
 *  - an interrupted scan (app closed, phone off, Android's time limit or
 *    another system stop) resumes after a chat reply finishes; it never
 *    delays or blocks starting a chat, because it only begins once the
 *    reply is done, on a worker thread;
 *  - the automatic complete rescan of conversations whose earlier progress
 *    could not be migrated starts the same way, once per app start.
 *
 * A paused scan is never resumed here: it waits for the user's Resume.
 */
object MemoryScanAutoResume {

    private val rescanTried = AtomicBoolean(false)

    /** Called after every finished chat reply, off the main thread. */
    fun onChatReplyFinished(context: Context) {
        try {
            if (Archivist.isRunLive()) return
            if (MemoryAnalysisForegroundService.state.value is MemoryAnalysisState.Running) return
            if (!MemoryStore.isProvisioned(context)) return
            if (DatabaseHealthState.anyDegraded(context) ||
                DatabaseHealthState.isDegraded(context, BackupType.MEMORY)
            ) return
            if (!Archivist.isScannerReady(context)) return
            val interrupted = Archivist.interruptedRun(context)
            if (interrupted != null) {
                MemoryAnalysisForegroundService.requestResume(context, interrupted.runId)
                return
            }
            if (MemoryStore.getInstance(context).chatsNeedingFullRescan().isNotEmpty() &&
                rescanTried.compareAndSet(false, true)
            ) {
                MemoryAnalysisForegroundService.startUnmigratedRescan(context)
            }
        } catch (_: Exception) {
            // Memory work is best-effort and must never disturb the chat.
        }
    }
}
