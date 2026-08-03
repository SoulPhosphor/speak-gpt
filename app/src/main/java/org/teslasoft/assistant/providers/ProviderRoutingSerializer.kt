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
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns a [RoutingDecision] into OpenRouter's request-body `provider` object,
 * and augments an already-serialized Chat Completions JSON body with it.
 *
 * OpenRouter has no literal "only" key: "Only" is expressed as a single-entry
 * `order` with `allow_fallbacks:false`. "Preferred" is an `order` with
 * `allow_fallbacks` as chosen. Excluded providers become `ignore`. Automatic
 * with no exclusions produces nothing (no `provider` object at all).
 *
 * [augmentBody] parses the body structurally (never string-replacement) and
 * SETS the `provider` key — overwriting any existing one — so a request that
 * passes through more than once (tool continuation, no-tools retry) always
 * carries exactly one `provider` object, never a duplicate. It is a pure
 * function of its inputs and holds no state between calls, so it cannot leak
 * one request's routing into another.
 */
object ProviderRoutingSerializer {

    /**
     * The `provider` object for [decision], or null when there is nothing to
     * send (a blocked decision, or Automatic with no exclusions). A blocked
     * decision must be handled before dispatch and never serialized.
     */
    fun providerObject(decision: RoutingDecision): JsonObject? {
        if (!decision.allowed) return null

        val obj = JsonObject()
        when {
            decision.only != null -> {
                obj.add("order", jsonArrayOf(listOf(decision.only)))
                obj.addProperty("allow_fallbacks", false)
            }
            decision.order.isNotEmpty() -> {
                obj.add("order", jsonArrayOf(decision.order))
                obj.addProperty("allow_fallbacks", decision.allowFallbacks)
            }
        }
        if (decision.ignore.isNotEmpty()) {
            obj.add("ignore", jsonArrayOf(decision.ignore))
        }
        return if (obj.size() == 0) null else obj
    }

    /**
     * Return [body] with [provider] set as its `provider` field. A null
     * [provider] leaves the body untouched. Malformed JSON is returned
     * unchanged rather than risking a broken request.
     */
    fun augmentBody(body: String, provider: JsonObject?): String {
        provider ?: return body
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            root.add("provider", provider) // set, not append — idempotent
            root.toString()
        } catch (_: Exception) {
            body
        }
    }

    private fun jsonArrayOf(values: List<String>): JsonArray {
        val array = JsonArray(values.size)
        values.forEach { array.add(it) }
        return array
    }
}
