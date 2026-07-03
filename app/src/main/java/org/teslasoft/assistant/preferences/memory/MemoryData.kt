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
    val nameHistory: List<NameHistoryEntry>
)

data class EntityRecord(
    val entityId: String,
    val kind: String,
    val name: String,
    val aliasesJson: String,              // JSON array of strings
    val summary: String,
    val status: String?,
    val importance: Int,
    val lastTouched: String?
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

data class ChangeLogEntry(
    val at: String,
    val actor: String,                    // user | archivist | companion | system
    val action: String,
    val note: String?,
    val priorStateJson: String?           // device-local undo snapshot; not exported
)

data class MemoryRecord(
    val memoryId: String,
    val scope: String,                    // global | companion
    val kind: String,
    val title: String,
    val content: String,
    val embeddingText: String?,
    val tagsJson: String,                 // JSON array of strings
    val importance: Int,
    val alwaysLoad: Boolean,
    val worldId: String?,
    val roleplayCharacterId: String?,
    val protectionJson: String?,          // schema protection object, verbatim
    val modeHintsJson: String,            // JSON array of mode ids
    val provenanceSource: String?,
    val provenanceConfidence: String?,
    val provenanceNotedOn: String?,
    val provenanceContext: String?,
    val createdAt: String,
    val updatedAt: String?,
    val status: String,                   // active | archived | superseded
    val supersedes: String?,
    val companionIds: List<String>,       // memory_companions join rows
    val entityRefs: List<String>,         // memory_entities join rows
    val changeLog: List<ChangeLogEntry>
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
    val companionIdsJson: String          // JSON array
)

data class DirectiveRecord(
    val directiveId: String,
    val text: String,
    val rationale: String?,
    val appliesToJson: String,            // JSON array; empty = all companions
    val priority: Int
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
    val transcripts: List<TranscriptRecord>
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
