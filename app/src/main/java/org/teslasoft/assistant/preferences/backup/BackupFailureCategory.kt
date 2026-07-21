/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup

/**
 * The stage of the snapshot pipeline (Build Phase 2 item 4) an attempt failed
 * in. The stage — not the exception type alone — decides the failure category,
 * because the same low-level error means different things at the source vs the
 * destination.
 */
enum class BackupStage {
    /** Reading / snapshotting the source database or chat storage. */
    READ_SOURCE,

    /** Verifying the staged (internal) snapshot before it leaves the device. */
    VERIFY_STAGED,

    /** Copying the staged file into the user-chosen destination folder. */
    WRITE_DESTINATION,

    /** Re-reading the finalized destination file to confirm it landed intact. */
    VERIFY_DESTINATION
}

/**
 * The load-bearing failure classification (Build Phase 2 item 5) — it gates the
 * Build Phase 3 response and is recorded per type instead of a bare "backup
 * failed":
 *
 *  - [SOURCE] — the store could not be read/snapshotted, or its staged snapshot
 *    failed verification. This is a HEALTH SIGNAL, not confirmed corruption: it
 *    triggers the store-specific integrity check (item 7); only a check that
 *    CONFIRMS damage escalates (Build Phase 3). A transient source failure alone
 *    never marks a store corrupt.
 *  - [DESTINATION_PERMISSION] — the folder moved/was deleted/disconnected or the
 *    persisted permission was revoked. Existing backups stay intact; the fix is
 *    Change Backup Folder. Never touches the source.
 *  - [DESTINATION_WRITE] — out of space / IO error writing the destination.
 *  - [VERIFY] — the completed destination file failed its re-read verification.
 */
enum class BackupFailureCategory {
    SOURCE,
    DESTINATION_PERMISSION,
    DESTINATION_WRITE,
    VERIFY;

    companion object {
        fun fromKey(key: String?): BackupFailureCategory? = values().firstOrNull { it.name == key }
    }
}

/**
 * Pure classifier (unit-tested). Destination writes are the only stage whose
 * category depends on the error: a revoked/missing folder is a
 * [BackupFailureCategory.DESTINATION_PERMISSION], everything else at that stage
 * (no space, plain IO) is [BackupFailureCategory.DESTINATION_WRITE]. Source and
 * verify stages map straight through.
 */
object BackupFailureClassifier {

    fun classify(stage: BackupStage, error: Throwable?): BackupFailureCategory = when (stage) {
        BackupStage.READ_SOURCE, BackupStage.VERIFY_STAGED -> BackupFailureCategory.SOURCE
        BackupStage.VERIFY_DESTINATION -> BackupFailureCategory.VERIFY
        BackupStage.WRITE_DESTINATION ->
            if (isPermissionRelated(error)) BackupFailureCategory.DESTINATION_PERMISSION
            else BackupFailureCategory.DESTINATION_WRITE
    }

    /**
     * True when a destination write failed because the chosen folder/document
     * moved, was deleted, or its permission was revoked — as opposed to running
     * out of space or a plain IO error. Walks the cause chain by SIMPLE NAME so
     * this classifier stays free of Android-only types and remains unit-testable
     * on the JVM. The chain walk is bounded so a self-referential cause cannot
     * loop.
     */
    fun isPermissionRelated(error: Throwable?): Boolean {
        var e = error
        var guard = 0
        while (e != null && guard < 20) {
            if (e is SecurityException) return true
            if (e.javaClass.simpleName == "FileNotFoundException") return true
            e = e.cause
            guard++
        }
        return false
    }
}
