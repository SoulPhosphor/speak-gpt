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

package org.teslasoft.assistant.util

/**
 * Reads the chat title out of an auto-naming reply.
 *
 * Some models write their thinking into the reply text before the title
 * ("Here's a thinking process:" followed by steps), so the first line is not
 * reliably the title. This looks past that text for the line that is actually
 * a title. It only reads the hidden naming reply; reasoning shown in the
 * conversation itself is never touched.
 *
 * Returns null when the reply contains no usable title, which the caller
 * treats as a failed naming attempt.
 */
object ChatTitleExtractor {

    /** The naming instruction sent to the main model and the summarizer. Also
     *  identifies the naming request on the outgoing-request hook. */
    const val TITLE_INSTRUCTION =
        "Create a concise 2-6 word title for the conversation that follows. " +
            "Return only the title text. Describe the conversation topic, not this naming instruction. " +
            "Do not explain or describe what the user wants. Do not prefix the title with " +
            "'Title', 'Name', 'Chat', or 'Bot'."

    private const val MAX_TITLE_CHARS = 80
    private const val MAX_TITLE_WORDS = 10

    private val THINK_BLOCK = Regex("(?is)<(think|thinking|reasoning)>.*?</\\1>")
    private val UNCLOSED_THINK = Regex("(?is)<(think|thinking|reasoning)>.*")
    private val LABEL_PREFIX = Regex("(?i)^(title|name)\\s*:\\s*")
    private val LIST_MARKER = Regex("^(?:[-*•]|\\d+[.)])\\s+")
    private val HEADING_MARKER = Regex("^#+\\s*")

    fun extract(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val visible = raw.replace(THINK_BLOCK, "\n").replace(UNCLOSED_THINK, "\n")

        // The answer comes after any thinking, so the last usable line wins.
        return visible.lineSequence()
            .mapNotNull { candidate(it) }
            .lastOrNull()
    }

    private fun candidate(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        // An introduction to more text ("Here's a thinking process:") or a
        // list step is part of the thinking, not a title.
        if (trimmed.endsWith(":")) return null
        if (LIST_MARKER.containsMatchIn(trimmed)) return null

        val cleaned = trimmed
            .replace(HEADING_MARKER, "")
            .replace(LABEL_PREFIX, "")
            .trim()
            .removeSurrounding("**")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("`")
            .removeSurrounding("*")
            .trim()

        if (cleaned.isEmpty()) return null
        if (cleaned.length > MAX_TITLE_CHARS) return null
        if (cleaned.split(Regex("\\s+")).size > MAX_TITLE_WORDS) return null
        return cleaned
    }
}
