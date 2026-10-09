package org.teslasoft.assistant.stt.api

import org.junit.Assert.assertEquals
import org.junit.Test

class SttWordingTest {
    @Test fun swapsFeatureWordsOnly() {
        assertEquals("Select a speech-to-text model before choosing its provider or adding it.",
            SttWording.adapt("Select a text-to-speech model before choosing its provider or adding it."))
        assertEquals("Speech-to-Text Model Unavailable", SttWording.adapt("TTS Model Unavailable"))
        assertEquals("Transcription Option Not Supported", SttWording.adapt("Speech Option Not Supported"))
        assertEquals("returned Not Found for this transcription request",
            SttWording.adapt("returned Not Found for this speech request"))
        assertEquals("Function: Speech-to-Text", SttWording.adapt("Function: Text to Speech"))
        // This feature's own operation name is already correct and must survive.
        assertEquals("Speech-to-Text Could Not Be Read", SttWording.adapt("Speech to Text Could Not Be Read"))
        assertEquals("a speech-to-text model", SttWording.adapt("a speech-to-text model"))
    }
}
