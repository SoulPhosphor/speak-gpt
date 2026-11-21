package org.teslasoft.assistant.conversation.responsegroups

import com.google.gson.Gson
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.TurnUsageRecord

/** Pure owned-snapshot mutations. Nothing here dispatches, promotes on browse, or changes legacy rows. */
object ResponseGroupEdits {
    fun canonicalFields(group: ResponseGroup): Map<String, String> {
        val slot = group.slots.single { it.slotId == group.primarySlotId }
        return slot.versions.single { it.versionId == slot.canonicalVersionId }.fields
    }

    fun foregroundFields(group: ResponseGroup): Map<String, String> {
        val slot = group.slots.single { it.slotId == group.foregroundSlotId }
        return slot.versions.single { it.versionId == slot.foregroundVersionId }.fields
    }

    /** Detached display only: the persisted message continues to mirror the primary canonical version. */
    fun display(message: Map<String, Any>): HashMap<String, Any> = HashMap(message).also { copy ->
        val group = (ResponseGroupCodec.read(message) as? ResponseGroupRead.Valid)?.group ?: return@also
        ChatAdapter.applyVariant(copy, foregroundFields(group))
    }

    fun browse(group: ResponseGroup, slotId: String, versionId: String): ResponseGroup = group.copy(
        foregroundSlotId = slotId,
        slots = group.slots.map { if (it.slotId == slotId) it.copy(foregroundVersionId = versionId) else it }
    ).also(ResponseGroupCodec::validate)

    fun promote(group: ResponseGroup, slotId: String, versionId: String): ResponseGroup = group.copy(
        primarySlotId = slotId,
        slots = group.slots.map { if (it.slotId == slotId) it.copy(canonicalVersionId = versionId) else it }
    ).also(ResponseGroupCodec::validate)

    /** Keep every ID and checkpoint; recovery is interruption, never success or a paid retry. */
    fun interrupted(group: ResponseGroup): ResponseGroup = group.copy(slots = group.slots.map { slot ->
        slot.copy(versions = slot.versions.map { version ->
            val fields = if (version.fields["state"] == MessageCompletionState.STREAMING)
                version.fields + mapOf("state" to MessageCompletionState.INTERRUPTED,
                    "stateDetail" to MessageCompletionState.DETAIL_PROCESS_DEATH) else version.fields
            version.copy(fields = fields, attempts = version.attempts.map { attempt ->
                if (attempt.state != MessageCompletionState.STREAMING) attempt else attempt.copy(
                    state = MessageCompletionState.INTERRUPTED,
                    partialFields = if (attempt.partialFields.isEmpty()) emptyMap() else attempt.partialFields +
                        mapOf("state" to MessageCompletionState.INTERRUPTED,
                            "stateDetail" to MessageCompletionState.DETAIL_PROCESS_DEATH)
                )
            })
        })
    })

    /** New attempts/versions append. Existing ownership and the original input cannot be replaced. */
    fun validateTransition(before: ResponseGroup, after: ResponseGroup) {
        ResponseGroupCodec.validate(after)
        require(before.schemaVersion == after.schemaVersion && before.groupId == after.groupId &&
            before.userMessageId == after.userMessageId && before.assistantMessageId == after.assistantMessageId &&
            before.input == after.input) { "immutable_group_input" }
        require(before.slots.map { it.slotId } == after.slots.map { it.slotId }) { "immutable_slots" }
        before.slots.zip(after.slots).forEach { (oldSlot, newSlot) ->
            require(oldSlot.assignment == newSlot.assignment && oldSlot.promptPartIds == newSlot.promptPartIds) {
                "immutable_assignment"
            }
            require(newSlot.versions.take(oldSlot.versions.size).map { it.versionId } ==
                oldSlot.versions.map { it.versionId }) { "immutable_versions" }
            oldSlot.versions.zip(newSlot.versions).forEach { (oldVersion, newVersion) ->
                require(newVersion.attempts.take(oldVersion.attempts.size).map { it.requestId } ==
                    oldVersion.attempts.map { it.requestId }) { "immutable_request_ids" }
                oldVersion.attempts.zip(newVersion.attempts).forEach { (old, new) ->
                    require(old.startedAtMs == new.startedAtMs) { "immutable_request_start" }
                    // A terminal receipt may be enriched with late provider usage, but not restarted or erased.
                    if (old.state != MessageCompletionState.STREAMING) {
                        require(old.state == new.state && old.endedAtMs == new.endedAtMs &&
                            old.partialFields == new.partialFields && old.failure == new.failure &&
                            old.warning == new.warning && (old.usageRecord == null || new.usageRecord != null)) {
                            "immutable_terminal_attempt"
                        }
                    }
                }
            }
        }
    }

    /** Atomic envelope/mirror replacement on a COPY. Identity, hidden marks, bookmarks and Includes survive. */
    fun install(message: Map<String, Any>, group: ResponseGroup): HashMap<String, Any> {
        when (val before = ResponseGroupCodec.read(message)) {
            ResponseGroupRead.Absent -> ResponseGroupCodec.validate(group)
            is ResponseGroupRead.Valid -> validateTransition(before.group, group)
            else -> error("unreadable_group_preserved")
        }
        require(message["message_id"] == group.assistantMessageId &&
            (message["isBot"] == true || message["isBot"]?.toString() == "true")) { "assistant_identity" }
        return HashMap(message).also { copy ->
            val slot = group.slots.single { it.slotId == group.primarySlotId }
            ChatAdapter.applyVariant(copy, canonicalFields(group))
            copy[ChatAdapter.KEY_VARIANTS] = Gson().toJson(slot.versions.map { it.fields })
            copy[ChatAdapter.KEY_CANONICAL_VARIANT] = slot.versions.indexOfFirst { it.versionId == slot.canonicalVersionId }.toString()
            // The legacy pager is also browse-only; it must never show a sibling model as a regeneration.
            copy[ChatAdapter.KEY_DISPLAY_VARIANT] = slot.versions.indexOfFirst { it.versionId == slot.foregroundVersionId }.toString()
            copy[ResponseGroupCodec.KEY] = ResponseGroupCodec.encode(group)
        }
    }

    /** All actual receipts, including failures/cancellations; the compatibility mirror is deliberately ignored. */
    fun usage(group: ResponseGroup): List<TurnUsageRecord> = group.slots.flatMap { slot ->
        slot.versions.flatMap { version -> version.attempts.flatMap { attempt ->
            TokenUsageAccounting.decodeRecords(attempt.usageRecord).ifEmpty {
                // A missing receipt is unknown consumption, never a free request or a mirror estimate.
                listOf(TurnUsageRecord(model = slot.assignment.modelId,
                    provider = TokenUsageAccounting.PROVIDER_NOT_REPORTED,
                    apiEndpoint = slot.assignment.endpoint, requestId = attempt.requestId,
                    requestedModel = slot.assignment.modelId, requestStartedAtMs = attempt.startedAtMs))
            }
        } }
    }.distinctBy { it.requestId }

    /** No missing or changed attachment may silently become an exact-regeneration prompt. */
    fun unavailableAttachments(group: ResponseGroup, read: (ResponseAttachment) -> ByteArray?): List<ResponseAttachment> =
        group.input.parts.mapNotNull { it.attachment }.filter { attachment ->
            val bytes = read(attachment)
            bytes == null || !attachment.matches(bytes)
        }
}
