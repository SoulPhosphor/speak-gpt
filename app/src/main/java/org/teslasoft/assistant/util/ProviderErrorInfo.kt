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

package org.teslasoft.assistant.util

import org.json.JSONObject

/**
 * Pulls the two human-facing facts out of a provider's raw error response body:
 * the server's own error message, and — for aggregators like OpenRouter — the
 * upstream provider name. These live in a non-standard part of the body that the
 * OpenAI client discards while parsing, so the raw body is captured separately
 * (see the network-capture layer) and handed here.
 *
 * OpenRouter's shape is `{"error":{"code":429,"message":"…","metadata":{"provider_name":"…","raw":"…"}}}`.
 * A plain OpenAI-style body is `{"error":{"message":"…"}}` with no provider name.
 * A provider that returns non-JSON (plain text) is treated as one verbatim
 * message with no provider name.
 *
 * Uses org.json (a real copy is on the unit-test classpath), so this stays
 * JVM-testable — see ProviderErrorInfoTest.
 */
object ProviderErrorInfo {

    data class Parsed(val providerName: String?, val message: String?)

    fun parse(rawBody: String?): Parsed {
        if (rawBody.isNullOrBlank()) return Parsed(null, null)
        return try {
            val root = JSONObject(rawBody)
            val error = root.optJSONObject("error")
            // OpenRouter and OpenAI both nest the message under "error"; fall
            // back to a top-level "message" if a provider puts it there.
            val message = error?.optString("message")?.ifBlank { null }
                ?: root.optString("message").ifBlank { null }
            // OpenRouter's metadata.raw carries the upstream provider's own text,
            // which is more specific than the generic "Provider returned error".
            val raw = error?.optJSONObject("metadata")
                ?.optString("raw")?.ifBlank { null }
            val provider = error?.optJSONObject("metadata")
                ?.optString("provider_name")?.ifBlank { null }
            Parsed(provider, raw ?: message)
        } catch (_: Exception) {
            // Not a JSON object (plain-text body, or a JSON array): the whole
            // body is the verbatim message.
            Parsed(null, rawBody.trim().ifBlank { null })
        }
    }
}
