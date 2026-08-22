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
    /** Exact API-reported charged cost. Kept out of the legacy diagnostics
     * envelope and delivered directly to durable accounting. */
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val totalCost: Double? = null,
    /** Response-reported model id. Kept out of the legacy diagnostic envelope;
     * durable usage accounting receives the observation object directly. */
    val model: String? = null,
    val generationId: String? = null,
    val malformedDataEvents: Int = 0,
    val flowEndedNormally: Boolean = false,
    val flowException: String? = null
)

/**
 * Compatibility envelope used by ReportedProviderParser's existing String
 * callback. ChatActivity wires that callback to the lifecycle recorder bound to
 * the exact HTTP attempt, so terminal metadata never needs a global/current
 * response lookup.
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
    var nonStreamingResponse: Boolean = false,
    var typedChunks: Int = 0,
    var typedContentChunks: Int = 0,
    var typedUsageReceived: Boolean = false,
    var rawObservation: RawStreamObservation? = null
) {
    fun snapshot(): LifecycleDiagnosticEvidence = copy(rawObservation = rawObservation?.copy())
}

/**
 * The response observer runs on a different coroutine from the typed stream.
 * Every HTTP generation attempt gets its own opaque attempt id and therefore its
 * own evidence slot, even when a retry reuses the same turn id and phase.
 *
 * [takeAndClose] is the ownership boundary: it atomically removes the attempt's
 * slot and snapshots it. Any observer callback that arrives after that point is
 * rejected instead of recreating a bucket that a later retry could consume.
 */
internal object LifecycleDiagnosticEvidenceStore {
    private val records = ConcurrentHashMap<String, LifecycleDiagnosticEvidence>()

    fun open(attemptId: String) {
        require(attemptId.isNotBlank()) { "Lifecycle attempt id must not be blank" }
        check(records.putIfAbsent(attemptId, LifecycleDiagnosticEvidence()) == null) {
            "Lifecycle attempt already open: $attemptId"
        }
    }

    fun isOpen(attemptId: String): Boolean = records.containsKey(attemptId)

    private fun mutate(
        attemptId: String,
        block: (LifecycleDiagnosticEvidence) -> Unit
    ): Boolean {
        val value = records[attemptId] ?: return false
        return synchronized(value) {
            // The slot may have been atomically closed after the initial lookup.
            // Never mutate an orphaned object and report that evidence accepted.
            if (records[attemptId] !== value) {
                false
            } else {
                block(value)
                true
            }
        }
    }

    fun noteTypedChunk(
        attemptId: String,
        contentLength: Int,
        usageReceived: Boolean
    ): Boolean = mutate(attemptId) {
        it.requestDispatchedObserved = true
        it.typedChunks++
        if (contentLength > 0) it.typedContentChunks++
        if (usageReceived) it.typedUsageReceived = true
    }

    fun noteSuccessfulHttpResponse(attemptId: String): Boolean = mutate(attemptId) {
        it.requestDispatchedObserved = true
        it.httpSuccessful = true
    }

    fun noteNonStreamingResponse(attemptId: String): Boolean = mutate(attemptId) {
        it.requestDispatchedObserved = true
        it.httpSuccessful = true
        it.nonStreamingResponse = true
    }

    fun noteRawObservation(
        attemptId: String,
        observation: RawStreamObservation
    ): Boolean = mutate(attemptId) {
        it.requestDispatchedObserved = true
        it.httpSuccessful = true
        it.rawObservation = observation
    }

    fun takeAndClose(attemptId: String): LifecycleDiagnosticEvidence? {
        val value = records[attemptId] ?: return null
        return synchronized(value) {
            if (!records.remove(attemptId, value)) {
                null
            } else {
                value.snapshot()
            }
        }
    }
}
