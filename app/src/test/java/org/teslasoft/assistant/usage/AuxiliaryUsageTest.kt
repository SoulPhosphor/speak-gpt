package org.teslasoft.assistant.usage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.providers.ReportedProviderParser

class AuxiliaryUsageTest {
    private fun attempt() = ProviderUsageAttempt("vendor/model", "Not Reported", "https://openrouter.ai/api/v1")

    private val completedBody = """
        {
          "id": "gen-1",
          "model": "vendor/model",
          "provider": "Provider A",
          "choices": [{"message": {"role": "assistant", "content": "Notes"}, "finish_reason": "stop"}],
          "usage": {"prompt_tokens": 1200, "completion_tokens": 80, "total_tokens": 1280,
                    "prompt_tokens_details": {"cached_tokens": 200}, "cost": 0.0042}
        }
    """.trimIndent()

    @Test fun `a complete response body yields its reported usage and charge`() {
        val observation = ReportedProviderParser.observeCompletedBody(completedBody)!!

        assertEquals(1200, observation.promptTokens)
        assertEquals(80, observation.completionTokens)
        assertEquals(200, observation.cachedInputTokens)
        assertEquals(0.0042, observation.totalCost!!, 1e-12)
        assertNull(ReportedProviderParser.observeCompletedBody("not json"))
    }

    @Test fun `a completed request is recorded from the provider's report with frozen pricing`() = runBlocking {
        val attempt = attempt()
        attempt.noteRawObservation(ReportedProviderParser.observeCompletedBody(completedBody)!!)
        val pricing = CompletableDeferred(TokenPricingCatalog("vendor/model"))

        val record = AuxiliaryUsage.record(attempt, pricing, succeeded = true) { null }!!

        assertEquals(1200, record.inputTokens)
        assertEquals(80, record.outputTokens)
        assertEquals(200, record.cachedInputTokens)
        assertEquals(0.0042, record.totalCost!!, 1e-12)
        assertEquals(TokenCountSource.PROVIDER_REPORTED, record.countSource)
    }

    @Test fun `a completed request without a usage report is kept with unknown counts`() = runBlocking {
        val record = AuxiliaryUsage.record(
            attempt(), CompletableDeferred(TokenPricingCatalog("vendor/model")), succeeded = true
        ) { null }!!

        assertNull(record.inputTokens)
        assertNull(record.totalCost)
        assertEquals(TokenCountSource.PROVIDER_REPORTED, record.countSource)
    }

    @Test fun `a failed request is recorded only when the provider reported usage`() = runBlocking {
        assertNull(
            AuxiliaryUsage.record(
                attempt(), CompletableDeferred(TokenPricingCatalog("vendor/model")), succeeded = false
            ) { null }
        )
        val charged = attempt().apply {
            noteRawObservation(ReportedProviderParser.observeCompletedBody(completedBody)!!)
        }
        val record = AuxiliaryUsage.record(
            charged, CompletableDeferred(TokenPricingCatalog("vendor/model")), succeeded = false
        ) { null }
        assertEquals(0.0042, record!!.totalCost!!, 1e-12)
    }
}
