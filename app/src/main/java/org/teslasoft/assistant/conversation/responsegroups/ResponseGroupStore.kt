package org.teslasoft.assistant.conversation.responsegroups

import android.content.Context
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/** Hidden foundation API. All group mutations serialize against the live guarded history save boundary. */
object ResponseGroupStore {
    sealed class Result {
        data class Saved(val change: ResponseGroupHistory.Change) : Result()
        data class Unavailable(val state: ChatStorageHealth.ReadState) : Result()
        data class WriteFailed(val outcome: ChatStorageHealth.WriteOutcome) : Result()
        data object Rejected : Result()
    }

    /**
     * No UI or dispatch caller until the later stages. Canonical source changes must be handled through
     * the existing summary/transcript reconciliation before this API is wired to promotion controls.
     * The required callback makes that responsibility explicit; browse-only saves never invoke it.
     */
    fun replace(
        context: Context,
        chatId: String,
        target: ResponseGroupTarget,
        expectedEnvelope: String?,
        group: ResponseGroup,
        discardDescendants: Boolean = false,
        canonicalSourcesChanged: (ResponseGroupHistory.Change) -> Unit
    ): Result = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        if (chatId != target.chatId) return@synchronized Result.Rejected
        val order = org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.shared
        // Claim before reading: a deletion queued during this mutation is
        // newer, while a successful mutation of a kept snapshot supersedes it.
        val seq = order.claim(chatId)
        val prefs = ChatPreferences.getChatPreferences()
        val read = prefs.getChatByIdResult(context, chatId, DamagedChatOperation.OPENING_A_CHAT)
        if (!ChatStorageHealth.isAuthoritative(read.state)) return@synchronized Result.Unavailable(read.state)
        val change = try {
            ResponseGroupHistory.replace(chatId, read.messages, target, expectedEnvelope, group, discardDescendants)
        } catch (_: Exception) { return@synchronized Result.Rejected }
        val outcome = order.writeIfNewest(chatId, seq, { it == ChatStorageHealth.WriteOutcome.OK },
            stillWanted = { order.isLatestClaim(chatId, seq) }) {
            prefs.saveChatHistory(context, chatId, change.messages, synchronous = true, responseGroupChanged = true)
        } ?: return@synchronized Result.Rejected
        if (outcome != ChatStorageHealth.WriteOutcome.OK) return@synchronized Result.WriteFailed(outcome)
        if (change.canonicalChanged) canonicalSourcesChanged(change)
        Result.Saved(change)
    }
}
