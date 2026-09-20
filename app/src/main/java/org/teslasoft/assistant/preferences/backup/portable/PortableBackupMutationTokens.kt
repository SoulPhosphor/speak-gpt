/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageDb

/**
 * Content generations for every authoritative source consumed by a portable
 * backup. These tokens deliberately describe bytes rather than timestamps:
 * normal app mutations do not have to cooperate with the backup engine, and a
 * same-size write inside one filesystem timestamp tick is still detected.
 *
 * The first set is read before category capture and the second after all
 * artifacts are staged. No token is retained as user data.
 */
internal object PortableBackupMutationTokens {
    fun read(context: Context): Map<String, String>? = try {
        val app = context.applicationContext
        linkedMapOf(
            "shared_preferences" to digestTree(File(app.dataDir, "shared_prefs"), includeContents = true),
            "memory_database" to digestDatabase(app, MemoryStore.DATABASE_NAME),
            "lorebook_database" to digestDatabase(app, LoreBookStore.DATABASE_NAME),
            "profile_image_database" to digestDatabase(app, ProfileImageDb.DATABASE_NAME),
            "generated_image_database" to digestDatabase(app, GeneratedImageCatalogStore.DATABASE_NAME),
            "profile_image_assets" to digestTree(app.getExternalFilesDir("profile_images"), includeContents = true),
            "generated_image_assets" to digestTree(app.getExternalFilesDir("images"), includeContents = true)
        )
    } catch (_: Exception) {
        null
    }

    private fun digestDatabase(context: Context, name: String): String {
        val main = context.getDatabasePath(name)
        return digestFiles(
            listOf(
                main,
                File(main.path + "-wal"),
                File(main.path + "-shm"),
                File(main.path + "-journal")
            ),
            includeContents = true
        )
    }

    private fun digestTree(root: File?, includeContents: Boolean): String {
        if (root == null) return "unavailable"
        if (!root.exists()) return "absent"
        val files = root.walkTopDown().filter(File::isFile).toList()
        return digestFiles(files, includeContents, root)
    }

    private fun digestFiles(
        files: List<File>,
        includeContents: Boolean,
        relativeTo: File? = null
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { file -> relativeTo?.let { file.relativeTo(it).invariantSeparatorsPath } ?: file.name }
            .forEach { file ->
                val name = relativeTo?.let { file.relativeTo(it).invariantSeparatorsPath } ?: file.name
                update(digest, name)
                if (!file.isFile) {
                    update(digest, "absent")
                    return@forEach
                }
                update(digest, file.length().toString())
                if (includeContents) {
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(digest: MessageDigest, value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
}
