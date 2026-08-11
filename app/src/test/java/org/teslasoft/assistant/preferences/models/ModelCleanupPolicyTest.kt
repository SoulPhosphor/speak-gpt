package org.teslasoft.assistant.preferences.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCleanupPolicyTest {
    private val openRouterModel = ModelIdentity("openrouter", "openai/gpt-5.1")
    private val deepSeekModel = ModelIdentity("deepseek", "openai/gpt-5.1")

    @Test
    fun sameModelIdIsEvaluatedSeparatelyForEachEndpoint() {
        val report = ModelCleanupPolicy.update(
            previous = ModelCleanupReport(),
            currentTargets = setOf(openRouterModel, deepSeekModel),
            checks = mapOf(
                "openrouter" to EndpointCatalogCheck.Checked(setOf("openai/gpt-5.1")),
                "deepseek" to EndpointCatalogCheck.Checked(setOf("deepseek-v4"))
            ),
            endpointLabels = emptyMap(),
            generatedAtMillis = 10L
        )

        assertFalse(openRouterModel in report.unavailable)
        assertTrue(deepSeekModel in report.unavailable)
    }

    @Test
    fun aggregatorAvailabilityUsesOverallModelIdOnly() {
        // No upstream-host route data enters the policy. If the overall model
        // id is in OpenRouter's catalog, it is available.
        val report = ModelCleanupPolicy.update(
            previous = ModelCleanupReport(),
            currentTargets = setOf(openRouterModel),
            checks = mapOf(
                "openrouter" to EndpointCatalogCheck.Checked(setOf("openai/gpt-5.1"))
            ),
            endpointLabels = emptyMap(),
            generatedAtMillis = 10L
        )
        assertTrue(report.unavailable.isEmpty())
    }

    @Test
    fun failedCheckNeverCreatesUnavailableStatus() {
        val report = ModelCleanupPolicy.update(
            previous = ModelCleanupReport(),
            currentTargets = setOf(openRouterModel),
            checks = mapOf("openrouter" to EndpointCatalogCheck.Unchecked),
            endpointLabels = mapOf("openrouter" to "OpenRouter"),
            generatedAtMillis = 10L
        )
        assertTrue(report.unavailable.isEmpty())
        assertEquals(setOf("openrouter"), report.uncheckedEndpointIds)
    }

    @Test
    fun failedRecheckPreservesPriorWarningUntilConclusiveRecovery() {
        val previous = ModelCleanupReport(
            generatedAtMillis = 1L,
            unavailable = setOf(openRouterModel)
        )
        val failed = ModelCleanupPolicy.update(
            previous = previous,
            currentTargets = setOf(openRouterModel),
            checks = mapOf("openrouter" to EndpointCatalogCheck.Unchecked),
            endpointLabels = emptyMap(),
            generatedAtMillis = 2L
        )
        assertTrue(openRouterModel in failed.unavailable)

        val recovered = ModelCleanupPolicy.update(
            previous = failed,
            currentTargets = setOf(openRouterModel),
            checks = mapOf(
                "openrouter" to EndpointCatalogCheck.Checked(setOf("openai/gpt-5.1"))
            ),
            endpointLabels = emptyMap(),
            generatedAtMillis = 3L
        )
        assertFalse(openRouterModel in recovered.unavailable)
    }

    @Test
    fun localPruneRemovesDeletedReferencesWithoutNetworkCheck() {
        val report = ModelCleanupReport(
            generatedAtMillis = 1L,
            unavailable = setOf(openRouterModel, deepSeekModel),
            uncheckedEndpointIds = setOf("deepseek")
        )
        val pruned = ModelCleanupPolicy.prune(report, setOf(openRouterModel))
        assertEquals(setOf(openRouterModel), pruned.unavailable)
        assertTrue(pruned.uncheckedEndpointIds.isEmpty())
        assertEquals(1L, pruned.generatedAtMillis)
    }
}
