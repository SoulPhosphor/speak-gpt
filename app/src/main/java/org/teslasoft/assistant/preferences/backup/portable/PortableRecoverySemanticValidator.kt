/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupValidator
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup

/**
 * The one read-only semantic gate shared by backup finalization and restore
 * preflight. It invokes the same category parsers and preparers used to build
 * restore participants, then proves cross-category reference closure and the
 * current manifest's declared record counts.
 */
object PortableRecoverySemanticValidator {
    sealed interface Result {
        data class Valid(
            val inventory: PortableRestoreInventory.Inventory,
            val recordCounts: Map<PortableRestoreCategory, Long>
        ) : Result
        data object Invalid : Result
        data object TooLarge : Result
    }

    fun validate(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        declaredCategories: Set<PortableRestoreCategory>? = null,
        explicitlyEmptyCategories: Set<PortableRestoreCategory> = emptySet(),
        declaredRecordCounts: Map<PortableRestoreCategory, Long> = emptyMap()
    ): Result = try {
        validateInternal(
            context,
            artifacts,
            declaredCategories,
            explicitlyEmptyCategories,
            declaredRecordCounts
        )
    } catch (_: Exception) {
        Result.Invalid
    }

    private fun validateInternal(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        declaredCategories: Set<PortableRestoreCategory>?,
        explicitlyEmptyCategories: Set<PortableRestoreCategory>,
        declaredRecordCounts: Map<PortableRestoreCategory, Long>
    ): Result {
        artifacts.forEach { artifact ->
            if (!artifact.stagedFile.isFile) return Result.Invalid
            if (PortableRecoveryLimits.maxDecodedBytes(artifact.entryName, artifact.type) == null) {
                return Result.Invalid
            }
            if (!PortableRecoveryLimits.accepts(
                    artifact.entryName, artifact.type, artifact.stagedFile.length()
                )
            ) return Result.TooLarge
        }

        val inventory = PortableRestoreInventory.from(
            artifacts, declaredCategories, explicitlyEmptyCategories
        )
        val available = inventory.available
        if (inventory.explicitlyEmpty.any { it !in available }) return Result.Invalid

        val chats = artifact(artifacts, PortablePackage.TYPE_CHATS_JSON)?.let { source ->
            if (source.stagedFile.length() > PortableRecoveryLimits.CHATS_JSON_BYTES) {
                return Result.TooLarge
            }
            (PortableChatRestorePlan.parse(source.stagedFile.readText(Charsets.UTF_8)) as?
                PortableChatRestorePlan.Result.Ok)?.plan ?: return Result.Invalid
        }

        val generated = when (val prepared = GeneratedImagePortableRestoreManager.prepare(artifacts)) {
            is GeneratedImagePortableRestoreManager.PrepareResult.Ready -> prepared.prepared
            GeneratedImagePortableRestoreManager.PrepareResult.Absent -> null
            is GeneratedImagePortableRestoreManager.PrepareResult.Invalid -> return Result.Invalid
        }

        val identityArtifact = artifact(
            artifacts, PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE
        )
        val identities = identityArtifact?.let {
            (CompanionBackupValidator.validate(it.stagedFile) as?
                CompanionBackupValidator.Verdict.Valid)?.manifest ?: return Result.Invalid
        }

        val profile = if (artifacts.any {
                it.type == PortablePackage.TYPE_SQLITE_DB && it.entryName == "user_images.db"
            }
        ) {
            (ProfileImagePortableRestoreManager.prepare(artifacts) as?
                ProfileImagePortableRestoreManager.Result.Ready)?.prepared ?: return Result.Invalid
        } else {
            if (artifacts.any { it.type == PortablePackage.TYPE_PROFILE_IMAGE_ASSET }) {
                return Result.Invalid
            }
            null
        }

        val memoryArtifactPresent = artifacts.any {
            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "memory.db"
        }
        val memories = if (memoryArtifactPresent) {
            UnifiedPortableRestore.readIncomingMemory(
                artifacts, MemoryPortableGroup.MEMORIES, incomingIsEmpty = false
            ) ?: return Result.Invalid
        } else null
        val modelRules = if (memoryArtifactPresent) {
            UnifiedPortableRestore.readIncomingMemory(
                artifacts, MemoryPortableGroup.MODEL_RULES, incomingIsEmpty = false
            ) ?: return Result.Invalid
        } else null

        val lorebookArtifactPresent = artifacts.any {
            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "lorebook.db"
        }
        val lorebooks = if (lorebookArtifactPresent) {
            UnifiedPortableRestore.readIncomingLorebooks(
                context.applicationContext, artifacts, incomingIsEmpty = false
            ) ?: return Result.Invalid
        } else null

        val endpointArtifact = artifact(artifacts, PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS)
        val endpoints = endpointArtifact?.let { source ->
            if (source.stagedFile.length() > PortableRecoveryLimits.MODEL_ENDPOINT_SETTINGS_BYTES) {
                return Result.TooLarge
            }
            (ModelEndpointPortableCodec.parse(source.stagedFile.readText(Charsets.UTF_8)) as?
                ModelEndpointPortableCodec.Result.Ok)?.data ?: return Result.Invalid
        }

        if (!requiredArtifactsAgreeWithInventory(
                available, inventory.explicitlyEmpty, chats, generated, identities, profile,
                memoryArtifactPresent, lorebookArtifactPresent, endpoints
            )
        ) return Result.Invalid

        val chatReferences = when (val scanned = PortableRestoreFinalState.scanChatImageReferences(chats)) {
            is PortableRestoreDependencyRead.Available -> scanned.snapshot.requiredAssetIds
            is PortableRestoreDependencyRead.Unavailable -> return Result.Invalid
        }
        val generatedIds = generated?.snapshot?.active.orEmpty().mapTo(HashSet()) { it.imageId }
        if (!generatedIds.containsAll(chatReferences)) return Result.Invalid

        val identityImageHashes = identityImageHashes(identities)
        val carriedIdentityImages = identities?.images.orEmpty().mapTo(HashSet()) { it.hash }
        if (!carriedIdentityImages.containsAll(identityImageHashes)) return Result.Invalid
        if (declaredCategories != null && PortableRestoreCategory.PROFILE_IMAGES in available) {
            val profileHashes = profile?.records.orEmpty().mapTo(HashSet()) { it.hash }
            if (!profileHashes.containsAll(identityImageHashes)) return Result.Invalid
        }

        if (declaredCategories != null && PortableRestoreCategory.LOREBOOKS in available) {
            val bookIds = lorebooks?.books.orEmpty().mapTo(HashSet()) { it.id }
            if (!bookIds.containsAll(identityLorebookIds(identities))) return Result.Invalid
        }

        val counts = linkedMapOf(
            PortableRestoreCategory.CHATS to (chats?.chatCount?.toLong() ?: 0L),
            PortableRestoreCategory.GENERATED_IMAGES to
                (generated?.snapshot?.let { it.active.size + it.tombstones.size }?.toLong() ?: 0L),
            PortableRestoreCategory.COMPANIONS to (identities?.companionProfiles?.size?.toLong() ?: 0L),
            PortableRestoreCategory.GLAMOURS to identityTableCount(identities, "user_personas"),
            PortableRestoreCategory.ROLEPLAY to roleplayRecordCount(identities),
            PortableRestoreCategory.PROFILE_IMAGES to (profile?.records?.size?.toLong() ?: 0L),
            PortableRestoreCategory.ACTIVATION_PROMPTS to
                (identities?.activationPrompts?.size?.toLong() ?: 0L),
            PortableRestoreCategory.SYSTEM_PROMPTS to
                (identities?.systemPrompts?.size?.toLong() ?: 0L),
            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS to
                (endpoints?.let { it.endpoints.size + it.favorites.size }?.toLong() ?: 0L),
            PortableRestoreCategory.MODEL_RULES to rowCount(modelRules),
            PortableRestoreCategory.MEMORIES to rowCount(memories),
            PortableRestoreCategory.LOREBOOKS to
                (lorebooks?.let { it.books.size + it.entries.size + it.deletedEntries.size }?.toLong() ?: 0L)
        )
        if (declaredRecordCounts.isNotEmpty() && declaredRecordCounts != counts) {
            return Result.Invalid
        }
        return Result.Valid(inventory, counts)
    }

    fun declarations(
        artifacts: List<PortablePackage.ValidatedArtifact>,
        counts: Map<PortableRestoreCategory, Long>
    ): List<PortablePackage.CategoryDeclaration> = PortableRestoreCategory.entries.map { category ->
        val names = categoryArtifactNames(category, artifacts)
        PortablePackage.CategoryDeclaration(
            category,
            if (names.isEmpty()) PortablePackage.CategoryRepresentation.EMPTY
            else PortablePackage.CategoryRepresentation.ARTIFACTS,
            names,
            counts.getValue(category)
        )
    }

    private fun requiredArtifactsAgreeWithInventory(
        available: Set<PortableRestoreCategory>,
        empty: Set<PortableRestoreCategory>,
        chats: PortableChatRestorePlan.Plan?,
        generated: GeneratedImagePortableRestoreManager.Prepared?,
        identities: CompanionBackupManifest?,
        profile: ProfileImagePortableRestoreManager.Prepared?,
        memoryPresent: Boolean,
        lorebooksPresent: Boolean,
        endpoints: ModelEndpointPortableCodec.Data?
    ): Boolean {
        fun present(category: PortableRestoreCategory, value: Boolean): Boolean =
            if (category in empty) !value else category !in available || value
        return present(PortableRestoreCategory.CHATS, chats != null) &&
            present(PortableRestoreCategory.GENERATED_IMAGES, generated != null) &&
            IDENTITY_CATEGORIES.all { present(it, identities != null) } &&
            present(PortableRestoreCategory.PROFILE_IMAGES, profile != null) &&
            present(PortableRestoreCategory.MEMORIES, memoryPresent) &&
            present(PortableRestoreCategory.MODEL_RULES, memoryPresent) &&
            present(PortableRestoreCategory.LOREBOOKS, lorebooksPresent) &&
            present(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS, endpoints != null)
    }

    private fun artifact(
        artifacts: List<PortablePackage.ValidatedArtifact>,
        type: String
    ): PortablePackage.ValidatedArtifact? = artifacts.singleOrNull { it.type == type }

    private fun identityImageHashes(manifest: CompanionBackupManifest?): Set<String> {
        if (manifest == null) return emptySet()
        val hashes = manifest.companionProfiles.mapNotNullTo(LinkedHashSet()) {
            it.avatarRef.takeIf(String::isNotBlank)
        }
        listOf("user_personas", "roleplay_characters").forEach { table ->
            manifest.roleplayTables[table].orEmpty().mapNotNullTo(hashes) {
                (it["image_ref"] as? String)?.takeIf(String::isNotBlank)
            }
        }
        return hashes
    }

    private fun identityLorebookIds(manifest: CompanionBackupManifest?): Set<String> {
        if (manifest == null) return emptySet()
        val ids = LinkedHashSet<String>()
        manifest.companionProfiles.forEach { profile ->
            profile.coreLoreBookId.takeIf(String::isNotBlank)?.let(ids::add)
            ids.addAll(profile.additionalLoreBookIds)
        }
        return ids
    }

    private fun identityTableCount(manifest: CompanionBackupManifest?, table: String): Long =
        manifest?.roleplayTables?.get(table).orEmpty().size.toLong()

    private fun roleplayRecordCount(manifest: CompanionBackupManifest?): Long =
        manifest?.roleplayTables.orEmpty()
            .filterKeys { it != "companions" && it != "user_personas" }
            .values.sumOf { it.size.toLong() }

    private fun rowCount(rows: org.teslasoft.assistant.preferences.memory.MemoryPortableRows?): Long =
        rows?.tables.orEmpty().values.sumOf { it.size.toLong() }

    private fun categoryArtifactNames(
        category: PortableRestoreCategory,
        artifacts: List<PortablePackage.ValidatedArtifact>
    ): Set<String> = artifacts.filter { artifact ->
        when (category) {
            PortableRestoreCategory.CHATS -> artifact.type == PortablePackage.TYPE_CHATS_JSON
            PortableRestoreCategory.GENERATED_IMAGES -> artifact.type in setOf(
                PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
                PortablePackage.TYPE_GENERATED_IMAGE_ASSET
            )
            PortableRestoreCategory.COMPANIONS,
            PortableRestoreCategory.GLAMOURS,
            PortableRestoreCategory.ROLEPLAY,
            PortableRestoreCategory.ACTIVATION_PROMPTS,
            PortableRestoreCategory.SYSTEM_PROMPTS ->
                artifact.type == PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE
            PortableRestoreCategory.PROFILE_IMAGES -> artifact.type == PortablePackage.TYPE_PROFILE_IMAGE_ASSET ||
                (artifact.type == PortablePackage.TYPE_SQLITE_DB && artifact.entryName == "user_images.db")
            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS ->
                artifact.type == PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS
            PortableRestoreCategory.MODEL_RULES,
            PortableRestoreCategory.MEMORIES -> artifact.type == PortablePackage.TYPE_SQLCIPHER_DB &&
                artifact.entryName == "memory.db"
            PortableRestoreCategory.LOREBOOKS -> artifact.type == PortablePackage.TYPE_SQLCIPHER_DB &&
                artifact.entryName == "lorebook.db"
        }
    }.mapTo(LinkedHashSet()) { it.entryName }

    private val IDENTITY_CATEGORIES = setOf(
        PortableRestoreCategory.COMPANIONS,
        PortableRestoreCategory.GLAMOURS,
        PortableRestoreCategory.ROLEPLAY,
        PortableRestoreCategory.ACTIVATION_PROMPTS,
        PortableRestoreCategory.SYSTEM_PROMPTS
    )
}
