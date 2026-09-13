package org.teslasoft.assistant.preferences.backup

import org.junit.Assert.assertEquals
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
