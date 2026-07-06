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

import android.content.Context
import org.json.JSONObject
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.ScoredMemory

/**
 * The retrieval half of the runtime: embeds text, maintains the vector index,
 * and searches memories by meaning with scope isolation enforced in the store
 * query. Brute-force cosine in Kotlin (the table plan's recommendation below
 * ~50k memories; zero extra deps — the embeddings table shape supports moving
 * to sqlite-vec later without a schema change).
 *
 * Always fails soft: no installed model, a model that won't load, or an
 * inference error all degrade to keyword/tag matching so a turn is never
 * blocked because "the librarian is out sick" (enforcer spec). The pure
 * ranking math lives in [rank] so it can be unit-tested without ORT.
 */
class Librarian private constructor(private val appContext: Context) {

    companion object {
        @Volatile private var instance: Librarian? = null

        fun getInstance(context: Context): Librarian =
            instance ?: synchronized(this) {
                instance ?: Librarian(context.applicationContext).also { instance = it }
            }

        // Scoring defaults (retrieval_policy overrides them when present).
        private const val DEFAULT_W_SIM = 0.6
        private const val DEFAULT_W_IMP = 0.3
        private const val DEFAULT_W_REC = 0.1
        private const val TENTATIVE_DAMPEN = 0.6

        // Relevance floor (seed-safety audit requirement 8): top-k alone
        // surfaces weak matches when the store is small, so results below
        // this cosine similarity are dropped even if they'd make the cut.
        // Conservative for EmbeddingGemma-256's asymmetric prompts; tune
        // on-device if real queries show it cutting good matches.
        private const val MIN_SIMILARITY = 0.30f

        // Priority ladder (Stage 3.2, rules §12): scope specificity as a
        // bounded additive boost blended with relevance — a strong preference
        // among comparably relevant entries, never a hard sort tier. With
        // w_sim = 0.6, even the maximum stacked boost (~0.26) cannot let a
        // weakly-relevant specific entry beat a strongly-relevant broader one
        // (§12.4), and the MIN_SIMILARITY floor still gates everything.
        // Ladder, most specific first: campaign → rp_character → world →
        // project → companion → real_life → global.
        private val SCOPE_BOOSTS = mapOf(
            "campaign" to 0.12,
            "rp_character" to 0.10,
            "world" to 0.08,
            "project" to 0.06,
            "companion" to 0.04,
            "real_life" to 0.02,
            "global" to 0.0
        )

        /** §4 rev 3: the chat's SELECTED project boosts its memories on top of
         *  the project scope tier — selection is a boost, never a gate. */
        private const val PROJECT_SELECTED_BOOST = 0.08

        // §6: tags are softer lorebook trigger words — useful ranking hints,
        // never gatekeepers and never a forced injection.
        private const val TAG_BONUS_PER_HIT = 0.02
        private const val TAG_BONUS_CAP = 0.06

        /**
         * The Stage 3.2 context boost for one memory, pure and unit-tested:
         * scope-specificity tier + selected-project boost + tag hints (a tag
         * appearing in the query text as a whole word). Tags arrive parsed —
         * org.json is an Android stub on the JVM, so no JSON in pure code.
         */
        fun retrievalBoost(
            scope: String,
            linkedToSelectedProject: Boolean,
            tags: List<String>,
            queryLower: String
        ): Double {
            var boost = SCOPE_BOOSTS[scope] ?: 0.0
            if (linkedToSelectedProject) boost += PROJECT_SELECTED_BOOST
            var tagBonus = 0.0
            for (tag in tags) {
                val t = tag.trim().lowercase()
                if (t.length > 1 && Regex("\\b" + Regex.escape(t) + "\\b").containsMatchIn(queryLower)) {
                    tagBonus += TAG_BONUS_PER_HIT
                }
            }
            return boost + tagBonus.coerceAtMost(TAG_BONUS_CAP)
        }

        /**
         * Pure ranking: score each candidate and return the top [topK], highest
         * first. score = w_sim·cosine + w_imp·(importance/5) + w_rec·recency +
         * boost; tentative-confidence memories are dampened (before the boost,
         * so context can't launder a guess into a certainty). No Android/ORT —
         * unit tested (LibrarianRankingTest).
         */
        fun rank(
            queryVector: FloatArray,
            candidates: List<Candidate>,
            weights: Weights,
            topK: Int
        ): List<ScoredMemory> {
            val scored = candidates.map { c ->
                val sim = VectorMath.cosine(queryVector, c.vector)
                var s = weights.similarity * sim +
                    weights.importance * (c.memory.importance / 5.0) +
                    weights.recency * c.recency
                if (c.memory.provenanceConfidence.equals("tentative", ignoreCase = true)) s *= TENTATIVE_DAMPEN
                s += c.boost
                ScoredMemory(c.memory, sim, s.toFloat())
            }
            return scored.sortedByDescending { it.score }.take(topK)
        }
    }

    data class Weights(val similarity: Double, val importance: Double, val recency: Double)

    /** One ranked candidate: the memory, its stored vector, a 0..1 recency
     *  (1 = newest), and the precomputed context boost ([retrievalBoost]). */
    data class Candidate(
        val memory: RetrievableMemory,
        val vector: FloatArray,
        val recency: Double,
        val boost: Double = 0.0
    )

    @Volatile private var model: EmbeddingModel? = null
    @Volatile private var modelLoadFailed = false

    /** The active model's sidecar tag, or null when none is usable. */
    fun activeTag(): String? = EmbeddingModelStorage.activeModel(appContext)?.embeddingTag

    fun hasUsableModel(): Boolean = ensureModel() != null

    @Synchronized
    private fun ensureModel(): EmbeddingModel? {
        model?.let { return it }
        if (modelLoadFailed) return null
        val catalog = EmbeddingModelStorage.activeModel(appContext) ?: return null
        return try {
            val m = OnnxEmbeddingModel.create(appContext, catalog)
            // One-time semantic self-check per installed model (marker-cached in
            // the model dir; a re-download clears it): bad tokenization or a
            // mis-probed graph must disable semantic retrieval — keyword
            // fallback — rather than silently index garbage vectors.
            val marker = EmbeddingModelStorage.selfCheckMarker(appContext, catalog)
            if (!marker.exists()) {
                val failure = m.selfCheck()
                if (failure != null) {
                    try { m.close() } catch (_: Throwable) { }
                    modelLoadFailed = true
                    MemoryLog.log(
                        appContext, "Librarian", "error",
                        "Embedding self-check FAILED for ${catalog.id}: $failure — semantic retrieval disabled, using keyword fallback"
                    )
                    return null
                }
                try { marker.createNewFile() } catch (_: Throwable) { /* re-check next process; still correct */ }
                MemoryLog.log(appContext, "Librarian", "info", "Embedding self-check passed for ${catalog.id}")
            }
            model = m
            m
        } catch (t: Throwable) {
            modelLoadFailed = true
            MemoryLog.log(appContext, "Librarian", "error", "Embedding model failed to load: ${t.message}")
            null
        }
    }

    /**
     * Embed arbitrary text with the active model, or null when no model is
     * usable or inference fails. Phase 4's enforcer uses this for mode-signal
     * vectors and lore-entry near-duplicate checks; both degrade gracefully
     * on null (keyword scoring / word-overlap check).
     */
    fun embedOrNull(text: String, isQuery: Boolean): FloatArray? {
        val m = ensureModel() ?: return null
        return try {
            m.embed(text, isQuery)
        } catch (t: Throwable) {
            MemoryLog.log(appContext, "Librarian", "error", "Embed failed: ${t.message}")
            null
        }
    }

    /**
     * Re-embed a single memory after a hand edit so the librarian matches on
     * its current text, not a stale (or missing) vector. Best-effort: a no-op
     * when there's no usable model or the memory is gone/inactive — the
     * index-rebuild hint then covers it. Runs the embed work on the caller's
     * thread, so call it off the main thread.
     */
    fun reindexMemory(memoryId: String) {
        if (!MemoryStore.isProvisioned(appContext)) return
        val m = ensureModel() ?: return
        val store = MemoryStore.getInstance(appContext)
        val mem = store.getMemory(memoryId)?.takeIf { it.status == "active" } ?: return
        try {
            val vec = m.embed(mem.embeddingText?.takeIf { it.isNotBlank() } ?: mem.content, isQuery = false)
            store.upsertEmbedding(memoryId, m.tag, VectorMath.toBlob(vec))
        } catch (t: Throwable) {
            MemoryLog.log(appContext, "Librarian", "error", "Reindex of $memoryId failed: ${t.message}")
        }
    }

    /** Force a reload next time (after a model download/removal). */
    @Synchronized
    fun invalidateModel() {
        try { model?.close() } catch (_: Throwable) { }
        model = null
        modelLoadFailed = false
    }

    private fun weights(store: MemoryStore): Weights {
        return try {
            val policy = store.getRetrievalWeights()
            if (policy != null) Weights(policy[0], policy[1], policy[2])
            else Weights(DEFAULT_W_SIM, DEFAULT_W_IMP, DEFAULT_W_REC)
        } catch (_: Exception) {
            Weights(DEFAULT_W_SIM, DEFAULT_W_IMP, DEFAULT_W_REC)
        }
    }

    /**
     * Semantic search within a conversation's scope (Stage 3.1/3.2). Falls
     * back to keyword matching when there's no usable model or no vectors yet.
     * Returns up to [topK] scored memories, best first.
     *
     * [scope] carries the seven-category eligibility context; the gates live
     * in the store query (active status, scope categories, the fiction wall,
     * no draft-companion memories) — Phase 4 injection consumes this same
     * method, so it inherits them. [selectedProjectId] only boosts ranking
     * (§4 rev 3), it never gates eligibility.
     */
    fun search(
        scope: RetrievalScope,
        query: String,
        topK: Int,
        selectedProjectId: String? = null
    ): List<ScoredMemory> {
        if (!MemoryStore.isProvisioned(appContext)) return emptyList()
        val store = MemoryStore.getInstance(appContext)
        val candidates = store.activeMemoriesForScope(scope)
        if (candidates.isEmpty()) return emptyList()

        // Context boosts (§12 ladder + selected project + tag hints) are
        // precomputed here so [rank] stays pure and JSON-free.
        val projectMemoryIds: Set<String> =
            selectedProjectId?.takeIf { it.isNotBlank() && !scope.isRoleplay }?.let {
                try { store.memoryIdsForProject(it) } catch (_: Exception) { emptySet() }
            } ?: emptySet()
        val queryLower = query.lowercase()
        fun boostOf(mem: RetrievableMemory): Double = retrievalBoost(
            mem.scope, projectMemoryIds.contains(mem.memoryId), parseTags(mem.tagsJson), queryLower
        )

        val m = ensureModel()
        if (m != null) {
            try {
                val tag = m.tag
                val vectors = store.activeEmbeddings(tag)
                val withVectors = candidates.mapNotNull { mem ->
                    vectors[mem.memoryId]?.let { blob -> mem to VectorMath.fromBlob(blob) }
                }
                if (withVectors.isNotEmpty()) {
                    val queryVec = m.embed(query, isQuery = true)
                    val recency = recencyMap(withVectors.map { it.first })
                    val ranked = withVectors.map { (mem, vec) ->
                        Candidate(mem, vec, recency[mem.memoryId] ?: 0.0, boostOf(mem))
                    }
                    return rank(queryVec, ranked, weights(store), topK)
                        .filter { it.similarity >= MIN_SIMILARITY }
                }
            } catch (t: Throwable) {
                MemoryLog.log(appContext, "Librarian", "error", "Vector search failed, using keyword fallback: ${t.message}")
            }
        }
        return keywordFallback(candidates, query, topK)
    }

    /** tags_json -> list; a garbled column degrades to "no tags", never an error. */
    private fun parseTags(tagsJson: String): List<String> = try {
        val arr = org.json.JSONArray(tagsJson)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    /** Recency in 0..1 by created_at order among the candidates (newest = 1).
     *  ISO-8601 strings sort correctly; unparseable ones fall to the bottom. */
    private fun recencyMap(memories: List<RetrievableMemory>): Map<String, Double> {
        if (memories.isEmpty()) return emptyMap()
        val sorted = memories.sortedBy { it.createdAt }
        val n = sorted.size
        val out = HashMap<String, Double>()
        sorted.forEachIndexed { i, m -> out[m.memoryId] = if (n == 1) 1.0 else i.toDouble() / (n - 1) }
        return out
    }

    /** Whole-word, case-insensitive keyword overlap; importance breaks ties.
     *  Deliberately simple — it only has to keep the companion working when the
     *  embedding model is unavailable. */
    private fun keywordFallback(candidates: List<RetrievableMemory>, query: String, topK: Int): List<ScoredMemory> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (terms.isEmpty()) return emptyList()
        return candidates.mapNotNull { mem ->
            val hay = (mem.title + " " + mem.textToEmbed()).lowercase()
            val hits = terms.count { hay.contains(it) }
            if (hits == 0) null else {
                val s = hits.toFloat() + mem.importance / 100f
                ScoredMemory(mem, hits.toFloat() / terms.size, s)
            }
        }.sortedByDescending { it.score }.take(topK)
    }

    /**
     * (Re)embed every active memory with the current model, replacing vectors
     * from any other model so a switch re-indexes cleanly. Reports progress as
     * (done, total). Returns the number embedded, or -1 when no model is usable.
     */
    fun rebuildIndex(progress: (Int, Int) -> Unit): Int {
        if (!MemoryStore.isProvisioned(appContext)) return 0
        val m = ensureModel() ?: return -1
        val store = MemoryStore.getInstance(appContext)
        store.deleteEmbeddingsNotModel(m.tag)
        val memories = store.allActiveMemories()
        var done = 0
        for (mem in memories) {
            try {
                val vec = m.embed(mem.textToEmbed(), isQuery = false)
                store.upsertEmbedding(mem.memoryId, m.tag, VectorMath.toBlob(vec))
            } catch (t: Throwable) {
                MemoryLog.log(appContext, "Librarian", "error", "Embed failed for ${mem.memoryId}: ${t.message}")
            }
            done++
            progress(done, memories.size)
        }
        store.setMeta(MemoryStore.META_INDEX_MODEL_TAG, m.tag)
        return done
    }

    /** True when the installed model's tag differs from what the index was last
     *  built with, or memories lack vectors — the "rebuild needed" signal. */
    fun indexNeedsRebuild(): Boolean {
        if (!MemoryStore.isProvisioned(appContext)) return false
        val tag = activeTag() ?: return false
        val store = MemoryStore.getInstance(appContext)
        if (store.getMeta(MemoryStore.META_INDEX_MODEL_TAG) != tag) return true
        return store.countMissingEmbeddings(tag) > 0
    }
}
