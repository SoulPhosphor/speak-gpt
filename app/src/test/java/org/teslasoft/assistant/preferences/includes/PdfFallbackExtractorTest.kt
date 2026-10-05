package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.*
import org.junit.Test

class PdfFallbackExtractorTest {
    @Test fun `page blocks preserve deterministic order and boundaries`() {
        val result = listOf("embedded", "ocr", "last")
            .mapIndexed { index, text -> PdfFallbackExtractor.pageBlock(index + 1, text) }
            .joinToString("\n\n")
        assertTrue(result.indexOf("Page 1") < result.indexOf("Page 2"))
        assertTrue(result.indexOf("Page 2") < result.indexOf("Page 3"))
        assertEquals("--- Page 2 ---\nocr", result.substringAfter("\n\n").substringBefore("\n\n"))
    }

    @Test fun `meaningful embedded text avoids OCR threshold`() {
        assertFalse(PdfFallbackExtractor.isMeaningful("page 1"))
        assertTrue(PdfFallbackExtractor.isMeaningful("Meaningful embedded text 123"))
    }

    @Test fun `render scale downsizes oversized pages and caps small pages`() {
        assertEquals(0.5f, PdfFallbackExtractor.renderScale(4400, 2200), 0.0001f)
        assertEquals(3f, PdfFallbackExtractor.renderScale(100, 200), 0.0001f)
        assertEquals(1f, PdfFallbackExtractor.renderScale(2200, 1100), 0.0001f)
    }
}
