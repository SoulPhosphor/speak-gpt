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

/**
 * Decides what happens to a target's memories when a world, campaign,
 * roleplay character, or project card is deleted — the owner's ruling of
 * July 8 2026 (`Memory System/roleplay_memory_deletion_fix.md`):
 *
 * - The JOIN tables (memory_worlds / memory_campaigns /
 *   memory_roleplay_characters / memory_projects) are the source of truth
 *   for ownership — never the single mirror column on `memories`.
 * - "Also delete this card's memories" deletes ONLY memories whose sole
 *   owner (of that target type) is the deleted card. A memory still linked
 *   to another valid card is never hard-deleted — its link to the deleted
 *   card is removed and it survives.
 * - A kept memory whose mirror column points at the deleted target gets the
 *   mirror REASSIGNED to one of its remaining owners (or cleared when none
 *   remain) — never left pointing at a dead card, and never nulled while a
 *   live owner exists.
 *
 * This is pure decision logic so the owner's required test cases can run as
 * plain unit tests (the store itself is SQLCipher and has no JVM harness);
 * MemoryStore executes the returned plan inside the teardown transaction.
 */
object TargetTeardownPlanner {

    /**
     * One memory the join table says the target owns.
     *
     * @param otherOwnerIds other targets OF THE SAME TYPE linked to this
     *   memory via the join table (sorted; the first becomes the new mirror
     *   when reassignment is needed).
     * @param mirrorId the memory's current mirror-column value for this
     *   target type (may name a different target than the one being deleted,
     *   or be null — the mirror is not trusted for ownership).
     * @param hasCharacterLink whether the memory has ANY row in
     *   memory_roleplay_characters — the join-based meaning of "a character's
     *   memory too" for deleteWorld's keepCharacterMemories option (fix spec
     *   step 5; the old code wrongly read the roleplay_character_id mirror).
     */
    data class OwnedMemory(
        val memoryId: String,
        val otherOwnerIds: List<String>,
        val mirrorId: String?,
        val hasCharacterLink: Boolean = false
    )

    /**
     * @param deleteMemoryIds memories to hard-delete (tombstoned; their join
     *   rows across all tables cascade with them).
     * @param unlinkMemoryIds kept memories whose single join row to the
     *   deleted target must be removed.
     * @param mirrorReassignments kept memories whose mirror pointed at the
     *   deleted target: memoryId → replacement owner id, or null to clear
     *   when no owner of that type remains.
     */
    data class Plan(
        val deleteMemoryIds: List<String>,
        val unlinkMemoryIds: List<String>,
        val mirrorReassignments: Map<String, String?>
    )

    fun plan(
        targetId: String,
        owned: List<OwnedMemory>,
        deleteMemories: Boolean,
        keepCharacterMemories: Boolean = false
    ): Plan {
        val deletes = ArrayList<String>()
        val unlinks = ArrayList<String>()
        val mirrors = LinkedHashMap<String, String?>()
        for (m in owned) {
            val soleOwner = m.otherOwnerIds.isEmpty()
            val keptForCharacter = keepCharacterMemories && m.hasCharacterLink
            if (deleteMemories && soleOwner && !keptForCharacter) {
                deletes.add(m.memoryId)
            } else {
                unlinks.add(m.memoryId)
                if (m.mirrorId == targetId) {
                    mirrors[m.memoryId] = m.otherOwnerIds.firstOrNull()
                }
            }
        }
        return Plan(deletes, unlinks, mirrors)
    }
}
