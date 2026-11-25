/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import org.teslasoft.assistant.preferences.memory.librarian.LiveRetrieval

/**
 * Code-based follow-ups for conversation recall (memory development plan
 * G36, Owner 2026-11-25). Each chat remembers, in memory only, the exchanges
 * recall delivered with its latest message, so a real follow-up can be given
 * them again. Exchange IDs are never mixed with saved-memory IDs.
 *
 * - Set only after a message is actually sent with recalled exchanges
 *   ([delivered]; Step 16 calls it). A blocked or cancelled draft leaves no
 *   trace.
 * - At most [RecallTuning.maxCarriedExchanges] per chat and [MAX_CHATS]
 *   chats, most recently used kept. Never written to disk, so an app restart
 *   clears it.
 * - Cleared when the saved-memory subject changes, when the chat is deleted
 *   or renamed, and when the context-assistance setting changes.
 * - Every carried exchange is checked again against the chat as stored now
 *   each time ([RecallEngine]); a missing subject is never enough on its own
 *   to carry one ([followUpShare]).
 */
class RecallContinuity(private val tuning: RecallTuning = RecallTuning()) {

    private val entries = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > MAX_CHATS
    }

    /** The exchanges delivered with [chatId]'s latest message, or empty. */
    @Synchronized
    fun carried(chatId: String): List<String> = entries[chatId].orEmpty()

    /**
     * After a message was sent under Code-based context assistance: the
     * exchanges it delivered replace the chat's list, and a message that
     * delivered nothing clears it. A message that set a new saved-memory
     * subject never carries the old list ([RecallEngine]), so after it the
     * list holds only what it found itself.
     */
    @Synchronized
    fun delivered(chatId: String, exchangeIds: List<String>) {
        val kept = exchangeIds.distinct().take(tuning.maxCarriedExchanges)
        // A new subject with nothing delivered, or nothing delivered at all,
        // leaves nothing to continue; delivered exchanges replace the list.
        if (kept.isEmpty()) entries.remove(chatId) else entries[chatId] = kept
    }

    @Synchronized
    fun forget(chatId: String) {
        entries.remove(chatId)
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val MAX_CHATS = 64
        val shared = RecallContinuity()

        /**
         * Words that point back at an earlier subject without naming a new
         * one ("that one over the others", "explain the rest", "how does it
         * work"). They are left out when deciding whether a short message
         * names something other than a carried exchange. Compared after
         * [org.teslasoft.assistant.preferences.memory.librarian.KeywordText]
         * normalisation.
         */
        val FOLLOW_UP_WORDS = setOf(
            "one", "ones", "other", "else", "rest", "mean", "meant", "explain", "remind", "detail",
            "part", "bit", "exactly", "happen", "work", "reason", "decide", "decid", "choose", "chose",
            "chosen", "pick", "went", "again", "more", "back", "earlier", "before", "idea", "plan"
        )

        /** The message's own informative words, follow-up words left out. */
        fun subjectTerms(message: String): Set<String> =
            LiveRetrieval.queryTerms(message).filterNot { it in FOLLOW_UP_WORDS }.toSet()

        /**
         * The share of the message's own informative words found in a carried
         * exchange, or null when the message names nothing of its own (a pure
         * follow-up such as "and then?").
         */
        fun followUpShare(terms: Set<String>, inExchange: (String) -> Boolean): Double? {
            if (terms.isEmpty()) return null
            return terms.count(inExchange).toDouble() / terms.size
        }
    }
}
