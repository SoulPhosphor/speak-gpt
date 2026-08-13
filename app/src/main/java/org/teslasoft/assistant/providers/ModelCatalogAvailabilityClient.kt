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

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.models.EndpointCatalogCheck
import java.util.concurrent.TimeUnit

/**
 * Reads one endpoint's model catalog without sending an inference request.
 * A scan makes one catalog request per relevant endpoint. For OpenRouter only,
 * saved ids absent from that catalog get a targeted model lookup because
 * OpenRouter may continue accepting a known alias after the catalog switches
 * to a newer canonical slug.
 * OpenRouter is intentionally checked through its overall model catalog: an
 * upstream route outage does not make the base OpenRouter model unavailable.
 */
object ModelCatalogAvailabilityClient {

    private enum class AliasCheck { AVAILABLE, UNAVAILABLE, INDETERMINATE }

    suspend fun check(
        endpoint: ApiEndpointObject,
        targetModelIds: Set<String> = emptySet()
    ): EndpointCatalogCheck = withContext(Dispatchers.IO) {
        try {
            val base = endpoint.host.toHttpUrlOrNull() ?: return@withContext EndpointCatalogCheck.Unchecked
            val url = base.newBuilder().addPathSegment("models").build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .applyEndpointAuth(endpoint)
                .get()
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(
                    ApiEndpointObject.coerceConnectTimeoutSeconds(endpoint.connectTimeoutSeconds).toLong(),
                    TimeUnit.SECONDS
                )
                .readTimeout(45, TimeUnit.SECONDS)
                .build()

            val ids = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext EndpointCatalogCheck.Unchecked
                val body = response.body?.string().orEmpty()
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return@withContext EndpointCatalogCheck.Unchecked
                val data = root.asJsonObject.get("data")
                if (data == null || !data.isJsonArray) return@withContext EndpointCatalogCheck.Unchecked
                data.asJsonArray.flatMap { item ->
                    val model = item.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@flatMap emptyList<String>()
                    buildList {
                        model.get("id")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::add)
                        if (endpoint.isOpenRouterRouting()) {
                            model.get("canonical_slug")
                                ?.takeIf { it.isJsonPrimitive }
                                ?.asString
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }.toSet()
            }

            // An empty success response is too weak to call every saved model
            // unavailable. Treat it as inconclusive instead.
            if (ids.isEmpty()) return@withContext EndpointCatalogCheck.Unchecked
            if (!endpoint.isOpenRouterRouting()) {
                return@withContext EndpointCatalogCheck.Checked(ids)
            }

            val available = ids.toMutableSet()
            val indeterminate = LinkedHashSet<String>()
            (targetModelIds - ids).forEach { targetModelId ->
                when (checkOpenRouterAlias(client, base, endpoint, targetModelId)) {
                    AliasCheck.AVAILABLE -> available.add(targetModelId)
                    AliasCheck.UNAVAILABLE -> Unit
                    AliasCheck.INDETERMINATE -> indeterminate.add(targetModelId)
                }
            }
            EndpointCatalogCheck.Checked(available, indeterminate)
        } catch (_: Exception) {
            EndpointCatalogCheck.Unchecked
        }
    }

    /**
     * A 200 model response proves the exact saved id is still accepted, even
     * when OpenRouter resolves it to a different canonical slug. A 404 is
     * conclusive absence. Authentication, transport, and malformed-response
     * failures remain indeterminate and therefore cannot create a deletion
     * candidate.
     */
    private fun checkOpenRouterAlias(
        client: OkHttpClient,
        base: okhttp3.HttpUrl,
        endpoint: ApiEndpointObject,
        modelId: String
    ): AliasCheck {
        val separator = modelId.indexOf('/')
        if (separator <= 0 || separator == modelId.lastIndex) return AliasCheck.UNAVAILABLE
        val author = modelId.substring(0, separator)
        val slug = modelId.substring(separator + 1)
        val url = base.newBuilder()
            .addPathSegment("model")
            .addPathSegment(author)
            .addPathSegment(slug)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .applyEndpointAuth(endpoint)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use AliasCheck.UNAVAILABLE
                if (!response.isSuccessful) return@use AliasCheck.INDETERMINATE
                val data = JsonParser.parseString(response.body?.string().orEmpty())
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("data")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: return@use AliasCheck.INDETERMINATE
                val resolvedId = data.get("id")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    .orEmpty()
                val canonicalSlug = data.get("canonical_slug")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    .orEmpty()
                if (resolvedId.isNotBlank() || canonicalSlug.isNotBlank()) {
                    AliasCheck.AVAILABLE
                } else {
                    AliasCheck.INDETERMINATE
                }
            }
        } catch (_: Exception) {
            AliasCheck.INDETERMINATE
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
