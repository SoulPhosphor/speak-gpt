/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

class ImageCapabilityResolverTest {
    private fun endpoint(
        host: String = "https://direct.example/v1/",
        identity: String = ApiEndpointObject.IDENTITY_GENERIC,
        stored: String = ""
    ) = ApiEndpointObject(
        label = "test",
        host = host,
        apiKey = "key",
        id = "endpoint-1",
        identity = identity,
        imageCapabilityByModel = stored
    )

    @Test fun knownVisionCapableDirectModelSendsWithoutWarning() {
        val endpoint = endpoint()
        endpoint.imageCapabilityByModel = ImageCapabilityStore.setMetadata(
            endpoint.imageCapabilityByModel,
            "vision-model",
            ImageCapability.SUPPORTED
        )
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityResolver.getImageCapability(endpoint, "vision-model")
        )
    }

    @Test fun knownTextOnlyDirectModelIsBlocked() {
        val endpoint = endpoint()
        endpoint.imageCapabilityByModel = ImageCapabilityStore.setMetadata(
            endpoint.imageCapabilityByModel,
            "text-model",
            ImageCapability.UNSUPPORTED
        )
        val decision = ImageCapabilityResolver.resolve(endpoint, "text-model")
        assertEquals(ImageCapability.UNSUPPORTED, decision.capability)
        assertEquals(ImageCapabilityBlocker.MODEL, decision.blocker)
    }

    @Test fun unknownDirectModelKeepsUncertaintyWarning() {
        assertEquals(
            ImageCapability.UNKNOWN,
            ImageCapabilityResolver.getImageCapability(endpoint(), "unknown-model")
        )
    }

    @Test fun aggregatorVisionModelWithAutomaticOrFallbackRoutingSends() {
        val endpoint = endpoint(
            host = "https://openrouter.ai/api/v1/",
            identity = ApiEndpointObject.IDENTITY_OPENROUTER
        )
        endpoint.imageCapabilityByModel = ImageCapabilityStore.setMetadata(
            "", "author/vision", ImageCapability.SUPPORTED
        )
        val automatic = ImageRoutingConfig.from(endpoint, null)
        val preferred = ImageRoutingConfig.from(
            endpoint,
            FavoriteModelObject(
                "author/vision",
                endpoint.id,
                routingType = FavoriteModelObject.ROUTING_PREFERRED,
                allowFallbacks = true,
                providerOrder = listOf("alpha")
            )
        )
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityResolver.getImageCapability(endpoint, "author/vision", automatic)
        )
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityResolver.getImageCapability(endpoint, "author/vision", preferred)
        )
    }

    @Test fun pinnedCompatibleProviderSendsAndKnownIncompatibleUsesProviderPopup() {
        val endpoint = endpoint(
            host = "https://openrouter.ai/api/v1/",
            identity = ApiEndpointObject.IDENTITY_OPENROUTER
        )
        endpoint.imageCapabilityByModel = ImageCapabilityStore.setMetadata(
            "", "author/vision", ImageCapability.SUPPORTED
        )
        val route = ImageRoutingConfig.from(
            endpoint,
            FavoriteModelObject(
                "author/vision",
                endpoint.id,
                routingType = FavoriteModelObject.ROUTING_ONLY,
                selectedProvider = "alpha"
            )
        )
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityResolver.resolve(
                endpoint,
                "author/vision",
                route,
                livePinnedProviderCapability = ImageCapability.SUPPORTED
            ).capability
        )
        val blocked = ImageCapabilityResolver.resolve(
            endpoint,
            "author/vision",
            route,
            livePinnedProviderCapability = ImageCapability.UNSUPPORTED
        )
        assertEquals(ImageCapability.UNSUPPORTED, blocked.capability)
        assertEquals(ImageCapabilityBlocker.PROVIDER, blocked.blocker)
    }

    @Test fun pinnedProviderWithUnknownCapabilityWarns() {
        val endpoint = endpoint(
            host = "https://openrouter.ai/api/v1/",
            identity = ApiEndpointObject.IDENTITY_OPENROUTER,
            stored = ImageCapabilityStore.setMetadata(
                "", "author/vision", ImageCapability.SUPPORTED
            )
        )
        val route = ImageRoutingConfig(
            ImageCapabilityProvider.OPENROUTER,
            FavoriteModelObject.ROUTING_ONLY,
            pinnedProvider = "unknown-host"
        )
        assertEquals(
            ImageCapability.UNKNOWN,
            ImageCapabilityResolver.getImageCapability(endpoint, "author/vision", route)
        )
    }

    @Test fun restrictedAggregatorProvidersWithUnknownCapabilityWarn() {
        val endpoint = endpoint(
            host = "https://openrouter.ai/api/v1/",
            identity = ApiEndpointObject.IDENTITY_OPENROUTER,
            stored = ImageCapabilityStore.setMetadata(
                "", "author/vision", ImageCapability.SUPPORTED
            )
        )
        val restricted = ImageRoutingConfig(
            ImageCapabilityProvider.OPENROUTER,
            FavoriteModelObject.ROUTING_PREFERRED,
            allowFallbacks = false
        )
        assertEquals(
            ImageCapability.UNKNOWN,
            ImageCapabilityResolver.getImageCapability(endpoint, "author/vision", restricted)
        )
    }

    @Test fun definitiveFailureIsRouteScopedAndDoesNotPoisonAnotherProvider() {
        val endpoint = endpoint(
            host = "https://openrouter.ai/api/v1/",
            identity = ApiEndpointObject.IDENTITY_OPENROUTER,
            stored = ImageCapabilityStore.setMetadata(
                "", "author/vision", ImageCapability.SUPPORTED
            )
        )
        val alpha = ImageRoutingConfig(
            ImageCapabilityProvider.OPENROUTER,
            FavoriteModelObject.ROUTING_ONLY,
            pinnedProvider = "alpha"
        )
        val beta = alpha.copy(pinnedProvider = "beta")
        endpoint.imageCapabilityByModel = ImageCapabilityStore.setLearned(
            endpoint.imageCapabilityByModel,
            ImageCapabilityScope.key(endpoint, "author/vision", alpha),
            ImageCapability.UNSUPPORTED
        )
        assertEquals(
            ImageCapability.UNSUPPORTED,
            ImageCapabilityResolver.resolve(endpoint, "author/vision", alpha).capability
        )
        assertEquals(
            ImageCapability.UNKNOWN,
            ImageCapabilityResolver.resolve(endpoint, "author/vision", beta).capability
        )
        assertNotEquals(
            ImageCapabilityScope.key(endpoint, "author/vision", alpha),
            ImageCapabilityScope.key(endpoint, "author/vision", beta)
        )
    }

    @Test fun downstreamFailureDoesNotPoisonAutomaticAggregatorRouting() {
        val endpoint = endpoint(
            host = "https://openrouter.ai/api/v1/",
            identity = ApiEndpointObject.IDENTITY_OPENROUTER
        )
        val alpha = ImageRoutingConfig(
            ImageCapabilityProvider.OPENROUTER,
            FavoriteModelObject.ROUTING_ONLY,
            pinnedProvider = "alpha"
        )
        endpoint.imageCapabilityByModel = ImageCapabilityStore.setLearned(
            "",
            ImageCapabilityScope.key(endpoint, "author/model", alpha),
            ImageCapability.UNSUPPORTED
        )
        assertEquals(
            ImageCapability.UNKNOWN,
            ImageCapabilityResolver.getImageCapability(
                endpoint,
                "author/model",
                ImageRoutingConfig.from(endpoint, null)
            )
        )
    }

    @Test fun refreshedMetadataOverridesStaleLearnedFailure() {
        val endpoint = endpoint()
        val route = ImageRoutingConfig.from(endpoint, null)
        endpoint.imageCapabilityByModel = ImageCapabilityStore.setLearned(
            "",
            ImageCapabilityScope.key(endpoint, "changing-model", route),
            ImageCapability.UNSUPPORTED
        )
        assertEquals(
            ImageCapability.UNSUPPORTED,
            ImageCapabilityResolver.resolve(endpoint, "changing-model", route).capability
        )

        endpoint.imageCapabilityByModel = ImageCapabilityStore.refreshMetadata(
            endpoint.imageCapabilityByModel,
            mapOf("changing-model" to ImageCapability.SUPPORTED)
        )
        assertEquals(
            ImageCapability.SUPPORTED,
            ImageCapabilityResolver.resolve(endpoint, "changing-model", route).capability
        )
    }
}
