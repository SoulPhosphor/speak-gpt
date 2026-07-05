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
 * Catalog of downloadable embedding models, mirroring the Whisper model
 * registry pattern (LocalWhisperModels): the user picks a quantization that
 * fits their phone; tier 2 requires one installed.
 *
 * Default: EmbeddingGemma-300m **q4** (onnx-community ONNX export), output
 * truncated to 256 dims via Matryoshka — the enforcer spec's recommendation
 * (negligible quality loss on short prose memories, smaller vectors + faster
 * search). int8 stays in the catalog as an optional higher-quality pick but is
 * deliberately NOT the default (owner decision: prioritize reliable install
 * and low memory on the Pixel).
 *
 * Each variant is TWO files in its own directory: the transformer (`model*.onnx`
 * from onnx-community/embeddinggemma-300m-ONNX) and the repo's own
 * **`tokenizer.json`**, tokenized on-device by the pure-Kotlin HfTokenizer.
 * Downloading the real tokenizer file at runtime (instead of generating and
 * bundling a tokenizer graph) means the app and its GitHub releases never
 * redistribute Gemma-licensed artifacts — owner decision, July 2026. Do not
 * reintroduce a bundled tokenizer asset.
 *
 * Everything model-specific is a catalog field — prompt prefixes, pooling,
 * dimensions, token budget — so a future different-architecture model (e.g.
 * BGE-M3: Unigram tokenizer, CLS pooling, symmetric prompts, 1024 dims, no
 * Matryoshka) is a new entry here, not new code. Nothing outside librarian/
 * may depend on a concrete model (plan decision D3).
 *
 * ON-DEVICE VALIDATION PENDING (memory-system-integration-plan.md, Phase 3):
 * these URLs follow the standard onnx-community layout but were NOT reachable
 * from the build sandbox (huggingface.co is blocked by the network policy).
 * Confirm them on the Pixel — same situation as the Whisper models, whose SHAs
 * are also null and validated on-device. sha256 stays null until a known-good
 * hash is captured; the downloader then verifies automatically with no code
 * change.
 */
object EmbeddingModels {

    /** How to derive a sentence vector when the graph only exposes token
     *  states. Models that output a ready sentence embedding ignore this. */
    enum class Pooling { MEAN, CLS }

    data class Model(
        /** Stable id: directory name, preference value, and part of the sidecar tag. */
        val id: String,
        val displayName: String,
        /** Combined on-disk size (model + tokenizer) in MB, for UI labels. */
        val sizeMb: Int,
        /** Transformer ONNX file name inside the model directory. */
        val modelFileName: String,
        /** Direct URL to the transformer ONNX. */
        val modelUrl: String,
        val modelSha256: String?,
        /** Tokenizer file name inside the model directory (HF tokenizer.json). */
        val tokenizerFileName: String,
        /** Direct URL to the repo's tokenizer.json. */
        val tokenizerUrl: String,
        val tokenizerSha256: String?,
        /** Output vector length after Matryoshka truncation. */
        val dimensions: Int,
        /** Full model dim before truncation (for validation of the raw output). */
        val fullDimensions: Int,
        /** Prompt prefixes for asymmetric-prompt models (EmbeddingGemma).
         *  Symmetric models (BGE-M3) leave both empty. */
        val queryPrefix: String,
        val documentPrefix: String,
        val pooling: Pooling,
        /** Encoder token budget per text; content is truncated beyond this. */
        val maxTokens: Int,
        val description: String
    ) {
        /** Sidecar key: identifies exactly which model+dim produced a vector, so
         *  a model or dimension change invalidates the index automatically. */
        val embeddingTag: String get() = "$id-$dimensions"
    }

    private const val BASE = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main"

    // EmbeddingGemma's asymmetric prompts (model card). Retrieval queries and
    // stored documents use different prefixes; getting this right matters more
    // than any quantization choice.
    private const val GEMMA_QUERY_PREFIX = "task: search result | query: "
    private const val GEMMA_DOCUMENT_PREFIX = "title: none | text: "

    val GEMMA_Q4 = Model(
        id = "embeddinggemma-300m-q4",
        displayName = "EmbeddingGemma 300M (q4)",
        sizeMb = 225,
        modelFileName = "model.onnx",
        modelUrl = "$BASE/onnx/model_q4.onnx",
        modelSha256 = null,
        tokenizerFileName = "tokenizer.json",
        tokenizerUrl = "$BASE/tokenizer.json",
        tokenizerSha256 = null,
        dimensions = 256,
        fullDimensions = 768,
        queryPrefix = GEMMA_QUERY_PREFIX,
        documentPrefix = GEMMA_DOCUMENT_PREFIX,
        pooling = Pooling.MEAN,
        maxTokens = 512,
        description = "Recommended for the Pixel: smallest, runs in well under 200 MB RAM."
    )

    val GEMMA_INT8 = Model(
        id = "embeddinggemma-300m-int8",
        displayName = "EmbeddingGemma 300M (int8)",
        sizeMb = 340,
        modelFileName = "model.onnx",
        modelUrl = "$BASE/onnx/model_quantized.onnx",
        modelSha256 = null,
        tokenizerFileName = "tokenizer.json",
        tokenizerUrl = "$BASE/tokenizer.json",
        tokenizerSha256 = null,
        dimensions = 256,
        fullDimensions = 768,
        queryPrefix = GEMMA_QUERY_PREFIX,
        documentPrefix = GEMMA_DOCUMENT_PREFIX,
        pooling = Pooling.MEAN,
        maxTokens = 512,
        description = "Higher quality than q4, larger and a little slower."
    )

    /** Display order in the model picker (smallest first). */
    val ALL: List<Model> = listOf(GEMMA_Q4, GEMMA_INT8)

    val DEFAULT: Model = GEMMA_Q4

    fun byId(id: String): Model? = ALL.firstOrNull { it.id == id }

    fun byTag(tag: String): Model? = ALL.firstOrNull { it.embeddingTag == tag }
}
