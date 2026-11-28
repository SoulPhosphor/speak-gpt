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

package org.teslasoft.assistant.conversation.reply

/**
 * The replies that are still being received with no chat screen showing
 * them, one per chat. A reopened chat screen finds its reply here and
 * reconnects to it instead of sending anything again. Deleting the chat,
 * renaming its storage or replacing the whole chat set reaches the reply
 * here too.
 */
internal object ReplyRunRegistry {
    private val lock = Any()
    private val runs = HashMap<String, ReplyRun>()
    private val deleted = HashSet<String>()

    /** Records [run] as [chatId]'s reply in progress. */
    fun register(chatId: String, run: ReplyRun) = synchronized(lock) { runs[chatId] = run }

    /** Removes [run] when it is still [chatId]'s reply in progress. */
    fun unregister(chatId: String, run: ReplyRun) = synchronized(lock) {
        if (runs[chatId] === run) runs.remove(chatId)
    }

    /** [chatId]'s reply in progress, or null. */
    fun active(chatId: String): ReplyRun? = synchronized(lock) { runs[chatId] }

    /** True once [chatId] was deleted: nothing may be written to it again. */
    fun isDeleted(chatId: String): Boolean = synchronized(lock) { chatId in deleted }

    /** The chats were deleted: their replies stop and write nothing more. */
    fun markChatsDeleted(chatIds: Set<String>) {
        val stopped = synchronized(lock) {
            deleted.addAll(chatIds)
            chatIds.mapNotNull { runs.remove(it) }
        }
        stopped.forEach { it.retire() }
    }

    /** A legacy chat moved to a new id; its reply follows it. */
    fun rename(oldId: String, newId: String) {
        if (oldId == newId) return
        synchronized(lock) {
            runs.remove(oldId)?.let { run ->
                runs[newId] = run
                run.state.chatId = newId
            }
        }
    }

    /** The whole chat set was replaced (a restore): every reply stops and writes nothing more. */
    fun discardAll() {
        val stopped = synchronized(lock) { runs.values.toList().also { runs.clear() } }
        stopped.forEach { it.retire() }
    }
}
