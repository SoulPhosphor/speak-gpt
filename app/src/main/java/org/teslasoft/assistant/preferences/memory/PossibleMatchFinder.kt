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

import android.content.Context
import org.json.JSONArray
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.memory.librarian.RetrievalDocument
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/**
 * The full Possible Match set for a pending draft: the deterministic exact layer
 * ([MemoryStore.deterministicMatchesForDraft]) PLUS the differently-worded
 * semantically-related layer found by the installed LOCAL embedding model.
 *
 * Design (owner ruling, Step 1.5):
 *  - Deterministic matching (exact normalized duplicates, placement, type,
 *    status) always runs — with or without a model.
 *  - The local embedding model finds semantically related existing memories.
 *    Its hits are Possible Match CANDIDATES only: they never classify, merge,
 *    replace, delete, or supersede anything.
 *  - Without a usable embedding model the semantic layer is simply UNAVAILABLE
 *    ([Result.semanticAvailable] = false). Exact matching still works. There is
 *    NO text/token-overlap substitute standing in for semantic matching.
 *  - No online or external AI request is ever made — only the on-device model
 *    already used for Associative Search.
 *
 * Only ACTIVE memories carry current vectors (the archive rule drops a vector
 * when a memory leaves active), so the semantic layer compares against active
 * memories in a comparable placement; archived/superseded matches remain the
 * deterministic layer's responsibility.
 */
object PossibleMatchFinder {

    /**
     * Cosine at or above which the embedding model's hit is surfaced as a
     * semantic Possible Match candidate. A shade below the enforcer's 0.85
     * near-duplicate bar because a Possible Match is deliberately broader than a
     * duplicate (it must also catch differently-worded updates and
     * contradictions), and the user, not the score, makes the final call.
     * Internal tunable; calibration against an on-device corpus is future work
     * (counterplan §5.7).
     */
    const val SEMANTIC_COSINE_THRESHOLD = 0.80f

    /**
     * @param matches the combined Possible Matches — deterministic first, then
     *   semantic (de-duplicated against the deterministic ids).
     * @param semanticAvailable whether the embedding layer actually ran. False
     *   means no usable model (or embedding failed): the caller should tell the
     *   user semantic detection is unavailable until a model is installed;
     *   [matches] then holds exact matches only.
     */
    data class Result(
        val matches: List<MemoryMatch.Match>,
        val semanticAvailable: Boolean
    )

    fun find(context: Context, draftId: String): Result {
        val store = MemoryStore.getInstance(context)
        val draft = store.getMemory(draftId)?.takeIf { it.status == "draft" }
            ?: return Result(emptyList(), semanticAvailable = false)

        val deterministic = store.deterministicMatchesForDraft(draftId)

        val librarian = Librarian.getInstance(context)
        val tag = librarian.activeTag()
        if (tag == null || !librarian.hasUsableModel()) {
            return Result(deterministic, semanticAvailable = false)
        }

        val doc = RetrievalDocument.semanticDocument(
            draft.title, draft.content, draft.embeddingText, parseTags(draft.tagsJson)
        )
        val queryVec = librarian.embedOrNull(doc, isQuery = false)
            ?: return Result(deterministic, semanticAvailable = false)

        val alreadyMatched = deterministic.mapTo(HashSet()) { it.memoryId }
        val candidateIds = store.comparableActiveMemoryIds(draftId).filter { it !in alreadyMatched }
        val vectors = store.embeddingsForMemories(candidateIds, RetrievalDocument.effectiveKey(tag))

        val semantic = ArrayList<MemoryMatch.Match>()
        for ((id, blob) in vectors) {
            val sim = VectorMath.cosine(queryVec, VectorMath.fromBlob(blob))
            if (sim >= SEMANTIC_COSINE_THRESHOLD) {
                semantic.add(MemoryMatch.Match(id, MemoryMatch.Relation.SEMANTIC_NEAR))
            }
        }
        return Result(deterministic + semantic, semanticAvailable = true)
    }

    private fun parseTags(tagsJson: String): List<String> = try {
        val arr = JSONArray(tagsJson)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
