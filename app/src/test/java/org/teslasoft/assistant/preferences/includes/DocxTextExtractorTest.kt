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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxTextExtractorTest {

    private fun paragraph(vararg runs: String) =
        "<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>" +
                runs.joinToString("") { "<w:r><w:rPr><w:b/></w:rPr><w:t>$it</w:t></w:r>" } +
                "</w:p>"

    private fun document(body: String) =
        "<?xml version=\"1.0\"?><w:document xmlns:w=\"x\"><w:body>$body</w:body></w:document>"

    private fun docxBytes(xml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    // ---- xml walk ---------------------------------------------------------

    @Test fun plainParagraphsBecomeLines() {
        val text = DocxTextExtractor.xmlToText(
            document(paragraph("First line.") + paragraph("Second line."))
        )
        assertEquals("First line.\nSecond line.", text)
    }

    @Test fun runsInsideOneParagraphJoinWithoutABreak() {
        // Word splits a sentence into runs at every formatting change; those
        // must not become separate lines.
        val text = DocxTextExtractor.xmlToText(
            document(paragraph("Hello ", "beautiful ", "world."))
        )
        assertEquals("Hello beautiful world.", text)
    }

    @Test fun formattingMarkupNeverLeaksIntoTheText() {
        val text = DocxTextExtractor.xmlToText(document(paragraph("Clean.")))
        assertFalse(text.contains("w:"))
        assertFalse(text.contains("<"))
        assertFalse(text.contains("rPr"))
    }

    @Test fun lineBreaksAndTabsAreHonoured() {
        val xml = document("<w:p><w:r><w:t>A</w:t><w:br/><w:t>B</w:t><w:tab/><w:t>C</w:t></w:r></w:p>")
        assertEquals("A\nB\tC", DocxTextExtractor.xmlToText(xml))
    }

    @Test fun xmlEntitiesAreDecoded() {
        val xml = document(paragraph("Tom &amp; Jerry &lt;tag&gt; &quot;quoted&quot;"))
        assertEquals("Tom & Jerry <tag> \"quoted\"", DocxTextExtractor.xmlToText(xml))
    }

    @Test fun runsOfBlankParagraphsCollapse() {
        val xml = document(
            paragraph("Top.") + "<w:p></w:p><w:p></w:p><w:p></w:p>" + paragraph("Bottom.")
        )
        assertEquals("Top.\n\nBottom.", DocxTextExtractor.xmlToText(xml))
    }

    @Test fun tableMarkupDoesNotLeakAsText() {
        // w:tbl / w:tr / w:tc all begin with "w:t"; a prefix match on the tag
        // name would spill their structural markup into the prompt.
        val xml = document(
            "<w:tbl><w:tblPr><w:tblW w:w=\"5000\"/></w:tblPr>" +
                    "<w:tr><w:tc>" + paragraph("Cell one.") + "</w:tc>" +
                    "<w:tc>" + paragraph("Cell two.") + "</w:tc></w:tr></w:tbl>"
        )
        val text = DocxTextExtractor.xmlToText(xml)
        assertEquals("Cell one.\nCell two.", text)
        assertFalse(text.contains("5000"))
        assertFalse(text.contains("w:"))
    }

    @Test fun textTagsCarryingAttributesStillReadAsText() {
        val xml = document(
            "<w:p><w:r><w:t xml:space=\"preserve\">Spaced text </w:t></w:r>" +
                    "<w:r><w:t>continues.</w:t></w:r></w:p>"
        )
        assertEquals("Spaced text continues.", DocxTextExtractor.xmlToText(xml))
    }

    @Test fun anEmptyBodyYieldsEmptyText() {
        assertEquals("", DocxTextExtractor.xmlToText(document("")))
    }

    @Test fun truncatedXmlDoesNotThrow() {
        // A damaged file must degrade, not crash the chat screen.
        DocxTextExtractor.xmlToText("<w:document><w:p><w:r><w:t>half a ta")
    }

    // ---- zip container ----------------------------------------------------

    @Test fun readsTextOutOfARealDocxContainer() {
        val bytes = docxBytes(document(paragraph("Contract terms.") + paragraph("Signed.")))
        val result = DocxTextExtractor.extract(bytes) as DocxTextExtractor.ExtractResult.Success
        assertEquals("Contract terms.\nSigned.", result.text)
    }

    @Test fun aZipWithoutADocumentPartIsNotADocx() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("something/else.xml"))
            zip.write("<x/>".toByteArray())
            zip.closeEntry()
        }
        assertEquals(
            DocxTextExtractor.ExtractResult.NotDocx,
            DocxTextExtractor.extract(out.toByteArray())
        )
    }

    @Test fun extractedDocumentTextPassesTheBinaryGuard() {
        val bytes = docxBytes(document(paragraph("Ordinary prose in a document.")))
        val result = DocxTextExtractor.extract(bytes) as DocxTextExtractor.ExtractResult.Success
        assertTrue(IncludeTextPolicy.looksLikeText(result.text))
    }

    @Test fun completelyNonZipBytesAreNotADocx() {
        val garbage = "not a zip file at all, just plain bytes".toByteArray()
        assertEquals(DocxTextExtractor.ExtractResult.NotDocx, DocxTextExtractor.extract(garbage))
    }

    @Test fun emptyBytesAreNotADocx() {
        assertEquals(DocxTextExtractor.ExtractResult.NotDocx, DocxTextExtractor.extract(ByteArray(0)))
    }

    // ---- password protection (row 6) ---------------------------------------

    @Test fun anOle2CfbContainerIsReportedAsPasswordProtected() {
        // The magic bytes a password-protected Office file is wrapped in;
        // the rest of the bytes are irrelevant to the signature check.
        val cfbSignature = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
        )
        val bytes = cfbSignature + ByteArray(100) { 0 }
        assertEquals(
            DocxTextExtractor.ExtractResult.PasswordProtected,
            DocxTextExtractor.extract(bytes)
        )
    }

    @Test fun bytesShorterThanTheSignatureAreNotMisreadAsProtected() {
        val tooShort = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte())
        assertEquals(DocxTextExtractor.ExtractResult.NotDocx, DocxTextExtractor.extract(tooShort))
    }

    // ---- corruption (row 9) -------------------------------------------------

    @Test fun aTruncatedDocumentPartIsReportedAsCorrupted() {
        // word/document.xml is genuinely located (proof this was a real
        // docx), but its compressed data is cut off mid-stream, so reading it
        // must fail — distinct from NotDocx, which has no such proof.
        val bytes = docxBytes(document(paragraph("A".repeat(5000))))
        val truncated = bytes.copyOfRange(0, bytes.size - 20)
        assertEquals(
            DocxTextExtractor.ExtractResult.Corrupted,
            DocxTextExtractor.extract(truncated)
        )
    }
}
