/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionBatchesTest {

    private fun user(chars: Int) = SummarizerController.Entry(false, "a".repeat(chars))
    private fun bot(chars: Int) = SummarizerController.Entry(true, "a".repeat(chars))

    @Test
    fun aUserMessageAndItsRepliesAreNeverSplit() {
        // Each exchange is ~100 tokens; a 150-token amount fits one exchange.
        val entries = listOf(user(200), bot(200), user(200), bot(200))
        assertEquals(2, CompactionBatches.batchEnd(entries, 0, entries.size, 150))
        assertEquals(4, CompactionBatches.batchEnd(entries, 2, entries.size, 150))
    }

    @Test
    fun anExchangeLargerThanTheAmountIsSentWhole() {
        val entries = listOf(user(400), bot(40_000), user(40))
        assertEquals(2, CompactionBatches.batchEnd(entries, 0, entries.size, 500))
    }

    @Test
    fun wholeExchangesAreAddedWhileTheyFit() {
        val entries = List(10) { if (it % 2 == 0) user(200) else bot(200) }
        // Five exchanges of ~100 tokens each; 300 fits three of them.
        assertEquals(6, CompactionBatches.batchEnd(entries, 0, entries.size, 300))
        assertEquals(10, CompactionBatches.batchEnd(entries, 0, entries.size, 100_000))
    }

    @Test
    fun severalRepliesStayWithTheirUserMessage() {
        val entries = listOf(user(40), bot(40), bot(40), bot(40), user(40))
        assertEquals(4, CompactionBatches.exchangeEnd(entries, 0, entries.size))
    }

    @Test
    fun theCompactionPointIsNeverCrossed() {
        val entries = listOf(user(40), bot(40), user(40), bot(40))
        assertEquals(3, CompactionBatches.batchEnd(entries, 0, 3, 100_000))
    }

    @Test
    fun aRejectedRequestAlwaysShrinks() {
        // A one-token exchange followed by blank (zero-token) messages: halving
        // the budget alone would pick the same messages again.
        val entries = listOf(user(4), bot(0), user(0), bot(0), user(0))
        val (end, _) = CompactionBatches.shrink(entries, 0, entries.size, entries.size)
        assertEquals(2, end)
        val mixed = List(10) { if (it % 2 == 0) user(200) else bot(200) }
        val (half, _) = CompactionBatches.shrink(mixed, 0, 10, 10)
        assertEquals(4, half)
    }

    @Test
    fun theDefaultAmountIs4000() {
        assertEquals(4000, CompactionBatches.DEFAULT_TOKENS)
    }

    @Test
    fun thePercentageComesFromMessagesDone() {
        fun running(requested: Int, done: Int) = SummarizerController.OperationState.Running(
            SummarizerController.OperationKind.COMPACTING, "Chat", requested, done
        )
        assertEquals(0, running(0, 0).percent)
        assertEquals(75, running(1000, 750).percent)
        assertEquals(100, running(40, 40).percent)
    }

    @Test
    fun oversizedMessageSplitsOnlyBetweenSentencesAndKeepsEveryCharacter() {
        val sentence = "A complete sentence. "
        val original = SummarizerController.Entry(false, sentence.repeat(20), "permanent-id", 123L)
        val pieces = CompactionBatches.splitMessage(original, 11)
        assertTrue(pieces.size > 1)
        assertEquals(original.text, pieces.joinToString("") { it.text })
        pieces.forEach {
            assertEquals(sentence.repeat(2), it.text)
            assertTrue(CompactionBatches.estimateTokens(it) <= 11)
            assertEquals(original.id, it.id)
            assertEquals(original.timeMillis, it.timeMillis)
            assertEquals(original.isBot, it.isBot)
        }
    }

    @Test
    fun messagesThatFitTheMaximumStayWholeEvenAboveTheAverage() {
        val original = SummarizerController.Entry(true, "One sentence. Another sentence. ", "reply")
        assertEquals(listOf(original), CompactionBatches.splitMessage(original, 100))
    }

    @Test
    fun aSentenceAboveTheMaximumGoesAloneAndIsNeverChopped() {
        val longSentence = "A " + "long ".repeat(100) + "sentence. "
        val original = SummarizerController.Entry(false, "Short. " + longSentence + "Last. ")
        val requests = CompactionBatches.boundedRequests(listOf(original), 10)
        assertEquals(listOf("Short. ", longSentence, "Last. "), requests.map { it.single().text })
        assertEquals(original.text, requests.flatten().joinToString("") { it.text })
    }

    @Test
    fun oversizedExchangeSeparatesWholeMessagesAndPreservesTheirRoles() {
        val entries = listOf(
            SummarizerController.Entry(false, "A user sentence. ".repeat(2), "user"),
            SummarizerController.Entry(true, "An assistant sentence. ".repeat(2), "assistant")
        )
        val requests = CompactionBatches.boundedRequests(entries, 12)
        assertEquals(2, requests.size)
        assertEquals(entries, requests.flatten())
    }

    @Test
    fun mixedUnicodeSentencesAreKeptLosslesslyInOrder() {
        val text = "Hello 🌈. Next sentence. 終わり。\n".repeat(20)
        val original = SummarizerController.Entry(true, text)
        val pieces = CompactionBatches.splitMessage(original, 20)
        assertEquals(text, pieces.joinToString("") { it.text })
        assertTrue(pieces.size > 1)
        pieces.forEach { piece ->
            assertTrue(!Character.isHighSurrogate(piece.text.last()))
            assertTrue(!Character.isLowSurrogate(piece.text.first()))
        }
    }

    @Test
    fun allowanceFinishesTheFinalExchangeButDoesNotFillUpToTheAllowance() {
        // Each exchange is 3,000 tokens: 4,000 may stretch to 6,000.
        val entries = List(6) { if (it % 2 == 0) user(6_000) else bot(6_000) }
        assertEquals(4, CompactionBatches.batchEnd(entries, 0, entries.size, 4_000, 2_000))
        // Once the average is reached, stop rather than treating 6,000 as the target.
        val exact = listOf(user(8_000), bot(8_000), user(2_000), bot(2_000))
        assertEquals(2, CompactionBatches.batchEnd(exact, 0, exact.size, 4_000, 2_000))
    }

    @Test
    fun allowanceIsAFlat2000AtHigherAverages() {
        val entries = listOf(user(20_000), bot(16_000), user(8_000), bot(8_000))
        // 9,000 + 4,000 must not fit a 10,000 target plus 2,000.
        assertEquals(2, CompactionBatches.batchEnd(entries, 0, entries.size, 10_000, 2_000))
        assertEquals(2_000, CompactionBatches.EXCHANGE_ALLOWANCE_TOKENS)
    }

    @Test
    fun oversizedPairSeparatesBetweenMessagesButDoesNotCutTheReplyAtTheAverage() {
        val entries = listOf(user(4_000), bot(24_000)) // 7,000 tokens together.
        val requests = CompactionBatches.boundedRequests(entries, 100_000, 6_000)
        assertEquals(listOf(listOf(entries[0]), listOf(entries[1])), requests)
        val hugeReply = listOf(user(4_000), bot(28_000))
        assertEquals(hugeReply, CompactionBatches.boundedRequests(hugeReply, 100_000, 6_000).flatten())
    }

}
