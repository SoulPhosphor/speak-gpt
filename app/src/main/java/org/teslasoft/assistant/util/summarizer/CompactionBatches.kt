/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import java.text.BreakIterator
import java.util.Locale

/**
 * Splits a compaction into requests of about the Compaction Average Token
 * Amount. Whole exchanges stay together up to the maximum. Only messages
 * over the maximum are split, at sentence boundaries without dropping text.
 * A sentence over the maximum is sent alone and intact (owner, Nov 3 2026).
 */
object CompactionBatches {
    const val DEFAULT_TOKENS = 4000
    const val DEFAULT_MAXIMUM_TOKENS = 100_000
    const val EXCHANGE_ALLOWANCE_TOKENS = 2_000

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
     * exchanges are added until [tokenBudget] is reached. The final exchange
     * may use [overrunTokens] extra tokens. Always at least one whole exchange;
     * the caller separates an exchange larger than the allowed request size.
     */
    fun batchEnd(
        entries: List<SummarizerController.Entry>,
        from: Int,
        limit: Int,
        tokenBudget: Int,
        overrunTokens: Int = 0
    ): Int {
        val limitTokens = tokenBudget.toLong() + overrunTokens.coerceAtLeast(0)
        var end = exchangeEnd(entries, from, limit)
        var tokenCount = tokens(entries, from, end)
        while (end < limit && tokenCount < tokenBudget) {
            val next = exchangeEnd(entries, end, limit)
            val nextTokens = tokens(entries, end, next)
            if (tokenCount.toLong() + nextTokens > limitTokens) break
            tokenCount += nextTokens
            end = next
        }
        return end
    }

    /**
     * A smaller request after the service called [from] until [end] too
     * large: about half its estimated tokens in whole exchanges. Always ends
     * strictly before [end] (at least one whole exchange), so a retry never
     * repeats the rejected request. Returns the new end and token amount.
     */
    fun shrink(
        entries: List<SummarizerController.Entry>,
        from: Int,
        end: Int,
        limit: Int
    ): Pair<Int, Int> {
        val budget = tokens(entries, from, end) / 2
        val smaller = batchEnd(entries, from, limit, budget)
        val newEnd = if (smaller < end) smaller else exchangeEnd(entries, from, limit)
        return newEnd to budget
    }

    /**
     * Split only an oversized message. BreakIterator supplies sentence ends,
     * including their whitespace; substring ranges cover the exact input once.
     * Counts match IncludeTextPolicy without repeatedly recounting the growing
     * chunk. An oversized sentence is the sole exception to the maximum.
     */
    fun splitMessage(entry: SummarizerController.Entry, maximumTokens: Int): List<SummarizerController.Entry> {
        val maximum = maximumTokens.coerceAtLeast(1)
        if (estimateTokens(entry) <= maximum) return listOf(entry)
        val text = entry.text
        val sentences = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
        val chunks = ArrayList<SummarizerController.Entry>()
        var chunkStart = 0
        var sentenceStart = sentences.first()
        var ascii = 0L
        var other = 0L
        var sentenceEnd = sentences.next()
        while (sentenceEnd != BreakIterator.DONE) {
            var sentenceAscii = 0L
            var sentenceOther = 0L
            var offset = sentenceStart
            while (offset < sentenceEnd) {
                val codePoint = text.codePointAt(offset)
                if (codePoint <= 0x7F) sentenceAscii++
                else sentenceOther += Character.charCount(codePoint)
                offset += Character.charCount(codePoint)
            }
            val candidateTokens = (ascii + sentenceAscii + 3) / 4 + other + sentenceOther
            if (candidateTokens > maximum && sentenceStart > chunkStart) {
                chunks.add(entry.copy(text = text.substring(chunkStart, sentenceStart)))
                chunkStart = sentenceStart
                ascii = 0
                other = 0
            }
            ascii += sentenceAscii
            other += sentenceOther
            if ((ascii + 3) / 4 + other > maximum) {
                // One sentence alone is oversized. Never cut it or add more to it.
                chunks.add(entry.copy(text = text.substring(chunkStart, sentenceEnd)))
                chunkStart = sentenceEnd
                ascii = 0
                other = 0
            }
            sentenceStart = sentenceEnd
            sentenceEnd = sentences.next()
        }
        if (chunkStart < text.length) chunks.add(entry.copy(text = text.substring(chunkStart)))
        return chunks
    }

    /**
     * Divide one oversized exchange between messages at [requestTokens]. A
     * message stays whole unless it individually exceeds [maximumTokens], even
     * if it exceeds the smaller average-plus-allowance target. Fragments keep
     * their original role, ID and time and never reach persisted chat history.
     */
    fun boundedRequests(
        entries: List<SummarizerController.Entry>,
        maximumTokens: Int,
        requestTokens: Int = maximumTokens
    ): List<List<SummarizerController.Entry>> {
        val maximum = maximumTokens.coerceAtLeast(1)
        val requestLimit = requestTokens.coerceIn(1, maximum)
        val requests = ArrayList<List<SummarizerController.Entry>>()
        var current = ArrayList<SummarizerController.Entry>()
        var count = 0L
        for (entry in entries) {
            for (piece in splitMessage(entry, maximum)) {
                val next = estimateTokens(piece).toLong()
                if (current.isNotEmpty() && count + next > requestLimit) {
                    requests.add(current)
                    current = ArrayList()
                    count = 0
                }
                current.add(piece)
                count += next
                if (count >= requestLimit) {
                    requests.add(current)
                    current = ArrayList()
                    count = 0
                }
            }
        }
        if (current.isNotEmpty()) requests.add(current)
        return requests
    }

    /** Estimated tokens of entries [from] until [end]. */
    fun tokens(entries: List<SummarizerController.Entry>, from: Int, end: Int): Int =
        (from until end).sumOf { estimateTokens(entries[it]).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
