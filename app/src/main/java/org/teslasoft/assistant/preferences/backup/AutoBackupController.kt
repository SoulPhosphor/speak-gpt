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
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean
import org.teslasoft.assistant.preferences.memory.MemoryLog

/**
 * The Android-side orchestrator for Automatic Backups (owner ruling, July 23
 * 2026). Every trigger — the WorkManager job ([AutoBackupWorker]) and the
 * opportunistic app-open check ([org.teslasoft.assistant.app.MainApplication]) —
 * funnels through here, and every yes/no comes from the pure
 * [AutoBackupScheduler] so the reliability rules are the unit-tested ones.
 *
 * What it guarantees, per the requirements:
 *  - disabled by default, only runs when enabled;
 *  - a valid, still-writable destination is required (a lost SAF grant BLOCKS
 *    future runs and is recorded honestly — never a silent fallback anywhere);
 *  - the app decides when a backup is due, at most one per due window
 *    (duplicate-safe via the schedule anchor + an in-process running latch);
 *  - it produces the SAME verified recovery backup as the manual engine, into
 *    the AUTOMATIC folder, with rotation OFF — automatic backups never delete
 *    an older copy yet;
 *  - it records last attempt / last success / last failure / next due time;
 *  - it never restores, never touches the live databases, never weakens
 *    encryption or verification, and never surfaces a raw URI or exception text
 *    (only a failure class name to the local Memory log).
 *
 * Runs OFF the main thread by contract (its callers are a WorkManager worker
 * thread and the startup housekeeping thread) — it must never be called on the
 * UI thread, because it opens SQLCipher and does SAF IO.
 */
object AutoBackupController {

    /** Process-wide single-run latch: two triggers that fire before the first
     *  records success still cannot overlap (the second sees ALREADY_RUNNING). */
    private val running = AtomicBoolean(false)

    /** The result of a trigger, for logging/UI. Not persisted. */
    enum class Outcome {
        DISABLED,
        NO_DESTINATION,
        PERMISSION_LOST,
        NOT_DUE,
        ALREADY_RUNNING,
        /** Ran and every type that had something to back up succeeded. */
        COMPLETED,
        /** Ran and advanced the schedule, but one or more types failed for a
         *  SOURCE reason (a degraded/locked store) — recorded per type. */
        COMPLETED_WITH_FAILURES
    }

    /** Scheduled / app-open trigger: run only if actually due. */
    fun runIfDue(context: Context): Outcome = run(context, requireDue = true)

    /** Manual "Retry / Back up now" trigger from the failure dialog: run
     *  regardless of the due window, but still honour every other gate. */
    fun runNow(context: Context): Outcome = run(context, requireDue = false)

    private fun run(context: Context, requireDue: Boolean): Outcome {
        val appContext = context.applicationContext
        val enabled = RecoveryBackupState.isEnabled(appContext)
        val uriStr = RecoveryBackupState.getAutoFolderUri(appContext)
        val hasDestination = uriStr != null
        val accessible = hasDestination && folderAccessible(appContext, uriStr!!)
        val frequency = RecoveryBackupState.getAutoFrequency(appContext)
        val lastSuccess = RecoveryBackupState.getAutoLastSuccess(appContext)
        val now = System.currentTimeMillis()

        val decision = AutoBackupScheduler.plan(
            enabled = enabled,
            hasDestination = hasDestination,
            destinationAccessible = accessible,
            alreadyRunning = running.get(),
            lastSuccessMillis = lastSuccess,
            nowMillis = now,
            frequency = frequency,
            requireDue = requireDue
        )

        when (decision) {
            AutoBackupScheduler.Decision.SKIP_DISABLED -> return Outcome.DISABLED
            AutoBackupScheduler.Decision.SKIP_NO_DESTINATION -> return Outcome.NO_DESTINATION
            AutoBackupScheduler.Decision.SKIP_PERMISSION_LOST -> {
                // The grant is gone before we even try: record it honestly and
                // leave the set due so the next trigger re-surfaces it, without
                // running (blocked until the user repairs the destination).
                RecoveryBackupState.recordAutoFailure(
                    appContext, BackupFailureCategory.DESTINATION_PERMISSION, nextDueMillis = 0L
                )
                return Outcome.PERMISSION_LOST
            }
            AutoBackupScheduler.Decision.SKIP_ALREADY_RUNNING -> return Outcome.ALREADY_RUNNING
            AutoBackupScheduler.Decision.SKIP_NOT_DUE -> return Outcome.NOT_DUE
            AutoBackupScheduler.Decision.RUN -> { /* fall through */ }
        }

        // Claim the single-run latch. A lost race here means another thread is
        // already running the pass — never start a second.
        if (!running.compareAndSet(false, true)) return Outcome.ALREADY_RUNNING
        try {
            RecoveryBackupState.recordAutoAttempt(appContext, now)

            // The verified recovery-backup engine, into the AUTOMATIC folder,
            // rotation OFF (never deletes an older copy). Per-type success/
            // failure is recorded by the manager; this pass records the set.
            val results = RecoveryBackupManager.createBackup(appContext, Uri.parse(uriStr), rotateOldCopies = false)

            val permissionFailed = results.any { it.category == BackupFailureCategory.DESTINATION_PERMISSION }
            if (!AutoBackupScheduler.shouldAdvanceSchedule(permissionFailed)) {
                // A destination/permission failure mid-run: block, keep due.
                RecoveryBackupState.recordAutoFailure(
                    appContext, BackupFailureCategory.DESTINATION_PERMISSION, nextDueMillis = 0L
                )
                MemoryLog.log(appContext, "AutoBackup", "warning",
                    "Automatic backup could not reach its folder; blocked until the destination is repaired.")
                return Outcome.PERMISSION_LOST
            }

            // Advance the schedule: this window is closed (no duplicate), and the
            // next-due time is recorded from this success + the current interval.
            val nextDue = AutoBackupScheduler.nextDueMillis(now, frequency)
            RecoveryBackupState.recordAutoSuccess(appContext, now, nextDue)

            val hadFailure = results.any { !it.success && it.category != null }
            if (hadFailure) {
                MemoryLog.log(appContext, "AutoBackup", "warning",
                    "Automatic backup completed with one or more per-type failures (recorded per type).")
                return Outcome.COMPLETED_WITH_FAILURES
            }
            return Outcome.COMPLETED
        } catch (e: Exception) {
            // The manager never throws, but stay defensive: a failure here is
            // recorded as a SOURCE category (the destination path succeeded far
            // enough to record its own category otherwise). Never rethrow.
            RecoveryBackupState.recordAutoFailure(
                appContext, BackupFailureCategory.SOURCE,
                nextDueMillis = AutoBackupScheduler.nextDueMillis(now, frequency)
            )
            try {
                MemoryLog.log(appContext, "AutoBackup", "error",
                    "Automatic backup pass failed (${e.javaClass.simpleName}).")
            } catch (_: Exception) { }
            return Outcome.COMPLETED_WITH_FAILURES
        } finally {
            running.set(false)
        }
    }

    /**
     * True while the persisted SAF grant for [uriStr] is still held with BOTH
     * read and write. When it is gone the folder moved / was deleted / the grant
     * was revoked, and the automatic system must block rather than fall back.
     * Best-effort: any error reading the permission list reads as "not
     * accessible" (the safe, honest direction).
     */
    fun folderAccessible(context: Context, uriStr: String): Boolean = try {
        val uri = Uri.parse(uriStr)
        context.applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    } catch (_: Exception) {
        false
    }
}
