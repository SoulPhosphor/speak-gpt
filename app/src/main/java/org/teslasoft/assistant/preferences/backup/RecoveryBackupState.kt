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
import androidx.core.content.edit

/**
 * The recovery-backup controller's state (Build Phase 1 item 3 / Build Phase 2
 * item 1). Lives OUTSIDE all three protected databases, in the raw,
 * UNENCRYPTED `storage_health` prefs file — the same plaintext journal Round 4
 * and [org.teslasoft.assistant.preferences.StartupHealth] use — precisely so a
 * damaged or key-locked database can never disable backup attempts or status
 * tracking for the others. This deliberately does NOT repeat the current
 * combined-export dependency, where the enabled flag and last-export timestamp
 * live inside `companion_memory.db` (META_AUTO_EXPORT_ENABLED /
 * META_LAST_AUTO_EXPORT_AT).
 *
 * Contents are metadata only: an enabled flag, the TWO user-chosen backup-folder
 * tree URIs (the manual Create Backup location and the Automatic Backups
 * location are kept SEPARATE — owner ruling, July 21 2026 — and never
 * combined), and per-type last-attempt / last-success / consecutive-failure /
 * last-failure-category. Never chat content, prompts, or keys. Keys are
 * namespaced with a `backup.` prefix so they cannot collide with
 * StartupHealth's `startup.`, ChatStorageHealth's `lock.`/`readfail.`/
 * `snapshot.`, or SnapshotRegistry's `registry.` entries in the shared file.
 *
 * All accessors are best-effort (exceptions swallowed) so the raw prefs file
 * being briefly unavailable never crashes a backup pass; the pure [isBackupDue]
 * decision is unit-tested.
 */
object RecoveryBackupState {

    private const val FILE = "storage_health"

    /** Default throttle: at most one successful backup per type per 24 h
     *  (Build Phase 2 item 2 — checked at app start / cheap foreground return,
     *  NOT guaranteed background scheduling). */
    const val THROTTLE_MILLIS: Long = 24L * 60L * 60L * 1000L

    private const val KEY_ENABLED = "backup.enabled"

    // Two separate destinations — never combined (owner ruling, July 21 2026).
    private const val KEY_MANUAL_FOLDER_URI = "backup.manual_folder_uri"
    private const val KEY_AUTO_FOLDER_URI = "backup.auto_folder_uri"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun lastSuccessKey(type: BackupType) = "backup.${type.key}.last_success"
    private fun lastAttemptKey(type: BackupType) = "backup.${type.key}.last_attempt"
    private fun consecFailuresKey(type: BackupType) = "backup.${type.key}.consec_failures"
    private fun lastCategoryKey(type: BackupType) = "backup.${type.key}.last_category"

    // ----- enabled -----------------------------------------------------------

    fun isEnabled(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_ENABLED, false) } catch (_: Exception) { false }

    fun setEnabled(context: Context, enabled: Boolean) {
        try { prefs(context).edit(commit = true) { putBoolean(KEY_ENABLED, enabled) } } catch (_: Exception) { }
    }

    // ----- destination folders (SAF tree URIs) -------------------------------
    // The manual "Create Backup" location and the "Automatic Backups" location
    // are kept SEPARATE and must never be combined (owner ruling, July 21 2026).

    /** The persisted MANUAL (Create Backup) folder tree URI, or null when the
     *  user has not chosen one yet. */
    fun getManualFolderUri(context: Context): String? =
        try { prefs(context).getString(KEY_MANUAL_FOLDER_URI, null) } catch (_: Exception) { null }

    fun setManualFolderUri(context: Context, uri: String?) {
        try {
            prefs(context).edit(commit = true) {
                if (uri.isNullOrEmpty()) remove(KEY_MANUAL_FOLDER_URI) else putString(KEY_MANUAL_FOLDER_URI, uri)
            }
        } catch (_: Exception) { }
    }

    /** The persisted AUTOMATIC-backup folder tree URI, or null when the user has
     *  not chosen one yet. */
    fun getAutoFolderUri(context: Context): String? =
        try { prefs(context).getString(KEY_AUTO_FOLDER_URI, null) } catch (_: Exception) { null }

    fun setAutoFolderUri(context: Context, uri: String?) {
        try {
            prefs(context).edit(commit = true) {
                if (uri.isNullOrEmpty()) remove(KEY_AUTO_FOLDER_URI) else putString(KEY_AUTO_FOLDER_URI, uri)
            }
        } catch (_: Exception) { }
    }

    // ----- per-type result tracking ------------------------------------------

    fun getLastSuccess(context: Context, type: BackupType): Long =
        try { prefs(context).getLong(lastSuccessKey(type), 0L) } catch (_: Exception) { 0L }

    fun getLastAttempt(context: Context, type: BackupType): Long =
        try { prefs(context).getLong(lastAttemptKey(type), 0L) } catch (_: Exception) { 0L }

    fun getConsecutiveFailures(context: Context, type: BackupType): Int =
        try { prefs(context).getInt(consecFailuresKey(type), 0) } catch (_: Exception) { 0 }

    /** The recorded category of the most recent failure for [type], or null
     *  when the last attempt succeeded (the category is cleared on success). */
    fun getLastFailureCategory(context: Context, type: BackupType): BackupFailureCategory? =
        try { BackupFailureCategory.fromKey(prefs(context).getString(lastCategoryKey(type), null)) } catch (_: Exception) { null }

    /** Record a successful backup: stamp success + attempt, clear the failure
     *  streak and the last-failure category. */
    fun recordSuccess(context: Context, type: BackupType, atMillis: Long) {
        try {
            prefs(context).edit(commit = true) {
                putLong(lastSuccessKey(type), atMillis)
                putLong(lastAttemptKey(type), atMillis)
                putInt(consecFailuresKey(type), 0)
                remove(lastCategoryKey(type))
            }
        } catch (_: Exception) { }
    }

    /** Record a failed backup: stamp the attempt, bump the streak, and store the
     *  CATEGORY (never a bare "backup failed" — Build Phase 1 item 3). The
     *  last-success stamp is left untouched so the status row can still show the
     *  last good backup. */
    fun recordFailure(context: Context, type: BackupType, atMillis: Long, category: BackupFailureCategory) {
        try {
            val p = prefs(context)
            val streak = try { p.getInt(consecFailuresKey(type), 0) } catch (_: Exception) { 0 }
            p.edit(commit = true) {
                putLong(lastAttemptKey(type), atMillis)
                putInt(consecFailuresKey(type), streak + 1)
                putString(lastCategoryKey(type), category.name)
            }
        } catch (_: Exception) { }
    }

    // ----- pure decision (unit-tested) ---------------------------------------

    /**
     * Whether a type is due for a backup: never backed up, or the last success
     * is at least [throttleMillis] old. A non-positive [lastSuccessMillis] means
     * "never" and is always due.
     */
    fun isBackupDue(
        lastSuccessMillis: Long,
        nowMillis: Long,
        throttleMillis: Long = THROTTLE_MILLIS
    ): Boolean =
        lastSuccessMillis <= 0L || nowMillis - lastSuccessMillis >= throttleMillis
}
