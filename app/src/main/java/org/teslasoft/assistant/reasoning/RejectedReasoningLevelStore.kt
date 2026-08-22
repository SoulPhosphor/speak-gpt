/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Legacy rejection-learning codec retained for endpoint migration and model
 * cleanup. Rejected levels no longer construct the user-facing effort ladder
 * or trigger retries; supported levels now come from provider evidence.
 */
object RejectedReasoningLevelStore {

    /** Canonical "nothing recorded" form. */
    const val EMPTY: String = "{}"

    /** The only values encoded by the retired learning implementation. */
    private val LEARNABLE = setOf(ReasoningEffort.MINIMAL, ReasoningEffort.XHIGH)

    /** The set of levels [modelId] is known to reject, empty when none. */
    fun get(json: String?, modelId: String): Set<ReasoningEffort> {
        if (json.isNullOrBlank() || modelId.isBlank()) return emptySet()
        val root = parse(json) ?: return emptySet()
        val arr = root.optJSONArray(modelId) ?: return emptySet()
        val out = LinkedHashSet<ReasoningEffort>()
        for (i in 0 until arr.length()) {
            ReasoningEffort.fromSerialized(arr.optString(i, null))
                ?.takeIf { it in LEARNABLE }
                ?.let { out.add(it) }
        }
        return out
    }

    /** True when [modelId] is known to reject [level]. */
    fun isRejected(json: String?, modelId: String, level: ReasoningEffort): Boolean =
        level in get(json, modelId)

    /**
     * Return a JSON string with [level] recorded as rejected for [modelId].
     * A level outside [LEARNABLE], a blank id, or an already-recorded level
     * leaves the store unchanged, so callers can cheaply detect a real change.
     */
    fun add(json: String?, modelId: String, level: ReasoningEffort): String {
        if (modelId.isBlank() || level !in LEARNABLE) return json.orEmpty().ifBlank { EMPTY }
        val root = parse(json) ?: JSONObject()
        val existing = LinkedHashSet<ReasoningEffort>()
        root.optJSONArray(modelId)?.let { arr ->
            for (i in 0 until arr.length()) {
                ReasoningEffort.fromSerialized(arr.optString(i, null))
                    ?.takeIf { it in LEARNABLE }
                    ?.let { existing.add(it) }
            }
        }
        if (!existing.add(level)) return if (root.length() == 0) EMPTY else root.toString()
        val arr = JSONArray()
        existing.forEach { arr.put(it.serialized) }
        root.put(modelId, arr)
        return root.toString()
    }

    /**
     * Drop every model-id entry whose id is not in [liveModelIds]. Supports the
     * approved silent model-cleanup of learned data for models that no longer
     * exist; this is a self-healing cache, so a purged entry simply re-learns on
     * next use. Returns the input unchanged (or [EMPTY]) when nothing is
     * removed, so callers can cheaply detect a change.
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
