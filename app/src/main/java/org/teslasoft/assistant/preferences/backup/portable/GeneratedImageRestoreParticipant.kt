/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogHealth
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.memory.DatabaseKeys

/** Generated Images adapter for the cross-category all-or-nothing boundary. */
class GeneratedImageRestoreParticipant internal constructor(
    private val artifacts: List<PortablePackage.ValidatedArtifact>,
    private val mode: PortableRestoreMode,
    private val protectedCurrentImageIds: Set<String>,
    private val stagingRoot: File,
    private val backend: Backend,
    private val includedBackupImageIds: Set<String>? = null,
    private val participantKey: String = PortableRestoreCategory.GENERATED_IMAGES.key,
    private val precomputed: PreparedPlan? = null
) : SelectedCategoryRestoreTransaction.Participant {

    data class PreparedPlan(
        val current: GeneratedImageCatalogSnapshot,
        val incoming: GeneratedImagePortableRestoreManager.Prepared,
        val desired: GeneratedImageCatalogSnapshot,
        val report: GeneratedImageCategoryPlanner.Report
    )

    interface Backend {
        fun snapshot(): GeneratedImageCatalogSnapshot?
        fun assetFile(fileName: String): File?
        fun replace(snapshot: GeneratedImageCatalogSnapshot, assets: Map<String, File>): Boolean
        fun restoreOriginal(
            snapshot: GeneratedImageCatalogSnapshot,
            assets: Map<String, File>
        ): Boolean = replace(snapshot, assets)
        fun recoverPending(): Boolean
        fun wasProvisionedBeforeStage(): Boolean = true
        fun removeProvisionedStore(): Boolean = true

        /** Post-apply reference-closure check (Phase 12.4 item 7): the live
         *  catalog must equal the desired set and every active row must resolve
         *  to a valid asset file. A false return fails apply, so the outer
         *  transaction rolls back. Test fakes that do not model live files keep
         *  the permissive default; the Android store implements the real check. */
        fun verifyClosure(desired: GeneratedImageCatalogSnapshot): Boolean = true
    }

    constructor(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        mode: PortableRestoreMode,
        protectedCurrentImageIds: Set<String>,
        stagingRoot: File
    ) : this(
        artifacts,
        mode,
        protectedCurrentImageIds,
        stagingRoot,
        AndroidBackend(context.applicationContext),
        null,
        PortableRestoreCategory.GENERATED_IMAGES.key,
        null
    )

    constructor(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        includedBackupImageIds: Set<String>,
        stagingRoot: File,
        dependencyParticipantKey: String
    ) : this(
        artifacts,
        PortableRestoreMode.MERGE,
        emptySet(),
        stagingRoot,
        AndroidBackend(context.applicationContext),
        includedBackupImageIds,
        dependencyParticipantKey,
        null
    )

    internal constructor(
        context: Context,
        plan: PreparedPlan,
        stagingRoot: File,
        participantKey: String = PortableRestoreCategory.GENERATED_IMAGES.key
    ) : this(
        emptyList(),
        PortableRestoreMode.MERGE,
        emptySet(),
        stagingRoot,
        AndroidBackend(context.applicationContext),
        null,
        participantKey,
        plan
    ) {
        incoming = plan.incoming
        report = plan.report
    }

    override val categoryKey: String = participantKey

    private val note = PortableRestoreFailureNote()

    override fun failureDetail(): String? = note.detail

    var report: GeneratedImageCategoryPlanner.Report? = null
        private set

    private var incoming: GeneratedImagePortableRestoreManager.Prepared? = null

    override fun validate(): Boolean {
        note.reset()
        precomputed?.let {
            incoming = it.incoming
            report = it.report
            return true
        }
        val prepared = (GeneratedImagePortableRestoreManager.prepare(artifacts) as?
            GeneratedImagePortableRestoreManager.PrepareResult.Ready)?.prepared
        incoming = if (prepared == null || includedBackupImageIds == null) prepared else {
            val filtered = prepared.snapshot.copy(
                active = prepared.snapshot.active.filter { it.imageId in includedBackupImageIds },
                tombstones = emptyList(),
                meta = emptyMap(),
                backfillChats = emptyMap()
            )
            val names = filtered.active.mapTo(HashSet()) { it.assetFileName }
            prepared.copy(snapshot = filtered, assets = prepared.assets.filterKeys(names::contains))
        }
        return incoming != null || note.fail("backup_catalog_rejected")
    }

    override fun stage(): Boolean {
        note.reset()
        val backup = incoming ?: return note.fail("backup_catalog_not_validated")
        val current = precomputed?.current ?: backend.snapshot()
            ?: return note.fail("current_catalog_unreadable")
        if (GeneratedImagePortableCatalog.validate(current) != null) {
            return note.fail("current_catalog_invalid")
        }
        val planned = precomputed?.let {
            GeneratedImageCategoryPlanner.Result.Ready(it.desired, it.report)
        } ?: (GeneratedImageCategoryPlanner.plan(
            current,
            backup.snapshot,
            mode,
            protectedCurrentImageIds
        ) as? GeneratedImageCategoryPlanner.Result.Ready ?: return note.fail("planning_failed"))
        report = planned.report

        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) {
                    return note.fail("staging_directory_not_empty")
                }
            } else if (!stagingRoot.mkdirs()) {
                return note.fail("staging_directory_unavailable")
            }
            if (!RestoreProvisioningState.write(stagingRoot, backend.wasProvisionedBeforeStage())) {
                return note.fail("staged_write_failed: provisioning state")
            }
            if (!stageSet(current, emptyMap(), CURRENT_DIR, CURRENT_CATALOG)) return false
            if (!stageSet(planned.snapshot, backup.assets, DESIRED_DIR, DESIRED_CATALOG)) return false
            (loadSet(CURRENT_CATALOG, CURRENT_DIR) != null ||
                note.fail("staged_copy_unreadable: $CURRENT_CATALOG")) &&
                (loadSet(DESIRED_CATALOG, DESIRED_DIR) != null ||
                    note.fail("staged_copy_unreadable: $DESIRED_CATALOG"))
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    override fun apply(): Boolean {
        note.reset()
        if (!applySet(DESIRED_CATALOG, DESIRED_DIR)) return false
        val desired = loadSet(DESIRED_CATALOG, DESIRED_DIR)?.first
            ?: return note.fail("staged_copy_unreadable: $DESIRED_CATALOG")
        return backend.verifyClosure(desired) || note.fail("written_catalog_does_not_match_plan")
    }

    override fun rollback(): Boolean {
        note.reset()
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot)
            ?: return note.fail("staged_copy_unreadable: provisioning state")
        if (!applySet(CURRENT_CATALOG, CURRENT_DIR, restoreOriginal = true)) return false
        return wasProvisioned || backend.removeProvisionedStore() || note.fail("database_removal_failed")
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun stageSet(
        snapshot: GeneratedImageCatalogSnapshot,
        preferredSources: Map<String, File>,
        directoryName: String,
        catalogName: String
    ): Boolean {
        val unique = uniqueRecords(snapshot)
            ?: return note.fail("catalog_has_conflicting_file_names: $directoryName")
        val directory = File(stagingRoot, directoryName)
        if (!directory.mkdirs()) return note.fail("staging_directory_unavailable: $directoryName")
        for ((fileName, record) in unique) {
            val preferred = preferredSources[fileName]
            val source = if (preferred != null && GeneratedImagePortableBackup.isValidAsset(preferred, record)) {
                preferred
            } else {
                backend.assetFile(fileName)
            } ?: return note.fail("image_file_missing: $directoryName")
            if (!GeneratedImagePortableBackup.isValidAsset(source, record)) {
                return note.fail("image_contents_do_not_match_catalog: $directoryName source")
            }
            val target = File(directory, fileName)
            source.copyTo(target, overwrite = false)
            if (!GeneratedImagePortableBackup.isValidAsset(target, record)) {
                return note.fail("image_contents_do_not_match_catalog: $directoryName copy")
            }
        }
        File(stagingRoot, catalogName).writeText(
            GeneratedImagePortableCatalog.toJson(snapshot),
            Charsets.UTF_8
        )
        return true
    }

    private fun applySet(
        catalogName: String,
        directoryName: String,
        restoreOriginal: Boolean = false
    ): Boolean {
        if (!backend.recoverPending()) return note.fail("earlier_image_restore_still_pending")
        val loaded = loadSet(catalogName, directoryName)
            ?: return note.fail("staged_copy_unreadable: $catalogName")
        val written = if (restoreOriginal) {
            backend.restoreOriginal(loaded.first, loaded.second)
        } else {
            backend.replace(loaded.first, loaded.second)
        }
        return written || note.fail("image_catalog_write_failed")
    }

    private fun loadSet(
        catalogName: String,
        directoryName: String
    ): Pair<GeneratedImageCatalogSnapshot, Map<String, File>>? {
        val catalog = File(stagingRoot, catalogName)
        if (!catalog.isFile || catalog.length() > GeneratedImagePortableCatalog.MAX_JSON_CHARS * 4L) {
            return null
        }
        val parsed = GeneratedImagePortableCatalog.parse(catalog.readText(Charsets.UTF_8)) as?
            GeneratedImagePortableCatalog.ParseResult.Ok ?: return null
        val unique = uniqueRecords(parsed.snapshot) ?: return null
        val directory = File(stagingRoot, directoryName)
        val assets = LinkedHashMap<String, File>()
        for ((name, record) in unique) {
            val file = File(directory, name)
            if (!GeneratedImagePortableBackup.isValidAsset(file, record)) return null
            assets[name] = file
        }
        return parsed.snapshot to assets
    }

    private fun uniqueRecords(
        snapshot: GeneratedImageCatalogSnapshot
    ): Map<String, GeneratedImageCatalogRecord>? {
        val result = LinkedHashMap<String, GeneratedImageCatalogRecord>()
        for (record in snapshot.active) {
            val previous = result.putIfAbsent(record.assetFileName, record)
            if (previous != null &&
                (!previous.fileHash.equals(record.fileHash, ignoreCase = true) ||
                    previous.mimeType != record.mimeType)
            ) return null
        }
        return result
    }

    private class AndroidBackend(context: Context) : Backend {
        private val app = context.applicationContext
        private val initiallyProvisioned =
            app.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME).exists() ||
                GeneratedImageCatalogHealth.isProvisioned(app)
        private val imagesDir: File? get() = app.getExternalFilesDir("images")
        private val journal: File get() = File(app.filesDir, JOURNAL_DIR)

        override fun snapshot(): GeneratedImageCatalogSnapshot? {
            // A never-created catalog is a valid empty current set. Do not
            // open the store during staging: opening it would provision a
            // database before the outer transaction begins mutation.
            if (!initiallyProvisioned) return emptySnapshot()
            val exported = GeneratedImageCatalogStore.exportSnapshot(app)
            if (exported.state != GeneratedImageCatalogStorageState.AVAILABLE) return null
            return exported.snapshot ?: emptySnapshot()
        }

        override fun assetFile(fileName: String): File? {
            if (!GeneratedImagePortableCatalog.safeAssetName(fileName)) return null
            return imagesDir?.let { File(it, fileName) }?.takeIf(File::isFile)
        }

        override fun replace(
            snapshot: GeneratedImageCatalogSnapshot,
            assets: Map<String, File>
        ): Boolean {
            val directory = imagesDir ?: return false
            return GeneratedImageRestoreTransaction.replace(
                journal,
                directory,
                snapshot,
                assets,
                catalogBackend()
            ) == GeneratedImageRestoreTransaction.Result.Success
        }

        override fun recoverPending(): Boolean {
            if (!journal.exists()) return true
            val directory = imagesDir ?: return false
            return GeneratedImageRestoreTransaction.recover(journal, directory, catalogBackend())
        }

        override fun wasProvisionedBeforeStage(): Boolean = initiallyProvisioned

        override fun verifyClosure(desired: GeneratedImageCatalogSnapshot): Boolean {
            val live = snapshot() ?: return false
            return closureHolds(live, desired) { record ->
                val file = assetFile(record.assetFileName)
                file != null && GeneratedImagePortableBackup.isValidAsset(file, record)
            }
        }

        override fun restoreOriginal(
            snapshot: GeneratedImageCatalogSnapshot,
            assets: Map<String, File>
        ): Boolean {
            return replace(snapshot, assets)
        }

        override fun removeProvisionedStore(): Boolean {
            GeneratedImageCatalogStore.invalidateInstance()
            val database = app.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME)
            val files = listOf(
                database,
                File(database.path + "-wal"),
                File(database.path + "-shm"),
                File(database.path + "-journal")
            )
            if (files.any { it.exists() && !it.delete() }) return false
            if (!DatabaseKeys.clearExisting(app, DatabaseKeys.KEY_GENERATED_IMAGES)) return false
            GeneratedImageCatalogHealth.clearAfterRestoreRollback(app)
            return true
        }

        private fun catalogBackend() = object : GeneratedImageRestoreTransaction.CatalogBackend {
            override fun snapshot(): GeneratedImageCatalogSnapshot? = this@AndroidBackend.snapshot()

            override fun replace(snapshot: GeneratedImageCatalogSnapshot): Boolean {
                val result = GeneratedImageCatalogStore.replaceSnapshot(app, snapshot)
                return result.success && result.state == GeneratedImageCatalogStorageState.AVAILABLE
            }
        }

        private fun emptySnapshot() = GeneratedImageCatalogSnapshot(
            active = emptyList(),
            tombstones = emptyList(),
            meta = emptyMap(),
            backfillChats = emptyMap()
        )
    }

    internal companion object {
        const val CURRENT_DIR = "current_assets"
        const val DESIRED_DIR = "desired_assets"
        const val CURRENT_CATALOG = "current.json"
        const val DESIRED_CATALOG = "desired.json"
        const val JOURNAL_DIR = "generated_image_restore_journal"

        /** Reference closure for the applied catalog: the live active set must
         *  equal the desired active set and every active row must resolve to a
         *  valid asset ([assetValid]). A replaced catalog holds no orphaned
         *  active row, so satisfying this also proves no file scheduled for
         *  deletion is still referenced. */
        internal fun closureHolds(
            live: GeneratedImageCatalogSnapshot,
            desired: GeneratedImageCatalogSnapshot,
            assetValid: (GeneratedImageCatalogRecord) -> Boolean
        ): Boolean {
            val liveIds = live.active.mapTo(HashSet()) { it.imageId }
            val desiredIds = desired.active.mapTo(HashSet()) { it.imageId }
            if (liveIds != desiredIds) return false
            return live.active.all(assetValid)
        }
    }
}
