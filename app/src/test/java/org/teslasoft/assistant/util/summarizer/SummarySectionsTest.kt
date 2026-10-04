/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.util.summarizer.SummarySections.SourceMessage
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class SummarySectionsTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Alternating user/assistant turns, ids m0..m(n-1), all on Oct 1. */
    private fun chat(n: Int, time: (Int) -> Long? = { at(1, 9, it) }): List<SourceMessage> =
        (0 until n).map { SourceMessage("m$it", it % 2 == 1, "text $it", time(it)) }

    @Test
    fun aSectionFormsAtTheBatchSizeOnATurnBoundary() {
        val messages = chat(30)
        val range = SummarySections.nextRange(messages, 0, windowEdge = 20, batchSize = 10, force = false, zone = utc)
        assertEquals(0 until 10, range)
    }

    @Test
    fun aPromptIsNeverSeparatedFromItsReply() {
        // m0 user, m1 bot, m2 bot (two replies), m3 user ... batch 2 must not cut at m2.
        val messages = listOf(
            SourceMessage("m0", false, "a", at(1, 9)),
            SourceMessage("m1", true, "b", at(1, 9)),
            SourceMessage("m2", true, "c", at(1, 9)),
            SourceMessage("m3", false, "d", at(1, 10)),
            SourceMessage("m4", true, "e", at(1, 10)),
            SourceMessage("m5", false, "f", at(1, 11))
        )
        val range = SummarySections.nextRange(messages, 0, windowEdge = 5, batchSize = 2, force = false, zone = utc)
        assertEquals(0 until 3, range)
    }

    @Test
    fun anOlderDateIsFinishedAsItsOwnShortSection() {
        // Three messages on Oct 1, then the user returns on Oct 3.
        val messages = listOf(
            SourceMessage("m0", false, "a", at(1, 9)),
            SourceMessage("m1", true, "b", at(1, 9)),
            SourceMessage("m2", false, "c", at(1, 10)),
            SourceMessage("m3", true, "d", at(1, 10)),
            SourceMessage("m4", false, "e", at(3, 8)),
            SourceMessage("m5", true, "f", at(3, 8)),
            SourceMessage("m6", false, "g", at(3, 9))
        )
        val range = SummarySections.nextRange(messages, 0, windowEdge = 6, batchSize = 10, force = false, zone = utc)
        assertEquals(0 until 4, range)
    }

    @Test
    fun messagesInsideTheWindowWaitAndForceTakesAShortFinalRun() {
        val messages = chat(14)
        assertNull(SummarySections.nextRange(messages, 0, windowEdge = 6, batchSize = 10, force = false, zone = utc))
        assertEquals(0 until 6, SummarySections.nextRange(messages, 0, windowEdge = 6, batchSize = 10, force = true, zone = utc))
        // A forced run never ends mid-turn: edge 7 is a reply, so it stops at the prompt before it.
        assertEquals(0 until 6, SummarySections.nextRange(messages, 0, windowEdge = 7, batchSize = 10, force = true, zone = utc))
    }

    @Test
    fun contextIsTheExchangeJustBeforeTheSection() {
        val messages = chat(12)
        assertEquals(listOf("m8", "m9"), SummarySections.contextBefore(messages, 10).map { it.id })
        assertTrue(SummarySections.contextBefore(messages, 0).isEmpty())
    }

    @Test
    fun deletingAMessageShrinksOnlyItsSectionAndKeepsOthersIntact() {
        val messages = chat(20)
        val first = SummarySections.newSection(messages.subList(0, 10), emptyList(), "first")
        val second = SummarySections.newSection(messages.subList(10, 20), messages.subList(8, 10), "second")
        val afterDelete = messages.filterNot { it.id == "m3" }
        val result = SummarySections.reconcile(listOf(first, second), afterDelete)
        assertTrue(result.changed)
        assertEquals(9, result.sections[0].messageIds.size)
        assertTrue(result.sections[0].awaitsRegeneration)
        assertFalse(result.sections[1].needsUpdate)
        assertEquals(19, SummarySections.coveredCount(result.sections, afterDelete))
    }

    @Test
    fun editingAContextMessageMarksOnlyTheDependentNeighbor() {
        val messages = chat(20)
        val first = SummarySections.newSection(messages.subList(0, 10), emptyList(), "first")
        val second = SummarySections.newSection(messages.subList(10, 20), messages.subList(8, 10), "second")
        val edited = messages.map { if (it.id == "m9") it.copy(text = "changed") else it }
        val result = SummarySections.reconcile(listOf(first, second), edited)
        assertTrue(result.sections[0].needsUpdate)
        assertTrue(result.sections[1].needsUpdate)
    }

    @Test
    fun aUserEditedOrLegacySectionIsMarkedButNeverQueuedForRewriting() {
        val messages = chat(10)
        val edited = SummarySections.newSection(messages, emptyList(), "mine").copy(edited = true)
        val changed = messages.map { if (it.id == "m4") it.copy(text = "new") else it }
        val result = SummarySections.reconcile(listOf(edited), changed).sections.single()
        assertTrue(result.needsUpdate)
        assertFalse(result.awaitsRegeneration)
        assertEquals("mine", result.text)
    }

    @Test
    fun aSectionWithNoMessagesLeftIsRemoved() {
        val messages = chat(4)
        val section = SummarySections.newSection(messages.subList(0, 2), emptyList(), "gone")
        val result = SummarySections.reconcile(listOf(section), messages.drop(2))
        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun aResultIsSavedOnlyWhenItsSourceIsUnchanged() {
        val messages = chat(6)
        val owned = messages.subList(2, 6)
        val context = messages.subList(0, 2)
        assertTrue(SummarySections.sourceStillCurrent(owned, context, messages))
        assertFalse(SummarySections.sourceStillCurrent(owned, context, messages.filterNot { it.id == "m1" }))
        assertFalse(
            SummarySections.sourceStillCurrent(owned, context, messages.map { if (it.id == "m3") it.copy(text = "x") else it })
        )
    }

    @Test
    fun anOlderSingleSummaryBecomesOneLegacyBlock() {
        val messages = chat(30)
        val legacy = SummarySections.legacyBlock("old summary", 12, messages)!!
        assertTrue(legacy.legacy)
        assertEquals(12, legacy.messageIds.size)
        assertNull(SummarySections.legacyBlock("", 12, messages))
    }

    @Test
    fun theChatAiReceivesTheDirectionThenEverySectionInOrderWithoutDates() {
        val messages = chat(4)
        val a = SummarySections.newSection(messages.subList(0, 2), emptyList(), "First part.")
        val b = SummarySections.newSection(messages.subList(2, 4), emptyList(), "Second part.")
        assertEquals("HEADER\n\nFirst part.\n\nSecond part.", SummarySections.injection("HEADER", listOf(a, b)))
        assertNull(SummarySections.injection("HEADER", emptyList()))
    }

    @Test
    fun sectionsSurviveStorage() {
        val messages = chat(4)
        val section = SummarySections.newSection(messages.subList(2, 4), messages.subList(0, 2), "Text ✦")
            .copy(edited = true, needsUpdate = true)
        assertEquals(listOf(section), SummarySections.fromJson(SummarySections.toJson(listOf(section))))
    }

    @Test
    fun theHeaderUsesTheFirstAndLastPromptTimes() {
        val messages = listOf(
            SourceMessage("m0", false, "a", at(1, 13, 0)),
            SourceMessage("m1", true, "b", at(1, 13, 1)),
            SourceMessage("m2", false, "c", at(1, 15, 15)),
            SourceMessage("m3", true, "d", at(1, 16, 0))
        )
        val section = SummarySections.newSection(messages, emptyList(), "x")
        val (start, end) = SummarySections.timeSpan(section, messages)!!
        assertEquals("October 1, 2026 · 1:00 PM–3:15 PM", SummarySectionTime.format(start, end, utc, Locale.US))
        assertEquals("October 3, 2026 · 10:42 AM", SummarySectionTime.format(at(3, 10, 42), at(3, 10, 42), utc, Locale.US))
    }

    @Test
    fun aKeptSectionIsMarkedButNotRewrittenAndAnEditIsDetectedOnce() {
        val messages = chat(10)
        val section = SummarySections.newSection(messages, emptyList(), "summary")
        val edited = messages.map { if (it.id == "m2") it.copy(text = "changed") else it }
        val reconciled = SummarySections.reconcile(listOf(section), edited).sections
        assertEquals(listOf(section.id), SummarySections.newlyAffected(listOf(section), reconciled).map { it.id })
        val kept = reconciled.single().copy(kept = true)
        assertFalse(kept.awaitsRegeneration)
        assertTrue(SummarySections.newlyAffected(listOf(kept), SummarySections.reconcile(listOf(kept), edited).sections).isEmpty())
        assertEquals(listOf(kept), SummarySections.fromJson(SummarySections.toJson(listOf(kept))))
    }
}
