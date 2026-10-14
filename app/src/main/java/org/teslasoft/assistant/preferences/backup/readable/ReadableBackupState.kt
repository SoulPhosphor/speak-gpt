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
 * Persistent state of the Human-Readable Data Backup (owner directive, July
 * 22 2026). Lives in the raw `storage_health` prefs file (same rationale as
 * RecoveryBackupState: must work even when the encrypted stores are down),
 * under its own `readable.` key prefix.
 *
 * Baselines contain metadata only: hashed chat or archive-entry identities
 * mapped to content fingerprints, plus timestamps. Never a chat title,
 * folder name, message, or key.
 *
 * BASELINE CONTRACT: the file baseline advances only after a complete or
 * incremental destination ZIP has been written and verified. The legacy
 * chat baseline stays separate. Cancelled, failed, empty, or corrupt writes
 * never advance either baseline.
 * This state is for the READABLE chat backup only and must never feed the
 * recovery-backup status rows — the two systems stay distinct on screen and
 * in storage.
 */
object ReadableBackupState {

    private const val FILE = "storage_health"

    private const val KEY_CATEGORY_HISTORY = "readable.category_history"
    private const val KEY_FILE_BASELINE = "readable.file_baseline"
    private const val KEY_BASELINE = "readable.baseline"
    private const val KEY_LAST_SUCCESS = "readable.last_success"
    private const val KEY_LAST_SUCCESS_SIZE = "readable.last_success_size"
    private const val KEY_SCOPE_ALL = "readable.scope_all"
    private const val KEY_FORMAT = "readable.format"
    private const val KEY_CATEGORIES = "readable.categories"
    private const val KEY_INCLUDE_FOLDERS = "readable.include_folders"
    private const val KEY_INCLUDE_THINKING = "readable.include_thinking"

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

    /** Hashes of entry identities and saved file bytes; no names or content. */
    fun getFileBaseline(context: Context): Map<String, String> =
        try { ReadableChatFormats.baselineFromJson(prefs(context).getString(KEY_FILE_BASELINE, null)) }
        catch (_: Exception) { emptyMap() }

    /** Call only after the final destination was reopened and verified. */
    fun setFileBaseline(context: Context, baseline: Map<String, String>) {
        try {
            prefs(context).edit(commit = true) {
                putString(KEY_FILE_BASELINE, ReadableChatFormats.baselineToJson(baseline))
            }
        } catch (_: Exception) { }
    }

    fun getCategoryHistory(context: Context): Map<ReadableBackupCategory, ReadableBackupHistory.Record> =
        try { ReadableBackupHistory.decode(prefs(context).getString(KEY_CATEGORY_HISTORY, null)) }
        catch (_: Exception) { emptyMap() }

    fun recordCategorySuccess(context: Context, categories: Set<ReadableBackupCategory>, complete: Boolean, atMillis: Long) {
        val updated = ReadableBackupHistory.record(getCategoryHistory(context), categories, complete, atMillis)
        try { prefs(context).edit(commit = true) { putString(KEY_CATEGORY_HISTORY, ReadableBackupHistory.encode(updated)) } }
        catch (_: Exception) { }
    }

    /** Epoch millis of the last VERIFIED readable chat backup (complete or
     *  incremental), 0 when none. Display only — never recovery status. */
    fun getLastSuccess(context: Context): Long =
        try { prefs(context).getLong(KEY_LAST_SUCCESS, 0L) } catch (_: Exception) { 0L }

    fun setLastSuccess(context: Context, atMillis: Long) {
        try { prefs(context).edit(commit = true) { putLong(KEY_LAST_SUCCESS, atMillis) } } catch (_: Exception) { }
    }

    /** The exact size, in bytes, of the last VERIFIED readable chat backup's
     *  FINAL destination ZIP — measured during the same hash-verification
     *  read that confirms the save succeeded, never the staged/temporary
     *  file, never an estimate. Null when unavailable (never recorded, or
     *  the size could not be determined for that save). Companion to
     *  [getLastSuccess] — stamp them together. */
    fun getLastSuccessSizeBytes(context: Context): Long? =
        try {
            val stored = prefs(context).getLong(KEY_LAST_SUCCESS_SIZE, -1L)
            if (stored < 0L) null else stored
        } catch (_: Exception) { null }

    fun setLastSuccessSizeBytes(context: Context, bytes: Long?) {
        try {
            prefs(context).edit(commit = true) {
                if (bytes != null && bytes >= 0L) putLong(KEY_LAST_SUCCESS_SIZE, bytes)
                else remove(KEY_LAST_SUCCESS_SIZE)
            }
        } catch (_: Exception) { }
    }

    // ----- last-selected dropdown options (owner ruling, July 22 2026) -------
    // Widget.App.Dropdown.* fields remember the last option the user picked,
    // so re-opening this screen never resets "Backup Style" or "Format" back
    // to a default the user already moved away from.

    /** The last-selected Backup Style: all selected files or only new and
     *  changed files. Defaults to all selected files until the user has picked at
     *  least once. */
    fun getScopeAll(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_SCOPE_ALL, true) } catch (_: Exception) { true }

    fun setScopeAll(context: Context, all: Boolean) {
        try { prefs(context).edit(commit = true) { putBoolean(KEY_SCOPE_ALL, all) } } catch (_: Exception) { }
    }

    /** The last-selected Format. An unrecognized or missing stored value
     *  falls back to TEXT. */
    fun getFormat(context: Context): ReadableChatBackup.Format {
        val name = try { prefs(context).getString(KEY_FORMAT, null) } catch (_: Exception) { null }
        return ReadableChatBackup.Format.values().firstOrNull { it.name == name } ?: ReadableChatBackup.Format.TEXT
    }

    fun setFormat(context: Context, format: ReadableChatBackup.Format) {
        try { prefs(context).edit(commit = true) { putString(KEY_FORMAT, format.name) } } catch (_: Exception) { }
    }

    fun getCategories(context: Context): Set<ReadableBackupCategory> {
        val stored = try { prefs(context).getStringSet(KEY_CATEGORIES, null) } catch (_: Exception) { null }
        return ReadableBackupCategory.fromStored(stored)
    }

    fun setCategories(context: Context, categories: Set<ReadableBackupCategory>) {
        try {
            prefs(context).edit(commit = true) {
                putStringSet(KEY_CATEGORIES, categories.mapTo(LinkedHashSet()) { it.name })
            }
        } catch (_: Exception) { }
    }

    /** Include Thinking; on until the user turns it off. */
    fun getIncludeThinking(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_INCLUDE_THINKING, true) } catch (_: Exception) { true }

    fun setIncludeThinking(context: Context, include: Boolean) {
        try { prefs(context).edit(commit = true) { putBoolean(KEY_INCLUDE_THINKING, include) } } catch (_: Exception) { }
    }

    /** Include Chat Folder Location; off until the user turns it on. */
    fun getIncludeFolders(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_INCLUDE_FOLDERS, false) } catch (_: Exception) { false }

    fun setIncludeFolders(context: Context, include: Boolean) {
        try { prefs(context).edit(commit = true) { putBoolean(KEY_INCLUDE_FOLDERS, include) } } catch (_: Exception) { }
    }
}
