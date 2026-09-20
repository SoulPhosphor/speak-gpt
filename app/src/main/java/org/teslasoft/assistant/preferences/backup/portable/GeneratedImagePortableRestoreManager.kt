/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import java.io.File

/** Validates the generated-image category extracted from a portable package,
 * then hands the complete set to the journaled replacement transaction. No UI
 * reaches this engine before the Phase 11 category design is approved. */
object GeneratedImagePortableRestoreManager {

    data class Prepared(
        val snapshot: GeneratedImageCatalogSnapshot,
        val assets: Map<String, File>
    )

    enum class InvalidReason {
        MISSING_CATALOG,
        DUPLICATE_CATALOG,
        UNSUPPORTED_VERSION,
        INVALID_CATALOG,
        MISSING_ASSET,
        EXTRA_ASSET,
        INVALID_ASSET
    }

    sealed class PrepareResult {
        data object Absent : PrepareResult()
        data class Ready(val prepared: Prepared) : PrepareResult()
        data class Invalid(val reason: InvalidReason) : PrepareResult()
    }

    fun prepare(artifacts: List<PortablePackage.ValidatedArtifact>): PrepareResult {
        val catalogs = artifacts.filter { it.type == PortablePackage.TYPE_GENERATED_IMAGES_CATALOG }
        val assets = artifacts.filter { it.type == PortablePackage.TYPE_GENERATED_IMAGE_ASSET }
        if (catalogs.isEmpty() && assets.isEmpty()) return PrepareResult.Absent
        if (catalogs.isEmpty()) return PrepareResult.Invalid(InvalidReason.MISSING_CATALOG)
        if (catalogs.size != 1) return PrepareResult.Invalid(InvalidReason.DUPLICATE_CATALOG)
        val catalog = catalogs.single()
        if (catalog.schemaVersion != 1) return PrepareResult.Invalid(InvalidReason.UNSUPPORTED_VERSION)
        if (!catalog.stagedFile.isFile ||
            catalog.stagedFile.length() > GeneratedImagePortableCatalog.MAX_JSON_CHARS * 4L
        ) return PrepareResult.Invalid(InvalidReason.INVALID_CATALOG)
        val parsed = GeneratedImagePortableCatalog.parse(
            try { catalog.stagedFile.readText(Charsets.UTF_8) }
            catch (_: Exception) { return PrepareResult.Invalid(InvalidReason.INVALID_CATALOG) }
        )
        if (parsed !is GeneratedImagePortableCatalog.ParseResult.Ok) {
            return PrepareResult.Invalid(InvalidReason.INVALID_CATALOG)
        }
        val snapshot = parsed.snapshot

        val expected = snapshot.active.groupBy { it.assetFileName }
        for (records in expected.values) {
            val first = records.first()
            if (records.any {
                    !it.fileHash.equals(first.fileHash, ignoreCase = true) || it.mimeType != first.mimeType
                }
            ) return PrepareResult.Invalid(InvalidReason.INVALID_CATALOG)
        }
        val actual = LinkedHashMap<String, File>()
        for (artifact in assets) {
            val prefix = "generated_images/assets/"
            if (!artifact.entryName.startsWith(prefix)) {
                return PrepareResult.Invalid(InvalidReason.EXTRA_ASSET)
            }
            val name = artifact.entryName.removePrefix(prefix)
            if (!GeneratedImagePortableCatalog.safeAssetName(name) || actual.put(name, artifact.stagedFile) != null) {
                return PrepareResult.Invalid(InvalidReason.EXTRA_ASSET)
            }
        }
        val missing = expected.keys - actual.keys
        if (missing.isNotEmpty()) return PrepareResult.Invalid(InvalidReason.MISSING_ASSET)
        if ((actual.keys - expected.keys).isNotEmpty()) return PrepareResult.Invalid(InvalidReason.EXTRA_ASSET)
        for ((name, records) in expected) {
            if (!GeneratedImagePortableBackup.isValidAsset(actual.getValue(name), records.first())) {
                return PrepareResult.Invalid(InvalidReason.INVALID_ASSET)
            }
        }
        return PrepareResult.Ready(Prepared(snapshot, actual))
    }

    fun restore(
        context: Context,
        prepared: Prepared
    ): GeneratedImageRestoreTransaction.Result {
        val app = context.applicationContext
        val imagesDir = app.getExternalFilesDir("images")
            ?: return GeneratedImageRestoreTransaction.Result.Failed(
                GeneratedImageRestoreTransaction.Failure.APPLY_FAILED
            )
        return GeneratedImageRestoreTransaction.replace(
            journalRoot = File(app.filesDir, JOURNAL_DIR),
            imagesDir = imagesDir,
            desired = prepared.snapshot,
            incomingAssets = prepared.assets,
            catalog = backend(app)
        )
    }

    fun recoverPending(context: Context): Boolean {
        val app = context.applicationContext
        val journal = File(app.filesDir, JOURNAL_DIR)
        if (!journal.exists()) return true
        val imagesDir = app.getExternalFilesDir("images") ?: return false
        return GeneratedImageRestoreTransaction.recover(journal, imagesDir, backend(app))
    }

    private fun backend(context: Context) = object : GeneratedImageRestoreTransaction.CatalogBackend {
        override fun snapshot(): GeneratedImageCatalogSnapshot? {
            val result = GeneratedImageCatalogStore.exportSnapshot(context)
            return result.snapshot.takeIf { result.state == GeneratedImageCatalogStorageState.AVAILABLE }
        }

        override fun replace(snapshot: GeneratedImageCatalogSnapshot): Boolean {
            val result = GeneratedImageCatalogStore.replaceSnapshot(context, snapshot)
            return result.success && result.state == GeneratedImageCatalogStorageState.AVAILABLE
        }
    }

    private const val JOURNAL_DIR = "generated_image_restore_journal"
}
