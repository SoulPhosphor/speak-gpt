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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatStorageHealth.FileState

/**
 * The chat-storage state classification, exhaustively. The precedence rules
 * ARE the Round-4 safety guarantees: an unopenable encrypted file is LOCKED
 * no matter what the plaintext side looks like — never "legacy", never
 * "fresh" — because those states hand the plaintext original back to
 * callers and let migration later consume it. Empty, locked, missing and
 * legacy data must each land in a distinct state.
 */
class ChatStorageHealthClassifierTest {

    @Test fun encryptedOpens_isHealthy_regardlessOfEverythingElse() {
        // Once the encrypted file opens, the other flags are irrelevant:
        // migration and outage reconciliation handle the leftovers.
        for (encExists in listOf(true, false)) {
            for (plainData in listOf(true, false)) {
                assertEquals(
                    FileState.HEALTHY,
                    ChatStorageHealth.classify(true, encExists, plainData)
                )
            }
        }
    }

    @Test fun encryptedFileExistsButCannotOpen_isLocked() {
        assertEquals(
            FileState.LOCKED,
            ChatStorageHealth.classify(encryptedOpens = false, encryptedFileExists = true, plaintextHasData = false)
        )
    }

    @Test fun locked_winsOverLegacyPlaintextData() {
        // A half-migrated install (plaintext not yet cleared) with a dead
        // Keystore must still be LOCKED: returning the plaintext file would
        // both mask the encrypted data AND let a later migration pass clear
        // the plaintext side against a store we could not verify.
        assertEquals(
            FileState.LOCKED,
            ChatStorageHealth.classify(encryptedOpens = false, encryptedFileExists = true, plaintextHasData = true)
        )
    }

    @Test fun noEncryptedFile_withPlaintextData_isLegacy() {
        // Pre-encryption install, Keystore unavailable: nothing encrypted
        // exists to mask, the plaintext file stays authoritative.
        assertEquals(
            FileState.LEGACY_PLAINTEXT,
            ChatStorageHealth.classify(encryptedOpens = false, encryptedFileExists = false, plaintextHasData = true)
        )
    }

    @Test fun nothingAnywhere_isFreshInstall() {
        // Genuinely new installation without a working Keystore — the only
        // state where "empty" truly means empty.
        assertEquals(
            FileState.FRESH_UNENCRYPTED,
            ChatStorageHealth.classify(encryptedOpens = false, encryptedFileExists = false, plaintextHasData = false)
        )
    }

    // ---- caller-visible read states (Round 4 correction) -------------------

    @Test fun lockedRead_isNeverOrdinaryEmpty() {
        // The masquerade Round 4 exists to end: a locked read must classify
        // as LOCKED regardless of what the fallback view claims — even when
        // it claims a present-and-empty value.
        for (keyPresent in listOf(true, false)) {
            for (hasEntries in listOf(true, false)) {
                val state = ChatStorageHealth.readStateFor(
                    locked = true, decryptFailed = false, parseFailed = false,
                    keyPresent = keyPresent, hasEntries = hasEntries
                )
                assertEquals(ChatStorageHealth.ReadState.LOCKED, state)
                assertTrue(state != ChatStorageHealth.ReadState.EMPTY)
            }
        }
    }

    @Test fun corruptBeatsMissingAndEmpty() {
        assertEquals(
            ChatStorageHealth.ReadState.CORRUPT,
            ChatStorageHealth.readStateFor(false, decryptFailed = true, parseFailed = false, keyPresent = true, hasEntries = false)
        )
        assertEquals(
            ChatStorageHealth.ReadState.CORRUPT,
            ChatStorageHealth.readStateFor(false, decryptFailed = false, parseFailed = true, keyPresent = true, hasEntries = false)
        )
    }

    @Test fun emptyMissingAndReadableAreDistinct() {
        assertEquals(
            ChatStorageHealth.ReadState.MISSING,
            ChatStorageHealth.readStateFor(false, false, false, keyPresent = false, hasEntries = false)
        )
        assertEquals(
            ChatStorageHealth.ReadState.EMPTY,
            ChatStorageHealth.readStateFor(false, false, false, keyPresent = true, hasEntries = false)
        )
        assertEquals(
            ChatStorageHealth.ReadState.OK,
            ChatStorageHealth.readStateFor(false, false, false, keyPresent = true, hasEntries = true)
        )
    }

    @Test fun onlyReadableStatesAreAuthoritative() {
        // Authority decisions (rename reconciliation, backfill completion,
        // export completeness) may run only on states that truly describe
        // what exists. Everything else — locked, corrupt, failed — defers.
        assertTrue(ChatStorageHealth.isAuthoritative(ChatStorageHealth.ReadState.OK))
        assertTrue(ChatStorageHealth.isAuthoritative(ChatStorageHealth.ReadState.EMPTY))
        assertTrue(ChatStorageHealth.isAuthoritative(ChatStorageHealth.ReadState.MISSING))
        assertFalse(ChatStorageHealth.isAuthoritative(ChatStorageHealth.ReadState.LOCKED))
        assertFalse(ChatStorageHealth.isAuthoritative(ChatStorageHealth.ReadState.CORRUPT))
        // Unknown/unexpected fails conservatively, never as empty.
        assertFalse(ChatStorageHealth.isAuthoritative(ChatStorageHealth.ReadState.FAILED))
    }

    @Test fun writesAreRefusedOverLockedOrPreservedCorruptStorage() {
        assertTrue(ChatStorageHealth.writeAllowed(locked = false, readFailed = false))
        assertFalse(ChatStorageHealth.writeAllowed(locked = true, readFailed = false))
        assertFalse(ChatStorageHealth.writeAllowed(locked = false, readFailed = true))
        assertFalse(ChatStorageHealth.writeAllowed(locked = true, readFailed = true))
    }
}
