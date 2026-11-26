/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import android.app.Application
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.R
import org.teslasoft.assistant.util.summarizer.SummarizerController.OperationKind

/**
 * Summarizer results and interruption reports (Owner, 2026-11-25): success
 * only when every section the run made was saved; a partial result and a
 * superseded one are told apart from success and from failure; an operation
 * that never ended is reported once, and never when it saved its result,
 * was cancelled or was superseded.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class SummarizerRunRecordsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val stored = LinkedHashMap<String, String>()
    private val store = object : SummarizerRunRecords.Store {
        override fun all(): Map<String, String> = LinkedHashMap(stored)
        override fun put(key: String, value: String) { stored[key] = value }
        override fun remove(key: String) { stored.remove(key) }
    }
    private val marks = HashMap<Pair<String, OperationKind>, String>()
    /** Chats whose settings are locked or unreadable now. */
    private val locked = HashSet<String>()

    @Before fun setUp() { SummarizerRunRecords.storeForTest = store }
    @After fun tearDown() { SummarizerRunRecords.storeForTest = null }

    private fun earlierSession(chatId: String, kind: OperationKind, token: String, started: Int = 0) {
        stored["$chatId|${kind.name}"] = JSONObject().put("token", token).put("session", "an earlier session")
            .put("started", started).toString()
    }

    private fun take(live: Set<String>? = setOf("chat")) =
        SummarizerRunRecords.takeInterrupted(context, { chat, kind -> if (chat in locked) null else marks[chat to kind] ?: "" }, { live })

    /** Takes the interruptions and writes their reports, as the start-up report does. */
    private fun takeAndReport(live: Set<String>? = setOf("chat")) =
        take(live).onEach { SummarizerRunRecords.reported(context, it) }

    // --- Result of an automatic summary run ------------------------------------

    @Test fun successOnlyWhenEverySectionTheRunMadeWasSaved() {
        assertEquals(SummaryCycleResult.End.SUCCEEDED, SummaryCycleResult.endOf(SummaryCycleResult(saved = 3), false))
    }

    @Test fun nothingSavedBecauseTheConversationChangedIsSupersededNotSuccessOrError() {
        assertEquals(SummaryCycleResult.End.SUPERSEDED, SummaryCycleResult.endOf(SummaryCycleResult(discarded = 1), false))
        assertEquals(SummaryCycleResult.End.SUPERSEDED, SummaryCycleResult.endOf(SummaryCycleResult(stoppedEarly = true), false))
        // Stopped because the chat closed after saving some: not claimed as success.
        assertEquals(SummaryCycleResult.End.SUPERSEDED,
            SummaryCycleResult.endOf(SummaryCycleResult(saved = 2, stoppedEarly = true), false))
    }

    @Test fun someSavedAndSomeDiscardedIsPartial() {
        assertEquals(SummaryCycleResult.End.PARTIAL,
            SummaryCycleResult.endOf(SummaryCycleResult(saved = 2, discarded = 1), false))
    }

    @Test fun anActualFailureIsAFailureWhateverWasSavedFirst() {
        assertEquals(SummaryCycleResult.End.FAILED, SummaryCycleResult.endOf(SummaryCycleResult(saved = 2), true))
        assertEquals(SummaryCycleResult.End.FAILED, SummaryCycleResult.endOf(SummaryCycleResult(), true))
    }

    // --- Interruption records ----------------------------------------------------

    @Test fun anOperationThatEndedInAnyWayLeavesNoRecord() {
        for (kind in OperationKind.values()) {
            val token = SummarizerRunRecords.started(context, "chat", kind)
            assertTrue(stored.isNotEmpty())
            // Success, failure, cancellation and a superseded result all end the same way.
            SummarizerRunRecords.ended(context, "chat", kind, token)
            assertTrue(stored.isEmpty())
        }
        assertTrue(take().isEmpty())
    }

    @Test fun aRecordOfThisProcessIsNeverReportedByIt() {
        SummarizerRunRecords.started(context, "chat", OperationKind.COMPACTING)
        assertTrue(take().isEmpty())
        assertEquals(1, stored.size)
    }

    @Test fun anInterruptedCompactionIsReportedOnceAndRepeatedStartsReportNothing() {
        earlierSession("chat", OperationKind.COMPACTING, "t1")
        assertEquals(listOf(SummarizerRunRecords.Interrupted("chat", OperationKind.COMPACTING, token = "t1")), takeAndReport())
        assertTrue(takeAndReport().isEmpty())
        assertTrue(takeAndReport().isEmpty())
    }

    @Test fun aReportThatCouldNotBeWrittenKeepsItsRecordForTheNextStart() {
        earlierSession("chat", OperationKind.COMPACTING, "t1")
        assertEquals(1, take().size)
        // Not reported (its entry could not be saved): the record stays and the next start tries again.
        assertEquals(1, take().size)
        assertEquals(1, takeAndReport().size)
        assertTrue(take().isEmpty())
    }

    @Test fun acknowledgingAnOldInterruptionNeverRemovesANewerRunsRecord() {
        earlierSession("chat", OperationKind.COMPACTING, "old")
        val interrupted = take().single()
        // The same chat and kind start again while the old report is being written.
        val newer = SummarizerRunRecords.started(context, "chat", OperationKind.COMPACTING)
        SummarizerRunRecords.reported(context, interrupted)
        assertEquals("the newer run keeps its record", newer, SummarizerRunRecords.parse(stored["chat|COMPACTING"]!!)?.token)
        assertTrue("and it is this process's, so it is not reported", take().isEmpty())
    }

    @Test fun aChatWhoseSettingsAreLockedDecidesNothingUntilTheyCanBeRead() {
        // The compaction saved its result, but its mark cannot be read now: never reported as unsaved.
        earlierSession("chat", OperationKind.COMPACTING, "t1")
        marks["chat" to OperationKind.COMPACTING] = "t1"
        locked += "chat"
        assertTrue(takeAndReport().isEmpty())
        assertEquals("the record waits", 1, stored.size)
        locked.clear()
        assertTrue("readable again: recognised as saved", takeAndReport().isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test fun aCompactionOrOutlineThatSavedItsResultBeforeTheAppStoppedIsNotReported() {
        earlierSession("chat", OperationKind.COMPACTING, "t1")
        marks["chat" to OperationKind.COMPACTING] = "t1"
        earlierSession("chat", OperationKind.OUTLINING, "t2")
        marks["chat" to OperationKind.OUTLINING] = "t2"
        assertTrue(take().isEmpty())
        assertTrue(stored.isEmpty())
        // A mark left by an earlier, different run does not count as this one's save.
        earlierSession("chat", OperationKind.COMPACTING, "t3")
        assertEquals(1, takeAndReport().size)
    }

    @Test fun aSummaryIsReportedOnlyWhenASectionItStartedWasNeverSaved() {
        earlierSession("chat", OperationKind.SUMMARIZING, "s1", started = 2)
        marks["chat" to OperationKind.SUMMARIZING] = SummarizerRunRecords.summarySaved("s1", 1)
        assertEquals(listOf(SummarizerRunRecords.Interrupted("chat", OperationKind.SUMMARIZING, token = "s1")), takeAndReport())

        // Every section it started was saved; it stopped before its clean-up.
        earlierSession("chat", OperationKind.SUMMARIZING, "s2", started = 2)
        marks["chat" to OperationKind.SUMMARIZING] = SummarizerRunRecords.summarySaved("s2", 2)
        assertTrue(take().isEmpty())

        // Started but never reached its first request: nothing was in progress.
        earlierSession("chat", OperationKind.SUMMARIZING, "s3", started = 0)
        assertTrue(take().isEmpty())
    }

    @Test fun sectionStartsAreCountedOnlyForTheRunningSummary() {
        val token = SummarizerRunRecords.started(context, "chat", OperationKind.SUMMARIZING)
        SummarizerRunRecords.sectionStarted(context, "chat", token)
        SummarizerRunRecords.sectionStarted(context, "chat", token)
        SummarizerRunRecords.sectionStarted(context, "chat", "another run")
        assertEquals(2, SummarizerRunRecords.parse(stored.values.single())?.started)
    }

    @Test fun whenTheChatListCannotBeReadNothingIsDecidedAndTheRecordsWait() {
        earlierSession("chat", OperationKind.COMPACTING, "t1")
        assertTrue(take(live = null).isEmpty())
        assertEquals(1, stored.size)
        assertEquals(1, takeAndReport().size)
    }

    @Test fun aRenameMovesTheRecordSoItsEndStillRemovesIt() {
        val token = SummarizerRunRecords.started(context, "old", OperationKind.COMPACTING)
        SummarizerRunRecords.chatRenamed(context, "old", "new")
        assertNull(stored["old|COMPACTING"])
        SummarizerRunRecords.ended(context, "new", OperationKind.COMPACTING, token)
        assertTrue(stored.isEmpty())
    }

    @Test fun aDeletedChatsInterruptionIsStillReported() {
        earlierSession("gone", OperationKind.OUTLINING, "t1")
        assertEquals(listOf(SummarizerRunRecords.Interrupted("gone", OperationKind.OUTLINING, token = "t1")), takeAndReport(live = setOf("chat")))
    }

    // --- What is written -----------------------------------------------------------

    @Test fun theReportNamesWhatWasInterruptedAndNeverACause() {
        val compaction = SummarizerInterruptionReport.errorLogLine(SummarizerRunRecords.Interrupted("c1", OperationKind.COMPACTING))
        assertEquals(
            "Summarizer Service: compaction for chat c1 did not finish. The previous app session ended while it was running. " +
                "Cause unknown. Nothing from it was saved.",
            compaction
        )
        assertTrue(SummarizerInterruptionReport.errorLogLine(SummarizerRunRecords.Interrupted("c1", OperationKind.SUMMARIZING))
            .endsWith("Finished sections were kept; the unfinished section was not saved."))
        assertTrue(SummarizerInterruptionReport.errorLogLine(SummarizerRunRecords.Interrupted("c1", OperationKind.OUTLINING))
            .contains("chat outline for chat c1"))
    }

    @Test fun eachInterruptionIsItsOwnSummarizerErrorWithTheApprovedMessage() {
        val first = SummarizerInterruptionReport.withInterruption(emptyList(), OperationKind.COMPACTING, 1L, "model")
        val second = SummarizerInterruptionReport.withInterruption(first, OperationKind.OUTLINING, 2L, "model")
        assertEquals(2, second.size)
        assertEquals(SummarizerErrorCategory.INTERRUPTED.name, second[0].category)
        assertEquals(R.string.summarizer_err_interrupted_outline, SummarizerErrorMessages.messageRes(second[0]))
        assertEquals(R.string.summarizer_err_interrupted_compaction, SummarizerErrorMessages.messageRes(second[1]))
        assertEquals(R.string.summarizer_err_interrupted_title, SummarizerErrorMessages.titleRes(SummarizerErrorCategory.INTERRUPTED))
        // The owner's exact wording.
        val strings = listOf(java.io.File("src/main/res/values/strings.xml"), java.io.File("app/src/main/res/values/strings.xml"))
            .first { it.exists() }.readText()
        assertTrue(strings.contains("<string name=\"summarizer_err_interrupted_compaction\">The app stopped before compaction finished. " +
            "Nothing from the interrupted compaction was saved. Run Compact again to complete it.</string>"))
        assertTrue(strings.contains("<string name=\"summarizer_err_interrupted_title\">Summarizer Interrupted</string>"))
        // An older entry without an operation keeps its category's message.
        val legacy = SummarizerErrorEntry(SummarizerErrorCategory.SAVE_FAILED.name, 1L, 1, "p", "m", null)
        assertEquals(R.string.summarizer_err_save, SummarizerErrorMessages.messageRes(legacy))
        assertFalse(second[0].operation.isNullOrBlank())
    }
}
