/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import java.util.concurrent.ConcurrentHashMap

/**
 * No summary update starts while the user is reviewing a chat's summary or
 * compacted text (owner ruling, Oct 3 2026). The Summary / Compaction
 * review screen holds the gate open for its chat. An update that would have run
 * by the next exchange runs as the screen opens instead, so the user reviews
 * current text; any update held back runs once the screen closes.
 */
object SummarizerReviewGate {
    /** Messages one exchange adds: the user's message and the reply. */
    private const val MESSAGES_PER_EXCHANGE = 2

    private val open = ConcurrentHashMap.newKeySet<String>()

    fun open(chatId: String, screen: String) {
        if (chatId.isNotBlank()) open.add("$chatId\u0000$screen")
    }

    fun close(chatId: String, screen: String) {
        open.remove("$chatId\u0000$screen")
    }

    fun isOpen(chatId: String): Boolean = open.any { it.startsWith("$chatId\u0000") }

    /**
     * True when messages already wait past the Complete Messages window and
     * the batch would be full by the next exchange, so it runs now instead.
     */
    fun runsBeforeReview(storedMessages: Int, window: Int, folded: Int): Boolean {
        val edge = (storedMessages - window.coerceAtLeast(1)).coerceAtLeast(0)
        val pending = edge - folded
        return pending > 0 && pending + MESSAGES_PER_EXCHANGE >= SummarizerController.BATCH_SIZE
    }

    const val CONVERSATION_SUMMARY = "conversation_summary"
}
