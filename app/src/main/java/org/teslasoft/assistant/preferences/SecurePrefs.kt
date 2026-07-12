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
 * silent-failure audit Round 4 — it replaced a single plaintext fallback
 * that let locked encrypted data masquerade as an empty chat list and then
 * silently destroyed anything written during the outage):
 *
 *  - HEALTHY: the encrypted file opens; reads/writes go to it. If a lock
 *    was recorded for this file, the recovery is logged and outage data is
 *    merged back at the next startup pass ([reconcileOutageAtStartup]).
 *  - LOCKED: an encrypted file EXISTS but cannot be opened (Keystore key
 *    unavailable — transient failure, cleared credentials, or a restore
 *    onto new hardware; the states are indistinguishable synchronously, so
 *    the journal counts attempts and recovery is automatic if the key ever
 *    returns). Reads and writes are redirected to a separate
 *    "outage.<name>" plaintext file: the encrypted file is NEVER read as
 *    empty, never written, never cleared, never migrated while locked, and
 *    outage writes can never be consumed by the legacy migration pass
 *    (which only looks at the original file name). The outage file holds
 *    only data the user creates during the outage — the same at-rest
 *    protection level the app had before encryption existed — and is
 *    merged back and deleted by OutageReconciler once the key returns.
 *  - LEGACY_PLAINTEXT / FRESH_UNENCRYPTED: no encrypted file exists, so
 *    nothing is being masked; the plaintext original stays in use exactly
 *    as before encryption, and the normal migration picks it up when the
 *    Keystore becomes available.
 *
 * Nothing is ever deleted on any failure path. The lock state, per-file, is
 * recorded persistently (ChatStorageHealth's journal) and queryable —
 * surfacing it in UI is an owner wording decision; the storage layer's job
 * is that the data survives to be surfaced.
 */
object SecurePrefs {

    private const val ENC_PREFIX = "enc."
    private const val MIGRATED_MARKER = "__migrated_to_encrypted"

    /** Directory (under files/) where unreadable encrypted files are copied
     *  byte-for-byte before anything could overwrite them. */
    private const val RECOVERY_DIR = "storage_recovery"

    private val cache = HashMap<String, SharedPreferences>()

    /** Names redirected to their outage file in THIS process. The reconciler
     *  must skip these: the app is actively writing to the outage copy, so
     *  merging and deleting it mid-session would drop those writes. */
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

    /** True while [name] is served by its outage file in this process. */
    @Synchronized
    fun isLockedThisProcess(name: String): Boolean = name in lockedThisProcess

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
                ChatStorageHealth.recordLock(context, name, e.message ?: e.javaClass.simpleName)
                if (loggedThisProcess.add("lock.$name")) {
                    val msg = "Encrypted storage for '$name' exists but cannot be opened (${e.message}). " +
                        "The data is LOCKED, not lost: nothing touches the encrypted file, and anything saved " +
                        "meanwhile goes to a separate recovery file that is merged back when the key returns."
                    MemoryLog.log(context, "SecurePrefs", "error", msg)
                    Logger.log(context, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "SecurePrefs", "error", msg)
                }
                lockedThisProcess.add(name)
                val outage = context.getSharedPreferences(OutageReconciler.OUTAGE_PREFIX + name, Context.MODE_PRIVATE)
                cache[name] = outage
                outage
            }
            else -> {
                // No encrypted file exists → nothing is masked. Pre-encryption
                // behavior: the plaintext original is authoritative and the
                // normal migration consumes it when the Keystore works.
                if (loggedThisProcess.add("plain.$name")) {
                    MemoryLog.log(context, "SecurePrefs", "error",
                        "Encrypted preferences unavailable for '$name' (${e.message}); no encrypted data exists, so the plaintext file stays in use until encryption is available."
                    )
                }
                cache[name] = plain
                plain
            }
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
                val snapName = "${name}_conflict_${System.currentTimeMillis()}"
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
            val dst = File(dir, "$ENC_PREFIX$name.${System.currentTimeMillis()}.xml")
            src.copyTo(dst, overwrite = false)
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
     * Merge any `outage.<name>` files back into their encrypted homes. Runs
     * on the startup background thread BEFORE RenameJournal.reconcile (which
     * consults the chat list this pass may be restoring) and before the
     * memory-store housekeeping. Restartable and idempotent — every decision
     * lives in OutageReconciler (pure, unit-tested) and every marker in
     * ChatStorageHealth's journal, so a process death at any boundary
     * re-converges on the next start. Files whose encrypted side is still
     * locked, or that the app is actively serving from their outage copy in
     * this process, are left untouched for a later start.
     */
    fun reconcileOutageAtStartup(context: Context) {
        val appContext = context.applicationContext
        val names = listOutageNames(appContext)
        if (names.isEmpty()) return

        val result = OutageReconciler.reconcile(androidFiles(appContext, names))
        if (result.merged.isNotEmpty() || result.deferred.isNotEmpty()) {
            val msg = "Storage-outage recovery: ${result.merged.size} file(s) merged back" +
                (if (result.conflictsPreserved.isNotEmpty()) ", ${result.conflictsPreserved.size} with both copies preserved" else "") +
                (if (result.deferred.isNotEmpty()) "; ${result.deferred.size} still waiting for the encryption key" else "") + "."
            MemoryLog.log(appContext, "SecurePrefs", "info", msg)
            Logger.log(appContext, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "SecurePrefs", "info", msg)
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
                // Never reconcile a file this process is actively serving
                // from its outage copy — the merge would race live writes.
                names.filter { !isLockedThisProcess(it) }

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
                // for live operation.
                val snapName = "${name}_recovered_${System.currentTimeMillis()}"
                val ok = putEncrypted(snapName, entries.filterValues { it != null })
                if (ok) snapName else null
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
