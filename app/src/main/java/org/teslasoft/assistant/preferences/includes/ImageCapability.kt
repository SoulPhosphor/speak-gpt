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

package org.teslasoft.assistant.preferences.includes

import org.json.JSONException
import org.json.JSONObject

/**
 * Whether a specific model at a specific endpoint accepts image input.
 *
 * The store never claims to be a global capability database. It records what
 * this endpoint has DEMONSTRATED so far — a successful vision reply marks the
 * model Supported, an unambiguous provider rejection marks it Unsupported —
 * plus any manual override the user has entered in the endpoint editor. Every
 * other model reads as [UNKNOWN] and the caller decides whether to warn.
 */
enum class ImageCapability(val key: String) {
    /** Not proven either way for this endpoint yet. */
    UNKNOWN("unknown"),

    /** A vision request against this model has already succeeded on this
     *  endpoint, or the user marked it supported by hand. */
    SUPPORTED("supported"),

    /** The provider clearly rejected image input for this model, or the user
     *  marked it unsupported by hand. */
    UNSUPPORTED("unsupported");

    companion object {
        fun fromKey(key: String?): ImageCapability =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * Pure model-id → capability map, encoded as a compact JSON object.
 *
 * Kept as pure functions so behaviour is unit-tested without Android. Only
 * [ImageCapability.SUPPORTED] and [ImageCapability.UNSUPPORTED] entries
 * persist — setting an entry back to [ImageCapability.UNKNOWN] removes it, so
 * the store's serialized form only carries proven-or-overridden classifications.
 */
object ImageCapabilityStore {

    /** Empty JSON string is the canonical "nothing recorded" form. */
    const val EMPTY: String = "{}"

    /** Read the capability recorded for [modelId]; UNKNOWN if none. */
    fun get(json: String?, modelId: String): ImageCapability {
        if (json.isNullOrBlank() || modelId.isBlank()) return ImageCapability.UNKNOWN
        val obj = parse(json) ?: return ImageCapability.UNKNOWN
        return ImageCapability.fromKey(obj.optString(modelId, "").ifEmpty { null })
    }

    /**
     * Return a JSON string with [modelId] set to [capability]. Setting a
     * value of [ImageCapability.UNKNOWN] REMOVES that entry, so the store
     * never carries "nothing to say" rows the user did not ask for.
     */
    fun set(json: String?, modelId: String, capability: ImageCapability): String {
        if (modelId.isBlank()) return json.orEmpty().ifBlank { EMPTY }
        val obj = parse(json) ?: JSONObject()
        if (capability == ImageCapability.UNKNOWN) {
            obj.remove(modelId)
        } else {
            obj.put(modelId, capability.key)
        }
        return if (obj.length() == 0) EMPTY else obj.toString()
    }

    /** Every recorded model-id + capability pair, in deterministic order. */
    fun entries(json: String?): List<Pair<String, ImageCapability>> {
        val obj = parse(json) ?: return emptyList()
        val out = ArrayList<Pair<String, ImageCapability>>(obj.length())
        val keys = obj.keys().asSequence().toMutableList()
        keys.sort()
        for (key in keys) {
            val cap = ImageCapability.fromKey(obj.optString(key, "").ifEmpty { null })
            if (cap != ImageCapability.UNKNOWN) out.add(key to cap)
        }
        return out
    }

    /** True when nothing is recorded for this endpoint yet. */
    fun isEmpty(json: String?): Boolean = entries(json).isEmpty()

    /** Discard every recorded value. Used by "Clear image capability history". */
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
