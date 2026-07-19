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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImageReconciliationTest {

    private val hash = "b".repeat(64)
    private val otherHash = "c".repeat(64)

    @Test
    fun acceptsAMatchingHashAtExactly512() {
        assertTrue(ProfileImageReconciliation.isValidReconciledImage(hash, hash, 512, 512))
    }

    @Test
    fun rejectsAHashMismatch() {
        assertFalse(ProfileImageReconciliation.isValidReconciledImage(hash, otherHash, 512, 512))
    }

    @Test
    fun rejectsWrongWidth() {
        assertFalse(ProfileImageReconciliation.isValidReconciledImage(hash, hash, 511, 512))
        assertFalse(ProfileImageReconciliation.isValidReconciledImage(hash, hash, 1024, 512))
    }

    @Test
    fun rejectsWrongHeight() {
        assertFalse(ProfileImageReconciliation.isValidReconciledImage(hash, hash, 512, 511))
        assertFalse(ProfileImageReconciliation.isValidReconciledImage(hash, hash, 512, 1024))
    }

    @Test
    fun rejectsWhenBothDimensionsAndHashAreWrong() {
        assertFalse(ProfileImageReconciliation.isValidReconciledImage(hash, otherHash, 100, 100))
    }
}
