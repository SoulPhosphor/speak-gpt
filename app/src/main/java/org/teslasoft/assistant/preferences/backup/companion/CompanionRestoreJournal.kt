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

package org.teslasoft.assistant.preferences.backup.companion

import android.content.Context
import org.json.JSONArray

/**
 * Durable journal for an in-flight Companion & Roleplay restore — the same
 * recover-at-startup architecture as ChatRestoreManager and RenameJournal.
 *
 * The plan's §6.3 rollback covers failures caught while the app is running.
 * This journal covers the one case that design alone cannot: Android killing
 * the process (or power loss) BETWEEN the image copy, the database
 * transaction, and the staged settings writes. It is written — synchronously,
 * before the database transaction begins — with everything recovery needs:
 * the restore token, whether the database is involved, whether this restore
 * created the store file, which image files/catalog rows this restore added,
 * the captured pre-restore settings (roll back) and the backup's settings
 * (roll forward). [CompanionRoleplayRestoreManager.resumeIfPending] settles
 * it at the next app start; a normal completion clears it in place.
 */
object CompanionRestoreJournal {

    private const val PREFS = "companion_restore_journal"

    private const val KEY_TOKEN = "token"
    private const val KEY_DB_INVOLVED = "db_involved"
    private const val KEY_PROVISIONED = "provisioned_by_restore"
    private const val KEY_ADDED_FILES = "added_image_files"
    private const val KEY_ADDED_CATALOG = "added_catalog_rows"
    private const val KEY_SETTINGS_OLD = "settings_old"
    private const val KEY_SETTINGS_NEW = "settings_new"

    data class Entry(
        val token: String,
        val dbInvolved: Boolean,
        val provisionedByRestore: Boolean,
        /** Hashes whose permanent image FILE this restore created. */
        val addedImageFiles: List<String>,
        /** Hashes whose catalog row this restore created. */
        val addedCatalogRows: List<String>,
        val settingsOld: CompanionSettingsPayload,
        val settingsNew: CompanionSettingsPayload
    )

    /** Synchronous, durable write — the journal must be on disk before the
     *  database transaction it protects begins. */
    fun write(context: Context, entry: Entry): Boolean {
        return prefs(context).edit()
            .clear()
            .putString(KEY_TOKEN, entry.token)
            .putBoolean(KEY_DB_INVOLVED, entry.dbInvolved)
            .putBoolean(KEY_PROVISIONED, entry.provisionedByRestore)
            .putString(KEY_ADDED_FILES, JSONArray(entry.addedImageFiles).toString())
            .putString(KEY_ADDED_CATALOG, JSONArray(entry.addedCatalogRows).toString())
            .putString(KEY_SETTINGS_OLD, CompanionSettingsPayloadCodec.toJson(entry.settingsOld))
            .putString(KEY_SETTINGS_NEW, CompanionSettingsPayloadCodec.toJson(entry.settingsNew))
            .commit()
    }

    /** True when a token is present at all — lets recovery distinguish "no
     *  restore pending" from "a journal exists but cannot be decoded". */
    fun hasToken(context: Context): Boolean =
        prefs(context).getString(KEY_TOKEN, null) != null

    /** The pending entry, or null when absent OR undecodable — pair with
     *  [hasToken] to tell those apart. */
    fun read(context: Context): Entry? {
        val p = prefs(context)
        val token = p.getString(KEY_TOKEN, null) ?: return null
        return try {
            Entry(
                token = token,
                dbInvolved = p.getBoolean(KEY_DB_INVOLVED, false),
                provisionedByRestore = p.getBoolean(KEY_PROVISIONED, false),
                addedImageFiles = stringList(p.getString(KEY_ADDED_FILES, null)),
                addedCatalogRows = stringList(p.getString(KEY_ADDED_CATALOG, null)),
                settingsOld = CompanionSettingsPayloadCodec.fromJson(
                    p.getString(KEY_SETTINGS_OLD, null) ?: return null
                ),
                settingsNew = CompanionSettingsPayloadCodec.fromJson(
                    p.getString(KEY_SETTINGS_NEW, null) ?: return null
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) out.add(array.getString(i))
        return out
    }
}
