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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import org.teslasoft.assistant.preferences.RawStreamObservation
import org.teslasoft.assistant.preferences.RawStreamObservationCodec

/** Reads API-reported serving-provider and terminal SSE metadata. */
object ReportedProviderParser {

    /**
     * Read the observer's split copy of a generation stream.
     *
     * The copy MUST be drained through end-of-stream. Ktor's channel splitter
     * feeds the live reply and this observer copy in lockstep; abandoning or
     * cancelling the copy can stall/cancel the origin. While draining, collect
     * only protocol/terminal metadata. Generated content is counted but never
     * retained or emitted into diagnostics.
     *
     * ChatActivity's existing callback is pinned to the exact lifecycle recorder
     * for this response. Provider names are sent normally; at terminal time one
     * private RawStreamObservation envelope is sent through that same callback so
     * raw metadata cannot race onto a later request.
     */
    suspend fun consumeObservedStream(
        channel: ByteReadChannel,
        /** Optional side observer given every raw SSE line as it is drained, so
         *  a second consumer (reasoning capture) can ride the SAME single drain
         *  without a second bodyAsChannel() read. It runs before this parser's
         *  own line handling and must never throw; any exception it raises is
         *  swallowed so it can never disturb the drain or the live stream.
         *  Declared before [onProvider] so existing trailing-lambda callers
         *  still bind their lambda to [onProvider]. */
        lineObserver: ((String) -> Unit)? = null,
        /** Direct terminal metadata consumer used by durable usage accounting.
         * It is separate from the compatibility String envelope consumed by
         * Response Lifecycle diagnostics. */
        onObservation: ((RawStreamObservation) -> Unit)? = null,
        onProvider: (String) -> Unit
    ) {
        val inspector = RawSseInspector()
        var providerNoted = false
        try {
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (lineObserver != null) {
                    try {
                        lineObserver(line)
                    } catch (_: Exception) { /* side observer is best-effort only */ }
                }
                val provider = inspector.acceptLine(line)
                if (!providerNoted && provider != null) {
                    onProvider(provider)
                    providerNoted = true
                }
            }
            val observation = inspector.finishNormally()
            onObservation?.invoke(observation)
            onProvider(RawStreamObservationCodec.encode(observation))
        } catch (t: Throwable) {
            // The observer is diagnostics, not generation control. Record its
            // exception instead of converting an observer-side read failure into
            // a new app-visible generation failure. Preserve coroutine cancel.
            val observation = inspector.finishByException(t)
            onObservation?.invoke(observation)
            onProvider(RawStreamObservationCodec.encode(observation))
            if (t is CancellationException) throw t
        }
    }

    /**
     * OpenRouter's opted-in router metadata is authoritative: use the endpoint
     * whose response marks `selected: true`. A top-level `provider` supplied by
     * another response shape is the fallback. Comments, `[DONE]`, malformed JSON,
     * and blank/missing values are ignored.
     */
    fun fromResponseLine(line: String): String? {
        val payload = payloadFromLine(line) ?: return null
        if (payload == "[DONE]") return null
        return try {
            providerFromRoot(JsonParser.parseString(payload).asJsonObject)
        } catch (_: Exception) {
            null
        }
    }

    internal fun payloadFromLine(line: String): String? {
        val trimmed = line.trim()
        val payload = when {
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed.substring(5).trim()
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }
        return payload.ifBlank { null }
    }

    internal fun providerFromRoot(root: JsonObject): String? {
        val available = root.get("openrouter_metadata")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("endpoints")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("available")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray

        if (available != null) {
            for (element in available) {
                val endpoint = element.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject ?: continue
                if (endpoint.get("selected")?.takeUnless { it.isJsonNull }?.asBoolean == true) {
                    endpoint.get("provider")
                        ?.takeUnless { it.isJsonNull }
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.ifBlank { null }
                        ?.let { return it }
                }
            }
        }

        return root.get("provider")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.ifBlank { null }
    }
}

/** Pure line inspector, kept device-free so terminal protocol behavior is unit-testable. */
internal class RawSseInspector {
    private var sseDataEvents = 0
    private var rawContentChunks = 0
    private var providerErrorReceived = false
    private var providerErrorSummary: String? = null
    private var finishReason: String? = null
    private var receivedDone = false
    private var protocolTerminalMarker: String? = null
    private var usageReceived = false
    private var promptTokens: Int? = null
    private var completionTokens: Int? = null
    private var totalTokens: Int? = null
    private var model: String? = null
    private var generationId: String? = null
    private var malformedDataEvents = 0

    /** Returns an API-reported provider if this line contains one. */
    fun acceptLine(line: String): String? {
        val payload = ReportedProviderParser.payloadFromLine(line) ?: return null
        if (line.trim().startsWith("data:", ignoreCase = true)) sseDataEvents++

        if (payload == "[DONE]") {
            receivedDone = true
            protocolTerminalMarker = "[DONE]"
            return null
        }

        val root = try {
            JsonParser.parseString(payload).takeIf { it.isJsonObject }?.asJsonObject
                ?: run {
                    malformedDataEvents++
                    return null
                }
        } catch (_: Exception) {
            malformedDataEvents++
            return null
        }

        if (generationId == null) {
            generationId = root.stringOrNull("id")
        }
        if (model == null) {
            model = root.stringOrNull("model")
        }

        root.get("usage")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject?.let { usage ->
            usageReceived = true
            usage.intOrNull("prompt_tokens")?.let { promptTokens = it }
            usage.intOrNull("completion_tokens")?.let { completionTokens = it }
            usage.intOrNull("total_tokens")?.let { totalTokens = it }
        }

        root.stringOrNull("type")?.let { type ->
            if (type.equals("response.done", ignoreCase = true)) {
                protocolTerminalMarker = "response.done"
            }
        }

        root.get("choices")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonArray }?.asJsonArray?.let { choices ->
            var hasContent = false
            for (element in choices) {
                val choice = element.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                choice.stringOrNull("finish_reason")?.let { finishReason = it }

                val text = choice.stringOrNull("text")
                if (!text.isNullOrEmpty()) hasContent = true

                val delta = choice.get("delta")?.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonObject }?.asJsonObject
                val content = delta?.stringOrNull("content")
                if (!content.isNullOrEmpty()) hasContent = true
            }
            if (hasContent) rawContentChunks++
        }

        root.get("error")?.takeUnless { it.isJsonNull }?.let { error ->
            providerErrorReceived = true
            providerErrorSummary = when {
                error.isJsonObject -> summarizeError(error.asJsonObject)
                error.isJsonPrimitive -> error.asString.trim().ifBlank { "provider error event received" }
                else -> "provider error event received"
            }.take(1200)
        }

        return ReportedProviderParser.providerFromRoot(root)
    }

    fun finishNormally(): RawStreamObservation = snapshot(
        flowEndedNormally = true,
        flowException = null
    )

    fun finishByException(t: Throwable): RawStreamObservation = snapshot(
        flowEndedNormally = false,
        flowException = buildString {
            append(t::class.java.simpleName.ifBlank { t::class.java.name })
            t.message?.trim()?.ifBlank { null }?.let { append(": ").append(it) }
        }.take(1200)
    )

    private fun snapshot(flowEndedNormally: Boolean, flowException: String?): RawStreamObservation =
        RawStreamObservation(
            sseDataEvents = sseDataEvents,
            rawContentChunks = rawContentChunks,
            providerErrorReceived = providerErrorReceived,
            providerErrorSummary = providerErrorSummary,
            finishReason = finishReason,
            receivedDone = receivedDone,
            protocolTerminalMarker = protocolTerminalMarker,
            usageReceived = usageReceived,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            model = model,
            generationId = generationId,
            malformedDataEvents = malformedDataEvents,
            flowEndedNormally = flowEndedNormally,
            flowException = flowException
        )

    private fun summarizeError(error: JsonObject): String {
        val parts = linkedSetOf<String>()
        error.stringOrNull("code")?.let { parts.add("code=$it") }
        error.stringOrNull("type")?.let { parts.add("type=$it") }
        error.stringOrNull("message")?.let { parts.add(it) }
        error.get("metadata")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject?.let { metadata ->
            metadata.stringOrNull("error_type")?.let { parts.add("error_type=$it") }
            metadata.stringOrNull("provider_name")?.let { parts.add("provider=$it") }
        }
        return parts.joinToString("; ").ifBlank { "provider error event received" }
    }
}

private fun JsonObject.stringOrNull(name: String): String? = try {
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString
        ?.trim()?.ifBlank { null }
} catch (_: Exception) {
    null
}

private fun JsonObject.intOrNull(name: String): Int? = try {
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asInt
} catch (_: Exception) {
    null
}
