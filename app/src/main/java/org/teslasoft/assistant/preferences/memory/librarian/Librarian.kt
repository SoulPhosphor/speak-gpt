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
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
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

        /**
         * Pure ranking: score each candidate and return the top [topK], highest
         * first. score = w_sim·cosine + w_imp·(importance/5) + w_rec·recency;
         * tentative-confidence memories are dampened. No Android/ORT — unit
         * tested (LibrarianRankingTest). [recency] is a 0..1 value the caller
         * supplies per memory (1 = newest); tests pass it directly.
         */
        fun rank(
            queryVector: FloatArray,
            candidates: List<Triple<RetrievableMemory, FloatArray, Double>>,
            weights: Weights,
            topK: Int
        ): List<ScoredMemory> {
            val scored = candidates.map { (mem, vec, recency) ->
                val sim = VectorMath.cosine(queryVector, vec)
                var s = weights.similarity * sim +
                    weights.importance * (mem.importance / 5.0) +
                    weights.recency * recency
                if (mem.provenanceConfidence.equals("tentative", ignoreCase = true)) s *= TENTATIVE_DAMPEN
                ScoredMemory(mem, sim, s.toFloat())
            }
            return scored.sortedByDescending { it.score }.take(topK)
        }
    }

    data class Weights(val similarity: Double, val importance: Double, val recency: Double)

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
            OnnxEmbeddingModel.create(appContext, catalog).also { model = it }
        } catch (t: Throwable) {
            modelLoadFailed = true
            Logger.log(appContext, "event", "Librarian", "ERROR", "Embedding model failed to load: ${t.message}")
            null
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
     * Semantic search within a conversation's scope. Falls back to keyword
     * matching when there's no usable model or no vectors yet. Returns up to
     * [topK] scored memories, best first.
     */
    fun search(companionId: String?, worldId: String?, query: String, topK: Int): List<ScoredMemory> {
        if (!MemoryStore.isProvisioned(appContext)) return emptyList()
        val store = MemoryStore.getInstance(appContext)
        val candidates = store.activeMemoriesForScope(companionId, worldId)
        if (candidates.isEmpty()) return emptyList()

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
                    val triples = withVectors.map { (mem, vec) ->
                        Triple(mem, vec, recency[mem.memoryId] ?: 0.0)
                    }
                    return rank(queryVec, triples, weights(store), topK)
                }
            } catch (t: Throwable) {
                Logger.log(appContext, "event", "Librarian", "ERROR", "Vector search failed, using keyword fallback: ${t.message}")
            }
        }
        return keywordFallback(candidates, query, topK)
    }

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
                Logger.log(appContext, "event", "Librarian", "ERROR", "Embed failed for ${mem.memoryId}: ${t.message}")
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
