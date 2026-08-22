package org.teslasoft.assistant.usage

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

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

    private fun endpoint(host: String, provider: String) = ApiEndpointObject(
        label = provider,
        host = host,
        apiKey = "",
        provider = provider
    )
}
