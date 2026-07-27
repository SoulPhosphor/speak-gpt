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

package org.teslasoft.assistant.preferences.includes

/**
 * Shared text utilities for complete document attachments.
 *
 * New imports have no token or row cap. Import admission belongs to device
 * memory, archive expansion and private-storage guards; model capacity belongs
 * to the complete frozen request at Send time.
 */
object IncludeTextPolicy {

    private const val CHARS_PER_TOKEN = 4
    private const val GARBAGE_SAMPLE = 4_000
    private const val GARBAGE_RATIO = 0.05

    /**
     * Fallback image estimate baseline (matches the low-detail floor several
     * vision providers publish). The estimate is never allowed below this even
     * for tiny images, so the tilde number does not misrepresent the request.
     */
    const val IMAGE_TOKEN_FLOOR = 85

    /**
     * Divisor for the pixel-based image estimate. Chosen conservatively so
     * the tilde number does not understate real photos on the most common
     * vision APIs, while remaining deliberately model-agnostic. It is not a
     * provider-specific tokenizer — the tilde communicates that.
     */
    const val IMAGE_TOKEN_PIXELS_PER_TOKEN = 750

    /** Approximate display count. Callers must render it with a leading "~". */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0

        var asciiCharacters = 0
        var nonAsciiTokens = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint <= 0x7F) {
                asciiCharacters++
            } else {
                nonAsciiTokens += if (Character.charCount(codePoint) == 2) 2 else 1
            }
            index += Character.charCount(codePoint)
        }
        return (asciiCharacters + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + nonAsciiTokens
    }

    /**
     * Refuses a renamed binary rather than dumping control-byte garbage into
     * the prompt. Tabs and line endings remain ordinary text.
     */
    fun looksLikeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val sample = if (text.length > GARBAGE_SAMPLE) {
            text.substring(0, GARBAGE_SAMPLE)
        } else {
            text
        }
        var control = 0
        for (char in sample) {
            if (char == '\t' || char == '\n' || char == '\r') continue
            if (char.code < 0x20 || char.code == 0x7F || char == '�') control++
        }
        return control.toDouble() / sample.length <= GARBAGE_RATIO
    }

    fun fallbackArtifactLine(fileName: String): String = "User sent $fileName."

    /**
     * Conservative, model-agnostic image estimate for the ~N tokens readout.
     * Uses the transmitted (post-downsample) dimensions the caller passes in,
     * not the original source. Always at least [IMAGE_TOKEN_FLOOR]; the tilde
     * around it communicates that this is a warning number, not a provider
     * token count.
     */
    fun estimateImageTokens(widthPx: Int, heightPx: Int): Int {
        if (widthPx <= 0 || heightPx <= 0) return IMAGE_TOKEN_FLOOR
        val pixels = widthPx.toLong() * heightPx.toLong()
        val pixelBased = ((pixels + IMAGE_TOKEN_PIXELS_PER_TOKEN - 1) /
                IMAGE_TOKEN_PIXELS_PER_TOKEN).toInt()
        return kotlin.math.max(IMAGE_TOKEN_FLOOR, pixelBased)
    }

    fun workbookSheetLabel(name: String): String = "[Sheet: $name]"

    fun sanitizeArtifactLine(
        raw: String?,
        fileName: String,
        maxWords: Int = 48,
        maxSentences: Int = 3
    ): String {
        val flat = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (flat.isEmpty()) return fallbackArtifactLine(fileName)
        val sentenceLimited = flat
            .split(Regex("(?<=[.!?])\\s+"))
            .take(maxSentences.coerceAtLeast(1))
            .joinToString(" ")
        val words = sentenceLimited.split(" ")
        val clipped = if (words.size <= maxWords) {
            sentenceLimited
        } else {
            words.take(maxWords).joinToString(" ")
        }
        return if (
            clipped.endsWith(".") ||
            clipped.endsWith("!") ||
            clipped.endsWith("?")
        ) {
            clipped
        } else {
            "$clipped."
        }
    }
}
