package org.teslasoft.assistant.tts.voices

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSpeechCatalogClientTest {
    @Test fun speechModelsComeFromReturnedCapabilitiesAndIds() {
        val data = JsonParser.parseString(
            """[
              {"id":"custom-talker","capabilities":{"text_to_speech":true}},
              {"id":"vendor/speech-v2"},
              {"id":"vendor/transcription","task":"speech-to-text"},
              {"id":"chat-model","capabilities":{"chat":true}}
            ]"""
        ).asJsonArray
        assertEquals(
            listOf("custom-talker", "vendor/speech-v2"),
            ApiSpeechCatalogClient.speechModelIds(data)
        )
    }

    @Test fun endpointVoiceMetadataIsPreservedWithoutInventingFields() {
        val voices = ApiSpeechCatalogClient.parseVoiceResponse(
            """{"voices":[{"id":"river-id","name":"River","language":"English"}]}"""
        )
        assertEquals("river-id", voices.single().id)
        assertEquals("River", voices.single().displayName)
        assertEquals("English", voices.single().language?.label)
        assertEquals(null, voices.single().gender)
    }

    @Test fun onlyExplicitUnknownVoiceFailuresAreLearned() {
        assertTrue(OpenAiVoiceProvider.isUnknownVoiceFailure("unknown voice: river"))
        assertTrue(OpenAiVoiceProvider.isUnknownVoiceFailure("voice not found"))
        assertFalse(OpenAiVoiceProvider.isUnknownVoiceFailure("connection timed out"))
    }
}
