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

import org.teslasoft.assistant.tts.api.*
import android.content.Context
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.client.OpenAI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.teslasoft.assistant.ui.chat.parallel.*
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.ResponseLifecycleRecorder
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.ProviderRoutingBlockedException
import org.teslasoft.assistant.providers.ProviderRoutingDiagnostics
import org.teslasoft.assistant.providers.ProviderRoutingResolver
import org.teslasoft.assistant.providers.ProviderRoutingSerializer
import org.teslasoft.assistant.providers.ReportedProviderParser
import org.teslasoft.assistant.providers.PromptCachingCatalogEvidence
import org.teslasoft.assistant.providers.PromptCachingCatalogReusePolicy
import org.teslasoft.assistant.providers.PromptCachingPolicy
import org.teslasoft.assistant.providers.PromptCachingRequestSerializer
import org.teslasoft.assistant.providers.RoutingBlock
import org.teslasoft.assistant.preferences.includes.NativePdfPayload
import org.teslasoft.assistant.preferences.includes.PdfCapabilityProvider
import org.teslasoft.assistant.preferences.includes.PdfDeliveryPolicy
import org.teslasoft.assistant.preferences.includes.PdfRequestSerializer
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.usage.TokenPricingCatalog
import org.teslasoft.assistant.util.Hash
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.util.AttributeKey
import java.net.URL
import java.util.Locale
import androidx.core.content.edit
import io.ktor.client.HttpClientConfig

/**
 * The chat's request settings and the running reply's diagnostics, shared by
 * the chat screen and its [ChatRequestHooks]. The hooks read only this, never
 * the screen, so a request in flight does not depend on the screen object.
 */
internal class ChatRequestState {
    @Volatile var endpoint: ApiEndpointObject? = null
    @Volatile var endpointPreferences: ApiEndpointPreferences? = null
    /** The chat's own settings (per-chat preferences). */
    @Volatile var chatPreferences: Preferences? = null
    @Volatile var chatId: String = ""

    /** Coroutine-propagated owner of a generation request (see [GenerationRequestContext]). */
    val requestContext = ThreadLocal<GenerationRequestContext?>()

    @Volatile var currentLifecycle: ResponseLifecycleRecorder? = null
    @Volatile var currentUsageAttempt: ProviderUsageAttempt? = null
    @Volatile var generationRequestActive: Boolean = false
    @Volatile var lastRoutingAttachment: String? = null
    @Volatile var lastReasoningAttachment: String? = null
    @Volatile var lastReasoningInbound: String? = null
    @Volatile var reasoningObservationActive: Boolean = false
    @Volatile var lastPromptCachingCatalog: PromptCachingCatalogEvidence? = null
}

internal data class ReasoningObservationContext(
    val accumulator: org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator,
    val endpointId: String,
    val modelId: String,
    val showReasoning: Boolean,
    val messageId: String,
    val observed: CompletableDeferred<Unit>?,
    val canDeliver: () -> Boolean,
    /** Shows or keeps the captured reasoning on the reply it belongs to. */
    val deliver: (ReasoningObservationContext, org.teslasoft.assistant.reasoning.ReasoningCapability?) -> Unit
)

/** Coroutine-propagated request owner. Unlike activity-wide "current" fields,
 * this remains correct if two attempts ever overlap on different jobs. */
internal data class GenerationRequestContext(
    val usageAttempt: ProviderUsageAttempt?,
    val lifecycle: ResponseLifecycleRecorder?,
    val pricingCatalog: Deferred<TokenPricingCatalog>?,
    val nativePdfs: List<NativePdfPayload>,
    val pricingCatalogEndpointFingerprint: String? = null,
    /** A Condense/Reduce/reminder request: capture its usage only, with no
     *  chat-turn diagnostics attached. */
    val auxiliaryUsage: Boolean = false,
    val messageId: String = "",
    val reasoningAccumulator: org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator? = null,
    val reasoningObserved: CompletableDeferred<Unit>? = null,
    val canDeliver: () -> Boolean = { false },
    /** Receives the reasoning captured for [messageId]. */
    val reasoningSink: (ReasoningObservationContext, org.teslasoft.assistant.reasoning.ReasoningCapability?) -> Unit = { _, _ -> }
)

/**
 * The chat's request hooks: before sending, they bind usage and lifecycle
 * diagnostics to the exact request and add provider routing, reasoning,
 * prompt caching and original PDFs to its body; while the response arrives
 * they observe the provider, usage and reasoning. Everything they need comes
 * from [ChatRequestState] and the request's own [GenerationRequestContext].
 */
internal class ChatRequestHooks(context: Context, val state: ChatRequestState) {
    private val app: Context = context.applicationContext

    /** Installs the response observer and the send hook on a chat client. */
    fun install(config: HttpClientConfig<*>) {
        with(config) {
                install(ResponseObserver) {
                    filter { call ->
                        if (!call.response.status.isSuccess()) {
                            true
                        } else {
                            // Observe a successful generation stream when
                            // lifecycle logging owns a recorder OR this turn
                            // wants provider reasoning displayed. The observer
                            // receives a split copy, so the normal typed
                            // stream is neither buffered nor consumed.
                            val recorder = if (call.request.attributes.contains(responseLifecycleRecorderAttribute)) {
                                call.request.attributes[responseLifecycleRecorderAttribute]
                            } else {
                                null
                            }
                            val streamed = if (call.request.attributes.contains(streamingRequestAttribute)) {
                                call.request.attributes[streamingRequestAttribute]
                            } else {
                                false
                            }
                            val usageAttempt = if (call.request.attributes.contains(providerUsageAttemptAttribute)) {
                                call.request.attributes[providerUsageAttemptAttribute]
                            } else {
                                null
                            }
                            val wantsReasoning = call.request.attributes.contains(reasoningObservationAttribute)
                            when {
                                usageAttempt != null -> {
                                    usageAttempt.beginObservation(call.response.status.value)
                                    recorder?.beginProviderObservation(call.response.status.value)
                                    true
                                }
                                recorder != null && streamed -> {
                                    recorder.beginProviderObservation(call.response.status.value)
                                    true
                                }
                                wantsReasoning -> true
                                else -> false
                            }
                        }
                    }
                    onResponse { response ->
                        try {
                            if (!response.status.isSuccess()) {
                                val attrs = response.call.request.attributes
                                val usageAttempt = if (attrs.contains(providerUsageAttemptAttribute)) {
                                    attrs[providerUsageAttemptAttribute]
                                } else {
                                    null
                                }
                                try {
                                    val body = response.bodyAsText()
                                    usageAttempt?.noteHttpResponse(response.status.value, body)
                                } finally {
                                    usageAttempt?.finishObservation()
                                }
                            } else {
                                val attrs = response.call.request.attributes
                                val recorder = if (attrs.contains(responseLifecycleRecorderAttribute)) {
                                    attrs[responseLifecycleRecorderAttribute]
                                } else {
                                    null
                                }
                                val usageAttempt = if (attrs.contains(providerUsageAttemptAttribute)) {
                                    attrs[providerUsageAttemptAttribute]
                                } else {
                                    null
                                }
                                val reasoningObservation = if (attrs.contains(reasoningObservationAttribute)) {
                                    attrs[reasoningObservationAttribute]
                                } else {
                                    null
                                }
                                if (recorder != null || reasoningObservation != null || usageAttempt != null) {
                                    try {
                                        // Receive the observer's split copy ONCE (a second
                                        // bodyAsChannel() throws and cancels the origin,
                                        // killing the live stream) and drain it to end of
                                        // stream — stopping early stalls Ktor's splitter
                                        // and freezes the visible reply after ~4 KB; see
                                        // consumeObservedStream. One drain feeds both the
                                        // lifecycle inspector and, via lineObserver, the
                                        // reasoning accumulator.
                                        val observedChannel = response.bodyAsChannel()
                                        ReportedProviderParser.consumeObservedStream(
                                            observedChannel,
                                            onProvider = { reported ->
                                                // Response-derived only. Never substitute the
                                                // configured or requested provider here.
                                                if (!org.teslasoft.assistant.preferences.RawStreamObservationCodec.isEncoded(reported)) {
                                                    recorder?.noteActualModelProvider(reported)
                                                    usageAttempt?.noteProvider(reported)
                                                }
                                            },
                                            onObservation = { observation ->
                                                recorder?.noteRawObservation(observation)
                                                usageAttempt?.noteRawObservation(observation)
                                            },
                                            lineObserver = reasoningObservation?.let { observation ->
                                                { line -> observation.accumulator.acceptLine(line) }
                                            }
                                        )
                                    } finally {
                                        recorder?.finishProviderObservation()
                                        usageAttempt?.finishObservation()
                                        if (reasoningObservation != null) {
                                            val reasoningAcc = reasoningObservation.accumulator
                                            // Inbound evidence for the Response Lifecycle entry:
                                            // which reasoning fields actually came back and how
                                            // many characters — presence and count only, never
                                            // the reasoning text (§7.8).
                                            val inbound = reasoningAcc.inboundDiagnostics()
                                            if (reasoningObservation.canDeliver()) state.lastReasoningInbound =
                                                "reasoning=${inbound.reasoning}, " +
                                                    "reasoning_content=${inbound.reasoningContent}, " +
                                                    "reasoning_details=${inbound.reasoningDetails}, " +
                                                    "summary_field=${inbound.summaryField}, " +
                                                    "chars=${inbound.characters}, " +
                                                    "shown=${reasoningObservation.showReasoning}"
                                            val learnedCapability = promoteObservedReasoning(reasoningObservation)
                                            // Display only when Show Reasoning wanted it; the
                                            // reasoning_details for a tool-call continuation are
                                            // held in the accumulator regardless.
                                            if (reasoningObservation.showReasoning) {
                                                reasoningObservation.deliver(reasoningObservation, learnedCapability)
                                            }
                                            // Signal capture complete so a tool continuation can
                                            // echo reasoning_details on its follow-up.
                                            reasoningObservation.observed?.complete(Unit)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) { /* best effort; never disturb the failure path */ }
                    }
                }
                // OpenRouter provider routing: structurally add the resolved
                // `provider` object to the outgoing Chat Completions body just
                // before it is sent. Applies to every request on THIS chat
                // client — the primary turn, tool-result continuations, and
                // no-tools retries — so routing carries through all of them.
                // Fully fail-safe: any error, a non-OpenRouter endpoint, a
                // non-chat body, or Automatic-with-no-exclusions leaves the
                // request byte-for-byte unchanged, so normal chat can never be
                // broken by this hook.
                install(createClientPlugin("OpenRouterProviderRouting") {
                    on(Send) { request ->
                        bindLifecycleRecorderToGenerationRequest(request)
                        try {
                            augmentRequestWithProviderRouting(request)
                        } catch (blocked: ProviderRoutingBlockedException) {
                            // Deliberate: abort dispatch for an unsatisfiable
                            // config rather than send it unrestricted.
                            throw blocked
                        } catch (_: Exception) {
                            // Any other issue is non-fatal: send unmodified.
                        }
                        try {
                            // Reasoning rides the same just-before-send body
                            // mutation; independent of routing and fully
                            // fail-safe, so it never blocks or breaks a send.
                            augmentRequestWithReasoning(request)
                        } catch (_: Exception) {
                            // Non-fatal: send without a reasoning instruction.
                        }
                        try {
                            // A moving explicit breakpoint opts supported
                            // OpenRouter providers into multi-turn caching.
                            augmentRequestWithPromptCaching(request)
                        } catch (_: Exception) {
                            // Non-fatal: send without an explicit breakpoint.
                        }
                        // Unlike optional reasoning, a native PDF must not
                        // be silently omitted. Any mutation failure aborts
                        // before dispatch and reaches the normal readable
                        // failed-request path.
                        augmentRequestWithNativePdfs(request)
                        proceed(request)
                    }
                })
        }
    }

    internal fun favorite(model: String): org.teslasoft.assistant.preferences.dto.FavoriteModelObject? {
        val endpoint = state.endpoint ?: return null
        return org.teslasoft.assistant.preferences.ChatModelRouting.effective(app, state.chatPreferences, endpoint.id, model)
    }

    /**
     * Just-before-send hook body. For a JSON Chat Completions request on an
     * OpenRouter-identity endpoint it either:
     *  - throws [ProviderRoutingBlockedException] when the saved routing cannot
     *    be satisfied (e.g. Only with no usable provider) — so the request is
     *    never silently sent unrestricted; or
     *  - sets the resolved `provider` object on the body, overwriting any
     *    existing one so a re-sent request (tool continuation / retry) never
     *    carries two.
     * While Response Lifecycle logging is active it also opts this OpenRouter
     * request into official response-side router metadata, which reports the
     * endpoint actually marked selected. Every other request BODY (generic
     * endpoint, non-chat body, parse issue, Automatic with no exclusions)
     * remains byte-for-byte unchanged.
     */
    /**
     * Bind lifecycle diagnostics to the exact Ktor request BEFORE dispatch.
     * Auxiliary requests share this client, so their explicit request context
     * binds usage without borrowing the visible generation's lifecycle.
     */
    internal fun bindLifecycleRecorderToGenerationRequest(request: HttpRequestBuilder) {
        if (request.attributes.contains(responseLifecycleRecorderAttribute) ||
            request.attributes.contains(generationRequestAttribute)
        ) return
        val boundContext = state.requestContext.get()
        val recorder = (boundContext?.lifecycle ?: state.currentLifecycle)
            ?.takeUnless { it.finalized }
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return

        var streamingGeneration = false
        val isGenerationRequest = try {
            val root = com.google.gson.JsonParser.parseString(content.text).asJsonObject
            streamingGeneration = root.get("stream")
                ?.takeUnless { it.isJsonNull }
                ?.asBoolean == true
            val hasGenerationInput = root.has("messages") || root.has("prompt")
            // The normal chat-completion path is identified by messages even
            // when the client uses its completed-response API and omits the
            // stream flag. Keep streamed legacy completion diagnostics intact.
            root.has("model") && hasGenerationInput &&
                (streamingGeneration || (root.has("messages") && state.generationRequestActive))
        } catch (_: Exception) {
            false
        }
        if (!isGenerationRequest && boundContext?.auxiliaryUsage == true) {
            boundContext.usageAttempt?.let {
                // Arm the wait before dispatch. ResponseObserver starts on a
                // separate coroutine and can otherwise lose the race with
                // terminal usage finalization, dropping raw-only cost/cache.
                it.beginObservation()
                request.attributes.put(providerUsageAttemptAttribute, it)
            }
            return
        }
        if (isGenerationRequest) {
            boundContext?.let { request.attributes.put(generationOwnerAttribute, it) }
            recorder?.let { request.attributes.put(responseLifecycleRecorderAttribute, it) }
            (boundContext?.usageAttempt ?: state.currentUsageAttempt)?.let {
                // Raw OpenRouter usage (cost and cache details) is richer than
                // the typed SDK usage. Finalization must wait for that observer.
                it.beginObservation()
                request.attributes.put(providerUsageAttemptAttribute, it)
            }
            request.attributes.put(generationRequestAttribute, true)
            request.attributes.put(streamingRequestAttribute, streamingGeneration)
        }
    }

    internal fun augmentRequestWithProviderRouting(request: HttpRequestBuilder) {
        if (state.endpoint?.isOpenRouterRouting() != true) return
        if (request.attributes.contains(responseLifecycleRecorderAttribute) ||
            request.attributes.contains(providerUsageAttemptAttribute)
        ) {
            // Official OpenRouter audit metadata, delivered on the final stream
            // chunk. This is the response authority for Automatic routing; the
            // app's requested provider settings are never substituted for it.
            request.headers.remove("X-OpenRouter-Metadata")
            request.headers.append("X-OpenRouter-Metadata", "enabled")
        }
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return
        val text = content.text

        // Only a chat request carries a messages array; skip anything else, and
        // degrade to "do nothing" on any parse failure.
        val model = try {
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            if (root.has("messages")) root.get("model")?.takeIf { !it.isJsonNull }?.asString else null
        } catch (_: Exception) {
            null
        } ?: return

        val resolution = ProviderRoutingResolver.resolve(true, favorite(model))

        // Block BEFORE dispatch — deliberately thrown so the send fails cleanly
        // through the existing error path rather than going out unrestricted.
        if (resolution.block != RoutingBlock.NONE) {
            state.lastRoutingAttachment = "BLOCKED (not sent)"
            throw ProviderRoutingBlockedException(providerBlockMessage(resolution.block))
        }

        val providerJson = resolution.providerJson
        if (providerJson == null) {
            // OpenRouter, but Automatic / no saved routing — nothing to attach.
            state.lastRoutingAttachment = "no provider object (Automatic / no saved routing)"
            return
        }
        try {
            val augmented = ProviderRoutingSerializer.augmentBody(text, providerJson)
            request.setBody(TextContent(augmented, content.contentType ?: ContentType.Application.Json))
            // Recorded ONLY after the body was actually replaced.
            state.lastRoutingAttachment = "provider object attached"
        } catch (_: Exception) {
            // Injection is best-effort; report honestly that it was not confirmed.
            state.lastRoutingAttachment = "attachment requested (mutation failed)"
        }
    }

    /**
     * Give every ordinary OpenRouter chat request a stable cache-routing key.
     * Message content is changed into explicit-cache blocks only when live
     * endpoint data proves that every provider this request can reach uses
     * cache-write pricing and does not advertise implicit caching. Auxiliary
     * requests are excluded by the generation-request attribute.
     */
    internal suspend fun augmentRequestWithPromptCaching(request: HttpRequestBuilder) {
        val endpoint = state.endpoint?.takeIf { it.isOpenRouterRouting() } ?: return
        if (!request.attributes.contains(generationRequestAttribute)) return
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return
        val model = try {
            com.google.gson.JsonParser.parseString(content.text).asJsonObject
                .get("model")?.takeUnless { it.isJsonNull }?.asString
        } catch (_: Exception) {
            null
        } ?: return
        val endpointFingerprint = promptCachingEndpointFingerprint(endpoint)
        val requestContext = state.requestContext.get()
        val requestEndpointFingerprint = requestContext?.pricingCatalogEndpointFingerprint
        val contextCatalog = requestContext?.pricingCatalog
            ?.takeIf { it.isCompleted }
            ?.let { runCatching { it.await() }.getOrNull() }
            ?.takeIf {
                requestEndpointFingerprint == endpointFingerprint &&
                    it.model == model
            }
        val catalog = contextCatalog ?: PromptCachingCatalogReusePolicy.reusableCatalog(
            state.lastPromptCachingCatalog,
            endpointFingerprint,
            model,
            android.os.SystemClock.elapsedRealtime()
        )
        val explicitBreakpoint = PromptCachingPolicy.shouldAddExplicitBreakpoint(
            catalog?.providerPrices.orEmpty(),
            favorite(model),
            catalogAuthoritative = catalog?.providerPricesAuthoritative == true
        )
        val augmented = PromptCachingRequestSerializer.augmentOpenRouterChatBody(
            content.text,
            state.chatId.takeIf { it.isNotBlank() },
            addExplicitBreakpoint = explicitBreakpoint
        )
        if (augmented != content.text) {
            request.setBody(TextContent(augmented, content.contentType ?: ContentType.Application.Json))
        }
    }

    /**
     * Provider catalogs can vary by saved OpenRouter profile, discovery path,
     * and account. Hash the credential rather than retaining it in cache
     * metadata; any profile edit then invalidates earlier evidence.
     */
    internal fun promptCachingEndpointFingerprint(endpoint: ApiEndpointObject?): String? {
        if (endpoint?.isOpenRouterRouting() != true) return null
        return listOf(
            endpoint.id.trim(),
            // Preserve URL path case: hosts are case-insensitive, but a proxy
            // may route /API/v1 and /api/v1 to different backends. Treating a
            // scheme/host case-only edit as a cache miss is safely conservative.
            endpoint.host.trim().trimEnd('/'),
            endpoint.providerDiscoveryPath.trim()
                .ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH },
            endpoint.authType.trim().lowercase(Locale.ROOT),
            Hash.hash(endpoint.apiKey)
        ).joinToString("\u001f")
    }

    /** Attach original PDFs only to the exact frozen generation request that owns them. */
    internal fun augmentRequestWithNativePdfs(request: HttpRequestBuilder) {
        val payloads = state.requestContext.get()?.nativePdfs.orEmpty()
        if (payloads.isEmpty()) return
        val provider = state.endpoint?.let { PdfCapabilityProvider.forEndpoint(it) } ?: return
        if (!PdfDeliveryPolicy.hasNativeTransport(provider)) return
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return
        val augmented = PdfRequestSerializer.augmentOpenAiChatBody(
            content.text,
            payloads,
            openRouterNative = provider == PdfCapabilityProvider.OPENROUTER
        )
        request.setBody(TextContent(augmented, content.contentType ?: ContentType.Application.Json))
    }

    /**
     * The reasoning capability of the active endpoint for [model], layering the
     * §7.7 confidence ladder over this endpoint's persisted capability store.
     */
    internal fun reasoningCapabilityForModel(model: String): org.teslasoft.assistant.reasoning.ReasoningCapability =
        org.teslasoft.assistant.reasoning.EndpointReasoningCapability.resolveWithLearnedRejections(
            state.endpoint?.reasoningCapabilityByModel,
            state.endpoint?.reasoningRejectedLevelsByModel,
            model,
            providerPath = org.teslasoft.assistant.reasoning.ReasoningProviderPath.forEndpoint(
                state.endpoint?.host,
                state.endpoint?.isOpenRouterRouting() == true
            )
        )

    /**
     * The per-message reasoning indicator token for [model], or null when the
     * model is not known to reason. This is the effort this turn REQUESTED
     * from the shared evidence-backed capability — never from speculative retry
     * substitution and not a provider-reported effective level. Auto records
     * as "automatic/level unknown" rather than claiming a specific level.
     */
    internal fun reasoningIndicatorTokenForModel(model: String): String? {
        val capability = reasoningCapabilityForModel(model)
        val effort = resolveReasoningForModel(model).effort
        return org.teslasoft.assistant.reasoning.ReasoningIndicator
            .forGeneration(capability, effort)?.token
    }

    /**
     * The effective reasoning settings for this turn (§7.5/§7.9): the
     * conversation's own override, else this model's favorite default, else
     * Auto — with the §7.8 clamp for an effort the active path no longer
     * supports. Read from the same favorite/conversation storage the UI writes,
     * so every send path (typed, voice, retry) resolves identically.
     */
    internal fun resolveReasoningForModel(model: String): org.teslasoft.assistant.reasoning.ResolvedReasoning {
        val capability = reasoningCapabilityForModel(model)
        val favorite = favorite(model)
        val favoriteEffort = favorite?.let {
            org.teslasoft.assistant.reasoning.ReasoningEffort.fromSerialized(it.reasoningEffort)
        }
        val override = org.teslasoft.assistant.reasoning.ReasoningEffort.fromSerialized(
            state.chatPreferences?.getReasoningEffortOverride()
        )
        return org.teslasoft.assistant.reasoning.ReasoningSettingsResolver.resolve(
            conversationOverride = override,
            favoriteEffort = favoriteEffort,
            favoriteShowReasoning = favorite?.showReasoning,
            capability = capability
        )
    }

    /**
     * Just-before-send hook that merges this turn's reasoning instruction into
     * the outgoing Chat Completions body (§7.9), using the same body-mutation
     * approach as provider routing so no separate request transport exists. It
     * applies to OpenRouter and generic OpenAI-compatible endpoints alike, in
     * each provider's own field shape. Fully fail-safe: a non-chat body, a
     * parse issue, a non-reasoning model, or Auto with reasoning returned leaves
     * the request byte-for-byte unchanged, so ordinary chat can never break.
     */
    internal fun augmentRequestWithReasoning(request: HttpRequestBuilder) {
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return
        val text = content.text

        titleRequestModel(text)?.let { titleModel ->
            augmentTitleRequestWithReasoningOff(request, content, titleModel)
            return
        }

        var bodyHasTools = false
        val model = try {
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            // Only visible chat generation carries reasoning. Auxiliary calls
            // on this same client (auto-naming, tool-name resolution,
            // summarizer) must not inherit a reasoning instruction.
            val streamed = root.get("stream")?.takeUnless { it.isJsonNull }?.asBoolean == true
            val generationRecorder = if (request.attributes.contains(responseLifecycleRecorderAttribute)) {
                request.attributes[responseLifecycleRecorderAttribute]
            } else {
                null
            }
            val generationRequest = request.attributes.contains(generationRequestAttribute)
            if (root.has("messages") &&
                (streamed || generationRecorder != null || generationRequest)
            ) {
                bodyHasTools = (root.get("tools")?.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) > 0
                root.get("model")?.takeIf { !it.isJsonNull }?.asString
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } ?: return

        val resolved = resolveReasoningForModel(model)
        val capability = reasoningCapabilityForModel(model)
        val isOpenRouter = state.endpoint?.isOpenRouterRouting() == true

        // Unknown capability is not the same as non-reasoning: observe it so a
        // real response can promote the exact endpoint/model. Known visible
        // reasoning is observed for display; OpenRouter tool turns are observed
        // for continuation state even when display is off. Known-absent and
        // hidden fixed paths avoid an unnecessary split-stream copy.
        val wantLearning = capability.support ==
            org.teslasoft.assistant.reasoning.ReasoningSupport.UNKNOWN
        val wantDisplay = resolved.showReasoning &&
            (capability.canReturnVisibleReasoning || wantLearning)
        val wantContinuationState = isOpenRouter && bodyHasTools &&
            capability.support != org.teslasoft.assistant.reasoning.ReasoningSupport.ABSENT
        val owner = if (request.attributes.contains(generationOwnerAttribute))
            request.attributes[generationOwnerAttribute] else null
        owner?.reasoningAccumulator?.takeIf {
            wantLearning || wantDisplay || wantContinuationState
        }?.let { accumulator ->
            request.attributes.put(
                reasoningObservationAttribute,
                ReasoningObservationContext(
                    accumulator = accumulator,
                    endpointId = state.endpoint?.id.orEmpty(),
                    modelId = model,
                    showReasoning = wantDisplay,
                    messageId = owner.messageId,
                    observed = owner.reasoningObserved,
                    canDeliver = owner.canDeliver,
                    deliver = owner.reasoningSink
                )
            )
            if (owner.canDeliver()) state.reasoningObservationActive = true
        }

        // 1) The reasoning request fields for this turn.
        val afterParam = org.teslasoft.assistant.reasoning.ReasoningRequestSerializer.augmentBody(
            text, resolved, isOpenRouter, capability.isReasoningCapable
        )
        val paramAttached = afterParam != text
        // 2) Reasoning-state continuation (§7.2): on an OpenRouter follow-up that
        //    carries an assistant tool-call message, echo back the reasoning
        //    details captured from the turn that produced the tool call. A no-op
        //    on the primary request (no tool-call assistant message yet) and off
        //    OpenRouter. Independent of Show Reasoning; never surfaced in the UI.
        var newBody = afterParam
        var continuationAttached = false
        if (isOpenRouter) {
            val withState = org.teslasoft.assistant.reasoning.ReasoningContinuationSerializer
                .attachToToolCallMessage(newBody, owner?.reasoningAccumulator?.reasoningDetails())
            if (withState != newBody) {
                newBody = withState
                continuationAttached = true
            }
        }

        // Record the final request shape on THIS attempt. Kept on the attempt
        // rather than in process-wide state so two overlapping generations can
        // never report each other's outbound fields.
        if (request.attributes.contains(providerUsageAttemptAttribute)) {
            request.attributes[providerUsageAttemptAttribute].noteOutboundFieldNames(
                org.teslasoft.assistant.util.OutboundRequestDiagnostics.fieldNamesOf(newBody)
            )
        }

        if (newBody != text) {
            request.setBody(TextContent(newBody, content.contentType ?: ContentType.Application.Json))
            // On OpenRouter, a visible summary is positively requested whenever
            // reasoning is wanted for display and not being disabled. Report it so
            // the request shape is explicit in the log.
            val summaryRequested = isOpenRouter && resolved.showReasoning && !resolved.disablesReasoning
            state.lastReasoningAttachment = buildString {
                append(
                    if (paramAttached) {
                        "reasoning attached (effort=${resolved.effort.serialized}, show=${resolved.showReasoning}, " +
                            "summary_requested=$summaryRequested, " +
                            "source=${resolved.source}, capability=${capability.source})"
                    } else {
                        "no reasoning param (Auto / provider default)"
                    }
                )
                if (continuationAttached) append("; reasoning_details echoed on tool-call continuation")
                resolved.clampedFrom?.let { append(", dropped unsupported=${it.serialized}") }
            }
        } else {
            state.lastReasoningAttachment = if (!capability.isReasoningCapable) {
                "no reasoning fields (model not known to reason)"
            } else {
                "no reasoning fields (Auto / provider default)"
            }
        }
    }

    /** The model of an auto-naming request, or null for any other body. The
     *  naming request is the one carrying the title instruction. */
    internal fun titleRequestModel(body: String): String? = try {
        val root = com.google.gson.JsonParser.parseString(body).asJsonObject
        val isTitleRequest = root.getAsJsonArray("messages")?.any { message ->
            message.isJsonObject &&
                message.asJsonObject.get("content")?.takeIf { it.isJsonPrimitive }?.asString ==
                org.teslasoft.assistant.util.ChatTitleExtractor.TITLE_INSTRUCTION
        } == true
        if (isTitleRequest) root.get("model")?.takeIf { !it.isJsonNull }?.asString else null
    } catch (_: Exception) {
        null
    }

    /**
     * A title needs no thinking (owner ruling, Oct 12 2026). When the model is
     * known to let thinking be turned off, the naming request asks for it off,
     * whatever this chat's reasoning setting is. Otherwise the request is sent
     * unchanged. A provider that refuses the request fails that naming
     * attempt, and the normal retries and summarizer backup take over.
     */
    internal fun augmentTitleRequestWithReasoningOff(
        request: HttpRequestBuilder,
        content: TextContent,
        model: String
    ) {
        val capability = reasoningCapabilityForModel(model)
        if (!capability.isReasoningCapable || !capability.canDisableReasoning) return
        val off = org.teslasoft.assistant.reasoning.ResolvedReasoning(
            effort = org.teslasoft.assistant.reasoning.ReasoningEffort.OFF,
            showReasoning = false,
            source = org.teslasoft.assistant.reasoning.ResolvedReasoning.Source.DEFAULT_AUTO
        )
        val fields = org.teslasoft.assistant.reasoning.ReasoningRequestSerializer.requestFields(
            off,
            state.endpoint?.isOpenRouterRouting() == true,
            reasoningCapable = true
        ) ?: return
        val newBody = try {
            val root = com.google.gson.JsonParser.parseString(content.text).asJsonObject
            for ((key, value) in fields.entrySet()) root.add(key, value)
            root.toString()
        } catch (_: Exception) {
            return
        }
        request.setBody(TextContent(newBody, content.contentType ?: ContentType.Application.Json))
    }

    /** Existing user-facing wording for a blocked routing configuration. */
    internal fun providerBlockMessage(block: RoutingBlock): String = when (block) {
        RoutingBlock.ONLY_PROVIDER_NOT_SELECTED,
        RoutingBlock.ONLY_PROVIDER_UNAVAILABLE -> app.getString(R.string.provider_only_mode_error)
        RoutingBlock.NO_PREFERRED_AVAILABLE -> app.getString(R.string.provider_no_preferred_message)
        RoutingBlock.NONE -> ""
    }

    /**
     * The clearly labeled provider-routing request lines appended to a Response
     * Lifecycle entry. The
     * attachment status is the interceptor's ACTUAL result for this request
     * ([state.lastRoutingAttachment], set only after a confirmed body replacement),
     * never re-derived from the routing decision. If the send hook never ran,
     * it stays null and the line honestly reads "attachment requested…" rather
     * than claiming the provider object was attached.
     */
    internal fun providerRoutingLogLine(model: String): String {
        val isOpenRouter = state.endpoint?.isOpenRouterRouting() == true
        val favorite = favorite(model)
        val status = state.lastRoutingAttachment ?: "attachment requested (send hook did not run)"
        return "\n" + ProviderRoutingDiagnostics.describe(isOpenRouter, favorite, status)
    }

    /**
     * The reasoning line appended to a Response Lifecycle entry (§7.8): what
     * reasoning instruction was actually written on the outgoing body and the
     * capability source it was resolved from. Reads the send hook's real
     * outcome ([state.lastReasoningAttachment]); if the hook never ran it says so
     * rather than implying anything was sent.
     */
    internal fun reasoningLogLine(): String {
        val outbound = state.lastReasoningAttachment ?: "reasoning hook did not run"
        // Keep outbound intent and inbound evidence on separate lines: "attached"
        // describes the request only; it never proves reasoning came back (§7.8).
        val inbound = state.lastReasoningInbound
            ?: "not observed (display off or no reasoning observation this turn)"
        return "\nReasoning (outbound intent): $outbound" +
            "\nReasoning (inbound observed): $inbound"
    }

    /**
     * Attach the reasoning captured from the split stream copy to the reply it
     * belongs to (§7.1/§7.2). Runs from the response observer once the copy has
     * drained; it is the sole writer of the reasoning fields, so it never races
     * the typed stream's own completion/persistence for the answer text. It
     * mutates the reply's in-memory map, so whichever save runs last (this one
     * or the answer's completion save) keeps the reasoning. Reasoning is a
     * separate channel and is never merged into the answer text — so it never
     * reaches TTS and never signals completion. Fully best-effort.
     */
    internal fun promoteObservedReasoning(
        observation: ReasoningObservationContext
    ): org.teslasoft.assistant.reasoning.ReasoningCapability? {
        if (!observation.accumulator.hasReasoning() ||
            observation.endpointId.isBlank() || observation.modelId.isBlank()
        ) return null
        return try {
            val endpointPrefs = state.endpointPreferences
                ?: org.teslasoft.assistant.preferences.ApiEndpointPreferences
                    .getApiEndpointPreferences(app)
            val endpoint = endpointPrefs.getApiEndpoint(app, observation.endpointId)
            val previous = endpoint.reasoningCapabilityByModel
            val updated = org.teslasoft.assistant.reasoning.EndpointReasoningCapability
                .learnFromObservedResponse(previous, observation.modelId)
            if (updated != previous) {
                endpoint.reasoningCapabilityByModel = updated
                endpointPrefs.setApiEndpoint(app, endpoint)
                if (state.endpoint?.id == observation.endpointId) {
                    state.endpoint?.reasoningCapabilityByModel = updated
                }
                org.teslasoft.assistant.preferences.Logger.logAsync(
                    app,
                    "event",
                    "ReasoningCapability",
                    "info",
                    "Reasoning capability source: cached observed response\n" +
                        "Model: ${observation.modelId}\n" +
                        "Reasoning: known\n" +
                        "Supported efforts: unknown"
                )
            }
            org.teslasoft.assistant.reasoning.EndpointReasoningCapability.resolve(
                updated,
                observation.modelId,
                providerPath = org.teslasoft.assistant.reasoning.ReasoningProviderPath.forEndpoint(
                    endpoint.host,
                    endpoint.isOpenRouterRouting()
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Pins a split raw response to the lifecycle recorder for that exact request. */
        internal val responseLifecycleRecorderAttribute =
            AttributeKey<ResponseLifecycleRecorder>("ResponseLifecycleRecorder")

        /** Pins durable accounting to the exact HTTP request independently of
         * optional diagnostics. */
        internal val providerUsageAttemptAttribute =
            AttributeKey<ProviderUsageAttempt>("ProviderUsageAttempt")

        /** Marks the primary/tool-generation request when lifecycle logging is
         * off, so final outbound-field diagnostics still exclude auxiliary
         * completed requests such as auto-naming. */
        internal val generationRequestAttribute =
            AttributeKey<Boolean>("GenerationRequest")

        /** Records whether the generation request was serialized with
         * `stream=true`; the response observer cannot inspect the outgoing
         * body through Ktor's response-side request object. */
        internal val streamingRequestAttribute =
            AttributeKey<Boolean>("StreamingRequest")

        /** Pins the current turn's reasoning accumulator to a request whose
         *  resolved settings want provider reasoning displayed (§7.2). Its
         *  presence is what tells the response observer to split this stream for
         *  reasoning even when lifecycle logging is off. */
        internal val reasoningObservationAttribute =
            AttributeKey<ReasoningObservationContext>("ReasoningObservation")

        internal val generationOwnerAttribute = AttributeKey<GenerationRequestContext>("GenerationOwner")
    }
}
