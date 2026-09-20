/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.companion

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.teslasoft.assistant.util.Hash

/** Writes a validated, category-planned manifest using image bytes from one
 * or more already validated archives. No live app storage is touched. */
object CompanionArchiveAssembler {
    data class Source(val archive: File, val manifest: CompanionBackupManifest)

    fun write(
        destination: File,
        manifest: CompanionBackupManifest,
        sources: List<Source>
    ): Boolean {
        return try {
            val imageSources = LinkedHashMap<String, Pair<File, CompanionBackupImage>>()
            for (source in sources) {
                source.manifest.images.forEach { image ->
                    imageSources.putIfAbsent(image.hash, source.archive to image)
                }
            }
            if (manifest.images.any { it.hash !in imageSources }) return false

            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(CompanionBackupFormat.MANIFEST_ENTRY))
                zip.write(CompanionBackupCodec.toJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (image in manifest.images) {
                    val (archive, sourceImage) = imageSources.getValue(image.hash)
                    val bytes = ZipFile(archive).use { sourceZip ->
                        val entry = sourceZip.getEntry(sourceImage.file) ?: return false
                        sourceZip.getInputStream(entry).use { it.readBytes() }
                    }
                    if (Hash.hash(bytes) != image.hash) return false
                    zip.putNextEntry(ZipEntry(image.file))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            CompanionBackupValidator.validate(destination) is CompanionBackupValidator.Verdict.Valid
        } catch (_: Exception) {
            destination.delete()
            false
        }
    }
}
