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
import org.json.JSONObject

/**
 * Durable record of the most recent Recovery Backup that passed every
 * verification and was saved to its destination, naming which chat storage
 * it was captured from (chat-storage-and-backup-plan.md §4.5).
 *
 * Original-file removal (plan stage 13) trusts only a receipt captured from
 * the database with the current store ID and a generation at or after the
 * last recovered-chat import. Nothing reads it yet; it is written now so a
 * backup made after activation already qualifies.
 */
object RecoveryBackupReceipt {

    /** Where the chats in a backup came from. */
    data class Source(
        /** [STORAGE_LEGACY] or [STORAGE_DATABASE]. */
        val storage: String,
        /** chats.db store ID, for a database capture. */
        val storeId: String? = null,
        /** chats.db store-wide revision counter at capture, for a database capture. */
        val generation: Long? = null
    )

    data class Receipt(val source: Source, val savedAtMillis: Long)

    const val STORAGE_LEGACY = "legacy"
    const val STORAGE_DATABASE = "database"

    private const val PREFS = "recovery_backup_receipt"
    private const val KEY = "latest"

    /** Record a verified, saved backup. Returns false if it could not be stored. */
    fun record(context: Context, source: Source, savedAtMillis: Long): Boolean = try {
        val json = JSONObject()
            .put("storage", source.storage)
            .put("saved_at", savedAtMillis)
        source.storeId?.let { json.put("store_id", it) }
        source.generation?.let { json.put("generation", it) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).commit()
    } catch (_: Exception) {
        false
    }

    /** The latest receipt, or null if none is stored or it cannot be read. */
    fun latest(context: Context): Receipt? = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.let { raw ->
                val json = JSONObject(raw)
                Receipt(
                    Source(
                        storage = json.getString("storage"),
                        storeId = json.optString("store_id").takeIf { json.has("store_id") },
                        generation = if (json.has("generation")) json.getLong("generation") else null
                    ),
                    json.getLong("saved_at")
                )
            }
    } catch (_: Exception) {
        null
    }
}
