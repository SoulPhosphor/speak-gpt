/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

/**
 * Whole-chat upkeep of the conversation index (memory development plan G.12,
 * Step 10). Each operation is one transaction: it applies completely or not
 * at all. The index is derived, so nothing here touches a stored chat.
 */
interface ConversationIndexMaintenance {

    /** Every chat that has passages or an index-state row. */
    fun indexedChatIds(): Set<String>

    /**
     * Removes all of [chatId]'s passages, keywords and index state, and every
     * vector no other passage uses (G.5a rule 3, "Deleting a chat removes
     * everything derived from it").
     */
    fun deleteChat(chatId: String)

    /**
     * Moves [fromChatId]'s entries to [toChatId] when a rename changes the
     * chat's ID (G.5a rule 9). Entries already under [toChatId] are replaced.
     * The moved chat is marked for a refresh, so it is compared again with
     * the chat as stored under its new ID. Vectors are kept.
     */
    fun moveChat(fromChatId: String, toChatId: String)

    /** Marks every indexed chat for a refresh, as after a restore (G.5a rule 8). */
    fun markAllForRefresh()

    /**
     * The user deletes the index (G.12): every passage, keyword, index-state
     * row and vector is removed. The chats are untouched.
     */
    fun deleteEverything()
}
