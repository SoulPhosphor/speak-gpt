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
import org.junit.Test

/**
 * Keep-5 rotation, per type (Build Phase 2 item 3). The invariant that matters
 * is last-known-good survival: the newest files are never deletion candidates.
 */
class BackupRotationPlannerTest {

    @Test
    fun withinKeepDeletesNothing() {
        assertEquals(emptyList<String>(), BackupRotationPlanner.toDelete(listOf("a", "b", "c")))
    }

    @Test
    fun exactlyKeepDeletesNothing() {
        assertEquals(emptyList<String>(), BackupRotationPlanner.toDelete(listOf("1", "2", "3", "4", "5")))
    }

    @Test
    fun beyondKeepDeletesOnlyTheOldest() {
        // Oldest first: with keep=5, the two oldest ("1","2") rotate out.
        assertEquals(
            listOf("1", "2"),
            BackupRotationPlanner.toDelete(listOf("1", "2", "3", "4", "5", "6", "7"))
        )
    }

    @Test
    fun newestIsNeverADeletionCandidate() {
        val del = BackupRotationPlanner.toDelete(listOf("1", "2", "3", "4", "5", "6", "7"))
        assertFalse(del.contains("7"))
        assertFalse(del.contains("6"))
    }

    @Test
    fun customKeepRetainsOnlyTheNewest() {
        assertEquals(
            listOf("1", "2", "3"),
            BackupRotationPlanner.toDelete(listOf("1", "2", "3", "4"), keep = 1)
        )
    }

    @Test
    fun emptySetDeletesNothing() {
        assertEquals(emptyList<String>(), BackupRotationPlanner.toDelete(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun keepMustBeAtLeastOne() {
        BackupRotationPlanner.toDelete(listOf("1", "2"), keep = 0)
    }
}
