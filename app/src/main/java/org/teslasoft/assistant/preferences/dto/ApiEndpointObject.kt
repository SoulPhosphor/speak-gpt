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

package org.teslasoft.assistant.preferences.dto

class ApiEndpointObject(
    var label: String,
    var host: String,
    var apiKey: String,
    /* Path appended to the base URL for chat completions, e.g. "/chat/completions". */
    var chatEndpoint: String = DEFAULT_CHAT_ENDPOINT,
    /* How the API key is sent: "bearer", "x-api-key" or "api-key". */
    var authType: String = AUTH_BEARER,
    var model: String = DEFAULT_MODEL,
    var temperature: Float = DEFAULT_TEMPERATURE,
    var topP: Float = DEFAULT_TOP_P,
    var frequencyPenalty: Float = DEFAULT_FREQUENCY_PENALTY,
    var presencePenalty: Float = DEFAULT_PRESENCE_PENALTY,
    var maxTokens: Int = DEFAULT_MAX_TOKENS,
    var endSeparator: String = "",
    var prefix: String = "",
    /* Optional, free-text provider name (e.g. "OpenAI", "z.ai"). Never required;
     * shown in the profiles list in place of the base URL when the user filled it
     * in. Kept at the END of the constructor so existing positional callers stay
     * valid. */
    var provider: String = "",
    /* Per-endpoint request (socket) timeout in seconds — how long the app waits
     * for this server to respond before giving up with an N2 "server did not
     * respond in time" error. Configurable because some providers/models (e.g.
     * slow "thinking" models on a custom base URL) legitimately need longer than
     * the default. Always coerced into [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]
     * on read/write so a bad stored value can never make every request fail
     * instantly or hang for minutes. Kept at the END of the constructor so
     * existing positional callers stay valid. */
    var requestTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
) {
    companion object {
        const val DEFAULT_CHAT_ENDPOINT = "/chat/completions"
        const val AUTH_BEARER = "bearer"
        const val AUTH_X_API_KEY = "x-api-key"
        const val AUTH_API_KEY = "api-key"
        const val DEFAULT_MODEL = "gpt-4o"
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 1.0f
        const val DEFAULT_FREQUENCY_PENALTY = 0.0f
        const val DEFAULT_PRESENCE_PENALTY = 0.0f
        const val DEFAULT_MAX_TOKENS = 1500

        /* Request-timeout bounds. Default matches the value that was hard-coded
         * app-wide before this became configurable. */
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MIN_TIMEOUT_SECONDS = 5
        const val MAX_TIMEOUT_SECONDS = 300

        /** Clamp any user- or disk-supplied timeout into the allowed range. */
        fun coerceTimeoutSeconds(value: Int): Int =
            value.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    }
}
