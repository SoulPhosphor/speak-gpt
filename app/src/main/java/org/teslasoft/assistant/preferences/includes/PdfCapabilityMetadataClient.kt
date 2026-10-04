/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.ProviderDiscoveryResolver

data class LivePdfCapabilityEvidence(
    val model: PdfCapability = PdfCapability.UNKNOWN,
    val pinnedProvider: PdfCapability = PdfCapability.UNKNOWN
)

/** Fetches official metadata only when a PDF send needs unresolved evidence. */
object PdfCapabilityMetadataClient {
    suspend fun resolve(
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: PdfRoutingConfig
    ): LivePdfCapabilityEvidence = withContext(Dispatchers.IO) {
        if (routing.provider !in setOf(PdfCapabilityProvider.OPENROUTER, PdfCapabilityProvider.NANOGPT)) {
            return@withContext LivePdfCapabilityEvidence()
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
            val base = endpoint.host.trimEnd('/')
            val url = when (routing.provider) {
                PdfCapabilityProvider.OPENROUTER -> ProviderDiscoveryResolver.modelLookupUrl(base, modelId)
                PdfCapabilityProvider.NANOGPT -> base.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegment("models")?.addQueryParameter("detailed", "true")
                    ?.build()?.toString()
                else -> null
            } ?: return@withContext LivePdfCapabilityEvidence()
            val body = fetch(client, endpoint, url) ?: return@withContext LivePdfCapabilityEvidence()
            var model = PdfCapabilityMetadata.capabilitiesFromResponse(body, routing.provider)
                ?.entries?.firstOrNull { it.key.equals(modelId, true) }?.value
                ?: PdfCapability.UNKNOWN
            var pinned = PdfCapability.UNKNOWN
            if (routing.provider == PdfCapabilityProvider.OPENROUTER) {
                ProviderDiscoveryResolver.detailsUrl(base, body)?.let { detailsUrl ->
                    fetch(client, endpoint, detailsUrl)?.let { details ->
                        PdfCapabilityMetadata.capabilitiesFromResponse(details, routing.provider)
                            ?.entries?.firstOrNull { it.key.equals(modelId, true) }?.value
                            ?.takeIf { it != PdfCapability.UNKNOWN }?.let { model = it }
                        if (routing.pinned) {
                            pinned = PdfCapabilityMetadata.openRouterPinnedProviderCapability(
                                details, routing.pinnedProvider
                            )
                        }
                    }
                }
            }
            LivePdfCapabilityEvidence(model, pinned)
        } catch (_: Exception) {
            LivePdfCapabilityEvidence()
        }
    }

    private fun fetch(client: OkHttpClient, endpoint: ApiEndpointObject, url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            when (endpoint.authType) {
                ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                else -> header("Authorization", "Bearer ${endpoint.apiKey}")
            }
        }.get().build()
        return client.newCall(request).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }
}
