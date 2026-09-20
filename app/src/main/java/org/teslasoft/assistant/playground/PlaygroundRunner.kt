/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.playground

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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.teslasoft.assistant.preferences.LogitBiasPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** One request implementation shared by the legacy and embedded Playground hosts. */
class PlaygroundRunner(
    private val preferences: Preferences,
    private val logitBiasPreferences: LogitBiasPreferences,
    endpoint: ApiEndpointObject
) {
    private val model = preferences.getModel()
    private val ai = OpenAI(
        OpenAIConfig(
            token = endpoint.apiKey,
            logging = LoggingConfig(LogLevel.None, Logger.Simple),
            timeout = Timeout(socket = 30.seconds),
            organization = null,
            headers = emptyMap(),
            host = OpenAIHost(endpoint.host),
            proxy = null,
            retry = RetryStrategy()
        )
    )

    suspend fun run(input: String, onOutput: (String) -> Unit): String {
        val messages = arrayListOf<ChatMessage>()
        preferences.getSystemMessage().takeIf { it.isNotEmpty() }?.let {
            messages.add(ChatMessage(role = ChatRole.System, content = it))
        }
        messages.add(ChatMessage(role = ChatRole.User, content = input))

        val logitConfigId = preferences.getLogitBiasesConfigId()
        val noLogitConfig = logitConfigId.isNullOrEmpty() || logitConfigId == "null"
        val fixedTemperatureModel = model.contains("gpt-5") || model.contains("o3") ||
            (!noLogitConfig && model.contains("o1"))
        val request = ChatCompletionRequest(
            model = ModelId(model),
            temperature = if (fixedTemperatureModel) {
                1.0
            } else if (preferences.getTemperature().toDouble() == 0.7) null
            else preferences.getTemperature().toDouble(),
            topP = if (preferences.getTopP().toDouble() == 1.0) null
                else preferences.getTopP().toDouble(),
            frequencyPenalty = if (preferences.getFrequencyPenalty().toDouble() == 0.0) null
                else preferences.getFrequencyPenalty().toDouble(),
            presencePenalty = if (preferences.getPresencePenalty().toDouble() == 0.0) null
                else preferences.getPresencePenalty().toDouble(),
            seed = preferences.getSeed().takeIf { it.isNotEmpty() }?.toInt(),
            logitBias = if (!noLogitConfig || model.contains("gpt-5") ||
                model.contains("o1") || model.contains("o3")
            ) null else logitBiasPreferences.getLogitBiasesMap(),
            messages = messages
        )

        var output = ""
        ai.chatCompletions(request).collect { chunk ->
            if (!currentCoroutineContext().isActive) throw CancellationException()
            chunk.choices.firstOrNull()?.delta?.content?.let {
                output += it
                onOutput(output)
            }
        }
        return output
    }
}
