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

import com.google.gson.JsonParser

/**
 * Privacy-safe diagnostics for the final serialized chat-completions request.
 *
 * Only top-level JSON field NAMES are retained. Field values, message text,
 * prompts, tool arguments, headers, API keys, and every other request value are
 * deliberately never copied into this store.
 *
 * This also removes an empty `logit_bias` object before dispatch. An empty bias
 * map has no request semantics, while providers that do not support the
 * parameter may reject the request merely because the field is present.
 */
object OutboundRequestDiagnostics {
    @Volatile
    private var latestOutboundFieldNames: List<String>? = null

    /**
     * Inspect a serialized Chat Completions body at the final send-side
     * augmentation seam. Both streamed and completed requests are captured;
     * auxiliary non-chat bodies are returned unchanged and do not leave stale
     * diagnostic state behind. The generation hook is the caller-side boundary
     * that keeps auto-naming and other auxiliary chat requests out of this log.
     */
    fun sanitizeAndCaptureSerializedChatBody(body: String): String {
        latestOutboundFieldNames = null

        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val isChatGeneration = root.has("model") && root.has("messages")
            if (!isChatGeneration) return body

            var changed = false
            val logitBias = root.get("logit_bias")
            if (logitBias != null && (
                    logitBias.isJsonNull ||
                        (logitBias.isJsonObject && logitBias.asJsonObject.size() == 0)
                    )
            ) {
                root.remove("logit_bias")
                changed = true
            }

            latestOutboundFieldNames = root.keySet().sorted()
            if (changed) root.toString() else body
        } catch (_: Exception) {
            body
        }
    }

    /** Snapshot safe to use from the later provider-failure path. */
    fun latestFieldNames(): List<String>? = latestOutboundFieldNames?.toList()

    fun latestFieldNamesText(): String? = latestOutboundFieldNames
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
}
