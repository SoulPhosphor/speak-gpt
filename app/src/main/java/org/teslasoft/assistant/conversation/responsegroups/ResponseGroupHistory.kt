package org.teslasoft.assistant.conversation.responsegroups

import org.teslasoft.assistant.preferences.MessageIdentity

/** A target always includes its owning conversation, even after a restore-as-copy preserves all embedded IDs. */
data class ResponseGroupTarget(val chatId: String, val assistantMessageId: String, val groupId: String)

/** Pure compare-and-replace for one owned history snapshot; persistence must commit this result under its save lock. */
object ResponseGroupHistory {
    data class Change(
        val messages: List<HashMap<String, Any>>,
        val before: Map<String, Any>,
        val after: Map<String, Any>,
        val canonicalChanged: Boolean,
        val removed: List<Map<String, Any>>
    )

    fun replace(
        chatId: String,
        messages: List<Map<String, Any>>,
        target: ResponseGroupTarget,
        expectedEnvelope: String?,
        group: ResponseGroup,
        discardDescendants: Boolean = false
    ): Change {
        require(chatId == target.chatId && chatId.isNotBlank()) { "conversation_scope" }
        val index = messages.indexOfFirst { MessageIdentity.idOf(it) == target.assistantMessageId }
        require(index >= 0 && messages.count { MessageIdentity.idOf(it) == target.assistantMessageId } == 1) { "message_identity" }
        val before = messages[index]
        require(before[ResponseGroupCodec.KEY] == expectedEnvelope && target.groupId == group.groupId &&
            target.assistantMessageId == group.assistantMessageId) { "stale_target" }
        val user = messages.take(index).lastOrNull { it["isBot"] != true && it["isBot"]?.toString() != "true" }
        require(user?.let(MessageIdentity::idOf) == group.userMessageId) { "user_identity" }
        val after = ResponseGroupEdits.install(before, group)
        val changed = ChatVersionProjection.fields(before) != ChatVersionProjection.fields(after)
        require(!changed || index == messages.lastIndex || discardDescendants) { "branch_confirmation_required" }
        val retained = if (changed && discardDescendants) messages.take(index + 1) else messages
        val result = retained.mapIndexed { i, row -> if (i == index) after else HashMap(row) }
        validateConversation(result)
        return Change(result, before, after, changed, messages.drop(retained.size))
    }

    /** Stored alongside the history, including when it is rebuilt by recovery restore. */
    fun fingerprint(messages: List<Map<String, Any>>): String? {
        val groups = messages.filter { it.containsKey(ResponseGroupCodec.KEY) }
        if (groups.isEmpty()) return null
        return org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection.fingerprint(
            com.google.gson.Gson().toJson(groups.map {
                listOf(it["message_id"], it[ResponseGroupCodec.KEY], ChatVersionProjection.fields(it))
            })
        )
    }

    /** A surviving row cannot shed its group or rewrite historical request ownership through a generic save. */
    fun validateSavedTransition(before: List<Map<String, Any>>, after: List<Map<String, Any>>) {
        val current = after.associateBy(MessageIdentity::idOf)
        before.filter { it.containsKey(ResponseGroupCodec.KEY) }.forEach { oldRow ->
            val newRow = current[MessageIdentity.idOf(oldRow)] ?: return@forEach // Whole-row deletion/truncation is allowed.
            val old = ResponseGroupCodec.read(oldRow)
            val new = ResponseGroupCodec.read(newRow)
            if (old is ResponseGroupRead.Valid && new is ResponseGroupRead.Valid) {
                ResponseGroupEdits.validateTransition(old.group, new.group)
            } else require(oldRow[ResponseGroupCodec.KEY] == newRow[ResponseGroupCodec.KEY] &&
                ChatVersionProjection.fields(oldRow) == ChatVersionProjection.fields(newRow)) { "preserve_unreadable_envelope" }
        }
        validateConversation(after)
    }

    /** IDs may repeat in another chat's restored copy, never within this conversation. */
    fun validateConversation(messages: List<Map<String, Any>>) {
        val ownedIds = HashSet<String>()
        val rowIds = messages.mapNotNull { it[MessageIdentity.KEY]?.toString() }
        require(rowIds.size == rowIds.toSet().size) { "duplicate_message_identity" }
        messages.forEachIndexed { index, row ->
            val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return@forEachIndexed
            val user = messages.take(index).lastOrNull { it["isBot"] != true && it["isBot"]?.toString() != "true" }
            require(user?.let(MessageIdentity::idOf) == group.userMessageId) { "user_identity" }
            val ids = listOf(group.groupId) + group.input.parts.map { it.partId } + group.slots.flatMap { slot ->
                listOf(slot.slotId) + slot.versions.flatMap { version -> listOf(version.versionId) + version.attempts.map { it.requestId } }
            }
            require(ids.all { ownedIds.add(it) && it !in rowIds }) { "duplicate_group_identity" }
        }
    }
}

/** Only versioned answer fields participate in source invalidation; browsing never changes them. */
internal object ChatVersionProjection {
    fun fields(message: Map<String, Any>): Map<String, String> =
        org.teslasoft.assistant.ui.adapters.chat.ChatAdapter.snapshotVariant(message)
}
