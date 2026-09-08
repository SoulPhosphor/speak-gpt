/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming
import org.teslasoft.assistant.preferences.profileimages.ProfileImageRecord

/** Read-only preparation of the complete profile-image gallery artifacts. */
object ProfileImagePortableRestoreManager {
    data class Prepared(
        val records: List<ProfileImageRecord>,
        val assets: Map<String, File>
    )

    sealed class Result {
        data class Ready(val prepared: Prepared) : Result()
        data object Invalid : Result()
    }

    fun prepare(artifacts: List<PortablePackage.ValidatedArtifact>): Result {
        val catalogs = artifacts.filter {
            it.type == PortablePackage.TYPE_SQLITE_DB && it.entryName == "user_images.db"
        }
        if (catalogs.size != 1) return Result.Invalid
        val records = readCatalog(catalogs.single().stagedFile) ?: return Result.Invalid
        val assetArtifacts = artifacts.filter { it.type == PortablePackage.TYPE_PROFILE_IMAGE_ASSET }
        val assets = LinkedHashMap<String, File>()
        for (artifact in assetArtifacts) {
            val fileName = artifact.entryName.substringAfterLast('/')
            val hash = ProfileImageFileNaming.hashFromPermanentFilename(fileName) ?: return Result.Invalid
            if (assets.putIfAbsent(hash, artifact.stagedFile) != null ||
                !ProfileImagePortableBackup.isValidAsset(artifact.stagedFile, hash)
            ) return Result.Invalid
        }
        val hashes = records.map(ProfileImageRecord::hash).toSet()
        if (assets.keys != hashes) return Result.Invalid
        return Result.Ready(Prepared(records, assets))
    }

    private fun readCatalog(file: File): List<ProfileImageRecord>? {
        return try {
            if (!file.isFile) return null
            val records = ArrayList<ProfileImageRecord>()
            val seen = HashSet<String>()
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { check ->
                    if (!check.moveToFirst() || !check.getString(0).equals("ok", true)) return null
                }
                db.rawQuery("SELECT hash, created_at FROM profile_images ORDER BY created_at DESC", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val hash = cursor.getString(0)
                        val createdAt = cursor.getLong(1)
                        if (!HASH.matches(hash) || createdAt < 0L || !seen.add(hash)) return null
                        records.add(ProfileImageRecord(hash, createdAt))
                    }
                }
            }
            records
        } catch (_: Exception) {
            null
        }
    }

    private val HASH = Regex("^[0-9a-f]{64}$")
}
