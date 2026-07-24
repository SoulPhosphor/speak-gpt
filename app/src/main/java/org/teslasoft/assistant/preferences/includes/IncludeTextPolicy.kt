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
 * Pure decision logic for attached documents: how heavy an item is, whether
 * it is safe to send whole, and how to shrink it honestly when it isn't.
 * No Android dependencies, so all of it is unit-tested.
 *
 * Weight is measured in ESTIMATED TOKENS, not characters (owner ruling, July
 * 24 2026) — tokens are what the user actually pays for, so that is the one
 * unit shown everywhere in the UI. The estimate is deliberately arithmetic
 * rather than a real tokenizer pass: running the app's tokenizer here would
 * repeat the main-thread stall that froze readback (see CLAUDE.md), and an
 * estimate within roughly a quarter of the true count is all the UI claims —
 * every number is rendered with a leading "~".
 */
object IncludeTextPolicy {

    /** Average characters per token across the models this app talks to. */
    private const val CHARS_PER_TOKEN = 4

    /** Above this, an item is flagged large but still sent whole. */
    const val LARGE_TOKENS = 10_000

    /** Above this, an item cannot be sent whole and is cut at the cap. */
    const val MAX_TOKENS = 30_000

    /** Rows kept from an oversized spreadsheet, after its header row. */
    const val CSV_MAX_ROWS = 500

    /** Bytes inspected when deciding whether a file is genuinely text. */
    private const val GARBAGE_SAMPLE = 4_000

    /**
     * Share of control characters above which a file is treated as binary
     * rather than text. Real text files carry almost none; a renamed binary
     * is saturated with them.
     */
    private const val GARBAGE_RATIO = 0.05

    fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    private fun tokensToChars(tokens: Int): Int = tokens * CHARS_PER_TOKEN

    /**
     * Decides whether extracted text looks like a real text document. A
     * renamed binary must be refused with an explanation rather than dumped
     * into the prompt as thousands of junk characters.
     *
     * Tabs, newlines and carriage returns are ordinary text; other C0
     * control characters and NUL are not.
     */
    fun looksLikeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val sample = if (text.length > GARBAGE_SAMPLE) text.substring(0, GARBAGE_SAMPLE) else text
        var control = 0
        for (c in sample) {
            if (c == '\t' || c == '\n' || c == '\r') continue
            if (c.code < 0x20 || c.code == 0x7F || c == '�') control++
        }
        return control.toDouble() / sample.length <= GARBAGE_RATIO
    }

    /** The outcome of size-guarding one extracted document. */
    data class SizedText(val text: String, val notice: IncludeNotice)

    /**
     * Applies the owner-approved size rules to extracted text.
     *
     * Under [LARGE_TOKENS] it goes as-is. Between there and [MAX_TOKENS] it
     * still goes whole, but carries the large-file notice so the cost is
     * visible. Above [MAX_TOKENS] it is cut at the cap and says so — the one
     * thing never allowed is silent truncation.
     *
     * A spreadsheet takes a different road ([trimCsv]): cutting a CSV at a
     * character count can strip the header and hide how much was dropped,
     * which would let the model analyse a fragment as though it were the
     * whole file.
     */
    fun applySizeGuard(text: String, kind: IncludeKind): SizedText {
        val tokens = estimateTokens(text)
        if (tokens <= LARGE_TOKENS) return SizedText(text, IncludeNotice.None)

        if (kind == IncludeKind.CSV) {
            val trimmed = trimCsv(text)
            if (trimmed != null) return trimmed
            // Not row-shaped after all; fall through to the plain rules.
        }

        if (tokens <= MAX_TOKENS) return SizedText(text, IncludeNotice.Large(tokens))

        val cut = text.substring(0, minOf(text.length, tokensToChars(MAX_TOKENS)))
        return SizedText(cut, IncludeNotice.Truncated(estimateTokens(cut)))
    }

    /**
     * Keeps a spreadsheet's header row plus the first [CSV_MAX_ROWS] data
     * rows, and reports the true total so the model knows what it is missing.
     * Returns null when the text has too few rows to be worth trimming this
     * way (the caller then applies the ordinary rules).
     */
    fun trimCsv(text: String): SizedText? {
        val lines = text.split("\n")
        // Header + the row cap + at least one dropped row, else nothing to do.
        if (lines.size <= CSV_MAX_ROWS + 2) return null

        val header = lines.first()
        val kept = lines.subList(1, minOf(lines.size, CSV_MAX_ROWS + 1))
        val totalRows = lines.count { it.isNotBlank() } - 1
        val body = StringBuilder(header)
        for (line in kept) body.append('\n').append(line)
        return SizedText(
            body.toString(),
            IncludeNotice.CsvTrimmed(sentRows = kept.size, totalRows = maxOf(totalRows, kept.size))
        )
    }

    /**
     * The bookmark line used when the model cannot be reached to write one.
     * Removal must never block or fail on a network problem — the fallback
     * IS a success path, and the user can edit the line afterwards.
     */
    fun fallbackArtifactLine(fileName: String): String = "User sent $fileName."

    /**
     * Trims a model-written bookmark line down to a single short sentence.
     * Models over-deliver on "one line"; this keeps the artifact genuinely
     * cheap no matter what comes back, and falls back when nothing usable
     * arrives.
     */
    fun sanitizeArtifactLine(raw: String?, fileName: String, maxWords: Int = 12): String {
        val flat = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (flat.isEmpty()) return fallbackArtifactLine(fileName)
        val words = flat.split(" ")
        val clipped = if (words.size <= maxWords) flat else words.take(maxWords).joinToString(" ")
        return if (clipped.endsWith(".") || clipped.endsWith("!") || clipped.endsWith("?")) {
            clipped
        } else {
            "$clipped."
        }
    }
}
