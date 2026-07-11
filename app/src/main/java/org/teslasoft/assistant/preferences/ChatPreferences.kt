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
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.util.Hash
import java.lang.Exception
import java.lang.reflect.Type
import androidx.core.content.edit

class ChatPreferences private constructor() {
    companion object {
        private var preferences: ChatPreferences? = null

        fun getChatPreferences() : ChatPreferences {
            if (preferences == null) preferences = ChatPreferences()
            return preferences!!
        }

        /**
         * The Logger channel the corrupt-data preservation notice is written
         * to. It used to pass "error", which is NOT one of Logger's channels
         * (crash/event/memory/performance) so the notice was silently dropped
         * by `Logger.log`'s unknown-type guard. The user-facing Error Log
         * (the "crash" channel) is the semantically correct home for a
         * data-integrity notice. Exposed so a test can assert it is a real
         * persistent channel — see LoggerTypeTest.
         */
        const val CORRUPT_DATA_LOG_TYPE = "crash"
    }

    /**
     * Called when a stored JSON blob fails to parse. Copies the raw payload
     * into a timestamped backup preferences file (committed synchronously)
     * and resets the original slot, so the next save can no longer overwrite
     * the only remaining copy of the data. Previously a parse failure was
     * silently turned into an empty list and the corrupt-but-recoverable
     * data was destroyed by the next save.
     *
     * @return true if a backup was made (i.e. there was real data to save).
     */
    private fun preserveCorruptData(context: Context, prefsName: String, key: String, raw: String?, what: String): Boolean {
        if (raw.isNullOrBlank() || raw == "[]" || raw == "null") return false

        val backupName = "${prefsName}_corrupt_${System.currentTimeMillis()}"
        SecurePrefs.get(context, backupName)
            .edit(commit = true) { putString(key, raw) }
        // Reset only after the backup is committed.
        SecurePrefs.get(context, prefsName)
            .edit(commit = true) { putString(key, "[]") }

        Logger.log(
            context, CORRUPT_DATA_LOG_TYPE, "ChatPreferences", "error",
            "Stored $what failed to parse. The raw data was preserved in the encrypted preferences file $backupName and the broken entry was reset."
        )

        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(
                    context.applicationContext,
                    "Stored $what was corrupted. A backup copy was kept on the device (see event log).",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) { /* notification is best-effort */ }
        }

        return true
    }

    /**
     * Clears all chat messages for a given chat ID.
     *
     * @param context The context of the application.
     * @param chatId The ID of the chat to clear.
     */
    fun clearChat(context: Context, chatId: String) {
        SecurePrefs.get(context, "chat_$chatId").edit { putString("chat", "[]") }
    }

    /**
     * Deletes a chat, including all messages, from the chat list.
     *
     * @param context The context of the application.
     * @param chatName The name of the chat to delete.
     */
    fun deleteChat(context: Context, chatName: String) {
        val list = getChatList(context)

        for (map: HashMap<String, String> in list) {
            if (map["name"] == chatName) {
                list.remove(map)
                break
            }
        }

        val json: String = Gson().toJson(list)

        val settings: SharedPreferences = SecurePrefs.get(context, "chat_list")
        settings.edit { putString("data", json) }

        val settings2: SharedPreferences = SecurePrefs.get(context, "chat_${Hash.hash(chatName)}")
        settings2.edit { clear() }
    }

    /**
     * Retrieves a list of all available chats.
     *
     * @param context The context of the application.
     * @return An ArrayList of HashMap objects, where each HashMap represents a chat with key-value pairs for the chat name and ID.
     */
    fun getChatList(context: Context) : ArrayList<HashMap<String, String>> {
        val settings: SharedPreferences = SecurePrefs.get(context, "chat_list")

        val gson = Gson()
        val json = settings.getString("data", "[]")
        val type: Type = TypeToken.getParameterized(ArrayList::class.java, HashMap::class.java).type

        var list: ArrayList<HashMap<String, String>> = try {
            gson.fromJson<Any>(json, type) as ArrayList<HashMap<String, String>>
        } catch (e: Exception) {
            preserveCorruptData(context, "chat_list", "data", json, "chat list")
            arrayListOf()
        }

        // Bugfix for R8 minifier, yes It make no sense for regular programmer, but it's a bug in R8 minifier
        if (list == null) list = arrayListOf()

        // Dumb things goes gere
        if (list.isNullOrEmpty()) return arrayListOf()

        for (chat in list) {
            val messagesList = getChatById(context, Hash.hash(chat["name"].toString()))

            if (messagesList.isNotEmpty()) {
                val firstMessage = messagesList[0]["message"].toString()
                chat["first_message"] = firstMessage
            } else {
                chat["first_message"] = "No messages yet."
            }
        }

        // Bugfix for R8 minifier, yes It make no sense for regular programmer, but it's a bug in R8 minifier
        if (list == null) list = arrayListOf()

        return list
    }

    fun switchPinState(context: Context, chatId: String) {
        val list = getChatList(context)

        for (map in list) {
            if (Hash.hash(map["name"].toString()) == chatId) {
                if (map["pinned"] == "true") {
                    map["pinned"] = "false"
                } else {
                    map["pinned"] = "true"
                }
                break
            }
        }

        val json: String = Gson().toJson(list)

        val settings: SharedPreferences = SecurePrefs.get(context, "chat_list")
        settings.edit { putString("data", json) }
    }

    fun putTimestampToChatById(context: Context, chatId: String) {
        val timestamp = System.currentTimeMillis().toString()

        putMetadataToChatById(context, chatId, "timestamp", timestamp)
    }

    private fun putMetadataToChatById(context: Context, chatId: String, key: String, value: String) {
        val list = getChatList(context)

        for (map in list) {
            if (Hash.hash(map["name"].toString()) == chatId) {
                map[key] = value
                break
            }
        }

        val json: String = Gson().toJson(list)

        val settings: SharedPreferences = SecurePrefs.get(context, "chat_list")
        settings.edit { putString("data", json) }
    }

    /**
     * Retrieves all chat messages for a given chat ID.
     *
     * @param context The context of the application.
     * @param chatId The ID of the chat to retrieve messages for.
     * @return An ArrayList of HashMap objects, where each HashMap represents a message with key-value pairs for the message content and sender ID.
     */
    fun getChatById(context: Context, chatId: String) : ArrayList<HashMap<String, Any>> {
        val chat: SharedPreferences = SecurePrefs.get(context, "chat_$chatId")

        var list: ArrayList<HashMap<String, Any>> = try {
            val gson = Gson()
            val json = chat.getString("chat", "[]")
            val type: Type = TypeToken.getParameterized(ArrayList::class.java, HashMap::class.java).type

            try {
                gson.fromJson<Any>(json, type) as ArrayList<HashMap<String, Any>>
            } catch (e: Exception) {
                preserveCorruptData(context, "chat_$chatId", "chat", json, "chat history")
                arrayListOf()
            }
        } catch (e: Exception) {
            arrayListOf()
        }

        // Bugfix for R8 minifier
        if (list == null) list = arrayListOf()

        return list
    }

    fun clearChatById(context: Context, chatId: String) {
        val chat: SharedPreferences = SecurePrefs.get(context, "chat_$chatId")

        chat.edit { putString("chat", "[]") }
    }

    /**
     * Generates a unique chat ID for a new chat.
     *
     * @param context The context of the application.
     * @return A unique chat ID as a String.
     */
    fun getAvailableChatId(context: Context) : String {
        var x = 1

        val list = getChatList(context)

        while (true) {
            var isFound = false
            for (map: HashMap<String, String> in list) {
                if (map["name"] == "New chat $x") {
                    isFound = true
                    break
                }
            }

            if (!isFound) break

            x++
        }

        return x.toString()
    }

    /**
     * Generates a unique chat ID for a new chat.
     *
     * @param context The context of the application.
     * @param prefix The prefix to use for the chat name.
     * @return A unique chat ID as a String.
     */
    fun getAvailableChatIdByPrefix(context: Context, prefix: String) : String {
        var x = 1

        val list = getChatList(context)

        while (true) {
            var isFound = false
            for (map: HashMap<String, String> in list) {
                if (map["name"] == "$prefix $x") {
                    isFound = true
                    break
                }
            }

            if (!isFound) break

            x++
        }

        return x.toString()
    }

    fun editMessage(context: Context, chatId: String, position: Int, newMessage: String) {
        val list = getChatById(context, chatId)

        // The position comes from the in-memory adapter list, which can briefly
        // be longer than the persisted list (e.g. a pending turn added in memory
        // before the next save, or after a failed generation). Indexing blindly
        // crashed the whole app with IndexOutOfBoundsException; bail out instead.
        if (position < 0 || position >= list.size) return

        list[position]["message"] = newMessage

        // A user edit finalizes an assistant reply: the user has taken ownership
        // of the text, so it is no longer a truncated fragment. Clear any
        // incomplete completion state (and its diagnostic/error fields) so the
        // message is treated as done everywhere — model context, transcript,
        // export. Harmless on user messages (they never carry state).
        if (list[position]["isBot"] == true &&
            !MessageCompletionState.isComplete(list[position][MessageCompletionState.KEY_STATE]?.toString())
        ) {
            list[position][MessageCompletionState.KEY_STATE] = MessageCompletionState.DONE
            list[position].remove(MessageCompletionState.KEY_STATE_DETAIL)
            list[position].remove(MessageCompletionState.KEY_ERROR_TEXT)
        }

        val json: String = Gson().toJson(list)

        val settings: SharedPreferences = SecurePrefs.get(context, "chat_$chatId")
        settings.edit { putString("chat", json) }
    }

    fun deleteMessage(context: Context, chatId: String, position: Int) {
        val list = getChatById(context, chatId)

        // Same desync guard as editMessage: removeAt(position) on a stale/shorter
        // persisted list threw IndexOutOfBoundsException (Index N out of bounds
        // for length N) and crashed the app while editing/deleting a message.
        if (position < 0 || position >= list.size) return

        list.removeAt(position)

        val json: String = Gson().toJson(list)

        val settings: SharedPreferences = SecurePrefs.get(context, "chat_$chatId")
        settings.edit { putString("chat", json) }
    }

    /**
     * Generates a unique chat ID for a new chat (auto-name).
     *
     * @param context The context of the application.
     * @return A unique chat ID as a String.
     */
    fun getAvailableChatIdForAutoname(context: Context) : String {
        var x = 1

        var list = getChatList(context)

        // R8 Bugfix
        if (list == null) list = arrayListOf()

        // Dumb things goes gere
        if (list.isEmpty()) list = arrayListOf()

        while (true) {
            var isFound = false
            for (map: HashMap<String, String> in list) {
                if (map["name"] == "_autoname_$x") {
                    isFound = true
                    break
                }
            }

            if (!isFound) break

            x++
        }

        return x.toString()
    }

    /**
     * Adds a new chat to the chat list.
     *
     * @param context The context of the application.
     * @param chatName The name of the chat to add.
     */
    fun addChat(context: Context, chatName: String) {
        val list = getChatList(context)

        val map: HashMap<String, String> = HashMap()

        map["name"] = chatName
        map["id"] = Hash.hash(chatName)
        map["timestamp"] = System.currentTimeMillis().toString()
        map["pinned"] = "false"

        list.add(map)
        val json: String = Gson().toJson(list)

        val settings: SharedPreferences = SecurePrefs.get(context, "chat_list")
        settings.edit { putString("data", json) }

        val settings2: SharedPreferences = SecurePrefs.get(context, "chat_${Hash.hash(chatName)}")
        settings2.edit { putString("chat", "[]") }
    }

    /**
     * Checks if a chat with the given name already exists in the chat list.
     *
     * @param context The context of the application.
     * @param chatName The name of the chat to check for duplicates.
     * @return True if a chat with the given name already exists in the chat list, false otherwise.
     */
    fun checkDuplicate(context: Context, chatName: String) : Boolean {
        val list = getChatList(context)

        var isFound = false
        for (map: HashMap<String, String> in list) {
            if (map["id"] == Hash.hash(chatName)) {
                isFound = true
                break
            }
        }

        return isFound
    }

    fun getChatName(context: Context, chatId: String) : String {
        val list = getChatList(context)

        var name = ""
        for (map: HashMap<String, String> in list) {
            if (map["id"] == chatId) {
                name = map["name"].toString()
                break
            }
        }

        return name
    }

    /**
     * Renames a chat: moves the message history AND the whole per-chat
     * settings file to the new chat id, then flips the chat-list pointer.
     *
     * Runs through [ChatRenameTransaction] — write-new → verify → copy
     * settings wholesale → verify → flip pointer → clear old, every write a
     * synchronous commit. The old implementation cleared the old history
     * file BEFORE the new one was written (both async), so a process kill
     * mid-rename silently destroyed the conversation; and it did not move
     * the settings at all, leaving that to two hand-maintained copy blocks
     * in callers that had drifted out of sync with the real per-chat key
     * set (see PerChatSettingKeys).
     *
     * @return true when the rename fully applied. false means NOTHING
     *         changed — the chat is intact under [previousName] and callers
     *         must keep using the old id. Never partially applied.
     */
    fun editChat(context: Context, chatName: String, previousName: String): Boolean {
        if (chatName == previousName) return true

        val oldId = Hash.hash(previousName)
        val newId = Hash.hash(chatName)

        val list = getChatList(context)
        val entry = list.firstOrNull { it["id"] == oldId } ?: return false

        // Renaming onto an id another chat already owns would silently
        // overwrite that chat's history. Refuse instead (the rename dialog
        // pre-checks duplicates; auto-naming may retry with another title).
        if (list.any { it !== entry && it["id"] == newId }) {
            Logger.log(
                context, "crash", "ChatPreferences", "error",
                "Rename refused: a chat named \"$chatName\" already exists; \"$previousName\" was left unchanged."
            )
            return false
        }

        entry["name"] = chatName
        entry["id"] = newId
        val newListJson: String = Gson().toJson(list)

        // Journal the rename BEFORE touching prefs. The prefs pointer flip is
        // the authoritative moment, but a process death right after it would
        // leave the memory re-point (a separate store) undone and the chat's
        // transcripts stranded under the old id. The journal makes that window
        // recoverable at next start; recovery consults the live chat list, so
        // an entry written for a rename that then fails pre-flip is safely
        // discarded (the old id is still live). Committed synchronously to
        // encrypted prefs, outside the memory DB, so it survives even when the
        // DB is the thing failing.
        RenameJournal.record(context, oldId, newId)

        val outcome = try {
            ChatRenameTransaction.rename(securePrefsFileAccess(context), oldId, newId, newListJson)
        } catch (e: Exception) {
            ChatRenameTransaction.Outcome(false, "unexpected error: ${e.message}")
        }

        if (!outcome.success) {
            // The pointer never flipped: the old chat is still authoritative,
            // so the journal entry would drive no re-point anyway (recovery
            // sees the old id live) — drop it now to keep the journal clean.
            RenameJournal.clear(context, oldId, newId)
            // Never silent: a failed rename is recorded in the user-facing
            // Error Log even though the data itself is safe.
            Logger.log(
                context, "crash", "ChatPreferences", "error",
                "Renaming \"$previousName\" to \"$chatName\" failed at ${outcome.failedStage}. " +
                    "The chat and its settings are untouched under the old name."
            )
            return false
        }
        for (warning in outcome.warnings) {
            Logger.log(context, "crash", "ChatPreferences", "warning", "Chat rename: $warning")
        }

        // The rename is now authoritative (chat list names the new id). Complete
        // the cross-store re-point of the memory rows. repointChat is one atomic
        // DB transaction and idempotent; on success the journal entry is dropped.
        // On failure (or a death here) the entry is LEFT for startup recovery to
        // retry — the rename still succeeds and the chat is fully usable under
        // the new name; only the memory re-point is deferred, and past
        // transcripts stay preserved under the old id until it completes. We do
        // NOT pretend the two stores committed together.
        var repointed = false
        try {
            if (MemoryStore.isProvisioned(context)) {
                MemoryStore.getInstance(context).repointChat(oldId, newId)
            }
            repointed = true // provisioned + moved, or nothing to move (no store)
        } catch (e: Exception) {
            Logger.log(
                context, "crash", "ChatPreferences", "error",
                "Renamed \"$previousName\" to \"$chatName\", but moving its memory records failed (${e.message}). " +
                    "It is queued for retry on the next app start; past messages stay preserved under the old name."
            )
        }
        if (repointed) RenameJournal.clear(context, oldId, newId)

        return true
    }

    /**
     * SharedPreferences-backed storage for [ChatRenameTransaction]. All
     * writes are synchronous commits — the transaction's ordering guarantee
     * (new data durable before the pointer flips, pointer durable before the
     * old data clears) only holds if nothing here is apply()-deferred.
     */
    private fun securePrefsFileAccess(context: Context) = object : ChatRenameTransaction.FileAccess {
        override fun readAll(fileName: String): Map<String, Any?> =
            SecurePrefs.get(context, fileName).all

        override fun readString(fileName: String, key: String): String? =
            SecurePrefs.get(context, fileName).getString(key, null)

        override fun replaceAll(fileName: String, entries: Map<String, Any?>): Boolean {
            val editor = SecurePrefs.get(context, fileName).edit()
            editor.clear()
            for ((key, value) in entries) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                }
            }
            return editor.commit()
        }

        override fun writeString(fileName: String, key: String, value: String): Boolean =
            SecurePrefs.get(context, fileName).edit().putString(key, value).commit()

        override fun clear(fileName: String): Boolean =
            SecurePrefs.get(context, fileName).edit().clear().commit()
    }

    fun deleteChatById(context: Context, chatId: String) {
        val list = getChatList(context)

        for (map: HashMap<String, String> in list) {
            if (map["id"] == chatId) {
                list.remove(map)
                break
            }
        }

        val json: String = Gson().toJson(list)

        val settings: SharedPreferences = SecurePrefs.get(context, "chat_list")
        settings.edit { putString("data", json) }

        val settings2: SharedPreferences = SecurePrefs.get(context, "chat_$chatId")
        settings2.edit { clear() }
    }
}
