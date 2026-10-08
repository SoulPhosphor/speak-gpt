package org.teslasoft.assistant.preferences.includes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfRequestSerializerTest {
    @Test
    fun `inline size limit routes oversized PDFs to local fallback`() {
        val limit = 20L * 1024L * 1024L
        assertTrue(PdfRequestSerializer.canInline(limit, limit))
        assertFalse(PdfRequestSerializer.canInline(limit + 1L, limit))
    }

    private val payload = NativePdfPayload("inc-1", "report.pdf", "QUJD")
    private val marker = """<attachment-reference>{\"id\":\"inc-1\",\"type\":\"document\",\"kind\":\"pdf\",\"name\":\"report.pdf\"}</attachment-reference>"""
    private val body = """{"model":"m","messages":[""" +
        """{"role":"user","content":"Earlier text mentioning $marker"},""" +
        """{"role":"user","content":[{"type":"text","text":"Read this.\n\nUploaded PDF (report.pdf):"},""" +
        """{"type":"text","text":"$marker"},{"type":"text","text":"Thanks"}]}]}"""

    @Test fun `OpenAI file part replaces the marker slot right after its label`() {
        val root = JSONObject(PdfRequestSerializer.augmentOpenAiChatBody(body, listOf(payload), false))
        val messages = root.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).get("content") is String)
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals(3, parts.length())
        assertEquals("Read this.\n\nUploaded PDF (report.pdf):", parts.getJSONObject(0).getString("text"))
        val file = parts.getJSONObject(1).getJSONObject("file")
        assertEquals("report.pdf", file.getString("filename"))
        assertEquals("data:application/pdf;base64,QUJD", file.getString("file_data"))
        assertEquals("Thanks", parts.getJSONObject(2).getString("text"))
        assertFalse(root.has("plugins"))
    }

    @Test(expected = IllegalStateException::class)
    fun `missing slot fails instead of sending without the PDF`() {
        PdfRequestSerializer.augmentOpenAiChatBody(
            """{"model":"m","messages":[{"role":"user","content":"no slot"}]}""",
            listOf(payload),
            false
        )
    }

    @Test fun `OpenRouter forces native parser`() {
        val root = JSONObject(PdfRequestSerializer.augmentOpenAiChatBody(body, listOf(payload), true))
        assertEquals("native", root.getJSONArray("plugins").getJSONObject(0)
            .getJSONObject("pdf").getString("engine"))
    }

    @Test fun `provider specific document shapes stay distinct`() {
        assertEquals("document", PdfRequestSerializer.anthropicDocumentBlock(payload).getString("type"))
        assertEquals("base64", PdfRequestSerializer.anthropicDocumentBlock(payload)
            .getJSONObject("source").getString("type"))
        val gemini = PdfRequestSerializer.geminiDocumentPart(payload).getJSONObject("inline_data")
        assertEquals("application/pdf", gemini.getString("mime_type"))
        assertEquals("QUJD", gemini.getString("data"))
        assertEquals("input_file", PdfRequestSerializer.xAiInputFile("file-1").getString("type"))
    }
}
