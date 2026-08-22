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
import org.teslasoft.assistant.util.OutboundRequestDiagnostics

/**
 * Translates the resolved reasoning settings for a turn into provider request
 * fields and merges them into an already-serialized Chat Completions body
 * (chat-redesign-plan.md §7.9). This is the send-side counterpart of the
 * provider-routing serializer and rides the SAME body-augmentation hook, so no
 * separate request transport is introduced.
 *
 * Two wire shapes, chosen by endpoint identity, since capability is keyed to
 * the effective path (§7.9):
 *
 * - **OpenRouter** — the unified `reasoning` object: `{ "effort": "..." }` for
 *   an explicit level, `{ "enabled": false }` to disable, and `"exclude": true`
 *   when reasoning should not be returned for display. `exclude` controls
 *   RETURN only; it never disables the model's reasoning (§7.2/§7.8). When
 *   reasoning is wanted for display (Show Reasoning On, not disabled), the object
 *   also carries `"summary": "auto"` — a positive request for a provider-chosen
 *   visible reasoning summary, rather than relying on whatever the provider
 *   returns when no reasoning object is sent. Observed behavior motivating this:
 *   supplying an explicit effort WITHOUT also requesting a summary correlated
 *   with the visible reasoning disappearing, while Auto (no reasoning object)
 *   still surfaced one; sending the summary request on both paths makes the
 *   visible-reasoning request explicit instead of incidental. `summary` governs
 *   the returned summary only; it never forces reasoning on or changes effort.
 * - **Generic OpenAI-compatible** — the top-level `reasoning_effort` string;
 *   `"none"` expresses the disable signal for providers that accept it. There
 *   is no standard chat-completions field to suppress returned reasoning, so
 *   Show Reasoning is handled entirely on the receive/display side there.
 *
 * Nothing is emitted for [ReasoningEffort.AUTO] with reasoning returned (the
 * provider/model default), and nothing at all is emitted for a path not known
 * to reason — a reasoning parameter is never sent to a model that may reject
 * it. Request transformation remains fail-safe: a malformed body is returned
 * unchanged rather than risking a broken request.
 */
object ReasoningRequestSerializer {

    /** Provider-chosen visible reasoning summary. "auto" lets the provider pick
     *  an appropriate summary form rather than forcing a specific verbosity. */
    private const val SUMMARY_AUTO = "auto"

    /**
     * The fields to set on the request root for this turn, or null when there
     * is nothing to add. Shape depends on [isOpenRouter].
     */
    fun requestFields(
        resolved: ResolvedReasoning,
        isOpenRouter: Boolean,
        reasoningCapable: Boolean
    ): JsonObject? {
        // Never send a reasoning parameter to a model not known to reason.
        if (!reasoningCapable) return null

        return if (isOpenRouter) {
            openRouterFields(resolved)
        } else {
            genericFields(resolved)
        }
    }

    private fun openRouterFields(resolved: ResolvedReasoning): JsonObject? {
        val reasoning = JsonObject()
        when {
            resolved.disablesReasoning -> reasoning.addProperty("enabled", false)
            resolved.sendsExplicitLevel -> reasoning.addProperty("effort", resolved.effort.serialized)
        }
        if (resolved.disablesReasoning) {
            // Reasoning is being turned off; requesting a summary would be
            // contradictory, and Show Reasoning is moot with no reasoning.
        } else if (resolved.showReasoning) {
            // Show Reasoning On → positively request a visible summary so the
            // request for reasoning-for-display is explicit on BOTH the explicit
            // -effort path and Auto (which otherwise sends no reasoning object).
            reasoning.addProperty("summary", SUMMARY_AUTO)
        } else {
            // Show Reasoning Off → ask OpenRouter not to return reasoning
            // content. The model still reasons; only its return/display is
            // suppressed.
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
     *
     * The existing send-side hook is also the last point where the fully
     * serialized request body is available before dispatch. The final body is
     * therefore passed through [OutboundRequestDiagnostics], which records only
     * top-level field names and strips a semantically empty `logit_bias` object.
     * This does not change reasoning capability or setting resolution.
     */
    fun augmentBody(
        body: String,
        resolved: ResolvedReasoning,
        isOpenRouter: Boolean,
        reasoningCapable: Boolean
    ): String {
        val fields = requestFields(resolved, isOpenRouter, reasoningCapable)
        val augmented = if (fields == null) {
            body
        } else {
            try {
                val root = JsonParser.parseString(body).asJsonObject
                for ((key, value) in fields.entrySet()) {
                    root.add(key, value)
                }
                root.toString()
            } catch (_: Exception) {
                body
            }
        }
        return OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(augmented)
    }
}
