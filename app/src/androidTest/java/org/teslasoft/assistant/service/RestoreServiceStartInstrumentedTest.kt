/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
import org.teslasoft.assistant.preferences.backup.portable.RestoreServiceStartFailure
import org.teslasoft.assistant.preferences.backup.portable.UnifiedPortableRestoreCoordinator

/**
 * Android's real refusal of the restore's foreground service (Owner,
 * 2026-11-25): with no screen of the app visible, Android refuses the start;
 * the refusal is returned rather than swallowed, the restore is cancelled before
 * it wrote anything, the Error Log names the actual exception, and the restore
 * screen has a failure to show instead of a silent cancellation.
 */
@RunWith(AndroidJUnit4::class)
class RestoreServiceStartInstrumentedTest {

    private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val root = File(app.cacheDir, "restore-start-test").apply { deleteRecursively(); mkdirs() }

    @After fun tearDown() {
        UnifiedPortableRestoreCoordinator.cancel()
        (UnifiedPortableRestoreCoordinator.snapshot() as? UnifiedPortableRestoreCoordinator.State.StartFailed)
            ?.let { UnifiedPortableRestoreCoordinator.startFailureShown(it.version) }
        root.deleteRecursively()
    }

    @Test fun androidRefusingTheStartIsRecordedAndShownAndNothingIsChanged() {
        val decode = File(root, "decode").apply { mkdirs() }
        assertTrue(
            UnifiedPortableRestoreCoordinator.begin(
                File(root, "package.bin").apply { writeText("x") }, decode,
                listOf(PortableRestoreSelectionPlan.Selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE)),
                PortablePackage.Inspection("password", "2026-11-25", "1", "app", "App", null, true),
                UnifiedPortableRestoreCoordinator.UnlockMaterial.Password("pw".toCharArray())
            )
        )
        // No screen of the app is visible: Android refuses a foreground service started from the background.
        val start = RestoreForegroundService.startUnified(app)
        assertTrue("Android accepted a background start: $start", start is RestoreForegroundService.StartResult.Refused)
        val error = (start as RestoreForegroundService.StartResult.Refused).error
        assertTrue(RestoreServiceStartFailure.androidRefused(error))

        val result = RestoreForegroundService.unifiedStartFailed(app, error)
        assertTrue(result is UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.Cancelled)
        val failed = UnifiedPortableRestoreCoordinator.snapshot() as UnifiedPortableRestoreCoordinator.State.StartFailed
        assertTrue(failed.androidRefused)
        assertFalse(UnifiedPortableRestoreCoordinator.isActive())
        assertFalse(decode.exists())

        val log = Logger.getCrashLog(app)
        assertTrue(log.contains("Restore Service: The restore service could not start: ${error.javaClass.name}"))
        assertTrue(log.contains("The restore did not run and nothing was changed."))
        assertEquals(true, UnifiedPortableRestoreCoordinator.startFailureShown(failed.version))
    }
}
