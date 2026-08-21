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
                completionTokens = 20, totalTokens = 30)
        )
        val result = attempt.snapshot()
        assertEquals("actual-model", result.model)
        assertEquals("DeepInfra", result.provider)
        assertEquals("https://endpoint", result.apiEndpoint)
        assertEquals(TokenCounts(10, 20, 30), result.counts)
    }
}
