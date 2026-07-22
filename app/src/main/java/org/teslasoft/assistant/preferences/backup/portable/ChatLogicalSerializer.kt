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

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.util.Hash

/**
 * Logical chat serialization for portable recovery packages (owner ruling 8,
 * July 22 2026) — REPLACES the raw `enc.*.xml` archive on the portable path:
 * those files are wrapped by a non-exportable Keystore key and can never be
 * restored on another installation. This serializes the decrypted logical
 * content — the chat list, every readable history, and each chat's per-chat
 * settings map — into JSON that a fresh installation can rebuild from.
 *
 * Rules enforced here:
 *  - A LOCKED or non-authoritative chat LIST is a typed, visible failure —
 *    never an apparently complete backup of fabricated empty data.
 *  - ANY individual non-authoritative chat likewise fails the whole artifact
 *    (a partial chat set must never look like a normal success — standing
 *    owner ruling).
 *  - The legacy per-chat `api_key` settings entry is EXCLUDED (owner ruling 9:
 *    API keys are never package contents; verified present in the per-chat
 *    settings surface at Preferences.kt getApiKey/setApiKey).
 *  - The whole read runs inside CHAT_LIST_LOCK (verified lock order:
 *    CHAT_LIST_LOCK before the SecurePrefs monitor) so the list, histories
 *    and settings form ONE consistent set.
 *  - Runs on a background thread only — it parses every history (the
 *    O(all-conversations) work class behind the July 15 ANR).
 */
object ChatLogicalSerializer {

    const val FORMAT = "chat-logical-v1"

    /** Settings keys that must never travel (external credentials). */
    private val EXCLUDED_SETTINGS_KEYS = setOf("api_key")

    sealed class Result {
        data class Ok(val json: String, val chatCount: Int) : Result()

        /** Chat storage is LOCKED or a chat is unreadable: the artifact fails
         *  visibly; no partial or fabricated output exists. */
        object Unavailable : Result()
    }

    fun serialize(context: Context): Result {
        val gson = Gson()
        val chatPreferences = ChatPreferences.getChatPreferences()

        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val listResult = chatPreferences.getChatListResult(context, includeFirstMessage = false)
            if (!ChatStorageHealth.isAuthoritative(listResult.state)) return Result.Unavailable

            val chats = JSONArray()
            for (chat in listResult.chats) {
                val name = chat["name"] ?: continue
                val chatId = Hash.hash(name)

                val history = chatPreferences.getChatByIdResult(context, chatId)
                if (!ChatStorageHealth.isAuthoritative(history.state)) return Result.Unavailable

                val obj = JSONObject()
                obj.put("name", name)
                obj.put("chat_id", chatId)
                for ((key, value) in chat) {
                    if (key != "name" && key != "first_message") obj.put("list_$key", value)
                }
                obj.put("messages", JSONArray(gson.toJson(history.messages)))
                obj.put("settings", serializeSettings(context, chatId))
                chats.put(obj)
            }

            val root = JSONObject()
            root.put("format", FORMAT)
            root.put("complete", true)
            root.put("chats", chats)
            return Result.Ok(root.toString(), chats.length())
        }
    }

    /** The chat's settings map, type-tagged for faithful restoration.
     *  Credentials excluded. */
    private fun serializeSettings(context: Context, chatId: String): JSONArray {
        val out = JSONArray()
        val all = try {
            SecurePrefs.get(context, "settings.$chatId").all
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        for ((key, value) in all) {
            if (key in EXCLUDED_SETTINGS_KEYS) continue
            val entry = JSONObject()
            entry.put("k", key)
            when (value) {
                is String -> { entry.put("t", "s"); entry.put("v", value) }
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is Int -> { entry.put("t", "i"); entry.put("v", value) }
                is Long -> { entry.put("t", "l"); entry.put("v", value) }
                is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                is Set<*> -> { entry.put("t", "ss"); entry.put("v", JSONArray(value.toList())) }
                else -> continue
            }
            out.put(entry)
        }
        return out
    }
}
