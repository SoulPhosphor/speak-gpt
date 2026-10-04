package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

class PdfCapabilityResolverTest {
    private fun endpoint(host: String, provider: String = "") =
        ApiEndpointObject("test", host, "key", provider = provider)

    @Test fun `direct documented providers and fallback providers are independent from vision`() {
        assertEquals(PdfCapability.SUPPORTED,
            PdfCapabilityResolver.resolve(endpoint("https://api.openai.com/v1"), "gpt-5"))
        assertEquals(PdfCapability.SUPPORTED,
            PdfCapabilityResolver.resolve(endpoint("https://api.anthropic.com"), "claude-sonnet-4"))
        assertEquals(PdfCapability.SUPPORTED,
            PdfCapabilityResolver.resolve(endpoint("https://generativelanguage.googleapis.com"), "gemini-3-pro"))
        assertEquals(PdfCapability.UNSUPPORTED,
            PdfCapabilityResolver.resolve(endpoint("https://api.featherless.ai/v1"), "vision-model"))
        assertEquals(PdfCapability.UNKNOWN,
            PdfCapabilityResolver.resolve(endpoint("https://custom.example/v1"), "vision-model"))
    }

    @Test fun `NanoGPT does not infer PDF from model name or vision`() {
        assertEquals(PdfCapability.UNKNOWN,
            PdfCapabilityResolver.resolve(endpoint("https://nano-gpt.com/api/v1"), "vision-model"))
    }

    @Test fun `OpenRouter pinned route requires model and downstream evidence`() {
        val ep = endpoint("https://openrouter.ai/api/v1").apply {
            identity = ApiEndpointObject.IDENTITY_OPENROUTER
            pdfCapabilityByModel = PdfCapabilityStore.setMetadata("", "vendor/model", PdfCapability.SUPPORTED)
        }
        val route = PdfRoutingConfig(
            PdfCapabilityProvider.OPENROUTER, FavoriteModelObject.ROUTING_ONLY, "Provider A", false
        )
        assertEquals(PdfCapability.UNKNOWN, PdfCapabilityResolver.resolve(ep, "vendor/model", route))
        val scope = PdfCapabilityResolver.scope(ep, "vendor/model", route)
        ep.pdfCapabilityByModel = PdfCapabilityStore.setRoute(
            ep.pdfCapabilityByModel, scope, PdfCapability.SUPPORTED
        )
        assertEquals(PdfCapability.SUPPORTED, PdfCapabilityResolver.resolve(ep, "vendor/model", route))
    }
}
