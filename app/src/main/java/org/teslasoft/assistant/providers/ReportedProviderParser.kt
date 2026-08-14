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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import org.json.JSONObject

/** Reads an API-reported serving provider without consulting app configuration. */
object ReportedProviderParser {

    /** Response facts the typed OpenAI-compatible client may not expose. */
    data class ResponseMetadata(
        val modelId: String? = null,
        val provider: String? = null,
        val responseId: String? = null,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val totalTokens: Long? = null,
        val usageJson: String? = null,
        val costJson: String? = null
    ) {
        fun isEmpty(): Boolean = modelId == null && provider == null && responseId == null &&
            inputTokens == null && outputTokens == null && totalTokens == null &&
            usageJson == null && costJson == null
    }

    /**
     * Read the observer's split copy of a generation stream, reporting the
     * first API-reported provider found.
     *
     * The copy MUST be read through to end of stream, found or not. Ktor's
     * channel splitter feeds the live reply and this copy in lockstep;
     * abandoning the copy mid-stream stalls the splitter once the copy's
     * ~4 KB buffer fills, freezing the visible reply. Cancelling the copy
     * instead makes Ktor's body copier cancel the origin response, killing
     * the reply outright. Draining to the end is the only safe exit.
     */
    suspend fun consumeObservedStream(channel: ByteReadChannel, onProvider: (String) -> Unit) {
        var noted = false
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (!noted) {
                val reported = fromResponseLine(line)
                if (reported != null) {
                    onProvider(reported)
                    noted = true
                }
            }
        }
    }

    /**
     * Drain an observed response copy while preserving every structured fact
     * relevant to durable message attribution. Unlike the provider-only helper,
     * this keeps reading metadata-bearing final usage chunks after identity was
     * found near the start of the stream.
     */
    suspend fun consumeObservedMetadataStream(
        channel: ByteReadChannel,
        onMetadata: (ResponseMetadata) -> Unit
    ) {
        var haveModel = false
        var haveResponseId = false
        while (true) {
            val line = channel.readUTF8Line() ?: break
            // Identity normally arrives on the first SSE chunk. After that,
            // avoid reparsing every text delta; keep watching only for a still
            // model/response identity, an explicit provider hint, or a late
            // structured usage/cost chunk.
            val hasProvider = line.contains("\"provider\"") ||
                line.contains("\"openrouter_metadata\"")
            val hasUsage = line.contains("\"usage\"") ||
                line.contains("cost", ignoreCase = true)
            if (haveModel && haveResponseId && !hasProvider && !hasUsage
            ) continue
            val metadata = metadataFromResponseLine(line)
            if (metadata != null && !metadata.isEmpty()) {
                onMetadata(metadata)
                haveModel = haveModel || metadata.modelId != null
                haveResponseId = haveResponseId || metadata.responseId != null
            }
        }
    }

    /**
     * OpenRouter's opted-in router metadata is authoritative: use the endpoint
     * whose response marks `selected: true`. A top-level `provider` supplied by
     * another response shape is the fallback. Comments, `[DONE]`, malformed
     * JSON, and blank/missing values are ignored.
     */
    fun fromResponseLine(line: String): String? {
        return metadataFromResponseLine(line)?.provider
    }

    /** Parse one SSE/JSON line without inferring absent fields. */
    fun metadataFromResponseLine(line: String): ResponseMetadata? {
        val payload = payload(line) ?: return null
        return try {
            val root = JsonParser.parseString(payload).asJsonObject
            val available = root.get("openrouter_metadata")
                ?.takeUnless { it.isJsonNull }
                ?.asJsonObject
                ?.get("endpoints")
                ?.takeUnless { it.isJsonNull }
                ?.asJsonObject
                ?.get("available")
                ?.takeUnless { it.isJsonNull }
                ?.asJsonArray

            var provider: String? = null
            if (available != null) {
                for (element in available) {
                    val endpoint = element.takeUnless { it.isJsonNull }?.asJsonObject ?: continue
                    if (endpoint.get("selected")?.takeUnless { it.isJsonNull }?.asBoolean == true) {
                        provider = endpoint.get("provider")
                            ?.takeUnless { it.isJsonNull }
                            ?.asString
                            ?.trim()
                            ?.ifBlank { null }
                        if (provider != null) break
                    }
                }
            }
            if (provider == null) {
                provider = root.get("provider")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.trim()
                    ?.ifBlank { null }
            }

            val usage = root.get("usage")?.takeUnless { it.isJsonNull }
            val usageObject = usage?.takeIf { it.isJsonObject }?.asJsonObject
            val costs = JSONObject()
            if (payload.contains("cost", ignoreCase = true)) {
                val rawRoot = JSONObject(payload)
                for (key in rawRoot.keys()) {
                    if (key.contains("cost", ignoreCase = true) && !rawRoot.isNull(key)) {
                        costs.put(key, rawRoot.get(key))
                    }
                }
            }
            ResponseMetadata(
                modelId = root.stringOrNull("model"),
                provider = provider,
                responseId = root.stringOrNull("id"),
                inputTokens = usageObject.longOrNull("input_tokens", "prompt_tokens"),
                outputTokens = usageObject.longOrNull("output_tokens", "completion_tokens"),
                totalTokens = usageObject.longOrNull("total_tokens"),
                usageJson = usage?.takeIf { it.isJsonObject }?.toString(),
                costJson = costs.takeIf { it.length() > 0 }?.toString()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun payload(line: String): String? {
        val trimmed = line.trim()
        val payload = when {
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed.substring(5).trim()
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }
        return payload.takeUnless { it.isBlank() || it == "[DONE]" }
    }

    private fun com.google.gson.JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString?.trim()?.ifBlank { null }

    private fun com.google.gson.JsonObject?.longOrNull(vararg keys: String): Long? {
        this ?: return null
        for (key in keys) {
            val value = get(key)?.takeUnless { it.isJsonNull } ?: continue
            try {
                value.asString.trim().toLongOrNull()?.let { return it }
            } catch (_: Exception) {
                // Structured/non-scalar values are retained below, not coerced.
            }
            // A provider's non-integer extension is retained in usageJson,
            // but never coerced or truncated into a token count.
        }
        return null
    }
}
