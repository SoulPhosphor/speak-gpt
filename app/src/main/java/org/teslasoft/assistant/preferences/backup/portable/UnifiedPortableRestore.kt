/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.backup.companion.CompanionCategoryPlanner
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageUsage

/** Constructs every selected category participant and executes the one outer
 * transaction. Activities own dialogs only; no live store is written here
 * until [SelectedCategoryRestoreTransaction.execute] begins its apply phase. */
object UnifiedPortableRestore {
    data class Request(
        val selections: List<PortableRestoreSelectionPlan.Selection>,
        val folderResolutions: Map<String, ChatMergePlanner.FolderResolution> = emptyMap()
    )

    sealed class BuildResult {
        data class Ready(
            val participants: List<SelectedCategoryRestoreTransaction.Participant>,
            val chatParticipant: ChatRestoreParticipant?
        ) : BuildResult()
        data class Failed(val detail: String) : BuildResult()
    }

    fun build(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        request: Request,
        stagingRoot: File
    ): BuildResult {
        if (request.selections.isEmpty()) return BuildResult.Failed("No categories were selected.")
        val app = context.applicationContext
        val modes = request.selections.associate { it.category to it.mode }
        val participants = ArrayList<SelectedCategoryRestoreTransaction.Participant>()

        val chatArtifact = artifact(artifacts, PortablePackage.TYPE_CHATS_JSON)
        var chatParticipant: ChatRestoreParticipant? = null
        if (PortableRestoreCategory.CHATS in modes) {
            chatArtifact ?: return BuildResult.Failed("Chats are missing from the staged backup.")
            chatParticipant = ChatRestoreParticipant(
                app, chatArtifact.stagedFile, modes.getValue(PortableRestoreCategory.CHATS),
                request.folderResolutions, File(stagingRoot, "chats")
            )
            participants.add(chatParticipant)
        }

        val generatedSelected = PortableRestoreCategory.GENERATED_IMAGES in modes
        if (generatedSelected) {
            val protected = if (PortableRestoreCategory.CHATS !in modes) {
                currentGeneratedImagesUsedByChats(app)
            } else emptySet()
            participants.add(GeneratedImageRestoreParticipant(
                app, artifacts, modes.getValue(PortableRestoreCategory.GENERATED_IMAGES),
                protected, File(stagingRoot, "generated_images")
            ))
        } else if (PortableRestoreCategory.CHATS in modes) {
            val parsed = PortableChatRestorePlan.parse(chatArtifact!!.stagedFile.readText(Charsets.UTF_8)) as?
                PortableChatRestorePlan.Result.Ok
                ?: return BuildResult.Failed("Chats failed dependency validation.")
            val chatIds = parsed.plan.chats.mapTo(HashSet()) { it.chatId }
            val generated = (GeneratedImagePortableRestoreManager.prepare(artifacts) as?
                GeneratedImagePortableRestoreManager.PrepareResult.Ready)?.prepared
            if (generated != null) {
                val required = generated.snapshot.active.filter {
                    it.originChatId != null && it.originChatId in chatIds
                }.mapTo(HashSet()) { it.imageId }
                if (required.isNotEmpty()) participants.add(GeneratedImageRestoreParticipant(
                    app, artifacts, required, File(stagingRoot, "chat_images"), "chat_image_dependencies"
                ))
            }
        }

        val identitySelections = request.selections.filter { it.category in IDENTITY_CATEGORIES }
            .map { CompanionCategoryPlanner.Selection(it.category, it.mode) }
        if (identitySelections.isNotEmpty()) {
            val archive = artifact(artifacts, PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE)
                ?: return BuildResult.Failed("Identity data is missing from the staged backup.")
            participants.add(CompanionCategoryRestoreParticipant(
                app, archive.stagedFile, identitySelections, File(stagingRoot, "identity_bundle")
            ))
        }

        if (PortableRestoreCategory.PROFILE_IMAGES in modes) {
            participants.add(ProfileImageRestoreParticipant(
                app, artifacts, modes.getValue(PortableRestoreCategory.PROFILE_IMAGES),
                protectedProfileImages(app, modes.keys), File(stagingRoot, "profile_images")
            ))
        }

        if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            val artifact = artifact(artifacts, PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS)
                ?: return BuildResult.Failed("Model and endpoint settings are missing.")
            participants.add(ModelEndpointRestoreParticipant(
                app, artifact.stagedFile,
                modes.getValue(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS),
                File(stagingRoot, "model_endpoints")
            ))
        }
        for (category in listOf(PortableRestoreCategory.MODEL_RULES, PortableRestoreCategory.MEMORIES)) {
            if (category in modes) participants.add(MemoryRowsRestoreParticipant(
                app, artifacts, category, modes.getValue(category), File(stagingRoot, category.key)
            ))
        }
        if (PortableRestoreCategory.LOREBOOKS in modes) participants.add(LorebookRestoreParticipant(
            app, artifacts, modes.getValue(PortableRestoreCategory.LOREBOOKS),
            File(stagingRoot, "lorebooks")
        ))
        return BuildResult.Ready(participants, chatParticipant)
    }

    fun execute(
        journalRoot: File,
        ready: BuildResult.Ready
    ): SelectedCategoryRestoreTransaction.Result =
        SelectedCategoryRestoreTransaction.execute(journalRoot, ready.participants)

    private fun artifact(
        artifacts: List<PortablePackage.ValidatedArtifact>, type: String
    ): PortablePackage.ValidatedArtifact? = artifacts.singleOrNull { it.type == type }

    private fun currentGeneratedImagesUsedByChats(context: Context): Set<String> {
        val exported = GeneratedImageCatalogStore.exportSnapshot(context)
        if (exported.state != GeneratedImageCatalogStorageState.AVAILABLE) return emptySet()
        return exported.snapshot?.active.orEmpty().filter { !it.originChatId.isNullOrBlank() }
            .mapTo(HashSet()) { it.imageId }
    }

    private fun protectedProfileImages(
        context: Context,
        selected: Set<PortableRestoreCategory>
    ): Set<String> = ProfileImageUsage.computeAll(context).filterValues { refs ->
        refs.any { reference -> when (reference.kind) {
            ProfileImageUsage.Kind.DEFAULT_USER_IMAGE -> true
            ProfileImageUsage.Kind.COMPANION -> PortableRestoreCategory.COMPANIONS !in selected
            ProfileImageUsage.Kind.MY_PERSONA -> PortableRestoreCategory.GLAMOURS !in selected
            ProfileImageUsage.Kind.ROLEPLAY_CHARACTER -> PortableRestoreCategory.ROLEPLAY !in selected
        } }
    }.keys

    private val IDENTITY_CATEGORIES = setOf(
        PortableRestoreCategory.COMPANIONS,
        PortableRestoreCategory.GLAMOURS,
        PortableRestoreCategory.ROLEPLAY,
        PortableRestoreCategory.ACTIVATION_PROMPTS,
        PortableRestoreCategory.SYSTEM_PROMPTS
    )
}
