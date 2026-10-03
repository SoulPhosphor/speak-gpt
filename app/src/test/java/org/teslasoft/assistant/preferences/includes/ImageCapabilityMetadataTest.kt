/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCapabilityMetadataTest {
    private fun entry(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun openRouterUsesInputModalities() {
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityMetadata.fromModelEntry(
                entry("""{"architecture":{"input_modalities":["text","image"]}}"""),
                ImageCapabilityProvider.OPENROUTER
            )
        )
        assertEquals(
            ImageCapability.UNSUPPORTED,
            ImageCapabilityMetadata.fromModelEntry(
                entry("""{"architecture":{"input_modalities":["text"]}}"""),
                ImageCapabilityProvider.OPENROUTER
            )
        )
    }

    @Test fun nanoGptUsesVisionCapabilityWithoutModelNameGuessing() {
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityMetadata.fromModelEntry(
                entry("""{"id":"opaque-a","capabilities":{"vision":true}}"""),
                ImageCapabilityProvider.NANOGPT
            )
        )
        assertEquals(
            ImageCapability.UNSUPPORTED,
            ImageCapabilityMetadata.fromModelEntry(
                entry("""{"id":"opaque-b","capabilities":{"vision":false}}"""),
                ImageCapabilityProvider.NANOGPT
            )
        )
    }

    @Test fun veniceUsesSupportsVision() {
        val supported = entry(
            """{"model_spec":{"capabilities":{"supportsVision":true}}}"""
        )
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityMetadata.fromModelEntry(supported, ImageCapabilityProvider.VENICE)
        )
    }

    @Test fun featherlessUsesPublishedDetailFields() {
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityMetadata.fromModelEntry(
                entry("""{"vision_supported":true,"input_modalities":["text","image"]}"""),
                ImageCapabilityProvider.FEATHERLESS
            )
        )
        assertEquals(
            ImageCapability.UNSUPPORTED,
            ImageCapabilityMetadata.fromModelEntry(
                entry("""{"vision_supported":false,"input_modalities":["text"]}"""),
                ImageCapabilityProvider.FEATHERLESS
            )
        )
    }

    @Test fun absentMetadataStaysUnknownForGenericAndGlm() {
        val opaque = entry("""{"id":"glm-something"}""")
        assertEquals(
            ImageCapability.UNKNOWN,
            ImageCapabilityMetadata.fromModelEntry(opaque, ImageCapabilityProvider.ZAI_GLM)
        )
        assertEquals(
            ImageCapability.UNKNOWN,
            ImageCapabilityMetadata.fromModelEntry(opaque, ImageCapabilityProvider.GENERIC)
        )
    }

    @Test fun responseRefreshCanReplaceAChangedCapability() {
        val first = ImageCapabilityMetadata.capabilitiesFromResponse(
            """{"data":[{"id":"m","architecture":{"input_modalities":["text"]}}]}""",
            ImageCapabilityProvider.OPENROUTER
        )!!
        val second = ImageCapabilityMetadata.capabilitiesFromResponse(
            """{"data":[{"id":"m","architecture":{"input_modalities":["text","image"]}}]}""",
            ImageCapabilityProvider.OPENROUTER
        )!!
        var stored = ImageCapabilityStore.refreshMetadata("", first)
        assertEquals(ImageCapability.UNSUPPORTED, ImageCapabilityStore.getMetadata(stored, "m"))
        stored = ImageCapabilityStore.refreshMetadata(stored, second)
        assertEquals(ImageCapability.SUPPORTED, ImageCapabilityStore.getMetadata(stored, "m"))
    }
}
