package org.teslasoft.assistant.service

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.ui.activities.MemoryControlsActivity
import org.teslasoft.assistant.util.summarizer.SummarizerController

/**
 * The keep-alive services under Android's real background time limit for
 * data-sync services, shortened to seconds through Android's test setting
 * (never waited out for six hours). Each must stop in time, so Android does
 * not crash the app, log the timeout once as a timeout, and cancel nothing.
 * The wording of every line is covered by `DataSyncTimeoutLogTest`.
 */
@RunWith(AndroidJUnit4::class)
class DataSyncTimeoutInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext.applicationContext

    @Before fun setUp() {
        // Opening the app resets Android's time for data-sync services.
        ActivityScenario.launch(MemoryControlsActivity::class.java).close()
    }

    @After fun tearDown() {
        shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
        instrumentation.runOnMainSync {
            SummarizerForegroundService.end(app, CHAT_ID)
            ImageGenerationForegroundService.end(app)
        }
        ActivityScenario.launch(MemoryControlsActivity::class.java).close()
    }

    @Test fun theSummarizerKeepAliveStopsInTimeLogsOnceAndCancelsNothing() {
        val before = Logger.getCrashLog(app)
        // Started while a screen is visible, as the app does; then the app is left.
        ActivityScenario.launch(MemoryControlsActivity::class.java).use {
            instrumentation.runOnMainSync {
                SummarizerForegroundService.begin(app, CHAT_ID, "Timeout test", SummarizerController.OperationKind.COMPACTING)
            }
            waitFor("the Summarizer service runs") { running(SummarizerForegroundService::class.java) }
        }
        shell("device_config put activity_manager data_sync_fgs_timeout_duration 4000")

        waitFor("Android's time limit stops it", 60_000) { !running(SummarizerForegroundService::class.java) }
        val added = Logger.getCrashLog(app).removePrefix(before)
        assertEquals("logged once", 1, Regex("Summarizer Service: Android's background execution time limit was reached").findAll(added).count())
        assertTrue(added, added.contains("[SummarizerService] [WARNING]"))
        assertTrue(added, added.contains("Operations running: compaction for chat $CHAT_ID. The operation was not explicitly cancelled"))
        assertTrue("no chat name is written", !added.contains("Timeout test"))
    }

    @Test fun theImageGenerationKeepAliveStopsInTimeLogsOnceAndCancelsNothing() {
        val before = Logger.getImageGenErrorsLog(app)
        ActivityScenario.launch(MemoryControlsActivity::class.java).use {
            var started = false
            instrumentation.runOnMainSync { started = ImageGenerationForegroundService.begin(app, CHAT_ID) }
            assertTrue(started)
            waitFor("the image generation service runs") { running(ImageGenerationForegroundService::class.java) }
        }
        shell("device_config put activity_manager data_sync_fgs_timeout_duration 4000")

        waitFor("Android's time limit stops it", 60_000) { !running(ImageGenerationForegroundService::class.java) }
        val added = Logger.getImageGenErrorsLog(app).removePrefix(before)
        assertEquals("logged once", 1, Regex("Image Generation Service: Android's background execution time limit was reached").findAll(added).count())
        assertTrue(added, added.contains("[ImageGenerationService] [WARNING]"))
        assertTrue(added, added.contains("Image generation was not explicitly cancelled"))
    }

    private fun running(service: Class<*>): Boolean {
        val manager = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.packageName == app.packageName && it.service.className == service.name && it.started
        }
    }

    private fun waitFor(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
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
        const val CHAT_ID = "timeout-test-chat"
    }
}
