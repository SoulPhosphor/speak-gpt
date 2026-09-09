/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.teslasoft.assistant.imagegen.ImageFormat
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogHealth
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Collects the complete active generated-image gallery for a portable
 * package. No database file or installation key is copied. */
object GeneratedImagePortableBackup {

    data class Inventory(
        val imageCount: Int,
        val uniqueAssetCount: Int,
        val imageBytes: Long,
        val galleryOnlyCount: Int
    )

    enum class Failure {
        CATALOG_UNAVAILABLE,
        INVALID_CATALOG,
        MISSING_ASSET,
        INVALID_ASSET
    }

    sealed class Result {
        data object NothingToBackUp : Result()
        data class Ok(
            val artifacts: List<PortablePackage.Artifact>,
            val inventory: Inventory
        ) : Result()
        data class Failed(val reason: Failure) : Result()
    }

    fun buildArtifacts(context: Context, stagingDir: File): Result {
        val app = context.applicationContext
        val database = app.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME)
        val snapshot = if (!database.exists() &&
            !GeneratedImageCatalogHealth.missingDatabaseRequiresRecovery(app)
        ) {
            GeneratedImageCatalogSnapshot(emptyList(), emptyList(), emptyMap(), emptyMap())
        } else {
            val exported = GeneratedImageCatalogStore.exportSnapshot(app)
            if (exported.state != GeneratedImageCatalogStorageState.AVAILABLE) {
                return Result.Failed(Failure.CATALOG_UNAVAILABLE)
            }
            exported.snapshot ?: return Result.Failed(Failure.CATALOG_UNAVAILABLE)
        }
        if (GeneratedImagePortableCatalog.validate(snapshot) != null) {
            return Result.Failed(Failure.INVALID_CATALOG)
        }

        val imagesDir = if (snapshot.active.isEmpty()) null else app.getExternalFilesDir("images")
            ?: return Result.Failed(Failure.MISSING_ASSET)
        val artifacts = ArrayList<PortablePackage.Artifact>()
        val uniqueRecords = LinkedHashMap<String, GeneratedImageCatalogRecord>()
        for (record in snapshot.active) {
            val prior = uniqueRecords.putIfAbsent(record.assetFileName, record)
            if (prior != null &&
                (!prior.fileHash.equals(record.fileHash, ignoreCase = true) ||
                    prior.mimeType != record.mimeType)
            ) return Result.Failed(Failure.INVALID_CATALOG)
        }

        var bytes = 0L
        for ((fileName, record) in uniqueRecords) {
            if (!GeneratedImagePortableCatalog.safeAssetName(fileName)) {
                return Result.Failed(Failure.INVALID_CATALOG)
            }
            val file = File(imagesDir ?: return Result.Failed(Failure.MISSING_ASSET), fileName)
            if (!file.isFile) return Result.Failed(Failure.MISSING_ASSET)
            if (file.length() <= 0L || file.length() > PortablePackage.MAX_ENTRY_BYTES) {
                return Result.Failed(Failure.INVALID_ASSET)
            }
            if (!isValidAsset(file, record)) {
                return Result.Failed(Failure.INVALID_ASSET)
            }
            bytes += file.length()
            if (bytes > PortablePackage.MAX_TOTAL_BYTES) return Result.Failed(Failure.INVALID_ASSET)
            artifacts.add(
                PortablePackage.Artifact(
                    entryName = "generated_images/assets/$fileName",
                    type = PortablePackage.TYPE_GENERATED_IMAGE_ASSET,
                    file = file,
                    databaseKeyHex = null,
                    keySemantics = null,
                    schemaVersion = null
                )
            )
        }

        val catalog = File(stagingDir, "generated_images_catalog.json")
        catalog.writeText(GeneratedImagePortableCatalog.toJson(snapshot), Charsets.UTF_8)
        artifacts.add(
            0,
            PortablePackage.Artifact(
                entryName = "generated_images/catalog.json",
                type = PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
                file = catalog,
                databaseKeyHex = null,
                keySemantics = null,
                schemaVersion = 1
            )
        )
        return Result.Ok(
            artifacts,
            Inventory(
                imageCount = snapshot.active.size,
                uniqueAssetCount = uniqueRecords.size,
                imageBytes = bytes,
                galleryOnlyCount = snapshot.active.count { it.originChatId == null }
            )
        )
    }

    private fun sniff(file: File): ImageFormat? {
        val prefix = ByteArray(16)
        val count = file.inputStream().use { it.read(prefix) }
        if (count < 12) return null
        return ImageFormat.detect(prefix.copyOf(count))
    }

    internal fun isValidAsset(file: File, record: GeneratedImageCatalogRecord): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > PortablePackage.MAX_ENTRY_BYTES) {
            return false
        }
        val format = sniff(file) ?: return false
        if (record.mimeType != null && record.mimeType != format.mimeType) return false
        if (!format.fileExtension.equals(file.extension, ignoreCase = true) &&
            !(format == ImageFormat.JPEG && file.extension.equals("jpeg", ignoreCase = true))
        ) return false
        return legacyContentHash(file).equals(record.fileHash, ignoreCase = true)
    }

    /** Catalog hashes predate the portable format and intentionally hash the
     * Base64 encoding of the image bytes. Stream that encoding so inventory
     * never loads an entire gallery image into memory. */
    private fun legacyContentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = DigestOutputStream(DiscardingOutputStream, digest)
        Base64.getEncoder().wrap(sink).use { encoded ->
            file.inputStream().use { input -> input.copyTo(encoded, 64 * 1024) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private object DiscardingOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
