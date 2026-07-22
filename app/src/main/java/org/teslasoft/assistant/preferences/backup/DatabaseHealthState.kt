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
import org.teslasoft.assistant.preferences.Logger

/**
 * Per-database degraded state (Database Health Build Phase 3 item 1 / design
 * §15.2a + B10). Lives in the raw, UNENCRYPTED `storage_health` prefs file —
 * the same plaintext journal Round 4, StartupHealth and RecoveryBackupState
 * use — precisely because it must keep working while the very database it
 * describes cannot open. Keys are namespaced `health.` so they cannot collide
 * with the other tenants of the shared file. Contents are state metadata only.
 *
 * THE LAW OF THIS FLAG (build plan Build Phase 3 item 1): it is set ONLY by
 * CONFIRMED damage — a failed `PRAGMA integrity_check` or a mid-session
 * corruption exception surfaced by the store layer. A source-category backup
 * failure is only the trigger to RUN a check; destination/verify backup
 * failures never touch this flag. While set, the store is genuinely OFF —
 * reads AND writes blocked ([DatabaseDegradedException] from the store
 * singletons), its backup artifact paused, the Archivist hard-disabled — and
 * the state survives restarts (B10) until a repair or restore actually
 * succeeds. Reopening never silently re-uses a corrupt database.
 *
 * Health transitions are logged ONCE per transition (§15.15): to the Error Log
 * ("crash" channel), regardless of any diagnostics toggle, under the
 * [HEALTH_TAG] tag that LogsActivity renders with a red timestamp.
 *
 * The USER_IMAGE database gets the same flag and banner but no feature-disable
 * beyond the gallery itself (§15.16 item 5): nothing else in the app depends
 * on the catalog to function, so [ProfileImageDb] is not gated on this flag —
 * only its backup artifact pauses and its repair flow reads it.
 */
object DatabaseHealthState {

    private const val FILE = "storage_health"

    /** The Error Log tag for once-per-transition database-health lines
     *  (§15.15). LogsActivity renders the timestamp of entries carrying this
     *  tag in red so they stand out when scanning the log. */
    const val HEALTH_TAG = "DatabaseHealth"

    /** Pending startup notices (which A1-family dialog the next foreground
     *  screen must show). Values of the `health.<type>.notice` key. */
    const val NOTICE_REPAIRED = "repaired"
    const val NOTICE_PROBLEM = "problem"
    const val NOTICE_RECOVERY_FAILED = "recovery_failed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun degradedKey(type: BackupType) = "health.${type.key}.degraded"
    private fun degradedSinceKey(type: BackupType) = "health.${type.key}.degraded_since"
    private fun noticeKey(type: BackupType) = "health.${type.key}.notice"
    private fun noticeDetailKey(type: BackupType) = "health.${type.key}.notice_detail"

    /** The database types the degraded flag applies to. CHATS is deliberately
     *  absent: chats are not a database — their health is owned by the
     *  existing Round-4 chat-storage lock machinery. */
    val databaseTypes: List<BackupType> =
        listOf(BackupType.MEMORY, BackupType.LOREBOOK, BackupType.USER_IMAGE)

    // ----- degraded flag -----------------------------------------------------

    fun isDegraded(context: Context, type: BackupType): Boolean =
        try { prefs(context).getBoolean(degradedKey(type), false) } catch (_: Exception) { false }

    fun degradedTypes(context: Context): List<BackupType> =
        databaseTypes.filter { isDegraded(context, it) }

    fun anyDegraded(context: Context): Boolean = degradedTypes(context).isNotEmpty()

    /**
     * Mark [type] degraded because damage was CONFIRMED ([reason] is a short
     * factual cause — an integrity-check summary or an exception class name,
     * never free user content). Idempotent: repeat confirmations of an
     * already-degraded store write nothing and log nothing (transition-only,
     * §15.15). Returns true when this call performed the transition.
     */
    fun markDegraded(context: Context, type: BackupType, reason: String): Boolean {
        val transition = try {
            val p = prefs(context)
            if (p.getBoolean(degradedKey(type), false)) {
                false
            } else {
                p.edit(commit = true) {
                    putBoolean(degradedKey(type), true)
                    putLong(degradedSinceKey(type), System.currentTimeMillis())
                }
                true
            }
        } catch (_: Exception) { false }
        if (transition) {
            logHealth(context, "error",
                "${displayNoun(type)} database confirmed damaged — feature disabled pending repair ($reason).")
        }
        return transition
    }

    /**
     * Clear the degraded flag after a repair or restore actually SUCCEEDED
     * ([how] names which — "repaired" / "restored from backup" / "started
     * fresh"). Transition-logged once. Returns true when a transition happened.
     */
    fun clearDegraded(context: Context, type: BackupType, how: String): Boolean {
        val transition = try {
            val p = prefs(context)
            if (!p.getBoolean(degradedKey(type), false)) {
                false
            } else {
                p.edit(commit = true) {
                    remove(degradedKey(type))
                    remove(degradedSinceKey(type))
                }
                true
            }
        } catch (_: Exception) { false }
        if (transition) {
            logHealth(context, "info", "${displayNoun(type)} database recovered ($how) — feature re-enabled.")
        }
        return transition
    }

    // ----- pending startup notices (A1-family dialog delivery) ---------------

    /**
     * Record which dialog the next foreground screen must show for [type]:
     * [NOTICE_REPAIRED] (A1 repaired variant), [NOTICE_PROBLEM] (A1 problem
     * variant) or [NOTICE_RECOVERY_FAILED] (A6; [detail] carries the preserved
     * file path it displays). The notice persists until a user actually sees
     * and dismisses the dialog — a missed launch shows it on the next one.
     */
    fun setPendingNotice(context: Context, type: BackupType, notice: String, detail: String? = null) {
        try {
            prefs(context).edit(commit = true) {
                putString(noticeKey(type), notice)
                if (detail.isNullOrBlank()) remove(noticeDetailKey(type)) else putString(noticeDetailKey(type), detail)
            }
        } catch (_: Exception) { }
    }

    fun getPendingNotice(context: Context, type: BackupType): String? =
        try { prefs(context).getString(noticeKey(type), null) } catch (_: Exception) { null }

    fun getPendingNoticeDetail(context: Context, type: BackupType): String? =
        try { prefs(context).getString(noticeDetailKey(type), null) } catch (_: Exception) { null }

    fun clearPendingNotice(context: Context, type: BackupType) {
        try {
            prefs(context).edit(commit = true) {
                remove(noticeKey(type))
                remove(noticeDetailKey(type))
            }
        } catch (_: Exception) { }
    }

    // ----- health-event logging (§15.15) -------------------------------------

    /**
     * One Error-Log line for a database-health EVENT (corruption found, repair
     * attempted/succeeded/failed, restore performed, repeated backup failure).
     * Written regardless of the diagnostics toggles — this is recovery
     * information, not optional debug noise — and synchronously ([Logger.log],
     * not logAsync) so the record exists even if the process dies right after
     * the event it describes. Callers own once-per-event discipline; the
     * transition helpers above already enforce it for flag flips.
     */
    fun logHealth(context: Context, level: String, message: String) {
        try {
            Logger.log(context, "crash", HEALTH_TAG, level, message)
        } catch (_: Exception) { /* diagnostics must never disturb the caller */ }
    }

    /** The lowercase database noun used inside sentences ("memory" /
     *  "lorebook" / "user image"), matching the §15.12 variable-name rule. */
    fun displayNoun(type: BackupType): String = when (type) {
        BackupType.MEMORY -> "memory"
        BackupType.LOREBOOK -> "lorebook"
        BackupType.USER_IMAGE -> "user image"
        BackupType.CHATS -> "chat"
    }
}
