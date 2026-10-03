package org.teslasoft.assistant.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.ProviderEndpointInfo

class TokenPricingCatalogTest {
    @Test fun onlyOfficialFirstPartyHostsUseThePublicOpenRouterCatalog() {
        assertEquals("openai", FirstPartyPricing.openRouterAuthorFor(
            endpoint("https://api.openai.com/v1/", "OpenAI")))
        assertEquals("anthropic", FirstPartyPricing.openRouterAuthorFor(
            endpoint("https://api.anthropic.com/v1/", "Anthropic")))
        assertEquals("x-ai", FirstPartyPricing.openRouterAuthorFor(
            endpoint("https://api.x.ai/v1/", "xAI")))
        assertNull(FirstPartyPricing.openRouterAuthorFor(
            endpoint("https://openrouter.ai/api/v1/", "OpenRouter")))
        assertNull(FirstPartyPricing.openRouterAuthorFor(
            endpoint("https://api.deepinfra.com/v1/openai/", "DeepInfra")))
        assertNull(FirstPartyPricing.openRouterAuthorFor(
            endpoint("https://api.openai.com.example.test/v1/", "Proxy")))
    }

    @Test fun anthropicDatedModelMatchesDottedCatalogEntryWithCacheRates() {
        val pricing = FirstPartyPricing.match(
            CATALOG, "anthropic", "claude-sonnet-4-5-20250929"
        )!!
        assertEquals(0.000003, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.000015, pricing.outputPricePerToken!!, 1e-15)
        assertEquals(0.0000003, pricing.cachedInputPricePerToken!!, 1e-15)
        assertEquals(0.00000375, pricing.cacheWriteInputPricePerToken!!, 1e-15)
        assertNotNull(FirstPartyPricing.match(CATALOG, "anthropic", "claude-3-5-sonnet-latest"))
    }

    @Test fun listedOpenAiSnapshotWinsOverItsUndatedAlias() {
        val snapshot = FirstPartyPricing.match(CATALOG, "openai", "gpt-4o-2024-05-13")!!
        assertEquals(0.000005, snapshot.inputPricePerToken!!, 1e-15)
        val unlisted = FirstPartyPricing.match(CATALOG, "openai", "gpt-4o-2024-08-06")!!
        assertEquals(0.0000025, unlisted.inputPricePerToken!!, 1e-15)
        assertNull(unlisted.cacheWriteInputPricePerToken)
    }

    @Test fun grokReasoningModeNamesMatchTheirCatalogModel() {
        val pricing = FirstPartyPricing.match(CATALOG, "x-ai", "grok-4-1-fast-reasoning")!!
        assertEquals(0.0000002, pricing.inputPricePerToken!!, 1e-15)
        assertNotNull(FirstPartyPricing.match(CATALOG, "x-ai", "grok-4-0709"))
    }

    @Test fun xaiOwnPriceListMatchesAliasesAndConvertsUnits() {
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
        assertNull(FirstPartyPricing.matchXai(catalog, "grok-3"))
        assertNull(FirstPartyPricing.matchXai("not json", "grok-4"))
    }

    @Test fun unmatchedOrForeignModelsHaveNoPrice() {
        assertNull(FirstPartyPricing.match(CATALOG, "openai", "gpt-unknown"))
        assertNull(FirstPartyPricing.match(CATALOG, "anthropic", "gpt-4o"))
        assertNull(FirstPartyPricing.match(CATALOG, "openai", "gpt-free-only"))
        assertNull(FirstPartyPricing.match(CATALOG, "openai", "variable-router"))
        assertNull(FirstPartyPricing.match("not json", "openai", "gpt-4o"))
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
    }

    @Test fun genericListInOpenRouterStyleIsReadPerToken() {
        val pricing = GenericPricing.match(
            """{"data":[{"id":"some/model","pricing":{"prompt":"0.000002",
                "completion":"0.000008","input_cache_read":"0.0000005"}}]}""",
            "some/model"
        )!!
        assertEquals(0.000002, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.0000005, pricing.cachedInputPricePerToken!!, 1e-15)
    }

    @Test fun nanoGptStatedPerMillionUnitIsConverted() {
        val pricing = GenericPricing.match(
            """{"data":[{"id":"openai/gpt-4o-mini","pricing":{"prompt":0.15,
                "completion":0.6,"currency":"USD","unit":"per_million_tokens"}}]}""",
            "gpt-4o-mini"
        )!!
        assertEquals(0.00000015, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.0000006, pricing.outputPricePerToken!!, 1e-15)
    }

    @Test fun unstatedUnitAboveAnyRealPerTokenPriceIsReadPerMillion() {
        val pricing = GenericPricing.match(
            """{"data":[{"id":"m","pricing":{"prompt":3,"completion":15}}]}""", "m"
        )!!
        assertEquals(0.000003, pricing.inputPricePerToken!!, 1e-15)
    }

    @Test fun veniceModelSpecPricesAreReadPerMillionUsd() {
        val pricing = GenericPricing.match(
            """{"data":[{"id":"venice-uncensored","model_spec":{"pricing":{
                "input":{"usd":0.2,"diem":0.2},"output":{"usd":0.9,"diem":0.9}}}}]}""",
            "venice-uncensored"
        )!!
        assertEquals(0.0000002, pricing.inputPricePerToken!!, 1e-15)
        assertEquals(0.0000009, pricing.outputPricePerToken!!, 1e-15)
    }

    @Test fun impossibleForeignOrMissingPricesAreRejected() {
        assertNull(GenericPricing.match(
            """{"data":[{"id":"m","pricing":{"prompt":5000,"completion":5000,
                "unit":"per_token"}}]}""", "m"))
        assertNull(GenericPricing.match(
            """{"data":[{"id":"m","pricing":{"prompt":1,"completion":2,
                "currency":"EUR","unit":"per_million_tokens"}}]}""", "m"))
        assertNull(GenericPricing.match("""{"data":[{"id":"m"}]}""", "m"))
        assertNull(GenericPricing.match("""{"data":[{"id":"other","pricing":{
            "prompt":"0.000001","completion":"0.000002"}}]}""", "m"))
    }

    private companion object {
        val CATALOG = """
            {"data": [
              {"id": "anthropic/claude-sonnet-4.5",
               "canonical_slug": "anthropic/claude-4.5-sonnet-20250929",
               "pricing": {"prompt": "0.000003", "completion": "0.000015",
                           "input_cache_read": "0.0000003", "input_cache_write": "0.00000375"}},
              {"id": "anthropic/claude-3.5-sonnet",
               "pricing": {"prompt": "0.000003", "completion": "0.000015"}},
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
    }

    private fun endpoint(host: String, provider: String) = ApiEndpointObject(
        label = provider,
        host = host,
        apiKey = "",
        provider = provider
    )
}
