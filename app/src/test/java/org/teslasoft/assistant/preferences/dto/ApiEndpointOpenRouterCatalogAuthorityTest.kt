/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.dto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointOpenRouterCatalogAuthorityTest {

    @Test
    fun standardOpenRouterHostIsCatalogAuthorityDespiteStaleGenericIdentity() {
        val endpoint = ApiEndpointObject(
            label = "Open Router",
            host = "https://openrouter.ai/api/v1/",
            apiKey = "key",
            identity = ApiEndpointObject.IDENTITY_GENERIC
        )

        assertFalse(endpoint.isOpenRouterRouting())
        assertTrue(endpoint.hasOpenRouterCatalogAuthority())
    }

    @Test
    fun stickyOpenRouterIdentityKeepsAuthorityBehindCustomGateway() {
        val endpoint = ApiEndpointObject(
            label = "Open Router Gateway",
            host = "https://gateway.example/v1/",
            apiKey = "key",
            identity = ApiEndpointObject.IDENTITY_OPENROUTER
        )

        assertTrue(endpoint.hasOpenRouterCatalogAuthority())
    }

    @Test
    fun genericCompatibleEndpointDoesNotGainOpenRouterCatalogAuthority() {
        val endpoint = ApiEndpointObject(
            label = "Generic",
            host = "https://compatible.example/v1/",
            apiKey = "key",
            identity = ApiEndpointObject.IDENTITY_GENERIC
        )

        assertFalse(endpoint.hasOpenRouterCatalogAuthority())
    }
}
