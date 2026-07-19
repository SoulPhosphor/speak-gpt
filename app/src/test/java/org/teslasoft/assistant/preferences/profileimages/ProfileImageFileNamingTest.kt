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

package org.teslasoft.assistant.preferences.profileimages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImageFileNamingTest {

    private val validHash = "a".repeat(64)

    @Test
    fun permanentFileNameFollowsTheContract() {
        assertEquals("profile_$validHash.jpg", ProfileImageFileNaming.permanentFileName(validHash))
    }

    @Test
    fun extractsTheHashFromAValidFilename() {
        assertEquals(validHash, ProfileImageFileNaming.hashFromPermanentFilename("profile_$validHash.jpg"))
    }

    @Test
    fun rejectsAWrongExtension() {
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("profile_$validHash.jpeg"))
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("profile_$validHash.png"))
    }

    @Test
    fun rejectsUppercaseHexCharacters() {
        val uppercaseHash = "A".repeat(64)
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("profile_$uppercaseHash.jpg"))
    }

    @Test
    fun rejectsAHashOfTheWrongLength() {
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("profile_${"a".repeat(63)}.jpg"))
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("profile_${"a".repeat(65)}.jpg"))
    }

    @Test
    fun rejectsAWrongPrefixOrExtraCharacters() {
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("avatar_$validHash.jpg"))
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("profile_$validHash.jpg.tmp"))
        assertNull(ProfileImageFileNaming.hashFromPermanentFilename("xprofile_$validHash.jpg"))
    }

    @Test
    fun sessionExactlyAtTheThresholdIsNotYetStale() {
        assertFalse(ProfileImageFileNaming.isStaleFramingSession(dirLastModifiedMillis = 0L, nowMillis = 1000L, staleThresholdMillis = 1000L))
    }

    @Test
    fun sessionPastTheThresholdIsStale() {
        assertTrue(ProfileImageFileNaming.isStaleFramingSession(dirLastModifiedMillis = 0L, nowMillis = 1001L, staleThresholdMillis = 1000L))
    }

    @Test
    fun freshSessionIsNeverStale() {
        assertFalse(ProfileImageFileNaming.isStaleFramingSession(dirLastModifiedMillis = 999L, nowMillis = 1000L, staleThresholdMillis = 1000L))
    }
}
