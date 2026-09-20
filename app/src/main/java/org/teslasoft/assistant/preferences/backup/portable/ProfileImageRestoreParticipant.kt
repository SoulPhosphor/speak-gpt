/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.profileimages.ProfileImageDb
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming
import org.teslasoft.assistant.preferences.profileimages.ProfileImageRecord
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore

/** Avatar/Profile Images adapter for the selected-category transaction. */
class ProfileImageRestoreParticipant internal constructor(
    private val artifacts: List<PortablePackage.ValidatedArtifact>,
    private val mode: PortableRestoreMode,
    private val protectedCurrentHashes: Set<String>,
    private val stagingRoot: File,
    private val backend: Backend,
    private val incomingIsEmpty: Boolean = false,
    private val precomputed: PreparedPlan? = null
) : SelectedCategoryRestoreTransaction.Participant {

    data class Snapshot(
        val records: List<ProfileImageRecord>,
        val assets: Map<String, File>
    )

    data class PreparedPlan(
        val current: Snapshot,
        val incoming: ProfileImagePortableRestoreManager.Prepared,
        val desiredRecords: List<ProfileImageRecord>,
        val report: ProfileImageCategoryPlanner.Report
    )

    interface Backend {
        fun snapshot(): Snapshot?
        fun replace(records: List<ProfileImageRecord>, assets: Map<String, File>): Boolean
        fun restoreOriginal(records: List<ProfileImageRecord>, assets: Map<String, File>): Boolean =
            replace(records, assets)
        fun wasProvisionedBeforeStage(): Boolean = true
        fun removeProvisionedStore(): Boolean = true

        /** Post-apply reference-closure check (Phase 12.4 item 7): the live
         *  catalog must equal the desired set and every catalog row must
         *  resolve to a valid image file. A false return fails apply, so the
         *  outer transaction rolls back. Test fakes that do not model live
         *  files keep the permissive default; the Android store implements the
         *  real check. */
        fun verifyClosure(desired: List<ProfileImageRecord>): Boolean = true
    }

    constructor(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        mode: PortableRestoreMode,
        protectedCurrentHashes: Set<String>,
        stagingRoot: File,
        incomingIsEmpty: Boolean = false
    ) : this(
        artifacts,
        mode,
        protectedCurrentHashes,
        stagingRoot,
        AndroidBackend(context.applicationContext),
        incomingIsEmpty,
        null
    )

    internal constructor(
        context: Context,
        plan: PreparedPlan,
        stagingRoot: File
    ) : this(
        emptyList(),
        PortableRestoreMode.MERGE,
        emptySet(),
        stagingRoot,
        AndroidBackend(context.applicationContext),
        false,
        plan
    ) {
        incoming = plan.incoming
        report = plan.report
    }

    override val categoryKey: String = PortableRestoreCategory.PROFILE_IMAGES.key

    var report: ProfileImageCategoryPlanner.Report? = null
        private set

    private var incoming: ProfileImagePortableRestoreManager.Prepared? = null

    override fun validate(): Boolean {
        precomputed?.let {
            incoming = it.incoming
            report = it.report
            return true
        }
        incoming = if (incomingIsEmpty && artifacts.none {
                it.type == PortablePackage.TYPE_SQLITE_DB && it.entryName == "user_images.db"
            }
        ) {
            ProfileImagePortableRestoreManager.Prepared(emptyList(), emptyMap())
        } else {
            (ProfileImagePortableRestoreManager.prepare(artifacts) as?
                ProfileImagePortableRestoreManager.Result.Ready)?.prepared
        }
        return incoming != null
    }

    override fun stage(): Boolean {
        val backup = incoming ?: return false
        val current = precomputed?.current ?: backend.snapshot() ?: return false
        val planned = precomputed?.let {
            ProfileImageCategoryPlanner.Result.Ready(it.desiredRecords, it.report)
        } ?: (ProfileImageCategoryPlanner.plan(
            current.records,
            backup.records,
            mode,
            protectedCurrentHashes
        ) as? ProfileImageCategoryPlanner.Result.Ready ?: return false)
        report = planned.report
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) return false
            } else if (!stagingRoot.mkdirs()) return false
            if (!RestoreProvisioningState.write(stagingRoot, backend.wasProvisionedBeforeStage())) return false
            if (!stageSet(current.records, current.assets, CURRENT_DIR, CURRENT_JSON)) return false
            val desiredAssets = LinkedHashMap<String, File>()
            val currentAssets = current.assets
            for (record in planned.records) {
                desiredAssets[record.hash] = backup.assets[record.hash]
                    ?: currentAssets[record.hash]
                    ?: return false
            }
            if (!stageSet(planned.records, desiredAssets, DESIRED_DIR, DESIRED_JSON)) return false
            loadSet(CURRENT_JSON, CURRENT_DIR) != null && loadSet(DESIRED_JSON, DESIRED_DIR) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun apply(): Boolean {
        val set = loadSet(DESIRED_JSON, DESIRED_DIR) ?: return false
        if (!backend.replace(set.records, set.assets)) return false
        return backend.verifyClosure(set.records)
    }

    override fun rollback(): Boolean {
        val set = loadSet(CURRENT_JSON, CURRENT_DIR) ?: return false
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot) ?: return false
        if (!backend.restoreOriginal(set.records, set.assets)) return false
        return wasProvisioned || backend.removeProvisionedStore()
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun stageSet(
        records: List<ProfileImageRecord>,
        sources: Map<String, File>,
        directoryName: String,
        jsonName: String
    ): Boolean {
        val directory = File(stagingRoot, directoryName)
        if (!directory.mkdirs()) return false
        for (record in records) {
            val source = sources[record.hash] ?: return false
            if (!ProfileImagePortableBackup.isValidAsset(source, record.hash)) return false
            val target = File(directory, ProfileImageFileNaming.permanentFileName(record.hash))
            source.copyTo(target, overwrite = false)
            if (!ProfileImagePortableBackup.isValidAsset(target, record.hash)) return false
        }
        File(stagingRoot, jsonName).writeText(
            JSONObject().put("version", 1).put("images", JSONArray().apply {
                records.forEach { put(JSONObject().put("hash", it.hash).put("created_at", it.createdAt)) }
            }).toString(),
            Charsets.UTF_8
        )
        return true
    }

    private fun loadSet(jsonName: String, directoryName: String): Snapshot? {
        return try {
            val file = File(stagingRoot, jsonName)
            if (!file.isFile || file.length() > MAX_JSON_BYTES) return null
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != 1) return null
            val array = root.getJSONArray("images")
            val records = ArrayList<ProfileImageRecord>(array.length())
            val assets = LinkedHashMap<String, File>()
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val record = ProfileImageRecord(item.getString("hash"), item.getLong("created_at"))
                if (!HASH.matches(record.hash) || record.createdAt < 0L || record.hash in assets) return null
                val asset = File(
                    File(stagingRoot, directoryName),
                    ProfileImageFileNaming.permanentFileName(record.hash)
                )
                if (!ProfileImagePortableBackup.isValidAsset(asset, record.hash)) return null
                records.add(record)
                assets[record.hash] = asset
            }
            Snapshot(records, assets)
        } catch (_: Exception) {
            null
        }
    }

    private class AndroidBackend(context: Context) : Backend {
        private val app = context.applicationContext
        private val initiallyProvisioned = app.getDatabasePath(ProfileImageDb.DATABASE_NAME).exists()
        private val store: ProfileImageStore get() = ProfileImageStore.getInstance(app)

        override fun snapshot(): Snapshot? {
            if (!initiallyProvisioned) return Snapshot(emptyList(), emptyMap())
            return try {
                val records = store.listNewestFirst()
                val assets = records.associate { record ->
                    record.hash to (store.imageFile(record.hash) ?: return null)
                }
                if (assets.any { (hash, file) -> !ProfileImagePortableBackup.isValidAsset(file, hash) }) {
                    return null
                }
                Snapshot(records, assets)
            } catch (_: Exception) {
                null
            }
        }

        override fun wasProvisionedBeforeStage(): Boolean = initiallyProvisioned

        override fun verifyClosure(desired: List<ProfileImageRecord>): Boolean {
            val live = snapshot() ?: return false
            return closureHolds(live, desired) { hash, file ->
                ProfileImagePortableBackup.isValidAsset(file, hash)
            }
        }

        override fun replace(
            records: List<ProfileImageRecord>,
            assets: Map<String, File>
        ): Boolean {
            return try {
                val previous = if (app.getDatabasePath(ProfileImageDb.DATABASE_NAME).exists()) {
                    store.listNewestFirst().map(ProfileImageRecord::hash).toSet()
                } else emptySet()
                for (record in records) {
                    val source = assets[record.hash] ?: return false
                    if (!ProfileImagePortableBackup.isValidAsset(source, record.hash)) return false
                    val imported = store.importEncodedImage(source.readBytes()) ?: return false
                    if (imported.hash != record.hash) return false
                }
                store.replaceCatalog(records)
                val desired = records.map(ProfileImageRecord::hash).toSet()
                for (hash in previous - desired) {
                    store.removeRestoredImage(hash, removeFile = true, removeCatalogRow = false)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        override fun restoreOriginal(
            records: List<ProfileImageRecord>,
            assets: Map<String, File>
        ): Boolean {
            return replace(records, assets)
        }

        override fun removeProvisionedStore(): Boolean {
            ProfileImageStore.invalidateInstance()
            ProfileImageDb.invalidateInstance()
            val database = app.getDatabasePath(ProfileImageDb.DATABASE_NAME)
            val files = listOf(
                database,
                File(database.path + "-wal"),
                File(database.path + "-shm"),
                File(database.path + "-journal")
            )
            return files.none { it.exists() && !it.delete() }
        }
    }

    internal companion object {
        const val CURRENT_DIR = "current_assets"
        const val DESIRED_DIR = "desired_assets"
        const val CURRENT_JSON = "current.json"
        const val DESIRED_JSON = "desired.json"
        const val MAX_JSON_BYTES = 8L * 1024L * 1024L
        val HASH = Regex("^[0-9a-f]{64}$")

        /** Reference closure for the applied gallery: the live catalog must
         *  equal the desired hash set and every row must resolve to a valid
         *  image file ([assetValid]). A replaced catalog holds no orphaned row,
         *  so this also proves no deleted file remains referenced. */
        internal fun closureHolds(
            live: Snapshot,
            desired: List<ProfileImageRecord>,
            assetValid: (hash: String, file: File) -> Boolean
        ): Boolean {
            val liveHashes = live.records.mapTo(HashSet()) { it.hash }
            val desiredHashes = desired.mapTo(HashSet()) { it.hash }
            if (liveHashes != desiredHashes) return false
            return live.records.all { record ->
                val file = live.assets[record.hash] ?: return@all false
                assetValid(record.hash, file)
            }
        }
    }
}
