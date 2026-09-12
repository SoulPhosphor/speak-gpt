/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRowFormat
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRows

/** Pure composition for the one physical companion_memory.db participant. */
object CompanionMemoryRestorePlanner {
    data class Input(
        val current: MemorySharedRestoreRows,
        val identitiesSelected: Boolean,
        val memoriesSelected: Boolean,
        val modelRulesSelected: Boolean,
        val finalRoleplayTables: Map<String, List<Map<String, Any?>>>? = null,
        val finalMemories: MemoryPortableRows? = null,
        val finalModelRules: MemoryPortableRows? = null,
        val finalIdentityReferences: MemoryReferenceIds = MemoryReferenceIds()
    )

    data class Plan(
        val current: MemorySharedRestoreRows,
        val desired: MemorySharedRestoreRows,
        val affectedTables: Set<String>
    )

    fun plan(input: Input): Plan? {
        if (!MemorySharedRestoreRowFormat.valid(input.current)) return null
        if (input.identitiesSelected != (input.finalRoleplayTables != null)) return null
        if (input.memoriesSelected != (input.finalMemories != null)) return null
        if (input.modelRulesSelected != (input.finalModelRules != null)) return null
        input.finalMemories?.let {
            if (!MemoryPortableRowFormat.valid(MemoryPortableGroup.MEMORIES, it)) return null
        }
        input.finalModelRules?.let {
            if (!MemoryPortableRowFormat.valid(MemoryPortableGroup.MODEL_RULES, it)) return null
        }
        input.finalRoleplayTables?.let {
            if (it.keys != CompanionBackupFormat.ROLEPLAY_TABLES.toSet()) return null
        }

        val tables = copyTables(input.current.tables)
        val affected = LinkedHashSet<String>()

        if (input.identitiesSelected) {
            affected.addAll(MemorySharedRestoreRowFormat.identityTables)
            // Approved identity replacement cleanup also changes memory links,
            // identity mirror columns, transcript context, memory tag links,
            // and the four active identity selections in app_state. Memory
            // content and every non-identity memory relationship are retained.
            affected.addAll(MemorySharedRestoreRowFormat.memoryTables)
            affected.addAll(MemorySharedRestoreRowFormat.memoryAuxiliaryTables)
            affected.add("app_state")
            replaceRoleplay(tables, input.finalRoleplayTables!!)
        }

        var memories = input.finalMemories ?: memoryRows(input.current)
        if (input.identitiesSelected) {
            memories = cleanIdentityReferences(memories, input.finalIdentityReferences)
                ?: return null
        }
        if (input.memoriesSelected || input.identitiesSelected) {
            affected.addAll(MemorySharedRestoreRowFormat.memoryTables)
            affected.addAll(MemorySharedRestoreRowFormat.memoryAuxiliaryTables)
            replaceMemoryRows(tables, memories)
        }

        if (input.memoriesSelected) {
            // These rows are device-local derivatives of the selected Memory
            // records. Successful Memory replacement intentionally rebuilds
            // them later instead of attaching stale state to restored rows.
            tables["generated_pending_drafts"] = emptyList()
            tables["embeddings"] = emptyList()
            tables["import_conflicts"] = emptyList()
            tables["injection_cooldowns"] = tables.getValue("injection_cooldowns")
                .filterNot { it["source_type"] == "memory" }
                .map(::copyRow)
        }

        if (input.identitiesSelected) {
            tables["app_state"] = tables.getValue("app_state").map { source ->
                copyRow(source).apply {
                    clearMissing("active_companion_id", input.finalIdentityReferences.companions)
                    clearMissing("active_world_id", input.finalIdentityReferences.worlds)
                    clearMissing(
                        "active_roleplay_character_id",
                        input.finalIdentityReferences.roleplayCharacters
                    )
                    clearMissing("active_user_persona_id", input.finalIdentityReferences.userPersonas)
                }
            }
        }

        if (input.modelRulesSelected) {
            affected.addAll(MemorySharedRestoreRowFormat.modelRuleTables)
            replaceGroup(tables, MemoryPortableGroup.MODEL_RULES, input.finalModelRules!!)
        }

        val desired = MemorySharedRestoreRows(tables)
        if (affected.isEmpty() || !MemorySharedRestoreRowFormat.valid(desired)) return null
        return Plan(input.current, desired, affected)
    }

    fun roleplayTables(rows: MemorySharedRestoreRows): Map<String, List<Map<String, Any?>>> =
        CompanionBackupFormat.ROLEPLAY_TABLES.associateTo(LinkedHashMap()) { table ->
            table to rows.tables.getValue(table).filter { row ->
                table != "rp_tag_links" || row["target_type"] != "memory"
            }.map(::copyRow)
        }

    fun memoryRows(rows: MemorySharedRestoreRows): MemoryPortableRows = MemoryPortableRows(
        MemoryPortableRowFormat.memorySpecs.associateTo(LinkedHashMap()) { spec ->
            spec.table to rows.tables.getValue(spec.table).filter { row ->
                when (spec.table) {
                    "rp_tag_links" -> row["target_type"] == "memory"
                    "deleted_ids" -> (row["record_type"] as? String) in memoryTombstoneTypes
                    else -> true
                }
            }.map(::copyRow)
        }
    )

    fun modelRuleRows(rows: MemorySharedRestoreRows): MemoryPortableRows = MemoryPortableRows(
        MemoryPortableRowFormat.modelRuleSpecs.associateTo(LinkedHashMap()) { spec ->
            spec.table to rows.tables.getValue(spec.table).map(::copyRow)
        }
    )

    private fun replaceRoleplay(
        tables: MutableMap<String, List<Map<String, Any?>>>,
        roleplay: Map<String, List<Map<String, Any?>>>
    ) {
        for (table in CompanionBackupFormat.ROLEPLAY_TABLES) {
            if (table == "rp_tag_links") {
                val memoryLinks = tables.getValue(table).filter { it["target_type"] == "memory" }
                tables[table] = roleplay.getValue(table).map(::copyRow) + memoryLinks.map(::copyRow)
            } else {
                tables[table] = roleplay.getValue(table).map(::copyRow)
            }
        }
    }

    private fun replaceMemoryRows(
        tables: MutableMap<String, List<Map<String, Any?>>>,
        memories: MemoryPortableRows
    ) {
        for (spec in MemoryPortableRowFormat.memorySpecs) {
            when (spec.table) {
                "rp_tag_links" -> {
                    val identityLinks = tables.getValue(spec.table)
                        .filter { it["target_type"] != "memory" }
                    tables[spec.table] = identityLinks.map(::copyRow) +
                        memories.tables.getValue(spec.table).map(::copyRow)
                }
                "deleted_ids" -> {
                    val unrelated = tables.getValue(spec.table).filter {
                        (it["record_type"] as? String) !in memoryTombstoneTypes
                    }
                    tables[spec.table] = unrelated.map(::copyRow) +
                        memories.tables.getValue(spec.table).map(::copyRow)
                }
                else -> tables[spec.table] = memories.tables.getValue(spec.table).map(::copyRow)
            }
        }
    }

    private fun replaceGroup(
        tables: MutableMap<String, List<Map<String, Any?>>>,
        group: MemoryPortableGroup,
        rows: MemoryPortableRows
    ) {
        for (spec in MemoryPortableRowFormat.specs(group)) {
            tables[spec.table] = rows.tables.getValue(spec.table).map(::copyRow)
        }
    }

    private fun cleanIdentityReferences(
        source: MemoryPortableRows,
        references: MemoryReferenceIds
    ): MemoryPortableRows? {
        if (!MemoryPortableRowFormat.valid(MemoryPortableGroup.MEMORIES, source)) return null
        val tables = source.tables.mapValuesTo(LinkedHashMap()) { (_, rows) ->
            rows.map(::copyRow)
        }
        tables["memory_companions"] = tables.getValue("memory_companions")
            .filter { (it["companion_id"] as? String) in references.companions }
        tables["memory_worlds"] = tables.getValue("memory_worlds")
            .filter { (it["world_id"] as? String) in references.worlds }
        tables["memory_campaigns"] = tables.getValue("memory_campaigns")
            .filter { (it["campaign_id"] as? String) in references.campaigns }
        tables["memory_roleplay_characters"] = tables.getValue("memory_roleplay_characters")
            .filter {
                (it["roleplay_character_id"] as? String) in references.roleplayCharacters
            }
        tables["memories"] = tables.getValue("memories").map { sourceRow ->
            copyRow(sourceRow).apply {
                clearMissing("world_id", references.worlds)
                clearMissing("campaign_id", references.campaigns)
                clearMissing("roleplay_character_id", references.roleplayCharacters)
            }
        }
        tables["transcripts"] = tables.getValue("transcripts").map { sourceRow ->
            copyRow(sourceRow).apply {
                clearMissing("companion_id", references.companions)
                clearMissing("world_id", references.worlds)
                clearMissing("roleplay_character_id", references.roleplayCharacters)
                clearMissing("user_persona_id", references.userPersonas)
            }
        }
        tables["rp_tag_links"] = tables.getValue("rp_tag_links")
            .filter { (it["tag_id"] as? String) in references.roleplayTags }
        return MemoryPortableRows(tables).takeIf {
            MemoryPortableRowFormat.valid(MemoryPortableGroup.MEMORIES, it)
        }
    }

    private fun MutableMap<String, Any?>.clearMissing(column: String, valid: Set<String>) {
        val id = this[column] as? String
        if (!id.isNullOrBlank() && id !in valid) this[column] = null
    }

    private fun copyTables(
        source: Map<String, List<Map<String, Any?>>>
    ): LinkedHashMap<String, List<Map<String, Any?>>> = source.mapValuesTo(LinkedHashMap()) {
        (_, rows) -> rows.map(::copyRow)
    }

    private fun copyRow(source: Map<String, Any?>): LinkedHashMap<String, Any?> =
        LinkedHashMap(source)

    private val memoryTombstoneTypes = setOf("memory", "entity", "project")
}
