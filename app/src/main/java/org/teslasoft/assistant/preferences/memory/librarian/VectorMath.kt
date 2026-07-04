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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin vector helpers for the librarian: cosine similarity, L2
 * normalization, and the float32 <-> BLOB codec used by the embeddings sidecar.
 * No Android or ORT imports, so the retrieval math is unit-tested on the JVM
 * (VectorMathTest) — the part of the librarian that must be provably correct
 * regardless of which embedding model produced the vectors.
 */
object VectorMath {

    /** Cosine similarity in [-1, 1]. Zero-magnitude vectors score 0 (never NaN). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
    }

    /** In-place L2 normalization; a zero vector is left untouched. */
    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x.toDouble()
        if (n == 0.0) return v
        val inv = (1.0 / Math.sqrt(n)).toFloat()
        for (i in v.indices) v[i] = v[i] * inv
        return v
    }

    /** Matryoshka truncation: keep the first [dims] components (then the caller
     *  usually re-normalizes). Returns the input untouched if already short. */
    fun truncate(v: FloatArray, dims: Int): FloatArray {
        if (dims <= 0 || v.size <= dims) return v
        return v.copyOfRange(0, dims)
    }

    /** float32 array -> little-endian BLOB for the embeddings.vector column. */
    fun toBlob(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    /** BLOB -> float32 array. Trailing bytes that don't fill a float are ignored. */
    fun fromBlob(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(blob.size / 4)
        for (i in out.indices) out[i] = buf.float
        return out
    }
}
