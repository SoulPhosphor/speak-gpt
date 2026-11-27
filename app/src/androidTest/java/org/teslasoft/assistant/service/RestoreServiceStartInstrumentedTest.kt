/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.service

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.backup.portable.PortablePackage
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreCategory
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreMode
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreSelectionPlan
import org.teslasoft.assistant.preferences.backup.portable.UnifiedPortableRestoreCoordinator
import org.teslasoft.assistant.ui.activities.MemoryControlsActivity

/**
 * Android's real refusal of the restore's foreground service (Owner,
 * 2026-11-25). The app's data-sync time is used up first through Android's
 * test setting (a shortened limit), so Android refuses the next data-sync
 * start, whichever call it refuses: the start request, or the service's own
 * startForeground. Either way the refusal is never silent, the restore is
 * cancelled before it wrote anything, the error log names the actual
 * exception, and the restore screen has a failure to show.
 */
@RunWith(AndroidJUnit4::class)
class RestoreServiceStartInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext.applicationContext
    private val root = File(app.cacheDir, "restore-start-test").apply { deleteRecursively(); mkdirs() }

    @After fun tearDown() {
        UnifiedPortableRestoreCoordinator.cancel()
        (UnifiedPortableRestoreCoordinator.snapshot() as? UnifiedPortableRestoreCoordinator.State.StartFailed)
            ?.let { UnifiedPortableRestoreCoordinator.startFailureShown(it.version) }
        instrumentation.runOnMainSync { ImageGenerationForegroundService.end(app) }
        shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
        // Opening the app resets Android's time for data-sync services.
        ActivityScenario.launch(MemoryControlsActivity::class.java).close()
        root.deleteRecursively()
    }

    @Test fun androidRefusingTheStartIsRecordedAndShownBeforeBackupDataIsApplied() {
        useUpTheDataSyncTime()
        val logBefore = Logger.getCrashLog(app)

        val decode = File(root, "decode").apply { mkdirs() }
        assertTrue(
            UnifiedPortableRestoreCoordinator.begin(
                File(root, "package.bin").apply { writeText("x") }, decode,
                listOf(PortableRestoreSelectionPlan.Selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE)),
                PortablePackage.Inspection("password", "2026-11-25", "1", "app", "App", null, true),
                UnifiedPortableRestoreCoordinator.UnlockMaterial.Password("pw".toCharArray())
            )
        )
        // Refused at the request, or accepted and then refused in the service's startForeground.
        var start: RestoreForegroundService.StartResult? = null
        instrumentation.runOnMainSync { start = RestoreForegroundService.startUnified(app) }
        (start as? RestoreForegroundService.StartResult.Refused)?.let { refused ->
            instrumentation.runOnMainSync { RestoreForegroundService.unifiedStartFailed(app, refused.error) }
        }
        waitFor("the refusal is handled", 20_000) {
            UnifiedPortableRestoreCoordinator.snapshot() is UnifiedPortableRestoreCoordinator.State.StartFailed
        }
        val failed = UnifiedPortableRestoreCoordinator.snapshot() as UnifiedPortableRestoreCoordinator.State.StartFailed
        assertTrue("classified as Android's refusal, not an unexpected failure", failed.androidRefused)
        assertFalse(UnifiedPortableRestoreCoordinator.isActive())
        assertFalse("the staged backup is removed", decode.exists())

        val added = Logger.getCrashLog(app).removePrefix(logBefore)
        assertTrue(added, added.contains("Restore Service: The restore service could not start: android.app.ForegroundServiceStartNotAllowedException"))
        assertTrue(added, added.contains("The restoration stopped before any backup data was applied."))
        assertEquals(true, UnifiedPortableRestoreCoordinator.startFailureShown(failed.version))
    }

    /** Starts a data-sync keep-alive while a screen is visible, leaves, and waits for Android's (shortened) limit. */
    private fun useUpTheDataSyncTime() {
        shell("device_config put activity_manager data_sync_fgs_timeout_duration 3000")
        waitFor("Android stores the shortened limit") {
            shellOutput("device_config get activity_manager data_sync_fgs_timeout_duration").trim() == "3000"
        }
        ActivityScenario.launch(MemoryControlsActivity::class.java).use {
            var started = false
            instrumentation.runOnMainSync { started = ImageGenerationForegroundService.begin(app, "restore-start-test") }
            assertTrue(started)
            waitFor("the keep-alive runs") { running(ImageGenerationForegroundService::class.java) }
        }
        waitFor("Android's time limit stops it", 60_000) { !running(ImageGenerationForegroundService::class.java) }
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
        shellOutput(command)
    }

    private fun shellOutput(command: String): String {
        val output: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val text = FileInputStream(output.fileDescriptor).use { String(it.readBytes()) }
        output.close()
        return text
    }
}
