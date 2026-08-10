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
 * A scan calls this once per relevant endpoint, never once per saved model.
 * OpenRouter is intentionally checked through its overall model catalog: an
 * upstream route outage does not make the base OpenRouter model unavailable.
 */
object ModelCatalogAvailabilityClient {

    suspend fun check(endpoint: ApiEndpointObject): EndpointCatalogCheck = withContext(Dispatchers.IO) {
        try {
            val base = endpoint.host.toHttpUrlOrNull() ?: return@withContext EndpointCatalogCheck.Unchecked
            val url = base.newBuilder().addPathSegment("models").build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    when (endpoint.authType) {
                        ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                        ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                        else -> header("Authorization", "Bearer ${endpoint.apiKey}")
                    }
                }
                .get()
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(
                    ApiEndpointObject.coerceConnectTimeoutSeconds(endpoint.connectTimeoutSeconds).toLong(),
                    TimeUnit.SECONDS
                )
                .readTimeout(45, TimeUnit.SECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext EndpointCatalogCheck.Unchecked
                val body = response.body?.string().orEmpty()
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return@withContext EndpointCatalogCheck.Unchecked
                val data = root.asJsonObject.get("data")
                if (data == null || !data.isJsonArray) return@withContext EndpointCatalogCheck.Unchecked
                val ids = data.asJsonArray.mapNotNull { item ->
                    item.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("id")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.takeIf { it.isNotBlank() }
                }.toSet()
                // An empty success response is too weak to call every saved
                // model unavailable. Treat it as inconclusive instead.
                if (ids.isEmpty()) EndpointCatalogCheck.Unchecked
                else EndpointCatalogCheck.Checked(ids)
            }
        } catch (_: Exception) {
            EndpointCatalogCheck.Unchecked
        }
    }
}
