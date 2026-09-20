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

package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The episode/dedup rules of conversation-summary-errors.md §3. */
class SummarizerErrorLogTest {

    private fun record(
        current: List<SummarizerErrorEntry>,
        episode: String,
        category: SummarizerErrorCategory,
        time: Long = 1000L
    ) = SummarizerErrorLog.record(current, episode, category, time, "Work", "gpt-4o-mini", null)

    @Test
    fun readableDetailKeepsTheRealMessageWithoutStackFrames() {
        val cause = IllegalStateException(
            "Illegal input: Fields [id, created, model, choices] are required"
        )
        val wrapped = RuntimeException("Illegal input: Fields [id, created, model, choices] are required", cause)
        val detail = SummarizerErrorDetail.readable(wrapped)!!
        // The real error survives...
        assertTrue(detail.contains("Fields [id, created, model, choices]"))
        // ...deduplicated to a single line when the cause repeats it...
        assertEquals(1, detail.lines().size)
        // ...and never carries stack frames.
        assertFalse(detail.contains("at "))
    }

    @Test
    fun readableDetailKeepsDistinctCauseMessages() {
        val root = IllegalArgumentException("underlying provider said no")
        val top = RuntimeException("could not update the summary", root)
        val detail = SummarizerErrorDetail.readable(top)!!
        assertEquals(2, detail.lines().size)
        assertTrue(detail.contains("could not update the summary"))
        assertTrue(detail.contains("underlying provider said no"))
    }

    @Test
    fun readableDetailFallsBackToTypeWhenNoMessage() {
        assertEquals("java.lang.NullPointerException", SummarizerErrorDetail.readable(NullPointerException()))
        assertEquals(null, SummarizerErrorDetail.readable(null))
    }

    @Test
    fun removeAtDropsThePickedEntryAndLeavesTheRest() {
        val a = record(emptyList(), "", SummarizerErrorCategory.CONNECT_TIMEOUT, 1000L).entries
        val b = record(a, SummarizerErrorCategory.CONNECT_TIMEOUT.name, SummarizerErrorCategory.QUOTA, 2000L).entries
        // Newest first, so QUOTA is at index 0 and CONNECT_TIMEOUT at index 1.
        assertEquals(2, b.size)
        val afterHidingNewest = SummarizerErrorLog.removeAt(b, 0)
        assertEquals(1, afterHidingNewest.size)
        assertEquals(SummarizerErrorCategory.CONNECT_TIMEOUT.name, afterHidingNewest[0].category)
        assertTrue(SummarizerErrorLog.removeAt(afterHidingNewest, 0).isEmpty())
    }

    @Test
    fun removeAtIgnoresAnOutOfRangeIndex() {
        val one = record(emptyList(), "", SummarizerErrorCategory.CONNECT_TIMEOUT).entries
        assertEquals(one, SummarizerErrorLog.removeAt(one, 5))
        assertEquals(one, SummarizerErrorLog.removeAt(one, -1))
    }

    @Test
    fun firstFailureStartsAnEpisodeAndPlaysTheSound() {
        val result = record(emptyList(), "", SummarizerErrorCategory.CONNECT_TIMEOUT)
        assertTrue(result.newEpisode)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.entries[0].count)
    }

    @Test
    fun repeatedSameCategoryFailuresMergeInsteadOfFillingTheLog() {
        var entries = record(emptyList(), "", SummarizerErrorCategory.CONNECT_TIMEOUT, 1000L).entries
        val episode = SummarizerErrorCategory.CONNECT_TIMEOUT.name
        repeat(7) { i ->
            val result = record(entries, episode, SummarizerErrorCategory.CONNECT_TIMEOUT, 2000L + i)
            assertFalse("retry ${i + 1} must not start a new episode", result.newEpisode)
            entries = result.entries
        }
        assertEquals(1, entries.size)
        assertEquals(8, entries[0].count)
        assertEquals(2006L, entries[0].timestamp)
    }

    @Test
    fun aDifferentCategoryStartsANewEntryAndEpisode() {
        val first = record(emptyList(), "", SummarizerErrorCategory.CONNECT_TIMEOUT).entries
        val result = record(
            first, SummarizerErrorCategory.CONNECT_TIMEOUT.name, SummarizerErrorCategory.QUOTA
        )
        assertTrue(result.newEpisode)
        assertEquals(2, result.entries.size)
        assertEquals(SummarizerErrorCategory.QUOTA.name, result.entries[0].category)
    }

    @Test
    fun aSuccessEndsTheEpisodeSoTheNextFailureIsNewEvenForTheSameCategory() {
        val first = record(emptyList(), "", SummarizerErrorCategory.CONNECT_TIMEOUT).entries
        // "" episode = a fold-in succeeded since (commitSummarizerFoldIn resets it).
        val result = record(first, "", SummarizerErrorCategory.CONNECT_TIMEOUT)
        assertTrue(result.newEpisode)
        assertEquals(2, result.entries.size)
    }

    @Test
    fun theLogKeepsAtMostFiveEntriesEvictingTheOldest() {
        var entries = emptyList<SummarizerErrorEntry>()
        val categories = listOf(
            SummarizerErrorCategory.CONNECT_TIMEOUT,
            SummarizerErrorCategory.QUOTA,
            SummarizerErrorCategory.RATE_LIMIT,
            SummarizerErrorCategory.ACCESS_REJECTED,
            SummarizerErrorCategory.MODEL_UNAVAILABLE,
            SummarizerErrorCategory.SERVICE_ERROR
        )
        var episode = ""
        for (category in categories) {
            val result = record(entries, episode, category)
            entries = result.entries
            episode = category.name
        }
        assertEquals(SummarizerErrorLog.MAX_ENTRIES, entries.size)
        // The very first category fell off; the newest is first.
        assertEquals(SummarizerErrorCategory.SERVICE_ERROR.name, entries[0].category)
        assertFalse(entries.any { it.category == SummarizerErrorCategory.CONNECT_TIMEOUT.name })
    }

    @Test
    fun deletingTheLogMidEpisodeLetsTheNextFailureCreateAFreshEntry() {
        // §3: after Delete, a later failure begins a new entry and may play
        // the sound again — even though the episode marker was still set.
        val result = record(emptyList(), SummarizerErrorCategory.CONNECT_TIMEOUT.name, SummarizerErrorCategory.CONNECT_TIMEOUT)
        assertTrue(result.newEpisode)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun jsonRoundTripPreservesEntries() {
        val entries = record(emptyList(), "", SummarizerErrorCategory.UNEXPECTED).entries
        val json = SummarizerErrorLog.toJson(entries)
        val parsed = SummarizerErrorLog.fromJson(json)
        assertEquals(entries, parsed)
    }

    @Test
    fun unreadableJsonReadsAsAnEmptyLogInsteadOfCrashing() {
        assertTrue(SummarizerErrorLog.fromJson("not json").isEmpty())
        assertTrue(SummarizerErrorLog.fromJson(null).isEmpty())
        assertTrue(SummarizerErrorLog.fromJson("").isEmpty())
    }
}
