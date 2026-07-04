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

package org.teslasoft.assistant.preferences.lorebook

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import java.io.File
import java.io.FileInputStream

/**
 * One-time in-place encryption of the legacy plaintext lorebook.db
 * (owner-requested: the lorebook holds the same class of personal material as
 * the companion memory store, so it gets the same SQLCipher treatment).
 *
 * [obtainPassword] is the single entry point, called by LoreBookStore before
 * the open helper is constructed. It returns the SQLCipher password to open
 * lorebook.db with, running the plaintext -> encrypted migration when needed:
 * export into a temp file via sqlcipher_export, verify (integrity + row counts
 * + schema version), then swap with the plaintext original kept aside until
 * the swap completes. On ANY failure the plaintext database keeps working
 * (empty password = plaintext for SQLCipher) and the migration retries on the
 * next app start — lorebooks must never stop working because encryption
 * couldn't happen (same never-wipe spirit as ChatPreferences).
 *
 * Only the already-encrypted-but-key-unreadable case throws: opening an
 * encrypted database without its key is impossible by design, and minting a
 * fresh key would brick the store permanently (see DatabaseKeys).
 */
object LoreBookEncryption {

    private val PLAINTEXT_HEADER = "SQLite format 3".toByteArray(Charsets.UTF_8)

    @Volatile
    private var libraryLoaded = false

    fun loadLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("sqlcipher")
            libraryLoaded = true
        }
    }

    fun obtainPassword(context: Context, databaseName: String): ByteArray {
        loadLibrary()
        val dbFile = context.getDatabasePath(databaseName)

        if (dbFile.exists() && !isPlaintextDatabase(dbFile)) {
            // Already encrypted: the key is mandatory.
            return DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_LOREBOOK, databaseExists = true)
                ?: throw IllegalStateException(
                    "Lorebook database is encrypted but its key could not be read"
                )
        }

        // Plaintext database (or fresh install). databaseExists=false here even
        // when the plaintext file exists: no ENCRYPTED database depends on the
        // stored key yet, so minting one is always safe.
        val key = DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_LOREBOOK, databaseExists = false)
        if (key == null) {
            MemoryLog.log(context, "LoreBookEncryption", "error",
                "Could not create/read the lorebook encryption key; lorebook stays unencrypted for now."
            )
            return ByteArray(0)
        }

        if (!dbFile.exists()) return key // fresh install: created encrypted from the start

        return if (encryptInPlace(context, dbFile, key)) {
            key
        } else {
            // Migration failed: keep operating on the intact plaintext file and
            // retry on the next app start.
            ByteArray(0)
        }
    }

    /** Plaintext SQLite files start with a fixed 16-byte magic; SQLCipher files
     *  begin with random salt. Reading the header is the reliable detector. */
    private fun isPlaintextDatabase(file: File): Boolean {
        return try {
            val header = ByteArray(PLAINTEXT_HEADER.size)
            FileInputStream(file).use { it.read(header) }
            header.contentEquals(PLAINTEXT_HEADER)
        } catch (_: Exception) {
            false
        }
    }

    private fun encryptInPlace(context: Context, plainFile: File, key: ByteArray): Boolean {
        val tmp = File(plainFile.parentFile, plainFile.name + ".enc-tmp")
        val backup = File(plainFile.parentFile, plainFile.name + ".plain-backup")
        tmp.delete()
        var plain: SQLiteDatabase? = null
        try {
            plain = SQLiteDatabase.openDatabase(
                plainFile.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null
            )
            val version = plain.version
            val books = countRows(plain, "lorebooks")
            val entries = countRows(plain, "memory_entries")

            // Raw-key literal (x'…') — binding the key as a parameter would make
            // SQLCipher derive a key FROM the string instead of using the bytes.
            plain.rawExecSQL(
                "ATTACH DATABASE ? AS encrypted KEY \"x'${DatabaseKeys.toHex(key)}'\"", tmp.path
            )
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            plain.rawExecSQL("PRAGMA encrypted.user_version = $version")
            plain.rawExecSQL("DETACH DATABASE encrypted")
            plain.close()
            plain = null

            // Verify the encrypted copy before touching the original.
            val enc = SQLiteDatabase.openDatabase(
                tmp.path, key, null, SQLiteDatabase.OPEN_READWRITE, null, null
            )
            val ok = enc.isDatabaseIntegrityOk &&
                enc.version == version &&
                countRows(enc, "lorebooks") == books &&
                countRows(enc, "memory_entries") == entries
            enc.close()
            if (!ok) {
                tmp.delete()
                MemoryLog.log(context, "LoreBookEncryption", "error",
                    "Encrypted lorebook copy failed verification; keeping the plaintext database."
                )
                return false
            }

            // Swap with the original kept aside until the encrypted file is in
            // place — a crash mid-swap leaves either the backup or the original
            // to fall back on, never neither.
            File(plainFile.path + "-journal").delete()
            File(plainFile.path + "-wal").delete()
            File(plainFile.path + "-shm").delete()
            backup.delete()
            if (!plainFile.renameTo(backup)) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(plainFile)) {
                backup.renameTo(plainFile) // restore the plaintext original
                tmp.delete()
                return false
            }
            backup.delete()
            MemoryLog.log(context, "LoreBookEncryption", "info",
                "lorebook.db encrypted in place ($books books, $entries memories)."
            )
            return true
        } catch (e: Exception) {
            try { plain?.close() } catch (_: Exception) { /* already closing on error */ }
            tmp.delete()
            // If the aside-rename happened but the swap didn't, restore it.
            if (!plainFile.exists() && backup.exists()) backup.renameTo(plainFile)
            MemoryLog.log(context, "LoreBookEncryption", "error",
                "Lorebook encryption failed (${e.message}); keeping the plaintext database."
            )
            return false
        }
    }

    /** -1 when the table doesn't exist (old schema versions predate some
     *  tables; onUpgrade runs after migration, on the encrypted file). The
     *  verification only needs plaintext and encrypted counts to MATCH. */
    private fun countRows(db: SQLiteDatabase, table: String): Long {
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $table", emptyArray<String>()).use {
                if (it.moveToFirst()) it.getLong(0) else -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}
