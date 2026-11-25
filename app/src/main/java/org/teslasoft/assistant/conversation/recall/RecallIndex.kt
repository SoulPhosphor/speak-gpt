/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import org.teslasoft.assistant.conversation.index.Speaker

/**
 * One indexed passage of a chat, as retrieval sees it (memory development plan
 * G.8a point 1). It holds positions and stamps only, never message text: the
 * text is read from the chat as stored now.
 */
data class RecallPassage(
    val rowId: Long,
    val messageId: String,
    val order: Int,
    val start: Int,
    val end: Int,
    /** The user message that opens the passage's exchange, or null when none precedes it. */
    val exchangeId: String?,
    val speaker: Speaker,
    /** The title the embedding model read the passage under. */
    val speakerTitle: String,
    val timeMillis: Long?,
    val chatPosition: Int,
    /** Fingerprint of the message's model-facing text when it was indexed. */
    val textFingerprint: String,
    val vectorModel: String,
    val vectorDimensions: Int,
    /** The permanent identity IDs recorded on the message (G.7); empty when its scope is unknown. */
    val identityIds: Set<String> = emptySet()
)

/**
 * The index's answers for one chat (G.8a point 1). Retrieval never reads the
 * index's storage directly. Each call is answered from the index as it is at
 * that moment; background indexing may change it between calls, so every
 * passage is checked against the chat as stored now before it is used.
 */
interface RecallIndex {
    /** Every indexed passage of [chatId]. */
    fun passages(chatId: String): List<RecallPassage>

    /** The row IDs of [chatId]'s passages whose own words include [term] (a [org.teslasoft.assistant.preferences.memory.librarian.KeywordText] term). */
    fun rowsWithTerm(chatId: String, term: String): Set<Long>

    /**
     * Up to [limit] made vectors of [chatId]'s passages for exactly this
     * model and size, with row ID above [afterRowId], in row order. A passage
     * whose vector is waiting or failed is not returned.
     */
    fun vectors(chatId: String, vectorModel: String, vectorDimensions: Int, afterRowId: Long, limit: Int): List<RowVector>

    class RowVector(val rowId: Long, val vector: FloatArray)
}
