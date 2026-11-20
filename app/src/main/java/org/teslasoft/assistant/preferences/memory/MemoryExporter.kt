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

package org.teslasoft.assistant.preferences.memory

import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import android.content.Context
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Backup/export for the companion memory store. One format everywhere: the
 * schema-shaped JSON from MemorySeedCodec, with the app's chat history riding
 * along under `app_chats` (integration plan D10 — "chats & memories" both
 * travel; embeddings never do, they're per-device and rebuilt on import).
 *
 * Degradation protection is the export, not hope (app_adaptation_notes 11a):
 * besides the manual SAF export in the memory settings screen, a rotating
 * automatic export runs at app start, throttled to once a day, keeping the
 * last [ROTATION_KEEP] files. It lives in the app's external files dir —
 * survives crashes and corrupt stores, not an uninstall; the manual export is
 * how the user puts a copy somewhere durable (their own drive/cloud folder).
 * The sync phase replaces this with a user-chosen SAF folder.
 */
object MemoryExporter {

    private const val BACKUP_DIR = "memory_backups"
    private const val ROTATION_KEEP = 5

    /** Leftover temporary files of an export that was cut off (the app was
     *  killed mid-write). Only this exporter writes them, so removing them
     *  while it holds [exportLock] never touches a running export. */
    private val STALE_TEMP = Regex("^memory-(?:export|backup)-.+\\.json\\.tmp$")

    /** One export at a time: the backup before a reset and the manual
     *  export never write together. */
    private val exportLock = Any()

    /**
     * Write a backup right now into the rotating backup dir, ignoring the daily
     * throttle. Used by "Reset memories" when the user leaves the backup-first
     * option checked. Returns the file name, or null on failure.
     *
     * The caller is about to DELETE the store on the strength of this file,
     * so "probably written" isn't enough: the file is streamed to a temporary
     * name, forced to disk, read back and checked complete before it takes
     * its real name. A null return means the reset must not proceed.
     */
    fun writeBackupNow(context: Context): String? {
        return try {
            if (isChatListUnavailable(context)) {
                // No backup while the chat list is unreadable: it would carry
                // no chats and could later be mistaken for the newest good
                // copy. Returning null aborts the caller (the reset gate
                // already treats null as "do not proceed"); the manual UI
                // shows the owner-approved dialog before ever calling here.
                MemoryLog.log(context, "MemoryExport", "error",
                    "Backup refused: encrypted chat storage is unavailable, so a complete chat backup cannot be created. Existing backups are untouched.")
                return null
            }
            val dir = File(context.getExternalFilesDir(null), BACKUP_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                MemoryLog.log(context, "MemoryExport", "error", "Backup-before-reset failed: the backup folder could not be created.")
                null
            } else {
                val stamp = MemoryStore.nowIso().replace(":", "-")
                val file = File(dir, "memory-backup-$stamp.json")
                if (writeExportFile(context, dir, file) != null) {
                    MemoryLog.log(context, "MemoryExport", "info", "Backup written before reset: ${file.name}")
                    file.name
                } else {
                    MemoryLog.log(context, "MemoryExport", "error", "Backup-before-reset failed: the file did not verify after writing.")
                    null
                }
            }
        } catch (e: Throwable) {
            // OutOfMemoryError is caught too: a null return refuses the reset
            // and shows the backup-failed dialog instead of crashing the app.
            if (e !is Exception && e !is OutOfMemoryError) throw e
            MemoryLog.log(context, "MemoryExport", "error", "Backup-before-reset failed: ${e.message}")
            null
        }
    }

    /**
     * The manual export to a file the user chose. The export is streamed to
     * a private temporary file and checked complete first, then copied to
     * the file the user chose, so a failure never leaves a cut-off backup in the
     * user's folder. Throws on failure; the caller reports it.
     */
    fun exportTo(context: Context, openDestination: () -> OutputStream?) {
        val staged = File(context.cacheDir, "memory-export-staging-${System.nanoTime()}.json")
        try {
            synchronized(exportLock) {
                MemoryExportStream.writeVerifiedFile(staged) { out -> streamExport(context, out) }
            } ?: throw IllegalStateException("the export did not verify after writing")
            // Opened only now: a failed export never creates or empties the
            // user's file.
            val destination = openDestination()
                ?: throw IllegalStateException("the destination file could not be opened")
            destination.use { out ->
                FileInputStream(staged).use { input -> input.copyTo(out, 64 * 1024) }
                out.flush()
            }
        } finally {
            staged.delete()
        }
    }

    /**
     * True when the AUTHORITATIVE chat list cannot be read (locked or
     * corrupt). In that state no chat backup may be produced at all — an
     * export of the masked (empty) list would look like "no chats", the
     * exact masquerade Round 4 forbids. Callers block the operation and the
     * manual flows show the owner-approved "Chat backup unavailable" dialog.
     */
    fun isChatListUnavailable(context: Context): Boolean = try {
        // State only — this runs on every app start (before the daily throttle)
        // and must NOT parse every chat's history. includeFirstMessage = false
        // keeps it O(list size), not O(all conversations).
        !ChatStorageHealth.isAuthoritative(
            ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false).state
        )
    } catch (_: Exception) {
        true // unknown state fails conservatively: no backup claimed complete
    }

    /**
     * Streams the full export into [out]: the memory store, then every chat
     * one at a time, then `export_meta`. Same document [MemorySeedCodec
     * .serialize] produces, without ever holding it in memory.
     */
    internal fun streamExport(context: Context, out: OutputStream): MemoryExportStream.Written {
        val store = MemoryStore.getInstance(context)
        // Belt: callers gate on isChatListUnavailable before invoking. If a
        // path reaches here anyway while the list is unavailable, omit the
        // chat envelope entirely and mark it incomplete — never serialize a
        // masked empty list as though it were all chats.
        val listUnavailable = isChatListUnavailable(context)
        return MemoryExportStream.write(
            out,
            memoryRoot = MemorySeedCodec.memoryRoot(store.exportData()),
            chats = if (listUnavailable) null else appChats(context),
            exportedAtIso = MemoryStore.nowIso(),
            storageDegraded = { chatStorageDegraded(context) }
        )
    }

    /** Streams into [file] inside [dir] after clearing leftovers of cut-off
     *  exports. Null when the result did not verify. */
    private fun writeExportFile(context: Context, dir: File, file: File): MemoryExportStream.Written? =
        synchronized(exportLock) {
            dir.listFiles { f -> STALE_TEMP.matches(f.name) }?.forEach { it.delete() }
            MemoryExportStream.writeVerifiedFile(file) { out -> streamExport(context, out) }
        }

    private fun chatStorageDegraded(context: Context): Boolean = try {
        ChatStorageHealth.anyChatDataDegraded(context)
    } catch (_: Exception) {
        false
    }

    /**
     * The app's chats serialized verbatim (list metadata + raw message maps),
     * read one chat at a time as the stream asks for them. Chat import/merge
     * is deliberately NOT part of this phase — the envelope exists so today's
     * backups already carry everything a future device needs.
     *
     * A chat whose history is LOCKED/CORRUPT/FAILED (Round 4) is carried as
     * an identifier-only entry with `"unavailable": true` and NO "messages"
     * key — never a fabricated empty history — and the whole export is
     * marked incomplete. Recovery snapshots are never included as chats.
     */
    private fun appChats(context: Context): Iterator<MemoryExportStream.Chat> {
        val chatPreferences = ChatPreferences.getChatPreferences()
        // first_message is discarded (chatHeader skips it); read the list
        // without the whole-store history parse and read each chat's history
        // exactly once, via getChatByIdResult.
        val list = chatPreferences.getChatListResult(context, includeFirstMessage = false).chats
        return list.asSequence()
            .mapNotNull { chat ->
                val header = chatHeader(chat, ChatPreferences.storedChatId(chat)) ?: return@mapNotNull null
                val history = chatPreferences.getChatByIdResult(
                    context, ChatPreferences.storedChatId(chat), DamagedChatOperation.MEMORY
                )
                MemoryExportStream.Chat(
                    header,
                    if (ChatStorageHealth.isAuthoritative(history.state)) history.messages else null
                )
            }
            .iterator()
    }

    /**
     * A chat's list entry as written in `app_chats`: `name`, then `chat_id`,
     * then every other list field except `first_message`. A field present in
     * the list entry replaces the derived one, and a null field is left out,
     * exactly as the previous object-based export did. Null without a name.
     */
    internal fun chatHeader(chat: Map<String, Any?>, storedChatId: String): Map<String, Any>? {
        val name = chat["name"] ?: return null
        val header = LinkedHashMap<String, Any>()
        header["name"] = name
        header["chat_id"] = storedChatId
        for ((key, value) in chat) {
            if (key == "name" || key == "first_message") continue
            if (value == null) header.remove(key) else header[key] = value
        }
        return header
    }
}
