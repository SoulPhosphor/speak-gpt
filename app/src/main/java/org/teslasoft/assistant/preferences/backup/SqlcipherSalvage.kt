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

package org.teslasoft.assistant.preferences.backup

import org.teslasoft.assistant.preferences.lorebook.LoreBookEncryption
import java.io.File
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/**
 * Bounded staged salvage of a damaged SQLCipher database (Build Phase 3 items
 * 2–3; "repair" per the build plan's honesty note: *salvage, not magic* — a
 * recover-what-reads pass into a SEPARATE staged file, never in-place on the
 * only copy).
 *
 * Method: open the damaged source READ-ONLY with its own key, read its schema
 * out of sqlite_master, create a fresh staged database with the SAME key,
 * replay the schema, then copy rows table by table — each table and each row
 * individually guarded, so one unreadable page loses that page's rows, not the
 * whole store. Indexes/triggers/views are recreated after the data (a failing
 * index is skipped rather than sinking the salvage). The staged result must
 * pass a full `PRAGMA integrity_check` before the caller may swap it in;
 * salvage failure is a NORMAL, designed outcome that routes to the revert
 * path.
 *
 * The caller quarantines the original BEFORE calling this (preserve-the-
 * original law) and owns the swap; this object never touches the source file
 * beyond reading it. An empty [key] is legal only for a legacy plaintext
 * lorebook (SQLCipher with an empty passphrase operates plaintext —
 * LoreBookEncryption contract).
 */
object SqlcipherSalvage {

    data class Result(
        val ok: Boolean,
        val tablesCopied: Int,
        val tablesLost: Int,
        val rowsCopied: Long,
        val rowsLost: Long,
        /** Exception class name of the terminal failure when !ok. */
        val detail: String?
    )

    private data class SchemaObject(val type: String, val name: String, val sql: String)

    /**
     * Salvage [srcPath] into [staged]. Returns a failed [Result] instead of
     * throwing; [staged] is deleted again on failure so a half-written file
     * can never be mistaken for a repaired database.
     */
    fun salvage(srcPath: String, key: ByteArray, staged: File): Result {
        var tablesCopied = 0
        var tablesLost = 0
        var rowsCopied = 0L
        var rowsLost = 0L
        try {
            LoreBookEncryption.loadLibrary()
            if (staged.exists()) staged.delete()
            val src = CipherDatabase.openDatabase(srcPath, key, null, CipherDatabase.OPEN_READONLY, null, null)
            try {
                // The schema version must be readable: a fresh helper open of
                // the salvaged file relies on user_version to run (or skip)
                // migrations; guessing it could re-run destructive steps.
                val userVersion = src.rawQuery("PRAGMA user_version", emptyArray<String>()).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else throw IllegalStateException("user_version unreadable")
                }

                val schema = ArrayList<SchemaObject>()
                src.rawQuery(
                    "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL",
                    emptyArray<String>()
                ).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (name.startsWith("sqlite_") || name == "android_metadata") continue
                        schema.add(SchemaObject(c.getString(0) ?: "", name, c.getString(2) ?: ""))
                    }
                }
                if (schema.none { it.type == "table" }) throw IllegalStateException("no readable schema")

                val dst = CipherDatabase.openOrCreateDatabase(staged.path, key, null, null)
                try {
                    // FK enforcement stays OFF for the whole copy: rows are
                    // salvaged table by table, so parents may arrive after
                    // children, and a lost parent must not cascade away the
                    // children that DID survive.
                    dst.execSQL("PRAGMA foreign_keys=OFF")

                    for (obj in schema.filter { it.type == "table" }) {
                        try {
                            dst.execSQL(obj.sql)
                        } catch (_: Exception) {
                            tablesLost++
                            continue
                        }
                        when (val copied = copyTable(src, dst, obj.name)) {
                            null -> tablesLost++
                            else -> {
                                tablesCopied++
                                rowsCopied += copied.first
                                rowsLost += copied.second
                            }
                        }
                    }
                    // Secondary objects after the data; each is optional.
                    for (obj in schema.filter { it.type != "table" }) {
                        try { dst.execSQL(obj.sql) } catch (_: Exception) { /* skipped */ }
                    }
                    dst.execSQL("PRAGMA user_version = $userVersion")
                } finally {
                    try { dst.close() } catch (_: Exception) { }
                }
            } finally {
                try { src.close() } catch (_: Exception) { }
            }

            // The staged result must be provably healthy before anyone trusts
            // it (throws on failure). An empty key produced a plaintext file
            // (legacy lorebook), which the plain checker verifies — same split
            // PortableRecoveryWriter uses.
            if (key.isEmpty()) RecoveryBackupManager.integrityCheckPlain(staged)
            else RecoveryBackupManager.integrityCheckCipher(staged, key)
            return Result(true, tablesCopied, tablesLost, rowsCopied, rowsLost, null)
        } catch (e: Exception) {
            try { if (staged.exists()) staged.delete() } catch (_: Exception) { }
            return Result(false, tablesCopied, tablesLost, rowsCopied, rowsLost, e.javaClass.simpleName)
        }
    }

    /** @return (rowsCopied, rowsLost), or null when the table produced no
     *  usable cursor at all. A cursor failure mid-iteration keeps the rows
     *  already copied and abandons the rest of that table. */
    private fun copyTable(src: CipherDatabase, dst: CipherDatabase, table: String): Pair<Long, Long>? {
        val quoted = "\"" + table.replace("\"", "\"\"") + "\""
        var copied = 0L
        var lost = 0L
        return try {
            src.rawQuery("SELECT * FROM $quoted", emptyArray<String>()).use { c ->
                val cols = c.columnCount
                if (cols <= 0) return@use
                val placeholders = (1..cols).joinToString(",") { "?" }
                val insertSql = "INSERT OR IGNORE INTO $quoted VALUES ($placeholders)"
                dst.beginTransaction()
                try {
                    while (true) {
                        val advanced = try { c.moveToNext() } catch (_: Exception) {
                            lost++ // the page under the cursor was unreadable
                            break
                        }
                        if (!advanced) break
                        try {
                            val args = arrayOfNulls<Any>(cols)
                            for (i in 0 until cols) {
                                args[i] = when (c.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> null
                                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                                    else -> c.getString(i)
                                }
                            }
                            dst.execSQL(insertSql, args)
                            copied++
                        } catch (_: Exception) {
                            lost++
                        }
                    }
                    dst.setTransactionSuccessful()
                } finally {
                    dst.endTransaction()
                }
            }
            Pair(copied, lost)
        } catch (_: Exception) {
            null
        }
    }
}
