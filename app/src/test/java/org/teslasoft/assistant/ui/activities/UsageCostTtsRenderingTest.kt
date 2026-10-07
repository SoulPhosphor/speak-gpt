package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.R
import org.teslasoft.assistant.usage.CostSource
import org.teslasoft.assistant.usage.MeteredUsageAccounting
import org.teslasoft.assistant.usage.TokenCountSource
import org.teslasoft.assistant.usage.TokenCounts
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.TurnUsageRecord
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageGroup
import org.teslasoft.assistant.usage.UsageLog
import org.teslasoft.assistant.usage.UsageMeter
import org.teslasoft.assistant.usage.UsageMeterComponent
import org.teslasoft.assistant.usage.UsageMeterUnit
import org.teslasoft.assistant.usage.UsageQuantitySource
import org.teslasoft.assistant.usage.UsageValueFormatter

/** The TTS card's content, read through the same presentation the screen binds. */
class UsageCostTtsRenderingTest {
    private fun meter(component: UsageMeterComponent, unit: UsageMeterUnit, quantity: Double?, amount: Double?,
        basis: Double?) = UsageMeter(component, unit, quantity, quantity?.let { UsageQuantitySource.LOCAL_EXACT },
        amount, basis, amount?.let { "USD" })

    private val chat = TurnUsageRecord(model = "gpt-chat", provider = "Chat Provider", inputTokens = 100,
        outputTokens = 50, totalTokens = 150, cachedInputTokens = 40, cacheWriteInputTokens = 0,
        source = TokenCountSource.PROVIDER_REPORTED.storedValue, inputPricePerToken = 0.000001,
        outputPricePerToken = 0.000002, cachedInputPricePerToken = 0.0000005, inputCost = 0.00008,
        outputCost = 0.0001, uncachedInputCost = 0.00006, cachedInputCost = 0.00002, totalCost = 0.00018,
        costSource = CostSource.FROZEN_PRICING.storedValue)

    private val tts = listOf(
        MeteredUsageAccounting.record("tts-1", "Characters Co", null, listOf(meter(UsageMeterComponent.CHARACTERS,
            UsageMeterUnit.CHARACTER, 2_847.0, 15.0, 1_000_000.0)), null),
        MeteredUsageAccounting.record("mini-tts", "Tokens Co", null, listOf(
            meter(UsageMeterComponent.TEXT_INPUT, UsageMeterUnit.TOKEN, 48.0, 0.6, 1_000_000.0),
            meter(UsageMeterComponent.AUDIO_OUTPUT, UsageMeterUnit.TOKEN, 914.0, 12.0, 1_000_000.0)), null),
        MeteredUsageAccounting.record("duration-tts", "Seconds Co", null, listOf(meter(UsageMeterComponent.AUDIO_OUTPUT,
            UsageMeterUnit.SECOND, 12.4, 0.01, 1.0)), null),
        MeteredUsageAccounting.record("bytes-tts", "Bytes Co", null, listOf(meter(UsageMeterComponent.UTF8_BYTES,
            UsageMeterUnit.BYTE, 3_012.0, 0.000001, 1.0)), null))

    private val noEstimate: (Int) -> TokenCounts = { TokenCounts(null, null, null) }
    private val log = UsageLog.EMPTY.append(listOf(UsageLog.entry(UsageCategory.CHAT, "m", chat, 1L)) +
        tts.map { UsageLog.entry(UsageCategory.TTS, null, it, 2L) })

    /** Exactly what the screen receives: the sections after their trip through the Intent. */
    private fun group(provider: String): UsageGroup = UsageLog.decodeSections(
        UsageLog.encodeSections(log.sections(emptyList(), noEstimate))
    ).flatMap { it.summary.groups }.single { it.provider == provider }

    private fun rows(provider: String) = MeteredUsagePresentation.rows(group(provider).meters!!) { "$it sec" }
    private fun prices(provider: String) = MeteredUsagePresentation.prices(group(provider).meters!!, "Variable")
    private fun captions(provider: String) = MeteredUsagePresentation.captions(group(provider).meters!!)

    @Test fun characterPricedRequestShowsOnlyCharacters() {
        assertEquals(listOf(MeteredUsagePresentation.Row(R.string.usage_meter_characters, "2,847",
            UsageValueFormatter.cost(2_847 * 15.0 / 1e6, false))), rows("Characters Co"))
        assertEquals(listOf(MeteredUsagePresentation.Price(R.string.usage_meter_characters, "\$15.00")),
            prices("Characters Co"))
        assertEquals(listOf(R.string.usage_price_per_million_characters), captions("Characters Co"))
    }

    @Test fun tokenPricedRequestShowsTextInputAndAudioOutput() {
        assertEquals(listOf(R.string.usage_meter_text_input to "48", R.string.usage_meter_audio_output to "914"),
            rows("Tokens Co").map { it.label to it.quantity })
        assertEquals(listOf("\$0.60", "\$12.00"), prices("Tokens Co").map { it.value })
        assertEquals(listOf(R.string.usage_price_per_million), captions("Tokens Co"))
    }

    @Test fun durationAndBytePricedRequestsShowTheirOwnUnits() {
        assertEquals(listOf(MeteredUsagePresentation.Row(R.string.usage_meter_audio_output, "12.4 sec", "\$0.12400")),
            rows("Seconds Co"))
        assertEquals("\$0.60", prices("Seconds Co").single().value)
        assertEquals(listOf(R.string.usage_price_per_minute_audio), captions("Seconds Co"))
        assertEquals(listOf(R.string.usage_meter_utf8_bytes to "3,012"), rows("Bytes Co").map { it.label to it.quantity })
        assertEquals("\$1.00", prices("Bytes Co").single().value)
        assertEquals(listOf(R.string.usage_price_per_million_utf8_bytes), captions("Bytes Co"))
    }

    @Test fun unknownAndVariableValuesAreLabeledNotGuessed() {
        val unknown = MeteredUsageAccounting.record("m", "P", null, listOf(meter(UsageMeterComponent.CHARACTERS,
            UsageMeterUnit.CHARACTER, null, null, null)), null)
        val totals = MeteredUsageAccounting.aggregate(listOf(unknown))!!
        assertEquals(listOf(MeteredUsagePresentation.Row(R.string.usage_meter_characters, "Not Reported", "Not Reported")),
            MeteredUsagePresentation.rows(totals) { it })
        assertEquals("Not Reported", MeteredUsagePresentation.prices(totals, "Variable").single().value)
        val variable = MeteredUsageAccounting.aggregate(listOf(tts[0], MeteredUsageAccounting.record("tts-1", "P", null,
            listOf(meter(UsageMeterComponent.CHARACTERS, UsageMeterUnit.CHARACTER, 1.0, 30.0, 1_000_000.0)), null)))!!
        assertEquals("Variable", MeteredUsagePresentation.prices(variable, "Variable").single().value)
    }

    @Test fun ttsCardHasNoCacheRowsAndChatCardIsUnchanged() {
        // Chat groups carry no meters, so the screen takes its unchanged token path.
        val chatGroup = group("Chat Provider")
        assertNull(chatGroup.meters)
        assertEquals(60, chatGroup.uncachedInputTokens)
        assertEquals(40, chatGroup.cachedInputTokens)
        val screen = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("org/teslasoft/assistant/ui/activities/TokenPricingDetailsActivity.kt").readText()
        val metered = screen.substringAfter("private fun bindMeteredRows").substringBefore("private fun bindUsageRow")
        assertTrue(metered.contains("R.id.cache_hit_rate_box).visibility = View.GONE"))
        assertTrue(metered.contains("table.removeAllViews()"))
        assertFalse(metered.contains("usage_cached"))
        assertFalse(metered.contains("usage_cache_hit_rate"))
        val layout = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
            .resolve("layout/view_usage_provider_block.xml").readText()
        for (id in listOf("usage_table", "cache_hit_rate_box", "price_facts", "price_caption"))
            assertTrue(id, layout.contains("@+id/$id"))
    }

    @Test fun conversationTotalIncludesKnownTtsCost() {
        val expected = 0.00018 + 2_847 * 15.0 / 1e6 + (48 * 0.6 + 914 * 12.0) / 1e6 + 12.4 * 0.01 + 3_012 * 0.000001
        val summary = TokenUsageAccounting.decodeSummary(TokenUsageAccounting.encodeSummary(log.summarize(emptyList(), noEstimate)))
        assertEquals(expected, summary.totalCost, 1e-12)
        assertFalse(summary.hasUnknownCost)
    }
}
