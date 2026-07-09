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

package org.teslasoft.assistant.preferences.memory.archivist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivistBatchPlannerTest {

    @Test
    fun smallConversationIsOneRequest() {
        val chunks = ArchivistBatchPlanner.splitIntoRequests(listOf(1000, 2000, 3000))
        assertEquals(listOf(listOf(0, 1, 2)), chunks)
    }

    @Test
    fun oversizedConversationSplitsOnRowBoundaries() {
        val chunks = ArchivistBatchPlanner.splitIntoRequests(listOf(60, 60, 60, 60), maxChars = 100)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), chunks)
        val chunks2 = ArchivistBatchPlanner.splitIntoRequests(listOf(40, 40, 40, 40), maxChars = 100)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), chunks2)
    }

    @Test
    fun singleRowOverBudgetTravelsAlone() {
        val chunks = ArchivistBatchPlanner.splitIntoRequests(listOf(10, 500, 10), maxChars = 100)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), chunks)
    }

    @Test
    fun emptyInputsYieldNoPlans() {
        assertTrue(ArchivistBatchPlanner.splitIntoRequests(emptyList()).isEmpty())
        assertTrue(ArchivistBatchPlanner.planBatches(emptyList()).isEmpty())
    }

    @Test
    fun smallRunIsOneBatch() {
        val batches = ArchivistBatchPlanner.planBatches(listOf(100, 100, 100))
        assertEquals(listOf(0..2), batches)
    }

    @Test
    fun batchClosesOnSize() {
        val batches = ArchivistBatchPlanner.planBatches(listOf(80, 80, 80), maxChars = 100, maxCount = 10)
        assertEquals(listOf(0..0, 1..1, 2..2), batches)
    }

    @Test
    fun batchClosesOnCount() {
        val sizes = List(25) { 1 }
        val batches = ArchivistBatchPlanner.planBatches(sizes, maxChars = 1000, maxCount = 10)
        assertEquals(listOf(0..9, 10..19, 20..24), batches)
        // Every conversation lands in exactly one batch, in order.
        assertEquals(25, batches.sumOf { it.count() })
    }

    @Test
    fun hundredConversationsAllCovered() {
        // The owner's "some idiot does 100 at a time" case: everything is
        // analyzed, just grouped for display.
        val sizes = List(100) { 50_000 }
        val batches = ArchivistBatchPlanner.planBatches(sizes)
        assertEquals(100, batches.sumOf { it.count() })
        assertTrue(batches.size > 1)
        for (i in 1 until batches.size) {
            assertEquals(batches[i - 1].last + 1, batches[i].first)
        }
    }
}
