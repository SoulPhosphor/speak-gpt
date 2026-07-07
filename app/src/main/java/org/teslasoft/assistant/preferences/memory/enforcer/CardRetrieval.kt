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

import org.teslasoft.assistant.preferences.lorebook.LoreBookTriggerMatcher
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.RpTagRecord

/**
 * Zone 2 card retrieval (3.6d, roleplay_cards_and_tags_spec §1/§3/§10):
 * TRIGGER-MATCHED, never embedded — entry names and SECTION names fire
 * against the latest user message via the lorebook matcher (whole-word,
 * case-insensitive, light suffix folding); tag names with auto-trigger ON
 * fire their tagged entries; entries sharing a fired entry's tags become
 * one-hop pull-along candidates (browse-only tags count here and for the
 * "connected to:" line — the per-tag switch silences ONLY message-text
 * matching). Group headers never trigger (they aren't in the label map).
 * Pure Kotlin, unit-tested; the Enforcer feeds it store data.
 *
 * The section labels here are protocol text the model may be told about,
 * matching the spec §6 names — hardcoded like the rest of the assembler's
 * wording, not localized UI copy.
 */
object CardRetrieval {

    /** Section key -> the spec §6 label. The label is the section's trigger
     *  word ("sections fire whole"); group headers are deliberately absent. */
    val SECTION_LABELS: Map<String, String> = mapOf(
        CardSections.ABILITIES to "Abilities",
        CardSections.INVENTORY to "Inventory",
        CardSections.RELATIONSHIPS to "Relationships",
        CardSections.TRAITS to "Traits",
        CardSections.BACKSTORY to "Backstory",
        CardSections.LANGUAGES to "Languages",
        CardSections.REGIONS to "Regions",
        CardSections.SETTLEMENTS to "Settlements",
        CardSections.POINTS_OF_INTEREST to "Points of Interest",
        CardSections.RACES_SPECIES to "Races / Species",
        CardSections.LANGUAGES_SCRIPTS to "Languages & Scripts",
        CardSections.HISTORICAL_EVENTS to "Historical Events",
        CardSections.ARCANE_KNOWLEDGE to "Arcane Knowledge",
        CardSections.ORGANIZATIONS_GUILDS to "Organizations & Guilds",
        CardSections.BANDS_THREATS to "Bands & Threats",
        CardSections.DEITIES to "Deities",
        CardSections.FAITHS to "Faiths",
        CardSections.SACRED_ARTIFACTS to "Sacred Artifacts",
        CardSections.HISTORICAL_FIGURES to "Historical Figures",
        CardSections.AUTHORITY_FIGURES to "Authority Figures",
        CardSections.SERVICE_NPCS to "Service NPCs",
        CardSections.ALLIES to "Allies",
        CardSections.ANTAGONISTS to "Antagonists",
        CardSections.CAMPAIGN_CAST to "Campaign Cast",
        CardSections.CAMPAIGN_LOCATIONS to "Campaign Locations",
        CardSections.PLOT_LEDGER to "Plot Ledger",
        CardSections.RELIQUARY to "Reliquary",
        CardSections.NOTES to "Notes"
    )

    data class Fired(val entry: CardEntryRecord, val reason: String)

    data class Result(val direct: List<Fired>, val pullAlong: List<Fired>)

    /**
     * Fires entries against [message]. Deterministic: candidates are walked
     * in (cardType, section, name, id) order, so the same store state and
     * message always produce the same firing order — the assembler's
     * byte-stability contract depends on it.
     *
     * The card-entry schema has no per-entry importance field, so the spec's
     * "importance-ranked" pull-along order falls back to the same
     * deterministic ordering (weights are implementation detail per §12.3;
     * revisit if entries ever grow an importance column).
     */
    fun fire(
        message: String,
        entries: List<CardEntryRecord>,
        tagsById: Map<String, RpTagRecord>,
        entryTags: Map<String, List<String>>
    ): Result {
        if (message.isBlank() || entries.isEmpty()) return Result(emptyList(), emptyList())

        val sorted = entries.sortedWith(
            compareBy({ it.cardType }, { it.section }, { it.name.lowercase() }, { it.entryId })
        )

        // Section labels only need matching once per message.
        val firedSections = SECTION_LABELS.filterValues { label ->
            LoreBookTriggerMatcher.matches(message, label)
        }.keys

        // Tag names with auto-trigger ON that appear in the message.
        val firedTagIds = tagsById.values
            .filter { it.autoTrigger && LoreBookTriggerMatcher.matches(message, it.name) }
            .map { it.tagId }
            .toSet()

        val direct = ArrayList<Fired>()
        val directIds = HashSet<String>()
        for (e in sorted) {
            val reason = when {
                LoreBookTriggerMatcher.matches(message, e.name) -> "name"
                e.section in firedSections -> "section \"${SECTION_LABELS[e.section]}\""
                else -> entryTags[e.entryId]?.firstOrNull { it in firedTagIds }
                    ?.let { "tag \"${tagsById[it]?.name}\"" }
            }
            if (reason != null) {
                direct.add(Fired(e, reason))
                directIds.add(e.entryId)
            }
        }
        if (direct.isEmpty()) return Result(emptyList(), emptyList())

        // One hop, no chaining: only tags carried by DIRECTLY fired entries
        // pull siblings along — browse-only tags included (spec §3).
        val hopTagIds = direct.flatMap { entryTags[it.entry.entryId].orEmpty() }.toSet()
        val pullAlong = ArrayList<Fired>()
        if (hopTagIds.isNotEmpty()) {
            for (e in sorted) {
                if (e.entryId in directIds) continue
                val shared = entryTags[e.entryId]?.firstOrNull { it in hopTagIds } ?: continue
                pullAlong.add(Fired(e, "pull-along via tag \"${tagsById[shared]?.name}\""))
            }
        }
        return Result(direct, pullAlong)
    }

    /**
     * Composes the entry's rendered body from its per-section fields, in a
     * fixed order. Geography entries carry their FULL parent chain (§6c —
     * "retrieval never guesses where something is"), walked via [entriesById]
     * and cycle-safe.
     */
    fun composeBody(entry: CardEntryRecord, entriesById: Map<String, CardEntryRecord>): String {
        val parts = ArrayList<String>()
        entry.entryKind?.takeIf { it.isNotBlank() }?.let { parts.add(it.replace('_', ' ')) }
        entry.quantity?.let { parts.add("×$it") }
        parentChain(entry, entriesById).takeIf { it.isNotEmpty() }?.let {
            parts.add("in " + it.joinToString(" → "))
        }
        entry.castIdentity?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        entry.castDisposition?.takeIf { it.isNotBlank() }?.let { parts.add("disposition: ${it.trim()}") }
        entry.castStatus?.takeIf { it.isNotBlank() }?.let { parts.add("status: ${it.trim()}") }
        entry.locationCondition?.takeIf { it.isNotBlank() }?.let { parts.add("condition: ${it.trim()}") }
        entry.locationChanges?.takeIf { it.isNotBlank() }?.let { parts.add("changed: ${it.trim()}") }
        entry.holder?.takeIf { it.isNotBlank() }?.let { parts.add("held by: ${it.trim()}") }
        entry.significance?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        entry.description?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        return parts.joinToString("; ")
    }

    /** Names from the entry up its parent chain (nearest first), cycle-safe. */
    fun parentChain(entry: CardEntryRecord, entriesById: Map<String, CardEntryRecord>): List<String> {
        val chain = ArrayList<String>()
        val seen = HashSet<String>()
        var cursor = entry.parentEntryId
        while (cursor != null && seen.add(cursor)) {
            val parent = entriesById[cursor] ?: break
            chain.add(parent.name)
            cursor = parent.parentEntryId
        }
        return chain
    }

    /** The §3 "connected to:" names: tag-sharing siblings (any tag mode),
     *  deduped, sorted, excluding the entry itself. */
    fun connectedNames(
        entry: CardEntryRecord,
        entries: List<CardEntryRecord>,
        entryTags: Map<String, List<String>>
    ): List<String> {
        val own = entryTags[entry.entryId]?.toSet().orEmpty()
        if (own.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { it.entryId != entry.entryId }
            .filter { e -> entryTags[e.entryId]?.any { it in own } == true }
            .map { it.name }
            .distinct()
            .sorted()
            .toList()
    }
}
