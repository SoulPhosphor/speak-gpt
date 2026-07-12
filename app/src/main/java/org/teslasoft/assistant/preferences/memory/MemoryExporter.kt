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

import android.content.Context
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.util.AtomicFileWriter
import org.teslasoft.assistant.util.Hash
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    /**
     * Write a backup right now into the rotating backup dir, ignoring the daily
     * throttle. Used by "Reset memories" when the user leaves the backup-first
     * option checked. Returns the file name, or null on failure.
     *
     * The caller is about to DELETE the store on the strength of this file,
     * so "probably written" isn't enough: the write is atomic (temp file →
     * rename) and read back before success is reported. A null return means
     * the reset must not proceed.
     */
    fun writeBackupNow(context: Context): String? = try {
        if (isChatListUnavailable(context)) {
            // No backup while the chat list is unreadable: it would carry no
            // chats and could later be mistaken for the newest good copy.
            // Returning null aborts the caller (the reset gate already
            // treats null as "do not proceed"); the manual UI shows the
            // owner-approved dialog before ever calling here.
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
            if (AtomicFileWriter.writeAndVerify(file, buildExportJson(context))) {
                MemoryLog.log(context, "MemoryExport", "info", "Backup written before reset: ${file.name}")
                file.name
            } else {
                MemoryLog.log(context, "MemoryExport", "error", "Backup-before-reset failed: the file did not verify after writing.")
                null
            }
        }
    } catch (e: Exception) {
        MemoryLog.log(context, "MemoryExport", "error", "Backup-before-reset failed: ${e.message}")
        null
    }

    /**
     * True when the AUTHORITATIVE chat list cannot be read (locked or
     * corrupt). In that state no chat backup may be produced at all — an
     * export of the masked (empty) list would look like "no chats", the
     * exact masquerade Round 4 forbids. Callers block the operation and the
     * manual flows show the owner-approved "Chat backup unavailable" dialog.
     */
    fun isChatListUnavailable(context: Context): Boolean = try {
        !ChatStorageHealth.isAuthoritative(
            ChatPreferences.getChatPreferences().getChatListResult(context).state
        )
    } catch (_: Exception) {
        true // unknown state fails conservatively: no backup claimed complete
    }

    fun buildExportJson(context: Context): String {
        val store = MemoryStore.getInstance(context)
        // Belt: callers gate on isChatListUnavailable before invoking. If a
        // path reaches here anyway while the list is unavailable, omit the
        // chat envelope entirely and mark it incomplete — never serialize a
        // masked empty list as though it were all chats.
        val listUnavailable = isChatListUnavailable(context)
        val chats = if (listUnavailable) AppChats(JSONArray(), complete = false) else buildAppChats(context)
        return MemorySeedCodec.serialize(
            store.exportData(),
            appChats = if (listUnavailable) null else chats.array,
            exportedAtIso = MemoryStore.nowIso(),
            appChatsComplete = chats.complete && !chatStorageDegraded(context)
        )
    }

    private data class AppChats(val array: JSONArray, val complete: Boolean)

    private fun chatStorageDegraded(context: Context): Boolean = try {
        ChatStorageHealth.anyChatDataDegraded(context)
    } catch (_: Exception) {
        false
    }

    /**
     * The app's chats serialized verbatim (list metadata + raw message maps).
     * Chat import/merge is deliberately NOT part of this phase — the envelope
     * exists so today's backups already carry everything a future device needs.
     *
     * A chat whose history is LOCKED/CORRUPT/FAILED (Round 4) is carried as
     * an identifier-only entry with `"unavailable": true` and NO "messages"
     * key — never a fabricated empty history — and the whole export is
     * marked incomplete. Recovery snapshots are never included as chats.
     */
    private fun buildAppChats(context: Context): AppChats {
        val gson = Gson()
        val chats = JSONArray()
        var complete = true
        val chatPreferences = ChatPreferences.getChatPreferences()
        for (chat in chatPreferences.getChatList(context)) {
            val name = chat["name"] ?: continue
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("chat_id", Hash.hash(name))
            for ((key, value) in chat) {
                if (key != "name" && key != "first_message") obj.put(key, value)
            }
            val history = chatPreferences.getChatByIdResult(context, Hash.hash(name))
            if (ChatStorageHealth.isAuthoritative(history.state)) {
                obj.put("messages", JSONArray(gson.toJson(history.messages)))
            } else {
                obj.put("unavailable", true)
                complete = false
            }
            chats.put(obj)
        }
        return AppChats(chats, complete)
    }

    /**
     * Startup housekeeping: write a rotating export if the last one is older
     * than a day. Caller guarantees the store is provisioned; everything else
     * is guarded here (the toggle in memory settings, the throttle, rotation).
     */
    fun autoExportIfDue(context: Context) {
        try {
            // Automatic backup PAUSES while the chat list is unreadable
            // (Round 4, owner policy): nothing is written and nothing is
            // rotated, so the newest existing backups — the last complete
            // ones — survive the outage untouched. The throttle marker is
            // not advanced, so the first healthy start exports immediately.
            if (isChatListUnavailable(context)) {
                MemoryLog.log(context, "MemoryExport", "warning",
                    "Automatic backup paused: encrypted chat storage is unavailable. Existing backups are preserved; backups resume when storage opens.")
                return
            }
            val store = MemoryStore.getInstance(context)
            if (store.getMeta(MemoryStore.META_AUTO_EXPORT_ENABLED) == "0") return

            val last = store.getMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT)
            if (last != null) {
                try {
                    if (Instant.parse(last).isAfter(Instant.now().minus(24, ChronoUnit.HOURS))) return
                } catch (_: Exception) { /* unparseable timestamp: export now */ }
            }

            val dir = File(context.getExternalFilesDir(null), BACKUP_DIR)
            if (!dir.exists() && !dir.mkdirs()) return

            val stamp = MemoryStore.nowIso().replace(":", "-")
            val file = File(dir, "memory-export-$stamp.json")
            // Atomic + verified like the pre-reset backup: a torn write must
            // not leave a truncated file as the NEWEST backup (the one a user
            // in trouble grabs first). On failure the throttle marker is left
            // unset so the next start retries.
            if (!AtomicFileWriter.writeAndVerify(file, buildExportJson(context))) {
                MemoryLog.log(context, "MemoryExport", "error", "Automatic memory backup did not verify after writing; will retry on the next start.")
                return
            }
            store.setMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT, MemoryStore.nowIso())

            // Rotation is suspended while chat storage is degraded: the
            // exports written during an outage carry an incomplete chat
            // envelope, and rotating on schedule would age out the last
            // backups that still hold the full chat set. Extra files
            // accumulate at most one per day until the outage ends.
            if (chatStorageDegraded(context)) {
                MemoryLog.log(context, "MemoryExport", "warning",
                    "Automatic memory backup written with an incomplete chat set (chat storage is currently locked or partially unreadable): ${file.name}. Older backups are being kept until a complete backup succeeds.")
            } else {
                val backups = dir.listFiles { f -> f.name.startsWith("memory-export-") && f.name.endsWith(".json") }
                if (backups != null && backups.size > ROTATION_KEEP) {
                    backups.sortedBy { it.name }.dropLast(ROTATION_KEEP).forEach { it.delete() }
                }
                MemoryLog.log(context, "MemoryExport", "info", "Automatic memory backup written: ${file.name}")
            }
        } catch (e: Exception) {
            MemoryLog.log(context, "MemoryExport", "error", "Automatic memory backup failed: ${e.message}")
        }
    }
}
