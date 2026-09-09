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
    private val participantKey: String = PortableRestoreCategory.GENERATED_IMAGES.key
) : SelectedCategoryRestoreTransaction.Participant {

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
        PortableRestoreCategory.GENERATED_IMAGES.key
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
        dependencyParticipantKey
    )

    override val categoryKey: String = participantKey

    var report: GeneratedImageCategoryPlanner.Report? = null
        private set

    private var incoming: GeneratedImagePortableRestoreManager.Prepared? = null

    override fun validate(): Boolean {
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
        return incoming != null
    }

    override fun stage(): Boolean {
        val backup = incoming ?: return false
        val current = backend.snapshot() ?: return false
        if (GeneratedImagePortableCatalog.validate(current) != null) return false
        val planned = GeneratedImageCategoryPlanner.plan(
            current,
            backup.snapshot,
            mode,
            protectedCurrentImageIds
        ) as? GeneratedImageCategoryPlanner.Result.Ready ?: return false
        report = planned.report

        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) return false
            } else if (!stagingRoot.mkdirs()) {
                return false
            }
            if (!RestoreProvisioningState.write(stagingRoot, backend.wasProvisionedBeforeStage())) return false
            if (!stageSet(current, emptyMap(), CURRENT_DIR, CURRENT_CATALOG)) return false
            if (!stageSet(planned.snapshot, backup.assets, DESIRED_DIR, DESIRED_CATALOG)) return false
            loadSet(CURRENT_CATALOG, CURRENT_DIR) != null &&
                loadSet(DESIRED_CATALOG, DESIRED_DIR) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun apply(): Boolean = applySet(DESIRED_CATALOG, DESIRED_DIR)

    override fun rollback(): Boolean {
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot) ?: return false
        if (!applySet(CURRENT_CATALOG, CURRENT_DIR, restoreOriginal = true)) return false
        return wasProvisioned || backend.removeProvisionedStore()
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
        val unique = uniqueRecords(snapshot) ?: return false
        val directory = File(stagingRoot, directoryName)
        if (!directory.mkdirs()) return false
        for ((fileName, record) in unique) {
            val preferred = preferredSources[fileName]
            val source = if (preferred != null && GeneratedImagePortableBackup.isValidAsset(preferred, record)) {
                preferred
            } else {
                backend.assetFile(fileName)
            } ?: return false
            if (!GeneratedImagePortableBackup.isValidAsset(source, record)) return false
            val target = File(directory, fileName)
            source.copyTo(target, overwrite = false)
            if (!GeneratedImagePortableBackup.isValidAsset(target, record)) return false
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
        if (!backend.recoverPending()) return false
        val loaded = loadSet(catalogName, directoryName) ?: return false
        return if (restoreOriginal) {
            backend.restoreOriginal(loaded.first, loaded.second)
        } else {
            backend.replace(loaded.first, loaded.second)
        }
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

    private companion object {
        const val CURRENT_DIR = "current_assets"
        const val DESIRED_DIR = "desired_assets"
        const val CURRENT_CATALOG = "current.json"
        const val DESIRED_CATALOG = "desired.json"
        const val JOURNAL_DIR = "generated_image_restore_journal"
    }
}
