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
    /* Per-endpoint CONNECTION timeout in seconds — how long the app waits to
     * establish a connection to this server before giving up with an N2
     * "connection timed out" error. Distinct from the response timeout below:
     * this bounds reaching the server, not waiting for the model's reply.
     * Coerced into [MIN_CONNECT_TIMEOUT_SECONDS]..[MAX_CONNECT_TIMEOUT_SECONDS]
     * on read/write so a bad stored value can never make every request fail
     * instantly or hang for minutes. Kept near the END of the constructor so
     * existing positional callers stay valid. */
    var connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    /* Per-endpoint RESPONSE timeout in seconds — once connected, how long the
     * app waits for this server to send a response before giving up with an N4
     * "response timed out" error. Defaults high (a slow "thinking" model on a
     * custom base URL can legitimately take minutes) and, by owner ruling, has
     * NO maximum — only a floor: the user may set it as high as they like and
     * stop a runaway readback with the stop button. Kept at the END of the
     * constructor so existing positional callers stay valid. */
    var responseTimeoutSeconds: Int = DEFAULT_RESPONSE_TIMEOUT_SECONDS
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

        /* Connection-timeout bounds. Default matches the value that was
         * hard-coded app-wide before this became configurable. */
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 30
        const val MIN_CONNECT_TIMEOUT_SECONDS = 5
        const val MAX_CONNECT_TIMEOUT_SECONDS = 300

        /* Response-timeout bounds. High default for slow models; a floor but
         * NO ceiling (owner ruling — the user may set it arbitrarily high). */
        const val DEFAULT_RESPONSE_TIMEOUT_SECONDS = 600
        const val MIN_RESPONSE_TIMEOUT_SECONDS = 45

        /** Clamp a connection timeout into its allowed range. */
        fun coerceConnectTimeoutSeconds(value: Int): Int =
            value.coerceIn(MIN_CONNECT_TIMEOUT_SECONDS, MAX_CONNECT_TIMEOUT_SECONDS)

        /** Clamp a response timeout to its floor. No upper bound by design. */
        fun coerceResponseTimeoutSeconds(value: Int): Int =
            value.coerceAtLeast(MIN_RESPONSE_TIMEOUT_SECONDS)
    }
}
