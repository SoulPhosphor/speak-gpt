package org.teslasoft.assistant.ui.chat.parallel

import org.teslasoft.assistant.conversation.responsegroups.*

/** Permanent dialog target: browsing, streaming and row replacement cannot redirect an edit. */
data class ParallelReplyEditTarget(val assistantMessageId: String, val groupId: String,
    val slotId: String, val versionId: String)

internal object ParallelReplyEditing {
    fun target(group: ResponseGroup): ParallelReplyEditTarget {
        val slot = group.slots.single { it.slotId == group.foregroundSlotId }
        return ParallelReplyEditTarget(group.assistantMessageId, group.groupId, slot.slotId, slot.foregroundVersionId)
    }

    /** User text is separate from the immutable provider checkpoints and usage receipts. */
    fun apply(group: ResponseGroup, target: ParallelReplyEditTarget, text: String): ResponseGroup {
        require(group.assistantMessageId == target.assistantMessageId && group.groupId == target.groupId)
        val selected = group.slots.single { it.slotId == target.slotId }
        require(selected.versions.any { it.versionId == target.versionId })
        return group.copy(slots = group.slots.map { slot ->
            if (slot.slotId != target.slotId) slot else slot.copy(versions = slot.versions.map { version ->
                if (version.versionId != target.versionId) version else version.copy(
                    fields = (version.fields - "visibleTextTokenEstimate") + ("message" to text))
            })
        }).also { ResponseGroupEdits.validateTransition(group, it) }
    }

    fun canonical(group: ResponseGroup, target: ParallelReplyEditTarget): Boolean =
        group.primarySlotId == target.slotId &&
            group.slots.single { it.slotId == target.slotId }.canonicalVersionId == target.versionId
}
