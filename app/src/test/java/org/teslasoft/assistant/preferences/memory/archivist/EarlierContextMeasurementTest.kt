/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.archivist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import org.teslasoft.assistant.preferences.memory.scan.ScanContext
import org.teslasoft.assistant.preferences.memory.scan.ScanMessage
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRows
import org.teslasoft.assistant.util.ConversationTokenChunks
import java.io.File

/**
 * Stage 6B.1 measurement on the Stage 6 test conversations: how many scanner
 * requests each chat needs, how much of each request is new source and how
 * much is repeated earlier text under the Earlier Context defaults (2,000
 * average, 4,000 maximum). Section targets below the 8,000 default stand in
 * for long chats and tight models. No model is called. The numbers are
 * printed to the test report; the assertions hold the approved limits.
 */
class EarlierContextMeasurementTest {

    private fun chats(): List<Pair<String, List<ScanMessage>>> {
        val file = listOf(
            File("src/test/resources/recall_test_set/conversations.json"),
            File("app/src/test/resources/recall_test_set/conversations.json")
        ).first { it.exists() }
        val chats = JSONObject(file.readText()).getJSONArray("chats")
        return (0 until chats.length()).map { i ->
            val chat = chats.getJSONObject(i)
            val messages = chat.getJSONArray("messages")
            chat.getString("id") to (0 until messages.length()).map { k ->
                val m = messages.getJSONObject(k)
                ScanMessage(
                    id = m.getString("id"), number = (k + 1).toLong(),
                    role = if (m.getString("author") == "user") "user" else "assistant",
                    text = m.getString("text"), eligible = true, scope = null,
                    timeMillis = null, model = null, fingerprint = m.getString("text")
                )
            }
        }
    }

    private data class Totals(var requests: Int = 0, var withEarlier: Int = 0, var newTokens: Int = 0,
                              var earlierTokens: Int = 0, var largestEarlier: Int = 0)

    @Test fun earlierContextStaysWithinTheDefaultsAndEveryMessageIsStillRead() {
        val average = ScanContext.DEFAULT_EARLIER_AVERAGE_TOKENS
        val maximum = ScanContext.DEFAULT_EARLIER_MAXIMUM_TOKENS
        val report = StringBuilder("Earlier Context measurement (average $average, maximum $maximum)\n")
        for (target in listOf(8_000, 2_000, 1_000)) {
            val totals = Totals()
            for ((chatId, messages) in chats()) {
                val rows = ScanSourceRows.build(chatId, messages, 0, 0) { ScanSourceRows.GENERAL }
                val chunks = ArchivistConversationChunker.split(
                    rows, target, ConversationTokenChunks.DEFAULT_MAXIMUM_TOKENS,
                    minOf(ConversationTokenChunks.EXCHANGE_ALLOWANCE_TOKENS, target)
                )
                // Every message is read exactly once, whatever the earlier text.
                assertEquals(rows.map { it.messageId }, chunks.flatMap { c -> c.rows.map { it.messageId } })
                for (chunk in chunks) {
                    val earlier = ScanContext.earlierExcerpts(
                        messages, chunk.rows.first(), { ScanSourceRows.GENERAL }, average, maximum
                    )
                    val earlierTokens = earlier.sumOf { IncludeTextPolicy.estimateTokens(it.text) }
                    assertTrue(earlierTokens <= maximum)
                    val newTokens = IncludeTextPolicy.estimateTokens(
                        ArchivistPrompt.userMessage(chatId, null, chunk.rows).text
                    )
                    totals.requests++
                    if (earlier.isNotEmpty()) totals.withEarlier++
                    totals.newTokens += newTokens
                    totals.earlierTokens += earlierTokens
                    totals.largestEarlier = maxOf(totals.largestEarlier, earlierTokens)
                }
            }
            val perRequest = if (totals.withEarlier == 0) 0 else totals.earlierTokens / totals.withEarlier
            report.append(
                "section target $target: ${totals.requests} requests, ${totals.withEarlier} with earlier text; " +
                    "new source ${totals.newTokens} tokens; earlier text ${totals.earlierTokens} tokens " +
                    "(average $perRequest, largest ${totals.largestEarlier} per request); " +
                    "added input ${if (totals.newTokens == 0) 0 else totals.earlierTokens * 100 / totals.newTokens}%\n"
            )
        }
        println(report)
    }
}
