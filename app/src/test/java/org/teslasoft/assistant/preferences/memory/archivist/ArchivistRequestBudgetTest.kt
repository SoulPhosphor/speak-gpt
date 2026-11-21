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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRow

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
            listOf(row("m1", "user", original)),
            ArchivistRequestBudget.STANDARD_TOKENS
        )
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all {
            it.estimatedTranscriptTokens <= ArchivistRequestBudget.STANDARD_TOKENS
        })
        assertEquals(original, chunks.flatMap { it.rows }.joinToString("") { it.text })
    }

    @Test
    fun wholeMessagesStayWholeAndAnOversizedMessageUsesNaturalBreaks() {
        val first = "short complete message"
        val second = "A".repeat(1_100) + "\n\n" + "B".repeat(1_100) + ". " + "C".repeat(1_100)
        val chunks = ArchivistConversationChunker.split(
            listOf(row("m1", "user", first), row("m2", "user", second)), 300
        )
        val rows = chunks.flatMap { it.rows }
        assertEquals(first, rows.first().text)
        assertEquals(first + second, rows.joinToString("") { it.text })
        // The long message breaks after its paragraph break first.
        val secondParts = rows.filter { it.messageId == "m2" }
        assertTrue(secondParts.size > 1)
        assertTrue(secondParts.any { it.text.endsWith("\n\n") })
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
        val rows = (1..3).flatMap { i ->
            listOf(row("u$i", "user", "u".repeat(6_000)), row("a$i", "assistant", "a".repeat(6_000)))
        }
        // Each pair contains 3,000 source-text tokens: two fit 4,000 + 2,000.
        val chunks = ArchivistConversationChunker.split(rows, 4_000, 100_000, 2_000)
        assertEquals(2, chunks.size)
        assertEquals(6_000, chunks.first().estimatedTranscriptTokens)
        assertEquals(listOf("u1", "a1", "u2", "a2"), chunks.first().rows.map { it.messageId })
    }

    @Test
    fun scannerSeparatesAnOversizedPairWithoutSplittingMessagesUnderTheMaximum() {
        val rows = listOf(
            row("u", "user", "u".repeat(16_000)),
            row("a", "assistant", "a".repeat(12_000))
        )
        val chunks = ArchivistConversationChunker.split(rows, 4_000, 100_000, 2_000)
        assertEquals(2, chunks.size)
        assertEquals(listOf("u".repeat(16_000), "a".repeat(12_000)), chunks.flatMap { it.rows }.map { it.text })
    }

    @Test
    fun fragmentsKeepTheirMessageIdNumberAndContiguousOffsets() {
        val original = "A complete sentence. ".repeat(100)
        val chunks = ArchivistConversationChunker.split(listOf(row("m7", "user", original)), 20, 100, 2_000)
        assertTrue(chunks.size > 1)
        val parts = chunks.flatMap { it.rows }
        assertEquals(original, parts.joinToString("") { it.text })
        var expectedStart = 0
        parts.forEachIndexed { i, part ->
            assertEquals("m7", part.messageId)
            assertEquals(7L, part.messageNumber)
            assertEquals(expectedStart, part.start)
            assertEquals(original.substring(part.start, part.end), part.text)
            assertEquals(i + 1, part.partIndex)
            assertEquals(parts.size, part.partCount)
            expectedStart = part.end
        }
        assertEquals(original.length, expectedStart)
    }

    @Test
    fun aSentenceAboveTheMaximumIsSplitBetweenWordsWithoutLosingText() {
        val sentence = "A " + "very ".repeat(100) + "long sentence without a period"
        val chunks = ArchivistConversationChunker.split(listOf(row("m1", "user", sentence)), 10, 20, 2_000)
        val parts = chunks.flatMap { it.rows }.map { it.text }
        assertTrue(parts.size > 1)
        assertEquals(sentence, parts.joinToString(""))
        // Every cut is right after a space: no word is broken.
        parts.dropLast(1).forEach { assertTrue(it.endsWith(" ")) }
    }

    @Test
    fun retryShrinkLowersTheSingleMessageMaximumToo() {
        // The retry passes its smaller target as the maximum as well, so a
        // message that fit the old maximum is split for the retry.
        val text = "word ".repeat(2_000)
        val whole = ArchivistConversationChunker.split(listOf(row("m1", "user", text)), 4_000, 4_000)
        assertEquals(1, whole.flatMap { it.rows }.size)
        val retry = ArchivistConversationChunker.split(whole.single().rows, 500, 500)
        assertTrue(retry.flatMap { it.rows }.size > 1)
        assertEquals(text, retry.flatMap { it.rows }.joinToString("") { it.text })
    }

    private fun target(choice: String, custom: Int? = null): Int =
        ArchivistRequestBudget.effectiveTranscriptTarget(
            choice,
            custom,
            verifiedContextTokens = null,
            requestHeadroomTokens = 0
        )

    private fun row(id: String, role: String, text: String) = ScanSourceRow(
        chatId = "chat-1",
        messageId = id,
        messageNumber = id.filter { it.isDigit() }.toLongOrNull(),
        role = role,
        start = 0,
        end = text.length,
        text = text,
        startedAt = "2026-08-10T00:00:00Z",
        modelTag = "model"
    )
}
