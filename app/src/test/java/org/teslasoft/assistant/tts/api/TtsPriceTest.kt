package org.teslasoft.assistant.tts.api

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.providers.SortDirection
import java.math.BigDecimal

class TtsPriceTest {
    private fun price(amount: String?, unit: String = "tokens", quantity: String = "1", currency: String = "USD",
        component: String = "input") = TtsPrice(listOf(TtsCharge(component, amount?.toBigDecimal(), currency,
            unit, quantity.toBigDecimal())), true)
    private fun provider(id: String, price: TtsPrice) = TtsProvider(id, id, price, null, null, null, null)
    private fun order(rows: List<TtsProvider>, direction: SortDirection) = TtsProviderSort(price = direction).apply(rows).map { it.id }

    @Test fun equivalentScalesCompareWithoutRounding() {
        assertEquals(0, TtsPriceComparator.compare(price("2", quantity = "1000"),
            price("2000", quantity = "1000000"), SortDirection.LOW_TO_HIGH))
        assertEquals(0, TtsPriceComparator.compare(price("0.6", "minute"),
            price("0.01", "second"), SortDirection.LOW_TO_HIGH))
    }

    @Test fun groupOrderIsStableAndDirectionOnlyReversesWithinGroup() {
        val rows = listOf(provider("tokensHigh", price("2")), provider("tokensLow", price("1")),
            provider("characters", price("9", "characters")), provider("minutes", price("9", "minute")),
            provider("unknown", price(null)), provider("free", price("0")))
        assertEquals(listOf("free", "minutes", "characters", "tokensLow", "tokensHigh", "unknown"), order(rows, SortDirection.LOW_TO_HIGH))
        assertEquals(listOf("free", "minutes", "characters", "tokensHigh", "tokensLow", "unknown"), order(rows, SortDirection.HIGH_TO_LOW))
        assertEquals(rows.map { it.id }.sorted(), TtsProviderSort().apply(rows).map { it.id })
    }

    @Test fun currenciesAndChargeSchemasRemainSeparate() {
        val rows = listOf(provider("usd", price("1")), provider("eur", price("999", currency = "EUR")),
            provider("output", price("0.01", component = "output")))
        assertEquals(listOf("eur", "usd", "output"), order(rows, SortDirection.LOW_TO_HIGH))
        assertEquals(listOf("eur", "usd", "output"), order(rows, SortDirection.HIGH_TO_LOW))
    }

    @Test fun paidInputAndZeroOutputAreNotFreeAndCompareInputFirst() {
        val a = TtsPrice(price("1").charges + price("0", component = "output").charges, true)
        val b = TtsPrice(price("0.5").charges + price("100", component = "output").charges, true)
        assertFalse(a.free)
        assertTrue(TtsPriceComparator.compare(a, b, SortDirection.LOW_TO_HIGH) > 0)
        assertFalse(a.copy(complete = false).free)
    }

    @Test fun missingRateOrBasisIsLastAndTinyPaidRateStaysVisible() {
        val tiny = price("0.0000000000000000000000001")
        assertTrue(tiny.display().contains("0.0000000000000000000000001"))
        assertFalse(tiny.free)
        val unknown = TtsPrice(listOf(TtsCharge("input", BigDecimal.ONE, "USD", null)), true)
        for (direction in listOf(SortDirection.HIGH_TO_LOW, SortDirection.LOW_TO_HIGH))
            assertTrue(TtsPriceComparator.compare(unknown, tiny, direction) > 0)
    }

    @Test fun parserPreservesComponentsAndDoesNotGuessUnitOrZero() {
        val parsed = TtsProviderParser.price(JsonParser.parseString("""{"prompt":"0.000015","completion":"0"}""").asJsonObject)
        assertFalse(parsed.free)
        assertEquals(listOf("input", "output"), parsed.charges.map { it.component })
        assertNull(parsed.charges.first().unit)
        assertFalse(TtsPriceComparator.known(parsed))
        assertFalse(TtsProviderParser.price(JsonParser.parseString("""{"completion":"0"}""").asJsonObject).free)
    }

    @Test fun optionalMetadataStaysUnknownAndExplicitFalseStaysFalse() {
        val parsed = TtsProviderParser.parse("""{"data":{"endpoints":[
            {"tag":"a","provider_name":"A","supported_parameters":[],"zdr":false,"training":false,
             "latency_last_30m":{"p50":0.3},"uptime_last_30m":99.5,"uptime_last_24h":100},
            {"tag":"b","provider_name":"B"}
        ]}}""")
        assertEquals(false, parsed.providers[0].zdr)
        assertEquals(false, parsed.providers[0].training)
        assertEquals("latency_last_30m:p50", parsed.providers[0].latency?.definition)
        assertEquals(99.5, parsed.providers[0].uptime!!.value, 0.0)
        assertNull(parsed.providers[1].latency)
        assertNull(parsed.providers[1].training)
        assertNull(parsed.providers[1].zdr)
    }

    @Test fun extraPaidComponentCannotDisappearBehindZeroInputAndOutput() {
        val p = TtsProviderParser.price(JsonParser.parseString(
            """{"prompt":"0","completion":"0","audio_output":"0.5","unit":"minute","currency":"USD"}""").asJsonObject)
        assertFalse(p.free)
        assertTrue(p.charges.any { it.component == "audio_output" && it.amount == BigDecimal("0.5") })
    }

    @Test fun singleReportedRateIsComparableButInvalidQuantityIsNotReplaced() {
        val p = TtsProviderParser.price(JsonParser.parseString(
            """{"amount":"0.02","unit":"minute","currency":"USD"}""").asJsonObject)
        assertTrue(TtsPriceComparator.known(p))
        val invalid = TtsProviderParser.price(JsonParser.parseString(
            """{"amount":"0.02","unit":"minute","currency":"USD","quantity":-1}""").asJsonObject)
        assertFalse(TtsPriceComparator.known(invalid))
    }

    @Test fun zdrOverlayRequiresExactModelAndProviderAndAbsenceStaysUnknown() {
        val catalog = TtsProviderParser.parse("""{"endpoints":[{"tag":"a","provider_name":"A"},{"tag":"b","provider_name":"B"}]}""")
        val updated = TtsProviderParser.overlayZdr(catalog,
            """{"data":[{"tag":"a","model_id":"exact"},{"tag":"b","model_id":"other"}],"has_more":true}""", "exact")
        assertEquals(true, updated.providers[0].zdr)
        assertNull(updated.providers[1].zdr)
    }
}
