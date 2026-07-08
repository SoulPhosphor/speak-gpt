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
import org.teslasoft.assistant.preferences.memory.TargetTeardownPlanner.OwnedMemory

/**
 * The owner's required test cases from
 * `Memory System/roleplay_memory_deletion_fix.md` (July 8 2026), run for each
 * target type (world / campaign / RP character — the planner is the shared
 * decision logic all three teardowns execute, so each case runs against every
 * type's id shape):
 *
 * 1. Memory linked only to Target A; delete A + keep → memory remains, A link
 *    removed, mirror cleared (null).
 * 2. Memory linked only to Target A; delete A + delete → memory is deleted.
 * 3. Memory linked to A and B; delete B (either keep or delete) → B link
 *    removed, memory remains linked to A, and if the mirror was B it is now
 *    A; in the delete variant the memory is NOT hard-deleted because A
 *    remains.
 * 4. Memory linked to A and B with mirror = B; delete B + keep → mirror
 *    reassigned to A (not left null).
 *
 * Plus the world-only step 5: keepCharacterMemories decides "a character's
 * memory too" from the memory_roleplay_characters JOIN (any row), never the
 * roleplay_character_id mirror column.
 */
class TargetTeardownPlannerTest {

    // The same cases must hold for every target type; ids only differ in shape.
    private val targetPairs = listOf(
        "world-a" to "world-b",
        "campaign-a" to "campaign-b",
        "rpchar-a" to "rpchar-b"
    )

    @Test
    fun case1_soleOwner_keep_memoryRemains_linkRemoved_mirrorCleared() {
        for ((a, _) in targetPairs) {
            val plan = TargetTeardownPlanner.plan(
                targetId = a,
                owned = listOf(OwnedMemory("m1", otherOwnerIds = emptyList(), mirrorId = a)),
                deleteMemories = false
            )
            assertTrue("$a: nothing hard-deleted", plan.deleteMemoryIds.isEmpty())
            assertEquals("$a: link removed", listOf("m1"), plan.unlinkMemoryIds)
            assertTrue("$a: mirror touched", plan.mirrorReassignments.containsKey("m1"))
            assertEquals("$a: mirror cleared", null, plan.mirrorReassignments["m1"])
        }
    }

    @Test
    fun case2_soleOwner_delete_memoryDeleted() {
        for ((a, _) in targetPairs) {
            val plan = TargetTeardownPlanner.plan(
                targetId = a,
                owned = listOf(OwnedMemory("m1", otherOwnerIds = emptyList(), mirrorId = a)),
                deleteMemories = true
            )
            assertEquals("$a: hard-deleted", listOf("m1"), plan.deleteMemoryIds)
            assertTrue("$a: no unlink for a deleted memory", plan.unlinkMemoryIds.isEmpty())
            assertTrue("$a: no mirror write for a deleted memory", plan.mirrorReassignments.isEmpty())
        }
    }

    @Test
    fun case3_sharedMemory_deleteVariant_survivesWithLinkRemoved() {
        for ((a, b) in targetPairs) {
            // Deleting B while the memory is also linked to A: never hard-deleted.
            val plan = TargetTeardownPlanner.plan(
                targetId = b,
                owned = listOf(OwnedMemory("m1", otherOwnerIds = listOf(a), mirrorId = b)),
                deleteMemories = true
            )
            assertFalse("$b: shared memory must survive", plan.deleteMemoryIds.contains("m1"))
            assertEquals("$b: B link removed", listOf("m1"), plan.unlinkMemoryIds)
            assertEquals("$b: mirror moved off the deleted target", a, plan.mirrorReassignments["m1"])
        }
    }

    @Test
    fun case3_sharedMemory_keepVariant_survivesWithLinkRemoved() {
        for ((a, b) in targetPairs) {
            val plan = TargetTeardownPlanner.plan(
                targetId = b,
                owned = listOf(OwnedMemory("m1", otherOwnerIds = listOf(a), mirrorId = b)),
                deleteMemories = false
            )
            assertTrue("$b: nothing hard-deleted", plan.deleteMemoryIds.isEmpty())
            assertEquals("$b: B link removed", listOf("m1"), plan.unlinkMemoryIds)
            assertEquals("$b: mirror moved off the deleted target", a, plan.mirrorReassignments["m1"])
        }
    }

    @Test
    fun case4_mirrorOnDeletedTarget_reassignedToSurvivor_notNulled() {
        for ((a, b) in targetPairs) {
            val plan = TargetTeardownPlanner.plan(
                targetId = b,
                owned = listOf(OwnedMemory("m1", otherOwnerIds = listOf(a), mirrorId = b)),
                deleteMemories = false
            )
            assertTrue(plan.mirrorReassignments.containsKey("m1"))
            assertEquals("mirror must be A, not null", a, plan.mirrorReassignments["m1"])
        }
    }

    @Test
    fun mirrorPointingElsewhere_isLeftAlone() {
        for ((a, b) in targetPairs) {
            // Join-only ownership (harm #2 in the fix doc): mirror already names
            // A, we delete B — the mirror needs no write.
            val plan = TargetTeardownPlanner.plan(
                targetId = b,
                owned = listOf(OwnedMemory("m1", otherOwnerIds = listOf(a), mirrorId = a)),
                deleteMemories = false
            )
            assertEquals(listOf("m1"), plan.unlinkMemoryIds)
            assertFalse("untouched mirror is not rewritten", plan.mirrorReassignments.containsKey("m1"))
        }
    }

    @Test
    fun joinOnlyOwnership_deleteVariant_soleOwnerIsStillDeleted() {
        // Harm #2's other half: the memory's ONLY owner is the deleted target
        // but the mirror points elsewhere (stale). Join-as-truth says it is
        // sole-owned, so delete-memories must remove it — the old mirror-keyed
        // query missed exactly this row.
        val plan = TargetTeardownPlanner.plan(
            targetId = "world-a",
            owned = listOf(OwnedMemory("m1", otherOwnerIds = emptyList(), mirrorId = "world-z")),
            deleteMemories = true
        )
        assertEquals(listOf("m1"), plan.deleteMemoryIds)
    }

    @Test
    fun worldStep5_characterLinkedMemoryKept_viaJoinNotMirror() {
        // keepCharacterMemories: sole-owned world memory, but a roleplay
        // character also holds it via memory_roleplay_characters → kept, world
        // link removed, mirror cleared.
        val plan = TargetTeardownPlanner.plan(
            targetId = "world-a",
            owned = listOf(
                OwnedMemory("kept", emptyList(), mirrorId = "world-a", hasCharacterLink = true),
                OwnedMemory("gone", emptyList(), mirrorId = "world-a", hasCharacterLink = false)
            ),
            deleteMemories = true,
            keepCharacterMemories = true
        )
        assertEquals(listOf("gone"), plan.deleteMemoryIds)
        assertEquals(listOf("kept"), plan.unlinkMemoryIds)
        assertEquals(null, plan.mirrorReassignments["kept"])
    }

    @Test
    fun worldStep5_withoutKeepFlag_characterLinkDoesNotProtect() {
        val plan = TargetTeardownPlanner.plan(
            targetId = "world-a",
            owned = listOf(OwnedMemory("m1", emptyList(), mirrorId = "world-a", hasCharacterLink = true)),
            deleteMemories = true,
            keepCharacterMemories = false
        )
        assertEquals(listOf("m1"), plan.deleteMemoryIds)
    }

    @Test
    fun companion_sharedMemorySurvives_soleOwnedDeleted() {
        // Owner answer 5 (July 8 2026): "if the other companion that it's
        // linked to is still active or existing then the memory should not be
        // deleted." Companions have no mirror column, so mirrorId is null and
        // no mirror writes may be planned.
        val plan = TargetTeardownPlanner.plan(
            targetId = "comp-a",
            owned = listOf(
                OwnedMemory("shared", otherOwnerIds = listOf("comp-b"), mirrorId = null),
                OwnedMemory("sole", otherOwnerIds = emptyList(), mirrorId = null)
            ),
            deleteMemories = true
        )
        assertEquals(listOf("sole"), plan.deleteMemoryIds)
        assertEquals(listOf("shared"), plan.unlinkMemoryIds)
        assertTrue(plan.mirrorReassignments.isEmpty())
    }

    @Test
    fun multipleSurvivingOwners_mirrorGetsOneOfThem() {
        val plan = TargetTeardownPlanner.plan(
            targetId = "campaign-c",
            owned = listOf(OwnedMemory("m1", listOf("campaign-a", "campaign-b"), mirrorId = "campaign-c")),
            deleteMemories = false
        )
        val reassigned = plan.mirrorReassignments["m1"]
        assertTrue(reassigned == "campaign-a" || reassigned == "campaign-b")
    }
}
