package org.teslasoft.assistant.usage

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRetryUsageTest {
    private val failed = TurnUsageRecord(
        model = "model", provider = "Provider", inputTokens = 100,
        outputTokens = 2, totalTokens = 102, cachedInputTokens = 40,
        source = TokenCountSource.PROVIDER_REPORTED.storedValue,
        inputPricePerToken = 0.000001, outputPricePerToken = 0.000002,
        totalCost = 0.125, costSource = CostSource.PROVIDER_REPORTED.storedValue
    )
    private val completed = failed.copy(outputTokens = 20, totalTokens = 120, totalCost = 0.25)

    @Test fun failedAttemptAndNoToolsRetryCountOnceWithoutChangingFrozenValues() {
        val user = message(false, "request", listOf(completed))
        val messages = mutableListOf(user, message(true, "", listOf(failed)))

        assertTrue(TokenUsageAccounting.removeEmptyAssistantForToolRetry(messages))
        assertEquals(listOf(completed, failed), stored(user))
        assertEquals("request", user["message"])
        assertFalse(user.containsKey("responseTokens"))
        assertFalse(TokenUsageAccounting.removeEmptyAssistantForToolRetry(messages))
        messages.add(message(true, "retry reply", listOf(completed)))

        val summary = TokenUsageAccounting.summarizeMessages(messages) {
            throw AssertionError("frozen attempts must not invoke estimation")
        }
        assertEquals(3, summary.groups.single().recordCount)
        assertEquals(300, summary.totalInputTokens)
        assertEquals(42, summary.totalOutputTokens)
        assertEquals(0.625, summary.totalCost, 0.000000001)
    }

    @Test fun variantRecordsMoveWithoutDoubleCountingTheirCanonicalMirror() {
        val user = message(false, "request")
        val placeholder = message(true, "", listOf(failed))
        placeholder["variants"] = Gson().toJson(listOf(
            mapOf(TokenUsageAccounting.KEY_USAGE_RECORDS to
                TokenUsageAccounting.encodeRecords(listOf(completed))),
            mapOf(TokenUsageAccounting.KEY_USAGE_RECORDS to
                TokenUsageAccounting.encodeRecords(listOf(failed)))
        ))
        val messages = mutableListOf(user, placeholder)

        assertTrue(TokenUsageAccounting.removeEmptyAssistantForToolRetry(messages))
        assertEquals(listOf(completed, failed), stored(user))
        assertEquals(1, messages.size)
    }

    @Test fun costOnlyTerminalRecordIsPreservedWithoutInventingCounts() {
        val costOnly = failed.copy(inputTokens = null, outputTokens = null,
            totalTokens = null, cachedInputTokens = null)
        val user = message(false, "request")
        val messages = mutableListOf(user, message(true, "", listOf(costOnly)))

        assertTrue(TokenUsageAccounting.removeEmptyAssistantForToolRetry(messages))
        assertEquals(listOf(costOnly), stored(user))
    }

    @Test fun emptyUnbilledPlaceholderStillRemovesWithoutAddingUsage() {
        val user = message(false, "request")
        val messages = mutableListOf(user, message(true, ""))

        assertTrue(TokenUsageAccounting.removeEmptyAssistantForToolRetry(messages))
        assertEquals(listOf(user), messages)
        assertFalse(user.containsKey(TokenUsageAccounting.KEY_USAGE_RECORDS))
    }

    @Test fun visibleRepliesAndUserMessagesAreNotRemoved() {
        val messages = mutableListOf(message(false, "request"), message(true, "partial", listOf(failed)))
        val before = Gson().toJson(messages)

        assertFalse(TokenUsageAccounting.removeEmptyAssistantForToolRetry(messages))
        assertEquals(before, Gson().toJson(messages))
        assertFalse(TokenUsageAccounting.removeEmptyAssistantForToolRetry(
            mutableListOf(message(false, ""))))
    }

    @Test fun reportedUsageIsRetainedWhenNoUserCarrierExists() {
        val placeholder = message(true, "", listOf(failed))
        val messages = mutableListOf(placeholder)

        assertFalse(TokenUsageAccounting.removeEmptyAssistantForToolRetry(messages))
        assertEquals(listOf(placeholder), messages)
        assertEquals(listOf(failed), stored(placeholder))
    }

    private fun message(bot: Boolean, text: String, records: List<TurnUsageRecord> = emptyList()) =
        hashMapOf<String, Any>("isBot" to bot, "message" to text).apply {
            if (records.isNotEmpty()) {
                put(TokenUsageAccounting.KEY_USAGE_RECORDS, TokenUsageAccounting.encodeRecords(records))
            }
        }

    private fun stored(message: Map<String, Any>) =
        TokenUsageAccounting.decodeRecords(message[TokenUsageAccounting.KEY_USAGE_RECORDS]?.toString())
}
