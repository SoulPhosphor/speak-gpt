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
        val folderResolutions: Map<String, ChatMergePlanner.FolderResolution> = emptyMap(),
        val explicitlyEmptyCategories: Set<PortableRestoreCategory> = emptySet()
    )

    sealed class BuildResult {
        data class Ready(
            val participants: List<SelectedCategoryRestoreTransaction.Participant>,
            val chatParticipant: ChatRestoreParticipant?
        ) : BuildResult()
        data class Failed(
            val reason: BuildFailure,
            val category: PortableRestoreCategory? = null
        ) : BuildResult()
    }

    enum class BuildFailure {
        EMPTY_SELECTION,
        MISSING_CATEGORY_ARTIFACT,
        DEPENDENCY_VALIDATION_FAILED
    }

    fun build(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        request: Request,
        stagingRoot: File
    ): BuildResult {
        if (request.selections.isEmpty()) return BuildResult.Failed(BuildFailure.EMPTY_SELECTION)
        val app = context.applicationContext
        val modes = request.selections.associate { it.category to it.mode }
        val participants = ArrayList<SelectedCategoryRestoreTransaction.Participant>()

        val chatArtifact = artifact(artifacts, PortablePackage.TYPE_CHATS_JSON)
        var chatParticipant: ChatRestoreParticipant? = null
        if (PortableRestoreCategory.CHATS in modes) {
            chatArtifact ?: return BuildResult.Failed(
                BuildFailure.MISSING_CATEGORY_ARTIFACT, PortableRestoreCategory.CHATS
            )
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
                ?: return BuildResult.Failed(
                    BuildFailure.DEPENDENCY_VALIDATION_FAILED, PortableRestoreCategory.CHATS
                )
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
        var identityParticipant: CompanionCategoryRestoreParticipant? = null
        if (identitySelections.isNotEmpty()) {
            val archive = artifact(artifacts, PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE)
                ?: return BuildResult.Failed(
                    BuildFailure.MISSING_CATEGORY_ARTIFACT,
                    identitySelections.first().category
                )
            identityParticipant = CompanionCategoryRestoreParticipant(
                app, archive.stagedFile, identitySelections, File(stagingRoot, "identity_bundle")
            )
            participants.add(identityParticipant)
        }

        if (PortableRestoreCategory.PROFILE_IMAGES in modes) {
            participants.add(ProfileImageRestoreParticipant(
                app, artifacts, modes.getValue(PortableRestoreCategory.PROFILE_IMAGES),
                protectedProfileImages(app, modes.keys), File(stagingRoot, "profile_images"),
                PortableRestoreCategory.PROFILE_IMAGES in request.explicitlyEmptyCategories
            ))
        }

        if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            val artifact = artifact(artifacts, PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS)
                ?: return BuildResult.Failed(
                    BuildFailure.MISSING_CATEGORY_ARTIFACT,
                    PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS
                )
            participants.add(ModelEndpointRestoreParticipant(
                app, artifact.stagedFile,
                modes.getValue(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS),
                File(stagingRoot, "model_endpoints")
            ))
        }
        for (category in listOf(PortableRestoreCategory.MODEL_RULES, PortableRestoreCategory.MEMORIES)) {
            if (category in modes) participants.add(
                if (category == PortableRestoreCategory.MEMORIES && identityParticipant != null) {
                    MemoryRowsRestoreParticipant(
                        app, artifacts, category, modes.getValue(category), File(stagingRoot, category.key),
                        category in request.explicitlyEmptyCategories
                    ) { identityParticipant?.memoryReferenceIds() ?: MemoryReferenceIds() }
                } else {
                    MemoryRowsRestoreParticipant(
                        app, artifacts, category, modes.getValue(category), File(stagingRoot, category.key),
                        category in request.explicitlyEmptyCategories
                    )
                }
            )
        }
        if (PortableRestoreCategory.LOREBOOKS in modes) participants.add(LorebookRestoreParticipant(
            app, artifacts, modes.getValue(PortableRestoreCategory.LOREBOOKS),
            File(stagingRoot, "lorebooks"),
            PortableRestoreCategory.LOREBOOKS in request.explicitlyEmptyCategories
        ))
        return BuildResult.Ready(participants, chatParticipant)
    }

    fun execute(
        journalRoot: File,
        ready: BuildResult.Ready
    ): SelectedCategoryRestoreTransaction.Result =
        SelectedCategoryRestoreTransaction.execute(journalRoot, ready.participants)

    /** Persistent rollback staging used only after the package has been fully
     * decoded and validated. Unlike decode staging, this must survive process
     * death until the outer transaction is complete or rolled back. */
    fun newTransactionStaging(context: Context): File? {
        val journal = journalRoot(context)
        if (journal.exists()) return null
        val root = transactionStagingRoot(context)
        if (root.exists() && !root.deleteRecursively()) return null
        return root.takeIf { it.mkdirs() || it.isDirectory }
    }

    fun journalRoot(context: Context): File =
        File(context.filesDir, JOURNAL_DIRECTORY)

    /** Resolve a selected-category transaction interrupted after mutation
     * began. Constructors are supplied only so each participant can read its
     * already-staged exact rollback snapshot; validate/stage are never called
     * by [SelectedCategoryRestoreTransaction.recover]. */
    @Synchronized
    fun recoverPending(context: Context): Boolean {
        val journal = journalRoot(context)
        val root = transactionStagingRoot(context)
        if (!journal.exists()) {
            if (root.exists()) root.deleteRecursively()
            return true
        }
        val unused = File(root, "unused")
        val participants = listOf<SelectedCategoryRestoreTransaction.Participant>(
            ChatRestoreParticipant(context, unused, PortableRestoreMode.MERGE, emptyMap(), File(root, "chats")),
            GeneratedImageRestoreParticipant(
                context, emptyList(), PortableRestoreMode.MERGE, emptySet(), File(root, "generated_images")
            ),
            GeneratedImageRestoreParticipant(
                context, emptyList(), emptySet(), File(root, "chat_images"), "chat_image_dependencies"
            ),
            CompanionCategoryRestoreParticipant(
                context, unused,
                listOf(CompanionCategoryPlanner.Selection(
                    PortableRestoreCategory.COMPANIONS, PortableRestoreMode.MERGE
                )),
                File(root, "identity_bundle")
            ),
            ProfileImageRestoreParticipant(
                context, emptyList(), PortableRestoreMode.MERGE, emptySet(), File(root, "profile_images")
            ),
            ModelEndpointRestoreParticipant(
                context, unused, PortableRestoreMode.MERGE, File(root, "model_endpoints")
            ),
            MemoryRowsRestoreParticipant(
                context, emptyList(), PortableRestoreCategory.MODEL_RULES,
                PortableRestoreMode.MERGE, File(root, PortableRestoreCategory.MODEL_RULES.key)
            ),
            MemoryRowsRestoreParticipant(
                context, emptyList(), PortableRestoreCategory.MEMORIES,
                PortableRestoreMode.MERGE, File(root, PortableRestoreCategory.MEMORIES.key)
            ),
            LorebookRestoreParticipant(
                context, emptyList(), PortableRestoreMode.MERGE, File(root, "lorebooks")
            )
        ).associateBy { it.categoryKey }
        val recovered = SelectedCategoryRestoreTransaction.recover(journal, participants)
        if (recovered) root.deleteRecursively()
        return recovered
    }

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

    private const val JOURNAL_DIRECTORY = "selected_category_restore_journal"
    private const val STAGING_DIRECTORY = "selected_category_restore_staging"

    private fun transactionStagingRoot(context: Context): File =
        File(context.filesDir, STAGING_DIRECTORY)
}
