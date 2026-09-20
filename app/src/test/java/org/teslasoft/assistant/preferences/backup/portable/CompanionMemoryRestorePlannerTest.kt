package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreBlob
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRowFormat
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRows

class CompanionMemoryRestorePlannerTest {
    @Test
    fun `empty Memory Replace removes only Memory tombstones`() {
        val current = state(mapOf(
            "deleted_ids" to listOf(
                tombstone("memory", "m-1"),
                tombstone("entity", "e-1"),
                tombstone("project", "p-1"),
                tombstone("roleplay_character", "r-1")
            )
        ))

        val plan = CompanionMemoryRestorePlanner.plan(
            CompanionMemoryRestorePlanner.Input(
                current = current,
                identitiesSelected = false,
                memoriesSelected = true,
                modelRulesSelected = false,
                finalMemories = memoryRows()
            )
        )!!

        assertEquals(
            listOf(tombstone("roleplay_character", "r-1")),
            plan.desired.tables.getValue("deleted_ids")
        )
        assertEquals(4, current.tables.getValue("deleted_ids").size)
    }

    @Test
    fun `identity cleanup changes only approved Memory references and preserves rollback rows`() {
        val embedding = MemorySharedRestoreBlob("AQID")
        val current = state(mapOf(
            "app_state" to listOf(linkedMapOf(
                "id" to 1L,
                "active_companion_id" to "old-companion",
                "active_world_id" to "old-world",
                "active_roleplay_character_id" to "old-character",
                "active_user_persona_id" to "old-persona"
            )),
            "companions" to listOf(linkedMapOf("companion_id" to "old-companion")),
            "worlds" to listOf(linkedMapOf("world_id" to "old-world")),
            "roleplay_characters" to listOf(linkedMapOf("roleplay_character_id" to "old-character")),
            "user_personas" to listOf(linkedMapOf("persona_id" to "old-persona")),
            "rp_tags" to listOf(linkedMapOf("tag_id" to "old-tag")),
            "entities" to listOf(linkedMapOf("entity_id" to "entity")),
            "projects" to listOf(linkedMapOf("project_id" to "project")),
            "memories" to listOf(linkedMapOf(
                "memory_id" to "memory",
                "content" to "kept",
                "world_id" to "old-world",
                "campaign_id" to null,
                "roleplay_character_id" to "old-character"
            )),
            "memory_companions" to listOf(linkedMapOf(
                "memory_id" to "memory", "companion_id" to "old-companion"
            )),
            "memory_entities" to listOf(linkedMapOf(
                "memory_id" to "memory", "entity_id" to "entity"
            )),
            "memory_worlds" to listOf(linkedMapOf(
                "memory_id" to "memory", "world_id" to "old-world"
            )),
            "memory_roleplay_characters" to listOf(linkedMapOf(
                "memory_id" to "memory", "roleplay_character_id" to "old-character"
            )),
            "memory_projects" to listOf(linkedMapOf(
                "memory_id" to "memory", "project_id" to "project"
            )),
            "transcripts" to listOf(linkedMapOf(
                "transcript_id" to "transcript",
                "content" to "kept transcript",
                "companion_id" to "old-companion",
                "world_id" to "old-world",
                "roleplay_character_id" to "old-character",
                "user_persona_id" to "old-persona"
            )),
            "rp_tag_links" to listOf(linkedMapOf(
                "tag_id" to "old-tag", "target_type" to "memory", "target_id" to "memory"
            )),
            "generated_pending_drafts" to listOf(linkedMapOf(
                "memory_id" to "memory", "created_at" to "now"
            )),
            "embeddings" to listOf(linkedMapOf(
                "memory_id" to "memory", "embedding_model" to "model", "vector" to embedding
            )),
            "import_conflicts" to listOf(linkedMapOf("conflict_id" to "conflict")),
            "injection_cooldowns" to listOf(linkedMapOf(
                "chat_id" to "chat", "source_type" to "memory", "entry_id" to "memory"
            ))
        ))

        val plan = CompanionMemoryRestorePlanner.plan(
            CompanionMemoryRestorePlanner.Input(
                current = current,
                identitiesSelected = true,
                memoriesSelected = false,
                modelRulesSelected = false,
                finalRoleplayTables = emptyRoleplay(),
                finalIdentityReferences = MemoryReferenceIds()
            )
        )!!
        val desired = plan.desired.tables

        assertEquals("kept", desired.getValue("memories").single()["content"])
        assertNull(desired.getValue("memories").single()["world_id"])
        assertNull(desired.getValue("memories").single()["roleplay_character_id"])
        assertTrue(desired.getValue("memory_companions").isEmpty())
        assertTrue(desired.getValue("memory_worlds").isEmpty())
        assertTrue(desired.getValue("memory_roleplay_characters").isEmpty())
        assertEquals(current.tables.getValue("memory_entities"), desired.getValue("memory_entities"))
        assertEquals(current.tables.getValue("memory_projects"), desired.getValue("memory_projects"))
        assertEquals("kept transcript", desired.getValue("transcripts").single()["content"])
        assertNull(desired.getValue("transcripts").single()["companion_id"])
        assertTrue(desired.getValue("rp_tag_links").isEmpty())
        assertTrue(desired.getValue("app_state").single().values.drop(1).all { it == null })
        assertEquals(current.tables.getValue("generated_pending_drafts"), desired.getValue("generated_pending_drafts"))
        assertEquals(current.tables.getValue("embeddings"), desired.getValue("embeddings"))
        assertEquals(current.tables.getValue("import_conflicts"), desired.getValue("import_conflicts"))
        assertEquals(current.tables.getValue("injection_cooldowns"), desired.getValue("injection_cooldowns"))
        assertEquals(current, plan.current)
    }

    @Test
    fun `combined identity and Memory final rows retain new relationships`() {
        val roleplay = emptyRoleplay().toMutableMap().apply {
            this["companions"] = listOf(linkedMapOf("companion_id" to "new-companion"))
        }
        val memories = memoryRows(mapOf(
            "memories" to listOf(linkedMapOf("memory_id" to "new-memory")),
            "memory_companions" to listOf(linkedMapOf(
                "memory_id" to "new-memory", "companion_id" to "new-companion"
            ))
        ))

        val plan = CompanionMemoryRestorePlanner.plan(
            CompanionMemoryRestorePlanner.Input(
                current = state(),
                identitiesSelected = true,
                memoriesSelected = true,
                modelRulesSelected = false,
                finalRoleplayTables = roleplay,
                finalMemories = memories,
                finalIdentityReferences = MemoryReferenceIds(companions = setOf("new-companion"))
            )
        )!!

        assertEquals("new-companion", plan.desired.tables.getValue("companions").single()["companion_id"])
        assertEquals(
            "new-companion",
            plan.desired.tables.getValue("memory_companions").single()["companion_id"]
        )
        assertTrue("app_state" in plan.affectedTables)
        assertTrue("memories" in plan.affectedTables)
    }

    @Test
    fun `shared snapshot codec preserves blobs and every table`() {
        val original = state(mapOf(
            "embeddings" to listOf(linkedMapOf(
                "memory_id" to "memory",
                "embedding_model" to "model",
                "vector" to MemorySharedRestoreBlob("AAEC/w==")
            ))
        ))

        val parsed = MemorySharedRestoreRowFormat.parse(
            MemorySharedRestoreRowFormat.toJson(original)
        )

        assertEquals(original, parsed)
        assertEquals(MemorySharedRestoreRowFormat.tableNames, parsed!!.tables.keys)
    }

    @Test
    fun `Memory Merge with overlapping tombstones is idempotent`() {
        val current = memoryRows(mapOf(
            "deleted_ids" to listOf(tombstone("memory", "same"))
        ))
        val incoming = memoryRows(mapOf(
            "deleted_ids" to listOf(tombstone("memory", "same"), tombstone("entity", "new"))
        ))
        val first = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES,
            current,
            incoming,
            PortableRestoreMode.MERGE
        ) as MemoryCategoryPlanner.Result.Ready
        val second = MemoryCategoryPlanner.plan(
            MemoryPortableGroup.MEMORIES,
            first.rows,
            incoming,
            PortableRestoreMode.MERGE
        ) as MemoryCategoryPlanner.Result.Ready

        assertEquals(2, first.rows.tables.getValue("deleted_ids").size)
        assertEquals(first.rows, second.rows)
        assertFalse(second.report.conflicts.isNotEmpty())
    }

    private fun state(
        replacements: Map<String, List<Map<String, Any?>>> = emptyMap()
    ): MemorySharedRestoreRows {
        val base = MemorySharedRestoreRowFormat.empty().tables
        return MemorySharedRestoreRows(base.mapValuesTo(LinkedHashMap()) { (table, rows) ->
            replacements[table] ?: rows
        })
    }

    private fun memoryRows(
        replacements: Map<String, List<Map<String, Any?>>> = emptyMap()
    ) = MemoryPortableRows(MemoryPortableRowFormat.specs(MemoryPortableGroup.MEMORIES)
        .associateTo(LinkedHashMap()) { spec -> spec.table to replacements[spec.table].orEmpty() })

    private fun emptyRoleplay(): Map<String, List<Map<String, Any?>>> =
        CompanionBackupFormat.ROLEPLAY_TABLES.associateTo(LinkedHashMap()) { it to emptyList() }

    private fun tombstone(type: String, id: String) = linkedMapOf<String, Any?>(
        "record_type" to type,
        "record_id" to id,
        "deleted_at" to "now"
    )
}
