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
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.CorruptionErrorHandlers
import org.teslasoft.assistant.preferences.backup.DatabaseDegradedException
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
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
 * Encrypted at rest with SQLCipher (owner-requested, matching the companion
 * memory store). Legacy plaintext databases are encrypted in place on first
 * open by LoreBookEncryption, which also supplies the password — an EMPTY
 * password means the migration couldn't run and the store keeps operating on
 * the plaintext file until the next attempt; lorebooks never stop working over
 * encryption.
 *
 * Three tables:
 *  - lorebooks: one row per lorebook (a named collection of memories)
 *  - memory_entries: one row per memory, scoped to a lorebook via lorebook_id
 *  - memory_triggers: trigger words/phrases, many per memory
 *
 * A chat injects only from the single lorebook selected as active for that chat.
 */
class LoreBookStore private constructor(context: Context, password: ByteArray) :
    SQLiteOpenHelper(
        context.applicationContext, DATABASE_NAME, password, null, DATABASE_VERSION, 0,
        // Explicit corruption handler (Build Phase 3): the library DEFAULT
        // deletes a corrupt database file; ours flags the store degraded and
        // preserves the file for quarantine + repair. Never pass null here.
        CorruptionErrorHandlers.Cipher(context, BackupType.LOREBOOK),
        null, false
    ) {

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

        // A multi-book/multi-entry batched query passes ids as bound
        // parameters; chunk them so the IN(...) list stays well under
        // SQLite's ~999 bound-variable ceiling.
        private const val ID_CHUNK = 400

        @Volatile
        private var instance: LoreBookStore? = null

        fun getInstance(context: Context): LoreBookStore {
            // Degraded gate (Database Health Build Phase 3, §15.2a): a store
            // with CONFIRMED damage is genuinely off — reads and writes —
            // until a repair/restore succeeds. Checked before the cached
            // instance too. ChatActivity's lorebook call sites already
            // try/catch-guard this path, so a degraded store means "no lore
            // this turn", never a crash.
            if (DatabaseHealthState.isDegraded(context.applicationContext, BackupType.LOREBOOK)) {
                throw DatabaseDegradedException(BackupType.LOREBOOK)
            }
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    // Runs the one-time plaintext -> SQLCipher migration when
                    // needed and loads the native library; must happen before
                    // the helper first touches the file.
                    val password = LoreBookEncryption.obtainPassword(appContext, DATABASE_NAME)
                    LoreBookStore(appContext, password).also { instance = it }
                }
            }
        }

        /** True once the lorebook database file exists — lets callers (e.g.
         *  the Companion & Roleplay Backup) ask about lorebooks without
         *  creating the store as a side effect. */
        fun isProvisioned(context: Context): Boolean =
            context.getDatabasePath(DATABASE_NAME).exists()

        /**
         * Close and forget the cached helper so the database FILE can be
         * replaced underneath (repair swap / restore / fresh start —
         * DatabaseRepairManager only). The next [getInstance] reopens against
         * whatever file then exists.
         */
        fun invalidateInstance() {
            synchronized(this) {
                try { instance?.close() } catch (_: Exception) { }
                instance = null
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

    /**
     * Returns null when the database is healthy, otherwise a short description
     * of what `PRAGMA integrity_check` reported (or the exception that prevented
     * the check). Mirrors the memory store's `integrityCheck()`. DETECTION ONLY
     * — the "Check Database Integrity" button (Build Phase 2) reports the
     * result; degraded flags, dialogs and repair are Build Phase 3.
     */
    fun integrityCheck(): String? {
        return try {
            readableDatabase.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    if (result.equals("ok", ignoreCase = true)) null else result
                } else "integrity_check returned no rows"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
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
     * Find every enabled memory across [lorebookIds] with a trigger that fires
     * on [message], searched in that order (the caller's book-priority order —
     * core book first) and by-book recency within each book, same as calling
     * the single-book search once per book and concatenating. Matching is
     * delegated to [LoreBookTriggerMatcher]: whole-word, case-insensitive, with
     * light suffix folding (dragon ↔ dragons), and triggers wrapped in double
     * quotes demand that exact text instead.
     *
     * One batched entries query plus one batched triggers query for ALL active
     * books, rather than a query per book and a trigger query per entry
     * (counterplan Step 1.6 — the N+1 pattern on every lore-enabled turn).
     */
    fun findMatches(message: String, lorebookIds: List<String>): ArrayList<LoreBookMatch> {
        val result = ArrayList<LoreBookMatch>()
        if (message.isBlank() || lorebookIds.isEmpty()) return result

        val byBook = enabledEntriesByBook(lorebookIds.distinct())
        for (bookId in lorebookIds) {
            for (entry in byBook[bookId].orEmpty()) {
                for (trigger in entry.triggers) {
                    if (LoreBookTriggerMatcher.matches(message, trigger)) {
                        result.add(LoreBookMatch(entry, trigger.trim()))
                        break // one match per memory is enough to inject it once
                    }
                }
            }
        }
        return result
    }

    /**
     * Enabled entries across [lorebookIds], triggers attached, grouped by book.
     * Each book's list keeps the same order [queryEntries] already produced
     * (updated-at descending) — one entries query and one triggers query,
     * chunked to respect the bound-variable limit, instead of one query per
     * book plus one per entry.
     */
    private fun enabledEntriesByBook(lorebookIds: List<String>): Map<String, List<LoreBookEntry>> {
        if (lorebookIds.isEmpty()) return emptyMap()
        val entries = ArrayList<LoreBookEntry>()
        var i = 0
        while (i < lorebookIds.size) {
            val chunk = lorebookIds.subList(i, minOf(i + ID_CHUNK, lorebookIds.size))
            val placeholders = chunk.joinToString(",") { "?" }
            entries.addAll(
                queryEntryRows(
                    "$COL_LOREBOOK_ID IN ($placeholders) AND $COL_ENABLED = 1",
                    chunk.toTypedArray()
                )
            )
            i += ID_CHUNK
        }
        val triggersById = fetchTriggersForEntries(entries.map { it.id })
        for (entry in entries) entry.triggers = triggersById[entry.id] ?: ArrayList()
        return entries.groupBy { it.lorebookId }
    }

    /**
     * All memories matching [selection], triggers attached — one entries query
     * plus one batched triggers query for every returned row, instead of a
     * trigger query per entry (counterplan Step 1.6).
     */
    private fun queryEntries(selection: String?, selectionArgs: Array<String>?): ArrayList<LoreBookEntry> {
        val entries = queryEntryRows(selection, selectionArgs)
        val triggersById = fetchTriggersForEntries(entries.map { it.id })
        for (entry in entries) entry.triggers = triggersById[entry.id] ?: ArrayList()
        return entries
    }

    /** Entry rows matching [selection], triggers left empty — callers attach
     *  triggers themselves via [fetchTriggersForEntries] so a multi-book fetch
     *  can batch that lookup once across every returned row instead of once
     *  per query. */
    private fun queryEntryRows(selection: String?, selectionArgs: Array<String>?): ArrayList<LoreBookEntry> {
        val entries = ArrayList<LoreBookEntry>()
        readableDatabase.query(
            TABLE_ENTRIES, null, selection, selectionArgs, null, null, "$COL_UPDATED_AT DESC"
        ).use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val bookIdx = it.getColumnIndexOrThrow(COL_LOREBOOK_ID)
            val labelIdx = it.getColumnIndexOrThrow(COL_LABEL)
            val contentIdx = it.getColumnIndexOrThrow(COL_CONTENT)
            val sourceIdx = it.getColumnIndexOrThrow(COL_SOURCE_TEXT)
            val enabledIdx = it.getColumnIndexOrThrow(COL_ENABLED)
            val createdIdx = it.getColumnIndexOrThrow(COL_CREATED_AT)
            val updatedIdx = it.getColumnIndexOrThrow(COL_UPDATED_AT)

            while (it.moveToNext()) {
                entries.add(
                    LoreBookEntry(
                        id = it.getString(idIdx),
                        lorebookId = it.getString(bookIdx) ?: "",
                        label = it.getString(labelIdx) ?: "",
                        content = it.getString(contentIdx) ?: "",
                        sourceText = it.getString(sourceIdx) ?: "",
                        triggers = ArrayList(),
                        enabled = it.getInt(enabledIdx) == 1,
                        createdAt = it.getLong(createdIdx),
                        updatedAt = it.getLong(updatedIdx)
                    )
                )
            }
        }
        return entries
    }

    /** Triggers for [entryIds], keyed by entry id and ordered as they were
     *  entered — one query (chunked for large id lists) instead of one per
     *  entry. */
    private fun fetchTriggersForEntries(entryIds: List<String>): Map<String, ArrayList<String>> {
        val out = HashMap<String, ArrayList<String>>()
        if (entryIds.isEmpty()) return out
        val db = readableDatabase
        var i = 0
        while (i < entryIds.size) {
            val chunk = entryIds.subList(i, minOf(i + ID_CHUNK, entryIds.size))
            val placeholders = chunk.joinToString(",") { "?" }
            db.query(
                TABLE_TRIGGERS,
                arrayOf(COL_TRIGGER_MEMORY_ID, COL_TRIGGER_TEXT),
                "$COL_TRIGGER_MEMORY_ID IN ($placeholders)",
                chunk.toTypedArray(),
                null, null, "$COL_TRIGGER_ROW_ID ASC"
            ).use {
                val memIdx = it.getColumnIndexOrThrow(COL_TRIGGER_MEMORY_ID)
                val textIdx = it.getColumnIndexOrThrow(COL_TRIGGER_TEXT)
                while (it.moveToNext()) {
                    out.getOrPut(it.getString(memIdx)) { ArrayList() }.add(it.getString(textIdx))
                }
            }
            i += ID_CHUNK
        }
        return out
    }
}

private const val DEFAULT_BOOK_NAME = "Default"
