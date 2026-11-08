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

package org.teslasoft.assistant.util.summarizer

import com.google.gson.JsonParser
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * The generation parameters one Summarizer request sends. Built from the
 * Summarizer's settings for the model, never from an API profile.
 *
 * Compatibility (owner ruling, Nov 8 2026): a parameter the service reports
 * the model does not accept is left out; when the service has not reported,
 * everything configured is sent. A request the provider refuses because of a
 * named parameter is sent once more without it ([SummarizerParameterRejection]).
 * The user's saved values are never changed by any of this, and no model
 * names are assumed.
 */
data class SummarizerRequestParameters(
    val stream: Boolean,
    val temperature: Double?,
    val topP: Double?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?
) {
    /** The request fields this request sends, by their API names. */
    fun sentNames(): Set<String> = buildSet {
        if (stream) add(STREAM)
        if (temperature != null) add(TEMPERATURE)
        if (topP != null) add(TOP_P)
        if (frequencyPenalty != null) add(FREQUENCY_PENALTY)
        if (presencePenalty != null) add(PRESENCE_PENALTY)
    }

    /** The same request without [names]. A refused stream is sent unstreamed. */
    fun without(names: Set<String>): SummarizerRequestParameters = copy(
        stream = stream && STREAM !in names,
        temperature = temperature.takeUnless { TEMPERATURE in names },
        topP = topP.takeUnless { TOP_P in names },
        frequencyPenalty = frequencyPenalty.takeUnless { FREQUENCY_PENALTY in names },
        presencePenalty = presencePenalty.takeUnless { PRESENCE_PENALTY in names }
    )

    companion object {
        const val STREAM = "stream"
        const val TEMPERATURE = "temperature"
        const val TOP_P = "top_p"
        const val FREQUENCY_PENALTY = "frequency_penalty"
        const val PRESENCE_PENALTY = "presence_penalty"

        /** [values]' five parameters, less any the service reports the model
         *  does not accept ([supported] null = not reported). Streaming is a
         *  way of delivering the reply, not a catalog parameter, so only a
         *  refusal removes it. */
        fun from(values: FavoriteModelObject, supported: Set<String>?): SummarizerRequestParameters {
            fun allowed(name: String) = supported == null || name in supported
            return SummarizerRequestParameters(
                stream = values.streaming == true,
                temperature = values.temperature?.toDouble()?.takeIf { allowed(TEMPERATURE) },
                topP = values.topP?.toDouble()?.takeIf { allowed(TOP_P) },
                frequencyPenalty = values.frequencyPenalty?.toDouble()?.takeIf { allowed(FREQUENCY_PENALTY) },
                presencePenalty = values.presencePenalty?.toDouble()?.takeIf { allowed(PRESENCE_PENALTY) }
            )
        }
    }
}

/**
 * Recognizes a provider explicitly refusing a request because of a parameter
 * it sent, and names those parameters. Anything else (a network failure, a
 * rate limit, a refusal that names no sent parameter) is not a parameter
 * refusal, so the request is not retried.
 */
object SummarizerParameterRejection {

    private val REFUSAL_STATUSES = setOf(400, 422)
    private val REFUSAL_WORDS = Regex(
        "unsupported|not supported|does not support|is not allowed|not permitted|unrecognized|unknown parameter",
        RegexOption.IGNORE_CASE
    )

    /** The sent parameters the provider refused, or empty. */
    fun rejected(status: Int?, body: String?, message: String?, sent: Set<String>): Set<String> {
        if (sent.isEmpty() || status !in REFUSAL_STATUSES) return emptySet()
        val found = LinkedHashSet<String>()
        val error = errorObject(body)
        // The error's own "param" field names the parameter exactly.
        error?.get("param")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.let { param ->
            sent.firstOrNull { it.equals(param, ignoreCase = true) }?.let(found::add)
        }
        val texts = listOfNotNull(
            error?.get("message")?.takeIf { it.isJsonPrimitive }?.asString,
            error?.get("code")?.takeIf { it.isJsonPrimitive }?.asString,
            message,
            body.takeIf { error == null }
        )
        for (text in texts) {
            if (!REFUSAL_WORDS.containsMatchIn(text)) continue
            sent.filterTo(found) { name -> mentions(text, name) }
        }
        return found
    }

    private fun mentions(text: String, name: String): Boolean =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    private fun errorObject(body: String?) = try {
        val root = body?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject
        root?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: root?.takeIf { it.has("param") }
    } catch (_: Exception) {
        null
    }
}
