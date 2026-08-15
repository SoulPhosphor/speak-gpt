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
 * Response Lifecycle diagnostics — the temporary, opt-in record of how each
 * user-visible AI reply ended. One entry is written per actual streamed
 * generation request (never one combined entry for a whole multi-step turn),
 * so a completed stream and the interrupted stream that follows it are two
 * separate, comparable records that share a turn id.
 *
 * This file holds ONLY the pure data and formatting/classification logic so it
 * can be unit-tested without a device. The capture itself lives at the streaming
 * sites in the chat screen; the durable write lives in [Logger].
 *
 * Three fields are deliberately recorded as unavailable/not reported rather than
 * guessed, because the app's OpenAI client library does not surface them:
 *   - receivedDone: the protocol-level end-of-stream marker (e.g. SSE "[DONE]").
 *     The library consumes it internally and never exposes whether it arrived,
 *     so this is logged as "unavailable" — NOT redefined to mean "a finish
 *     reason arrived" or "the stream looked done", which are separate facts
 *     captured independently (finishReason / streamClosed / terminationSource).
 *   - reasoningTokens and providerCost: provider extensions (e.g. OpenRouter)
 *     that the typed usage model does not carry, so they read "not reported".
 * Surfacing any of the three truthfully would require reading the raw response
 * stream ourselves; until that exists these stay honest placeholders.
 */
object ResponseLifecycle {

    // Phases of a single visible turn. Each streamed request is logged under
    // exactly one of these; related requests in one turn share a turn id.
    const val PHASE_PRIMARY = "primary"
    const val PHASE_TOOL_CONTINUATION = "tool_continuation"
    // Defined for a future "continue generating" control. No such path exists
    // in the app today (regenerate is simply a fresh primary turn), so nothing
    // writes this value yet.
    const val PHASE_MANUAL_CONTINUE = "manual_continue"

    // The fixed reading for the protocol end-of-stream marker (see the file
    // header). Kept as a constant so the reason it is not true/false is in one
    // place.
    const val RECEIVED_DONE_UNAVAILABLE = "unavailable"

    // Fields the current client library cannot surface.
    const val NOT_REPORTED = "not reported"
    const val NOT_REPORTED_BY_API = "not reported by API"
    const val NONE_REPORTED = "none reported"

    /** The terminal outcomes. [INCOMPLETE] and [EMPTY] are shown in red. */
    enum class Outcome(val display: String) {
        COMPLETE("Complete"),
        INCOMPLETE("Incomplete"),
        // The stream ended on its own but delivered no visible text at all —
        // the provider returned nothing. Its own outcome (not Complete or
        // Incomplete) so an empty answer is unmistakable, and shown in red.
        EMPTY("Empty"),
        STOPPED("Stopped"),
        CANCELLED("Cancelled")
    }

    /** Why the stream ended, in the owner-specified vocabulary. */
    enum class Termination(val wire: String) {
        PROVIDER_DONE("provider_done"),
        STREAM_CLOSED("stream_closed"),
        PROVIDER_ERROR("provider_error"),
        NETWORK_ERROR("network_error"),
        PARSER_ERROR("parser_error"),
        CLIENT_TIMEOUT("client_timeout"),
        USER_STOP("user_stop"),
        APP_CANCEL("app_cancel"),
        // The generation attempt existed and a visible assistant row was
        // created, but the provider request had NOT begun dispatch/collection
        // when the attempt ended — a failure or a non-user, non-teardown
        // cancellation during request construction. Names only the fact we
        // know (nothing was sent), so a purely local pre-dispatch end is never
        // attributed to the provider, the network, the parser, or a closed
        // stream, and is never written to the Provider Failure Log.
        REQUEST_NOT_SENT("request_not_sent")
    }

    /**
     * The terminal decision for a stream that ended on its own (the library's
     * flow completed without throwing). This is the ONLY case decided purely
     * from the recorded finish reason; error, stop and cancel cases are decided
     * by the caller from the failure it caught and are passed to [format]
     * directly.
     *
     *  - no visible text at all -> Empty: the provider returned nothing. This
     *    wins over every finish reason below (a "stop" that delivered zero
     *    characters is still an empty answer), EXCEPT a tool-call handoff,
     *    which legitimately has no text and is not an empty reply.
     *  - no finish reason seen  -> Incomplete, the stream just closed. The reply
     *    is NOT treated as complete merely because text arrived or the callback
     *    ended.
     *  - finish reason "length" -> Incomplete: the answer was truncated even
     *    though the provider ended the stream normally.
     *  - any other finish reason ("stop", "tool_calls", …) -> Complete.
     */
    data class NormalResult(
        val outcome: Outcome,
        val termination: Termination,
        val finishReasonDisplay: String,
        val streamClosed: Boolean
    )

    fun classifyNormalCompletion(lastFinishReason: String?, receivedCharacters: Int): NormalResult {
        val fr = lastFinishReason?.trim()?.ifBlank { null }
        // A tool-call finish carries no visible text on purpose (the model
        // called a tool instead of answering), so it is never "empty".
        val isToolCallFinish = fr != null && fr.equals("tool_calls", ignoreCase = true)
        return when {
            receivedCharacters <= 0 && !isToolCallFinish -> {
                // Nothing reached the user. The termination source still records
                // whether the provider ended cleanly or the stream just closed.
                val termination = if (fr == null) Termination.STREAM_CLOSED else Termination.PROVIDER_DONE
                NormalResult(Outcome.EMPTY, termination, fr ?: "missing", streamClosed = fr == null)
            }
            fr == null -> NormalResult(Outcome.INCOMPLETE, Termination.STREAM_CLOSED, "missing", streamClosed = true)
            fr.equals("length", ignoreCase = true) ->
                NormalResult(Outcome.INCOMPLETE, Termination.PROVIDER_DONE, fr, streamClosed = false)
            else -> NormalResult(Outcome.COMPLETE, Termination.PROVIDER_DONE, fr, streamClosed = false)
        }
    }

    /**
     * Build the entry body — every line AFTER the "[timestamp]" header, which
     * [Logger] prepends. Labels and order match the owner's specified format
     * exactly. A null numeric field is rendered as the honest placeholder rather
     * than as 0.
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
        fun num(v: Int?): String = v?.toString() ?: NOT_REPORTED
        val error = errorText?.trim()?.ifBlank { null } ?: NONE_REPORTED
        return buildString {
            append("Turn ID: ").append(turnId).append('\n')
            append("Phase: ").append(phase).append('\n')
            append("Configured API Provider: ").append(apiProvider.ifBlank { NOT_REPORTED }).append('\n')
            append("API Endpoint: ").append(apiEndpoint.ifBlank { NOT_REPORTED }).append('\n')
            append("Actual Model Provider (API response): ")
                .append(actualModelProvider?.trim()?.ifBlank { null } ?: NOT_REPORTED_BY_API)
                .append('\n')
            append("Model: ").append(model.ifBlank { NOT_REPORTED }).append('\n')
            append("Outcome: ").append(outcome.display).append('\n')
            append("Finish Reason: ").append(finishReasonDisplay).append('\n')
            append("Received Done: ").append(RECEIVED_DONE_UNAVAILABLE).append('\n')
            append("Stream Closed: ").append(streamClosed).append('\n')
            append("Termination Source: ").append(termination.wire).append('\n')
            append("Requested Max Output: ").append(num(requestedMaxOutput)).append('\n')
            append("Prompt Tokens: ").append(num(promptTokens)).append('\n')
            append("Completion Tokens: ").append(num(completionTokens)).append('\n')
            append("Reasoning Tokens: ").append(NOT_REPORTED).append('\n')
            append("Total Tokens: ").append(num(totalTokens)).append('\n')
            append("Provider Cost: ").append(NOT_REPORTED).append('\n')
            append("Received Characters: ").append(receivedCharacters).append('\n')
            append("Duration: ").append(durationMs).append(" ms").append('\n')
            append("Generation ID: ").append(generationId?.trim()?.ifBlank { null } ?: NOT_REPORTED).append('\n')
            append("Error: ").append(error)
        }
    }
}

/**
 * Accumulates the observable facts of one streamed generation request as its
 * chunks arrive, then hands them to [ResponseLifecycle.format] at finalization.
 * A recorder is finalized exactly once; [finalized] guards the streaming sites'
 * success path and the shared error/cancel funnel from double-writing.
 *
 * Most fields are confined to the generation coroutine. The actual provider is
 * the one exception: Ktor's split-response observer fills it from the API's raw
 * response stream while the normal typed stream continues unchanged.
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

    /**
     * Record one chunk. The finish reason is kept as the LAST one seen (the
     * parser keeps consuming after a finish reason, since usage can arrive in a
     * later chunk), the generation id as the FIRST seen, and received characters
     * as the running length of the visible text the app actually obtained —
     * which is compared against the provider's completion-token count.
     */
    fun noteChunk(
        finishReason: String?,
        id: String?,
        contentLength: Int,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?
    ) {
        finishReason?.trim()?.ifBlank { null }?.let { lastFinishReason = it }
        if (generationId == null) id?.trim()?.ifBlank { null }?.let { generationId = it }
        if (contentLength > 0) receivedCharacters += contentLength
        promptTokens?.let { this.promptTokens = it }
        completionTokens?.let { this.completionTokens = it }
        totalTokens?.let { this.totalTokens = it }
    }

    fun beginProviderObservation() {
        providerObservationExpected = true
    }

    fun noteActualModelProvider(value: String?) {
        value?.trim()?.ifBlank { null }?.let { actualModelProvider = it }
    }

    fun finishProviderObservation() {
        providerObservationFinished.complete(Unit)
    }

    /** Close the small observer/main-stream scheduling race on very short replies. */
    suspend fun awaitProviderObservation(timeoutMs: Long) {
        if (!providerObservationExpected || providerObservationFinished.isCompleted) return
        withTimeoutOrNull(timeoutMs) { providerObservationFinished.await() }
    }

    fun markFinalized() { finalized = true }
}
