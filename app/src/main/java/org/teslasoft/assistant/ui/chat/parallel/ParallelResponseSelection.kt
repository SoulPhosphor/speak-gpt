package org.teslasoft.assistant.ui.chat.parallel

import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState

/** Browsing is durable and harmless. Only Send commits the displayed causal branch. */
internal object ParallelResponseSelection {
    data class Pending(val position: Int, val group: ResponseGroup, val hasDescendants: Boolean)

    fun pending(messages: List<Map<String, Any>>): Pending? = messages.withIndex().firstNotNullOfOrNull { (index, row) ->
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return@firstNotNullOfOrNull null
        val slot = group.slots.single { it.slotId == group.foregroundSlotId }
        if (group.primarySlotId != slot.slotId || slot.canonicalVersionId != slot.foregroundVersionId)
            Pending(index, group, index != messages.lastIndex) else null
    }

    fun adopt(group: ResponseGroup): ResponseGroup {
        val slot = group.slots.single { it.slotId == group.foregroundSlotId }
        return ResponseGroupEdits.promote(group, slot.slotId, slot.foregroundVersionId)
    }

    fun running(group: ResponseGroup): Boolean = group.slots.any(::running)
    fun running(slot: ResponseSlot): Boolean = slot.versions.any { version ->
        version.attempts.any { it.state == MessageCompletionState.STREAMING }
    }

    fun display(row: Map<String, Any>): HashMap<String, Any> = ResponseGroupEdits.display(row).also { display ->
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return@also
        val slot = group.slots.single { it.slotId == group.foregroundSlotId }
        val version = slot.versions.single { it.versionId == slot.foregroundVersionId }
        val records = version.attempts.flatMap { org.teslasoft.assistant.usage.TokenUsageAccounting.decodeRecords(it.usageRecord) }
        if (records.isNotEmpty()) display[org.teslasoft.assistant.usage.TokenUsageAccounting.KEY_USAGE_RECORDS] =
            org.teslasoft.assistant.usage.TokenUsageAccounting.encodeRecords(records)
        image(version)?.let { display[org.teslasoft.assistant.imagegen.GeneratedImageMetadata.KEY] = it }
    }

    fun canonicalImage(row: Map<String, Any>): String? {
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return null
        val slot = group.slots.single { it.slotId == group.primarySlotId }
        return image(slot.versions.single { it.versionId == slot.canonicalVersionId })
    }
    private fun image(version: ResponseVersion): String? = version.attempts.asReversed().firstNotNullOfOrNull { it.imageResult }

    fun displayIndex(row: Map<String, Any>): Int? {
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return null
        return group.slots.flatMap { slot -> slot.versions.map { slot.slotId to it.versionId } }
            .indexOf(group.foregroundSlotId to group.slots.single { it.slotId == group.foregroundSlotId }.foregroundVersionId)
    }

    fun storeEstimate(row: HashMap<String, Any>, display: HashMap<String, Any>, index: Int, text: String, count: Int): Boolean? {
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return null
        if (displayIndex(row) != index || ResponseGroupEdits.foregroundFields(group)["message"] != text) return false
        val slot = group.slots.single { it.slotId == group.foregroundSlotId }
        val after = group.copy(slots = group.slots.map { selected ->
            if (selected.slotId != slot.slotId) selected else selected.copy(versions = selected.versions.map { version ->
                if (version.versionId != slot.foregroundVersionId) version else version.copy(fields = version.fields + ("visibleTextTokenEstimate" to count.toString()))
            })
        })
        val replacement = ResponseGroupEdits.install(row, after)
        row.clear(); row.putAll(replacement)
        display["visibleTextTokenEstimate"] = count.toString()
        return true
    }
}
