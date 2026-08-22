/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 **************************************************************************/

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointCapabilityCachePolicyTest {

    @Test
    fun cosmeticSlashAndBlankDefaultDifferencesPreserveCache() {
        assertFalse(
            EndpointCapabilityCachePolicy.effectivePathChanged(
                "https://api.example.test/v1/",
                "/chat/completions",
                "https://api.example.test/v1",
                "chat/completions/"
            )
        )
        assertFalse(
            EndpointCapabilityCachePolicy.effectivePathChanged(
                "https://api.example.test/v1",
                "",
                "https://api.example.test/v1",
                "/chat/completions"
            )
        )
    }

    @Test
    fun hostOrChatPathChangeInvalidatesCache() {
        assertTrue(
            EndpointCapabilityCachePolicy.effectivePathChanged(
                "https://one.example/v1",
                "/chat/completions",
                "https://two.example/v1",
                "/chat/completions"
            )
        )
        assertTrue(
            EndpointCapabilityCachePolicy.effectivePathChanged(
                "https://api.example/v1",
                "/chat/completions",
                "https://api.example/v1",
                "/responses"
            )
        )
    }
}
