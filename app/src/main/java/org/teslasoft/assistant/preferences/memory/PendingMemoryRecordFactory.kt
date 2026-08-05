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
 * Builds the ONE canonical Pending (draft) Associative Memory record from a
 * validated [MemoryCandidate] (canonical recovery plan Phase 2, item 8).
 *
 * Every filing origin — the API Memory Assistant, a validated computer-file
 * import, and manual creation — converges here after validation, so all three
 * produce the SAME canonical object for equivalent approved data. The pure
 * build step is separated from storage ([PendingMemoryFiler]) so the shared
 * shape is unit-testable on the JVM.
 *
 * What this factory deliberately does NOT put on the memory (item 8):
 *  - the API/computer transport origin — it is never stored on the memory and
 *    never displayed on the Pending card;
 *  - conversation policy, an Analysis Note, chat identity, or processing
 *    method — none of it is copied into the memory.
 *
 * The record carries only the approved memory fields needed for review and
 * later approval. Titles are retired (§3.1), so [MemoryRecord.title] is the
 * inert empty placeholder the legacy NOT NULL column requires, and the legacy
 * [MemoryRecord.kind] is derived from the Type id so the two can never disagree
 * (Phase 1 item 4). Pure Kotlin, unit tested (PendingMemoryRecordFactoryTest).
 */
object PendingMemoryRecordFactory {

    /**
     * Build a canonical draft [MemoryRecord] from [candidate]. [memoryId] and
     * [now] are injected (not generated here) so the pure build is deterministic
     * and testable; callers pass a fresh id and the current timestamp.
     */
    fun build(candidate: MemoryCandidate, memoryId: String, now: String): MemoryRecord {
        val companionIds: List<String>
        val worldIds: List<String>
        val campaignIds: List<String>
        val rpCharacterIds: List<String>
        val projectIds: List<String>
        when (candidate) {
            is MemoryCandidate.CompanionTargeted -> {
                companionIds = listOf(candidate.companionId)
                worldIds = emptyList()
                campaignIds = emptyList()
                rpCharacterIds = emptyList()
                projectIds = emptyList()
            }
            is MemoryCandidate.General -> {
                companionIds = emptyList()
                worldIds = candidate.worldIds
                campaignIds = candidate.campaignIds
                rpCharacterIds = candidate.roleplayCharacterIds
                projectIds = candidate.projectIds
            }
        }
        return MemoryRecord(
            memoryId = memoryId,
            scope = candidate.scope,
            // The Type id is the sole source of truth (Phase 2 review): a new
            // memory stores an inert empty legacy kind, never a value derived
            // from the Type. Nothing reads kind for behavior anymore.
            kind = "",
            typeId = candidate.typeId,
            title = "",
            content = candidate.content,
            embeddingText = null,
            tagsJson = tagsToJson(candidate.tags),
            importance = candidate.importance,
            worldIds = worldIds,
            roleplayCharacterIds = rpCharacterIds,
            campaignIds = campaignIds,
            projectIds = projectIds,
            protectionJson = null,
            modeHintsJson = "[]",
            // No permanent provenance on a canonical Pending memory (review
            // finding 1): no source/confidence (no user_stated/inferred/certain/
            // tentative), no noted-on date, no chat name (provenanceContext), and
            // no chat id (sourceChatId). The legacy columns stay, inert and null.
            provenanceSource = null,
            provenanceConfidence = null,
            provenanceNotedOn = null,
            provenanceContext = null,
            sourceChatId = null,
            createdAt = now,
            updatedAt = null,
            status = "draft",
            supersedes = null,
            companionIds = companionIds,
            entityRefs = emptyList(),
            changeLog = emptyList(),
            origin = candidate.origin,
            // No analyzer-created card-placement metadata on a canonical candidate
            // (Phase 2 review): the legacy columns are stored null.
            suggestedCardType = null,
            suggestedCardId = null,
            suggestedSection = null
        )
    }

    /** Minimal JSON array encoding for tags — pure, no org.json (an Android
     *  stub on the JVM). Matches the store's stringsToJson escaping. */
    private fun tagsToJson(tags: List<String>): String {
        if (tags.isEmpty()) return "[]"
        return tags.joinToString(prefix = "[", postfix = "]") { s ->
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        }
    }
}
