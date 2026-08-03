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
 * Possible Match detection — the DETERMINISTIC, placement-aware identity layer
 * behind the Associative Memory Pending rules (`Memory System/plan_one_page.md`)
 * and the Step 1.5 hygiene behavior
 * (`Memory System/external_memory_analysis_counterplan.md` §5.2(b) / §5.7).
 *
 * This object decides EXACT identity only: duplicates, placement, type, and
 * status. It never uses similarity of any kind — the differently-worded,
 * semantically-related layer is the installed local embedding model, wired in
 * [PossibleMatchFinder]. Deterministic matching works with or without a model;
 * semantic matching needs the model and is unavailable without it. Nothing here
 * (or there) ever merges, deletes, replaces, or supersedes anything — it only
 * classifies. The user decides.
 *
 * Pure Kotlin so the required cases run as plain JVM unit tests (the store is
 * SQLCipher and has no JVM harness); [MemoryStore] loads the comparable library
 * and calls in here to classify.
 *
 * Deterministic identity (counterplan §5.2(b)): compare Unicode-NFKC,
 * locale-independent case-folded, whitespace-collapsed CONTENT — the title is
 * excluded (models retitle the same fact) and punctuation/negation are NOT
 * stripped ("likes X" and "no longer likes X" must stay distinct) — PLUS scope
 * and the sorted stable target IDs (the same sentence in two fictional worlds
 * is legitimately two memories).
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

        /** Differently worded but semantically related, found by the local
         *  embedding model — NOT produced here (this object is deterministic);
         *  [PossibleMatchFinder] tags its vector-search candidates with it. */
        SEMANTIC_NEAR,

        /** Unrelated. */
        NONE
    }

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

    /** Whether two placements can hold comparable memories: same scope, and
     *  either both untargeted or sharing at least one target. Bounds the
     *  embedding search and keeps the fiction wall intact — World A never
     *  matches a disjoint World B, even for identical text. */
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
     * Classify one existing memory against the candidate — EXACT identity only.
     * A same-placement, same-content pending draft or active memory of the same
     * kind is [ALREADY_PRESENT]; the same content with a different active kind is
     * [EXACT_DIFFERENT_KIND]; the same content on an archived/superseded row is
     * [EXACT_INACTIVE]. Everything else is [NONE] here — near-matches are the
     * embedding model's job, not this object's.
     */
    fun relate(candidate: Candidate, existing: Existing): Relation {
        val exact = sameExactPlacement(candidate, existing) &&
            normalizeContent(candidate.content) == normalizeContent(existing.content)
        if (!exact) return Relation.NONE
        return when (existing.status) {
            "draft" -> if (existing.kind == candidate.kind) Relation.ALREADY_PRESENT else Relation.NONE
            "active" -> if (existing.kind == candidate.kind) Relation.ALREADY_PRESENT else Relation.EXACT_DIFFERENT_KIND
            else -> Relation.EXACT_INACTIVE // archived | superseded
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

        /** File the draft; nothing exactly matches — direct approve/discard.
         *  (The embedding layer may still find a semantic match; that is decided
         *  in [PossibleMatchFinder], not here.) */
        object Unique : Outcome()
    }

    data class Match(val memoryId: String, val relation: Relation)

    /**
     * Classify a candidate against the whole comparable [library] on exact
     * identity. An [Outcome.AlreadyPresent] anywhere wins (suppress at staging);
     * otherwise every exact [Relation] other than [Relation.NONE] becomes a
     * Possible Match.
     */
    fun classify(candidate: Candidate, library: List<Existing>): Outcome {
        val matches = ArrayList<Match>()
        for (e in library) {
            when (val r = relate(candidate, e)) {
                Relation.ALREADY_PRESENT -> return Outcome.AlreadyPresent
                Relation.NONE -> Unit
                else -> matches.add(Match(e.memoryId, r))
            }
        }
        return if (matches.isEmpty()) Outcome.Unique else Outcome.Possible(matches)
    }
}
