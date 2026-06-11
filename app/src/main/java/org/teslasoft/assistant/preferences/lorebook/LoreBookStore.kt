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
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.dto.LoreBookEntry
import java.util.UUID

/**
 * SQLite-backed storage for lorebooks and their memories (Phase 1 of the memory
 * system).
 *
 * The rest of the app stores its data in SharedPreferences, but the memory
 * system is intentionally built on a real database from the start: later phases
 * (full-text search, conversation summaries, vector embeddings, sync metadata)
 * all need relational rows and queries that key-value preferences can't provide.
 *
 * Three tables:
 *  - lorebooks: one row per lorebook (a named collection of memories)
 *  - memory_entries: one row per memory, scoped to a lorebook via lorebook_id
 *  - memory_triggers: trigger words/phrases, many per memory
 *
 * A chat injects only from the single lorebook selected as active for that chat.
 */
class LoreBookStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "lorebook.db"

        // v1: single flat memory pool.
        // v2: introduced lorebooks; memories scoped by lorebook_id.
        // v3: lorebooks gained a single "tag" (type) column for filtering.
        private const val DATABASE_VERSION = 3

        private const val TABLE_BOOKS = "lorebooks"
        private const val COL_BOOK_ID = "id"
        private const val COL_BOOK_NAME = "name"
        private const val COL_BOOK_DESCRIPTION = "description"
        private const val COL_BOOK_TAG = "tag"
        private const val COL_BOOK_CREATED_AT = "created_at"
        private const val COL_BOOK_UPDATED_AT = "updated_at"

        private const val TABLE_ENTRIES = "memory_entries"
        private const val COL_ID = "id"
        private const val COL_LOREBOOK_ID = "lorebook_id"
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

        // Injection safety budget: if many memories trigger at once, only this
        // many entries / characters are injected per request (core book first),
        // so a trigger-storm can't flood the model's context or the user's bill.
        const val MAX_INJECTED_ENTRIES = 20
        const val MAX_INJECTED_CHARS = 6000

        @Volatile
        private var instance: LoreBookStore? = null

        fun getInstance(context: Context): LoreBookStore {
            return instance ?: synchronized(this) {
                instance ?: LoreBookStore(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBooksTable(db)
        createEntriesTable(db)
        createTriggersTable(db)

        // Fresh installs start with one empty lorebook so the UI and the active-book
        // selector always have something to point at.
        insertBook(db, UUID.randomUUID().toString(), DEFAULT_BOOK_NAME, "")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: add the lorebooks table and a lorebook_id on every memory, then
        // move any pre-existing (single-pool) memories into a "Default" lorebook so
        // nothing the user already created disappears.
        if (oldVersion < 2) {
            createBooksTable(db)
            db.execSQL("ALTER TABLE $TABLE_ENTRIES ADD COLUMN $COL_LOREBOOK_ID TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_book ON $TABLE_ENTRIES($COL_LOREBOOK_ID)")

            val defaultBookId = UUID.randomUUID().toString()
            insertBook(db, defaultBookId, DEFAULT_BOOK_NAME, "")

            val values = ContentValues().apply { put(COL_LOREBOOK_ID, defaultBookId) }
            db.update(TABLE_ENTRIES, values, "$COL_LOREBOOK_ID = ? OR $COL_LOREBOOK_ID IS NULL", arrayOf(""))
        }

        // v2 -> v3: lorebooks gain a single tag for type filtering.
        if (oldVersion in 2 until 3) {
            db.execSQL("ALTER TABLE $TABLE_BOOKS ADD COLUMN $COL_BOOK_TAG TEXT NOT NULL DEFAULT ''")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun createBooksTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_BOOKS (" +
                "$COL_BOOK_ID TEXT PRIMARY KEY, " +
                "$COL_BOOK_NAME TEXT NOT NULL DEFAULT '', " +
                "$COL_BOOK_DESCRIPTION TEXT NOT NULL DEFAULT '', " +
                "$COL_BOOK_TAG TEXT NOT NULL DEFAULT '', " +
                "$COL_BOOK_CREATED_AT INTEGER NOT NULL DEFAULT 0, " +
                "$COL_BOOK_UPDATED_AT INTEGER NOT NULL DEFAULT 0" +
                ")"
        )
    }

    private fun createEntriesTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_ENTRIES (" +
                "$COL_ID TEXT PRIMARY KEY, " +
                "$COL_LOREBOOK_ID TEXT NOT NULL DEFAULT '', " +
                "$COL_LABEL TEXT NOT NULL DEFAULT '', " +
                "$COL_CONTENT TEXT NOT NULL DEFAULT '', " +
                "$COL_SOURCE_TEXT TEXT NOT NULL DEFAULT '', " +
                "$COL_ENABLED INTEGER NOT NULL DEFAULT 1, " +
                "$COL_CREATED_AT INTEGER NOT NULL DEFAULT 0, " +
                "$COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0" +
                ")"
        )
        db.execSQL("CREATE INDEX idx_entries_book ON $TABLE_ENTRIES($COL_LOREBOOK_ID)")
    }

    private fun createTriggersTable(db: SQLiteDatabase) {
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

    private fun insertBook(db: SQLiteDatabase, id: String, name: String, description: String) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_BOOK_ID, id)
            put(COL_BOOK_NAME, name)
            put(COL_BOOK_DESCRIPTION, description)
            put(COL_BOOK_CREATED_AT, now)
            put(COL_BOOK_UPDATED_AT, now)
        }
        db.insertWithOnConflict(TABLE_BOOKS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /* ---------------------------------------------------------------------- */
    /* Lorebooks                                                              */
    /* ---------------------------------------------------------------------- */

    fun saveBook(book: LoreBook): LoreBook {
        val now = System.currentTimeMillis()
        val isNew = book.id.isBlank()
        val saved = book.copy(
            id = if (isNew) UUID.randomUUID().toString() else book.id,
            createdAt = if (isNew || book.createdAt == 0L) now else book.createdAt,
            updatedAt = now
        )

        val values = ContentValues().apply {
            put(COL_BOOK_ID, saved.id)
            put(COL_BOOK_NAME, saved.name)
            put(COL_BOOK_DESCRIPTION, saved.description)
            put(COL_BOOK_TAG, saved.tag.trim())
            put(COL_BOOK_CREATED_AT, saved.createdAt)
            put(COL_BOOK_UPDATED_AT, saved.updatedAt)
        }
        writableDatabase.insertWithOnConflict(TABLE_BOOKS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return saved
    }

    fun deleteBook(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Remove the book's memories (and their triggers via cascade) first, then
            // the book row itself.
            for (entry in getEntries(id)) {
                db.delete(TABLE_ENTRIES, "$COL_ID = ?", arrayOf(entry.id))
            }
            db.delete(TABLE_BOOKS, "$COL_BOOK_ID = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getBook(id: String): LoreBook? {
        val cursor = readableDatabase.query(
            TABLE_BOOKS, null, "$COL_BOOK_ID = ?", arrayOf(id), null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) return readBook(it)
        }
        return null
    }

    fun getAllBooks(): ArrayList<LoreBook> {
        val books = ArrayList<LoreBook>()
        val cursor = readableDatabase.query(
            TABLE_BOOKS, null, null, null, null, null, "$COL_BOOK_CREATED_AT ASC"
        )
        cursor.use {
            while (it.moveToNext()) books.add(readBook(it))
        }
        return books
    }

    /** Distinct non-empty tags across all books, for the type filter. */
    fun getAllTags(): ArrayList<String> {
        val tags = ArrayList<String>()
        val cursor = readableDatabase.query(
            true, TABLE_BOOKS, arrayOf(COL_BOOK_TAG), "$COL_BOOK_TAG != ''", null, null, null, "$COL_BOOK_TAG ASC", null
        )
        cursor.use {
            while (it.moveToNext()) {
                val t = it.getString(0)
                if (!t.isNullOrBlank()) tags.add(t)
            }
        }
        return tags
    }

    /** Number of enabled/total memories in a book, for list subtitles. */
    fun getEntryCount(lorebookId: String): Int {
        val cursor = readableDatabase.query(
            TABLE_ENTRIES, arrayOf(COL_ID), "$COL_LOREBOOK_ID = ?", arrayOf(lorebookId), null, null, null
        )
        cursor.use { return it.count }
    }

    private fun readBook(c: android.database.Cursor): LoreBook {
        return LoreBook(
            id = c.getString(c.getColumnIndexOrThrow(COL_BOOK_ID)),
            name = c.getString(c.getColumnIndexOrThrow(COL_BOOK_NAME)) ?: "",
            description = c.getString(c.getColumnIndexOrThrow(COL_BOOK_DESCRIPTION)) ?: "",
            tag = c.getString(c.getColumnIndexOrThrow(COL_BOOK_TAG)) ?: "",
            createdAt = c.getLong(c.getColumnIndexOrThrow(COL_BOOK_CREATED_AT)),
            updatedAt = c.getLong(c.getColumnIndexOrThrow(COL_BOOK_UPDATED_AT))
        )
    }

    /* ---------------------------------------------------------------------- */
    /* Memories                                                               */
    /* ---------------------------------------------------------------------- */

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
                put(COL_LOREBOOK_ID, saved.lorebookId)
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
        return queryEntries("$COL_ID = ?", arrayOf(id)).firstOrNull()
    }

    /** All memories in a single lorebook. */
    fun getEntries(lorebookId: String): ArrayList<LoreBookEntry> {
        return queryEntries("$COL_LOREBOOK_ID = ?", arrayOf(lorebookId))
    }

    /**
     * Find every enabled memory in [lorebookId] with a trigger that fires on
     * [message]. Matching is delegated to [LoreBookTriggerMatcher]: whole-word,
     * case-insensitive, with light suffix folding (dragon ↔ dragons), and
     * triggers wrapped in double quotes demand that exact text instead.
     */
    fun findMatches(message: String, lorebookId: String): ArrayList<LoreBookMatch> {
        val result = ArrayList<LoreBookMatch>()
        if (message.isBlank() || lorebookId.isBlank()) return result

        for (entry in queryEntries("$COL_LOREBOOK_ID = ? AND $COL_ENABLED = 1", arrayOf(lorebookId))) {
            for (trigger in entry.triggers) {
                if (LoreBookTriggerMatcher.matches(message, trigger)) {
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
            val bookIdx = it.getColumnIndexOrThrow(COL_LOREBOOK_ID)
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
                        lorebookId = it.getString(bookIdx) ?: "",
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

private const val DEFAULT_BOOK_NAME = "Default"
