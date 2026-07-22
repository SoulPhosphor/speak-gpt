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
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.memory.MemoryLog
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
 *  - A LOCKED per-chat SETTINGS file likewise fails the whole artifact
 *    (owner correction, July 22 2026): SecurePrefs presents a locked file as
 *    an inert EMPTY map without throwing, so the lock must be checked
 *    explicitly via isLockedName after the open — never substitute empty
 *    settings after any read failure. A genuinely readable-but-empty
 *    settings file is honest data and is allowed. Decision logic is the
 *    pure, unit-tested [ChatSettingsReadPolicy].
 *  - CHAT IDENTITY FOLLOWS THE APP (compatibility correction, July 22 2026,
 *    after the on-device failure "Your chats could not be read"): a chat-list
 *    entry with a MISSING or BLANK name is serialized faithfully, exactly as
 *    the app itself handles it — ChatPreferences hashes the stored name's
 *    string form everywhere (`map["name"].toString()`, so an absent name
 *    hashes the literal string "null") — instead of failing the artifact. The
 *    previous build treated such an entry as malformed data and refused the
 *    whole backup, which turned a chat the app displays and uses every day
 *    into "your chats could not be read". Nothing is omitted and nothing is
 *    invented: the entry's stored fields travel as-is; only GENUINELY
 *    unreadable content (locked/corrupt/failed reads) fails the artifact.
 *  - Every failure is CATEGORIZED ([FailureCategory]) and logged with chat
 *    IDS and states only — never a chat title, message, settings value, or
 *    key (owner logging rules) — so the next on-device failure is diagnosable
 *    from the Error Log instead of a guess.
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

    /** WHICH part of chat storage was unreadable — carried up to the UI so
     *  the failure names the failing part in plain words, and logged for
     *  diagnosis. */
    enum class FailureCategory { LIST, HISTORY, SETTINGS }

    sealed class Result {
        data class Ok(val json: String, val chatCount: Int) : Result()

        /** Chat storage is LOCKED or a chat is unreadable: the artifact fails
         *  visibly; no partial or fabricated output exists. */
        data class Unavailable(val category: FailureCategory) : Result()
    }

    /**
     * The exact name-to-id mapping the app itself uses for a stored chat-list
     * name value: the string form of whatever is stored, with a missing value
     * becoming the literal "null" (ChatPreferences hashes
     * `map["name"].toString()` everywhere). Pure — unit-tested so the backup
     * can never drift from the app again.
     */
    fun storedNameForId(storedName: String?): String = storedName ?: "null"

    fun serialize(context: Context): Result {
        val gson = Gson()
        val chatPreferences = ChatPreferences.getChatPreferences()

        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val listResult = chatPreferences.getChatListResult(context, includeFirstMessage = false)
            if (!ChatStorageHealth.isAuthoritative(listResult.state)) {
                logFailure(context, FailureCategory.LIST,
                    "the chat list could not be read (state ${listResult.state})")
                return Result.Unavailable(FailureCategory.LIST)
            }

            val chats = JSONArray()
            for (chat in listResult.chats) {
                val storedName = chat["name"]
                val name = storedNameForId(storedName)
                val chatId = Hash.hash(name)

                val history = chatPreferences.getChatByIdResult(context, chatId)
                if (!ChatStorageHealth.isAuthoritative(history.state)) {
                    logFailure(context, FailureCategory.HISTORY,
                        "the history of chat $chatId could not be read (state ${history.state})")
                    return Result.Unavailable(FailureCategory.HISTORY)
                }

                val settings = serializeSettings(context, chatId)
                    ?: return Result.Unavailable(FailureCategory.SETTINGS)

                val obj = JSONObject()
                // The stored value travels as-is; a truly absent name is
                // recorded as JSON null so a restore rebuilds the entry the
                // app had, not an invented one.
                obj.put("name", storedName ?: JSONObject.NULL)
                obj.put("chat_id", chatId)
                for ((key, value) in chat) {
                    if (key != "name" && key != "first_message") obj.put("list_$key", value)
                }
                obj.put("messages", JSONArray(gson.toJson(history.messages)))
                obj.put("settings", settings)
                chats.put(obj)
            }

            val root = JSONObject()
            root.put("format", FORMAT)
            root.put("complete", true)
            root.put("chats", chats)
            return Result.Ok(root.toString(), chats.length())
        }
    }

    /**
     * The chat's settings map, type-tagged for faithful restoration, or NULL
     * when the settings file is LOCKED or unreadable — the caller fails the
     * whole artifact. Never substitutes an empty map after a failure (the
     * Round-4 masquerade). Credentials excluded.
     */
    private fun serializeSettings(context: Context, chatId: String): JSONArray? {
        val name = "settings.$chatId"
        val entriesOrNull = try {
            SecurePrefs.get(context, name).all
        } catch (_: Exception) {
            null
        }
        // The lock check comes AFTER the open attempt: SecurePrefs classifies
        // on open, and a locked file presents an inert EMPTY map — it never
        // throws, so the exception path alone cannot catch it.
        val locked = SecurePrefs.isLockedName(name)
        val all = when (
            val d = ChatSettingsReadPolicy.decide(locked, entriesOrNull)
        ) {
            is ChatSettingsReadPolicy.Decision.Unavailable -> {
                logFailure(context, FailureCategory.SETTINGS,
                    "the settings of chat $chatId could not be read (" +
                        (if (locked) "locked" else "read failed") + ")")
                return null
            }
            is ChatSettingsReadPolicy.Decision.Readable -> d.entries
        }
        val out = JSONArray()
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

    /** One line to the Memory log AND the user-facing Error Log per failed
     *  attempt: category + chat id (already a hash) + read state. Never a
     *  title, message, settings value, or key. */
    private fun logFailure(context: Context, category: FailureCategory, detail: String) {
        val msg = "Recovery backup failed (${category.name}): $detail. " +
            "No backup was created; nothing was modified."
        try {
            MemoryLog.log(context, "PortableRecovery", "error", msg)
            Logger.log(context, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "PortableRecovery", "error", msg)
        } catch (_: Exception) { /* logging is best-effort */ }
    }
}
