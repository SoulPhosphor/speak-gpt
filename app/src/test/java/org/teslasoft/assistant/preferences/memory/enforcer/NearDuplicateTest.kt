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

package org.teslasoft.assistant.preferences.memory.enforcer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which memory and lorebook entry pairs the existing duplicate rule treats as
 * the same, without the embedding model (word overlap of at least one half).
 * The thresholds are unchanged; these examples document what they do.
 */
class NearDuplicateTest {

    @Test
    fun thresholdsAreUnchanged() {
        assertEquals(0.85f, NearDuplicate.COSINE_THRESHOLD)
        assertEquals(0.5, NearDuplicate.JACCARD_THRESHOLD, 0.0)
    }

    @Test
    fun theSameFactInNearlyTheSameWordsIsADuplicate() {
        assertTrue(NearDuplicate.isTextNearDup(
            "Pepper is the user's grey cat who hates the vacuum.",
            "Pepper: Pepper is the user's grey cat and she hates the vacuum."
        ))
    }

    @Test
    fun aRelatedMemoryThatAddsNewDetailIsNotADuplicate() {
        assertFalse(NearDuplicate.isTextNearDup(
            "Pepper was taken to the vet on Tuesday for a sore paw and needs drops twice a day.",
            "Pepper: Pepper is the user's grey cat and she hates the vacuum."
        ))
    }

    @Test
    fun aShortMemoryRepeatingMostOfAShortEntryIsADuplicate() {
        assertTrue(NearDuplicate.isTextNearDup("The user's sister is named Dana.", "Dana: the user's sister Dana."))
    }

    @Test
    fun sharingOnlyANameIsNotADuplicate() {
        assertFalse(NearDuplicate.isTextNearDup(
            "Dana is moving to Denver in the spring for a new nursing job.",
            "Dana: the user's sister Dana."
        ))
    }
}
