/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.ProviderDiscoveryResolver
import java.util.concurrent.TimeUnit

data class LiveImageCapabilityEvidence(
    val model: ImageCapability = ImageCapability.UNKNOWN,
    val pinnedProvider: ImageCapability = ImageCapability.UNKNOWN,
    /** The complete selected-model result that may be persisted as metadata. */
    val refreshedModelCapability: ImageCapability = ImageCapability.UNKNOWN
)

/** Short, non-inference metadata lookup used only when an image send cannot be
 * decided from provider metadata already cached on the endpoint. Any network,
 * auth, parse, or catalog ambiguity remains UNKNOWN. */
object ImageCapabilityMetadataClient {
    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 12L
    private const val CALL_TIMEOUT_SECONDS = 15L

    suspend fun resolve(
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: ImageRoutingConfig
    ): LiveImageCapabilityEvidence = withContext(Dispatchers.IO) {
        if (endpoint.host.isBlank() || modelId.isBlank()) {
            return@withContext LiveImageCapabilityEvidence()
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val provider = routing.provider
            val base = endpoint.host.trimEnd('/')
            val primaryUrl = metadataUrl(base, modelId, provider)
                ?: return@withContext LiveImageCapabilityEvidence()
            val body = fetch(client, endpoint, provider, primaryUrl)
                ?: return@withContext LiveImageCapabilityEvidence()
            val capabilities = ImageCapabilityMetadata.capabilitiesFromResponse(body, provider)
            var modelCapability = capabilities
                ?.entries
                ?.firstOrNull { it.key.equals(modelId, ignoreCase = true) }
                ?.value
                ?: ImageCapability.UNKNOWN

            var pinnedCapability = ImageCapability.UNKNOWN
            if (provider == ImageCapabilityProvider.OPENROUTER) {
                // The single-model lookup can return a canonical details link.
                // It is also the only route where a provider-level record might
                // carry its own image metadata.
                val detailsUrl = ProviderDiscoveryResolver.detailsUrl(base, body)
                if (detailsUrl != null) {
                    val detailsBody = fetch(client, endpoint, provider, detailsUrl)
                    if (detailsBody != null) {
                        val detailsModels = ImageCapabilityMetadata.capabilitiesFromResponse(
                            detailsBody,
                            provider
                        )
                        detailsModels?.entries
                            ?.firstOrNull { it.key.equals(modelId, ignoreCase = true) }
                            ?.value
                            ?.takeIf { it != ImageCapability.UNKNOWN }
                            ?.let { modelCapability = it }
                        if (routing.pinned) {
                            pinnedCapability =
                                ImageCapabilityMetadata.openRouterPinnedProviderCapability(
                                    detailsBody,
                                    routing.pinnedProvider
                                )
                        }
                    }
                }
            }
            LiveImageCapabilityEvidence(
                model = modelCapability,
                pinnedProvider = pinnedCapability,
                refreshedModelCapability = modelCapability
            )
        } catch (_: Exception) {
            LiveImageCapabilityEvidence()
        }
    }

    private fun metadataUrl(
        base: String,
        modelId: String,
        provider: ImageCapabilityProvider
    ): String? {
        if (provider == ImageCapabilityProvider.OPENROUTER) {
            return ProviderDiscoveryResolver.modelLookupUrl(base, modelId)
        }
        val url = base.toHttpUrlOrNull() ?: return null
        return when (provider) {
            ImageCapabilityProvider.NANOGPT -> url.newBuilder()
                .addPathSegment("models")
                .addQueryParameter("detailed", "true")
                .build().toString()
            ImageCapabilityProvider.VENICE -> url.newBuilder()
                .addPathSegment("models")
                .addQueryParameter("type", "text")
                .build().toString()
            ImageCapabilityProvider.FEATHERLESS -> url.newBuilder()
                .addPathSegment("models")
                .addPathSegment(modelId)
                .build().toString()
            else -> url.newBuilder().addPathSegment("models").build().toString()
        }
    }

    private fun fetch(
        client: OkHttpClient,
        endpoint: ApiEndpointObject,
        provider: ImageCapabilityProvider,
        url: String
    ): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                when (endpoint.authType) {
                    ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                    ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                    else -> header("Authorization", "Bearer ${endpoint.apiKey}")
                }
                if (provider == ImageCapabilityProvider.ANTHROPIC) {
                    header("anthropic-version", "2023-06-01")
                }
            }
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }
}
