/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.concurrent.TimeUnit

/** Result of a quick, non-inference check for one saved Only-mode provider. */
enum class OnlyProviderAvailability {
    AVAILABLE,
    UNAVAILABLE,
    /** Network failure, unreadable/partial response, or any other inconclusive state. */
    UNKNOWN
}

/**
 * Quick Settings model changes need to know whether a model's saved Only-mode
 * provider still serves that model BEFORE the chat adopts the model. This client
 * uses the same provider-discovery path and the same authoritative-response rule
 * as [org.teslasoft.assistant.ui.activities.ChooseProviderActivity]: absence is
 * called UNAVAILABLE only after a complete provider list was successfully read.
 *
 * A failed/slow lookup is UNKNOWN, never UNAVAILABLE. That deliberately avoids
 * trapping the user behind a false warning when the network itself is having a
 * bad moment. Request-time Only routing remains fail-closed independently.
 */
object OnlyProviderAvailabilityClient {
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val CALL_TIMEOUT_SECONDS = 20L

    suspend fun check(
        endpoint: ApiEndpointObject,
        modelId: String,
        providerSlug: String
    ): OnlyProviderAvailability = withContext(Dispatchers.IO) {
        if (!endpoint.isOpenRouterRouting() || modelId.isBlank() || providerSlug.isBlank()) {
            return@withContext OnlyProviderAvailability.UNKNOWN
        }

        try {
            val client = OkHttpClient.Builder()
                // This is an interactive pre-selection probe, not generation.
                // Keep it short; timeout means UNKNOWN and the normal request
                // path remains responsible for enforcing Only mode.
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val base = endpoint.host.trimEnd('/')
            if (base.isBlank()) return@withContext OnlyProviderAvailability.UNKNOWN

            val discoveryPath = endpoint.providerDiscoveryPath
                .ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
            val fallbackUrl = base + discoveryPath.replace("{model}", modelId)

            // Match ChooseProviderActivity's alias hardening: with the standard
            // OpenRouter path, first ask the model record for its current details
            // link. If that lookup fails, the canonical fallback path still runs.
            val resolvedUrl = if (discoveryPath == ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH) {
                fetchBody(
                    client,
                    endpoint,
                    ProviderDiscoveryResolver.modelLookupUrl(base, modelId)
                )?.let { ProviderDiscoveryResolver.detailsUrl(base, it) }
            } else {
                null
            }

            val firstUrl = resolvedUrl ?: fallbackUrl
            var parsed = fetchBody(client, endpoint, firstUrl)?.let(ProviderEndpointsParser::parse)
            if (parsed == null && resolvedUrl != null && firstUrl != fallbackUrl) {
                parsed = fetchBody(client, endpoint, fallbackUrl)?.let(ProviderEndpointsParser::parse)
            }

            val result = parsed ?: return@withContext OnlyProviderAvailability.UNKNOWN
            if (!result.authoritative) return@withContext OnlyProviderAvailability.UNKNOWN

            if (result.endpoints.any { it.slug.equals(providerSlug, ignoreCase = true) }) {
                OnlyProviderAvailability.AVAILABLE
            } else {
                OnlyProviderAvailability.UNAVAILABLE
            }
        } catch (_: Exception) {
            OnlyProviderAvailability.UNKNOWN
        }
    }

    private fun fetchBody(
        client: OkHttpClient,
        endpoint: ApiEndpointObject,
        url: String
    ): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .applyEndpointAuth(endpoint)
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }

    private fun Request.Builder.applyEndpointAuth(endpoint: ApiEndpointObject): Request.Builder = apply {
        when (endpoint.authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
            ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
            else -> header("Authorization", "Bearer ${endpoint.apiKey}")
        }
    }
}
