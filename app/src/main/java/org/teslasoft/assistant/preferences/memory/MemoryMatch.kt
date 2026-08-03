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

import java.text.Normalizer
import java.util.Locale

/**
 * Possible Match detection — the deterministic, placement-aware identity and
 * near-match logic behind the Associative Memory Pending rules
 * (`Memory System/plan_one_page.md`) and the Step 1.5 hygiene behavior
 * (`Memory System/external_memory_analysis_counterplan.md` §5.2(b) / §5.7).
 *
 * Pure Kotlin so the required cases run as plain JVM unit tests (the store is
 * SQLCipher and has no JVM harness); [MemoryStore] loads the comparable
 * library, calls in here to classify, then executes the chosen resolution.
 *
 * Deterministic identity (counterplan §5.2(b)): compare Unicode-NFKC,
 * locale-independent case-folded, whitespace-collapsed CONTENT — the title is
 * excluded (models retitle the same fact) and punctuation/negation are NOT
 * stripped ("likes X" and "no longer likes X" must stay distinct) — PLUS scope
 * and the sorted stable target IDs (the same sentence in two fictional worlds
 * is legitimately two memories).
 *
 * This service never merges, deletes, replaces, or supersedes anything. It only
 * classifies. The user decides.
 */
object MemoryMatch {

    /** How one existing memory relates to a staged/edited candidate. */
    enum class Relation {
        /** Exact normalized identity, same kind, and the existing row is
         *  active or draft: a true duplicate — do not create a second draft. */
        ALREADY_PRESENT,

        /** Exact normalized identity (content + placement) against an active
         *  row whose kind would change rendering semantics (e.g. fact vs
         *  instruction). A Possible Match, never a silent metadata overwrite. */
        EXACT_DIFFERENT_KIND,

        /** Exact normalized identity against an archived or superseded row —
         *  the user chooses restore / replace / keep-separate. */
        EXACT_INACTIVE,

        /** Not identical, but text-near enough to compare. Updates, negations,
         *  and contradictions all land here — similarity cannot tell them
         *  apart, so the user decides. */
        SEMANTIC_NEAR,

        /** Unrelated. */
        NONE
    }

    /**
     * Word-overlap (Jaccard) at or above which two memories are near enough to
     * surface as a Possible Match in the no-embedding-model path. Lexical
     * overlap is the honest universal signal when no vectors exist; a vector
     * comparator can be supplied to [relate]/[classify] to raise precision when
     * a model is installed, without changing this contract.
     */
    const val SEMANTIC_TEXT_THRESHOLD = 0.6

    /** NFKC, locale-independent case-fold, whitespace-collapse. Punctuation and
     *  negation are preserved on purpose. */
    fun normalizeContent(content: String): String =
        Normalizer.normalize(content, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("\\s+"), " ")

    /** Placement identity: scope plus the sorted target IDs. */
    fun placementKey(scope: String, targetIds: Collection<String>): String =
        "$scope|${targetIds.toSortedSet().joinToString(",")}"

    /** Whether two placements can hold comparable (near-match) memories: same
     *  scope, and either both untargeted or sharing at least one target. Keeps
     *  the fiction wall intact — World A never near-matches a disjoint World B,
     *  even for identical text. */
    fun comparablePlacement(
        scopeA: String, targetsA: Collection<String>,
        scopeB: String, targetsB: Collection<String>
    ): Boolean {
        if (scopeA != scopeB) return false
        if (targetsA.isEmpty() && targetsB.isEmpty()) return true
        return targetsA.any { it in targetsB }
    }

    private fun sameExactPlacement(a: Candidate, b: Existing): Boolean =
        a.scope == b.scope && a.targetIds.toSortedSet() == b.targetIds.toSortedSet()

    private fun tokens(text: String): Set<String> =
        normalizeContent(text).split(Regex("\\W+")).filter { it.length > 2 }.toSet()

    /** Jaccard word overlap of two contents in [0,1]. */
    fun textSimilarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        return inter / (ta.size + tb.size - inter)
    }

    /** The staged (or edited) memory being checked against the library. */
    data class Candidate(
        val content: String,
        val scope: String,
        val kind: String,
        val targetIds: List<String>
    )

    /** One existing memory row loaded from the store for comparison. */
    data class Existing(
        val memoryId: String,
        val content: String,
        val scope: String,
        val kind: String,
        val status: String,        // active | draft | archived | superseded
        val targetIds: List<String>
    )

    /**
     * Classify one existing memory against the candidate. Exact identity is
     * deterministic; [SEMANTIC_NEAR] uses [similarity] (default lexical Jaccard)
     * only when identity does not match and only within a comparable placement.
     * Other pending drafts never surface as reviewable near-matches — they can
     * only trip [ALREADY_PRESENT] suppression.
     */
    fun relate(
        candidate: Candidate,
        existing: Existing,
        similarity: (String, String) -> Double = ::textSimilarity
    ): Relation {
        val exact = sameExactPlacement(candidate, existing) &&
            normalizeContent(candidate.content) == normalizeContent(existing.content)
        when (existing.status) {
            "draft" ->
                return if (exact && existing.kind == candidate.kind) Relation.ALREADY_PRESENT
                else Relation.NONE
            "active" ->
                if (exact) return if (existing.kind == candidate.kind) Relation.ALREADY_PRESENT
                else Relation.EXACT_DIFFERENT_KIND
            else -> // archived | superseded
                if (exact) return Relation.EXACT_INACTIVE
        }
        // Non-identical, non-draft: a near-match only inside a comparable
        // placement. Similarity cannot assert a duplicate — it only nominates a
        // pair for the user to compare.
        if (!comparablePlacement(candidate.scope, candidate.targetIds, existing.scope, existing.targetIds)) {
            return Relation.NONE
        }
        return if (similarity(candidate.content, existing.content) >= SEMANTIC_TEXT_THRESHOLD) {
            Relation.SEMANTIC_NEAR
        } else {
            Relation.NONE
        }
    }

    /** The whole-library decision for a staged candidate. */
    sealed class Outcome {
        /** A true duplicate exists (active/draft, same kind, exact identity):
         *  do not create a second draft. */
        object AlreadyPresent : Outcome()

        /** File the draft; these existing memories must be compared before it
         *  can be approved (the Pending caution icon and Review action). */
        data class Possible(val matches: List<Match>) : Outcome()

        /** File the draft; nothing to compare — it can be approved or discarded
         *  directly from Pending. */
        object Unique : Outcome()
    }

    data class Match(val memoryId: String, val relation: Relation)

    /**
     * Classify a candidate against the whole comparable [library]. An
     * [Outcome.AlreadyPresent] anywhere wins (suppress at staging); otherwise
     * every [Relation] other than [Relation.NONE] becomes a Possible Match.
     */
    fun classify(
        candidate: Candidate,
        library: List<Existing>,
        similarity: (String, String) -> Double = ::textSimilarity
    ): Outcome {
        val matches = ArrayList<Match>()
        for (e in library) {
            when (val r = relate(candidate, e, similarity)) {
                Relation.ALREADY_PRESENT -> return Outcome.AlreadyPresent
                Relation.NONE -> Unit
                else -> matches.add(Match(e.memoryId, r))
            }
        }
        return if (matches.isEmpty()) Outcome.Unique else Outcome.Possible(matches)
    }
}
