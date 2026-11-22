/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * The scanner comparison cache (Stage 6B.2; owner approval 2026-11-22): two
 * tables in the encrypted memory database, holding comparison vectors only,
 * never memory or conversation text.
 *
 * - [FILED_TABLE] keeps vectors of filed Pending and Archived memories, by
 *   memory, embedding model and a fingerprint of the exact text the vector
 *   was made from. Deleting a memory deletes its rows (foreign key); changed
 *   text or another model never matches, and the stale row is replaced.
 * - [DRAFT_TABLE] keeps vectors of one scan range's working drafts so a
 *   paused or interrupted scan reuses them. Its rows go with the range's
 *   temporary candidate collection (foreign key) and are cleared when the
 *   range restarts.
 *
 * Chat retrieval never reads either table, so nothing here can make an
 * Archived or Pending memory reach a chat. Both tables together hold at most
 * [MAX_ENTRIES] rows, least recently used removed first. Neither is part of
 * any export or portable backup, and everything can be rebuilt.
 */
object ScannerComparisonCache {

    const val FILED_TABLE = "scanner_comparison_vectors"
    const val DRAFT_TABLE = "scanner_draft_vectors"
    const val MAX_ENTRIES = 5_000

    /** Creates both tables; idempotent (fresh install and migration v38). */
    fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $FILED_TABLE (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "embedding_model TEXT NOT NULL, " +
                "text_fingerprint TEXT NOT NULL, " +
                "vector BLOB NOT NULL, " +
                "last_used_at INTEGER NOT NULL, " +
                "PRIMARY KEY (memory_id, embedding_model))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $DRAFT_TABLE (" +
                "range_id TEXT NOT NULL REFERENCES analysis_run_state(run_id) ON DELETE CASCADE, " +
                "draft_id INTEGER NOT NULL, " +
                "embedding_model TEXT NOT NULL, " +
                "text_fingerprint TEXT NOT NULL, " +
                "vector BLOB NOT NULL, " +
                "last_used_at INTEGER NOT NULL, " +
                "PRIMARY KEY (range_id, draft_id, embedding_model))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_${FILED_TABLE}_used ON $FILED_TABLE(last_used_at)"
        )
    }

    /** Cached filed vectors whose fingerprint still matches, by memory ID. */
    fun filedVectors(
        db: SQLiteDatabase,
        modelKey: String,
        fingerprints: Map<String, String>,
        now: Long = System.currentTimeMillis()
    ): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        for (batch in fingerprints.keys.chunked(BATCH)) {
            val marks = batch.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT memory_id, text_fingerprint, vector FROM $FILED_TABLE " +
                    "WHERE embedding_model = ? AND memory_id IN ($marks)",
                arrayOf(modelKey, *batch.toTypedArray())
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (fingerprints[id] == c.getString(1)) out[id] = c.getBlob(2)
                }
            }
        }
        touch(db, FILED_TABLE, "memory_id", out.keys.toList(), "embedding_model = ?", arrayOf(modelKey), now)
        return out
    }

    /** Keeps one filed memory's vector. False when the memory no longer
     *  exists (it was deleted meanwhile); nothing is kept then. */
    fun putFiled(
        db: SQLiteDatabase,
        memoryId: String,
        modelKey: String,
        fingerprint: String,
        vector: ByteArray,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        db.beginTransaction()
        try {
            val exists = db.rawQuery(
                "SELECT 1 FROM memories WHERE memory_id = ?", arrayOf(memoryId)
            ).use { it.moveToFirst() }
            if (!exists) return false
            db.insertWithOnConflict(FILED_TABLE, null, ContentValues().apply {
                put("memory_id", memoryId)
                put("embedding_model", modelKey)
                put("text_fingerprint", fingerprint)
                put("vector", vector)
                put("last_used_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            evict(db)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Cached vectors of a range's drafts whose fingerprint still matches. */
    fun draftVectors(
        db: SQLiteDatabase,
        rangeId: String,
        modelKey: String,
        fingerprints: Map<Int, String>,
        now: Long = System.currentTimeMillis()
    ): Map<Int, ByteArray> {
        if (fingerprints.isEmpty()) return emptyMap()
        val out = HashMap<Int, ByteArray>()
        db.rawQuery(
            "SELECT draft_id, text_fingerprint, vector FROM $DRAFT_TABLE " +
                "WHERE range_id = ? AND embedding_model = ?",
            arrayOf(rangeId, modelKey)
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                if (fingerprints[id] == c.getString(1)) out[id] = c.getBlob(2)
            }
        }
        touch(
            db, DRAFT_TABLE, "draft_id", out.keys.map { it.toLong() },
            "range_id = ? AND embedding_model = ?", arrayOf(rangeId, modelKey), now
        )
        return out
    }

    /** Keeps a working draft's vector. False when the range's collection no
     *  longer exists (cancelled or finished meanwhile). */
    fun putDraft(
        db: SQLiteDatabase,
        rangeId: String,
        draftId: Int,
        modelKey: String,
        fingerprint: String,
        vector: ByteArray,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        db.beginTransaction()
        try {
            val exists = db.rawQuery(
                "SELECT 1 FROM analysis_run_state WHERE run_id = ?", arrayOf(rangeId)
            ).use { it.moveToFirst() }
            if (!exists) return false
            db.insertWithOnConflict(DRAFT_TABLE, null, ContentValues().apply {
                put("range_id", rangeId)
                put("draft_id", draftId)
                put("embedding_model", modelKey)
                put("text_fingerprint", fingerprint)
                put("vector", vector)
                put("last_used_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            evict(db)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** A range's draft vectors, when its drafts are discarded. */
    fun clearDrafts(db: SQLiteDatabase, rangeId: String) {
        db.delete(DRAFT_TABLE, "range_id = ?", arrayOf(rangeId))
    }

    /** Every range's draft vectors (a restore ends all scans). */
    fun clearAllDrafts(db: SQLiteDatabase) {
        db.delete(DRAFT_TABLE, null, null)
    }

    /** Draft vectors whose range collection no longer exists. */
    fun clearOrphanDrafts(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM $DRAFT_TABLE WHERE range_id NOT IN (SELECT run_id FROM analysis_run_state)")
    }

    fun count(db: SQLiteDatabase): Int =
        db.rawQuery(
            "SELECT (SELECT COUNT(*) FROM $FILED_TABLE) + (SELECT COUNT(*) FROM $DRAFT_TABLE)",
            emptyArray<String>()
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Keeps the two tables within [MAX_ENTRIES]: filed rows least recently
     *  used go first (they can be rebuilt any time); a running scan's draft
     *  rows go only if filed rows alone cannot make room. */
    private fun evict(db: SQLiteDatabase) {
        var excess = count(db) - MAX_ENTRIES
        if (excess <= 0) return
        val filed = db.rawQuery("SELECT COUNT(*) FROM $FILED_TABLE", emptyArray<String>())
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val fromFiled = minOf(excess, filed)
        if (fromFiled > 0) {
            db.execSQL(
                "DELETE FROM $FILED_TABLE WHERE rowid IN (SELECT rowid FROM $FILED_TABLE " +
                    "ORDER BY last_used_at ASC, rowid ASC LIMIT $fromFiled)"
            )
            excess -= fromFiled
        }
        if (excess > 0) {
            db.execSQL(
                "DELETE FROM $DRAFT_TABLE WHERE rowid IN (SELECT rowid FROM $DRAFT_TABLE " +
                    "ORDER BY last_used_at ASC, rowid ASC LIMIT $excess)"
            )
        }
    }

    private fun touch(
        db: SQLiteDatabase,
        table: String,
        idColumn: String,
        ids: List<Any>,
        where: String,
        whereArgs: Array<String>,
        now: Long
    ) {
        for (batch in ids.chunked(BATCH)) {
            val marks = batch.joinToString(",") { "?" }
            db.execSQL(
                "UPDATE $table SET last_used_at = ? WHERE $where AND $idColumn IN ($marks)",
                arrayOf<Any>(now, *whereArgs, *batch.toTypedArray())
            )
        }
    }

    private const val BATCH = 400
}
