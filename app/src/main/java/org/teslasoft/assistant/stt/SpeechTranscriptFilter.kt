/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.stt

/**
 * Filters Whisper's common non-speech hallucinations before they become chat
 * turns. Background audio such as wind, music, applause, or silence can produce
 * short captions even though the user did not say anything; hands-free mode
 * should simply reopen the microphone for those cases.
 */
object SpeechTranscriptFilter {
    private val exactNoiseCaptions = setOf(
        "wind blowing",
        "wind blows",
        "wind noise",
        "background noise",
        "noise",
        "static",
        "silence",
        "music",
        "applause",
        "laughter",
        "thunder",
        "rain",
        "typing",
        "footsteps"
    )

    private val bracketedNoiseWords = setOf(
        "music",
        "applause",
        "laughter",
        "laughing",
        "noise",
        "silence",
        "wind",
        "wind blowing",
        "background noise",
        "inaudible"
    )

    fun cleanedOrNull(transcript: String?): String? {
        val trimmed = transcript?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val normalized = trimmed
            .lowercase()
            .replace(Regex("^[\\[\\(]+|[\\]\\)]+$"), "")
            .replace(Regex("[.!?…]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isEmpty()) return null
        if (normalized in exactNoiseCaptions) return null
        if (normalized in bracketedNoiseWords) return null

        val bracketOnly = trimmed.matches(Regex("^[\\[\\(][^\\]\\)]{1,40}[\\]\\)]$"))
        if (bracketOnly && normalized in bracketedNoiseWords) return null

        return trimmed
    }
}
