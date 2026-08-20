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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Echoes provider-returned reasoning state back onto the assistant message of a
 * tool-call continuation (chat-redesign-plan.md §7.2/§7.3).
 *
 * OpenRouter requires `reasoning_details` — including opaque/encrypted blocks
 * and thought signatures — to be resent unmodified on the assistant message
 * that carries `tool_calls`, or reasoning models (e.g. Gemini) reject the
 * follow-up with a 400 and degrade. The app's typed streaming client cannot
 * carry that field, and its tool continuation rebuilds the request body, so
 * this rides the same just-before-send body mutation as the reasoning request
 * fields: it finds the assistant message bearing tool_calls in the outgoing
 * body and sets its `reasoning_details` to the captured blocks.
 *
 * This is continuation STATE, not display content: it is attached whether or
 * not Show Reasoning is on, and it never surfaces in the UI or TTS. Pure and
 * fail-safe — a malformed body, a body with no tool-call assistant message, or
 * absent state leaves the body byte-for-byte unchanged.
 */
object ReasoningContinuationSerializer {

    /**
     * Return [body] with [reasoningDetails] set on its assistant tool-call
     * message, or [body] unchanged when there is nothing to attach or no such
     * message exists. When several assistant messages carry tool_calls, the
     * LAST one is chosen — the just-produced call being continued. `set`, not
     * append, so a re-sent request never accumulates duplicates.
     */
    fun attachToToolCallMessage(body: String, reasoningDetails: JsonArray?): String {
        reasoningDetails ?: return body
        if (reasoningDetails.size() == 0) return body
        return try {
            val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return body
            val messages = root.get("messages")?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonArray }?.asJsonArray ?: return body

            var target: JsonObject? = null
            for (element in messages) {
                val message = element.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val role = message.get("role")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString
                if (role != "assistant") continue
                val toolCalls = message.get("tool_calls")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonArray }?.asJsonArray
                if (toolCalls != null && toolCalls.size() > 0) target = message
            }

            val assistant = target ?: return body
            assistant.add("reasoning_details", reasoningDetails)
            root.toString()
        } catch (_: Exception) {
            body
        }
    }
}
