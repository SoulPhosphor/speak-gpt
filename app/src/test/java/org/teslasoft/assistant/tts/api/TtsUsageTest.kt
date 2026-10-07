package org.teslasoft.assistant.tts.api

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.usage.CostSource
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageMeterComponent
import org.teslasoft.assistant.usage.UsageMeterUnit
import org.teslasoft.assistant.usage.UsageQuantitySource
import java.math.BigDecimal

class TtsUsageTest {
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)

    private fun resolved(host: String, model: String, openRouter: Boolean = false) = ResolvedTtsSource(
        TtsTarget("ep", model, sourceId = "api-tts:entry", voiceId = "v"),
        TtsEndpoint.from(ApiEndpointObject("My Speech", host, "key", id = "ep",
            identity = if (openRouter) ApiEndpointObject.IDENTITY_OPENROUTER else ApiEndpointObject.IDENTITY_GENERIC)))

    private fun billed(source: ResolvedTtsSource, input: String, metering: TtsMetering = TtsMetering(),
        audio: ByteArray = mp3, fetchedPrice: TtsPrice? = null) = TtsBilledSynthesis(source, input, TtsOperation.SPEECH,
        TtsAudio(audio, source.target, metering.generationId, metering, fetchedPrice))

    private val openRouter = resolved("https://openrouter.ai/api/v1", "openai/gpt-4o-mini-tts", openRouter = true)

    /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, no padding: 417 bytes and 1152 samples per frame. */
    private fun frames(count: Int, xing: Boolean = false): ByteArray {
        val frame = ByteArray(417).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte() }
        return (0 until count).fold(ByteArray(0)) { all, index ->
            val copy = frame.copyOf()
            if (xing && index == 0) "Info".forEachIndexed { i, c -> copy[4 + 32 + i] = c.code.toByte() }
            all + copy
        }
    }

    private fun price(component: String, unit: String?, amount: String, quantity: String = "1") =
        TtsPrice(listOf(TtsCharge(component, BigDecimal(amount), null, unit, BigDecimal(quantity))), true)

    @Test fun charactersAreCodePointsAndBytesAreUtf8() {
        val text = "héllo 👋"
        assertEquals(8, text.length)
        assertEquals(7L, TtsTextMeasure.characters(text))
        assertEquals(11L, TtsTextMeasure.utf8Bytes(text))
        assertEquals(0L, TtsTextMeasure.characters(""))
    }

    @Test fun openAiCharacterBillingUsesOnlyTheFetchedRateForAnyModelId() {
        val text = "a".repeat(2_000) + "👋"
        for (model in listOf("new-character-speech", "future-character-speech-snapshot")) {
            val record = TtsUsageAccounting.record(billed(resolved("https://api.openai.com/v1", model), text,
                fetchedPrice = price("characters", "character", "0.025", "1000")))
            val meter = record.meters!!.single()
            assertEquals(UsageMeterComponent.CHARACTERS, meter.component)
            assertEquals(2_001.0, meter.quantity!!, 0.0)
            assertEquals(UsageQuantitySource.LOCAL_EXACT, meter.quantitySource)
            assertEquals(0.025, meter.priceAmount!!, 0.0)
            assertEquals(0.050025, record.totalCost!!, 1e-12)
            assertEquals(CostSource.FROZEN_PRICING.storedValue, record.costSource)
            assertEquals("My Speech", record.provider)
            assertNull(record.inputTokens)
        }
    }

    @Test fun openAiTokenSpeechKeepsReportedTextInputAndAudioOutputWithoutAnyPriceInTheApp() {
        val record = TtsUsageAccounting.record(billed(resolved("https://api.openai.com/v1", "gpt-4o-mini-tts"), "Hi",
            TtsMetering(tokens = TtsReportedTokens(48, 914, 962))))
        val (input, output) = record.meters!!
        assertEquals(UsageMeterComponent.TEXT_INPUT, input.component)
        assertEquals(UsageMeterUnit.TOKEN, input.unit)
        assertEquals(48.0, input.quantity!!, 0.0)
        assertEquals(UsageMeterComponent.AUDIO_OUTPUT, output.component)
        assertEquals(914.0, output.quantity!!, 0.0)
        assertEquals(UsageQuantitySource.PROVIDER_REPORTED, output.quantitySource)
        assertTrue(record.meters!!.all { it.priceAmount == null && it.cost == null })
        assertNull(record.totalCost)
        // Without the reported usage, no audio tokens are invented.
        val unreported = TtsUsageAccounting.record(billed(resolved("https://api.openai.com/v1", "gpt-4o-mini-tts"), "Hi",
            fetchedPrice = TtsPrice(listOf(TtsCharge("input", BigDecimal("0.002"), "USD", "token"),
                TtsCharge("output", BigDecimal("0.006"), "USD", "token")), true)))
        assertTrue(unreported.meters!!.all { it.quantity == null && it.cost == null })
        assertNull(unreported.totalCost)
    }

    @Test fun elevenLabsCreditsAreNeverMislabelledAsInputCharacters() {
        val record = TtsUsageAccounting.record(billed(resolved("https://api.elevenlabs.io/v1", "eleven_flash_v2_5"),
            "Hello", TtsMetering(characterCost = 2_847)))
        val meter = record.meters!!.single()
        assertEquals(5.0, meter.quantity!!, 0.0)
        assertEquals(UsageQuantitySource.LOCAL_EXACT, meter.quantitySource)
        assertNull(meter.cost)
        assertNull(record.totalCost)
        assertEquals(CostSource.UNKNOWN.storedValue, record.costSource)
    }

    @Test fun incompletePricingStillKeepsProviderReportedTokenUsage() {
        val record = TtsUsageAccounting.record(billed(resolved("https://api.openai.com/v1", "future-speech"), "Hi",
            TtsMetering(tokens = TtsReportedTokens(10, 20, 30)), fetchedPrice = TtsPrice(emptyList(), false)))
        assertEquals(listOf(10.0, 20.0), record.meters!!.map { it.quantity })
        assertNull(record.totalCost)
    }

    @Test fun aFetchedZeroTokenRateCanEstablishZeroCostWithoutInventingTokens() {
        val record = TtsUsageAccounting.record(billed(resolved("https://api.openai.com/v1", "free-speech"), "Hi",
            fetchedPrice = TtsPrice(price("input", "token", "0").charges + price("output", "token", "0").charges, true)))
        assertTrue(record.meters!!.all { it.quantity == null })
        assertEquals(0.0, record.totalCost!!, 0.0)
    }

    @Test fun genericEndpointRecordsTheRequestWithoutGuessingAUnit() {
        val record = TtsUsageAccounting.record(billed(resolved("https://speech.example/v1", "voice-model"), "Hello"))
        assertEquals(emptyList<Any>(), record.meters)
        assertNull(record.totalCost)
    }

    @Test fun openRouterGenerationMetadataReadsTotalCostProviderAndModel() {
        val parsed = OpenRouterGenerationParser.parse("""{"data":{"id":"gen-tts-1","total_cost":0.00042,
            "provider_name":"OpenAI","model":"openai/gpt-4o-mini-tts","usage":0.00042}}""")!!
        assertEquals(0.00042, parsed.totalCost!!, 0.0)
        assertEquals("OpenAI", parsed.providerName)
        assertEquals("openai/gpt-4o-mini-tts", parsed.model)
        assertNull(OpenRouterGenerationParser.parse("not json"))
        assertNull(OpenRouterGenerationParser.parse("""{"data":{"total_cost":-1}}""")!!.totalCost)
    }

    @Test fun openRouterReportedChargeIsTheTotalAndServingProviderComesFromOpenRouter() {
        val catalog = TtsProviderCatalog(listOf(
            TtsProvider("openai", "OpenAI", price("prompt", "character", "0.000015"), null, null, null, null),
            TtsProvider("azure", "Azure", price("prompt", "character", "0.000020"), null, null, null, null)), true)
        val record = TtsUsageAccounting.resolve(billed(openRouter, "Hello", TtsMetering(generationId = "gen-1")),
            generationLookup = { _, id -> assertEquals("gen-1", id); OpenRouterGeneration(0.0001, "Azure", "openai/gpt-4o-mini-tts") },
            catalogLookup = { catalog })
        assertEquals(0.0001, record.totalCost!!, 0.0)
        assertEquals(CostSource.PROVIDER_REPORTED.storedValue, record.costSource)
        assertEquals("Azure", record.provider)
        // The detail row uses the serving provider's frozen price, not the requested or cheapest one.
        assertEquals(5 * 0.000020, record.meters!!.single().cost!!, 1e-15)
    }

    @Test fun openRouterLookupFailureFallsBackWithoutFailingOrChoosingAProvider() {
        val shared = TtsProviderCatalog(listOf(
            TtsProvider("a", "A", price("prompt", "characters", "0.000015"), null, null, null, null),
            TtsProvider("b", "B", price("prompt", "characters", "0.0000150"), null, null, null, null)), true)
        val record = TtsUsageAccounting.resolve(billed(openRouter, "Hello", TtsMetering(generationId = "gen-1")),
            generationLookup = { _, _ -> throw IllegalStateException("unavailable") }, catalogLookup = { shared })
        assertEquals(TokenUsageAccounting.PROVIDER_NOT_REPORTED, record.provider)
        assertEquals(5 * 0.000015, record.totalCost!!, 1e-15)
        assertEquals(CostSource.FROZEN_PRICING.storedValue, record.costSource)
        val differing = TtsProviderCatalog(listOf(
            TtsProvider("a", "A", price("prompt", "characters", "0.000015"), null, null, null, null),
            TtsProvider("b", "B", price("prompt", "characters", "0.00003"), null, null, null, null)), true)
        val unknown = TtsUsageAccounting.resolve(billed(openRouter, "Hello", TtsMetering(generationId = "gen-1")),
            generationLookup = { _, _ -> null }, catalogLookup = { differing })
        assertNull(unknown.totalCost)
        assertTrue(unknown.meters!!.isEmpty())
    }

    @Test fun generationClientRetriesThenNeverThrows() {
        var calls = 0
        val ok = OpenRouterGenerationClient(FakeHttp {
            calls++
            if (calls == 1) response("""{"error":{"message":"not found"}}""", 404)
            else response("""{"data":{"total_cost":0.002,"provider_name":"OpenAI"}}""")
        }, sleep = {}).lookup(openRouter, "gen-1", TtsOperation.SPEECH)
        assertEquals(0.002, ok!!.totalCost!!, 0.0)
        assertEquals(2, calls)
        val http = FakeHttp { throw java.io.IOException("offline") }
        assertNull(OpenRouterGenerationClient(http, sleep = {}).lookup(openRouter, "gen-1", TtsOperation.SPEECH))
        assertEquals("https://openrouter.ai/api/v1/generation?id=gen-1", http.requests.first().url.toString())
        assertEquals(3, http.requests.size)
    }

    @Test fun unitsComeOnlyFromThePriceAndAreMeasuredExactly() {
        val text = "héllo 👋"
        val bytes = TtsUsageAccounting.openRouterMeters(price("prompt", "utf8_bytes", "0.000001"), text, mp3)!!.single()
        assertEquals(UsageMeterComponent.UTF8_BYTES, bytes.component)
        assertEquals(11.0, bytes.quantity!!, 0.0)
        val chars = TtsUsageAccounting.openRouterMeters(price("prompt", "character", "0.000001"), text, mp3)!!.single()
        assertEquals(7.0, chars.quantity!!, 0.0)
        // A price without a stated unit, or with one this app cannot measure, is not applied.
        assertNull(TtsUsageAccounting.openRouterMeters(price("prompt", null, "0.000015"), text, mp3))
        assertNull(TtsUsageAccounting.openRouterMeters(price("prompt", "request", "0.01"), text, mp3))
        // Token-billed speech: OpenRouter's speech response reports no tokens, so they stay unknown.
        val tokens = TtsUsageAccounting.openRouterMeters(TtsPrice(price("prompt", "token", "0.0000005").charges +
            price("completion", "token", "0.00001").charges, true), text, mp3)!!
        assertEquals(listOf(UsageMeterComponent.TEXT_INPUT, UsageMeterComponent.AUDIO_OUTPUT), tokens.map { it.component })
        assertTrue(tokens.all { it.quantity == null })
    }

    @Test fun durationBillingUsesTheAudioReturnedNotTheTextSent() {
        val audio = frames(10)
        assertEquals(10 * 1152.0 / 44100, Mp3Duration.seconds(audio)!!, 1e-9)
        // A Xing/Info header frame carries no audio and a truncated frame is not counted.
        assertEquals(9 * 1152.0 / 44100, Mp3Duration.seconds(frames(10, xing = true))!!, 1e-9)
        assertEquals(10 * 1152.0 / 44100, Mp3Duration.seconds(audio + byteArrayOf(0xFF.toByte(), 0xFB.toByte()))!!, 1e-9)
        assertNull(Mp3Duration.seconds(byteArrayOf(1, 2, 3, 4)))
        val perMinute = price("completion", "minute", "0.6")
        val short = TtsUsageAccounting.openRouterMeters(perMinute, "Hi", audio)!!.single()
        val long = TtsUsageAccounting.openRouterMeters(perMinute, "A much longer sentence ".repeat(50), audio)!!.single()
        assertEquals(short.quantity!!, long.quantity!!, 0.0)
        assertEquals(UsageMeterUnit.SECOND, short.unit)
        assertEquals(60.0, short.priceQuantity!!, 0.0)
        assertEquals(10 * 1152.0 / 44100 * 0.01, short.withCalculatedCost().cost!!, 1e-12)
    }
}
