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

package org.teslasoft.assistant.preferences.memory.enforcer

/**
 * Lore-book coexistence rule #2 (enforcer spec): the model must never see the
 * same fact twice, so a retrieved memory that is a near-duplicate of an
 * injected lore entry is suppressed (the lore entry — user-authored — wins).
 * Vector cosine when the librarian's model is available; word-overlap
 * (Jaccard) fallback otherwise. Pure Kotlin, unit-tested.
 */
object NearDuplicate {

    /** Cosine similarity above which a memory duplicates a lore entry. */
    const val COSINE_THRESHOLD = 0.85f

    /** Jaccard word-overlap threshold for the no-model fallback. */
    const val JACCARD_THRESHOLD = 0.5

    fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()

    fun jaccard(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        return inter / (ta.size + tb.size - inter)
    }

    fun isTextNearDup(memoryText: String, loreText: String): Boolean =
        jaccard(memoryText, loreText) >= JACCARD_THRESHOLD
}
