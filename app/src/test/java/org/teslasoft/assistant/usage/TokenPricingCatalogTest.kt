package org.teslasoft.assistant.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.ProviderEndpointInfo

class TokenPricingCatalogTest {
    @Test fun providersAreRecognizedOnlyByTheirOfficialHosts() {
        assertEquals(PricingSource.OPENAI,
            PricingSource.forEndpoint(endpoint("https://api.openai.com/v1/", "OpenAI")))
        assertEquals(PricingSource.ANTHROPIC,
            PricingSource.forEndpoint(endpoint("https://api.anthropic.com/v1/", "Anthropic")))
        assertEquals(PricingSource.XAI,
            PricingSource.forEndpoint(endpoint("https://api.x.ai/v1/", "xAI")))
        assertEquals(PricingSource.NANOGPT,
            PricingSource.forEndpoint(endpoint("https://nano-gpt.com/api/v1/", "NanoGPT")))
        assertEquals(PricingSource.NANOGPT,
            PricingSource.forEndpoint(endpoint("https://api.nano-gpt.com/api/v1/", "NanoGPT")))
        assertEquals(PricingSource.VENICE,
            PricingSource.forEndpoint(endpoint("https://api.venice.ai/api/v1/", "Venice")))
        assertNull(PricingSource.forEndpoint(endpoint("https://openrouter.ai/api/v1/", "OpenRouter")))
        assertNull(PricingSource.forEndpoint(endpoint("https://api.deepinfra.com/v1/openai/", "DeepInfra")))
        assertNull(PricingSource.forEndpoint(endpoint("https://api.openai.com.example.test/v1/", "Proxy")))
    }

    @Test fun openRouterCatalogMatchesOnlyAnExactIdOrCanonicalSlug() {
        val pricing = FirstPartyPricing.match(CATALOG, "anthropic", "claude-sonnet-4.5")!!
        assertEquals(0.000003, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.0000003, pricing.cachedInputPricePerToken!!, 1e-15)
        assertEquals(0.00000375, pricing.cacheWriteInputPricePerToken!!, 1e-15)
        assertNotNull(FirstPartyPricing.match(CATALOG, "anthropic", "claude-4.5-sonnet-20250929"))
        assertNull(FirstPartyPricing.match(CATALOG, "anthropic", "claude-sonnet-4-5"))
        assertNull(FirstPartyPricing.match(CATALOG, "anthropic", "claude-sonnet-4-5-20250929"))
    }

    @Test fun unlistedDatedSnapshotsNeverBorrowTheUndatedPrice() {
        val snapshot = FirstPartyPricing.match(CATALOG, "openai", "gpt-4o-2024-05-13")!!
        assertEquals(0.000005, snapshot.inputPricePerToken!!, 1e-15)
        assertNull(FirstPartyPricing.match(CATALOG, "openai", "gpt-4o-2024-08-06"))
        assertNull(FirstPartyPricing.match(CATALOG, "openai", "gpt-4o-latest"))
    }

    @Test fun grokReasoningNamesNeedTheirOwnEntry() {
        assertNull(FirstPartyPricing.match(CATALOG, "x-ai", "grok-4.1-fast-reasoning"))
        assertNull(FirstPartyPricing.match(CATALOG, "x-ai", "grok-4-0709"))
        assertNotNull(FirstPartyPricing.match(CATALOG, "x-ai", "grok-4"))
    }

    @Test fun reportedZeroPricesAreValidAndNegativeOnesAreNot() {
        val free = FirstPartyPricing.match(CATALOG, "openai", "gpt-free-only:free")!!
        assertEquals(0.0, free.inputPricePerToken!!, 0.0)
        assertEquals(0.0, free.outputPricePerToken!!, 0.0)
        assertNull(FirstPartyPricing.match(CATALOG, "openai", "gpt-free-only"))
        assertNull(FirstPartyPricing.match(CATALOG, "openai", "variable-router"))
        assertNull(FirstPartyPricing.match(CATALOG, "anthropic", "gpt-4o"))
        assertNull(FirstPartyPricing.match("not json", "openai", "gpt-4o"))
    }

    @Test fun xaiOwnPriceListMatchesIdsAndListedAliasesOnly() {
        val catalog = """
            {"models": [
              {"id": "grok-4-0709", "aliases": ["grok-4", "grok-4-latest"],
               "prompt_text_token_price": 30000,
               "cached_prompt_text_token_price": 7500,
               "completion_text_token_price": 150000}
            ]}
        """.trimIndent()
        val pricing = FirstPartyPricing.matchXai(catalog, "grok-4")!!
        assertEquals(0.000003, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.00000075, pricing.cachedInputPricePerToken!!, 1e-15)
        assertEquals(0.000015, pricing.outputPricePerToken!!, 1e-15)
        assertNotNull(FirstPartyPricing.matchXai(catalog, "grok-4-0709"))
        assertNull(FirstPartyPricing.matchXai(catalog, "grok-4-0709-reasoning"))
        assertNull(FirstPartyPricing.matchXai(catalog, "grok-3"))
        assertNull(FirstPartyPricing.matchXai("not json", "grok-4"))
    }

    @Test fun xaiLongContextRatesComeFromXaisOwnFields() {
        val catalog = """
            {"models": [
              {"id": "grok-4-0709", "aliases": ["grok-4"],
               "prompt_text_token_price": 30000,
               "cached_prompt_text_token_price": 7500,
               "completion_text_token_price": 150000,
               "long_context_threshold": 128000,
               "prompt_text_token_price_long_context": 60000,
               "cached_prompt_text_token_price_long_context": 0,
               "completion_text_token_price_long_context": 300000},
              {"id": "grok-3", "prompt_text_token_price": 30000,
               "completion_text_token_price": 150000, "long_context_threshold": 0}
            ]}
        """.trimIndent()
        val tier = FirstPartyPricing.matchXai(catalog, "grok-4")!!.extended!!
        assertEquals(128_000L, tier.inputTokenThreshold)
        assertTrue(tier.appliesAtThreshold)
        assertEquals(0.000006, tier.pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.00003, tier.pricing.outputPricePerToken!!, 1e-15)
        // A long-context price of 0 means the standard price applies.
        assertEquals(0.00000075, tier.pricing.cachedInputPricePerToken!!, 1e-15)
        assertNull(FirstPartyPricing.matchXai(catalog, "grok-3")!!.extended)
    }

    @Test fun nanoGptDetailedPricesUseTheirDocumentedUnits() {
        val pricing = NanoGptPricing.match(NANOGPT, "anthropic/claude-opus-5.5")!!
        assertEquals(0.000004, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.00002, pricing.outputPricePerToken!!, 1e-15)
        assertEquals(0.0000002, pricing.cachedInputPricePerToken!!, 1e-15)
        assertEquals(0.000005, pricing.cacheWriteInputPricePerToken!!, 1e-15)
        assertNull(NanoGptPricing.match(NANOGPT, "claude-opus-5.5"))
    }

    @Test fun nanoGptPricesInAnotherCurrencyOrUnitAreNotUsed() {
        assertNull(NanoGptPricing.match("""{"data":[{"id":"m","pricing":{"prompt":1,
            "completion":2,"currency":"EUR","unit":"per_million_tokens"}}]}""", "m"))
        assertNull(NanoGptPricing.match("""{"data":[{"id":"m","pricing":{"prompt":1,
            "completion":2,"currency":"USD","unit":"per_request"}}]}""", "m"))
    }

    @Test fun veniceBaseCacheAndExtendedRatesAreReadInUsd() {
        val pricing = VenicePricing.match(VENICE, "claude-opus-4-5")!!
        assertEquals(0.000006, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.00003, pricing.outputPricePerToken!!, 1e-15)
        assertEquals(0.0000006, pricing.cachedInputPricePerToken!!, 1e-15)
        assertEquals(0.0000075, pricing.cacheWriteInputPricePerToken!!, 1e-15)
        assertEquals(true, pricing.cachingOffered)
        val tier = pricing.extended!!
        assertEquals(200_000L, tier.inputTokenThreshold)
        assertEquals(0.000011, tier.pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.00004125, tier.pricing.outputPricePerToken!!, 1e-15)
    }

    @Test fun veniceModelWithoutCacheInputOffersNoCaching() {
        val pricing = VenicePricing.match(VENICE, "venice-uncensored")!!
        assertFalse(pricing.cachingOffered!!)
        assertNull(pricing.cachedInputPricePerToken)
        assertNull(pricing.extended)
    }

    @Test fun veniceAliasesResolveOnlyThroughVenicesMapping() {
        val mapping = """{"data":{"gpt-4o":"venice-uncensored"},"object":"list"}"""
        assertNull(VenicePricing.match(VENICE, "gpt-4o"))
        assertEquals("venice-uncensored", VenicePricing.mappedModel(mapping, "gpt-4o"))
        assertNull(VenicePricing.mappedModel(mapping, "gpt-4"))
    }

    @Test fun otherServicesUseAStatedUnitOrOpenRoutersPerTokenConvention() {
        val perToken = GenericPricing.match(
            """{"data":[{"id":"some/model","pricing":{"prompt":"0.000002",
                "completion":"0.000008","input_cache_read":"0.0000005"}}]}""",
            "some/model"
        )!!
        assertEquals(0.000002, perToken.inputPricePerToken!!, 1e-15)
        assertEquals(0.0000005, perToken.cachedInputPricePerToken!!, 1e-15)
        val stated = GenericPricing.match(
            """{"data":[{"id":"m","pricing":{"prompt":3,"completion":15,
                "unit":"per_million_tokens"}}]}""", "m"
        )!!
        assertEquals(0.000003, stated.inputPricePerToken!!, 1e-15)
        // No unit: the number is taken at its convention, however large.
        val large = GenericPricing.match(
            """{"data":[{"id":"m","pricing":{"prompt":3,"completion":15}}]}""", "m"
        )!!
        assertEquals(3.0, large.inputPricePerToken!!, 0.0)
    }

    @Test fun otherServicesWithUnknownUnitsCurrenciesOrNamesHaveNoPrice() {
        assertNull(GenericPricing.match(
            """{"data":[{"id":"m","pricing":{"prompt":1,"completion":2,
                "unit":"per_request"}}]}""", "m"))
        assertNull(GenericPricing.match(
            """{"data":[{"id":"m","pricing":{"prompt":1,"completion":2,
                "currency":"EUR"}}]}""", "m"))
        assertNull(GenericPricing.match(
            """{"data":[{"id":"openai/gpt-4o-mini","pricing":{"prompt":"0.000001",
                "completion":"0.000002"}}]}""", "gpt-4o-mini"))
        assertNull(GenericPricing.match("""{"data":[{"id":"m"}]}""", "m"))
    }

    @Test fun providerPricingPreservesCacheReadAndWriteRates() {
        val catalog = TokenPricingCatalog(
            model = "model",
            providerPrices = listOf(
                ProviderEndpointInfo(
                    providerName = "DeepInfra",
                    slug = "deepinfra",
                    quantization = null,
                    promptPrice = 0.000001,
                    completionPrice = 0.000002,
                    cacheReadPrice = 0.0000001,
                    cacheWritePrice = 0.00000125,
                    latency = null,
                    throughput = null,
                    uptime = null,
                    supportsTools = null,
                    supportsCaching = true,
                    zdr = null
                )
            )
        )
        val pricing = catalog.pricingFor("deepinfra")!!
        assertNotNull(pricing.cachedInputPricePerToken)
        assertNotNull(pricing.cacheWriteInputPricePerToken)
        assertTrue(pricing.inputPricePerToken!! > 0.0)
    }

    private companion object {
        val CATALOG = """
            {"data": [
              {"id": "anthropic/claude-sonnet-4.5",
               "canonical_slug": "anthropic/claude-4.5-sonnet-20250929",
               "pricing": {"prompt": "0.000003", "completion": "0.000015",
                           "input_cache_read": "0.0000003", "input_cache_write": "0.00000375"}},
              {"id": "openai/gpt-4o",
               "pricing": {"prompt": "0.0000025", "completion": "0.00001",
                           "input_cache_read": "0.00000125"}},
              {"id": "openai/gpt-4o-2024-05-13",
               "pricing": {"prompt": "0.000005", "completion": "0.000015"}},
              {"id": "openai/gpt-free-only:free",
               "pricing": {"prompt": "0", "completion": "0"}},
              {"id": "openai/variable-router",
               "pricing": {"prompt": "-1", "completion": "-1"}},
              {"id": "x-ai/grok-4.1-fast",
               "pricing": {"prompt": "0.0000002", "completion": "0.0000005",
                           "input_cache_read": "0.00000005"}},
              {"id": "x-ai/grok-4",
               "pricing": {"prompt": "0.000003", "completion": "0.000015"}}
            ]}
        """.trimIndent()

        /** Shaped like NanoGPT's documented `/models?detailed=true` example. */
        val NANOGPT = """
            {"object": "list", "data": [
              {"id": "anthropic/claude-opus-5.5", "object": "model",
               "pricing": {"prompt": 4, "completion": 20,
                           "cacheReadInputPer1kTokens": 0.0002,
                           "cacheWriteInputPer1kTokens": 0.005,
                           "currency": "USD", "unit": "per_million_tokens"}}
            ]}
        """.trimIndent()

        /** Shaped like Venice's documented `/models` model_spec.pricing. */
        val VENICE = """
            {"object": "list", "type": "text", "data": [
              {"id": "claude-opus-4-5", "model_spec": {"pricing": {
                 "input": {"usd": 6, "diem": 6},
                 "cache_input": {"usd": 0.6, "diem": 0.6},
                 "cache_write": {"usd": 7.5, "diem": 7.5},
                 "output": {"usd": 30, "diem": 30},
                 "extended": {"context_token_threshold": 200000,
                   "input": {"usd": 11, "diem": 11},
                   "output": {"usd": 41.25, "diem": 41.25},
                   "cache_input": {"usd": 1.1, "diem": 1.1},
                   "cache_write": {"usd": 13.75, "diem": 13.75}}}}},
              {"id": "venice-uncensored", "model_spec": {"pricing": {
                 "input": {"usd": 0.2, "diem": 0.2},
                 "output": {"usd": 0.9, "diem": 0.9}}}}
            ]}
        """.trimIndent()
    }

    private fun endpoint(host: String, provider: String) = ApiEndpointObject(
        label = provider,
        host = host,
        apiKey = "",
        provider = provider
    )
}
