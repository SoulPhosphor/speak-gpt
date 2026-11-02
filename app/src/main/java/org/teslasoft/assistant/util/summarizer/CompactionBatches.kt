/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy

/**
 * Splits a compaction into requests of about the Compaction Average Token
 * Amount (owner ruling, Nov 2 2026). Messages are never cut: a user message
 * and the AI replies that follow it always travel together in one request.
 * A single exchange larger than the amount is sent alone, whole.
 */
object CompactionBatches {
    const val DEFAULT_TOKENS = 4000

    fun estimateTokens(entry: SummarizerController.Entry): Int =
        if (entry.text.isBlank()) 0 else IncludeTextPolicy.estimateTokens(entry.text)

    /** End (exclusive) of the exchange starting at [from]: the message at
     *  [from] plus every AI reply directly after it, up to [limit]. */
    fun exchangeEnd(entries: List<SummarizerController.Entry>, from: Int, limit: Int): Int {
        var end = from + 1
        while (end < limit && entries[end].isBot) end++
        return end
    }

    /**
     * End (exclusive) of the next request starting at [from]: whole
     * exchanges are added while the estimated total stays within
     * [tokenBudget]. Always at least one whole exchange.
     */
    fun batchEnd(
        entries: List<SummarizerController.Entry>,
        from: Int,
        limit: Int,
        tokenBudget: Int
    ): Int {
        var end = exchangeEnd(entries, from, limit)
        var tokens = (from until end).sumOf { estimateTokens(entries[it]) }
        while (end < limit) {
            val next = exchangeEnd(entries, end, limit)
            val nextTokens = (end until next).sumOf { estimateTokens(entries[it]) }
            if (tokens + nextTokens > tokenBudget) break
            tokens += nextTokens
            end = next
        }
        return end
    }

    /** Estimated tokens of entries [from] until [end]. */
    fun tokens(entries: List<SummarizerController.Entry>, from: Int, end: Int): Int =
        (from until end).sumOf { estimateTokens(entries[it]) }
}
