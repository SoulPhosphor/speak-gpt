package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.*
import org.junit.Test

class PdfIncludeTest {
    @Test fun `pdf JSON round trip preserves storage and fallback metadata`() {
        val original = ChatInclude(
            id = "inc-pdf", fileName = "paper.pdf", kind = IncludeKind.PDF,
            form = IncludeForm.FULL, fullText = "", pdfFileHash = "abc123",
            pdfMimeType = "application/pdf", pdfByteSize = 4096, pdfPageCount = 3,
            pdfFallbackText = "--- Page 1 ---\nHello",
            pdfFallbackProvenance = PdfFallbackProvenance.MIXED
        )
        assertEquals(original, ChatInclude.fromJson(original.toJson()))
    }

    @Test fun `old include JSON remains readable`() {
        val restored = ChatInclude.listFromJson(
            """[{"id":"old","name":"notes.txt","kind":"txt","form":"full","full":"hello"}]"""
        ).single()
        assertEquals(IncludeKind.TXT, restored.kind)
        assertNull(restored.pdfFileHash)
    }

    @Test fun `pdf is a document and full bytes do not leak into inline text`() {
        val pdf = ChatInclude(
            id = "p", fileName = "a.pdf", kind = IncludeKind.PDF,
            form = IncludeForm.FULL, fullText = "", pdfFileHash = "hash",
            pdfMimeType = "application/pdf"
        )
        assertFalse(pdf.kind.isImage())
        assertEquals("", pdf.modelText())
        assertTrue(StableAttachmentReference.serialize(pdf).contains("\"type\":\"document\""))
        assertEquals("pdf", IncludeKind.fromFileName("SCAN.PDF")?.key)
    }

    @Test fun `completed and artifact forms discard original PDF reference`() {
        val full = ChatInclude(
            "p", "a.pdf", IncludeKind.PDF, IncludeForm.FULL, "",
            pdfFileHash = "hash", pdfMimeType = "application/pdf", pdfByteSize = 10,
            pdfPageCount = 1, pdfFallbackText = "text",
            pdfFallbackProvenance = PdfFallbackProvenance.OCR
        )
        assertFalse(full.withoutPdfBytes().hasLivePdfBytes())
        assertNull(full.withoutPdfBytes().pdfFallbackText)
    }
}
