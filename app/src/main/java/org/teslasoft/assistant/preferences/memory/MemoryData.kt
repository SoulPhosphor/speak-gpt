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
    // `premise` and `rules` are DORMANT pre-card columns (owner ruling July 7
    // 2026, spec §8a: the new cards never show, map, or migrate the old
    // free-text blocks). The world core (spec §6c, Zone 1) lives in the
    // fresh v7/v8 columns below: cosmology + premiseVibe + magicRules.
    val premise: String,
    val rules: String?,
    val cosmology: String? = null,
    val premiseVibe: String? = null,
    val magicRules: String? = null,
    val companionIdsJson: String,         // JSON array of companion ids
    val status: String,                   // active | dormant | ended | archived (v7)
    val createdAt: String?
)

data class UserPersonaRecord(
    val personaId: String,
    val name: String,
    val presentation: String,
    val status: String,                   // active | archived
    val createdAt: String?,
    // Profile Images (DB v15): bare hash of the assigned image, or null for
    // none. The catalog/files live in profile_images.db; this only references.
    val imageRef: String? = null
)

data class RoleplayCharacterRecord(
    val roleplayCharacterId: String,
    val name: String,
    val playedBy: String,                 // 'user' or a companion_id
    val description: String,              // pre-3.6 free-text definition (kept; the card fields below supersede it)
    val arc: String?,                     // Archivist-maintained story-so-far
    val worldsPlayedJson: String,         // JSON array of world ids
    val status: String,                   // active | archived
    val createdAt: String?,
    // User RP-character card Zone 1 (roleplay_cards_and_tags_spec §6a, DB v7):
    // the always-injected core. Multi-line, no length caps (spec §6 ruling).
    val species: String? = null,
    val charClass: String? = null,        // column `char_class`; UI label "Class"
    val corePersonality: String? = null,
    val physicalDescription: String? = null,
    val goalsDrives: String? = null,
    // Profile Images (DB v15): bare hash of the assigned image, or null for
    // none. The catalog/files live in profile_images.db; this only references.
    val imageRef: String? = null
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
    val storySoFar: String?,              // pre-3.6 free-text summary (kept; the Plot Ledger card section supersedes it)
    val createdAt: String?,
    // Campaign card Zone 1 (roleplay_cards_and_tags_spec §6d, DB v7): "the
    // bookmark" — Quest Anchor (main objective + optional side-objective
    // lines, one multi-line field) and Active Scene (location + condition).
    // User-maintained, session-end updates only (no-mid-conversation-writes
    // law); never written by any automatic process.
    val questAnchor: String? = null,
    val activeScene: String? = null,
    /** Linked party members (spec §4: campaigns LINK party members — join,
     *  not ownership). Backed by campaign_party_members; carried on the
     *  record for export/import only. [MemoryStore.upsertCampaign] does NOT
     *  write this list (so pre-3.6 save paths can't wipe links) — use the
     *  dedicated link/unlink methods. */
    val partyMemberIds: List<String> = emptyList()
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

/* -------------------------------------------------------------------------
 * Roleplay cards + tags (Stage 3.6a, roleplay_cards_and_tags_spec.md).
 * Card content lives in card-owned tables, NEVER in memories rows; retrieval
 * over it is trigger-matched, never embedded (the embeddings index stays
 * memories-only). Nothing here ships pre-populated — fresh means empty.
 * ------------------------------------------------------------------------- */

/** Card types that own Zone 2 entries and can carry roleplay tags. */
object CardType {
    const val RP_CHARACTER = "rp_character"   // user roleplay character (spec §6a)
    const val PARTY_MEMBER = "party_member"   // NPC party member (spec §6b)
    const val WORLD = "world"                 // spec §6c
    const val CAMPAIGN = "campaign"           // spec §6d
}

/**
 * Canonical Zone 2 section keys (roleplay_cards_and_tags_spec §6). These are
 * storage keys, not labels — the user-facing section names come from
 * strings.xml in the card editors (3.6b) and must match the spec word for
 * word. Section names are the trigger units; the world card's GROUP headers
 * (Geography, Species & Culture, History & Lore, Organized Groups,
 * Religions & Pantheons, Notable NPCs) are visual organization only and are
 * mapped in UI code, never stored and never triggering (owner ruling §6c).
 */
object CardSections {
    // User RP-character + NPC party-member cards (§6a/§6b — same six).
    const val ABILITIES = "abilities"                    // Type: innate|trained|class_feature|spell|other
    const val INVENTORY = "inventory"                    // Type: mundane|magical|quest|weapon|armor|other; quantity required
    const val RELATIONSHIPS = "relationships"            // Relationship: ally|enemy|family|mentor|rival|member|other
    const val TRAITS = "traits"                          // Type: fear|like|dislike; name is the trigger word
    const val BACKSTORY = "backstory"                    // Title (required) + Description (required)
    const val LANGUAGES = "languages"

    // World card (§6c). Geography is parent-chained: settlements carry a
    // region parent, points of interest a settlement-or-region parent.
    const val REGIONS = "regions"
    const val SETTLEMENTS = "settlements"
    const val POINTS_OF_INTEREST = "points_of_interest"
    const val RACES_SPECIES = "races_species"
    const val LANGUAGES_SCRIPTS = "languages_scripts"
    const val HISTORICAL_EVENTS = "historical_events"
    const val ARCANE_KNOWLEDGE = "arcane_knowledge"
    const val ORGANIZATIONS_GUILDS = "organizations_guilds"
    const val BANDS_THREATS = "bands_threats"
    const val DEITIES = "deities"
    const val FAITHS = "faiths"
    const val SACRED_ARTIFACTS = "sacred_artifacts"
    const val HISTORICAL_FIGURES = "historical_figures"
    const val AUTHORITY_FIGURES = "authority_figures"
    const val SERVICE_NPCS = "service_npcs"
    const val ALLIES = "allies"
    const val ANTAGONISTS = "antagonists"

    // Campaign card (§6d).
    const val CAMPAIGN_CAST = "campaign_cast"            // overlay (worldEntryId set) or campaign-native
    const val CAMPAIGN_LOCATIONS = "campaign_locations"  // scene-state overlays over world geography
    const val PLOT_LEDGER = "plot_ledger"                // title is the trigger; recency-boosted
    const val RELIQUARY = "reliquary"                    // plot items: holder + narrative significance
    const val NOTES = "notes"                            // freeform don't-forget entries

    val CHARACTER_SECTIONS = listOf(ABILITIES, INVENTORY, RELATIONSHIPS, TRAITS, BACKSTORY, LANGUAGES)
    val WORLD_SECTIONS = listOf(
        REGIONS, SETTLEMENTS, POINTS_OF_INTEREST, RACES_SPECIES, LANGUAGES_SCRIPTS,
        HISTORICAL_EVENTS, ARCANE_KNOWLEDGE, ORGANIZATIONS_GUILDS, BANDS_THREATS,
        DEITIES, FAITHS, SACRED_ARTIFACTS, HISTORICAL_FIGURES, AUTHORITY_FIGURES,
        SERVICE_NPCS, ALLIES, ANTAGONISTS
    )
    val CAMPAIGN_SECTIONS = listOf(CAMPAIGN_CAST, CAMPAIGN_LOCATIONS, PLOT_LEDGER, RELIQUARY, NOTES)

    fun sectionsFor(cardType: String): List<String> = when (cardType) {
        CardType.RP_CHARACTER, CardType.PARTY_MEMBER -> CHARACTER_SECTIONS
        CardType.WORLD -> WORLD_SECTIONS
        CardType.CAMPAIGN -> CAMPAIGN_SECTIONS
        else -> emptyList()
    }
}

/**
 * An NPC party member (spec §4/§6b): a top-level roster card, linked into
 * campaigns via campaign_party_members (join, not ownership — an NPC travels
 * between campaigns without rebuild). Structurally the user character card
 * plus Speech Style and the four-state fiction Status; leanness is the
 * user's choice, never a cap. `status` gates Zone 1 injection (3.6d):
 * alive/incapacitated inject the full core, dead/enemy drop to the generated
 * campaign roster line. Death is a status change, NEVER a delete (§4).
 * `archived` is the separate card-lifecycle flag for the §5 Archive section.
 */
data class PartyMemberRecord(
    val partyMemberId: String,
    val name: String,
    val species: String? = null,
    val charClass: String? = null,
    val corePersonality: String? = null,
    val physicalDescription: String? = null,
    val goalsDrives: String? = null,
    val speechStyle: String? = null,      // NPC-only Zone 1 field; empty = nothing injected
    val status: String = "alive",         // alive | incapacitated | dead | enemy
    val archived: Boolean = false,
    val createdAt: String,
    val updatedAt: String? = null
)

/**
 * One Zone 2 card entry (spec §6): a named, individually-retrievable row in
 * a card's section. One polymorphic table serves all four card types — the
 * retrieval machinery is section-agnostic (named entries in containers).
 * Only the columns a section defines are used; the rest stay null:
 *  - entryKind: the per-section Type/Relationship dropdown value (§6a/§6b)
 *  - quantity: Inventory only (required there, enforced in the editor)
 *  - parentEntryId: geography parent chain (§6c) — a settlement's region, a
 *    point of interest's settlement-or-region
 *  - worldEntryId: campaign overlay link (§6d) — cast/location entries that
 *    overlay a world card entry (the world holds the timeless definition,
 *    the campaign holds what this story did to it)
 *  - partyMemberId: the §6c promotion pointer — a world NPC entry that
 *    graduated to a party-member card points at it and stays lightweight
 *    lore (the card is the source of truth, never two competing versions)
 *  - holder / significance: Reliquary (§6d)
 *  - castIdentity / castDisposition / castStatus: Campaign Cast (§6d) —
 *    identity is the one-phrase line on campaign-native NPCs
 *  - locationCondition / locationChanges: Campaign Locations (§6d)
 * The reference columns are deliberately soft (no FK): §5 rules that
 * surviving references to a gone card render "(archived card)" /
 * "(deleted card)" instead of vanishing, so a dangling id plus its
 * deleted_ids tombstone is the intended representation, not corruption.
 */
data class CardEntryRecord(
    val entryId: String,
    val cardType: String,                 // CardType value
    val cardId: String,
    val section: String,                  // CardSections key
    val name: String,                     // the entry name/title — the trigger word
    val description: String? = null,
    val entryKind: String? = null,
    val quantity: Int? = null,
    val parentEntryId: String? = null,
    val worldEntryId: String? = null,
    val partyMemberId: String? = null,
    val holder: String? = null,
    val significance: String? = null,
    val castIdentity: String? = null,
    val castDisposition: String? = null,
    val castStatus: String? = null,
    val locationCondition: String? = null,
    val locationChanges: String? = null,
    val createdAt: String,
    val updatedAt: String? = null
)

/** Roleplay tag-link target types (spec §3): the polymorphic link table
 *  reaches card entries, whole cards, and roleplay-scoped memories. */
object RpTagTargetType {
    const val CARD_ENTRY = "card_entry"
    const val RP_CHARACTER = CardType.RP_CHARACTER
    const val PARTY_MEMBER = CardType.PARTY_MEMBER
    const val WORLD = CardType.WORLD
    const val CAMPAIGN = CardType.CAMPAIGN
    const val MEMORY = "memory"

    val ALL = setOf(CARD_ENTRY, RP_CHARACTER, PARTY_MEMBER, WORLD, CAMPAIGN, MEMORY)
}

/**
 * A roleplay-realm tag (spec §3): ONE shared pool across the whole roleplay
 * module, and ONLY the roleplay module — the REALM WALL is structural.
 * Real-life memory tags live in memories.tags_json and never enter these
 * tables; identical words on the two sides never link. `autoTrigger` is the
 * per-tag switch (default ON): OFF turns the tag browse/organize-only —
 * message-text matching stops, but the human tag view, the "connected to:"
 * line and one-hop pull-along all keep working (owner ruling July 7). The
 * app NEVER auto-flips it. No starter tags ship, ever.
 */
data class RpTagRecord(
    val tagId: String,
    val name: String,
    val autoTrigger: Boolean = true,
    val createdAt: String? = null,
    /** (targetType, targetId) links — carried for export/import; live link
     *  edits go through the store's link/unlink methods. */
    val targets: List<Pair<String, String>> = emptyList()
)

/* -------------------------------------------------------------------------
 * Model rules (Stage 4, owner_approved_rules §11, Revision 5): user-written
 * patches for a specific AI model's habits. The MODEL STRING is the primary
 * identity — there are no profiles/groups. Each rule carries its own list of
 * model strings it applies to (a family string like "glm-5" matches every
 * snapshot; endpoints/provider prefixes are irrelevant — the same model is
 * the same model from any provider). Tags organize rules for the human
 * (tap a tag → every rule that carries it), a separate pool that never
 * decides what injects — the model strings do that. Injection is automatic
 * and ON by default; a global default + per-chat toggle gate it. A rule with
 * status='draft' is a Phase 6 Archivist suggestion awaiting review.
 * ------------------------------------------------------------------------- */

data class ModelRuleRecord(
    val ruleId: String,
    val text: String,
    /** JSON array of model id strings this rule applies to. A rule matches a
     *  chat when any string here matches the chat's model (case-insensitive
     *  contains, provider prefix ignored — see ModelRuleMatcher). */
    val modelStringsJson: String,
    val status: String,                   // draft | active (drafts arrive with Phase 6 filing)
    /** The model string of the chat a draft was filed from (Phase 6). Seeds
     *  the model-strings list the user confirms on accept. Null for
     *  hand-written rules. */
    val sourceModelString: String? = null,
    val createdAt: String,
    val updatedAt: String? = null
)

/** A model-rule tag — a plain organizing label, no colors. Its own pool,
 *  distinct from memory tags and the roleplay tag realm. */
data class ModelRuleTagRecord(
    val tagId: String,
    val name: String,
    val createdAt: String
)

/** rule ↔ tag link, carried in backups so tagging survives export/import. */
data class ModelRuleTagLink(
    val ruleId: String,
    val tagId: String
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
    val origin: String = "user",
    /** Archivist card-placement suggestion (DB v13, drafts only): proposed
     *  CardType / card id / CardSections key, pre-selecting the Add-to-Card
     *  and Link dropdowns and driving the §7 outline treatment. Cleared when
     *  the draft is accepted without the card. */
    val suggestedCardType: String? = null,
    val suggestedCardId: String? = null,
    val suggestedSection: String? = null
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
 * One Archivist analysis run (Phase 6, DB v11) — the storage behind the
 * Memory Assistant's "Recent Memory Analysis" list
 * (`Memory System/memory_assistant_design.md` §4). Device-local operational
 * history, like embeddings: never exported, no tombstones. transcript ids are
 * kept so a run can be re-fed by Rerun; memory ids so "deleted since this
 * run" can be computed against the current store.
 */
data class ArchivistRunRecord(
    val runId: String,
    val startedAt: String,
    val finishedAt: String?,
    val status: String,                   // complete | failed
    val chatIdsJson: String,              // JSON array: chat ids analyzed
    val transcriptIdsJson: String,        // JSON array: transcript rows fed
    val memoryIdsJson: String,            // JSON array: draft memory ids created
    val ruleIdsJson: String,              // JSON array: model-rule draft ids created
    val foundCount: Int,
    val failedChatIdsJson: String,        // JSON array: chats whose analysis failed (stay pending)
    val error: String?,
    /** Display outcome for the Recent Memory Analysis row (DB v12,
     *  archivist_status_wording_spec.md): completed | full_failed |
     *  partial_failed | nothing | no_new | interrupted. Null on legacy rows —
     *  derived from counts then. */
    val outcome: String? = null,
    /** Dominant [ArchivistFailure] key when the run (fully or partially)
     *  failed; picks the on-screen reason sentence. */
    val failureReason: String? = null
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
    val projects: List<ProjectRecord> = emptyList(),
    // Roleplay cards + tags (Stage 3.6a) — ride every backup so the
    // Reset-memories "save a backup first" path can never lose a card.
    val partyMembers: List<PartyMemberRecord> = emptyList(),
    val cardEntries: List<CardEntryRecord> = emptyList(),
    val rpTags: List<RpTagRecord> = emptyList(),
    // Model rules (Stage 4, rules §11 Revision 5) — user-authored, so they
    // ride every backup like everything else the user typed in.
    val modelRules: List<ModelRuleRecord> = emptyList(),
    val modelRuleTags: List<ModelRuleTagRecord> = emptyList(),
    val modelRuleTagLinks: List<ModelRuleTagLink> = emptyList()
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
