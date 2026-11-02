package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.*
import org.junit.Test

class SummarizerRequestSizeTest {
    @Test fun capturesTheConfiguredValueAndTheSmallerRetryIndependently() {
        val size = SummarizerRequestSize.capture(
            "instructions " + "a".repeat(80), listOf("a".repeat(40)),
            existingSummary = "b".repeat(20), configuredBatchTokens = 2300, attemptBatchTokens = 1100
        )
        assertEquals(2300, size.configuredBatchTokens)
        assertEquals(1100, size.attemptBatchTokens)
        assertEquals(10, size.estimatedBatchTokens)
        assertEquals(5, size.estimatedExistingSummaryTokens)
        assertEquals(93, size.requestTextCharacters)
        assertTrue(size.estimatedRequestTokens > size.estimatedBatchTokens)
    }

    @Test fun anOversizedWholeExchangeIsMeasuredWithoutClampingToTheSetting() {
        val text = "x".repeat(12000)
        val size = SummarizerRequestSize.capture(text, listOf(text),
            configuredBatchTokens = 2000, attemptBatchTokens = 2000)
        assertEquals(3000, size.estimatedBatchTokens)
        assertEquals(3000, size.estimatedRequestTokens)
    }

    @Test fun automaticSummaryDoesNotClaimToUseTheCompactionTokenSetting() {
        val size = SummarizerRequestSize.capture("instructions and context", listOf("new text"))
        assertNull(size.configuredBatchTokens)
        assertNull(size.attemptBatchTokens)
        assertEquals(1, size.batchMessages)
        assertFalse(size.format().contains("Batch Token Amount"))
    }

    @Test fun diagnosticsContainCountsWithoutRetainingPrivateText() {
        val privateText = "confidential conversation text"
        val size = SummarizerRequestSize.capture(privateText, listOf(privateText))
        assertFalse(size.format().contains(privateText))
        assertTrue(size.format().contains("Output Token Limit: Not Sent"))
    }
}
