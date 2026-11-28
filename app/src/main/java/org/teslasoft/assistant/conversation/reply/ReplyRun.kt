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

package org.teslasoft.assistant.conversation.reply

import android.content.Context
import android.util.Log
import com.aallam.ktoken.Encoding
import com.aallam.ktoken.Tokenizer
import com.aallam.openai.api.logging.Logger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.teslasoft.assistant.imagegen.StreamedToolCallAssembler
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.ResponseLifecycle
import org.teslasoft.assistant.preferences.ResponseLifecycleRecorder
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.PromptCachingCatalogEvidence
import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import org.teslasoft.assistant.tts.api.*
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.chat.parallel.*
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.usage.ProviderUsageSnapshot
import org.teslasoft.assistant.usage.TokenCounts
import org.teslasoft.assistant.usage.TokenPricingCatalog
import org.teslasoft.assistant.usage.TokenPricingCatalogClient
import org.teslasoft.assistant.usage.TokenPricingSnapshot
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.TurnUsageRecord
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageFunction
import org.teslasoft.assistant.usage.UsageLog
import org.teslasoft.assistant.usage.UsageLogStore

/**
 * What a reply's bookkeeping needs from the conversation it belongs to: the
 * rows, their storage and the turn's identity. The chat screen provides it
 * while it shows the chat.
 */
internal interface ReplyRunHost {
    /** The model the chat is set to. */
    val model: String
    /** True while the chat's stored history may not be written. */
    val storageUnavailable: Boolean
    /** The conversation's rows as they stand (read-only use). */
    fun conversationSnapshot(): List<Map<String, Any>>
    /** The conversation's last row, or null when it is empty. */
    fun lastRow(): HashMap<String, Any>?
    /** Whether [row] (this exact object) is still in the conversation. */
    fun containsRow(row: Map<String, Any>?): Boolean
    /** Saves the conversation without making the caller wait. */
    fun saveInBackground()
    /** The row a reply's usage is recorded on, chosen before anything waits. */
    fun usageCarrier(): HashMap<String, Any>?
    /** The id shared by this turn's requests in the Response Lifecycle Log. */
    fun lifecycleTurnId(): String
}

/**
 * One reply's usage, pricing and lifecycle bookkeeping: the provider usage
 * attempt and its price lookup, the completed request waiting to be recorded,
 * the Response Lifecycle entry, provider warnings and the usage log. Uses the
 * chat's [ChatRequestState] and [ChatRequestHooks], never the chat screen.
 */
internal class ReplyRun(
    context: Context,
    val state: ChatRequestState,
    val hooks: ChatRequestHooks,
    val fence: org.teslasoft.assistant.ui.chat.ConversationTurnFence,
    var host: ReplyRunHost,
    /** Runs the price lookups for this reply's requests. */
    private val scope: CoroutineScope
) {
    private val app: Context = context.applicationContext

    var pricingCatalog: Deferred<TokenPricingCatalog>? = null
    var pricingCatalogEndpointFingerprint: String? = null
    var pricingCatalogCompletedAtElapsedMs: AtomicLong? = null
    var pendingUsageSnapshot: ProviderUsageSnapshot? = null
    var pendingPricingCatalog: Deferred<TokenPricingCatalog>? = null
    var pendingPricingCatalogEndpointFingerprint: String? = null
    var pendingPricingCatalogCompletedAtElapsedMs: AtomicLong? = null

    /** True once the request reached the provider (the pre-dispatch boundary). */
    @Volatile var dispatched: Boolean = false

    /** The turn's reasoning accumulator and the end of its observation. */
    @Volatile var turnReasoning: org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator? = null
    @Volatile var turnReasoningObserved: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Runs this reply's provider requests in the reply's own scope, never a screen's. */
    private val requests = RequestRelay(
        CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    )

    /**
     * While false, a reader that stops (Stop, or the screen closing) also
     * stops the provider request. Once true, the request continues and its
     * remaining output stays readable from the same relay.
     */
    @Volatile var handedOff: Boolean = false

    /** The current request's reply text as received so far. */
    @Volatile var text: String = ""
        private set

    /** The current request's tool-call pieces as received so far. */
    @Volatile var toolCalls: StreamedToolCallAssembler = StreamedToolCallAssembler()
        private set

    /**
     * Records one piece of the provider's reply: its lifecycle and usage
     * figures, its tool-call pieces and its text, in that order. Throws when
     * the piece ends the reply with a provider error. Called where the
     * request is collected, so the reply stays complete whoever displays it.
     */
    fun accept(piece: ReplyPiece) {
        noteLifecycleChunk(
            piece.finishReason, piece.id, piece.contentLength,
            piece.promptTokens, piece.completionTokens, piece.totalTokens
        )
        piece.toolCalls.forEach { toolCalls.accept(it.index, it.id, it.name, it.arguments) }
        if (piece.whole) {
            text = piece.content.orEmpty()
            state.currentLifecycle?.markNonStreamingResponse()
        } else {
            piece.content?.let { text += it }
        }
    }

    /** Starts a new request phase: no text or tool calls received yet. */
    private fun beginReceiving() {
        text = ""
        toolCalls = StreamedToolCallAssembler()
    }

    /**
     * Runs the provider request [source] through [RequestRelay] with the
     * current request context (which the request hooks read), recording each
     * piece with [accept] as it arrives. The returned flow delivers the same
     * items, after they are recorded, and the request's failure.
     */
    fun <T> relay(source: kotlinx.coroutines.flow.Flow<T>, piece: (T) -> ReplyPiece): kotlinx.coroutines.flow.Flow<T> {
        beginReceiving()
        return requests.relay(
            source.onEach { accept(piece(it)) },
            state.requestContext.asContextElement(state.requestContext.get())
        ) { handedOff }
    }

    /** A completed (non-streaming) provider call, run and recorded like [relay]. */
    suspend fun <T> relayCall(call: suspend () -> T, piece: (T) -> ReplyPiece): T {
        beginReceiving()
        return requests.relayCall(
            { call().also { accept(piece(it)) } },
            state.requestContext.asContextElement(state.requestContext.get())
        ) { handedOff }
    }

    /** One reply's usage and lifecycle state, taken off the screen's fields. */
    internal class ClaimedTerminal(
        val attempt: ProviderUsageAttempt?,
        val pricing: Deferred<TokenPricingCatalog>?,
        val pendingPricing: Deferred<TokenPricingCatalog>?,
        val lifecycle: ResponseLifecycleRecorder?,
        /** A completed request whose usage was not recorded yet (deletion only). */
        val completed: ProviderUsageSnapshot? = null,
        val completedFingerprint: String? = null,
        val completedAtElapsedMs: AtomicLong? = null,
        val completedRows: List<Map<String, Any>>? = null
    )

    /** Begin recording one generation request when Response Lifecycle logging is
     *  on. A still-pending recorder (e.g. the §8 first attempt that is about to
     *  be retried) is closed first so its record is never dropped. */
    internal suspend fun startLifecycle(phase: String, requestedMaxOutput: Int?) {
        // A reply retired by a message deletion never starts another request
        // phase, even if a setup step swallowed its cancellation.
        if (fence.isStale()) throw CancellationException("Retired by message deletion")
        dispatched = false
        org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.shared.awaitSaved(state.chatId)
        if (fence.isStale()) throw CancellationException("Retired by message deletion")
        state.currentLifecycle?.let {
            if (!it.finalized) finalizeLifecycleTerminal(
                ResponseLifecycle.Outcome.INCOMPLETE, "missing", true,
                ResponseLifecycle.Termination.STREAM_CLOSED, "superseded by a new request"
            )
        }
        // Reset for THIS request (after any superseded entry above is written
        // with its own result), so the send hook's actual outcome is recorded
        // fresh and a hook that never runs never reports a stale attachment.
        state.lastRoutingAttachment = null
        state.lastReasoningAttachment = null
        state.lastReasoningInbound = null
        // Reset the dispatch boundary for THIS attempt regardless of whether
        // lifecycle logging is on, because the Provider Failure Log gate also
        // depends on it.
        val usedModel = host.model.ifBlank { state.chatPreferences?.getModel().orEmpty() }
        state.currentUsageAttempt = ProviderUsageAttempt(
            requestedModel = usedModel,
            fallbackProvider = fallbackServingProvider(),
            apiEndpoint = state.endpoint?.host.orEmpty()
        )
        val pricingEndpoint = state.endpoint
        pricingCatalogEndpointFingerprint = hooks.promptCachingEndpointFingerprint(pricingEndpoint)
        val pricingCompletedAtElapsedMs = AtomicLong(-1L)
        pricingCatalogCompletedAtElapsedMs = pricingCompletedAtElapsedMs
        pricingCatalog = scope.async(Dispatchers.IO) {
            TokenPricingCatalogClient.load(pricingEndpoint, usedModel).also {
                pricingCompletedAtElapsedMs.set(android.os.SystemClock.elapsedRealtime())
            }
        }
        if (state.chatPreferences?.getResponseLifecycleLogging() != true) {
            state.currentLifecycle = null
            return
        }
        val endpoint = state.endpoint
        val apiProvider = endpoint?.provider?.trim()?.ifBlank { null }
            ?: endpoint?.label?.trim()?.ifBlank { null }
            ?: endpoint?.host?.trim().orEmpty()
        state.currentLifecycle = ResponseLifecycleRecorder(
            turnId = host.lifecycleTurnId(),
            phase = phase,
            apiProvider = apiProvider,
            apiEndpoint = endpoint?.host ?: "",
            model = host.model,
            requestedMaxOutput = requestedMaxOutput,
            startUptimeMs = android.os.SystemClock.uptimeMillis()
        )
    }

    internal fun noteLifecycleChunk(
        finishReason: String?, id: String?, contentLength: Int,
        promptTokens: Int?, completionTokens: Int?, totalTokens: Int?
    ) {
        val boundContext = state.requestContext.get()
        val attempt = boundContext?.usageAttempt ?: state.currentUsageAttempt
        attempt?.noteTypedUsage(promptTokens, completionTokens, totalTokens)
        attempt?.noteTypedChunk(finishReason, id, contentLength)
        (boundContext?.lifecycle ?: state.currentLifecycle)?.takeUnless { it.finalized }?.noteChunk(
            finishReason, id, contentLength, promptTokens, completionTokens, totalTokens
        )

        // This gate is always active, independent of the optional Response
        // Lifecycle toggle. The thrown exception carries the exact request
        // owner; its raw SSE observer may finish a few scheduling ticks later,
        // and the catch path awaits that same object's evidence.
        val terminal = finishReason?.trim()?.lowercase()
        if (terminal in setOf("error", "content_filter", "safety", "prohibited_content")) {
            throw org.teslasoft.assistant.preferences.ProviderStreamTerminalException(
                attempt = attempt,
                providerFinishReason = terminal ?: "error"
            )
        }
    }

    /** Finalize a response that ended on its own. Streaming uses the finish
     *  reason/terminal evidence matrix; a successful completed-response call
     *  has provider-completion evidence from the client return itself. */
    internal suspend fun finalizeLifecycleSuccess(): ProviderDiagnosticSnapshot? {
        val usageAttempt = state.currentUsageAttempt
        val providerDiagnostics = usageAttempt?.diagnosticSnapshot()
        usageAttempt?.let {
            pendingUsageSnapshot = it.snapshot()
            pendingPricingCatalog = pricingCatalog
            pendingPricingCatalogEndpointFingerprint =
                pricingCatalogEndpointFingerprint
            pendingPricingCatalogCompletedAtElapsedMs =
                pricingCatalogCompletedAtElapsedMs
        }
        state.currentUsageAttempt = null
        pricingCatalog = null
        pricingCatalogEndpointFingerprint = null
        pricingCatalogCompletedAtElapsedMs = null
        val r = state.currentLifecycle ?: return providerDiagnostics
        if (r.finalized) return providerDiagnostics
        val n = if (r.nonStreamingResponse) {
            ResponseLifecycle.classifyNonStreamingCompletion(r.lastFinishReason, r.receivedCharacters)
        } else {
            ResponseLifecycle.classifyNormalCompletion(r.lastFinishReason, r.receivedCharacters)
        }
        writeLifecycle(r, n.outcome, n.finishReasonDisplay, n.streamClosed, n.termination, null)
        return providerDiagnostics
    }

    /** Finalize a response cut short by an error, a user stop, or an app cancel —
     *  the terminal values are decided by the caller from what it caught. */
    internal suspend fun finalizeLifecycleTerminal(
        outcome: ResponseLifecycle.Outcome, finishReasonDisplay: String,
        streamClosed: Boolean, termination: ResponseLifecycle.Termination, errorText: String?,
        /** The turn ends here: a completed request whose usage was not
         *  recorded yet is recorded now instead of being dropped. */
        keepCompletedUsage: Boolean = false
    ) {
        // A message deletion that retired this reply finalized it already.
        if (fence.isStale()) return
        val carrier = host.usageCarrier()
        val attached = completeClaimedTerminal(
            claimTerminalState(keepCompletedUsage), carrier,
            outcome, finishReasonDisplay, streamClosed, termination, errorText
        )
        // Retired by a message deletion while the record was being completed:
        // this reply's own save is skipped, so save the usage it added.
        if (attached && fence.isStale() && host.containsRow(carrier)) host.saveInBackground()
    }

    /**
     * Takes the running reply's usage and lifecycle state before anything can
     * wait, so a reply started while this one finishes keeps its own state.
     */
    internal fun claimTerminalState(keepCompletedUsage: Boolean = false): ClaimedTerminal {
        val completed = if (keepCompletedUsage) pendingUsageSnapshot else null
        val claimed = ClaimedTerminal(
            state.currentUsageAttempt, pricingCatalog,
            pendingPricingCatalog, state.currentLifecycle,
            completed = completed,
            completedFingerprint = if (completed != null) pendingPricingCatalogEndpointFingerprint else null,
            completedAtElapsedMs = if (completed != null) pendingPricingCatalogCompletedAtElapsedMs else null,
            completedRows = if (completed != null && !completed.counts.hasAnyValue()) host.conversationSnapshot().map { HashMap(it) } else null
        )
        state.currentUsageAttempt = null
        pricingCatalog = null
        pricingCatalogEndpointFingerprint = null
        pricingCatalogCompletedAtElapsedMs = null
        pendingUsageSnapshot = null
        pendingPricingCatalog = null
        pendingPricingCatalogEndpointFingerprint = null
        pendingPricingCatalogCompletedAtElapsedMs = null
        state.currentLifecycle = null
        return claimed
    }

    /**
     * Records the claimed reply's usage on [carrier] and writes its lifecycle
     * entry. A carrier deleted meanwhile still has its usage added to the
     * chat's usage log (attachUsageRecords writes the log first).
     */
    internal suspend fun completeClaimedTerminal(
        claimed: ClaimedTerminal,
        carrier: HashMap<String, Any>?,
        outcome: ResponseLifecycle.Outcome, finishReasonDisplay: String,
        streamClosed: Boolean, termination: ResponseLifecycle.Termination, errorText: String?
    ): Boolean = org.teslasoft.assistant.ui.chat.ReplyUsageCompletion.finish {
        val records = run {
            // A completed request the deletion caught before its usage was
            // recorded keeps its own price wait (it owns pendingPricing then).
            val completedRecord = claimed.completed?.let {
                completeUsageRecordFrom(
                    it, claimed.pendingPricing, claimed.completedFingerprint,
                    claimed.completedAtElapsedMs, claimed.completedRows, updateCachingEvidence = false
                )
            }
            listOfNotNull(completedRecord, completeTerminalUsageRecord(claimed.attempt, claimed.pricing))
        }
        claimed.pricing?.cancel()
        claimed.pendingPricing?.cancel()
        var attached = false
        if (records.isNotEmpty()) {
            carrier?.let {
                attachUsageRecords(it, records, it["isBot"] == true)
                mirrorUsageRecordsToCanonicalVariant(it)
                attached = true
                // The append can fail even when the row survives. Retain the
                // receipt independently of both the cancelled caller and disk.
                keepUsageOfDeletedRows(listOf(HashMap(it)))
            }
        }
        val r = claimed.lifecycle
        if (r != null && !r.finalized) {
            writeLifecycle(r, outcome, finishReasonDisplay, streamClosed, termination, errorText)
        }
        attached
    }

    /** A reply with versions is totalled from its version list, and its
     * top-level fields mirror the canonical version. A record added to the
     * top level must therefore also be written into that version, or it would
     * be left out of the totals and lost when another version is shown. */
    internal fun mirrorUsageRecordsToCanonicalVariant(message: HashMap<String, Any>) {
        val variants = ChatAdapter.parseVariants(message[ChatAdapter.KEY_VARIANTS]?.toString())
        if (variants.isEmpty()) return
        val canonical = (message[ChatAdapter.KEY_CANONICAL_VARIANT]?.toString()?.toIntOrNull()
            ?: variants.lastIndex).coerceIn(0, variants.lastIndex)
        val records = message[ChatAdapter.KEY_TOKEN_USAGE_RECORDS]?.toString() ?: return
        variants[canonical][ChatAdapter.KEY_TOKEN_USAGE_RECORDS] = records
        message[ChatAdapter.KEY_VARIANTS] = ChatAdapter.variantsToJson(variants)
    }

    /** A stopped or failed request counts only when the serving API actually
     * returned usage or charged-cost evidence. Never estimate an interrupted
     * request, but never discard a charge merely because no final reply is
     * visible in chat. */
    internal suspend fun completeTerminalUsageRecord(
        attempt: ProviderUsageAttempt?,
        pricingDeferred: Deferred<TokenPricingCatalog>?
    ): TurnUsageRecord? {
        val snapshot = attempt?.snapshot() ?: return null
        if (!snapshot.counts.hasAnyValue() && !snapshot.providerCost.hasAnyValue()) return null
        val catalog = org.teslasoft.assistant.ui.chat.ReplyUsageCompletion.awaitPricing(pricingDeferred, 500L)
        val pricing = catalog
            ?.takeIf { it.model.equals(snapshot.model, ignoreCase = true) }
            ?.pricingFor(snapshot.provider)
            ?: TokenPricingSnapshot()
        return TokenUsageAccounting.createRecord(
            model = snapshot.model,
            provider = snapshot.provider,
            apiEndpoint = snapshot.apiEndpoint,
            counts = snapshot.counts.withDerivedTotal(),
            source = org.teslasoft.assistant.usage.TokenCountSource.PROVIDER_REPORTED,
            pricing = pricing,
            providerCost = snapshot.providerCost
        ).copy(requestId = snapshot.attemptId)
    }

    /** Surface non-fatal provider warnings without contaminating model text. */
    internal fun applyProviderWarnings(snapshot: ProviderDiagnosticSnapshot?) {
        val warnings = snapshot?.warningMessages.orEmpty()
        if (warnings.isEmpty()) return
        val row = host.lastRow() ?: return
        if (row["isBot"] != true) return
        val exact = warnings.joinToString("\n")
        row[MessageCompletionState.KEY_PROVIDER_WARNING_TEXT] = exact
        try {
            val body = StringBuilder("Non-fatal provider warning\n")
            appendProviderEvidence(body, snapshot)
            org.teslasoft.assistant.preferences.Logger.log(
                app, "crash", "GenWarning", "warning", body.toString()
            )
        } catch (_: Throwable) { /* diagnostics must not alter generation */ }
    }

    /** Request intent only; never substituted for the response-derived server. */
    internal fun requestedRoutedProviderForDiagnostics(modelId: String): String? {
        if (state.endpoint?.isOpenRouterRouting() != true) return null
        val favorite = hooks.favorite(modelId)
        return when (favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC) {
            FavoriteModelObject.ROUTING_ONLY -> favorite?.selectedProvider?.trim()?.ifBlank { null }
            FavoriteModelObject.ROUTING_PREFERRED -> favorite?.providerOrder
                ?.filter { it.isNotBlank() }?.joinToString(", ")?.ifBlank { null }
            // Automatic routing requests no particular provider. Reporting a
            // placeholder here would read like a provider name and would hide
            // the fact that the response-derived serving provider is the only
            // provider fact that exists for this attempt.
            else -> null
        }
    }

    /** Rich technical facts shared by Error Log errors and warnings. */
    internal fun appendProviderEvidence(
        out: StringBuilder,
        snapshot: ProviderDiagnosticSnapshot?
    ) {
        val notReported = "Not Reported"
        out.append("Attempt ID: ").append(snapshot?.attemptId ?: notReported).append('\n')
        out.append("Configured API Provider: ")
            .append(state.endpoint?.label?.trim()?.ifBlank { null }
                ?: state.endpoint?.host?.trim()?.ifBlank { null } ?: notReported).append('\n')
        // Only present when a provider was actually requested; automatic routing
        // asks for none, and an absent line is truthful where a placeholder is not.
        requestedRoutedProviderForDiagnostics(host.model)?.let {
            out.append("Requested/Routed Model Provider: ").append(it).append('\n')
        }
        out.append("Actual Serving Model Provider: ")
            .append(snapshot?.actualServingProvider ?: notReported).append('\n')
        out.append("Outer HTTP Status: ").append(snapshot?.outerHttpStatus ?: notReported).append('\n')
        out.append("Embedded Provider Status: ")
            .append(snapshot?.embeddedHttpStatus ?: notReported).append('\n')
        out.append("Provider Code: ").append(snapshot?.providerCode ?: notReported).append('\n')
        out.append("Provider Type: ").append(snapshot?.providerType ?: notReported).append('\n')
        out.append("Provider Error Type: ")
            .append(snapshot?.providerErrorType ?: notReported).append('\n')
        out.append("Content Filter Side: ")
            .append(snapshot?.contentFilterSide?.wire ?: notReported).append('\n')
        out.append("Finish Reason: ").append(snapshot?.finishReason ?: notReported).append('\n')
        out.append("Generation ID: ").append(snapshot?.generationId ?: notReported).append('\n')
        out.append("Partial Content Characters: ")
            .append(snapshot?.partialContentCharacters ?: 0).append('\n')
        out.append("Reasoning Characters Received: ")
            .append(snapshot?.reasoningCharacters ?: 0).append('\n')
        out.append("Prompt Tokens: ").append(snapshot?.promptTokens ?: notReported).append('\n')
        out.append("Completion Tokens: ").append(snapshot?.completionTokens ?: notReported).append('\n')
        out.append("Total Tokens: ").append(snapshot?.totalTokens ?: notReported).append('\n')
        out.append("Malformed Provider Payloads: ")
            .append(snapshot?.malformedPayloadCount ?: 0).append('\n')
        // Field NAMES only. Paired with the provider's own rejection message,
        // this is what identifies which parameter a request was refused over.
        out.append("Outbound Request Fields: ")
            .append(
                snapshot?.outboundFieldNames?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: notReported
            ).append('\n')
        val messages = snapshot?.errorMessages.orEmpty()
        out.append("Provider Message: ")
            .append(messages.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: notReported)
        snapshot?.events?.mapNotNull { it.rawPayload }?.distinct()?.takeIf { it.isNotEmpty() }?.let {
            out.append("\nRaw Provider Payload(s):\n").append(it.joinToString("\n---\n"))
        }
    }

    internal suspend fun writeLifecycle(
        r: ResponseLifecycleRecorder, outcome: ResponseLifecycle.Outcome,
        finishReasonDisplay: String, streamClosed: Boolean,
        termination: ResponseLifecycle.Termination, errorText: String?
    ) {
        r.markFinalized()
        val durationMs = android.os.SystemClock.uptimeMillis() - r.startUptimeMs
        // The response observer usually records the provider from the first SSE
        // chunk. This bounded wait only closes a scheduling race on extremely
        // short replies, and applies solely while this opt-in log is active.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            r.awaitProviderObservation(250L)
            // Let this turn's reasoning observation finish draining so the entry
            // can report inbound reasoning evidence, not just outbound intent.
            // Bounded so a stalled observer never hangs finalization; skipped
            // when no reasoning observation ran.
            if (state.reasoningObservationActive) {
                kotlinx.coroutines.withTimeoutOrNull(250L) {
                    turnReasoningObserved?.await()
                }
            }
        }
        val body = ResponseLifecycle.format(
            turnId = r.turnId, phase = r.phase, apiProvider = r.apiProvider,
            apiEndpoint = r.apiEndpoint, actualModelProvider = r.actualModelProvider,
            model = r.model,
            outcome = outcome, finishReasonDisplay = finishReasonDisplay,
            streamClosed = streamClosed, termination = termination,
            requestedMaxOutput = r.requestedMaxOutput, promptTokens = r.promptTokens,
            completionTokens = r.completionTokens, totalTokens = r.totalTokens,
            receivedCharacters = r.receivedCharacters, durationMs = durationMs,
            generationId = r.generationId, errorText = errorText,
            attemptId = r.attemptId
        ) + hooks.providerRoutingLogLine(r.model) + hooks.reasoningLogLine()
        if (state.currentLifecycle === r) state.currentLifecycle = null
        org.teslasoft.assistant.preferences.Logger.logResponseLifecycleAsync(app, body)
    }

    /**
     * Completes and attaches usage that [take] removes from the screen's
     * pending state, without letting a cancellation drop it half-way. A
     * carrier deleted meanwhile still has the usage kept in the usage log;
     * a reply retired by a message deletion then stops here.
     */
    internal suspend fun recordTakenUsageSafely(
        carrier: HashMap<String, Any>,
        mirrorCompactMetadata: Boolean = true,
        take: suspend () -> List<TurnUsageRecord>
    ) {
        org.teslasoft.assistant.ui.chat.ReplyUsageCompletion.finish {
            val records = take()
            attachUsageRecords(carrier, records, mirrorCompactMetadata)
            if (records.isNotEmpty()) {
                mirrorUsageRecordsToCanonicalVariant(carrier)
                if (fence.isStale() || !host.containsRow(carrier))
                    keepUsageOfDeletedRows(listOf(HashMap(carrier)))
                // Deletion already saved its earlier snapshot, and the stale
                // completion will not reach its normal save below.
                if (fence.isStale() && host.containsRow(carrier)) host.saveInBackground()
            }
        }
        if (fence.isStale()) throw CancellationException("Retired by message deletion")
    }

    /** Attach only completed requests. Usage-only tool calls use the initiating
     * user message as a durable carrier; visible completions use their assistant
     * message and may mirror compact display metadata. */
    internal suspend fun attachUsageRecords(
        message: HashMap<String, Any>,
        newRecords: List<TurnUsageRecord>,
        mirrorCompactMetadata: Boolean = true
    ) {
        if (newRecords.isEmpty()) return
        // The chat's usage log is the lasting account: deleting or
        // regenerating this message later never removes what was spent.
        appendUsageLog(
            UsageCategory.CHAT,
            org.teslasoft.assistant.preferences.MessageIdentity.idOf(message),
            newRecords
        )
        val records = TokenUsageAccounting.decodeRecords(
            message[ChatAdapter.KEY_TOKEN_USAGE_RECORDS]?.toString()
        ) + newRecords
        message[ChatAdapter.KEY_TOKEN_USAGE_RECORDS] =
            TokenUsageAccounting.encodeRecords(records)
        if (!mirrorCompactMetadata) return
        // Mirror the final completed request's response-reported attribution
        // into the compact metadata. Unknown remains absent.
        records.last().model.takeIf { it != TokenUsageAccounting.MODEL_NOT_REPORTED }
            ?.let { message[ChatAdapter.KEY_MESSAGE_MODEL] = it }
        records.last().provider.takeIf { it != TokenUsageAccounting.PROVIDER_NOT_REPORTED }
            ?.let { message[ChatAdapter.KEY_MESSAGE_PROVIDER] = it }
        // Provider request accounting stays in tokenUsageRecords. The message
        // UI estimates only this bubble's visible text and must not mirror a
        // whole-request or provider-output count into responseTokens.
        message.remove(ChatAdapter.KEY_MESSAGE_TOKENS)
    }

    /** Adds requests to this chat's usage log. Seeding first also repairs any
     * older message-backed request that a previous append failed to persist. */
    internal suspend fun appendUsageLog(
        category: UsageCategory,
        messageId: String?,
        records: List<TurnUsageRecord>,
        function: UsageFunction? = null
    ) {
        val prefs = state.chatPreferences ?: return
        if (records.isEmpty() || host.storageUnavailable) return
        val snapshot = host.conversationSnapshot().map { HashMap(it) }
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            UsageLogStore.seed(prefs, snapshot)
            UsageLogStore.append(
                prefs,
                records.map { UsageLog.entry(category, messageId?.ifBlank { null }, it, function = function) }
            )
        }
    }

    internal suspend fun completePendingUsageRecord(): TurnUsageRecord? {
        val snapshot = pendingUsageSnapshot ?: return null
        pendingUsageSnapshot = null
        val pricingDeferred = pendingPricingCatalog
        pendingPricingCatalog = null
        val pricingEndpointFingerprint = pendingPricingCatalogEndpointFingerprint
        pendingPricingCatalogEndpointFingerprint = null
        val pricingCompletedAtElapsedMs = pendingPricingCatalogCompletedAtElapsedMs
        pendingPricingCatalogCompletedAtElapsedMs = null
        val messageSnapshot = if (snapshot.counts.hasAnyValue()) null else host.conversationSnapshot().map { HashMap(it) }
        return completeUsageRecordFrom(
            snapshot, pricingDeferred, pricingEndpointFingerprint, pricingCompletedAtElapsedMs, messageSnapshot
        )
    }

    /** A completed request's usage record from its saved snapshot. Rows are
     *  needed only to estimate counts the provider did not report. */
    internal suspend fun completeUsageRecordFrom(
        snapshot: ProviderUsageSnapshot,
        pricingDeferred: Deferred<TokenPricingCatalog>?,
        pricingEndpointFingerprint: String?,
        pricingCompletedAtElapsedMs: AtomicLong?,
        messageSnapshot: List<Map<String, Any>>?,
        updateCachingEvidence: Boolean = true
    ): TurnUsageRecord {
        val pricingEndpoint = org.teslasoft.assistant.ui.chat.ReplyUsageCompletion.pricingEndpoint(
            state.endpoint?.takeIf {
                pricingEndpointFingerprint == null || hooks.promptCachingEndpointFingerprint(it) == pricingEndpointFingerprint
            }, snapshot.apiEndpoint
        )
        val estimate = if (snapshot.counts.hasAnyValue() || messageSnapshot == null) {
            null
        } else {
            withContext(Dispatchers.Default) { estimateLastAssistantUsageCl100k(messageSnapshot) }
        }
        val (counts, source) = TokenUsageAccounting.chooseCounts(snapshot.counts) {
            estimate ?: TokenCounts(null, null, null)
        }
        var catalog = org.teslasoft.assistant.ui.chat.ReplyUsageCompletion.awaitPricing(pricingDeferred, 2000L)
        if (catalog == null) pricingDeferred?.cancel()
        val promptCachingCatalog = catalog
        if (catalog != null && !catalog.model.equals(snapshot.model, ignoreCase = true)) {
            catalog = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                TokenPricingCatalogClient.load(pricingEndpoint, snapshot.model)
            }
        }
        val completedAtElapsedMs = pricingCompletedAtElapsedMs?.get()?.takeIf { it >= 0L }
        if (updateCachingEvidence && !fence.isStale() && promptCachingCatalog != null && pricingEndpointFingerprint != null &&
            completedAtElapsedMs != null
        ) {
            state.lastPromptCachingCatalog = PromptCachingCatalogEvidence(
                pricingEndpointFingerprint,
                promptCachingCatalog,
                completedAtElapsedMs
            )
        }
        val pricing = catalog?.pricingFor(snapshot.provider)
            ?: TokenPricingSnapshot()
        return TokenUsageAccounting.createRecord(
            model = snapshot.model,
            provider = snapshot.provider,
            apiEndpoint = snapshot.apiEndpoint,
            counts = counts,
            source = source,
            pricing = pricing,
            providerCost = snapshot.providerCost
        ).copy(requestId = snapshot.attemptId)
    }

    internal suspend fun estimateLastAssistantUsageCl100k(
        messageSnapshot: List<Map<String, Any>>
    ): TokenCounts {
        val assistantIndex = messageSnapshot.indexOfLast { it["isBot"] == true }
        if (assistantIndex < 0) return TokenCounts(null, null, null)
        val tokenizer = Tokenizer.of(Encoding.CL100K_BASE)
        var input = 0
        messageSnapshot.forEachIndexed { index, message ->
            if (index >= assistantIndex) return@forEachIndexed
            val content = message["message"]?.toString().orEmpty()
            if (!content.trim().startsWith("~file:")) input += tokenizer.encode(content).size
        }
        val outputText = messageSnapshot[assistantIndex]["message"]?.toString().orEmpty()
        val output = if (outputText.trim().startsWith("~file:")) 0
            else tokenizer.encode(outputText).size
        return TokenCounts(input, output, input + output)
    }

    /**
     * The usage of rows that are no longer in the chat can only live in the
     * chat's usage log, so it is written there and retried until the write is
     * committed. The existing usage-log repair adds or enriches each request
     * by its request ID, so a retry never counts anything twice.
     */
    internal fun keepUsageOfDeletedRows(rows: List<Map<String, Any>>) {
        val prefs = state.chatPreferences ?: return
        if (rows.isEmpty()) return
        CoroutineScope(Dispatchers.IO + kotlinx.coroutines.NonCancellable).launch {
            var delayMs = 250L
            while (true) {
                val kept = try { UsageLogStore.seedChecked(prefs, rows) } catch (_: Exception) { false }
                if (kept) break
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Best non-response fallback for serving-provider attribution. An
     * OpenRouter Only route cannot fall back, so a successful response came
     * from that selected provider. Automatic/Preferred routing stays honestly
     * Not Reported unless response metadata names the actual route. */
    internal fun fallbackServingProvider(): String {
        val endpoint = state.endpoint
        if (endpoint?.isOpenRouterRouting() == true) {
            val favorite = hooks.favorite(host.model.ifBlank { state.chatPreferences?.getModel().orEmpty() })
            if (favorite?.routingType == FavoriteModelObject.ROUTING_ONLY) {
                return favorite.selectedProvider.trim()
                    .ifBlank { TokenUsageAccounting.PROVIDER_NOT_REPORTED }
            }
            return TokenUsageAccounting.PROVIDER_NOT_REPORTED
        }
        return endpoint?.provider?.trim()?.ifBlank { null }
            ?: endpoint?.label?.trim()?.ifBlank { null }
            ?: TokenUsageAccounting.PROVIDER_NOT_REPORTED
    }
}
