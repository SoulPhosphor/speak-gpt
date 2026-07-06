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
     */
    fun writeBackupNow(context: Context): String? = try {
        val dir = File(context.getExternalFilesDir(null), BACKUP_DIR)
        if (!dir.exists() && !dir.mkdirs()) null
        else {
            val stamp = MemoryStore.nowIso().replace(":", "-")
            val file = File(dir, "memory-backup-$stamp.json")
            file.writeText(buildExportJson(context))
            MemoryLog.log(context, "MemoryExport", "info", "Backup written before reset: ${file.name}")
            file.name
        }
    } catch (e: Exception) {
        MemoryLog.log(context, "MemoryExport", "error", "Backup-before-reset failed: ${e.message}")
        null
    }

    fun buildExportJson(context: Context): String {
        val store = MemoryStore.getInstance(context)
        return MemorySeedCodec.serialize(
            store.exportData(),
            appChats = buildAppChats(context),
            exportedAtIso = MemoryStore.nowIso()
        )
    }

    /**
     * The app's chats serialized verbatim (list metadata + raw message maps).
     * Chat import/merge is deliberately NOT part of this phase — the envelope
     * exists so today's backups already carry everything a future device needs.
     */
    private fun buildAppChats(context: Context): JSONArray {
        val gson = Gson()
        val chats = JSONArray()
        val chatPreferences = ChatPreferences.getChatPreferences()
        for (chat in chatPreferences.getChatList(context)) {
            val name = chat["name"] ?: continue
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("chat_id", Hash.hash(name))
            for ((key, value) in chat) {
                if (key != "name" && key != "first_message") obj.put(key, value)
            }
            val messages = chatPreferences.getChatById(context, Hash.hash(name))
            obj.put("messages", JSONArray(gson.toJson(messages)))
            chats.put(obj)
        }
        return chats
    }

    /**
     * Startup housekeeping: write a rotating export if the last one is older
     * than a day. Caller guarantees the store is provisioned; everything else
     * is guarded here (the toggle in memory settings, the throttle, rotation).
     */
    fun autoExportIfDue(context: Context) {
        try {
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
            file.writeText(buildExportJson(context))
            store.setMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT, MemoryStore.nowIso())

            val backups = dir.listFiles { f -> f.name.startsWith("memory-export-") && f.name.endsWith(".json") }
            if (backups != null && backups.size > ROTATION_KEEP) {
                backups.sortedBy { it.name }.dropLast(ROTATION_KEEP).forEach { it.delete() }
            }
            MemoryLog.log(context, "MemoryExport", "info", "Automatic memory backup written: ${file.name}")
        } catch (e: Exception) {
            MemoryLog.log(context, "MemoryExport", "error", "Automatic memory backup failed: ${e.message}")
        }
    }
}
