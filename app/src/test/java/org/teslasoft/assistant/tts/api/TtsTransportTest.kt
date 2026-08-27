package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.SavedTtsSource
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TtsTransportTest {
    private fun body(request: okhttp3.Request): JsonObject {
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        return JsonParser.parseString(buffer.readUtf8()).asJsonObject
    }
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)

    @Test fun routingPayloadsRetainExactIdsAndOptionsWithoutChangingChatState() {
        val transport = TtsSpeechTransport()
        val options = JsonObject().apply { add("vendor", JsonObject().apply { addProperty("style", "calm") }) }
        val only = source(routing = TtsRoutingSettings(TtsRoutingMode.ONLY, "Provider/Exact"))
        val request = transport.request(only, "Hello", options = options)
        assertEquals("https://speech.example/api/v1/custom/speech/", request.url.toString())
        assertEquals("private-test-key", request.header("x-api-key"))
        assertNull(request.header("Authorization"))
        val payload = body(request)
        assertEquals("vendor/talker:exact", payload["model"].asString)
        assertEquals("voice/exact", payload["voice"].asString)
        assertEquals("mp3", payload["response_format"].asString)
        val provider = payload.getAsJsonObject("provider")
        assertEquals("Provider/Exact", provider.getAsJsonArray("only")[0].asString)
        assertFalse(provider["allow_fallbacks"].asBoolean)
        assertEquals(options, provider["options"])
        assertNull(body(transport.request(source(), "Hello"))["provider"])
        val preferred = source(routing = TtsRoutingSettings(TtsRoutingMode.PREFERRED,
            providerOrder = listOf("second", "first"), allowFallbacks = false))
        val p = body(transport.request(preferred, "Hello")).getAsJsonObject("provider")
        assertEquals(listOf("second", "first"), p.getAsJsonArray("order").map { it.asString })
        assertFalse(p["allow_fallbacks"].asBoolean)
    }

    @Test fun onlyWithoutProviderIsBlockedBeforeNetwork() {
        val http = FakeHttp { fail("must not send"); response("") }
        val error = assertThrows(TtsException::class.java) {
            TtsSpeechTransport(http).synthesize(source(routing = TtsRoutingSettings(TtsRoutingMode.ONLY)), "hello", TtsRequestGate().begin())
        }
        assertEquals(TtsFailureKind.PROVIDER_REQUIRED, error.failure.kind)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun speechOptionsAlreadyInBodySurviveRoutingComposition() {
        val original = JsonParser.parseString("""{"provider":{"options":{"existing":{"style":"calm"}},"order":["stale"]}}""").asJsonObject
        val result = TtsRouting.compose(original, TtsRoutingSettings(TtsRoutingMode.ONLY, "chosen"), JsonObject())
        assertEquals("calm", result.getAsJsonObject("provider").getAsJsonObject("options")
            .getAsJsonObject("existing")["style"].asString)
        assertFalse(result.getAsJsonObject("provider").has("order"))
        assertTrue(original.getAsJsonObject("provider").has("order"))
    }

    @Test fun emptyPreferredWithoutFallbackCannotBecomeUnrestricted() {
        val failure = assertThrows(TtsException::class.java) {
            TtsSpeechTransport().request(source(routing = TtsRoutingSettings(TtsRoutingMode.PREFERRED, allowFallbacks = false)), "Hello")
        }.failure
        assertEquals(TtsFailureKind.PROVIDER_REQUIRED, failure.kind)
    }

    @Test fun networkReasonsRemainSpecificWithoutProviderAttribution() {
        val cases = listOf(java.net.UnknownHostException() to TtsFailureKind.DNS,
            java.net.ConnectException("Connection refused") to TtsFailureKind.REFUSED,
            java.net.SocketTimeoutException("connect timed out") to TtsFailureKind.CONNECT_TIMEOUT,
            javax.net.ssl.SSLHandshakeException("certificate invalid") to TtsFailureKind.TLS)
        for ((exception, kind) in cases) {
            val client = OkHttpClient.Builder().addInterceptor { throw exception }.build()
            val failure = assertThrows(TtsException::class.java) {
                TtsSpeechTransport(OkHttpTtsExecutor(client)).synthesize(source(), "Hello", TtsRequestGate().begin())
            }.failure
            assertEquals(kind, failure.kind)
            assertFalse(failure.responseReceived)
            assertTrue(failure.evidence!!.errorMessages.isEmpty())
        }
    }

    @Test fun savedResolverUsesExactProfileAndCannotFallBackToChatEndpoint() {
        val endpointA = ApiEndpointObject("Chat", "https://chat.example", "chat-key", id = "a")
        val endpointB = ApiEndpointObject("Speech", "https://speech.example/prefix/", "speech-key", id = "b",
            speechEndpoint = "/say", connectTimeoutSeconds = 21, responseTimeoutSeconds = 93)
        val row = SavedTtsSource("entry", "b", "exact:model")
        val resolver = TtsSourceResolver({ Result.success(listOf(row)) }, { listOf(endpointA, endpointB) })
        val resolved = resolver.saved(row.sourceId, "voice").getOrThrow()
        assertEquals("b", resolved.endpoint.id)
        assertEquals(21, resolved.endpoint.connectSeconds)
        assertEquals(93, resolved.endpoint.responseSeconds)
        endpointB.host = "https://edited.example"
        val request = TtsSpeechTransport().request(resolved, "Hello")
        assertEquals("https://speech.example/prefix/say", request.url.toString())
        assertEquals("Bearer speech-key", request.header("Authorization"))
        assertTrue(resolver.saved("api-tts:missing", "voice").isFailure)
        val missing = TtsSourceResolver({ Result.success(listOf(row)) }, { listOf(endpointA) })
        val error = missing.saved(row.sourceId, "voice").exceptionOrNull() as TtsException
        assertEquals(TtsFailureKind.PROFILE_MISSING, error.failure.kind)
    }

    @Test fun sourceLoadFailureDoesNotBecomeAnEmptyList() {
        val resolver = TtsSourceResolver({ Result.failure(IllegalStateException()) }, { emptyList() })
        val error = resolver.saved("api-tts:entry", "voice").exceptionOrNull() as TtsException
        assertEquals(TtsFailureKind.STORAGE, error.failure.kind)
    }

    @Test fun explicitNoAuthModeDoesNotSendSavedSecret() {
        val profile = ApiEndpointObject("No Auth", "https://speech.example", "saved-key", id = "id", authType = "none")
        val s = ResolvedTtsSource(TtsTarget("id", "model", voiceId = "voice"), TtsEndpoint.from(profile))
        val request = TtsSpeechTransport().request(s, "Hello")
        assertNull(request.header("Authorization")); assertNull(request.header("api-key")); assertNull(request.header("x-api-key"))
    }

    @Test fun everyAuthModeUsesOnlyItsOwnHeader() {
        for ((mode, header, value) in listOf(Triple("bearer", "Authorization", "Bearer key"),
            Triple("api-key", "api-key", "key"), Triple("x-api-key", "x-api-key", "key"))) {
            val p = ApiEndpointObject("Name", "https://speech.example", "key", id = "id", authType = mode)
            val s = ResolvedTtsSource(TtsTarget("id", "model", voiceId = "voice"), TtsEndpoint.from(p))
            val request = TtsSpeechTransport().request(s, "Hello")
            assertEquals(value, request.header(header))
            assertEquals(1, listOf("Authorization", "api-key", "x-api-key").count { request.header(it) != null })
        }
    }

    @Test fun jsonErrorAndPcmNeverReachPlayerAndOnlyIsNotRetried() {
        for (r in listOf(response("""{"error":{"message":"unsupported provider routing"}}""", 400),
            response("""{"error":{"message":"unsupported provider routing"}}"""),
            TtsHttpResponse(200, byteArrayOf(0, 1, 2), "audio/pcm"))) {
            val http = FakeHttp { r }
            assertThrows(TtsException::class.java) {
                TtsSpeechTransport(http).synthesize(source(routing = TtsRoutingSettings(TtsRoutingMode.ONLY, "only")), "Hello", TtsRequestGate().begin())
            }
            assertEquals(1, http.requests.size)
            assertEquals("only", body(http.requests.single()).getAsJsonObject("provider").getAsJsonArray("only")[0].asString)
        }
    }

    @Test fun emptyAudioIsDifferentFromUnsupportedAudio() {
        val error = assertThrows(TtsException::class.java) {
            TtsSpeechTransport(FakeHttp { TtsHttpResponse(200, byteArrayOf(), "audio/mpeg") })
                .synthesize(source(), "Hello", TtsRequestGate().begin())
        }
        assertEquals(TtsFailureKind.NO_AUDIO, error.failure.kind)
        assertTrue(error.failure.responseReceived)
    }

    @Test fun successfulJsonWithoutAudioDoesNotClaimAudioWasReturned() {
        val error = assertThrows(TtsException::class.java) {
            TtsSpeechTransport(FakeHttp { response("{}") })
                .synthesize(source(), "Hello", TtsRequestGate().begin())
        }
        assertEquals(TtsFailureKind.NO_AUDIO, error.failure.kind)
        assertNull(error.failure.evidence)
        assertTrue(error.failure.responseReceived)
    }

    @Test fun cancelledOrReplacedRequestCannotStartLatePlayback() {
        val gate = TtsRequestGate(); val token = gate.begin()
        val http = FakeHttp { token.cancel(); TtsHttpResponse(200, mp3, "audio/mpeg") }
        assertThrows(CancellationException::class.java) { TtsSpeechTransport(http).synthesize(source(), "Hello", token) }
        assertFalse(token.deliver { fail("cancelled audio") })
        val next = gate.begin()
        val audio = TtsSpeechTransport(FakeHttp { TtsHttpResponse(200, mp3, "audio/mpeg") }).synthesize(source(), "Hello", next)
        gate.begin()
        assertFalse(next.deliver { fail("queued audio ${audio.extension}") })
    }

    @Test fun realHttpBoundarySendsAudioRequestAndHonorsResponseTimeout() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(mp3)))
            val p = ApiEndpointObject("Local", server.url("/v1/").toString(), "key", id = "id", responseTimeoutSeconds = 1)
            val s = ResolvedTtsSource(TtsTarget("id", "speech-model", voiceId = "exact-voice"), TtsEndpoint.from(p))
            val audio = TtsSpeechTransport().synthesize(s, "Hello", TtsRequestGate().begin())
            assertArrayEquals(mp3, audio.bytes)
            assertEquals("/v1/audio/speech", server.takeRequest().path)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failure = assertThrows(TtsException::class.java) {
                TtsSpeechTransport().synthesize(s, "Hello", TtsRequestGate().begin())
            }.failure
            assertEquals(TtsFailureKind.RESPONSE_TIMEOUT, failure.kind)
            assertNull(failure.evidence?.outerHttpStatus)
        }
    }

    @Test fun cancellationClosesAnInFlightSocket() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val executor = Executors.newSingleThreadExecutor()
            val token = TtsRequestGate().begin()
            try {
                val future = executor.submit<Boolean> {
                    try { TtsSpeechTransport().synthesize(source(host = server.url("/").toString()), "Hello", token); false }
                    catch (_: CancellationException) { true }
                }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                token.cancel()
                assertTrue(future.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun redirectsAreNotFollowedWithCredentialsOrPaidPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://elsewhere.example/steal"))
            assertThrows(TtsException::class.java) {
                TtsSpeechTransport().synthesize(source(host = server.url("/").toString()), "Hello", TtsRequestGate().begin())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun offlineIsConfirmedNotGuessedFromDns() {
        val http = OkHttpTtsExecutor(OkHttpClient(), confirmedOffline = { true })
        val failure = assertThrows(TtsException::class.java) {
            TtsSpeechTransport(http).synthesize(source(), "Hello", TtsRequestGate().begin())
        }.failure
        assertEquals(TtsFailureKind.OFFLINE, failure.kind)
        assertFalse(failure.evidence!!.providerResponded)
    }
}
