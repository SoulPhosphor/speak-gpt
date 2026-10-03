/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import java.net.URI

/** Provider families whose official catalog shapes SpeakGPT understands. */
enum class ImageCapabilityProvider {
    OPENROUTER,
    NANOGPT,
    VENICE,
    FEATHERLESS,
    ANTHROPIC,
    ZAI_GLM,
    OPENAI,
    GENERIC;

    val isAggregator: Boolean
        get() = this == OPENROUTER || this == NANOGPT

    companion object {
        fun forEndpoint(endpoint: ApiEndpointObject): ImageCapabilityProvider {
            if (endpoint.isOpenRouterRouting() ||
                ApiEndpointObject.isRecognizedOpenRouterUrl(endpoint.host)
            ) return OPENROUTER
            val hostname = try {
                URI(endpoint.host.trim()).host?.lowercase().orEmpty()
            } catch (_: Exception) {
                ""
            }
            return when {
                hostname == "nano-gpt.com" || hostname.endsWith(".nano-gpt.com") -> NANOGPT
                hostname == "api.venice.ai" -> VENICE
                hostname == "api.featherless.ai" -> FEATHERLESS
                hostname == "api.anthropic.com" -> ANTHROPIC
                hostname == "api.z.ai" || hostname == "open.bigmodel.cn" -> ZAI_GLM
                hostname == "api.openai.com" -> OPENAI
                else -> GENERIC
            }
        }
    }
}

/** The actual outgoing route. Preferred mode with fallbacks enabled remains a
 * routable aggregator request; Only mode is the one-provider pinned case. */
data class ImageRoutingConfig(
    val provider: ImageCapabilityProvider,
    val routingType: String = FavoriteModelObject.ROUTING_AUTOMATIC,
    val pinnedProvider: String? = null,
    val allowFallbacks: Boolean = true,
    val providerOrder: List<String> = emptyList(),
    val ignoredProviders: List<String> = emptyList()
) {
    val pinned: Boolean
        get() = provider.isAggregator &&
            routingType == FavoriteModelObject.ROUTING_ONLY &&
            !pinnedProvider.isNullOrBlank()

    val aggregatorCanRoute: Boolean
        get() = provider.isAggregator && !pinned &&
            (routingType == FavoriteModelObject.ROUTING_AUTOMATIC || allowFallbacks)

    companion object {
        fun from(endpoint: ApiEndpointObject, favorite: FavoriteModelObject?): ImageRoutingConfig {
            val provider = ImageCapabilityProvider.forEndpoint(endpoint)
            return ImageRoutingConfig(
                provider = provider,
                routingType = favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC,
                pinnedProvider = favorite?.selectedProvider?.trim()?.ifBlank { null },
                allowFallbacks = favorite?.allowFallbacks != false,
                providerOrder = favorite?.providerOrder.orEmpty(),
                ignoredProviders = favorite?.ignoredProviders.orEmpty()
            )
        }
    }
}

/** Distinguishes the two hard-block messages without leaking provider details
 * into UI code. */
enum class ImageCapabilityBlocker { MODEL, PROVIDER }

data class ImageCapabilityDecision(
    val capability: ImageCapability,
    val blocker: ImageCapabilityBlocker? = null
)

/** Stable identity for learned failures and warning suppression. The endpoint
 * profile id is supplied by the caller/store; this key covers the remaining
 * endpoint/API type, model, routed provider, and image modality dimensions. */
object ImageCapabilityScope {
    fun key(
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: ImageRoutingConfig
    ): String = listOf(
        "v2",
        routing.provider.name.lowercase(),
        endpoint.host.trim().trimEnd('/').lowercase(),
        endpoint.chatEndpoint.trim()
            .ifBlank { ApiEndpointObject.DEFAULT_CHAT_ENDPOINT }
            .lowercase(),
        modelId.trim(),
        when {
            routing.pinned -> "only:${routing.pinnedProvider!!.lowercase()}"
            routing.provider.isAggregator -> buildString {
                append(if (routing.aggregatorCanRoute) "routed:" else "restricted:")
                append(routing.routingType.lowercase())
                append(":fallback=").append(routing.allowFallbacks)
                append(":order=").append(
                    routing.providerOrder.joinToString(",") { it.trim().lowercase() }
                )
                append(":ignore=").append(
                    routing.ignoredProviders.joinToString(",") { it.trim().lowercase() }
                )
            }
            else -> "direct"
        },
        "image"
    ).joinToString("|")
}

/** One shared answer used by every image-send path. Provider code supplies
 * evidence; this object owns the ordering and aggregator/pinned semantics. */
object ImageCapabilityResolver {

    fun getImageCapability(
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: ImageRoutingConfig = ImageRoutingConfig.from(endpoint, null),
        liveModelCapability: ImageCapability = ImageCapability.UNKNOWN,
        livePinnedProviderCapability: ImageCapability = ImageCapability.UNKNOWN
    ): ImageCapability = resolve(
        endpoint,
        modelId,
        routing,
        liveModelCapability,
        livePinnedProviderCapability
    ).capability

    fun resolve(
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: ImageRoutingConfig = ImageRoutingConfig.from(endpoint, null),
        liveModelCapability: ImageCapability = ImageCapability.UNKNOWN,
        livePinnedProviderCapability: ImageCapability = ImageCapability.UNKNOWN
    ): ImageCapabilityDecision {
        val stored = endpoint.imageCapabilityByModel

        // 1. Fresh provider metadata, then 2. provider metadata already cached
        // by normal catalog work. A legacy plain model row is retained as the
        // user's explicit endpoint-level override.
        val modelCapability = firstDefinitive(
            liveModelCapability,
            ImageCapabilityStore.getMetadata(stored, modelId),
            ImageCapabilityStore.get(stored, modelId)
        )
        if (modelCapability == ImageCapability.UNSUPPORTED) {
            return ImageCapabilityDecision(
                ImageCapability.UNSUPPORTED,
                ImageCapabilityBlocker.MODEL
            )
        }

        val scope = ImageCapabilityScope.key(endpoint, modelId, routing)
        val learnedRoute = ImageCapabilityStore.getLearned(stored, scope)

        if (routing.pinned) {
            // A model can accept images overall while one explicitly selected
            // downstream provider cannot. Keep that answer provider-scoped.
            val providerCapability = firstDefinitive(
                livePinnedProviderCapability,
                learnedRoute
            )
            if (providerCapability == ImageCapability.UNSUPPORTED) {
                return ImageCapabilityDecision(
                    ImageCapability.UNSUPPORTED,
                    ImageCapabilityBlocker.PROVIDER
                )
            }
            if (providerCapability == ImageCapability.SUPPORTED &&
                (modelCapability == ImageCapability.SUPPORTED ||
                    learnedRoute == ImageCapability.SUPPORTED ||
                    livePinnedProviderCapability == ImageCapability.SUPPORTED)
            ) return ImageCapabilityDecision(ImageCapability.SUPPORTED)
            return ImageCapabilityDecision(ImageCapability.UNKNOWN)
        }

        // Automatic/fallback aggregator routing may choose any compatible
        // endpoint. Never turn uncertainty about individual downstreams into a
        // warning when the model catalog itself confirms image input.
        if (routing.aggregatorCanRoute && modelCapability == ImageCapability.SUPPORTED) {
            return ImageCapabilityDecision(ImageCapability.SUPPORTED)
        }

        if (routing.provider.isAggregator && !routing.pinned &&
            !routing.aggregatorCanRoute
        ) {
            return when (learnedRoute) {
                ImageCapability.SUPPORTED -> ImageCapabilityDecision(ImageCapability.SUPPORTED)
                ImageCapability.UNSUPPORTED -> ImageCapabilityDecision(
                    ImageCapability.UNSUPPORTED,
                    ImageCapabilityBlocker.PROVIDER
                )
                ImageCapability.UNKNOWN -> ImageCapabilityDecision(ImageCapability.UNKNOWN)
            }
        }

        val finalCapability = firstDefinitive(modelCapability, learnedRoute)
        return ImageCapabilityDecision(
            finalCapability,
            if (finalCapability == ImageCapability.UNSUPPORTED) {
                ImageCapabilityBlocker.MODEL
            } else null
        )
    }

    private fun firstDefinitive(vararg values: ImageCapability): ImageCapability =
        values.firstOrNull { it != ImageCapability.UNKNOWN } ?: ImageCapability.UNKNOWN
}
