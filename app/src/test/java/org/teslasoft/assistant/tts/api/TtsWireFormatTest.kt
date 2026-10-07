package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Base64
import java.util.concurrent.CancellationException

class TtsWireFormatTest {
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4)

    private fun body(request: okhttp3.Request): JsonObject {
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        return JsonParser.parseString(buffer.readUtf8()).asJsonObject
    }

    private fun resolved(host: String, model: String, auth: String = ApiEndpointObject.AUTH_BEARER,
        voice: String = "voice-1", openRouter: Boolean = false) = ResolvedTtsSource(
        TtsTarget("ep", model, sourceId = "api-tts:entry", voiceId = voice),
        TtsEndpoint.from(ApiEndpointObject("Service", host, "secret-key", id = "ep", authType = auth,
            identity = if (openRouter) ApiEndpointObject.IDENTITY_OPENROUTER else ApiEndpointObject.IDENTITY_GENERIC)))

    private fun openAi(model: String) = resolved("https://api.openai.com/v1", model)
    private fun elevenLabs(voice: String = "21m00Tcm4TlvDq8ikWAM", auth: String = ApiEndpointObject.AUTH_XI_API_KEY) =
        resolved("https://api.elevenlabs.io/v1", "eleven_multilingual_v2", auth, voice)

    @Test fun openRouterAndGenericSpeechRequestsKeepTheOpenAiCompatibleShape() {
        for (s in listOf(source(openRouter = true, host = "https://openrouter.ai/api/v1"), source(),
            source(model = "elevenlabs/eleven-turbo-v2", openRouter = true, host = "https://openrouter.ai/api/v1"))) {
            val request = TtsSpeechTransport().request(s, "Hello")
            assertEquals(setOf("model", "voice", "input", "response_format"), body(request).keySet())
            assertEquals("mp3", body(request)["response_format"].asString)
            assertEquals("audio/mpeg", request.header("Accept"))
            assertTrue(request.url.encodedPath.endsWith("/custom/speech/"))
            assertNull(request.header("xi-api-key"))
        }
        assertEquals(TtsEndpointKind.OPENROUTER, source(openRouter = true).endpoint.kind)
        assertEquals(TtsEndpointKind.GENERIC, source().endpoint.kind)
    }

    @Test fun generationIdIsStillCapturedFromTheSpeechResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg")
                .setHeader("X-Generation-Id", "gen-tts-123").setBody(Buffer().write(mp3)))
            val audio = TtsSpeechTransport().synthesize(resolved(server.url("/api/v1/").toString(), "m"),
                "Hello", TtsRequestGate().begin())
            assertEquals("gen-tts-123", audio.generationId)
            assertEquals("gen-tts-123", audio.metering.generationId)
        }
    }

    @Test fun officialOpenAiUsesSseOnlyForDocumentedModels() {
        assertEquals(TtsEndpointKind.OPENAI, openAi("tts-1").endpoint.kind)
        for (model in listOf("tts-1", "tts-1-hd")) {
            val request = TtsSpeechTransport().request(openAi(model), "Hi")
            assertFalse(body(request).has("stream_format"))
            assertEquals("https://api.openai.com/v1/audio/speech", request.url.toString())
        }
        val sse = TtsSpeechTransport().request(openAi("gpt-4o-mini-tts"), "Hi")
        assertEquals("sse", body(sse)["stream_format"].asString)
        assertEquals("text/event-stream", sse.header("Accept"))
        // A compatible service, or an unknown OpenAI model, is never switched to SSE.
        assertFalse(body(TtsSpeechTransport().request(resolved("https://speech.example/v1", "gpt-4o-mini-tts"), "Hi"))
            .has("stream_format"))
        assertFalse(body(TtsSpeechTransport().request(openAi("gpt-future-tts"), "Hi")).has("stream_format"))
        assertFalse(body(TtsSpeechTransport().request(resolved("http://api.openai.com/v1", "gpt-4o-mini-tts"), "Hi"))
            .has("stream_format"))
    }

    @Test fun openAiSseDeltasAreDecodedSeparatelyJoinedInOrderAndUsageIsKept() {
        val first = mp3.copyOfRange(0, 5); val second = mp3.copyOfRange(5, mp3.size)
        fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        val stream = "data: {\"type\":\"speech.audio.delta\",\"audio\":\"${b64(first)}\"}\n\n" +
            ": keep-alive\n\n" +
            "data: {\"type\":\"speech.audio.delta\",\"audio\":\"${b64(second)}\"}\r\n\r\n" +
            "data: {\"type\":\"speech.audio.done\",\"usage\":{\"input_tokens\":48,\"output_tokens\":914,\"total_tokens\":962}}\n\n"
        val http = FakeHttp { TtsHttpResponse(200, stream.toByteArray(), "text/event-stream") }
        val audio = TtsSpeechTransport(http).synthesize(openAi("gpt-4o-mini-tts"), "Hi", TtsRequestGate().begin())
        assertArrayEquals(mp3, audio.bytes)
        assertEquals(TtsReportedTokens(48, 914, 962), audio.metering.tokens)
    }

    @Test fun openAiSseWithoutDoneEventKeepsUsageUnknownAndErrorEventsFail() {
        val delta = "data: {\"type\":\"speech.audio.delta\",\"audio\":\"${Base64.getEncoder().encodeToString(mp3)}\"}\n\n"
        val audio = TtsSpeechTransport(FakeHttp { TtsHttpResponse(200, delta.toByteArray(), "text/event-stream") })
            .synthesize(openAi("gpt-4o-mini-tts"), "Hi", TtsRequestGate().begin())
        assertNull(audio.metering.tokens)
        val error = "data: {\"type\":\"error\",\"error\":{\"message\":\"bad voice\"}}\n\n"
        var billed = 0
        assertThrows(TtsException::class.java) {
            TtsSpeechTransport(FakeHttp { TtsHttpResponse(200, error.toByteArray(), "text/event-stream") })
                .synthesize(openAi("gpt-4o-mini-tts"), "Hi", TtsRequestGate().begin(), onBilled = { billed++ })
        }
        assertEquals(0, billed)
    }

    @Test fun elevenLabsUsesItsNativeRequestWithVoiceInPathAndXiApiKey() {
        val request = TtsSpeechTransport().request(elevenLabs(), "Hello there")
        assertEquals("/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM", request.url.encodedPath)
        assertEquals("mp3_44100_128", request.url.queryParameter("output_format"))
        assertEquals(setOf("text", "model_id"), body(request).keySet())
        assertEquals("Hello there", body(request)["text"].asString)
        assertEquals("eleven_multilingual_v2", body(request)["model_id"].asString)
        assertEquals("secret-key", request.header("xi-api-key"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("x-api-key"))
        assertEquals("audio/mpeg", request.header("Accept"))
        val odd = TtsSpeechTransport().request(elevenLabs(voice = "voice/with space"), "Hi")
        assertEquals("/v1/text-to-speech/voice%2Fwith%20space", odd.url.encodedPath)
    }

    @Test fun elevenLabsCapturesCharacterCostAndRequestId() {
        val http = FakeHttp { TtsHttpResponse(200, mp3, "audio/mpeg",
            headers = mapOf("character-cost" to "42", "request-id" to "req-9")) }
        val audio = TtsSpeechTransport(http).synthesize(elevenLabs(), "Hello", TtsRequestGate().begin())
        assertEquals(42L, audio.metering.characterCost)
        assertEquals("req-9", audio.metering.requestId)
        val missing = TtsSpeechTransport(FakeHttp { TtsHttpResponse(200, mp3, "audio/mpeg") })
            .synthesize(elevenLabs(), "Hello", TtsRequestGate().begin())
        assertNull(missing.metering.characterCost)
    }

    @Test fun realHttpBoundaryCapturesElevenLabsHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg")
                .setHeader("character-cost", "17").setHeader("request-id", "abc").setBody(Buffer().write(mp3)))
            val response = OkHttpTtsExecutor().execute(source().endpoint, source().target, TtsOperation.SPEECH,
                okhttp3.Request.Builder().url(server.url("/v1/x")).build(), TtsRequestGate().begin())
            assertEquals(mapOf("character-cost" to "17", "request-id" to "abc"), response.headers)
        }
    }

    @Test fun genericOrOpenRouterEndpointsNeverReceiveElevenLabsWireFormat() {
        // xi-api-key on another host only changes the header, not the request contract.
        val generic = TtsSpeechTransport().request(resolved("https://speech.example/v1", "eleven_turbo_v2",
            ApiEndpointObject.AUTH_XI_API_KEY), "Hi")
        assertEquals(setOf("model", "voice", "input", "response_format"), body(generic).keySet())
        assertEquals("/v1/audio/speech", generic.url.encodedPath)
        assertEquals("secret-key", generic.header("xi-api-key"))
        val routed = TtsSpeechTransport().request(resolved("https://elevenlabs-proxy.example/v1",
            "elevenlabs/eleven-turbo-v2", openRouter = true), "Hi")
        assertEquals("/v1/audio/speech", routed.url.encodedPath)
        assertEquals(TtsEndpointKind.ELEVENLABS, elevenLabs().endpoint.kind)
        assertEquals(TtsEndpointKind.ELEVENLABS, resolved("https://api.eu.residency.elevenlabs.io/v1", "m").endpoint.kind)
        assertEquals(TtsEndpointKind.GENERIC, resolved("https://api.elevenlabs.io.example/v1", "m").endpoint.kind)
    }

    @Test fun billingRunsOnceForValidAudioEvenWhenStopArrivesWithTheResponse() {
        val gate = TtsRequestGate(); val token = gate.begin()
        val billed = mutableListOf<TtsAudio>()
        val http = FakeHttp { token.cancel(); TtsHttpResponse(200, mp3, "audio/mpeg", "gen-1") }
        assertThrows(CancellationException::class.java) {
            TtsSpeechTransport(http).synthesize(source(), "Hello", token, onBilled = { billed += it })
        }
        assertEquals(listOf("gen-1"), billed.map { it.generationId })
    }

    @Test fun noChargeIsRecordedWithoutValidAudioOrBeforeAResponse() {
        var billed = 0
        val cancelled = TtsRequestGate().begin().also { it.cancel() }
        val http = FakeHttp { fail("must not send"); response("") }
        assertThrows(CancellationException::class.java) {
            TtsSpeechTransport(http).synthesize(source(), "Hello", cancelled, onBilled = { billed++ })
        }
        for (r in listOf(response("""{"error":{"message":"no"}}""", 400), TtsHttpResponse(200, byteArrayOf(), "audio/mpeg"))) {
            assertThrows(TtsException::class.java) {
                TtsSpeechTransport(FakeHttp { r }).synthesize(source(), "Hello", TtsRequestGate().begin(),
                    onBilled = { billed++ })
            }
        }
        assertEquals(0, billed)
    }

    @Test fun xiApiKeyModeSendsOnlyItsOwnHeader() {
        val request = TtsSpeechTransport().request(elevenLabs(), "Hello")
        assertEquals(1, listOf("Authorization", "api-key", "x-api-key", "xi-api-key").count { request.header(it) != null })
    }
}
