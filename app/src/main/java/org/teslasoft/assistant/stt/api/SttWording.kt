/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.stt.api

/**
 * API speech-to-text reuses the API voice (TTS) failure messages with the
 * feature words swapped (owner ruling, Oct 9 2026): "text-to-speech" and
 * "TTS" become speech-to-text, and "speech" becomes transcription.
 */
object SttWording {
    private const val HOLD_LOWER = "\u0000stt-lower\u0000"
    private const val HOLD_TITLE = "\u0000stt-title\u0000"

    fun adapt(text: String): String = text
        // Already speech-to-text (for example this feature's own operation
        // name): keep it, so "Speech" inside it is not swapped.
        .replace("speech-to-text", HOLD_LOWER)
        .replace("Speech-to-Text", HOLD_TITLE)
        .replace("Speech to Text", HOLD_TITLE)
        .replace("text-to-speech", HOLD_LOWER)
        .replace("Text-to-Speech", HOLD_TITLE)
        .replace("Text to Speech", HOLD_TITLE)
        .replace(Regex("\\bTTS\\b"), HOLD_TITLE)
        .replace(Regex("\\bSpeech\\b"), "Transcription")
        .replace(Regex("\\bspeech\\b"), "transcription")
        .replace(HOLD_LOWER, "speech-to-text")
        .replace(HOLD_TITLE, "Speech-to-Text")
}
