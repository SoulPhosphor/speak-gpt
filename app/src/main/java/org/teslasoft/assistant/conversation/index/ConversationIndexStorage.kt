/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope

/**
 * What every passage of one indexed message records about the message
 * (memory development plan G.5). Two stamps that are equal describe the same
 * indexed state; any difference means the message must be written again.
 */
data class MessageStamp(
    val textFingerprint: String,
    val speaker: Speaker,
    /**
     * The title the embedding model read for this message's passages. It
     * comes from the message itself, so renaming an identity does not change
     * it unless the title policy is changed on purpose.
     */
    val speakerTitle: String,
    /** The user message ID that opens the exchange, or null when none precedes it. */
    val exchangeId: String?,
    /** The message's position among the chat's stored messages. */
    val chatPosition: Int,
    val timeMillis: Long?,
    /** Null when the scope is unknown (G.7). */
    val scope: ScanMessageScope?,
    val cutterVersion: Int,
    /** Target size, overlap and reader key: a change means cutting again. */
    val searchSettings: String,
    val vectorModel: String,
    val vectorDimensions: Int
) {
    /** True when [other] has the same passages and search texts, differing only in placement or scope. */
    fun sameSearchTexts(other: MessageStamp): Boolean =
        textFingerprint == other.textFingerprint && speaker == other.speaker && speakerTitle == other.speakerTitle &&
            cutterVersion == other.cutterVersion && searchSettings == other.searchSettings &&
            vectorModel == other.vectorModel && vectorDimensions == other.vectorDimensions
}

/** One message's passages, ready to be written. */
data class MessageEntries(val messageId: String, val stamp: MessageStamp, val passages: List<SearchPassage>) {
    init {
        require(passages.isNotEmpty()) { "an indexed message has at least one passage" }
    }
}

/** The index state row of one chat (G.5, Storage design). */
data class ChatIndexState(
    /** Which saved version of the chat the index fully describes, or null. */
    val describedVersion: String?,
    val needsRefresh: Boolean,
    val waitingVectors: Int,
    /**
     * Every passage has its vector: none is waiting and none failed. A failed
     * vector is never retried for the same text, so it is not indexed (Owner,
     * 2026-11-24).
     */
    val fullyIndexed: Boolean,
    /**
     * Messages of the described version that could be indexed but have no
     * permanent ID yet (G.5a rule 7): the chat has not been opened since IDs
     * were introduced.
     */
    val unidentifiedMessages: Int = 0
)

enum class IndexWrite {
    WRITTEN,

    /** The chat or the message changed since the update read it; nothing was written. */
    STALE
}

/**
 * The four conversation-index stores (G.5). Every write is one transaction
 * that first checks, inside the transaction, that the chat is still at the
 * recorded version and that the message is still in the state the caller
 * compared against (G.5a rule 4). A failure rolls the transaction back.
 */
interface ConversationIndexStorage {

    /** The stamp of every indexed message of [chatId], by permanent message ID. */
    fun indexedMessages(chatId: String): Map<String, MessageStamp>

    fun indexState(chatId: String): ChatIndexState?

    /** Marks the chat as needing a refresh while an update runs; keeps its described version. */
    fun beginUpdate(chatId: String)

    /**
     * Replaces one message's passages and keywords, or removes them when
     * [replacement] is null. New search texts get a "waiting" vector row; a
     * vector no passage uses any more is removed (G.5a rule 3).
     */
    fun writeMessage(
        chatId: String,
        messageId: String,
        expected: MessageStamp?,
        replacement: MessageEntries?,
        chatUnchanged: () -> Boolean
    ): IndexWrite

    /** Updates placement and scope on a message whose search texts are unchanged. */
    fun restampMessage(
        chatId: String,
        messageId: String,
        expected: MessageStamp,
        stamp: MessageStamp,
        chatUnchanged: () -> Boolean
    ): IndexWrite

    /**
     * Records that the index describes [chatVersion], when the indexed
     * messages are exactly [expected] and the chat is unchanged; otherwise
     * leaves the chat marked as needing a refresh. [unidentifiedMessages] is
     * recorded with it ([ChatIndexState.unidentifiedMessages]).
     */
    fun finishUpdate(
        chatId: String,
        chatVersion: String,
        expected: Map<String, MessageStamp>,
        unidentifiedMessages: Int,
        chatUnchanged: () -> Boolean
    ): IndexWrite
}
