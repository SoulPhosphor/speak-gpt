package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfCapabilityMetadataTest {
    @Test fun `OpenRouter file modality is PDF evidence independent of vision`() {
        val body = """{"data":[
            {"id":"file-model","architecture":{"input_modalities":["text","file"]}},
            {"id":"vision-only","architecture":{"input_modalities":["text","image"]}}
        ]}"""
        val result = PdfCapabilityMetadata.capabilitiesFromResponse(
            body, PdfCapabilityProvider.OPENROUTER
        )!!
        assertEquals(PdfCapability.SUPPORTED, result["file-model"])
        assertEquals(PdfCapability.UNSUPPORTED, result["vision-only"])
    }

    @Test fun `NanoGPT requires explicit native PDF evidence`() {
        val body = """{"data":[
            {"id":"native","capabilities":["Vision","Native PDF"]},
            {"id":"vision","capabilities":["Vision"]}
        ]}"""
        val result = PdfCapabilityMetadata.capabilitiesFromResponse(body, PdfCapabilityProvider.NANOGPT)!!
        assertEquals(PdfCapability.SUPPORTED, result["native"])
        assertEquals(PdfCapability.UNSUPPORTED, result["vision"])
    }
}
