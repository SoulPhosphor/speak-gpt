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
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.teslasoft.assistant.preferences.memory.MemoryLog
import java.io.File

/**
 * Encrypted-at-rest replacement for the chat-content SharedPreferences files
 * (chat list, per-chat message history, per-chat settings — the owner asked
 * for chat history to be encrypted like the memory store is).
 *
 * Every call site that used `context.getSharedPreferences(name, MODE_PRIVATE)`
 * for those files now calls [get] with the same logical name. Under the hood
 * the data lives in an EncryptedSharedPreferences file named "enc.<name>"
 * (a DIFFERENT file — mixing encrypted entries into the old plaintext XML
 * would corrupt both). On first access the old plaintext file's entries are
 * copied over, verified, and only then cleared — the plaintext file is left
 * holding a single migration marker.
 *
 * Failure behavior is a per-file state machine (ChatStorageHealth.classify,
 * silent-failure audit Round 4; corrected to the owner's approved lock
 * policy July 12 2026 — the original Round-4 pass redirected locked
 * traffic into plaintext "outage.<name>" files, which the owner rejected
 * as an unapproved plaintext fallback):
 *
 *  - HEALTHY: the encrypted file opens; reads/writes go to it. If a lock
 *    was recorded for this file, the recovery is logged and any legacy
 *    outage data is merged back at the next startup pass
 *    ([reconcileOutageAtStartup]).
 *  - LOCKED: an encrypted file EXISTS but cannot be opened (Keystore key
 *    unavailable — transient failure, cleared credentials, or a restore
 *    onto new hardware; the states are indistinguishable synchronously, so
 *    the journal counts attempts and recovery is automatic if the key ever
 *    returns). **Chat activity is BLOCKED, not redirected (owner policy):**
 *    [get] returns an inert [LockedSharedPreferences] whose reads present
 *    nothing and whose writes are refused (commit() == false, apply() is a
 *    no-op) — NOTHING is written to plaintext storage, the encrypted file
 *    is never read as empty, never written, never cleared, never migrated.
 *    Callers must consult [isLockedName]/ChatPreferences' result-typed
 *    reads instead of interpreting the empty view, and the UI shows the
 *    owner-approved locked screen (ChatStorageLockedActivity). Legacy
 *    outage files created by the earlier Round-4 build are still read and
 *    reconciled when the key returns — but no code path CREATES or WRITES
 *    outage files anymore.
 *  - LEGACY_PLAINTEXT / FRESH_UNENCRYPTED: no encrypted file exists, so
 *    nothing is being masked; the plaintext original stays in use exactly
 *    as before encryption existed, and the normal migration picks it up
 *    when the Keystore becomes available. (This is the one deliberate
 *    exception to "no plaintext writes": a device whose Keystore has never
 *    worked keeps its pre-encryption behavior rather than becoming
 *    unusable; there is no encrypted data to protect or mask there.)
 *
 * Nothing is ever deleted on any failure path. The lock state, per-file, is
 * recorded persistently (ChatStorageHealth's journal, sanitized categories
 * only) and queryable; every preserved artifact is indexed in
 * SnapshotRegistry.
 */
object SecurePrefs {

    private const val ENC_PREFIX = "enc."
    private const val MIGRATED_MARKER = "__migrated_to_encrypted"

    /** Directory (under files/) where unreadable encrypted files are copied
     *  byte-for-byte before anything could overwrite them. */
    private const val RECOVERY_DIR = "storage_recovery"

    private val cache = HashMap<String, SharedPreferences>()

    /** Names currently served by the inert locked view in THIS process.
     *  Cleared per name by [retryUnlock] when the user asks for a retry. */
    private val lockedThisProcess = LinkedHashSet<String>()

    /** Once-per-process log dedup so a lock that is hit on every read does
     *  not flood the Error Log; the journal still counts every occurrence. */
    private val loggedThisProcess = HashSet<String>()

    private var masterKey: MasterKey? = null

    @Synchronized
    fun get(context: Context, name: String): SharedPreferences {
        cache[name]?.let { return it }
        val appContext = context.applicationContext

        val encrypted = try {
            createEncrypted(appContext, ENC_PREFIX + name)
        } catch (e: Exception) {
            return degradedFallback(appContext, name, e)
        }

        if (ChatStorageHealth.clearLock(appContext, name)) {
            MemoryLog.log(appContext, "SecurePrefs", "info",
                "Encrypted storage for '$name' is readable again; data written during the outage is merged back at the next app start."
            )
        }
        migrateIfNeeded(appContext, name, encrypted)
        cache[name] = encrypted
        return encrypted
    }

    /** True while [name] is in the LOCKED state in this process. */
    @Synchronized
    fun isLockedName(name: String): Boolean = name in lockedThisProcess

    /**
     * Whether the authoritative chat list is currently LOCKED. Triggers
     * classification if the file has not been opened yet this process, so
     * activity gates can call it first thing.
     */
    fun isChatStorageLocked(context: Context): Boolean {
        get(context, ChatRenameTransaction.CHAT_LIST_FILE)
        return isLockedName(ChatRenameTransaction.CHAT_LIST_FILE)
    }

    /**
     * The "Try Again" path of the locked screen: drop every locked entry
     * from the cache so the next [get] re-attempts the encrypted open, then
     * probe the chat list. Touches no data — a failed retry leaves the same
     * locked views in place. Must be called off the main thread (Keystore +
     * disk work).
     *
     * @return true when the chat list opened; the caller relaunches the UI.
     */
    fun retryUnlock(context: Context): Boolean {
        synchronized(this) {
            for (name in lockedThisProcess.toList()) cache.remove(name)
            lockedThisProcess.clear()
            // Allow the recovery (or the repeat failure) to log once more.
            loggedThisProcess.removeAll { it.startsWith("lock.") }
        }
        return !isChatStorageLocked(context)
    }

    private fun degradedFallback(context: Context, name: String, e: Exception): SharedPreferences {
        val encExists = encryptedFileExists(context, name)
        val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val plainHasData = try {
            plain.all.keys.any { it != MIGRATED_MARKER }
        } catch (_: Exception) {
            false
        }

        return when (ChatStorageHealth.classify(false, encExists, plainHasData)) {
            ChatStorageHealth.FileState.LOCKED -> {
                ChatStorageHealth.recordLock(context, name, e)
                if (loggedThisProcess.add("lock.$name")) {
                    val msg = "Encrypted storage for '$name' exists but cannot be opened " +
                        "(${StorageErrorSanitizer.categorize(e)}). The data is LOCKED, not lost: nothing touches " +
                        "the encrypted file, and saving is paused until storage is available again."
                    MemoryLog.log(context, "SecurePrefs", "error", msg)
                    Logger.log(context, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "SecurePrefs", "error", msg)
                }
                lockedThisProcess.add(name)
                // Owner policy (July 12 2026): BLOCK, never redirect to
                // plaintext. The inert view presents nothing and refuses
                // every write; result-typed reads and the UI gates surface
                // the state explicitly.
                val locked = LockedSharedPreferences
                cache[name] = locked
                locked
            }
            else -> {
                // No encrypted file exists → nothing is masked. Pre-encryption
                // behavior: the plaintext original is authoritative and the
                // normal migration consumes it when the Keystore works.
                if (loggedThisProcess.add("plain.$name")) {
                    MemoryLog.log(context, "SecurePrefs", "error",
                        "Encrypted preferences unavailable for '$name' (${StorageErrorSanitizer.categorize(e)}); no encrypted data exists, so the plaintext file stays in use until encryption is available."
                    )
                }
                cache[name] = plain
                plain
            }
        }
    }

    /**
     * The LOCKED-state view: reads present nothing (callers must consult the
     * lock state, never interpret this as empty — that is what the
     * result-typed reads in ChatPreferences are for) and every write is
     * refused (commit() == false, apply() drops silently at THIS layer;
     * operational writers check [isLockedName] first and refuse loudly).
     * Stateless, hence a shared object.
     */
    internal object LockedSharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = LockedEditor
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private object LockedEditor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = false
            override fun apply() {}
        }
    }

    private fun encryptedFileExists(context: Context, name: String): Boolean = try {
        File(File(context.dataDir, "shared_prefs"), "$ENC_PREFIX$name.xml").exists()
    } catch (_: Exception) {
        // If existence can't even be checked, assume the safe direction:
        // treat encrypted data as possibly present so nothing masks it.
        true
    }

    private fun createEncrypted(context: Context, fileName: String): SharedPreferences {
        if (masterKey == null) {
            masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey!!,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateIfNeeded(context: Context, name: String, encrypted: SharedPreferences) {
        try {
            val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = plain.all.filterKeys { it != MIGRATED_MARKER }
            if (entries.isEmpty()) return

            val encryptedBefore = encrypted.all
            encrypted.edit(commit = true) {
                for ((key, value) in entries) {
                    if (key in encryptedBefore.keys) continue // never clobber newer encrypted data
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                    }
                }
            }

            // Keys present on BOTH sides with different values are a real
            // conflict — e.g. an older build's fallback wrote into the
            // plaintext file during a Keystore outage. The old code skipped
            // them above and then cleared the plaintext file, silently
            // destroying one side. Preserve the plaintext copies in an
            // encrypted snapshot BEFORE the clear; if that preservation
            // fails, the clear does not happen and the migration retries.
            val conflicts = entries.filter { (k, v) ->
                k in encryptedBefore.keys && encryptedBefore[k] != v
            }
            if (conflicts.isNotEmpty()) {
                val snapName = "${name}_conflict_${SnapshotRegistry.uniqueSuffix()}"
                val snap = createEncrypted(context, ENC_PREFIX + snapName)
                val wrote = snap.edit().apply {
                    for ((key, value) in conflicts) {
                        when (value) {
                            is String -> putString(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                        }
                    }
                }.commit()
                if (!wrote) {
                    MemoryLog.log(context, "SecurePrefs", "error",
                        "Migration of '$name' found ${conflicts.size} conflicting entr${if (conflicts.size == 1) "y" else "ies"} but could not preserve them; plaintext kept for retry."
                    )
                    return
                }
                SnapshotRegistry.record(
                    context, snapName, name,
                    SnapshotRegistry.ORIGIN_LEGACY_CONFLICT, "migration_conflict"
                )
                val logMsg = "Migration of '$name' found ${conflicts.size} entr${if (conflicts.size == 1) "y" else "ies"} that differ between the plaintext and encrypted copies. " +
                    "The plaintext versions were preserved in '$snapName'; the encrypted versions stay in place. Neither was deleted."
                MemoryLog.log(context, "SecurePrefs", "error", logMsg)
                Logger.log(context, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "SecurePrefs", "error", logMsg)
            }

            // Destroy the plaintext copy only once every key is readable from
            // the encrypted side (or preserved in the conflict snapshot).
            if (encrypted.all.keys.containsAll(entries.keys)) {
                plain.edit(commit = true) {
                    clear()
                    putBoolean(MIGRATED_MARKER, true)
                }
                MemoryLog.log(context, "SecurePrefs", "info",
                    "Migrated ${entries.size} entr${if (entries.size == 1) "y" else "ies"} of '$name' to encrypted storage."
                )
            } else {
                MemoryLog.log(context, "SecurePrefs", "error",
                    "Migration of '$name' did not verify; plaintext kept for retry on next access."
                )
            }
        } catch (e: Exception) {
            // Keep the plaintext data untouched; a failed migration retries on
            // the next access.
            MemoryLog.log(context, "SecurePrefs", "error", "Migration of '$name' failed: ${e.message}")
        }
    }

    /**
     * Copy the underlying encrypted XML file of [name] byte-for-byte into
     * files/storage_recovery/ — called when a value inside an OPEN encrypted
     * file fails to decrypt (ChatStorageHealth read-failure state). The API
     * offers no way to extract the raw ciphertext of one entry, so the whole
     * file is preserved before any subsequent save can replace the only
     * copy. Once per process per file; the copy is never deleted by code.
     *
     * @return the preserved file's name, or null when there was nothing to
     *         copy or the copy failed (the failure is logged; callers
     *         proceed — preservation is best-effort on top of the journal
     *         record, never a gate on reading).
     */
    @Synchronized
    fun preserveEncryptedFileCopy(context: Context, name: String): String? {
        if (!loggedThisProcess.add("preserve.$name")) return null
        return try {
            val src = File(File(context.dataDir, "shared_prefs"), "$ENC_PREFIX$name.xml")
            if (!src.exists()) return null
            val dir = File(context.filesDir, RECOVERY_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val dst = File(dir, "$ENC_PREFIX$name.${SnapshotRegistry.uniqueSuffix()}.xml")
            src.copyTo(dst, overwrite = false)
            SnapshotRegistry.record(
                context, dst.name, name,
                SnapshotRegistry.ORIGIN_READ_FAILURE, "undecryptable_value"
            )
            MemoryLog.log(context, "SecurePrefs", "error",
                "A value in '$name' could not be decrypted; the encrypted file was preserved as '${dst.name}' before anything can overwrite it."
            )
            dst.name
        } catch (e: Exception) {
            MemoryLog.log(context, "SecurePrefs", "error",
                "Could not preserve a copy of the unreadable encrypted file '$name': ${e.message}"
            )
            null
        }
    }

    // ----- Startup outage reconciliation -----------------------------------

    /**
     * Merge any `outage.<name>` files back into their encrypted homes. Only
     * LEGACY outage files exist — the earlier Round-4 build created them;
     * since the owner's July 12 2026 lock policy nothing writes them, but
     * data already inside them must still be recovered. Runs on the startup
     * background thread BEFORE RenameJournal.reconcile (which consults the
     * chat list this pass may be restoring) and before the memory-store
     * housekeeping. Restartable and idempotent — every decision lives in
     * OutageReconciler (pure, unit-tested) and every marker in
     * ChatStorageHealth's journal, so a process death at any boundary
     * re-converges on the next start. Files whose encrypted side is still
     * locked are left untouched for a later start. The chat-list merge runs
     * under ChatPreferences.CHAT_LIST_LOCK — the same monitor every normal
     * chat-list mutation holds — so a user write can never interleave with
     * the merge's read-modify-write-verify-delete sequence.
     *
     * Also runs the one-time discovery of pre-registry preserved artifacts
     * (SnapshotRegistry.discoverLegacy) — cheap, idempotent, best-effort.
     */
    fun reconcileOutageAtStartup(context: Context) {
        val appContext = context.applicationContext

        // One-time chore latch (Build Phase 1). Once the recovery scan has fully
        // settled — no outage file left to merge, no chat rename still mid-
        // recovery — neither the legacy-snapshot discovery nor the outage-file
        // scan needs to run again. Before this latch, discoverLegacy re-scanned
        // two directories and re-parsed the whole SnapshotRegistry on EVERY
        // launch (and the registry only grows), pure startup waste after the
        // one-time recovery work is done. New preserved artifacts are indexed
        // when created (SnapshotRegistry.record), so nothing pre-registry can
        // appear after the first settled scan.
        if (StartupHealth.isRecoveryScanSettled(appContext)) return

        SnapshotRegistry.discoverLegacy(appContext)
        val names = listOutageNames(appContext)

        var deferred = 0
        if (names.isNotEmpty()) {
            val result = OutageReconciler.reconcile(
                androidFiles(appContext, names),
                ChatPreferences.CHAT_LIST_LOCK
            )
            // A merged file's encrypted side opened and absorbed the outage data:
            // its lock record is finished business. (Locks for files that are
            // simply read again also clear in get(); this covers files nothing
            // reads anymore, which would otherwise pin anyChatDataDegraded — and
            // with it the exporter's incomplete marking — forever.)
            for (name in result.merged) {
                ChatStorageHealth.clearLock(appContext, name)
            }
            if (result.merged.isNotEmpty() || result.deferred.isNotEmpty()) {
                val msg = "Storage-outage recovery: ${result.merged.size} file(s) merged back" +
                    (if (result.conflictsPreserved.isNotEmpty()) ", ${result.conflictsPreserved.size} with both copies preserved" else "") +
                    (if (result.deferred.isNotEmpty()) "; ${result.deferred.size} still waiting for the encryption key" else "") + "."
                MemoryLog.log(appContext, "SecurePrefs", "info", msg)
                Logger.log(appContext, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "SecurePrefs", "info", msg)
            }
            deferred = result.deferred.size
        }

        // Settle the latch only when there is genuinely nothing left to do: no
        // outage file deferred waiting for its key, and no chat rename still
        // mid-recovery. A masked or in-flux chat list must never drive a "done"
        // decision (the same rule RenameJournal reconciliation follows) — while
        // either is pending the scan simply re-runs next start (idempotent).
        if (deferred == 0 && !RenameJournal.hasPending(appContext)) {
            StartupHealth.markRecoveryScanSettled(appContext)
        }
    }

    private fun listOutageNames(context: Context): List<String> = try {
        val dir = File(context.dataDir, "shared_prefs")
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith(OutageReconciler.OUTAGE_PREFIX) && it.name.endsWith(".xml") }
            .map { it.name.removePrefix(OutageReconciler.OUTAGE_PREFIX).removeSuffix(".xml") }
            .filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun androidFiles(context: Context, names: List<String>): OutageReconciler.Files =
        object : OutageReconciler.Files {
            override fun outageNames(): List<String> =
                // A name still locked this process can't open its encrypted
                // side anyway; skipping it here just avoids pointless work.
                names.filter { !isLockedName(it) }

            override fun encryptedOpens(name: String): Boolean = try {
                synchronized(this@SecurePrefs) { createEncrypted(context, ENC_PREFIX + name) }
                true
            } catch (_: Exception) {
                false
            }

            override fun readEncrypted(name: String): Map<String, Any?>? = try {
                synchronized(this@SecurePrefs) { createEncrypted(context, ENC_PREFIX + name) }.all
            } catch (_: Exception) {
                null
            }

            override fun readOutage(name: String): Map<String, Any?> = try {
                context.getSharedPreferences(OutageReconciler.OUTAGE_PREFIX + name, Context.MODE_PRIVATE).all
            } catch (_: Exception) {
                emptyMap()
            }

            override fun putEncrypted(name: String, entries: Map<String, Any?>): Boolean = try {
                val prefs = synchronized(this@SecurePrefs) { createEncrypted(context, ENC_PREFIX + name) }
                prefs.edit().apply {
                    for ((key, value) in entries) {
                        when (value) {
                            is String -> putString(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                        }
                    }
                }.commit()
            } catch (_: Exception) {
                false
            }

            override fun snapshot(name: String, entries: Map<String, Any?>): String? = try {
                // Preserved copies are written ENCRYPTED (the Keystore is
                // healthy on this path) — the plaintext outage data is
                // re-protected at rest the moment it stops being needed
                // for live operation. Collision-proof name + a permanent
                // registry entry (the idempotency MARKER in the journal is
                // cleared after settlement; the registry entry never is).
                val snapName = "${name}_recovered_${SnapshotRegistry.uniqueSuffix()}"
                val ok = putEncrypted(snapName, entries.filterValues { it != null })
                if (ok) {
                    SnapshotRegistry.record(
                        context, snapName, name,
                        SnapshotRegistry.ORIGIN_OUTAGE_RECONCILIATION, "outage_conflict"
                    )
                    snapName
                } else null
            } catch (_: Exception) {
                null
            }

            override fun deleteOutage(name: String): Boolean = try {
                context.getSharedPreferences(OutageReconciler.OUTAGE_PREFIX + name, Context.MODE_PRIVATE)
                    .edit().clear().commit()
                context.deleteSharedPreferences(OutageReconciler.OUTAGE_PREFIX + name)
            } catch (_: Exception) {
                false
            }

            override fun snapshotMarker(name: String): String? =
                ChatStorageHealth.snapshotMarker(context, name)

            override fun setSnapshotMarker(name: String, snapshotId: String?) =
                ChatStorageHealth.setSnapshotMarker(context, name, snapshotId)

            override fun log(message: String) {
                MemoryLog.log(context, "SecurePrefs", "warning", message)
                Logger.log(context, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "SecurePrefs", "warning", message)
            }
        }
}
