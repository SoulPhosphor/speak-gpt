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

package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner ruling (July 29 2026): Summary Length is not a hard limit — the app
 * counts the returned words, tolerates 10% over, and beyond that saves the
 * text unchanged but flags it for compression on the next regular fold-in.
 */
class SummarizerLengthPolicyTest {

    @Test
    fun wordsAreCountedOnWhitespaceIgnoringBlanks() {
        assertEquals(0, SummarizerLengthPolicy.wordCount(""))
        assertEquals(0, SummarizerLengthPolicy.wordCount("   \n  "))
        assertEquals(3, SummarizerLengthPolicy.wordCount("one two three"))
        assertEquals(3, SummarizerLengthPolicy.wordCount("  one \n two\tthree  "))
    }

    @Test
    fun tenPercentOverIsTolerated() {
        assertEquals(330, SummarizerLengthPolicy.allowedWords(300))
        val exactlyAtTolerance = (1..330).joinToString(" ") { "w$it" }
        assertFalse(SummarizerLengthPolicy.isOverLength(exactlyAtTolerance, 300))
    }

    @Test
    fun beyondTheToleranceIsFlaggedOverLength() {
        val oneWordTooMany = (1..331).joinToString(" ") { "w$it" }
        assertTrue(SummarizerLengthPolicy.isOverLength(oneWordTooMany, 300))
    }

    @Test
    fun smallLengthsRoundTheToleranceDown() {
        // 10% of 25 is 2.5 — the tolerance is whole words (27), so 28 is over.
        assertEquals(27, SummarizerLengthPolicy.allowedWords(25))
    }
}
