/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
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
}
