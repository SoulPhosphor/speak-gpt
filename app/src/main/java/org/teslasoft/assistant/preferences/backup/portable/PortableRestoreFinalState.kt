/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import java.util.Collections
import org.json.JSONArray
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows

/** A dependency read is never represented by a nullable or fabricated empty set. */
sealed interface PortableRestoreDependencyRead<out T> {
    data class Available<T>(val snapshot: T) : PortableRestoreDependencyRead<T>
    data class Unavailable(val reason: String) : PortableRestoreDependencyRead<Nothing>
}

/**
 * One immutable, inspectable description of the state a selected-category
 * restore intends to publish. All collections are defensive read-only copies;
 * participants receive the separately prepared payloads that produced these
 * projections and do not recompute dependency decisions.
 */
class PortableRestoreFinalState private constructor(
    categoryModes: Map<PortableRestoreCategory, PortableRestoreMode>,
    finalChatIds: Set<String>,
    generatedImageIdsReferencedByChats: Set<String>,
    finalIdentityIds: IdentityIds,
    identityProfileImageReferences: List<IdentityProfileImageReference>,
    identityLorebookReferences: List<IdentityLorebookReference>,
    finalLorebookIds: Set<String>,
    finalMemoryIds: Set<String>,
    finalEntityIds: Set<String>,
    finalProjectIds: Set<String>,
    memoryIdentityRelationships: List<MemoryIdentityRelationship>,
    finalProfileImageAssetHashes: Set<String>,
    finalGeneratedImageAssetIds: Set<String>,
    sourceGenerations: Map<Source, String>,
    plannedReferenceChanges: List<ReferenceChange>
) {
    val categoryModes = immutableMap(categoryModes)
    val finalChatIds = immutableSet(finalChatIds)
    val generatedImageIdsReferencedByChats = immutableSet(generatedImageIdsReferencedByChats)
    val finalIdentityIds = finalIdentityIds.immutableCopy()
    val identityProfileImageReferences = immutableList(identityProfileImageReferences)
    val identityLorebookReferences = immutableList(identityLorebookReferences)
    val finalLorebookIds = immutableSet(finalLorebookIds)
    val finalMemoryIds = immutableSet(finalMemoryIds)
    val finalEntityIds = immutableSet(finalEntityIds)
    val finalProjectIds = immutableSet(finalProjectIds)
    val memoryIdentityRelationships = immutableList(memoryIdentityRelationships)
    val finalProfileImageAssetHashes = immutableSet(finalProfileImageAssetHashes)
    val finalGeneratedImageAssetIds = immutableSet(finalGeneratedImageAssetIds)
    val sourceGenerations = immutableMap(sourceGenerations)
    val plannedReferenceChanges = immutableList(plannedReferenceChanges)

    class IdentityIds private constructor(
        companions: Set<String>,
        glamours: Set<String>,
        roleplayCharacters: Set<String>,
        worlds: Set<String>,
        campaigns: Set<String>,
        roleplayTags: Set<String>
    ) {
        val companions = immutableSet(companions)
        val glamours = immutableSet(glamours)
        val roleplayCharacters = immutableSet(roleplayCharacters)
        val worlds = immutableSet(worlds)
        val campaigns = immutableSet(campaigns)
        val roleplayTags = immutableSet(roleplayTags)

        internal fun immutableCopy() = of(
            companions, glamours, roleplayCharacters, worlds, campaigns, roleplayTags
        )

        companion object {
            fun of(
                companions: Set<String> = emptySet(),
                glamours: Set<String> = emptySet(),
                roleplayCharacters: Set<String> = emptySet(),
                worlds: Set<String> = emptySet(),
                campaigns: Set<String> = emptySet(),
                roleplayTags: Set<String> = emptySet()
            ) = IdentityIds(
                companions, glamours, roleplayCharacters, worlds, campaigns, roleplayTags
            )
        }
    }

    data class IdentityProfileImageReference(
        val category: PortableRestoreCategory?,
        val identityId: String,
        val imageHash: String
    )

    data class IdentityLorebookReference(
        val companionId: String,
        val lorebookId: String
    )

    data class MemoryIdentityRelationship(
        val sourceId: String,
        val relationship: String,
        val identityId: String
    )

    enum class Source {
        CHATS,
        GENERATED_IMAGES,
        IDENTITIES,
        PROFILE_IMAGES,
        MEMORY_ROWS,
        MODEL_RULE_ROWS,
        LOREBOOKS,
        MODEL_ENDPOINT_SETTINGS
    }

    sealed interface ReferenceChange {
        data class LorebookLinkRemoved(val link: RemovedLorebookLink) : ReferenceChange
        data class ChatFolderRemapped(
            val sourceFolderId: String,
            val destinationFolderId: String
        ) : ReferenceChange
        data class GeneratedImageProtected(val imageId: String) : ReferenceChange
        data class ProfileImageProtected(val imageHash: String) : ReferenceChange
    }

    internal data class Inputs(
        val categoryModes: Map<PortableRestoreCategory, PortableRestoreMode>,
        val finalChats: PortableChatRestorePlan.Plan?,
        val finalGeneratedImages: GeneratedImageCatalogSnapshot?,
        val finalIdentities: CompanionBackupManifest?,
        val additionalIdentityImageReferences: List<IdentityProfileImageReference> = emptyList(),
        val finalLorebooks: LorebookPortableData?,
        val finalMemories: MemoryPortableRows?,
        val finalProfileImageAssetHashes: Set<String>,
        val sourceGenerations: Map<Source, String>,
        val removedLorebookLinks: List<RemovedLorebookLink> = emptyList(),
        val folderRemaps: Map<String, String> = emptyMap(),
        val protectedGeneratedImageIds: Set<String> = emptySet(),
        val protectedProfileImageHashes: Set<String> = emptySet()
    )

    internal data class ChatImageReferences(
        val allIds: Set<String>,
        val requiredAssetIds: Set<String>
    )

    companion object {
        internal fun create(inputs: Inputs): PortableRestoreDependencyRead<PortableRestoreFinalState> {
            val chatReferences = when (val scanned = scanChatImageReferences(inputs.finalChats)) {
                is PortableRestoreDependencyRead.Available -> scanned.snapshot
                is PortableRestoreDependencyRead.Unavailable -> return scanned
            }
            val generatedAssetIds = inputs.finalGeneratedImages?.active.orEmpty()
                .mapTo(LinkedHashSet()) { it.imageId }
            val missingGeneratedAssets = chatReferences.requiredAssetIds - generatedAssetIds
            if (missingGeneratedAssets.isNotEmpty()) {
                return PortableRestoreDependencyRead.Unavailable(
                    "a final chat references a generated image asset absent from the final gallery"
                )
            }

            val identityIds = identityIds(
                inputs.finalIdentities,
                inputs.additionalIdentityImageReferences
            )
            val identityImages = identityImages(inputs.finalIdentities) +
                inputs.additionalIdentityImageReferences
            val finalProfileAssets = LinkedHashSet(inputs.finalProfileImageAssetHashes)
            inputs.finalIdentities?.images?.mapTo(finalProfileAssets) { it.hash }
            if (identityImages.any { it.imageHash !in finalProfileAssets }) {
                return PortableRestoreDependencyRead.Unavailable(
                    "a final identity references a profile image asset absent from the final gallery"
                )
            }

            val finalLorebookIds = inputs.finalLorebooks?.books.orEmpty()
                .mapTo(LinkedHashSet()) { it.id }
            val identityLorebooks = identityLorebooks(inputs.finalIdentities, finalLorebookIds)
            val memory = memoryState(inputs.finalMemories)
            val changes = ArrayList<ReferenceChange>()
            inputs.removedLorebookLinks.forEach {
                changes.add(ReferenceChange.LorebookLinkRemoved(it))
            }
            inputs.folderRemaps.forEach { (source, target) ->
                changes.add(ReferenceChange.ChatFolderRemapped(source, target))
            }
            inputs.protectedGeneratedImageIds.forEach {
                changes.add(ReferenceChange.GeneratedImageProtected(it))
            }
            inputs.protectedProfileImageHashes.forEach {
                changes.add(ReferenceChange.ProfileImageProtected(it))
            }

            return PortableRestoreDependencyRead.Available(
                PortableRestoreFinalState(
                    categoryModes = inputs.categoryModes,
                    finalChatIds = inputs.finalChats?.chats.orEmpty()
                        .mapTo(LinkedHashSet()) { it.chatId },
                    generatedImageIdsReferencedByChats = chatReferences.allIds,
                    finalIdentityIds = identityIds,
                    identityProfileImageReferences = identityImages,
                    identityLorebookReferences = identityLorebooks,
                    finalLorebookIds = finalLorebookIds,
                    finalMemoryIds = memory.memoryIds,
                    finalEntityIds = memory.entityIds,
                    finalProjectIds = memory.projectIds,
                    memoryIdentityRelationships = memory.relationships,
                    finalProfileImageAssetHashes = finalProfileAssets,
                    finalGeneratedImageAssetIds = generatedAssetIds,
                    sourceGenerations = inputs.sourceGenerations,
                    plannedReferenceChanges = changes
                )
            )
        }

        internal fun scanChatImageReferences(
            chats: PortableChatRestorePlan.Plan?
        ): PortableRestoreDependencyRead<ChatImageReferences> {
            if (chats == null) {
                return PortableRestoreDependencyRead.Available(
                    ChatImageReferences(emptySet(), emptySet())
                )
            }
            val all = LinkedHashSet<String>()
            val required = LinkedHashSet<String>()
            return try {
                for (chat in chats.chats) {
                    val messages = JSONArray(chat.messagesJson)
                    repeat(messages.length()) { index ->
                        val message = messages.optJSONObject(index) ?: return PortableRestoreDependencyRead.Unavailable(
                            "a final chat message is not an object"
                        )
                        if (!message.has(GeneratedImageMetadata.KEY) ||
                            message.isNull(GeneratedImageMetadata.KEY)
                        ) return@repeat
                        val raw = message.opt(GeneratedImageMetadata.KEY)?.toString().orEmpty()
                        val metadata = GeneratedImageMetadata.fromJson(raw)
                            ?: return PortableRestoreDependencyRead.Unavailable(
                                "a final chat has unreadable generated-image metadata"
                            )
                        if (metadata.imageId.isBlank()) {
                            return PortableRestoreDependencyRead.Unavailable(
                                "a final chat has generated-image metadata without an image id"
                            )
                        }
                        all.add(metadata.imageId)
                        if (metadata.status == GeneratedImageMetadata.STATUS_COMPLETE ||
                            !metadata.fileHash.isNullOrBlank()
                        ) required.add(metadata.imageId)
                    }
                }
                PortableRestoreDependencyRead.Available(ChatImageReferences(all, required))
            } catch (_: Exception) {
                PortableRestoreDependencyRead.Unavailable(
                    "the final chat messages could not be scanned for generated images"
                )
            }
        }

        private fun identityIds(
            manifest: CompanionBackupManifest?,
            additional: List<IdentityProfileImageReference>
        ): IdentityIds {
            fun ids(table: String, column: String): Set<String> = manifest?.roleplayTables?.get(table)
                .orEmpty().mapNotNullTo(LinkedHashSet()) {
                    (it[column] as? String)?.takeIf(String::isNotBlank)
                }
            val companions = manifest?.companionProfiles.orEmpty()
                .mapTo(LinkedHashSet()) { it.id }
            val glamours = ids("user_personas", "persona_id").toMutableSet()
            val roleplayCharacters = ids("roleplay_characters", "roleplay_character_id").toMutableSet()
            additional.forEach { reference ->
                when (reference.category) {
                    PortableRestoreCategory.COMPANIONS -> companions.add(reference.identityId)
                    PortableRestoreCategory.GLAMOURS -> glamours.add(reference.identityId)
                    PortableRestoreCategory.ROLEPLAY -> roleplayCharacters.add(reference.identityId)
                    else -> Unit
                }
            }
            return IdentityIds.of(
                companions = companions + ids("companions", "companion_id"),
                glamours = glamours,
                roleplayCharacters = roleplayCharacters,
                worlds = ids("worlds", "world_id"),
                campaigns = ids("campaigns", "campaign_id"),
                roleplayTags = ids("rp_tags", "tag_id")
            )
        }

        private fun identityImages(
            manifest: CompanionBackupManifest?
        ): List<IdentityProfileImageReference> {
            if (manifest == null) return emptyList()
            val result = ArrayList<IdentityProfileImageReference>()
            manifest.companionProfiles.forEach { profile ->
                if (profile.avatarRef.isNotBlank()) result.add(
                    IdentityProfileImageReference(
                        PortableRestoreCategory.COMPANIONS, profile.id, profile.avatarRef
                    )
                )
            }
            listOf(
                Triple("user_personas", "persona_id", PortableRestoreCategory.GLAMOURS),
                Triple("roleplay_characters", "roleplay_character_id", PortableRestoreCategory.ROLEPLAY)
            ).forEach { (table, idColumn, category) ->
                manifest.roleplayTables[table].orEmpty().forEach { row ->
                    val id = row[idColumn] as? String
                    val hash = row["image_ref"] as? String
                    if (!id.isNullOrBlank() && !hash.isNullOrBlank()) result.add(
                        IdentityProfileImageReference(category, id, hash)
                    )
                }
            }
            return result
        }

        private fun identityLorebooks(
            manifest: CompanionBackupManifest?,
            validLorebooks: Set<String>
        ): List<IdentityLorebookReference> {
            if (manifest == null) return emptyList()
            val result = ArrayList<IdentityLorebookReference>()
            manifest.companionProfiles.forEach { profile ->
                if (profile.coreLoreBookId in validLorebooks) {
                    result.add(IdentityLorebookReference(profile.id, profile.coreLoreBookId))
                }
                profile.additionalLoreBookIds.filter(validLorebooks::contains).forEach {
                    result.add(IdentityLorebookReference(profile.id, it))
                }
            }
            return result
        }

        private data class MemoryState(
            val memoryIds: Set<String>,
            val entityIds: Set<String>,
            val projectIds: Set<String>,
            val relationships: List<MemoryIdentityRelationship>
        )

        private fun memoryState(rows: MemoryPortableRows?): MemoryState {
            if (rows == null) return MemoryState(emptySet(), emptySet(), emptySet(), emptyList())
            fun ids(table: String, column: String): Set<String> = rows.tables[table].orEmpty()
                .mapNotNullTo(LinkedHashSet()) { (it[column] as? String)?.takeIf(String::isNotBlank) }
            val relationships = ArrayList<MemoryIdentityRelationship>()
            fun joins(table: String, source: String, target: String, kind: String) {
                rows.tables[table].orEmpty().forEach { row ->
                    val sourceId = row[source] as? String
                    val targetId = row[target] as? String
                    if (!sourceId.isNullOrBlank() && !targetId.isNullOrBlank()) {
                        relationships.add(MemoryIdentityRelationship(sourceId, kind, targetId))
                    }
                }
            }
            joins("memory_companions", "memory_id", "companion_id", "companion")
            joins("memory_worlds", "memory_id", "world_id", "world")
            joins("memory_campaigns", "memory_id", "campaign_id", "campaign")
            joins(
                "memory_roleplay_characters", "memory_id", "roleplay_character_id",
                "roleplay_character"
            )
            rows.tables["memories"].orEmpty().forEach { row ->
                val memoryId = row["memory_id"] as? String ?: return@forEach
                listOf(
                    "world_id" to "world",
                    "campaign_id" to "campaign",
                    "roleplay_character_id" to "roleplay_character"
                ).forEach { (column, kind) ->
                    (row[column] as? String)?.takeIf(String::isNotBlank)?.let {
                        relationships.add(MemoryIdentityRelationship(memoryId, kind, it))
                    }
                }
            }
            return MemoryState(
                ids("memories", "memory_id"),
                ids("entities", "entity_id"),
                ids("projects", "project_id"),
                relationships
            )
        }

        private fun <T> immutableSet(values: Collection<T>): Set<T> =
            Collections.unmodifiableSet(LinkedHashSet(values))

        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))

        private fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}

/** Bounded generation check: one retry, then a typed non-mutating refusal. */
internal object PortableRestoreStablePlanner {
    data class Capture<T>(val value: T, val generations: Map<PortableRestoreFinalState.Source, String>)

    sealed interface Result<out T> {
        data class Ready<T>(val planned: T, val generations: Map<PortableRestoreFinalState.Source, String>) : Result<T>
        data class Unavailable(val reason: String) : Result<Nothing>
        data object ChangedTwice : Result<Nothing>
    }

    fun <S, T> plan(
        capture: (attempt: Int, verification: Boolean) -> PortableRestoreDependencyRead<Capture<S>>,
        planner: (S, Map<PortableRestoreFinalState.Source, String>) -> PortableRestoreDependencyRead<T>
    ): Result<T> {
        repeat(2) { attempt ->
            val before = when (val result = capture(attempt, false)) {
                is PortableRestoreDependencyRead.Available -> result.snapshot
                is PortableRestoreDependencyRead.Unavailable -> return Result.Unavailable(result.reason)
            }
            val planned = when (val result = planner(before.value, before.generations)) {
                is PortableRestoreDependencyRead.Available -> result.snapshot
                is PortableRestoreDependencyRead.Unavailable -> return Result.Unavailable(result.reason)
            }
            val after = when (val result = capture(attempt, true)) {
                is PortableRestoreDependencyRead.Available -> result.snapshot
                is PortableRestoreDependencyRead.Unavailable -> return Result.Unavailable(result.reason)
            }
            if (before.generations == after.generations) {
                return Result.Ready(planned, before.generations)
            }
        }
        return Result.ChangedTwice
    }
}
