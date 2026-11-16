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

package org.teslasoft.assistant.preferences.memory.context

import android.content.Context
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.StreamOptions
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.DedicatedModelRouting
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.providers.ModelRequestParameters
import org.teslasoft.assistant.providers.ModelSupportedParametersStore
import org.teslasoft.assistant.providers.PromptCachingRequestSerializer
import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import org.teslasoft.assistant.providers.ProviderRoutingResolver
import org.teslasoft.assistant.providers.ProviderRoutingSerializer
import org.teslasoft.assistant.providers.ReportedProviderParser
import org.teslasoft.assistant.providers.RoutingBlock
import org.teslasoft.assistant.usage.AuxiliaryUsage
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.usage.TokenPricingCatalog
import org.teslasoft.assistant.usage.TokenPricingCatalogClient
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageLog
import org.teslasoft.assistant.usage.UsageLogStore
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.util.ProviderErrorInfo
import org.teslasoft.assistant.util.summarizer.SummarizerDetailSanitizer
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sends the API helper's request to the model chosen on Memory Context Helper
 * Tool API, with that screen's routing and parameters (M.4). It reads nothing
 * from the chat's, the Summarizer's or the Memory Scanner's settings, and
 * never falls back to another model: with no usable model it reports
 * [ApiContextAssistant.HelperUnavailable].
 *
 * Each request's provider-reported usage and cost go to the chat's usage log
 * under Memory Context Helper (M.4 item 5), recorded in the background so
 * they never delay sending. Nothing is written to any diagnostic log.
 */
class ApiContextHelperTransport(context: Context) : ApiContextAssistant.HelperTransport {

    private val appContext = context.applicationContext
    private val routing = DedicatedModelRouting.MEMORY_CONTEXT_HELPER

    private data class Target(
        val endpoint: ApiEndpointObject,
        val model: String,
        val providerRouting: com.google.gson.JsonObject?,
        val requestedProvider: String?
    )

    @Volatile private var target: Target? = null
    @Volatile private var errorBody: String? = null
    @Volatile private var evidence: ProviderDiagnosticSnapshot? = null

    /** The helper's model, endpoint and routing, or [ApiContextAssistant.HelperUnavailable]. */
    private fun resolve(): Target {
        target?.let { return it }
        val endpointId = routing.endpointId(appContext)
        val model = routing.modelId(appContext)
        if (endpointId.isBlank() || model.isBlank()) {
            throw ApiContextAssistant.HelperUnavailable(HelperFailure.Local.NoModel)
        }
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(appContext)
            .getApiEndpoint(appContext, endpointId)
        if (endpoint == null || endpoint.id != endpointId || endpoint.host.isBlank()) {
            throw ApiContextAssistant.HelperUnavailable(HelperFailure.Local.ProfileGone)
        }
        val favorite = if (endpoint.isOpenRouterRouting()) routing.effective(appContext, endpointId, model) else null
        if (favorite != null && DedicatedModelRoutingPolicy.needsSetup(favorite.routingType, favorite)) {
            throw ApiContextAssistant.HelperUnavailable(HelperFailure.Local.RoutingNotSetUp)
        }
        val resolution = ProviderRoutingResolver.resolve(endpoint.isOpenRouterRouting(), favorite)
        if (resolution.block != RoutingBlock.NONE) {
            throw ApiContextAssistant.HelperUnavailable(HelperFailure.Local.RoutingNotSetUp)
        }
        return Target(endpoint, model, resolution.providerJson, favorite?.selectedProvider?.takeIf { it.isNotBlank() })
            .also { target = it }
    }

    override fun contextWindowTokens(): Int? {
        val t = resolve()
        return t.endpoint.contextWindowTokens?.takeIf { t.endpoint.contextWindowModelId == t.model }
    }

    override fun profileAndModel(): Pair<String?, String?> {
        target?.let { return it.endpoint.label to it.model }
        val endpointId = routing.endpointId(appContext)
        val label = endpointId.takeIf { it.isNotBlank() }?.let {
            ApiEndpointPreferences.getApiEndpointPreferences(appContext).getApiEndpoint(appContext, it)
                ?.takeIf { e -> e.id == endpointId }?.label
        }
        return label to routing.modelId(appContext).ifBlank { null }
    }

    override suspend fun complete(request: ApiContextAssistant.HelperRequest): String {
        val t = resolve()
        val parameters = ModelRequestParameters.from(
            FavoriteModelParameters.live(appContext, ModelPickerScope.MEMORY_CONTEXT_HELPER),
            ModelSupportedParametersStore.get(appContext).supported(t.endpoint.id, t.model)
        )
        val attempt = ProviderUsageAttempt(
            requestedModel = t.model,
            fallbackProvider = t.requestedProvider ?: TokenUsageAccounting.PROVIDER_NOT_REPORTED,
            apiEndpoint = t.endpoint.host
        )
        attempt.beginObservation()
        // The price list loads beside the request and is read only afterwards,
        // in the background, so it never adds to the helper's time.
        val pricing = background.async { TokenPricingCatalogClient.load(t.endpoint, t.model) }
        val client = buildClient(t, attempt, request.cacheKey)
        val chatRequest = ChatCompletionRequest(
            model = ModelId(t.model),
            messages = buildList {
                add(ChatMessage(role = ChatRole.System, content = request.instructions))
                request.earlier.forEach { turn ->
                    add(ChatMessage(role = if (turn.isUser) ChatRole.User else ChatRole.Assistant, content = turn.text))
                }
                add(ChatMessage(role = ChatRole.User, content = request.newest))
            },
            temperature = parameters.temperature,
            topP = parameters.topP,
            frequencyPenalty = parameters.frequencyPenalty,
            presencePenalty = parameters.presencePenalty,
            streamOptions = if (parameters.stream) StreamOptions(includeUsage = true) else null
        )
        errorBody = null
        evidence = null
        var succeeded = false
        try {
            val reply = if (parameters.stream) {
                val text = StringBuilder()
                client.chatCompletions(chatRequest).collect { chunk ->
                    chunk.choices.firstOrNull()?.delta?.content?.takeIf { it != "null" }?.let(text::append)
                }
                text.toString()
            } else {
                client.chatCompletion(chatRequest).choices.firstOrNull()?.message?.content.orEmpty()
            }
            succeeded = true
            routing.recordUse(appContext, t.endpoint.id, t.model)
            return reply
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            evidence = kotlinx.coroutines.withContext(NonCancellable) { attempt.diagnosticSnapshot() }
            throw e
        } finally {
            client.close()
            recordUsage(request.chatId, attempt, pricing, succeeded)
        }
    }

    override fun describe(error: Throwable, privateValues: List<String>): HelperFailure {
        val (profile, model) = profileAndModel()
        val private = privateValues + listOfNotNull(target?.endpoint?.apiKey)
        val result = GenerationErrorClassifier.classify(error, evidence)
        val providerMessage = SummarizerDetailSanitizer.sanitize(errorBody?.takeIf { it.isNotBlank() }, private)
        val exceptionMessage = SummarizerDetailSanitizer.sanitize(
            error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName, private
        )
        val serving = evidence?.actualServingProvider
            ?: providerMessage?.let { ProviderErrorInfo.parse(it).providerName }
        return HelperFailure(
            HelperFailure.categoryOf(result), profile, model,
            provider = HelperFailure.ProviderDetail(
                result, exceptionMessage, providerMessage, evidence,
                SummarizerDetailSanitizer.sanitize(serving, private)
            )
        )
    }

    /** Provider-reported usage to the chat's usage log, never estimated. */
    private fun recordUsage(
        chatId: String?,
        attempt: ProviderUsageAttempt,
        pricing: Deferred<TokenPricingCatalog>,
        succeeded: Boolean
    ) {
        val t = target ?: return
        if (chatId.isNullOrBlank()) {
            pricing.cancel()
            return
        }
        background.launch(NonCancellable) {
            val record = AuxiliaryUsage.record(attempt, pricing, succeeded) { reportedModel ->
                TokenPricingCatalogClient.load(t.endpoint, reportedModel)
            } ?: return@launch
            try {
                UsageLogStore.append(
                    Preferences.getPreferences(appContext, chatId),
                    listOf(UsageLog.entry(UsageCategory.MEMORY_CONTEXT_HELPER, null, record))
                )
            } catch (_: Exception) { /* Usage is never allowed to affect sending. */ }
        }
    }

    private fun buildClient(t: Target, attempt: ProviderUsageAttempt, cacheKey: String?): OpenAI {
        val endpoint = t.endpoint
        val isBearerAuth = endpoint.authType == ApiEndpointObject.AUTH_BEARER
        val extraHeaders: Map<String, String> = when (endpoint.authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to endpoint.apiKey)
            ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to endpoint.apiKey)
            else -> emptyMap()
        }
        return OpenAI(
            OpenAIConfig(
                token = if (isBearerAuth) endpoint.apiKey else "",
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                // The helper's own time allowance (ApiContextAssistant) is the
                // real limit; this only bounds a request outside it.
                timeout = Timeout(socket = ApiContextAssistant.TEXT_ALLOWANCE_MS.milliseconds),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(composeChatHost(endpoint.host, endpoint.chatEndpoint)),
                proxy = null,
                // No automatic retries (M.4 item 3).
                retry = RetryStrategy(maxRetries = 0),
                httpClientConfig = {
                    install(ResponseObserver) {
                        onResponse { response ->
                            try {
                                val body = response.bodyAsText()
                                if (response.status.isSuccess()) {
                                    (ReportedProviderParser.observeCompletedBody(body)
                                        ?: ReportedProviderParser.observeStreamedBody(body))
                                        ?.let(attempt::noteRawObservation)
                                } else {
                                    errorBody = body
                                    attempt.noteHttpResponse(response.status.value, body)
                                }
                            } catch (_: Exception) {
                                /* best effort */
                            } finally {
                                attempt.finishObservation()
                            }
                        }
                    }
                    val providerRouting = t.providerRouting
                    if (endpoint.isOpenRouterRouting()) {
                        // The helper's provider routing, and the chat's
                        // stable cache key so OpenRouter can send it where its
                        // earlier requests may be cached. No explicit cache
                        // marker: short helper requests rarely reach a
                        // provider's caching minimum, and some providers
                        // charge to write one (Owner, 2026-11-16).
                        install(createClientPlugin("MemoryContextHelperRequest") {
                            on(Send) { request ->
                                val content = request.body as? TextContent
                                if (content?.contentType?.match(ContentType.Application.Json) == true) {
                                    var body = content.text
                                    if (providerRouting != null) {
                                        body = ProviderRoutingSerializer.augmentBody(body, providerRouting)
                                    }
                                    body = PromptCachingRequestSerializer.augmentOpenRouterChatBody(body, cacheKey)
                                    request.setBody(
                                        TextContent(body, content.contentType ?: ContentType.Application.Json)
                                    )
                                }
                                proceed(request)
                            }
                        })
                    }
                }
            )
        )
    }

    /** The endpoint's chat-completions base, honoring a custom path. */
    private fun composeChatHost(rawBase: String?, rawEndpoint: String?): String {
        var base = (rawBase ?: "").trim()
        if (base.isBlank()) return base
        if (!base.endsWith("/")) base += "/"
        val endpoint = (rawEndpoint ?: ApiEndpointObject.DEFAULT_CHAT_ENDPOINT).trim().trimStart('/')
        val marker = "chat/completions"
        val full = base + endpoint
        return if (full.endsWith(marker)) full.removeSuffix(marker) else base
    }

    private companion object {
        /** Background work that outlives one request: price lists and usage. */
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
