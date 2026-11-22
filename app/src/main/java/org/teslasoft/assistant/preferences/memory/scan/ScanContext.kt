/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy

/**
 * The scanner AI's view of where a section sits in its conversation (owner
 * decision, Stage 6A question 10):
 *
 *  - [labelParts] marks the pieces of a message too long for one request as
 *    "part k of n" of that message. The parts come from splitting the whole
 *    message at the given maximum, so a piece keeps its label however the
 *    reading was divided into sections or resumed. Message numbers never
 *    change; part numbers are only temporary labels.
 *  - [earlierExcerpts] rereads, from the chat, the messages just before a
 *    section as bounded context: previously analyzed material that helps the
 *    AI understand, correct or update working memories. It is held in
 *    working memory for one request only.
 */
object ScanContext {

    /** Defaults of the Earlier Context token settings on Memory Scanner
     *  Tool Settings (owner decision, Stage 6B.1). */
    const val DEFAULT_EARLIER_AVERAGE_TOKENS = 2_000
    const val DEFAULT_EARLIER_MAXIMUM_TOKENS = 4_000

    /** [rows] with each piece of a split message labeled as its part. */
    fun labelParts(
        rows: List<ScanSourceRow>,
        messageText: (String) -> String?,
        maximumTokens: Int,
        estimate: (String) -> Int = IncludeTextPolicy::estimateTokens
    ): List<ScanSourceRow> {
        val cache = HashMap<String, List<ScanTextSplitter.Piece>>()
        return rows.map { row ->
            val text = messageText(row.messageId) ?: return@map row
            val pieces = cache.getOrPut(row.messageId) { ScanTextSplitter.split(text, maximumTokens, estimate) }
            if (pieces.size <= 1) return@map row.copy(partIndex = 1, partCount = 1)
            val index = pieces.indexOfFirst { row.start >= it.start && row.start < it.end }
                .takeIf { it >= 0 } ?: pieces.lastIndex
            row.copy(partIndex = index + 1, partCount = pieces.size)
        }
    }

    /**
     * Earlier eligible messages before [first] (the section's first piece),
     * newest first, returned in chat order. A section that starts inside a
     * message begins with that message's earlier text. Whole messages are
     * added until about [average] tokens are reached; the average may be
     * exceeded to finish a message, but [maximum] never is. A message is cut
     * (keeping its end, after a space where one exists) only when it cannot
     * fit whole under the maximum, and nothing older is added after it.
     */
    fun earlierExcerpts(
        chatMessages: List<ScanMessage>,
        first: ScanSourceRow,
        resolveScope: (ScanMessageScope?) -> ScanScopeResolution,
        average: Int = DEFAULT_EARLIER_AVERAGE_TOKENS,
        maximum: Int = DEFAULT_EARLIER_MAXIMUM_TOKENS,
        estimate: (String) -> Int = IncludeTextPolicy::estimateTokens
    ): List<ScanSourceRow> {
        val ceiling = maxOf(average, maximum)
        if (average <= 0 || ceiling <= 0) return emptyList()
        val at = chatMessages.indexOfFirst { it.id == first.messageId }
        if (at < 0) return emptyList()
        val out = ArrayList<ScanSourceRow>()
        var used = 0
        fun row(message: ScanMessage, scope: ScanScopeResolution.Read, start: Int, end: Int) = ScanSourceRow(
            chatId = first.chatId, messageId = message.id, messageNumber = message.number,
            role = message.role, start = start, end = end, text = message.text.substring(start, end),
            startedAt = message.startedAt, modelTag = message.model,
            companionId = scope.companionId, worldId = scope.worldId, campaignId = scope.campaignId,
            roleplayCharacterId = scope.roleplayCharacterId, userPersonaId = scope.userPersonaId,
            projectId = scope.projectId
        )
        /** False once nothing more may be added. */
        fun take(message: ScanMessage, end: Int): Boolean {
            val scope = resolveScope(message.scope) as? ScanScopeResolution.Read ?: return true
            if (end <= 0 || message.text.substring(0, end).isBlank()) return true
            val whole = estimate(message.text.substring(0, end))
            if (used + whole <= ceiling) {
                out.add(row(message, scope, 0, end))
                used += whole
                return used < average
            }
            val start = tailStart(message.text, end, ceiling - used, estimate)
            if (start < end && message.text.substring(start, end).isNotBlank()) {
                out.add(row(message, scope, start, end))
            }
            return false
        }
        if (first.start > 0 && chatMessages[at].eligible) {
            if (!take(chatMessages[at], first.start)) return out.reversed()
        }
        for (index in at - 1 downTo 0) {
            val message = chatMessages[index]
            if (!message.eligible) continue
            if (!take(message, message.text.length)) break
        }
        return out.reversed()
    }

    /** The start of the longest tail of text[0, end) within [budget] tokens,
     *  moved forward to just after a space when the cut would split a word. */
    private fun tailStart(text: String, end: Int, budget: Int, estimate: (String) -> Int): Int {
        var low = 0
        var high = end
        while (low < high) {
            val mid = (low + high) ushr 1
            if (estimate(text.substring(mid, end)) <= budget) high = mid else low = mid + 1
        }
        if (low == 0 || text[low - 1].isWhitespace()) return low
        val space = (low until end).firstOrNull { text[it].isWhitespace() } ?: return low
        return space + 1
    }
}
