package org.teslasoft.assistant.stt.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import org.teslasoft.assistant.tts.api.ResolvedTtsSource
import org.teslasoft.assistant.tts.api.TtsCatalogParser
import org.teslasoft.assistant.tts.api.TtsEndpoint
import org.teslasoft.assistant.tts.api.TtsException
import org.teslasoft.assistant.tts.api.TtsFailureKind
import org.teslasoft.assistant.tts.api.TtsTarget

class SttTransportTest {
    private fun source(host: String, model: String = "openai/whisper-1",
        routing: TtsRoutingSettings = TtsRoutingSettings()): ResolvedTtsSource {
        val profile = ApiEndpointObject("Endpoint", host, "secret-key", id = "ep")
        return ResolvedTtsSource(TtsTarget("ep", model, routing), TtsEndpoint.from(profile))
    }
    private fun text(request: okhttp3.Request): String {
        val buffer = Buffer(); request.body!!.writeTo(buffer); return buffer.readUtf8()
    }
    private val audio = byteArrayOf(1, 2, 3)

    @Test fun openRouterSendsJsonAudioLanguageRoutingAndHintForTheNamedProvider() {
        val request = SttTransport().request(source("https://openrouter.ai/api/v1",
            routing = TtsRoutingSettings(TtsRoutingMode.ONLY, "groq")), audio, "m4a", "en",
            listOf("Seket", "Phosphor"), encode = { "AQID" })
        assertEquals("/api/v1/audio/transcriptions", request.url.encodedPath)
        assertEquals("Bearer secret-key", request.header("Authorization"))
        val body = JsonParser.parseString(text(request)).asJsonObject
        assertEquals("openai/whisper-1", body.get("model").asString)
        assertEquals("AQID", body.getAsJsonObject("input_audio").get("data").asString)
        assertEquals("m4a", body.getAsJsonObject("input_audio").get("format").asString)
        assertEquals("en", body.get("language").asString)
        val provider = body.getAsJsonObject("provider")
        assertEquals("groq", provider.getAsJsonArray("only").single().asString)
        assertEquals("Seket, Phosphor", provider.getAsJsonObject("options").getAsJsonObject("groq").get("prompt").asString)
    }

    @Test fun openRouterAutomaticOmitsLanguageAndAddsHintForTheModelAuthor() {
        val body = JsonParser.parseString(text(SttTransport().request(source("https://openrouter.ai/api/v1"),
            audio, "m4a", null, listOf("Seket"), encode = { "x" }))).asJsonObject
        assertFalse(body.has("language"))
        val provider = body.getAsJsonObject("provider")
        assertFalse(provider.has("only"))
        assertEquals("Seket", provider.getAsJsonObject("options").getAsJsonObject("openai").get("prompt").asString)
    }

    @Test fun otherEndpointsSendOpenAiStyleMultipartWithPromptHint() {
        val request = SttTransport().request(source("https://api.example.com/v1/", "whisper-large"),
            audio, "m4a", "fr", listOf("Seket"))
        assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
        val body = text(request)
        assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data"))
        listOf("name=\"file\"", "name=\"model\"", "whisper-large", "name=\"language\"", "fr",
            "name=\"prompt\"", "Seket").forEach { assertTrue(it, body.contains(it)) }
    }

    @Test fun onlyRoutingWithoutAProviderIsRejectedBeforeSending() {
        val error = runCatching {
            SttTransport().request(source("https://openrouter.ai/api/v1",
                routing = TtsRoutingSettings(TtsRoutingMode.ONLY, "")), audio, "m4a", null, emptyList(), encode = { "" })
        }.exceptionOrNull() as TtsException
        assertEquals(TtsFailureKind.PROVIDER_REQUIRED, error.failure.kind)
    }

    @Test fun parsesTranscriptText() {
        assertEquals("hello there", SttTransport.parseText("{\"text\":\"hello there\",\"usage\":{}}"))
        assertNull(SttTransport.parseText("{\"error\":{}}"))
    }

    @Test fun transcriptionModelsNeedExplicitTranscriptionModality() {
        val catalog = TtsCatalogParser.transcriptionModels("""{"data":[
            {"id":"openai/whisper-1","architecture":{"output_modalities":["transcription"]}},
            {"id":"openai/gpt-4o","architecture":{"output_modalities":["text"]}},
            {"id":"plain-model"}]}""")
        assertEquals(listOf("openai/whisper-1"), catalog.models.map { it.id })
        // A model list without modality data cannot be read as speech-to-text.
        assertTrue(TtsCatalogParser.transcriptionModels("""{"data":[{"id":"whisper-1"}]}""").models.isEmpty())
    }

    @Suppress("unused") private val keepJson = JsonObject()
}
