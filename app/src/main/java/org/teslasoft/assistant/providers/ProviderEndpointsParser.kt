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
                // latency_last_30m / throughput_last_30m arrive as percentile
                // objects ({"p50": …}); p50 is the value shown. A plain number
                // is accepted too for proxies that flatten it.
                latency = metric(obj, "latency_last_30m") ?: metric(obj, "latency"),
                throughput = metric(obj, "throughput_last_30m") ?: metric(obj, "throughput"),
                uptime = metric(obj, "uptime_last_30m") ?: metric(obj, "uptime"),
                supportsTools = supportedParams?.let { it.contains("tools") },
                // Only the API's explicit field decides caching support; cache
                // pricing alone is NOT proof (owner correction, Aug 2 2026).
                supportsCaching = bool(obj, "supports_implicit_caching"),
                // ZDR is NOT reported on the model-endpoints response; it comes
                // from the separate /endpoints/zdr list (parseZdrMatches below),
                // overlaid by the caller. An explicit field is honored if a
                // proxy provides one; otherwise unknown here.
                zdr = bool(obj, "is_zdr") ?: bool(obj, "zdr")
            )
        }
    }

    /**
     * Parses the Zero Data Retention endpoint list (OpenRouter:
     * GET /endpoints/zdr) and returns the lowercase provider identifiers
     * (tag, provider name) of the ZDR endpoints serving [modelId]. The caller
     * overlays this on the chart: listed → ZDR yes, absent → ZDR no.
     *
     * Records that carry a model identity for a DIFFERENT model are skipped;
     * records with no recognizable model identity are also skipped rather than
     * over-claiming ZDR for every model. Returns null when the body carries no
     * readable data array at all (→ ZDR stays unknown, "?").
     */
    fun parseZdrMatches(body: String, modelId: String): Set<String>? {
        val root = try {
            Gson().fromJson(body, JsonObject::class.java)
        } catch (_: Exception) {
            null
        } ?: return null

        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: root.get("endpoints")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return null

        val wanted = modelId.trim().lowercase()
        val matches = mutableSetOf<String>()
        for (el in data) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject

            // Model identity: explicit fields first, then the "Provider |
            // author/model" composite name.
            val name = str(obj, "name")
            val recordModel = (
                str(obj, "model_variant_slug") ?: str(obj, "model_id") ?: str(obj, "model")
                    ?: name?.substringAfter("|", "")?.trim()?.takeIf { it.contains("/") }
                )?.lowercase() ?: continue
            if (recordModel != wanted) continue

            str(obj, "tag")?.let { matches.add(it.lowercase()) }
            str(obj, "provider_name")?.let { matches.add(it.lowercase()) }
            name?.substringBefore("|")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { matches.add(it.lowercase()) }
        }
        return matches
    }

    private fun str(obj: JsonObject?, key: String): String? =
        obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun num(obj: JsonObject?, key: String): Double? =
        obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()

    /** A stats value that may be a plain number or a percentile object — the
     *  percentile shape yields its p50. */
    private fun metric(obj: JsonObject?, key: String): Double? {
        val el = obj?.get(key) ?: return null
        return when {
            el.isJsonPrimitive -> el.asString.toDoubleOrNull()
            el.isJsonObject -> num(el.asJsonObject, "p50")
            else -> null
        }
    }

    private fun bool(obj: JsonObject?, key: String): Boolean? =
        obj?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
