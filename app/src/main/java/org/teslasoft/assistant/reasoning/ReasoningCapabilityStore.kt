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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Per-endpoint record of reasoning capability learned from provider/model
 * metadata (chat-redesign-plan.md §7.7).
 *
 * Like the image/tool capability stores, this is a compact `model-id ->
 * capability` JSON map kept on the endpoint profile. Its purpose is to let a
 * favorite row or a request path know a model reasons WITHOUT re-fetching the
 * catalog: capability discovery runs during normal catalog work (the model
 * picker's `/models` read) and records what structured metadata established.
 *
 * Only [ReasoningSupport.KNOWN] capabilities are stored. Absence of an entry
 * reads back as "nothing recorded", so the caller falls through to live tiers
 * (direct-provider knowledge, variant markers) and finally Unknown — an absent
 * entry never means "known not to reason" (§7.7 #4). A favorite created while
 * capability was Unknown therefore gains its lightbulb automatically the first
 * time metadata records the model here (§7.7).
 *
 * Pure functions only, so behavior is unit-tested without Android.
 */
object ReasoningCapabilityStore {

    /** Canonical "nothing recorded" form. */
    const val EMPTY: String = "{}"

    // Compact per-model field keys.
    private const val K_EFFORT_CONFIGURABLE = "efc"
    private const val K_EFFORTS = "eff"
    private const val K_CAN_DISABLE = "dis"
    private const val K_VISIBLE = "vis"
    private const val K_BUDGET = "tb"
    private const val K_SOURCE = "src"
    private const val K_REQUEST_FORMAT = "wf"
    private const val K_CONTINUATION = "cont"

    /**
     * Capability recorded for [modelId], or [ReasoningCapability.UNKNOWN] when
     * nothing is stored. A stored entry always decodes to a KNOWN capability.
     */
    fun get(json: String?, modelId: String): ReasoningCapability {
        if (json.isNullOrBlank() || modelId.isBlank()) return ReasoningCapability.UNKNOWN
        val root = parse(json) ?: return ReasoningCapability.UNKNOWN
        val entry = root.optJSONObject(modelId) ?: return ReasoningCapability.UNKNOWN
        return decodeEntry(entry)
    }

    /**
     * Return a JSON string with [modelId] recorded as [capability]. A capability
     * that is not [ReasoningSupport.KNOWN] REMOVES any entry (uncertainty is not
     * persisted), so the store only ever carries established reasoning models.
     */
    fun set(json: String?, modelId: String, capability: ReasoningCapability): String {
        if (modelId.isBlank()) return json.orEmpty().ifBlank { EMPTY }
        val root = parse(json) ?: JSONObject()
        if (capability.support != ReasoningSupport.KNOWN) {
            root.remove(modelId)
        } else {
            root.put(modelId, encodeEntry(capability))
        }
        return if (root.length() == 0) EMPTY else root.toString()
    }

    /** True when nothing is recorded for this endpoint yet. */
    fun isEmpty(json: String?): Boolean {
        val root = parse(json) ?: return true
        return root.length() == 0
    }

    private fun encodeEntry(cap: ReasoningCapability): JSONObject {
        val obj = JSONObject()
        obj.put(K_EFFORT_CONFIGURABLE, cap.effortConfigurable)
        if (cap.supportedEfforts.isNotEmpty()) {
            val arr = JSONArray()
            cap.supportedEfforts.forEach { arr.put(it.serialized) }
            obj.put(K_EFFORTS, arr)
        }
        obj.put(K_CAN_DISABLE, cap.canDisableReasoning)
        obj.put(K_VISIBLE, cap.canReturnVisibleReasoning)
        obj.put(K_BUDGET, cap.tokenBudgetSupported)
        obj.put(K_SOURCE, cap.source.name)
        obj.put(K_REQUEST_FORMAT, cap.requestFormat.serialized)
        obj.put(K_CONTINUATION, cap.continuationStateSupported)
        return obj
    }

    private fun decodeEntry(entry: JSONObject): ReasoningCapability {
        val efforts = ArrayList<ReasoningEffort>()
        entry.optJSONArray(K_EFFORTS)?.let { arr ->
            for (i in 0 until arr.length()) {
                ReasoningEffort.fromSerialized(arr.optString(i, null))
                    ?.takeIf { it.isExplicitLevel }
                    ?.let { efforts.add(it) }
            }
        }
        val source = try {
            CapabilitySource.valueOf(entry.optString(K_SOURCE, CapabilitySource.PROVIDER_METADATA.name))
        } catch (_: IllegalArgumentException) {
            CapabilitySource.PROVIDER_METADATA
        }
        // Records written before the generic boundary fields were introduced
        // all came from OpenRouter catalog discovery. Preserve their original
        // continuation behavior while new records carry the explicit format.
        val requestFormat = ReasoningRequestFormat.fromSerialized(
            entry.optString(K_REQUEST_FORMAT, "")
        ) ?: ReasoningRequestFormat.OPENROUTER
        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = entry.optBoolean(K_EFFORT_CONFIGURABLE, false),
            supportedEfforts = efforts,
            canDisableReasoning = entry.optBoolean(K_CAN_DISABLE, false),
            canReturnVisibleReasoning = entry.optBoolean(K_VISIBLE, false),
            tokenBudgetSupported = entry.optBoolean(K_BUDGET, false),
            source = source,
            requestFormat = requestFormat,
            continuationStateSupported = entry.optBoolean(
                K_CONTINUATION,
                requestFormat == ReasoningRequestFormat.OPENROUTER
            )
        )
    }

    private fun parse(json: String?): JSONObject? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json)
        } catch (_: JSONException) {
            null
        }
    }
}
