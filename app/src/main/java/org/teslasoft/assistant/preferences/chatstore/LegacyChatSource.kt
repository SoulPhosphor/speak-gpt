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

package org.teslasoft.assistant.preferences.chatstore

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.io.StringReader
import java.security.MessageDigest
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.ChatStorageHealth.ReadState
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/**
 * [ChatSource] over today's storage: the chat list in `enc.chat_list` and
 * each conversation in `enc.chat_<id>`, both EncryptedSharedPreferences.
 *
 * A stored conversation is one encrypted value, so it is still decrypted
 * whole; what this avoids is every further copy (the parsed list of maps and
 * anything built from it). Messages are handed out one element at a time.
 *
 * This reader never decides on its own that stored data is damaged. Locked
 * storage is reported directly, as the app does. For anything else that
 * fails — a value that will not decrypt or a list the streaming check
 * refuses — the read is handed to the app's own reader in [ChatPreferences],
 * which runs the existing damaged-data handling exactly as it does today. If
 * that reader opens what the streaming check refused, the result is FAILED:
 * nothing is changed and nothing is presented as empty.
 */
class LegacyChatSource(context: Context) : ChatSource {

    private val app = context.applicationContext
    private val gson = Gson()

    private sealed class Raw {
        data object Locked : Raw()
        data object Unreadable : Raw()
        class Value(val json: String, val keyPresent: Boolean) : Raw()
    }

    override fun readList(): ChatSource.ListRead {
        val raw = readRaw(LIST_FILE, LIST_KEY)
        if (raw is Raw.Locked) return ChatSource.ListRead(ReadState.LOCKED, emptyList())
        if (raw !is Raw.Value) return deferList()
        val count = StreamingJson.countObjectList(StringReader(raw.json))
        if (count == StreamingJson.INVALID) return deferList()
        if (count == 0) return ChatSource.ListRead(emptyState(raw.keyPresent), emptyList())

        val entries = ArrayList<ChatSource.ChatEntry>(count)
        var unusable = false
        StreamingJson.forEachElement(StringReader(raw.json)) { element ->
            val chatId = if (element.isJsonObject) chatIdOf(element) else null
            if (chatId == null) {
                // A null row, or one whose ID the app itself could not
                // compute; refuse rather than skip it.
                unusable = true
                false
            } else {
                entries.add(ChatSource.ChatEntry(chatId, element.toString()))
                true
            }
        }
        return if (unusable) ChatSource.ListRead(ReadState.FAILED, emptyList())
        else ChatSource.ListRead(ReadState.OK, entries)
    }

    override fun readHistory(
        chatId: String,
        detectedDuring: DamagedChatOperation,
        action: (ChatSource.Message) -> Boolean
    ): ReadState {
        val raw = readRaw(historyFile(chatId), HISTORY_KEY)
        if (raw is Raw.Locked) return ReadState.LOCKED
        if (raw !is Raw.Value) return deferHistory(chatId, detectedDuring)
        val count = StreamingJson.countObjectList(StringReader(raw.json))
        if (count == StreamingJson.INVALID) return deferHistory(chatId, detectedDuring)
        if (count == 0) return emptyState(raw.keyPresent)
        val fields = StreamingJson.collectFields(StringReader(raw.json), LOOKUP_FIELDS)
        var index = 0
        StreamingJson.forEachElementStreamed(StringReader(raw.json)) { element ->
            action(messageOf(element, fields[index++]))
        }
        return ReadState.OK
    }

    override fun readChatValues(chatId: String): ChatSource.ValuesRead =
        readValues(historyFile(chatId), HISTORY_KEY)

    override fun readListValues(): ChatSource.ValuesRead = readValues(LIST_FILE, LIST_KEY)

    override fun changeToken(chatId: String): String? {
        val raw = readRaw(historyFile(chatId), HISTORY_KEY) as? Raw.Value ?: return null
        // A stored history the reader would not deliver gets no token.
        if (StreamingJson.countObjectList(StringReader(raw.json)) == StreamingJson.INVALID) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.json.toByteArray(Charsets.UTF_8))
        return TOKEN_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * The stored value as the app reads it: classify the file on open, report
     * a lock before touching the value, and clear an earlier read-failure
     * record once the value decrypts (as [ChatPreferences] does).
     */
    private fun readRaw(file: String, key: String): Raw {
        val prefs = SecurePrefs.get(app, file)
        if (SecurePrefs.isLockedName(file)) return Raw.Locked
        val keyPresent = try { prefs.contains(key) } catch (_: Exception) { true }
        val json = try {
            prefs.getString(key, "[]") ?: "[]"
        } catch (_: Exception) {
            return Raw.Unreadable
        }
        ChatStorageHealth.clearReadFailure(app, file)
        return Raw.Value(json, keyPresent)
    }

    /** Every value in [file] except [skipKey], with its stored type. */
    private fun readValues(file: String, skipKey: String): ChatSource.ValuesRead {
        val all = try {
            val prefs = SecurePrefs.get(app, file)
            if (SecurePrefs.isLockedName(file)) {
                return ChatSource.ValuesRead(ReadState.LOCKED, emptyMap())
            }
            // Decrypts every value, including the skipped one; there is no way
            // to list an encrypted file's keys without it.
            prefs.all
        } catch (_: Exception) {
            return ChatSource.ValuesRead(ReadState.FAILED, emptyMap())
        }
        val values = LinkedHashMap<String, Any>()
        for ((key, value) in all) {
            if (key != skipKey && value != null) values[key] = value
        }
        return ChatSource.ValuesRead(ReadState.OK, values)
    }

    private fun deferList(): ChatSource.ListRead {
        val result = ChatPreferences.getChatPreferences()
            .getChatListResult(app, includeFirstMessage = false)
        return ChatSource.ListRead(deferredState(result.state), emptyList())
    }

    private fun deferHistory(chatId: String, detectedDuring: DamagedChatOperation): ReadState {
        val result = ChatPreferences.getChatPreferences()
            .getChatByIdResult(app, chatId, detectedDuring)
        return deferredState(result.state)
    }

    /** The app's reader opened what this reader could not: report, change nothing. */
    private fun deferredState(state: ReadState): ReadState =
        if (ChatStorageHealth.isAuthoritative(state)) ReadState.FAILED else state

    private fun emptyState(keyPresent: Boolean): ReadState =
        ChatStorageHealth.readStateFor(
            locked = false, decryptFailed = false, parseFailed = false,
            keyPresent = keyPresent, hasEntries = false
        )

    /** The ID the app derives for this row ([ChatPreferences.storedChatId]). */
    private fun chatIdOf(row: JsonElement): String? = try {
        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(row, HashMap::class.java) as Map<String, String>
        ChatPreferences.storedChatId(map)
    } catch (_: Exception) {
        null
    }

    private fun messageOf(
        element: StreamingJson.Element,
        fields: Map<String, JsonPrimitive>
    ): ChatSource.Message {
        val id = fields[SearchableMessageProjection.MESSAGE_ID_KEY]?.takeIf { it.isString }?.asString
        val bot = fields[IS_BOT_KEY]?.let {
            if (it.isBoolean) it.asBoolean else it.isString && it.asString == "true"
        } ?: false
        return StreamedMessage(element, id, bot)
    }

    /**
     * A message copied straight from the decrypted history text when asked,
     * never built as a tree, so the only extra copy is the payload itself.
     */
    private class StreamedMessage(
        private val element: StreamingJson.Element,
        messageId: String?,
        isBot: Boolean
    ) : ChatSource.Message(messageId, isBot) {
        override val payloadJson: String get() = element.asString()
        override fun copyPayloadTo(out: Appendable) = element.copyTo(out)
    }

    companion object {
        const val LIST_FILE = "chat_list"
        const val LIST_KEY = "data"
        const val HISTORY_KEY = "chat"
        private const val IS_BOT_KEY = "isBot"
        private val LOOKUP_FIELDS = setOf(SearchableMessageProjection.MESSAGE_ID_KEY, IS_BOT_KEY)
        private const val TOKEN_PREFIX = "legacy:"

        fun historyFile(chatId: String): String = "chat_$chatId"
    }
}
