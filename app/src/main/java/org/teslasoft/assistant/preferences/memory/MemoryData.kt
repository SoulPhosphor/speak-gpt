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
 * In-memory model of the companion memory store (schema v1.11).
 *
 * These classes mirror the SQLite shape in `Memory System/sqlite_table_plan.md`:
 * fields the tables store as JSON text columns are kept here as raw JSON
 * strings (arrays/objects in schema shape), NOT expanded into nested classes —
 * the store persists them verbatim and only later phases (enforcer, Archivist)
 * reach inside them. Keeping them opaque here means the codec, the store, and
 * the export format can never drift apart on fields the app doesn't yet
 * interpret. No Android imports: this file and MemorySeedCodec are pure Kotlin
 * so the JSON round-trip is unit-testable on the JVM.
 */

data class OwnerProfile(
    val portrait: String,
    val standingContext: String?,
    val updatedAt: String?
)

data class NameHistoryEntry(
    val name: String,
    val effectiveFrom: String,
    val effectiveUntil: String?
)

data class CompanionRecord(
    val companionId: String,
    val currentName: String,
    val essence: String,
    val relationshipNotes: String?,
    val memoryParticipation: String,      // full | global_only | none
    val hardLimitsJson: String,           // JSON array of strings
    val appCharacterId: String?,          // Hash.hash(persona label); may dangle after persona delete
    val mirrorText: String?,              // one-way app -> store snapshot for drift detection
    val mirrorSyncedAt: String?,
    val modelAdaptationsJson: String,     // JSON array of {model_tag, notes}
    val createdAt: String,
    val status: String,                   // draft | active | resting | retired
    val nameHistory: List<NameHistoryEntry>,
    /** 'user' (default), 'seed' (bundled/example template), 'archivist' (future
     *  proposals). Machine-readable record source — 'seed' is gated out of
     *  retrieval and targeted by the purge (seed-safety audit, July 2026). */
    val origin: String = "user"
)

data class EntityRecord(
    val entityId: String,
    val kind: String,
    val name: String,
    val aliasesJson: String,              // JSON array of strings
    val summary: String,
    val status: String?,
    val importance: Int,
    val lastTouched: String?,
    val origin: String = "user"
)

data class WorldRecord(
    val worldId: String,
    val name: String,
    val premise: String,
    val rules: String?,
    val companionIdsJson: String,         // JSON array of companion ids
    val status: String,                   // active | dormant | ended
    val createdAt: String?
)

data class UserPersonaRecord(
    val personaId: String,
    val name: String,
    val presentation: String,
    val status: String,                   // active | archived
    val createdAt: String?
)

data class RoleplayCharacterRecord(
    val roleplayCharacterId: String,
    val name: String,
    val playedBy: String,                 // 'user' or a companion_id
    val description: String,              // user-owned definition
    val arc: String?,                     // Archivist-maintained story-so-far
    val worldsPlayedJson: String,         // JSON array of world ids
    val status: String,                   // active | archived
    val createdAt: String?
)

/**
 * A campaign: one roleplay continuity (playthrough) inside a world. The same
 * world can host several campaigns and the same character can live several
 * separate existences in it, so a campaign is the continuity bucket that keeps
 * their game-state facts from bleeding together (integration plan 📌 amendment).
 * `storySoFar` is Archivist-maintained (proposal-bound, like a roleplay arc).
 */
data class CampaignRecord(
    val campaignId: String,
    val name: String,                     // the user-facing continuity name
    val worldId: String?,
    val roleplayCharacterId: String?,     // the user's character in this campaign
    val companionId: String?,             // the DM/GM companion running it
    val status: String,                   // active | paused | ended | archived
    val storySoFar: String?,              // Archivist-maintained summary
    val createdAt: String?
)

/**
 * A project (owner_approved_rules §4): a plain user-defined named bucket a
 * memory can be scoped to. Nothing more elaborate — name it, rename it,
 * archive it.
 */
data class ProjectRecord(
    val projectId: String,
    val name: String,
    val status: String,                   // active | archived
    val createdAt: String?,
    val updatedAt: String?
)

data class ChangeLogEntry(
    val at: String,
    val actor: String,                    // user | archivist | companion | system
    val action: String,
    val note: String?,
    val priorStateJson: String?           // device-local undo snapshot; not exported
)

data class MemoryRecord(
    val memoryId: String,
    // Primary scope category (owner_approved_rules §1/§2): global | real_life |
    // companion | project | world | campaign | rp_character. Targets ride the
    // link columns below (companion via memory_companions).
    val scope: String,
    val kind: String,                     // Type: fact|preference|event|status|instruction|lore
    val title: String,
    val content: String,
    val embeddingText: String?,
    val tagsJson: String,                 // JSON array of strings
    val importance: Int,
    /** Named target scopes are multi-select (owner_approved_rules §2): a memory
     *  may belong to several worlds/campaigns/RP characters/projects at once
     *  (join-table backed, like [companionIds]). Retrieval eligibility reads
     *  these join tables too (Stage 3.1); the store still mirrors the first of
     *  each into the legacy single columns for the teardown paths — that
     *  mirror is internal and not exposed. */
    val worldIds: List<String>,
    val roleplayCharacterIds: List<String>,
    val campaignIds: List<String>,
    val projectIds: List<String>,
    val protectionJson: String?,          // schema protection object, verbatim
    val modeHintsJson: String,            // JSON array of mode ids
    val provenanceSource: String?,
    val provenanceConfidence: String?,
    val provenanceNotedOn: String?,
    val provenanceContext: String?,
    val createdAt: String,
    val updatedAt: String?,
    val status: String,                   // draft | active | archived | superseded
    val supersedes: String?,
    val companionIds: List<String>,       // memory_companions join rows
    val entityRefs: List<String>,         // memory_entities join rows
    val changeLog: List<ChangeLogEntry>,
    val origin: String = "user"
)

data class ModeRecord(
    val modeId: String,
    val name: String,
    val purpose: String?,
    val signalsJson: String,              // JSON array
    val respondJson: String,              // JSON array
    val avoidJson: String,                // JSON array
    val transitionNote: String?,
    val overridesJson: String,            // JSON array of mode ids
    val scope: String,                    // 'global' or 'companion'
    val companionIdsJson: String,         // JSON array
    val origin: String = "user"
)

data class DirectiveRecord(
    val directiveId: String,
    val text: String,
    val rationale: String?,
    val appliesToJson: String,            // JSON array; empty = all companions
    val priority: Int,
    val origin: String = "user"
)

data class ArchivistSettingsRecord(
    val runTrigger: String,               // schema calls this 'trigger' (SQL keyword)
    val harvestGenerosity: String,
    val autonomyJson: String,             // JSON object of category -> auto|propose
    val notes: String?
)

data class ProposalRecord(
    val proposalId: String,
    val targetType: String,
    val targetId: String?,
    val summary: String,
    val proposedChangeJson: String?,      // schema 'proposed_change', any shape, verbatim
    val rationale: String?,
    val status: String,                   // pending | accepted | rejected
    val createdAt: String,
    val resolvedAt: String?
)

/**
 * Transcripts are storage-level (not part of the JSON schema's record types)
 * but travel in exports per app_adaptation_notes 11c — they are the raw
 * material the whole store can be re-derived from. chat_id and user_persona_id
 * are app-side additions to the table plan: chat_id ties the row back to the
 * app's persistent chat (the watermark model in the integration plan), and the
 * Archivist prompt expects persona_id among its inputs even though the table
 * plan omitted a column for it.
 */
data class TranscriptRecord(
    val transcriptId: String,
    val chatId: String?,
    val companionId: String?,             // nullable: chats can run with no persona selected
    val worldId: String?,
    val roleplayCharacterId: String?,
    val userPersonaId: String?,
    val source: String,                   // live | imported
    val startedAt: String?,
    val endedAt: String?,
    val content: String,
    val modelTag: String?,
    val quickSettingsJson: String?,
    val reviewStatus: String,             // pending | processed | excluded
    val processedAt: String?
)

data class MemoryStoreData(
    val schemaVersion: String,
    val ownerProfile: OwnerProfile?,
    val companions: List<CompanionRecord>,
    val entities: List<EntityRecord>,
    val memories: List<MemoryRecord>,
    val modes: List<ModeRecord>,
    val directives: List<DirectiveRecord>,
    val worlds: List<WorldRecord>,
    val userPersonas: List<UserPersonaRecord>,
    val roleplayCharacters: List<RoleplayCharacterRecord>,
    val archivistSettings: ArchivistSettingsRecord?,
    val proposals: List<ProposalRecord>,
    val retrievalPolicyJson: String?,     // whole retrieval_policy object, verbatim
    val transcripts: List<TranscriptRecord>,
    val campaigns: List<CampaignRecord> = emptyList(),
    val projects: List<ProjectRecord> = emptyList()
)

/**
 * The conversation context every retrieval is scoped to (Stage 3.1, rules
 * §1/§3/§4 rev 3). Built by the enforcer from the chat's Quick Settings
 * selections; consumed by [MemoryStore.activeMemoriesForScope] — the single
 * eligibility gate — so scope isolation stays enforced IN THE QUERY.
 *
 * The great split (law 1) is [isRoleplay]: ANY roleplay selection (world,
 * campaign, or RP character) puts the chat in fiction, where real-life and
 * project memories are blocked and companion memories need an explicit door
 * ([allowCompanionInRoleplay] — the narrator/GM match or the global toggle).
 */
data class RetrievalScope(
    /** The chat's active companion, already participation-filtered by the
     *  caller (null = no companion branch at all). */
    val companionId: String?,
    val worldId: String?,
    val campaignId: String?,
    val roleplayCharacterId: String?,
    val allowCompanionInRoleplay: Boolean = false
) {
    val isRoleplay: Boolean
        get() = worldId != null || campaignId != null || roleplayCharacterId != null

    companion object {
        /** Scope-less context (debug search, index tooling): ordinary chat,
         *  no companion — global + real-life + project memories only. */
        val NONE = RetrievalScope(null, null, null, null)
    }
}

/**
 * A memory as the librarian sees it: enough to embed, score, and render a
 * retrieval result, without loading joins/change-log the way [MemoryRecord]
 * does. `embeddingText` is the condensed text to embed when present, else the
 * content.
 */
data class RetrievableMemory(
    val memoryId: String,
    val scope: String,
    val title: String,
    val content: String,
    val embeddingText: String?,
    val importance: Int,
    val createdAt: String,
    val worldId: String?,
    val provenanceConfidence: String?,
    // Phase 4 (enforcer): a protected memory must be structurally inseparable
    // from its handling, so the retrieval row carries the protection object and
    // provenance source with it — the assembler never has to re-query (and can
    // therefore never "forget" the HANDLE WITH CARE line).
    val protectionJson: String? = null,
    val provenanceSource: String? = null,
    // Stage 3: the Type drives the render (Instruction memories become handling
    // rules) and the tags feed soft ranking hints (§6 — never gatekeepers).
    val kind: String = "fact",
    val tagsJson: String = "[]"
) {
    fun textToEmbed(): String = embeddingText?.takeIf { it.isNotBlank() } ?: content
}

/** A scored retrieval hit for the debug view and (later) the enforcer packet. */
data class ScoredMemory(
    val memory: RetrievableMemory,
    val similarity: Float,
    val score: Float
)

/** Per-record-type added/skipped tallies from an import, for the user-facing summary. */
data class ImportReport(
    val added: LinkedHashMap<String, Int> = LinkedHashMap(),
    val skipped: LinkedHashMap<String, Int> = LinkedHashMap()
) {
    fun addAdded(type: String, n: Int = 1) { added[type] = (added[type] ?: 0) + n }
    fun addSkipped(type: String, n: Int = 1) { skipped[type] = (skipped[type] ?: 0) + n }

    fun summary(): String {
        val a = added.filterValues { it > 0 }.entries.joinToString(", ") { "${it.value} ${it.key}" }
        val s = skipped.filterValues { it > 0 }.entries.joinToString(", ") { "${it.value} ${it.key}" }
        val parts = ArrayList<String>()
        if (a.isNotEmpty()) parts.add("Added: $a")
        if (s.isNotEmpty()) parts.add("Skipped (already present): $s")
        return if (parts.isEmpty()) "Nothing to import." else parts.joinToString(". ")
    }
}
