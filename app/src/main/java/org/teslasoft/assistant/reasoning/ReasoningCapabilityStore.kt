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
 * Established KNOWN and ABSENT classifications may be stored. Absence of an
 * entry still means Unknown; ABSENT is persisted only when an authoritative
 * catalog explicitly classified a live model as non-reasoning. This lets a
 * later authoritative refresh correct a previously cached lightbulb without
 * turning missing or failed metadata into a false negative.
 *
 * Pure functions only, so behavior is unit-tested without Android.
 */
object ReasoningCapabilityStore {

    /** Canonical "nothing recorded" form. */
    const val EMPTY: String = "{}"

    // Compact per-model field keys.
    private const val K_EFFORT_CONFIGURABLE = "efc"
    private const val K_SUPPORT = "sup"
    private const val K_EFFORTS = "eff"
    private const val K_CAN_DISABLE = "dis"
    private const val K_VISIBLE = "vis"
    private const val K_BUDGET = "tb"
    private const val K_SOURCE = "src"
    private const val K_AUTHORITATIVE = "auth"

    /**
     * Capability recorded for [modelId], or [ReasoningCapability.UNKNOWN] when
     * nothing is stored.
     */
    fun get(json: String?, modelId: String): ReasoningCapability {
        if (json.isNullOrBlank() || modelId.isBlank()) return ReasoningCapability.UNKNOWN
        val root = parse(json) ?: return ReasoningCapability.UNKNOWN
        val entry = root.optJSONObject(modelId) ?: return ReasoningCapability.UNKNOWN
        return decodeEntry(entry)
    }

    /**
     * Return a JSON string with [modelId] recorded as [capability]. UNKNOWN
     * removes any entry; KNOWN and authoritative ABSENT are established states.
     */
    fun set(json: String?, modelId: String, capability: ReasoningCapability): String {
        if (modelId.isBlank()) return json.orEmpty().ifBlank { EMPTY }
        val root = parse(json) ?: JSONObject()
        if (capability.support == ReasoningSupport.UNKNOWN) {
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
        obj.put(K_SUPPORT, cap.support.name)
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
        obj.put(K_AUTHORITATIVE, cap.effortsAuthoritative)
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
        val support = try {
            ReasoningSupport.valueOf(entry.optString(K_SUPPORT, ReasoningSupport.KNOWN.name))
        } catch (_: IllegalArgumentException) {
            ReasoningSupport.KNOWN
        }
        return ReasoningCapability(
            support = support,
            effortConfigurable = entry.optBoolean(K_EFFORT_CONFIGURABLE, false),
            supportedEfforts = efforts,
            canDisableReasoning = entry.optBoolean(K_CAN_DISABLE, false),
            canReturnVisibleReasoning = entry.optBoolean(K_VISIBLE, false),
            tokenBudgetSupported = entry.optBoolean(K_BUDGET, false),
            source = source,
            effortsAuthoritative = entry.optBoolean(K_AUTHORITATIVE, false)
        )
    }

    /**
     * Drop every model-id entry whose id is not in [liveModelIds]. Supports the
     * approved silent model-cleanup of learned reasoning capability for models
     * that no longer exist. Fresh catalog metadata remains authoritative — a
     * still-present model keeps its record and is refreshed by normal catalog
     * learning. Returns the input unchanged (or [EMPTY]) when nothing is removed.
     */
    fun retainOnly(json: String?, liveModelIds: Set<String>): String {
        val root = parse(json) ?: return EMPTY
        val keys = ArrayList<String>()
        val it = root.keys()
        while (it.hasNext()) keys.add(it.next())
        var changed = false
        for (key in keys) {
            if (key !in liveModelIds) {
                root.remove(key)
                changed = true
            }
        }
        if (!changed) return json?.ifBlank { EMPTY } ?: EMPTY
        return if (root.length() == 0) EMPTY else root.toString()
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
