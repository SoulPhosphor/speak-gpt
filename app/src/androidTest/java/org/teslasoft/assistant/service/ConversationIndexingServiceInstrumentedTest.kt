package org.teslasoft.assistant.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.BackgroundIndexingContent
import org.teslasoft.assistant.conversation.index.BackgroundIndexingStep
import org.teslasoft.assistant.conversation.index.BackgroundStopReason
import org.teslasoft.assistant.conversation.index.BackgroundIndexingLog
import org.teslasoft.assistant.conversation.index.ConversationIndexRuntime
import org.teslasoft.assistant.conversation.index.IndexingLogSettings
import org.teslasoft.assistant.conversation.index.IndexingLogText
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.conversation.index.IndexingConditions
import org.teslasoft.assistant.conversation.index.IndexingDisplay
import org.teslasoft.assistant.conversation.index.IndexingNumbers
import org.teslasoft.assistant.ui.activities.MemoryControlsActivity

/**
 * Step 13 on a device: the real data-sync service and its notification,
 * Android's own time limit (shortened to seconds through Android's test
 * setting, never waited out for six hours), the start Android refuses once
 * the time is used up, and opening the app. What the service shows is set
 * through its test step; the choice of that step from the real progress is
 * covered by `BackgroundIndexingTest`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIndexingServiceInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext.applicationContext
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val controller get() = AppBackgroundIndexing.controller(app)

    @Volatile private var step: BackgroundIndexingStep =
        BackgroundIndexingStep.Show(BackgroundIndexingContent.Percent(42))

    @Before fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        }
        ConversationIndexingForegroundService.updateMillis = 300L
        ConversationIndexingForegroundService.stepForTest = { step }
        // What the leave decision reads: a running catch-up with Index Conversations on.
        AppBackgroundIndexing.displayForTest = IndexingDisplay.Running(IndexingNumbers(42, 3, 9))
        AppBackgroundIndexing.conditionsForTest =
            IndexingConditions(settingOn = true, rebuilding = false, recoveryFailed = false, modelUnavailable = false)
        notifications.cancelAll()
    }

    @After fun tearDown() {
        // The app's own progress decides again before the app is opened and left below.
        AppBackgroundIndexing.displayForTest = null
        AppBackgroundIndexing.conditionsForTest = null
        ConversationIndexingForegroundService.stepForTest = { BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED) }
        onMain { ConversationIndexingForegroundService.requestStop(BackgroundStopReason.CATCH_UP_ENDED) }
        waitFor("the service stops") { !onMain { ConversationIndexingForegroundService.isRunning } }
        shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
        // Opening the app resets Android's time for data-sync services and releases any hold.
        ActivityScenario.launch(MemoryControlsActivity::class.java).close()
        ConversationIndexingForegroundService.stepForTest = null
        ConversationIndexingForegroundService.updateMillis = 10_000L
        notifications.cancelAll()
    }

    @Test fun theNotificationShowsTheRealContentForEachStateAndGoesWhenTheCatchUpEnds() {
        startWhileVisibleThenLeave()

        val percent = waitForIndexing { text(it) == app.getString(R.string.conversation_indexing_notification_percent, 42) }
        assertEquals("Indexing Conversations", percent.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("42% done. Tap to open.", text(percent))
        assertEquals(42, percent.notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, percent.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(percent.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertNotNull("tapping opens the app", percent.notification.contentIntent)
        assertTrue("no buttons", percent.notification.actions.isNullOrEmpty())
        assertTrue(percent.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)

        step = BackgroundIndexingStep.Show(BackgroundIndexingContent.Counting)
        val counting = waitForIndexing { text(it) == "Counting messages to index…" }
        assertTrue("a moving bar", counting.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertFalse("no percentage while counting", text(counting).contains("%"))

        step = BackgroundIndexingStep.Show(BackgroundIndexingContent.Rebuilding)
        val rebuilding = waitForIndexing { text(it) == "Rebuilding conversation index…" }
        assertTrue(rebuilding.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))

        step = BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED)
        waitFor("the service stops when the catch-up ends") { !onMain { ConversationIndexingForegroundService.isRunning } }
        waitFor("its notification goes") { find(INDEXING_ID) == null }
        assertEquals(BackgroundStopReason.CATCH_UP_ENDED, controller.lastStop)
        assertNull("no paused notice for a finished catch-up", find(PAUSED_ID))
    }

    @Test fun androidsRealTimeLimitStopsTheServiceInTimeHoldsIndexingAndARestartIsRefused() {
        shell("device_config put activity_manager data_sync_fgs_timeout_duration 4000")
        startWhileVisibleThenLeave()
        waitForIndexing { text(it) == "42% done. Tap to open." }

        // Android calls onTimeout once its (shortened) time is used up; the service must stop within seconds.
        waitFor("Android's time limit stops the service", 60_000) {
            !onMain { ConversationIndexingForegroundService.isRunning }
        }
        assertEquals(BackgroundStopReason.TIME_LIMIT, controller.lastStop)
        assertFalse("indexing waits for the app", ConversationIndexRuntime.backgroundPermit.mayRun())
        waitFor("the progress notification goes") { find(INDEXING_ID) == null }
        val paused = waitForValue("the approved notice is shown") { find(PAUSED_ID) }
        assertEquals("Indexing Conversations", paused.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Background indexing is paused. Open the app to continue. Finished work was saved.",
            paused.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
        assertTrue("no buttons", paused.notification.actions.isNullOrEmpty())
        assertNotNull(paused.notification.contentIntent)
        // Owner, 2026-11-25: the limit left work waiting, so the Error Log explains it as a limit, not an error.
        assertTrue(Logger.getCrashLog(app).contains(IndexingLogText.pausedByAndroidLimit(BackgroundStopReason.TIME_LIMIT, null)))

        // With the time used up and the app in the background, Android refuses the next start.
        onMain { controller.startService() }
        waitFor("the refusal is handled") { controller.lastStop != BackgroundStopReason.TIME_LIMIT }
        val refused = controller.lastStop
        assertTrue("refused as an Android limit, not a failure: $refused", refused?.isAndroidLimit == true)
        assertFalse(onMain { ConversationIndexingForegroundService.isRunning })
        assertFalse(controller.serviceActive)

        // Opening the app removes the notice and lets indexing run (nothing to continue when it is left again).
        AppBackgroundIndexing.displayForTest = IndexingDisplay.Hidden
        ActivityScenario.launch(MemoryControlsActivity::class.java).use {
            waitFor("the notice goes when the app opens") { find(PAUSED_ID) == null }
            assertTrue("indexing runs again in the app", ConversationIndexRuntime.backgroundPermit.mayRun())
        }
    }

    @Test fun openingTheAppStopsTheServiceAndRemovesItsNotification() {
        startWhileVisibleThenLeave()
        waitForIndexing { text(it) == "42% done. Tap to open." }
        assertTrue(onMain { ConversationIndexingForegroundService.isRunning })

        AppBackgroundIndexing.displayForTest = IndexingDisplay.Hidden
        ActivityScenario.launch(MemoryControlsActivity::class.java).use {
            waitFor("opening the app stops it") { !onMain { ConversationIndexingForegroundService.isRunning } }
            waitFor("its notification goes") { find(INDEXING_ID) == null }
            assertEquals(BackgroundStopReason.APP_OPENED, controller.lastStop)
            assertTrue("indexing runs again in the app", ConversationIndexRuntime.backgroundPermit.mayRun())
            assertNull(find(PAUSED_ID))
        }
    }

    @Test fun anOrdinaryUpdateShowsTheUpdateLineWithAndroidsUsualTimingAndLogsOnlyToTheAppsLogs() {
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(IndexingLogSettings.ACTIVITY, true).commit()
        Logger.clearIndexingLog(app)
        val errorsBefore = Logger.getCrashLog(app)
        try {
            step = BackgroundIndexingStep.Show(BackgroundIndexingContent.Updating)
            AppBackgroundIndexing.displayForTest = IndexingDisplay.Hidden
            AppBackgroundIndexing.pendingForTest = true
            startWhileVisibleThenLeave()
            // Android may hold the notification back for a short update; here the service keeps running.
            val updating = waitForIndexing { text(it) == "Updating conversation index…" }
            assertEquals("Indexing Conversations", updating.notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue("a moving bar", updating.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertEquals("Android's usual timing, not immediate",
                    Notification.FOREGROUND_SERVICE_DEFAULT, updating.notification.foregroundServiceBehavior)
            }
            step = BackgroundIndexingStep.Stop(BackgroundStopReason.UPDATES_DONE)
            waitFor("the service stops when the updates are done") { !onMain { ConversationIndexingForegroundService.isRunning } }
            val log = Logger.getIndexingLog(app)
            assertTrue(log.contains(BackgroundIndexingLog.STARTED))
            assertTrue(log.contains(BackgroundIndexingLog.stopped(BackgroundStopReason.UPDATES_DONE)))
            assertEquals("a normal stop is not an error", errorsBefore, Logger.getCrashLog(app))
        } finally {
            AppBackgroundIndexing.pendingForTest = null
            prefs.edit().remove(IndexingLogSettings.ACTIVITY).commit()
        }
    }

    @Test fun withIndexingActivityOffNothingRoutineIsWritten() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove(IndexingLogSettings.ACTIVITY).commit()
        Logger.clearIndexingLog(app)
        startWhileVisibleThenLeave()
        step = BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED)
        waitFor("the service stops") { !onMain { ConversationIndexingForegroundService.isRunning } }
        assertEquals("", Logger.getIndexingLog(app))
    }

    @Test fun aRunThatNeverRecordedItsStopIsReportedOnceAtTheNextStart() {
        val marks = app.getSharedPreferences("conversation_indexing_service", Context.MODE_PRIVATE)
        marks.edit().putBoolean("service_run_going", true).commit()
        AppBackgroundIndexing.previousRunEnded(app)
        AppBackgroundIndexing.previousRunEnded(app)
        val log = Logger.getCrashLog(app)
        assertEquals(1, log.split(IndexingLogText.PREVIOUS_SESSION_ENDED).size - 1 - countBefore)
        assertFalse(marks.contains("service_run_going"))
    }

    private var countBefore = 0

    @Before fun countPreviousReports() {
        countBefore = Logger.getCrashLog(app).split(IndexingLogText.PREVIOUS_SESSION_ENDED).size - 1
    }

    // --- Helpers ---------------------------------------------------------------

    /**
     * Opens a screen of the app and leaves it during a catch-up: the app's own
     * leave decision starts the service as the screen stops, as Android allows.
     */
    private fun startWhileVisibleThenLeave() {
        ActivityScenario.launch(MemoryControlsActivity::class.java).close()
        waitFor("leaving starts the service") { onMain { ConversationIndexingForegroundService.isRunning } }
        assertTrue(onMain { controller.serviceActive })
        assertTrue("the service continues indexing", ConversationIndexRuntime.backgroundPermit.mayRun())
    }

    private fun waitForIndexing(condition: (StatusBarNotification) -> Boolean): StatusBarNotification =
        waitForValue("the indexing notification") { find(INDEXING_ID)?.takeIf(condition) }

    private fun find(id: Int): StatusBarNotification? = notifications.activeNotifications.firstOrNull { it.id == id }

    private fun text(n: StatusBarNotification) = n.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        waitForValue(what, timeoutMillis) { if (condition()) Unit else null }
    }

    private fun <T : Any> waitForValue(what: String, timeoutMillis: Long = 15_000, value: () -> T?): T {
        val until = System.currentTimeMillis() + timeoutMillis
        while (true) {
            value()?.let { return it }
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(100)
        }
    }

    private fun shell(command: String) {
        val output: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        FileInputStream(output.fileDescriptor).use { it.readBytes() }
        output.close()
    }

    private companion object {
        const val INDEXING_ID = 9951
        const val PAUSED_ID = 9952
    }
}
