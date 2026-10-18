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
 * Read access to stored chats that works the same whether the encrypted
 * preference files ([LegacyChatSource]) or chats.db ([DatabaseChatSource])
 * hold them (chat-storage-and-backup-plan.md §4.2). Migration, backups, the
 * readable export, the memory export, search and transcript backfill read
 * through this, so they do not change when the database becomes authoritative.
 *
 * Messages are delivered one at a time as the exact JSON the app stores, so
 * no caller needs a whole conversation in memory and unknown fields survive.
 *
 * Read states keep the app's existing meaning ([ReadState]): LOCKED, CORRUPT
 * and FAILED are never presented as an empty chat.
 */
interface ChatSource {

    /** One chat-list row: the chat's ID and the row exactly as stored. */
    class ChatEntry(val chatId: String, val listRowJson: String)

    class ListRead(val state: ReadState, val chats: List<ChatEntry>)

    /**
     * One stored message, with the two fields used for lookups pulled out.
     * Read the payload during the [readHistory] visit that delivered it.
     */
    abstract class Message(val messageId: String?, val isBot: Boolean) {
        /** The whole payload JSON as one string. */
        abstract val payloadJson: String

        /**
         * Write the payload JSON to [out]. Bounded-memory consumers (backups,
         * exports) use this: a source may hold only part of a very large
         * message at a time.
         */
        abstract fun copyPayloadTo(out: Appendable)
    }

    /** Stored preference values (types kept), or the reason they could not be read. */
    class ValuesRead(val state: ReadState, val values: Map<String, Any>)

    /** The chat list in stored order. Never reads any conversation. */
    fun readList(): ListRead

    /**
     * Deliver the chat's messages oldest first to [action], which returns
     * false to stop early. Returns the read state. The messages delivered are
     * the complete conversation only when the result is OK; on any other
     * result the caller must discard whatever it received. For a chat that
     * cannot be read, nothing is delivered and the existing damaged-chat
     * handling runs, recording [detectedDuring].
     */
    fun readHistory(
        chatId: String,
        detectedDuring: DamagedChatOperation,
        action: (Message) -> Boolean
    ): ReadState

    /**
     * The other values stored in the chat's own file `chat_<id>` (everything
     * except its messages). Per-chat settings live in `settings.<id>`, which
     * is not chat storage and stays where it is under both sources.
     */
    fun readChatValues(chatId: String): ValuesRead

    /** The chat list file's other stored values (folders and the like). */
    fun readListValues(): ValuesRead

    /**
     * A value that changes whenever the chat's messages change, for
     * incremental exports, or null when the chat cannot be read. Tokens from
     * different sources never match each other.
     */
    fun changeToken(chatId: String): String?
}
