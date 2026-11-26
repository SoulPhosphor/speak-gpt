/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.conversationindex.ConversationIndexNotificationLine

/**
 * The corrections to after-close indexing (Owner, 2026-11-25), as plain
 * functions: which waiting work the service continues, notifications turned
 * off, the update line, and the approved diagnostics wording, which never
 * calls an Android limit an error and never names a cause it does not know.
 */
class IndexingDiagnosticsTest {

    private val on = IndexingConditions(settingOn = true, rebuilding = false, recoveryFailed = false, modelUnavailable = false)

    private fun decide(
        pending: Boolean = false,
        display: IndexingDisplay = IndexingDisplay.Hidden,
        conditions: IndexingConditions = on,
        paused: Boolean = false,
        userWork: Boolean = false,
        stalled: Boolean = false,
        notifications: Boolean = true
    ) = BackgroundIndexingRule.startDecision(display, conditions, paused, userWork, stalled, pending, notifications)

    @Test fun everyLegitimateWaitingWorkStartsTheService() {
        assertEquals(BackgroundStartDecision.START, decide(pending = true))
        assertEquals(BackgroundStartDecision.START, decide(display = IndexingDisplay.Running(null)))
        assertEquals("a waiting recovery shows as rebuilding", BackgroundStartDecision.START,
            decide(conditions = on.copy(rebuilding = true)))
        assertEquals("nothing waiting starts nothing", BackgroundStartDecision.NOT_NEEDED, decide())
    }

    @Test fun waitingWorkIsNotContinuedWhenPausedOffWithoutTheModelStalledOrWithoutNotifications() {
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(pending = true, paused = true))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(pending = true, conditions = on.copy(settingOn = false)))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(pending = true, conditions = on.copy(modelUnavailable = true)))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(pending = true, conditions = on.copy(recoveryFailed = true)))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(pending = true, stalled = true))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(pending = true, notifications = false))
        assertEquals("work the user started still comes first", BackgroundStartDecision.YIELD,
            decide(pending = true, userWork = true))
    }

    @Test fun theReasonWaitingWorkWasNotContinuedIsNamed() {
        fun reason(paused: Boolean = false, conditions: IndexingConditions = on, userWork: Boolean = false,
                   stalled: Boolean = false, notifications: Boolean = true, pending: Boolean = true) =
            BackgroundIndexingRule.notContinuedReason(IndexingDisplay.Hidden, conditions, paused, userWork, stalled, pending, notifications)
        assertEquals("paused", reason(paused = true))
        assertEquals("turned off", reason(conditions = on.copy(settingOn = false)))
        assertEquals("model unavailable", reason(conditions = on.copy(modelUnavailable = true)))
        assertEquals("stopped after an error", reason(stalled = true))
        assertEquals("work you started is running", reason(userWork = true))
        assertEquals("notifications are turned off", reason(notifications = false))
        assertNull("nothing waited: nothing to say", reason(pending = false, notifications = false))
        assertNull("continued: nothing to say", reason())
    }

    @Test fun anOrdinaryUpdateShowsTheUpdateLineAndEndsWhenNothingWaits() {
        fun next(pending: Boolean, hadCatchUp: Boolean = false, notifications: Boolean = true,
                 display: IndexingDisplay = IndexingDisplay.Hidden) =
            BackgroundIndexingRule.next(display, on, paused = false, appVisible = false,
                pendingWork = pending, notificationsAllowed = notifications, hadCatchUp = hadCatchUp)
        assertEquals(BackgroundIndexingStep.Show(BackgroundIndexingContent.Updating), next(pending = true))
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.UPDATES_DONE), next(pending = false))
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED), next(pending = false, hadCatchUp = true))
        assertEquals("a catch-up keeps its own percentage", BackgroundIndexingStep.Show(BackgroundIndexingContent.Percent(42)),
            next(pending = true, display = IndexingDisplay.Running(IndexingNumbers(42, 3, 9))))
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.NOTIFICATIONS_OFF), next(pending = true, notifications = false))
        // Android's usual timing for an ordinary update; everything else shows at once.
        assertEquals(androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_DEFAULT,
            BackgroundIndexingRule.foregroundBehavior(BackgroundIndexingContent.Updating))
        for (content in listOf(BackgroundIndexingContent.Counting, BackgroundIndexingContent.Rebuilding,
            BackgroundIndexingContent.Percent(3), null)) {
            assertEquals(androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE, BackgroundIndexingRule.foregroundBehavior(content))
        }
        assertFalse(BackgroundStopReason.NOTIFICATIONS_OFF.isAndroidLimit)
    }

    @Test fun notificationsOffAreToldApartAndExplainedWithTheApprovedLines() {
        assertEquals(IndexingNotificationAccess.APP_OFF, IndexingNotificationAccess.of(false, false))
        assertEquals(IndexingNotificationAccess.APP_OFF, IndexingNotificationAccess.of(false, true))
        assertEquals(IndexingNotificationAccess.CHANNEL_OFF, IndexingNotificationAccess.of(true, true))
        assertEquals(IndexingNotificationAccess.ALLOWED, IndexingNotificationAccess.of(true, false))
        assertEquals(R.string.conversation_index_notifications_app_off, ConversationIndexNotificationLine.textRes(IndexingNotificationAccess.APP_OFF))
        assertEquals(R.string.conversation_index_notifications_channel_off, ConversationIndexNotificationLine.textRes(IndexingNotificationAccess.CHANNEL_OFF))
        assertNull(ConversationIndexNotificationLine.textRes(IndexingNotificationAccess.ALLOWED))
        val strings = strings()
        assertTrue(strings.contains("<string name=\"conversation_index_notifications_app_off\">Indexing continues only while the app is open because the app\\'s notifications are turned off. Allow notifications for the app in Android settings to let indexing continue after the app is closed.</string>"))
        assertTrue(strings.contains("<string name=\"conversation_index_notifications_channel_off\">Indexing continues only while the app is open because Indexing Conversations notifications are turned off. Turn them on in Android settings to let indexing continue after the app is closed.</string>"))
        assertTrue(strings.contains("<string name=\"conversation_indexing_notification_updating\">Updating conversation index…</string>"))
    }

    @Test fun theLoggingControlsUseTheApprovedWordingAndDefaults() {
        val strings = strings()
        assertTrue(strings.contains("<string name=\"indexing_error_details_title\">Indexing Error Details</string>"))
        assertTrue(strings.contains("Failures are always recorded.</string>"))
        assertTrue(strings.contains("<string name=\"indexing_activity_title\">Indexing Activity</string>"))
        assertTrue(strings.contains("<string name=\"indexing_detailed_title\">Detailed Indexing Diagnostics</string>"))
        assertTrue(strings.contains("<string name=\"title_indexing_log\">Indexing Log</string>"))
        assertTrue(IndexingLogSettings.ERROR_DETAILS_DEFAULT)
        assertFalse(IndexingLogSettings.ACTIVITY_DEFAULT)
        assertFalse(IndexingLogSettings.DETAILED_DEFAULT)
        // Placement: at the very bottom of Logging Preferences, immediately above Audio Debugging; the
        // Indexing Log directly below Image Generation Log in the Log Cabin.
        val prefs = resource("layout/activity_alert_debug_menu.xml")
        assertTrue(prefs.indexOf("field_indexing_log_max_days") in prefs.indexOf("switch_system_logging")..prefs.indexOf("row_audio_debugging"))
        val cabin = resource("layout/activity_log_cabin.xml")
        val image = cabin.indexOf("row_image_gen_log")
        val indexing = cabin.indexOf("row_indexing_log")
        assertTrue(image in 0 until indexing)
        assertFalse("directly below: no row between them", cabin.substring(image + "row_image_gen_log".length, indexing).contains("@+id/row_"))
    }

    @Test fun androidLimitsSayTheyAreNotErrorsAndUnknownCausesAreNeverGuessed() {
        val paused = IndexingLogText.pausedByAndroidLimit(BackgroundStopReason.TIME_LIMIT, null)
        assertEquals(
            "Conversation indexing paused by an Android limit: Android's time limit for background data work was reached. " +
                "This is an Android limit, not an app error. Unfinished work is waiting and continues when the app is opened. " +
                "Completed progress retained.",
            paused
        )
        assertTrue(IndexingLogText.pausedByAndroidLimit(BackgroundStopReason.BACKGROUND_START_NOT_ALLOWED, "X: y").contains("from the background: X: y."))
        assertEquals("Conversation indexing background service stopped by an Android limit: " +
            "Android's time limit for background data work was reached. No work was interrupted.",
            IndexingLogText.stoppedByAndroidLimitNoInterruption(BackgroundStopReason.TIME_LIMIT))
        assertEquals("Previous indexing session ended without recording a normal stop. Cause unknown. Completed progress retained.",
            IndexingLogText.PREVIOUS_SESSION_ENDED)
        assertFalse(IndexingLogText.PREVIOUS_SESSION_ENDED.contains("Android"))
        for (reason in BackgroundStopReason.entries) assertFalse(reason.text, reason.text.contains("damage", true))
    }

    @Test fun errorDetailsCarryTheCausesAndStateButNoStackFrames() {
        val chain = IndexingLogText.causeChain(IllegalStateException("outer", java.io.IOException("disk full")))
        assertEquals("java.lang.IllegalStateException: outer <- java.io.IOException: disk full", chain)
        assertNull(IndexingLogText.causeChain(null))
        assertEquals(" Chain of causes: a. Indexing state: s.", IndexingLogText.details("a", "s"))
        assertEquals("", IndexingLogText.details(null, null))
        assertEquals(
            "Conversation indexing could not prepare the on-device search model or the index: X: y. " +
                "Effect: waiting search vectors were not made. Retry: r.",
            IndexingLogText.vectorPreparationFailed("X: y", "r")
        )
    }

    @Test fun summariesAreAggregatedAndOnlyWrittenWhenSomethingRan() {
        var now = 0L
        val totals = IndexingTotals { now }
        assertNull("nothing ran: no summary", totals.take())
        totals.chatCompared(4)
        totals.chatCompared(2)
        totals.vectorPass(5, 1)
        now = 75_000
        val taken = totals.take()!!
        assertEquals(IndexingTotals.Snapshot(2, 6, 5, 1, 1, 75_000), taken)
        assertNull("taking starts over", totals.take())
        assertEquals(
            "Conversation indexing summary (catch-up): 2 chats compared, 6 messages written, 5 vectors made, " +
                "1 failed, 3 waiting, 1 vector passes, 1 min 15 s.",
            IndexingLogText.summary(IndexingLogText.SummaryScope.CATCH_UP, taken, 3)
        )
        assertTrue(IndexingLogText.summary(IndexingLogText.SummaryScope.SERVICE_RUN, taken, null).contains("not known waiting"))
        assertEquals("Leaving the app: started the background service. State: ordinary update; paused no; work you started running no.",
            IndexingLogText.leaveDecision(true, IndexingLogText.State.UPDATE, paused = false, userWork = false))
    }

    private fun resource(path: String): String =
        listOf(File("src/main/res/$path"), File("app/src/main/res/$path")).first { it.exists() }.readText()

    private fun strings() = resource("values/strings.xml")
}
