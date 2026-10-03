package org.teslasoft.assistant.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.providers.RawSseInspector

/** Fixtures use the official model-detail, plan, and completion layouts.
 * Cache counts at the accounting boundary use the subset semantics documented
 * in /usage/activity; no undocumented completion cache or receipt fields are
 * invented, and the admin endpoint is never fetched. */
class FeatherlessUsageTest {
    private val endpoint = "https://api.featherless.ai/v1"
    private val model = "meta-llama/Meta-Llama-3-8B-Instruct"
    private val detail = """{"id":"meta-llama/Meta-Llama-3-8B-Instruct",
        "object":"model","pricing":{"prompt":"0.0000001",
        "completion":"0.0000001","image":"0","request":"0"}}"""
    private val requestPlan = """{"id":"feather_request_pricing"}"""
    private val flatPlan = """{"id":"feather_pro_plus","name":"Feather Premium",
        "max_context_length":32768,"max_model_size":null,"concurrency":4}"""

    private fun load(plan: String? = requestPlan): TokenPricingSnapshot =
        FeatherlessPricing.load(endpoint, model) { url ->
            when {
                url.contains("/models/") -> detail
                url.endsWith("/plan") -> plan
                else -> error("Unexpected API access: $url")
            }
        }!!

    private fun record(
        counts: TokenCounts = TokenCounts(1000, 20, 1020, 0, 0),
        pricing: TokenPricingSnapshot = load(),
        cost: ProviderReportedCost? = null
    ) = TokenUsageAccounting.createRecord(model, "Featherless", endpoint, counts,
        TokenCountSource.PROVIDER_REPORTED, pricing, cost)

    @Test fun officialHostIsRecognizedWithoutSubstringMatching() {
        assertEquals(PricingSource.FEATHERLESS, PricingSource.forUrl(endpoint))
        assertEquals(PricingSource.FEATHERLESS, PricingSource.forUrl("$endpoint/"))
        assertNull(PricingSource.forUrl("https://api.featherless.ai.example.test/v1"))
        assertNull(PricingSource.forUrl("https://featherless.ai/v1"))
    }

    @Test fun modelDetailUsesExactIdsAndDecimalUsdPerToken() {
        val pricing = FeatherlessPricing.match(detail, model)!!
        assertEquals(1e-7, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(1e-7, pricing.outputPricePerToken!!, 1e-15)
        assertNull(pricing.cachedInputPricePerToken)
        assertNull(pricing.cachingOffered)
        assertNull(FeatherlessPricing.match(detail, model.lowercase()))
        assertNull(FeatherlessPricing.match(detail, "Meta-Llama-3-8B-Instruct"))
        assertNull(FeatherlessPricing.match(detail, "$model-latest"))
    }

    @Test fun priceLookupEncodesTheExactModelAndNeverNeedsAdminBillingAccess() {
        val urls = mutableListOf<String>()
        val pricing = FeatherlessPricing.load(endpoint, model) { url ->
            urls += url
            when (url) {
                "$endpoint/models/meta-llama%2FMeta-Llama-3-8B-Instruct" -> detail
                "$endpoint/plan" -> requestPlan
                // Simulate unavailable optional billing access if ever attempted.
                else -> throw SecurityException("organization_permission_denied")
            }
        }!!
        assertEquals(2, urls.size)
        assertTrue(urls.none { it.contains("/usage/activity") || it.contains("/credits") })
        assertEquals(true, pricing.featherlessRequestPricingConfirmed)
        assertEquals(0.000102, record(pricing = pricing).totalCost!!, 1e-15)
    }

    @Test fun ordinaryCompletionPreservesCountsAndLeavesUnreportedCacheUnknown() = runBlocking {
        val inspector = RawSseInspector()
        inspector.acceptLine("""{"id":"chatcmpl-example","object":"chat.completion",
            "created":1630569482,"model":"$model",
            "choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},
            "finish_reason":"stop"}],
            "usage":{"prompt_tokens":1000,"completion_tokens":20,"total_tokens":1020}}""")
        val attempt = ProviderUsageAttempt(model, "Featherless", endpoint)
        attempt.noteRawObservation(inspector.finishNormally())
        val snapshot = attempt.snapshot()
        assertEquals(TokenCounts(1000, 20, 1020), snapshot.counts)
        assertFalse(snapshot.providerCost.hasAnyValue())
        val result = record(snapshot.counts)
        assertEquals(1000, result.inputTokens)
        assertEquals(20, result.outputTokens)
        assertEquals(1020, result.totalTokens)
        assertNull(result.cachedInputTokens)
        assertNull(result.uncachedInputCost)
        assertNull(result.totalCost)
        assertEquals(0.000002, result.outputCost!!, 1e-15)
    }

    @Test fun explicitlyReportedZeroCacheAllowsCalculatedComponents() {
        val result = record()
        assertEquals(0.0001, result.uncachedInputCost!!, 1e-15)
        assertEquals(0.0, result.cachedInputCost!!, 0.0)
        assertEquals(0.000002, result.outputCost!!, 1e-15)
        assertEquals(0.000102, result.totalCost!!, 1e-15)
        assertEquals(CostSource.FROZEN_PRICING, result.storedCostSource)
    }

    @Test fun cachedInputIsASubsetAndMissingCacheRateIsNeverInvented() {
        // The detailed billing API defines input_tokens as including cached_input_tokens.
        val result = record(TokenCounts(1000, 20, 1020, 200, 0))
        assertEquals(1000, result.inputTokens)
        assertEquals(200, result.cachedInputTokens)
        assertEquals(1020, result.totalTokens)
        assertEquals(0.00008, result.uncachedInputCost!!, 1e-15)
        assertNull(result.cachedInputCost)
        assertNull(result.totalCost)
        val summary = TokenUsageAccounting.aggregate(listOf(result))
        assertEquals(800, summary.totalUncachedInputTokens)
        assertEquals(200, summary.totalCachedInputTokens)
        assertEquals(1000, summary.totalInputTokens)
    }

    @Test fun zeroPricesAreLegitimateAndInvalidPricesStayUnavailable() {
        val free = FeatherlessPricing.match(detail.replace("0.0000001", "0"), model)!!
            .copy(featherlessRequestPricingConfirmed = true)
        assertEquals(0.0, record(pricing = free).totalCost!!, 0.0)
        for (value in listOf("-1", "NaN", "Infinity", "not-a-number")) {
            assertNull(FeatherlessPricing.match(detail.replace("0.0000001", value), model))
        }
        assertNull(FeatherlessPricing.match("not json", model))
        assertNull(FeatherlessPricing.match("""{"id":"$model"}""", model))
        assertNull(record(TokenCounts(null, null, null)).totalCost)
    }

    @Test fun flatRateAndUnknownPlansNeverAcquireChargesIncludingInventedZero() {
        for (plan in listOf(flatPlan, null, "{}", "not json", """{"id":"future_plan"}""")) {
            val result = record(pricing = load(plan))
            assertEquals(1000, result.inputTokens)
            assertNotNull(result.inputPricePerToken)
            assertNull(result.uncachedInputCost)
            assertNull(result.inputCost)
            assertNull(result.cachedInputCost)
            assertNull(result.outputCost)
            assertNull(result.totalCost)
            assertEquals(CostSource.UNKNOWN, result.storedCostSource)
        }
    }

    @Test fun planReadFailureRetainsNormalUsageAndPrices() {
        val pricing = FeatherlessPricing.load(endpoint, model) { url ->
            if (url.contains("/models/")) detail else throw SecurityException("access denied")
        }!!
        assertEquals(1e-7, pricing.outputPricePerToken!!, 1e-15)
        assertEquals(20, record(pricing = pricing).outputTokens)
        assertNull(record(pricing = pricing).outputCost)
        assertNull(record(pricing = pricing).totalCost)
    }

    @Test fun missingModelDetailAndPartiallyMissingPricesRemainUnavailable() {
        assertNull(FeatherlessPricing.load(endpoint, model) { null })
        val partial = FeatherlessPricing.match(
            """{"id":"$model","pricing":{"prompt":"0","completion":null}}""", model)!!
            .copy(featherlessRequestPricingConfirmed = true)
        assertEquals(0.0, record(pricing = partial).uncachedInputCost!!, 0.0)
        assertNull(record(pricing = partial).outputCost)
        assertNull(record(pricing = partial).totalCost)
    }

    @Test fun reasoningIsNotAddedAgainAndRecordsRoundTripWithFrozenCosts() {
        assertEquals(20, outputIncludingReasoning(endpoint, 20, 10))
        val result = record()
        assertEquals(listOf(result), TokenUsageAccounting.decodeRecords(
            TokenUsageAccounting.encodeRecords(listOf(result))))
    }
}
