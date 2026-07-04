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
import ai.onnxruntime.extensions.OrtxPackage
import android.content.Context
import java.nio.LongBuffer

/**
 * EmbeddingGemma via ONNX Runtime + ONNX Runtime Extensions.
 *
 * Two sessions: a tokenizer graph (EmbeddingGemma's SentencePiece tokenizer
 * wrapped as an ONNX custom op, run through the extensions library) turns a
 * string into token ids; the transformer turns ids into a sentence vector,
 * which is mean-pooled if needed, L2-normalized, Matryoshka-truncated to the
 * catalog dimension, and re-normalized.
 *
 * ON-DEVICE VALIDATION PENDING (memory-system-integration-plan.md, Phase 3).
 * huggingface.co is blocked in the build sandbox, so the exact tensor names
 * and the tokenizer-graph artifact could not be exercised here. The code
 * therefore PROBES input/output names rather than hardcoding them, and every
 * step is guarded: any mismatch throws, the Librarian catches it, and
 * retrieval degrades to keyword matching — exactly how Silero falls back to
 * the energy detector. Confirm names/behaviour on the Pixel and tighten if
 * needed.
 *
 * The environment and sessions are heavy; [create] builds them once and the
 * Librarian keeps the instance for the process. Not thread-safe: the Librarian
 * serializes embed calls.
 */
class OnnxEmbeddingModel private constructor(
    private val env: OrtEnvironment,
    private val tokenizerSession: OrtSession,
    private val modelSession: OrtSession,
    override val tag: String,
    override val dimensions: Int,
    private val fullDimensions: Int
) : EmbeddingModel {

    // EmbeddingGemma's asymmetric prompts. Retrieval queries and stored
    // documents use different prefixes; getting this right matters more than
    // any quantization choice.
    private val queryPrefix = "task: search result | query: "
    private val documentPrefix = "title: none | text: "

    override fun embed(text: String, isQuery: Boolean): FloatArray {
        val prepared = (if (isQuery) queryPrefix else documentPrefix) + text
        val (ids, mask) = tokenize(prepared)
        val pooled = runModel(ids, mask)
        val truncated = VectorMath.truncate(pooled, dimensions)
        return VectorMath.normalize(truncated)
    }

    private fun tokenize(text: String): Pair<LongArray, LongArray> {
        val inputName = tokenizerSession.inputNames.firstOrNull()
            ?: throw IllegalStateException("tokenizer has no input")
        OnnxTensor.createTensor(env, arrayOf(text), longArrayOf(1)).use { inputT ->
            tokenizerSession.run(mapOf(inputName to inputT)).use { result ->
                val outputs = tokenizerSession.outputNames.toList()
                val idsName = outputs.firstOrNull { it.contains("ids", true) } ?: outputs.first()
                val ids = readInt64(result, idsName)
                val maskName = outputs.firstOrNull { it.contains("mask", true) }
                val mask = if (maskName != null) readInt64(result, maskName) else LongArray(ids.size) { 1L }
                return ids to mask
            }
        }
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
                // Prefer a ready sentence embedding; otherwise mean-pool the token states.
                val sentenceName = outputs.firstOrNull {
                    it.contains("sentence", true) || it.contains("embedding", true) || it.contains("pooler", true)
                }
                if (sentenceName != null) {
                    val vec = readFloatVector(result, sentenceName)
                    if (vec.size >= fullDimensions) return vec
                }
                val hiddenName = outputs.firstOrNull { it.contains("hidden", true) } ?: outputs.first()
                return meanPool(readFloat3D(result, hiddenName), mask)
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

    private fun readInt64(result: OrtSession.Result, name: String): LongArray {
        val value = result.get(name).orElseThrow { IllegalStateException("missing tokenizer output $name") }.value
        return flattenLong(value)
    }

    private fun flattenLong(value: Any?): LongArray = when (value) {
        is LongArray -> value
        is Array<*> -> {
            val parts = value.map { flattenLong(it) }
            val total = parts.sumOf { it.size }
            val out = LongArray(total)
            var i = 0
            for (p in parts) { System.arraycopy(p, 0, out, i, p.size); i += p.size }
            out
        }
        is IntArray -> LongArray(value.size) { value[it].toLong() }
        else -> throw IllegalStateException("unexpected token id type ${value?.javaClass}")
    }

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
        try { tokenizerSession.close() } catch (_: Throwable) { }
        try { modelSession.close() } catch (_: Throwable) { }
    }

    companion object {
        /**
         * Builds both sessions with the extensions custom-op library registered
         * (needed for the tokenizer graph's SentencePiece op). Throws on any
         * failure — the Librarian treats a null/thrown model as "no vectors
         * this session" and uses keyword fallback.
         */
        fun create(context: Context, model: EmbeddingModels.Model): OnnxEmbeddingModel {
            val modelFile = EmbeddingModelStorage.modelFile(context, model)
            val tokFile = EmbeddingModelStorage.tokenizerFile(context, model)
            require(modelFile.exists() && tokFile.exists()) { "model files missing" }

            val env = OrtEnvironment.getEnvironment()
            val extPath = OrtxPackage.getLibraryPath()

            val tokOptions = OrtSession.SessionOptions().apply { registerCustomOpLibrary(extPath) }
            val modelOptions = OrtSession.SessionOptions().apply { registerCustomOpLibrary(extPath) }

            val tokSession = env.createSession(tokFile.path, tokOptions)
            val modelSession = env.createSession(modelFile.path, modelOptions)

            return OnnxEmbeddingModel(
                env, tokSession, modelSession,
                model.embeddingTag, model.dimensions, model.fullDimensions
            )
        }
    }
}
