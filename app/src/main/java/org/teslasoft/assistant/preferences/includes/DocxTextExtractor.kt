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

import java.util.zip.ZipInputStream

/**
 * Reads the words out of a .docx file.
 *
 * A .docx is a zip archive whose `word/document.xml` holds the body text, so
 * no third-party document library is needed — the platform's own zip and a
 * small tag walk are enough. That matters: the alternative (a PDF/Office
 * library) would add megabytes to an app deliberately kept lean.
 *
 * WORDS ONLY. Formatting, styles, images, comments and tracked changes are
 * not carried across, and nothing here ever writes a .docx back — the file on
 * the user's device is only ever read. Legacy binary .doc is a completely
 * different format and is deliberately unsupported.
 *
 * The XML walk is deliberately hand-rolled rather than a DOM/SAX parse: the
 * only constructs that matter are `<w:t>` runs (text), `<w:p>` (paragraph
 * break), `<w:br>`/`<w:tab>` (whitespace), and a DOM parse of a large
 * document would hold the whole tree in memory for no gain.
 */
object DocxTextExtractor {

    private const val DOCUMENT_ENTRY = "word/document.xml"

    /** Guards against a zip bomb: stop reading a single entry past this. */
    private const val MAX_XML_CHARS = 40_000_000

    /**
     * Magic bytes at the start of an OLE2 Compound File Binary container —
     * the format a password-protected Office file is wrapped in (Office
     * cannot encrypt a plain zip entry-by-entry, so a protected .docx is not
     * a zip at all at the top level; it is a CFB container holding an
     * encrypted package stream). The same signature also opens a legacy
     * binary .doc/.xls/.ppt — this app only routes files with a `.docx`
     * extension here, and a legacy `.doc` renamed to `.docx` would also read
     * as this signature, which is a known limitation of a signature-only
     * check rather than something this detection tries to resolve further.
     */
    private val CFB_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    /** Outcome of attempting to read a .docx. */
    sealed class ExtractResult {
        data class Success(val text: String) : ExtractResult()

        /** Not a zip at all, or a zip with no `word/document.xml` entry —
         *  no positive evidence this was ever a genuine Word document. */
        data object NotDocx : ExtractResult()

        /** An OLE2/CFB container — a password-protected Office file. */
        data object PasswordProtected : ExtractResult()

        /** `word/document.xml` was located, but reading or decompressing it
         *  failed — positive evidence of a genuine, damaged Word document. */
        data object Corrupted : ExtractResult()
    }

    /**
     * Marks a failure that happened AFTER `word/document.xml` was located,
     * so the caller can tell "never found a real docx" (NotDocx) apart from
     * "found one, but it's damaged" (Corrupted) — both surface as an
     * exception from the zip machinery otherwise.
     */
    private class DocumentEntryUnreadable(cause: Throwable) : Exception(cause)

    fun extract(bytes: ByteArray): ExtractResult {
        if (isCfbContainer(bytes)) return ExtractResult.PasswordProtected

        val xml = try {
            locateDocumentXml(bytes) ?: return ExtractResult.NotDocx
        } catch (_: DocumentEntryUnreadable) {
            return ExtractResult.Corrupted
        } catch (_: Exception) {
            return ExtractResult.NotDocx
        }

        return ExtractResult.Success(xmlToText(xml))
    }

    private fun isCfbContainer(bytes: ByteArray): Boolean {
        if (bytes.size < CFB_SIGNATURE.size) return false
        for (i in CFB_SIGNATURE.indices) {
            if (bytes[i] != CFB_SIGNATURE[i]) return false
        }
        return true
    }

    private fun locateDocumentXml(bytes: ByteArray): String? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == DOCUMENT_ENTRY) {
                    return try {
                        readEntryText(zip)
                    } catch (e: Exception) {
                        throw DocumentEntryUnreadable(e)
                    }
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun readEntryText(zip: ZipInputStream): String {
        val out = StringBuilder()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = zip.read(buffer)
            if (read <= 0) break
            out.append(String(buffer, 0, read, Charsets.UTF_8))
            if (out.length > MAX_XML_CHARS) break
        }
        return out.toString()
    }

    /**
     * Flattens WordprocessingML into plain text. Visible for testing so the
     * tag handling can be exercised without building a zip.
     */
    fun xmlToText(xml: String): String {
        val out = StringBuilder()
        var i = 0
        // Text inside <w:t> is content; everything else between tags is
        // formatting noise and must not leak into the output.
        var inText = false

        while (i < xml.length) {
            val c = xml[i]
            if (c == '<') {
                val close = xml.indexOf('>', i)
                if (close < 0) break
                when (tagName(xml.substring(i + 1, close))) {
                    "w:t" -> inText = true
                    "/w:t" -> inText = false
                    "/w:p" -> out.append('\n')
                    "w:br", "w:cr" -> out.append('\n')
                    "w:tab" -> out.append('\t')
                }
                i = close + 1
            } else {
                if (inText) out.append(c)
                i++
            }
        }

        return unescape(out.toString()).lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * The element name inside a tag body, with attributes and any
     * self-closing slash stripped: `w:t xml:space="preserve"` -> `w:t`,
     * `w:br/` -> `w:br`, `/w:p` -> `/w:p`.
     *
     * Exact names are load-bearing here. Prefix matching looks tempting but
     * is wrong: `w:tc` (table cell), `w:tr` (table row) and `w:tbl` (table)
     * all begin with `w:t`, so a prefix test would treat a table's structural
     * markup as body text and spill raw XML into the prompt for any document
     * containing a table.
     *
     * The slash is positional, and getting that wrong silently breaks
     * extraction: a CLOSING tag opens with `/`, which is part of its name,
     * while a SELF-CLOSING tag ends with one, which is not. Scanning for the
     * first slash from index 0 therefore returned "" for every closing tag,
     * so `/w:t` never cleared the in-text flag and `/w:p` never emitted a
     * paragraph break — whole documents came out as one run-on line with
     * formatting text able to leak in. Start the terminator scan past a
     * leading slash.
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

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
