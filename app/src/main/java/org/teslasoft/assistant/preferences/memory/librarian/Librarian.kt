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
import org.teslasoft.assistant.preferences.memory.RetrievalPolicy
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

        private const val TENTATIVE_DAMPEN = 0.6

        // Relevance floor (seed-safety audit requirement 8): top-k alone
        // surfaces weak matches when the store is small, so results below
        // this cosine similarity are dropped even if they'd make the cut.
        // Conservative for EmbeddingGemma-256's asymmetric prompts; tune
        // on-device if real queries show it cutting good matches.
        private const val MIN_SIMILARITY = 0.30f

        // memory-doc-v2 lexical matching (counterplan §10 A.2): whole Unicode
        // word tokens only — no substring hit for "cat" in "catalog". Query
        // terms shorter than this are noise and skipped.
        private val TOKEN_BOUNDARY = Regex("[^\\p{L}\\p{Nd}]+")
        private const val MIN_TERM_LENGTH = 3

        /** Bounded bonus when a query term hits the title — the user's own
         *  name for the fact — applied after dampening like the context
         *  boost, so it breaks ties without overwhelming relevance. */
        private const val TITLE_HIT_BONUS = 0.05

        /** Whole word tokens of [text], lowercased. Pure. */
        fun lexicalTokens(text: String): Set<String> =
            text.lowercase().split(TOKEN_BOUNDARY).filter { it.isNotEmpty() }.toSet()

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
         *
         * The relevance floor is applied BEFORE top-K (counterplan §5.5):
         * importance/recency must not let an irrelevant hit consume a slot
         * while relevant candidates wait below it.
         */
        fun rank(
            queryVector: FloatArray,
            candidates: List<Candidate>,
            weights: Weights,
            topK: Int,
            minSimilarity: Float = 0f
        ): List<ScoredMemory> {
            val scored = candidates.mapNotNull { c ->
                val sim = VectorMath.cosine(queryVector, c.vector)
                if (sim < minSimilarity) return@mapNotNull null
                var s = weights.similarity * sim +
                    weights.importance * (c.memory.importance / 5.0) +
                    weights.recency * c.recency
                if (c.memory.provenanceConfidence.equals("tentative", ignoreCase = true)) s *= TENTATIVE_DAMPEN
                s += c.boost
                ScoredMemory(c.memory, sim, s.toFloat())
            }
            return scored.sortedByDescending { it.score }.take(topK)
        }

        /**
         * Pure lexical ranking over the COMPLETE eligible set — the fallback
         * whenever the vector index cannot be trusted (no model, load failure,
         * partial or stale index). Same ranking contract as [rank]
         * (counterplan §5.5 — the fallback must not bypass it): whole-token
         * overlap across the memory-doc-v2 lexical document (title + current
         * content + optional condensed text + tags) is the relevance gate,
         * then bounded importance/recency/context boosts order the relevant
         * candidates. A candidate with no token hit is ineligible, so a scope
         * boost can never inject an unrelated memory.
         */
        fun rankLexical(
            query: String,
            candidates: List<LexicalCandidate>,
            weights: Weights,
            topK: Int
        ): List<ScoredMemory> {
            val terms = lexicalTokens(query).filter { it.length >= MIN_TERM_LENGTH }
            if (terms.isEmpty()) return emptyList()
            val scored = candidates.mapNotNull { c ->
                val mem = c.memory
                val titleTokens = lexicalTokens(mem.title)
                val docTokens = HashSet(titleTokens)
                docTokens.addAll(lexicalTokens(mem.content))
                mem.embeddingText?.takeIf { it.isNotBlank() }?.let { docTokens.addAll(lexicalTokens(it)) }
                for (tag in c.tags) docTokens.addAll(lexicalTokens(tag))
                for (alias in c.aliases) docTokens.addAll(lexicalTokens(alias))
                val hits = terms.count { it in docTokens }
                if (hits == 0) return@mapNotNull null
                val relevance = hits.toFloat() / terms.size
                var s = weights.similarity * relevance +
                    weights.importance * (mem.importance / 5.0) +
                    weights.recency * c.recency
                if (mem.provenanceConfidence.equals("tentative", ignoreCase = true)) s *= TENTATIVE_DAMPEN
                s += c.boost
                if (terms.any { it in titleTokens }) s += TITLE_HIT_BONUS
                ScoredMemory(mem, relevance, s.toFloat())
            }
            return scored.sortedByDescending { it.score }.take(topK)
        }

        /**
         * The pure search pipeline (counterplan §10 A.2, unit tested in
         * LibrarianSearchCoreTest): semantic ranking runs only when EVERY
         * corpus row carries a current-document vector and the query embeds;
         * any missing vector — no model, an interrupted rebuild, a stale
         * document version — routes the whole turn to lexical ranking over
         * the complete corpus. [embedQuery] is invoked only when the index
         * is complete, so an incomplete index costs no model work.
         */
        fun searchCore(
            query: String,
            embedQuery: () -> FloatArray?,
            corpus: List<CorpusMemory>,
            weights: Weights,
            topK: Int
        ): List<ScoredMemory> {
            if (corpus.isEmpty()) return emptyList()
            if (corpus.all { it.vector != null }) {
                val queryVec = embedQuery()
                if (queryVec != null) {
                    return rank(
                        queryVec,
                        corpus.map { Candidate(it.memory, it.vector!!, it.recency, it.boost) },
                        weights, topK, MIN_SIMILARITY
                    )
                }
            }
            return rankLexical(
                query,
                corpus.map { LexicalCandidate(it.memory, it.tags, it.recency, it.boost, it.aliases) },
                weights, topK
            )
        }

        /**
         * Freshness in 0..1 among the candidates (1 = freshest), ordered by
         * updated-at-or-created-at so a corrected memory ranks fresher than
         * its obsolete peers (counterplan §5.5). ISO-8601 strings sort
         * correctly; unparseable ones fall to the bottom. Pure.
         */
        fun freshness(memories: List<RetrievableMemory>): Map<String, Double> {
            if (memories.isEmpty()) return emptyMap()
            val sorted = memories.sortedBy { m -> m.updatedAt?.takeIf { it.isNotBlank() } ?: m.createdAt }
            val n = sorted.size
            val out = HashMap<String, Double>()
            sorted.forEachIndexed { i, m -> out[m.memoryId] = if (n == 1) 1.0 else i.toDouble() / (n - 1) }
            return out
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

    /** One lexical candidate: the memory, its parsed tags, a 0..1 recency
     *  (1 = freshest), the precomputed context boost ([retrievalBoost]), and
     *  the stable target display names (world/campaign/character/companion/
     *  project) so a memory is findable by the name of the thing it is
     *  about. Tags arrive parsed — org.json is an Android stub on the JVM. */
    data class LexicalCandidate(
        val memory: RetrievableMemory,
        val tags: List<String>,
        val recency: Double,
        val boost: Double = 0.0,
        val aliases: List<String> = emptyList()
    )

    /** One corpus row for [searchCore]: the memory, its current-document
     *  vector when one exists (null = missing/stale), parsed tags, target
     *  display names, freshness, and context boost. */
    data class CorpusMemory(
        val memory: RetrievableMemory,
        val vector: FloatArray?,
        val tags: List<String>,
        val aliases: List<String>,
        val recency: Double,
        val boost: Double
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
            val doc = RetrievalDocument.semanticDocument(
                mem.title, mem.content, mem.embeddingText, parseTags(mem.tagsJson)
            )
            val vec = m.embed(doc, isQuery = false)
            store.upsertEmbedding(memoryId, RetrievalDocument.effectiveKey(m.tag), VectorMath.toBlob(vec))
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

    /** Stored scoring weights, bounded (counterplan §5.5): a non-finite,
     *  negative, or all-zero stored set degrades to the defaults with a log
     *  note rather than producing nonsense scores. */
    private fun weights(store: MemoryStore): Weights {
        val raw = try { store.getRetrievalWeights() } catch (_: Exception) { null }
        val bounded = RetrievalPolicy.boundWeights(raw)
        bounded.substitutionNote?.let { MemoryLog.log(appContext, "Librarian", "info", it) }
        return Weights(bounded.value[0], bounded.value[1], bounded.value[2])
    }

    /**
     * Semantic search within a conversation's scope (Stage 3.1/3.2). Returns
     * up to [topK] scored memories, best first.
     *
     * Complete-set rule (counterplan §10 A.2): semantic ranking runs only
     * when EVERY eligible candidate has a current vector. A partial index
     * must never shrink the candidate universe — a newly imported or edited
     * memory without a vector would silently disappear from retrieval. While
     * the index is incomplete (or there is no usable model), lexical ranking
     * covers the complete eligible set instead.
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
        // precomputed here so the ranking functions stay pure and JSON-free.
        val projectMemoryIds: Set<String> =
            selectedProjectId?.takeIf { it.isNotBlank() && !scope.isRoleplay }?.let {
                try { store.memoryIdsForProject(it) } catch (_: Exception) { emptySet() }
            } ?: emptySet()
        val queryLower = query.lowercase()
        val tagsById = candidates.associate { it.memoryId to parseTags(it.tagsJson) }
        val aliasesById: Map<String, List<String>> = try {
            store.activeMemoryTargetNames()
        } catch (_: Exception) { emptyMap() }
        val recency = freshness(candidates)

        val m = ensureModel()
        var vectors: Map<String, ByteArray> = emptyMap()
        if (m != null) {
            try {
                vectors = store.activeEmbeddings(RetrievalDocument.effectiveKey(m.tag))
                val missing = candidates.count { !vectors.containsKey(it.memoryId) }
                if (missing > 0) {
                    MemoryLog.log(
                        appContext, "Librarian", "info",
                        "Vector index incomplete — $missing of ${candidates.size} eligible memories lack a current ${RetrievalDocument.effectiveKey(m.tag)} vector; lexical retrieval over the complete set this turn"
                    )
                }
            } catch (t: Throwable) {
                vectors = emptyMap()
                MemoryLog.log(appContext, "Librarian", "error", "Vector load failed, using lexical fallback: ${t.message}")
            }
        }
        val corpus = candidates.map { mem ->
            CorpusMemory(
                memory = mem,
                vector = vectors[mem.memoryId]?.let { VectorMath.fromBlob(it) },
                tags = tagsById[mem.memoryId].orEmpty(),
                aliases = aliasesById[mem.memoryId].orEmpty(),
                recency = recency[mem.memoryId] ?: 0.0,
                boost = retrievalBoost(
                    mem.scope, projectMemoryIds.contains(mem.memoryId),
                    tagsById[mem.memoryId].orEmpty(), queryLower
                )
            )
        }
        val embedQuery: () -> FloatArray? = embed@{
            val model = m ?: return@embed null
            try {
                model.embed(query, isQuery = true)
            } catch (t: Throwable) {
                MemoryLog.log(appContext, "Librarian", "error", "Query embed failed, using lexical fallback: ${t.message}")
                null
            }
        }
        return searchCore(query, embedQuery, corpus, weights(store), topK)
    }

    /** tags_json -> list; a garbled column degrades to "no tags", never an error. */
    private fun parseTags(tagsJson: String): List<String> = try {
        val arr = org.json.JSONArray(tagsJson)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    /**
     * (Re)embed every active memory with the current model, replacing vectors
     * from any other model so a switch re-indexes cleanly. Reports progress as
     * (done, total). Returns the number embedded, or -1 when no model is usable.
     */
    fun rebuildIndex(progress: (Int, Int) -> Unit): Int {
        if (!MemoryStore.isProvisioned(appContext)) return 0
        val m = ensureModel() ?: return -1
        val key = RetrievalDocument.effectiveKey(m.tag)
        val store = MemoryStore.getInstance(appContext)
        // Drops vectors from other models AND from older document versions —
        // both are stale derived data under the versioned key.
        store.deleteEmbeddingsNotModel(key)
        val memories = store.allActiveMemories()
        var done = 0
        for (mem in memories) {
            try {
                val doc = RetrievalDocument.semanticDocument(
                    mem.title, mem.content, mem.embeddingText, parseTags(mem.tagsJson)
                )
                val vec = m.embed(doc, isQuery = false)
                store.upsertEmbedding(mem.memoryId, key, VectorMath.toBlob(vec))
            } catch (t: Throwable) {
                MemoryLog.log(appContext, "Librarian", "error", "Embed failed for ${mem.memoryId}: ${t.message}")
            }
            done++
            progress(done, memories.size)
        }
        store.setMeta(MemoryStore.META_INDEX_MODEL_TAG, key)
        return done
    }

    /** True when the installed model's tag or the retrieval document version
     *  differs from what the index was last built with, or memories lack
     *  vectors — the "rebuild needed" signal. */
    fun indexNeedsRebuild(): Boolean {
        if (!MemoryStore.isProvisioned(appContext)) return false
        val tag = activeTag() ?: return false
        val key = RetrievalDocument.effectiveKey(tag)
        val store = MemoryStore.getInstance(appContext)
        if (store.getMeta(MemoryStore.META_INDEX_MODEL_TAG) != key) return true
        return store.countMissingEmbeddings(key) > 0
    }
}
