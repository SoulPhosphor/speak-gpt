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

import org.teslasoft.assistant.preferences.ChatStorageHealth.ReadState
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/**
 * [ChatSource] over chats.db ([ChatStore]).
 *
 * A chat recorded as not readable (it was locked or damaged when chats moved
 * into the database, so only its list row was copied) is still read from its
 * original file through [unmovedChats], so it keeps behaving exactly as it did.
 *
 * A database error part-way through is reported as FAILED; per the
 * [ChatSource.readHistory] contract the caller then discards what it received.
 */
class DatabaseChatSource(
    private val store: ChatStore,
    private val unmovedChats: ChatSource
) : ChatSource {

    override fun readList(): ChatSource.ListRead = try {
        val chats = store.listChats().map { ChatSource.ChatEntry(it.chatId, it.listRowJson) }
        ChatSource.ListRead(if (chats.isEmpty()) ReadState.EMPTY else ReadState.OK, chats)
    } catch (_: Exception) {
        ChatSource.ListRead(ReadState.FAILED, emptyList())
    }

    override fun readHistory(
        chatId: String,
        detectedDuring: DamagedChatOperation,
        action: (ChatSource.Message) -> Boolean
    ): ReadState {
        val chat = try {
            store.getChat(chatId)
        } catch (_: Exception) {
            return ReadState.FAILED
        } ?: return ReadState.MISSING
        if (!chat.readable) return unmovedChats.readHistory(chatId, detectedDuring, action)
        return try {
            var any = false
            store.forEachMessage(chatId) { stored ->
                any = true
                action(ChatSource.Message(stored.payloadJson, stored.messageId, stored.isBot))
            }
            if (any) ReadState.OK else ReadState.EMPTY
        } catch (_: Exception) {
            ReadState.FAILED
        }
    }

    override fun readChatValues(chatId: String): ChatSource.ValuesRead {
        val chat = try {
            store.getChat(chatId)
        } catch (_: Exception) {
            return ChatSource.ValuesRead(ReadState.FAILED, emptyMap())
        } ?: return ChatSource.ValuesRead(ReadState.MISSING, emptyMap())
        if (!chat.readable) return unmovedChats.readChatValues(chatId)
        val values = TypedValueCodec.decodeMap(chat.prefsJson)
            ?: return ChatSource.ValuesRead(ReadState.FAILED, emptyMap())
        return ChatSource.ValuesRead(ReadState.OK, values)
    }

    override fun readListValues(): ChatSource.ValuesRead {
        val failed = ChatSource.ValuesRead(ReadState.FAILED, emptyMap())
        val values = LinkedHashMap<String, Any>()
        try {
            for (key in store.listStateKeys()) {
                val json = store.getListState(key) ?: return failed
                values[key] = TypedValueCodec.decodeValue(json) ?: return failed
            }
        } catch (_: Exception) {
            return failed
        }
        return ChatSource.ValuesRead(ReadState.OK, values)
    }

    override fun changeToken(chatId: String): String? = try {
        val chat = store.getChat(chatId)
        when {
            chat == null -> null
            !chat.readable -> unmovedChats.changeToken(chatId)
            else -> store.storeId()?.let { "$TOKEN_PREFIX$it:${chat.revision}" }
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TOKEN_PREFIX = "db:"
    }
}
