/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retrieval stage 2's plain date rules, the G36 time allowance, and Code-based continuity's per-chat memory. */
class RecallTimeAndContinuityTest {

    private val utc = ZoneOffset.UTC
    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun range(message: String, now: String) = RecallTimeReference.read(message, t(now), utc)
    private fun days(r: RecallTimeReference.Range?) = r?.let { Instant.ofEpochMilli(it.fromMillis).toString().take(10) to Instant.ofEpochMilli(it.toMillis).toString().take(10) }

    @Test fun theTestChatsTimeWindowsAreReadExactly() {
        // conversations.json's time_window for each time question.
        assertEquals("2026-05-11" to "2026-05-17", days(range("What did I change in the garden plan last week?", "2026-05-21T18:00:00Z")))
        assertEquals("2026-03-01" to "2026-03-31", days(range("What did I plant in March?", "2026-05-21T18:00:00Z")))
        assertEquals("2026-03-23" to "2026-03-29", days(range("What did we plan last week?", "2026-04-02T18:00:00Z")))
        assertEquals("2026-01-01" to "2026-01-31", days(range("What did we book back in January?", "2026-04-02T18:00:00Z")))
    }

    @Test fun clearPhrasesBecomeRanges() {
        val now = "2026-05-21T18:00:00Z" // a Thursday
        assertEquals("2026-05-21" to "2026-05-21", days(range("what did I say today", now)))
        assertEquals("2026-05-20" to "2026-05-20", days(range("yesterday's plan", now)))
        assertEquals("2026-05-18" to "2026-05-18", days(range("three days ago", now)))
        assertEquals("2026-05-19" to "2026-05-19", days(range("what did we decide on Tuesday", now)))
        assertEquals("2026-05-18" to "2026-05-21", days(range("earlier this week", now)))
        assertEquals("2026-04-01" to "2026-04-30", days(range("last month", now)))
        assertEquals("2026-05-01" to "2026-05-21", days(range("in May", now)))
        assertEquals("2025-06-01" to "2025-06-30", days(range("in June", now)))
        assertEquals("2026-03-03" to "2026-03-03", days(range("on March 3", now)))
        assertEquals("2025-12-24" to "2025-12-24", days(range("the 24th of December", now)))
        assertEquals("2024-02-29" to "2024-02-29", days(range("on 2024-02-29", now)))
        assertEquals("2026-05-21T23:59:59.999Z", Instant.ofEpochMilli(range("today", now)!!.toMillis).toString())
    }

    @Test fun unclearOrMissingPhrasesGiveNothing() {
        val now = "2026-05-21T18:00:00Z"
        assertNull(range("What did we decide about the beds?", now))
        assertNull(range("I may need more soil", now))
        assertNull(range("forty days ago", now))
        assertNull(range("on 2026-02-30", now))
        assertNull(range("the march of the penguins", now))
    }

    @Test fun theZoneDecidesWhereADayBegins() {
        val r = RecallTimeReference.read("yesterday", t("2026-05-21T02:00:00Z"), java.time.ZoneId.of("America/New_York"))!!
        assertEquals(t("2026-05-19T04:00:00Z"), r.fromMillis)
    }

    @Test fun theBudgetIsTheEarlierOfRecallsOwnAllowanceAndTheSharedDeadline() {
        var now = 0L
        val clock = RecallClock { now }
        val shared = PreSendDeadline(12_000, clock, 0)
        val budget = RecallBudget(1_500, shared, 300, clock)
        assertEquals(1_500, budget.remainingMillis())
        now = 1_250_000_000L
        assertFalse(budget.mayStartOptional())
        assertFalse(budget.expired())
        now = 1_501_000_000L
        assertTrue(budget.expired())

        now = 0L
        val late = PreSendDeadline(5_000, clock, 0)
        now = 4_800_000_000L
        val handsFree = RecallBudget.forSend(handsFree = true, shared = late, clock = clock)
        assertEquals(200, handsFree.remainingMillis())
        assertFalse(handsFree.mayStartOptional())
        assertEquals(PreSendDeadline.HANDS_FREE_MILLIS, PreSendDeadline.start(true, clock).allowanceMillis)
        assertEquals(PreSendDeadline.TYPED_MILLIS, PreSendDeadline.start(false, clock).allowanceMillis)
        assertEquals(750, RecallBudget.forSend(handsFree = true, shared = null, clock = clock).remainingMillis())
        assertEquals(1_500, RecallBudget.forSend(handsFree = false, shared = null, clock = clock).remainingMillis())
    }

    @Test fun continuityKeepsAtMostThreeExchangesPerChatInMemoryAndIsClearedWhenAsked() {
        val c = RecallContinuity()
        c.delivered("chat", listOf("a", "b", "a", "c", "d"))
        assertEquals(listOf("a", "b", "c"), c.carried("chat"))
        c.delivered("chat", emptyList())
        assertEquals(emptyList<String>(), c.carried("chat"))
        c.delivered("chat", listOf("x"))
        c.forget("chat")
        assertEquals(emptyList<String>(), c.carried("chat"))
        c.delivered("one", listOf("x"))
        c.delivered("two", listOf("y"))
        c.clear()
        assertEquals(emptyList<String>(), c.carried("one") + c.carried("two"))
    }

    @Test fun continuityForgetsTheLeastRecentlyUsedChatBeyondItsLimit() {
        val c = RecallContinuity()
        for (i in 0..RecallContinuity.MAX_CHATS) c.delivered("chat$i", listOf("e$i"))
        assertEquals(emptyList<String>(), c.carried("chat0"))
        assertEquals(listOf("e1"), c.carried("chat1"))
        assertEquals(listOf("e${RecallContinuity.MAX_CHATS}"), c.carried("chat${RecallContinuity.MAX_CHATS}"))
    }

    @Test fun aFollowUpsOwnWordsDecideWhetherItNamesSomethingElse() {
        assertEquals(emptySet<String>(), RecallContinuity.subjectTerms("Why did we go with that one over the others?"))
        assertEquals(setOf("joke", "penguin"), RecallContinuity.subjectTerms("Tell me a joke about penguins."))
        assertNull(RecallContinuity.followUpShare(emptySet()) { true })
        assertEquals(0.5, RecallContinuity.followUpShare(setOf("sync", "calendar")) { it == "sync" }!!, 1e-9)
    }
}
