/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.imagegen.ImageFormat
import java.io.File

/** The only catalog/legacy path resolver. A known active catalog row or
 * tombstone wins over hash fallback; fallback is reserved for genuine legacy
 * data with no catalog knowledge. */
object GeneratedImageAssetResolver {

    sealed class Result {
        data class Available(
            val file: File,
            val mimeType: String,
            val catalogManaged: Boolean
        ) : Result()

        data class Missing(val explicitlyDeleted: Boolean) : Result()
        data class CatalogUnavailable(val state: GeneratedImageCatalogStorageState) : Result()
    }

    fun resolve(
        context: Context,
        metadata: GeneratedImageMetadata?,
        legacyHash: String?
    ): Result {
        val imagesDir = context.applicationContext.getExternalFilesDir("images")
            ?: return Result.Missing(explicitlyDeleted = false)
        val imageId = metadata?.imageId?.trim().orEmpty()

        if (imageId.isNotEmpty()) {
            val lookup = GeneratedImageCatalogStore.lookup(context, imageId)
            when (lookup.state) {
                GeneratedImageCatalogStorageState.AVAILABLE -> {
                    lookup.record?.let { record ->
                        val file = safeChild(imagesDir, record.assetFileName)
                            ?: return Result.Missing(explicitlyDeleted = false)
                        return if (file.isFile) {
                            Result.Available(
                                file,
                                record.mimeType ?: mimeTypeFor(file),
                                catalogManaged = true
                            )
                        } else {
                            Result.Missing(explicitlyDeleted = false)
                        }
                    }
                    if (lookup.tombstoned) return Result.Missing(explicitlyDeleted = true)
                    // No catalog/tombstone knowledge: this is structured legacy
                    // metadata from before the catalog and may use a hash path.
                }
                else -> {
                    // Message metadata carries the canonical relative path so a
                    // temporary catalog lock does not blank otherwise-good chat
                    // history. Never hash-fallback a known canonical item here.
                    metadata?.assetFileName?.let { name ->
                        safeChild(imagesDir, name)?.takeIf { it.isFile }?.let { file ->
                            return Result.Available(
                                file,
                                metadata.mimeType ?: mimeTypeFor(file),
                                catalogManaged = true
                            )
                        }
                    }
                    return Result.CatalogUnavailable(lookup.state)
                }
            }
        }

        metadata?.assetFileName?.let { name ->
            safeChild(imagesDir, name)?.takeIf { it.isFile }?.let { file ->
                return Result.Available(
                    file,
                    metadata.mimeType ?: mimeTypeFor(file),
                    catalogManaged = true
                )
            }
        }

        val hash = metadata?.fileHash?.takeIf { it.isNotBlank() }
            ?: legacyHash?.takeIf { it.isNotBlank() }
            ?: return Result.Missing(explicitlyDeleted = false)
        for (format in ImageFormat.entries) {
            val file = File(imagesDir, "$hash.${format.fileExtension}")
            if (file.isFile) return Result.Available(file, format.mimeType, catalogManaged = false)
        }
        return Result.Missing(explicitlyDeleted = false)
    }

    fun findLegacyFile(context: Context, fileHash: String): File? {
        val dir = context.applicationContext.getExternalFilesDir("images") ?: return null
        return ImageFormat.entries.asSequence()
            .map { File(dir, "$fileHash.${it.fileExtension}") }
            .firstOrNull { it.isFile }
    }

    /** Resolves a current catalog identity without accepting a raw path. */
    fun resolveCatalogImage(context: Context, imageId: String): Result {
        val lookup = GeneratedImageCatalogStore.lookup(context, imageId)
        if (lookup.state != GeneratedImageCatalogStorageState.AVAILABLE) {
            return Result.CatalogUnavailable(lookup.state)
        }
        val record = lookup.record ?: return Result.Missing(lookup.tombstoned)
        val dir = context.applicationContext.getExternalFilesDir("images")
            ?: return Result.Missing(explicitlyDeleted = false)
        val file = safeChild(dir, record.assetFileName)
            ?: return Result.Missing(explicitlyDeleted = false)
        return if (file.isFile) {
            Result.Available(file, record.mimeType ?: mimeTypeFor(file), catalogManaged = true)
        } else {
            Result.Missing(explicitlyDeleted = false)
        }
    }

    private fun safeChild(parent: File, name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
        return File(parent, name)
    }

    private fun mimeTypeFor(file: File): String = ImageFormat.entries
        .firstOrNull { it.fileExtension.equals(file.extension, ignoreCase = true) }
        ?.mimeType ?: "image/png"
}
