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

    /** Starting allowances from the November 18 scanner proposal: at most
     *  256 estimated tokens from one message and 768 in total per request.
     *  The Stage 6B setting will let the user choose the total. */
    const val EXCERPT_TOKENS_PER_MESSAGE = 256
    const val EXCERPT_TOKENS_TOTAL = 768

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
     * newest first while they fit, returned in chat order. A section that
     * starts inside a message begins with that message's earlier text. Each
     * message contributes at most its last [perMessage] tokens, cut after a
     * space where one exists; [total] bounds all of them together.
     */
    fun earlierExcerpts(
        chatMessages: List<ScanMessage>,
        first: ScanSourceRow,
        resolveScope: (ScanMessageScope?) -> ScanScopeResolution,
        perMessage: Int = EXCERPT_TOKENS_PER_MESSAGE,
        total: Int = EXCERPT_TOKENS_TOTAL,
        estimate: (String) -> Int = IncludeTextPolicy::estimateTokens
    ): List<ScanSourceRow> {
        if (total <= 0) return emptyList()
        val at = chatMessages.indexOfFirst { it.id == first.messageId }
        if (at < 0) return emptyList()
        val out = ArrayList<ScanSourceRow>()
        var left = total
        fun take(message: ScanMessage, end: Int): Boolean {
            val scope = resolveScope(message.scope) as? ScanScopeResolution.Read ?: return true
            val budget = minOf(perMessage, left)
            if (budget <= 0) return false
            val start = tailStart(message.text, end, budget, estimate)
            if (start >= end) return true
            val text = message.text.substring(start, end)
            if (text.isBlank()) return true
            out.add(
                ScanSourceRow(
                    chatId = first.chatId, messageId = message.id, messageNumber = message.number,
                    role = message.role, start = start, end = end, text = text,
                    startedAt = message.startedAt, modelTag = message.model,
                    companionId = scope.companionId, worldId = scope.worldId, campaignId = scope.campaignId,
                    roleplayCharacterId = scope.roleplayCharacterId, userPersonaId = scope.userPersonaId,
                    projectId = scope.projectId
                )
            )
            left -= estimate(text)
            return left > 0
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
