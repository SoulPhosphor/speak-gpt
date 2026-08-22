/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningProviderPathTest {

    @Test
    fun officialHostsSelectOnlyTheirOwnAdapters() {
        assertEquals(
            ReasoningProviderPath.OPENAI,
            ReasoningProviderPath.forEndpoint("https://api.openai.com/v1", false)
        )
        assertEquals(
            ReasoningProviderPath.GEMINI_OPENAI_COMPATIBLE,
            ReasoningProviderPath.forEndpoint(
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                false
            )
        )
        assertEquals(
            ReasoningProviderPath.ANTHROPIC_OPENAI_COMPATIBLE,
            ReasoningProviderPath.forEndpoint("https://api.anthropic.com/v1/", false)
        )
    }

    @Test
    fun customCompatibleEndpointNeverInheritsAnOfficialAdapterByNameOrPath() {
        assertEquals(
            ReasoningProviderPath.GENERIC_OPENAI_COMPATIBLE,
            ReasoningProviderPath.forEndpoint("https://proxy.example/openai/v1", false)
        )
        assertEquals(
            ReasoningCapability.UNKNOWN,
            ReasoningCapabilityResolver.resolve(
                modelId = "openai/o3-mini",
                providerPath = ReasoningProviderPath.forEndpoint(
                    "https://proxy.example/openai/v1",
                    false
                )
            )
        )
    }

    @Test
    fun explicitOpenRouterIdentityWinsOverHostGuessing() {
        assertEquals(
            ReasoningProviderPath.OPENROUTER,
            ReasoningProviderPath.forEndpoint("https://custom.example/v1", true)
        )
    }
}
