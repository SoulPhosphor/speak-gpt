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

package org.teslasoft.assistant.preferences.lorebook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoreBookTriggerMatcherTest {

    private fun fires(message: String, trigger: String) =
        LoreBookTriggerMatcher.matches(message, trigger)

    // ---- whole-word matching -------------------------------------------

    @Test fun plainWordMatches() {
        assertTrue(fires("Tell me about the dragon today", "dragon"))
    }

    @Test fun caseInsensitive() {
        assertTrue(fires("DRAGON attack!", "Dragon"))
    }

    @Test fun noSubstringFalsePositive() {
        // The old substring engine fired "cat" on "catalog" — must not anymore.
        assertFalse(fires("I browsed the catalog", "cat"))
        assertFalse(fires("Everything was scattered", "cat"))
    }

    @Test fun punctuationIsAWordBoundary() {
        assertTrue(fires("Is that a dragon?", "dragon"))
        assertTrue(fires("dragon, fire and smoke", "dragon"))
    }

    // ---- suffix folding --------------------------------------------------

    @Test fun pluralBothDirections() {
        assertTrue(fires("the dragons are restless", "dragon"))
        assertTrue(fires("a single dragon appeared", "dragons"))
    }

    @Test fun possessive() {
        assertTrue(fires("the dragon's lair", "dragon"))
    }

    @Test fun iesPlural() {
        assertTrue(fires("so many stories tonight", "story"))
        assertTrue(fires("one story at bedtime", "stories"))
    }

    @Test fun ingForm() {
        assertTrue(fires("she was casting a spell", "cast"))
        assertTrue(fires("he kept running", "run"))
        assertTrue(fires("we are making bread", "make"))
    }

    @Test fun edForm() {
        assertTrue(fires("they walked home", "walk"))
        assertTrue(fires("it was planned for weeks", "plan"))
        assertTrue(fires("she saved the file", "save"))
    }

    @Test fun shortWordsAreNotMangled() {
        // "as" must not fold to "a"; "ring" must not fold to "r".
        assertFalse(fires("a quick note", "as"))
        assertFalse(fires("r is a letter", "ring"))
        assertTrue(fires("the red door", "red"))
    }

    // ---- multi-word triggers ----------------------------------------------

    @Test fun multiWordConsecutive() {
        assertTrue(fires("beware the dragon fire ahead", "dragon fire"))
        assertFalse(fires("the dragon breathed no fire", "dragon fire"))
    }

    @Test fun multiWordWithFolding() {
        assertTrue(fires("all the dragon's fires burned", "dragon fire"))
    }

    // ---- quoted exact mode -----------------------------------------------

    @Test fun quotedDemandsExactText() {
        assertTrue(fires("I browsed the catalog", "\"cat\""))
        assertFalse(fires("the dragons are restless", "\"dragon \""))
        assertTrue(fires("the dragon flies", "\"dragon\""))
    }

    @Test fun quotedIsStillCaseInsensitive() {
        assertTrue(fires("DRAGON FIRE", "\"dragon fire\""))
    }

    @Test fun curlyQuotesAlsoWork() {
        // Phone keyboards auto-insert curly quotes.
        assertTrue(fires("I browsed the catalog", "“cat”"))
    }

    // ---- edge cases --------------------------------------------------------

    @Test fun blankInputsNeverMatch() {
        assertFalse(fires("", "dragon"))
        assertFalse(fires("hello", ""))
        assertFalse(fires("hello", "   "))
        assertFalse(fires("hello", "\"\""))
    }
}
