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
import org.json.JSONObject

/**
 * The chat-storage state machine (silent-failure audit, Round 4).
 *
 * Chat content lives in Keystore-backed EncryptedSharedPreferences files.
 * When the Keystore key is unavailable (cleared credential storage, a device
 * restore onto new hardware, a transient Keystore failure) the encrypted
 * files still exist but cannot be opened. The old fallback returned the
 * ORIGINAL plaintext file in that state — which, post-migration, holds only
 * a marker — so locked encrypted data masqueraded as an ordinary empty chat
 * list, new writes landed where the next migration pass would silently
 * discard them, and the outage ended in data loss instead of recovery.
 *
 * Four distinct per-file states replace that single fallback. [classify] is
 * pure and unit-tested; SecurePrefs feeds it and acts on the result:
 *
 *  - [FileState.HEALTHY] — the encrypted file opens. Reads and writes go to
 *    it. Legacy plaintext migration (with conflict preservation) and outage
 *    reconciliation may run.
 *  - [FileState.LOCKED] — an encrypted file EXISTS on disk but cannot be
 *    opened. The data is unreadable, not absent. Reads and writes are
 *    redirected to a separate `outage.<name>` file so nothing can touch,
 *    mask, or later clobber the encrypted copy; the lock is recorded here
 *    (persistently) and reconciliation merges the outage file back when the
 *    key returns. The encrypted file is never modified, cleared, or deleted
 *    in this state.
 *  - [FileState.LEGACY_PLAINTEXT] — no encrypted file exists and the
 *    plaintext file has real data: a pre-encryption install whose Keystore
 *    is unavailable. The plaintext file stays authoritative (the pre-July-
 *    2026 behavior); nothing encrypted exists to mask.
 *  - [FileState.FRESH_UNENCRYPTED] — nothing exists on either side and the
 *    Keystore is unavailable: a genuinely new installation. The plaintext
 *    file is used so first-run data is picked up by the normal migration
 *    when encryption becomes available.
 *
 * "Temporarily unavailable" vs "permanently lost" cannot be distinguished
 * synchronously — the Keystore returns the same failures for both — so the
 * journal records first-seen/last-seen/count per file and recovery is
 * simply automatic whenever the encrypted file opens again. Surfacing a
 * long-running lock to the user is a wording decision reserved for the
 * owner; every state here is queryable so that UI can be added without
 * touching the storage layer.
 *
 * The journal file itself is DELIBERATELY plain, unencrypted
 * SharedPreferences accessed raw (the "never raw getSharedPreferences"
 * rule targets chat-content files): it must stay readable and writable
 * precisely when the encryption layer is down, and it contains only state
 * metadata — logical file names (already visible as file names on disk),
 * timestamps, counters and exception summaries — never chat content.
 */
object ChatStorageHealth {

    enum class FileState { HEALTHY, LOCKED, LEGACY_PLAINTEXT, FRESH_UNENCRYPTED }

    /**
     * Pure classification of one logical preferences file. The precedence is
     * the whole safety argument: an existing encrypted file ALWAYS means
     * LOCKED when it cannot be opened — locked must never be confused with
     * the legacy or fresh states no matter what the plaintext side holds,
     * because those states read and write plaintext files that migration
     * later consumes.
     */
    fun classify(
        encryptedOpens: Boolean,
        encryptedFileExists: Boolean,
        plaintextHasData: Boolean
    ): FileState = when {
        encryptedOpens -> FileState.HEALTHY
        encryptedFileExists -> FileState.LOCKED
        plaintextHasData -> FileState.LEGACY_PLAINTEXT
        else -> FileState.FRESH_UNENCRYPTED
    }

    // ----- Persistent health journal -------------------------------------

    private const val FILE = "storage_health"
    private const val KEY_LOCK_PREFIX = "lock."
    private const val KEY_READFAIL_PREFIX = "readfail."
    private const val KEY_SNAPSHOT_PREFIX = "snapshot."

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun recordEvent(context: Context, key: String, error: String?) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val existing = try {
            p.getString(key, null)?.let { JSONObject(it) }
        } catch (_: Exception) {
            null
        }
        val obj = existing ?: JSONObject().put("first", now)
        obj.put("last", now)
        obj.put("count", obj.optInt("count", 0) + 1)
        if (error != null) obj.put("error", error.take(300))
        p.edit(commit = true) { putString(key, obj.toString()) }
    }

    private fun namesWithPrefix(context: Context, prefix: String): Set<String> = try {
        prefs(context).all.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** An encrypted file exists but could not be opened this attempt. */
    fun recordLock(context: Context, name: String, error: String?) =
        recordEvent(context, KEY_LOCK_PREFIX + name, error)

    /**
     * The encrypted file opened again. Clears the lock record; returns true
     * when a lock had been recorded (so the recovery can be logged once).
     */
    fun clearLock(context: Context, name: String): Boolean {
        val p = prefs(context)
        val key = KEY_LOCK_PREFIX + name
        if (!p.contains(key)) return false
        p.edit(commit = true) { remove(key) }
        return true
    }

    fun lockedNames(context: Context): Set<String> = namesWithPrefix(context, KEY_LOCK_PREFIX)

    /**
     * The encrypted file opened but a value inside it failed to decrypt or
     * read. Distinct from LOCKED (the file-level state) and from a JSON
     * parse failure (handled by ChatPreferences.preserveCorruptData): here
     * the ciphertext itself is the unreadable thing, so the caller must
     * preserve a copy of the underlying file BEFORE any write can replace it.
     */
    fun recordReadFailure(context: Context, name: String, error: String?) =
        recordEvent(context, KEY_READFAIL_PREFIX + name, error)

    fun readFailureNames(context: Context): Set<String> = namesWithPrefix(context, KEY_READFAIL_PREFIX)

    /**
     * The value reads again (a transient decrypt failure passed). Contains-
     * guarded so calling this on every successful read costs an in-memory
     * lookup, not a disk commit. Returns true when a record was cleared.
     */
    fun clearReadFailure(context: Context, name: String): Boolean {
        val p = prefs(context)
        val key = KEY_READFAIL_PREFIX + name
        if (!p.contains(key)) return false
        p.edit(commit = true) { remove(key) }
        return true
    }

    // Process-level once-only latch for log lines tied to states that are
    // hit on every read (a locked file is read constantly by the UI): the
    // journal counts every occurrence; the human-facing log gets one line.
    private val loggedOnce = java.util.Collections.synchronizedSet(HashSet<String>())

    fun shouldLogOnce(key: String): Boolean = loggedOnce.add(key)

    // Reconciliation snapshot markers (see OutageReconciler): remembering an
    // already-written conflict snapshot across process death is what makes a
    // re-run idempotent instead of either duplicating or — far worse —
    // skipping the preservation step.

    fun snapshotMarker(context: Context, name: String): String? = try {
        prefs(context).getString(KEY_SNAPSHOT_PREFIX + name, null)
    } catch (_: Exception) {
        null
    }

    fun setSnapshotMarker(context: Context, name: String, snapshotId: String?) {
        prefs(context).edit(commit = true) {
            if (snapshotId == null) remove(KEY_SNAPSHOT_PREFIX + name)
            else putString(KEY_SNAPSHOT_PREFIX + name, snapshotId)
        }
    }

    /**
     * True when any CHAT-content file (the chat list, a chat history, or a
     * per-chat settings file) is currently lock-recorded or read-failed.
     * Exporters use this to mark a backup's chat envelope as incomplete —
     * an export taken during an outage must never be mistaken for a full
     * copy of the user's chats.
     */
    fun anyChatDataDegraded(context: Context): Boolean {
        val names = lockedNames(context) + readFailureNames(context)
        return names.any {
            it == "chat_list" || it.startsWith("chat_") || it.startsWith("settings.")
        }
    }
}
