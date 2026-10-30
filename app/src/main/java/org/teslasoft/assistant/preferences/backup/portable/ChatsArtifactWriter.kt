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

import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex
import android.content.Context
import com.google.gson.Gson
import java.io.Writer
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.ChatStorageHealth.ReadState
import org.teslasoft.assistant.preferences.backup.portable.ChatLogicalSerializer.FailureCategory
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatstore.ChatSource
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/**
 * Writes the Recovery Backup chats artifact ([ChatLogicalSerializer.FORMAT_V3])
 * straight to [Writer] from a [ChatSource], one message at a time, so a chat
 * set of any size is never built in memory (chat-storage-and-backup-plan.md
 * §4.5). Each message is copied part by part through
 * [ChatSource.Message.copyPayloadTo].
 *
 * Content and rules match [ChatLogicalSerializer.serializeV2], which this
 * replaces for Recovery Backups: the same list-row fields, settings (never
 * credentials), referenced folders, and the same visible failure categories
 * when any part of chat storage cannot be read. v3 adds `history_saved`.
 *
 * The output is only valid when [Result.Ok] is returned; on any other result
 * the caller discards the partly written file.
 */
internal object ChatsArtifactWriter {

    sealed class Result {
        data class Ok(val chatCount: Int, val messageCount: Long) : Result()
        data class Unavailable(val category: FailureCategory) : Result()
    }

    fun write(context: Context, source: ChatSource, out: Writer): Result =
        // The list, histories and settings form one consistent set.
        synchronized(ChatPreferences.CHAT_LIST_LOCK) { writeLocked(context, source, out) }

    private fun writeLocked(context: Context, source: ChatSource, out: Writer): Result {
        val list = source.readList()
        if (!ChatStorageHealth.isAuthoritative(list.state)) {
            ChatLogicalSerializer.logFailure(
                context, FailureCategory.LIST,
                "the chat list could not be read (state ${list.state})"
            )
            return Result.Unavailable(FailureCategory.LIST)
        }
        val gson = Gson()
        val rows = list.chats.map { entry ->
            @Suppress("UNCHECKED_CAST")
            entry.chatId to (gson.fromJson(entry.listRowJson, HashMap::class.java) as Map<String, Any?>)
        }
        val referencedFolderIds = LinkedHashSet<String>()
        rows.forEach { (_, row) ->
            (row[ChatNavigationRepository.FOLDER_ID_KEY] as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let(referencedFolderIds::add)
        }
        val folders = referencedFolders(source, referencedFolderIds)
            ?: return Result.Unavailable(FailureCategory.LIST)

        out.write("{\"format\":")
        out.write(JSONObject.quote(ChatLogicalSerializer.FORMAT_V3))
        out.write(",\"complete\":true,\"folders\":")
        out.write(folders.toString())
        out.write(",\"chats\":[")
        var messageTotal = 0L
        rows.forEachIndexed { index, (chatId, row) ->
            if (index > 0) out.write(",")
            // The header is built exactly as serializeV2 builds each chat.
            val header = JSONObject()
            val storedName = row["name"]
            header.put("name", storedName ?: JSONObject.NULL)
            header.put("chat_id", chatId)
            for ((key, value) in row) {
                // Derived chat-list display values are rebuilt after a restore.
                if (key != "name" && key != "first_message" && key !in ChatListIndex.KEYS) header.put("list_$key", value)
            }
            val storedModifiedAt =
                (source.readChatValue(chatId, ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY) as? Long) ?: 0L
            val listedModifiedAt =
                row[ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY]?.toString()?.toLongOrNull() ?: 0L
            maxOf(storedModifiedAt, listedModifiedAt).takeIf { it > 0L }?.let {
                header.put("list_${ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY}", it.toString())
            }
            header.put("list_id", chatId)
            val headerText = header.toString()
            out.write(headerText, 0, headerText.length - 1)

            out.write(",\"messages\":[")
            var count = 0L
            val state = source.readHistory(chatId, DamagedChatOperation.RECOVERY_BACKUP) { message ->
                if (count > 0) out.write(",")
                message.copyPayloadTo(out)
                count++
                true
            }
            if (!ChatStorageHealth.isAuthoritative(state)) {
                ChatLogicalSerializer.logFailure(
                    context, FailureCategory.HISTORY,
                    "the history of chat $chatId could not be read (state $state)"
                )
                return Result.Unavailable(FailureCategory.HISTORY)
            }
            messageTotal += count
            out.write("],\"settings\":")
            val settings = ChatLogicalSerializer.serializeSettings(context, chatId)
                ?: return Result.Unavailable(FailureCategory.SETTINGS)
            out.write(settings.toString())
            out.write(",\"")
            out.write(PortableChatRestorePlan.HISTORY_SAVED_KEY)
            out.write("\":")
            out.write(if (state == ReadState.MISSING) "false" else "true")
            out.write("}")
        }
        out.write("]}")
        out.flush()
        return Result.Ok(rows.size, messageTotal)
    }

    /** The folder definitions every backed-up chat references, or null if unreadable. */
    private fun referencedFolders(source: ChatSource, referencedIds: Set<String>): JSONArray? {
        if (referencedIds.isEmpty()) return JSONArray()
        val values = source.readListValues()
        if (values.state != ReadState.OK) return null
        val raw = values.values[ChatNavigationRepository.FOLDERS_KEY] as? String ?: return null
        val decoded = ChatFolderPortableCodec.decodeStored(raw) ?: return null
        val byId = decoded.associateBy { it.id }
        if (!byId.keys.containsAll(referencedIds)) return null
        return JSONArray().apply {
            referencedIds.forEach { id -> put(ChatFolderPortableCodec.toArtifactJson(byId.getValue(id))) }
        }
    }
}
