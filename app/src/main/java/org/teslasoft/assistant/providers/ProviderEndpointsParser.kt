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

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Parses the provider-discovery response (OpenRouter's
 * GET /models/{author}/{slug}/endpoints) into [ProviderEndpointInfo] rows.
 *
 * Deliberately tolerant: the endpoints API's optional fields vary by provider,
 * so every absent or unreadable field becomes null (rendered "?" in the chart)
 * instead of failing the whole list. Only a response with no readable
 * endpoints array at all counts as a parse failure (null return).
 */
object ProviderEndpointsParser {

    /** The default discovery path for OpenRouter; {model} is replaced with the
     *  model id. Matches the Provider Discovery Path placeholder on the
     *  endpoint editor's Advanced Options section. */
    const val DEFAULT_DISCOVERY_PATH = "/models/{model}/endpoints"

    fun parse(body: String): List<ProviderEndpointInfo>? {
        val root = try {
            Gson().fromJson(body, JsonObject::class.java)
        } catch (_: Exception) {
            null
        } ?: return null

        // {"data": {"endpoints": [...]}} is the documented shape; accept a
        // top-level "endpoints" array too in case a proxy flattens it.
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val endpoints = (data?.get("endpoints") ?: root.get("endpoints"))
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return null

        return endpoints.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject

            val providerName = str(obj, "provider_name") ?: str(obj, "name") ?: return@mapNotNull null
            val slug = str(obj, "tag") ?: providerName

            val pricing = obj.get("pricing")?.takeIf { it.isJsonObject }?.asJsonObject
            val cacheRead = num(pricing, "input_cache_read")
            val cacheWrite = num(pricing, "input_cache_write")

            val supportedParams = obj.get("supported_parameters")
                ?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { p -> if (p.isJsonPrimitive) p.asString else null }

            ProviderEndpointInfo(
                providerName = providerName,
                slug = slug,
                quantization = str(obj, "quantization"),
                promptPrice = num(pricing, "prompt"),
                completionPrice = num(pricing, "completion"),
                cacheReadPrice = cacheRead,
                cacheWritePrice = cacheWrite,
                latency = num(obj, "latency_last_30m") ?: num(obj, "latency"),
                throughput = num(obj, "throughput_last_30m") ?: num(obj, "throughput"),
                uptime = num(obj, "uptime_last_30m") ?: num(obj, "uptime"),
                supportsTools = supportedParams?.let { it.contains("tools") },
                // A provider that prices cache reads implements prompt caching.
                // No cache pricing does NOT prove absence, so only an explicit
                // false-y signal would set false — absent stays unknown unless
                // pricing exists at all (then no cache price = not cached).
                supportsCaching = when {
                    cacheRead != null || cacheWrite != null -> true
                    pricing != null -> false
                    else -> null
                },
                zdr = bool(obj, "is_zdr") ?: bool(obj, "zdr")
            )
        }
    }

    private fun str(obj: JsonObject?, key: String): String? =
        obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun num(obj: JsonObject?, key: String): Double? =
        obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()

    private fun bool(obj: JsonObject?, key: String): Boolean? =
        obj?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
