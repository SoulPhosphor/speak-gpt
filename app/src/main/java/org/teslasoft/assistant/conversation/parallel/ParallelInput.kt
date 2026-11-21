package org.teslasoft.assistant.conversation.parallel

import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState
import java.util.UUID

/** Already-eligible canonical context, prepared once by the turn owner, before any transport runs.
 * Text may be a serialized provider-neutral message part; binary content stays in backed-up stores.
 * The caller performs the existing Include/capability and prompt-layer preparation, never a sibling.
 */
internal sealed interface ParallelInputPart {
    data class Text(val value: String) : ParallelInputPart
    data class Attachment(val value: ResponseAttachment) : ParallelInputPart
}

internal data class ParallelSlotInput(
    val assignment: ResponseModelAssignment,
    /** Complete ordered prompt, including the common eligible input and this model's prompt layers. */
    val prompt: List<ParallelInputPart>,
    val attribution: Map<String, String> = emptyMap()
)

/** Builds a detached, content-shared durable input. It never reads mutable chat/settings itself. */
internal object ParallelInput {
    fun freeze(
        userMessageId: String,
        assistantMessageId: String,
        contextMode: String,
        context: List<ParallelInputPart>,
        currentTurn: List<ParallelInputPart>,
        slots: List<ParallelSlotInput>,
        primaryIndex: Int,
        nowMs: Long
    ): ResponseGroup {
        require(slots.size >= 2 && primaryIndex in slots.indices)
        require(slots.map { it.assignment.endpointProfileId to it.assignment.modelId }.distinct().size == slots.size)
        val parts = LinkedHashMap<ParallelInputPart, ResponseSnapshotPart>()
        fun refs(input: List<ParallelInputPart>) = input.map { part ->
            parts.getOrPut(part) {
                when (part) {
                    is ParallelInputPart.Text -> ResponseSnapshotPart(id(), text = part.value)
                    is ParallelInputPart.Attachment -> ResponseSnapshotPart(id(), attachment = part.value.copy())
                }
            }.partId
        }
        val contextIds = refs(context)
        val currentIds = refs(currentTurn)
        require(currentIds.isNotEmpty())
        val frozenSlots = slots.map { selected ->
            val promptIds = refs(selected.prompt)
            // Every slot must carry the complete eligible context/current turn in order.
            // A different model may add layers but may never silently omit an attachment or turn.
            require(isSubsequence(contextIds + currentIds, promptIds)) { "incomplete_frozen_input" }
            val versionId = id()
            val fields = selected.attribution + mapOf("message" to "", "state" to MessageCompletionState.STREAMING)
            ResponseSlot(id(), selected.assignment.copy(
                routing = selected.assignment.routing.toMap(), parameters = selected.assignment.parameters.toMap(),
                modelRules = selected.assignment.modelRules.toMap(), capabilities = selected.assignment.capabilities.toMap()
            ), promptIds, listOf(ResponseVersion(versionId, fields.toMap(), listOf(ResponseAttempt.pending(nowMs)))),
                versionId, versionId)
        }
        return ResponseGroup(groupId = id(), userMessageId = userMessageId, assistantMessageId = assistantMessageId,
            slots = frozenSlots, primarySlotId = frozenSlots[primaryIndex].slotId,
            foregroundSlotId = frozenSlots[primaryIndex].slotId,
            input = ResponseInputSnapshot(contextMode, parts.values.toList(), contextIds, currentIds))
            .also { ResponseGroupCodec.encode(it) }
    }

    private fun isSubsequence(required: List<String>, actual: List<String>): Boolean {
        var next = 0
        actual.forEach { if (next < required.size && it == required[next]) next++ }
        return next == required.size
    }

    private fun id() = UUID.randomUUID().toString()
}
