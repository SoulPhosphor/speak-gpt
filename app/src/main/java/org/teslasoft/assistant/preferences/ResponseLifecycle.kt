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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Raised from the typed chunk path when OpenRouter's unified mid-stream error
 * chunk reaches the OpenAI-compatible client as finish_reason="error".
 *
 * The top-level `error` object is not represented by aallam's typed
 * ChatCompletionChunk model, but OpenRouter guarantees this terminal choice on
 * its Chat Completions mid-stream error shape. Throwing here keeps the existing
 * ChatActivity error/UI path in charge instead of letting a provider error fall
 * through the normal-success tail and mark the message done.
 */
class ProviderStreamTerminalException : RuntimeException(
    "HTTP/1.1 200 provider SSE stream terminated with finish_reason=error"
)

/**
 * Response Lifecycle diagnostics: an evidence record of how each user-visible
 * streamed generation ended. One entry is written per streamed generation
 * request, so retries/continuations remain separately diagnosable.
 *
 * The typed OpenAI client is not treated as the protocol authority. A typed Flow
 * completing without throwing means only that the APP-side Flow completed. Raw
 * SSE terminal evidence gathered by the split Ktor response observer is recorded
 * separately and is allowed to correct a too-weak typed conclusion.
 */
object ResponseLifecycle {

    const val PHASE_PRIMARY = "primary"
    const val PHASE_TOOL_CONTINUATION = "tool_continuation"
    const val PHASE_MANUAL_CONTINUE = "manual_continue"

    const val RECEIVED_DONE_UNAVAILABLE = "unavailable"
    const val NOT_REPORTED = "not reported"
    const val NOT_REPORTED_BY_API = "not reported by API"
    const val NONE_REPORTED = "none reported"
    private const val NOT_OBSERVED = "not observed"
    private const val NOT_CONFIRMED = "not confirmed"

    enum class Outcome(val display: String) {
        COMPLETE("Complete"),
        INCOMPLETE("Incomplete"),
        /** Reserved for a clean provider completion that intentionally carried no visible text. */
        EMPTY("Empty"),
        STOPPED("Stopped"),
        CANCELLED("Cancelled")
    }

    enum class Termination(val wire: String) {
        PROVIDER_DONE("provider_done"),
        /** Existing generic close/cancellation bucket used by non-normal caller paths. */
        STREAM_CLOSED("stream_closed"),
        /** Typed stream reached EOF without semantic/protocol proof of provider completion. */
        PREMATURE_STREAM_CLOSE("premature_stream_close"),
        PROVIDER_ERROR("provider_error"),
        NETWORK_ERROR("network_error"),
        PARSER_ERROR("parser_error"),
        CLIENT_TIMEOUT("client_timeout"),
        USER_STOP("user_stop"),
        APP_CANCEL("app_cancel"),
        REQUEST_NOT_SENT("request_not_sent")
    }

    data class NormalResult(
        val outcome: Outcome,
        val termination: Termination,
        val finishReasonDisplay: String,
        val streamClosed: Boolean
    )

    /**
     * Classify a typed Flow that returned normally. Missing finish_reason is
     * intentionally checked BEFORE zero-content handling: EOF + zero characters
     * does not prove the provider intentionally generated an empty answer.
     *
     * A clean finish reason does establish provider completion. Only then may a
     * zero-visible-content reply be called Empty. Tool-call handoffs are a normal
     * no-text completion. `length` is a clean but truncated provider completion.
     */
    fun classifyNormalCompletion(lastFinishReason: String?, receivedCharacters: Int): NormalResult {
        val fr = lastFinishReason?.trim()?.ifBlank { null }
        val isToolCallFinish = fr != null && fr.equals("tool_calls", ignoreCase = true)
        return when {
            fr == null -> NormalResult(
                Outcome.INCOMPLETE,
                Termination.PREMATURE_STREAM_CLOSE,
                "missing",
                streamClosed = true
            )
            fr.equals("error", ignoreCase = true) -> NormalResult(
                Outcome.INCOMPLETE,
                Termination.PROVIDER_ERROR,
                fr,
                streamClosed = true
            )
            fr.equals("length", ignoreCase = true) -> NormalResult(
                Outcome.INCOMPLETE,
                Termination.PROVIDER_DONE,
                fr,
                streamClosed = false
            )
            receivedCharacters <= 0 && !isToolCallFinish -> NormalResult(
                Outcome.EMPTY,
                Termination.PROVIDER_DONE,
                fr,
                streamClosed = false
            )
            else -> NormalResult(
                Outcome.COMPLETE,
                Termination.PROVIDER_DONE,
                fr,
                streamClosed = false
            )
        }
    }

    /**
     * Pure failure/cancellation matrix used by regression tests and by callers
     * that have explicit terminal facts. Strong intentional/local causes win
     * before provider/transport causes; pre-dispatch is never attributed to a
     * provider or network.
     */
    fun classifyTerminalFailure(
        requestDispatched: Boolean,
        userStop: Boolean = false,
        appCancel: Boolean = false,
        providerError: Boolean = false,
        networkError: Boolean = false,
        parserError: Boolean = false,
        clientTimeout: Boolean = false
    ): Termination = when {
        userStop -> Termination.USER_STOP
        appCancel -> Termination.APP_CANCEL
        !requestDispatched -> Termination.REQUEST_NOT_SENT
        providerError -> Termination.PROVIDER_ERROR
        clientTimeout -> Termination.CLIENT_TIMEOUT
        parserError -> Termination.PARSER_ERROR
        networkError -> Termination.NETWORK_ERROR
        else -> Termination.STREAM_CLOSED
    }

    private fun boolCount(value: Boolean, count: Int): String = "$value ($count)"

    /**
     * Build one lifecycle entry. Raw/typed diagnostic counters are retrieved from
     * the recorder's cross-coroutine evidence store using turn+phase; no response
     * content is stored or logged.
     *
     * Raw evidence can strengthen a normal typed completion. For example, an SSE
     * error event received over HTTP 200 is provider_error even if the typed Flow
     * merely reaches EOF. Conversely, a missing finish reason is never upgraded
     * to Complete/Empty merely because EOF was orderly on the client side.
     */
    fun format(
        turnId: String,
        phase: String,
        apiProvider: String,
        apiEndpoint: String,
        actualModelProvider: String?,
        model: String,
        outcome: Outcome,
        finishReasonDisplay: String,
        streamClosed: Boolean,
        termination: Termination,
        requestedMaxOutput: Int?,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        receivedCharacters: Int,
        durationMs: Long,
        generationId: String?,
        errorText: String?
    ): String {
        val evidence = LifecycleDiagnosticEvidenceStore.take(turnId, phase)
        val raw = evidence?.rawObservation

        val suppliedFinish = finishReasonDisplay.trim().ifBlank { "missing" }
        val observedFinish = raw?.finishReason?.trim()?.ifBlank { null }
        val effectiveFinish = when {
            suppliedFinish.equals("missing", ignoreCase = true) && observedFinish != null -> observedFinish
            suppliedFinish.equals("error", ignoreCase = true) && observedFinish != null -> observedFinish
            else -> suppliedFinish
        }

        var finalOutcome = outcome
        var finalTermination = termination
        var finalFinish = effectiveFinish
        var finalStreamClosed = streamClosed
        var finalError = errorText?.trim()?.ifBlank { null }

        // ChatActivity historically classifies timeout/parser catches from only
        // the top throwable class/message. If Ktor wraps the real cause, that
        // local check can fall through to NETWORK_ERROR even though the error
        // text still contains clear client-side parser/timeout evidence. Refine
        // only that weak fallback here; never rewrite an explicit provider,
        // user, app, pre-dispatch, or already-specific terminal cause.
        if (finalTermination == Termination.NETWORK_ERROR && finalError != null) {
            val diagnostic = finalError.lowercase()
            finalTermination = when {
                diagnostic.contains("timeout") -> Termination.CLIENT_TIMEOUT
                diagnostic.contains("serialization") ||
                    diagnostic.contains("jsondecodingexception") ||
                    diagnostic.contains("notransformationfoundexception") ||
                    diagnostic.contains("expected response body") -> Termination.PARSER_ERROR
                else -> finalTermination
            }
        }

        if (raw?.providerErrorReceived == true) {
            finalOutcome = Outcome.INCOMPLETE
            finalTermination = Termination.PROVIDER_ERROR
            finalFinish = observedFinish ?: "error"
            finalStreamClosed = true
            val rawSummary = raw.providerErrorSummary ?: "provider error event received in SSE stream"
            finalError = when {
                finalError == null -> rawSummary
                raw.providerErrorSummary != null && !finalError.contains(raw.providerErrorSummary) ->
                    "$finalError | SSE: ${raw.providerErrorSummary}"
                else -> finalError
            }
        } else if (finalError == null &&
            termination in setOf(Termination.PROVIDER_DONE, Termination.STREAM_CLOSED, Termination.PREMATURE_STREAM_CLOSE)
        ) {
            // Success-path classification may have run a few scheduling ticks
            // before the split raw observer reached its final event. Reconcile
            // after ChatActivity's bounded await, when format() is called.
            val finishForClassification = observedFinish ?: suppliedFinish.takeUnless {
                it.equals("missing", ignoreCase = true) || it.equals("error", ignoreCase = true)
            }
            val normal = classifyNormalCompletion(finishForClassification, receivedCharacters)
            finalOutcome = normal.outcome
            finalTermination = normal.termination
            finalFinish = normal.finishReasonDisplay
            finalStreamClosed = normal.streamClosed
        }

        if (finalError == null && finalTermination == Termination.PREMATURE_STREAM_CLOSE) {
            finalError = when {
                raw?.flowException != null -> raw.flowException
                raw != null && raw.flowEndedNormally && !raw.receivedDone ->
                    "stream ended without provider finish_reason or protocol terminal marker"
                raw != null && raw.flowEndedNormally ->
                    "stream ended without provider finish_reason"
                else -> "typed stream ended without provider finish_reason"
            }
        }

        val requestDispatched = when {
            evidence?.requestDispatchedObserved == true -> "true"
            finalTermination == Termination.REQUEST_NOT_SENT -> "false"
            finalTermination in setOf(
                Termination.PROVIDER_DONE,
                Termination.PREMATURE_STREAM_CLOSE,
                Termination.PROVIDER_ERROR,
                Termination.NETWORK_ERROR,
                Termination.PARSER_ERROR,
                Termination.CLIENT_TIMEOUT
            ) -> "true"
            else -> NOT_CONFIRMED
        }

        val httpSuccessful = when {
            evidence?.httpSuccessful == true -> "true"
            finalTermination == Termination.PROVIDER_ERROR && raw == null -> "false"
            finalTermination == Termination.REQUEST_NOT_SENT -> NOT_OBSERVED
            else -> NOT_OBSERVED
        }

        val rawSseEvents = raw?.sseDataEvents ?: 0
        val rawContentChunks = raw?.rawContentChunks ?: 0
        val typedChunks = evidence?.typedChunks ?: 0
        val typedContentChunks = evidence?.typedContentChunks ?: 0
        val anyContentChunks = typedContentChunks > 0 || rawContentChunks > 0
        val usageReceived = evidence?.typedUsageReceived == true || raw?.usageReceived == true
        val receivedDoneDisplay = when {
            raw == null -> RECEIVED_DONE_UNAVAILABLE
            raw.receivedDone -> "true"
            else -> "false"
        }
        val rawFlowEnd = when {
            raw == null -> NOT_OBSERVED
            raw.flowEndedNormally -> "normal"
            else -> "exception"
        }

        fun num(v: Int?): String = v?.toString() ?: NOT_REPORTED
        val error = finalError ?: NONE_REPORTED
        return buildString {
            append("Turn ID: ").append(turnId).append('\n')
            append("Phase: ").append(phase).append('\n')
            append("Configured API Provider: ").append(apiProvider.ifBlank { NOT_REPORTED }).append('\n')
            append("API Endpoint: ").append(apiEndpoint.ifBlank { NOT_REPORTED }).append('\n')
            append("Actual Model Provider (API response): ")
                .append(actualModelProvider?.trim()?.ifBlank { null } ?: NOT_REPORTED_BY_API)
                .append('\n')
            append("Model: ").append(model.ifBlank { NOT_REPORTED }).append('\n')
            append("Request Dispatched: ").append(requestDispatched).append('\n')
            append("HTTP Status Successful: ").append(httpSuccessful).append('\n')
            append("Outcome: ").append(finalOutcome.display).append('\n')
            append("Finish Reason: ").append(finalFinish).append('\n')
            append("Finish Reason Received: ").append(!finalFinish.equals("missing", ignoreCase = true)).append('\n')
            append("Received Done: ").append(receivedDoneDisplay).append('\n')
            append("Protocol Terminal Marker: ")
                .append(raw?.protocolTerminalMarker ?: if (raw == null) RECEIVED_DONE_UNAVAILABLE else "missing")
                .append('\n')
            append("Stream Closed: ").append(finalStreamClosed).append('\n')
            append("Termination Source: ").append(finalTermination.wire).append('\n')
            append("Raw SSE Events Received: ").append(boolCount(rawSseEvents > 0, rawSseEvents)).append('\n')
            append("Typed Chunks Received: ").append(boolCount(typedChunks > 0, typedChunks)).append('\n')
            append("Content Chunks Received: ").append(anyContentChunks)
                .append(" (typed=").append(typedContentChunks)
                .append(", raw=").append(rawContentChunks).append(")\n")
            append("Provider SSE Error Received: ").append(raw?.providerErrorReceived ?: false).append('\n')
            append("Usage Metadata Received: ").append(usageReceived).append('\n')
            append("Raw SSE Flow End: ").append(rawFlowEnd).append('\n')
            append("Raw SSE Flow Exception: ").append(raw?.flowException ?: NONE_REPORTED).append('\n')
            append("Malformed Raw SSE Data Events: ").append(raw?.malformedDataEvents ?: 0).append('\n')
            append("Requested Max Output: ").append(num(requestedMaxOutput)).append('\n')
            append("Prompt Tokens: ").append(num(promptTokens)).append('\n')
            append("Completion Tokens: ").append(num(completionTokens)).append('\n')
            append("Reasoning Tokens: ").append(NOT_REPORTED).append('\n')
            append("Total Tokens: ").append(num(totalTokens)).append('\n')
            append("Provider Cost: ").append(NOT_REPORTED).append('\n')
            append("Received Characters: ").append(receivedCharacters).append('\n')
            append("Duration: ").append(durationMs).append(" ms").append('\n')
            append("Generation ID: ").append(generationId?.trim()?.ifBlank { null } ?: NOT_REPORTED).append('\n')
            append("Generation ID Received: ").append(!generationId.isNullOrBlank()).append('\n')
            append("Error: ").append(error)
        }
    }
}

/**
 * Accumulates observable facts for one streamed generation. Typed chunk facts
 * are kept separately from raw-SSE facts so a parser/client loss does not erase
 * what actually arrived on the wire.
 */
class ResponseLifecycleRecorder(
    val turnId: String,
    val phase: String,
    val apiProvider: String,
    val apiEndpoint: String,
    val model: String,
    val requestedMaxOutput: Int?,
    val startUptimeMs: Long
) {
    @Volatile
    var actualModelProvider: String? = null
        private set
    @Volatile
    private var providerObservationExpected: Boolean = false
    private val providerObservationFinished = CompletableDeferred<Unit>()

    var lastFinishReason: String? = null
        private set
    var generationId: String? = null
        private set
    var promptTokens: Int? = null
        private set
    var completionTokens: Int? = null
        private set
    var totalTokens: Int? = null
        private set
    var receivedCharacters: Int = 0
        private set
    var finalized: Boolean = false
        private set

    fun noteChunk(
        finishReason: String?,
        id: String?,
        contentLength: Int,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?
    ) {
        val normalizedFinish = finishReason?.trim()?.ifBlank { null }
        normalizedFinish?.let { lastFinishReason = it }
        if (generationId == null) id?.trim()?.ifBlank { null }?.let { generationId = it }
        if (contentLength > 0) receivedCharacters += contentLength
        promptTokens?.let { this.promptTokens = it }
        completionTokens?.let { this.completionTokens = it }
        totalTokens?.let { this.totalTokens = it }
        LifecycleDiagnosticEvidenceStore.noteTypedChunk(
            turnId = turnId,
            phase = phase,
            contentLength = contentLength,
            usageReceived = promptTokens != null || completionTokens != null || totalTokens != null
        )

        // OpenRouter's Chat Completions mid-stream provider error is a normal
        // HTTP-200 SSE chunk whose choice terminates with finish_reason="error".
        // aallam ignores the unknown top-level `error` object, so without this
        // explicit terminal signal ChatActivity would continue its success tail.
        if (normalizedFinish.equals("error", ignoreCase = true)) {
            throw ProviderStreamTerminalException()
        }
    }

    /** Called only by ResponseObserver's successful-HTTP branch. */
    fun beginProviderObservation() {
        providerObservationExpected = true
        LifecycleDiagnosticEvidenceStore.noteSuccessfulHttpResponse(turnId, phase)
    }

    /**
     * Existing observer callback. Normal strings are provider names. A private
     * raw-observation envelope is decoded into terminal metadata and is never
     * allowed to become the displayed provider name.
     */
    fun noteActualModelProvider(value: String?) {
        if (RawStreamObservationCodec.isEncoded(value)) {
            val raw = RawStreamObservationCodec.decode(value) ?: return
            raw.finishReason?.trim()?.ifBlank { null }?.let {
                if (lastFinishReason == null) lastFinishReason = it
            }
            raw.generationId?.trim()?.ifBlank { null }?.let {
                if (generationId == null) generationId = it
            }
            raw.promptTokens?.let { if (promptTokens == null) promptTokens = it }
            raw.completionTokens?.let { if (completionTokens == null) completionTokens = it }
            raw.totalTokens?.let { if (totalTokens == null) totalTokens = it }
            LifecycleDiagnosticEvidenceStore.noteRawObservation(turnId, phase, raw)
            return
        }
        value?.trim()?.ifBlank { null }?.let { actualModelProvider = it }
    }

    fun finishProviderObservation() {
        providerObservationFinished.complete(Unit)
    }

    /** Close the observer/main-stream scheduling race on very short replies. */
    suspend fun awaitProviderObservation(timeoutMs: Long) {
        if (!providerObservationExpected || providerObservationFinished.isCompleted) return
        withTimeoutOrNull(timeoutMs) { providerObservationFinished.await() }
    }

    fun markFinalized() { finalized = true }
}
