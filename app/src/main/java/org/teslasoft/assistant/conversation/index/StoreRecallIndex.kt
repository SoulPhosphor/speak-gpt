/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import org.teslasoft.assistant.conversation.recall.RecallIndex
import org.teslasoft.assistant.conversation.recall.RecallPassage
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/**
 * Conversation recall's read-only view of the conversation index (memory
 * development plan G.8a point 1). Each call is one statement on the readable
 * database; nothing is written.
 */
class StoreRecallIndex(private val store: ConversationIndexStore) : RecallIndex {

    override fun passages(chatId: String): List<RecallPassage> =
        store.readableDatabase.rawQuery(
            "SELECT row_id, message_id, passage_order, text_start, text_end, exchange_id, speaker, speaker_title, " +
                "time_millis, chat_position, text_fingerprint, vector_model, vector_dimensions, scope_known, " +
                "scope_companion_persona, scope_companion, scope_character, scope_user_persona " +
                "FROM passages WHERE chat_id = ? ORDER BY row_id",
            arrayOf(chatId)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val identities = if (c.getInt(13) == 1) (14..17).mapNotNull { i ->
                        if (c.isNull(i)) null else c.getString(i).takeIf { it.isNotBlank() }
                    }.toSet() else emptySet()
                    add(RecallPassage(
                        rowId = c.getLong(0),
                        messageId = c.getString(1),
                        order = c.getInt(2),
                        start = c.getInt(3),
                        end = c.getInt(4),
                        exchangeId = if (c.isNull(5)) null else c.getString(5),
                        speaker = Speaker.fromStored(c.getString(6)),
                        speakerTitle = c.getString(7),
                        timeMillis = if (c.isNull(8)) null else c.getLong(8),
                        chatPosition = c.getInt(9),
                        textFingerprint = c.getString(10),
                        vectorModel = c.getString(11),
                        vectorDimensions = c.getInt(12),
                        identityIds = identities
                    ))
                }
            }
        }

    override fun rowsWithTerm(chatId: String, term: String): Set<Long> {
        if (term.isBlank()) return emptySet()
        return store.readableDatabase.rawQuery(
            "SELECT p.row_id FROM passage_keywords JOIN passages p ON p.row_id = passage_keywords.rowid " +
                "WHERE passage_keywords MATCH ? AND p.chat_id = ?",
            arrayOf("\"" + term.replace("\"", "\"\"") + "\"", chatId)
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    override fun vectors(
        chatId: String,
        vectorModel: String,
        vectorDimensions: Int,
        afterRowId: Long,
        limit: Int
    ): List<RecallIndex.RowVector> =
        store.readableDatabase.rawQuery(
            "SELECT p.row_id, v.vector FROM passages p JOIN passage_vectors v " +
                "ON v.search_fingerprint = p.search_fingerprint AND v.vector_model = p.vector_model " +
                "AND v.vector_dimensions = p.vector_dimensions " +
                "WHERE p.chat_id = ? AND p.vector_model = ? AND p.vector_dimensions = ? AND v.state = 'made' " +
                "AND v.vector IS NOT NULL AND p.row_id > ? ORDER BY p.row_id LIMIT ?",
            arrayOf(chatId, vectorModel, vectorDimensions.toString(), afterRowId.toString(), limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(RecallIndex.RowVector(c.getLong(0), VectorMath.fromBlob(c.getBlob(1))))
            }
        }
}
