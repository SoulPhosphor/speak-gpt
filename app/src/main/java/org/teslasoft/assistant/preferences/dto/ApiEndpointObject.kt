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

import org.teslasoft.assistant.util.Hash

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
     * stop a runaway readback with the stop button. Kept near the END of the
     * constructor so existing positional callers stay valid. */
    var responseTimeoutSeconds: Int = DEFAULT_RESPONSE_TIMEOUT_SECONDS,
    /* Stable identity of this endpoint profile. Minted ONCE at creation and never
     * recomputed from [label], so renaming keeps the encrypted API key, favorite
     * models and per-chat endpoint selection attached to the same profile. Empty
     * only for a brand-new object; [ApiEndpointPreferences.setApiEndpoint] assigns
     * one on first save. Existing profiles keep their original hashed id (the
     * preference key). The built-in "Default" profile uses the reserved
     * [DEFAULT_ENDPOINT_ID] so the default per-chat reference keeps resolving.
     * Kept at the END of the constructor so existing positional callers stay valid. */
    var id: String = "",
    /**
     * Optional total context capacity for this exact endpoint profile and
     * [contextWindowModelId]. Null means unknown and never blocks Send.
     */
    var contextWindowTokens: Int? = null,
    /** Exact model id the optional context value belongs to. */
    var contextWindowModelId: String = "",
    /**
     * Compact JSON map of `model-id -> capability key` recording which of this
     * endpoint's models are known to accept (or refuse) image input. Only
     * proven or user-overridden classifications persist; models not in the map
     * read as [ImageCapability.UNKNOWN] at the check site. See
     * [ImageCapabilityStore] for the format. Kept at the END of the
     * constructor so existing positional callers stay valid.
     */
    var imageCapabilityByModel: String = "",
    /**
     * Compact JSON map of `model-id -> capability key` recording which of
     * this endpoint's models are known to accept (or clearly refuse)
     * TOOL-BEARING requests (image-generation-rebuild-plan.md §8). A
     * separate capability from image input, same store shape; see
     * [org.teslasoft.assistant.imagegen.ToolCapabilityStore]. Kept at the
     * END of the constructor so existing positional callers stay valid.
     */
    var toolCapabilityByModel: String = "",
    /**
     * Path appended to the base URL to discover a model's provider endpoints
     * (OpenRouter provider routing). {model} is replaced with the model id.
     * Blank means use [DEFAULT_PROVIDER_DISCOVERY_PATH]; editable on the
     * endpoint editor's Advanced Options section for custom API proxies. Kept
     * at the END of the constructor so existing positional callers stay valid.
     */
    var providerDiscoveryPath: String = "",
    /**
     * Persisted routing identity of this endpoint: [IDENTITY_GENERIC] or
     * [IDENTITY_OPENROUTER]. It is established once — a recognized standard
     * OpenRouter base URL marks the endpoint OPENROUTER on save (or on
     * migration of an existing profile) — and is STICKY: later base-URL edits
     * never demote it. Only OPENROUTER endpoints expose provider routing and
     * ever serialize an OpenRouter `provider` object; a GENERIC endpoint (e.g.
     * a plain custom proxy created from scratch) never does. Distinct from the
     * image-generation host check in ImageProviderAdapters, which is unrelated
     * and unchanged. Kept at the END of the constructor so existing positional
     * callers stay valid.
     */
    var identity: String = IDENTITY_GENERIC
) {
    /** True when this endpoint carries OpenRouter routing identity. */
    fun isOpenRouterRouting(): Boolean = identity == IDENTITY_OPENROUTER

    companion object {
        const val IDENTITY_GENERIC = "generic"
        const val IDENTITY_OPENROUTER = "openrouter"

        /** The recognition rule for a standard OpenRouter base URL. Matches the
         *  rest of the app's host check so identity lines up with it. */
        fun isRecognizedOpenRouterUrl(host: String): Boolean =
            host.contains("openrouter.ai", ignoreCase = true)


        /* Reserved, fixed id for the built-in "Default" endpoint. It is NOT a
         * name-derived identity in the mutable sense: the profile keeps this id
         * even if the user renames its label, and it is the value every
         * install's default per-chat reference already points at
         * (Preferences.getApiEndpointId defaults to it), so it is preserved
         * verbatim as this record's permanent, constant id. */
        val DEFAULT_ENDPOINT_ID: String = Hash.hash("Default")

        const val DEFAULT_CHAT_ENDPOINT = "/chat/completions"
        /** OpenRouter's provider-discovery path; {model} → the model id. */
        const val DEFAULT_PROVIDER_DISCOVERY_PATH = "/models/{model}/endpoints"
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
