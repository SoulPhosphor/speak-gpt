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

package org.teslasoft.assistant.preferences.profileimages

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Catalog of permanent Profile Images: which content hashes have a saved
 * profile_<hash>.jpg file and when each was first added.
 *
 * Deliberately a plain framework SQLite database, not SQLCipher - the owner
 * approved storing Profile Images without application-level encryption (see
 * STORAGE PRIVACY DECISION in profile-images-plan.md): the JPEG files
 * themselves are ordinary files, so encrypting only the catalog would not
 * protect the images, and full media encryption is not approved for this
 * feature. Do not add net.zetetic SQLCipher to this class.
 *
 * The catalog stores only a hash and a timestamp - never is_used or a
 * reference count. Usage is always computed live from the identities that
 * reference a hash (Companions, My Personas, Roleplay Characters, the
 * Default User Image), never cached here.
 */
class ProfileImageDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "profile_images.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_IMAGES = "profile_images"
        private const val COL_HASH = "hash"
        private const val COL_CREATED_AT = "created_at"

        @Volatile
        private var instance: ProfileImageDb? = null

        fun getInstance(context: Context): ProfileImageDb {
            return instance ?: synchronized(this) {
                instance ?: ProfileImageDb(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_IMAGES (" +
                "$COL_HASH TEXT PRIMARY KEY NOT NULL, " +
                "$COL_CREATED_AT INTEGER NOT NULL" +
                ")"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No upgrades yet - schema is v1.
    }

    /**
     * Returns null when the catalog is healthy, otherwise a short description of
     * what `PRAGMA integrity_check` reported (or the exception that prevented
     * the check). Plain framework SQLite (this catalog is deliberately not
     * SQLCipher). DETECTION ONLY for the "Check Database Integrity" button
     * (Build Phase 2); rebuild-from-files repair is Build Phase 3.
     */
    fun integrityCheck(): String? {
        return try {
            readableDatabase.rawQuery("PRAGMA integrity_check", null).use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    if (result.equals("ok", ignoreCase = true)) null else result
                } else "integrity_check returned no rows"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * Inserts [hash] with [createdAt] if it is not already catalogued.
     * Re-uploading byte-identical content must reuse the existing row and
     * keep its original created_at, so this never updates on conflict.
     */
    fun insertOrIgnore(hash: String, createdAt: Long) {
        val values = ContentValues().apply {
            put(COL_HASH, hash)
            put(COL_CREATED_AT, createdAt)
        }
        writableDatabase.insertWithOnConflict(TABLE_IMAGES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun listNewestFirst(): List<ProfileImageRecord> {
        val records = ArrayList<ProfileImageRecord>()
        val cursor = readableDatabase.query(
            TABLE_IMAGES, null, null, null, null, null, "$COL_CREATED_AT DESC"
        )
        cursor.use {
            val hashIdx = it.getColumnIndexOrThrow(COL_HASH)
            val createdIdx = it.getColumnIndexOrThrow(COL_CREATED_AT)
            while (it.moveToNext()) {
                records.add(ProfileImageRecord(it.getString(hashIdx), it.getLong(createdIdx)))
            }
        }
        return records
    }

    fun contains(hash: String): Boolean {
        val cursor = readableDatabase.query(
            TABLE_IMAGES, arrayOf(COL_HASH), "$COL_HASH = ?", arrayOf(hash), null, null, null
        )
        cursor.use { return it.count > 0 }
    }

    /** Removes the catalog row for [hash]. Used after its permanent file is deleted. */
    fun delete(hash: String) {
        writableDatabase.delete(TABLE_IMAGES, "$COL_HASH = ?", arrayOf(hash))
    }

    /**
     * Same removal as [delete], named separately for reconciliation call
     * sites: a catalog record whose permanent file is missing and which no
     * identity references may be cleared this way (see RECONCILIATION in
     * profile-images-plan.md). Kept distinct from [delete] so a future
     * change to one path's semantics does not silently affect the other.
     */
    fun clearMissingUnusedRecord(hash: String) {
        delete(hash)
    }
}

data class ProfileImageRecord(val hash: String, val createdAt: Long)
