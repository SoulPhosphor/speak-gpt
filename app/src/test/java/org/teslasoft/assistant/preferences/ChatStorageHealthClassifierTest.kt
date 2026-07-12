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
}
