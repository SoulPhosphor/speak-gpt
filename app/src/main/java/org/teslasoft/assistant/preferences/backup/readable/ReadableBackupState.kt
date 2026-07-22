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

package org.teslasoft.assistant.preferences.backup.readable

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent state of the Human-Readable Chat Backup (owner directive, July
 * 22 2026). Lives in the raw `storage_health` prefs file (same rationale as
 * RecoveryBackupState: must work even when the encrypted stores are down),
 * under its own `readable.` key prefix.
 *
 * Contents are metadata only — chat ids (already SHA-256 hashes) mapped to
 * content fingerprints (SHA-256 of stored content), plus timestamps. Never a
 * chat title, message, or key.
 *
 * BASELINE CONTRACT (owner directive): the incremental baseline advances ONLY
 * after an incremental export's destination ZIP has been written AND
 * verified. A cancelled, failed, empty, or corrupt write never touches it.
 * This state is for the READABLE chat backup only and must never feed the
 * recovery-backup status rows — the two systems stay distinct on screen and
 * in storage.
 */
object ReadableBackupState {

    private const val FILE = "storage_health"

    private const val KEY_BASELINE = "readable.baseline"
    private const val KEY_LAST_SUCCESS = "readable.last_success"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The verified incremental baseline: chat id -> content fingerprint.
     *  Missing or unparseable -> EMPTY (everything counts as new — the safe
     *  direction: re-export, never skip). */
    fun getBaseline(context: Context): Map<String, String> =
        try { ReadableChatFormats.baselineFromJson(prefs(context).getString(KEY_BASELINE, null)) }
        catch (_: Exception) { emptyMap() }

    /** Replace the baseline — call ONLY after the destination ZIP of an
     *  incremental export has been verified (see the class contract). */
    fun setBaseline(context: Context, baseline: Map<String, String>) {
        try {
            prefs(context).edit(commit = true) {
                putString(KEY_BASELINE, ReadableChatFormats.baselineToJson(baseline))
            }
        } catch (_: Exception) { }
    }

    /** Epoch millis of the last VERIFIED readable chat backup (complete or
     *  incremental), 0 when none. Display only — never recovery status. */
    fun getLastSuccess(context: Context): Long =
        try { prefs(context).getLong(KEY_LAST_SUCCESS, 0L) } catch (_: Exception) { 0L }

    fun setLastSuccess(context: Context, atMillis: Long) {
        try { prefs(context).edit(commit = true) { putLong(KEY_LAST_SUCCESS, atMillis) } } catch (_: Exception) { }
    }
}
