package org.teslasoft.assistant.preferences.memory.archivist

import org.json.JSONArray
import org.teslasoft.assistant.preferences.memory.MemoryMatch
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.librarian.MemoryTokenBudget
import org.teslasoft.assistant.preferences.memory.librarian.RetrievalDocument
import java.util.Locale

/** Request-local final-part deduplication, before the chat is committed atomically. */
class StagedMemoryParts(private val reader: MemoryTokenBudget.Reader?) {
    val records = ArrayList<MemoryRecord>()
    val relationshipHints = linkedMapOf<String, List<String>>()
    private val indices = HashMap<String, MutableList<Int>>()

    /** Returns true for a new record. A duplicate keeps all additional metadata. */
    fun add(record: MemoryRecord, hints: List<String>): Boolean {
        val key = identity(record)
        for (index in indices[key].orEmpty()) {
            val prior = records[index]
            val tags = (RetrievalDocument.parseTags(prior.tagsJson) + RetrievalDocument.parseTags(record.tagsJson))
                .distinctBy { it.lowercase(Locale.ROOT) }
            val merged = prior.copy(tagsJson = JSONArray(tags).toString())
            // Distinct tag groups must remain separate if their union cannot
            // fit. Dropping metadata to deduplicate would lose information.
            if (reader != null && !reader.measure(MemoryTokenBudget.Input.from(merged)).fits) continue
            records[index] = merged
            val mergedHints = (relationshipHints[prior.memoryId].orEmpty() + hints).distinct()
            if (mergedHints.isNotEmpty()) relationshipHints[prior.memoryId] = mergedHints
            return false
        }
        indices.getOrPut(key) { ArrayList() }.add(records.size)
        records.add(record)
        if (hints.isNotEmpty()) relationshipHints[record.memoryId] = hints.distinct()
        return true
    }

    private fun identity(record: MemoryRecord): String = listOf(
        MemoryMatch.normalizeContent(record.content),
        MemoryMatch.placementKey(record.scope, record.worldIds + record.roleplayCharacterIds +
            record.campaignIds + record.projectIds + record.companionIds),
        record.typeId.orEmpty()
    ).joinToString("\u0000")
}
