package org.teslasoft.assistant.conversation.parallel

import android.content.Context
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/** The transform runs against the latest owned history under the storage lock, never a cached group.
 * Exceptions mean no commit was acknowledged. Callers may retry saving evidence, never the network.
 */
internal interface ParallelGroupPersistence {
    fun read(target: ResponseGroupTarget): ResponseGroup
    fun update(target: ResponseGroupTarget, transform: (ResponseGroup?) -> ResponseGroup): ResponseGroup
}

internal class ParallelPersistenceException(val category: String) : Exception(category)

/** Uses the existing encrypted-preference authority and synchronous guarded Stage 1 boundary.
 * Canonical source reconciliation is a required dependency, not an implicit no-op callback.
 */
internal class StoredParallelGroups(
    context: Context,
    val scope: ParallelChatScope,
    private val canonicalSourcesChanged: (ResponseGroupHistory.Change) -> Unit
) : ParallelGroupPersistence {
    private val appContext = context.applicationContext

    override fun read(target: ResponseGroupTarget): ResponseGroup = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        val (_, group) = load(scope.target(target))
        group ?: throw ParallelPersistenceException("group_missing")
    }

    override fun update(target: ResponseGroupTarget, transform: (ResponseGroup?) -> ResponseGroup): ResponseGroup =
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val actualTarget = scope.target(target)
            val (envelope, before) = load(actualTarget)
            val after = transform(before)
            if (after == before) return@synchronized after
            when (ResponseGroupStore.replace(appContext, actualTarget.chatId, actualTarget, envelope, after,
                canonicalSourcesChanged = canonicalSourcesChanged)) {
                is ResponseGroupStore.Result.Saved -> after
                is ResponseGroupStore.Result.Unavailable -> throw ParallelPersistenceException("storage_unavailable")
                is ResponseGroupStore.Result.WriteFailed -> throw ParallelPersistenceException("write_failed")
                ResponseGroupStore.Result.Rejected -> throw ParallelPersistenceException("owner_or_branch_changed")
            }
        }

    private fun load(target: ResponseGroupTarget): Pair<String?, ResponseGroup?> {
        val result = ChatPreferences.getChatPreferences().getChatByIdResult(
            appContext, target.chatId, DamagedChatOperation.OPENING_A_CHAT)
        if (!ChatStorageHealth.isAuthoritative(result.state)) throw ParallelPersistenceException("storage_unavailable")
        val rows = result.messages.filter { it["message_id"] == target.assistantMessageId }
        val row = rows.singleOrNull() ?: throw ParallelPersistenceException("message_missing")
        return when (val read = ResponseGroupCodec.read(row)) {
            ResponseGroupRead.Absent -> null to null
            is ResponseGroupRead.Valid -> {
                if (read.group.groupId != target.groupId) throw ParallelPersistenceException("owner_changed")
                row[ResponseGroupCodec.KEY] as String to read.group
            }
            else -> throw ParallelPersistenceException("group_unreadable")
        }
    }
}
