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
import org.teslasoft.assistant.preferences.lorebook.LoreBookEncryption
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRowFormat
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRows
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
        val explicitlyEmptyCategories: Set<PortableRestoreCategory> = emptySet(),
        /** Manifest record counts; each is checked only for a selected category. */
        val declaredRecordCounts: Map<PortableRestoreCategory, Long> = emptyMap()
    )

    sealed class BuildResult {
        data class Ready(
            val participants: List<SelectedCategoryRestoreTransaction.Participant>,
            val chatParticipant: ChatRestoreParticipant?,
            val finalState: PortableRestoreFinalState,
            /** Selected categories set aside because their own data, or the
             * current data they must be planned against, could not be used.
             * Every other selected category is still restored. */
            val categoryFailures: List<CategoryFailure> = emptyList(),
            /** Which selected categories each participant writes; the two
             * shared participants cover several. Diagnostics only. */
            val participantCategories: Map<String, List<PortableRestoreCategory>> = emptyMap()
        ) : BuildResult()
        data class NeedsFolderDecisions(
            val collisions: List<ChatMergePlanner.FolderCollision>
        ) : BuildResult()
        data class Failed(
            val reason: BuildFailure,
            val category: PortableRestoreCategory? = null,
            /** Diagnostic detail for the Error Log only; never shown in a dialog. */
            val detail: String? = null
        ) : BuildResult()
        /** Every selected category failed on its own; nothing can be restored. */
        data class NothingRestorable(val failures: List<CategoryFailure>) : BuildResult()
    }

    /**
     * Stage 1 isolates failures per selected CATEGORY. It does not yet recover
     * individual records: a category whose reader rejects its data is reported
     * as not restored, with the most specific reason the reader provides.
     */
    data class CategoryFailure(
        val category: PortableRestoreCategory,
        val reason: CategoryFailureReason,
        val chatReason: PortableChatRestorePlan.Reason? = null,
        /** The internal reason, for the Error Log only; never shown in a dialog.
         * Names only steps, tables, columns, counts and types. */
        val detail: String? = null
    )

    enum class CategoryFailureReason {
        /** The category's data is not in the decoded backup. */
        MISSING_DATA,
        /** The category's reader rejected its data. */
        INVALID_DATA,
        /** The Chats reader rejected the chat data; [CategoryFailure.chatReason] says why. */
        CHAT_DATA,
        /** The category holds a different number of records than the manifest declares. */
        COUNT_MISMATCH,
        /** The data currently on this device could not be read to plan against. */
        CURRENT_DATA_UNAVAILABLE,
        /** The category could not be combined with the current data safely. */
        PLANNING_FAILED
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
            return BuildResult.Failed(
                BuildFailure.DEPENDENCY_VALIDATION_FAILED, detail = "a category was selected twice"
            )
        }
        if (modes[PortableRestoreCategory.SETTINGS]?.let { it != PortableRestoreMode.REPLACE } == true) {
            return BuildResult.Failed(
                BuildFailure.DEPENDENCY_VALIDATION_FAILED,
                PortableRestoreCategory.SETTINGS,
                "settings can only be restored in Replace mode"
            )
        }
        val failures = LinkedHashMap<PortableRestoreCategory, CategoryFailure>()
        val parsedBackup = parseBackup(app, artifacts, request, modes, failures)
        val planningRoot = File(stagingRoot, "final_state_planning")
        if (planningRoot.exists() && !planningRoot.deleteRecursively()) {
            return BuildResult.Failed(
                BuildFailure.DEPENDENCY_VALIDATION_FAILED, detail = "planning storage could not be cleared"
            )
        }
        if (!planningRoot.mkdirs()) {
            return BuildResult.Failed(
                BuildFailure.DEPENDENCY_VALIDATION_FAILED, detail = "planning storage could not be created"
            )
        }

        // A selected category that cannot be planned is set aside and the rest
        // are planned again. Only a failure no category can be blamed for
        // stops the whole restore.
        var active: Map<PortableRestoreCategory, PortableRestoreMode> =
            modes.filterKeys { it !in failures }
        var pass = 0
        while (true) {
            if (active.isEmpty()) return BuildResult.NothingRestorable(failures.values.toList())
            val passModes = active
            val identitySelections = request.selections
                .filter { it.category in IDENTITY_CATEGORIES && it.category in passModes }
                .map { CompanionCategoryPlanner.Selection(it.category, it.mode) }
            val passRoot = File(planningRoot, "pass_${pass++}")
            val blame = LinkedHashMap<PortableRestoreCategory, CategoryFailure>()
            var folderCollisions: List<ChatMergePlanner.FolderCollision> = emptyList()
            // An unexpected error is recorded as the reason instead of
            // escaping and ending the app; nothing has been written yet.
            val stable = PortableRestoreStablePlanner.plan(
                capture = { attempt, verification ->
                    try {
                        captureLiveState(
                            app,
                            passModes,
                            identitySelections.isNotEmpty(),
                            File(passRoot, "attempt_${attempt}_${if (verification) "after" else "before"}"),
                            blame
                        )
                    } catch (e: Exception) {
                        PortableRestoreDependencyRead.Unavailable(
                            "reading current device data: ${PortableRestoreDiagnostics.unexpected(e)}"
                        )
                    }
                },
                planner = { live, generations ->
                    val planned = try {
                        planFinalState(
                            passModes,
                            request,
                            parsedBackup,
                            live,
                            identitySelections,
                            generations,
                            blame
                        )
                    } catch (e: Exception) {
                        PlanningResult.Unavailable(
                            "category planning: ${PortableRestoreDiagnostics.unexpected(e)}"
                        )
                    }
                    when (planned) {
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
                    val culprits = blame.filterKeys { it in passModes }
                    if (culprits.isEmpty()) {
                        return BuildResult.Failed(
                            BuildFailure.DEPENDENCY_VALIDATION_FAILED, detail = stable.reason
                        )
                    }
                    culprits.forEach { (category, failure) -> failures.putIfAbsent(category, failure) }
                    active = passModes.filterKeys { it !in culprits }
                    continue
                }
                is PortableRestoreStablePlanner.Result.ChangedTwice ->
                    return BuildResult.Failed(
                        BuildFailure.DEPENDENCY_VALIDATION_FAILED,
                        detail = "current device data changed during both planning attempts: " +
                            stable.changedSources.joinToString(",") { it.name }
                    )
            }

            val participants = ArrayList<SelectedCategoryRestoreTransaction.Participant>()
            val covered = LinkedHashMap<String, List<PortableRestoreCategory>>()
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
            planned.sharedMemory?.let {
                participants.add(CompanionMemoryRestoreParticipant(
                    app, it, File(stagingRoot, CompanionMemoryRestoreParticipant.CATEGORY_KEY)
                ))
            }
            planned.profileImages?.let {
                participants.add(ProfileImageRestoreParticipant(app, it, File(stagingRoot, "profile_images")))
            }
            planned.modelEndpoints?.let {
                participants.add(ModelEndpointRestoreParticipant(app, it, File(stagingRoot, "model_endpoints")))
            }
            planned.lorebooks?.let {
                participants.add(LorebookRestoreParticipant(app, it, File(stagingRoot, "lorebooks")))
            }
            planned.settings?.let {
                participants.add(AppSettingsRestoreParticipant(app, File(stagingRoot, "app_settings"), it))
            }
            participants.forEach { participant ->
                covered[participant.categoryKey] = coveredCategories(participant.categoryKey, passModes.keys)
            }
            return BuildResult.Ready(
                participants, chatParticipant, planned.finalState, failures.values.toList(), covered
            )
        }
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
        val modelEndpoints: ModelEndpointPortableCodec.Data? = null,
        val settings: AppSettingsPortableData? = null
    )

    private data class LiveState(
        val chats: PortableChatRestorePlan.Plan? = null,
        val generatedImages: GeneratedImageCatalogSnapshot? = null,
        val identities: CompanionBackupManifest? = null,
        val identityArchive: File? = null,
        val identityImageUsage: List<ProfileImageUsage.RestoreReference> = emptyList(),
        val identityReferences: MemoryReferenceIds = MemoryReferenceIds(),
        val profileImages: ProfileImageRestoreParticipant.Snapshot? = null,
        val sharedMemory: MemorySharedRestoreRows? = null,
        val memories: MemoryPortableRows? = null,
        val modelRules: MemoryPortableRows? = null,
        val lorebooks: LorebookPortableData? = null,
        val modelEndpoints: ModelEndpointPortableCodec.Data? = null,
        val settings: AppSettingsPortableData? = null
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
        val sharedMemory: CompanionMemoryRestoreParticipant.PreparedPlan? = null,
        val profileImages: ProfileImageRestoreParticipant.PreparedPlan? = null,
        val modelEndpoints: ModelEndpointRestoreParticipant.PreparedPlan? = null,
        val lorebooks: LorebookRestoreParticipant.PreparedPlan? = null,
        val settings: AppSettingsRestoreParticipant.PreparedPlan? = null
    )

    private sealed interface PlanningResult {
        data class Ready(val plan: PlannedRestore) : PlanningResult
        data class FolderDecisions(
            val collisions: List<ChatMergePlanner.FolderCollision>
        ) : PlanningResult
        data class Unavailable(val reason: String) : PlanningResult
    }

    /**
     * Reads each selected category on its own. A category whose data is
     * missing, rejected by its reader, or inconsistent with the manifest count
     * is recorded in [failures] and left out; it never prevents the other
     * selected categories from being read.
     */
    private fun parseBackup(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        request: Request,
        modes: Map<PortableRestoreCategory, PortableRestoreMode>,
        failures: MutableMap<PortableRestoreCategory, CategoryFailure>
    ): ParsedBackup {
        fun fail(
            category: PortableRestoreCategory,
            reason: CategoryFailureReason,
            chatReason: PortableChatRestorePlan.Reason? = null,
            detail: String? = null
        ) {
            failures.putIfAbsent(category, CategoryFailure(category, reason, chatReason, detail))
        }
        fun <T> read(categories: Collection<PortableRestoreCategory>, block: () -> T?): T? = try {
            block()
        } catch (e: Exception) {
            categories.forEach {
                fail(it, CategoryFailureReason.INVALID_DATA, detail = PortableRestoreDiagnostics.unexpected(e))
            }
            null
        }
        // The most recent reader's reason, consumed by the next fail call.
        var readerDetail: String? = null
        val reject: (String) -> Unit = { readerDetail = it }
        fun takeReaderDetail(): String? = readerDetail.also { readerDetail = null }

        val chats = if (PortableRestoreCategory.CHATS in modes) {
            read(listOf(PortableRestoreCategory.CHATS)) {
                val source = artifact(artifacts, PortablePackage.TYPE_CHATS_JSON)?.stagedFile
                if (source == null) {
                    fail(
                        PortableRestoreCategory.CHATS, CategoryFailureReason.MISSING_DATA,
                        detail = "no chats artifact in the backup"
                    )
                    null
                } else when (val parsed = PortableChatRestorePlan.parse(source.readText(Charsets.UTF_8))) {
                    is PortableChatRestorePlan.Result.Ok -> parsed.plan
                    is PortableChatRestorePlan.Result.Rejected -> {
                        // The reader's own detail names chat and message IDs,
                        // which must not be logged; its reason code is enough.
                        fail(
                            PortableRestoreCategory.CHATS,
                            CategoryFailureReason.CHAT_DATA,
                            parsed.reason,
                            "chats reader rejected the data: ${parsed.reason.name}"
                        )
                        null
                    }
                }
            }
        } else null

        // Restored chats need the generated images they show. When the
        // gallery data is unusable the chats are still restored and the images
        // they cannot show are reported as missing references.
        val generatedSelected = PortableRestoreCategory.GENERATED_IMAGES in modes
        val needGenerated = generatedSelected || chats != null
        val generated = if (needGenerated) {
            var prepareError: String? = null
            val prepared = try {
                GeneratedImagePortableRestoreManager.prepare(artifacts)
            } catch (e: Exception) {
                prepareError = PortableRestoreDiagnostics.unexpected(e)
                GeneratedImagePortableRestoreManager.PrepareResult.Invalid(
                    GeneratedImagePortableRestoreManager.InvalidReason.INVALID_CATALOG
                )
            }
            when (prepared) {
                is GeneratedImagePortableRestoreManager.PrepareResult.Ready -> prepared.prepared
                GeneratedImagePortableRestoreManager.PrepareResult.Absent -> {
                    if (generatedSelected &&
                        PortableRestoreCategory.GENERATED_IMAGES !in request.explicitlyEmptyCategories
                    ) fail(
                        PortableRestoreCategory.GENERATED_IMAGES, CategoryFailureReason.MISSING_DATA,
                        detail = "no generated image catalog in the backup"
                    )
                    GeneratedImagePortableRestoreManager.Prepared(emptyGeneratedSnapshot(), emptyMap())
                }
                is GeneratedImagePortableRestoreManager.PrepareResult.Invalid -> {
                    if (generatedSelected) {
                        fail(
                            PortableRestoreCategory.GENERATED_IMAGES, CategoryFailureReason.INVALID_DATA,
                            detail = "generated image catalog rejected: " +
                                (prepareError ?: prepared.reason.name)
                        )
                    }
                    GeneratedImagePortableRestoreManager.Prepared(emptyGeneratedSnapshot(), emptyMap())
                }
            }
        } else null

        val identitySelections = modes.keys intersect IDENTITY_CATEGORIES
        var identityArtifact: PortablePackage.ValidatedArtifact? = null
        val identities = if (identitySelections.isNotEmpty()) {
            read(identitySelections) {
                val found = artifact(artifacts, PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE)
                if (found == null) {
                    identitySelections.forEach {
                        fail(
                            it, CategoryFailureReason.MISSING_DATA,
                            detail = "no companion and roleplay archive in the backup"
                        )
                    }
                    null
                } else {
                    val (verdict, verdictDetail) = CompanionBackupValidator.validateWithDetail(found.stagedFile)
                    val manifest = (verdict as? CompanionBackupValidator.Verdict.Valid)?.manifest
                    if (manifest == null) {
                        identitySelections.forEach {
                            fail(
                                it, CategoryFailureReason.INVALID_DATA,
                                detail = "companion and roleplay archive rejected: " +
                                    "${verdict.javaClass.simpleName}: ${verdictDetail ?: "no_reason_given"}"
                            )
                        }
                    } else identityArtifact = found
                    manifest
                }
            }
        } else null

        val profileCatalogPresent = artifacts.any {
            it.type == PortablePackage.TYPE_SQLITE_DB && it.entryName == "user_images.db"
        }
        val profileImages = if (PortableRestoreCategory.PROFILE_IMAGES in modes) {
            read(listOf(PortableRestoreCategory.PROFILE_IMAGES)) {
                if (PortableRestoreCategory.PROFILE_IMAGES in request.explicitlyEmptyCategories &&
                    !profileCatalogPresent
                ) {
                    ProfileImagePortableRestoreManager.Prepared(emptyList(), emptyMap())
                } else {
                    (ProfileImagePortableRestoreManager.prepare(artifacts) as?
                        ProfileImagePortableRestoreManager.Result.Ready)?.prepared
                        ?: run {
                            fail(
                                PortableRestoreCategory.PROFILE_IMAGES,
                                invalidOrMissing(profileCatalogPresent),
                                detail = if (profileCatalogPresent) {
                                    "profile image catalog or its image files were rejected"
                                } else "no profile image catalog in the backup"
                            )
                            null
                        }
                }
            }
        } else null

        val memoryPresent = artifacts.any {
            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "memory.db"
        }
        val memories = if (PortableRestoreCategory.MEMORIES in modes) {
            read(listOf(PortableRestoreCategory.MEMORIES)) {
                readIncomingMemory(
                    artifacts,
                    MemoryPortableGroup.MEMORIES,
                    PortableRestoreCategory.MEMORIES in request.explicitlyEmptyCategories,
                    reject
                ) ?: run {
                    fail(
                        PortableRestoreCategory.MEMORIES, invalidOrMissing(memoryPresent),
                        detail = takeReaderDetail()
                    )
                    null
                }
            }
        } else null
        val rules = if (PortableRestoreCategory.MODEL_RULES in modes) {
            read(listOf(PortableRestoreCategory.MODEL_RULES)) {
                readIncomingMemory(
                    artifacts,
                    MemoryPortableGroup.MODEL_RULES,
                    PortableRestoreCategory.MODEL_RULES in request.explicitlyEmptyCategories,
                    reject
                ) ?: run {
                    fail(
                        PortableRestoreCategory.MODEL_RULES, invalidOrMissing(memoryPresent),
                        detail = takeReaderDetail()
                    )
                    null
                }
            }
        } else null
        val lorebooks = if (PortableRestoreCategory.LOREBOOKS in modes) {
            read(listOf(PortableRestoreCategory.LOREBOOKS)) {
                readIncomingLorebooks(
                    context,
                    artifacts,
                    PortableRestoreCategory.LOREBOOKS in request.explicitlyEmptyCategories,
                    reject
                ) ?: run {
                    fail(
                        PortableRestoreCategory.LOREBOOKS,
                        invalidOrMissing(artifacts.any {
                            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "lorebook.db"
                        }),
                        detail = takeReaderDetail()
                    )
                    null
                }
            }
        } else null
        val endpoints = if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            read(listOf(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS)) {
                val source = artifact(artifacts, PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS)?.stagedFile
                if (source == null) {
                    fail(
                        PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS, CategoryFailureReason.MISSING_DATA,
                        detail = "no model settings artifact in the backup"
                    )
                    null
                } else if (!source.isFile || source.length() > ModelEndpointPortableCodec.MAX_ARTIFACT_BYTES) {
                    fail(
                        PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS, CategoryFailureReason.INVALID_DATA,
                        detail = "model settings artifact is missing or larger than the size limit"
                    )
                    null
                } else (ModelEndpointPortableCodec.parse(source.readText(Charsets.UTF_8)) as?
                    ModelEndpointPortableCodec.Result.Ok)?.data
                    ?: run {
                        fail(
                            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS, CategoryFailureReason.INVALID_DATA,
                            detail = "model settings codec rejected the backup data"
                        )
                        null
                    }
            }
        } else null
        val settings = if (PortableRestoreCategory.SETTINGS in modes) {
            read(listOf(PortableRestoreCategory.SETTINGS)) {
                val source = artifact(artifacts, PortablePackage.TYPE_APP_SETTINGS)?.stagedFile
                if (source == null) {
                    fail(
                        PortableRestoreCategory.SETTINGS, CategoryFailureReason.MISSING_DATA,
                        detail = "no app settings artifact in the backup"
                    )
                    null
                } else if (!source.isFile || source.length() > PortableRecoveryLimits.APP_SETTINGS_BYTES) {
                    fail(
                        PortableRestoreCategory.SETTINGS, CategoryFailureReason.INVALID_DATA,
                        detail = "app settings artifact is missing or larger than the size limit"
                    )
                    null
                } else (AppSettingsPortableCodec.parse(source.readText(Charsets.UTF_8)) as?
                    AppSettingsPortableCodec.Result.Ok)?.data
                    ?: run {
                        fail(
                            PortableRestoreCategory.SETTINGS, CategoryFailureReason.INVALID_DATA,
                            detail = "app settings codec rejected the backup data"
                        )
                        null
                    }
            }
        } else null

        // The manifest's declared count is checked for each selected category
        // that was read, never for an unselected one.
        if (request.declaredRecordCounts.isNotEmpty()) {
            val counts = PortableRecoverySemanticValidator.recordCounts(
                chats, generated, identities, profileImages, memories, rules, lorebooks, endpoints, settings
            )
            modes.keys.filter { it !in failures }.forEach { category ->
                val declared = request.declaredRecordCounts[category] ?: return@forEach
                if (counts[category] != declared) fail(
                    category, CategoryFailureReason.COUNT_MISMATCH,
                    detail = "manifest declares $declared records, backup data holds ${counts[category]}"
                )
            }
        }

        return ParsedBackup(
            chats.takeIf { PortableRestoreCategory.CHATS !in failures },
            generated,
            identities.takeIf { identitySelections.none(failures::containsKey) },
            identityArtifact?.stagedFile,
            profileImages.takeIf { PortableRestoreCategory.PROFILE_IMAGES !in failures },
            memories.takeIf { PortableRestoreCategory.MEMORIES !in failures },
            rules.takeIf { PortableRestoreCategory.MODEL_RULES !in failures },
            lorebooks.takeIf { PortableRestoreCategory.LOREBOOKS !in failures },
            endpoints.takeIf { PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS !in failures },
            settings.takeIf { PortableRestoreCategory.SETTINGS !in failures }
        )
    }

    private fun invalidOrMissing(present: Boolean): CategoryFailureReason =
        if (present) CategoryFailureReason.INVALID_DATA else CategoryFailureReason.MISSING_DATA

    private fun captureLiveState(
        context: Context,
        modes: Map<PortableRestoreCategory, PortableRestoreMode>,
        identitiesSelected: Boolean,
        root: File,
        blame: MutableMap<PortableRestoreCategory, CategoryFailure>? = null
    ): PortableRestoreDependencyRead<PortableRestoreStablePlanner.Capture<LiveState>> {
        val identityModes = modes.keys intersect IDENTITY_CATEGORIES
        // The most recent reader's reason, appended to the next unavailable().
        var readerDetail: String? = null
        val reject: (String) -> Unit = { readerDetail = it }
        fun unavailable(
            reason: String,
            categories: Collection<PortableRestoreCategory>
        ): PortableRestoreDependencyRead.Unavailable {
            val detail = readerDetail?.let { "$reason: $it" } ?: reason
            readerDetail = null
            blame?.let { target ->
                categories.filter(modes::containsKey).forEach {
                    target.putIfAbsent(
                        it,
                        CategoryFailure(it, CategoryFailureReason.CURRENT_DATA_UNAVAILABLE, detail = detail)
                    )
                }
            }
            return PortableRestoreDependencyRead.Unavailable(detail)
        }
        fun firstSelected(vararg groups: Collection<PortableRestoreCategory>): List<PortableRestoreCategory> =
            groups.map { group -> group.filter(modes::containsKey) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (!root.mkdirs()) {
            return PortableRestoreDependencyRead.Unavailable("planning storage is unavailable")
        }
        val generations = LinkedHashMap<PortableRestoreFinalState.Source, String>()
        val needChats = PortableRestoreCategory.CHATS in modes ||
            PortableRestoreCategory.GENERATED_IMAGES in modes
        val chats = if (needChats) {
            val current = readCurrentChats(context, reject)
                ?: return unavailable(
                    "current chats are unavailable",
                    firstSelected(
                        listOf(PortableRestoreCategory.CHATS),
                        listOf(PortableRestoreCategory.GENERATED_IMAGES)
                    )
                )
            generations[PortableRestoreFinalState.Source.CHATS] = chatToken(current)
            current
        } else null

        val generated = if (needChats) {
            val current = readCurrentGeneratedImages(context, reject)
                ?: return unavailable(
                    "generated images are unavailable",
                    firstSelected(
                        listOf(PortableRestoreCategory.GENERATED_IMAGES),
                        listOf(PortableRestoreCategory.CHATS)
                    )
                )
            generations[PortableRestoreFinalState.Source.GENERATED_IMAGES] =
                Hash.hash(GeneratedImagePortableCatalog.toJson(current))
            current
        } else null

        val needIdentityUsage = PortableRestoreCategory.PROFILE_IMAGES in modes
        val usage = if (needIdentityUsage) {
            when (val read = ProfileImageUsage.readForRestore(context)) {
                is ProfileImageUsage.RestoreRead.Available -> read.references
                is ProfileImageUsage.RestoreRead.Unavailable ->
                    return unavailable(
                        "profile image usage is unavailable: ${read.reason}",
                        listOf(PortableRestoreCategory.PROFILE_IMAGES)
                    )
            }
        } else emptyList()

        var identities: CompanionBackupManifest? = null
        var identityArchive: File? = null
        if (identitiesSelected) {
            val archive = File(root, "current-identities.zip")
            val manifest = when (val built = CompanionBackupExporter.buildBackupZip(context, archive)) {
                is CompanionBackupExporter.BuildResult.Ok -> built.manifest
                else -> {
                    readerDetail = "export refused: ${built.javaClass.simpleName}"
                    null
                }
            } ?: return unavailable("current identities are unavailable", identityModes)
            identities = manifest
            identityArchive = archive
        }

        val sharedStoreSelected = identitiesSelected ||
            PortableRestoreCategory.MEMORIES in modes ||
            PortableRestoreCategory.MODEL_RULES in modes
        val sharedMemory = if (sharedStoreSelected) {
            readCurrentSharedMemory(context, reject)
                ?: return unavailable(
                    "current shared memory data is unavailable",
                    firstSelected(
                        listOf(PortableRestoreCategory.MEMORIES),
                        listOf(PortableRestoreCategory.MODEL_RULES),
                        identityModes
                    )
                )
        } else null
        if (sharedMemory != null) {
            MemorySharedRestoreRowFormat.invalidReason(sharedMemory)?.let {
                readerDetail = it
                return unavailable(
                    "current shared memory rows are not valid",
                    firstSelected(
                        listOf(PortableRestoreCategory.MEMORIES),
                        listOf(PortableRestoreCategory.MODEL_RULES),
                        identityModes
                    )
                )
            }
            generations[PortableRestoreFinalState.Source.COMPANION_MEMORY_STORE] =
                Hash.hash(MemorySharedRestoreRowFormat.toJson(sharedMemory))
        }
        if (identities != null && sharedMemory != null) {
            val sharedRoleplay = CompanionMemoryRestorePlanner.roleplayTables(sharedMemory)
            if (!sameRoleplayRows(identities.roleplayTables, sharedRoleplay)) {
                readerDetail = PortableRestoreDiagnostics.tableDifference(
                    identities.roleplayTables, sharedRoleplay, "roleplay "
                )
                return unavailable(
                    "identity data changed while the shared database snapshot was captured",
                    identityModes
                )
            }
        }

        val needIdentityReferences = PortableRestoreCategory.MEMORIES in modes && !identitiesSelected
        val identityReferences = if (needIdentityReferences) {
            sharedMemory?.let(CompanionMemoryRestorePlanner::roleplayTables)
                ?.let(::memoryReferences)
                ?: return unavailable(
                    "identity relationships are unavailable",
                    listOf(PortableRestoreCategory.MEMORIES)
                )
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
            val current = readCurrentProfileImages(context, reject)
                ?: return unavailable(
                    "profile images are unavailable",
                    firstSelected(listOf(PortableRestoreCategory.PROFILE_IMAGES), identityModes)
                )
            generations[PortableRestoreFinalState.Source.PROFILE_IMAGES] = profileToken(current)
            current
        } else null

        val memories = if (PortableRestoreCategory.MEMORIES in modes || identitiesSelected) {
            val current = sharedMemory?.let(CompanionMemoryRestorePlanner::memoryRows)
                ?: return unavailable(
                    "current memories are unavailable",
                    firstSelected(listOf(PortableRestoreCategory.MEMORIES), identityModes)
                )
            generations[PortableRestoreFinalState.Source.MEMORY_ROWS] =
                Hash.hash(MemoryPortableRowFormat.toJson(MemoryPortableGroup.MEMORIES, current))
            current
        } else null
        val rules = if (PortableRestoreCategory.MODEL_RULES in modes) {
            val current = sharedMemory?.let(CompanionMemoryRestorePlanner::modelRuleRows)
                ?: return unavailable(
                    "current model rules are unavailable",
                    listOf(PortableRestoreCategory.MODEL_RULES)
                )
            generations[PortableRestoreFinalState.Source.MODEL_RULE_ROWS] =
                Hash.hash(MemoryPortableRowFormat.toJson(MemoryPortableGroup.MODEL_RULES, current))
            current
        } else null

        val needLorebooks = PortableRestoreCategory.LOREBOOKS in modes || identitiesSelected
        val lorebooks = if (needLorebooks) {
            val current = readCurrentLorebooks(context, reject)
                ?: return unavailable(
                    "current lorebooks are unavailable",
                    firstSelected(listOf(PortableRestoreCategory.LOREBOOKS), identityModes)
                )
            generations[PortableRestoreFinalState.Source.LOREBOOKS] =
                Hash.hash(LorebookPortableCodec.toJson(current))
            current
        } else null

        val endpoints = if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            val file = File(root, "current-model-endpoints.json")
            if (ModelEndpointPortableBackup.write(context, file) is ModelEndpointPortableBackup.Result.Failed) {
                readerDetail = "current model settings could not be exported"
                return unavailable(
                    "current model settings are unavailable",
                    listOf(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS)
                )
            }
            val current = (ModelEndpointPortableCodec.parse(file.readText(Charsets.UTF_8)) as?
                ModelEndpointPortableCodec.Result.Ok)?.data
                ?: run {
                    readerDetail = "model settings codec rejected the current settings"
                    return unavailable(
                        "current model settings are unavailable",
                        listOf(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS)
                    )
                }
            generations[PortableRestoreFinalState.Source.MODEL_ENDPOINT_SETTINGS] =
                Hash.hash(ModelEndpointPortableCodec.encode(current))
            current
        } else null

        val settings = if (PortableRestoreCategory.SETTINGS in modes) {
            val captured = AppSettingsPortableStore.capture(context)
            val current = captured.getOrNull()
                ?: run {
                    readerDetail = captured.exceptionOrNull()?.let(PortableRestoreDiagnostics::unexpected)
                    return unavailable(
                        "current app settings are unavailable",
                        listOf(PortableRestoreCategory.SETTINGS)
                    )
                }
            generations[PortableRestoreFinalState.Source.APP_SETTINGS] =
                Hash.hash(AppSettingsPortableCodec.encode(current))
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
                    sharedMemory,
                    memories,
                    rules,
                    lorebooks,
                    endpoints,
                    settings
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
        generations: Map<PortableRestoreFinalState.Source, String>,
        blame: MutableMap<PortableRestoreCategory, CategoryFailure>
    ): PlanningResult {
        val identityModes = modes.keys intersect IDENTITY_CATEGORIES
        fun blamed(
            reason: String,
            failure: CategoryFailureReason,
            categories: Collection<PortableRestoreCategory>
        ): PlanningResult.Unavailable {
            categories.filter(modes::containsKey).forEach {
                blame.putIfAbsent(it, CategoryFailure(it, failure, detail = reason))
            }
            return PlanningResult.Unavailable(reason)
        }
        fun firstSelected(vararg groups: Collection<PortableRestoreCategory>): List<PortableRestoreCategory> =
            groups.map { group -> group.filter(modes::containsKey) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        // The shared memory store carries Memories, Model Rules and identity
        // rows together; set aside one selected group at a time, most specific first.
        fun sharedMemoryCulprit(): List<PortableRestoreCategory> = firstSelected(
            listOf(PortableRestoreCategory.MEMORIES),
            listOf(PortableRestoreCategory.MODEL_RULES),
            identityModes
        )
        val currentFailure = CategoryFailureReason.CURRENT_DATA_UNAVAILABLE
        val planningFailure = CategoryFailureReason.PLANNING_FAILED
        val chatsOrGallery = firstSelected(
            listOf(PortableRestoreCategory.CHATS), listOf(PortableRestoreCategory.GENERATED_IMAGES)
        )
        var plannedChat: PlannedChat? = null
        val finalChats = if (PortableRestoreCategory.CHATS in modes) {
            val current = live.chats ?: return blamed(
                "current chats are unavailable",
                currentFailure,
                listOf(PortableRestoreCategory.CHATS)
            )
            val incoming = backup.chats ?: return blamed(
                "backup chats are unavailable",
                planningFailure,
                listOf(PortableRestoreCategory.CHATS)
            )
            val prepared = if (modes.getValue(PortableRestoreCategory.CHATS) == PortableRestoreMode.REPLACE) {
                PortableChatRestoreCoordinator.Prepared(incoming, PortableRestoreMode.REPLACE)
            } else {
                when (val merged = ChatMergePlanner.plan(current, incoming, request.folderResolutions)) {
                    is ChatMergePlanner.Result.NeedsFolderDecisions ->
                        return PlanningResult.FolderDecisions(merged.collisions)
                    // The merge planner's detail can name a folder ID; it is not logged.
                    is ChatMergePlanner.Result.Rejected ->
                        return blamed(
                            "chats could not be merged with the current chats",
                            planningFailure,
                            listOf(PortableRestoreCategory.CHATS)
                        )
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
            is PortableRestoreDependencyRead.Unavailable ->
                return blamed(scanned.reason, planningFailure, chatsOrGallery)
        }
        var generatedReport: GeneratedImageCategoryPlanner.Report? = null
        var plannedGenerated: PlannedGenerated? = null
        val finalGenerated = if (live.generatedImages != null) {
            val current = live.generatedImages
            val incoming = backup.generatedImages ?: GeneratedImagePortableRestoreManager.Prepared(
                emptyGeneratedSnapshot(), emptyMap()
            )
            if (PortableRestoreCategory.GENERATED_IMAGES in modes) {
                val plannedResult = GeneratedImageCategoryPlanner.plan(
                    current,
                    incoming.snapshot,
                    modes.getValue(PortableRestoreCategory.GENERATED_IMAGES),
                    chatImages.requiredAssetIds
                )
                val planned = plannedResult as? GeneratedImageCategoryPlanner.Result.Ready
                    ?: return blamed(
                        "generated images could not be planned: " +
                            ((plannedResult as? GeneratedImageCategoryPlanner.Result.Rejected)?.reason?.name
                                ?: plannedResult.javaClass.simpleName),
                        planningFailure,
                        listOf(PortableRestoreCategory.GENERATED_IMAGES)
                    )
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
                // An image the backup does not carry stays a missing reference:
                // the chat is still restored and the image is reported.
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
                        ?: return blamed(
                            "chat image dependencies could not be planned",
                            planningFailure,
                            listOf(PortableRestoreCategory.CHATS)
                        )
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
                ?: return blamed(
                    "current lorebooks are unavailable",
                    currentFailure,
                    listOf(PortableRestoreCategory.LOREBOOKS)
                )
            val incoming = backup.lorebooks
                ?: return blamed(
                    "backup lorebooks are unavailable",
                    planningFailure,
                    listOf(PortableRestoreCategory.LOREBOOKS)
                )
            val planned = LorebookCategoryPlanner.plan(
                current, incoming, modes.getValue(PortableRestoreCategory.LOREBOOKS)
            ) as? LorebookCategoryPlanner.Result.Ready
                ?: return blamed(
                    "lorebooks could not be planned",
                    planningFailure,
                    listOf(PortableRestoreCategory.LOREBOOKS)
                )
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
                ?: return blamed("current identities are unavailable", currentFailure, identityModes)
            val incoming = backup.identities
                ?: return blamed("backup identities are unavailable", planningFailure, identityModes)
            val plannedResult = CompanionCategoryPlanner.plan(current, incoming, identitySelections)
            val planned = plannedResult as? CompanionCategoryPlanner.Result.Ready
                ?: return blamed(
                    "identities could not be planned: " +
                        ((plannedResult as? CompanionCategoryPlanner.Result.Rejected)?.reason?.name
                            ?: plannedResult.javaClass.simpleName),
                    planningFailure,
                    identityModes
                )
            val finalLorebookIds = finalLorebooks?.books.orEmpty().mapTo(HashSet()) { it.id }
            val restorePlan = CompanionRestorePlanner.plan(planned.manifest, finalLorebookIds)
            val rollbackLorebooks = currentLorebooks?.books.orEmpty().mapTo(HashSet()) { it.id }
            val rollbackPlan = CompanionRestorePlanner.plan(current, rollbackLorebooks)
            removedLorebookLinks = restorePlan.removedLinks
            identityParticipant = CompanionCategoryRestoreParticipant.PreparedPlan(
                live.identityArchive
                    ?: return blamed("current identity snapshot is unavailable", currentFailure, identityModes),
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
        var memoryReport: MemoryCategoryPlanner.Report? = null
        val plannedMemories = if (PortableRestoreCategory.MEMORIES in modes) {
            val current = live.memories
                ?: return blamed(
                    "current memories are unavailable",
                    currentFailure,
                    listOf(PortableRestoreCategory.MEMORIES)
                )
            val incoming = backup.memories
                ?: return blamed(
                    "backup memories are unavailable",
                    planningFailure,
                    listOf(PortableRestoreCategory.MEMORIES)
                )
            val planned = MemoryCategoryPlanner.plan(
                MemoryPortableGroup.MEMORIES,
                current,
                incoming,
                modes.getValue(PortableRestoreCategory.MEMORIES),
                finalIdentityReferences
            ) as? MemoryCategoryPlanner.Result.Ready
                ?: return blamed(
                    "memories could not be planned",
                    planningFailure,
                    listOf(PortableRestoreCategory.MEMORIES)
                )
            memoryReport = planned.report
            planned.rows
        } else live.memories

        var modelRulesReport: MemoryCategoryPlanner.Report? = null
        var plannedModelRules: MemoryPortableRows? = null
        if (PortableRestoreCategory.MODEL_RULES in modes) {
            val current = live.modelRules
                ?: return blamed(
                    "current model rules are unavailable",
                    currentFailure,
                    listOf(PortableRestoreCategory.MODEL_RULES)
                )
            val incoming = backup.modelRules
                ?: return blamed(
                    "backup model rules are unavailable",
                    planningFailure,
                    listOf(PortableRestoreCategory.MODEL_RULES)
                )
            val planned = MemoryCategoryPlanner.plan(
                MemoryPortableGroup.MODEL_RULES,
                current,
                incoming,
                modes.getValue(PortableRestoreCategory.MODEL_RULES)
            ) as? MemoryCategoryPlanner.Result.Ready
                ?: return blamed(
                    "model rules could not be planned",
                    planningFailure,
                    listOf(PortableRestoreCategory.MODEL_RULES)
                )
            modelRulesReport = planned.report
            plannedModelRules = planned.rows
        }

        val sharedStoreSelected = identitySelections.isNotEmpty() ||
            PortableRestoreCategory.MEMORIES in modes ||
            PortableRestoreCategory.MODEL_RULES in modes
        val sharedParticipant = if (sharedStoreSelected) {
            val current = live.sharedMemory
                ?: return blamed("current shared memory data is unavailable", currentFailure, sharedMemoryCulprit())
            var sharedRejection: String? = null
            val shared = CompanionMemoryRestorePlanner.plan(
                CompanionMemoryRestorePlanner.Input(
                    current = current,
                    identitiesSelected = identitySelections.isNotEmpty(),
                    memoriesSelected = PortableRestoreCategory.MEMORIES in modes,
                    modelRulesSelected = PortableRestoreCategory.MODEL_RULES in modes,
                    finalRoleplayTables = finalIdentities?.roleplayTables,
                    finalMemories = if (PortableRestoreCategory.MEMORIES in modes) plannedMemories else null,
                    finalModelRules = plannedModelRules,
                    finalIdentityReferences = finalIdentityReferences
                )
            ) { sharedRejection = it } ?: return blamed(
                "shared memory data could not be planned: ${sharedRejection ?: "no_reason_given"}",
                planningFailure,
                sharedMemoryCulprit()
            )
            CompanionMemoryRestoreParticipant.PreparedPlan(
                shared.current,
                shared.desired,
                shared.affectedTables,
                memoryReport,
                modelRulesReport
            )
        } else null
        val finalMemories = sharedParticipant?.desired
            ?.let(CompanionMemoryRestorePlanner::memoryRows)
            ?: plannedMemories

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
                        category, reference.identityId, reference.imageHash, reference.name
                    )
                )
            }
        }
        var profileParticipant: ProfileImageRestoreParticipant.PreparedPlan? = null
        val finalProfileRecords = if (PortableRestoreCategory.PROFILE_IMAGES in modes) {
            val current = live.profileImages
                ?: return blamed(
                    "current profile images are unavailable",
                    currentFailure,
                    listOf(PortableRestoreCategory.PROFILE_IMAGES)
                )
            val incoming = backup.profileImages
                ?: return blamed(
                    "backup profile images are unavailable",
                    planningFailure,
                    listOf(PortableRestoreCategory.PROFILE_IMAGES)
                )
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
                ?: return blamed(
                    "profile images could not be planned",
                    planningFailure,
                    listOf(PortableRestoreCategory.PROFILE_IMAGES)
                )
            profileParticipant = ProfileImageRestoreParticipant.PreparedPlan(
                current, incoming, planned.records, planned.report
            )
            planned.records
        } else live.profileImages?.records.orEmpty()

        var endpointParticipant: ModelEndpointRestoreParticipant.PreparedPlan? = null
        if (PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS in modes) {
            val current = live.modelEndpoints
                ?: return blamed(
                    "current model settings are unavailable",
                    currentFailure,
                    listOf(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS)
                )
            val incoming = backup.modelEndpoints
                ?: return blamed(
                    "backup model settings are unavailable",
                    planningFailure,
                    listOf(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS)
                )
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

        val settingsParticipant = if (PortableRestoreCategory.SETTINGS in modes) {
            val current = live.settings
                ?: return blamed(
                    "current app settings are unavailable",
                    currentFailure,
                    listOf(PortableRestoreCategory.SETTINGS)
                )
            val incoming = backup.settings
                ?: return blamed(
                    "backup app settings are unavailable",
                    planningFailure,
                    listOf(PortableRestoreCategory.SETTINGS)
                )
            AppSettingsRestoreParticipant.PreparedPlan(current, incoming)
        } else null

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
            is PortableRestoreDependencyRead.Unavailable ->
                return blamed(built.reason, planningFailure, chatsOrGallery)
        }
        return PlanningResult.Ready(
            PlannedRestore(
                finalState,
                plannedChat,
                plannedGenerated,
                identityParticipant,
                sharedParticipant,
                profileParticipant,
                endpointParticipant,
                lorebookParticipant,
                settingsParticipant
            )
        )
    }

    fun execute(
        journalRoot: File,
        ready: BuildResult.Ready,
        beforeCleanup: () -> Boolean = { true }
    ): SelectedCategoryRestoreTransaction.Result =
        SelectedCategoryRestoreTransaction.execute(
            journalRoot, ready.participants, beforeCleanup = beforeCleanup
        )

    /** A cheap final fence immediately before staging/apply. A mismatch never
     * mutates data; the durable coordinator discards this Ready object and
     * returns through semantic preflight with the same decoded artifacts. */
    fun sourceGenerationsMatch(
        context: Context,
        ready: BuildResult.Ready,
        stagingRoot: File,
        onMismatch: (String) -> Unit = {}
    ): Boolean {
        val modes = ready.finalState.categoryModes
        val verificationRoot = File(stagingRoot, "pre_apply_generation_check")
        if (verificationRoot.exists() && !verificationRoot.deleteRecursively()) {
            onMismatch("pre-apply check storage could not be cleared")
            return false
        }
        val captured = try {
            captureLiveState(
                context.applicationContext,
                modes,
                modes.keys.any(IDENTITY_CATEGORIES::contains),
                verificationRoot
            )
        } catch (e: Exception) {
            PortableRestoreDependencyRead.Unavailable(
                "reading current device data: ${PortableRestoreDiagnostics.unexpected(e)}"
            )
        }
        return when (captured) {
            is PortableRestoreDependencyRead.Available -> {
                val now = captured.snapshot.generations
                val planned = ready.finalState.sourceGenerations
                if (now == planned) true else {
                    val changed = (now.keys + planned.keys).filter { now[it] != planned[it] }
                    onMismatch(
                        "current device data changed after planning: " +
                            changed.joinToString(",") { it.name }
                    )
                    false
                }
            }
            is PortableRestoreDependencyRead.Unavailable -> {
                onMismatch("current device data could not be re-read before apply: ${captured.reason}")
                false
            }
        }
    }

    /** The selected categories a participant writes. The companion/roleplay
     * participant carries the five identity categories; the shared memory
     * participant carries Memories, Model Rules and those identity rows. */
    private fun coveredCategories(
        participantKey: String,
        selected: Set<PortableRestoreCategory>
    ): List<PortableRestoreCategory> = when (participantKey) {
        "identity_bundle" -> PortableRestoreCategory.entries.filter {
            it in IDENTITY_CATEGORIES && it in selected
        }
        CompanionMemoryRestoreParticipant.CATEGORY_KEY -> PortableRestoreCategory.entries.filter {
            (it in IDENTITY_CATEGORIES || it == PortableRestoreCategory.MEMORIES ||
                it == PortableRestoreCategory.MODEL_RULES) && it in selected
        }
        "chat_image_dependencies" -> listOf(PortableRestoreCategory.CHATS)
        else -> PortableRestoreCategory.entries.filter { it.key == participantKey }
    }

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
            CompanionMemoryRestoreParticipant(
                context,
                File(root, CompanionMemoryRestoreParticipant.CATEGORY_KEY)
            ),
            ProfileImageRestoreParticipant(
                context, emptyList(), PortableRestoreMode.MERGE, emptySet(), File(root, "profile_images")
            ),
            ModelEndpointRestoreParticipant(
                context, unused, PortableRestoreMode.MERGE, File(root, "model_endpoints")
            ),
            LorebookRestoreParticipant(
                context, emptyList(), PortableRestoreMode.MERGE, File(root, "lorebooks")
            ),
            AppSettingsRestoreParticipant(
                context, File(root, "app_settings")
            )
        ).associateBy { it.categoryKey }
        val recovered = SelectedCategoryRestoreTransaction.recover(journal, participants)
        if (recovered) root.deleteRecursively()
        return recovered
    }

    private fun artifact(
        artifacts: List<PortablePackage.ValidatedArtifact>, type: String
    ): PortablePackage.ValidatedArtifact? = artifacts.singleOrNull { it.type == type }

    // Each reader below reports why it returned null through [reject],
    // naming only the failing step, a storage state, or an error type.

    private fun readCurrentChats(
        context: Context,
        reject: (String) -> Unit = {}
    ): PortableChatRestorePlan.Plan? {
        val serialized = ChatLogicalSerializer.serializeV2(context)
        val json = (serialized as? ChatLogicalSerializer.Result.Ok)?.json ?: run {
            reject(
                "chat storage could not be read: " +
                    ((serialized as? ChatLogicalSerializer.Result.Unavailable)?.category?.name
                        ?: serialized.javaClass.simpleName)
            )
            return null
        }
        return when (val parsed = PortableChatRestorePlan.parse(json)) {
            is PortableChatRestorePlan.Result.Ok -> parsed.plan
            is PortableChatRestorePlan.Result.Rejected -> {
                reject("current chats were rejected by the chats reader: ${parsed.reason.name}")
                null
            }
        }
    }

    private fun readCurrentGeneratedImages(
        context: Context,
        reject: (String) -> Unit = {}
    ): GeneratedImageCatalogSnapshot? {
        if (!context.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME).exists()) {
            return emptyGeneratedSnapshot()
        }
        val exported = GeneratedImageCatalogStore.exportSnapshot(context)
        if (exported.state != GeneratedImageCatalogStorageState.AVAILABLE) {
            reject("generated image catalog state is ${exported.state.name}")
            return null
        }
        return exported.snapshot ?: emptyGeneratedSnapshot()
    }

    private fun readCurrentProfileImages(
        context: Context,
        reject: (String) -> Unit = {}
    ): ProfileImageRestoreParticipant.Snapshot? {
        if (!context.getDatabasePath(ProfileImageDb.DATABASE_NAME).exists()) {
            return ProfileImageRestoreParticipant.Snapshot(emptyList(), emptyMap())
        }
        return try {
            val store = ProfileImageStore.getInstance(context)
            val records = store.listNewestFirst()
            val assets = records.associate { record ->
                record.hash to (store.imageFile(record.hash) ?: run {
                    reject("a catalogued profile image file is missing on this device")
                    return null
                })
            }
            if (assets.any { (hash, file) ->
                    !ProfileImagePortableBackup.isValidAsset(file, hash)
                }
            ) {
                reject("a profile image file on this device does not match its recorded hash")
                return null
            }
            ProfileImageRestoreParticipant.Snapshot(records, assets)
        } catch (e: Exception) {
            reject(PortableRestoreDiagnostics.unexpected(e))
            null
        }
    }

    private fun readCurrentSharedMemory(
        context: Context,
        reject: (String) -> Unit = {}
    ): MemorySharedRestoreRows? {
        if (!MemoryStore.isProvisioned(context)) return MemorySharedRestoreRowFormat.empty()
        if (DatabaseHealthState.isDegraded(context, BackupType.MEMORY)) {
            reject("memory database is marked degraded")
            return null
        }
        return try { MemoryStore.getInstance(context).exportSharedRestoreRows() }
        catch (e: Exception) {
            reject(PortableRestoreDiagnostics.unexpected(e))
            null
        }
    }

    private fun readCurrentLorebooks(
        context: Context,
        reject: (String) -> Unit = {}
    ): LorebookPortableData? {
        if (!LoreBookStore.isProvisioned(context)) {
            return LorebookPortableData(emptyList(), emptyList(), emptyList())
        }
        if (DatabaseHealthState.isDegraded(context, BackupType.LOREBOOK)) {
            reject("lorebook database is marked degraded")
            return null
        }
        return try { LoreBookStore.getInstance(context).exportPortableData() }
        catch (e: Exception) {
            reject(PortableRestoreDiagnostics.unexpected(e))
            null
        }
    }

    internal fun readIncomingMemory(
        artifacts: List<PortablePackage.ValidatedArtifact>,
        group: MemoryPortableGroup,
        incomingIsEmpty: Boolean,
        reject: (String) -> Unit = {}
    ): MemoryPortableRows? {
        fun refuse(reason: String): MemoryPortableRows? {
            reject(reason)
            return null
        }
        val matches = artifacts.filter {
            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "memory.db"
        }
        if (matches.isEmpty() && incomingIsEmpty) return emptyMemoryRows(group)
        if (matches.size != 1) return refuse("backup holds ${matches.size} memory databases")
        val databaseArtifact = matches.single()
        if (databaseArtifact.keySemantics != PortablePackage.KEY_SEMANTICS_PASSPHRASE) {
            return refuse("backup memory database has an unsupported key type")
        }
        val key = decodeHex(databaseArtifact.databaseKeyHex ?: return refuse("backup memory database has no key"))
            ?: return refuse("backup memory database key is malformed")
        var database: SQLiteDatabase? = null
        return try {
            LoreBookEncryption.loadLibrary()
            val opened = SQLiteDatabase.openDatabase(
                databaseArtifact.stagedFile.absolutePath,
                key,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
            database = opened
            if (!opened.isDatabaseIntegrityOk) return refuse("backup memory database failed its integrity check")
            MemoryPortableRowFormat.read(opened, group)
        } catch (e: Exception) {
            refuse("backup memory database could not be read: ${PortableRestoreDiagnostics.unexpected(e)}")
        } finally {
            runCatching { database?.close() }
            key.fill(0)
        }
    }

    internal fun readIncomingLorebooks(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        incomingIsEmpty: Boolean,
        reject: (String) -> Unit = {}
    ): LorebookPortableData? {
        fun refuse(reason: String): LorebookPortableData? {
            reject(reason)
            return null
        }
        val matches = artifacts.filter {
            it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "lorebook.db"
        }
        if (matches.isEmpty() && incomingIsEmpty) {
            return LorebookPortableData(emptyList(), emptyList(), emptyList())
        }
        if (matches.size != 1) return refuse("backup holds ${matches.size} lorebook databases")
        val databaseArtifact = matches.single()
        val key = when (databaseArtifact.keySemantics) {
            PortablePackage.KEY_SEMANTICS_PASSPHRASE ->
                decodeHex(databaseArtifact.databaseKeyHex ?: return refuse("backup lorebook database has no key"))
                    ?: return refuse("backup lorebook database key is malformed")
            PortablePackage.KEY_SEMANTICS_PLAINTEXT -> {
                if (databaseArtifact.databaseKeyHex != null) {
                    return refuse("unencrypted backup lorebook database carries a key")
                }
                ByteArray(0)
            }
            else -> return refuse("backup lorebook database has an unsupported key type")
        }
        val store = try {
            LoreBookStore.openForTest(context, databaseArtifact.stagedFile.absolutePath, key)
        } catch (e: Exception) {
            key.fill(0)
            return refuse("backup lorebook database could not be opened: ${PortableRestoreDiagnostics.unexpected(e)}")
        }
        return try {
            store.integrityCheck()?.let { return refuse("backup lorebook database failed its integrity check") }
            store.exportPortableData()
        } catch (e: Exception) {
            refuse("backup lorebook database could not be read: ${PortableRestoreDiagnostics.unexpected(e)}")
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

    private fun sameRoleplayRows(
        first: Map<String, List<Map<String, Any?>>>,
        second: Map<String, List<Map<String, Any?>>>
    ): Boolean = first.keys == second.keys && first.keys.all { table ->
        first.getValue(table).toSet() == second.getValue(table).toSet()
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

    internal fun identityToken(manifest: CompanionBackupManifest): String = Hash.hash(
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
