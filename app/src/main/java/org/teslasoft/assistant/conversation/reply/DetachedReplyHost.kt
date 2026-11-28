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
import org.teslasoft.assistant.preferences.ChatHistoryWriteOrder
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
import org.teslasoft.assistant.usage.TokenUsageAccounting

/** An immutable transaction: update the reply and its usage carrier, and/or remove
 * the empty reply, together. It never replaces an unrelated conversation row. */
internal data class ReplyEdits(
    val rows: List<HashMap<String, Any>>,
    val removed: Set<String> = emptySet()
) {
    fun mergeInto(history: MutableList<HashMap<String, Any>>) {
        history.removeAll { MessageIdentity.idOf(it) in removed }
        for (row in rows) {
            val index = history.indexOfFirst { MessageIdentity.idOf(it) == MessageIdentity.idOf(row) }
            if (index >= 0) {
                val current = history[index]
                // Generation owns response fields. Bookmarks, visibility, display
                // choices, user text and attachments still belong to the conversation.
                val fields = if (row["isBot"] == true) ChatAdapter.VARIANT_FIELDS
                    else listOf(ChatAdapter.KEY_TOKEN_USAGE_RECORDS, ScanMessageScope.KEY)
                for (field in fields) {
                    if (row["isBot"] != true && field == ChatAdapter.KEY_TOKEN_USAGE_RECORDS) {
                        // Several tool selections can charge the same initiating
                        // message. A late receipt must not erase a newer one.
                        val existing = TokenUsageAccounting.decodeRecords(current[field]?.toString())
                        val incoming = TokenUsageAccounting.decodeRecords(row[field]?.toString())
                        val combined = existing.toMutableList()
                        incoming.forEach { record ->
                            val index = combined.indexOfFirst { old ->
                                if (record.requestId != null) old.requestId == record.requestId else old == record
                            }
                            if (index < 0) combined.add(record) else combined[index] = record
                        }
                        if (combined.isNotEmpty()) current[field] = TokenUsageAccounting.encodeRecords(combined)
                        continue
                    }
                    if (row.containsKey(field)) current[field] = row.getValue(field)
                    else if (row["isBot"] == true) current.remove(field)
                }
                for (field in listOf(ChatAdapter.KEY_VARIANTS, ChatAdapter.KEY_CANONICAL_VARIANT)) {
                    row[field]?.let { current[field] = it }
                }
            }
        }
    }
}

internal object ReplyRowWriter {
    enum class Outcome { SAVED, ROW_GONE, CHAT_GONE, NOT_SAVED }

    fun write(context: Context, chatId: String, row: Map<String, Any>, detectedDuring: DamagedChatOperation): Outcome =
        apply(context, chatId, ReplyEdits(ChatHistoryWriteOrder.snapshotOf(listOf(row))), detectedDuring)

    fun remove(context: Context, chatId: String, rowId: String, detectedDuring: DamagedChatOperation): Boolean =
        apply(context, chatId, ReplyEdits(emptyList(), setOf(rowId)), detectedDuring) in
            setOf(Outcome.SAVED, Outcome.ROW_GONE, Outcome.CHAT_GONE)

    fun apply(
        context: Context, chatId: String, edits: ReplyEdits,
        detectedDuring: DamagedChatOperation, wanted: () -> Boolean = { true }
    ): Outcome {
        if (chatId.isBlank()) return Outcome.CHAT_GONE
        val app = context.applicationContext
        val order = ChatHistoryWriteOrder.shared
        var outcome = Outcome.NOT_SAVED
        val seq = order.claim(chatId)
        val written = order.writeIfNewest(chatId, seq, { it == ChatStorageHealth.WriteOutcome.OK }, stillWanted = wanted) {
            if (ReplyRunRegistry.isDeleted(chatId)) {
                outcome = Outcome.CHAT_GONE
                return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            }
            if (order.hasPending(chatId)) return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            val chats = ChatPreferences.getChatPreferences()
            val stored = chats.getChatByIdResult(app, chatId, detectedDuring)
            if (!ChatStorageHealth.isAuthoritative(stored.state)) return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            if (edits.rows.isNotEmpty() && edits.rows.none { update -> stored.messages.any { MessageIdentity.idOf(it) == MessageIdentity.idOf(update) } }) {
                outcome = Outcome.ROW_GONE
                return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            }
            // A deletion queued during the read takes priority. Retry its newer history.
            if (!order.isLatestClaim(chatId, seq) || !wanted()) return@writeIfNewest ChatStorageHealth.WriteOutcome.FAILED
            edits.mergeInto(stored.messages)
            chats.saveChatHistory(app, chatId, stored.messages, synchronous = true)
        }
        if (written == ChatStorageHealth.WriteOutcome.OK) outcome = Outcome.SAVED
        return outcome
    }
}

/** The reply's permanent state/storage owner after its original screen closes.
 * Reopened screens observe this host; they never replace its rows or writer. All
 * row mutation/snapshot creation happens on main; IO only sees immutable copies. */
internal class DetachedReplyHost(
    context: Context,
    private val chatId: () -> String,
    override val model: String,
    private val rows: List<HashMap<String, Any>>,
    private val row: HashMap<String, Any>,
    private val turnId: String,
    private val detectedDuring: DamagedChatOperation,
    private val ownerWanted: () -> Boolean = { true }
) : ReplyRunHost {
    private val app = context.applicationContext
    private val changed = LinkedHashMap<String, HashMap<String, Any>>()
    private val removed = LinkedHashSet<String>()
    private var lastSaveUptime = 0L
    private val saves = ReplyWriteQueue<ReplyEdits>(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { edits, wanted ->
        val target = chatId()
        if (!ownerWanted()) ReplyWriteQueue.Result.GONE else when (ReplyRowWriter.apply(
            app, target, edits, detectedDuring, { wanted() && target == chatId() && ownerWanted() }
        )) {
            ReplyRowWriter.Outcome.SAVED -> ReplyWriteQueue.Result.SAVED
            ReplyRowWriter.Outcome.NOT_SAVED -> if (ownerWanted()) ReplyWriteQueue.Result.RETRY else ReplyWriteQueue.Result.GONE
            else -> ReplyWriteQueue.Result.GONE
        }
    }

    override val storageUnavailable: Boolean get() = false
    override fun conversationSnapshot(): List<Map<String, Any>> = rows.filterNot { MessageIdentity.idOf(it) in removed }
    override fun lastRow(): HashMap<String, Any> = row
    override fun containsRow(row: Map<String, Any>?): Boolean = row != null &&
        MessageIdentity.idOf(row) !in removed && rows.any { it === row }
    override fun usageCarrier(): HashMap<String, Any> = row
    override fun lifecycleTurnId(): String = turnId
    override fun replyFinished() {}

    override fun replyUpdated(text: String) {
        if (MessageIdentity.idOf(row) in removed) return
        row["message"] = text
        val now = SystemClock.uptimeMillis()
        if (now - lastSaveUptime >= 2000L) { lastSaveUptime = now; saveInBackground() }
    }

    override fun reasoningObserved(observation: ReasoningObservationContext, learnedCapability: org.teslasoft.assistant.reasoning.ReasoningCapability?) {
        if (MessageIdentity.idOf(row) !in removed && ObservedReasoning.belongsTo(row, observation) &&
            ObservedReasoning.applyTo(row, observation, learnedCapability)) saveInBackground()
    }

    private fun snapshot(): ReplyEdits {
        val updates = LinkedHashMap(changed)
        if (MessageIdentity.idOf(row) !in removed) updates[MessageIdentity.idOf(row)] = row
        return ReplyEdits(ChatHistoryWriteOrder.snapshotOf(updates.values.toList()), removed.toSet())
    }

    override fun reconcileInto(history: MutableList<HashMap<String, Any>>) = snapshot().mergeInto(history)
    override fun saveInBackground() = saves.submit(snapshot())
    override suspend fun awaitSaved() = saves.awaitSaved()
    override fun released() = saves.retire()
    override fun saveRow(row: HashMap<String, Any>) {
        changed[MessageIdentity.idOf(row)] = row
        saveInBackground()
    }
    override fun removeRow(row: HashMap<String, Any>) {
        removed.add(MessageIdentity.idOf(row))
        changed.remove(MessageIdentity.idOf(row))
        saveInBackground()
    }
}
