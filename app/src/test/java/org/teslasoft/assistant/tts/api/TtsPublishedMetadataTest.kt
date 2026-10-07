package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.usage.CostSource
import org.teslasoft.assistant.usage.UsageMeterComponent
import java.math.BigDecimal

/** Fictional catalogs/rates demonstrate that downloaded data, rather than shipped values, controls the app. */
class TtsPublishedMetadataTest {
    private val reference = """
        |- `model: string or SpeechModel`
        |  - `SpeechModel = "speech-future" or "speech-classic"`
        |- `voice: string`
        |    - `"river"`
        |    - `"pebble"`
        |- `instructions: optional string`
        |- `stream_format: optional "sse" or "audio"`
        |  `sse` is not supported for `speech-classic`.
    """.trimMargin()
    private val guide = "The `speech-classic` models support a smaller set: `pebble`."
    private fun modelDoc(id: String) = """
        |Model ID: `$id`
        |## Pricing
        |### Text tokens
        || Metric | Price | Unit |
        || Input | ${'$'}2.5 | 1M tokens |
        |### Audio tokens
        || Output | ${'$'}7.5 | 1M tokens |
        |## Endpoints
        || Speech generation | `v1/audio/speech` | Supported |
        |## Snapshots
        |- `$id-snapshot-a`
    """.trimMargin()
    private fun source(host: String = "https://api.openai.com/v1", model: String = "speech-future") =
        ResolvedTtsSource(TtsTarget("ep", model, voiceId = "river"),
            TtsEndpoint.from(ApiEndpointObject("Speech", host, "secret", id = "ep")))

    private fun page(json: String): String = "<script>self.__next_f.push([1," +
        JsonObject().apply { addProperty("s", "1:${JsonParser.parseString(json)}\n") }.get("s").toString() + "])</script>"

    @Test fun newModelsSnapshotsVoicesStreamingAndPricesComeFromDownloadedDocuments() {
        val parsed = OpenAiPublishedParser.catalog(reference, guide,
            mapOf("speech-future" to modelDoc("speech-future"), "speech-classic" to modelDoc("speech-classic")))
        assertTrue("speech-future-snapshot-a" in parsed.modelIds)
        assertEquals(listOf("river", "pebble"), parsed.voicesFor("speech-future").map { it.id })
        assertEquals(listOf("pebble"), parsed.voicesFor("speech-classic-snapshot-a").map { it.id })
        assertTrue("speech-future-snapshot-a" in parsed.sseModels)
        assertFalse("speech-classic-snapshot-a" in parsed.sseModels)
        assertEquals(BigDecimal("2.5"), parsed.priceFor("speech-future-snapshot-a")!!.charges.first().amount)
        assertEquals(BigDecimal("1000000"), parsed.priceFor("speech-future")!!.charges.first().quantity)
    }

    @Test fun abbreviatedEnumHeadingsDoNotOmitTheFullPublishedModelList() {
        val abbreviated = reference.replace("or \"speech-classic\"", "or 3 more\n    - `\"speech-classic\"`\n    - `\"speech-next\"`")
        assertEquals(setOf("speech-future", "speech-classic", "speech-next"), OpenAiPublishedParser.modelIds(abbreviated))
    }

    @Test fun unreadableBillingScalesAreNotSilentlyTreatedAsOneToken() {
        assertNull(PublishedPriceUnits.parse("1 billion tokens"))
        assertNull(PublishedPriceUnits.parse("unknown tokens"))
        assertEquals(BigDecimal("1000000") to "token", PublishedPriceUnits.parse("1M tokens"))
    }

    @Test fun modelSpecificApiVoicesTakePriorityOverTheGeneralPublishedList() {
        val http = FakeHttp { request -> when (request.url.toString()) {
            "https://api.openai.com/v1/models" -> response("""{"data":[{"id":"speech-future","supported_voices":["account-specific"]}]}""")
            TtsPublishedMetadataClient.OPENAI_REFERENCE -> response(reference)
            TtsPublishedMetadataClient.OPENAI_GUIDE -> response(guide)
            else -> response(modelDoc(request.url.encodedPath.substringAfterLast('/').removeSuffix(".md")))
        } }
        val catalog = TtsDiscoveryClient(http).voices(source(), TtsRequestGate().begin()) as TtsVoiceCatalog.Known
        assertEquals(listOf("account-specific"), catalog.voices.map { it.id })
    }

    @Test fun officialOpenAiPickerActuallyFetchesThePublishedVoiceCatalogWithoutSendingTheKey() {
        val http = FakeHttp { request -> when (request.url.toString()) {
            "https://api.openai.com/v1/models" -> response("""{"data":[{"id":"speech-future"},{"id":"speech-future-snapshot-a"},{"id":"chat-only"}]}""")
            TtsPublishedMetadataClient.OPENAI_REFERENCE -> response(reference)
            TtsPublishedMetadataClient.OPENAI_GUIDE -> response(guide)
            else -> response(modelDoc(request.url.encodedPath.substringAfterLast('/').removeSuffix(".md")))
        } }
        val voices = TtsDiscoveryClient(http).voices(source(model = "speech-future-snapshot-a"), TtsRequestGate().begin())
        assertEquals(listOf("river", "pebble"), (voices as TtsVoiceCatalog.Known).voices.map { it.id })
        assertTrue(http.requests.none { it.url.encodedPath.endsWith("/audio/voices") })
        http.requests.filter { it.url.host == "developers.openai.com" }.forEach {
            assertNull(it.header("Authorization")); assertNull(it.header("xi-api-key"))
        }
    }

    @Test fun pricesAreFrozenBeforeSpeechAndUsedWithReportedTokensInUsage() {
        val audio = byteArrayOf(73, 68, 51, 0, 0, 0)
        val encoded = java.util.Base64.getEncoder().encodeToString(audio)
        val http = FakeHttp { request -> when (request.url.toString()) {
            TtsPublishedMetadataClient.OPENAI_REFERENCE -> response(reference)
            TtsPublishedMetadataClient.OPENAI_GUIDE -> response(guide)
            "https://api.openai.com/v1/audio/speech" -> TtsHttpResponse(200,
                ("data: {\"type\":\"speech.audio.delta\",\"audio\":\"$encoded\"}\n\n" +
                    "data: {\"type\":\"speech.audio.done\",\"usage\":{\"input_tokens\":100,\"output_tokens\":200}}\n\n")
                    .toByteArray(), "text/event-stream")
            else -> response(modelDoc(request.url.encodedPath.substringAfterLast('/').removeSuffix(".md")))
        } }
        val resolved = source()
        val result = TtsSpeechTransport(http).synthesize(resolved, "Hi", TtsRequestGate().begin())
        assertEquals("/v1/audio/speech", http.requests.last().url.encodedPath)
        val record = TtsUsageAccounting.record(TtsBilledSynthesis(resolved, "Hi", TtsOperation.SPEECH, result))
        assertEquals(0.00175, record.totalCost!!, 1e-12)
        assertEquals(CostSource.FROZEN_PRICING.storedValue, record.costSource)
        assertEquals(listOf(UsageMeterComponent.TEXT_INPUT, UsageMeterComponent.AUDIO_OUTPUT), record.meters!!.map { it.component })
    }

    @Test fun openRouterPricesHaveTheirPublishedUnitsAndProviderIdentity() {
        val data = page("""[{"model_variant_slug":"vendor/new-speech","provider_slug":"provider/eu",
            "pricing_json":{"tts:characters":"0.000023"}},{"model_variant_slug":"vendor/new-speech",
            "provider_slug":"duration-provider","pricing_json":{"new_tts:output_seconds":"0.0042"}},
            {"model_variant_slug":"vendor/other","provider_slug":"other","pricing_json":{"tts:characters":"9"}}]""")
        val prices = OpenRouterPublishedPrices.parse(data, "vendor/new-speech")
        assertEquals(setOf("provider/eu", "duration-provider"), prices.keys)
        assertEquals("character", prices["provider/eu"]!!.charges.single().unit)
        assertEquals("second", prices["duration-provider"]!!.charges.single().unit)
        assertEquals(BigDecimal("0.0042"), prices["duration-provider"]!!.charges.single().amount)
        val unfamiliar = OpenRouterPublishedPrices.parse(page("""{"model_variant_slug":"m","provider_slug":"p",
            "pricing_json":{"tts:new_billing_unit":"0.01"}}"""), "m")["p"]!!
        assertFalse(unfamiliar.complete)
    }

    @Test fun elevenLabsRatesFollowTheFetchedPlanAndActivePromotionWithoutAnEmbeddedRate() {
        fun published(rate: String) = page("""{"models":[{"id":"eleven_future","name":"Future","category":"Text to Speech"}],
            "tiers":[{"id":"plan-new","title":"New plan"}],"sections":[{"id":"text_to_speech",
            "subSections":[{"label":"Future","rows":[{"label":"Price per 1K characters","values":[
            {"tierId":"plan-new","value":{"type":"currency","value":$rate,"strikethroughValue":9}}]}]}]}]}""")
        val model = JsonParser.parseString("""{"model_id":"eleven_future","name":"Future"}""").asJsonObject
        val first = ElevenLabsPublishedPrices.parse(published("0.024"), model, "plan-new")!!
        val changed = ElevenLabsPublishedPrices.parse(published("0.036"), model, "plan-new")!!
        assertEquals(BigDecimal("0.024"), first.charges.single().amount)
        assertEquals(BigDecimal("0.036"), changed.charges.single().amount)
        assertEquals(BigDecimal("1000"), changed.charges.single().quantity)
        assertNull(ElevenLabsPublishedPrices.parse(published("0.024"), model, "negotiated-plan"))
    }

    @Test fun anExplicitEndpointRateTakesPriorityOverPublicPricing() {
        val public = TtsPrice(listOf(TtsCharge("characters", BigDecimal("0.003"), "USD", "character")), true)
        val account = TtsPrice(listOf(TtsCharge("characters", BigDecimal("0.002"), "USD", "character")), true)
        assertEquals(account, OpenRouterPublishedPrices.forEndpoint(account, public))
        val unitless = account.copy(charges = account.charges.map { it.copy(unit = null) })
        assertEquals(account, OpenRouterPublishedPrices.forEndpoint(unitless, public))
        assertEquals(unitless, OpenRouterPublishedPrices.forEndpoint(unitless, null))
        val flat = TtsPrice(listOf(TtsCharge("input", BigDecimal("0.002"), null, null),
            TtsCharge("output", BigDecimal.ZERO, null, null)), true)
        assertEquals(account, OpenRouterPublishedPrices.forEndpoint(flat, public))
        val withFee = flat.copy(charges = flat.charges + TtsCharge("unknown_fee", BigDecimal.ONE, null, null))
        assertEquals(withFee, OpenRouterPublishedPrices.forEndpoint(withFee, public))
    }

    @Test fun elevenLabsFetchesAccountPlanAndVoiceBeforeApplyingThePublishedRate() {
        val published = page("""{"models":[{"id":"speech-future","name":"Future","category":"Text to Speech"}],
            "tiers":[{"id":"plan-new","title":"New plan"}],"sections":[{"id":"text_to_speech",
            "subSections":[{"label":"Future","rows":[{"label":"Price per 1K characters","values":[
            {"tierId":"plan-new","value":{"type":"currency","value":0.036}}]}]}]}]}""")
        val http = FakeHttp { request -> when {
            request.url.toString() == TtsPublishedMetadataClient.ELEVENLABS_PRICING -> response(published)
            request.url.encodedPath == "/v1/user/subscription" -> response("""{"tier":"plan-new"}""")
            request.url.encodedPath == "/v1/models" -> response("""[{"model_id":"speech-future","name":"Future"}]""")
            request.url.encodedPath == "/v1/voices/river" -> response("""{"voice_id":"river","sharing":null}""")
            else -> TtsHttpResponse(200, byteArrayOf(73, 68, 51, 0, 0, 0), "audio/mpeg")
        } }
        val resolved = source("https://api.elevenlabs.io/v1")
        val audio = TtsSpeechTransport(http).synthesize(resolved, "Hello", TtsRequestGate().begin())
        assertEquals("POST", http.requests.last().method)
        http.requests.filter { it.url.host == "api.elevenlabs.io" }.forEach {
            assertEquals("secret", it.header("xi-api-key")); assertNull(it.header("Authorization"))
        }
        http.requests.filter { it.url.host == "elevenlabs.io" }.forEach {
            assertNull(it.header("xi-api-key")); assertNull(it.header("Authorization"))
        }
        val record = TtsUsageAccounting.record(TtsBilledSynthesis(resolved, "Hello", TtsOperation.SPEECH, audio))
        assertEquals(0.00018, record.totalCost!!, 1e-12)
        assertEquals(CostSource.FROZEN_PRICING.storedValue, record.costSource)
    }

    @Test fun aMetadataOutageDoesNotPreventSpeechOrInventAPrice() {
        val http = FakeHttp { request -> if (request.method == "POST")
            TtsHttpResponse(200, byteArrayOf(73, 68, 51, 0, 0, 0), "audio/mpeg")
            else response("unavailable", 503) }
        val resolved = source()
        val result = TtsSpeechTransport(http).synthesize(resolved, "Hi", TtsRequestGate().begin())
        assertNull(result.price)
        assertEquals(1, http.requests.count { it.method == "POST" })
        assertNull(TtsUsageAccounting.record(TtsBilledSynthesis(resolved, "Hi", TtsOperation.SPEECH, result)).totalCost)
    }

    @Test fun brokenPublicationCannotProduceAnInventedCatalogOrPartialBill() {
        assertThrows(IllegalArgumentException::class.java) { OpenAiPublishedParser.modelIds("not a catalog") }
        val price = OpenAiPublishedParser.modelPrice(modelDoc("m").replace("1M tokens |\n## Endpoints", "unknown units |\n## Endpoints"))
        assertFalse(price.complete)
        assertTrue(OpenRouterPublishedPrices.parse("broken page", "m").isEmpty())
        assertNull(ElevenLabsPublishedPrices.parse("broken page", JsonObject(), "plan"))
        assertFalse(OpenAiPublishedParser.modelPrice(modelDoc("m").substringBefore("### Audio tokens") +
            "\n## Endpoints\n").complete)
    }

    @Test fun largePublishedJsonStringsAreReadWithoutRegexRecursion() {
        val payload = JsonObject().apply { addProperty("description", "long ".repeat(20_000)) }
        assertEquals(payload, PublishedPageJson.objects(page(payload.toString())).single())
    }
}
