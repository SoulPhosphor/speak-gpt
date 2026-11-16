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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.LoreBookEntry

/** The lorebook limits every way of sending shares (owner rules, Stage 2). */
class LorebookLimitsTest {

    private fun resolve(settings: LorebookLimits.Settings, memoryActive: Boolean = false) =
        LorebookLimits.resolve(settings, memoryActive)

    @Test
    fun defaultsAreTwentyEntriesAndNoCharacterLimit() {
        val limits = resolve(LorebookLimits.Settings())
        assertEquals(20, limits.maxEntries)
        assertNull(limits.maxCharacters)
    }

    @Test
    fun anOutOfRangeStoredEntryLimitFallsBackToTwenty() {
        assertEquals(20, resolve(LorebookLimits.Settings(maxEntries = 0)).maxEntries)
        assertEquals(20, resolve(LorebookLimits.Settings(maxEntries = 100)).maxEntries)
        assertEquals(99, resolve(LorebookLimits.Settings(maxEntries = 99)).maxEntries)
    }

    @Test
    fun theCharacterLimitNeedsTheToggleAndAValidNumber() {
        assertNull(resolve(LorebookLimits.Settings(characterLimitEnabled = false, maxCharacters = 500)).maxCharacters)
        assertNull(resolve(LorebookLimits.Settings(characterLimitEnabled = true, maxCharacters = null)).maxCharacters)
        assertNull(resolve(LorebookLimits.Settings(characterLimitEnabled = true, maxCharacters = 0)).maxCharacters)
        assertNull(resolve(LorebookLimits.Settings(characterLimitEnabled = true, maxCharacters = 10_000)).maxCharacters)
        assertEquals(9_999, resolve(LorebookLimits.Settings(characterLimitEnabled = true, maxCharacters = 9_999)).maxCharacters)
    }

    @Test
    fun theMemoryOverrideAppliesOnlyWhenMemoryIsOnTheToggleIsOnAndTheNumberIsValid() {
        val on = LorebookLimits.Settings(maxEntries = 20, memoryOverrideEnabled = true, memoryOverrideEntries = 5)
        assertEquals(5, resolve(on, memoryActive = true).maxEntries)
        assertEquals(20, resolve(on, memoryActive = false).maxEntries)
        assertEquals(20, resolve(on.copy(memoryOverrideEnabled = false), memoryActive = true).maxEntries)
        assertEquals(20, resolve(on.copy(memoryOverrideEntries = null), memoryActive = true).maxEntries)
        assertEquals(20, resolve(on.copy(memoryOverrideEntries = 0), memoryActive = true).maxEntries)
    }

    @Test
    fun everyMatchUpToTheLimitIsSentInOrder() {
        val matches = (1..25).map {
            LoreBookMatch(LoreBookEntry(id = "e$it", lorebookId = "book", label = "e$it", content = "text $it"), "t")
        }
        val selection = LorebookLimits.select(matches, resolve(LorebookLimits.Settings()))
        assertEquals((1..20).map { "e$it" }, selection.injectedEntryIds)
        assertEquals((21..25).map { "e$it" }, selection.cut.map { it.match.entry.id })
    }
}
