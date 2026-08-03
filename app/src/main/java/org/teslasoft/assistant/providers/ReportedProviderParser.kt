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

package org.teslasoft.assistant.providers

import com.google.gson.JsonParser

/** Reads an API-reported serving provider without consulting app configuration. */
object ReportedProviderParser {

    /**
     * OpenRouter's opted-in router metadata is authoritative: use the endpoint
     * whose response marks `selected: true`. A top-level `provider` supplied by
     * another response shape is the fallback. Comments, `[DONE]`, malformed
     * JSON, and blank/missing values are ignored.
     */
    fun fromResponseLine(line: String): String? {
        val trimmed = line.trim()
        val payload = when {
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed.substring(5).trim()
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }
        if (payload.isBlank() || payload == "[DONE]") return null
        if (!payload.contains("\"provider\"") && !payload.contains("\"openrouter_metadata\"")) return null

        return try {
            val root = JsonParser.parseString(payload).asJsonObject
            val available = root.get("openrouter_metadata")
                ?.takeUnless { it.isJsonNull }
                ?.asJsonObject
                ?.get("endpoints")
                ?.takeUnless { it.isJsonNull }
                ?.asJsonObject
                ?.get("available")
                ?.takeUnless { it.isJsonNull }
                ?.asJsonArray

            if (available != null) {
                for (element in available) {
                    val endpoint = element.takeUnless { it.isJsonNull }?.asJsonObject ?: continue
                    if (endpoint.get("selected")?.takeUnless { it.isJsonNull }?.asBoolean == true) {
                        endpoint.get("provider")
                            ?.takeUnless { it.isJsonNull }
                            ?.asString
                            ?.trim()
                            ?.ifBlank { null }
                            ?.let { return it }
                    }
                }
            }

            root.get("provider")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.trim()
                ?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
