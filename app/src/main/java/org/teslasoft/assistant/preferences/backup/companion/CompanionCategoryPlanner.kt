/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.companion

import org.json.JSONArray
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreCategory
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreMode

/**
 * Builds one complete Companion/Roleplay manifest from independently selected
 * categories. Unselected sections come from [current]. Selected Replace
 * sections come from [backup]; selected Merge sections use stable identity,
 * keep the current item on a different-content collision, and never mint an
 * identity. The resulting complete manifest is applied by the existing
 * journaled restore manager in one database/settings transaction.
 */
object CompanionCategoryPlanner {
    data class Selection(
        val category: PortableRestoreCategory,
        val mode: PortableRestoreMode
    )

    data class Conflict(
        val category: PortableRestoreCategory,
        val itemId: String,
        val displayName: String
    )

    data class Report(
        val conflicts: List<Conflict>,
        val clearedReferences: Int
    )

    enum class Rejection {
        EMPTY_SELECTION,
        UNSUPPORTED_CATEGORY,
        DUPLICATE_SELECTION,
        DUPLICATE_ID,
        MISSING_ID,
        INVALID_REFERENCE_DATA
    }

    sealed class Result {
        data class Ready(
            val manifest: CompanionBackupManifest,
            val report: Report
        ) : Result()

        data class Rejected(val reason: Rejection) : Result()
    }

    private val supported = setOf(
        PortableRestoreCategory.COMPANIONS,
        PortableRestoreCategory.GLAMOURS,
        PortableRestoreCategory.ROLEPLAY,
        PortableRestoreCategory.ACTIVATION_PROMPTS,
        PortableRestoreCategory.SYSTEM_PROMPTS
    )

    private val companionTables = setOf("companions", "companion_name_history")
    private val glamourTables = setOf("user_personas")
    private val roleplayTables = CompanionBackupFormat.ROLEPLAY_TABLES.toSet() -
        companionTables - glamourTables

    fun plan(
        current: CompanionBackupManifest,
        backup: CompanionBackupManifest,
        selections: List<Selection>
    ): Result {
        if (selections.isEmpty()) return Result.Rejected(Rejection.EMPTY_SELECTION)
        if (selections.any { it.category !in supported }) {
            return Result.Rejected(Rejection.UNSUPPORTED_CATEGORY)
        }
        if (selections.map { it.category }.distinct().size != selections.size) {
            return Result.Rejected(Rejection.DUPLICATE_SELECTION)
        }
        validateManifest(current)?.let { return Result.Rejected(it) }
        validateManifest(backup)?.let { return Result.Rejected(it) }

        val modes = selections.associate { it.category to it.mode }
        val conflicts = ArrayList<Conflict>()

        val activation = choose(
            current.activationPrompts,
            backup.activationPrompts,
            modes[PortableRestoreCategory.ACTIVATION_PROMPTS],
            PortableRestoreCategory.ACTIVATION_PROMPTS,
            ActivationPromptEntry::id,
            ActivationPromptEntry::label,
            conflicts
        )
        val system = choose(
            current.systemPrompts,
            backup.systemPrompts,
            modes[PortableRestoreCategory.SYSTEM_PROMPTS],
            PortableRestoreCategory.SYSTEM_PROMPTS,
            SystemPromptEntry::id,
            SystemPromptEntry::title,
            conflicts
        )
        val profiles = choose(
            current.companionProfiles,
            backup.companionProfiles,
            modes[PortableRestoreCategory.COMPANIONS],
            PortableRestoreCategory.COMPANIONS,
            CompanionProfileEntry::id,
            CompanionProfileEntry::label,
            conflicts
        )

        val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (table in CompanionBackupFormat.ROLEPLAY_TABLES) {
            val category = when (table) {
                in companionTables -> PortableRestoreCategory.COMPANIONS
                in glamourTables -> PortableRestoreCategory.GLAMOURS
                else -> PortableRestoreCategory.ROLEPLAY
            }
            val identity = tableIdentity(table)
            tables[table] = chooseRows(
                current.roleplayTables[table].orEmpty(),
                backup.roleplayTables[table].orEmpty(),
                modes[category],
                category,
                table,
                identity,
                conflicts
            )
        }

        val activationIds = activation.mapTo(HashSet()) { it.id }
        var cleared = 0
        val fixedProfiles = profiles.map { profile ->
            if (profile.activationPromptId.isNotBlank() && profile.activationPromptId !in activationIds) {
                cleared++
                profile.copy(activationPromptId = "")
            } else profile
        }
        val fixedTables = sanitizeReferences(tables) { cleared++ }
            ?: return Result.Rejected(Rejection.INVALID_REFERENCE_DATA)

        val selectedSystemId = when {
            PortableRestoreCategory.SYSTEM_PROMPTS !in modes -> current.selectedSystemPromptId
            modes[PortableRestoreCategory.SYSTEM_PROMPTS] == PortableRestoreMode.REPLACE ->
                backup.selectedSystemPromptId
            current.selectedSystemPromptId.isNotBlank() -> current.selectedSystemPromptId
            else -> backup.selectedSystemPromptId
        }.takeIf { id -> system.any { it.id == id } }.orEmpty()

        val referencedImages = collectReferencedImages(fixedProfiles, fixedTables)
        val imagesByHash = LinkedHashMap<String, CompanionBackupImage>()
        current.images.forEach { imagesByHash.putIfAbsent(it.hash, it) }
        backup.images.forEach { imagesByHash.putIfAbsent(it.hash, it) }
        val images = referencedImages.mapNotNull(imagesByHash::get)

        return Result.Ready(
            CompanionBackupManifest(
                formatVersion = CompanionBackupFormat.FORMAT_VERSION,
                appVersion = backup.appVersion,
                exportedAt = backup.exportedAt,
                companionProfiles = fixedProfiles,
                activationPrompts = activation,
                systemPrompts = system,
                selectedSystemPromptId = selectedSystemId,
                roleplayTables = fixedTables,
                images = images
            ),
            Report(conflicts, cleared)
        )
    }

    private fun validateManifest(manifest: CompanionBackupManifest): Rejection? {
        fun <T> validate(items: List<T>, id: (T) -> String): Rejection? {
            val ids = items.map(id)
            if (ids.any(String::isBlank)) return Rejection.MISSING_ID
            if (ids.size != ids.distinct().size) return Rejection.DUPLICATE_ID
            return null
        }
        validate(manifest.companionProfiles, CompanionProfileEntry::id)?.let { return it }
        validate(manifest.activationPrompts, ActivationPromptEntry::id)?.let { return it }
        validate(manifest.systemPrompts, SystemPromptEntry::id)?.let { return it }
        val imageHashes = manifest.images.map(CompanionBackupImage::hash)
        if (imageHashes.size != imageHashes.distinct().size ||
            manifest.images.map(CompanionBackupImage::file).distinct().size != manifest.images.size
        ) return Rejection.DUPLICATE_ID
        for (table in CompanionBackupFormat.ROLEPLAY_TABLES) {
            val identity = tableIdentity(table)
            val ids = manifest.roleplayTables[table].orEmpty().map { identity(it) }
            if (ids.any(String::isBlank)) return Rejection.MISSING_ID
            if (ids.size != ids.distinct().size) return Rejection.DUPLICATE_ID
        }
        return null
    }

    private fun <T> choose(
        current: List<T>,
        backup: List<T>,
        mode: PortableRestoreMode?,
        category: PortableRestoreCategory,
        id: (T) -> String,
        name: (T) -> String,
        conflicts: MutableList<Conflict>
    ): List<T> {
        if (mode == null) return current
        if (mode == PortableRestoreMode.REPLACE) return backup
        val result = current.toMutableList()
        val currentById = current.associateBy(id)
        for (item in backup) {
            val existing = currentById[id(item)]
            if (existing == null) result.add(item)
            else if (existing != item) conflicts.add(Conflict(category, id(item), name(existing)))
        }
        return result
    }

    private fun chooseRows(
        current: List<Map<String, Any?>>,
        backup: List<Map<String, Any?>>,
        mode: PortableRestoreMode?,
        category: PortableRestoreCategory,
        table: String,
        identity: (Map<String, Any?>) -> String,
        conflicts: MutableList<Conflict>
    ): List<Map<String, Any?>> {
        val normalize: (Map<String, Any?>) -> Map<String, Any?> = { row ->
            if (table == "companion_name_history") row - "id" else row
        }
        if (mode == null) return current.map(normalize)
        if (mode == PortableRestoreMode.REPLACE) return backup.map(normalize)
        val result = current.map(normalize).toMutableList()
        val currentById = current.associateBy(identity)
        for (row in backup) {
            val rowId = identity(row)
            val existing = currentById[rowId]
            if (existing == null) result.add(normalize(row))
            else if (normalize(existing) != normalize(row)) {
                conflicts.add(
                    Conflict(category, rowId, displayName(table, existing, rowId))
                )
            }
        }
        return result
    }

    private fun tableIdentity(table: String): (Map<String, Any?>) -> String = when (table) {
        "companions" -> key("companion_id")
        "companion_name_history" -> composite("companion_id", "name", "effective_from")
        "user_personas" -> key("persona_id")
        "roleplay_characters" -> key("roleplay_character_id")
        "worlds" -> key("world_id")
        "campaigns" -> key("campaign_id")
        "party_members" -> key("party_member_id")
        "campaign_party_members" -> composite("campaign_id", "party_member_id")
        "card_entries" -> key("entry_id")
        "rp_tags" -> key("tag_id")
        "rp_tag_links" -> composite("tag_id", "target_type", "target_id")
        else -> { _ -> "" }
    }

    private fun key(column: String): (Map<String, Any?>) -> String = { row ->
        (row[column] as? String).orEmpty()
    }

    private fun composite(vararg columns: String): (Map<String, Any?>) -> String = { row ->
        columns.joinToString("\u0000") { (row[it] as? String).orEmpty() }
            .takeIf { value -> value.split('\u0000').all(String::isNotBlank) }.orEmpty()
    }

    private fun displayName(table: String, row: Map<String, Any?>, fallback: String): String =
        when (table) {
            "companions", "user_personas", "roleplay_characters", "worlds", "campaigns",
            "party_members", "card_entries", "rp_tags" -> row["name"] as? String
                ?: row["current_name"] as? String
            "companion_name_history" -> row["name"] as? String
            else -> null
        }.orEmpty().ifBlank { fallback }

    private fun sanitizeReferences(
        source: Map<String, List<Map<String, Any?>>>,
        onCleared: () -> Unit
    ): Map<String, List<Map<String, Any?>>>? {
        val out = source.mapValuesTo(LinkedHashMap()) { (_, rows) -> rows.map { LinkedHashMap(it) } }
        val companions = ids(out, "companions", "companion_id")
        val personas = ids(out, "user_personas", "persona_id")
        val characters = ids(out, "roleplay_characters", "roleplay_character_id")
        val worlds = ids(out, "worlds", "world_id")
        val campaigns = ids(out, "campaigns", "campaign_id")
        val party = ids(out, "party_members", "party_member_id")
        val entries = ids(out, "card_entries", "entry_id")
        val tags = ids(out, "rp_tags", "tag_id")

        out["companion_name_history"] = out["companion_name_history"].orEmpty().filter {
            (it["companion_id"] as? String) in companions
        }.also { kept -> repeat(source["companion_name_history"].orEmpty().size - kept.size) { onCleared() } }

        out["campaigns"] = out["campaigns"].orEmpty().map { row ->
            row.apply {
                clearMissing("world_id", worlds, onCleared)
                clearMissing("roleplay_character_id", characters, onCleared)
                clearMissing("companion_id", companions, onCleared)
            }
        }
        out["campaign_party_members"] = out["campaign_party_members"].orEmpty().filter {
            (it["campaign_id"] as? String) in campaigns &&
                (it["party_member_id"] as? String) in party
        }.also { kept -> repeat(source["campaign_party_members"].orEmpty().size - kept.size) { onCleared() } }

        for (row in out["worlds"].orEmpty()) {
            val sanitized = sanitizeJsonIds(row["companion_ids_json"] as? String, companions)
                ?: return null
            if (sanitized.first) onCleared()
            row["companion_ids_json"] = sanitized.second
        }
        for (row in out["roleplay_characters"].orEmpty()) {
            val sanitized = sanitizeJsonIds(row["worlds_played_json"] as? String, worlds)
                ?: return null
            if (sanitized.first) onCleared()
            row["worlds_played_json"] = sanitized.second
        }

        val validTargets = mapOf(
            "card_entry" to entries,
            "rp_character" to characters,
            "party_member" to party,
            "world" to worlds,
            "campaign" to campaigns
        )
        out["rp_tag_links"] = out["rp_tag_links"].orEmpty().filter { row ->
            val tagOk = (row["tag_id"] as? String) in tags
            val type = row["target_type"] as? String
            val target = row["target_id"] as? String
            tagOk && type != null && target != null && target in validTargets[type].orEmpty()
        }.also { kept -> repeat(source["rp_tag_links"].orEmpty().size - kept.size) { onCleared() } }

        // This archive intentionally excludes memory targets. [personas] is
        // read above so the final set is explicit for future dependency rules.
        @Suppress("UNUSED_VARIABLE") val finalPersonaIds = personas
        return out
    }

    private fun MutableMap<String, Any?>.clearMissing(
        column: String,
        valid: Set<String>,
        onCleared: () -> Unit
    ) {
        val id = this[column] as? String
        if (!id.isNullOrBlank() && id !in valid) {
            this[column] = null
            onCleared()
        }
    }

    private fun sanitizeJsonIds(value: String?, valid: Set<String>): Pair<Boolean, String>? {
        if (value.isNullOrBlank()) return false to "[]"
        return try {
            val array = JSONArray(value)
            val before = ArrayList<String>(array.length())
            repeat(array.length()) { before.add(array.getString(it)) }
            val after = before.filter(valid::contains).distinct()
            (before != after) to JSONArray(after).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun ids(
        tables: Map<String, List<Map<String, Any?>>>,
        table: String,
        column: String
    ): Set<String> = tables[table].orEmpty().mapNotNull { it[column] as? String }.toSet()

    private fun collectReferencedImages(
        profiles: List<CompanionProfileEntry>,
        tables: Map<String, List<Map<String, Any?>>>
    ): LinkedHashSet<String> = LinkedHashSet<String>().apply {
        profiles.map(CompanionProfileEntry::avatarRef).filter(String::isNotBlank).forEach(::add)
        listOf("user_personas", "roleplay_characters").forEach { table ->
            tables[table].orEmpty().mapNotNull { it["image_ref"] as? String }
                .filter(String::isNotBlank).forEach(::add)
        }
    }
}
