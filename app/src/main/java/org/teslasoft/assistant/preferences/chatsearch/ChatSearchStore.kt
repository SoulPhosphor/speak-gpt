package org.teslasoft.assistant.preferences.chatsearch

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.teslasoft.assistant.preferences.memory.DatabaseKeys

/** Disposable encrypted FTS projection. Authoritative chat text never lives only here. */
class ChatSearchStore private constructor(
    context: Context,
    password: ByteArray,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    password,
    null,
    DATABASE_VERSION,
    0,
    SearchCorruptionHandler,
    null,
    true
) {
    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This database is derived. Schema changes are rebuilt from source rather than migrated.
        db.execSQL("DROP TABLE IF EXISTS search_documents_fts")
        db.execSQL("DROP TABLE IF EXISTS search_documents")
        db.execSQL("DROP TABLE IF EXISTS search_meta")
        createSchema(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE search_documents (" +
                "row_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "generation INTEGER NOT NULL," +
                "chat_id TEXT NOT NULL," +
                "document_key TEXT NOT NULL," +
                "document_kind TEXT NOT NULL CHECK(document_kind IN ('title','message'))," +
                "message_id TEXT," +
                "legacy_ordinal INTEGER," +
                "legacy_time INTEGER," +
                "legacy_role TEXT," +
                "raw_text TEXT NOT NULL," +
                "index_text TEXT NOT NULL," +
                "content_fingerprint TEXT NOT NULL," +
                "source_revision TEXT," +
                "chat_timestamp INTEGER NOT NULL," +
                "message_timestamp INTEGER," +
                "UNIQUE(generation, document_key))"
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE search_documents_fts USING fts5(" +
                "index_text, content='search_documents', content_rowid='row_id'," +
                "tokenize='unicode61 remove_diacritics 0', prefix='2 3 4')"
        )
        db.execSQL(
            "CREATE TRIGGER search_documents_ai AFTER INSERT ON search_documents BEGIN " +
                "INSERT INTO search_documents_fts(rowid,index_text) VALUES(new.row_id,new.index_text); END"
        )
        db.execSQL(
            "CREATE TRIGGER search_documents_ad AFTER DELETE ON search_documents BEGIN " +
                "INSERT INTO search_documents_fts(search_documents_fts,rowid,index_text) " +
                "VALUES('delete',old.row_id,old.index_text); END"
        )
        db.execSQL(
            "CREATE TRIGGER search_documents_au AFTER UPDATE ON search_documents BEGIN " +
                "INSERT INTO search_documents_fts(search_documents_fts,rowid,index_text) " +
                "VALUES('delete',old.row_id,old.index_text); " +
                "INSERT INTO search_documents_fts(rowid,index_text) VALUES(new.row_id,new.index_text); END"
        )
        db.execSQL("CREATE INDEX idx_search_generation_chat ON search_documents(generation,chat_id)")
        db.execSQL("CREATE INDEX idx_search_generation_kind ON search_documents(generation,document_kind)")
        db.execSQL("CREATE TABLE search_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        putMeta(db, META_SCHEMA, DATABASE_VERSION.toString())
    }

    fun activeGeneration(): Long? = meta(META_ACTIVE_GENERATION)?.toLongOrNull()

    fun health(): SearchHealth {
        val state = when (meta(META_CORPUS_STATE)) {
            "ready" -> SearchCorpusState.READY
            "incomplete" -> SearchCorpusState.INCOMPLETE
            "preparing" -> SearchCorpusState.PREPARING
            else -> SearchCorpusState.UNAVAILABLE
        }
        return SearchHealth(state, meta(META_SKIPPED)?.toIntOrNull() ?: 0)
    }

    fun requiresRebuild(localeTag: String): Boolean =
        activeGeneration() == null ||
            !meta(META_BUILD_GENERATION).isNullOrBlank() ||
            meta(META_POLICY_VERSION) != SearchTextPolicy.POLICY_VERSION.toString() ||
            meta(META_LOCALE) != localeTag

    fun beginGeneration(generation: Long, localeTag: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            putMeta(db, META_BUILD_GENERATION, generation.toString())
            putMeta(db, META_CORPUS_STATE, "preparing")
            putMeta(db, META_POLICY_VERSION, SearchTextPolicy.POLICY_VERSION.toString())
            putMeta(db, META_LOCALE, localeTag)
            db.delete("search_documents", "generation = ?", arrayOf(generation.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun insertDocuments(generation: Long, documents: List<SearchDocument>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            documents.forEach { document ->
                val values = ContentValues().apply {
                    put("generation", generation)
                    put("chat_id", document.chatId)
                    put("document_key", document.documentKey)
                    put("document_kind", if (document.kind == SearchDocumentKind.TITLE) "title" else "message")
                    put("message_id", document.messageId)
                    put("legacy_ordinal", document.legacyOrdinal)
                    put("legacy_time", document.messageTimestamp)
                    put("legacy_role", document.legacyRole)
                    put("raw_text", document.rawText)
                    put("index_text", document.indexText)
                    put("content_fingerprint", document.contentFingerprint)
                    put("source_revision", document.sourceRevision)
                    put("chat_timestamp", document.chatTimestamp)
                    put("message_timestamp", document.messageTimestamp)
                }
                db.insertOrThrow("search_documents", null, values)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun activateGeneration(generation: Long, skippedChats: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            putMeta(db, META_ACTIVE_GENERATION, generation.toString())
            putMeta(db, META_BUILD_GENERATION, "")
            putMeta(db, META_SKIPPED, skippedChats.toString())
            putMeta(db, META_CORPUS_STATE, if (skippedChats == 0) "ready" else "incomplete")
            db.delete("search_documents", "generation != ?", arrayOf(generation.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun abortGeneration(generation: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("search_documents", "generation = ?", arrayOf(generation.toString()))
            putMeta(db, META_BUILD_GENERATION, "")
            putMeta(
                db,
                META_CORPUS_STATE,
                when {
                    activeGeneration() == null -> "unavailable"
                    (meta(META_SKIPPED)?.toIntOrNull() ?: 0) > 0 -> "incomplete"
                    else -> "ready"
                }
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun replaceChat(generation: Long, chatId: String, documents: List<SearchDocument>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("search_documents", "generation = ? AND chat_id = ?", arrayOf(generation.toString(), chatId))
            documents.forEach { document ->
                val values = ContentValues().apply {
                    put("generation", generation)
                    put("chat_id", document.chatId)
                    put("document_key", document.documentKey)
                    put("document_kind", if (document.kind == SearchDocumentKind.TITLE) "title" else "message")
                    put("message_id", document.messageId)
                    put("legacy_ordinal", document.legacyOrdinal)
                    put("legacy_time", document.messageTimestamp)
                    put("legacy_role", document.legacyRole)
                    put("raw_text", document.rawText)
                    put("index_text", document.indexText)
                    put("content_fingerprint", document.contentFingerprint)
                    put("source_revision", document.sourceRevision)
                    put("chat_timestamp", document.chatTimestamp)
                    put("message_timestamp", document.messageTimestamp)
                }
                db.insertOrThrow("search_documents", null, values)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun replaceTitle(generation: Long, chatId: String, document: SearchDocument) {
        require(document.kind == SearchDocumentKind.TITLE && document.chatId == chatId)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "search_documents",
                "generation = ? AND chat_id = ? AND document_kind = 'title'",
                arrayOf(generation.toString(), chatId)
            )
            val values = ContentValues().apply {
                put("generation", generation)
                put("chat_id", document.chatId)
                put("document_key", document.documentKey)
                put("document_kind", "title")
                put("message_id", document.messageId)
                put("legacy_ordinal", document.legacyOrdinal)
                put("legacy_time", document.messageTimestamp)
                put("legacy_role", document.legacyRole)
                put("raw_text", document.rawText)
                put("index_text", document.indexText)
                put("content_fingerprint", document.contentFingerprint)
                put("source_revision", document.sourceRevision)
                put("chat_timestamp", document.chatTimestamp)
                put("message_timestamp", document.messageTimestamp)
            }
            db.insertOrThrow("search_documents", null, values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deleteChat(generation: Long, chatId: String) {
        writableDatabase.delete(
            "search_documents", "generation = ? AND chat_id = ?", arrayOf(generation.toString(), chatId)
        )
    }

    fun candidates(generation: Long, match: String, limit: Int, offset: Int): List<SearchCandidate> {
        val sql = "SELECT d.row_id,d.chat_id,d.document_key,d.document_kind,d.message_id," +
            "d.legacy_ordinal,d.legacy_time,d.legacy_role,d.raw_text,d.index_text," +
            "d.content_fingerprint,d.source_revision,d.chat_timestamp,d.message_timestamp," +
            "bm25(search_documents_fts) " +
            "FROM search_documents_fts JOIN search_documents d ON d.row_id=search_documents_fts.rowid " +
            "WHERE search_documents_fts MATCH ? AND d.generation=? " +
            "ORDER BY bm25(search_documents_fts),d.chat_timestamp DESC,d.row_id LIMIT ? OFFSET ?"
        return readableDatabase.rawQuery(
            sql, arrayOf(match, generation.toString(), limit.toString(), offset.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val kind = if (cursor.getString(3) == "title") SearchDocumentKind.TITLE else SearchDocumentKind.MESSAGE
                    val document = SearchDocument(
                        chatId = cursor.getString(1), documentKey = cursor.getString(2), kind = kind,
                        rawText = cursor.getString(8), indexText = cursor.getString(9),
                        contentFingerprint = cursor.getString(10),
                        sourceRevision = cursor.getStringOrNull(11), chatTimestamp = cursor.getLong(12),
                        messageTimestamp = cursor.getLongOrNull(13), messageId = cursor.getStringOrNull(4),
                        legacyOrdinal = cursor.getIntOrNull(5), legacyRole = cursor.getStringOrNull(7)
                    )
                    add(SearchCandidate(cursor.getLong(0), document, cursor.getDouble(14)))
                }
            }
        }
    }

    fun verifyIntegrity(): Boolean = try {
        val ordinary = readableDatabase.rawQuery("PRAGMA integrity_check", emptyArray()).use {
            it.moveToFirst() && it.getString(0) == "ok"
        }
        readableDatabase.execSQL("INSERT INTO search_documents_fts(search_documents_fts) VALUES('integrity-check')")
        ordinary
    } catch (_: Exception) { false }

    fun rebuildFullTextIndex() {
        writableDatabase.execSQL(
            "INSERT INTO search_documents_fts(search_documents_fts) VALUES('rebuild')"
        )
    }

    private fun meta(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM search_meta WHERE key=?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO search_meta(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    companion object {
        const val DATABASE_NAME = "chat_search.db"
        private const val DATABASE_VERSION = 1
        private const val META_SCHEMA = "schema"
        private const val META_POLICY_VERSION = "match_policy_version"
        private const val META_LOCALE = "locale"
        private const val META_ACTIVE_GENERATION = "active_generation"
        private const val META_BUILD_GENERATION = "build_generation"
        private const val META_CORPUS_STATE = "corpus_state"
        private const val META_SKIPPED = "skipped_chats"

        @Volatile private var instance: ChatSearchStore? = null
        @Volatile private var libraryLoaded = false

        fun get(context: Context): ChatSearchStore {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: run {
                    if (!libraryLoaded) {
                        System.loadLibrary("sqlcipher")
                        libraryLoaded = true
                    }
                    val file = app.getDatabasePath(DATABASE_NAME)
                    val key = DatabaseKeys.getOrCreate(app, DatabaseKeys.KEY_CHAT_SEARCH, file.exists())
                        ?: throw IllegalStateException("Search index key unavailable")
                    ChatSearchStore(app, key).also { instance = it }
                }
            }
        }

        fun openForTest(context: Context, databaseName: String, password: ByteArray): ChatSearchStore {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
            return ChatSearchStore(context.applicationContext, password, databaseName)
        }

        fun discard(context: Context): Boolean = synchronized(this) {
            try { instance?.close() } catch (_: Exception) { }
            instance = null
            val app = context.applicationContext
            listOf("", "-wal", "-shm", "-journal").all { suffix ->
                val file = app.getDatabasePath(DATABASE_NAME + suffix)
                !file.exists() || file.delete()
            }
        }
    }

    private object SearchCorruptionHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase) {
            try { dbObj.close() } catch (_: Exception) { }
        }
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index)
