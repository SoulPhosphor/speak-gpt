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

package org.teslasoft.assistant.preferences.memory

/**
 * How a Pending suggestion's Possible Matches are presented on its Memory
 * Browser card and in Possible Match Review (Stage 6C, owner design
 * 2026-11-22). Pure Kotlin so the ordering, labels and selection rules run
 * as plain JVM tests; [PendingMatchLoader] supplies the live data.
 *
 * Nothing here finds matches: [PossibleMatchFinder] does, with or without an
 * embedding model. This only decides what each match is called, which one is
 * the closest, and which may be preselected.
 */
object PendingMatchPresentation {

    /** How a match was found, as far as the app can tell today. Keyword
     *  matches never reach a Pending card (the scanner files no keyword-only
     *  links and the Browser has no keyword layer), so there is no keyword
     *  method here (owner decision: no separate matching just for a label). */
    enum class Method {
        /** Exact wording in the same placement. */
        EXACT,
        /** The other wording of a linked pair: a Pending suggestion that links
         *  back to this one. */
        ALTERNATIVE,
        /** The embedding model's meaning match; the only method with a score. */
        SIMILARITY,
        /** Any other link the scanner saved, whose precise reason is not stored. */
        SCANNER
    }

    data class Presented(
        val memoryId: String,
        /** The matched memory's current status: active | draft | archived | superseded. */
        val status: String,
        val method: Method,
        /** Meaning similarity, only for [Method.SIMILARITY]. */
        val score: Float?
    ) {
        /** Pending matches stay review-only until Stage 6D adds their actions
         *  (owner decision): never selectable for Save & Edit Old Memory,
         *  Save & Supersede or Save & Replace. */
        val reviewOnly: Boolean get() = status == "draft"
    }

    fun methodOf(relation: MemoryMatch.Relation, status: String, linksBack: Boolean): Method? = when (relation) {
        MemoryMatch.Relation.ALREADY_PRESENT,
        MemoryMatch.Relation.EXACT_DIFFERENT_KIND,
        MemoryMatch.Relation.EXACT_INACTIVE -> Method.EXACT
        MemoryMatch.Relation.SEMANTIC_NEAR -> Method.SIMILARITY
        MemoryMatch.Relation.AI_RELATED ->
            if (status == "draft" && linksBack) Method.ALTERNATIVE else Method.SCANNER
        MemoryMatch.Relation.NONE -> null
    }

    /**
     * The matches in presentation order (owner decision): exact matches, then
     * a linked alternative, then meaning matches from the highest similarity
     * down, then other scanner links. Ties keep the finder's order. A match
     * whose memory no longer exists ([statusOf] returns null) is left out.
     */
    fun order(
        matches: List<MemoryMatch.Match>,
        statusOf: (String) -> String?,
        linksBack: (String) -> Boolean
    ): List<Presented> {
        val seen = HashSet<String>()
        val presented = matches.mapNotNull { m ->
            if (!seen.add(m.memoryId)) return@mapNotNull null
            val status = statusOf(m.memoryId) ?: return@mapNotNull null
            val method = methodOf(m.relation, status, linksBack(m.memoryId)) ?: return@mapNotNull null
            Presented(m.memoryId, status, method, if (method == Method.SIMILARITY) m.score else null)
        }
        return presented.withIndex().sortedWith(
            compareBy<IndexedValue<Presented>> { it.value.method.ordinal }
                .thenByDescending { if (it.value.method == Method.SIMILARITY) it.value.score ?: 0f else 0f }
                .thenBy { it.index }
        ).map { it.value }
    }

    /** What a Pending card shows: the closest match and how many others exist. */
    data class Summary(val closest: Presented, val additionalCount: Int)

    fun summary(ordered: List<Presented>): Summary? =
        ordered.firstOrNull()?.let { Summary(it, ordered.size - 1) }

    /**
     * The Possible Match Review arrangement. [requestedId] is the related
     * memory the user tapped (null when Review was opened from its button,
     * which opens the closest match).
     */
    data class Opened(
        val selected: Presented,
        val additional: List<Presented>,
        /** The tapped memory was deleted or is no longer a match; the current
         *  closest match is shown instead and the user is told. */
        val requestedMissing: Boolean,
        /** Memory IDs ticked when the screen opens: only the match the user
         *  opened, and never a review-only (Pending) match. */
        val preselected: Set<String>
    )

    fun open(ordered: List<Presented>, requestedId: String?): Opened? {
        if (ordered.isEmpty()) return null
        val requested = requestedId?.let { id -> ordered.firstOrNull { it.memoryId == id } }
        val missing = requestedId != null && requested == null
        val selected = requested ?: ordered.first()
        val preselected = if (missing || selected.reviewOnly) emptySet() else setOf(selected.memoryId)
        return Opened(selected, ordered.filter { it.memoryId != selected.memoryId }, missing, preselected)
    }

    /**
     * The fields a review decision about [record] depends on. A resolution is
     * applied only while every selected memory (and the proposal) still has
     * the fingerprint it had when the user saw it. Derived search text is left
     * out so a background re-index never blocks a decision.
     */
    fun reviewFingerprint(record: MemoryRecord): String = listOf(
        record.status, record.content, record.scope, record.typeId.orEmpty(),
        record.tagsJson, record.importance.toString(), record.title, record.vibe,
        record.worldIds.sorted().joinToString(","),
        record.roleplayCharacterIds.sorted().joinToString(","),
        record.campaignIds.sorted().joinToString(","),
        record.projectIds.sorted().joinToString(","),
        record.companionIds.sorted().joinToString(",")
    ).joinToString("\u0000")
}
