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
 * Encrypted per-message chat storage (chat-storage-plan.md, stage 1).
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
        val revision: Long
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
                "revision INTEGER NOT NULL DEFAULT 0)"
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
        db.execSQL("CREATE TABLE list_state (key TEXT PRIMARY KEY, value_json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_messages_message_id ON messages(message_id)")
        putMeta(db, META_SCHEMA, DATABASE_VERSION.toString())
        // Distinguishes this database from any earlier or restored one, so a
        // later revision-based change check can't confuse two stores whose
        // revision counters happen to match.
        putMeta(db, META_STORE_ID, UUID.randomUUID().toString())
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

    fun putChat(chat: StoredChat) {
        val values = ContentValues().apply {
            put("chat_id", chat.chatId)
            put("list_order", chat.listOrder)
            put("list_row_json", chat.listRowJson)
            put("prefs_json", chat.prefsJson)
            put("readable", if (chat.readable) 1 else 0)
            put("revision", chat.revision)
        }
        val db = writableDatabase
        // UPDATE first: INSERT OR REPLACE would delete the row and cascade
        // away the chat's messages.
        val updated = db.update("chats", values, "chat_id = ?", arrayOf(chat.chatId))
        if (updated == 0) db.insertOrThrow("chats", null, values)
    }

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

    private fun splitPayload(payload: String): SplitPayload {
        if (payload.length <= PART_CHARS) return SplitPayload(payload, emptyList())
        val parts = ArrayList<String>(payload.length / PART_CHARS + 1)
        var start = 0
        while (start < payload.length) {
            var end = minOf(start + PART_CHARS, payload.length)
            // Never cut a surrogate pair in half.
            if (end < payload.length && Character.isHighSurrogate(payload[end - 1])) end--
            parts.add(payload.substring(start, end))
            start = end
        }
        return SplitPayload(null, parts)
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
        db.execSQL("UPDATE chats SET revision = revision + 1 WHERE chat_id = ?", arrayOf(chatId))
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
        revision = getLong(5)
    )

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    companion object {
        const val DATABASE_NAME = "chats.db"
        private const val DATABASE_VERSION = 1
        const val META_SCHEMA = "schema"
        const val META_STORE_ID = "store_id"

        /** Largest payload kept in one column; longer ones go to message_parts. */
        const val PART_CHARS = 128 * 1024
        const val DEFAULT_PAGE = 200

        private const val CHAT_COLUMNS = "chat_id, list_order, list_row_json, prefs_json, readable, revision"
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
