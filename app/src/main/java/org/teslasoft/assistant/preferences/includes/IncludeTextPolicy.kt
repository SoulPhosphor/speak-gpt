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
 * Weight is measured in estimated tokens, not characters. The estimate is
 * deliberately arithmetic rather than a real tokenizer pass: running the
 * app's tokenizer here would repeat the main-thread stall that froze readback
 * (see CLAUDE.md). Every number is rendered with a leading "~" because
 * tokenization differs by model.
 */
object IncludeTextPolicy {

    /** Average characters per token across the models this app talks to. */
    private const val CHARS_PER_TOKEN = 4

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
     * Applies the documented size rules to extracted text.
     *
     * At or below [MAX_TOKENS] it goes as-is; the row's token estimate already
     * states its size. Above [MAX_TOKENS] it is cut at the cap and says so —
     * the one thing never allowed is silent truncation.
     *
     * A spreadsheet takes a different road ([trimCsv]): cutting a CSV at a
     * character count can strip the header and hide how much was dropped,
     * which would let the model analyse a fragment as though it were the
     * whole file.
     */
    fun applySizeGuard(
        text: String,
        kind: IncludeKind,
        sourceTruncated: Boolean = false,
        csvTotalRows: Int? = null
    ): SizedText {
        val tokens = estimateTokens(text)
        if (!sourceTruncated && tokens <= MAX_TOKENS) {
            return SizedText(text, IncludeNotice.None)
        }

        if (kind == IncludeKind.CSV) {
            val trimmed = trimCsv(
                text = text,
                totalRowsOverride = csvTotalRows,
                sourceTruncated = sourceTruncated
            )
            if (trimmed != null) return trimmed
            // Not row-shaped after all; fall through to the plain rules.
        }

        val cut = truncateToEstimatedTokens(text, MAX_TOKENS)
        return SizedText(cut, IncludeNotice.Truncated(estimateTokens(cut)))
    }

    /**
     * Keeps a spreadsheet's header row plus the first [CSV_MAX_ROWS] data
     * rows, and reports the true total so the model knows what it is missing.
     * Returns null when the text has too few rows to be worth trimming this
     * way (the caller then applies the ordinary rules).
     */
    fun trimCsv(
        text: String,
        totalRowsOverride: Int? = null,
        sourceTruncated: Boolean = false
    ): SizedText? {
        val parsed = parseCsvRecords(text)
        val records = parsed.records.toMutableList()
        if (sourceTruncated && !parsed.endsAtRecordBoundary && records.isNotEmpty()) {
            records.removeAt(records.lastIndex)
        }

        val nonBlankRecords = records.filter { it.isNotBlank() }
        if (nonBlankRecords.isEmpty()) return null

        val header = nonBlankRecords.first()
        val availableRows = nonBlankRecords.drop(1)
        val totalRows = totalRowsOverride ?: availableRows.size
        if (totalRows <= CSV_MAX_ROWS || availableRows.isEmpty()) return null

        val kept = availableRows.take(CSV_MAX_ROWS)
        val body = StringBuilder(header)
        for (record in kept) body.append('\n').append(record)
        return SizedText(
            body.toString(),
            IncludeNotice.CsvTrimmed(sentRows = kept.size, totalRows = maxOf(totalRows, kept.size))
        )
    }

    /** Counts logical CSV records without treating a newline inside quotes as
     * a new row. The first non-empty record is the header. */
    fun countCsvDataRows(reader: java.io.Reader): Int {
        var inQuotes = false
        var recordHasContent = false
        var records = 0
        var previousBoundaryWasCr = false
        val buffer = CharArray(8 * 1024)

        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            for (index in 0 until read) {
                val c = buffer[index]
                if (c == '"') {
                    inQuotes = !inQuotes
                    recordHasContent = true
                    previousBoundaryWasCr = false
                    continue
                }

                if (!inQuotes && (c == '\n' || c == '\r')) {
                    if (c == '\n' && previousBoundaryWasCr) {
                        previousBoundaryWasCr = false
                        continue
                    }
                    if (recordHasContent) records++
                    recordHasContent = false
                    previousBoundaryWasCr = c == '\r'
                    continue
                }

                if (!c.isWhitespace()) recordHasContent = true
                previousBoundaryWasCr = false
            }
        }

        if (recordHasContent) records++
        return maxOf(0, records - 1)
    }

    private data class CsvRecords(
        val records: List<String>,
        val endsAtRecordBoundary: Boolean
    )

    private fun parseCsvRecords(text: String): CsvRecords {
        val records = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        var endsAtBoundary = false

        while (index < text.length) {
            val c = text[index]
            if (c == '"') {
                inQuotes = !inQuotes
                current.append(c)
                endsAtBoundary = false
                index++
                continue
            }

            if (!inQuotes && (c == '\n' || c == '\r')) {
                records.add(current.toString())
                current.setLength(0)
                if (c == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                    index++
                }
                endsAtBoundary = true
                index++
                continue
            }

            current.append(c)
            endsAtBoundary = false
            index++
        }

        if (current.isNotEmpty()) records.add(current.toString())
        return CsvRecords(records, endsAtBoundary)
    }

    private fun truncateToEstimatedTokens(text: String, maxTokens: Int): String {
        var asciiCharacters = 0
        var nonAsciiTokens = 0
        var index = 0
        var safeEnd = 0

        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (codePoint <= 0x7F) {
                asciiCharacters++
            } else {
                nonAsciiTokens += if (charCount == 2) 2 else 1
            }

            val tokens =
                (asciiCharacters + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + nonAsciiTokens
            if (tokens > maxTokens) break
            index += charCount
            safeEnd = index
        }

        return text.substring(0, safeEnd)
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
