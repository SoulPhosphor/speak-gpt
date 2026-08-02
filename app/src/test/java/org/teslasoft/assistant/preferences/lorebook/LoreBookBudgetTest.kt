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

package org.teslasoft.assistant.preferences.lorebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.LoreBookEntry

class LoreBookBudgetTest {

    private fun match(id: String, content: String) =
        LoreBookMatch(LoreBookEntry(id = id, lorebookId = "book", label = id, content = content), "trigger")

    @Test fun everythingFitsWhenUnderBothLimits() {
        val matches = listOf(match("a", "short"), match("b", "also short"))
        val result = LoreBookBudget.select(matches, maxEntries = 20, maxChars = 6000)
        assertEquals(listOf("a", "b"), result.kept.map { it.entry.id })
        assertTrue(result.cut.isEmpty())
    }

    @Test fun entryLimitCutsEverythingFromThatPointOnwardWithReason() {
        val matches = (1..5).map { match("m$it", "x") }
        val result = LoreBookBudget.select(matches, maxEntries = 3, maxChars = 6000)
        assertEquals(listOf("m1", "m2", "m3"), result.kept.map { it.entry.id })
        assertEquals(listOf("m4", "m5"), result.cut.map { it.match.entry.id })
        assertTrue(result.cut.all { it.reason.contains("entry limit") })
    }

    @Test fun characterBudgetStopsTheWalkWithoutBackfill() {
        // "big" (60 chars) doesn't fit after "small" (10 chars) within a 50-char
        // budget; the walk STOPS there rather than skipping ahead to try a
        // later, smaller candidate — lore stays simple/deterministic (§5.6).
        val small = match("small", "s".repeat(10))
        val big = match("big", "b".repeat(60))
        val tiny = match("tiny", "t".repeat(2))
        val result = LoreBookBudget.select(listOf(small, big, tiny), maxEntries = 20, maxChars = 50)
        assertEquals(listOf("small"), result.kept.map { it.entry.id })
        assertEquals(listOf("big", "tiny"), result.cut.map { it.match.entry.id })
        assertTrue(result.cut.all { it.reason.contains("character budget") })
    }

    @Test fun aSingleOversizedFirstEntryStillGetsIn() {
        // kept.isEmpty() bypasses the char check for the very first candidate —
        // one huge note is never zero-injected, matching the original loop.
        val huge = match("huge", "x".repeat(10_000))
        val result = LoreBookBudget.select(listOf(huge), maxEntries = 20, maxChars = 50)
        assertEquals(listOf("huge"), result.kept.map { it.entry.id })
        assertTrue(result.cut.isEmpty())
    }

    @Test fun emptyInputProducesEmptySelection() {
        val result = LoreBookBudget.select(emptyList(), maxEntries = 20, maxChars = 6000)
        assertTrue(result.kept.isEmpty())
        assertTrue(result.cut.isEmpty())
    }
}
