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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer

/**
 * Embedding inference via ONNX Runtime, tokenized on-device by [HfTokenizer]
 * from the model repo's own `tokenizer.json` (downloaded at runtime alongside
 * the transformer — the app never bundles or redistributes model-licensed
 * tokenizer artifacts; owner decision, July 2026). All model-specific choices
 * (prompt prefixes, pooling, dimensions, token budget) come from the catalog
 * entry, so a different future model (BGE-M3 etc.) needs no changes here.
 *
 * ON-DEVICE VALIDATION PENDING (memory-system-integration-plan.md, Phase 3).
 * huggingface.co is blocked in the build sandbox, so the exact tensor names
 * could not be exercised here. The code therefore PROBES input/output names
 * rather than hardcoding them, and every step is guarded: any mismatch throws,
 * the Librarian catches it, and retrieval degrades to keyword matching —
 * exactly how Silero falls back to the energy detector. On top of that the
 * Librarian runs [selfCheck] once per installed model before trusting it, so
 * a pipeline that loads but embeds nonsense is disabled instead of silently
 * poisoning the index.
 *
 * The environment and session are heavy; [create] builds them once and the
 * Librarian keeps the instance for the process. Not thread-safe: the Librarian
 * serializes embed calls.
 */
class OnnxEmbeddingModel private constructor(
    private val env: OrtEnvironment,
    private val tokenizer: HfTokenizer,
    private val modelSession: OrtSession,
    override val tag: String,
    override val dimensions: Int,
    private val fullDimensions: Int,
    private val queryPrefix: String,
    private val documentPrefix: String,
    private val pooling: EmbeddingModels.Pooling,
    private val maxTokens: Int
) : EmbeddingModel {

    override fun embed(text: String, isQuery: Boolean): FloatArray {
        val prepared = (if (isQuery) queryPrefix else documentPrefix) + text
        val ids = tokenizer.encode(prepared, maxTokens)
        if (ids.isEmpty()) throw IllegalStateException("tokenizer produced no ids")
        val mask = LongArray(ids.size) { 1L }
        val pooled = runModel(ids, mask)
        val truncated = VectorMath.truncate(pooled, dimensions)
        return VectorMath.normalize(truncated)
    }

    /**
     * End-to-end sanity check of tokenizer + graph + pooling, run by the
     * Librarian once per installed model (marker-cached). Embeds two related
     * and one unrelated sentence and demands the obvious ordering with a
     * margin, plus non-degenerate finite vectors. Returns null on pass, else
     * a human-readable failure reason for MemoryLog. A wrong tokenizer or a
     * mis-probed tensor fails here BEFORE any vector reaches the index.
     */
    fun selfCheck(): String? {
        val a = embed("I love drinking coffee in the morning.", isQuery = false)
        val b = embed("My favorite morning drink is coffee.", isQuery = false)
        val c = embed("The spaceship landed on the distant planet.", isQuery = false)

        for ((name, v) in listOf("a" to a, "b" to b, "c" to c)) {
            if (v.size != dimensions) return "vector '$name' has ${v.size} dims, expected $dimensions"
            var norm = 0.0
            for (x in v) {
                if (!x.isFinite()) return "vector '$name' contains non-finite values"
                norm += x.toDouble() * x.toDouble()
            }
            if (norm < 1e-6) return "vector '$name' is (near) zero"
        }

        val simRelated = VectorMath.cosine(a, b)
        val simUnrelated = VectorMath.cosine(a, c)
        // Near-identical similarities for ALL pairs means the model collapsed
        // to a constant output (a classic wrong-tokenizer symptom).
        if (simUnrelated > 0.98f) return "unrelated texts score $simUnrelated — output looks constant"
        if (simRelated < simUnrelated + 0.05f)
            return "related pair ($simRelated) not above unrelated pair ($simUnrelated) — embeddings look wrong"
        return null
    }

    private fun runModel(ids: LongArray, mask: LongArray): FloatArray {
        val seq = ids.size
        val shape = longArrayOf(1, seq.toLong())
        val tensors = HashMap<String, OnnxTensor>()
        try {
            for (name in modelSession.inputNames) {
                val tensor = when {
                    name.contains("input_ids", true) || (name.contains("ids", true) && !name.contains("position", true)) ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                    name.contains("attention", true) || name.contains("mask", true) ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
                    name.contains("token_type", true) ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq)), shape)
                    name.contains("position", true) ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { it.toLong() }), shape)
                    else -> continue // unknown optional input: let the model default it
                }
                tensors[name] = tensor
            }

            modelSession.run(tensors).use { result ->
                val outputs = modelSession.outputNames.toList()
                // Prefer a ready sentence embedding; otherwise pool the token states.
                val sentenceName = outputs.firstOrNull {
                    it.contains("sentence", true) || it.contains("embedding", true) || it.contains("pooler", true)
                }
                if (sentenceName != null) {
                    val vec = readFloatVector(result, sentenceName)
                    if (vec.size >= fullDimensions) return vec
                }
                val hiddenName = outputs.firstOrNull { it.contains("hidden", true) } ?: outputs.first()
                val states = readFloat3D(result, hiddenName)
                return when (pooling) {
                    EmbeddingModels.Pooling.MEAN -> meanPool(states, mask)
                    EmbeddingModels.Pooling.CLS ->
                        states.firstOrNull() ?: throw IllegalStateException("empty hidden states")
                }
            }
        } finally {
            for (t in tensors.values) try { t.close() } catch (_: Throwable) { }
        }
    }

    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        if (hidden.isEmpty()) return FloatArray(0)
        val dim = hidden[0].size
        val out = FloatArray(dim)
        var count = 0.0
        for (t in hidden.indices) {
            if (t < mask.size && mask[t] == 0L) continue
            val row = hidden[t]
            for (d in 0 until dim) out[d] += row[d]
            count += 1.0
        }
        if (count > 0) for (d in 0 until dim) out[d] = (out[d] / count).toFloat()
        return out
    }

    /* --- output readers, tolerant of the several shapes ORT hands back --- */

    /** [1, dim] or [dim] float output -> flat vector. */
    private fun readFloatVector(result: OrtSession.Result, name: String): FloatArray {
        val value = result.get(name).orElseThrow { IllegalStateException("missing output $name") }.value
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val first = value.firstOrNull()
                if (first is FloatArray) first else throw IllegalStateException("unexpected embedding shape")
            }
            else -> throw IllegalStateException("unexpected embedding type ${value?.javaClass}")
        }
    }

    /** [1, seq, dim] float output -> Array<seq> of FloatArray<dim>. */
    @Suppress("UNCHECKED_CAST")
    private fun readFloat3D(result: OrtSession.Result, name: String): Array<FloatArray> {
        val value = result.get(name).orElseThrow { IllegalStateException("missing output $name") }.value
        val batch = value as? Array<*> ?: throw IllegalStateException("unexpected hidden state type")
        val first = batch.firstOrNull() as? Array<FloatArray>
            ?: throw IllegalStateException("unexpected hidden state shape")
        return first
    }

    override fun close() {
        try { modelSession.close() } catch (_: Throwable) { }
    }

    companion object {
        /**
         * Loads the tokenizer.json and builds the ORT session. Throws on any
         * failure — including a tokenizer whose basic output is already broken
         * (empty/out-of-range ids) — and the Librarian treats a thrown model as
         * "no vectors this session" and uses keyword fallback.
         */
        fun create(context: Context, model: EmbeddingModels.Model): OnnxEmbeddingModel {
            val modelFile = EmbeddingModelStorage.modelFile(context, model)
            val tokFile = EmbeddingModelStorage.tokenizerFile(context, model)
            require(modelFile.exists() && tokFile.exists()) { "model files missing" }

            val tokenizer = HfTokenizer.load(tokFile)
            // Cheap structural sanity before any inference: ids exist, are in
            // range, and distinct texts tokenize differently.
            val probeA = tokenizer.encode("hello world", 64)
            val probeB = tokenizer.encode("The quick brown fox jumps over 12 lazy dogs.", 64)
            if (probeA.isEmpty() || probeB.isEmpty())
                throw IllegalStateException("tokenizer produced no ids")
            if (probeA.contentEquals(probeB))
                throw IllegalStateException("tokenizer maps different texts to identical ids")
            for (id in probeA + probeB) {
                if (id < 0 || id >= tokenizer.vocabSize)
                    throw IllegalStateException("tokenizer id $id out of range (vocab ${tokenizer.vocabSize})")
            }

            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelFile.path, OrtSession.SessionOptions())

            return OnnxEmbeddingModel(
                env, tokenizer, session,
                model.embeddingTag, model.dimensions, model.fullDimensions,
                model.queryPrefix, model.documentPrefix, model.pooling, model.maxTokens
            )
        }
    }
}
