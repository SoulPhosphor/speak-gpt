/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup

/** Pure decision table used by startup recovery and exhaustive JVM tests. */
internal object DirectDatabaseRestoreRecoveryPlanner {
    enum class FileState { ORIGINAL, INSTALLED, ABSENT, UNKNOWN }
    enum class KeyState { ORIGINAL, INTENDED, NOT_APPLICABLE, UNKNOWN }
    enum class Decision { FINISH_INSTALL, ROLLBACK, CLEAN_COMMITTED, BLOCK }

    data class Evidence(
        val phase: DirectDatabaseRestoreJournal.Phase,
        val originalExisted: Boolean,
        val active: FileState,
        val stagedInstalledCopyValid: Boolean,
        val quarantineOriginalCopyValid: Boolean,
        val key: KeyState
    )

    fun decide(evidence: Evidence): Decision {
        val intendedKeyAvailable = evidence.key == KeyState.INTENDED ||
            evidence.key == KeyState.NOT_APPLICABLE
        if (evidence.phase == DirectDatabaseRestoreJournal.Phase.COMMITTED) {
            return if (evidence.active == FileState.INSTALLED && intendedKeyAvailable) {
                Decision.CLEAN_COMMITTED
            } else {
                Decision.BLOCK
            }
        }
        if (evidence.active == FileState.INSTALLED || evidence.stagedInstalledCopyValid) {
            return Decision.FINISH_INSTALL
        }
        if (evidence.originalExisted && evidence.quarantineOriginalCopyValid) {
            return Decision.ROLLBACK
        }
        if (!evidence.originalExisted && evidence.active == FileState.ABSENT &&
            (evidence.key == KeyState.ORIGINAL || evidence.key == KeyState.NOT_APPLICABLE)
        ) {
            return Decision.ROLLBACK
        }
        return Decision.BLOCK
    }
}
