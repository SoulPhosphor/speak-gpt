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

package org.teslasoft.assistant.reasoning

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Translates the resolved reasoning settings for a turn into provider request
 * fields and merges them into an already-serialized Chat Completions body
 * (chat-redesign-plan.md §7.9). This is the send-side counterpart of the
 * provider-routing serializer and rides the SAME body-augmentation hook, so no
 * separate request transport is introduced.
 *
 * Two wire shapes, chosen by the normalized request boundary on the capability
 * object. Callers never pass an OpenRouter boolean into this layer:
 *
 * - **OpenRouter** — the unified `reasoning` object: `{ "effort": "..." }` for
 *   an explicit level, `{ "enabled": false }` to disable, and `"exclude": true`
 *   when reasoning should not be returned for display. `exclude` controls
 *   RETURN only; it never disables the model's reasoning (§7.2/§7.8).
 * - **Generic OpenAI-compatible** — the top-level `reasoning_effort` string;
 *   `"none"` expresses the disable signal for providers that accept it. There
 *   is no standard chat-completions field to suppress returned reasoning, so
 *   Show Reasoning is handled entirely on the receive/display side there.
 *
 * Nothing is emitted for [ReasoningEffort.AUTO] with reasoning returned (the
 * provider/model default), and nothing at all is emitted for a path not known
 * to reason — a reasoning parameter is never sent to a model that may reject
 * it. Every function is pure and fail-safe: a malformed body is returned
 * unchanged rather than risking a broken request.
 */
object ReasoningRequestSerializer {

    /**
     * The fields to set on the request root for this turn, or null when there
     * is nothing to add. Unsupported saved values are rejected defensively
     * here as well as by [ReasoningSettingsResolver].
     */
    fun requestFields(
        resolved: ResolvedReasoning,
        capability: ReasoningCapability
    ): JsonObject? {
        // Never send a reasoning parameter to a model not known to reason.
        if (!capability.isReasoningCapable || !capability.supports(resolved.effort)) return null

        return if (capability.requestFormat == ReasoningRequestFormat.OPENROUTER) {
            openRouterFields(resolved, capability.canReturnVisibleReasoning)
        } else {
            genericFields(resolved)
        }
    }

    private fun openRouterFields(
        resolved: ResolvedReasoning,
        canReturnVisibleReasoning: Boolean
    ): JsonObject? {
        val reasoning = JsonObject()
        when {
            resolved.disablesReasoning -> reasoning.addProperty("enabled", false)
            resolved.sendsExplicitLevel -> reasoning.addProperty("effort", resolved.effort.serialized)
        }
        // Show Reasoning Off → ask OpenRouter not to return reasoning content.
        // The model still reasons; only its return/display is suppressed.
        if (!resolved.showReasoning && canReturnVisibleReasoning && !resolved.disablesReasoning) {
            reasoning.addProperty("exclude", true)
        }
        if (reasoning.size() == 0) return null
        val root = JsonObject()
        root.add("reasoning", reasoning)
        return root
    }

    private fun genericFields(resolved: ResolvedReasoning): JsonObject? {
        val effortValue = when {
            resolved.disablesReasoning -> "none"
            resolved.sendsExplicitLevel -> resolved.effort.serialized
            else -> return null // AUTO — send no explicit effort
        }
        val root = JsonObject()
        root.addProperty("reasoning_effort", effortValue)
        return root
    }

    /**
     * Return [body] with this turn's reasoning fields merged in, or [body]
     * unchanged when there is nothing to add or the body is not parseable JSON.
     * Existing keys of the same name are overwritten (set, not appended), so a
     * request that passes through more than once (tool continuation, retry)
     * carries exactly one reasoning instruction.
     */
    fun augmentBody(
        body: String,
        resolved: ResolvedReasoning,
        capability: ReasoningCapability
    ): String {
        val fields = requestFields(resolved, capability) ?: return body
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            for ((key, value) in fields.entrySet()) {
                root.add(key, value)
            }
            root.toString()
        } catch (_: Exception) {
            body
        }
    }
}
