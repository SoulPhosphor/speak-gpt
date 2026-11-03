/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.memory.archivist

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.TranscriptRecord

class ArchivistRequestBudgetTest {

    @Test
    fun approvedChunkChoicesMapToTranscriptTokenTargets() {
        assertEquals(4_000, target(ArchivistRequestBudget.CHOICE_SMALL))
        assertEquals(8_000, target(ArchivistRequestBudget.CHOICE_STANDARD))
        assertEquals(16_000, target(ArchivistRequestBudget.CHOICE_LARGE))
        assertEquals(12_345, target(ArchivistRequestBudget.CHOICE_CUSTOM, 12_345))
    }

    @Test
    fun customRequiresAtLeastOneThousandTranscriptTokens() {
        assertNull(ArchivistRequestBudget.validateCustomTarget(null))
        assertNull(ArchivistRequestBudget.validateCustomTarget(999))
        assertEquals(1_000, ArchivistRequestBudget.validateCustomTarget(1_000)!!)
    }

    @Test
    fun autoWithoutVerifiedContextUsesStandardTarget() {
        assertEquals(
            ArchivistRequestBudget.STANDARD_TOKENS,
            ArchivistRequestBudget.effectiveTranscriptTarget(
                ArchivistRequestBudget.CHOICE_AUTO,
                null,
                verifiedContextTokens = null,
                requestHeadroomTokens = 20_000
            )
        )
    }

    @Test
    fun verifiedContextReservesCompleteRequestHeadroom() {
        assertEquals(
            7_000,
            ArchivistRequestBudget.effectiveTranscriptTarget(
                ArchivistRequestBudget.CHOICE_LARGE,
                null,
                verifiedContextTokens = 12_000,
                requestHeadroomTokens = 5_000
            )
        )
        assertEquals(
            7_000,
            ArchivistRequestBudget.effectiveTranscriptTarget(
                ArchivistRequestBudget.CHOICE_AUTO,
                null,
                verifiedContextTokens = 12_000,
                requestHeadroomTokens = 5_000
            )
        )
        assertTrue(
            ArchivistRequestBudget.headroomTokens("x".repeat(4_000)) >
                ArchivistRequestBudget.EXPECTED_OUTPUT_TOKENS
        )
    }

    @Test
    fun legacyTwoHundredThousandCharacterRequestFallbackIsGone() {
        val original = "Paragraph sentence. ".repeat(14_000)
        assertTrue(original.length > 200_000)
        val chunks = ArchivistConversationChunker.split(
            listOf(transcript(original)),
            ArchivistRequestBudget.STANDARD_TOKENS
        )
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all {
            it.estimatedTranscriptTokens <= ArchivistRequestBudget.STANDARD_TOKENS
        })
        val reconstructed = chunks
            .flatMap { it.transcripts }
            .joinToString("") { row ->
                JSONArray(row.content).getJSONObject(0).getString("content")
            }
        assertEquals(original, reconstructed)
    }

    @Test
    fun wholeMessagesStayWholeAndAnOversizedMessageUsesNaturalBreaks() {
        val first = "short complete message"
        val second = "A".repeat(1_100) + "\n\n" + "B".repeat(1_100) + ". " + "C".repeat(1_100)
        val row = transcript(first, second)
        val chunks = ArchivistConversationChunker.split(listOf(row), 300)
        val contents = chunks.flatMap { it.transcripts }.flatMap { splitRow ->
            val turns = JSONArray(splitRow.content)
            (0 until turns.length()).map { turns.getJSONObject(it).getString("content") }
        }
        assertEquals(first, contents.first())
        assertEquals(first + second, contents.joinToString(""))
    }

    @Test
    fun verifiedRejectionShrinksAndRetriesWithAStableBound() = runBlocking {
        var attempts = 0
        val outputs = ArchivistBoundedShrinkExecutor.execute(
            initial = 8,
            analyze = { value, _ ->
                attempts++
                if (value == 8) throw ArchivistShrinkRequiredException(4, "context")
                value
            },
            shouldShrink = ArchivistRetryPolicy::isVerifiedContextRejection,
            shrink = { value, _ -> listOf(value / 2, value / 2) }
        )
        assertEquals(listOf(4, 4), outputs)
        assertEquals(3, attempts)

        attempts = 0
        try {
            ArchivistBoundedShrinkExecutor.execute(
                initial = 8,
                analyze = { _, _ ->
                    attempts++
                    throw ArchivistShrinkRequiredException(1, "still too large")
                },
                shouldShrink = ArchivistRetryPolicy::isVerifiedContextRejection,
                shrink = { value, _ -> listOf(value / 2, value / 2) }
            )
        } catch (_: ArchivistShrinkRequiredException) {
            // expected
        }
        assertEquals(3, attempts)
    }

    @Test
    fun clearTruncationDetectionDoesNotMislabelCompleteOrEmptyExtraction() {
        assertTrue(
            ArchivistRetryPolicy.looksClearlyTruncated(
                "{\"memories\":[{\"content\":\"cut", "length"
            )
        )
        assertTrue(
            ArchivistRetryPolicy.looksClearlyTruncated(
                "{\"memories\":[{\"content\":\"cut", null
            )
        )
        assertFalse(
            ArchivistRetryPolicy.looksClearlyTruncated(
                "{\"memories\":[],\"model_rules\":[]}", "stop"
            )
        )
    }

    @Test
    fun scannerUsesFlatAllowanceAndKeepsTheFinalPairWhole() {
        val turns = JSONArray()
        repeat(3) {
            turns.put(JSONObject().put("role", "user").put("content", "u".repeat(6_000)))
            turns.put(JSONObject().put("role", "assistant").put("content", "a".repeat(6_000)))
        }
        // Each pair contains 3,000 source-text tokens: two fit 4,000 + 2,000.
        val row = transcript().copy(content = turns.toString())
        val chunks = ArchivistConversationChunker.split(listOf(row), 4_000, 100_000, 2_000)
        assertEquals(2, chunks.size)
        assertEquals(6_000, chunks.first().estimatedTranscriptTokens)
        assertEquals(4, JSONArray(chunks.first().transcripts.single().content).length())
        assertEquals(row.transcriptId, chunks.first().transcripts.single().transcriptId)
    }

    @Test
    fun scannerSeparatesAnOversizedPairWithoutSplittingIndividualMessages() {
        val turns = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "u".repeat(16_000)))
            .put(JSONObject().put("role", "assistant").put("content", "a".repeat(12_000)))
        val row = transcript().copy(content = turns.toString())
        val chunks = ArchivistConversationChunker.split(listOf(row), 4_000, 100_000, 2_000)
        assertEquals(2, chunks.size)
        val rebuilt = chunks.flatMap { it.transcripts }.flatMap {
            val data = JSONArray(it.content)
            (0 until data.length()).map { i -> data.getJSONObject(i).getString("content") }
        }
        assertEquals(listOf("u".repeat(16_000), "a".repeat(12_000)), rebuilt)
    }

    @Test
    fun scannerSentenceChunksKeepAllTextAndPermanentTranscriptIds() {
        val original = "A complete sentence. ".repeat(100)
        val row = transcript(original)
        val chunks = ArchivistConversationChunker.split(listOf(row), 20, 100, 2_000)
        assertTrue(chunks.size > 1)
        val rows = chunks.flatMap { it.transcripts }
        assertEquals(original, rows.joinToString("") { JSONArray(it.content).getJSONObject(0).getString("content") })
        rows.forEach { assertEquals(row.transcriptId, it.transcriptId) }
    }

    @Test
    fun scannerKeepsASentenceAboveTheMaximumWholeAndAlone() {
        val sentence = "A " + "very ".repeat(100) + "long sentence. "
        val row = transcript("Short. " + sentence + "Last. ")
        val chunks = ArchivistConversationChunker.split(listOf(row), 10, 20, 2_000)
        val messages = chunks.map { JSONArray(it.transcripts.single().content).getJSONObject(0).getString("content") }
        assertEquals(listOf("Short. ", sentence, "Last. "), messages)
    }

    private fun target(choice: String, custom: Int? = null): Int =
        ArchivistRequestBudget.effectiveTranscriptTarget(
            choice,
            custom,
            verifiedContextTokens = null,
            requestHeadroomTokens = 0
        )

    private fun transcript(vararg messages: String): TranscriptRecord {
        val turns = JSONArray()
        for (message in messages) {
            turns.put(JSONObject().put("role", "user").put("content", message))
        }
        return TranscriptRecord(
            transcriptId = "t-1",
            chatId = "chat-1",
            companionId = null,
            worldId = null,
            roleplayCharacterId = null,
            userPersonaId = null,
            source = "live",
            startedAt = "2026-08-10T00:00:00Z",
            endedAt = "2026-08-10T00:01:00Z",
            content = turns.toString(),
            modelTag = "model",
            quickSettingsJson = null,
            reviewStatus = "pending",
            processedAt = null
        )
    }
}
