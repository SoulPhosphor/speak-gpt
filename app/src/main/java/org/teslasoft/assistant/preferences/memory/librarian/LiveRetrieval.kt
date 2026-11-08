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

package org.teslasoft.assistant.preferences.memory.librarian

import org.teslasoft.assistant.preferences.memory.ImportanceRanking
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Live chat memory search (`Memory System/dynamic_memory_plan.md`, Part B and
 * B.5). Pure Kotlin, unit tested in LiveRetrievalTest.
 *
 * Every message runs meaning search (embedding similarity) and keyword search
 * (rare-word matching) together. A memory is sent only when it passes the
 * relevance gate:
 *  - its similarity clears a fixed floor AND stands out from this message's
 *    other similarities (median / median-absolute-deviation, so a topic with
 *    many relevant memories does not raise the bar for all of them); or
 *  - its keyword strength clears a minimum.
 * Passing nothing is a valid result. Relevance orders the result; importance
 * is a capped nudge that can never overturn a clear relevance lead. Scope only
 * decides which memories are in [search]'s corpus — it adds nothing to the
 * score — and freshness plays no part.
 *
 * The Memory Assistant's reconciliation search deliberately stays on
 * [Librarian.searchCore]; this object is used only for live chat retrieval.
 */
object LiveRetrieval {

    /**
     * Every tunable number. Defaults are starting values measured against the
     * app's default model (plan Part A); they are read from the stored
     * `retrieval_policy` record's `live_search` object when present, so they
     * can be tuned without new code.
     */
    data class Params(
        /** Meaning search: similarity a memory must reach before it can pass. */
        val similarityFloor: Double = 0.35,
        /** Meaning search: how far above this message's typical similarity a
         *  memory must stand, in robust spreads (median absolute deviation). */
        val standoutMin: Double = 2.5,
        /** Keyword search: strength (0..1) needed to pass on keywords alone. */
        val keywordMin: Double = 0.6,
        /** Smallest spread used for the stand-out measure, so a small or
         *  uniform library cannot make tiny differences look large. */
        val minSpread: Double = 0.02,
        /** Below this many searchable memories the stand-out measure is not
         *  meaningful; only [smallLibraryFloor] is used. */
        val smallLibrarySize: Int = 8,
        /** Similarity floor used alone for small libraries. */
        val smallLibraryFloor: Double = 0.40,
        /** Similarity treated as full meaning strength when ordering. */
        val fullStrengthSimilarity: Double = 0.55,
        /** Extra relevance when meaning and keyword search agree. */
        val agreementBonus: Double = 0.1,
        /** Largest importance nudge, up or down, on the 0..1 relevance scale. */
        val importanceWeight: Double = 0.1
    ) {
        /** A relevance lead larger than this always wins over any importance
         *  difference (importance moves a score by at most ±[importanceWeight]). */
        val clearLead: Double get() = 2 * importanceWeight

        companion object {
            const val KEY_SIMILARITY_FLOOR = "similarity_floor"
            const val KEY_STANDOUT_MIN = "standout_min"
            const val KEY_KEYWORD_MIN = "keyword_min"
            const val KEY_MIN_SPREAD = "min_spread"
            const val KEY_SMALL_LIBRARY_SIZE = "small_library_size"
            const val KEY_SMALL_LIBRARY_FLOOR = "small_library_floor"
            const val KEY_FULL_STRENGTH_SIMILARITY = "full_strength_similarity"
            const val KEY_AGREEMENT_BONUS = "agreement_bonus"
            const val KEY_IMPORTANCE_WEIGHT = "importance_weight"

            /**
             * Builds params from stored raw numbers (missing keys keep their
             * defaults). Each value is clamped to a sane range so corrupt or
             * hand-edited data cannot disable the gate; a substituted value is
             * reported in the returned list.
             */
            fun bounded(raw: Map<String, Double?>): Pair<Params, List<String>> {
                val d = Params()
                val notes = ArrayList<String>()
                fun pick(key: String, default: Double, lo: Double, hi: Double): Double {
                    if (!raw.containsKey(key)) return default
                    val v = raw[key]
                    if (v == null || !v.isFinite()) {
                        notes.add("live search $key is malformed — using default $default")
                        return default
                    }
                    if (v < lo || v > hi) {
                        val c = v.coerceIn(lo, hi)
                        notes.add("live search $key ($v) is out of range — using $c")
                        return c
                    }
                    return v
                }
                val floor = pick(KEY_SIMILARITY_FLOOR, d.similarityFloor, 0.0, 0.95)
                val p = Params(
                    similarityFloor = floor,
                    standoutMin = pick(KEY_STANDOUT_MIN, d.standoutMin, 0.0, 10.0),
                    keywordMin = pick(KEY_KEYWORD_MIN, d.keywordMin, 0.05, 1.01),
                    minSpread = pick(KEY_MIN_SPREAD, d.minSpread, 0.001, 0.5),
                    smallLibrarySize = pick(KEY_SMALL_LIBRARY_SIZE, d.smallLibrarySize.toDouble(), 0.0, 1000.0).toInt(),
                    smallLibraryFloor = pick(KEY_SMALL_LIBRARY_FLOOR, d.smallLibraryFloor, 0.0, 0.95),
                    fullStrengthSimilarity = pick(KEY_FULL_STRENGTH_SIMILARITY, d.fullStrengthSimilarity, 0.01, 1.0)
                        .coerceAtLeast(floor + 0.01),
                    agreementBonus = pick(KEY_AGREEMENT_BONUS, d.agreementBonus, 0.0, 1.0),
                    importanceWeight = pick(KEY_IMPORTANCE_WEIGHT, d.importanceWeight, 0.0, 0.5)
                )
                return p to notes
            }
        }
    }

    /** One searchable memory: its stored vector (null = missing or stale),
     *  parsed tags, and the display names of the things it is linked to. */
    data class Candidate(
        val memory: RetrievableMemory,
        val vector: FloatArray?,
        val tags: List<String>,
        val aliases: List<String>
    )

    /** What one search did, for diagnostics. [meaningRan] is false when no
     *  query vector was available (no model, or the query embed failed). */
    data class Diagnostics(
        val eligible: Int,
        val withVector: Int,
        val meaningRan: Boolean,
        val passed: Int
    )

    /** Every number behind one memory's result, for diagnostics. */
    data class Explanation(
        val memory: RetrievableMemory,
        /** Embedding similarity, or null when this memory had no vector or
         *  meaning search did not run. */
        val similarity: Double?,
        val standout: Double?,
        val keywordStrength: Double,
        val passedMeaning: Boolean,
        val passedKeyword: Boolean,
        val relevance: Double,
        val importanceBonus: Double,
        val score: Double
    ) {
        val passed: Boolean get() = passedMeaning || passedKeyword
    }

    data class Result(
        val ranked: List<ScoredMemory>,
        val explanations: List<Explanation>,
        val diagnostics: Diagnostics
    )

    /**
     * Searches [corpus] for [query]. [queryVector] is the query's embedding,
     * or null when meaning search is unavailable — keyword search then runs
     * alone with the same gate. Returns the memories that passed the gate,
     * best first, at most [limit] plus any relevant +3 memories beyond it
     * ([includeMandatoryImportance]).
     */
    fun search(
        query: String,
        queryVector: FloatArray?,
        corpus: List<Candidate>,
        params: Params,
        limit: Int,
        useImportanceRatings: Boolean = true,
        includeMandatoryImportance: Boolean = true
    ): Result {
        if (corpus.isEmpty()) {
            return Result(emptyList(), emptyList(), Diagnostics(0, 0, queryVector != null, 0))
        }

        // Meaning search over every memory that has a current vector. A
        // missing vector removes only that memory from meaning search.
        val sims: Map<String, Double> = if (queryVector == null) emptyMap() else {
            corpus.mapNotNull { c ->
                c.vector?.let { v -> c.memory.memoryId to VectorMath.cosine(queryVector, v).toDouble() }
            }.toMap()
        }
        val withVector = corpus.count { it.vector != null }
        val meaningRan = queryVector != null && sims.isNotEmpty()
        val small = sims.size < params.smallLibrarySize
        val median = if (sims.isEmpty()) 0.0 else median(sims.values.toList())
        val spread = if (sims.isEmpty()) params.minSpread else max(
            1.4826 * median(sims.values.map { abs(it - median) }),
            params.minSpread
        )

        // Keyword search over every memory, meaning search or not.
        val docs = corpus.associate { c -> c.memory.memoryId to documentTerms(c) }
        val idf = inverseDocumentFrequency(docs.values)
        val queryTerms = KeywordText.terms(query).filter { it.length >= 3 }.toSet()
        val idfMax = ln(1.0 + (docs.size - 0.5) / 1.5).coerceAtLeast(1e-9)

        val explanations = corpus.map { c ->
            val id = c.memory.memoryId
            val sim = sims[id]
            val standout = sim?.let { (it - median) / spread }
            val passedMeaning = sim != null && if (small) {
                sim >= max(params.similarityFloor, params.smallLibraryFloor)
            } else {
                sim >= params.similarityFloor && standout!! >= params.standoutMin
            }
            val doc = docs.getValue(id)
            val kw = (queryTerms.filter { it in doc }.sumOf { idf[it] ?: 0.0 } / idfMax).coerceIn(0.0, 1.0)
            val passedKeyword = kw >= params.keywordMin

            val strengthRange = (params.fullStrengthSimilarity - params.similarityFloor).coerceAtLeast(1e-6)
            val meaningStrength = sim?.let {
                ((it - params.similarityFloor) / strengthRange).coerceIn(0.0, 1.0)
            } ?: 0.0
            val relevance = max(meaningStrength, kw) +
                params.agreementBonus * min(meaningStrength, kw)
            val importanceBonus = if (useImportanceRatings) {
                params.importanceWeight *
                    ImportanceRanking.normalizedRankingImportance(c.memory.importance.toDouble())
            } else 0.0
            Explanation(
                memory = c.memory,
                similarity = sim,
                standout = standout,
                keywordStrength = kw,
                passedMeaning = passedMeaning,
                passedKeyword = passedKeyword,
                relevance = relevance,
                importanceBonus = importanceBonus,
                score = relevance + importanceBonus
            )
        }

        val passed = explanations.filter { it.passed }.sortedByDescending { it.score }
        val ranked = ImportanceRanking.includeMandatory(passed, limit) { e ->
            includeMandatoryImportance &&
                ImportanceRanking.isMandatory(e.memory.importance.toDouble(), useImportanceRatings)
        }.map { e -> ScoredMemory(e.memory, (e.similarity ?: 0.0).toFloat(), e.score.toFloat()) }

        return Result(
            ranked = ranked,
            explanations = explanations.sortedByDescending { it.score },
            diagnostics = Diagnostics(corpus.size, withVector, meaningRan, passed.size)
        )
    }

    /** The searchable words of one memory: its content, its condensed hint,
     *  its tags, and the names of what it is linked to. */
    private fun documentTerms(c: Candidate): Set<String> {
        val out = HashSet<String>()
        out.addAll(KeywordText.terms(c.memory.content))
        c.memory.embeddingText?.takeIf { it.isNotBlank() }?.let { out.addAll(KeywordText.terms(it)) }
        for (t in c.tags) out.addAll(KeywordText.terms(t))
        for (a in c.aliases) out.addAll(KeywordText.terms(a))
        return out
    }

    /** BM25's inverse document frequency for every term in [docs]. */
    private fun inverseDocumentFrequency(docs: Collection<Set<String>>): Map<String, Double> {
        val df = HashMap<String, Int>()
        for (d in docs) for (t in d) df[t] = (df[t] ?: 0) + 1
        val n = docs.size
        return df.mapValues { (_, f) -> ln(1.0 + (n - f + 0.5) / (f + 0.5)) }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }
}
