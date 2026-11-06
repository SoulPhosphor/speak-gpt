package org.teslasoft.assistant.preferences.memory.enforcer

import org.json.JSONArray
import org.json.JSONObject

import org.teslasoft.assistant.preferences.memory.ManualMemorySelection
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.RetrievableMemory

/** Explicit attachments bypass automatic relevance/scope/cooldown gates for this turn. */
object ManualMemoryPriority {
    fun remainingSlots(topK: Int, selectedCount: Int): Int = (topK - selectedCount).coerceAtLeast(0)

    /** Resolve current content at Send, preserving protection and Instruction Type behavior.
     * Missing records fail preparation rather than silently discarding a chosen attachment. */
    fun resolve(ids: List<String>, load: (String) -> MemoryRecord?): List<RetrievableMemory> {
        val unique = ids.distinct()
        require(unique.size <= ManualMemorySelection.MAX_COUNT)
        return unique.map { id ->
            val memory = checkNotNull(load(id)) { "A selected memory is no longer available." }
            check(memory.status != "draft") { "A selected memory is no longer approved." }
            RetrievableMemory(
                memoryId = memory.memoryId, scope = memory.scope, content = memory.content,
                embeddingText = memory.embeddingText, importance = memory.importance,
                createdAt = memory.createdAt, worldId = memory.worldIds.firstOrNull(),
                protectionJson = memory.protectionJson, typeId = memory.typeId,
                tagsJson = memory.tagsJson, updatedAt = memory.updatedAt
            )
        }
    }

    data class Rendered(val prompt: String, val memoryIds: List<String>)

    /** The plain-completion pipeline also supports explicit attachments. */
    fun render(ids: List<String>, load: (String) -> MemoryRecord?): Rendered {
        val memories = resolve(ids, load).map { assemble(it, 1f, 1f) }
        return Rendered(PromptAssembler.render(AssemblyComponents(memories = memories)), memories.map { it.memoryId })
    }

    fun assemble(m: RetrievableMemory, score: Float, similarity: Float): AssembledMemory {
        var handling: List<String> = emptyList()
        var neverAssume: List<String> = emptyList()
        m.protectionJson?.takeIf { it.isNotBlank() }?.let {
            try {
                val p = JSONObject(it)
                if (p.optBoolean("is_protected", false) ||
                    p.has("handling") || p.has("never_assume")
                ) {
                    handling = jsonToList(p.optJSONArray("handling"))
                    neverAssume = jsonToList(p.optJSONArray("never_assume"))
                }
            } catch (_: Exception) { /* unparseable protection: memory still renders, unprotected fields empty */ }
        }
        return AssembledMemory(
            memoryId = m.memoryId,
            content = m.content,
            handling = handling,
            neverAssume = neverAssume,
            score = score,
            similarity = similarity,
            importance = m.importance ?: 0,
            typeId = m.typeId
        )
    }

    private fun jsonToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

}
