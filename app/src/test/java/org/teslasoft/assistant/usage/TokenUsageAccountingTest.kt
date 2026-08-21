package org.teslasoft.assistant.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun partialProviderUsageStaysPartialAndDoesNotRunCl100k() {
        var estimatorCalled = false
        val (counts, source) = TokenUsageAccounting.chooseCounts(
            TokenCounts(null, 20, null)
        ) {
            estimatorCalled = true
            TokenCounts(100, 200, 300)
        }
        assertFalse(estimatorCalled)
        assertNull(counts.inputTokens)
        assertEquals(20, counts.outputTokens)
        assertNull(counts.totalTokens)
        assertEquals(TokenCountSource.PROVIDER_REPORTED, source)
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

    @Test fun providerReportedTotalCostWinsWithoutInventingInputOutputSplit() {
        val record = TokenUsageAccounting.createRecord(
            "glm-5", "DeepInfra", null, TokenCounts(1_000, 100, 1_100),
            TokenCountSource.PROVIDER_REPORTED,
            TokenPricingSnapshot(0.001, 0.002),
            ProviderReportedCost(totalCost = 0.25)
        )
        assertEquals(0.25, record.totalCost!!, 0.000000001)
        assertNull(record.inputCost)
        assertNull(record.outputCost)
        assertEquals(CostSource.PROVIDER_REPORTED, record.storedCostSource)
        val restored = TokenUsageAccounting.decodeRecords(
            TokenUsageAccounting.encodeRecords(listOf(record))
        ).single()
        assertEquals(0.25, restored.totalCost!!, 0.000000001)
        assertEquals(CostSource.PROVIDER_REPORTED, restored.storedCostSource)
    }

    @Test fun frozenPricingCalculatesCostWhenProviderCostIsUnavailable() {
        val record = TokenUsageAccounting.createRecord(
            "glm-5", "DeepInfra", null, TokenCounts(100, 10, 110),
            TokenCountSource.PROVIDER_REPORTED, pricing
        )
        assertEquals(0.0001, record.inputCost!!, 0.000000001)
        assertEquals(0.00002, record.outputCost!!, 0.000000001)
        assertEquals(0.00012, record.totalCost!!, 0.000000001)
        assertEquals(CostSource.FROZEN_PRICING, record.storedCostSource)
    }

    @Test fun exactDiscountedProviderCostIsNotRecalculatedAtNominalPrice() {
        val record = TokenUsageAccounting.createRecord(
            "cached-model", "Provider", null, TokenCounts(10_000, 500, 10_500),
            TokenCountSource.PROVIDER_REPORTED,
            TokenPricingSnapshot(0.00001, 0.00002),
            ProviderReportedCost(totalCost = 0.0123)
        )
        assertEquals(0.0123, record.totalCost!!, 0.000000001)
        assertFalse(record.totalCost == 0.11)
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

    @Test fun groupingIgnoresModelAndProviderCapitalizationButPreservesDisplayText() {
        val summary = TokenUsageAccounting.aggregate(listOf(
            record("GLM-5", "DeepInfra", 10, 2),
            record("glm-5", "deepinfra", 20, 3)
        ))
        assertEquals(1, summary.groups.size)
        assertEquals("GLM-5", summary.groups.single().model)
        assertEquals("DeepInfra", summary.groups.single().provider)
        assertEquals(30, summary.groups.single().inputTokens)
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

    @Test fun mixedKnownAndUnknownTokenCountsRemainUnknownAtTheUiBoundary() {
        val known = TurnUsageRecord(
            model = "glm-5", provider = "DeepInfra", inputTokens = 10,
            outputTokens = 2, totalTokens = 12
        )
        val incomplete = TurnUsageRecord(
            model = "glm-5", provider = "DeepInfra", inputTokens = null,
            outputTokens = 3, totalTokens = null
        )
        val group = TokenUsageAccounting.aggregate(listOf(known, incomplete)).groups.single()
        assertEquals(10, group.inputTokens)
        assertTrue(group.hasUnknownInputTokens)
        assertEquals(UsageValueFormatter.NOT_REPORTED,
            UsageValueFormatter.tokens(group.inputTokens, group.hasUnknownInputTokens))
        assertEquals("5", UsageValueFormatter.tokens(group.outputTokens, group.hasUnknownOutputTokens))
    }

    @Test fun mixedKnownAndUnknownCostsDoNotPresentPartialTotalAsComplete() {
        val known = record("glm-5", "DeepInfra", 10, 2)
        val unknown = TokenUsageAccounting.createRecord(
            "glm-5", "DeepInfra", null, TokenCounts(5, 1, 6),
            TokenCountSource.PROVIDER_REPORTED, TokenPricingSnapshot()
        )
        val group = TokenUsageAccounting.aggregate(listOf(known, unknown)).groups.single()
        assertTrue(group.totalCost > 0.0)
        assertTrue(group.hasUnknownCost)
        assertEquals(UsageValueFormatter.NOT_REPORTED,
            UsageValueFormatter.cost(group.totalCost, group.hasUnknownCost))
    }

    @Test fun knownZeroFreeModelCostStaysARealZero() {
        val free = TokenUsageAccounting.createRecord(
            "free-model", "Provider", null, TokenCounts(10, 2, 12),
            TokenCountSource.PROVIDER_REPORTED, TokenPricingSnapshot(0.0, 0.0)
        )
        val group = TokenUsageAccounting.aggregate(listOf(free)).groups.single()
        assertFalse(group.hasUnknownCost)
        assertEquals(0.0, group.totalCost, 0.0)
        assertEquals("\$0.00000", UsageValueFormatter.cost(group.totalCost, group.hasUnknownCost))
    }

    @Test fun multiPricingConversationWithIncompleteUsageReportsUnknownComponents() {
        val complete = record("glm-5", "DeepInfra", 10, 2)
        val incomplete = TokenUsageAccounting.createRecord(
            "kimi-k2.5", "Novita", null, TokenCounts(null, 3, null),
            TokenCountSource.PROVIDER_REPORTED, TokenPricingSnapshot()
        )
        val presentation = QuickSettingsUsagePresentation.from(
            TokenUsageAccounting.aggregate(listOf(complete, incomplete))
        )
        assertEquals(UsageCardMode.MULTI_PRICING, presentation.mode)
        assertTrue(presentation.hasUnknownInputTokens)
        assertTrue(presentation.hasUnknownCost)
        assertEquals(UsageValueFormatter.NOT_REPORTED,
            UsageValueFormatter.tokens(
                presentation.totalInputTokens, presentation.hasUnknownInputTokens
            ))
        assertEquals(UsageValueFormatter.NOT_REPORTED,
            UsageValueFormatter.cost(presentation.totalCost, presentation.hasUnknownCost))
    }

    @Test fun completedToolCallUsageSurvivesFailedContinuation() {
        val completedToolCall = record("glm-5", "DeepInfra", 10, 2)
        val messages = listOf(
            mapOf<String, Any>(
                "isBot" to false,
                "message" to "make an image",
                TokenUsageAccounting.KEY_USAGE_RECORDS to
                    TokenUsageAccounting.encodeRecords(listOf(completedToolCall))
            ),
            mapOf<String, Any>(
                "isBot" to true,
                "message" to "partial continuation",
                "state" to "failed"
            )
        )
        val summary = TokenUsageAccounting.summarizeMessages(messages) {
            throw AssertionError("failed continuation must not be estimated as completed usage")
        }
        assertEquals(1, summary.groups.single().recordCount)
        assertEquals(10, summary.totalInputTokens)
        assertEquals(2, summary.totalOutputTokens)
    }

    @Test fun completedToolCallAndSuccessfulContinuationAreBothIncluded() {
        val completedToolCall = record("glm-5", "DeepInfra", 10, 2)
        val continuation = record("glm-5", "DeepInfra", 20, 3)
        val messages = listOf(
            mapOf<String, Any>(
                "isBot" to false,
                "message" to "make an image",
                TokenUsageAccounting.KEY_USAGE_RECORDS to
                    TokenUsageAccounting.encodeRecords(listOf(completedToolCall))
            ),
            mapOf<String, Any>(
                "isBot" to true,
                "message" to "finished",
                "state" to "done",
                TokenUsageAccounting.KEY_USAGE_RECORDS to
                    TokenUsageAccounting.encodeRecords(listOf(continuation))
            )
        )
        val summary = TokenUsageAccounting.summarizeMessages(messages) {
            throw AssertionError("durable records must not invoke legacy estimation")
        }
        assertEquals(2, summary.groups.single().recordCount)
        assertEquals(30, summary.totalInputTokens)
        assertEquals(5, summary.totalOutputTokens)
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
            listOf(oldMessage)
        ) { TokenCounts(6, 4, 10) }
        val group = summary.groups.single()
        assertEquals("glm-5", group.model)
        assertEquals(6, group.inputTokens)
        assertEquals(4, group.outputTokens)
        assertTrue(group.containsEstimatedTokens)
        assertTrue(group.hasUnknownCost)
    }

    @Test fun oldUnknownAttributionAndSummaryNeverChangeWithCurrentSelection() {
        val oldMessage = mapOf<String, Any>("isBot" to true, "message" to "old reply")
        var currentSelection = "glm-5" to "DeepInfra"
        assertEquals("glm-5" to "DeepInfra", currentSelection)
        val before = TokenUsageAccounting.summarizeMessages(listOf(oldMessage)) {
            TokenCounts(6, 4, 10)
        }
        currentSelection = "kimi-k2.5" to "Novita"
        val after = TokenUsageAccounting.summarizeMessages(listOf(oldMessage)) {
            TokenCounts(6, 4, 10)
        }
        assertEquals("kimi-k2.5" to "Novita", currentSelection)
        assertEquals(TokenUsageAccounting.encodeSummary(before), TokenUsageAccounting.encodeSummary(after))
        assertEquals(TokenUsageAccounting.MODEL_NOT_REPORTED, before.groups.single().model)
        assertEquals(TokenUsageAccounting.PROVIDER_NOT_REPORTED, before.groups.single().provider)
        assertTrue(before.groups.single().hasUnknownCost)
    }

    private fun record(model: String, provider: String, input: Int, output: Int) =
        TokenUsageAccounting.createRecord(
            model, provider, null, TokenCounts(input, output, input + output),
            TokenCountSource.PROVIDER_REPORTED, pricing
        )
}
