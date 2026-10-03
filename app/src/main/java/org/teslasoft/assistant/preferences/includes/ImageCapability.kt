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
 * the provider has stated or this exact route has DEMONSTRATED so far — live
 * catalog metadata, a successful vision reply, or an unambiguous provider
 * rejection — plus any manual override entered in the endpoint editor. Every
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
 * Compact capability map encoded as JSON. Legacy/manual rows use model ids;
 * provider metadata and learned route results use reserved internal prefixes.
 *
 * Kept as pure functions so behaviour is unit-tested without Android. Only
 * [ImageCapability.SUPPORTED] and [ImageCapability.UNSUPPORTED] entries
 * persist — setting an entry back to [ImageCapability.UNKNOWN] removes it, so
 * the store's serialized form only carries proven-or-overridden classifications.
 */
object ImageCapabilityStore {

    private const val METADATA_PREFIX = "@metadata:"
    private const val LEARNED_PREFIX = "@learned:"

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

    /** Provider catalog evidence is kept separate from observed request
     * failures. A refreshed catalog can therefore replace stale metadata while
     * a route-specific rejection remains available as lower-priority evidence. */
    fun getMetadata(json: String?, modelId: String): ImageCapability =
        get(json, metadataKey(modelId))

    fun setMetadata(
        json: String?,
        modelId: String,
        capability: ImageCapability
    ): String = set(json, metadataKey(modelId), capability)

    /** A definitive request result belongs to one exact outgoing route, not to
     * every endpoint/provider that happens to expose the same model id. */
    fun getLearned(json: String?, scopeKey: String): ImageCapability =
        get(json, learnedKey(scopeKey))

    fun setLearned(
        json: String?,
        scopeKey: String,
        capability: ImageCapability
    ): String = set(json, learnedKey(scopeKey), capability)

    /** Replace every definitive capability supplied by a successful catalog
     * refresh. An entry with no usable capability fields is not evidence that
     * richer metadata learned earlier became false. Manual legacy rows and
     * route-scoped learned results are untouched. */
    fun refreshMetadata(
        json: String?,
        capabilities: Map<String, ImageCapability>
    ): String {
        var current = json.orEmpty().ifBlank { EMPTY }
        for ((modelId, capability) in capabilities) {
            if (capability == ImageCapability.UNKNOWN) continue
            current = setMetadata(current, modelId, capability)
        }
        return current
    }

    fun metadataEntries(json: String?): Map<String, ImageCapability> =
        entries(json).mapNotNull { (key, capability) ->
            key.removePrefix(METADATA_PREFIX)
                .takeIf { key.startsWith(METADATA_PREFIX) }
                ?.let { it to capability }
        }.toMap()

    private fun metadataKey(modelId: String): String = METADATA_PREFIX + modelId.trim()
    private fun learnedKey(scopeKey: String): String = LEARNED_PREFIX + scopeKey

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
