/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

/** What the index holds for one chat, read for the settings status (Step 11). */
data class ChatIndexSummary(
    /** The chat version and cutting settings the index fully describes, or null. */
    val describedVersion: String?,
    val needsRefresh: Boolean,
    /** Messages that could be indexed once they have a permanent ID (G28). */
    val unidentifiedMessages: Int,
    val passages: Int,
    /** Passages whose vector is made (not waiting, not failed). */
    val madePassages: Int,
    /** Passages cut for a model other than the current one. */
    val otherModelPassages: Int
)

interface ConversationIndexSummaries {
    /** One summary per chat with an index-state row, counting vectors of [vectorModel] at [vectorDimensions] as current. */
    fun chatSummaries(vectorModel: String, vectorDimensions: Int): Map<String, ChatIndexSummary>

    /** Passages whose vector for the model is still waiting to be made. */
    fun waitingPassages(vectorModel: String, vectorDimensions: Int): Int
}

/** The numbers on the settings status line (G.16a, G28). */
data class ConversationIndexCounts(val indexed: Int, val needOpening: Int)

/**
 * Counts the chats for the status line (Owner, 2026-11-24, G28):
 *
 *  - **indexed:** the index describes the chat exactly as it is stored now,
 *    under the current cutting settings and model, every message that can be
 *    indexed has a permanent ID, and every passage has its vector made. A
 *    waiting or failed vector, a pending refresh or a change not yet compared
 *    keeps the chat out of the count.
 *  - **need opening:** the index describes the chat as stored now and it has
 *    messages that can only be indexed once opening gives them permanent IDs.
 *
 * A chat with nothing to index (empty, or every message hidden) is in
 * neither count, nor is a chat the index has not described yet. Only chats
 * in the chat list are counted.
 */
object ConversationIndexCounting {

    /**
     * @param liveChats every chat in the chat list.
     * @param chatVersion the chat's stored version now.
     * @param settingsKey the current cutting settings and model, or null with
     *   no model installed: then a chat counts by the model it was indexed
     *   with, and only its stored version is compared.
     */
    fun count(
        liveChats: Collection<String>,
        summaries: Map<String, ChatIndexSummary>,
        chatVersion: (String) -> String,
        settingsKey: String?
    ): ConversationIndexCounts {
        var indexed = 0
        var needOpening = 0
        for (chatId in liveChats.toSet()) {
            val summary = summaries[chatId] ?: continue
            if (summary.needsRefresh) continue
            val described = summary.describedVersion ?: continue
            val current = if (settingsKey != null) {
                ConversationIndexCoordinator.describedVersion(chatVersion(chatId), settingsKey) == described
            } else {
                described.substringBefore('|') == chatVersion(chatId)
            }
            if (!current) continue
            when {
                summary.unidentifiedMessages > 0 -> needOpening++
                summary.passages > 0 && summary.madePassages == summary.passages &&
                    (settingsKey == null || summary.otherModelPassages == 0) -> indexed++
            }
        }
        return ConversationIndexCounts(indexed, needOpening)
    }
}
