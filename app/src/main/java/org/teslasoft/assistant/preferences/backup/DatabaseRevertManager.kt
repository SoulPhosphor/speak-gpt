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
import org.teslasoft.assistant.preferences.memory.MemorySeedCodec
import org.teslasoft.assistant.preferences.memory.MemoryStore
import java.io.File

/**
 * "Revert to Last Good Database" (Build Phase 3 item 4; §15.2b + §15.6):
 * verify-first, confirm-second, then a quarantine-preserving replacement.
 *
 * WHAT THE WALK ACTUALLY WALKS (honesty note, July 22 2026): the only
 * recovery backups that exist on-device and are ENUMERABLE without a user
 * file-pick are the rotating memory JSON exports in `memory_backups/`
 * (MemoryExporter — written daily for months of real use). The Build Phase 2
 * per-type `.dbbackup` writer never shipped (its controls were hidden by the
 * July 22 owner correction and its separate-artifact naming is dormant), and
 * the v2 portable recovery packages are saved through Save As, which grants
 * no enumerable folder access. So today:
 *  - MEMORY walks the JSON export chain — real protection, restorable into a
 *    fresh store via MemorySeedCodec/importData (memory data only; chats are
 *    not restored by this path and are not part of this database).
 *  - LOREBOOK / USER_IMAGE have no enumerable backup source yet; the walk
 *    honestly returns nothing and the flow falls through to §15.6 step 4
 *    (fresh empty database + the A6 disclosure). When the approved automatic
 *    per-type system lands, its candidates plug in here.
 *
 * Verification = the backup must PARSE as a complete seed export before it is
 * offered (§15.6 step 3 — never "restore" from a torn file onto a damaged
 * store). The A5 confirmation (UI layer) then names this backup's real date
 * and the loss noun before anything is overwritten.
 */
object DatabaseRevertManager {

    data class Candidate(val file: File, val backupAtMillis: Long)

    /** A candidate that passed verification and may be offered in A5. */
    data class Verified(val candidate: Candidate)

    private fun backupDir(context: Context): File =
        File(context.getExternalFilesDir(null), "memory_backups")

    /** Enumerable, newest-first candidates for [type]. See the class doc for
     *  why only MEMORY has any today. */
    fun listCandidates(context: Context, type: BackupType): List<Candidate> {
        if (type != BackupType.MEMORY) return emptyList()
        return try {
            val dir = backupDir(context)
            val byName = (dir.listFiles() ?: emptyArray()).associateBy { it.name }
            BackupWalkPlanner.orderNewestFirst(byName.keys.toList()).mapNotNull { name ->
                val file = byName[name] ?: return@mapNotNull null
                Candidate(file, BackupWalkPlanner.backupInstantMillis(name) ?: file.lastModified())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * §15.6 step 3: walk newest-to-oldest and return the first backup that
     * verifies, or null when none is usable. Verification parses the whole
     * file through the seed codec — a truncated or corrupt export fails here
     * and the walk falls back to the next older file.
     */
    fun findUsableBackup(context: Context, type: BackupType): Verified? {
        for (candidate in listCandidates(context, type)) {
            try {
                MemorySeedCodec.parse(candidate.file.readText())
                return Verified(candidate)
            } catch (_: Exception) {
                DatabaseHealthState.logHealth(context, "warning",
                    "Backup ${candidate.file.name} failed verification during the restore walk; trying the next older backup.")
            }
        }
        return null
    }

    /**
     * Execute the restore the user confirmed in A5. Sequence: quarantine the
     * damaged database (preserve-the-original — abort if that fails) →
     * invalidate handles → delete the damaged file → clear the degraded flag
     * (the damaged store no longer exists) → fresh store → import the backup
     * → integrity-verify. Runs off the main thread only.
     *
     * A failure AFTER the damaged file was replaced leaves a fresh store with
     * whatever imported (never silently: the health line says so, the caller
     * reports it) — the quarantined original and every older backup are
     * untouched, so nothing is lost that was not already lost.
     */
    fun restoreMemory(context: Context, verified: Verified): DatabaseRepairManager.Outcome {
        val appContext = context.applicationContext
        val type = BackupType.MEMORY
        DatabaseHealthState.logHealth(appContext, "warning",
            "Restore of the memory database from backup ${verified.candidate.file.name} started.")
        // Parse FIRST — before anything destructive — so a file that changed
        // since verification cannot strand us with an empty store.
        val data = try {
            MemorySeedCodec.parse(verified.candidate.file.readText())
        } catch (e: Exception) {
            return DatabaseRepairManager.Outcome(false, null, "backup unreadable: ${e.javaClass.simpleName}")
        }
        val quarantinePath = DatabaseRepairManager.quarantine(appContext, type)
        // A missing database file is legal here (the store may already have
        // been replaced by a failed earlier attempt); a COPY failure is not.
        if (quarantinePath == null && appContext.getDatabasePath(MemoryStore.DATABASE_NAME).exists()) {
            return DatabaseRepairManager.Outcome(false, null, "quarantine failed — restore refused")
        }
        return try {
            DatabaseRepairManager.invalidateStore(appContext, type)
            val db = appContext.getDatabasePath(MemoryStore.DATABASE_NAME)
            try { if (db.exists()) db.delete() } catch (_: Exception) { }
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                try {
                    val sidecar = File(db.parentFile, db.name + suffix)
                    if (sidecar.exists()) sidecar.delete()
                } catch (_: Exception) { }
            }
            DatabaseHealthState.clearDegraded(appContext, type, "damaged file replaced for restore")
            val store = MemoryStore.getInstance(appContext)
            store.importData(data, overwriteSingletons = true)
            val problem = store.integrityCheck()
            if (problem != null) {
                DatabaseHealthState.logHealth(appContext, "error",
                    "Restored memory database failed its integrity check ($problem).")
                DatabaseRepairManager.Outcome(false, quarantinePath, problem)
            } else {
                DatabaseHealthState.logHealth(appContext, "info",
                    "Memory database restored from backup ${verified.candidate.file.name}. " +
                        "Damaged original preserved" + (quarantinePath?.let { " at $it" } ?: "") + ".")
                DatabaseRepairManager.Outcome(true, quarantinePath, null)
            }
        } catch (e: Exception) {
            DatabaseHealthState.logHealth(appContext, "error",
                "Restore of the memory database failed (${e.javaClass.simpleName}). " +
                    "The damaged original and all older backups are untouched.")
            DatabaseRepairManager.Outcome(false, quarantinePath, e.javaClass.simpleName)
        }
    }
}
