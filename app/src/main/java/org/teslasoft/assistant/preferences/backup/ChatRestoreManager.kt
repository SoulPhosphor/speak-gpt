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
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.RenameJournal
import org.teslasoft.assistant.preferences.SnapshotRegistry
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Same-device chat recovery restore (Build Phase 3 item 5): a wholesale,
 * journaled REPLACEMENT of encrypted chat storage from a verified chats
 * recovery archive. ENGINE ONLY in this phase — the user-facing flow that
 * invokes it ships with the Build Phase 4 chat-recovery wording set, which
 * the owner has not approved yet (build plan Build Phase 4 item 6: "Each
 * lands only after the owner approves the words in chat"). Nothing calls
 * [restoreFromArchive] from UI today; [resumeIfPending] runs at startup so a
 * swap interrupted by process death is finished from its verified staging.
 *
 * Required sequence (all inside, in order):
 *  validate the COMPLETE archive (manifest + per-file hashes + strict entry
 *  whitelist) → extract into staging → re-verify the staged bytes → under
 *  CHAT_LIST_LOCK: quarantine every current chat file (SnapshotRegistry-
 *  indexed pre-restore copies) → journal SWAPPING → replace the chat file
 *  set → clear the journal. The caller then performs the controlled app
 *  restart: live SharedPreferences/SecurePrefs handles cache the OLD files
 *  in memory, so the process must not keep running on them.
 *
 * Boundaries (plan): repairs damaged chat FILES on this phone; it cannot
 * cure a lost Keystore key (Round-4 lock machinery owns that case). Honors
 * [RenameJournal.hasPending] — a half-finished rename must settle first.
 * Lock order respected: CHAT_LIST_LOCK is taken here; SecurePrefs' monitor
 * is never taken inside it by this code.
 */
object ChatRestoreManager {

    private const val FILE = "storage_health"
    private const val KEY_PHASE = "chatrestore.phase"
    private const val KEY_STAGING = "chatrestore.staging_dir"
    private const val KEY_FILES = "chatrestore.files"

    data class Result(val ok: Boolean, val detail: String?)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun sharedPrefsDir(context: Context) = File(context.dataDir, "shared_prefs")

    /**
     * Restore chat storage from [archive]. Runs on a worker thread only.
     * On success the app MUST be restarted by the caller before any chat
     * read/write happens.
     */
    fun restoreFromArchive(context: Context, archive: File): Result {
        val appContext = context.applicationContext
        if (RenameJournal.hasPending(appContext)) {
            return Result(false, "a chat rename is still being reconciled")
        }

        // ---- 1. validate the whole archive before touching anything ----
        val entries: Map<String, ByteArray>
        try {
            entries = readAndVerifyArchive(archive)
        } catch (e: Exception) {
            return Result(false, "archive failed verification: ${e.javaClass.simpleName}")
        }

        // ---- 2. extract to staging + re-verify the staged bytes ----
        val staging = File(appContext.filesDir, "chat_restore_staging/${SnapshotRegistry.uniqueSuffix()}")
        try {
            if (!staging.mkdirs()) return Result(false, "could not create staging")
            for ((name, bytes) in entries) {
                File(staging, name).writeBytes(bytes)
            }
            for ((name, bytes) in entries) {
                val staged = File(staging, name)
                if (!staged.exists() || !MessageDigest.isEqual(sha256(staged.readBytes()), sha256(bytes))) {
                    throw IllegalStateException("staged file did not verify: $name")
                }
            }
        } catch (e: Exception) {
            try { staging.deleteRecursively() } catch (_: Exception) { }
            return Result(false, "staging failed: ${e.javaClass.simpleName}")
        }
        writeJournal(appContext, ChatRestorePlanner.PHASE_STAGED, staging.absolutePath, entries.keys)

        // ---- 3. the journaled swap, under the chat-list lock ----
        return synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            try {
                quarantineCurrentChatFiles(appContext)
                writeJournal(appContext, ChatRestorePlanner.PHASE_SWAPPING, staging.absolutePath, entries.keys)
                performSwap(appContext, staging, entries.keys)
                clearJournal(appContext)
                try { staging.deleteRecursively() } catch (_: Exception) { }
                DatabaseHealthState.logHealth(appContext, "info",
                    "Chat storage restored from recovery backup ${archive.name} (${entries.size} files). " +
                        "Previous files preserved in storage_recovery. App restart required.")
                Result(true, null)
            } catch (e: Exception) {
                DatabaseHealthState.logHealth(appContext, "error",
                    "Chat restore failed mid-swap (${e.javaClass.simpleName}); the journal will finish it at next start.")
                Result(false, e.javaClass.simpleName)
            }
        }
    }

    /**
     * Startup recovery (MainApplication, BEFORE the outage reconcile and
     * before RenameJournal.reconcile): finish or discard an interrupted
     * restore per the pure [ChatRestorePlanner]. Idempotent; never throws.
     */
    fun resumeIfPending(context: Context) {
        val appContext = context.applicationContext
        try {
            val p = prefs(appContext)
            val phase = p.getString(KEY_PHASE, null) ?: return
            val stagingPath = p.getString(KEY_STAGING, null)
            val names = readJournalFiles(p.getString(KEY_FILES, null))
            val staging = stagingPath?.let { File(it) }
            val stagingComplete = staging != null && names.isNotEmpty() &&
                names.all { File(staging, it).exists() }
            when (ChatRestorePlanner.planRecovery(phase, stagingComplete)) {
                ChatRestorePlanner.Recovery.NOTHING -> return
                ChatRestorePlanner.Recovery.RESUME_SWAP -> {
                    synchronized(ChatPreferences.CHAT_LIST_LOCK) {
                        performSwap(appContext, staging!!, names)
                    }
                    clearJournal(appContext)
                    try { staging!!.deleteRecursively() } catch (_: Exception) { }
                    DatabaseHealthState.logHealth(appContext, "warning",
                        "An interrupted chat restore was finished from its verified staging at startup.")
                }
                ChatRestorePlanner.Recovery.DISCARD_STAGING -> {
                    clearJournal(appContext)
                    try { staging?.deleteRecursively() } catch (_: Exception) { }
                }
                ChatRestorePlanner.Recovery.UNRECOVERABLE -> {
                    clearJournal(appContext)
                    DatabaseHealthState.logHealth(appContext, "error",
                        "An interrupted chat restore could not be finished (staging incomplete). " +
                            "The pre-restore copies in storage_recovery are the recovery source.")
                }
            }
        } catch (_: Exception) { /* startup recovery must never crash launch */ }
    }

    // ---- internals ----------------------------------------------------------

    /** Full validation: manifest present + complete, every listed file
     *  present with matching SHA-256, no unexpected entries, every name on
     *  the strict chat-storage whitelist. Returns name → bytes. */
    private fun readAndVerifyArchive(archive: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(archive).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json")
                ?: throw IllegalStateException("missing manifest")
            val meta = JSONObject(
                zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            )
            if (!meta.optBoolean("complete", false)) throw IllegalStateException("archive marked incomplete")
            val fileHashes = meta.getJSONObject("file_hashes")
            val names = fileHashes.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (!ChatRestorePlanner.isAllowedEntryName(name)) {
                    throw IllegalStateException("disallowed entry name")
                }
                val entry = zip.getEntry(name) ?: throw IllegalStateException("missing $name")
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (hex(sha256(bytes)) != fileHashes.getString(name)) {
                    throw IllegalStateException("hash mismatch for $name")
                }
                out[name] = bytes
            }
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.name != "manifest.json" && e.name !in out) {
                    throw IllegalStateException("unexpected entry")
                }
            }
        }
        if (out.isEmpty()) throw IllegalStateException("archive holds no chat files")
        return out
    }

    /** Copy every current chat-storage file into an indexed pre-restore
     *  quarantine. A copy failure aborts the restore (throws) — the current
     *  files must be preserved before anything replaces them. */
    private fun quarantineCurrentChatFiles(context: Context) {
        val dir = sharedPrefsDir(context)
        val current = (dir.listFiles() ?: emptyArray())
            .filter { ChatRestorePlanner.isChatStorageFileName(it.name) }
        if (current.isEmpty()) return
        val quarantineDir = File(context.filesDir, "storage_recovery")
        if (!quarantineDir.exists() && !quarantineDir.mkdirs()) {
            throw IllegalStateException("quarantine dir unavailable")
        }
        val marker = SnapshotRegistry.uniqueSuffix()
        for (file in current) {
            val copy = File(quarantineDir, "${file.name}.pre_restore_$marker")
            file.copyTo(copy, overwrite = false)
            SnapshotRegistry.record(
                context, copy.name,
                file.name.removePrefix("enc.").removeSuffix(".xml"),
                SnapshotRegistry.ORIGIN_PRE_RESTORE, "chat_restore"
            )
        }
    }

    /** The wholesale replacement: delete every current chat-storage file
     *  (already quarantined), then copy the staged set in. Idempotent — safe
     *  to re-run from startup recovery. */
    private fun performSwap(context: Context, staging: File, names: Collection<String>) {
        val dir = sharedPrefsDir(context)
        for (file in (dir.listFiles() ?: emptyArray())) {
            if (ChatRestorePlanner.isChatStorageFileName(file.name) && file.name !in names) {
                try { file.delete() } catch (_: Exception) { }
            }
        }
        for (name in names) {
            val staged = File(staging, name)
            staged.copyTo(File(dir, name), overwrite = true)
        }
    }

    private fun writeJournal(context: Context, phase: String, stagingPath: String, names: Collection<String>) {
        try {
            prefs(context).edit(commit = true) {
                putString(KEY_PHASE, phase)
                putString(KEY_STAGING, stagingPath)
                putString(KEY_FILES, JSONArray(names.toList()).toString())
            }
        } catch (_: Exception) { }
    }

    private fun clearJournal(context: Context) {
        try {
            prefs(context).edit(commit = true) {
                remove(KEY_PHASE)
                remove(KEY_STAGING)
                remove(KEY_FILES)
            }
        } catch (_: Exception) { }
    }

    private fun readJournalFiles(json: String?): List<String> = try {
        if (json.isNullOrEmpty()) emptyList()
        else JSONArray(json).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
