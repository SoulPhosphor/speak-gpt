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

package org.teslasoft.assistant.preferences

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Metadata extracted from the observer's split copy of a streamed HTTP response.
 * Deliberately contains no generated response text. The only free-form strings
 * retained are terminal/error metadata and identifiers.
 */
data class RawStreamObservation(
    val sseDataEvents: Int = 0,
    val rawContentChunks: Int = 0,
    val providerErrorReceived: Boolean = false,
    val providerErrorSummary: String? = null,
    val finishReason: String? = null,
    val receivedDone: Boolean = false,
    val protocolTerminalMarker: String? = null,
    val usageReceived: Boolean = false,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val generationId: String? = null,
    val malformedDataEvents: Int = 0,
    val flowEndedNormally: Boolean = false,
    val flowException: String? = null
)

/**
 * Compatibility envelope used by ReportedProviderParser's existing String
 * callback. ChatActivity already wires that callback to the exact lifecycle
 * recorder for the exact split response, so carrying one private terminal
 * summary through the same callback avoids a second global/current-response
 * lookup and cannot attach metadata to a later request.
 */
object RawStreamObservationCodec {
    private const val PREFIX = "\u0000response-lifecycle-raw-v1:"

    fun isEncoded(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun encode(value: RawStreamObservation): String {
        fun b(v: Boolean) = if (v) "1" else "0"
        fun n(v: Int?) = v?.toString().orEmpty()
        fun s(v: String?): String {
            val text = v.orEmpty()
            if (text.isEmpty()) return ""
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.toByteArray(StandardCharsets.UTF_8))
        }
        return PREFIX + listOf(
            value.sseDataEvents.toString(),
            value.rawContentChunks.toString(),
            b(value.providerErrorReceived),
            s(value.providerErrorSummary),
            s(value.finishReason),
            b(value.receivedDone),
            s(value.protocolTerminalMarker),
            b(value.usageReceived),
            n(value.promptTokens),
            n(value.completionTokens),
            n(value.totalTokens),
            s(value.generationId),
            value.malformedDataEvents.toString(),
            b(value.flowEndedNormally),
            s(value.flowException)
        ).joinToString("|")
    }

    fun decode(value: String?): RawStreamObservation? {
        if (!isEncoded(value)) return null
        return try {
            val parts = value!!.substring(PREFIX.length).split('|')
            if (parts.size != 15) return null
            fun bool(i: Int) = parts[i] == "1"
            fun int(i: Int) = parts[i].toIntOrNull()
            fun str(i: Int): String? {
                val encoded = parts[i]
                if (encoded.isEmpty()) return null
                return String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                    .trim().ifBlank { null }
            }
            RawStreamObservation(
                sseDataEvents = int(0) ?: 0,
                rawContentChunks = int(1) ?: 0,
                providerErrorReceived = bool(2),
                providerErrorSummary = str(3),
                finishReason = str(4),
                receivedDone = bool(5),
                protocolTerminalMarker = str(6),
                usageReceived = bool(7),
                promptTokens = int(8),
                completionTokens = int(9),
                totalTokens = int(10),
                generationId = str(11),
                malformedDataEvents = int(12) ?: 0,
                flowEndedNormally = bool(13),
                flowException = str(14)
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Facts gathered on the typed and raw sides of one lifecycle record. */
internal data class LifecycleDiagnosticEvidence(
    var requestDispatchedObserved: Boolean? = null,
    var httpSuccessful: Boolean? = null,
    var typedChunks: Int = 0,
    var typedContentChunks: Int = 0,
    var typedUsageReceived: Boolean = false,
    var rawObservation: RawStreamObservation? = null
) {
    fun snapshot(): LifecycleDiagnosticEvidence = copy(rawObservation = rawObservation?.copy())
}

/**
 * The response observer runs on a different coroutine from the typed stream.
 * Store only counters/terminal facts keyed by the lifecycle record until the
 * formatter consumes them. startLifecycle finalizes an old record before a new
 * record of the same turn/phase is opened, so this key is unambiguous in the
 * app's strictly sequential visible-generation pipeline.
 */
internal object LifecycleDiagnosticEvidenceStore {
    private val records = ConcurrentHashMap<String, LifecycleDiagnosticEvidence>()

    private fun key(turnId: String, phase: String): String = "$turnId\u0000$phase"

    private fun mutate(turnId: String, phase: String, block: (LifecycleDiagnosticEvidence) -> Unit) {
        val k = key(turnId, phase)
        records.compute(k) { _, current ->
            val value = current ?: LifecycleDiagnosticEvidence()
            synchronized(value) { block(value) }
            value
        }
    }

    fun noteTypedChunk(
        turnId: String,
        phase: String,
        contentLength: Int,
        usageReceived: Boolean
    ) = mutate(turnId, phase) {
        it.requestDispatchedObserved = true
        it.typedChunks++
        if (contentLength > 0) it.typedContentChunks++
        if (usageReceived) it.typedUsageReceived = true
    }

    fun noteSuccessfulHttpResponse(turnId: String, phase: String) = mutate(turnId, phase) {
        it.requestDispatchedObserved = true
        it.httpSuccessful = true
    }

    fun noteRawObservation(turnId: String, phase: String, observation: RawStreamObservation) =
        mutate(turnId, phase) {
            it.requestDispatchedObserved = true
            it.httpSuccessful = true
            it.rawObservation = observation
        }

    fun take(turnId: String, phase: String): LifecycleDiagnosticEvidence? {
        val value = records.remove(key(turnId, phase)) ?: return null
        return synchronized(value) { value.snapshot() }
    }
}
