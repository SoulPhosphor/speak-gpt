package org.teslasoft.assistant.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity

class UsageLogTest {
    private fun record(model: String, input: Int, output: Int, cost: Double) = TurnUsageRecord(
        model = model, provider = "Provider", inputTokens = input, outputTokens = output,
        totalTokens = input + output, cachedInputTokens = 0, source = TokenCountSource.PROVIDER_REPORTED.storedValue,
        inputCost = cost / 2, outputCost = cost / 2, uncachedInputCost = cost / 2,
        cachedInputCost = 0.0, totalCost = cost, costSource = CostSource.FROZEN_PRICING.storedValue
    )

    private fun reply(id: String, vararg records: TurnUsageRecord): HashMap<String, Any> = hashMapOf(
        "isBot" to true,
        "message" to "reply $id",
        MessageIdentity.KEY to id,
        MessageCompletionState.KEY_STATE to MessageCompletionState.DONE,
        TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(records.toList())
    )

    private val noEstimate: (Int) -> TokenCounts = { TokenCounts(null, null, null) }

    @Test fun `seeding copies message records once with their message ids`() {
        val messages = listOf(reply("m1", record("a", 10, 5, 1.0)), reply("m2", record("b", 20, 5, 2.0)))

        val seeded = UsageLog.EMPTY.seed(messages, nowMs = 7L)
        val again = seeded.seed(messages + reply("m3", record("c", 1, 1, 9.0)), nowMs = 8L)

        assertTrue(seeded.seeded)
        assertEquals(listOf("m1", "m2"), seeded.entries.map { it.messageId })
        assertTrue(seeded.entries.all { it.category == UsageCategory.CHAT })
        assertEquals(seeded, again)
    }

    @Test fun `deleting messages never removes usage already logged`() {
        val messages = listOf(reply("m1", record("a", 10, 5, 1.0)), reply("m2", record("b", 20, 5, 2.0)))
        val log = UsageLog.EMPTY.seed(messages, nowMs = 1L)

        val afterDelete = log.summarize(messages.take(0), noEstimate)

        assertEquals(3.0, afterDelete.totalCost, 1e-9)
        assertEquals(45, afterDelete.totalInputTokens + afterDelete.totalOutputTokens)
    }

    @Test fun `entries appended before seeding are kept when the history is merged in`() {
        val summary = UsageLog.entry(UsageCategory.SUMMARIZATION, null, record("s", 100, 10, 0.5), 3L)
        val early = UsageLog.EMPTY.append(listOf(summary))
        assertFalse(early.seeded)

        val seeded = early.seed(listOf(reply("m1", record("a", 10, 5, 1.0))), nowMs = 4L)

        assertEquals(listOf(UsageCategory.CHAT, UsageCategory.SUMMARIZATION), seeded.entries.map { it.category })
        assertEquals(1.5, seeded.summarize(emptyList(), noEstimate).totalCost, 1e-9)
    }

    @Test fun `every regenerated version stays counted even when it was never used`() {
        val versions = listOf(
            hashMapOf(TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(listOf(record("sonnet-4.6", 10, 5, 1.0)))),
            hashMapOf(TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(listOf(record("gpt-5", 10, 5, 2.0))))
        )
        val message = reply("m1").apply {
            put("variants", com.google.gson.Gson().toJson(versions))
        }

        val summary = UsageLog.EMPTY.seed(listOf(message), 1L).summarize(listOf(message), noEstimate)

        assertEquals(setOf("sonnet-4.6", "gpt-5"), summary.groups.map { it.model }.toSet())
        assertEquals(3.0, summary.totalCost, 1e-9)
    }

    @Test fun `legacy replies are estimated only from messages without records`() {
        val legacy = hashMapOf<String, Any>(
            "isBot" to true, "message" to "old",
            MessageCompletionState.KEY_STATE to MessageCompletionState.DONE
        )
        val logged = reply("m1", record("a", 10, 5, 1.0))
        val log = UsageLog.EMPTY.seed(listOf(logged, legacy), 1L)

        val summary = log.summarize(listOf(logged, legacy)) { TokenCounts(4, 2, 6) }

        assertEquals(2, summary.groups.sumOf { it.recordCount })
        assertTrue(summary.groups.any { it.containsEstimatedTokens })
    }

    @Test fun `categories can be summarized separately`() {
        val log = UsageLog.EMPTY.seed(emptyList(), 1L).append(
            listOf(
                UsageLog.entry(UsageCategory.CHAT, "m1", record("a", 10, 5, 1.0)),
                UsageLog.entry(UsageCategory.ATTACHMENTS, "m1", record("a", 50, 20, 4.0)),
                UsageLog.entry(UsageCategory.SUMMARIZATION, null, record("s", 30, 10, 0.25))
            )
        )

        assertEquals(4.0, log.summarize(emptyList(), noEstimate, setOf(UsageCategory.ATTACHMENTS)).totalCost, 1e-9)
        assertEquals(5.25, log.summarize(emptyList(), noEstimate).totalCost, 1e-9)
    }

    @Test fun `log survives a save and reload unchanged`() {
        val log = UsageLog.EMPTY.seed(listOf(reply("m1", record("a", 10, 5, 1.0))), 2L).append(
            listOf(UsageLog.entry(UsageCategory.ATTACHMENTS, null, record("b", 1, 2, 0.1), 5L))
        )

        val reloaded = UsageLog.decode(UsageLog.encode(log))

        assertEquals(log, reloaded)
        assertEquals(UsageLog.EMPTY, UsageLog.decode(""))
        assertNull(UsageLog.decode("{not json"))
    }
}
