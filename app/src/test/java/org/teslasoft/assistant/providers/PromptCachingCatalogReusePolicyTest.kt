package org.teslasoft.assistant.providers

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.teslasoft.assistant.usage.TokenPricingCatalog

class PromptCachingCatalogReusePolicyTest {

    private val catalog = TokenPricingCatalog("qwen/model")

    @Test
    fun reusesOnlyMatchingFreshEndpointEvidence() {
        val evidence = PromptCachingCatalogEvidence("profile-a", catalog, 1_000L)

        assertSame(catalog, PromptCachingCatalogReusePolicy.reusableCatalog(
            evidence, "profile-a", "qwen/model", 2_000L
        ))
        assertNull(PromptCachingCatalogReusePolicy.reusableCatalog(
            evidence, "profile-b", "qwen/model", 2_000L
        ))
        assertNull(PromptCachingCatalogReusePolicy.reusableCatalog(
            evidence, "profile-a", "other/model", 2_000L
        ))
        assertNull(PromptCachingCatalogReusePolicy.reusableCatalog(
            evidence, "profile-a", "QWEN/MODEL", 2_000L
        ))
    }

    @Test
    fun rejectsExpiredOrFutureEvidence() {
        val evidence = PromptCachingCatalogEvidence("profile-a", catalog, 10_000L)

        assertNull(PromptCachingCatalogReusePolicy.reusableCatalog(
            evidence,
            "profile-a",
            "qwen/model",
            10_000L + PromptCachingCatalogReusePolicy.MAX_AGE_MS + 1L
        ))
        assertNull(PromptCachingCatalogReusePolicy.reusableCatalog(
            evidence, "profile-a", "qwen/model", 9_999L
        ))
    }
}
