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

/**
 * The librarian's swappable brain. EmbeddingGemma is the default, but nothing
 * outside this package may depend on a specific model — the memory-system plan
 * (D3) requires the model be replaceable/upgradeable (and a future Windows
 * build uses a larger one). Anything that needs a vector goes through this
 * interface; the sidecar keys stored vectors by [tag] so switching models is a
 * re-embed, never a schema or code change.
 *
 * Implementations must be safe to call from a background thread and must fail
 * soft: a load or inference error surfaces as an exception the Librarian
 * catches and degrades from (keyword fallback), never a crash.
 */
interface EmbeddingModel {

    /** Stable identifier stored in embeddings.embedding_model, e.g.
     *  "embeddinggemma-300m-q4-256". Changing the model or its output dim
     *  changes the tag, which is what triggers an automatic re-index. */
    val tag: String

    /** Output vector length after any Matryoshka truncation. */
    val dimensions: Int

    /**
     * Embed one text into a normalized vector of length [dimensions].
     * [isQuery] lets models that use asymmetric prompts (EmbeddingGemma's
     * "search query:" vs "title: none | text:") pick the right prefix.
     */
    fun embed(text: String, isQuery: Boolean): FloatArray

    /** Batch convenience; default is a simple loop (models may override for
     *  a genuine batched session run). */
    fun embedAll(texts: List<String>, isQuery: Boolean): List<FloatArray> =
        texts.map { embed(it, isQuery) }

    /** Release native sessions. Safe to call more than once. */
    fun close()
}
