package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows

class MemoryCategoryPlannerTest {
    @Test
    fun `memory merge keeps conflicting current identity and adds new memory`() {
        val current = rows(MemoryPortableGroup.MEMORIES, mapOf(
            "memories" to listOf(memory("same", "Current"))
        ))
        val backup = rows(MemoryPortableGroup.MEMORIES, mapOf(
            "memories" to listOf(memory("same", "Backup"), memory("new", "New"))
        ))
        val ready = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES, current, backup, PortableRestoreMode.MERGE
        ) as MemoryCategoryPlanner.Result.Ready

        assertEquals(listOf("same", "new"), ready.rows.tables.getValue("memories").map { it["memory_id"] })
        assertEquals("Current", ready.rows.tables.getValue("memories").first()["content"])
        assertEquals(1, ready.report.conflicts.size)
    }

    @Test
    fun `memory references unavailable in final roleplay set are removed`() {
        val backup = rows(MemoryPortableGroup.MEMORIES, mapOf(
            "memories" to listOf(memory("memory", "Text", "missing-world")),
            "memory_worlds" to listOf(linkedMapOf("memory_id" to "memory", "world_id" to "missing-world"))
        ))
        val ready = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES,
            rows(MemoryPortableGroup.MEMORIES),
            backup,
            PortableRestoreMode.REPLACE,
            MemoryReferenceIds(worlds = emptySet())
        ) as MemoryCategoryPlanner.Result.Ready

        assertEquals(null, ready.rows.tables.getValue("memories").single()["world_id"])
        assertTrue(ready.rows.tables.getValue("memory_worlds").isEmpty())
        assertEquals(2, ready.report.clearedReferences)
    }

    @Test
    fun `model rule links require both stable owners`() {
        val backup = rows(MemoryPortableGroup.MODEL_RULES, mapOf(
            "model_rules" to listOf(linkedMapOf("rule_id" to "rule", "text" to "Text")),
            "model_rule_tag_links" to listOf(linkedMapOf("rule_id" to "rule", "tag_id" to "missing"))
        ))
        val ready = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MODEL_RULES,
            rows(MemoryPortableGroup.MODEL_RULES),
            backup,
            PortableRestoreMode.REPLACE
        ) as MemoryCategoryPlanner.Result.Ready
        assertTrue(ready.rows.tables.getValue("model_rule_tag_links").isEmpty())
    }

    @Test
    fun `row codec round trips values without changing identities`() {
        val original = rows(MemoryPortableGroup.MODEL_RULES, mapOf(
            "model_rules" to listOf(linkedMapOf(
                "rule_id" to "rule", "text" to "Text", "updated_at" to null, "count" to 4L
            ))
        ))
        assertEquals(
            original,
            MemoryPortableRowFormat.parse(
                MemoryPortableRowFormat.toJson(MemoryPortableGroup.MODEL_RULES, original),
                MemoryPortableGroup.MODEL_RULES
            )
        )
    }

    private fun rows(
        group: MemoryPortableGroup,
        replacements: Map<String, List<Map<String, Any?>>> = emptyMap()
    ) = MemoryPortableRows(MemoryPortableRowFormat.specs(group).associate { spec ->
        spec.table to replacements[spec.table].orEmpty()
    })

    private fun memory(id: String, content: String, world: String? = null) = linkedMapOf<String, Any?>(
        "memory_id" to id,
        "scope" to "global",
        "content" to content,
        "world_id" to world
    )
}
