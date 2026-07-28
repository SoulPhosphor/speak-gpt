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

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.FilterReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * Complete, file-backed DOCX/XLSX extraction used by new document imports.
 *
 * The source has already reached EOF in an app-private temporary file before
 * any method here runs. Zip entries are then parsed incrementally. Worksheet
 * XML is never retained as a complete String and workbook rows stream directly
 * into the one normalized attachment output.
 */
internal object CompleteOfficeTextExtractor {

    sealed class Result {
        data class Success(val text: String) : Result()
        data object NotOfficeDocument : Result()
        data object PasswordProtected : Result()
        data object Corrupted : Result()
        data object DeviceMemoryLimit : Result()
        data object ArchiveExpansionLimit : Result()
    }

    private const val DOCX_DOCUMENT = "word/document.xml"
    private const val XLSX_WORKBOOK = "xl/workbook.xml"
    private const val XLSX_RELATIONSHIPS = "xl/_rels/workbook.xml.rels"
    private const val XLSX_SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val XLSX_WORKSHEET_PREFIX = "xl/worksheets/"
    private const val MAX_XML_CHARS_PER_PART = 4_000_000L
    private const val MAX_XLSX_XML_CHARS = 12_000_000L
    private const val CFB_SCAN_BYTES = 2 * 1024 * 1024

    private val CFB_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    fun extractDocx(
        file: File,
        budget: ImportMemoryBudget,
        expansion: OfficeArchiveExpansionGuard
    ): Result {
        encryptedContainerResult(file, budget)?.let { return it }
        return try {
            ZipFile(file).use { zip ->
                preflightArchive(zip, budget, expansion)
                val entry = zip.getEntry(DOCX_DOCUMENT) ?: return Result.NotOfficeDocument
                val output = BudgetedTextBuilder(budget)
                try {
                    parseEntry(
                        zip, entry, budget, expansion, MAX_XML_CHARS_PER_PART,
                        XmlCharTotal(MAX_XML_CHARS_PER_PART),
                        WordHandler(output, expansion)
                    )
                    trimTrailingWhitespace(output)
                    val text = output.finish()
                    if (text.isBlank()) Result.Success("") else Result.Success(text)
                } catch (e: ImportMemoryBudget.LimitExceeded) {
                    output.discard()
                    Result.DeviceMemoryLimit
                } catch (e: OfficeArchiveExpansionGuard.LimitExceeded) {
                    output.discard()
                    Result.ArchiveExpansionLimit
                } catch (e: XmlCharacterLimitExceeded) {
                    output.discard()
                    Result.ArchiveExpansionLimit
                } catch (e: OutOfMemoryError) {
                    output.discard()
                    Result.DeviceMemoryLimit
                } catch (e: Exception) {
                    output.discard()
                    Result.Corrupted
                }
            }
        } catch (e: OfficeArchiveExpansionGuard.LimitExceeded) {
            Result.ArchiveExpansionLimit
        } catch (e: ImportMemoryBudget.LimitExceeded) {
            Result.DeviceMemoryLimit
        } catch (e: OutOfMemoryError) {
            Result.DeviceMemoryLimit
        } catch (e: ZipException) {
            Result.NotOfficeDocument
        } catch (e: Exception) {
            Result.NotOfficeDocument
        }
    }

    fun extractXlsx(
        file: File,
        budget: ImportMemoryBudget,
        expansion: OfficeArchiveExpansionGuard
    ): Result {
        encryptedContainerResult(file, budget)?.let { return it }
        return try {
            ZipFile(file).use { zip ->
                preflightArchive(zip, budget, expansion)
                val workbookEntry = zip.getEntry(XLSX_WORKBOOK)
                    ?: return Result.NotOfficeDocument
                val xmlTotal = XmlCharTotal(MAX_XLSX_XML_CHARS)

                val workbookHandler = WorkbookHandler(budget)
                parseEntry(
                    zip, workbookEntry, budget, expansion, MAX_XML_CHARS_PER_PART,
                    xmlTotal, workbookHandler
                )
                val orderedSheets = workbookHandler.sheets
                if (orderedSheets.isEmpty()) return Result.Corrupted

                val relationships = zip.getEntry(XLSX_RELATIONSHIPS)?.let { entry ->
                    val handler = RelationshipsHandler(budget)
                    parseEntry(
                        zip, entry, budget, expansion, MAX_XML_CHARS_PER_PART,
                        xmlTotal, handler
                    )
                    handler.relationships
                } ?: emptyMap()

                val shared = zip.getEntry(XLSX_SHARED_STRINGS)?.let { entry ->
                    val handler = SharedStringsHandler(budget)
                    parseEntry(
                        zip, entry, budget, expansion, MAX_XML_CHARS_PER_PART,
                        xmlTotal, handler
                    )
                    handler.values
                } ?: emptyList()

                val output = BudgetedTextBuilder(budget)
                var sheetsParsed = 0
                var nonEmptyRows = 0
                try {
                    for (sheet in orderedSheets) {
                        val relationshipId = sheet.relationshipId
                            ?: throw CorruptedOfficeDocument()
                        val resolved = relationships[relationshipId]
                            ?.takeIf {
                                it.startsWith(XLSX_WORKSHEET_PREFIX) &&
                                    it.endsWith(".xml")
                            }
                            ?: throw CorruptedOfficeDocument()
                        val entry = zip.getEntry(resolved)
                            ?: throw CorruptedOfficeDocument()

                        if (sheetsParsed > 0) {
                            appendOutput(output, "\n\n", expansion)
                        }
                        appendOutput(
                            output,
                            IncludeTextPolicy.workbookSheetLabel(sheet.name),
                            expansion
                        )
                        val worksheetHandler =
                            WorksheetHandler(output, shared, budget, expansion)
                        parseEntry(
                            zip, entry, budget, expansion,
                            MAX_XML_CHARS_PER_PART, xmlTotal, worksheetHandler
                        )
                        nonEmptyRows += worksheetHandler.nonEmptyRows
                        sheetsParsed++
                    }

                    trimTrailingWhitespace(output)
                    val text = output.finish()
                    shared.forEach { it.release() }
                    when {
                        sheetsParsed == 0 -> Result.Corrupted
                        nonEmptyRows == 0 -> Result.Success("")
                        else -> Result.Success(text)
                    }
                } catch (e: OutOfMemoryError) {
                    output.discard()
                    shared.forEach { it.release() }
                    return Result.DeviceMemoryLimit
                } catch (e: Exception) {
                    output.discard()
                    shared.forEach { it.release() }
                    throw e
                }
            }
        } catch (e: ImportMemoryBudget.LimitExceeded) {
            Result.DeviceMemoryLimit
        } catch (e: OutOfMemoryError) {
            Result.DeviceMemoryLimit
        } catch (e: OfficeArchiveExpansionGuard.LimitExceeded) {
            Result.ArchiveExpansionLimit
        } catch (e: XmlCharacterLimitExceeded) {
            Result.ArchiveExpansionLimit
        } catch (e: ZipException) {
            Result.NotOfficeDocument
        } catch (e: CorruptedOfficeDocument) {
            Result.Corrupted
        } catch (e: Exception) {
            // The workbook part was positively identified before later parse
            // work in the normal path. A malformed ZIP that cannot be opened
            // at all has no such evidence and remains a content mismatch.
            try {
                ZipFile(file).use { zip ->
                    if (zip.getEntry(XLSX_WORKBOOK) != null) Result.Corrupted
                    else Result.NotOfficeDocument
                }
            } catch (_: Exception) {
                Result.NotOfficeDocument
            }
        }
    }

    private fun encryptedContainerResult(
        file: File,
        budget: ImportMemoryBudget
    ): Result? {
        val charge = budget.claim(CFB_SCAN_BYTES.toLong())
        return try {
            val prefix = ByteArray(CFB_SCAN_BYTES)
            val read = file.inputStream().buffered().use { input ->
                var total = 0
                while (total < prefix.size) {
                    val count = input.read(prefix, total, prefix.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                total
            }
            if (!prefix.hasPrefix(CFB_SIGNATURE, read)) {
                null
            } else if (
                prefix.containsUtf16Le("EncryptedPackage", read) &&
                prefix.containsUtf16Le("EncryptionInfo", read)
            ) {
                Result.PasswordProtected
            } else {
                Result.NotOfficeDocument
            }
        } finally {
            charge.release()
        }
    }

    private fun preflightArchive(
        zip: ZipFile,
        budget: ImportMemoryBudget,
        expansion: OfficeArchiveExpansionGuard
    ) {
        var total = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            budget.claim(96L + entry.name.length.toLong() * 2L)
            val size = entry.size
            if (size < 0) continue
            if (total > Long.MAX_VALUE - size) {
                throw OfficeArchiveExpansionGuard.LimitExceeded()
            }
            total += size
        }
        expansion.preflightTotal(total)
    }

    private fun parseEntry(
        zip: ZipFile,
        entry: ZipEntry,
        budget: ImportMemoryBudget,
        expansion: OfficeArchiveExpansionGuard,
        perPartCharacterLimit: Long,
        totalCharacters: XmlCharTotal,
        handler: DefaultHandler
    ) {
        val parserCharge = budget.claim(128L * 1024L)
        try {
            zip.getInputStream(entry).use { raw ->
                val guarded = ExpandedInputStream(raw, expansion)
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val reader = CharacterLimitReader(
                    InputStreamReader(guarded, decoder),
                    perPartCharacterLimit,
                    totalCharacters
                )
                val parser = secureSaxFactory().newSAXParser().xmlReader
                parser.contentHandler = handler
                parser.errorHandler = handler
                parser.entityResolver = org.xml.sax.EntityResolver { _, _ ->
                    InputSource(StringReader(""))
                }
                parser.parse(InputSource(reader))
            }
        } finally {
            parserCharge.release()
        }
    }

    private fun secureSaxFactory(): SAXParserFactory =
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }

    private fun SAXParserFactory.setFeatureSafely(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Android parser variants do not all expose every hardening flag.
            // The entity resolver still refuses all external entities.
        }
    }

    private class ExpandedInputStream(
        input: InputStream,
        private val expansion: OfficeArchiveExpansionGuard
    ) : FilterInputStream(input) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) expansion.account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) expansion.account(read)
            return read
        }
    }

    private class XmlCharacterLimitExceeded : Exception()
    private class CorruptedOfficeDocument : Exception()

    private class XmlCharTotal(private val maximum: Long) {
        private var total = 0L

        fun account(count: Int) {
            if (total > maximum - count.toLong()) throw XmlCharacterLimitExceeded()
            total += count
        }
    }

    private class CharacterLimitReader(
        reader: Reader,
        private val partMaximum: Long,
        private val total: XmlCharTotal
    ) : FilterReader(reader) {
        private var part = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) account(read)
            return read
        }

        private fun account(count: Int) {
            if (part > partMaximum - count.toLong()) throw XmlCharacterLimitExceeded()
            part += count
            total.account(count)
        }
    }

    private class WordHandler(
        private val output: BudgetedTextBuilder,
        private val expansion: OfficeArchiveExpansionGuard
    ) : DefaultHandler() {
        private var inText = false

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            when (localName.orEmpty()) {
                "t" -> inText = true
                "br", "cr" -> appendBreak()
                "tab" -> appendOutput(output, "\t", expansion)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (localName.orEmpty()) {
                "t" -> inText = false
                "p" -> appendBreak()
            }
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (!inText || length == 0) return
            output.append(chars, start, length)
            expansion.accountNormalized(utf8Bytes(chars, start, length))
        }

        private fun appendBreak() {
            while (output.length > 0) {
                val last = output.charAt(output.length - 1)
                if (last != ' ' && last != '\t' && last != '\r') break
                output.setLength(output.length - 1)
            }
            var newlines = 0
            var index = output.length - 1
            while (index >= 0 && output.charAt(index) == '\n') {
                newlines++
                index--
            }
            if (output.isNotEmpty() && newlines < 2) {
                appendOutput(output, "\n", expansion)
            }
        }
    }

    private data class SheetRef(val name: String, val relationshipId: String?)

    private class WorkbookHandler(private val budget: ImportMemoryBudget) : DefaultHandler() {
        val sheets = ArrayList<SheetRef>()

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            if (localName != "sheet" || attributes == null) return
            val name = attributes.getValue("name") ?: return
            val relationshipId = attributes.getValue(
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                "id"
            ) ?: attributes.getValue("r:id") ?: attributes.getValue("id")
            budget.claim(48L + name.length.toLong() * 2L +
                (relationshipId?.length?.toLong() ?: 0L) * 2L)
            sheets.add(SheetRef(name, relationshipId))
        }
    }

    private class RelationshipsHandler(private val budget: ImportMemoryBudget) : DefaultHandler() {
        val relationships = LinkedHashMap<String, String>()

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            if (localName != "Relationship" || attributes == null) return
            val id = attributes.getValue("Id") ?: return
            val target = attributes.getValue("Target") ?: return
            val normalized = if (target.startsWith("/")) {
                target.removePrefix("/")
            } else {
                "xl/${target.removePrefix("./")}"
            }
            budget.claim(64L + (id.length + normalized.length).toLong() * 2L)
            relationships[id] = normalized
        }
    }

    private class SharedStringsHandler(private val budget: ImportMemoryBudget) : DefaultHandler() {
        val values = ArrayList<BudgetedTextBuilder.RetainedText>()
        private var current: BudgetedTextBuilder? = null
        private var inText = false
        private var inPhonetic = false

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            when (localName.orEmpty()) {
                "si" -> current = BudgetedTextBuilder(budget, 32)
                "rPh" -> inPhonetic = true
                "t" -> if (!inPhonetic) inText = true
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (localName.orEmpty()) {
                "t" -> inText = false
                "rPh" -> inPhonetic = false
                "si" -> {
                    val value = current ?: BudgetedTextBuilder(budget, 1)
                    budget.claim(8)
                    values.add(value.finishRetained())
                    current = null
                }
            }
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (inText && !inPhonetic) current?.append(chars, start, length)
        }
    }

    private class WorksheetHandler(
        private val output: BudgetedTextBuilder,
        private val shared: List<BudgetedTextBuilder.RetainedText>,
        private val budget: ImportMemoryBudget,
        private val expansion: OfficeArchiveExpansionGuard
    ) : DefaultHandler() {
        private data class Cell(
            val value: String,
            val charge: ImportMemoryBudget.Charge
        )

        private var inSheetData = false
        private var row: HashMap<Int, Cell>? = null
        private var rowIndexCharge: ImportMemoryBudget.Charge? = null
        private var inCell = false
        private var cellColumn = 0
        private var nextColumn = 0
        private var cellType = ""
        private var capturing = false
        private var cellValue: BudgetedTextBuilder? = null
        var nonEmptyRows: Int = 0
            private set

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            when (localName.orEmpty()) {
                "sheetData" -> inSheetData = true
                "row" -> if (inSheetData) {
                    releaseRow()
                    rowIndexCharge = budget.claim(256)
                    row = HashMap()
                    nextColumn = 0
                }
                "c" -> {
                    if (row == null) return
                    cellColumn = attributes?.getValue("r")
                        ?.let(::columnIndex)
                        ?.takeIf { it >= 0 }
                        ?: nextColumn
                    nextColumn = maxOf(nextColumn, cellColumn + 1)
                    cellType = attributes?.getValue("t").orEmpty()
                    cellValue = BudgetedTextBuilder(budget, 32)
                    inCell = true
                }
                "v", "t" -> if (inCell) capturing = true
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (localName.orEmpty()) {
                "v", "t" -> capturing = false
                "c" -> finishCell()
                "row" -> finishRow()
                "sheetData" -> inSheetData = false
            }
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (capturing) cellValue?.append(chars, start, length)
        }

        private fun finishCell() {
            if (!inCell) return
            val raw = cellValue?.finishRetained()
            cellValue = null
            val rawText = raw?.text.orEmpty()
            val decoded = when (cellType) {
                "s" -> rawText.trim().toIntOrNull()
                    ?.let { shared.getOrNull(it)?.text }
                    .orEmpty()
                "b" -> if (rawText.trim() == "1") "TRUE" else "FALSE"
                else -> rawText
            }
            val retainedValueBytes = if (cellType == "s" || cellType == "b") {
                0L
            } else {
                decoded.length.toLong() * 2L
            }
            val charge = budget.claim(64L + retainedValueBytes)
            row?.put(cellColumn, Cell(decoded, charge))?.charge?.release()
            raw?.release()
            inCell = false
        }

        private fun finishRow() {
            finishCell()
            val values = row
            if (!values.isNullOrEmpty()) {
                var last = -1
                for ((column, cell) in values) {
                    if (cell.value.isNotEmpty() && column > last) last = column
                }
                if (last >= 0) {
                    val line = BudgetedTextBuilder(budget, 128)
                    for (column in 0..last) {
                        if (column > 0) line.append(',')
                        appendCsvCell(line, values[column]?.value.orEmpty())
                    }
                    val retained = line.finishRetained()
                    appendOutput(output, "\n", expansion)
                    appendOutput(output, retained.text, expansion)
                    retained.release()
                    nonEmptyRows++
                }
            }
            releaseRow()
        }

        private fun releaseRow() {
            row?.values?.forEach { it.charge.release() }
            row?.clear()
            row = null
            rowIndexCharge?.release()
            rowIndexCharge = null
            cellValue?.discard()
            cellValue = null
            inCell = false
            capturing = false
        }
    }

    private fun appendCsvCell(output: BudgetedTextBuilder, value: String) {
        val quoted = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!quoted) {
            output.append(value)
            return
        }
        output.append('"')
        for (char in value) {
            if (char == '"') output.append('"')
            output.append(char)
        }
        output.append('"')
    }

    private fun appendOutput(
        output: BudgetedTextBuilder,
        value: CharSequence,
        expansion: OfficeArchiveExpansionGuard
    ) {
        output.append(value)
        expansion.accountNormalized(utf8Bytes(value))
    }

    private fun trimTrailingWhitespace(output: BudgetedTextBuilder) {
        while (output.length > 0 && output.charAt(output.length - 1).isWhitespace()) {
            output.setLength(output.length - 1)
        }
    }

    private fun utf8Bytes(value: CharSequence): Int {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            bytes += when {
                char.code <= 0x7F -> 1
                char.code <= 0x7FF -> 2
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            if (bytes > Int.MAX_VALUE) return Int.MAX_VALUE
            index++
        }
        return bytes.toInt()
    }

    private fun utf8Bytes(chars: CharArray, start: Int, length: Int): Int =
        utf8Bytes(CharArraySlice(chars, start, length))

    private class CharArraySlice(
        private val chars: CharArray,
        private val start: Int,
        override val length: Int
    ) : CharSequence {
        override fun get(index: Int): Char = chars[start + index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            CharArraySlice(chars, start + startIndex, endIndex - startIndex)
    }

    private fun columnIndex(reference: String): Int {
        var result = 0
        var seen = 0
        for (char in reference) {
            val upper = char.uppercaseChar()
            if (upper !in 'A'..'Z') break
            result = result * 26 + (upper - 'A' + 1)
            seen++
        }
        return if (seen == 0) -1 else result - 1
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray, available: Int): Boolean {
        if (available < prefix.size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    private fun ByteArray.containsUtf16Le(text: String, available: Int): Boolean {
        val needle = text.toByteArray(Charsets.UTF_16LE)
        if (needle.size > available) return false
        for (start in 0..(available - needle.size)) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
