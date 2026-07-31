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
 * Turns a Markdown reply into what text-to-speech should actually say when
 * the "Read Formatting Language" setting is off. Rendering on screen is never
 * touched — this only rewrites the string handed to the speech engine.
 *
 * Rules:
 *  - A fenced block that holds programming code is not read; it becomes the
 *    spoken note [CODE_NOTE]. A block is treated as code whenever it carries a
 *    language identifier (python, javascript, kotlin, json, …).
 *  - A fenced block that is plain text — unlabeled, or labeled "text",
 *    "plaintext" or "txt" — is read as its contents.
 *  - Ordinary text outside fenced blocks is read normally, with the Markdown
 *    formatting symbols (headings, emphasis, list markers, blockquotes,
 *    dividers, table pipes, link URLs) removed so they are not pronounced.
 */
object SpeechTextFormatter {

    /** Spoken in place of a code block. */
    const val CODE_NOTE = "You can see the code in our conversation."

    /** Fence labels that mean "this is plain readable text", not code. */
    private val PLAIN_TEXT_LANGUAGES = setOf("", "text", "plaintext", "txt")

    /** ``` optionally followed by a language id, then the block body up to the
     *  closing ```. DOT_MATCHES_ALL so a body spanning many lines is captured. */
    private val FENCED_BLOCK = Regex(
        "```[ \\t]*([A-Za-z0-9_+#.-]*)[ \\t]*\\r?\\n([\\s\\S]*?)```"
    )

    fun forSpeech(raw: String): String {
        val withoutCode = replaceFencedBlocks(raw)
        val withoutFormatting = stripFormatting(withoutCode)
        return withoutFormatting.trim()
    }

    /** Swap each fenced block for either its plain-text contents or the code
     *  note, deciding by the block's language label. */
    private fun replaceFencedBlocks(text: String): String {
        val sb = StringBuilder()
        var last = 0
        for (match in FENCED_BLOCK.findAll(text)) {
            sb.append(text, last, match.range.first)
            val language = match.groupValues[1].trim().lowercase()
            val body = match.groupValues[2]
            if (language in PLAIN_TEXT_LANGUAGES) {
                sb.append(body.trim())
            } else {
                sb.append(CODE_NOTE)
            }
            sb.append("\n")
            last = match.range.last + 1
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    private val HORIZONTAL_RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?[\\s:|-]*-[\\s:|-]*\\|?\\s*$")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s*")
    private val BLOCKQUOTE = Regex("^\\s{0,3}>+\\s?")
    private val LIST_MARKER = Regex("^\\s{0,3}[-*+]\\s+")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val EMPHASIS = Regex("[*`~]+")
    private val EMPHASIS_UNDERSCORE = Regex("(?<![A-Za-z0-9])_+|_+(?![A-Za-z0-9])")
    private val MULTISPACE = Regex("[ \\t]{2,}")
    private val MULTINEWLINE = Regex("\\n{3,}")

    /** Remove the Markdown symbols that would otherwise be pronounced, while
     *  keeping the words they wrapped. Works line by line for block-level marks
     *  (headings, quotes, list bullets, dividers, table rows) then clears the
     *  inline ones (links, emphasis, inline code). */
    private fun stripFormatting(text: String): String {
        val out = ArrayList<String>()
        for (rawLine in text.split("\n")) {
            val trimmed = rawLine.trim()
            // A divider (---, ***, ___) or a table separator row (|---|---|)
            // carries no words to read, so drop the whole line.
            if (HORIZONTAL_RULE.matches(trimmed)) continue
            if (trimmed.contains("|") && TABLE_SEPARATOR.matches(trimmed)) continue

            var line = rawLine
            line = line.replace(HEADING, "")
            line = line.replace(BLOCKQUOTE, "")
            line = line.replace(LIST_MARKER, "")

            // Table row: read the cells as a short comma-separated run instead
            // of speaking the pipes.
            if (line.contains("|")) {
                line = line.trim().removePrefix("|").removeSuffix("|")
                line = line.split("|").joinToString(", ") { it.trim() }
            }

            out.add(line)
        }

        var s = out.joinToString("\n")
        s = s.replace(IMAGE, "$1")
        s = s.replace(LINK, "$1")
        s = s.replace(EMPHASIS, "")
        s = s.replace(EMPHASIS_UNDERSCORE, "")
        s = s.replace(MULTISPACE, " ")
        s = s.replace(MULTINEWLINE, "\n\n")
        return s
    }
}
