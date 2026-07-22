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

package org.teslasoft.assistant.preferences.backup

import android.content.Context
import org.teslasoft.assistant.preferences.StartupHealth
import org.teslasoft.assistant.preferences.memory.MemoryStore

/**
 * The Build Phase 3 startup path (§15.2 / §15.3): runs on the housekeeping
 * thread ONLY when the crash-triggered gate fired (abnormal previous exit or
 * an explicitly pending check). All three databases, each independently:
 *
 *   integrity check → damage CONFIRMED → degraded flag set → automatic staged
 *   repair attempt (owner order: repair FIRST, automatically) → on success the
 *   `Database Repaired` disclosure is queued; on failure the store stays off
 *   and the A1 `Database Problem Found!` dialog is queued.
 *
 * Dialogs cannot be shown from Application, so outcomes are queued as pending
 * notices ([DatabaseHealthState]) that the next foreground screen delivers —
 * a missed launch shows them on the following one. This REPLACES the old
 * vanishing Toast (design doc F3).
 *
 * A store that merely cannot be OPENED (locked key) is a health signal, not
 * confirmed damage: nothing is flagged, nothing is repaired. A database file
 * that does not exist is skipped — a health check must never CREATE a store
 * as a side effect at startup.
 */
object StartupDatabaseCheck {

    fun run(context: Context) {
        val appContext = context.applicationContext
        for (type in DatabaseHealthState.databaseTypes) {
            try {
                checkOne(appContext, type)
            } catch (_: Exception) { /* one database's failure never blocks the others */ }
        }
        // The force-next-check flag mirrors "anything still degraded", so a
        // declined repair keeps forcing the startup check until resolved.
        StartupHealth.setIntegrityCheckPending(appContext, DatabaseHealthState.anyDegraded(appContext))
    }

    private fun checkOne(context: Context, type: BackupType) {
        // Already degraded: known bad, dialog already pending — never reopen
        // a confirmed-corrupt file just to re-confirm it.
        if (DatabaseHealthState.isDegraded(context, type)) return
        if (!databaseFileExists(context, type)) return

        val result = when (type) {
            BackupType.MEMORY -> DatabaseHealthChecker.checkMemory(context)
            BackupType.LOREBOOK -> DatabaseHealthChecker.checkLorebook(context)
            BackupType.USER_IMAGE -> DatabaseHealthChecker.checkUserImage(context)
            BackupType.CHATS -> return
        }
        if (result.status != DatabaseHealthChecker.Status.DAMAGED) return

        DatabaseHealthState.markDegraded(context, type, result.detail ?: "integrity check failed")
        val outcome = DatabaseRepairManager.attemptRepair(context, type)
        DatabaseHealthState.setPendingNotice(
            context, type,
            if (outcome.ok) DatabaseHealthState.NOTICE_REPAIRED else DatabaseHealthState.NOTICE_PROBLEM
        )
    }

    private fun databaseFileExists(context: Context, type: BackupType): Boolean = when (type) {
        BackupType.MEMORY -> MemoryStore.isProvisioned(context)
        BackupType.LOREBOOK -> context.getDatabasePath("lorebook.db").exists()
        BackupType.USER_IMAGE -> context.getDatabasePath("profile_images.db").exists()
        BackupType.CHATS -> false
    }
}
