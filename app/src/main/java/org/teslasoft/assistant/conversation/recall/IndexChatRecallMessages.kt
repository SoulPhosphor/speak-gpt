/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import org.teslasoft.assistant.conversation.index.IndexChatSource
import org.teslasoft.assistant.conversation.index.IndexSourceMessage

/**
 * Recall reads the chat through the same reader the index uses
 * ([org.teslasoft.assistant.conversation.index.SavedChatIndexSource]): the
 * model-facing text, eligibility and scope of every message, never writing
 * to the chat. A chat with a save still in progress cannot be read, so recall
 * is skipped for that message rather than trusting an older copy.
 */
class IndexChatRecallMessages(private val source: IndexChatSource) : RecallMessageSource {
    override fun messages(chatId: String): List<IndexSourceMessage>? =
        (source.read(chatId) as? IndexChatSource.Read.Messages)?.messages

    override fun currentMessage(chatId: String, messageId: String): IndexSourceMessage? =
        source.currentMessage(chatId, messageId)
}
