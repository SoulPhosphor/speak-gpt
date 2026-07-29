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

package org.teslasoft.assistant.imagegen

import org.json.JSONException
import org.json.JSONObject

/**
 * Whether a specific conversation model at a specific endpoint accepts
 * tool-bearing requests (image-generation-rebuild-plan.md §8). The same
 * three-state pattern as image-input capability, but a SEPARATE
 * capability: no list of allowed models exists anywhere.
 *
 * UNKNOWN tries sending the tool; SUPPORTED means the endpoint accepted a
 * request containing tools (a clean text reply proves acceptance, not
 * refusal to use the tool); UNSUPPORTED is learned ONLY from a clear
 * tools-not-supported provider error — never from a timeout, content
 * refusal, or unrelated error (owner ruling, 2026-07-29).
 */
enum class ToolCapability(val key: String) {
    UNKNOWN("unknown"),
    SUPPORTED("supported"),
    UNSUPPORTED("unsupported");

    companion object {
        fun fromKey(key: String?): ToolCapability =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * Pure model-id → tool capability map, encoded as a compact JSON object —
 * the same shape as ImageCapabilityStore. Only SUPPORTED and UNSUPPORTED
 * entries persist; setting UNKNOWN removes the entry, which is also what
 * the §8 reset action does so a provider upgrade is never permanent.
 */
object ToolCapabilityStore {

    const val EMPTY: String = "{}"

    fun get(json: String?, modelId: String): ToolCapability {
        if (json.isNullOrBlank() || modelId.isBlank()) return ToolCapability.UNKNOWN
        val obj = parse(json) ?: return ToolCapability.UNKNOWN
        return ToolCapability.fromKey(obj.optString(modelId, "").ifEmpty { null })
    }

    fun set(json: String?, modelId: String, capability: ToolCapability): String {
        if (modelId.isBlank()) return json.orEmpty().ifBlank { EMPTY }
        val obj = parse(json) ?: JSONObject()
        if (capability == ToolCapability.UNKNOWN) {
            obj.remove(modelId)
        } else {
            obj.put(modelId, capability.key)
        }
        return if (obj.length() == 0) EMPTY else obj.toString()
    }

    fun entries(json: String?): List<Pair<String, ToolCapability>> {
        val obj = parse(json) ?: return emptyList()
        val out = ArrayList<Pair<String, ToolCapability>>(obj.length())
        val keys = obj.keys().asSequence().toMutableList()
        keys.sort()
        for (key in keys) {
            val capability = ToolCapability.fromKey(obj.optString(key, "").ifEmpty { null })
            if (capability != ToolCapability.UNKNOWN) out.add(key to capability)
        }
        return out
    }

    fun isEmpty(json: String?): Boolean = entries(json).isEmpty()

    /** Forget everything recorded — the §8 reset. */
    fun clear(): String = EMPTY

    private fun parse(json: String?): JSONObject? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json)
        } catch (_: JSONException) {
            null
        }
    }
}

/**
 * The §8/§29 strictness guard: an endpoint/model pair is marked
 * UNSUPPORTED only when the provider error CLEARLY says tools are not
 * supported. The message must both mention tools and carry a
 * not-supported signal; timeouts, content refusals, and unrelated errors
 * never match.
 */
object ToolSupportClassifier {

    fun isToolsNotSupportedError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        val mentionsTools = lower.contains("tool") ||
            lower.contains("function calling") || lower.contains("function_call")
        if (!mentionsTools) return false
        return lower.contains("not supported") ||
            lower.contains("unsupported") ||
            lower.contains("does not support") ||
            lower.contains("no endpoints found") ||
            lower.contains("not allowed") ||
            lower.contains("cannot be used")
    }
}
