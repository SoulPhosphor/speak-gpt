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
    }

    override val categoryKey: String = "identity_bundle"

    var report: CompanionCategoryPlanner.Report? = null
        private set

    private var incomingManifest: CompanionBackupManifest? = null
    private var desiredMemoryReferences: MemoryReferenceIds? = null

    fun memoryReferenceIds(): MemoryReferenceIds? = desiredMemoryReferences

    override fun validate(): Boolean {
        precomputed?.let {
            incomingManifest = it.incoming
            desiredMemoryReferences = references(it.desired)
            report = it.report
            return selections.isNotEmpty()
        }
        incomingManifest = (CompanionBackupValidator.validate(incomingArchive) as?
            CompanionBackupValidator.Verdict.Valid)?.manifest
        return incomingManifest != null && selections.isNotEmpty()
    }

    override fun stage(): Boolean {
        val incoming = incomingManifest ?: return false
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) return false
            } else if (!stagingRoot.mkdirs()) return false

            val currentArchive = File(stagingRoot, CURRENT_ARCHIVE)
            val current = if (precomputed != null) {
                precomputed.currentArchive.copyTo(currentArchive, overwrite = false)
                precomputed.current
            } else {
                backend.snapshot(currentArchive) ?: return false
            }
            val currentValidated = CompanionBackupValidator.validate(currentArchive) as?
                CompanionBackupValidator.Verdict.Valid ?: return false
            if (currentValidated.manifest != current) return false

            val planned = precomputed?.let {
                CompanionCategoryPlanner.Result.Ready(it.desired, it.report)
            } ?: (CompanionCategoryPlanner.plan(current, incoming, selections) as?
                CompanionCategoryPlanner.Result.Ready ?: return false)
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
            ) return false

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
            load(DESIRED_ARCHIVE) != null && readPresence() != null
        } catch (_: Exception) {
            false
        }
    }

    override fun apply(): Boolean {
        if (!backend.recoverPending()) return false
        val (archive, manifest) = load(DESIRED_ARCHIVE) ?: return false
        val planned = precomputed?.restorePlan
        return if (planned == null) backend.apply(manifest, archive)
        else backend.apply(manifest, archive, planned)
    }

    override fun rollback(): Boolean {
        if (!backend.recoverPending()) return false
        val (archive, manifest) = load(CURRENT_ARCHIVE) ?: return false
        val rollbackPlan = precomputed?.rollbackPlan ?: readRollbackLorebookIds()?.let {
            CompanionRestorePlanner.plan(manifest, it)
        }
        val restored = if (rollbackPlan == null) backend.apply(manifest, archive)
        else backend.apply(manifest, archive, rollbackPlan)
        if (!restored) return false
        val presence = readPresence() ?: return false
        return presence.all { (hash, before) ->
            backend.removeRestoredImage(
                hash,
                removeFile = !before.file,
                removeCatalog = !before.catalog
            )
        }
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun load(name: String): Pair<File, CompanionBackupManifest>? {
        val archive = File(stagingRoot, name)
        val manifest = (CompanionBackupValidator.validate(archive) as?
            CompanionBackupValidator.Verdict.Valid)?.manifest ?: return null
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

        override fun snapshot(destination: File): CompanionBackupManifest? =
            (CompanionBackupExporter.buildBackupZip(app, destination) as?
                CompanionBackupExporter.BuildResult.Ok)?.manifest

        override fun imagePresence(hash: String): ImagePresence = ImagePresence(
            file = imageStore.imageFile(hash)?.isFile == true,
            catalog = imageStore.contains(hash)
        )

        override fun recoverPending(): Boolean {
            CompanionRoleplayRestoreManager.resumeIfPending(app)
            return !CompanionRestoreJournal.hasToken(app)
        }

        override fun apply(manifest: CompanionBackupManifest, archive: File): Boolean =
            CompanionRoleplayRestoreManager.restore(app, manifest, archive) is
                CompanionRoleplayRestoreManager.RestoreResult.Success

        override fun apply(
            manifest: CompanionBackupManifest,
            archive: File,
            restorePlan: CompanionRestorePlanner.Plan
        ): Boolean = CompanionRoleplayRestoreManager.restore(
            app, manifest, archive, restorePlan
        ) is CompanionRoleplayRestoreManager.RestoreResult.Success

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
