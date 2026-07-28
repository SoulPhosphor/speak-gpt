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

package org.teslasoft.assistant.preferences.memory.archivist

import android.content.Context
import org.json.JSONArray
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import java.time.Instant

/**
 * The ONE filing boundary for suggested memories (external-memory
 * counterplan §4, Step 1.5 foundation): every transport — the API
 * Archivist today, the computer suggestions import next — funnels its
 * proposals through [fileMemoryDrafts], so no route can bypass a check
 * another route applies. The checks, in order, for each draft:
 *
 *  1. exact duplicate against the whole library (any status);
 *  2. the owner's deliberately-narrow rejected-draft suppression, keyed
 *     to the rename-safe source chat id;
 *  3. card-placement suggestion resolution against EXISTING cards only;
 *  4. target name resolution against existing records only (no match →
 *     the draft arrives untargeted; the user assigns targets on review);
 *  5. provenance stamping (source chat name at filing time + chat id);
 *  6. insertion as a DRAFT with origin='archivist' — enforced again at
 *     the store layer; nothing takes effect without the user's approval.
 */
object DraftFiling {

    /** Where a batch of drafts came from — the provenance every transport
     *  must supply. [chatId] is the rename-safe anchor; [chatName] is the
     *  display name captured at filing time. */
    data class Source(
        val chatId: String,
        val chatName: String,
        val companionId: String?
    )

    /**
     * File [drafts] as Pending memory drafts. Appends created ids to
     * [collectedIds]; returns how many candidates were skipped as exact
     * duplicates of memories that already exist. A store insert failure
     * aborts the batch as [ArchivistFailure.SAVE_FAILED] (the caller's
     * conversation stays retryable).
     */
    fun fileMemoryDrafts(
        context: Context,
        store: MemoryStore,
        source: Source,
        drafts: List<ArchivistResponseParser.DraftMemory>,
        collectedIds: MutableList<String>,
        cardSuggestionsOn: Boolean
    ): Int {
        if (drafts.isEmpty()) return 0
        var duplicates = 0
        val now = Instant.now().toString()
        // Live cards for placement-suggestion resolution: name → (type, id).
        // Loaded once per batch; exact case-insensitive name match against
        // EXISTING cards only — an unknown card name just drops the
        // suggestion, never the memory, and nothing is ever created.
        val liveCards: List<Triple<String, String, String>> = if (cardSuggestionsOn) {
            buildList {
                store.getAllWorlds().filter { it.status == "active" }
                    .forEach { add(Triple(CardType.WORLD, it.worldId, it.name)) }
                store.getActiveCampaigns()
                    .forEach { add(Triple(CardType.CAMPAIGN, it.campaignId, it.name)) }
                store.getAllRoleplayCharacters().filter { it.status == "active" }
                    .forEach { add(Triple(CardType.RP_CHARACTER, it.roleplayCharacterId, it.name)) }
                store.getPartyMembers(includeArchived = false)
                    .forEach { add(Triple(CardType.PARTY_MEMBER, it.partyMemberId, it.name)) }
            }
        } else emptyList()
        for (d in drafts) {
            if (store.memoryExistsWithText(d.title, d.content)) { duplicates++; continue }
            // A draft the user deleted is a rejection (owner preference,
            // July 9 2026): the exact same draft from the same conversation
            // is not refiled on rerun. Deliberately narrow — different
            // wording or a different conversation files normally. Keyed by
            // chat ID (counterplan §4(c)) so a rename cannot defeat it.
            if (store.isDraftRejected(d.title, d.content, source.chatId)) {
                MemoryLog.log(context, "Archivist", "info",
                    "chat=${source.chatId}: previously rejected draft not refiled (\"${d.title}\")")
                continue
            }
            // Resolve a proposed placement (roleplay scopes only): the section
            // must be a real key for the matched card's type.
            var sugType: String? = null
            var sugId: String? = null
            var sugSection: String? = null
            if (cardSuggestionsOn && d.cardName != null && d.cardSection != null &&
                d.scope in setOf("world", "campaign", "rp_character")
            ) {
                val match = liveCards.firstOrNull { it.third.equals(d.cardName, ignoreCase = true) }
                if (match != null && d.cardSection in CardSections.sectionsFor(match.first)) {
                    sugType = match.first
                    sugId = match.second
                    sugSection = d.cardSection
                }
            }
            val record = MemoryRecord(
                memoryId = MemoryStore.newId("m-"),
                scope = d.scope,
                kind = d.kind,
                title = d.title,
                content = d.content,
                embeddingText = null,
                tagsJson = listToJson(d.tags),
                importance = d.importance,
                worldIds = resolveTarget(d, "world") { store.getAllWorlds().map { it.worldId to it.name } },
                roleplayCharacterIds = resolveTarget(d, "rp_character") {
                    store.getAllRoleplayCharacters().map { it.roleplayCharacterId to it.name }
                },
                campaignIds = resolveTarget(d, "campaign") { store.getCampaigns().map { it.campaignId to it.name } },
                projectIds = resolveTarget(d, "project") { store.getProjects().map { it.projectId to it.name } },
                protectionJson = null,
                modeHintsJson = "[]",
                provenanceSource = if (d.stated) "user_stated" else "inferred",
                provenanceConfidence = if (d.stated) "certain" else "tentative",
                provenanceNotedOn = now,
                // §14: the editor shows which chat a draft came from and when.
                provenanceContext = source.chatName,
                // Rename-safe source anchor (§4(c)): repointChat keeps this
                // current, so a rejection registered at deletion time always
                // matches the chat id a rerun looks up.
                sourceChatId = source.chatId,
                createdAt = now,
                updatedAt = null,
                status = "draft",
                supersedes = null,
                companionIds = if (d.scope == "companion" && source.companionId != null)
                    listOf(source.companionId) else emptyList(),
                entityRefs = emptyList(),
                changeLog = emptyList(),
                origin = "archivist",
                suggestedCardType = sugType,
                suggestedCardId = sugId,
                suggestedSection = sugSection
            )
            try {
                store.insertArchivistDraftMemory(record)
                collectedIds.add(record.memoryId)
            } catch (e: Exception) {
                MemoryLog.logAlways(context, "Archivist", "error", "draft insert failed: ${e.message}")
                throw TaggedArchivistException(ArchivistFailure.SAVE_FAILED, e)
            }
        }
        return duplicates
    }

    /** A proposed target NAME only ever links to a record that already exists
     *  (exact name match, case-insensitive). Suggestions never create
     *  worlds/campaigns/characters/projects. No match → the draft arrives
     *  untargeted and the user assigns targets in the editor before
     *  accepting. */
    private fun resolveTarget(
        d: ArchivistResponseParser.DraftMemory,
        scope: String,
        candidates: () -> List<Pair<String, String>>
    ): List<String> {
        if (d.scope != scope) return emptyList()
        val name = d.targetName ?: return emptyList()
        return candidates()
            .filter { it.second.equals(name, ignoreCase = true) }
            .map { it.first }
            .take(1)
    }

    private fun listToJson(items: List<String>): String {
        val arr = JSONArray()
        for (s in items) arr.put(s)
        return arr.toString()
    }
}
