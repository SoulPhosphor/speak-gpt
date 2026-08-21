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
 * Per-endpoint record of reasoning-effort levels a model was PROVEN not to
 * accept (dynamic minimal/xhigh learning, owner ruling Aug 2026).
 *
 * Only the two optimistically offered extremes — minimal and extra high — can
 * be learned-rejected; when a model refuses one, its token is recorded here so
 * that level is never offered for that model again. Like the capability store,
 * this is a compact `model-id -> [level, …]` JSON map kept on the endpoint
 * profile, and every function is pure so behavior is unit-tested without
 * Android.
 *
 * Absence means "nothing learned" — never "supported". The offered ladder starts
 * optimistic and this store only ever SUBTRACTS from it.
 */
object RejectedReasoningLevelStore {

    /** Canonical "nothing recorded" form. */
    const val EMPTY: String = "{}"

    /** The explicit levels this store may carry — the only optimistically
     *  offered, and therefore rejectable, levels. */
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

    private fun parse(json: String?): JSONObject? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json)
        } catch (_: JSONException) {
            null
        }
    }
}
