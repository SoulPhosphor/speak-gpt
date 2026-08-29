package org.teslasoft.assistant.tts.api

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings

internal fun source(model: String = "vendor/talker:exact", routing: TtsRoutingSettings = TtsRoutingSettings(),
    host: String = "https://speech.example/api/v1/", openRouter: Boolean = false): ResolvedTtsSource =
    ResolvedTtsSource(TtsTarget("speech-endpoint", model, routing, "api-tts:entry", "voice/exact"),
        TtsEndpoint.from(ApiEndpointObject("Renamed Service", host, "private-test-key", id = "speech-endpoint",
            identity = if (openRouter) ApiEndpointObject.IDENTITY_OPENROUTER else ApiEndpointObject.IDENTITY_GENERIC,
            speechEndpoint = "/custom/speech/", authType = ApiEndpointObject.AUTH_X_API_KEY,
            connectTimeoutSeconds = 13, responseTimeoutSeconds = 87)))

internal class FakeHttp(private val responder: (Request) -> TtsHttpResponse) : TtsHttpExecutor {
    val requests = mutableListOf<Request>()
    override fun execute(endpoint: TtsEndpoint, target: TtsTarget, operation: TtsOperation,
        request: Request, token: TtsRequestToken): TtsHttpResponse {
        requests += request
        return responder(request)
    }
}
internal fun response(body: String, status: Int = 200) = TtsHttpResponse(status, body.toByteArray(), "application/json")

class TtsCatalogTest {
    @Test fun capabilityEvidenceExcludesGuessesAndAudioChat() {
        val parsed = TtsCatalogParser.models("""{"data":[
            {"id":"vendor/talker:exact","architecture":{"output_modalities":["speech"]},"supported_parameters":[]},
            {"id":"another","capabilities":{"text_to_speech":true}},
            {"id":"tts-but-no-evidence"},
            {"id":"audio-chat","architecture":{"input_modalities":["audio"],"output_modalities":["text","audio"]}},
            {"id":"speech-transcriber","task":"speech-to-text","output_modalities":["speech"]}
        ]}""")
        assertEquals(listOf("vendor/talker:exact", "another"), parsed.models.map { it.id })
        assertEquals(setOf("output_modalities:speech"), parsed.models.first().capabilityEvidence)
    }

    @Test fun modelsRetainSeparateVoicesAndMissingIsDifferentFromEmpty() {
        val models = TtsCatalogParser.models("""{"data":[
          {"id":"a","task":"tts","supported_voices":["a1"]},
          {"id":"b","task":"tts","supported_voices":[{"id":"b1","name":"Bee","gender":"female"}]},
          {"id":"c","task":"tts","supported_voices":null},
          {"id":"d","task":"tts","supported_voices":[]}
        ]}""").models
        assertEquals("a1", (models[0].voices as TtsVoiceCatalog.Known).voices.single().id)
        assertEquals("b1", (models[1].voices as TtsVoiceCatalog.Known).voices.single().id)
        assertEquals(TtsVoiceCatalog.Unavailable, models[2].voices)
        assertTrue((models[3].voices as TtsVoiceCatalog.Known).voices.isEmpty())
    }

    @Test fun malformedAndMissingIdentifiersStayDistinct() {
        assertEquals(TtsVoiceCatalog.Invalid(TtsFailureKind.MALFORMED), TtsCatalogParser.voiceResponse("{"))
        assertEquals(TtsVoiceCatalog.Invalid(TtsFailureKind.IDENTIFIERS_MISSING),
            TtsCatalogParser.voiceResponse("""{"voices":[{"name":"Display Only"}]}"""))
        assertEquals(TtsVoiceCatalog.Unavailable, TtsCatalogParser.voiceResponse("{}"))
    }

    @Test fun emptyAndPartialCatalogsCannotEstablishAbsence() {
        assertFalse(TtsCatalogParser.models("""{"data":[]}""").complete)
        assertFalse(TtsCatalogParser.models("""{"data":[{"id":"a","task":"tts"}],"next":"page2"}""").complete)
        assertFalse(TtsCatalogParser.models("""{"data":[{"id":"a","task":"tts"},{}]}""").complete)
    }

    @Test fun openRouterUsesSpeechFilterAndNeverInventsVoices() {
        val http = FakeHttp { response("""{"data":[{"id":"vendor/talker:exact","architecture":{"output_modalities":["speech"]}}]}""") }
        val result = TtsDiscoveryClient(http).voices(source(openRouter = true), TtsRequestGate().begin())
        assertEquals(TtsVoiceCatalog.Unavailable, result)
        assertEquals("speech", http.requests.single().url.queryParameter("output_modalities"))
        assertEquals("/api/v1/models", http.requests.single().url.encodedPath)
    }

    @Test fun optionalProbeFailureDoesNotHideLaterSuccessfulVoiceList() {
        val http = FakeHttp { request -> when(request.url.encodedPath) {
            "/api/v1/models" -> response("""{"data":[{"id":"vendor/talker:exact","task":"tts"}]}""")
            "/api/v1/audio/voices" -> response("""{"error":{"message":"temporary failure"}}""", 500)
            else -> response("""{"voices":["right-voice"]}""")
        } }
        val result = TtsDiscoveryClient(http).voices(source(), TtsRequestGate().begin()) as TtsVoiceCatalog.Known
        assertEquals("right-voice", result.voices.single().id)
        assertEquals("vendor/talker:exact", http.requests.last().url.queryParameter("model"))
    }

    @Test fun failedVoiceRequestIsNotAnEmptyListOrModelAbsence() {
        val http = FakeHttp { request -> if (request.url.encodedPath.endsWith("models"))
            response("""{"data":[{"id":"vendor/talker:exact","task":"tts"}]}""")
            else response("""{"error":{"message":"Incorrect API key"}}""", 401) }
        val error = assertThrows(TtsException::class.java) {
            TtsDiscoveryClient(http).voices(source(), TtsRequestGate().begin())
        }
        assertEquals(TtsFailureKind.AUTH, error.failure.kind)
        assertEquals(TtsOperation.VOICES, error.failure.operation)
    }

    @Test fun providerDiscoveryUsesCustomPathAndProfileAuth() {
        val profile = ApiEndpointObject("Custom", "https://custom.example/prefix/", "key",
            id = "endpoint", providerDiscoveryPath = "/routes/{model}", authType = ApiEndpointObject.AUTH_API_KEY)
        val custom = ResolvedTtsSource(TtsTarget("endpoint", "vendor/exact:1"), TtsEndpoint.from(profile))
        val http = FakeHttp { response("""{"endpoints":[{"tag":"route-id","provider_name":"Name","supported_parameters":[]}]}""") }
        val providers = TtsDiscoveryClient(http).providers(custom, TtsRequestGate().begin())
        assertEquals("route-id", providers.providers.single().id)
        assertEquals("/prefix/routes/vendor/exact%3A1", http.requests.single().url.encodedPath)
        assertEquals("key", http.requests.single().header("api-key"))
        assertNull(http.requests.single().header("Authorization"))
    }

    @Test fun staleDiscoveryCannotBeDelivered() {
        val gate = TtsRequestGate()
        val token = gate.begin()
        val http = FakeHttp { gate.begin(); response("""{"data":[]}""") }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            TtsDiscoveryClient(http).models(source(), token)
        }
        assertFalse(token.deliver { fail("stale delivery") })
    }

    @Test fun invalidSavedAddressesRemainStructuredFailures() {
        for (openRouter in listOf(false, true)) {
            val http = FakeHttp { fail("Invalid address must not be sent"); response("") }
            val client = TtsDiscoveryClient(http)
            val failure = assertThrows(TtsException::class.java) {
                client.voices(source(host = "not a URL", openRouter = openRouter), TtsRequestGate().begin())
            }.failure
            assertEquals(TtsFailureKind.INVALID_ADDRESS, failure.kind)
            assertNull(failure.evidence)
        }
    }
}
