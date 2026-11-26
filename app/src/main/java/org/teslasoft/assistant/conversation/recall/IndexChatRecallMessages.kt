/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import org.teslasoft.assistant.conversation.index.IndexChatSource
import org.teslasoft.assistant.preferences.ChatHistoryWriteOrder

/**
 * Recall reads the chat through the same reader the index uses
 * ([org.teslasoft.assistant.conversation.index.SavedChatIndexSource]): the
 * model-facing text, eligibility and scope of every message, never writing
 * to the chat. A chat with a save still in progress cannot be read, so recall
 * is skipped for that message rather than trusting an older copy.
 *
 * A read's version is the chat's stored version and the count of changes
 * reported while the app runs, both taken before the messages were read, so a
 * save that lands during a search, or is still on its way, is seen.
 */
class IndexChatRecallMessages(
    private val source: IndexChatSource,
    private val savePending: (String) -> Boolean = { ChatHistoryWriteOrder.shared.hasPending(it) }
) : RecallMessageSource {
    override fun read(chatId: String): ChatSnapshot? =
        (source.read(chatId) as? IndexChatSource.Read.Messages)?.let { ChatSnapshot(it.messages, version(it.version, it.changeCount)) }

    override fun changedSince(chatId: String, snapshot: ChatSnapshot): Boolean =
        savePending(chatId) || version(source.chatVersion(chatId), source.changeCount(chatId)) != snapshot.version

    private fun version(stored: String, changes: Long) = "$stored|$changes"
}
