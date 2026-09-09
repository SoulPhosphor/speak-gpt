/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupCodec
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupExporter
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupValidator
import org.teslasoft.assistant.preferences.backup.companion.CompanionCategoryPlanner
import org.teslasoft.assistant.preferences.backup.companion.CompanionRestorePlanner
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageDb
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageUsage
import org.teslasoft.assistant.util.Hash

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
            val chatParticipant: ChatRestoreParticipant?,
            val finalState: PortableRestoreFinalState
        ) : BuildResult()
        data class NeedsFolderDecisions(
            val collisions: List<ChatMergePlanner.FolderCollision>
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
        if (modes.size != request.selections.size) {
            return BuildResult.Failed(BuildFailure.DEPENDENCY_VALIDATION_FAILED)
        }
        val parsedBackup = parseBackup(app, artifacts, request, modes)
            ?: return BuildResult.Failed(BuildFailure.MISSING_CATEGORY_ARTIFACT)
        val identitySelections = request.selections.filter { it.category in IDENTITY_CATEGORIES }
            .map { CompanionCategoryPlanner.Selection(it.category, it.mode) }
        val planningRoot = File(stagingRoot, "final_state_planning")
        if (planningRoot.exists() && !planningRoot.deleteRecursively()) {
            return BuildResult.Failed(BuildFailure.DEPENDENCY_VALIDATION_FAILED)
        }
        if (!planningRoot.mkdirs()) {
            return BuildResult.Failed(BuildFailure.DEPENDENCY_VALIDATION_FAILED)
        }

        var folderCollisions: List<ChatMergePlanner.FolderCollision> = emptyList()
        val stable = PortableRestoreStablePlanner.plan(
            capture = { attempt, verification ->
                captureLiveState(
                    app,
                    modes,
                    identitySelections.isNotEmpty(),
                    File(planningRoot, "attempt_${attempt}_${if (verification) "after" else "before"}")
                )
            },
            planner = { live, generations ->
                when (val planned = planFinalState(
                    modes,
                    request,
                    parsedBackup,
                    live,
                    identitySelections,
                    generations
                )) {
                    is PlanningResult.Ready -> PortableRestoreDependencyRead.Available(planned.plan)
                    is PlanningResult.FolderDecisions -> {
                        folderCollisions = planned.collisions
                        PortableRestoreDependencyRead.Unavailable(FOLDER_DECISIONS_REQUIRED)
                    }
                    is PlanningResult.Unavailable -> PortableRestoreDependencyRead.Unavailable(planned.reason)
                }
            }
        )
        val planned = when (stable) {
            is PortableRestoreStablePlanner.Result.Ready -> stable.planned
            is PortableRestoreStablePlanner.Result.Unavailable -> {
                if (stable.reason == FOLDER_DECISIONS_REQUIRED && folderCollisions.isNotEmpty()) {
                    return BuildResult.NeedsFolderDecisions(folderCollisions)
                }
                return BuildResult.Failed(BuildFailure.DEPENDENCY_VALIDATION_FAILED)
            }
            PortableRestoreStablePlanner.Result.ChangedTwice ->
                return BuildResult.Failed(BuildFailure.DEPENDENCY_VALIDATION_FAILED)
        }

        val participants = ArrayList<SelectedCategoryRestoreTransaction.Participant>()
        var chatParticipant: ChatRestoreParticipant? = null
        planned.chat?.let {
            chatParticipant = ChatRestoreParticipant(app, it.desired, it.current, File(stagingRoot, "chats"))
            participants.add(chatParticipant!!)
        }
        planned.generated?.let {
            participants.add(GeneratedImageRestoreParticipant(
                app, it.plan, File(stagingRoot, it.stagingDirectory), it.participantKey
            ))
        }
        planned.identities?.let {
            participants.add(CompanionCategoryRestoreParticipant(
                app, it, identitySelections, File(stagingRoot, "identity_bundle")
            ))
        }
        planned.profileImages?.let {
            participants.add(ProfileImageRestoreParticipant(app, it, File(stagingRoot, "profile_images")))
        }
        planned.modelEndpoints?.let {
            participants.add(ModelEndpointRestoreParticipant(app, it, File(stagingRoot, "model_endpoints")))
        }
        planned.modelRules?.let {
            participants.add(MemoryRowsRestoreParticipant(
                app, PortableRestoreCategory.MODEL_RULES, it,
                File(stagingRoot, PortableRestoreCategory.MODEL_RULES.key)
            ))
        }
        planned.memories?.let {
            participants.add(MemoryRowsRestoreParticipant(
                app, PortableRestoreCategory.MEMORIES, it,
                File(stagingRoot, PortableRestoreCategory.MEMORIES.key)
            ))
        }
        planned.lorebooks?.let {
            participants.add(LorebookRestoreParticipant(app, it, File(stagingRoot, "lorebooks")))
        }
        return BuildResult.Ready(participants, chatParticipant, planned.finalState)
    }

    private data class ParsedBackup(
        val chats: PortableChatRestorePlan.Plan? = null,
        val generatedImages: GeneratedImagePortableRestoreManager.Prepared? = null,
        val identities: CompanionBackupManifest? = null,
        val identityArchive: File? = null,
        val profileImages: ProfileImagePortableRestoreManager.Prepared? = null,
        val memories: MemoryPortableRows? = null,
        val modelRules: MemoryPortableRows? = null,
        val lorebooks: LorebookPortableData? = null,
        val modelEndpoints: ModelEndpointPortableCodec.Data? = null
    )

    private data class LiveState(
        val chats: PortableChatRestorePlan.Plan? = null,
        val generatedImages: GeneratedImageCatalogSnapshot? = null,
        val identities: CompanionBackupManifest? = null,
        val identityArchive: File? = null,
        val identityImageUsage: List<ProfileImageUsage.RestoreReference> = emptyList(),
        val identityReferences: MemoryReferenceIds = MemoryReferenceIds(),
        val profileImages: ProfileImageRestoreParticipant.Snapshot? = null,
        val memories: MemoryPortableRows? = null,
        val modelRules: MemoryPortableRows? = null,
        val lorebooks: LorebookPortableData? = null,
        val modelEndpoints: ModelEndpointPortableCodec.Data? = null
    )

    private data class PlannedChat(
        val current: PortableChatRestorePlan.Plan,
        val desired: PortableChatRestoreCoordinator.Prepared
    )

    private data class PlannedGenerated(
        val plan: GeneratedImageRestoreParticipant.PreparedPlan,
        val participantKey: String,
        val stagingDirectory: String
    )

    private data class PlannedRestore(
        val finalState: PortableRestoreFinalState,
        val chat: PlannedChat? = null,
        val generated: PlannedGenerated? = null,
        val identities: CompanionCategoryRestoreParticipant.PreparedPlan? = null,
        val profileImages: ProfileImageRestoreParticipant.PreparedPlan? = null,
        val modelEndpoints: ModelEndpointRestoreParticipant.PreparedPlan? = null,
        val modelRules: MemoryRowsRestoreParticipant.PreparedPlan? = null,
        val memories: MemoryRowsRestoreParticipant.PreparedPlan? = null,
        val lorebooks: LorebookRestoreParticipant.PreparedPlan? = null
    )

    private sealed interface PlanningResult {
        data class Ready(val plan: PlannedRestore) : PlanningResult
        data class FolderDecisions(
            val collisions: List<ChatMergePlanner.FolderCollision>
        ) : PlanningResult
        data class Unavailable(val reason: String) : PlanningResult
    }

    private fun parseBackup(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        request: Request,
        modes: Map<PortableRestoreCategory, PortableRestoreMode>
    ): ParsedBackup? {
        return try {
        val chats = if (PortableRestoreCategory.CHATS in modes) {
            val source = artifact(artifacts, PortablePackage.TYPE_CHATS_JSON)?.stagedFile
                ?: return null
            (PortableChatRestorePlan.parse(source.readText(Charsets.UTF_8)) as?
                PortableChatRestorePlan.Result.Ok)?.plan ?: return null
        } else null

        val needGenerated = PortableRestoreCategory.GENERATED_IMAGES in modes ||
            PortableRestoreCategory.CHATS in modes
        val generated = if (needGenerated) {
            when (val prepared = GeneratedImagePortableRestoreManager.prepare(artifacts)) {
                is GeneratedImagePortableRestoreManager.PrepareResult.Ready -> prepared.prepared
                GeneratedImagePortableRestoreManager.PrepareResult.Absent ->
                    GeneratedImagePortableRestoreManager.Prepared(emptyGeneratedSnapshot(), emptyMap())
                is GeneratedImagePortableRestoreManager.PrepareResult.Invalid -> return null
            }
        } else null
        if (PortableRestoreCategory.GENERATED_IMAGES in modes &&
            generated?.snapshot?.active.isNullOrEmpty() &&
            PortableRestoreCategory.GENERATED_IMAGES !in request.explicitlyEmptyCategories &&
            artifacts.none { it.type == PortablePackage.TYPE_GENERATED_IMAGES_CATALOG }
        ) return null

        val identitySelections = modes.keys intersect IDENTITY_CATEGORIES
        val identityArtifact = if (identitySelections.isNotEmpty()) {
            artifact(artifacts, PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE) ?: return null
        } else null
        val identities = identityArtifact?.let {
            (CompanionBackupValidator.validate(it.stagedFile) as?
                CompanionBackupValidator.Verdict.Valid)?.manifest ?: return null
        }

        val profileImages = if (PortableRestoreCategory.PROFILE_IMAGES in modes) {
            if (PortableRestoreCategory.PROFILE_IMAGES in request.explicitlyEmptyCategories &&
                artifacts.none { it.type == PortablePackage.TYPE_SQLITE_DB && it.entryName == "user_images.db" }
            ) ProfileImagePortableRestoreManager.Prepared(emptyList(), emptyMap())
            else (ProfileImagePortableRestoreManager.prepare(artifacts) as?
                ProfileImagePortableRestoreManager.Result.Ready)?.prepared ?: return null
        } else null

        val memories = if (PortableRestoreCategory.MEMORIES in modes) {
            readIncomingMemory(
                artifacts,
                MemoryPortableGroup.MEMORIES,
                PortableRestoreCategory.MEMORIES in request.explicitlyEmptyCategories
            ) ?: return null
        } else null
        val rules = if (PortableRestoreCategory.MODEL_RULES in modes) {
            readIncomingMemory(
                artifacts,
                MemoryPortableGroup.MODEL_RULES,
                PortableRestoreCategory.MODEL_RULES in request.explicitlyEmptyCategories
            ) ?: return null
        } else null
        val lorebooks = if (PortableRestoreCategory.LOREBOOKS in modes) {
            readIncomingLorebooks(
                context,
                artifacts,
                PortableRestoreCategory.LOREBOOKS in request.explicitlyEmptyCategories
            ) ?: return null
        } else null
        val endpoints = if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            val source = artifact(artifacts, PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS)?.stagedFile
                ?: return null
            if (!source.isFile || source.length() > ModelEndpointPortableCodec.MAX_ARTIFACT_BYTES) {
                return null
            }
            (ModelEndpointPortableCodec.parse(source.readText(Charsets.UTF_8)) as?
                ModelEndpointPortableCodec.Result.Ok)?.data ?: return null
        } else null

            ParsedBackup(
                chats,
                generated,
                identities,
                identityArtifact?.stagedFile,
                profileImages,
                memories,
                rules,
                lorebooks,
                endpoints
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun captureLiveState(
        context: Context,
        modes: Map<PortableRestoreCategory, PortableRestoreMode>,
        identitiesSelected: Boolean,
        root: File
    ): PortableRestoreDependencyRead<PortableRestoreStablePlanner.Capture<LiveState>> {
        if (!root.mkdirs()) {
            return PortableRestoreDependencyRead.Unavailable("planning storage is unavailable")
        }
        val generations = LinkedHashMap<PortableRestoreFinalState.Source, String>()
        val needChats = PortableRestoreCategory.CHATS in modes ||
            PortableRestoreCategory.GENERATED_IMAGES in modes
        val chats = if (needChats) {
            val current = readCurrentChats(context)
                ?: return PortableRestoreDependencyRead.Unavailable("current chats are unavailable")
            generations[PortableRestoreFinalState.Source.CHATS] = chatToken(current)
            current
        } else null

        val generated = if (needChats) {
            val current = readCurrentGeneratedImages(context)
                ?: return PortableRestoreDependencyRead.Unavailable("generated images are unavailable")
            generations[PortableRestoreFinalState.Source.GENERATED_IMAGES] =
                Hash.hash(GeneratedImagePortableCatalog.toJson(current))
            current
        } else null

        val needIdentityUsage = PortableRestoreCategory.PROFILE_IMAGES in modes
        val usage = if (needIdentityUsage) {
            when (val read = ProfileImageUsage.readForRestore(context)) {
                is ProfileImageUsage.RestoreRead.Available -> read.references
                is ProfileImageUsage.RestoreRead.Unavailable ->
                    return PortableRestoreDependencyRead.Unavailable(read.reason)
            }
        } else emptyList()

        var identities: CompanionBackupManifest? = null
        var identityArchive: File? = null
        if (identitiesSelected) {
            val archive = File(root, "current-identities.zip")
            val manifest = (CompanionBackupExporter.buildBackupZip(context, archive) as?
                CompanionBackupExporter.BuildResult.Ok)?.manifest
                ?: return PortableRestoreDependencyRead.Unavailable("current identities are unavailable")
            identities = manifest
            identityArchive = archive
        }

        val needIdentityReferences = PortableRestoreCategory.MEMORIES in modes && !identitiesSelected
        val identityReferences = if (needIdentityReferences) {
            readCurrentMemoryReferences(context)
                ?: return PortableRestoreDependencyRead.Unavailable("identity relationships are unavailable")
        } else MemoryReferenceIds()
        if (identitiesSelected || needIdentityUsage || needIdentityReferences) {
            val tokenParts = ArrayList<String>()
            identities?.let { tokenParts.add(identityToken(it)) }
            if (needIdentityUsage) tokenParts.add(usageToken(usage))
            if (needIdentityReferences) tokenParts.add(referenceToken(identityReferences))
            generations[PortableRestoreFinalState.Source.IDENTITIES] = Hash.hash(tokenParts.joinToString("|"))
        }

        val needProfile = PortableRestoreCategory.PROFILE_IMAGES in modes || identitiesSelected
        val profile = if (needProfile) {
            val current = readCurrentProfileImages(context)
                ?: return PortableRestoreDependencyRead.Unavailable("profile images are unavailable")
            generations[PortableRestoreFinalState.Source.PROFILE_IMAGES] = profileToken(current)
            current
        } else null

        val memories = if (PortableRestoreCategory.MEMORIES in modes) {
            val current = readCurrentMemory(context, MemoryPortableGroup.MEMORIES)
                ?: return PortableRestoreDependencyRead.Unavailable("current memories are unavailable")
            generations[PortableRestoreFinalState.Source.MEMORY_ROWS] =
                Hash.hash(MemoryPortableRowFormat.toJson(MemoryPortableGroup.MEMORIES, current))
            current
        } else null
        val rules = if (PortableRestoreCategory.MODEL_RULES in modes) {
            val current = readCurrentMemory(context, MemoryPortableGroup.MODEL_RULES)
                ?: return PortableRestoreDependencyRead.Unavailable("current model rules are unavailable")
            generations[PortableRestoreFinalState.Source.MODEL_RULE_ROWS] =
                Hash.hash(MemoryPortableRowFormat.toJson(MemoryPortableGroup.MODEL_RULES, current))
            current
        } else null

        val needLorebooks = PortableRestoreCategory.LOREBOOKS in modes || identitiesSelected
        val lorebooks = if (needLorebooks) {
            val current = readCurrentLorebooks(context)
                ?: return PortableRestoreDependencyRead.Unavailable("current lorebooks are unavailable")
            generations[PortableRestoreFinalState.Source.LOREBOOKS] =
                Hash.hash(LorebookPortableCodec.toJson(current))
            current
        } else null

        val endpoints = if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            val file = File(root, "current-model-endpoints.json")
            if (ModelEndpointPortableBackup.write(context, file) is ModelEndpointPortableBackup.Result.Failed) {
                return PortableRestoreDependencyRead.Unavailable("current model settings are unavailable")
            }
            val current = (ModelEndpointPortableCodec.parse(file.readText(Charsets.UTF_8)) as?
                ModelEndpointPortableCodec.Result.Ok)?.data
                ?: return PortableRestoreDependencyRead.Unavailable("current model settings are unavailable")
            generations[PortableRestoreFinalState.Source.MODEL_ENDPOINT_SETTINGS] =
                Hash.hash(ModelEndpointPortableCodec.encode(current))
            current
        } else null

        return PortableRestoreDependencyRead.Available(
            PortableRestoreStablePlanner.Capture(
                LiveState(
                    chats,
                    generated,
                    identities,
                    identityArchive,
                    usage,
                    identityReferences,
                    profile,
                    memories,
                    rules,
                    lorebooks,
                    endpoints
                ),
                generations
            )
        )
    }

    private fun planFinalState(
        modes: Map<PortableRestoreCategory, PortableRestoreMode>,
        request: Request,
        backup: ParsedBackup,
        live: LiveState,
        identitySelections: List<CompanionCategoryPlanner.Selection>,
        generations: Map<PortableRestoreFinalState.Source, String>
    ): PlanningResult {
        var plannedChat: PlannedChat? = null
        val finalChats = if (PortableRestoreCategory.CHATS in modes) {
            val current = live.chats ?: return PlanningResult.Unavailable("current chats are unavailable")
            val incoming = backup.chats ?: return PlanningResult.Unavailable("backup chats are unavailable")
            val prepared = if (modes.getValue(PortableRestoreCategory.CHATS) == PortableRestoreMode.REPLACE) {
                PortableChatRestoreCoordinator.Prepared(incoming, PortableRestoreMode.REPLACE)
            } else {
                when (val merged = ChatMergePlanner.plan(current, incoming, request.folderResolutions)) {
                    is ChatMergePlanner.Result.NeedsFolderDecisions ->
                        return PlanningResult.FolderDecisions(merged.collisions)
                    is ChatMergePlanner.Result.Rejected -> return PlanningResult.Unavailable(merged.detail)
                    is ChatMergePlanner.Result.Ready -> PortableChatRestoreCoordinator.Prepared(
                        merged.plan,
                        PortableRestoreMode.MERGE,
                        merged.report
                    )
                }
            }
            plannedChat = PlannedChat(current, prepared)
            prepared.plan
        } else live.chats

        val chatImages = when (val scanned = PortableRestoreFinalState.scanChatImageReferences(finalChats)) {
            is PortableRestoreDependencyRead.Available -> scanned.snapshot
            is PortableRestoreDependencyRead.Unavailable -> return PlanningResult.Unavailable(scanned.reason)
        }
        var generatedReport: GeneratedImageCategoryPlanner.Report? = null
        var plannedGenerated: PlannedGenerated? = null
        val finalGenerated = if (live.generatedImages != null) {
            val current = live.generatedImages
            val incoming = backup.generatedImages ?: GeneratedImagePortableRestoreManager.Prepared(
                emptyGeneratedSnapshot(), emptyMap()
            )
            if (PortableRestoreCategory.GENERATED_IMAGES in modes) {
                val planned = GeneratedImageCategoryPlanner.plan(
                    current,
                    incoming.snapshot,
                    modes.getValue(PortableRestoreCategory.GENERATED_IMAGES),
                    chatImages.requiredAssetIds
                ) as? GeneratedImageCategoryPlanner.Result.Ready
                    ?: return PlanningResult.Unavailable("generated images could not be planned")
                generatedReport = planned.report
                plannedGenerated = PlannedGenerated(
                    GeneratedImageRestoreParticipant.PreparedPlan(
                        current, incoming, planned.snapshot, planned.report
                    ),
                    PortableRestoreCategory.GENERATED_IMAGES.key,
                    "generated_images"
                )
                planned.snapshot
            } else if (PortableRestoreCategory.CHATS in modes) {
                val currentIds = current.active.mapTo(HashSet()) { it.imageId }
                val neededFromBackup = chatImages.requiredAssetIds - currentIds
                val incomingRecords = incoming.snapshot.active.filter { it.imageId in neededFromBackup }
                if (incomingRecords.mapTo(HashSet()) { it.imageId } != neededFromBackup) {
                    return PlanningResult.Unavailable("a selected chat image is unavailable")
                }
                if (incomingRecords.isEmpty()) current else {
                    val names = incomingRecords.mapTo(HashSet()) { it.assetFileName }
                    val filtered = incoming.copy(
                        snapshot = incoming.snapshot.copy(
                            active = incomingRecords,
                            tombstones = emptyList(),
                            meta = emptyMap(),
                            backfillChats = emptyMap()
                        ),
                        assets = incoming.assets.filterKeys(names::contains)
                    )
                    val merged = GeneratedImageCategoryPlanner.plan(
                        current, filtered.snapshot, PortableRestoreMode.MERGE
                    ) as? GeneratedImageCategoryPlanner.Result.Ready
                        ?: return PlanningResult.Unavailable("chat image dependencies could not be planned")
                    generatedReport = merged.report
                    plannedGenerated = PlannedGenerated(
                        GeneratedImageRestoreParticipant.PreparedPlan(
                            current, filtered, merged.snapshot, merged.report
                        ),
                        "chat_image_dependencies",
                        "chat_images"
                    )
                    merged.snapshot
                }
            } else current
        } else null

        val currentLorebooks = live.lorebooks
        var lorebookParticipant: LorebookRestoreParticipant.PreparedPlan? = null
        val finalLorebooks = if (PortableRestoreCategory.LOREBOOKS in modes) {
            val current = currentLorebooks
                ?: return PlanningResult.Unavailable("current lorebooks are unavailable")
            val incoming = backup.lorebooks
                ?: return PlanningResult.Unavailable("backup lorebooks are unavailable")
            val planned = LorebookCategoryPlanner.plan(
                current, incoming, modes.getValue(PortableRestoreCategory.LOREBOOKS)
            ) as? LorebookCategoryPlanner.Result.Ready
                ?: return PlanningResult.Unavailable("lorebooks could not be planned")
            lorebookParticipant = LorebookRestoreParticipant.PreparedPlan(
                current, incoming, planned.data, planned.report
            )
            planned.data
        } else currentLorebooks

        var identityParticipant: CompanionCategoryRestoreParticipant.PreparedPlan? = null
        var finalIdentities: CompanionBackupManifest? = null
        var removedLorebookLinks = emptyList<org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink>()
        if (identitySelections.isNotEmpty()) {
            val current = live.identities
                ?: return PlanningResult.Unavailable("current identities are unavailable")
            val incoming = backup.identities
                ?: return PlanningResult.Unavailable("backup identities are unavailable")
            val planned = CompanionCategoryPlanner.plan(current, incoming, identitySelections) as?
                CompanionCategoryPlanner.Result.Ready
                ?: return PlanningResult.Unavailable("identities could not be planned")
            val finalLorebookIds = finalLorebooks?.books.orEmpty().mapTo(HashSet()) { it.id }
            val restorePlan = CompanionRestorePlanner.plan(planned.manifest, finalLorebookIds)
            val rollbackLorebooks = currentLorebooks?.books.orEmpty().mapTo(HashSet()) { it.id }
            val rollbackPlan = CompanionRestorePlanner.plan(current, rollbackLorebooks)
            removedLorebookLinks = restorePlan.removedLinks
            identityParticipant = CompanionCategoryRestoreParticipant.PreparedPlan(
                live.identityArchive
                    ?: return PlanningResult.Unavailable("current identity snapshot is unavailable"),
                current,
                incoming,
                planned.manifest,
                planned.report,
                restorePlan,
                rollbackPlan
            )
            finalIdentities = planned.manifest
        }

        val finalIdentityReferences = finalIdentities?.let(::memoryReferences)
            ?: live.identityReferences
        var memoryParticipant: MemoryRowsRestoreParticipant.PreparedPlan? = null
        val finalMemories = if (PortableRestoreCategory.MEMORIES in modes) {
            val current = live.memories
                ?: return PlanningResult.Unavailable("current memories are unavailable")
            val incoming = backup.memories
                ?: return PlanningResult.Unavailable("backup memories are unavailable")
            val planned = MemoryCategoryPlanner.plan(
                MemoryPortableGroup.MEMORIES,
                current,
                incoming,
                modes.getValue(PortableRestoreCategory.MEMORIES),
                finalIdentityReferences
            ) as? MemoryCategoryPlanner.Result.Ready
                ?: return PlanningResult.Unavailable("memories could not be planned")
            memoryParticipant = MemoryRowsRestoreParticipant.PreparedPlan(
                current, incoming, planned.rows, planned.report, finalIdentityReferences
            )
            planned.rows
        } else null

        var rulesParticipant: MemoryRowsRestoreParticipant.PreparedPlan? = null
        if (PortableRestoreCategory.MODEL_RULES in modes) {
            val current = live.modelRules
                ?: return PlanningResult.Unavailable("current model rules are unavailable")
            val incoming = backup.modelRules
                ?: return PlanningResult.Unavailable("backup model rules are unavailable")
            val planned = MemoryCategoryPlanner.plan(
                MemoryPortableGroup.MODEL_RULES,
                current,
                incoming,
                modes.getValue(PortableRestoreCategory.MODEL_RULES)
            ) as? MemoryCategoryPlanner.Result.Ready
                ?: return PlanningResult.Unavailable("model rules could not be planned")
            rulesParticipant = MemoryRowsRestoreParticipant.PreparedPlan(
                current, incoming, planned.rows, planned.report, MemoryReferenceIds()
            )
        }

        val finalUsage = ArrayList<PortableRestoreFinalState.IdentityProfileImageReference>()
        if (PortableRestoreCategory.PROFILE_IMAGES in modes) {
            live.identityImageUsage.forEach { reference ->
                val category = when (reference.kind) {
                    ProfileImageUsage.Kind.DEFAULT_USER_IMAGE -> null
                    ProfileImageUsage.Kind.COMPANION -> PortableRestoreCategory.COMPANIONS
                    ProfileImageUsage.Kind.MY_PERSONA -> PortableRestoreCategory.GLAMOURS
                    ProfileImageUsage.Kind.ROLEPLAY_CHARACTER -> PortableRestoreCategory.ROLEPLAY
                }
                if (category == null || category !in modes) finalUsage.add(
                    PortableRestoreFinalState.IdentityProfileImageReference(
                        category, reference.identityId, reference.imageHash
                    )
                )
            }
        }
        var profileParticipant: ProfileImageRestoreParticipant.PreparedPlan? = null
        val finalProfileRecords = if (PortableRestoreCategory.PROFILE_IMAGES in modes) {
            val current = live.profileImages
                ?: return PlanningResult.Unavailable("current profile images are unavailable")
            val incoming = backup.profileImages
                ?: return PlanningResult.Unavailable("backup profile images are unavailable")
            val protected = LinkedHashSet<String>()
            finalUsage.mapTo(protected) { it.imageHash }
            finalIdentities?.let { manifest ->
                manifest.companionProfiles.map(CompanionProfileImageHash).filter(String::isNotBlank)
                    .forEach(protected::add)
                listOf("user_personas", "roleplay_characters").forEach { table ->
                    manifest.roleplayTables[table].orEmpty().mapNotNull { it["image_ref"] as? String }
                        .filter(String::isNotBlank).forEach(protected::add)
                }
            }
            val planned = ProfileImageCategoryPlanner.plan(
                current.records,
                incoming.records,
                modes.getValue(PortableRestoreCategory.PROFILE_IMAGES),
                protected
            ) as? ProfileImageCategoryPlanner.Result.Ready
                ?: return PlanningResult.Unavailable("profile images could not be planned")
            profileParticipant = ProfileImageRestoreParticipant.PreparedPlan(
                current, incoming, planned.records, planned.report
            )
            planned.records
        } else live.profileImages?.records.orEmpty()

        var endpointParticipant: ModelEndpointRestoreParticipant.PreparedPlan? = null
        if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            val current = live.modelEndpoints
                ?: return PlanningResult.Unavailable("current model settings are unavailable")
            val incoming = backup.modelEndpoints
                ?: return PlanningResult.Unavailable("backup model settings are unavailable")
            val merged = if (modes.getValue(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS) ==
                PortableRestoreMode.REPLACE
            ) null else ModelEndpointMergePlanner.merge(current, incoming)
            endpointParticipant = ModelEndpointRestoreParticipant.PreparedPlan(
                current,
                incoming,
                merged?.data ?: incoming,
                merged?.report
            )
        }

        val remaps = request.folderResolutions.mapNotNull { (source, resolution) ->
            (resolution as? ChatMergePlanner.FolderResolution.MergeInto)?.let {
                source to it.currentFolderId
            }
        }.toMap()
        val finalState = when (val built = PortableRestoreFinalState.create(
            PortableRestoreFinalState.Inputs(
                categoryModes = modes,
                finalChats = finalChats,
                finalGeneratedImages = finalGenerated,
                finalIdentities = finalIdentities,
                additionalIdentityImageReferences = finalUsage,
                finalLorebooks = finalLorebooks,
                finalMemories = finalMemories,
                finalProfileImageAssetHashes = finalProfileRecords.mapTo(LinkedHashSet()) { it.hash },
                sourceGenerations = generations,
                removedLorebookLinks = removedLorebookLinks,
                folderRemaps = remaps,
                protectedGeneratedImageIds = generatedReport?.protectedCurrentImageIds.orEmpty().toSet(),
                protectedProfileImageHashes = profileParticipant?.report?.protectedCurrentHashes.orEmpty().toSet()
            )
        )) {
            is PortableRestoreDependencyRead.Available -> built.snapshot
            is PortableRestoreDependencyRead.Unavailable -> return PlanningResult.Unavailable(built.reason)
        }
        return PlanningResult.Ready(
            PlannedRestore(
                finalState,
                plannedChat,
                plannedGenerated,
                identityParticipant,
                profileParticipant,
                endpointParticipant,
                rulesParticipant,
                memoryParticipant,
                lorebookParticipant
            )
        )
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

    private fun readCurrentChats(context: Context): PortableChatRestorePlan.Plan? {
        val serialized = ChatLogicalSerializer.serializeV2(context) as?
            ChatLogicalSerializer.Result.Ok ?: return null
        return (PortableChatRestorePlan.parse(serialized.json) as?
            PortableChatRestorePlan.Result.Ok)?.plan
    }

    private fun readCurrentGeneratedImages(context: Context): GeneratedImageCatalogSnapshot? {
        if (!context.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME).exists()) {
            return emptyGeneratedSnapshot()
        }
        val exported = GeneratedImageCatalogStore.exportSnapshot(context)
        if (exported.state != GeneratedImageCatalogStorageState.AVAILABLE) return null
        return exported.snapshot ?: emptyGeneratedSnapshot()
    }

    private fun readCurrentProfileImages(
        context: Context
    ): ProfileImageRestoreParticipant.Snapshot? {
        if (!context.getDatabasePath(ProfileImageDb.DATABASE_NAME).exists()) {
            return ProfileImageRestoreParticipant.Snapshot(emptyList(), emptyMap())
        }
        return try {
            val store = ProfileImageStore.getInstance(context)
            val records = store.listNewestFirst()
            val assets = records.associate { record ->
                record.hash to (store.imageFile(record.hash) ?: return null)
            }
            if (assets.any { (hash, file) ->
                    !ProfileImagePortableBackup.isValidAsset(file, hash)
                }
            ) return null
            ProfileImageRestoreParticipant.Snapshot(records, assets)
        } catch (_: Exception) {
            null
        }
    }

    private fun readCurrentMemory(
        context: Context,
        group: MemoryPortableGroup
    ): MemoryPortableRows? {
        if (!MemoryStore.isProvisioned(context)) return emptyMemoryRows(group)
        if (DatabaseHealthState.isDegraded(context, BackupType.MEMORY)) return null
        return try { MemoryStore.getInstance(context).exportPortableRows(group) }
        catch (_: Exception) { null }
    }

    private fun readCurrentMemoryReferences(context: Context): MemoryReferenceIds? {
        if (!MemoryStore.isProvisioned(context)) return MemoryReferenceIds()
        if (DatabaseHealthState.isDegraded(context, BackupType.MEMORY)) return null
        return try { memoryReferences(MemoryStore.getInstance(context).exportRoleplayTables()) }
        catch (_: Exception) { null }
    }

    private fun readCurrentLorebooks(context: Context): LorebookPortableData? {
        if (!LoreBookStore.isProvisioned(context)) {
            return LorebookPortableData(emptyList(), emptyList(), emptyList())
        }
        if (DatabaseHealthState.isDegraded(context, BackupType.LOREBOOK)) return null
        return try { LoreBookStore.getInstance(context).exportPortableData() }
        catch (_: Exception) { null }
    }

    private fun readIncomingMemory(
        artifacts: List<PortablePackage.ValidatedArtifact>,
        group: MemoryPortableGroup,
        incomingIsEmpty: Boolean
    ): MemoryPortableRows? {
        val matches = artifacts.filter {
            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "memory.db"
        }
        if (matches.isEmpty() && incomingIsEmpty) return emptyMemoryRows(group)
        if (matches.size != 1) return null
        val databaseArtifact = matches.single()
        if (databaseArtifact.keySemantics != PortablePackage.KEY_SEMANTICS_PASSPHRASE) return null
        val key = decodeHex(databaseArtifact.databaseKeyHex ?: return null) ?: return null
        var database: SQLiteDatabase? = null
        return try {
            val opened = SQLiteDatabase.openDatabase(
                databaseArtifact.stagedFile.absolutePath,
                key,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
            database = opened
            if (!opened.isDatabaseIntegrityOk) return null
            MemoryPortableRowFormat.read(opened, group)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { database?.close() }
            key.fill(0)
        }
    }

    private fun readIncomingLorebooks(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        incomingIsEmpty: Boolean
    ): LorebookPortableData? {
        val matches = artifacts.filter {
            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "lorebook.db"
        }
        if (matches.isEmpty() && incomingIsEmpty) {
            return LorebookPortableData(emptyList(), emptyList(), emptyList())
        }
        if (matches.size != 1) return null
        val databaseArtifact = matches.single()
        if (databaseArtifact.keySemantics != PortablePackage.KEY_SEMANTICS_PASSPHRASE) return null
        val key = decodeHex(databaseArtifact.databaseKeyHex ?: return null) ?: return null
        val store = try {
            LoreBookStore.openForTest(context, databaseArtifact.stagedFile.absolutePath, key)
        } catch (_: Exception) {
            key.fill(0)
            return null
        }
        return try {
            store.integrityCheck()?.let { return null }
            store.exportPortableData()
        } catch (_: Exception) {
            null
        } finally {
            store.close()
            key.fill(0)
        }
    }

    private fun emptyMemoryRows(group: MemoryPortableGroup) = MemoryPortableRows(
        MemoryPortableRowFormat.specs(group).associate { it.table to emptyList() }
    )

    private fun emptyGeneratedSnapshot() = GeneratedImageCatalogSnapshot(
        active = emptyList(),
        tombstones = emptyList(),
        meta = emptyMap(),
        backfillChats = emptyMap()
    )

    private fun memoryReferences(manifest: CompanionBackupManifest): MemoryReferenceIds =
        memoryReferences(manifest.roleplayTables)

    private fun memoryReferences(
        tables: Map<String, List<Map<String, Any?>>>
    ): MemoryReferenceIds {
        fun ids(table: String, column: String): Set<String> = tables[table].orEmpty()
            .mapNotNullTo(LinkedHashSet()) { (it[column] as? String)?.takeIf(String::isNotBlank) }
        return MemoryReferenceIds(
            companions = ids("companions", "companion_id"),
            worlds = ids("worlds", "world_id"),
            campaigns = ids("campaigns", "campaign_id"),
            roleplayCharacters = ids("roleplay_characters", "roleplay_character_id"),
            userPersonas = ids("user_personas", "persona_id"),
            roleplayTags = ids("rp_tags", "tag_id")
        )
    }

    private fun chatToken(plan: PortableChatRestorePlan.Plan): String = Hash.hash(
        buildString {
            append(plan.sourceFormat).append('|')
            plan.folders.forEach {
                append(it.id).append(':').append(it.name).append(':').append(it.pinned).append('|')
            }
            plan.chats.forEach { chat ->
                append(chat.chatId).append(':').append(chat.listRow).append(':')
                    .append(chat.messagesJson).append(':').append(chat.settings).append('|')
            }
        }
    )

    private fun identityToken(manifest: CompanionBackupManifest): String = Hash.hash(
        CompanionBackupCodec.toJson(manifest.copy(appVersion = "", exportedAt = ""))
    )

    private fun usageToken(references: List<ProfileImageUsage.RestoreReference>): String = Hash.hash(
        references.sortedWith(compareBy({ it.kind.name }, { it.identityId }, { it.imageHash }))
            .joinToString("|") { "${it.kind}:${it.identityId}:${it.imageHash}" }
    )

    private fun referenceToken(references: MemoryReferenceIds): String = Hash.hash(
        listOf(
            references.companions,
            references.worlds,
            references.campaigns,
            references.roleplayCharacters,
            references.userPersonas,
            references.roleplayTags
        ).joinToString("|") { it.sorted().joinToString(",") }
    )

    private fun profileToken(snapshot: ProfileImageRestoreParticipant.Snapshot): String = Hash.hash(
        snapshot.records.joinToString("|") { "${it.hash}:${it.createdAt}" }
    )

    private fun decodeHex(value: String): ByteArray? {
        if (value.length != 64 || !value.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return try {
            ByteArray(32) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private val IDENTITY_CATEGORIES = setOf(
        PortableRestoreCategory.COMPANIONS,
        PortableRestoreCategory.GLAMOURS,
        PortableRestoreCategory.ROLEPLAY,
        PortableRestoreCategory.ACTIVATION_PROMPTS,
        PortableRestoreCategory.SYSTEM_PROMPTS
    )

    private val CompanionProfileImageHash:
        (org.teslasoft.assistant.preferences.backup.companion.CompanionProfileEntry) -> String =
        { it.avatarRef }

    private const val JOURNAL_DIRECTORY = "selected_category_restore_journal"
    private const val STAGING_DIRECTORY = "selected_category_restore_staging"
    private const val FOLDER_DECISIONS_REQUIRED = "folder decisions required"

    private fun transactionStagingRoot(context: Context): File =
        File(context.filesDir, STAGING_DIRECTORY)
}
