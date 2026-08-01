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

class LoreDedupTest {

    private fun entry(id: String, bookId: String, content: String, label: String = id) =
        LoreBookEntry(id = id, lorebookId = bookId, label = label, content = content)

    private fun match(id: String, bookId: String, content: String, trigger: String = "trigger") =
        LoreBookMatch(entry(id, bookId, content), trigger)

    @Test fun exactDuplicateContentAcrossBooksIsDropped() {
        val core = match("core-1", "core", "The dragon sleeps in the eastern cave.")
        val shared = match("shared-1", "shared", "The dragon sleeps in the eastern cave.")
        val result = LoreDedup.dedup(listOf(core, shared))
        assertEquals(listOf("core-1"), result.map { it.entry.id })
    }

    @Test fun firstOccurrenceWinsRegardlessOfWhichBookItIsFrom() {
        // Caller order IS book-priority order (core book first); dedup must
        // never reorder, only drop later duplicates.
        val core = match("core-1", "core", "shared fact")
        val other = match("other-1", "other", "shared fact")
        val result = LoreDedup.dedup(listOf(core, other))
        assertEquals("core-1", result.single().entry.id)
    }

    @Test fun whitespaceAndCaseDifferencesStillCountAsDuplicates() {
        val a = match("a", "book1", "  The Dragon Sleeps  ")
        val b = match("b", "book2", "the dragon   sleeps")
        val result = LoreDedup.dedup(listOf(a, b))
        assertEquals(listOf("a"), result.map { it.entry.id })
    }

    @Test fun differentContentIsNeverMerged() {
        val a = match("a", "book1", "The dragon sleeps in the cave.")
        val b = match("b", "book2", "The dragon guards the treasure.")
        val result = LoreDedup.dedup(listOf(a, b))
        assertEquals(2, result.size)
    }

    @Test fun droppedDuplicatesPairsEachDropWithItsSurvivor() {
        val core = match("core-1", "core", "shared fact")
        val other = match("other-1", "other", "shared fact")
        val dropped = LoreDedup.droppedDuplicates(listOf(core, other))
        assertEquals(1, dropped.size)
        assertEquals("other-1", dropped[0].first.entry.id)
        assertEquals("core-1", dropped[0].second.entry.id)
    }

    @Test fun noDuplicatesMeansNothingDropped() {
        val a = match("a", "book1", "fact one")
        val b = match("b", "book2", "fact two")
        assertTrue(LoreDedup.droppedDuplicates(listOf(a, b)).isEmpty())
    }
}
