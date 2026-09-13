package org.teslasoft.assistant.preferences.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDatabaseRestoreRecoveryPlannerTest {
    private val phases = DirectDatabaseRestoreJournal.Phase.entries

    @Test
    fun everyPreCommitCrashWithAValidStagedCopyFinishesInstallation() {
        for (phase in phases - DirectDatabaseRestoreJournal.Phase.COMMITTED) {
            for (active in DirectDatabaseRestoreRecoveryPlanner.FileState.entries) {
                for (key in DirectDatabaseRestoreRecoveryPlanner.KeyState.entries) {
                    assertEquals(
                        "phase=$phase active=$active key=$key",
                        DirectDatabaseRestoreRecoveryPlanner.Decision.FINISH_INSTALL,
                        decide(phase, active, staged = true, quarantine = true, key = key)
                    )
                }
            }
        }
    }

    @Test
    fun installedFileFinishesAcrossKeySwitchAndRenameCrashWindows() {
        for (phase in phases - DirectDatabaseRestoreJournal.Phase.COMMITTED) {
            for (key in DirectDatabaseRestoreRecoveryPlanner.KeyState.entries) {
                assertEquals(
                    DirectDatabaseRestoreRecoveryPlanner.Decision.FINISH_INSTALL,
                    decide(
                        phase,
                        DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                        staged = false,
                        quarantine = true,
                        key = key
                    )
                )
            }
        }
    }

    @Test
    fun missingOrTruncatedDesiredFilesRollBackOnlyFromExactQuarantine() {
        for (active in listOf(
            DirectDatabaseRestoreRecoveryPlanner.FileState.ORIGINAL,
            DirectDatabaseRestoreRecoveryPlanner.FileState.ABSENT,
            DirectDatabaseRestoreRecoveryPlanner.FileState.UNKNOWN
        )) {
            assertEquals(
                DirectDatabaseRestoreRecoveryPlanner.Decision.ROLLBACK,
                decide(
                    DirectDatabaseRestoreJournal.Phase.FILE_INSTALLED,
                    active,
                    staged = false,
                    quarantine = true,
                    key = DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED
                )
            )
            assertEquals(
                DirectDatabaseRestoreRecoveryPlanner.Decision.BLOCK,
                decide(
                    DirectDatabaseRestoreJournal.Phase.FILE_INSTALLED,
                    active,
                    staged = false,
                    quarantine = false,
                    key = DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED
                )
            )
        }
    }

    @Test
    fun absentOriginalCanReturnToExactAbsentState() {
        val evidence = DirectDatabaseRestoreRecoveryPlanner.Evidence(
            phase = DirectDatabaseRestoreJournal.Phase.PREPARED,
            originalExisted = false,
            active = DirectDatabaseRestoreRecoveryPlanner.FileState.ABSENT,
            stagedInstalledCopyValid = false,
            quarantineOriginalCopyValid = true,
            key = DirectDatabaseRestoreRecoveryPlanner.KeyState.ORIGINAL
        )
        assertEquals(
            DirectDatabaseRestoreRecoveryPlanner.Decision.ROLLBACK,
            DirectDatabaseRestoreRecoveryPlanner.decide(evidence)
        )
    }

    @Test
    fun committedStateIsCleanedOnlyWithMatchingFileAndKey() {
        val committed = DirectDatabaseRestoreJournal.Phase.COMMITTED
        assertEquals(
            DirectDatabaseRestoreRecoveryPlanner.Decision.CLEAN_COMMITTED,
            decide(
                committed,
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                staged = false,
                quarantine = true,
                key = DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED
            )
        )
        assertEquals(
            DirectDatabaseRestoreRecoveryPlanner.Decision.CLEAN_COMMITTED,
            decide(
                committed,
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                staged = false,
                quarantine = true,
                key = DirectDatabaseRestoreRecoveryPlanner.KeyState.NOT_APPLICABLE
            )
        )
        for (key in listOf(
            DirectDatabaseRestoreRecoveryPlanner.KeyState.ORIGINAL,
            DirectDatabaseRestoreRecoveryPlanner.KeyState.UNKNOWN
        )) {
            assertEquals(
                DirectDatabaseRestoreRecoveryPlanner.Decision.BLOCK,
                decide(
                    committed,
                    DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                    staged = false,
                    quarantine = true,
                    key = key
                )
            )
        }
    }

    @Test
    fun absentPresentAndRotatedKeysAreRepresentedByFingerprints() {
        val original = byteArrayOf(1, 2, 3, 4)
        val rotated = byteArrayOf(4, 3, 2, 1)
        val absent = DirectDatabaseRestoreJournal.keyEvidence(null)
        val present = DirectDatabaseRestoreJournal.keyEvidence(original)

        assertEquals(DirectDatabaseRestoreJournal.KeyKind.ABSENT, absent.kind)
        assertEquals(DirectDatabaseRestoreJournal.KeyKind.PRESENT, present.kind)
        assertTrue(DirectDatabaseRestoreJournal.keyMatches(original, present))
        assertFalse(DirectDatabaseRestoreJournal.keyMatches(rotated, present))
        assertFalse(present.sha256!!.contains(original.joinToString("") { "%02x".format(it) }))
    }

    @Test
    fun walAndNonWalEvidenceUseTheSameDeterministicDecision() {
        // File-set hashing treats the main file and every present sidecar as
        // one observation. Once that observation is exact, WAL and non-WAL
        // starts intentionally select the same recovery direction.
        for (quarantineMatches in listOf(false, true)) {
            assertEquals(
                if (quarantineMatches) DirectDatabaseRestoreRecoveryPlanner.Decision.ROLLBACK
                else DirectDatabaseRestoreRecoveryPlanner.Decision.BLOCK,
                decide(
                    DirectDatabaseRestoreJournal.Phase.KEY_SWITCHED,
                    DirectDatabaseRestoreRecoveryPlanner.FileState.UNKNOWN,
                    staged = false,
                    quarantine = quarantineMatches,
                    key = DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED
                )
            )
        }
    }

    @Test
    fun everyInjectedCrashBoundaryHasOneDeterministicRecoveryDirection() {
        data class CrashState(
            val phase: DirectDatabaseRestoreJournal.Phase,
            val active: DirectDatabaseRestoreRecoveryPlanner.FileState,
            val staged: Boolean,
            val key: DirectDatabaseRestoreRecoveryPlanner.KeyState,
            val decision: DirectDatabaseRestoreRecoveryPlanner.Decision
        )

        val finish = DirectDatabaseRestoreRecoveryPlanner.Decision.FINISH_INSTALL
        val states = mapOf(
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_PREPARED to CrashState(
                DirectDatabaseRestoreJournal.Phase.PREPARED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.ORIGINAL,
                true,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.ORIGINAL,
                finish
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_KEY_WRITE to CrashState(
                DirectDatabaseRestoreJournal.Phase.PREPARED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.ORIGINAL,
                true,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                finish
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_KEY_SWITCHED to CrashState(
                DirectDatabaseRestoreJournal.Phase.KEY_SWITCHED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.ORIGINAL,
                true,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                finish
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_SIDECAR_REMOVAL to CrashState(
                DirectDatabaseRestoreJournal.Phase.KEY_SWITCHED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.UNKNOWN,
                true,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                finish
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_FILE_RENAME to CrashState(
                DirectDatabaseRestoreJournal.Phase.KEY_SWITCHED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                false,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                finish
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_FILE_INSTALLED to CrashState(
                DirectDatabaseRestoreJournal.Phase.FILE_INSTALLED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                false,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                finish
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_LOW_LEVEL_VERIFY to CrashState(
                DirectDatabaseRestoreJournal.Phase.FILE_INSTALLED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                false,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                finish
            ),
            // A normal store open may checkpoint or otherwise change the
            // bytes before final evidence is journaled. Exact quarantine
            // evidence therefore selects the original, never an assumption.
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_NORMAL_VERIFY to CrashState(
                DirectDatabaseRestoreJournal.Phase.FILE_INSTALLED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.UNKNOWN,
                false,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                DirectDatabaseRestoreRecoveryPlanner.Decision.ROLLBACK
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_VERIFIED to CrashState(
                DirectDatabaseRestoreJournal.Phase.VERIFIED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                false,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                finish
            ),
            DirectDatabaseRestoreCoordinator.Boundary.AFTER_COMMITTED to CrashState(
                DirectDatabaseRestoreJournal.Phase.COMMITTED,
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED,
                false,
                DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED,
                DirectDatabaseRestoreRecoveryPlanner.Decision.CLEAN_COMMITTED
            )
        )

        assertEquals(
            DirectDatabaseRestoreCoordinator.Boundary.entries.toSet() -
                DirectDatabaseRestoreCoordinator.Boundary.AFTER_JOURNAL_DELETE,
            states.keys
        )
        for ((boundary, state) in states) {
            assertEquals(
                boundary.name,
                state.decision,
                decide(
                    state.phase,
                    state.active,
                    staged = state.staged,
                    quarantine = true,
                    key = state.key
                )
            )
        }
    }

    private fun decide(
        phase: DirectDatabaseRestoreJournal.Phase,
        active: DirectDatabaseRestoreRecoveryPlanner.FileState,
        staged: Boolean,
        quarantine: Boolean,
        key: DirectDatabaseRestoreRecoveryPlanner.KeyState
    ): DirectDatabaseRestoreRecoveryPlanner.Decision =
        DirectDatabaseRestoreRecoveryPlanner.decide(
            DirectDatabaseRestoreRecoveryPlanner.Evidence(
                phase = phase,
                originalExisted = true,
                active = active,
                stagedInstalledCopyValid = staged,
                quarantineOriginalCopyValid = quarantine,
                key = key
            )
        )
}
