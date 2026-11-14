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
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.ImportanceRanking
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

        /** How many lore-overlap vectors to keep cached across turns. */
        private const val LORE_VECTOR_CACHE_MAX = 256

        /** How many Possible Match comparison vectors to keep cached (Step 1.5).
         *  Archived/superseded memories carry no stored vector, so the Pending
         *  comparison regenerates them on demand; this reuses a temporary vector
         *  across the drafts compared in one Memory Browser session. */
        private const val COMPARISON_VECTOR_CACHE_MAX = 256

        /** The reading limit stored memory vectors were made with before
         *  conversation_recall_plan.md Step 1. A memory whose document fits
         *  inside it has exactly the same vector under the new limit, so only
         *  longer memories are read again. */
        internal const val PREVIOUS_STORED_TEXT_TOKEN_LIMIT = 512

        /** The meta value that marks the long-memory re-read complete. */
        internal fun storedTextReadMarker(tokenLimit: Int, embeddingKey: String): String =
            "$tokenLimit|$embeddingKey"

        /** Whether a stored vector made under the previous limit was cut, so
         *  the memory must be read again. [documentTokens] is the full count
         *  from [EmbeddingModel.countTokens]. Pure. */
        internal fun needsLongerRead(documentTokens: Int): Boolean =
            documentTokens > PREVIOUS_STORED_TEXT_TOKEN_LIMIT

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

        /** Archivist reconciliation is relevance-first and deliberately does
         * not inherit live delivery quotas, cooldowns, Memory Priority, or
         * stored live weighting. Importance, recency, and context together can
         * contribute at most this much after base semantic/lexical relevance. */
        const val RECONCILIATION_MAX_TIE_BREAK = 0.06
        private const val RECONCILIATION_IMPORTANCE_WEIGHT = 0.02
        private const val RECONCILIATION_RECENCY_WEIGHT = 0.02
        private const val RECONCILIATION_CONTEXT_CAP = 0.02

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

        /** Bound the live-context boost before it participates in Archivist
         * reconciliation. Scope/project/tag signals remain useful only as a
         * small tie-break and cannot overpower a larger relevance advantage. */
        fun reconciliationContextBoost(liveBoost: Double): Double =
            liveBoost.coerceIn(0.0, RECONCILIATION_CONTEXT_CAP)

        /** Fixed reconciliation weights. This pure seam makes the Stage F
         * boundary explicit: live delivery priority, cooldown, memory-count,
         * and token settings are not inputs and therefore cannot gate or
         * reweight Archivist awareness. */
        internal fun reconciliationWeights(useImportance: Boolean): Weights = Weights(
            similarity = 1.0,
            importance = if (useImportance) RECONCILIATION_IMPORTANCE_WEIGHT else 0.0,
            recency = RECONCILIATION_RECENCY_WEIGHT
        )

        /** Union independent local topic-window searches by stable memory id.
         * The best score a memory achieved in any window wins. */
        fun mergeReconciliationWindows(
            windows: List<List<ScoredMemory>>,
            limit: Int
        ): List<ScoredMemory> {
            if (limit <= 0) return emptyList()
            val best = LinkedHashMap<String, ScoredMemory>()
            for (window in windows) {
                for (hit in window) {
                    val prior = best[hit.memory.memoryId]
                    if (prior == null || hit.score > prior.score) {
                        best[hit.memory.memoryId] = hit
                    }
                }
            }
            return best.values.sortedByDescending { it.score }.take(limit)
        }

        /**
         * Pure ranking: score each candidate and return the top [topK], highest
         * first. score = w_sim·cosine + w_imp·signedImportance + w_rec·recency +
         * boost. Provenance (confidence/source) does not influence the score
         * (Phase 2 review). No Android/ORT — unit tested (LibrarianRankingTest).
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
            minSimilarity: Float = 0f,
            useImportanceRatings: Boolean = true,
            includeMandatoryImportance: Boolean = true
        ): List<ScoredMemory> {
            val scored = candidates.mapNotNull { c ->
                val sim = VectorMath.cosine(queryVector, c.vector)
                if (sim < minSimilarity) return@mapNotNull null
                val importance = if (useImportanceRatings) {
                    ImportanceRanking.normalizedRankingImportance(c.memory.importance.toDouble())
                } else 0.0
                var s = weights.similarity * sim +
                    weights.importance * importance +
                    weights.recency * c.recency
                // Provenance no longer influences ranking (Phase 2 review): a
                // memory's confidence/source is not read into its score.
                s += c.boost
                ScoredMemory(c.memory, sim, s.toFloat())
            }
            val ranked = scored.sortedByDescending { it.score }
            return ImportanceRanking.includeMandatory(ranked, topK) { hit ->
                includeMandatoryImportance &&
                    ImportanceRanking.isMandatory(hit.memory.importance.toDouble(), useImportanceRatings)
            }
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
            topK: Int,
            useImportanceRatings: Boolean = true,
            includeMandatoryImportance: Boolean = true
        ): List<ScoredMemory> {
            val terms = lexicalTokens(query).filter { it.length >= MIN_TERM_LENGTH }
            if (terms.isEmpty()) return emptyList()
            val scored = candidates.mapNotNull { c ->
                val mem = c.memory
                // Titles are retired (§3.1): the lexical document is content,
                // the condensed hint, tags, and target aliases — never a title.
                val docTokens = HashSet(lexicalTokens(mem.content))
                mem.embeddingText?.takeIf { it.isNotBlank() }?.let { docTokens.addAll(lexicalTokens(it)) }
                for (tag in c.tags) docTokens.addAll(lexicalTokens(tag))
                for (alias in c.aliases) docTokens.addAll(lexicalTokens(alias))
                val hits = terms.count { it in docTokens }
                if (hits == 0) return@mapNotNull null
                val relevance = hits.toFloat() / terms.size
                val importance = if (useImportanceRatings) {
                    ImportanceRanking.normalizedRankingImportance(mem.importance.toDouble())
                } else 0.0
                var s = weights.similarity * relevance +
                    weights.importance * importance +
                    weights.recency * c.recency
                // Provenance no longer influences ranking (Phase 2 review).
                s += c.boost
                // No title bonus (§3.1): retrieval never rewards a title.
                ScoredMemory(mem, relevance, s.toFloat())
            }
            val ranked = scored.sortedByDescending { it.score }
            return ImportanceRanking.includeMandatory(ranked, topK) { hit ->
                includeMandatoryImportance &&
                    ImportanceRanking.isMandatory(hit.memory.importance.toDouble(), useImportanceRatings)
            }
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
            topK: Int,
            useImportanceRatings: Boolean = true,
            includeMandatoryImportance: Boolean = true,
            onSemantic: ((Boolean) -> Unit)? = null
        ): List<ScoredMemory> {
            if (corpus.isEmpty()) return emptyList()
            if (corpus.all { it.vector != null }) {
                val queryVec = embedQuery()
                if (queryVec != null) {
                    onSemantic?.invoke(true)
                    return rank(
                        queryVec,
                        corpus.map { Candidate(it.memory, it.vector!!, it.recency, it.boost) },
                        weights, topK, MIN_SIMILARITY,
                        useImportanceRatings, includeMandatoryImportance
                    )
                }
            }
            onSemantic?.invoke(false)
            return rankLexical(
                query,
                corpus.map { LexicalCandidate(it.memory, it.tags, it.recency, it.boost, it.aliases) },
                weights, topK,
                useImportanceRatings, includeMandatoryImportance
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

    /** What one [search] turn actually did, for the debug view (counterplan
     *  Step 1.4 — truthful per-source diagnostics). [eligible] candidates
     *  passed the store's scope gates; [withVector] of them had a current
     *  vector; [semantic] is whether semantic ranking ran (false = the
     *  complete-set lexical fallback, because a model was missing or the
     *  index was incomplete). */
    data class RetrievalDiagnostics(
        val eligible: Int,
        val withVector: Int,
        val semantic: Boolean,
        val hasModel: Boolean,
        /** Memories in the result that only a section of a long message
         *  found ([LongMessageSearch]); they are listed after everything the
         *  whole message matched and never go past the memory limit. */
        val foundBySection: Set<String> = emptySet()
    )

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

    // The ONNX model is not thread-safe and reads one text at a time. Every
    // read — the live query, lore overlap, the self-check, reindex, rebuild,
    // comparison, and the background repairs below — goes through this one
    // queue (conversation_recall_plan.md G.5a rule 6). A live read goes ahead
    // of every waiting background read, and background work is queued one
    // text at a time, so a live turn waits for at most one running read; a
    // running background read is stopped for it where the model allows.
    private val embeddingQueue = EmbeddingQueue()

    /** A read the user is waiting on: the message being sent, lore overlap,
     *  the Memory Search Test. Uses the message reading limit. */
    private fun embedLive(m: EmbeddingModel, text: String, isQuery: Boolean): FloatArray =
        embeddingQueue.run(EmbeddingQueue.Priority.LIVE) { control ->
            m.embed(text, isQuery, m.messageTokenLimit, control)
        }

    /** A background read of a query-side text (Archivist reconciliation),
     *  with the message reading limit. */
    private fun embedBackgroundQuery(m: EmbeddingModel, text: String): FloatArray =
        embeddingQueue.run(EmbeddingQueue.Priority.BACKGROUND) { control ->
            m.embed(text, isQuery = true, tokenLimit = m.messageTokenLimit, control = control)
        }

    /** A background read of stored text — a saved memory's retrieval
     *  document — with the stored-text reading limit. */
    private fun embedStoredText(m: EmbeddingModel, document: String): FloatArray =
        embeddingQueue.run(EmbeddingQueue.Priority.BACKGROUND) { control ->
            m.embed(document, isQuery = false, tokenLimit = m.storedTextTokenLimit, control = control)
        }

    // Lore-overlap vectors cached by content + model tag so the same authored
    // lore note is not re-embedded every turn (counterplan Step 1.4). The key
    // carries the model tag and the full note text, so an edit (new text) or a
    // model change (new tag) simply misses; [invalidateModel] clears it on a
    // model swap. Bounded LRU, guarded by its own lock.
    private val loreCacheLock = Any()
    private val loreVectorCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > LORE_VECTOR_CACHE_MAX
    }

    // Possible Match comparison vectors (Step 1.5), same shape as the lore
    // cache: keyed by model tag + text so an edit or model swap misses, cleared
    // on [invalidateModel]. Comparison-only — these vectors are NEVER written to
    // the embeddings table, so an archived/superseded memory never becomes
    // eligible for chat retrieval by being compared here.
    private val comparisonCacheLock = Any()
    private val comparisonVectorCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > COMPARISON_VECTOR_CACHE_MAX
    }

    // Single-flight background missing-vector repair (counterplan Step 1.4 —
    // "self-repairing search"). Low priority so a live turn's own embed is
    // never starved; the guard collapses a burst of "index incomplete" turns
    // into one running pass.
    private val repairInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val repairExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "memory-vector-repair").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }

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
                val failure = embeddingQueue.run(EmbeddingQueue.Priority.LIVE) { m.selfCheck() }
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
            requestLongMemoryReread()
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
            embedLive(m, text, isQuery)
        } catch (t: Throwable) {
            MemoryLog.log(appContext, "Librarian", "error", "Embed failed: ${t.message}")
            null
        }
    }

    /**
     * Embed a lore-overlap text, reusing a cached vector for the same note and
     * model tag rather than re-embedding it every turn (counterplan Step 1.4).
     * Null when no model is usable or inference fails — the enforcer then falls
     * back to deterministic word-overlap. Serialized like every other embed.
     */
    fun embedLoreCached(text: String): FloatArray? {
        val m = ensureModel() ?: return null
        val key = m.tag + " " + text
        synchronized(loreCacheLock) { loreVectorCache[key]?.let { return it } }
        val vec = try {
            embedLive(m, text, false)
        } catch (t: Throwable) {
            MemoryLog.log(appContext, "Librarian", "error", "Lore embed failed: ${t.message}")
            return null
        }
        synchronized(loreCacheLock) { loreVectorCache[key] = vec }
        return vec
    }

    /**
     * Embed a Possible Match comparison text (Step 1.5), reusing a cached vector
     * for the same text and model tag so an archived/superseded memory is not
     * re-embedded for every pending draft in a Memory Browser session. Null when
     * no model is usable or inference fails — the finder then omits the semantic
     * layer for that memory. The vector is comparison-only and never persisted,
     * so it can never make an inactive memory eligible for chat retrieval.
     */
    fun embedComparisonCached(text: String): FloatArray? {
        val m = ensureModel() ?: return null
        val key = m.tag + " " + text
        synchronized(comparisonCacheLock) { comparisonVectorCache[key]?.let { return it } }
        val vec = try {
            embedStoredText(m, text)
        } catch (t: Throwable) {
            MemoryLog.log(appContext, "Librarian", "error", "Comparison embed failed: ${t.message}")
            return null
        }
        synchronized(comparisonCacheLock) { comparisonVectorCache[key] = vec }
        return vec
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
                mem.content, mem.embeddingText, parseTags(mem.tagsJson)
            )
            val vec = embedStoredText(m, doc)
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
        synchronized(loreCacheLock) { loreVectorCache.clear() }
        synchronized(comparisonCacheLock) { comparisonVectorCache.clear() }
    }

    /** Stored scoring weights, bounded (counterplan §5.5): a non-finite,
     *  negative, or all-zero stored set degrades to the defaults with a log
     *  note rather than producing nonsense scores. */
    private fun weights(store: MemoryStore): Weights {
        val raw = try { store.getRetrievalWeights() } catch (_: Exception) { null }
        val bounded = RetrievalPolicy.boundWeights(raw)
        bounded.substitutionNote?.let { MemoryLog.log(appContext, "Librarian", "info", it) }
        // The one shared importance-access path (Phase 2, item 3): the
        // "Use Importance Ratings" master toggle decides whether stored
        // importance influences the score. Off => exactly zero contribution;
        // the stored ratings on each memory are never read into the score and
        // never modified — turning it back On restores their effect. This gates
        // only the weight; eligibility is decided before scoring, so importance
        // can never make an ineligible memory eligible.
        val useImportance = try {
            Preferences.getPreferences(appContext, "").getUseImportanceRatings()
        } catch (_: Exception) { true }
        val importanceWeight = ImportanceRanking.effectiveImportanceWeight(bounded.value[1], useImportance)
        return Weights(bounded.value[0], importanceWeight, bounded.value[2])
    }

    /** Reconciliation has its own fixed relevance-first policy. The owner's
     * importance toggle is still respected: Off means stored importance is
     * ignored everywhere, including Archivist awareness. */
    private fun reconciliationWeights(): Weights {
        val useImportance = try {
            Preferences.getPreferences(appContext, "").getUseImportanceRatings()
        } catch (_: Exception) { true }
        return reconciliationWeights(useImportance)
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
     * method, so it inherits them. Live search ranks by relevance only
     * ([LiveRetrieval]); [selectedProjectId] no longer changes live ranking
     * and never gates eligibility.
     */
    fun search(
        scope: RetrievalScope,
        query: String,
        topK: Int,
        selectedProjectId: String? = null,
        diag: ((RetrievalDiagnostics) -> Unit)? = null
    ): List<ScoredMemory> = searchInternal(
        scope = scope,
        query = query,
        topK = topK,
        selectedProjectId = selectedProjectId,
        rankingWeights = null,
        reconciliation = false,
        diag = diag
    )

    /**
     * Stage C local pre-extraction retrieval. Each supplied text window is
     * searched through the same scope eligibility, vector completeness, and
     * lexical fallback machinery as live retrieval; results are unioned by
     * stable id under [candidateCeiling]. Live count/token limits, cooldown,
     * and Memory Priority are not consulted.
     */
    fun searchForReconciliation(
        scope: RetrievalScope,
        queryWindows: List<String>,
        candidateCeiling: Int,
        selectedProjectId: String? = null,
        diag: ((RetrievalDiagnostics) -> Unit)? = null
    ): List<ScoredMemory> {
        val boundedCeiling = candidateCeiling.coerceIn(1, 15)
        val windows = queryWindows.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (windows.isEmpty()) return emptyList()
        var reported = false
        val results = windows.map { query ->
            searchInternal(
                scope = scope,
                query = query,
                topK = boundedCeiling,
                selectedProjectId = selectedProjectId,
                rankingWeights = reconciliationWeights(),
                reconciliation = true,
                diag = { d ->
                    if (!reported) {
                        reported = true
                        diag?.invoke(d)
                    }
                }
            )
        }
        return mergeReconciliationWindows(results, boundedCeiling)
    }

    private fun searchInternal(
        scope: RetrievalScope,
        query: String,
        topK: Int,
        selectedProjectId: String?,
        rankingWeights: Weights?,
        reconciliation: Boolean,
        diag: ((RetrievalDiagnostics) -> Unit)?
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
                // Scoped load (counterplan Step 1.4): read only the eligible
                // candidates' vectors, not every active vector in the library.
                vectors = store.embeddingsForMemories(
                    candidates.map { it.memoryId }, RetrievalDocument.effectiveKey(m.tag)
                )
                val missing = candidates.count { !vectors.containsKey(it.memoryId) }
                if (missing > 0) {
                    // Self-repairing search: fill the gaps in the background so
                    // later turns can use semantic ranking again.
                    val repairing = requestMissingVectorRepair()
                    MemoryLog.log(
                        appContext, "Librarian", "info",
                        if (reconciliation) {
                            "$missing of ${candidates.size} eligible memories lack a current ${RetrievalDocument.effectiveKey(m.tag)} embedding. Memory Assistant searched the complete eligible set by keyword instead." +
                                if (repairing) " The app is preparing them in the background." else ""
                        } else {
                            "$missing of ${candidates.size} eligible memories aren't ready for embedding search. They were searched by keyword instead." +
                                if (repairing) " The app is preparing them in the background." else ""
                        }
                    )
                }
            } catch (t: Throwable) {
                vectors = emptyMap()
                MemoryLog.log(appContext, "Librarian", "error", "Couldn't load the embedding index. Used keyword search instead. Error: ${t.message}")
            }
        }
        val withVector = candidates.count { vectors.containsKey(it.memoryId) }
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
                ).let { if (reconciliation) reconciliationContextBoost(it) else it }
            )
        }
        val embedQuery: () -> FloatArray? = embed@{
            val model = m ?: return@embed null
            try {
                // The live search is the message being sent; Archivist
                // reconciliation runs in the background behind it.
                if (reconciliation) embedBackgroundQuery(model, query)
                else embedLive(model, query, isQuery = true)
            } catch (t: Throwable) {
                MemoryLog.log(appContext, "Librarian", "error", RecallDiagnostics.queryFailure(reconciliation, t.message ?: t.javaClass.simpleName))
                null
            }
        }
        val useImportanceRatings = try {
            Preferences.getPreferences(appContext, "").getUseImportanceRatings()
        } catch (_: Exception) { true }
        if (!reconciliation) {
            // Live chat retrieval: meaning and keyword search together, with
            // the relevance gate and relevance-first ranking (LiveRetrieval).
            // A missing vector removes only that memory from meaning search.
            // Scope, project, tag, and freshness boosts play no part here.
            val queryVector = if (withVector > 0) embedQuery() else null
            val index = LiveRetrieval.index(
                corpus.map { LiveRetrieval.Candidate(it.memory, it.vector, it.tags, it.aliases) }
            )
            val params = liveParams(store)
            val result = SavedMemorySearch.search(query, queryVector, index, params, topK, useImportanceRatings) {
                if (m == null) LongMessageSearch.Reading.NONE else readSections(m, query, index)
            }
            RecallDiagnostics.sectionFailure(result.sections)?.let { message ->
                MemoryLog.log(appContext, "Librarian", "error", message)
            }
            diag?.invoke(RetrievalDiagnostics(candidates.size, withVector,
                result.whole.diagnostics.meaningRan, m != null, result.sectionIds))
            return result.ranked
        }
        val result = searchCore(
            query = query,
            embedQuery = embedQuery,
            corpus = corpus,
            weights = rankingWeights ?: weights(store),
            topK = topK,
            useImportanceRatings = useImportanceRatings,
            // +3's overflow rule is for live prompt delivery, not the
            // Archivist's bounded reconciliation candidate pool.
            includeMandatoryImportance = useImportanceRatings && !reconciliation
        ) { semantic ->
            diag?.invoke(
                RetrievalDiagnostics(
                    eligible = candidates.size,
                    withVector = withVector,
                    semantic = semantic,
                    hasModel = m != null
                )
            )
        }
        return result
    }

    /** Measures waiting separately from model work, including failed reads.
     * The total also includes cutting and rare-word prioritization. */
    private fun readSections(m: EmbeddingModel, query: String, index: LiveRetrieval.Index): LongMessageSearch.Reading {
        var queueNanos = 0L
        var modelNanos = 0L
        val started = System.nanoTime()
        val reading = LongMessageSearch.read(query, index, budgetedEmbed = { text, remaining ->
            val queued = System.nanoTime()
            var entered = false
            try {
                embeddingQueue.runWithin(remaining) { control ->
                    entered = true
                    val running = System.nanoTime()
                    queueNanos += running - queued
                    try { m.embed(text, true, m.messageTokenLimit, control) }
                    finally { modelNanos += System.nanoTime() - running }
                }
            } finally {
                if (!entered) queueNanos += System.nanoTime() - queued
            }
        }) { text -> embedLive(m, text, isQuery = true) }
        return if (reading.sections.isEmpty()) reading else reading.copy(
            millis = (System.nanoTime() - started) / 1_000_000,
            queueMillis = queueNanos / 1_000_000, modelMillis = modelNanos / 1_000_000)
    }

    /** Live search tuning from the stored policy's `live_search` object.
     *  Missing or unreadable values keep LiveRetrieval's defaults; stored
     *  values are clamped to safe ranges by [LiveRetrieval.Params.bounded]. */
    private fun liveParams(store: MemoryStore): LiveRetrieval.Params =
        try {
            SearchTuning.fromPolicyJson(store.getRetrievalPolicyJson()).params
        } catch (_: Exception) {
            LiveRetrieval.Params()
        }

    /** One test message, prepared once so the Memory Search Test screen can
     *  re-score it instantly while numbers are adjusted. */
    data class PreparedSearchTest(
        val query: String,
        val queryVector: FloatArray?,
        val corpus: List<LiveRetrieval.Candidate>,
        val hasModel: Boolean,
        val embedMillis: Long,
        val loadMillis: Long,
        /** The message's sections as read for the long-message search. */
        val sections: LongMessageSearch.Reading = LongMessageSearch.Reading.NONE
    )

    /**
     * Loads the memories [scope] may use and embeds [query], exactly as live
     * chat search does, without writing anything: no log lines, no vector
     * repair, no cooldown or mention records. Returns null when the memory
     * store is not set up yet.
     */
    fun prepareSearchTest(scope: RetrievalScope, query: String): PreparedSearchTest? {
        if (!MemoryStore.isProvisioned(appContext)) return null
        val started = System.nanoTime()
        val store = MemoryStore.getInstance(appContext)
        val candidates = store.activeMemoriesForScope(scope)
        val aliasesById: Map<String, List<String>> = try {
            store.activeMemoryTargetNames()
        } catch (_: Exception) { emptyMap() }
        val m = ensureModel()
        val vectors: Map<String, ByteArray> = if (m == null || candidates.isEmpty()) emptyMap() else try {
            store.embeddingsForMemories(candidates.map { it.memoryId }, RetrievalDocument.effectiveKey(m.tag))
        } catch (_: Throwable) { emptyMap() }
        val corpus = candidates.map { mem ->
            LiveRetrieval.Candidate(
                mem,
                vectors[mem.memoryId]?.let { VectorMath.fromBlob(it) },
                parseTags(mem.tagsJson),
                aliasesById[mem.memoryId].orEmpty()
            )
        }
        val loadMillis = (System.nanoTime() - started) / 1_000_000
        var embedMillis = 0L
        val queryVector = if (m != null && vectors.isNotEmpty()) {
            try {
                embeddingQueue.run(EmbeddingQueue.Priority.LIVE) { control ->
                    // Timed inside the queue: the read itself, not the wait.
                    val embedStarted = System.nanoTime()
                    val v = m.embed(query, true, m.messageTokenLimit, control)
                    embedMillis = (System.nanoTime() - embedStarted) / 1_000_000
                    v
                }
            } catch (_: Throwable) { null }
        } else null
        // The same section reads a chat makes, with the same limits. A
        // section that cannot be read is skipped; the test writes no log.
        val sections = if (m != null && queryVector != null) {
            readSections(m, query, LiveRetrieval.index(corpus))
        } else LongMessageSearch.Reading.NONE
        return PreparedSearchTest(query, queryVector, corpus, m != null, embedMillis, loadMillis, sections)
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
        var failed = 0
        for (mem in memories) {
            try {
                val doc = RetrievalDocument.semanticDocument(
                    mem.content, mem.embeddingText, parseTags(mem.tagsJson)
                )
                val vec = embedStoredText(m, doc)
                store.upsertEmbedding(mem.memoryId, key, VectorMath.toBlob(vec))
            } catch (t: Throwable) {
                failed++
                MemoryLog.log(appContext, "Librarian", "error", "Embed failed for ${mem.memoryId}: ${t.message}")
            }
            done++
            progress(done, memories.size)
        }
        store.setMeta(MemoryStore.META_INDEX_MODEL_TAG, key)
        // Every vector was just made with the stored-text limit, so no long
        // memory needs reading again.
        if (failed == 0) {
            store.setMeta(LongMemoryReread.META_COMPLETE, storedTextReadMarker(m.storedTextTokenLimit, key))
            store.deleteMeta(LongMemoryReread.META_PROGRESS)
        }
        return done
    }

    /**
     * Ask the background repair to fill any missing current-model vectors
     * (counterplan Step 1.4 — self-repairing search). Fire-and-forget and
     * single-flight: a no-op when unprovisioned, when no model is installed,
     * or when a pass is already running, so a burst of incomplete-index turns
     * schedules one pass, not one per turn. Safe to call from a retrieval turn
     * — the work runs off the caller's thread. Returns true when a repair
     * pass is now running or scheduled.
     */
    fun requestMissingVectorRepair(): Boolean {
        if (!MemoryStore.isProvisioned(appContext)) return false
        if (activeTag() == null) return false
        if (!repairInFlight.compareAndSet(false, true)) return true
        return try {
            repairExecutor.execute {
                try { repairMissingVectors() } finally { repairInFlight.set(false) }
            }
            true
        } catch (t: Throwable) {
            // Executor rejected (e.g. shutdown) — release the guard so a later
            // turn can try again.
            repairInFlight.set(false)
            MemoryLog.log(appContext, "Librarian", "error", "Could not schedule missing-vector repair: ${t.message}")
            false
        }
    }

    /**
     * Re-embed every active memory that lacks a current-model vector. The
     * inventory is read fresh from the store (a derived queue, never a stored
     * list that could drift). Best-effort per memory: one failure is logged and
     * skipped, not fatal. Does not stamp the index-model meta — a full model
     * switch remains [rebuildIndex]'s job, which also drops the old vectors.
     */
    private fun repairMissingVectors() {
        val m = ensureModel() ?: return
        val key = RetrievalDocument.effectiveKey(m.tag)
        val store = MemoryStore.getInstance(appContext)
        val pending = try {
            store.activeMemoriesMissingEmbedding(key)
        } catch (t: Throwable) {
            MemoryLog.log(appContext, "Librarian", "error", "Missing-vector repair could not read the inventory: ${t.message}")
            return
        }
        if (pending.isEmpty()) return
        var repaired = 0
        for (mem in pending) {
            try {
                val doc = RetrievalDocument.semanticDocument(
                    mem.content, mem.embeddingText, parseTags(mem.tagsJson)
                )
                val vec = embedStoredText(m, doc)
                store.upsertEmbedding(mem.memoryId, key, VectorMath.toBlob(vec))
                repaired++
            } catch (t: Throwable) {
                MemoryLog.log(appContext, "Librarian", "error", "Missing-vector repair failed for ${mem.memoryId}: ${t.message}")
            }
        }
        if (repaired > 0) {
            MemoryLog.log(
                appContext, "Librarian", "info",
                "Missing-vector repair re-embedded $repaired memory vector(s) for $key"
            )
        }
    }

    // Single-flight re-read of memories longer than the previous reading
    // limit (conversation_recall_plan.md Step 1, G33).
    private val rereadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ask the background worker to read again, with the stored-text limit,
     * every saved memory whose vector was cut at the previous 512-token limit.
     * A no-op once that is done for the current model and limit (recorded in
     * meta), when unprovisioned, or while a pass is already running.
     */
    private fun requestLongMemoryReread() {
        if (!MemoryStore.isProvisioned(appContext)) return
        if (!rereadInFlight.compareAndSet(false, true)) return
        try {
            repairExecutor.execute {
                try { rereadLongMemories() } catch (_: Throwable) { } finally { rereadInFlight.set(false) }
            }
        } catch (_: Throwable) {
            rereadInFlight.set(false)
        }
    }

    /**
     * Runs the long-memory re-read ([LongMemoryReread]) against the memory
     * store and the active model. Progress is saved after each memory, so an
     * interrupted or failed pass continues where it stopped the next time the
     * model loads.
     */
    private fun rereadLongMemories() {
        val m = ensureModel() ?: return
        val key = RetrievalDocument.effectiveKey(m.tag)
        val store = MemoryStore.getInstance(appContext)
        LongMemoryReread(object : LongMemoryReread.Host {
            override val marker = storedTextReadMarker(m.storedTextTokenLimit, key)
            override fun readMeta(key: String): String? = store.getMeta(key)
            override fun writeMeta(key: String, value: String) = store.setMeta(key, value)
            override fun deleteMeta(key: String) = store.deleteMeta(key)
            override fun memoriesWithVector(): List<LongMemoryReread.Item> {
                val memories = store.allActiveMemories()
                val withVector = store.embeddingsForMemories(memories.map { it.memoryId }, key).keys
                return memories.filter { it.memoryId in withVector }.map {
                    LongMemoryReread.Item(
                        it.memoryId,
                        RetrievalDocument.semanticDocument(it.content, it.embeddingText, parseTags(it.tagsJson))
                    )
                }
            }
            override fun countTokens(document: String): Int = m.countTokens(document, isQuery = false)
            override fun read(document: String): FloatArray = embedStoredText(m, document)
            override fun replaceIfCurrent(
                memoryId: String,
                document: String,
                vector: FloatArray
            ): LongMemoryReread.WriteResult =
                when (store.replaceEmbeddingIfCurrent(memoryId, key, VectorMath.toBlob(vector)) { record ->
                    RetrievalDocument.semanticDocument(record.content, record.embeddingText, parseTags(record.tagsJson)) == document
                }) {
                    true -> LongMemoryReread.WriteResult.REPLACED
                    false -> LongMemoryReread.WriteResult.CHANGED
                    null -> LongMemoryReread.WriteResult.GONE
                }
            override fun currentDocument(memoryId: String): String? =
                store.getMemory(memoryId)?.takeIf { it.status == "active" }?.let {
                    RetrievalDocument.semanticDocument(it.content, it.embeddingText, parseTags(it.tagsJson))
                }
        }).run()
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
