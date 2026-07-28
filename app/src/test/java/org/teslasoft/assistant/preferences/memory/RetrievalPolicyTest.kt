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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.enforcer.PromptAssembler

/**
 * A stored/imported retrieval_policy is data, not code (counterplan §5.5):
 * malformed values must bound to safe defaults with a recorded substitution,
 * never disable the whole RAG assembly for the turn.
 */
class RetrievalPolicyTest {

    /* -------- top-K -------- */

    @Test
    fun absentTopKUsesDefaultSilently() {
        val b = RetrievalPolicy.boundTopK(null)
        assertEquals(RetrievalPolicy.DEFAULT_TOP_K, b.value)
        assertNull(b.substitutionNote)
    }

    @Test
    fun validTopKPassesThrough() {
        val b = RetrievalPolicy.boundTopK(12)
        assertEquals(12, b.value)
        assertNull(b.substitutionNote)
    }

    @Test
    fun negativeOrZeroTopKSubstitutesDefault() {
        for (raw in listOf(-3, 0)) {
            val b = RetrievalPolicy.boundTopK(raw)
            assertEquals(RetrievalPolicy.DEFAULT_TOP_K, b.value)
            assertNotNull(b.substitutionNote)
        }
    }

    @Test
    fun extremeTopKIsCapped() {
        val b = RetrievalPolicy.boundTopK(100_000)
        assertEquals(RetrievalPolicy.MAX_TOP_K, b.value)
        assertNotNull(b.substitutionNote)
    }

    @Test
    fun nonNumericTopKSubstitutesDefault() {
        for (raw in listOf<Any?>("eight", Double.NaN, Double.POSITIVE_INFINITY)) {
            val b = RetrievalPolicy.boundTopK(raw)
            assertEquals(RetrievalPolicy.DEFAULT_TOP_K, b.value)
            assertNotNull(b.substitutionNote)
        }
    }

    /* -------- char budget -------- */

    @Test
    fun absentBudgetUsesAssemblerDefaultSilently() {
        val b = RetrievalPolicy.boundCharBudget(null)
        assertEquals(PromptAssembler.DEFAULT_CHAR_BUDGET, b.value)
        assertNull(b.substitutionNote)
    }

    @Test
    fun negativeBudgetSubstitutesDefault() {
        val b = RetrievalPolicy.boundCharBudget(-500)
        assertEquals(PromptAssembler.DEFAULT_CHAR_BUDGET, b.value)
        assertNotNull(b.substitutionNote)
    }

    @Test
    fun extremeBudgetIsCapped() {
        val b = RetrievalPolicy.boundCharBudget(Int.MAX_VALUE)
        assertEquals(RetrievalPolicy.MAX_CHAR_BUDGET, b.value)
        assertNotNull(b.substitutionNote)
    }

    /* -------- weights -------- */

    @Test
    fun absentWeightsUseDefaultsSilently() {
        val b = RetrievalPolicy.boundWeights(null)
        assertEquals(RetrievalPolicy.DEFAULT_W_SIM, b.value[0], 1e-9)
        assertEquals(RetrievalPolicy.DEFAULT_W_IMP, b.value[1], 1e-9)
        assertEquals(RetrievalPolicy.DEFAULT_W_REC, b.value[2], 1e-9)
        assertNull(b.substitutionNote)
    }

    @Test
    fun validCustomWeightsPassThrough() {
        val b = RetrievalPolicy.boundWeights(doubleArrayOf(0.8, 0.1, 0.1))
        assertTrue(b.value.contentEquals(doubleArrayOf(0.8, 0.1, 0.1)))
        assertNull(b.substitutionNote)
    }

    @Test
    fun malformedWeightsSubstituteDefaults() {
        val malformed = listOf(
            doubleArrayOf(0.6, 0.3),                     // wrong size
            doubleArrayOf(-0.1, 0.3, 0.1),               // negative
            doubleArrayOf(Double.NaN, 0.3, 0.1),         // non-finite
            doubleArrayOf(Double.POSITIVE_INFINITY, 0.3, 0.1),
            doubleArrayOf(0.0, 0.0, 0.0),                // all zero: pure-boost ranking
            doubleArrayOf(1e6, 0.3, 0.1)                 // oversized
        )
        for (raw in malformed) {
            val b = RetrievalPolicy.boundWeights(raw)
            assertEquals(RetrievalPolicy.DEFAULT_W_SIM, b.value[0], 1e-9)
            assertNotNull(b.substitutionNote)
        }
    }
}
