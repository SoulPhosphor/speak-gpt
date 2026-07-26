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

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Reads the cell values out of an .xlsx workbook.
 *
 * Like .docx, an .xlsx is a zip of XML, so the platform's own zip support and
 * a small tag walk are enough — no third-party spreadsheet library is added
 * to an app deliberately kept lean. This is the spreadsheet counterpart of
 * [DocxTextExtractor] and follows the same shape on purpose.
 *
 * WHAT IS READ: every visible worksheet, in the order the workbook lists
 * them, as rows of cell values. Shared strings, inline strings, numbers,
 * booleans, and the values a spreadsheet program already calculated and
 * stored for its formulas.
 *
 * WHAT IS NOT READ, deliberately: formatting, number formats, charts,
 * images, comments, pivot tables, macros, validation rules, hidden
 * worksheets, and anything else that is not a cell value. Formulas are NOT
 * evaluated — only the value last stored with them is used, so a workbook
 * saved without cached values yields empty cells there.
 *
 * This is NOT general Excel compatibility, and must not be described as
 * such. It is a narrow reader for getting a spreadsheet's contents in front
 * of the AI as text.
 *
 * KNOWN LIMITATION: because number formats are ignored, a date is read as
 * the number a spreadsheet stores underneath it (a day count), not as a
 * date. Text, numbers, and booleans read normally.
 */
object XlsxTextExtractor {

    private const val WORKBOOK_ENTRY = "xl/workbook.xml"
    private const val WORKBOOK_RELS_ENTRY = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml"
    private const val WORKSHEET_PREFIX = "xl/worksheets/"

    /** Guards against a zip bomb in any single part. */
    private const val MAX_XML_CHARS = 4_000_000

    /** Guards against a workbook of many individually-legal parts. */
    private const val MAX_TOTAL_CHARS = 12_000_000

    private const val CFB_SCAN_BYTES = 2 * 1024 * 1024

    /**
     * Magic bytes at the start of an OLE2 Compound File Binary container.
     * Both encrypted Office files and legacy binary Office files use this
     * container, so the signature alone is not evidence of encryption.
     */
    private val CFB_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    /** One worksheet's label and its rows, each row already CSV-encoded. */
    data class SheetData(val name: String, val rows: List<String>)

    /** Outcome of attempting to read an .xlsx. */
    sealed class ExtractResult {
        data class Success(
            val sheets: List<SheetData>,
            val sourceTruncated: Boolean = false
        ) : ExtractResult()

        /** Not a zip at all, or a zip with no `xl/workbook.xml` entry — no
         *  positive evidence this was ever a genuine workbook. */
        data object NotXlsx : ExtractResult()

        /** An OLE2/CFB container with an encrypted OOXML package stream. */
        data object PasswordProtected : ExtractResult()

        /** `xl/workbook.xml` was located, but the workbook could not be read
         *  — positive evidence of a genuine, damaged spreadsheet. */
        data object Corrupted : ExtractResult()
    }

    fun extract(bytes: ByteArray): ExtractResult = extract(bytes.inputStream())

    fun extract(input: InputStream): ExtractResult {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(CFB_SCAN_BYTES + 1)
        val signature = readPrefix(buffered, CFB_SIGNATURE.size)
        if (isCfbContainer(signature)) {
            val prefix = signature + readPrefix(buffered, CFB_SCAN_BYTES - signature.size)
            buffered.reset()
            return if (containsEncryptedPackageMarker(prefix)) {
                ExtractResult.PasswordProtected
            } else {
                ExtractResult.NotXlsx
            }
        }
        buffered.reset()

        val parts = try {
            readParts(buffered)
        } catch (_: Exception) {
            return ExtractResult.NotXlsx
        }

        // Seeing the workbook part is what proves this is a spreadsheet at
        // all. Once it has been seen, every later failure is a DAMAGED
        // workbook, never "some other kind of file" — the two need different
        // explanations and only this ordering can tell them apart.
        if (!parts.sawWorkbook) return ExtractResult.NotXlsx
        val workbookXml = parts.entries[WORKBOOK_ENTRY] ?: return ExtractResult.Corrupted
        if (parts.damaged) return ExtractResult.Corrupted

        return try {
            val shared = parts.entries[SHARED_STRINGS_ENTRY]
                ?.let { parseSharedStrings(it) }
                ?: emptyList()
            val relations = parts.entries[WORKBOOK_RELS_ENTRY]
                ?.let { parseRelationships(it) }
                ?: emptyMap()
            val ordered = parseWorkbookSheets(workbookXml)

            val sheets = ArrayList<SheetData>(ordered.size)
            var fallbackIndex = 0
            for (sheet in ordered) {
                val entryName = worksheetEntryName(sheet, relations)
                    ?: nthWorksheetEntry(parts.worksheetOrder, fallbackIndex)
                fallbackIndex++
                val sheetXml = entryName?.let { parts.entries[it] } ?: continue
                sheets.add(SheetData(sheet.name, parseSheetRows(sheetXml, shared)))
            }

            // A workbook part that lists no usable worksheet is a damaged
            // spreadsheet, not "some other file" — the workbook part proved
            // this really is an .xlsx.
            if (sheets.isEmpty()) return ExtractResult.Corrupted

            ExtractResult.Success(sheets, parts.sourceTruncated)
        } catch (_: Exception) {
            ExtractResult.Corrupted
        }
    }

    // ---- zip reading ------------------------------------------------------

    private class Parts(
        val entries: Map<String, String>,
        val worksheetOrder: List<String>,
        val sourceTruncated: Boolean,
        /** The workbook part was present, whether or not it could be read. */
        val sawWorkbook: Boolean,
        /** A part was located but could not be decoded or unpacked. */
        val damaged: Boolean
    )

    /**
     * Collects the parts of interest in ONE pass.
     *
     * A second pass is not possible (the source is a stream, and re-opening a
     * Google export would mean asking Drive to convert the document all over
     * again), and a single pass is not enough on its own: `sharedStrings.xml`
     * — where most cell text actually lives — is normally written AFTER the
     * worksheets that reference it. So the worksheet XML is held and the
     * strings are resolved once everything has been seen.
     */
    private fun readParts(input: InputStream): Parts {
        val entries = HashMap<String, String>()
        val worksheetOrder = ArrayList<String>()
        var totalChars = 0
        var truncated = false
        var sawWorkbook = false
        var damaged = false

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry ?: break
                } catch (_: Exception) {
                    // The archive stopped making sense part-way. If the
                    // workbook part was already seen, this is a damaged
                    // spreadsheet; if not, it was never one to begin with.
                    damaged = true
                    break
                }
                val name = entry.name.removePrefix("/")
                if (name == WORKBOOK_ENTRY) sawWorkbook = true

                val wanted = name == WORKBOOK_ENTRY ||
                    name == WORKBOOK_RELS_ENTRY ||
                    name == SHARED_STRINGS_ENTRY ||
                    (name.startsWith(WORKSHEET_PREFIX) && name.endsWith(".xml"))
                if (!wanted) continue

                if (totalChars >= MAX_TOTAL_CHARS) {
                    truncated = true
                    continue
                }
                val budget = minOf(MAX_XML_CHARS, MAX_TOTAL_CHARS - totalChars)
                val text = try {
                    readEntryText(zip, budget)
                } catch (_: Exception) {
                    // Located but unreadable. Skipping it silently would drop
                    // a worksheet without telling anyone, so it is recorded
                    // and the whole workbook is refused instead.
                    damaged = true
                    continue
                }
                entries[name] = text.text
                totalChars += text.text.length
                if (text.sourceTruncated) truncated = true
                if (name.startsWith(WORKSHEET_PREFIX)) worksheetOrder.add(name)
            }
        }
        return Parts(entries, worksheetOrder, truncated, sawWorkbook, damaged)
    }

    private data class EntryText(val text: String, val sourceTruncated: Boolean)

    private fun readEntryText(zip: ZipInputStream, limit: Int): EntryText {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = InputStreamReader(zip, decoder)
        val out = StringBuilder()
        val buffer = CharArray(8 * 1024)

        while (out.length < limit) {
            val remaining = limit - out.length
            val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) return EntryText(out.toString(), false)
            if (read == 0) continue
            out.append(buffer, 0, read)
        }
        return EntryText(out.toString(), reader.read() >= 0)
    }

    private fun readPrefix(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            if (read == 0) continue
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun isCfbContainer(bytes: ByteArray): Boolean {
        if (bytes.size < CFB_SIGNATURE.size) return false
        for (i in CFB_SIGNATURE.indices) if (bytes[i] != CFB_SIGNATURE[i]) return false
        return true
    }

    private fun containsEncryptedPackageMarker(bytes: ByteArray): Boolean =
        containsUtf16Le(bytes, "EncryptedPackage") && containsUtf16Le(bytes, "EncryptionInfo")

    private fun containsUtf16Le(bytes: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_16LE)
        if (needle.size > bytes.size) return false
        for (start in 0..(bytes.size - needle.size)) {
            var matches = true
            for (offset in needle.indices) {
                if (bytes[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    // ---- workbook structure ----------------------------------------------

    /** One `<sheet>` entry of the workbook's own list. */
    data class SheetRef(val name: String, val relationId: String?)

    /**
     * The visible worksheets, in workbook order. Hidden worksheets are
     * skipped: they are not part of what the user is looking at, and pulling
     * them in would silently spend the row budget on content the user did
     * not know was there.
     */
    fun parseWorkbookSheets(xml: String): List<SheetRef> {
        val out = ArrayList<SheetRef>()
        forEachTag(xml) { body ->
            if (tagName(body) != "sheet") return@forEachTag
            val state = attribute(body, "state")
            if (state != null && !state.equals("visible", ignoreCase = true)) return@forEachTag
            val name = attribute(body, "name")?.let { unescape(it) } ?: return@forEachTag
            out.add(SheetRef(name, attribute(body, "r:id") ?: attribute(body, "id")))
        }
        return out
    }

    /** Relationship id -> part name, from `xl/_rels/workbook.xml.rels`. */
    fun parseRelationships(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        forEachTag(xml) { body ->
            if (tagName(body) != "Relationship") return@forEachTag
            val id = attribute(body, "Id") ?: return@forEachTag
            val target = attribute(body, "Target") ?: return@forEachTag
            out[id] = normalizeTarget(unescape(target))
        }
        return out
    }

    /** A relationship target is relative to the `xl/` folder unless absolute. */
    private fun normalizeTarget(target: String): String =
        if (target.startsWith("/")) target.removePrefix("/") else "xl/${target.removePrefix("./")}"

    private fun worksheetEntryName(sheet: SheetRef, relations: Map<String, String>): String? {
        val id = sheet.relationId ?: return null
        val target = relations[id] ?: return null
        return if (target.startsWith(WORKSHEET_PREFIX)) target else null
    }

    /** Fallback when the relationship table is missing or does not resolve:
     *  match workbook order to worksheet-part order. */
    private fun nthWorksheetEntry(order: List<String>, index: Int): String? =
        order.getOrNull(index)

    // ---- part parsing -----------------------------------------------------

    /**
     * The workbook's shared string table. Most text in a spreadsheet is
     * stored once here and referenced by number from the cells, so without
     * this a workbook reads as a grid of integers.
     */
    fun parseSharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inItem = false
        var inText = false
        var inPhonetic = false
        var index = 0

        while (index < xml.length) {
            val c = xml[index]
            if (c == '<') {
                val close = xml.indexOf('>', index)
                if (close < 0) break
                val body = xml.substring(index + 1, close)
                val selfClosing = body.endsWith("/")
                when (tagName(body)) {
                    "si" -> {
                        if (selfClosing) {
                            out.add("")
                        } else {
                            inItem = true
                            current.setLength(0)
                        }
                    }
                    "/si" -> {
                        if (inItem) {
                            out.add(unescape(current.toString()))
                            inItem = false
                        }
                    }
                    // Phonetic guides are pronunciation hints, not content.
                    "rPh" -> if (!selfClosing) inPhonetic = true
                    "/rPh" -> inPhonetic = false
                    "t" -> if (!selfClosing) inText = true
                    "/t" -> inText = false
                }
                index = close + 1
            } else {
                if (inItem && inText && !inPhonetic) current.append(c)
                index++
            }
        }
        return out
    }

    /**
     * One worksheet's rows, each already CSV-encoded so the rest of the app
     * can treat a workbook the way it treats a spreadsheet file.
     *
     * Rows that carry no content at all are dropped, matching how the CSV
     * path already ignores blank records — otherwise a decorative blank line
     * at the top of a sheet would be taken for its header row.
     */
    fun parseSheetRows(xml: String, shared: List<String>): List<String> {
        val rows = ArrayList<String>()
        var inSheetData = false
        var cells: TreeMapLike? = null

        var inCell = false
        var cellColumn = 0
        var cellType = ""
        var capturing = false
        val value = StringBuilder()

        var index = 0
        while (index < xml.length) {
            val c = xml[index]
            if (c != '<') {
                if (capturing) value.append(c)
                index++
                continue
            }
            val close = xml.indexOf('>', index)
            if (close < 0) break
            val body = xml.substring(index + 1, close)
            val selfClosing = body.endsWith("/")
            when (val name = tagName(body)) {
                "sheetData" -> if (!selfClosing) inSheetData = true
                "/sheetData" -> inSheetData = false
                "row" -> if (inSheetData) {
                    cells = if (selfClosing) null else TreeMapLike()
                    inCell = false
                }
                "/row" -> {
                    val built = cells
                    if (built != null) {
                        val line = built.toCsvLine()
                        if (line.isNotEmpty()) rows.add(line)
                    }
                    cells = null
                    inCell = false
                }
                "c" -> {
                    val row = cells
                    if (row != null) {
                        val reference = attribute(body, "r")?.let { columnIndex(it) }
                        cellColumn = if (reference != null && reference >= 0) {
                            reference
                        } else {
                            row.nextColumn()
                        }
                        cellType = attribute(body, "t") ?: ""
                        value.setLength(0)
                        inCell = !selfClosing
                        if (selfClosing) row.put(cellColumn, "")
                    }
                }
                "/c" -> {
                    val row = cells
                    if (row != null && inCell) {
                        row.put(cellColumn, decodeCell(value.toString(), cellType, shared))
                        inCell = false
                    }
                }
                // Only a cell's value and its inline text are content. A
                // formula lives in <f> and is never captured, so formulas are
                // carried across as the value already stored with them and
                // are never evaluated here.
                "v", "t" -> if (inCell && !selfClosing) capturing = true
                "/v", "/t" -> capturing = false
                else -> if (name.isEmpty()) { /* comment or declaration */ }
            }
            index = close + 1
        }
        return rows
    }

    private fun decodeCell(raw: String, type: String, shared: List<String>): String {
        val text = unescape(raw)
        return when (type) {
            "s" -> text.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "b" -> if (text.trim() == "1") "TRUE" else "FALSE"
            // "inlineStr", "str" (a formula's stored text) and numbers all
            // arrive as their own text already.
            else -> text
        }
    }

    /**
     * Sparse cells by column index, rendered as one CSV line.
     *
     * A spreadsheet only stores the cells that have something in them, so a
     * row that skips column B arrives as A then C. Without filling that gap
     * every value after it would shift one column left and silently line up
     * under the wrong heading.
     */
    private class TreeMapLike {
        private val values = HashMap<Int, String>()
        private var highest = -1

        fun put(column: Int, value: String) {
            if (column < 0) return
            values[column] = value
            if (column > highest) highest = column
        }

        fun nextColumn(): Int = highest + 1

        fun toCsvLine(): String {
            if (highest < 0) return ""
            var last = -1
            for (i in 0..highest) {
                if (!values[i].isNullOrEmpty()) last = i
            }
            if (last < 0) return ""
            val out = StringBuilder()
            for (i in 0..last) {
                if (i > 0) out.append(',')
                out.append(csvCell(values[i] ?: ""))
            }
            return out.toString()
        }
    }

    private fun csvCell(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    /** `A1` -> 0, `B7` -> 1, `AA3` -> 26. Returns -1 when unparseable. */
    fun columnIndex(reference: String): Int {
        var result = 0
        var seen = 0
        for (c in reference) {
            val upper = c.uppercaseChar()
            if (upper < 'A' || upper > 'Z') break
            result = result * 26 + (upper - 'A' + 1)
            seen++
        }
        return if (seen == 0) -1 else result - 1
    }

    // ---- small XML helpers ------------------------------------------------

    private inline fun forEachTag(xml: String, action: (String) -> Unit) {
        var index = 0
        while (index < xml.length) {
            val open = xml.indexOf('<', index)
            if (open < 0) break
            val close = xml.indexOf('>', open)
            if (close < 0) break
            action(xml.substring(open + 1, close))
            index = close + 1
        }
    }

    /**
     * The element name inside a tag body, with attributes and any
     * self-closing slash stripped. Same positional rules as the Word
     * extractor: a CLOSING tag's leading slash is part of its name, a
     * SELF-CLOSING tag's trailing slash is not.
     */
    private fun tagName(tagBody: String): String {
        val start = if (tagBody.startsWith("/")) 1 else 0
        var end = tagBody.length
        for (i in start until tagBody.length) {
            val c = tagBody[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/') {
                end = i
                break
            }
        }
        return tagBody.substring(0, end)
    }

    /** Reads `name="value"` (or single-quoted) out of a tag body. */
    fun attribute(tagBody: String, name: String): String? {
        var search = 0
        while (true) {
            val at = tagBody.indexOf(name, search)
            if (at < 0) return null
            search = at + name.length
            // Must be a whole attribute name, not the tail of a longer one.
            if (at > 0 && !tagBody[at - 1].isWhitespace()) continue
            var i = search
            while (i < tagBody.length && tagBody[i].isWhitespace()) i++
            if (i >= tagBody.length || tagBody[i] != '=') continue
            i++
            while (i < tagBody.length && tagBody[i].isWhitespace()) i++
            if (i >= tagBody.length) return null
            val quote = tagBody[i]
            if (quote != '"' && quote != '\'') continue
            val end = tagBody.indexOf(quote, i + 1)
            if (end < 0) return null
            return tagBody.substring(i + 1, end)
        }
    }

    /**
     * Decodes XML entities, including the numeric ones. Numeric entities
     * matter here in a way they do not for Word text: a line break inside a
     * spreadsheet cell is stored as `&#10;`, and left encoded it would reach
     * the AI as literal ampersand-hash-ten in the middle of the data.
     */
    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = s.indexOf(';', i)
            if (end < 0 || end - i > 10) {
                out.append(c)
                i++
                continue
            }
            when (val entity = s.substring(i + 1, end)) {
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                "amp" -> out.append('&')
                else -> {
                    val code = when {
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.substring(2).toIntOrNull(16)
                        entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                        else -> null
                    }
                    if (code != null && code in 1..0x10FFFF) {
                        out.appendCodePoint(code)
                    } else {
                        out.append(s, i, end + 1)
                    }
                }
            }
            i = end + 1
        }
        return out.toString()
    }
}
