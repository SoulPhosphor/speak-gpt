/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.json.JSONArray
import org.json.JSONObject

/**
 * The compaction version tabs (compaction-versions-plan.md, owner-approved
 * Nov 2 2026). Each version is the whole compacted text as it stood after one
 * compaction, with the number of stored messages it covers. At most
 * [MAX_VERSIONS] are kept; one compaction means one version.
 *
 * The version in use ([State.activeIndex]) is the one sent to the main AI and
 * the one the next compaction builds on. Its current text always lives in the
 * chat's saved compacted text (so the user's edits there count); the copy in
 * this list is refreshed from it whenever the list is rewritten. Versions
 * after the one in use are Unused: they can be put back with Use This
 * Summary until the next compaction deletes them.
 */
object CompactionVersions {

    const val MAX_VERSIONS = 4

    data class Version(
        /** Stored messages this version covers, from the first message. */
        val count: Int,
        val text: String,
        /** Where the compaction that made this version started, and the text
         *  it started from: what a redo of only this step needs. */
        val baseCount: Int,
        val baseText: String
    )

    data class State(val versions: List<Version>, val activeIndex: Int) {
        val active: Version? get() = versions.getOrNull(activeIndex)

        /** Versions after the one in use: shown as Unused. */
        fun isUnused(index: Int): Boolean = index > activeIndex && index < versions.size
    }

    val EMPTY = State(emptyList(), -1)

    /* ------------------------------ storage ------------------------------ */

    fun toJson(state: State): String {
        val array = JSONArray()
        for (v in state.versions) {
            array.put(
                JSONObject()
                    .put("count", v.count)
                    .put("text", v.text)
                    .put("baseCount", v.baseCount)
                    .put("baseText", v.baseText)
            )
        }
        return JSONObject().put("active", state.activeIndex).put("versions", array).toString()
    }

    fun fromJson(json: String): State {
        if (json.isBlank()) return EMPTY
        return try {
            val root = JSONObject(json)
            val array = root.optJSONArray("versions") ?: JSONArray()
            val versions = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Version(
                    count = o.optInt("count", 0).coerceAtLeast(0),
                    text = o.optString("text", ""),
                    baseCount = o.optInt("baseCount", 0).coerceAtLeast(0),
                    baseText = o.optString("baseText", "")
                )
            }
            if (versions.isEmpty()) EMPTY
            else State(versions, root.optInt("active", versions.lastIndex).coerceIn(0, versions.lastIndex))
        } catch (_: Exception) {
            EMPTY
        }
    }

    /**
     * The stored versions with the version in use carrying the chat's current
     * compacted text. A chat compacted before versions existed gets its
     * current compacted text as its one version.
     */
    fun current(storedJson: String, currentText: String, boundary: Int): State {
        val stored = fromJson(storedJson)
        if (stored.versions.isEmpty()) {
            if (boundary <= 0 || currentText.isBlank()) return EMPTY
            return State(listOf(Version(boundary, currentText, 0, "")), 0)
        }
        val active = stored.active ?: return stored
        return stored.copy(
            versions = stored.versions.toMutableList().also {
                it[stored.activeIndex] = active.copy(text = currentText)
            }
        )
    }

    /** A finished additive compaction: Unused versions are deleted, the new
     *  version is added after the one in use, and the oldest is dropped past
     *  [MAX_VERSIONS]. */
    fun afterCompaction(state: State, added: Version): State {
        val kept = if (state.activeIndex >= 0) state.versions.take(state.activeIndex + 1) else emptyList()
        val all = (kept + added).takeLast(MAX_VERSIONS)
        return State(all, all.lastIndex)
    }

    /** Recompact Messages from Scratch: every version is replaced by one. */
    fun afterRecompactFromScratch(added: Version): State = State(listOf(added), 0)

    /** Use This Summary on [index]. */
    fun use(state: State, index: Int): State =
        if (index !in state.versions.indices) state else state.copy(activeIndex = index)

    /** Stored messages [start, end) were deleted: every version's coverage
     *  shrinks the same way as the chat's compaction marker. */
    fun afterRangeRemoval(state: State, start: Int, end: Int): State =
        state.copy(
            versions = state.versions.map {
                it.copy(
                    count = CondensedBoundaryRealignment.afterRangeRemoval(it.count, start, end),
                    baseCount = CondensedBoundaryRealignment.afterRangeRemoval(it.baseCount, start, end)
                )
            }
        )

    /** The furthest any kept version reaches, so replies inside any version
     *  (in use or Unused) stay fixed while that version can still be used. */
    fun furthestCount(state: State): Int = state.versions.maxOfOrNull { it.count } ?: 0
}
