package org.teslasoft.assistant.preferences.includes

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class PdfDeliveryPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    private val limit = PdfDeliveryPolicy.MAX_INLINE_BYTES

    private fun endpoint(host: String) = ApiEndpointObject("test", host, "key")

    /** End to end: what the resolver concludes, then what the policy sends. */
    private fun native(host: String, model: String, bytes: Long = 1_000L): Boolean {
        val ep = endpoint(host)
        val provider = PdfCapabilityProvider.forEndpoint(ep)
        return PdfDeliveryPolicy.useNative(
            provider, PdfCapabilityResolver.resolve(ep, model), bytes
        )
    }

    @Test fun `confirmed OpenAI model sends the original PDF`() {
        assertTrue(native("https://api.openai.com/v1", "gpt-5"))
    }

    @Test fun `oversized PDF falls back to local text even on a native route`() {
        assertFalse(native("https://api.openai.com/v1", "gpt-5", bytes = limit + 1))
    }

    @Test fun `unknown and unsupported capability always use local text`() {
        assertFalse(PdfDeliveryPolicy.useNative(PdfCapabilityProvider.OPENAI, PdfCapability.UNKNOWN, 1))
        assertFalse(PdfDeliveryPolicy.useNative(PdfCapabilityProvider.OPENROUTER, PdfCapability.UNKNOWN, 1))
        assertFalse(PdfDeliveryPolicy.useNative(PdfCapabilityProvider.OPENAI, PdfCapability.UNSUPPORTED, 1))
    }

    @Test fun `confirmed OpenRouter route sends the original PDF`() {
        assertTrue(PdfDeliveryPolicy.useNative(PdfCapabilityProvider.OPENROUTER, PdfCapability.SUPPORTED, 1))
    }

    @Test fun `NanoGPT uses local text even with native PDF evidence until its transport is verified`() {
        assertFalse(native("https://nano-gpt.com/api/v1", "vision-model"))
        assertFalse(PdfDeliveryPolicy.useNative(PdfCapabilityProvider.NANOGPT, PdfCapability.SUPPORTED, 1))
    }

    @Test fun `Featherless and generic endpoints use local text`() {
        assertFalse(native("https://api.featherless.ai/v1", "vision-model"))
        assertFalse(native("https://custom.example/v1", "vision-model"))
        assertFalse(PdfDeliveryPolicy.useNative(PdfCapabilityProvider.GENERIC, PdfCapability.SUPPORTED, 1))
    }

    @Test fun `OpenAI-compatible Anthropic and Gemini endpoints never drop a PDF into an unsupported part`() {
        // The model can read PDFs, but these endpoints ignore or reject the
        // file part, so the PDF must arrive as local text instead.
        assertFalse(native("https://api.anthropic.com/v1", "claude-sonnet-4"))
        assertFalse(native("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-pro"))
    }

    @Test fun `confirmed xAI file-capable model uploads the original PDF up to 50 MB`() {
        assertTrue(native("https://api.x.ai/v1", "grok-4.7"))
        assertTrue(PdfDeliveryPolicy.usesUploadedFile(PdfCapabilityProvider.XAI))
        assertTrue(native("https://api.x.ai/v1", "grok-4.7", bytes = XaiPdfFiles.MAX_BYTES))
        assertFalse(native("https://api.x.ai/v1", "grok-4.7", bytes = XaiPdfFiles.MAX_BYTES + 1))
        assertTrue(native("https://api.x.ai/v1", "grok-4.20"))
        // A Grok model without confirmed file input stays on local text.
        assertFalse(native("https://api.x.ai/v1", "grok-3"))
        assertFalse(native("https://api.x.ai/v1", "grok-4.5"))
        assertFalse(native("https://api.x.ai/v1", "grok-4.20-non-reasoning"))
    }

    @Test fun `PDF header may follow leading bytes within the first kilobyte`() {
        val header = "%PDF-1.7".toByteArray()
        assertTrue(PdfImporter.hasPdfHeader(header))
        val padded = ByteArray(600) { ' '.code.toByte() } + header
        assertTrue(PdfImporter.hasPdfHeader(padded))
        val late = ByteArray(PdfImporter.HEADER_SEARCH_BYTES) { 0 } + header
        assertFalse(PdfImporter.hasPdfHeader(late))
        assertFalse(PdfImporter.hasPdfHeader("%PD".toByteArray()))
        assertFalse(PdfImporter.hasPdfHeader("not a pdf at all".toByteArray()))
    }

    @Test fun `abandoned import working copies are cleaned but a running import is kept`() {
        val dir = temp.newFolder("chat")
        val abandoned = File(dir, "pdf-import-123.tmp").apply { writeText("partial") }
        val kept = File(dir, "abc.pdf").apply { writeText("%PDF-") }
        val running = PdfAttachmentStore.createImportTemp(dir)
        try {
            PdfAttachmentStore.deleteAbandonedImportTemps(dir)
            assertFalse(abandoned.exists())
            assertTrue(running.exists())
            assertTrue(kept.exists())
        } finally {
            PdfAttachmentStore.releaseImportTemp(running)
        }
        PdfAttachmentStore.deleteAbandonedImportTemps(dir)
        assertFalse(running.exists())
    }
}
