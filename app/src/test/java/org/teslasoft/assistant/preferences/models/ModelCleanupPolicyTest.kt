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
    fun acceptedOpenRouterAliasIsNotMarkedUnavailable() {
        val oldAlias = ModelIdentity("openrouter", "deepseek/deepseek-chat-v3.1")
        val report = ModelCleanupPolicy.update(
            previous = ModelCleanupReport(),
            currentTargets = setOf(oldAlias),
            // The client adds the original saved id after OpenRouter's targeted
            // lookup resolves it to the current canonical model.
            checks = mapOf(
                "openrouter" to EndpointCatalogCheck.Checked(
                    setOf("deepseek/deepseek-v3.1", oldAlias.modelId)
                )
            ),
            endpointLabels = emptyMap(),
            generatedAtMillis = 10L
        )

        assertTrue(report.unavailable.isEmpty())
    }

    @Test
    fun inconclusiveAliasLookupCannotCreateADeletionCandidate() {
        val oldAlias = ModelIdentity("openrouter", "deepseek/deepseek-chat-v3.1")
        val report = ModelCleanupPolicy.update(
            previous = ModelCleanupReport(),
            currentTargets = setOf(oldAlias),
            checks = mapOf(
                "openrouter" to EndpointCatalogCheck.Checked(
                    modelIds = setOf("deepseek/deepseek-v3.1"),
                    indeterminateModelIds = setOf(oldAlias.modelId)
                )
            ),
            endpointLabels = emptyMap(),
            generatedAtMillis = 10L
        )

        assertTrue(report.unavailable.isEmpty())
        assertEquals(setOf("openrouter"), report.uncheckedEndpointIds)
    }

    @Test
    fun inconclusiveAliasRecheckPreservesAnExistingWarning() {
        val oldAlias = ModelIdentity("openrouter", "deepseek/deepseek-chat-v3.1")
        val report = ModelCleanupPolicy.update(
            previous = ModelCleanupReport(
                generatedAtMillis = 1L,
                unavailable = setOf(oldAlias)
            ),
            currentTargets = setOf(oldAlias),
            checks = mapOf(
                "openrouter" to EndpointCatalogCheck.Checked(
                    modelIds = setOf("deepseek/deepseek-v3.1"),
                    indeterminateModelIds = setOf(oldAlias.modelId)
                )
            ),
            endpointLabels = emptyMap(),
            generatedAtMillis = 10L
        )

        assertEquals(setOf(oldAlias), report.unavailable)
        assertEquals(setOf("openrouter"), report.uncheckedEndpointIds)
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
    @Test fun ttsOnlyReferencesAreDeduplicatedAndSurviveReportPruning() {
        val a = org.teslasoft.assistant.preferences.tts.SavedTtsSource("a", "speech", "vendor/model",
            org.teslasoft.assistant.preferences.tts.TtsRoutingSettings())
        val b = a.copy(id = "b", routing = org.teslasoft.assistant.preferences.tts.TtsRoutingSettings(
            org.teslasoft.assistant.preferences.tts.TtsRoutingMode.ONLY, "provider"))
        val refs = ModelCleanupReferences(emptySet(), emptyMap(), 0).withTtsSources(Result.success(listOf(a, b)))
        val target = ModelIdentity("speech", "vendor/model")
        assertEquals(setOf(target), refs.allTargets)
        val report = ModelCleanupPolicy.update(ModelCleanupReport(), refs.allTargets,
            mapOf("speech" to EndpointCatalogCheck.Checked(setOf("other"))), emptyMap(), 10)
        assertEquals(setOf(target), ModelCleanupPolicy.prune(report, refs).unavailable)
        assertFalse(ModelIdentity("speech", "unsaved") in report.unavailable)
    }

    @Test fun unreadableTtsCollectionPreservesReportAndIsNotAnEmptySuccessfulRead() {
        val report = ModelCleanupReport(10, setOf(openRouterModel), setOf("openrouter"), mapOf("openrouter" to "Speech"))
        val refs = ModelCleanupReferences(emptySet(), emptyMap(), 0)
            .withTtsSources(Result.failure(java.io.IOException("read failed")))
        assertFalse(refs.isComplete)
        assertFalse(refs.ttsReadable)
        assertEquals(report, ModelCleanupPolicy.prune(report, refs))
    }

    @Test fun deletingTtsReferencesKeepsSharedFavoriteAndRuleWarnings() {
        val refs = ModelCleanupReferences(setOf(openRouterModel), mapOf("rule" to setOf(deepSeekModel)), 0)
        val ttsOnly = ModelIdentity("speech", "model")
        val report = ModelCleanupReport(10, setOf(openRouterModel, deepSeekModel, ttsOnly))
        val pruned = ModelCleanupPolicy.prune(report, refs.withTtsSources(Result.success(emptyList())))
        assertEquals(setOf(openRouterModel, deepSeekModel), pruned.unavailable)
    }

}
