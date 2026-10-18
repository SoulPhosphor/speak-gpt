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

package org.teslasoft.assistant.preferences.chatstore

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.util.UUID

/**
 * Encrypted per-message chat storage (chat-storage-and-backup-plan.md §4.1).
 *
 * One row per chat and one row per message, so a conversation can be read a
 * page at a time and a single message can be saved without rewriting the
 * rest. Message and chat payloads are kept as the exact JSON the app already
 * stores, so every field — including ones this class does not know about —
 * survives unchanged.
 *
 * A message payload longer than [PART_CHARS] is stored in ordered parts
 * instead of one column value, so no single row can outgrow the cursor
 * window. Parts are an internal detail: callers always see one message.
 *
 * Attached-document text can be kept beside its message in
 * include_text_parts, keyed by include ID and split the same way, so a
 * book-length document never has to sit inside the message payload. Rows
 * belong to their message and are removed with it.
 *
 * Separate from companion_memory.db on purpose (chats and memories are reset
 * and restored independently) and from chat_search.db (which is disposable).
 * The caller picks the corruption handler: before this store is the
 * authoritative chat copy a corrupt file is simply closed; afterwards the
 * app's preserve-the-original handler is used. No handler here deletes data.
 */
class ChatStore private constructor(
    context: Context,
    password: ByteArray,
    databaseName: String,
    errorHandler: DatabaseErrorHandler
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    password,
    null,
    DATABASE_VERSION,
    0,
    errorHandler,
    null,
    true
) {

    data class StoredChat(
        val chatId: String,
        val listOrder: Long,
        val listRowJson: String,
        val prefsJson: String,
        val readable: Boolean,
        /** Read from the database; ignored when writing (see [putChat]). */
        val revision: Long,
        /** False when the chat's history has never been saved (the app's
         *  MISSING state, distinct from a saved empty history). Any message
         *  write sets it. */
        val historySaved: Boolean = true
    )

    data class MessageRow(
        val messageId: String?,
        val isBot: Boolean,
        val payloadJson: String
    )

    data class StoredMessage(
        val seq: Long,
        val messageId: String?,
        val isBot: Boolean,
        val payloadJson: String
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE chats (" +
                "chat_id TEXT PRIMARY KEY, " +
                "list_order INTEGER NOT NULL, " +
                "list_row_json TEXT NOT NULL, " +
                "prefs_json TEXT NOT NULL DEFAULT '{}', " +
                "readable INTEGER NOT NULL DEFAULT 1, " +
                "revision INTEGER NOT NULL DEFAULT 0, " +
                "history_saved INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE messages (" +
                "row_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id TEXT NOT NULL REFERENCES chats(chat_id) ON DELETE CASCADE, " +
                "seq INTEGER NOT NULL, " +
                "message_id TEXT, " +
                "is_bot INTEGER NOT NULL DEFAULT 0, " +
                // NULL when the payload is stored in message_parts.
                "payload_json TEXT, " +
                "UNIQUE(chat_id, seq))"
        )
        db.execSQL(
            "CREATE TABLE message_parts (" +
                "message_row INTEGER NOT NULL REFERENCES messages(row_id) ON DELETE CASCADE, " +
                "part_index INTEGER NOT NULL, " +
                "part_text TEXT NOT NULL, " +
                "PRIMARY KEY(message_row, part_index))"
        )
        db.execSQL(
            "CREATE TABLE include_text_parts (" +
                "message_row INTEGER NOT NULL REFERENCES messages(row_id) ON DELETE CASCADE, " +
                "include_id TEXT NOT NULL, " +
                "part_index INTEGER NOT NULL, " +
                "part_text TEXT NOT NULL, " +
                "PRIMARY KEY(message_row, include_id, part_index))"
        )
        db.execSQL("CREATE TABLE list_state (key TEXT PRIMARY KEY, value_json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_messages_message_id ON messages(message_id)")
        putMeta(db, META_SCHEMA, DATABASE_VERSION.toString())
        // Distinguishes this database from any earlier or restored one, so a
        // later revision-based change check can't confuse two stores whose
        // revision counters happen to match.
        putMeta(db, META_STORE_ID, UUID.randomUUID().toString())
        // Revisions come from one store-wide counter, so a chat deleted and
        // recreated under the same ID never repeats an earlier revision.
        putMeta(db, META_REVISION_COUNTER, "0")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Later versions add additive steps
        // here; this store is authoritative and is never rebuilt from scratch.
    }

    /* ------------------------------ meta ------------------------------ */

    fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) = putMeta(writableDatabase, key, value)

    fun storeId(): String? = getMeta(META_STORE_ID)

    /* ------------------------------ chats ------------------------------ */

    /**
     * Add [chat], or update an existing chat's list row, values and state.
     * The revision belongs to the database: every insert and update takes the
     * next value of the store-wide counter, and [StoredChat.revision] is
     * ignored. [StoredChat.historySaved] is used only when the chat is
     * created; updates keep the stored flag. So a stale or re-read
     * [StoredChat] can never hide a change, move the revision backward, or
     * un-save a history. Adding a chat also marks the list as saved.
     */
    fun putChat(chat: StoredChat) = inTransaction { db ->
        val values = ContentValues().apply {
            put("list_order", chat.listOrder)
            put("list_row_json", chat.listRowJson)
            put("prefs_json", chat.prefsJson)
            put("readable", if (chat.readable) 1 else 0)
        }
        // UPDATE first: INSERT OR REPLACE would delete the row and cascade
        // away the chat's messages.
        val updated = db.update("chats", values, "chat_id = ?", arrayOf(chat.chatId))
        if (updated == 0) {
            values.put("chat_id", chat.chatId)
            values.put("revision", nextRevision(db))
            values.put("history_saved", if (chat.historySaved) 1 else 0)
            db.insertOrThrow("chats", null, values)
            putMeta(db, META_LIST_SAVED, "1")
        } else {
            db.execSQL(
                "UPDATE chats SET revision = ? WHERE chat_id = ?",
                arrayOf<Any>(nextRevision(db), chat.chatId)
            )
        }
    }

    /**
     * Whether the chat list has ever been saved: false only for a store that
     * has never held a chat or been marked, the app's MISSING list state.
     */
    fun listSaved(): Boolean = getMeta(META_LIST_SAVED) == "1"

    /** Record a saved list that holds no chats (the app's EMPTY list state). */
    fun markListSaved() = putMeta(writableDatabase, META_LIST_SAVED, "1")

    fun getChat(chatId: String): StoredChat? =
        readableDatabase.rawQuery(
            "SELECT $CHAT_COLUMNS FROM chats WHERE chat_id = ?", arrayOf(chatId)
        ).use { if (it.moveToFirst()) it.toChat() else null }

    /** Every chat in list order. Holds list metadata only, never messages. */
    fun listChats(): List<StoredChat> =
        readableDatabase.rawQuery(
            "SELECT $CHAT_COLUMNS FROM chats ORDER BY list_order, chat_id", null
        ).use { c ->
            val out = ArrayList<StoredChat>(c.count)
            while (c.moveToNext()) out.add(c.toChat())
            out
        }

    fun deleteChat(chatId: String): Boolean =
        writableDatabase.delete("chats", "chat_id = ?", arrayOf(chatId)) > 0

    fun revision(chatId: String): Long? =
        readableDatabase.rawQuery("SELECT revision FROM chats WHERE chat_id = ?", arrayOf(chatId))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    /* ------------------------------ list state ------------------------------ */

    fun putListState(key: String, valueJson: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO list_state (key, value_json) VALUES (?, ?)",
            arrayOf(key, valueJson)
        )
    }

    fun getListState(key: String): String? =
        readableDatabase.rawQuery("SELECT value_json FROM list_state WHERE key = ?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun listStateKeys(): List<String> =
        readableDatabase.rawQuery("SELECT key FROM list_state ORDER BY key", null).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    /* ------------------------------ messages: reads ------------------------------ */

    fun messageCount(chatId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE chat_id = ?", arrayOf(chatId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Messages at positions [offset] until [offset] + [limit], oldest first. */
    fun readPage(chatId: String, offset: Int, limit: Int): List<StoredMessage> =
        readRows(
            "SELECT $MESSAGE_COLUMNS FROM messages WHERE chat_id = ? ORDER BY seq LIMIT ? OFFSET ?",
            arrayOf(chatId, limit.toString(), offset.toString())
        )

    /**
     * Up to [limit] messages immediately before [beforeSeq] (or the newest
     * messages when null), returned oldest first — the read a chat screen
     * makes when it opens and when the user scrolls up.
     */
    fun readBefore(chatId: String, beforeSeq: Long?, limit: Int): List<StoredMessage> {
        val rows = if (beforeSeq == null) {
            readRows(
                "SELECT $MESSAGE_COLUMNS FROM messages WHERE chat_id = ? ORDER BY seq DESC LIMIT ?",
                arrayOf(chatId, limit.toString())
            )
        } else {
            readRows(
                "SELECT $MESSAGE_COLUMNS FROM messages WHERE chat_id = ? AND seq < ? " +
                    "ORDER BY seq DESC LIMIT ?",
                arrayOf(chatId, beforeSeq.toString(), limit.toString())
            )
        }
        return rows.asReversed()
    }

    fun messageAt(chatId: String, position: Int): StoredMessage? =
        readPage(chatId, position, 1).firstOrNull()

    fun findByMessageId(chatId: String, messageId: String): StoredMessage? =
        readRows(
            "SELECT $MESSAGE_COLUMNS FROM messages WHERE chat_id = ? AND message_id = ? " +
                "ORDER BY seq LIMIT 1",
            arrayOf(chatId, messageId)
        ).firstOrNull()

    /** Zero-based position of the message with [seq], or null if it is gone. */
    fun positionOf(chatId: String, seq: Long): Int? {
        val exists = readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE chat_id = ? AND seq = ?", arrayOf(chatId, seq.toString())
        ).use { it.moveToFirst() }
        if (!exists) return null
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE chat_id = ? AND seq < ?",
            arrayOf(chatId, seq.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    /**
     * Visit every message oldest first, one page at a time, holding at most
     * one page in memory. [action] returns false to stop early.
     */
    fun forEachMessage(chatId: String, pageSize: Int = DEFAULT_PAGE, action: (StoredMessage) -> Boolean) {
        var afterSeq = Long.MIN_VALUE
        while (true) {
            val page = readRows(
                "SELECT $MESSAGE_COLUMNS FROM messages WHERE chat_id = ? AND seq > ? " +
                    "ORDER BY seq LIMIT ?",
                arrayOf(chatId, afterSeq.toString(), pageSize.toString())
            )
            if (page.isEmpty()) return
            for (message in page) {
                if (!action(message)) return
            }
            afterSeq = page.last().seq
        }
    }

    /* ------------------------------ messages: writes ------------------------------ */

    /** Append one message after the chat's current last message. Returns its seq. */
    fun appendMessage(chatId: String, row: MessageRow): Long = inTransaction { db ->
        val next = db.rawQuery(
            "SELECT COALESCE(MAX(seq) + 1, 0) FROM messages WHERE chat_id = ?", arrayOf(chatId)
        ).use { it.moveToFirst(); it.getLong(0) }
        insertMessage(db, chatId, next, row)
        bumpRevision(db, chatId)
        next
    }

    /** Replace the content of the message with [seq]. False if it does not exist. */
    fun replaceMessage(chatId: String, seq: Long, row: MessageRow): Boolean = inTransaction { db ->
        val rowId = rowIdOf(db, chatId, seq) ?: return@inTransaction false
        db.delete("message_parts", "message_row = ?", arrayOf(rowId.toString()))
        val stored = splitPayload(row.payloadJson)
        db.update("messages", ContentValues().apply {
            put("message_id", row.messageId)
            put("is_bot", if (row.isBot) 1 else 0)
            if (stored.inline != null) put("payload_json", stored.inline) else putNull("payload_json")
        }, "row_id = ?", arrayOf(rowId.toString()))
        insertParts(db, rowId, stored.parts)
        bumpRevision(db, chatId)
        true
    }

    fun deleteMessage(chatId: String, seq: Long): Boolean = inTransaction { db ->
        val deleted = db.delete(
            "messages", "chat_id = ? AND seq = ?", arrayOf(chatId, seq.toString())
        ) > 0
        if (deleted) bumpRevision(db, chatId)
        deleted
    }

    /** Remove every message after [seq] (Regenerate / Make Current). Returns how many. */
    fun deleteAfter(chatId: String, seq: Long): Int = inTransaction { db ->
        val deleted = db.delete(
            "messages", "chat_id = ? AND seq > ?", arrayOf(chatId, seq.toString())
        )
        if (deleted > 0) bumpRevision(db, chatId)
        deleted
    }

    /**
     * Replace a chat's whole history in one transaction, consuming [rows]
     * one at a time so the caller never has to hold the full history. Either
     * every row lands or none does. Returns the number written.
     */
    fun replaceAllMessages(chatId: String, rows: Sequence<MessageRow>): Int = inTransaction { db ->
        db.delete("messages", "chat_id = ?", arrayOf(chatId))
        var seq = 0L
        for (row in rows) {
            insertMessage(db, chatId, seq, row)
            seq++
        }
        bumpRevision(db, chatId)
        seq.toInt()
    }

    /* ------------------------------ document text ------------------------------ */

    /**
     * Store [text] as the document text of include [includeId] on the message
     * with [seq], replacing any earlier text for that include. False if the
     * message does not exist.
     */
    fun putIncludeText(chatId: String, seq: Long, includeId: String, text: String): Boolean =
        inTransaction { db ->
            val rowId = rowIdOf(db, chatId, seq) ?: return@inTransaction false
            db.delete(
                "include_text_parts", "message_row = ? AND include_id = ?",
                arrayOf(rowId.toString(), includeId)
            )
            splitText(text).forEachIndexed { index, part ->
                db.insertOrThrow("include_text_parts", null, ContentValues().apply {
                    put("message_row", rowId)
                    put("include_id", includeId)
                    put("part_index", index)
                    put("part_text", part)
                })
            }
            bumpRevision(db, chatId)
            true
        }

    /** The stored document text of include [includeId], or null if none is stored. */
    fun readIncludeText(chatId: String, seq: Long, includeId: String): String? {
        val db = readableDatabase
        val rowId = rowIdOf(db, chatId, seq) ?: return null
        val builder = StringBuilder()
        var found = false
        db.rawQuery(
            "SELECT part_text FROM include_text_parts WHERE message_row = ? AND include_id = ? " +
                "ORDER BY part_index",
            arrayOf(rowId.toString(), includeId)
        ).use { c ->
            while (c.moveToNext()) {
                found = true
                builder.append(c.getString(0))
            }
        }
        return if (found) builder.toString() else null
    }

    /** Include IDs with stored document text on the message with [seq], in ID order. */
    fun includeTextIds(chatId: String, seq: Long): List<String> {
        val db = readableDatabase
        val rowId = rowIdOf(db, chatId, seq) ?: return emptyList()
        return db.rawQuery(
            "SELECT DISTINCT include_id FROM include_text_parts WHERE message_row = ? ORDER BY include_id",
            arrayOf(rowId.toString())
        ).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
    }

    fun deleteIncludeText(chatId: String, seq: Long, includeId: String): Boolean = inTransaction { db ->
        val rowId = rowIdOf(db, chatId, seq) ?: return@inTransaction false
        val deleted = db.delete(
            "include_text_parts", "message_row = ? AND include_id = ?",
            arrayOf(rowId.toString(), includeId)
        ) > 0
        if (deleted) bumpRevision(db, chatId)
        deleted
    }

    /* ------------------------------ integrity ------------------------------ */

    /**
     * True when SQLite's own integrity check passes and no row points at a
     * missing parent. Used before a staged database is trusted.
     */
    fun integrityOk(): Boolean {
        val db = readableDatabase
        val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
            c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()
        }
        if (!integrity) return false
        if (db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }) return false
        // Foreign keys cannot see a missing child row, so check that every
        // message is stored exactly one way and every split value is whole.
        return SPLIT_CONSISTENCY_CHECKS.none { sql ->
            db.rawQuery(sql, null).use { it.moveToFirst() }
        }
    }

    /* ------------------------------ transactions ------------------------------ */

    fun <T> inTransaction(block: (SQLiteDatabase) -> T): T {
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

    /* ------------------------------ internals ------------------------------ */

    private class SplitPayload(val inline: String?, val parts: List<String>)

    private fun splitPayload(payload: String): SplitPayload =
        if (payload.length <= PART_CHARS) SplitPayload(payload, emptyList())
        else SplitPayload(null, splitText(payload))

    private fun splitText(text: String): List<String> {
        if (text.length <= PART_CHARS) return listOf(text)
        val parts = ArrayList<String>(text.length / PART_CHARS + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + PART_CHARS, text.length)
            // Never cut a surrogate pair in half.
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            parts.add(text.substring(start, end))
            start = end
        }
        return parts
    }

    private fun insertMessage(db: SQLiteDatabase, chatId: String, seq: Long, row: MessageRow) {
        val stored = splitPayload(row.payloadJson)
        val rowId = db.insertOrThrow("messages", null, ContentValues().apply {
            put("chat_id", chatId)
            put("seq", seq)
            put("message_id", row.messageId)
            put("is_bot", if (row.isBot) 1 else 0)
            if (stored.inline != null) put("payload_json", stored.inline) else putNull("payload_json")
        })
        insertParts(db, rowId, stored.parts)
    }

    private fun insertParts(db: SQLiteDatabase, rowId: Long, parts: List<String>) {
        parts.forEachIndexed { index, text ->
            db.insertOrThrow("message_parts", null, ContentValues().apply {
                put("message_row", rowId)
                put("part_index", index)
                put("part_text", text)
            })
        }
    }

    private fun rowIdOf(db: SQLiteDatabase, chatId: String, seq: Long): Long? =
        db.rawQuery(
            "SELECT row_id FROM messages WHERE chat_id = ? AND seq = ?", arrayOf(chatId, seq.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun bumpRevision(db: SQLiteDatabase, chatId: String) {
        db.execSQL(
            "UPDATE chats SET revision = ?, history_saved = 1 WHERE chat_id = ?",
            arrayOf<Any>(nextRevision(db), chatId)
        )
    }

    /** Advance the store-wide revision counter. Callers hold a transaction. */
    private fun nextRevision(db: SQLiteDatabase): Long {
        db.execSQL(
            "UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = ?",
            arrayOf(META_REVISION_COUNTER)
        )
        return db.rawQuery(
            "SELECT CAST(value AS INTEGER) FROM meta WHERE key = ?", arrayOf(META_REVISION_COUNTER)
        ).use { c ->
            check(c.moveToFirst()) { "revision counter missing" }
            c.getLong(0)
        }
    }

    private class RawRow(
        val rowId: Long,
        val seq: Long,
        val messageId: String?,
        val isBot: Boolean,
        val inlinePayload: String?
    )

    private fun readRows(sql: String, args: Array<String>): List<StoredMessage> {
        val db = readableDatabase
        val raw = db.rawQuery(sql, args).use { c ->
            val out = ArrayList<RawRow>(c.count)
            while (c.moveToNext()) {
                out.add(RawRow(
                    rowId = c.getLong(0),
                    seq = c.getLong(1),
                    messageId = if (c.isNull(2)) null else c.getString(2),
                    isBot = c.getInt(3) != 0,
                    inlinePayload = if (c.isNull(4)) null else c.getString(4)
                ))
            }
            out
        }
        // Split payloads are read after the page cursor is closed, one
        // message at a time.
        return raw.map { row ->
            StoredMessage(
                seq = row.seq,
                messageId = row.messageId,
                isBot = row.isBot,
                payloadJson = row.inlinePayload ?: readParts(db, row.rowId)
            )
        }
    }

    private fun readParts(db: SQLiteDatabase, rowId: Long): String {
        val builder = StringBuilder()
        db.rawQuery(
            "SELECT part_text FROM message_parts WHERE message_row = ? ORDER BY part_index",
            arrayOf(rowId.toString())
        ).use { c -> while (c.moveToNext()) builder.append(c.getString(0)) }
        return builder.toString()
    }

    private fun Cursor.toChat(): StoredChat = StoredChat(
        chatId = getString(0),
        listOrder = getLong(1),
        listRowJson = getString(2),
        prefsJson = getString(3),
        readable = getInt(4) != 0,
        revision = getLong(5),
        historySaved = getInt(6) != 0
    )

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    companion object {
        const val DATABASE_NAME = "chats.db"
        private const val DATABASE_VERSION = 1
        const val META_SCHEMA = "schema"
        const val META_STORE_ID = "store_id"
        private const val META_REVISION_COUNTER = "revision_counter"
        private const val META_LIST_SAVED = "list_saved"

        /** Largest payload kept in one column; longer ones go to message_parts. */
        const val PART_CHARS = 128 * 1024
        const val DEFAULT_PAGE = 200

        private const val CHAT_COLUMNS =
            "chat_id, list_order, list_row_json, prefs_json, readable, revision, history_saved"

        /** Each query returns a row only when stored data is inconsistent. */
        private val SPLIT_CONSISTENCY_CHECKS = listOf(
            // Inline payload that also has parts.
            "SELECT 1 FROM messages m WHERE m.payload_json IS NOT NULL AND EXISTS " +
                "(SELECT 1 FROM message_parts p WHERE p.message_row = m.row_id) LIMIT 1",
            // Split payload with no parts at all.
            "SELECT 1 FROM messages m WHERE m.payload_json IS NULL AND NOT EXISTS " +
                "(SELECT 1 FROM message_parts p WHERE p.message_row = m.row_id) LIMIT 1",
            // Parts that do not run 0, 1, 2, ... without a gap.
            "SELECT 1 FROM message_parts GROUP BY message_row " +
                "HAVING MIN(part_index) != 0 OR MAX(part_index) != COUNT(*) - 1 LIMIT 1",
            "SELECT 1 FROM include_text_parts GROUP BY message_row, include_id " +
                "HAVING MIN(part_index) != 0 OR MAX(part_index) != COUNT(*) - 1 LIMIT 1"
        )
        private const val MESSAGE_COLUMNS = "row_id, seq, message_id, is_bot, payload_json"

        @Volatile private var libraryLoaded = false

        private fun loadLibrary() {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
        }

        /** Open the store on [databaseName] with [password]. The caller owns the instance. */
        fun open(
            context: Context,
            password: ByteArray,
            errorHandler: DatabaseErrorHandler,
            databaseName: String = DATABASE_NAME
        ): ChatStore {
            loadLibrary()
            return ChatStore(context.applicationContext, password, databaseName, errorHandler)
        }
    }

    /** Closes a corrupt connection and deletes nothing. */
    object CloseOnCorruption : DatabaseErrorHandler {
        override fun onCorruption(
            dbObj: SQLiteDatabase,
            exception: android.database.sqlite.SQLiteException?
        ) {
            try { if (dbObj.isOpen) dbObj.close() } catch (_: Exception) { }
        }
    }
}
