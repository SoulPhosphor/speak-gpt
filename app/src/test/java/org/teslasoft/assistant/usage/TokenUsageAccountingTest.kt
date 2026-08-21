package org.teslasoft.assistant.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageAccountingTest {
    private val pricing = TokenPricingSnapshot(0.000001, 0.000002)

    @Test fun providerReportedUsageTakesPrecedenceWithoutRunningCl100k() {
        var estimatorCalled = false
        val (counts, source) = TokenUsageAccounting.chooseCounts(
            TokenCounts(10, 20, 30)
        ) {
            estimatorCalled = true
            TokenCounts(100, 200, 300)
        }
        assertFalse(estimatorCalled)
        assertEquals(TokenCounts(10, 20, 30), counts)
        assertEquals(TokenCountSource.PROVIDER_REPORTED, source)
    }

    @Test fun cl100kFallbackIsUsedAndStoredWhenProviderUsageIsAbsent() {
        var estimatorCalled = false
        val (counts, source) = TokenUsageAccounting.chooseCounts(null) {
            estimatorCalled = true
            TokenCounts(11, 7, 18)
        }
        assertTrue(estimatorCalled)
        assertEquals(TokenCounts(11, 7, 18), counts)
        assertEquals(TokenCountSource.ESTIMATED_CL100K, source)

        val record = TokenUsageAccounting.createRecord(
            "model", "Provider", "https://endpoint", counts, source, pricing
        )
        assertEquals("estimated_cl100k", record.source)
    }

    @Test fun recordCodecPreservesUsageSourceModelProviderEndpointAndSeparateCounts() {
        val record = TokenUsageAccounting.createRecord(
            "glm-5", "DeepInfra", "https://openrouter.ai/api/v1/",
            TokenCounts(12, 34, 46), TokenCountSource.PROVIDER_REPORTED, pricing
        )
        val decoded = TokenUsageAccounting.decodeRecords(
            TokenUsageAccounting.encodeRecords(listOf(record))
        ).single()
        assertEquals("glm-5", decoded.model)
        assertEquals("DeepInfra", decoded.provider)
        assertEquals("https://openrouter.ai/api/v1/", decoded.apiEndpoint)
        assertEquals(12, decoded.inputTokens)
        assertEquals(34, decoded.outputTokens)
        assertEquals(46, decoded.totalTokens)
        assertEquals(TokenCountSource.PROVIDER_REPORTED, decoded.countSource)
    }

    @Test fun repeatedSameModelAndProviderAccumulatesIntoOneGroup() {
        val summary = TokenUsageAccounting.aggregate(listOf(
            record("glm-5", "DeepInfra", 10, 2),
            record("glm-5", "DeepInfra", 20, 3)
        ))
        assertEquals(1, summary.groups.size)
        assertEquals(30, summary.groups.single().inputTokens)
        assertEquals(5, summary.groups.single().outputTokens)
    }

    @Test fun sameModelWithDifferentProvidersCreatesSeparateGroups() {
        val summary = TokenUsageAccounting.aggregate(listOf(
            record("glm-5", "DeepInfra", 10, 2),
            record("glm-5", "Novita", 20, 3)
        ))
        assertEquals(2, summary.groups.size)
    }

    @Test fun differentModelsOnSameProviderCreateSeparateGroups() {
        val summary = TokenUsageAccounting.aggregate(listOf(
            record("glm-5", "DeepInfra", 10, 2),
            record("kimi-k2.5", "DeepInfra", 20, 3)
        ))
        assertEquals(2, summary.groups.size)
    }

    @Test fun wholeConversationTotalsSumEveryPricingGroup() {
        val summary = TokenUsageAccounting.aggregate(listOf(
            record("glm-5", "DeepInfra", 10, 2),
            record("glm-5", "Novita", 20, 3),
            record("kimi-k2.5", "DeepInfra", 30, 4)
        ))
        assertEquals(60, summary.totalInputTokens)
        assertEquals(9, summary.totalOutputTokens)
        assertEquals(0.000078, summary.totalCost, 0.000000001)
    }

    @Test fun quickSettingsUsesDetailedSinglePricingPresentation() {
        val presentation = QuickSettingsUsagePresentation.from(
            TokenUsageAccounting.aggregate(listOf(record("glm-5", "DeepInfra", 10, 2)))
        )
        assertEquals(UsageCardMode.SINGLE_PRICING, presentation.mode)
        assertFalse(presentation.showPricingDetails)
        assertEquals("glm-5", presentation.singleGroup?.model)
    }

    @Test fun quickSettingsUsesWholeConversationMultiPricingPresentation() {
        val presentation = QuickSettingsUsagePresentation.from(
            TokenUsageAccounting.aggregate(listOf(
                record("glm-5", "DeepInfra", 10, 2),
                record("glm-5", "Novita", 20, 3)
            ))
        )
        assertEquals(UsageCardMode.MULTI_PRICING, presentation.mode)
        assertTrue(presentation.showPricingDetails)
        assertEquals(30, presentation.totalInputTokens)
        assertEquals(5, presentation.totalOutputTokens)
    }

    @Test fun historicalPricingIsSummedFromRecordsNotAReplacementCurrentPrice() {
        val old = TokenUsageAccounting.createRecord(
            "glm-5", "DeepInfra", null, TokenCounts(100, 10, 110),
            TokenCountSource.PROVIDER_REPORTED, TokenPricingSnapshot(0.000001, 0.000002)
        )
        val newer = TokenUsageAccounting.createRecord(
            "glm-5", "DeepInfra", null, TokenCounts(100, 10, 110),
            TokenCountSource.PROVIDER_REPORTED, TokenPricingSnapshot(0.000003, 0.000004)
        )
        val group = TokenUsageAccounting.aggregate(listOf(old, newer)).groups.single()
        assertEquals(0.00046, group.totalCost, 0.000000001)
        assertEquals(null, group.inputPricePerToken)
        assertEquals(null, group.outputPricePerToken)
        assertTrue(group.hasVariablePricing)
    }

    @Test fun olderMessagesWithoutMetadataAreSafeEstimatedAndIgnoreLegacyTotalAsOutput() {
        val oldMessage = mapOf<String, Any>(
            "isBot" to true,
            "message" to "old reply",
            "responseModel" to "glm-5",
            "responseTokens" to "999"
        )
        val summary = TokenUsageAccounting.summarizeMessages(
            listOf(oldMessage), "currently-selected-model"
        ) { TokenCounts(6, 4, 10) }
        val group = summary.groups.single()
        assertEquals("glm-5", group.model)
        assertEquals(6, group.inputTokens)
        assertEquals(4, group.outputTokens)
        assertTrue(group.containsEstimatedTokens)
    }

    private fun record(model: String, provider: String, input: Int, output: Int) =
        TokenUsageAccounting.createRecord(
            model, provider, null, TokenCounts(input, output, input + output),
            TokenCountSource.PROVIDER_REPORTED, pricing
        )
}
