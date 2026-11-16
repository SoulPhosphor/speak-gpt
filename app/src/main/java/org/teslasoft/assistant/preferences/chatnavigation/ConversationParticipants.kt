/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames
import org.teslasoft.assistant.ui.drawer.ChatListDetails

/**
 * Who took part in a conversation and when it ran, for the memory editor's
 * conversation lines (development plan M.3). Derived from the chat's own
 * messages and kept in [ChatListIndex], so the editor never reads the history.
 *
 * Names are the ones saved on each message when it was sent, so they stay as
 * the chat shows them even after a companion or Glamour is deleted. A message
 * sent before names were saved is kept as [UNNAMED]; [displayed] resolves it
 * and the chat's own username with the chat screen's own rules.
 */
data class ConversationParticipants(
    /** Saved user names, distinct, in order of first appearance. */
    val userNames: List<String> = emptyList(),
    /** Saved companion names on replies, distinct, in order of first appearance. */
    val companionNames: List<String> = emptyList(),
    /** The newest message time, epoch milliseconds. */
    val lastMessageAt: Long? = null,
    /** The first message's own time, epoch milliseconds; null when the first
     *  message has none (never a later message's time). */
    val firstMessageAt: Long? = null
) {
    companion object {
        /** Stands for a message that has no saved name. */
        const val UNNAMED = ""

        fun fromMessages(messages: List<Map<String, *>>): ConversationParticipants {
            val users = LinkedHashSet<String>()
            val companions = LinkedHashSet<String>()
            var first: Long? = null
            var last: Long? = null
            var seenFirst = false
            for (message in messages) {
                // Image placeholders are not messages anyone said.
                if (message[ChatAdapter.KEY_IMAGE_CONFIRMATION] == true ||
                    message[ChatAdapter.KEY_IMAGE_PROGRESS] == true) continue
                val isBot = message["isBot"] == true || message["isBot"]?.toString() == "true"
                val key = if (isBot) ChatSpeakerNames.COMPANION_NAME_KEY else ChatSpeakerNames.USER_NAME_KEY
                val name = message[key]?.toString()?.trim().orEmpty()
                (if (isBot) companions else users).add(name.ifEmpty { UNNAMED })
                // Each date is that exact message's own time: a first or newest
                // message without one gives no date, never a neighbor's.
                val time = ChatListDetails.messageTime(message)
                if (!seenFirst) {
                    first = time
                    seenFirst = true
                }
                last = time
            }
            return ConversationParticipants(users.toList(), companions.toList(), last, first)
        }

        /**
         * The names shown for each role, applying the chat screen's rules: the
         * chat's own username replaces every user name; an unnamed reply shows
         * the chat's current companion, else "Assistant"; an unnamed user
         * message shows "User". Distinct, in order of first appearance.
         */
        fun displayed(
            names: List<String>,
            resolveUnnamed: () -> String,
            override: String? = null
        ): List<String> {
            if (names.isEmpty()) return emptyList()
            override?.trim()?.takeIf { it.isNotEmpty() }?.let { return listOf(it) }
            return names.map { if (it == UNNAMED) resolveUnnamed() else it }.distinct()
        }
    }
}
