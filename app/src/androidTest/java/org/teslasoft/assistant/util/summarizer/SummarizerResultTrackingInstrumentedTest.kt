/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.util.summarizer.SummarizerController.OperationState

/**
 * The real Summarizer controller and chat storage, with each section's AI
 * request answered in place (Owner, 2026-11-25): complete success, nothing
 * saved, a partial result and an actual failure end differently; every ending
 * removes the interruption record; and a record left by an earlier session is
 * reported once.
 */
@RunWith(AndroidJUnit4::class)
class SummarizerResultTrackingInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext.applicationContext
    private val chatId = "summarizer-result-test-${System.nanoTime()}"
    private val records = app.getSharedPreferences("summarizer_run_records", Context.MODE_PRIVATE)
    private lateinit var controller: SummarizerController
    private val states = java.util.Collections.synchronizedList(ArrayList<OperationState>())

    @Volatile private var entries: List<SummarizerController.Entry> = (0 until 21).map {
        SummarizerController.Entry(isBot = it % 2 == 1, text = "message $it", id = "id-$it", timeMillis = DAY)
    }

    @Before fun setUp() {
        Preferences.getPreferences(app, chatId).setChatUseSummarizer(true)
        controller = SummarizerController(app) { chatId }
        controller.listener = object : SummarizerController.Listener {
            override fun onSummarizerStateChanged() {}
            override fun onSummarizerErrorEpisode() {}
            override fun onSummarizerOperationChanged(state: OperationState) { states += state }
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { controller.cancel() }
        records.edit().clear().commit()
    }

    private fun edit(index: Int) {
        entries = entries.mapIndexed { i, e -> if (i == index) e.copy(text = e.text + " (edited)") else e }
    }

    private fun recordStarted(): Int? = records.getString("$chatId|SUMMARIZING", null)
        ?.let { JSONObject(it).optInt("started") }

    /** Runs one automatic summary to its end; [answer] is called with the request number. */
    private fun run(storageUnavailable: () -> Boolean = { false }, answer: (Int) -> String?): OperationState {
        var request = 0
        controller.sectionRequestForTest = {
            request++
            // While a section is requested, its record exists and counts it.
            assertEquals(request, recordStarted())
            answer(request)
        }
        instrumentation.runOnMainSync {
            controller.runCycle(true, "Result test", {
                if (storageUnavailable()) null else SummarizerController.Snapshot(entries, 1)
            }, storageUnavailable = storageUnavailable)
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val last = states.lastOrNull()
            if (last != null && last !is OperationState.Running && last != OperationState.Idle) return last
            Thread.sleep(20)
        }
        throw AssertionError("the summary run did not end: $states")
    }

    private fun savedSections(): Int = SummarySections.fromJson(Preferences.getPreferences(app, chatId).getSummarySections()) {}.size

    @Test fun everySectionSavedIsSuccessAndLeavesNoRecord() {
        val end = run { "summary $it" }
        assertTrue("$end", end is OperationState.Succeeded)
        assertEquals(2, savedSections())
        assertEquals(null, recordStarted())
    }

    @Test fun aSectionDiscardedBecauseTheConversationChangedWithNothingSavedIsNeitherSuccessNorError() {
        val end = run { edit(3); "summary $it" }
        assertTrue("$end", end is OperationState.Cancelled)
        assertEquals(0, savedSections())
        assertTrue(SummarizerErrorLog.fromJson(Preferences.getPreferences(app, chatId).getSummarizerErrors()).isEmpty())
        assertEquals(null, recordStarted())
    }

    @Test fun someSavedAndOneDiscardedIsPartiallyUpdatedAndKeepsTheSavedSections() {
        val end = run { if (it == 2) edit(15); "summary $it" }
        assertTrue("$end", end is OperationState.PartiallyUpdated)
        assertEquals(1, savedSections())
        assertTrue(SummarizerErrorLog.fromJson(Preferences.getPreferences(app, chatId).getSummarizerErrors()).isEmpty())
        assertEquals(null, recordStarted())
    }

    @Test fun anUnreadableChatIsAnActualFailureRecordedInSummarizerErrors() {
        var unavailable = false
        val end = run(storageUnavailable = { unavailable }) { unavailable = true; "summary $it" }
        assertTrue("$end", end is OperationState.Failed)
        assertEquals(SummarizerErrorCategory.SAVE_FAILED, (end as OperationState.Failed).category)
        val errors = SummarizerErrorLog.fromJson(Preferences.getPreferences(app, chatId).getSummarizerErrors())
        assertTrue(errors.single().detail.orEmpty().contains("could not be read when the summary returned"))
        assertEquals(0, savedSections())
        assertEquals(null, recordStarted())
    }

    @Test fun aRecordLeftByAnEarlierSessionIsReportedOnceInTheErrorLog() {
        val gone = "summarizer-interrupted-${System.nanoTime()}"
        records.edit().putString(
            "$gone|COMPACTING",
            JSONObject().put("token", "t").put("session", "an earlier session").put("started", 0).toString()
        ).commit()
        val line = SummarizerInterruptionReport.errorLogLine(
            SummarizerRunRecords.Interrupted(gone, SummarizerController.OperationKind.COMPACTING)
        )
        SummarizerInterruptionReport.reportAtStartup(app)
        assertEquals(1, Logger.getCrashLog(app).split(line).size - 1)
        SummarizerInterruptionReport.reportAtStartup(app)
        assertEquals("a repeated start reports nothing again", 1, Logger.getCrashLog(app).split(line).size - 1)
        assertFalse(records.contains("$gone|COMPACTING"))
    }

    private companion object {
        const val DAY = 1_764_000_000_000L
    }
}
