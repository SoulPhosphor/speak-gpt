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

package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.MemoryScopeGrouping.BrowserGroup

/**
 * The canonical browser grouping helper (canonical recovery plan Phase 2, item
 * 4). Covers:
 *
 *  7. Grouping places world / campaign / rp_character under Roleplay and every
 *     other scope under General.
 *  8. Grouping does not change actual scope or target eligibility — it is a
 *     display concern only.
 */
class MemoryScopeGroupingTest {

    @Test
    fun roleplayScopesGroupUnderRoleplay() {
        assertEquals(BrowserGroup.ROLEPLAY, MemoryScopeGrouping.groupFor("world"))
        assertEquals(BrowserGroup.ROLEPLAY, MemoryScopeGrouping.groupFor("campaign"))
        assertEquals(BrowserGroup.ROLEPLAY, MemoryScopeGrouping.groupFor("rp_character"))
    }

    @Test
    fun everyOtherScopeGroupsUnderGeneral() {
        for (scope in listOf("global", "real_life", "project", "companion")) {
            assertEquals("$scope must group under General", BrowserGroup.GENERAL, MemoryScopeGrouping.groupFor(scope))
        }
        // Unknown / blank scopes are still listed (grouping never hides a memory).
        assertEquals(BrowserGroup.GENERAL, MemoryScopeGrouping.groupFor("something_new"))
        assertEquals(BrowserGroup.GENERAL, MemoryScopeGrouping.groupFor(null))
    }

    @Test
    fun roleplayScopeSetIsExactlyTheThreeFictionScopes() {
        assertEquals(setOf("world", "campaign", "rp_character"), MemoryScopeGrouping.ROLEPLAY_SCOPES)
    }

    @Test
    fun groupingDoesNotChangeScopeOrEligibility() {
        // A Companion memory shown under the General group is STILL a companion
        // memory: the helper reports a display group but neither returns nor
        // mutates the scope, and the companion scope is not a roleplay scope.
        val companionScope = SCOPE_COMPANION
        assertEquals(BrowserGroup.GENERAL, MemoryScopeGrouping.groupFor(companionScope))
        assertFalse(
            "companion must not be a roleplay retrieval scope just because it shows under General",
            companionScope in MemoryScopeGrouping.ROLEPLAY_SCOPES
        )
        // The helper is a pure classifier: the input string is unchanged and no
        // scope is invented. companion stays companion; world stays a fiction
        // scope regardless of which section header it appears beneath.
        assertTrue("world" in MemoryScopeGrouping.ROLEPLAY_SCOPES)
        assertFalse("real_life" in MemoryScopeGrouping.ROLEPLAY_SCOPES)
    }
}
