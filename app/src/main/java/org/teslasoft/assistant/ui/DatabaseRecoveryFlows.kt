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

package org.teslasoft.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.StartupHealth
import org.teslasoft.assistant.preferences.backup.BackupStatusFormatter
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.backup.DatabaseRepairManager
import org.teslasoft.assistant.preferences.backup.DatabaseRevertManager
import org.teslasoft.assistant.ui.activities.LogsActivity

/**
 * The shared Build Phase 3 dialog flows (§15.2 / §15.2b / §15.6, wording
 * §15.12): the A1 problem/repaired dialogs, the A5 restore confirmation, the
 * §15.6-step-4 fresh-start confirmation, and the A6 recovery-failed dialog.
 * Used from MainActivity + the Backup & Restore screen (pending startup
 * notices), the B8 manual-check routing, the A2 chat banner's Repair action,
 * and the Memory Assistant's A3 buttons — ONE flow everywhere, so the repair
 * order (verify → confirm → act, preserve-the-original throughout) cannot
 * drift between surfaces.
 *
 * All dialogs are persistent and user-dismissed (no toasts, no timeouts); all
 * store work runs on a worker thread behind a non-cancelable "do not close
 * your app" progress dialog (§15.2 item 4). [onStateChanged] fires on the UI
 * thread after any flag/store transition so the host screen can refresh.
 */
object DatabaseRecoveryFlows {

    /** Process-wide re-entry guard so a resumed screen cannot stack a second
     *  copy of a flow dialog over the first. */
    @Volatile
    private var flowDialogOpen = false

    private fun noun(type: BackupType): String = DatabaseHealthState.displayNoun(type)

    private fun problemBody(activity: Activity, type: BackupType): String = when (type) {
        BackupType.MEMORY -> activity.getString(R.string.health_problem_body_memory)
        BackupType.LOREBOOK -> activity.getString(R.string.health_problem_body_lorebook)
        else -> activity.getString(R.string.health_problem_body_user_image)
    }

    private fun builder(activity: Activity) =
        MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)

    private fun progressDialog(activity: Activity, message: String): Dialog {
        val d = builder(activity)
            .setMessage(message)
            .setCancelable(false)
            .create()
        d.show()
        return d
    }

    private fun track(dialog: AlertDialog): AlertDialog {
        flowDialogOpen = true
        dialog.setOnDismissListener { flowDialogOpen = false }
        return dialog
    }

    private fun refreshPendingGate(activity: Activity) {
        // The startup-check force flag mirrors "anything still degraded":
        // a pending repair keeps forcing the check even after a clean exit.
        StartupHealth.setIntegrityCheckPending(activity, DatabaseHealthState.anyDegraded(activity))
    }

    // ---- pending startup notices -------------------------------------------

    /**
     * Show the highest-priority pending health notice, one at a time (the
     * next one appears on the next resume). Safe to call from onResume of any
     * screen that hosts these dialogs.
     */
    fun showPendingNoticeIfAny(activity: Activity, onStateChanged: (() -> Unit)? = null) {
        if (activity.isFinishing || flowDialogOpen) return
        for (type in DatabaseHealthState.databaseTypes) {
            when (DatabaseHealthState.getPendingNotice(activity, type)) {
                DatabaseHealthState.NOTICE_PROBLEM -> {
                    showProblemDialog(activity, type, onStateChanged); return
                }
                DatabaseHealthState.NOTICE_REPAIRED -> {
                    showRepairedDialog(activity, type); return
                }
                DatabaseHealthState.NOTICE_RECOVERY_FAILED -> {
                    showRecoveryFailedDialog(
                        activity, type, DatabaseHealthState.getPendingNoticeDetail(activity, type)
                    ); return
                }
            }
        }
    }

    // ---- A1: problem found --------------------------------------------------

    /** The A1 "Database Problem Found!" dialog: Repair | Revert to Last Good
     *  Database | Cancel (owner: Cancel, never "Not Now"). Cancel = degraded
     *  mode — the store stays off, the A2 banner keeps reminding. */
    fun showProblemDialog(activity: Activity, type: BackupType, onStateChanged: (() -> Unit)? = null) {
        if (activity.isFinishing) return
        track(builder(activity)
            .setTitle(R.string.health_problem_title)
            .setMessage(problemBody(activity, type))
            .setCancelable(false)
            .setPositiveButton(R.string.health_btn_repair) { _, _ ->
                DatabaseHealthState.clearPendingNotice(activity, type)
                runRepair(activity, type, onStateChanged)
            }
            .setNeutralButton(R.string.health_btn_revert) { _, _ ->
                DatabaseHealthState.clearPendingNotice(activity, type)
                runRevert(activity, type, onStateChanged)
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Declining = stay in degraded mode (§15.2a): flag + banner
                // persist; only the startup nag is consumed.
                DatabaseHealthState.clearPendingNotice(activity, type)
                onStateChanged?.invoke()
            }
            .show())
    }

    /** The A1 "Database Repaired" disclosure — a repair is never silent. */
    fun showRepairedDialog(activity: Activity, type: BackupType) {
        if (activity.isFinishing) return
        track(builder(activity)
            .setTitle(R.string.health_repaired_title)
            .setMessage(activity.getString(R.string.health_repaired_body, noun(type)))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                DatabaseHealthState.clearPendingNotice(activity, type)
            }
            .show())
    }

    // ---- repair -------------------------------------------------------------

    fun runRepair(activity: Activity, type: BackupType, onStateChanged: (() -> Unit)? = null) {
        if (activity.isFinishing) return
        val progress = progressDialog(activity, activity.getString(R.string.health_repair_progress, noun(type)))
        Thread {
            val outcome = DatabaseRepairManager.attemptRepair(activity.applicationContext, type)
            activity.runOnUiThread {
                try { progress.dismiss() } catch (_: Exception) { }
                if (activity.isFinishing) return@runOnUiThread
                refreshPendingGate(activity)
                onStateChanged?.invoke()
                if (outcome.ok) {
                    DatabaseHealthState.clearPendingNotice(activity, type)
                    showRepairedDialog(activity, type)
                } else {
                    showRepairFailedDialog(activity, type, onStateChanged)
                }
            }
        }.start()
    }

    private fun showRepairFailedDialog(activity: Activity, type: BackupType, onStateChanged: (() -> Unit)?) {
        track(builder(activity)
            .setTitle(R.string.health_repair_failed_title)
            .setMessage(activity.getString(R.string.health_repair_failed_body, noun(type)))
            .setCancelable(false)
            .setPositiveButton(R.string.health_btn_revert) { _, _ -> runRevert(activity, type, onStateChanged) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onStateChanged?.invoke() }
            .show())
    }

    // ---- revert: verify → A5 confirm → restore ------------------------------

    /** §15.2b: verify FIRST (walk the backups newest-to-oldest off-thread),
     *  then the A5 confirmation, and only then the restore. */
    fun runRevert(activity: Activity, type: BackupType, onStateChanged: (() -> Unit)? = null) {
        if (activity.isFinishing) return
        val progress = progressDialog(activity, activity.getString(R.string.health_restore_progress, noun(type)))
        Thread {
            val verified = DatabaseRevertManager.findUsableBackup(activity.applicationContext, type)
            activity.runOnUiThread {
                try { progress.dismiss() } catch (_: Exception) { }
                if (activity.isFinishing) return@runOnUiThread
                if (verified == null) {
                    showNoUsableBackupDialog(activity, type, onStateChanged)
                } else {
                    showRestoreConfirmDialog(activity, type, verified, onStateChanged)
                }
            }
        }.start()
    }

    /** A5 — the deliberate secondary check before anything is overwritten. */
    private fun showRestoreConfirmDialog(
        activity: Activity,
        type: BackupType,
        verified: DatabaseRevertManager.Verified,
        onStateChanged: (() -> Unit)?
    ) {
        val date = BackupStatusFormatter.formatDate(verified.candidate.backupAtMillis)
        val body = when (type) {
            BackupType.LOREBOOK -> activity.getString(R.string.health_restore_body_lorebook, date)
            else -> activity.getString(R.string.health_restore_body_memory, date)
        }
        track(builder(activity)
            .setTitle(R.string.health_restore_title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.health_btn_restore) { _, _ ->
                executeRestore(activity, type, verified, onStateChanged)
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onStateChanged?.invoke() }
            .show())
    }

    private fun executeRestore(
        activity: Activity,
        type: BackupType,
        verified: DatabaseRevertManager.Verified,
        onStateChanged: (() -> Unit)?
    ) {
        val progress = progressDialog(activity, activity.getString(R.string.health_restore_progress, noun(type)))
        Thread {
            // Only the memory database has an enumerable backup chain today
            // (DatabaseRevertManager doc); findUsableBackup already returned
            // null for the others, so this branch is effectively MEMORY-only.
            val outcome = DatabaseRevertManager.restoreMemory(activity.applicationContext, verified)
            activity.runOnUiThread {
                try { progress.dismiss() } catch (_: Exception) { }
                if (activity.isFinishing) return@runOnUiThread
                refreshPendingGate(activity)
                onStateChanged?.invoke()
                if (outcome.ok) {
                    DatabaseHealthState.clearPendingNotice(activity, type)
                    track(builder(activity)
                        .setTitle(R.string.health_restore_done_title)
                        .setMessage(activity.getString(R.string.health_restore_done_body, noun(type)))
                        .setCancelable(false)
                        .setPositiveButton(R.string.btn_ok) { _, _ -> }
                        .show())
                } else {
                    track(builder(activity)
                        .setTitle(R.string.health_restore_failed_title)
                        .setMessage(R.string.health_restore_failed_body)
                        .setCancelable(false)
                        .setPositiveButton(R.string.btn_ok) { _, _ -> }
                        .setNeutralButton(R.string.health_btn_view_log) { _, _ -> openErrorLog(activity) }
                        .show())
                }
            }
        }.start()
    }

    // ---- §15.6 step 4: nothing usable → fresh empty database ---------------

    private fun showNoUsableBackupDialog(activity: Activity, type: BackupType, onStateChanged: (() -> Unit)?) {
        track(builder(activity)
            .setTitle(R.string.health_no_backup_title)
            .setMessage(activity.getString(R.string.health_no_backup_body, noun(type)))
            .setCancelable(false)
            .setPositiveButton(R.string.health_btn_start_fresh) { _, _ ->
                executeStartFresh(activity, type, onStateChanged)
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onStateChanged?.invoke() }
            .show())
    }

    private fun executeStartFresh(activity: Activity, type: BackupType, onStateChanged: (() -> Unit)?) {
        val progress = progressDialog(activity, activity.getString(R.string.health_repair_progress, noun(type)))
        Thread {
            val outcome = DatabaseRepairManager.startFresh(activity.applicationContext, type, null)
            activity.runOnUiThread {
                try { progress.dismiss() } catch (_: Exception) { }
                if (activity.isFinishing) return@runOnUiThread
                refreshPendingGate(activity)
                onStateChanged?.invoke()
                if (outcome.ok) {
                    showRecoveryFailedDialog(activity, type, outcome.quarantinePath)
                } else {
                    // The fresh start itself failed (quarantine refused):
                    // nothing was deleted; the store stays off.
                    track(builder(activity)
                        .setTitle(R.string.health_repair_failed_title)
                        .setMessage(activity.getString(R.string.health_repair_failed_body, noun(type)))
                        .setCancelable(false)
                        .setPositiveButton(R.string.btn_ok) { _, _ -> }
                        .setNeutralButton(R.string.health_btn_view_log) { _, _ -> openErrorLog(activity) }
                        .show())
                }
            }
        }.start()
    }

    /** A6 — the honest disclosure that a database was started over empty and
     *  where the damaged original was preserved. */
    fun showRecoveryFailedDialog(activity: Activity, type: BackupType, preservedPath: String?) {
        if (activity.isFinishing) return
        val path = preservedPath ?: "files/storage_recovery"
        track(builder(activity)
            .setTitle(R.string.health_recovery_failed_title)
            .setMessage(activity.getString(R.string.health_recovery_failed_body, noun(type), path))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                DatabaseHealthState.clearPendingNotice(activity, type)
            }
            .setNegativeButton(R.string.health_btn_open_location) { _, _ ->
                DatabaseHealthState.clearPendingNotice(activity, type)
                // Best-effort convenience (plan risk note): the preserved copy
                // lives in app-private storage, which most file managers
                // cannot browse; the path above is the reliable record.
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(path), "resource/folder")
                    })
                } catch (_: Exception) { /* unreliability never blocks (plan) */ }
            }
            .show())
    }

    // ---- screen gate --------------------------------------------------------

    /**
     * Blocking notice for a screen that cannot function while [type] is
     * degraded (memory/lorebook management screens): Repair routes to the
     * Backup & Restore screen's flow, OK just leaves. Both close the screen —
     * it must not sit half-loaded over a refused store.
     */
    fun showBlockedScreenDialog(activity: Activity, type: BackupType) {
        if (activity.isFinishing) return
        val body = when (type) {
            BackupType.LOREBOOK -> activity.getString(R.string.health_screen_blocked_lorebook)
            else -> activity.getString(R.string.health_screen_blocked_memory)
        }
        track(builder(activity)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.health_btn_repair) { _, _ ->
                openRepairScreen(activity, type)
                activity.finish()
            }
            .setNegativeButton(R.string.btn_ok) { _, _ -> activity.finish() }
            .show())
    }

    /** Open the Backup & Restore screen with [type]'s A1 dialog queued. */
    fun openRepairScreen(activity: Activity, type: BackupType) {
        try {
            val intent = Intent(
                activity,
                org.teslasoft.assistant.ui.activities.MemoryBackupRestoreActivity::class.java
            )
            intent.putExtra(
                org.teslasoft.assistant.ui.activities.MemoryBackupRestoreActivity.EXTRA_START_REPAIR_FOR,
                type.key
            )
            activity.startActivity(intent)
        } catch (_: Exception) { }
    }

    private fun openErrorLog(activity: Activity) {
        try {
            val intent = Intent(activity, LogsActivity::class.java)
            intent.putExtra("type", "crash")
            activity.startActivity(intent)
        } catch (_: Exception) { }
    }
}
