package org.teslasoft.assistant.preferences.includes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfRequestSerializerTest {
    private val payload = NativePdfPayload("inc-1", "report.pdf", "QUJD")
    private val body = """{"model":"m","messages":[{"role":"user","content":"<attachment-reference>{\"id\":\"inc-1\",\"type\":\"document\",\"kind\":\"pdf\",\"name\":\"report.pdf\"}</attachment-reference>"}]}"""

    @Test fun `OpenAI file part uses inline PDF data`() {
        val root = JSONObject(PdfRequestSerializer.augmentOpenAiChatBody(body, listOf(payload), false))
        val file = root.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(1).getJSONObject("file")
        assertEquals("report.pdf", file.getString("filename"))
        assertEquals("data:application/pdf;base64,QUJD", file.getString("file_data"))
        assertFalse(root.has("plugins"))
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
        assertEquals("application/pdf", PdfRequestSerializer.geminiDocumentPart(payload).getString("mime_type"))
        assertEquals("input_file", PdfRequestSerializer.xAiInputFile("file-1").getString("type"))
    }
}
