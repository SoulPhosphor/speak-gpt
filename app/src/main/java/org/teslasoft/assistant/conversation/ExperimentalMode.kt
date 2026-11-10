/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.conversation

import org.teslasoft.assistant.preferences.includes.CanonicalConversationMessage

/**
 * Experimental Mode (owner ruling, Nov 10 2026): while it is on for a chat,
 * each request is built as if the chat were fresh and nothing had been sent
 * yet. Everything a fresh chat sends still goes (persona, system prompt,
 * model rules, memories, lorebooks, and the files attached to the current
 * message); only the earlier messages, and the summaries that stand in for
 * them, are left out. Every turn is still saved to the chat as usual, so
 * turning the mode off sends the whole chat again.
 *
 * The setting belongs to one chat: a new chat always starts with it off, and
 * returning to a chat where it was on finds it still on.
 */
object ExperimentalMode {

    /**
     * The conversation a request sends: unchanged when the mode is off;
     * otherwise only the newest user message, the one being sent.
     */
    fun requestConversation(
        canonical: List<CanonicalConversationMessage>,
        enabled: Boolean
    ): List<CanonicalConversationMessage> =
        requestEntries(canonical, enabled) { !it.isBot }

    /**
     * The same choice for any per-message list: all of [entries] when the mode
     * is off; otherwise only the newest entry [isUser] accepts.
     */
    fun <T> requestEntries(
        entries: List<T>,
        enabled: Boolean,
        isUser: (T) -> Boolean
    ): List<T> {
        if (!enabled) return entries
        val current = entries.indexOfLast(isUser)
        return if (current < 0) emptyList() else listOf(entries[current])
    }
}
