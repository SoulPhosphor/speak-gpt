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
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The owner-approved filename architecture (July 22 2026): every shape,
 * collision suffixes, brand injection, sanitization, blank-brand fallback,
 * brand changes, and the brand-agnostic automatic-shape recognizer (which is
 * never, by itself, authorization to touch a file).
 */
class RecoveryFileNamingTest {

    private val utc = ZoneOffset.UTC

    /** 2026-07-21 17:22 UTC — the stamp used in the owner's examples. */
    private val at = ZonedDateTime.of(2026, 7, 21, 17, 22, 0, 0, utc).toInstant().toEpochMilli()

    // ----- every approved shape ----------------------------------------------

    @Test
    fun manualProtectedShape() {
        assertEquals(
            "TestBrand-Recovery-Protected-2026-07-21_1722.zip",
            RecoveryFileNaming.manualRecoveryPackage("TestBrand", protected = true, epochMillis = at, zone = utc)
        )
    }

    @Test
    fun manualUnencryptedShape() {
        assertEquals(
            "TestBrand-Recovery-Unencrypted-2026-07-21_1722.zip",
            RecoveryFileNaming.manualRecoveryPackage("TestBrand", protected = false, epochMillis = at, zone = utc)
        )
    }

    @Test
    fun automaticProtectedShape() {
        assertEquals(
            "TestBrand-Automatic-Recovery-Protected-2026-07-21_1722.zip",
            RecoveryFileNaming.automaticRecoveryPackage("TestBrand", protected = true, epochMillis = at, zone = utc)
        )
    }

    @Test
    fun automaticUnencryptedShape() {
        assertEquals(
            "TestBrand-Automatic-Recovery-Unencrypted-2026-07-21_1722.zip",
            RecoveryFileNaming.automaticRecoveryPackage("TestBrand", protected = false, epochMillis = at, zone = utc)
        )
    }

    @Test
    fun readableChatsCompleteShape() {
        assertEquals(
            "TestBrand-Readable-Chats-Complete-2026-07-21_1722.zip",
            RecoveryFileNaming.readableChats("TestBrand", complete = true, epochMillis = at, zone = utc)
        )
    }

    @Test
    fun readableChatsIncrementalShape() {
        assertEquals(
            "TestBrand-Readable-Chats-Incremental-2026-07-21_1722.zip",
            RecoveryFileNaming.readableChats("TestBrand", complete = false, epochMillis = at, zone = utc)
        )
    }

    // ----- collision suffixes ------------------------------------------------

    @Test
    fun sameMinuteCollisionsAppendSequenceNumbers() {
        assertEquals(
            "TestBrand-Recovery-Protected-2026-07-21_1722.zip",
            RecoveryFileNaming.manualRecoveryPackage("TestBrand", true, at, utc, seq = 1)
        )
        assertEquals(
            "TestBrand-Recovery-Protected-2026-07-21_1722-2.zip",
            RecoveryFileNaming.manualRecoveryPackage("TestBrand", true, at, utc, seq = 2)
        )
        assertEquals(
            "TestBrand-Automatic-Recovery-Unencrypted-2026-07-21_1722-3.zip",
            RecoveryFileNaming.automaticRecoveryPackage("TestBrand", false, at, utc, seq = 3)
        )
        assertEquals(
            "TestBrand-Readable-Chats-Complete-2026-07-21_1722-2.zip",
            RecoveryFileNaming.readableChats("TestBrand", true, at, utc, seq = 2)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun sequenceBelowOneIsRejected() {
        RecoveryFileNaming.manualRecoveryPackage("TestBrand", true, at, utc, seq = 0)
    }

    // ----- brand injection, sanitization, fallback, change -------------------

    @Test
    fun suppliedBrandIsUsedVerbatimWhenAlreadySafe() {
        assertEquals("MyApp", BackupBrand.sanitize("MyApp"))
    }

    @Test
    fun unsafeCharactersAreSanitized() {
        assertEquals("My-App", BackupBrand.sanitize("My App!"))
        assertEquals("Phosphor-Shines", BackupBrand.sanitize("Phosphor Shines"))
        assertEquals("AB", BackupBrand.sanitize("A/\\:*?\"<>|B"))
        assertEquals("A-B", BackupBrand.sanitize("A --- B"))
        assertEquals("App", BackupBrand.sanitize("  App…  ")) // unicode stripped, trimmed
    }

    @Test
    fun blankOrInvalidBrandFallsBack() {
        assertEquals(BackupBrand.FALLBACK, BackupBrand.sanitize(""))
        assertEquals(BackupBrand.FALLBACK, BackupBrand.sanitize("   "))
        assertEquals(BackupBrand.FALLBACK, BackupBrand.sanitize(null))
        assertEquals(BackupBrand.FALLBACK, BackupBrand.sanitize("!!!///:::"))
        assertEquals(BackupBrand.FALLBACK, BackupBrand.sanitize("---"))
    }

    @Test
    fun overlongBrandIsCapped() {
        val long = "A".repeat(200)
        assertEquals(BackupBrand.MAX_LENGTH, BackupBrand.sanitize(long).length)
    }

    @Test
    fun namingSanitizesDefensivelyEvenIfCallerForgot() {
        assertEquals(
            "My-App-Recovery-Protected-2026-07-21_1722.zip",
            RecoveryFileNaming.manualRecoveryPackage("My App!", true, at, utc)
        )
    }

    @Test
    fun brandChangeOnlyChangesThePrefix() {
        val old = RecoveryFileNaming.automaticRecoveryPackage("OldBrand", true, at, utc)
        val new = RecoveryFileNaming.automaticRecoveryPackage("NewBrand", true, at, utc)
        assertEquals(old.removePrefix("OldBrand"), new.removePrefix("NewBrand"))
    }

    // ----- the brand-agnostic automatic-shape recognizer ---------------------

    @Test
    fun automaticShapeMatchesAnyBrandOldOrNew() {
        assertTrue(RecoveryFileNaming.hasAutomaticRecoveryShape("OldBrand-Automatic-Recovery-Protected-2026-07-21_1722.zip"))
        assertTrue(RecoveryFileNaming.hasAutomaticRecoveryShape("NewBrand-Automatic-Recovery-Unencrypted-2026-07-21_1722-2.zip"))
    }

    @Test
    fun manualAndReadableShapesAreNeverAutomaticShapes() {
        assertFalse(RecoveryFileNaming.hasAutomaticRecoveryShape("Brand-Recovery-Protected-2026-07-21_1722.zip"))
        assertFalse(RecoveryFileNaming.hasAutomaticRecoveryShape("Brand-Readable-Chats-Complete-2026-07-21_1722.zip"))
        assertFalse(RecoveryFileNaming.hasAutomaticRecoveryShape("random-user-file.zip"))
        assertFalse(RecoveryFileNaming.hasAutomaticRecoveryShape(""))
    }

    // ----- dormant separate-artifact names (no production caller) ------------

    @Test
    fun dormantSeparateArtifactShapesRemainDefined() {
        assertEquals(
            "TestBrand-Memory-2026-07-21_1722.dbbackup",
            RecoveryFileNaming.dormantSeparateArtifact("TestBrand", BackupType.MEMORY, at, utc)
        )
        assertEquals(
            "TestBrand-Chats-Recovery-2026-07-21_1722-2.zip",
            RecoveryFileNaming.dormantSeparateArtifact("TestBrand", BackupType.CHATS, at, utc, seq = 2)
        )
    }
}
