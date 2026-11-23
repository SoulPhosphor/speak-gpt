/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope

/**
 * The conversation index's own encrypted database (memory development plan
 * G.5, Storage design), separate from the stored chats, the chat search
 * index and the saved-memory database. It holds positions, fingerprints,
 * scope, a word index and vectors, never a readable copy of message text:
 *
 *  - `passages`: one row per passage, keyed by chat ID, message ID and order,
 *    with the title its search text was read under (a label or, later, a
 *    name recorded on the message; never an identity key);
 *  - `passage_vectors`: one row per search-text fingerprint, model and size;
 *  - `passage_keywords`: an FTS5 word index with no stored content and no
 *    word positions (`content=''`, `detail=none`), one row per passage row;
 *  - `index_state`: one row per chat.
 *
 * Passages, keywords and index state are derived and can be rebuilt from the
 * chats; vectors are kept through a rebuild.
 */
class ConversationIndexStore private constructor(
    context: Context,
    password: ByteArray,
    databaseName: String
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    password,
    null,
    DATABASE_VERSION,
    0,
    CorruptionHandler,
    null,
    true
), ConversationIndexStorage {

    /** Test hook: called inside a message or restamp write after it has changed rows, before the commit check. */
    @Volatile internal var afterOldRowsRemoved: (() -> Unit)? = null

    override fun onCreate(db: SQLiteDatabase) {
        createDerivedStores(db)
        createVectorStore(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Derived stores are rebuilt from the chats; vectors are kept (G.5).
        dropDerivedStores(db)
        createDerivedStores(db)
        createVectorStore(db)
    }

    private fun createVectorStore(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS passage_vectors (" +
                "search_fingerprint TEXT NOT NULL," +
                "vector_model TEXT NOT NULL," +
                "vector_dimensions INTEGER NOT NULL," +
                "state TEXT NOT NULL CHECK(state IN ('$STATE_WAITING','made','failed'))," +
                "vector BLOB," +
                "PRIMARY KEY(search_fingerprint, vector_model, vector_dimensions))"
        )
    }

    private fun createDerivedStores(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE passages (" +
                "row_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "source_kind TEXT NOT NULL CHECK(source_kind IN ('$SOURCE_CHAT_MESSAGE'))," +
                "chat_id TEXT NOT NULL," +
                "message_id TEXT NOT NULL," +
                "passage_order INTEGER NOT NULL," +
                "text_start INTEGER NOT NULL," +
                "text_end INTEGER NOT NULL," +
                "exchange_id TEXT," +
                "previous_order INTEGER," +
                "next_order INTEGER," +
                "speaker TEXT NOT NULL CHECK(speaker IN ('user','ai'))," +
                "speaker_title TEXT NOT NULL," +
                "time_millis INTEGER," +
                "chat_position INTEGER NOT NULL," +
                "text_fingerprint TEXT NOT NULL," +
                "search_fingerprint TEXT NOT NULL," +
                "scope_known INTEGER NOT NULL CHECK(scope_known IN (0,1))," +
                "scope_companion_persona TEXT," +
                "scope_companion TEXT," +
                "scope_world TEXT," +
                "scope_campaign TEXT," +
                "scope_character TEXT," +
                "scope_user_persona TEXT," +
                "scope_project TEXT," +
                "cutter_version INTEGER NOT NULL," +
                "search_settings TEXT NOT NULL," +
                "vector_model TEXT NOT NULL," +
                "vector_dimensions INTEGER NOT NULL," +
                "UNIQUE(chat_id, message_id, passage_order))"
        )
        db.execSQL("CREATE INDEX idx_passages_vector ON passages(search_fingerprint, vector_model, vector_dimensions)")
        db.execSQL(
            "CREATE VIRTUAL TABLE passage_keywords USING fts5(" +
                "words, content='', contentless_delete=1, detail=none," +
                // An apostrophe is part of a word, as in KeywordText's terms, so every
                // stored term is one token and never needs a phrase query.
                "tokenize=\"unicode61 remove_diacritics 0 tokenchars \")"
        )
        db.execSQL(
            "CREATE TRIGGER passages_keywords_ad AFTER DELETE ON passages BEGIN " +
                "DELETE FROM passage_keywords WHERE rowid = old.row_id; END"
        )
        db.execSQL(
            "CREATE TABLE index_state (" +
                "chat_id TEXT PRIMARY KEY," +
                "described_version TEXT," +
                "needs_refresh INTEGER NOT NULL CHECK(needs_refresh IN (0,1))," +
                "waiting_vectors INTEGER NOT NULL," +
                "fully_indexed INTEGER NOT NULL CHECK(fully_indexed IN (0,1)))"
        )
    }

    private fun dropDerivedStores(db: SQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS passages_keywords_ad")
        db.execSQL("DROP TABLE IF EXISTS passage_keywords")
        db.execSQL("DROP TABLE IF EXISTS passages")
        db.execSQL("DROP TABLE IF EXISTS index_state")
    }

    override fun indexedMessages(chatId: String): Map<String, MessageStamp> =
        readStamps(readableDatabase, chatId, null)

    override fun indexState(chatId: String): ChatIndexState? =
        readableDatabase.rawQuery(
            "SELECT described_version, needs_refresh, waiting_vectors, fully_indexed FROM index_state WHERE chat_id = ?",
            arrayOf(chatId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ChatIndexState(
                describedVersion = cursor.getStringOrNull(0),
                needsRefresh = cursor.getInt(1) == 1,
                waitingVectors = cursor.getInt(2),
                fullyIndexed = cursor.getInt(3) == 1
            )
        }

    override fun beginUpdate(chatId: String) = transaction { db ->
        db.execSQL(
            "INSERT INTO index_state(chat_id, described_version, needs_refresh, waiting_vectors, fully_indexed) " +
                "VALUES(?, NULL, 1, 0, 0) ON CONFLICT(chat_id) DO UPDATE SET needs_refresh = 1, fully_indexed = 0",
            arrayOf(chatId)
        )
    }

    override fun writeMessage(
        chatId: String,
        messageId: String,
        expected: MessageStamp?,
        replacement: MessageEntries?,
        chatUnchanged: () -> Boolean
    ): IndexWrite = writeTransaction { db ->
        require(replacement == null || replacement.messageId == messageId)
        if (!chatUnchanged() || readStamps(db, chatId, messageId)[messageId] != expected) return@writeTransaction IndexWrite.STALE
        val oldVectors = vectorKeys(db, chatId, messageId)
        db.delete("passages", "chat_id = ? AND message_id = ?", arrayOf(chatId, messageId))
        afterOldRowsRemoved?.invoke()
        replacement?.let { insertPassages(db, chatId, it) }
        removeUnusedVectors(db, oldVectors)
        publishIfUnchanged(chatUnchanged)
    }

    override fun restampMessage(
        chatId: String,
        messageId: String,
        expected: MessageStamp,
        stamp: MessageStamp,
        chatUnchanged: () -> Boolean
    ): IndexWrite = writeTransaction { db ->
        require(expected.sameSearchTexts(stamp))
        if (!chatUnchanged() || readStamps(db, chatId, messageId)[messageId] != expected) return@writeTransaction IndexWrite.STALE
        db.update("passages", ContentValues().apply { putPlacement(stamp) },
            "chat_id = ? AND message_id = ?", arrayOf(chatId, messageId))
        afterOldRowsRemoved?.invoke()
        publishIfUnchanged(chatUnchanged)
    }

    override fun finishUpdate(
        chatId: String,
        chatVersion: String,
        expected: Map<String, MessageStamp>,
        chatUnchanged: () -> Boolean
    ): IndexWrite = writeTransaction { db ->
        if (!chatUnchanged() || readStamps(db, chatId, null) != expected) return@writeTransaction IndexWrite.STALE
        val waiting = db.rawQuery(
            "SELECT COUNT(*) FROM passages p JOIN passage_vectors v ON v.search_fingerprint = p.search_fingerprint " +
                "AND v.vector_model = p.vector_model AND v.vector_dimensions = p.vector_dimensions " +
                "WHERE p.chat_id = ? AND v.state = '$STATE_WAITING'",
            arrayOf(chatId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        db.execSQL(
            "INSERT INTO index_state(chat_id, described_version, needs_refresh, waiting_vectors, fully_indexed) " +
                "VALUES(?, ?, 0, ?, ?) ON CONFLICT(chat_id) DO UPDATE SET described_version = excluded.described_version, " +
                "needs_refresh = 0, waiting_vectors = excluded.waiting_vectors, fully_indexed = excluded.fully_indexed",
            arrayOf<Any>(chatId, chatVersion, waiting, if (waiting == 0) 1 else 0)
        )
        publishIfUnchanged(chatUnchanged)
    }

    /** Thrown inside a transaction to roll it back when the chat changed while it ran. */
    private class ChatChanged : RuntimeException()

    /** The chat store is not locked by this transaction, so its version is checked again just before commit. */
    private fun publishIfUnchanged(chatUnchanged: () -> Boolean): IndexWrite {
        if (!chatUnchanged()) throw ChatChanged()
        return IndexWrite.WRITTEN
    }

    /** For tests and later steps: the state of one vector row, or null when there is none. */
    fun vectorState(searchFingerprint: String, vectorModel: String, vectorDimensions: Int): String? =
        readableDatabase.rawQuery(
            "SELECT state FROM passage_vectors WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?",
            arrayOf(searchFingerprint, vectorModel, vectorDimensions.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    /** For tests and later steps: the passage row IDs of [chatId] whose words include every one of [terms]. */
    fun passagesWithWords(chatId: String, terms: List<String>): List<Long> {
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" AND ") { "\"" + it.replace("\"", "\"\"") + "\"" }
        return readableDatabase.rawQuery(
            "SELECT p.row_id FROM passage_keywords JOIN passages p ON p.row_id = passage_keywords.rowid " +
                "WHERE passage_keywords MATCH ? AND p.chat_id = ? ORDER BY p.row_id",
            arrayOf(match, chatId)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
    }

    private fun insertPassages(db: SQLiteDatabase, chatId: String, entries: MessageEntries) {
        val stamp = entries.stamp
        val last = entries.passages.lastIndex
        for (passage in entries.passages) {
            val rowId = db.insertOrThrow("passages", null, ContentValues().apply {
                put("source_kind", SOURCE_CHAT_MESSAGE)
                put("chat_id", chatId)
                put("message_id", entries.messageId)
                put("passage_order", passage.order)
                put("text_start", passage.start)
                put("text_end", passage.end)
                if (passage.order > 0) put("previous_order", passage.order - 1) else putNull("previous_order")
                if (passage.order < last) put("next_order", passage.order + 1) else putNull("next_order")
                put("speaker", stamp.speaker.stored)
                put("speaker_title", stamp.speakerTitle)
                put("text_fingerprint", stamp.textFingerprint)
                put("search_fingerprint", passage.searchFingerprint)
                put("cutter_version", stamp.cutterVersion)
                put("search_settings", stamp.searchSettings)
                put("vector_model", stamp.vectorModel)
                put("vector_dimensions", stamp.vectorDimensions)
                putPlacement(stamp)
            })
            db.execSQL("INSERT INTO passage_keywords(rowid, words) VALUES(?, ?)", arrayOf<Any>(rowId, passage.keywords))
            db.execSQL(
                "INSERT OR IGNORE INTO passage_vectors(search_fingerprint, vector_model, vector_dimensions, state, vector) " +
                    "VALUES(?, ?, ?, '$STATE_WAITING', NULL)",
                arrayOf<Any>(passage.searchFingerprint, stamp.vectorModel, stamp.vectorDimensions)
            )
        }
    }

    private fun ContentValues.putPlacement(stamp: MessageStamp) {
        put("exchange_id", stamp.exchangeId)
        put("chat_position", stamp.chatPosition)
        if (stamp.timeMillis != null) put("time_millis", stamp.timeMillis) else putNull("time_millis")
        val scope = stamp.scope
        put("scope_known", if (scope != null) 1 else 0)
        put("scope_companion_persona", scope?.companionPersonaId)
        put("scope_companion", scope?.companionId)
        put("scope_world", scope?.worldId)
        put("scope_campaign", scope?.campaignId)
        put("scope_character", scope?.roleplayCharacterId)
        put("scope_user_persona", scope?.userPersonaId)
        put("scope_project", scope?.projectId)
    }

    private data class VectorKey(val fingerprint: String, val model: String, val dimensions: Int)

    private fun vectorKeys(db: SQLiteDatabase, chatId: String, messageId: String): Set<VectorKey> =
        db.rawQuery(
            "SELECT search_fingerprint, vector_model, vector_dimensions FROM passages WHERE chat_id = ? AND message_id = ?",
            arrayOf(chatId, messageId)
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(VectorKey(cursor.getString(0), cursor.getString(1), cursor.getInt(2))) }
        }

    private fun removeUnusedVectors(db: SQLiteDatabase, keys: Set<VectorKey>) {
        for (key in keys) {
            db.execSQL(
                "DELETE FROM passage_vectors WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ? " +
                    "AND NOT EXISTS (SELECT 1 FROM passages WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?)",
                arrayOf<Any>(key.fingerprint, key.model, key.dimensions, key.fingerprint, key.model, key.dimensions)
            )
        }
    }

    /** The stamp of each indexed message of [chatId] (or of [messageId] only), read from its first passage. */
    private fun readStamps(db: SQLiteDatabase, chatId: String, messageId: String?): Map<String, MessageStamp> {
        val where = if (messageId == null) "chat_id = ?" else "chat_id = ? AND message_id = ?"
        val args = if (messageId == null) arrayOf(chatId) else arrayOf(chatId, messageId)
        return db.rawQuery(
            "SELECT message_id, text_fingerprint, speaker, exchange_id, chat_position, time_millis, scope_known, " +
                "scope_companion_persona, scope_companion, scope_world, scope_campaign, scope_character, " +
                "scope_user_persona, scope_project, cutter_version, search_settings, vector_model, vector_dimensions, " +
                "speaker_title " +
                "FROM passages WHERE $where AND passage_order = 0",
            args
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), MessageStamp(
                        textFingerprint = cursor.getString(1),
                        speaker = Speaker.fromStored(cursor.getString(2)),
                        speakerTitle = cursor.getString(18),
                        exchangeId = cursor.getStringOrNull(3),
                        chatPosition = cursor.getInt(4),
                        timeMillis = if (cursor.isNull(5)) null else cursor.getLong(5),
                        scope = if (cursor.getInt(6) == 0) null else ScanMessageScope(
                            companionPersonaId = cursor.getStringOrNull(7),
                            companionId = cursor.getStringOrNull(8),
                            worldId = cursor.getStringOrNull(9),
                            campaignId = cursor.getStringOrNull(10),
                            roleplayCharacterId = cursor.getStringOrNull(11),
                            userPersonaId = cursor.getStringOrNull(12),
                            projectId = cursor.getStringOrNull(13)
                        ),
                        cutterVersion = cursor.getInt(14),
                        searchSettings = cursor.getString(15),
                        vectorModel = cursor.getString(16),
                        vectorDimensions = cursor.getInt(17)
                    ))
                }
            }
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    /** A write transaction; when the chat changed before commit, it is rolled back and reported stale. */
    private inline fun writeTransaction(block: (SQLiteDatabase) -> IndexWrite): IndexWrite =
        try { transaction(block) } catch (_: ChatChanged) { IndexWrite.STALE }

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DATABASE_NAME = "conversation_index.db"
        private const val DATABASE_VERSION = 1
        private const val SOURCE_CHAT_MESSAGE = "chat_message"
        const val STATE_WAITING = "waiting"

        @Volatile private var instance: ConversationIndexStore? = null
        @Volatile private var libraryLoaded = false

        private fun loadLibrary() {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
        }

        /** The app's index. Throws when the database key cannot be read; the chat is never affected. */
        fun get(context: Context): ConversationIndexStore {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: run {
                    loadLibrary()
                    val exists = app.getDatabasePath(DATABASE_NAME).exists()
                    val key = DatabaseKeys.getOrCreate(app, DatabaseKeys.KEY_CONVERSATION_INDEX, exists)
                        ?: throw IllegalStateException("Conversation index key unavailable")
                    ConversationIndexStore(app, key, DATABASE_NAME).also { instance = it }
                }
            }
        }

        fun openForTest(context: Context, databaseName: String, password: ByteArray): ConversationIndexStore {
            synchronized(this) { loadLibrary() }
            return ConversationIndexStore(context.applicationContext, password, databaseName)
        }
    }

    private object CorruptionHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase, exception: android.database.sqlite.SQLiteException?) {
            try { dbObj.close() } catch (_: Exception) { }
        }
    }
}
