package org.teslasoft.assistant.tts.api

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class TtsProviderDiscoveryTest {
    private fun resolved(host: String, model: String = "", auth: String = ApiEndpointObject.AUTH_BEARER) =
        ResolvedTtsSource(TtsTarget("ep", model, sourceId = "api-tts:entry"),
            TtsEndpoint.from(ApiEndpointObject("Service", host, "secret-key", id = "ep", authType = auth)))

    private val elevenLabsModels = """[
        {"model_id":"eleven_multilingual_v2","name":"Eleven Multilingual v2","can_do_text_to_speech":true},
        {"model_id":"eleven_english_sts_v2","name":"Speech to Speech","can_do_text_to_speech":false},
        {"model_id":"eleven_flash_v2_5","name":"Eleven Flash v2.5","can_do_text_to_speech":true}]"""

    @Test fun elevenLabsModelListIsReadFromItsNativeArray() {
        val catalog = TtsCatalogParser.elevenLabsModels(elevenLabsModels)
        assertEquals(listOf("eleven_multilingual_v2", "eleven_flash_v2_5"), catalog.models.map { it.id })
        assertEquals("Eleven Flash v2.5", catalog.models.last().name)
        assertTrue(catalog.complete)
        val http = FakeHttp { response(elevenLabsModels) }
        val models = TtsDiscoveryClient(http).models(resolved("https://api.elevenlabs.io/v1"), TtsRequestGate().begin())
        assertEquals(2, models.models.size)
        assertEquals("https://api.elevenlabs.io/v1/models", http.requests.single().url.toString())
        assertEquals("secret-key", http.requests.single().header("xi-api-key"))
        assertNull(http.requests.single().header("Authorization"))
    }

    @Test fun elevenLabsVoicesComeFromTheAccountVoiceListIntoTheSameCatalog() {
        val http = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/v1/models" -> response(elevenLabsModels)
                "/v1/voices" -> response("""{"voices":[{"voice_id":"21m00Tcm4TlvDq8ikWAM","name":"Rachel",
                    "labels":{"accent":"american","gender":"female"}},{"voice_id":"pNInz6obpgDQGcFmaJgB","name":"Adam"}]}""")
                else -> response("{}", 404)
            }
        }
        val discovery = TtsDiscoveryClient(http).voiceDiscovery(resolved("https://api.elevenlabs.io/v1",
            "eleven_multilingual_v2"), TtsRequestGate().begin())
        val voices = (discovery.catalog as TtsVoiceCatalog.Known).voices
        assertEquals(listOf("21m00Tcm4TlvDq8ikWAM", "pNInz6obpgDQGcFmaJgB"), voices.map { it.id })
        assertEquals("Rachel", voices.first().displayName)
        assertEquals("female", voices.first().gender?.id)
        assertEquals("american", voices.first().accent?.id)
        // One documented voice list, without the OpenAI-style probe or a model filter.
        val voiceRequest = http.requests.single { it.url.encodedPath == "/v1/voices" }
        assertNull(voiceRequest.url.queryParameter("model"))
        assertTrue(http.requests.none { it.url.encodedPath == "/v1/audio/voices" })
    }

    @Test fun officialOpenAiSpeechModelsAreRecognizedByExactDocumentedId() {
        val body = """{"data":[{"id":"tts-1"},{"id":"gpt-4o-mini-tts"},{"id":"gpt-4o"},{"id":"tts-1-hd"},
            {"id":"my-tts-1-finetune"}]}"""
        val official = TtsDiscoveryClient(FakeHttp { response(body) })
            .models(resolved("https://api.openai.com/v1"), TtsRequestGate().begin())
        assertEquals(listOf("tts-1", "gpt-4o-mini-tts", "tts-1-hd"), official.models.map { it.id })
        // Another service listing the same IDs still needs real synthesis evidence.
        val generic = TtsDiscoveryClient(FakeHttp { response(body) })
            .models(resolved("https://speech.example/v1"), TtsRequestGate().begin())
        assertTrue(generic.models.isEmpty())
    }
}
