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

package org.teslasoft.assistant.preferences

import android.content.Context
import androidx.core.content.edit

/**
 * Startup-health gating state (Database Health & Backups, Build Phase 1).
 *
 * Held in the raw, UNENCRYPTED `storage_health` prefs file — the same
 * plaintext journal Round 4 uses for chat-storage state — precisely so it
 * keeps working while the Keystore is down or a database cannot open, which
 * are the conditions these gates exist to survive. Contents are metadata only
 * (booleans); never chat content, prompts, or keys. Keys are namespaced with
 * a `startup.` prefix so they cannot collide with `ChatStorageHealth`'s
 * `lock.`/`readfail.`/`snapshot.` entries or `SnapshotRegistry`'s `registry.`
 * entries in the shared file.
 *
 * Two jobs:
 *  - Gate the expensive, whole-database integrity check so it runs ONLY after
 *    an abnormal previous exit (a clean launch could not have interrupted a
 *    write) or when a check/repair is explicitly pending. Before Build Phase 1
 *    the `PRAGMA integrity_check` ran on EVERY launch and grew with the store.
 *  - Latch the one-time startup recovery scan (legacy snapshot discovery plus
 *    the outage-file reconciliation) so it stops rescanning every launch once
 *    the system is fully settled.
 */
object StartupHealth {

    private const val FILE = "storage_health"
    private const val KEY_RECOVERY_SCAN_SETTLED = "startup.recovery_scan_settled"
    private const val KEY_INTEGRITY_CHECK_PENDING = "startup.integrity_check_pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Pure decision (unit-tested): run the automatic integrity check only when
     * a check or repair is explicitly pending, or the previous process exit was
     * abnormal (crash / ANR / kill — a database write could have been left
     * mid-flight). A clean exit skips it. [previousExitAbnormal] is null when
     * the platform cannot answer (API < 30); the safe direction there is to run
     * the check.
     */
    fun shouldRunIntegrityCheck(previousExitAbnormal: Boolean?, checkOrRepairPending: Boolean): Boolean =
        checkOrRepairPending || (previousExitAbnormal ?: true)

    /**
     * True while a database check/repair has been explicitly requested and not
     * yet satisfied. Nothing sets this today; Build Phase 3's repair flow will,
     * and the startup gate already honors it (so a pending repair still forces
     * a check even after a clean exit).
     */
    fun isIntegrityCheckPending(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_INTEGRITY_CHECK_PENDING, false) } catch (_: Exception) { false }

    fun setIntegrityCheckPending(context: Context, pending: Boolean) {
        try {
            prefs(context).edit(commit = true) { putBoolean(KEY_INTEGRITY_CHECK_PENDING, pending) }
        } catch (_: Exception) { /* best-effort; a gate default of "run" is the safe miss */ }
    }

    /**
     * True once the one-time startup recovery scan (legacy snapshot discovery +
     * outage-file reconciliation) has fully settled and need not run again.
     */
    fun isRecoveryScanSettled(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_RECOVERY_SCAN_SETTLED, false) } catch (_: Exception) { false }

    fun markRecoveryScanSettled(context: Context) {
        try {
            prefs(context).edit(commit = true) { putBoolean(KEY_RECOVERY_SCAN_SETTLED, true) }
        } catch (_: Exception) { /* best-effort; an un-set latch just rescans next start */ }
    }
}
