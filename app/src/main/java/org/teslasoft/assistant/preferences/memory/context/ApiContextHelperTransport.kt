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
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.DedicatedModelRouting
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.providers.ModelRequestParameters
import org.teslasoft.assistant.providers.ModelSupportedParametersStore
import org.teslasoft.assistant.providers.ProviderRoutingResolver
import org.teslasoft.assistant.providers.ProviderRoutingSerializer
import org.teslasoft.assistant.providers.RoutingBlock
import org.teslasoft.assistant.util.ProviderErrorInfo
import org.teslasoft.assistant.util.summarizer.SummarizerDetailSanitizer
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sends the API helper's request to the model chosen on Memory Context Helper
 * Tool API, with that screen's routing and parameters (M.4). It reads nothing
 * from the chat's, the Summarizer's or the Memory Scanner's settings, and
 * never falls back to another model: with no usable model it reports
 * [ApiContextAssistant.HelperUnavailable].
 */
class ApiContextHelperTransport(
    context: Context,
    /** Receives each completed request for the usage log. */
    private val usage: HelperUsage? = null
) : ApiContextAssistant.HelperTransport {

    /** Records the provider-reported usage of one helper request. */
    fun interface HelperUsage {
        suspend fun record(endpoint: ApiEndpointObject, model: String, block: suspend (Observer) -> String): String
    }

    /** Receives each response body of a helper request, so its reported
     *  usage can be read. */
    fun interface Observer {
        fun observe(status: Int, body: String)
    }

    private val appContext = context.applicationContext
    private val routing = DedicatedModelRouting.MEMORY_CONTEXT_HELPER

    private data class Target(
        val endpoint: ApiEndpointObject,
        val model: String,
        val providerRouting: com.google.gson.JsonObject?
    )

    @Volatile private var target: Target? = null
    @Volatile private var errorBody: String? = null

    /** The helper's model, endpoint and routing, or [ApiContextAssistant.HelperUnavailable]. */
    private fun resolve(): Target {
        target?.let { return it }
        val endpointId = routing.endpointId(appContext)
        val model = routing.modelId(appContext)
        if (endpointId.isBlank() || model.isBlank()) {
            throw ApiContextAssistant.HelperUnavailable("no model is set")
        }
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(appContext)
            .getApiEndpoint(appContext, endpointId)
        if (endpoint == null || endpoint.id != endpointId || endpoint.host.isBlank()) {
            throw ApiContextAssistant.HelperUnavailable("the API profile is unavailable")
        }
        val favorite = if (endpoint.isOpenRouterRouting()) routing.effective(appContext, endpointId, model) else null
        if (favorite != null && DedicatedModelRoutingPolicy.needsSetup(favorite.routingType, favorite)) {
            throw ApiContextAssistant.HelperUnavailable("provider routing is not set up")
        }
        val resolution = ProviderRoutingResolver.resolve(endpoint.isOpenRouterRouting(), favorite)
        if (resolution.block != RoutingBlock.NONE) {
            throw ApiContextAssistant.HelperUnavailable("provider routing cannot be satisfied")
        }
        return Target(endpoint, model, resolution.providerJson).also { target = it }
    }

    override fun contextWindowTokens(): Int? {
        val t = resolve()
        return t.endpoint.contextWindowTokens?.takeIf { t.endpoint.contextWindowModelId == t.model }
    }

    override suspend fun complete(request: ApiContextAssistant.HelperRequest): String {
        val t = resolve()
        val parameters = ModelRequestParameters.from(
            FavoriteModelParameters.live(appContext, ModelPickerScope.MEMORY_CONTEXT_HELPER),
            ModelSupportedParametersStore.get(appContext).supported(t.endpoint.id, t.model)
        )
        val send: suspend (Observer?) -> String = { observer ->
            val client = buildClient(t, observer)
            val chatRequest = ChatCompletionRequest(
                model = ModelId(t.model),
                messages = listOf(
                    ChatMessage(role = ChatRole.System, content = request.instructions),
                    ChatMessage(role = ChatRole.User, content = request.conversation)
                ),
                temperature = parameters.temperature,
                topP = parameters.topP,
                frequencyPenalty = parameters.frequencyPenalty,
                presencePenalty = parameters.presencePenalty,
                streamOptions = if (parameters.stream) {
                    com.aallam.openai.api.chat.StreamOptions(includeUsage = true)
                } else null
            )
            try {
                if (parameters.stream) {
                    val text = StringBuilder()
                    client.chatCompletions(chatRequest).collect { chunk ->
                        chunk.choices.firstOrNull()?.delta?.content?.takeIf { it != "null" }?.let(text::append)
                    }
                    text.toString()
                } else {
                    client.chatCompletion(chatRequest).choices.firstOrNull()?.message?.content.orEmpty()
                }
            } finally {
                client.close()
            }
        }
        errorBody = null
        val reply = usage?.record(t.endpoint, t.model) { observer -> send(observer) } ?: send(null)
        routing.recordUse(appContext, t.endpoint.id, t.model)
        return reply
    }

    /**
     * Writes one failure to the Provider Failure Log under the function name
     * Memory Context Helper (M.4 item 8): its type and technical details,
     * never conversation text or credentials. Follows the same Log Chat
     * Failures setting as every other provider failure.
     */
    override fun recordFailure(failure: ApiContextAssistant.Failure, error: Throwable?, privateValues: List<String>) {
        if (failure == ApiContextAssistant.Failure.NO_MODEL) return
        if (!Preferences.getPreferences(appContext, "").getLogChatFailures()) return
        val t = target ?: return
        val private = privateValues + t.endpoint.apiKey
        val detail = when (failure) {
            ApiContextAssistant.Failure.TIMED_OUT -> "No reply within the time allowance"
            ApiContextAssistant.Failure.INVALID_OUTPUT ->
                "The reply was not one line of search text within the embedding model's message limit"
            else -> SummarizerDetailSanitizer.sanitize(
                errorBody?.takeIf { it.isNotBlank() } ?: error?.let { "${it::class.java.simpleName}: ${it.message}" },
                private
            ) ?: "Provider returned no error message"
        }
        val provider = ProviderErrorInfo.parse(detail)
        try {
            org.teslasoft.assistant.preferences.Logger.logProviderFailure(
                appContext, t.endpoint.label, provider.providerName ?: "Not Reported", t.model,
                appContext.getString(R.string.memory_context_helper_function), detail
            )
        } catch (_: Exception) { /* A diagnostic failure never affects sending. */ }
    }

    private fun buildClient(t: Target, observer: Observer?): OpenAI {
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
                                if (!response.status.isSuccess()) errorBody = body
                                observer?.observe(response.status.value, body)
                            } catch (_: Exception) { /* best effort */ }
                        }
                    }
                    val providerRouting = t.providerRouting
                    if (endpoint.isOpenRouterRouting() && providerRouting != null) {
                        install(createClientPlugin("MemoryContextHelperProviderRouting") {
                            on(Send) { request ->
                                val content = request.body as? TextContent
                                if (content?.contentType?.match(ContentType.Application.Json) == true) {
                                    request.setBody(
                                        TextContent(
                                            ProviderRoutingSerializer.augmentBody(content.text, providerRouting),
                                            content.contentType ?: ContentType.Application.Json
                                        )
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
}
