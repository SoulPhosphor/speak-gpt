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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-filter backfill (counterplan §10 A.2): a candidate removed by a
 * filter frees its slot for the next relevant candidate instead of
 * shrinking the final list.
 */
class RetrievalBackfillTest {

    @Test
    fun filteredCandidateIsBackfilledFromTheRankedStream() {
        val selection = RetrievalBackfill.select(listOf("a", "b", "c", "d"), topK = 2) { it != "b" }
        assertEquals(listOf("a", "c"), selection.kept)
        assertFalse(selection.scanCapReached)
    }

    @Test
    fun examinationStopsOnceTopKSurvive() {
        val examined = ArrayList<String>()
        val selection = RetrievalBackfill.select(listOf("a", "b", "c", "d"), topK = 2) {
            examined.add(it)
            true
        }
        assertEquals(listOf("a", "b"), selection.kept)
        assertEquals(listOf("a", "b"), examined)
        assertEquals(2, selection.examined)
    }

    @Test
    fun relevanceExhaustionReturnsWhatSurvived() {
        val selection = RetrievalBackfill.select(listOf("a", "b"), topK = 5) { true }
        assertEquals(listOf("a", "b"), selection.kept)
        assertFalse(selection.scanCapReached)
    }

    @Test
    fun scanCapBoundsTheWalkAndIsReported() {
        val candidates = (1..10).map { "m$it" }
        val selection = RetrievalBackfill.select(candidates, topK = 3, scanCap = 4) { false }
        assertTrue(selection.kept.isEmpty())
        assertEquals(4, selection.examined)
        assertTrue(selection.scanCapReached)
    }

    @Test
    fun capIsNotReportedWhenTheListSimplyEnds() {
        val selection = RetrievalBackfill.select(listOf("a", "b", "c"), topK = 5, scanCap = 3) { false }
        assertEquals(3, selection.examined)
        assertFalse(selection.scanCapReached)
    }

    @Test
    fun scanCapScalesWithRequestedTopK() {
        assertEquals(8 + RetrievalBackfill.SCAN_MARGIN, RetrievalBackfill.scanCap(8))
        // Headroom to backfill exists even at the maximum policy top-K.
        assertTrue(RetrievalBackfill.scanCap(64) > 64)
    }

    @Test
    fun nonPositiveTopKExaminesNothingWithoutMandatoryCandidates() {
        var calls = 0
        val selection = RetrievalBackfill.select(listOf("a", "b"), topK = 0) { calls++; true }
        assertTrue(selection.kept.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun mandatoryCandidateSurvivesBeyondNormalTopK() {
        val candidates = listOf("normal", "also-normal", "must")
        val selection = RetrievalBackfill.select(
            candidates,
            topK = 1,
            isMandatory = { it == "must" }
        ) { true }
        assertEquals(listOf("normal", "must"), selection.kept)
    }

    @Test
    fun mandatoryCandidateIsExaminedBeyondOrdinaryScanCap() {
        val candidates = listOf("drop", "skip", "must")
        val selection = RetrievalBackfill.select(
            candidates,
            topK = 1,
            scanCap = 1,
            isMandatory = { it == "must" }
        ) { it != "drop" }
        assertEquals(listOf("must"), selection.kept)
        assertTrue(selection.scanCapReached)
    }

    @Test
    fun mandatoryCandidatesDoNotConsumeNormalCountOrScanBudget() {
        val candidates = listOf("must-a", "must-b", "normal-a", "normal-b")
        val selection = RetrievalBackfill.select(
            candidates,
            topK = 2,
            scanCap = 2,
            isMandatory = { it.startsWith("must-") }
        ) { true }

        assertEquals(listOf("must-a", "must-b", "normal-a", "normal-b"), selection.kept)
        assertEquals(4, selection.examined)
        assertFalse(selection.scanCapReached)
    }
}