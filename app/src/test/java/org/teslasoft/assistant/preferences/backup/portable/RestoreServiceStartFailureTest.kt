/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.R

/**
 * A restore whose foreground service cannot start (Owner, 2026-11-25): it is
 * cancelled only before applying began, so "Nothing was changed" is true; a
 * restore that is applying is never cancelled; the error-log entry precedes the
 * message; the message is shown once; and an outdated screen cannot cancel a
 * newer restoration stage.
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
        advancing().set(null)
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

    @Suppress("UNCHECKED_CAST")
    private fun advancing(): AtomicReference<Any?> =
        field("advancing").get(coordinator) as AtomicReference<Any?>

    private fun setWorkerRunning(running: Boolean): Any? = if (running) {
        Any().also { advancing().set(it) }
    } else {
        advancing().set(null)
        null
    }

    private fun finishWaitingStep(workerToken: Any): Boolean {
        val method = UnifiedPortableRestoreCoordinator::class.java
            .getDeclaredMethod("finishWaitingStep", Any::class.java)
            .apply { isAccessible = true }
        return method.invoke(coordinator, workerToken) as Boolean
    }

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
        setWorkerRunning(true)
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.StillRunning(
                UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING
            ),
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertTrue(coordinator.isActive())
        assertEquals(applying, coordinator.snapshot())
        setWorkerRunning(false)

        val restarting = UnifiedPortableRestoreCoordinator.State.Progress(101, UnifiedPortableRestoreCoordinator.ProgressPhase.RESTARTING)
        forceState(restarting)
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.StillRunning(
                UnifiedPortableRestoreCoordinator.ProgressPhase.RESTARTING
            ),
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertTrue(coordinator.isActive())
        assertEquals(restarting, coordinator.snapshot())
    }

    @Test
    fun aRestoreThatIsAlreadyCheckingTheBackupIsNeverCancelledByAnotherStartFailure() {
        begin()
        val checking = UnifiedPortableRestoreCoordinator.State.Progress(
            103, UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT
        )
        forceState(checking)
        setWorkerRunning(true)
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.StillRunning(
                UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT
            ),
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertTrue(coordinator.isActive())
        assertEquals(checking, coordinator.snapshot())
    }

    @Test
    fun aDecisionSubmittedBeforeTheWaitingWorkerFinishesIsConsumedByThatWorker() {
        begin()
        val waiting = UnifiedPortableRestoreCoordinator.State.UnlockFailed(
            104,
            PortablePackageFormat.RestoreError.MISTYPED_CODE,
            coordinator.inspection()!!
        )
        forceState(waiting)
        val workerToken = setWorkerRunning(true)!!

        assertTrue(coordinator.retryUnlock(
            UnifiedPortableRestoreCoordinator.UnlockMaterial.Password("retry".toCharArray()),
            waiting.version
        ))
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.StillRunning(
                UnifiedPortableRestoreCoordinator.ProgressPhase.DECODING
            ),
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertFalse(
            "the existing worker must continue with the submitted decision",
            finishWaitingStep(workerToken)
        )
        assertEquals(workerToken, advancing().get())
        assertTrue(coordinator.isActive())
    }

    @Test
    fun aDecisionSubmittedAfterTheWaitingWorkerFinishesIsCancelledIfNoReplacementStarts() {
        begin()
        val waiting = UnifiedPortableRestoreCoordinator.State.UnlockFailed(
            105,
            PortablePackageFormat.RestoreError.MISTYPED_CODE,
            coordinator.inspection()!!
        )
        forceState(waiting)
        val workerToken = setWorkerRunning(true)!!

        assertTrue(finishWaitingStep(workerToken))
        assertEquals(null, advancing().get())
        assertTrue(coordinator.retryUnlock(
            UnifiedPortableRestoreCoordinator.UnlockMaterial.Password("retry".toCharArray()),
            waiting.version
        ))
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.Cancelled(
                UnifiedPortableRestoreCoordinator.ProgressPhase.DECODING
            ),
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertFalse(coordinator.isActive())
        assertTrue(coordinator.snapshot() is UnifiedPortableRestoreCoordinator.State.StartFailed)
    }

    @Test
    fun aQueuedWorkerDoesNotRepeatWorkWhileTheRestorationIsWaitingForTheUser() {
        begin()
        val waiting = UnifiedPortableRestoreCoordinator.State.UnlockFailed(
            106,
            PortablePackageFormat.RestoreError.MISTYPED_CODE,
            coordinator.inspection()!!
        )
        forceState(waiting)
        setWorkerRunning(false)

        assertEquals(
            UnifiedPortableRestoreCoordinator.AdvanceResult.NO_WORK,
            coordinator.advance(RuntimeEnvironment.getApplication())
        )
        assertEquals(waiting, coordinator.snapshot())
        assertTrue(coordinator.isActive())
        assertEquals(null, advancing().get())
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
    fun aFinishedRestorationsResultIsNeverReplacedByAServiceStartFailure() {
        begin()
        val terminal = UnifiedPortableRestoreCoordinator.State.Terminal(
            102, PortableRestoreOutcome.NothingAvailable
        )
        forceState(terminal)
        assertEquals(
            UnifiedPortableRestoreCoordinator.ServiceStartFailureResult.TerminalPreserved,
            coordinator.cancelAfterServiceStartFailure(androidRefused = true)
        )
        assertTrue(coordinator.isActive())
        assertEquals(terminal, coordinator.snapshot())
    }

    @Test
    fun theLogSaysWhatHappenedAndNeverClaimsTheUserWasToldOrThatAnApplyingRestoreContinues() {
        val refused = ForegroundServiceStartNotAllowedException("Time limit already exhausted for foreground service type dataSync")
        val notStarted = RestoreServiceStartFailure.notStarted(refused, UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT)
        assertTrue(notStarted.startsWith("Restore Service: The restore service could not start: android.app.ForegroundServiceStartNotAllowedException: Time limit already exhausted"))
        assertTrue(notStarted.contains("Operation: unified backup restore, checking."))
        assertTrue(notStarted.contains("The restoration stopped before any backup data was applied."))
        assertTrue(notStarted.contains("has not been shown yet"))
        assertFalse(notStarted.contains("was told"))

        val applying = RestoreServiceStartFailure.stillRunning(
            refused, UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING
        )
        assertTrue(applying.startsWith("Restore Service: Android refused a further start of the restore service while the restoration was applying changes"))
        assertTrue(applying.contains("The failed additional service request did not cancel the restoration."))
        assertFalse(applying.contains("nothing was changed"))
        assertTrue(
            RestoreServiceStartFailure.stillRunning(
                IllegalStateException("x"), UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT
            ).contains("A further start of the restore service failed")
        )

        val noActiveRestore = RestoreServiceStartFailure.noActiveRestore(refused)
        assertTrue(noActiveRestore.contains("No active restoration was found"))
        assertTrue(noActiveRestore.contains("does not establish the outcome"))
        assertFalse(noActiveRestore.contains("nothing was changed"))

        val terminalPreserved = RestoreServiceStartFailure.terminalPreserved(refused)
        assertTrue(terminalPreserved.contains("result and recovery information were preserved"))
        assertTrue(terminalPreserved.contains("Open Backup & Restore"))
    }

    @Test
    fun anOutdatedCancelActionCannotCancelTheCurrentRestoration() {
        begin()
        val current = coordinator.snapshot()
        assertFalse(coordinator.cancel(current.version - 1))
        assertTrue(coordinator.isActive())
        assertEquals(current, coordinator.snapshot())
        assertTrue(coordinator.cancel(current.version))
        assertFalse(coordinator.isActive())
    }

    @Test
    fun beginRefusesASecondRestoreWhileOneIsActive() {
        begin()
        val pkg = File(root, "other.bin").apply { writeText("y") }
        val decode = File(root, "decode2").apply { mkdirs() }
        assertEquals(
            UnifiedPortableRestoreCoordinator.BeginResult.AlreadyInProgress,
            coordinator.beginDetailed(
                pkg, decode,
                listOf(PortableRestoreSelectionPlan.Selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE)),
                PortablePackage.Inspection("password", "2026-11-25", "1", "app", "App", null, true),
                UnifiedPortableRestoreCoordinator.UnlockMaterial.Password("pw".toCharArray())
            )
        )
        assertTrue(coordinator.isActive())
    }
}
