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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.LoreBookEntry
import org.teslasoft.assistant.preferences.memory.ActiveMemoryAttribution

class LoreBookBudgetTest {

    private fun match(id: String, content: String) =
        LoreBookMatch(LoreBookEntry(id = id, lorebookId = "book", label = id, content = content), "trigger")

    @Test fun everythingFitsWhenUnderBothLimits() {
        val matches = listOf(match("a", "short"), match("b", "also short"))
        val result = LoreBookBudget.select(matches, maxEntries = 20, maxChars = null)
        assertEquals(listOf("a", "b"), result.kept.map { it.entry.id })
        assertTrue(result.cut.isEmpty())
    }

    @Test fun entryLimitCutsEverythingFromThatPointOnwardWithReason() {
        val matches = (1..5).map { match("m$it", "x") }
        val result = LoreBookBudget.select(matches, maxEntries = 3, maxChars = null)
        assertEquals(listOf("m1", "m2", "m3"), result.kept.map { it.entry.id })
        assertEquals(listOf("m4", "m5"), result.cut.map { it.match.entry.id })
        assertTrue(result.cut.all { it.reason.contains("entry limit") })
    }

    @Test fun characterLimitSkipsAnEntryThatDoesNotFitAndKeepsLaterOnesThatDo() {
        // Owner rule (Stage 2): with the optional character limit on, fewer
        // entries may be sent; an entry that would pass the limit is skipped
        // and later entries that still fit are kept. The limit is never passed.
        val small = match("small", "s".repeat(10))
        val big = match("big", "b".repeat(60))
        val tiny = match("tiny", "t".repeat(2))
        val result = LoreBookBudget.select(listOf(small, big, tiny), maxEntries = 20, maxChars = 50)
        assertEquals(listOf("small", "tiny"), result.kept.map { it.entry.id })
        assertEquals(listOf("big"), result.cut.map { it.match.entry.id })
        assertTrue(result.cut.all { it.reason.contains("character budget") })
    }

    @Test fun withoutACharacterLimitOnlyTheEntryLimitApplies() {
        val huge = match("huge", "x".repeat(50_000))
        val result = LoreBookBudget.select(listOf(huge, match("b", "y")), maxEntries = 20, maxChars = null)
        assertEquals(listOf("huge", "b"), result.kept.map { it.entry.id })
    }

    @Test fun budgetTrimmedLorebookEntryNeverAppearsInAttribution() {
        val kept = match("kept", "k".repeat(10))
        val trimmed = match("trimmed", "t".repeat(60))
        val selection = LoreBookBudget.select(
            listOf(kept, trimmed),
            maxEntries = 20,
            maxChars = 50
        )

        val attribution = ActiveMemoryAttribution.fromFinalSelection(
            memoryIds = emptyList(),
            lorebookIds = selection.injectedEntryIds
        )

        assertEquals(listOf("kept"), attribution.map { it.id })
        assertFalse(attribution.any { it.id == "trimmed" })
    }

    @Test fun aFirstEntryLongerThanTheCharacterLimitIsNotSent() {
        val huge = match("huge", "x".repeat(10_000))
        val result = LoreBookBudget.select(listOf(huge), maxEntries = 20, maxChars = 50)
        assertTrue(result.kept.isEmpty())
        assertEquals(listOf("huge"), result.cut.map { it.match.entry.id })
    }

    @Test fun emptyInputProducesEmptySelection() {
        val result = LoreBookBudget.select(emptyList(), maxEntries = 20, maxChars = null)
        assertTrue(result.kept.isEmpty())
        assertTrue(result.cut.isEmpty())
    }
}
