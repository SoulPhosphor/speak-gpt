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

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.teslasoft.assistant.preferences.ChatHistoryWriteOrder
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/**
 * Writes one reply's row into its stored chat, by the row's permanent ID,
 * without touching any other row. The read, the change and the write happen
 * under the chat history lock as one ordered save, so it never replaces a
 * newer save, never writes a deleted chat and never writes over a chat whose
 * storage is locked or damaged.
 */
internal object ReplyRowWriter {
    enum class Outcome { SAVED, ROW_GONE, CHAT_GONE, NOT_SAVED }

    fun write(
        context: Context,
        chatId: String,
        row: Map<String, Any>,
        detectedDuring: DamagedChatOperation
    ): Outcome {
        val app = context.applicationContext
        val rowId = MessageIdentity.idOf(row)
        if (rowId.isBlank() || chatId.isBlank()) return Outcome.ROW_GONE
        val order = ChatHistoryWriteOrder.shared
        var outcome = Outcome.NOT_SAVED
        val seq = order.claim(chatId)
        val written = order.writeIfNewest(chatId, seq, { it == ChatStorageHealth.WriteOutcome.OK }) {
            if (ReplyRunRegistry.isDeleted(chatId)) {
                outcome = Outcome.CHAT_GONE
                return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            }
            val chats = ChatPreferences.getChatPreferences()
            val stored = chats.getChatByIdResult(app, chatId, detectedDuring)
            if (!ChatStorageHealth.isAuthoritative(stored.state)) return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            val history = stored.messages
            val index = history.indexOfFirst { MessageIdentity.idOf(it) == rowId }
            if (index < 0) {
                outcome = Outcome.ROW_GONE
                return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            }
            history[index] = ChatHistoryWriteOrder.snapshotOf(listOf(row)).first()
            chats.saveChatHistory(app, chatId, history, synchronous = true)
        }
        if (written == ChatStorageHealth.WriteOutcome.OK) outcome = Outcome.SAVED
        return outcome
    }

    /** Removes the stored row with [rowId]; true when it is gone afterwards. */
    fun remove(context: Context, chatId: String, rowId: String, detectedDuring: DamagedChatOperation): Boolean {
        val app = context.applicationContext
        if (rowId.isBlank() || chatId.isBlank()) return true
        val order = ChatHistoryWriteOrder.shared
        var gone = false
        val seq = order.claim(chatId)
        val written = order.writeIfNewest(chatId, seq, { it == ChatStorageHealth.WriteOutcome.OK }) {
            if (ReplyRunRegistry.isDeleted(chatId)) {
                gone = true
                return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            }
            val chats = ChatPreferences.getChatPreferences()
            val stored = chats.getChatByIdResult(app, chatId, detectedDuring)
            if (!ChatStorageHealth.isAuthoritative(stored.state)) return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            val history = stored.messages
            val index = history.indexOfFirst { MessageIdentity.idOf(it) == rowId }
            if (index < 0) {
                gone = true
                return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            }
            history.removeAt(index)
            chats.saveChatHistory(app, chatId, history, synchronous = true)
        }
        return gone || written == ChatStorageHealth.WriteOutcome.OK
    }
}

/**
 * The host of a reply whose chat screen closed: the conversation as the
 * screen left it, and the reply's own row, which it saves through
 * [ReplyRowWriter] — every couple of seconds while text arrives, and once
 * more at the end, retrying a save storage refused until it lands or the
 * row or chat is gone.
 */
internal class DetachedReplyHost(
    context: Context,
    private val chatId: () -> String,
    override val model: String,
    private val rows: List<HashMap<String, Any>>,
    private val row: HashMap<String, Any>,
    private val turnId: String,
    private val detectedDuring: DamagedChatOperation
) : ReplyRunHost {
    private val app = context.applicationContext
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    @Volatile private var lastSaveUptime = 0L
    @Volatile private var saving = false
    @Volatile private var saveAgain = false
    @Volatile private var stopped = false

    override val storageUnavailable: Boolean get() = false
    override fun conversationSnapshot(): List<Map<String, Any>> = rows
    override fun lastRow(): HashMap<String, Any> = row
    override fun containsRow(row: Map<String, Any>?): Boolean = row != null && (row === this.row || rows.any { it === row })
    override fun usageCarrier(): HashMap<String, Any> = row
    override fun lifecycleTurnId(): String = turnId

    override fun replyUpdated(text: String) {
        row["message"] = text
        val now = SystemClock.uptimeMillis()
        if (now - lastSaveUptime >= SAVE_INTERVAL_MS) {
            lastSaveUptime = now
            saveInBackground()
        }
    }

    override fun replyFinished() {}

    override fun released() {
        synchronized(saveLock) { stopped = true }
    }

    override fun saveRow(row: HashMap<String, Any>) {
        val snapshot = HashMap(row)
        saves.launch {
            var delayMs = FIRST_RETRY_MS
            while (ReplyRowWriter.write(app, chatId(), snapshot, detectedDuring) == ReplyRowWriter.Outcome.NOT_SAVED) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    override fun removeRow(row: HashMap<String, Any>) {
        synchronized(saveLock) { stopped = true }
        val rowId = MessageIdentity.idOf(row)
        saves.launch {
            var delayMs = FIRST_RETRY_MS
            while (!ReplyRowWriter.remove(app, chatId(), rowId, detectedDuring)) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    /** Saves a copy of the row as it is now; a save already running saves again after. */
    override fun saveInBackground() {
        val snapshot: Map<String, Any>
        synchronized(saveLock) {
            if (stopped) return
            if (saving) { saveAgain = true; return }
            saving = true
            snapshot = HashMap(row)
        }
        saves.launch { saveWithRetry(snapshot) }
    }

    private suspend fun saveWithRetry(first: Map<String, Any>) {
        var snapshot = first
        var delayMs = FIRST_RETRY_MS
        while (true) {
            if (synchronized(saveLock) { stopped }) {
                synchronized(saveLock) { saving = false }
                return
            }
            when (ReplyRowWriter.write(app, chatId(), snapshot, detectedDuring)) {
                ReplyRowWriter.Outcome.ROW_GONE, ReplyRowWriter.Outcome.CHAT_GONE -> {
                    synchronized(saveLock) { stopped = true; saving = false }
                    return
                }
                ReplyRowWriter.Outcome.SAVED -> {
                    val again = synchronized(saveLock) {
                        if (saveAgain) { saveAgain = false; HashMap(row) } else { saving = false; null }
                    } ?: return
                    snapshot = again
                    delayMs = FIRST_RETRY_MS
                }
                ReplyRowWriter.Outcome.NOT_SAVED -> {
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
                    // Retry with the newest text.
                    snapshot = synchronized(saveLock) { saveAgain = false; HashMap(row) }
                }
            }
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 2000L
        const val FIRST_RETRY_MS = 250L
        const val MAX_RETRY_MS = 30_000L
    }
}
