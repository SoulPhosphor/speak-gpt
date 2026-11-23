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
), ConversationIndexStorage, PassageVectorStorage {

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
                "state TEXT NOT NULL CHECK(state IN ('$STATE_WAITING','$STATE_MADE','$STATE_FAILED'))," +
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
                "tokenize=\"unicode61 remove_diacritics 0 tokenchars ''''\")"
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
        removeUnusedVectors(db, oldVectors, replacement?.stamp)
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

    private fun vectorKeys(db: SQLiteDatabase, chatId: String, messageId: String): Set<VectorKey> =
        db.rawQuery(
            "SELECT search_fingerprint, vector_model, vector_dimensions FROM passages WHERE chat_id = ? AND message_id = ?",
            arrayOf(chatId, messageId)
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(VectorKey(cursor.getString(0), cursor.getString(1), cursor.getInt(2))) }
        }

    /**
     * Removes each of [keys] that no passage uses any more. When a message is
     * re-cut for another model ([replacement] names the new one), a vector
     * already made by the old model is kept until the new model's index is
     * complete ([removeObsoleteVectors], G.5a rule 3).
     */
    private fun removeUnusedVectors(db: SQLiteDatabase, keys: Set<VectorKey>, replacement: MessageStamp?) {
        for (key in keys) {
            val otherModel = replacement != null &&
                (key.vectorModel != replacement.vectorModel || key.vectorDimensions != replacement.vectorDimensions)
            db.execSQL(
                "DELETE FROM passage_vectors WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ? " +
                    (if (otherModel) "AND state <> '$STATE_MADE' " else "") +
                    "AND NOT EXISTS (SELECT 1 FROM passages WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?)",
                arrayOf<Any>(
                    key.searchFingerprint, key.vectorModel, key.vectorDimensions,
                    key.searchFingerprint, key.vectorModel, key.vectorDimensions
                )
            )
        }
    }

    override fun waitingVectors(
        vectorModel: String,
        vectorDimensions: Int,
        afterFingerprint: String?,
        limit: Int
    ): List<WaitingVector> {
        require(limit >= 1)
        val db = readableDatabase
        val fingerprints = db.rawQuery(
            "SELECT v.search_fingerprint FROM passage_vectors v WHERE v.vector_model = ? AND v.vector_dimensions = ? " +
                "AND v.state = '$STATE_WAITING' AND v.search_fingerprint > ? AND EXISTS (SELECT 1 FROM passages p " +
                "WHERE p.search_fingerprint = v.search_fingerprint AND p.vector_model = v.vector_model " +
                "AND p.vector_dimensions = v.vector_dimensions) ORDER BY v.search_fingerprint LIMIT ?",
            // Every fingerprint is a non-empty hex string, so "" sorts before all of them.
            arrayOf(vectorModel, vectorDimensions.toString(), afterFingerprint ?: "", limit.toString())
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return fingerprints.map { fingerprint ->
            val key = VectorKey(fingerprint, vectorModel, vectorDimensions)
            WaitingVector(key, sourcesOf(db, key))
        }.filter { it.sources.isNotEmpty() }
    }

    private fun sourcesOf(db: SQLiteDatabase, key: VectorKey): List<VectorSourcePassage> =
        db.rawQuery(
            "SELECT chat_id, message_id, passage_order, text_fingerprint, speaker_title, cutter_version, search_settings " +
                "FROM passages WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ? " +
                "ORDER BY chat_id, message_id, passage_order",
            arrayOf(key.searchFingerprint, key.vectorModel, key.vectorDimensions.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(VectorSourcePassage(
                    chatId = cursor.getString(0),
                    messageId = cursor.getString(1),
                    order = cursor.getInt(2),
                    textFingerprint = cursor.getString(3),
                    speakerTitle = cursor.getString(4),
                    cutterVersion = cursor.getInt(5),
                    searchSettings = cursor.getString(6)
                ))
            }
        }

    override fun publishVector(
        key: VectorKey,
        source: VectorSourcePassage,
        vector: ByteArray,
        sourceUnchanged: () -> Boolean
    ): VectorWrite = vectorTransaction(key, source, sourceUnchanged) { db ->
        db.execSQL(
            "UPDATE passage_vectors SET state = '$STATE_MADE', vector = ? " +
                "WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?",
            arrayOf<Any>(vector, key.searchFingerprint, key.vectorModel, key.vectorDimensions)
        )
    }

    override fun markVectorFailed(
        key: VectorKey,
        source: VectorSourcePassage,
        sourceUnchanged: () -> Boolean
    ): VectorWrite = vectorTransaction(key, source, sourceUnchanged) { db ->
        db.execSQL(
            "UPDATE passage_vectors SET state = '$STATE_FAILED', vector = NULL " +
                "WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?",
            arrayOf<Any>(key.searchFingerprint, key.vectorModel, key.vectorDimensions)
        )
    }

    /** Test hook: called inside a vector write after it changed the row, before the source is checked again. */
    @Volatile internal var afterVectorWritten: (() -> Unit)? = null

    /**
     * One vector write: the row must still be waiting and [source] must still
     * use it with the same original-text fingerprint; [change] runs, every
     * chat using the vector gets its progress recounted, and [sourceUnchanged]
     * is checked just before commit. Any failed check writes nothing.
     */
    private fun vectorTransaction(
        key: VectorKey,
        source: VectorSourcePassage,
        sourceUnchanged: () -> Boolean,
        change: (SQLiteDatabase) -> Unit
    ): VectorWrite = try {
        transaction { db ->
            val state = db.rawQuery(
                "SELECT state FROM passage_vectors WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?",
                arrayOf(key.searchFingerprint, key.vectorModel, key.vectorDimensions.toString())
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            val used = db.rawQuery(
                "SELECT 1 FROM passages WHERE chat_id = ? AND message_id = ? AND passage_order = ? " +
                    "AND text_fingerprint = ? AND search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?",
                arrayOf(
                    source.chatId, source.messageId, source.order.toString(), source.textFingerprint,
                    key.searchFingerprint, key.vectorModel, key.vectorDimensions.toString()
                )
            ).use { it.moveToFirst() }
            if (state != STATE_WAITING || !used) return@transaction VectorWrite.GONE
            change(db)
            recountChatsUsing(db, key)
            afterVectorWritten?.invoke()
            if (!sourceUnchanged()) throw ChatChanged()
            VectorWrite.WRITTEN
        }
    } catch (_: ChatChanged) {
        VectorWrite.STALE
    }

    /** Recounts waiting passages, and whether the chat is fully indexed, for every chat using [key]. */
    private fun recountChatsUsing(db: SQLiteDatabase, key: VectorKey) {
        val waiting = "(SELECT COUNT(*) FROM passages p JOIN passage_vectors v ON v.search_fingerprint = p.search_fingerprint " +
            "AND v.vector_model = p.vector_model AND v.vector_dimensions = p.vector_dimensions " +
            "WHERE p.chat_id = index_state.chat_id AND v.state = '$STATE_WAITING')"
        db.execSQL(
            "UPDATE index_state SET waiting_vectors = $waiting, fully_indexed = CASE WHEN needs_refresh = 0 " +
                "AND described_version IS NOT NULL AND $waiting = 0 THEN 1 ELSE 0 END WHERE chat_id IN " +
                "(SELECT DISTINCT chat_id FROM passages WHERE search_fingerprint = ? AND vector_model = ? AND vector_dimensions = ?)",
            arrayOf<Any>(key.searchFingerprint, key.vectorModel, key.vectorDimensions)
        )
    }

    override fun markNeedsRefresh(chatId: String) = beginUpdate(chatId)

    override fun chatProgress(chatId: String): ChatIndexProgress {
        val db = readableDatabase
        val counts = db.rawQuery(
            "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN v.state = '$STATE_MADE' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN v.state = '$STATE_WAITING' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN v.state = '$STATE_FAILED' THEN 1 ELSE 0 END), 0) " +
                "FROM passages p LEFT JOIN passage_vectors v ON v.search_fingerprint = p.search_fingerprint " +
                "AND v.vector_model = p.vector_model AND v.vector_dimensions = p.vector_dimensions WHERE p.chat_id = ?",
            arrayOf(chatId)
        ).use { cursor -> cursor.moveToFirst(); IntArray(4) { cursor.getInt(it) } }
        val state = indexState(chatId)
        return ChatIndexProgress(
            passages = counts[0],
            made = counts[1],
            waiting = counts[2],
            failed = counts[3],
            needsRefresh = state?.needsRefresh ?: false,
            fullyIndexed = state?.fullyIndexed ?: false
        )
    }

    override fun removeObsoleteVectors(currentModel: String, currentDimensions: Int): Int = transaction { db ->
        val current = arrayOf(currentModel, currentDimensions.toString())
        fun any(sql: String, args: Array<String> = emptyArray()) = db.rawQuery(sql, args).use { it.moveToFirst() }
        val incomplete =
            any("SELECT 1 FROM passages WHERE NOT (vector_model = ? AND vector_dimensions = ?) LIMIT 1", current) ||
                any("SELECT 1 FROM index_state WHERE needs_refresh = 1 LIMIT 1") ||
                any(
                    "SELECT 1 FROM passages p JOIN passage_vectors v ON v.search_fingerprint = p.search_fingerprint " +
                        "AND v.vector_model = p.vector_model AND v.vector_dimensions = p.vector_dimensions " +
                        "WHERE v.state = '$STATE_WAITING' LIMIT 1"
                )
        if (incomplete) 0 else db.delete(
            "passage_vectors", "NOT (vector_model = ? AND vector_dimensions = ?)", arrayOf<Any>(currentModel, currentDimensions)
        )
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
        const val STATE_MADE = "made"
        const val STATE_FAILED = "failed"

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
