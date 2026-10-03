/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerReviewGateTest {

    @Test
    fun anUpdateDueByTheNextExchangeRunsBeforeReview() {
        // Window 20, 28 stored, 0 folded: 8 waiting, 10 after one exchange.
        assertTrue(SummarizerReviewGate.runsBeforeReview(storedMessages = 28, window = 20, folded = 0))
        // Already a full batch waiting.
        assertTrue(SummarizerReviewGate.runsBeforeReview(storedMessages = 40, window = 20, folded = 0))
    }

    @Test
    fun anUpdateNotYetDueDoesNotStart() {
        assertFalse(SummarizerReviewGate.runsBeforeReview(storedMessages = 27, window = 20, folded = 0))
        assertFalse(SummarizerReviewGate.runsBeforeReview(storedMessages = 20, window = 20, folded = 0))
        assertFalse(SummarizerReviewGate.runsBeforeReview(storedMessages = 30, window = 20, folded = 10))
    }

    @Test
    fun theGateIsHeldPerChatAndScreen() {
        SummarizerReviewGate.open("chat-a", SummarizerReviewGate.SUMMARY_VIEW)
        SummarizerReviewGate.open("chat-a", SummarizerReviewGate.COMPACTION_SUMMARY)
        assertTrue(SummarizerReviewGate.isOpen("chat-a"))
        assertFalse(SummarizerReviewGate.isOpen("chat-b"))
        SummarizerReviewGate.close("chat-a", SummarizerReviewGate.SUMMARY_VIEW)
        assertTrue(SummarizerReviewGate.isOpen("chat-a"))
        SummarizerReviewGate.close("chat-a", SummarizerReviewGate.COMPACTION_SUMMARY)
        assertFalse(SummarizerReviewGate.isOpen("chat-a"))
    }
}
