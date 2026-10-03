package org.teslasoft.assistant.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSummarySerializationTest {
    @Test
    fun summaryRoundTripRestoresConcreteUsageGroupsAndComputedTotals() {
        val original = ConversationUsageSummary(
            listOf(
                UsageGroup(
                    model = "glm-5",
                    provider = "DeepInfra",
                    inputTokens = 12,
                    outputTokens = 3,
                    uncachedInputTokens = 10,
                    cachedInputTokens = 2,
                    inputCost = 0.000012,
                    outputCost = 0.000006,
                    uncachedInputCost = 0.000010,
                    cachedInputCost = 0.000002,
                    totalCost = 0.000018,
                    inputPricePerToken = 0.000001,
                    outputPricePerToken = 0.000002,
                    cachedInputPricePerToken = 0.0000001,
                    hasUnknownInputTokens = false,
                    hasUnknownOutputTokens = false,
                    hasUnknownUncachedInputTokens = false,
                    hasUnknownCachedInputTokens = false,
                    hasUnknownInputCost = false,
                    hasUnknownOutputCost = false,
                    hasUnknownUncachedInputCost = false,
                    hasUnknownCachedInputCost = false,
                    hasUnknownCost = false,
                    hasVariableInputPricing = false,
                    hasVariableOutputPricing = false,
                    hasVariableCachedInputPricing = false,
                    hasVariablePricing = false,
                    containsEstimatedTokens = false,
                    recordCount = 1
                )
            )
        )

        val decoded = TokenUsageAccounting.decodeSummary(
            TokenUsageAccounting.encodeSummary(original)
        )

        assertEquals(1, decoded.groups.size)
        assertEquals(UsageGroup::class.java, decoded.groups.single()::class.java)
        assertEquals("glm-5", decoded.groups.single().model)
        assertEquals("DeepInfra", decoded.groups.single().provider)
        assertEquals(12, decoded.totalInputTokens)
        assertEquals(3, decoded.totalOutputTokens)
        assertEquals(0.000018, decoded.totalCost, 0.000000001)
    }

    @Test
    fun summaryDecoderFailsClosedForMissingOrMalformedGroups() {
        assertTrue(TokenUsageAccounting.decodeSummary(null).groups.isEmpty())
        assertTrue(TokenUsageAccounting.decodeSummary("").groups.isEmpty())
        assertTrue(TokenUsageAccounting.decodeSummary("{}").groups.isEmpty())
        assertTrue(TokenUsageAccounting.decodeSummary("{\"groups\":{}}").groups.isEmpty())
        assertTrue(TokenUsageAccounting.decodeSummary("not json").groups.isEmpty())
    }
}
