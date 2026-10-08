package org.teslasoft.assistant.usage

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class MeteredUsageTest {
    private val noEstimate: (Int) -> TokenCounts = { TokenCounts(null, null, null) }

    private fun characters(count: Double?, perMillion: Double? = 15.0) = UsageMeter(
        UsageMeterComponent.CHARACTERS, UsageMeterUnit.CHARACTER, count,
        count?.let { UsageQuantitySource.LOCAL_EXACT }, perMillion, perMillion?.let { 1_000_000.0 },
        perMillion?.let { "USD" })

    private fun tts(vararg meters: UsageMeter, reported: Double? = null, provider: String = "Provider") =
        MeteredUsageAccounting.record("speech-model", provider, "https://speech.example/v1", meters.toList(), reported)

    private fun chat(cost: Double) = TurnUsageRecord(model = "gpt", provider = "P", inputTokens = 10,
        outputTokens = 5, totalTokens = 15, cachedInputTokens = 0, source = TokenCountSource.PROVIDER_REPORTED.storedValue,
        inputCost = cost / 2, outputCost = cost / 2, uncachedInputCost = cost / 2, cachedInputCost = 0.0,
        totalCost = cost, costSource = CostSource.FROZEN_PRICING.storedValue)

    @Test fun totalIsTheSumOnlyWhenEveryBilledMeterIsPriced() {
        val priced = tts(characters(2_847.0))
        assertEquals(0.042705, priced.totalCost!!, 1e-12)
        assertEquals(CostSource.FROZEN_PRICING.storedValue, priced.costSource)
        assertNull(priced.inputTokens)
        val audio = UsageMeter(UsageMeterComponent.AUDIO_OUTPUT, UsageMeterUnit.TOKEN, null, null, 12.0, 1_000_000.0, "USD")
        assertNull(tts(characters(10.0), audio).totalCost)
        assertNull(tts(characters(10.0, perMillion = null)).totalCost)
        assertNull(tts().totalCost)
    }

    @Test fun providerReportedTotalIsKeptEvenWithoutDetailRows() {
        val record = tts(reported = 0.0042)
        assertEquals(0.0042, record.totalCost!!, 0.0)
        assertEquals(CostSource.PROVIDER_REPORTED.storedValue, record.costSource)
        val zero = tts(characters(10.0), reported = 0.0)
        assertEquals(0.0, zero.totalCost!!, 0.0)
    }

    @Test fun groupsSumMetersAndKeepVariablePricing() {
        val rows = listOf(tts(characters(100.0)), tts(characters(200.0)), tts(characters(50.0, perMillion = 30.0)))
        val total = MeteredUsageAccounting.aggregate(rows)!!.single()
        assertEquals(350.0, total.quantity, 0.0)
        assertFalse(total.hasUnknownQuantity)
        assertTrue(total.hasVariablePrice)
        assertNull(total.priceAmount)
        val same = MeteredUsageAccounting.aggregate(rows.take(2))!!.single()
        assertFalse(same.hasVariablePrice)
        assertEquals(15.0, same.priceAmount!!, 0.0)
        // A request without the component makes that row's sums unknown, not partial.
        val missing = MeteredUsageAccounting.aggregate(listOf(tts(characters(100.0)), tts()))!!.single()
        assertTrue(missing.hasUnknownQuantity)
        assertTrue(missing.hasUnknownCost)
        assertNull(MeteredUsageAccounting.aggregate(listOf(chat(1.0))))
    }

    @Test fun chatGroupsAreUnchangedAndTtsCostJoinsTheConversationTotal() {
        val log = UsageLog.EMPTY.append(listOf(
            UsageLog.entry(UsageCategory.CHAT, "m1", chat(1.0), 1L),
            UsageLog.entry(UsageCategory.TTS, null, tts(characters(1_000_000.0)), 2L)))
        val sections = log.sections(emptyList(), noEstimate)
        assertEquals(listOf(UsageCategory.CHAT, UsageCategory.TTS), sections.map { it.category })
        assertNull(sections.first().summary.groups.single().meters)
        assertEquals(15, sections.first().summary.groups.single().inputTokens + sections.first().summary.groups.single().outputTokens)
        assertEquals(15.0, sections.last().summary.totalCost, 1e-9)
        assertEquals(16.0, log.summarize(emptyList(), noEstimate).totalCost, 1e-9)
        val unknown = log.append(listOf(UsageLog.entry(UsageCategory.TTS, null, tts(characters(null)), 3L)))
        assertTrue(unknown.summarize(emptyList(), noEstimate).hasUnknownCost)
    }

    @Test fun usageLogRoundTripsMetersWithConcreteTypes() {
        val record = tts(characters(12.0), UsageMeter(UsageMeterComponent.AUDIO_OUTPUT, UsageMeterUnit.SECOND, 12.4,
            UsageQuantitySource.LOCAL_EXACT, 0.6, 60.0, "USD"))
        val state = UsageLog.EMPTY.append(listOf(UsageLog.entry(UsageCategory.TTS, null, record, 5L)))
        val encoded = UsageLog.encode(state)
        val decoded = UsageLog.decode(encoded)!!
        assertEquals(state, decoded)
        assertTrue(decoded.entries.single().record.meters!!.all { it is UsageMeter })
        // Backup copies the stored string unchanged, so a second round trip is identical.
        assertEquals(encoded, UsageLog.encode(decoded))
    }

    @Test fun logsWrittenBeforeMetersDecodeAsBefore() {
        val old = """{"version":1,"seeded":true,"entries":[{"id":"e1","category":"chat","messageId":"m1",
            "recordedAtMs":3,"record":{"model":"gpt","provider":"P","inputTokens":10,"outputTokens":5,
            "totalTokens":15,"source":"provider_reported","totalCost":0.5,"costSource":"frozen_pricing"}}]}"""
        val decoded = UsageLog.decode(old)!!
        val record = decoded.entries.single().record
        assertNull(record.meters)
        assertEquals(10, record.inputTokens)
        assertEquals(0.5, decoded.summarize(emptyList(), noEstimate).totalCost, 0.0)
    }

    @Test fun metersNeverRelyOnGenericSignaturesThatMinificationErases() {
        // Gson skips transient fields; the hand-written codec is the only path for these lists.
        assertTrue(Modifier.isTransient(TurnUsageRecord::class.java.getDeclaredField("meters").modifiers))
        assertTrue(Modifier.isTransient(UsageGroup::class.java.getDeclaredField("meters").modifiers))
        val json = Gson().toJson(tts(characters(3.0)))
        assertFalse(JsonParser.parseString(json).asJsonObject.has("meters"))
    }

    @Test fun summaryHandedToTheScreenKeepsMeterTotals() {
        val log = UsageLog.EMPTY.append(listOf(
            UsageLog.entry(UsageCategory.TTS, null, tts(characters(10.0)), 1L),
            UsageLog.entry(UsageCategory.CHAT, null, chat(1.0), 2L)))
        val sections = log.sections(emptyList(), noEstimate)
        val decoded = UsageLog.decodeSections(UsageLog.encodeSections(sections))
        assertEquals(sections.map { it.summary.groups }, decoded.map { it.summary.groups })
        val summary = TokenUsageAccounting.decodeSummary(TokenUsageAccounting.encodeSummary(log.summarize(emptyList(), noEstimate)))
        assertEquals(10.0, summary.groups.single { it.meters != null }.meters!!.single().quantity, 0.0)
        assertTrue(summary.groups.single { it.meters != null }.meters!!.single() is UsageMeterTotal)
    }

    @Test fun pricesAreNeverRoundedToAMisleadingZero() {
        assertEquals("\$15.00", UsageValueFormatter.price(15.0))
        assertEquals("\$0.0012", UsageValueFormatter.price(0.0012))
        assertEquals("\$0.62", UsageValueFormatter.price(0.62))
        assertEquals("Not Reported", UsageValueFormatter.price(null))
        assertEquals("2,847", UsageValueFormatter.count(2_847.0, false))
        assertEquals("12.4", UsageValueFormatter.seconds(12.4))
    }
}
