package org.teslasoft.assistant.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

class PromptCachingPolicyTest {

    @Test
    fun automaticRequiresEveryCandidateToUseExplicitWriteCaching() {
        assertTrue(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(explicit("alibaba"), explicit("anthropic")),
            favorite(FavoriteModelObject.ROUTING_AUTOMATIC),
            catalogAuthoritative = true
        ))
        assertFalse(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(explicit("alibaba"), implicit("deepinfra")),
            favorite(FavoriteModelObject.ROUTING_AUTOMATIC),
            catalogAuthoritative = true
        ))
    }

    @Test
    fun automaticIgnoresEndpointsTheUserExcluded() {
        val favorite = favorite(FavoriteModelObject.ROUTING_AUTOMATIC).apply {
            ignoredProviders = listOf("deepinfra")
        }

        assertTrue(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(explicit("alibaba"), implicit("deepinfra")), favorite,
            catalogAuthoritative = true
        ))
    }

    @Test
    fun onlyUsesTheSelectedProvider() {
        val favorite = favorite(FavoriteModelObject.ROUTING_ONLY).apply {
            selectedProvider = "alibaba"
        }

        assertTrue(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(explicit("alibaba"), implicit("deepinfra")), favorite
        ))
    }

    @Test
    fun preferredWithoutFallbacksUsesOnlyTheOrderList() {
        val favorite = favorite(FavoriteModelObject.ROUTING_PREFERRED).apply {
            allowFallbacks = false
            providerOrder = listOf("alibaba")
        }

        assertTrue(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(explicit("alibaba"), implicit("deepinfra")), favorite
        ))
    }

    @Test
    fun unknownOrReadOnlyCacheEvidenceNeverChangesMessages() {
        assertFalse(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(endpoint("unknown", cacheWrite = null, implicit = null)), null
        ))
        assertFalse(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(endpoint("read-only", cacheWrite = null, implicit = false)), null
        ))
        assertFalse(PromptCachingPolicy.shouldAddExplicitBreakpoint(emptyList(), null))
    }

    @Test
    fun implicitCachingWinsEvenWhenTheEndpointAlsoListsAWritePrice() {
        assertFalse(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(endpoint("openai", cacheWrite = 0.00000125, implicit = true)), null,
            catalogAuthoritative = true
        ))
    }

    @Test
    fun automaticRequiresACompleteEndpointCatalog() {
        assertFalse(PromptCachingPolicy.shouldAddExplicitBreakpoint(
            listOf(explicit("alibaba")),
            favorite(FavoriteModelObject.ROUTING_AUTOMATIC),
            catalogAuthoritative = false
        ))
    }

    private fun favorite(routing: String) = FavoriteModelObject(
        modelId = "model", endpointId = "openrouter", routingType = routing
    )

    private fun explicit(slug: String) = endpoint(slug, cacheWrite = 0.00000125, implicit = false)

    private fun implicit(slug: String) = endpoint(slug, cacheWrite = null, implicit = true)

    private fun endpoint(slug: String, cacheWrite: Double?, implicit: Boolean?) =
        ProviderEndpointInfo(
            providerName = slug.replaceFirstChar { it.uppercase() },
            slug = slug,
            quantization = null,
            promptPrice = 0.000001,
            completionPrice = 0.000002,
            cacheReadPrice = 0.0000001,
            cacheWritePrice = cacheWrite,
            latency = null,
            throughput = null,
            uptime = null,
            supportsTools = null,
            supportsCaching = implicit,
            zdr = null
        )
}
