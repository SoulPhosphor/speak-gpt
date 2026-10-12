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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Adds OpenRouter's stable conversation key to an ordinary Chat Completions
 * request and, only when the caller has proved it is compatible with every
 * possible routed endpoint, one ephemeral cache breakpoint.
 *
 * The newest message is deliberately excluded. The last text block before it
 * is the reusable conversation prefix: on the next turn that same prefix can
 * be read from cache, while the newly appended message remains uncached. The
 * breakpoint advances naturally as the conversation grows.
 *
 * OpenRouter translates this Anthropic-shaped `cache_control` marker for
 * providers that use a different explicit-caching wire format. Providers and
 * models that cache implicitly may ignore the marker and keep their own
 * caching behavior.
 */
object PromptCachingRequestSerializer {

    fun augmentOpenRouterChatBody(
        body: String,
        cacheKey: String? = null,
        addExplicitBreakpoint: Boolean = false
    ): String {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val messages = root.get("messages")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: return body
            var changed = false
            cacheKey?.takeIf { it.isNotBlank() }?.take(256)?.let {
                root.addProperty("prompt_cache_key", it)
                changed = true
            }
            if (addExplicitBreakpoint && messages.size() >= 2) {
                changed = markLastReusableText(messages) || changed
            }
            if (changed) root.toString() else body
        } catch (_: Exception) {
            body
        }
    }

    private fun markLastReusableText(messages: JsonArray): Boolean {
        for (messageIndex in messages.size() - 2 downTo 0) {
            val message = messages[messageIndex]
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: continue
            val content = message.get("content") ?: continue
            if (markContent(message, content)) return true
        }
        return false
    }

    private fun markContent(message: JsonObject, content: JsonElement): Boolean {
        if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            val text = content.asString
            if (text.isEmpty()) return false
            val block = JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
                add("cache_control", ephemeralMarker())
            }
            message.add("content", JsonArray().apply { add(block) })
            return true
        }
        if (!content.isJsonArray) return false

        val blocks = content.asJsonArray
        for (blockIndex in blocks.size() - 1 downTo 0) {
            val block = blocks[blockIndex]
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: continue
            val text = block.get("text")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?: continue
            if (text.isEmpty()) continue
            block.add("cache_control", ephemeralMarker())
            return true
        }
        return false
    }

    private fun ephemeralMarker(): JsonObject = JsonObject().apply {
        addProperty("type", "ephemeral")
    }
}
