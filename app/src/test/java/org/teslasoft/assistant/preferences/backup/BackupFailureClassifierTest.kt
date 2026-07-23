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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The load-bearing failure classification (Build Phase 2 item 5). The category
 * gates the Build Phase 3 response, so these cases pin exactly which stage maps
 * to which category — a source failure must never masquerade as a destination
 * problem and vice versa.
 */
class BackupFailureClassifierTest {

    @Test
    fun readSourceIsSource() {
        assertEquals(
            BackupFailureCategory.SOURCE,
            BackupFailureClassifier.classify(BackupStage.READ_SOURCE, RuntimeException("boom"))
        )
    }

    @Test
    fun verifyStagedIsSource() {
        // A staged snapshot that fails its own integrity check is a SOURCE
        // problem (the snapshot is bad), not a destination problem.
        assertEquals(
            BackupFailureCategory.SOURCE,
            BackupFailureClassifier.classify(BackupStage.VERIFY_STAGED, null)
        )
    }

    @Test
    fun verifyDestinationIsVerify() {
        assertEquals(
            BackupFailureCategory.VERIFY,
            BackupFailureClassifier.classify(BackupStage.VERIFY_DESTINATION, RuntimeException("hash mismatch"))
        )
    }

    @Test
    fun writeWithSecurityExceptionIsPermission() {
        assertEquals(
            BackupFailureCategory.DESTINATION_PERMISSION,
            BackupFailureClassifier.classify(BackupStage.WRITE_DESTINATION, SecurityException("permission revoked"))
        )
    }

    @Test
    fun writeWithMissingFolderIsPermission() {
        assertEquals(
            BackupFailureCategory.DESTINATION_PERMISSION,
            BackupFailureClassifier.classify(BackupStage.WRITE_DESTINATION, FileNotFoundException("tree gone"))
        )
    }

    @Test
    fun writeWithIoIsWrite() {
        assertEquals(
            BackupFailureCategory.DESTINATION_WRITE,
            BackupFailureClassifier.classify(BackupStage.WRITE_DESTINATION, IOException("ENOSPC: No space left on device"))
        )
    }

    @Test
    fun permissionDetectedThroughCauseChain() {
        val wrapped = RuntimeException("copy failed", SecurityException("nested revoke"))
        assertEquals(
            BackupFailureCategory.DESTINATION_PERMISSION,
            BackupFailureClassifier.classify(BackupStage.WRITE_DESTINATION, wrapped)
        )
    }

    @Test
    fun nullErrorAtWriteIsWrite() {
        assertEquals(
            BackupFailureCategory.DESTINATION_WRITE,
            BackupFailureClassifier.classify(BackupStage.WRITE_DESTINATION, null)
        )
    }

    @Test
    fun selfReferentialCauseChainDoesNotLoop() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // cycle; the bounded walk must terminate
        assertFalse(BackupFailureClassifier.isPermissionRelated(a))
    }

    /* -------------------- insufficient-storage detection ------------------- */

    @Test
    fun enospcMessageIsInsufficientStorage() {
        assertTrue(BackupFailureClassifier.isInsufficientStorage(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun noSpaceLeftPhraseIsInsufficientStorage() {
        assertTrue(BackupFailureClassifier.isInsufficientStorage(IOException("No space left on device")))
    }

    @Test
    fun notEnoughSpacePhraseIsInsufficientStorage() {
        assertTrue(BackupFailureClassifier.isInsufficientStorage(RuntimeException("Not enough space to complete write")))
    }

    @Test
    fun insufficientStorageThroughCauseChain() {
        val wrapped = RuntimeException("copy failed", IOException("ENOSPC"))
        assertTrue(BackupFailureClassifier.isInsufficientStorage(wrapped))
    }

    @Test
    fun caseInsensitiveStorageMatch() {
        assertTrue(BackupFailureClassifier.isInsufficientStorage(IOException("no SPACE left ON device")))
    }

    @Test
    fun genericIoErrorIsNotInsufficientStorage() {
        // A plain write failure must NOT be mislabeled out-of-space (owner
        // rule: storage message "if and only if" the exception says so).
        assertFalse(BackupFailureClassifier.isInsufficientStorage(IOException("Broken pipe")))
        assertFalse(BackupFailureClassifier.isInsufficientStorage(IOException("Permission denied")))
        assertFalse(BackupFailureClassifier.isInsufficientStorage(null))
        assertFalse(BackupFailureClassifier.isInsufficientStorage(RuntimeException()))
    }

    @Test
    fun insufficientStorageSelfReferentialChainDoesNotLoop() {
        val a = RuntimeException("outer")
        val b = RuntimeException("inner", a)
        a.initCause(b)
        // Must terminate (and not match — no storage phrase present).
        assertFalse(BackupFailureClassifier.isInsufficientStorage(a))
    }

    @Test
    fun categoryRoundTripsThroughKey() {
        for (c in BackupFailureCategory.values()) {
            assertEquals(c, BackupFailureCategory.fromKey(c.name))
        }
        assertTrue(BackupFailureCategory.fromKey("nonsense") == null)
        assertTrue(BackupFailureCategory.fromKey(null) == null)
    }
}
