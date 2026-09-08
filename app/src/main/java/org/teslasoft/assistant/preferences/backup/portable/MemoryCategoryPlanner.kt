/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows

data class MemoryReferenceIds(
    val companions: Set<String> = emptySet(),
    val worlds: Set<String> = emptySet(),
    val campaigns: Set<String> = emptySet(),
    val roleplayCharacters: Set<String> = emptySet(),
    val userPersonas: Set<String> = emptySet(),
    val roleplayTags: Set<String> = emptySet()
)

object MemoryCategoryPlanner {
    data class Conflict(val table: String, val identity: String)
    data class Report(val conflicts: List<Conflict>, val clearedReferences: Int)

    sealed class Result {
        data class Ready(val rows: MemoryPortableRows, val report: Report) : Result()
        data object Invalid : Result()
    }

    fun plan(
        group: MemoryPortableGroup,
        current: MemoryPortableRows,
        backup: MemoryPortableRows,
        mode: PortableRestoreMode,
        references: MemoryReferenceIds = MemoryReferenceIds()
    ): Result {
        if (!MemoryPortableRowFormat.valid(group, current) ||
            !MemoryPortableRowFormat.valid(group, backup)
        ) return Result.Invalid
        val conflicts = ArrayList<Conflict>()
        val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (spec in MemoryPortableRowFormat.specs(group)) {
            val currentRows = current.tables.getValue(spec.table)
            val backupRows = backup.tables.getValue(spec.table)
            if (mode == PortableRestoreMode.REPLACE) {
                tables[spec.table] = backupRows.map { LinkedHashMap(it) }
                continue
            }
            val currentById = currentRows.associateBy { MemoryPortableRowFormat.identity(spec, it)!! }
            val result = currentRows.map { LinkedHashMap(it) }.toMutableList()
            for (row in backupRows) {
                val id = MemoryPortableRowFormat.identity(spec, row)!!
                val existing = currentById[id]
                if (existing == null) result.add(LinkedHashMap(row))
                else if (existing != row) conflicts.add(Conflict(spec.table, id))
            }
            tables[spec.table] = result
        }
        var cleared = 0
        if (group == MemoryPortableGroup.MEMORIES) {
            val memoryIds = ids(tables, "memories", "memory_id")
            val entityIds = ids(tables, "entities", "entity_id")
            val projectIds = ids(tables, "projects", "project_id")
            val typeIds = ids(tables, "memory_types", "type_id")
            tables["memories"] = tables.getValue("memories").map { row -> LinkedHashMap(row).apply {
                clearMissing("type_id", typeIds) { cleared++ }
                clearMissing("world_id", references.worlds) { cleared++ }
                clearMissing("roleplay_character_id", references.roleplayCharacters) { cleared++ }
                clearMissing("campaign_id", references.campaigns) { cleared++ }
                clearMissing("project_id", projectIds) { cleared++ }
                clearMissing("supersedes", memoryIds) { cleared++ }
            } }
            cleared += filterJoin(tables, "memory_companions", "memory_id", memoryIds, "companion_id", references.companions)
            cleared += filterJoin(tables, "memory_entities", "memory_id", memoryIds, "entity_id", entityIds)
            cleared += filterJoin(tables, "memory_worlds", "memory_id", memoryIds, "world_id", references.worlds)
            cleared += filterJoin(tables, "memory_campaigns", "memory_id", memoryIds, "campaign_id", references.campaigns)
            cleared += filterJoin(tables, "memory_roleplay_characters", "memory_id", memoryIds, "roleplay_character_id", references.roleplayCharacters)
            cleared += filterJoin(tables, "memory_projects", "memory_id", memoryIds, "project_id", projectIds)
            for (table in listOf("change_log", "memory_possible_match_hints")) {
                val keys = if (table == "change_log") listOf("memory_id")
                    else listOf("draft_memory_id", "existing_memory_id")
                val before = tables.getValue(table)
                tables[table] = before.filter { row -> keys.all { (row[it] as? String) in memoryIds } }
                cleared += before.size - tables.getValue(table).size
            }
            val supersessions = tables.getValue("memory_supersessions")
            tables["memory_supersessions"] = supersessions.filter { row ->
                (row["new_memory_id"] as? String) in memoryIds &&
                    (row["old_memory_id"] as? String) in memoryIds
            }
            cleared += supersessions.size - tables.getValue("memory_supersessions").size
            tables["transcripts"] = tables.getValue("transcripts").map { row -> LinkedHashMap(row).apply {
                clearMissing("companion_id", references.companions) { cleared++ }
                clearMissing("world_id", references.worlds) { cleared++ }
                clearMissing("roleplay_character_id", references.roleplayCharacters) { cleared++ }
                clearMissing("user_persona_id", references.userPersonas) { cleared++ }
                clearMissing("project_id", projectIds) { cleared++ }
            } }
            val tagLinks = tables.getValue("rp_tag_links")
            tables["rp_tag_links"] = tagLinks.filter { row ->
                (row["tag_id"] as? String) in references.roleplayTags &&
                    (row["target_id"] as? String) in memoryIds
            }
            cleared += tagLinks.size - tables.getValue("rp_tag_links").size
        } else {
            val rules = ids(tables, "model_rules", "rule_id")
            val tags = ids(tables, "model_rule_tags", "tag_id")
            val links = tables.getValue("model_rule_tag_links")
            tables["model_rule_tag_links"] = links.filter { row ->
                (row["rule_id"] as? String) in rules && (row["tag_id"] as? String) in tags
            }
            cleared += links.size - tables.getValue("model_rule_tag_links").size
        }
        val result = MemoryPortableRows(tables)
        if (!MemoryPortableRowFormat.valid(group, result)) return Result.Invalid
        return Result.Ready(result, Report(conflicts, cleared))
    }

    private fun ids(
        tables: Map<String, List<Map<String, Any?>>>, table: String, column: String
    ): Set<String> = tables.getValue(table).mapNotNull { it[column] as? String }.toSet()

    private fun filterJoin(
        tables: MutableMap<String, List<Map<String, Any?>>>,
        table: String,
        leftColumn: String,
        left: Set<String>,
        rightColumn: String,
        right: Set<String>
    ): Int {
        val before = tables.getValue(table)
        tables[table] = before.filter { row ->
            (row[leftColumn] as? String) in left && (row[rightColumn] as? String) in right
        }
        return before.size - tables.getValue(table).size
    }

    private fun MutableMap<String, Any?>.clearMissing(
        column: String, valid: Set<String>, cleared: () -> Unit
    ) {
        val id = this[column] as? String
        if (!id.isNullOrBlank() && id !in valid) {
            this[column] = null
            cleared()
        }
    }
}
