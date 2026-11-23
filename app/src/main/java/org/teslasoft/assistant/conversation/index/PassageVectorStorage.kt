/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

/** One passage-vector row: the exact model input's fingerprint, the model and the vector size (G.5). */
data class VectorKey(val searchFingerprint: String, val vectorModel: String, val vectorDimensions: Int)

/**
 * A passage that uses a waiting vector, with what is needed to rebuild its
 * search text from the original message (G.5, "Two texts for every passage").
 */
data class VectorSourcePassage(
    val chatId: String,
    val messageId: String,
    val order: Int,
    val textFingerprint: String,
    val speakerTitle: String,
    val cutterVersion: Int,
    val searchSettings: String
)

/** A vector still to be made, and every passage that uses it, in a fixed order. */
data class WaitingVector(val key: VectorKey, val sources: List<VectorSourcePassage>)

/** Progress of one chat's index, counted over its passages (G.5a rule 5). */
data class ChatIndexProgress(
    val passages: Int,
    val made: Int,
    val waiting: Int,
    /** Passages whose search text does not fit the model; never retried for the same text. */
    val failed: Int,
    val needsRefresh: Boolean,
    val fullyIndexed: Boolean
)

enum class VectorWrite {
    WRITTEN,

    /** No passage uses the vector any more, or it is no longer waiting; nothing was written. */
    GONE,

    /** The passage's original message changed; nothing was written. */
    STALE
}

/**
 * The vector side of the conversation index (Step 9; G.5a rules 2, 3, 4).
 * Every write is one transaction that checks, inside it, that the vector row
 * is still waiting and still used by the passage it was made for, and that
 * the passage still matches its original message.
 */
interface PassageVectorStorage {

    /**
     * Up to [limit] waiting vectors of [vectorModel] and [vectorDimensions]
     * that at least one passage uses, ordered by fingerprint and starting
     * after [afterFingerprint].
     */
    fun waitingVectors(
        vectorModel: String,
        vectorDimensions: Int,
        afterFingerprint: String?,
        limit: Int
    ): List<WaitingVector>

    /**
     * Stores [vector] for [key] when the row is still waiting, [source] still
     * uses it, and [sourceUnchanged] is still true just before commit. Updates
     * the progress of every chat that uses the vector.
     */
    fun publishVector(
        key: VectorKey,
        source: VectorSourcePassage,
        vector: ByteArray,
        sourceUnchanged: () -> Boolean
    ): VectorWrite

    /** Records [key] as failed: its exact input does not fit the model. Same checks as [publishVector]. */
    fun markVectorFailed(
        key: VectorKey,
        source: VectorSourcePassage,
        sourceUnchanged: () -> Boolean
    ): VectorWrite

    /** Marks a chat whose passages no longer match its messages for a refresh (G.5a rule 4). */
    fun markNeedsRefresh(chatId: String)

    fun chatProgress(chatId: String): ChatIndexProgress

    /**
     * G.5a rule 3: once the index is complete for the current model (no
     * passage uses another model, nothing waits and no chat is being
     * refreshed), removes the vectors of every other model. Before that,
     * they are kept, so an unfinished model change loses nothing.
     * Returns how many were removed.
     */
    fun removeObsoleteVectors(currentModel: String, currentDimensions: Int): Int
}
