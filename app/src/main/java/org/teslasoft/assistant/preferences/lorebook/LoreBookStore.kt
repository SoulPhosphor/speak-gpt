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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.teslasoft.assistant.preferences.dto.LoreBookEntry
import java.util.UUID

/**
 * SQLite-backed storage for lorebook memories (Phase 1 of the memory system).
 *
 * The rest of the app stores its data in SharedPreferences, but the memory
 * system is intentionally built on a real database from the start: later phases
 * (full-text search, conversation summaries, vector embeddings, sync metadata)
 * all need relational rows and queries that key-value preferences can't provide.
 *
 * Two tables:
 *  - memory_entries: one row per memory (id, label, content, source text, state, timestamps)
 *  - memory_triggers: trigger words/phrases, many per memory
 */
class LoreBookStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "lorebook.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_ENTRIES = "memory_entries"
        private const val COL_ID = "id"
        private const val COL_LABEL = "label"
        private const val COL_CONTENT = "content"
        private const val COL_SOURCE_TEXT = "source_text"
        private const val COL_ENABLED = "enabled"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"

        private const val TABLE_TRIGGERS = "memory_triggers"
        private const val COL_TRIGGER_ROW_ID = "_id"
        private const val COL_TRIGGER_MEMORY_ID = "memory_id"
        private const val COL_TRIGGER_TEXT = "trigger_text"

        @Volatile
        private var instance: LoreBookStore? = null

        fun getInstance(context: Context): LoreBookStore {
            return instance ?: synchronized(this) {
                instance ?: LoreBookStore(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_ENTRIES (" +
                "$COL_ID TEXT PRIMARY KEY, " +
                "$COL_LABEL TEXT NOT NULL DEFAULT '', " +
                "$COL_CONTENT TEXT NOT NULL DEFAULT '', " +
                "$COL_SOURCE_TEXT TEXT NOT NULL DEFAULT '', " +
                "$COL_ENABLED INTEGER NOT NULL DEFAULT 1, " +
                "$COL_CREATED_AT INTEGER NOT NULL DEFAULT 0, " +
                "$COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0" +
                ")"
        )

        db.execSQL(
            "CREATE TABLE $TABLE_TRIGGERS (" +
                "$COL_TRIGGER_ROW_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COL_TRIGGER_MEMORY_ID TEXT NOT NULL, " +
                "$COL_TRIGGER_TEXT TEXT NOT NULL, " +
                "FOREIGN KEY($COL_TRIGGER_MEMORY_ID) REFERENCES $TABLE_ENTRIES($COL_ID) ON DELETE CASCADE" +
                ")"
        )

        db.execSQL("CREATE INDEX idx_triggers_memory ON $TABLE_TRIGGERS($COL_TRIGGER_MEMORY_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Phase 1 ships at version 1. Later phases add migrations here instead of
        // dropping data; the schema is meant to grow, not be recreated.
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /**
     * Insert a new memory or update an existing one (matched by id). Triggers are
     * fully replaced. Returns the saved entry (with a generated id/timestamps if new).
     */
    fun saveEntry(entry: LoreBookEntry): LoreBookEntry {
        val now = System.currentTimeMillis()
        val isNew = entry.id.isBlank()

        val saved = entry.copy(
            id = if (isNew) UUID.randomUUID().toString() else entry.id,
            createdAt = if (isNew || entry.createdAt == 0L) now else entry.createdAt,
            updatedAt = now,
            triggers = ArrayList(entry.triggers.map { it.trim() }.filter { it.isNotEmpty() })
        )

        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(COL_ID, saved.id)
                put(COL_LABEL, saved.label)
                put(COL_CONTENT, saved.content)
                put(COL_SOURCE_TEXT, saved.sourceText)
                put(COL_ENABLED, if (saved.enabled) 1 else 0)
                put(COL_CREATED_AT, saved.createdAt)
                put(COL_UPDATED_AT, saved.updatedAt)
            }
            db.insertWithOnConflict(TABLE_ENTRIES, null, values, SQLiteDatabase.CONFLICT_REPLACE)

            db.delete(TABLE_TRIGGERS, "$COL_TRIGGER_MEMORY_ID = ?", arrayOf(saved.id))
            for (trigger in saved.triggers) {
                val triggerValues = ContentValues().apply {
                    put(COL_TRIGGER_MEMORY_ID, saved.id)
                    put(COL_TRIGGER_TEXT, trigger)
                }
                db.insert(TABLE_TRIGGERS, null, triggerValues)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return saved
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val values = ContentValues().apply {
            put(COL_ENABLED, if (enabled) 1 else 0)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_ENTRIES, values, "$COL_ID = ?", arrayOf(id))
    }

    fun deleteEntry(id: String) {
        // Triggers go away via ON DELETE CASCADE.
        writableDatabase.delete(TABLE_ENTRIES, "$COL_ID = ?", arrayOf(id))
    }

    fun getEntry(id: String): LoreBookEntry? {
        val all = queryEntries("$COL_ID = ?", arrayOf(id))
        return all.firstOrNull()
    }

    fun getAllEntries(): ArrayList<LoreBookEntry> {
        return queryEntries(null, null)
    }

    /**
     * Find every enabled memory whose any trigger appears (case insensitive,
     * substring) in [message]. This is the Phase 1 matching engine — deliberately
     * simple. Stronger trigger modes (exact phrase, all-required, word boundary)
     * arrive in Phase 3.
     */
    fun findMatches(message: String): ArrayList<LoreBookMatch> {
        val result = ArrayList<LoreBookMatch>()
        if (message.isBlank()) return result

        val haystack = message.lowercase()
        for (entry in queryEntries("$COL_ENABLED = 1", null)) {
            for (trigger in entry.triggers) {
                val needle = trigger.trim().lowercase()
                if (needle.isNotEmpty() && haystack.contains(needle)) {
                    result.add(LoreBookMatch(entry, trigger.trim()))
                    break // one match per memory is enough to inject it once
                }
            }
        }
        return result
    }

    private fun queryEntries(selection: String?, selectionArgs: Array<String>?): ArrayList<LoreBookEntry> {
        val entries = ArrayList<LoreBookEntry>()
        val db = readableDatabase

        val cursor = db.query(
            TABLE_ENTRIES,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_UPDATED_AT DESC"
        )

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val labelIdx = it.getColumnIndexOrThrow(COL_LABEL)
            val contentIdx = it.getColumnIndexOrThrow(COL_CONTENT)
            val sourceIdx = it.getColumnIndexOrThrow(COL_SOURCE_TEXT)
            val enabledIdx = it.getColumnIndexOrThrow(COL_ENABLED)
            val createdIdx = it.getColumnIndexOrThrow(COL_CREATED_AT)
            val updatedIdx = it.getColumnIndexOrThrow(COL_UPDATED_AT)

            while (it.moveToNext()) {
                val id = it.getString(idIdx)
                entries.add(
                    LoreBookEntry(
                        id = id,
                        label = it.getString(labelIdx) ?: "",
                        content = it.getString(contentIdx) ?: "",
                        sourceText = it.getString(sourceIdx) ?: "",
                        triggers = getTriggers(db, id),
                        enabled = it.getInt(enabledIdx) == 1,
                        createdAt = it.getLong(createdIdx),
                        updatedAt = it.getLong(updatedIdx)
                    )
                )
            }
        }

        return entries
    }

    private fun getTriggers(db: SQLiteDatabase, memoryId: String): ArrayList<String> {
        val triggers = ArrayList<String>()
        val cursor = db.query(
            TABLE_TRIGGERS,
            arrayOf(COL_TRIGGER_TEXT),
            "$COL_TRIGGER_MEMORY_ID = ?",
            arrayOf(memoryId),
            null,
            null,
            "$COL_TRIGGER_ROW_ID ASC"
        )
        cursor.use {
            val textIdx = it.getColumnIndexOrThrow(COL_TRIGGER_TEXT)
            while (it.moveToNext()) {
                triggers.add(it.getString(textIdx))
            }
        }
        return triggers
    }
}
