/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming
import java.io.File
import java.security.MessageDigest

/**
 * Collects the complete Avatar/Profile Images gallery from the already
 * verified catalog snapshot. The catalog is the point-in-time authority;
 * content-addressed JPEGs are immutable, and a concurrent disappearance makes
 * the run fail instead of publishing a catalog with missing pixels.
 */
object ProfileImagePortableBackup {

    data class Inventory(val imageCount: Int, val imageBytes: Long)

    enum class Failure { INVALID_CATALOG, MISSING_ASSET, INVALID_ASSET }

    sealed class Result {
        data class Ok(
            val artifacts: List<PortablePackage.Artifact>,
            val inventory: Inventory
        ) : Result()
        data class Failed(val reason: Failure) : Result()
    }

    fun buildArtifacts(context: Context, catalogSnapshot: File): Result {
        val hashes = try {
            readHashes(catalogSnapshot)
        } catch (_: Exception) {
            return Result.Failed(Failure.INVALID_CATALOG)
        }
        val imagesDir = context.applicationContext.getExternalFilesDir("profile_images")
        if (hashes.isNotEmpty() && imagesDir == null) {
            return Result.Failed(Failure.MISSING_ASSET)
        }

        val artifacts = ArrayList<PortablePackage.Artifact>()
        var bytes = 0L
        for (hash in hashes) {
            val fileName = ProfileImageFileNaming.permanentFileName(hash)
            val file = File(imagesDir ?: return Result.Failed(Failure.MISSING_ASSET), fileName)
            if (!file.isFile) return Result.Failed(Failure.MISSING_ASSET)
            if (!isValidAsset(file, hash)) return Result.Failed(Failure.INVALID_ASSET)
            bytes += file.length()
            if (bytes > PortablePackage.MAX_TOTAL_BYTES) {
                return Result.Failed(Failure.INVALID_ASSET)
            }
            artifacts.add(
                PortablePackage.Artifact(
                    entryName = "profile_images/assets/$fileName",
                    type = PortablePackage.TYPE_PROFILE_IMAGE_ASSET,
                    file = file,
                    databaseKeyHex = null,
                    keySemantics = null,
                    schemaVersion = null
                )
            )
        }
        return Result.Ok(artifacts, Inventory(hashes.size, bytes))
    }

    private fun readHashes(catalogSnapshot: File): List<String> {
        if (!catalogSnapshot.isFile) throw IllegalArgumentException("missing catalog")
        val hashes = ArrayList<String>()
        val seen = HashSet<String>()
        SQLiteDatabase.openDatabase(
            catalogSnapshot.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("SELECT hash FROM profile_images ORDER BY created_at DESC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val hash = cursor.getString(0)
                    if (!HASH.matches(hash) || !seen.add(hash)) {
                        throw IllegalArgumentException("invalid profile image identity")
                    }
                    hashes.add(hash)
                }
            }
        }
        return hashes
    }

    internal fun isValidAsset(file: File, expectedHash: String): Boolean {
        if (!HASH.matches(expectedHash) || !file.isFile || file.length() <= 3L ||
            file.length() > PortablePackage.MAX_ENTRY_BYTES
        ) return false
        val prefix = ByteArray(3)
        if (file.inputStream().use { it.read(prefix) } != prefix.size ||
            prefix[0] != 0xff.toByte() || prefix[1] != 0xd8.toByte() || prefix[2] != 0xff.toByte()
        ) return false

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == expectedHash
    }

    private val HASH = Regex("^[0-9a-f]{64}$")
}
