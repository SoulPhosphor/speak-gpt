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
 * Default: EmbeddingGemma-300m (onnx-community ONNX export), output truncated
 * to 256 dims via Matryoshka — the enforcer spec's recommendation (negligible
 * quality loss on short prose memories, smaller vectors + faster search).
 *
 * Each variant is TWO files in its own directory: the transformer (`model*.onnx`
 * from onnx-community/embeddinggemma-300m-ONNX) and a tokenizer graph
 * (`tokenizer.onnx`). The tokenizer graph wraps EmbeddingGemma's SentencePiece
 * tokenizer as an ONNX custom op (generated offline with
 * onnxruntime_extensions.gen_processing_models from the repo's tokenizer.json);
 * it is run through ONNX Runtime Extensions on-device — see OnnxEmbeddingModel.
 *
 * ON-DEVICE VALIDATION PENDING (memory-system-integration-plan.md, Phase 3):
 * these URLs and the model/tokenizer I/O names follow the standard
 * onnx-community layout but were NOT reachable from the build sandbox
 * (huggingface.co is blocked by the network policy). Confirm them on the
 * Pixel — same situation as the Whisper models, whose SHAs are also null and
 * validated on-device. sha256 stays null until a known-good hash is captured;
 * the downloader then verifies automatically with no code change.
 */
object EmbeddingModels {

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
        /** Tokenizer ONNX file name inside the model directory. */
        val tokenizerFileName: String,
        /** Direct URL to the tokenizer ONNX graph. */
        val tokenizerUrl: String,
        val tokenizerSha256: String?,
        /** Output vector length after Matryoshka truncation. */
        val dimensions: Int,
        /** Full model dim before truncation (for validation of the raw output). */
        val fullDimensions: Int,
        val description: String
    ) {
        /** Sidecar key: identifies exactly which model+dim produced a vector, so
         *  a model or dimension change invalidates the index automatically. */
        val embeddingTag: String get() = "$id-$dimensions"
    }

    private const val BASE = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main"
    // The tokenizer graph is generated from the repo's tokenizer.json; host it
    // alongside the model. Placeholder path confirmed/hosted during on-device
    // bring-up (see class header).
    private const val TOK = "$BASE/onnx/tokenizer.onnx"

    val GEMMA_Q4 = Model(
        id = "embeddinggemma-300m-q4",
        displayName = "EmbeddingGemma 300M (q4)",
        sizeMb = 190,
        modelFileName = "model.onnx",
        modelUrl = "$BASE/onnx/model_q4.onnx",
        modelSha256 = null,
        tokenizerFileName = "tokenizer.onnx",
        tokenizerUrl = TOK,
        tokenizerSha256 = null,
        dimensions = 256,
        fullDimensions = 768,
        description = "Recommended for the Pixel: smallest, runs in well under 200 MB RAM."
    )

    val GEMMA_INT8 = Model(
        id = "embeddinggemma-300m-int8",
        displayName = "EmbeddingGemma 300M (int8)",
        sizeMb = 320,
        modelFileName = "model.onnx",
        modelUrl = "$BASE/onnx/model_quantized.onnx",
        modelSha256 = null,
        tokenizerFileName = "tokenizer.onnx",
        tokenizerUrl = TOK,
        tokenizerSha256 = null,
        dimensions = 256,
        fullDimensions = 768,
        description = "Higher quality than q4, larger and a little slower."
    )

    /** Display order in the model picker (smallest first). */
    val ALL: List<Model> = listOf(GEMMA_Q4, GEMMA_INT8)

    val DEFAULT: Model = GEMMA_Q4

    fun byId(id: String): Model? = ALL.firstOrNull { it.id == id }

    fun byTag(tag: String): Model? = ALL.firstOrNull { it.embeddingTag == tag }
}
