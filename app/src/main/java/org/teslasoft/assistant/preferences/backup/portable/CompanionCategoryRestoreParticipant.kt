/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.backup.companion.CompanionArchiveAssembler
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupExporter
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupValidator
import org.teslasoft.assistant.preferences.backup.companion.CompanionCategoryPlanner
import org.teslasoft.assistant.preferences.backup.companion.CompanionRestoreJournal
import org.teslasoft.assistant.preferences.backup.companion.CompanionRestorePlanner
import org.teslasoft.assistant.preferences.backup.companion.CompanionRoleplayRestoreManager
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore

/**
 * One physical participant for the five logical categories carried by the
 * Companion/Roleplay archive. The categories remain independently selected
 * and planned, but one complete desired archive is applied so the shared
 * settings and memory-database tables retain their existing atomic boundary.
 */
class CompanionCategoryRestoreParticipant internal constructor(
    private val incomingArchive: File,
    private val selections: List<CompanionCategoryPlanner.Selection>,
    private val stagingRoot: File,
    private val backend: Backend,
    private val precomputed: PreparedPlan? = null
) : SelectedCategoryRestoreTransaction.Participant {

    data class ImagePresence(val file: Boolean, val catalog: Boolean)

    data class PreparedPlan(
        val currentArchive: File,
        val current: CompanionBackupManifest,
        val incoming: CompanionBackupManifest,
        val desired: CompanionBackupManifest,
        val report: CompanionCategoryPlanner.Report,
        val restorePlan: CompanionRestorePlanner.Plan,
        val rollbackPlan: CompanionRestorePlanner.Plan
    )

    interface Backend {
        fun snapshot(destination: File): CompanionBackupManifest?
        fun imagePresence(hash: String): ImagePresence
        fun recoverPending(): Boolean
        fun apply(manifest: CompanionBackupManifest, archive: File): Boolean
        fun apply(
            manifest: CompanionBackupManifest,
            archive: File,
            restorePlan: CompanionRestorePlanner.Plan
        ): Boolean = apply(manifest, archive)
        fun removeRestoredImage(hash: String, removeFile: Boolean, removeCatalog: Boolean): Boolean

        /** Non-content reason for the most recent failed call, if known. */
        fun lastFailure(): String? = null
    }

    constructor(
        context: Context,
        incomingArchive: File,
        selections: List<CompanionCategoryPlanner.Selection>,
        stagingRoot: File
    ) : this(
        incomingArchive,
        selections,
        stagingRoot,
        AndroidBackend(context.applicationContext),
        null
    )

    internal constructor(
        context: Context,
        plan: PreparedPlan,
        selections: List<CompanionCategoryPlanner.Selection>,
        stagingRoot: File
    ) : this(
        plan.currentArchive,
        selections,
        stagingRoot,
        AndroidBackend(context.applicationContext),
        plan
    ) {
        incomingManifest = plan.incoming
        desiredMemoryReferences = references(plan.desired)
        report = plan.report
        removedLorebookLinks = plan.restorePlan.removedLinks
    }

    override val categoryKey: String = "identity_bundle"

    private val note = PortableRestoreFailureNote()

    override fun failureDetail(): String? = note.detail

    var report: CompanionCategoryPlanner.Report? = null
        private set

    /**
     * Phase 12.3 (BR-04): the identity -> lorebook connections this restore
     * removes because their lorebooks are absent from the planned final
     * lorebook set. Retained as a structured outcome so the unified restore
     * report can show every intentional removal instead of discarding it
     * behind a Boolean apply result. Empty on the recovery-only reconstruction
     * path, which has no precomputed plan and never reports to the user.
     */
    var removedLorebookLinks: List<RemovedLorebookLink> = emptyList()
        private set

    private var incomingManifest: CompanionBackupManifest? = null
    private var desiredMemoryReferences: MemoryReferenceIds? = null

    fun memoryReferenceIds(): MemoryReferenceIds? = desiredMemoryReferences

    override fun validate(): Boolean {
        note.reset()
        precomputed?.let {
            incomingManifest = it.incoming
            desiredMemoryReferences = references(it.desired)
            report = it.report
            removedLorebookLinks = it.restorePlan.removedLinks
            return selections.isNotEmpty() || note.fail("no_categories_selected")
        }
        val (verdict, detail) = CompanionBackupValidator.validateWithDetail(incomingArchive)
        incomingManifest = (verdict as? CompanionBackupValidator.Verdict.Valid)?.manifest
        if (incomingManifest == null) {
            return note.fail("backup_archive_rejected: ${verdictName(verdict)}: ${detail ?: "no_reason_given"}")
        }
        return selections.isNotEmpty() || note.fail("no_categories_selected")
    }

    override fun stage(): Boolean {
        note.reset()
        val incoming = incomingManifest ?: return note.fail("backup_archive_not_validated")
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) {
                    return note.fail("staging_directory_not_empty")
                }
            } else if (!stagingRoot.mkdirs()) return note.fail("staging_directory_unavailable")

            val currentArchive = File(stagingRoot, CURRENT_ARCHIVE)
            val current = if (precomputed != null) {
                try {
                    precomputed.currentArchive.copyTo(currentArchive, overwrite = false)
                } catch (e: Exception) {
                    return note.fail(
                        "current_snapshot_copy_failed: ${PortableRestoreDiagnostics.unexpected(e)}"
                    )
                }
                precomputed.current
            } else {
                backend.snapshot(currentArchive) ?: return note.fail(
                    "current_snapshot_unavailable: ${backend.lastFailure() ?: "no_reason_given"}"
                )
            }
            val (currentVerdict, currentDetail) =
                CompanionBackupValidator.validateWithDetail(currentArchive)
            val currentValidated = currentVerdict as? CompanionBackupValidator.Verdict.Valid
                ?: return note.fail(
                    "current_snapshot_rejected: ${verdictName(currentVerdict)}: " +
                        (currentDetail ?: "no_reason_given")
                )
            if (currentValidated.manifest != current) {
                return note.fail(
                    "current_snapshot_mismatch_after_reread: " +
                        PortableRestoreDiagnostics.manifestDifference(current, currentValidated.manifest)
                )
            }

            val planned = precomputed?.let {
                CompanionCategoryPlanner.Result.Ready(it.desired, it.report)
            } ?: when (val result = CompanionCategoryPlanner.plan(current, incoming, selections)) {
                is CompanionCategoryPlanner.Result.Ready -> result
                else -> return note.fail("planning_failed: ${planningReason(result)}")
            }
            report = planned.report
            desiredMemoryReferences = references(planned.manifest)

            val desiredArchive = File(stagingRoot, DESIRED_ARCHIVE)
            if (!CompanionArchiveAssembler.write(
                    desiredArchive,
                    planned.manifest,
                    listOf(
                        CompanionArchiveAssembler.Source(currentArchive, current),
                        CompanionArchiveAssembler.Source(incomingArchive, incoming)
                    )
                )
            ) return note.fail("desired_archive_assembly_failed")

            val currentHashes = current.images.mapTo(HashSet(), CompanionBackupImageHash)
            val additions = planned.manifest.images.filter { it.hash !in currentHashes }.map { image ->
                val presence = backend.imagePresence(image.hash)
                JSONObject()
                    .put("hash", image.hash)
                    .put("file", presence.file)
                    .put("catalog", presence.catalog)
            }
            File(stagingRoot, STATE_FILE).writeText(
                JSONObject()
                    .put("version", 1)
                    .put("added", JSONArray(additions))
                    .put("rollback_lorebook_ids", JSONArray(lorebookIds(current).toList()))
                    .toString(),
                Charsets.UTF_8
            )
            if (load(DESIRED_ARCHIVE) == null) return false
            readPresence() != null || note.fail("staged_copy_unreadable: $STATE_FILE")
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    override fun apply(): Boolean {
        note.reset()
        if (!backend.recoverPending()) return note.fail("earlier_companion_restore_still_pending")
        val (archive, manifest) = load(DESIRED_ARCHIVE) ?: return false
        val planned = precomputed?.restorePlan
        val applied = if (planned == null) backend.apply(manifest, archive)
        else backend.apply(manifest, archive, planned)
        return applied || note.fail("write_failed: ${backend.lastFailure() ?: "no_reason_given"}")
    }

    override fun rollback(): Boolean {
        note.reset()
        if (!backend.recoverPending()) return note.fail("earlier_companion_restore_still_pending")
        val (archive, manifest) = load(CURRENT_ARCHIVE) ?: return false
        val rollbackPlan = precomputed?.rollbackPlan ?: readRollbackLorebookIds()?.let {
            CompanionRestorePlanner.plan(manifest, it)
        }
        val restored = if (rollbackPlan == null) backend.apply(manifest, archive)
        else backend.apply(manifest, archive, rollbackPlan)
        if (!restored) return note.fail("write_failed: ${backend.lastFailure() ?: "no_reason_given"}")
        val presence = readPresence() ?: return note.fail("staged_copy_unreadable: $STATE_FILE")
        return presence.all { (hash, before) ->
            backend.removeRestoredImage(
                hash,
                removeFile = !before.file,
                removeCatalog = !before.catalog
            )
        } || note.fail("restored_profile_image_removal_failed")
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    /** Null when unreadable; the reason is recorded in [note]. */
    private fun load(name: String): Pair<File, CompanionBackupManifest>? {
        val archive = File(stagingRoot, name)
        val (verdict, detail) = CompanionBackupValidator.validateWithDetail(archive)
        val manifest = (verdict as? CompanionBackupValidator.Verdict.Valid)?.manifest ?: run {
            note.fail("staged_archive_rejected: $name: ${verdictName(verdict)}: ${detail ?: "no_reason_given"}")
            return null
        }
        return archive to manifest
    }

    private fun readPresence(): List<Pair<String, ImagePresence>>? {
        return try {
            val stateFile = File(stagingRoot, STATE_FILE)
            if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) return null
            val root = JSONObject(stateFile.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != 1) return null
            val array = root.getJSONArray("added")
            val result = ArrayList<Pair<String, ImagePresence>>(array.length())
            val seen = HashSet<String>()
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val hash = item.getString("hash")
                if (!HASH.matches(hash) || !seen.add(hash)) return null
                result.add(hash to ImagePresence(item.getBoolean("file"), item.getBoolean("catalog")))
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun readRollbackLorebookIds(): Set<String>? {
        return try {
            val stateFile = File(stagingRoot, STATE_FILE)
            if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) return null
            val root = JSONObject(stateFile.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != 1) return null
            val array = root.getJSONArray("rollback_lorebook_ids")
            val result = LinkedHashSet<String>()
            repeat(array.length()) {
                val id = array.getString(it)
                if (id.isBlank() || !result.add(id)) return null
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private class AndroidBackend(context: Context) : Backend {
        private val app = context.applicationContext
        private val imageStore: ProfileImageStore get() = ProfileImageStore.getInstance(app)

        private var failure: String? = null

        override fun lastFailure(): String? = failure

        override fun snapshot(destination: File): CompanionBackupManifest? {
            failure = null
            return try {
                when (val built = CompanionBackupExporter.buildBackupZip(app, destination)) {
                    is CompanionBackupExporter.BuildResult.Ok -> built.manifest
                    else -> {
                        failure = "export refused: ${built.javaClass.simpleName}"
                        null
                    }
                }
            } catch (e: Exception) {
                failure = PortableRestoreDiagnostics.unexpected(e)
                null
            }
        }

        override fun imagePresence(hash: String): ImagePresence = ImagePresence(
            file = imageStore.imageFile(hash)?.isFile == true,
            catalog = imageStore.contains(hash)
        )

        override fun recoverPending(): Boolean {
            CompanionRoleplayRestoreManager.resumeIfPending(app)
            return !CompanionRestoreJournal.hasToken(app)
        }

        override fun apply(manifest: CompanionBackupManifest, archive: File): Boolean =
            apply(manifest, archive, CompanionRestorePlanner.plan(manifest, lorebookIds(manifest)))

        override fun apply(
            manifest: CompanionBackupManifest,
            archive: File,
            restorePlan: CompanionRestorePlanner.Plan
        ): Boolean {
            failure = null
            return when (val result = CompanionRoleplayRestoreManager.restoreSettingsAndImages(
                app, manifest, archive, restorePlan
            )) {
                is CompanionRoleplayRestoreManager.RestoreResult.Success -> true
                is CompanionRoleplayRestoreManager.RestoreResult.Failed -> {
                    failure = "companion restore refused: ${result.reason.name}"
                    false
                }
            }
        }

        override fun removeRestoredImage(
            hash: String,
            removeFile: Boolean,
            removeCatalog: Boolean
        ): Boolean = try {
            imageStore.removeRestoredImage(hash, removeFile, removeCatalog)
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        fun verdictName(verdict: CompanionBackupValidator.Verdict): String =
            verdict.javaClass.simpleName

        fun planningReason(result: CompanionCategoryPlanner.Result): String =
            (result as? CompanionCategoryPlanner.Result.Rejected)?.reason?.name
                ?: result.javaClass.simpleName

        fun references(manifest: CompanionBackupManifest): MemoryReferenceIds {
            fun ids(table: String, column: String): Set<String> = manifest.roleplayTables[table]
                .orEmpty().mapNotNullTo(LinkedHashSet()) { (it[column] as? String)?.takeIf(String::isNotBlank) }
            return MemoryReferenceIds(
                companions = ids("companions", "companion_id"),
                worlds = ids("worlds", "world_id"),
                campaigns = ids("campaigns", "campaign_id"),
                roleplayCharacters = ids("roleplay_characters", "roleplay_character_id"),
                userPersonas = ids("user_personas", "persona_id"),
                roleplayTags = ids("rp_tags", "tag_id")
            )
        }

        fun lorebookIds(manifest: CompanionBackupManifest): Set<String> =
            manifest.companionProfiles.flatMap { profile ->
                listOf(profile.coreLoreBookId) + profile.additionalLoreBookIds
            }.filter(String::isNotBlank).toSet()

        val CompanionBackupImageHash: (org.teslasoft.assistant.preferences.backup.companion.CompanionBackupImage) -> String = { it.hash }
        const val CURRENT_ARCHIVE = "current.zip"
        const val DESIRED_ARCHIVE = "desired.zip"
        const val STATE_FILE = "state.json"
        const val MAX_STATE_BYTES = 256L * 1024L
        val HASH = Regex("^[0-9a-f]{64}$")
    }
}
