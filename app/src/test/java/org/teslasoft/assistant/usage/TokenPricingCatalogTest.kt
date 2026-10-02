package org.teslasoft.assistant.usage

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.ProviderEndpointInfo

class TokenPricingCatalogTest {
    @Test fun legacyOpenAiPriceIsAvailableOnlyForOfficialOpenAiHost() {
        val official = endpoint("https://api.openai.com/v1/", "OpenAI")
        assertNotNull(TokenPricingCatalogClient.legacyPricingFor(official, "gpt-4o"))

        val openRouter = endpoint("https://openrouter.ai/api/v1/", "OpenRouter")
        assertNull(TokenPricingCatalogClient.legacyPricingFor(openRouter, "gpt-4o"))

        val anotherProvider = endpoint("https://api.deepinfra.com/v1/openai/", "DeepInfra")
        assertNull(TokenPricingCatalogClient.legacyPricingFor(anotherProvider, "gpt-4o"))

        val deceptiveHost = endpoint("https://api.openai.com.example.test/v1/", "Proxy")
        assertNull(TokenPricingCatalogClient.legacyPricingFor(deceptiveHost, "gpt-4o"))
    }

    @Test fun missingEndpointNeverReceivesLegacyModelPricing() {
        assertNull(TokenPricingCatalogClient.legacyPricingFor(null, "gpt-4o"))
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

    private fun endpoint(host: String, provider: String) = ApiEndpointObject(
        label = provider,
        host = host,
        apiKey = "",
        provider = provider
    )
}
