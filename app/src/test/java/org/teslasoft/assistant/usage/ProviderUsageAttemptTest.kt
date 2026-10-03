package org.teslasoft.assistant.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.RawStreamObservation

class ProviderUsageAttemptTest {
    @Test fun responseReportedAttributionAndUsageOverrideRequestFallbacks() = runBlocking {
        val attempt = ProviderUsageAttempt("requested-model", "Configured Provider", "https://endpoint")
        attempt.noteTypedUsage(10, 20, 30)
        attempt.noteProvider("DeepInfra")
        attempt.noteRawObservation(
            RawStreamObservation(model = "actual-model", promptTokens = 10,
                completionTokens = 20, totalTokens = 30,
                cachedInputTokens = 6, cacheWriteInputTokens = 0,
                totalCost = 0.0042)
        )
        val result = attempt.snapshot()
        assertEquals("actual-model", result.model)
        assertEquals("DeepInfra", result.provider)
        assertEquals("https://endpoint", result.apiEndpoint)
        assertEquals(TokenCounts(10, 20, 30, 6, 0), result.counts)
        assertEquals(0.0042, result.providerCost.totalCost!!, 0.000000001)
    }

    @Test fun reasoningReportedOutsideCompletionIsCountedAsOutput() = runBlocking {
        val attempt = ProviderUsageAttempt("grok-4", "xAI", "https://api.x.ai/v1/")
        attempt.noteTypedUsage(100, 20, 150)
        attempt.noteRawObservation(
            RawStreamObservation(promptTokens = 100, completionTokens = 20,
                totalTokens = 150, reasoningOutputTokens = 30)
        )
        assertEquals(50, attempt.snapshot().counts.outputTokens)
    }

    @Test fun reasoningAlreadyInsideCompletionIsNotCountedTwice() {
        assertEquals(50, outputIncludingReasoning(100, 50, 150, 30))
        assertEquals(50, outputIncludingReasoning(100, 50, null, 30))
        assertEquals(50, outputIncludingReasoning(100, 50, 150, null))
    }
}
