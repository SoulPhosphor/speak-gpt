/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.R

/**
 * A restore whose foreground service cannot start (Owner, 2026-11-25): it is
 * cancelled only before applying began, so "Nothing was changed" is true; a
 * restore that is applying is never cancelled; the Error Log entry precedes the
 * message; the message is shown once; and stale decisions never report a failure
 * for a restore that is still going.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class RestoreServiceStartFailureTest {

    private lateinit var root: File
    private val coordinator = UnifiedPortableRestoreCoordinator

    @Before
    fun setUp() {
        root = Files.createTempDirectory("restore-start").toFile()
        coordinator.cancel()
        forceState(UnifiedPortableRestoreCoordinator.State.Idle(0))
    }

    @After
    fun tearDown() {
        advancing().set(false)
        coordinator.cancel()
        forceState(UnifiedPortableRestoreCoordinator.State.Idle(0))
        root.deleteRecursively()
    }

    private fun begin(): File {
        val pkg = File(root, "package.bin").apply { writeText("x") }
        val decode = File(root, "decode").apply { mkdirs() }
        val inspection = PortablePackage.Inspection("password", "2026-11-25", "1", "app", "App", null, true)
        assertTrue(
            coordinator.begin(
                pkg, decode,
                listOf(PortableRestoreSelectionPlan.Selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE)),
                inspection,
                UnifiedPortableRestoreCoordinator.UnlockMaterial.Password("pw".toCharArray())
            )
        )
        return decode
    }

    private fun field(name: String) = UnifiedPortableRestoreCoordinator::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun advancing(): AtomicBoolean = field("advancing").get(coordinator) as AtomicBoolean

    private fun forceState(state: UnifiedPortableRestoreCoordinator.State) {
        field("state").set(coordinator, state)
    }

    @Test
    fun androidsRefusalIsToldApartFromAnUnexpectedFailure() {
        assertTrue(RestoreServiceStartFailure.androidRefused(ForegroundServiceStartNotAllowedException("Time limit already exhausted")))
        assertFalse(RestoreServiceStartFailure.androidRefused(SecurityException("missing permission")))
        assertFalse(RestoreServiceStartFailure.androidRefused(IllegalStateException("boom")))
        assertEquals(R.string.portable_restore_start_refused, PortableRestoreScreenRule.startFailureMessage(true))
        assertEquals(R.string.portable_restore_start_failed, PortableRestoreScreenRule.startFailureMessage(false))
    }

    @Test
    fun aRefusalWhileCheckingCancelsTheRestoreRecordsItFirstAndShowsItOnce() {
        val decode = begin()
        val seen = mutableListOf<UnifiedPortableRestoreCoordinator.State>()
        val order = mutableListOf<String>()
        val observer = UnifiedPortableRestoreCoordinator.Observer { seen += it; order += "published" }
        coordinator.addObserver(observer)
        try {
            val result = coordinator.cancelAfterServiceStartFailure(androidRefused = true) { order += "recorded" }
            assertEquals(
                UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.Cancelled(
                    UnifiedPortableRestoreCoordinator.ProgressPhase.DECODING
                ),
                result
            )
            assertEquals(listOf("recorded", "published"), order)
            val failed = seen.last() as UnifiedPortableRestoreCoordinator.State.StartFailed
            assertTrue(failed.androidRefused)
            assertFalse(coordinator.isActive())
            assertTrue(coordinator.hasPendingStartFailure())
            assertFalse("the staged backup is removed", decode.exists())
            // Shown once: the state becomes Idle, and an older or repeated acknowledgement does nothing.
            assertTrue(coordinator.startFailureShown(failed.version))
            assertFalse(coordinator.startFailureShown(failed.version))
            assertTrue(coordinator.snapshot() is UnifiedPortableRestoreCoordinator.State.Idle)
            assertFalse(coordinator.hasPendingStartFailure())
        } finally {
            coordinator.removeObserver(observer)
        }
    }

    @Test
    fun aRefusalAfterConfirmingButBeforeApplyingBeganStillChangedNothing() {
        begin()
        forceState(UnifiedPortableRestoreCoordinator.State.Progress(99, UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING))
        val result = coordinator.cancelAfterServiceStartFailure(androidRefused = false)
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.Cancelled(
                UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING
            ),
            result
        )
        assertTrue(coordinator.snapshot() is UnifiedPortableRestoreCoordinator.State.StartFailed)
        assertEquals("before applying", RestoreServiceStartFailure.phaseText(UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING))
    }

    @Test
    fun aRestoreThatIsApplyingOrRestartingIsNeverCancelledAndNoFailureIsShown() {
        begin()
        val applying = UnifiedPortableRestoreCoordinator.State.Progress(100, UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING)
        forceState(applying)
        advancing().set(true)
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.StillApplying,
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertTrue(coordinator.isActive())
        assertEquals(applying, coordinator.snapshot())
        advancing().set(false)

        val restarting = UnifiedPortableRestoreCoordinator.State.Progress(101, UnifiedPortableRestoreCoordinator.ProgressPhase.RESTARTING)
        forceState(restarting)
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.StillApplying,
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertTrue(coordinator.isActive())
        assertEquals(restarting, coordinator.snapshot())
    }

    @Test
    fun withNoRestoreNothingIsCancelledOrShown() {
        var recorded: UnifiedPortableRestoreCoordinator.ServiceStartFailureResult? = null
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.NoRestore,
            coordinator.cancelAfterServiceStartFailure(androidRefused = true) { recorded = it }
        )
        assertEquals(UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.NoRestore, recorded)
        assertFalse(coordinator.hasPendingStartFailure())
    }

    @Test
    fun theLogSaysWhatHappenedAndNeverClaimsTheUserWasToldOrThatAnApplyingRestoreContinues() {
        val refused = ForegroundServiceStartNotAllowedException("Time limit already exhausted for foreground service type dataSync")
        val notStarted = RestoreServiceStartFailure.notStarted(refused, UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT)
        assertTrue(notStarted.startsWith("Restore Service: The restore service could not start: android.app.ForegroundServiceStartNotAllowedException: Time limit already exhausted"))
        assertTrue(notStarted.contains("Operation: unified backup restore, checking."))
        assertTrue(notStarted.contains("The restore did not run and nothing was changed."))
        assertTrue(notStarted.contains("has not been shown yet"))
        assertFalse(notStarted.contains("was told"))

        val applying = RestoreServiceStartFailure.stillApplying(refused)
        assertTrue(applying.startsWith("Restore Service: Android refused a further start of the restore service while the restore was applying"))
        assertTrue(applying.contains("The restore was not cancelled. It may continue without the foreground-service protection and may be interrupted by Android."))
        assertFalse(applying.contains("nothing was changed"))
        assertFalse(applying.contains("continues without"))
        assertTrue(RestoreServiceStartFailure.stillApplying(IllegalStateException("x")).contains("A further start of the restore service failed"))
    }

    @Test
    fun staleDecisionsRedrawAGoingRestoreAndOnlyReportAFailureWhenNoneIsActive() {
        val progress = UnifiedPortableRestoreCoordinator.State.Progress(1, UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT)
        val idle = UnifiedPortableRestoreCoordinator.State.Idle(2)
        // A double tap while the restore goes on: its actual state is redrawn, never a failure.
        assertEquals(PortableRestoreScreenRule.Refusal.REDRAW, PortableRestoreScreenRule.refusal(false, true, progress))
        assertEquals(PortableRestoreScreenRule.Refusal.REDRAW,
            PortableRestoreScreenRule.refusal(false, false, UnifiedPortableRestoreCoordinator.State.StartFailed(3, true)))
        assertEquals(PortableRestoreScreenRule.Refusal.STATE_CHANGED, PortableRestoreScreenRule.refusal(false, false, idle))
        // Starting a new restore while another holds the coordinator: unavailable, never "Nothing was changed".
        assertEquals(PortableRestoreScreenRule.Refusal.UNAVAILABLE, PortableRestoreScreenRule.refusal(true, true, progress))
        assertEquals(PortableRestoreScreenRule.Refusal.VALIDATION, PortableRestoreScreenRule.refusal(true, false, idle))
    }

    @Test
    fun beginRefusesASecondRestoreWhileOneIsActive() {
        begin()
        val pkg = File(root, "other.bin").apply { writeText("y") }
        val decode = File(root, "decode2").apply { mkdirs() }
        assertFalse(
            coordinator.begin(
                pkg, decode,
                listOf(PortableRestoreSelectionPlan.Selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE)),
                PortablePackage.Inspection("password", "2026-11-25", "1", "app", "App", null, true),
                UnifiedPortableRestoreCoordinator.UnlockMaterial.Password("pw".toCharArray())
            )
        )
        assertEquals(PortableRestoreScreenRule.Refusal.UNAVAILABLE,
            PortableRestoreScreenRule.refusal(true, coordinator.isActive(), coordinator.snapshot()))
    }
}
