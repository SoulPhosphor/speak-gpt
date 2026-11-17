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
    fun `replacing with a backup made before Inside Joke keeps the type`() {
        val joke = linkedMapOf<String, Any?>(
            "type_id" to "mtype-inside-joke", "name" to "Inside Joke", "created_at" to "2026-11-10T08:00:00Z"
        )
        val fact = linkedMapOf<String, Any?>(
            "type_id" to "mtype-fact", "name" to "Fact", "created_at" to "2026-08-01T00:00:00Z"
        )
        val current = rows(MemoryPortableGroup.MEMORIES, mapOf("memory_types" to listOf(fact, joke)))
        val oldBackup = rows(MemoryPortableGroup.MEMORIES, mapOf(
            "memory_types" to listOf(fact),
            "memories" to listOf(memory("m", "Text"))
        ))
        val ready = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES, current, oldBackup, PortableRestoreMode.REPLACE
        ) as MemoryCategoryPlanner.Result.Ready

        val types = ready.rows.tables.getValue("memory_types")
        assertEquals(listOf("mtype-fact", "mtype-inside-joke"), types.map { it["type_id"] })
        assertEquals(joke, types.last())
    }

    @Test
    fun `a backup that has Inside Joke is restored as it is`() {
        val fromBackup = linkedMapOf<String, Any?>(
            "type_id" to "mtype-inside-joke", "name" to "Inside Joke", "created_at" to "2026-11-11T00:00:00Z"
        )
        val ready = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES,
            rows(MemoryPortableGroup.MEMORIES),
            rows(MemoryPortableGroup.MEMORIES, mapOf("memory_types" to listOf(fromBackup))),
            PortableRestoreMode.REPLACE
        ) as MemoryCategoryPlanner.Result.Ready
        assertEquals(listOf(fromBackup), ready.rows.tables.getValue("memory_types"))
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

    @Test
    fun `sources travel with their memory and orphans are dropped on merge`() {
        val source = linkedMapOf<String, Any?>(
            "source_id" to "src-1", "memory_id" to "new", "kind" to "chat_message",
            "chat_id" to "chat-1", "message_id" to "msg-1",
            "attached_at" to "2026-11-16T00:00:00Z", "attached_by" to "memory_assistant"
        )
        val orphan = LinkedHashMap(source).apply { put("source_id", "src-2"); put("memory_id", "gone") }
        val backup = rows(MemoryPortableGroup.MEMORIES, mapOf(
            "memories" to listOf(memory("new", "New")),
            "memory_sources" to listOf(source, orphan)
        ))
        val ready = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES, rows(MemoryPortableGroup.MEMORIES), backup, PortableRestoreMode.MERGE
        ) as MemoryCategoryPlanner.Result.Ready
        assertEquals(listOf("src-1"), ready.rows.tables.getValue("memory_sources").map { it["source_id"] })
    }

    @Test
    fun `a merge keeps the current handling preferences even when none are saved`() {
        val off = listOf(linkedMapOf<String, Any?>("id" to 1L, "delete_moves_to_archived" to 0L))
        val backup = rows(MemoryPortableGroup.MEMORIES, mapOf("memory_handling_preferences" to off))
        val merged = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES, rows(MemoryPortableGroup.MEMORIES), backup, PortableRestoreMode.MERGE
        ) as MemoryCategoryPlanner.Result.Ready
        assertTrue(merged.rows.tables.getValue("memory_handling_preferences").isEmpty())
        assertTrue(merged.report.conflicts.isEmpty())

        val replaced = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES, rows(MemoryPortableGroup.MEMORIES), backup, PortableRestoreMode.REPLACE
        ) as MemoryCategoryPlanner.Result.Ready
        assertEquals(off, replaced.rows.tables.getValue("memory_handling_preferences"))
    }

    @Test
    fun `readable memory rows from before sources parse with none`() {
        val full = rows(MemoryPortableGroup.MEMORIES, mapOf("memories" to listOf(memory("m", "Text"))))
        val json = org.json.JSONObject(MemoryPortableRowFormat.toJson(MemoryPortableGroup.MEMORIES, full))
        json.getJSONObject("tables").remove("memory_sources")
        val parsed = MemoryPortableRowFormat.parse(json.toString(), MemoryPortableGroup.MEMORIES)!!
        assertTrue(parsed.tables.getValue("memory_sources").isEmpty())
        assertEquals(1, parsed.tables.getValue("memories").size)
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
