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

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

/**
 * The companion memory store: a SEPARATE SQLCipher database
 * (companion_memory.db), NOT an extension of lorebook.db — lorebooks stay the
 * independent low-RAM tier. Schema follows `Memory System/sqlite_table_plan.md`
 * (v1.11) with four documented deviations:
 *
 *  - companions.status also allows 'draft' (the schema/seed require drafts;
 *    the table plan's CHECK list omitted it).
 *  - transcripts gains chat_id (the app's persistent chats map to transcript
 *    rows via a watermark — integration plan D5) and user_persona_id (the
 *    Archivist prompt lists persona_id among its inputs).
 *  - transcripts.companion_id is nullable: app chats can run with no persona.
 *  - deleted_ids tombstone table (integration plan D10): future cross-device
 *    merge must distinguish "deleted here" from "never had it"; impossible to
 *    retrofit after the fact.
 *
 * Migrations: SQLiteOpenHelper's version drives onUpgrade; meta.db_migration
 * mirrors the applied number for exports/diagnostics. Always bump both, always
 * additive steps, never edit old blocks (same rule as LoreBookStore).
 */
class MemoryStore private constructor(context: Context, password: ByteArray) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, password, null, DATABASE_VERSION, 0, null, null, true) {

    companion object {
        const val DATABASE_NAME = "companion_memory.db"
        private const val DATABASE_VERSION = 9

        // Freshness-cooldown source types (rules §10 / Stage 3.3): the
        // composite key (chat_id, source_type, entry_id) keeps ids from
        // different tables from colliding — 'memory' rows are memories;
        // 'card_entry' is the Stage 3.6 roleplay-card Zone 2 entries
        // (card_entries rows; replaces the never-written 'ledger' value the
        // superseded six-section-ledger plan had reserved). Card CORES are
        // always-injected and cooldown-exempt (§10), so they never appear
        // in the cooldown table at all.
        const val COOLDOWN_SOURCE_MEMORY = "memory"
        const val COOLDOWN_SOURCE_CARD_ENTRY = "card_entry"

        // meta keys
        const val META_SCHEMA_VERSION = "schema_version"
        const val META_DB_MIGRATION = "db_migration"
        const val META_SEED_IMPORTED_AT = "seed_imported_at"
        const val META_BOOTSTRAP_DONE = "bootstrap_done"
        const val META_AUTO_EXPORT_ENABLED = "auto_export_enabled"
        const val META_LAST_AUTO_EXPORT_AT = "last_auto_export_at"
        const val META_INDEX_MODEL_TAG = "index_model_tag"
        const val META_BACKFILL_DONE = "backfill_done"
        // Set once the one-time purge of pre-written origin='system' modes has
        // run (owner_approved_rules.md §15 — the app pre-authors no modes).
        const val META_SYSTEM_MODES_PURGED = "system_modes_purged"

        // A transcript row past this size closes and a new row opens: keeps the
        // per-turn parse-append-write affordable and Archivist inputs bounded.
        private const val MAX_TRANSCRIPT_CHARS = 200_000

        @Volatile
        private var instance: MemoryStore? = null

        @Volatile
        private var libraryLoaded = false

        fun getInstance(context: Context): MemoryStore {
            return instance ?: synchronized(this) {
                instance ?: run {
                    if (!libraryLoaded) {
                        System.loadLibrary("sqlcipher")
                        libraryLoaded = true
                    }
                    val appContext = context.applicationContext
                    val key = DatabaseKeys.getOrCreate(appContext, DatabaseKeys.KEY_MEMORY, isProvisioned(appContext))
                        ?: throw IllegalStateException(
                            "Memory store key unavailable (database exists but its key could not be read)"
                        )
                    MemoryStore(appContext, key).also { instance = it }
                }
            }
        }

        /** True once the database file exists — used to keep hooks and startup
         *  housekeeping from creating the store before the user opts in. */
        fun isProvisioned(context: Context): Boolean =
            context.getDatabasePath(DATABASE_NAME).exists()

        fun nowIso(): String = Instant.now().toString()

        fun newId(prefix: String): String = prefix + UUID.randomUUID().toString()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // The v4 migration rebuilds the `memories` table (and v7 the `worlds`
        // table) to relax CHECK constraints (SQLite cannot loosen a CHECK with
        // ALTER, so the table is recreated). Dropping a parent table while
        // foreign keys are ON would fire ON DELETE CASCADE on its child tables
        // and wipe them; the PRAGMA can't be toggled inside onUpgrade's
        // transaction, so we disable enforcement here (onConfigure runs before
        // that transaction) whenever an older schema is about to be migrated,
        // and re-enable it in onOpen. Fresh installs (version 0) and
        // already-migrated stores keep FKs on.
        val migratingOlder = db.version in 1 until DATABASE_VERSION
        db.setForeignKeyConstraintsEnabled(!migratingOlder)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Restore enforcement after any FK-off migration (see onConfigure).
        if (!db.isReadOnly) db.setForeignKeyConstraintsEnabled(true)
        purgeSystemModesOnce(db)
    }

    /**
     * One-time removal of the five pre-written origin='system' modes that older
     * builds provisioned (owner_approved_rules.md §15 — no AI pre-authors memory
     * content; the modes machinery stays dormant and empty until the user fills
     * it). User-authored modes (origin='user') are untouched. Guarded by a meta
     * flag so it runs once, and wrapped so a failure never blocks store open —
     * the flag stays unset and the purge simply retries on the next open.
     */
    private fun purgeSystemModesOnce(db: SQLiteDatabase) {
        try {
            val alreadyPurged = db.rawQuery(
                "SELECT 1 FROM meta WHERE key = ?", arrayOf(META_SYSTEM_MODES_PURGED)
            ).use { it.moveToFirst() }
            if (alreadyPurged) return

            db.delete("modes", "origin = ?", arrayOf("system"))
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_SYSTEM_MODES_PURGED, nowIso())
            )
        } catch (_: Exception) {
            // Best-effort: leave the flag unset so the next open retries.
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")

        db.execSQL(
            "CREATE TABLE app_state (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "active_companion_id TEXT, " +
                "active_world_id TEXT, " +
                "active_roleplay_character_id TEXT, " +
                "active_user_persona_id TEXT)"
        )

        db.execSQL(
            "CREATE TABLE owner_profile (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "portrait TEXT NOT NULL, " +
                "standing_context TEXT, " +
                "updated_at TEXT)"
        )

        db.execSQL(
            "CREATE TABLE companions (" +
                "companion_id TEXT PRIMARY KEY, " +
                "current_name TEXT NOT NULL, " +
                "essence TEXT NOT NULL, " +
                "relationship_notes TEXT, " +
                "memory_participation TEXT NOT NULL DEFAULT 'full' CHECK (memory_participation IN ('full','global_only','none')), " +
                "hard_limits_json TEXT NOT NULL DEFAULT '[]', " +
                "app_character_id TEXT, " +
                "base_personality_mirror_text TEXT, " +
                "base_personality_mirror_synced_at TEXT, " +
                "model_adaptations_json TEXT DEFAULT '[]', " +
                "created_at TEXT NOT NULL, " +
                "status TEXT NOT NULL CHECK (status IN ('draft','active','resting','retired')), " +
                "origin TEXT NOT NULL DEFAULT 'user')"
        )

        db.execSQL(
            "CREATE TABLE companion_name_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "companion_id TEXT NOT NULL REFERENCES companions(companion_id) ON DELETE CASCADE, " +
                "name TEXT NOT NULL, " +
                "effective_from TEXT NOT NULL, " +
                "effective_until TEXT)"
        )

        db.execSQL(
            "CREATE TABLE entities (" +
                "entity_id TEXT PRIMARY KEY, " +
                "kind TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "aliases_json TEXT DEFAULT '[]', " +
                "summary TEXT NOT NULL, " +
                "status TEXT, " +
                "importance INTEGER DEFAULT 3, " +
                "last_touched TEXT, " +
                "origin TEXT NOT NULL DEFAULT 'user')"
        )

        // World card Zone 1 (spec §6c) lives in cosmology (v7) +
        // premise_vibe + magic_rules (v8, FRESH per the owner's July 7
        // ruling, spec §8a: cards never reuse the old free-text blocks).
        // premise/rules are dormant pre-card columns kept only so old
        // backups still import. 'archived' status added in v7 for the §5
        // Archive sections.
        db.execSQL(
            "CREATE TABLE worlds (" +
                "world_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "premise TEXT NOT NULL, " +
                "rules TEXT, " +
                "cosmology TEXT, " +
                "premise_vibe TEXT, " +
                "magic_rules TEXT, " +
                "companion_ids_json TEXT DEFAULT '[]', " +
                "status TEXT NOT NULL CHECK (status IN ('active','dormant','ended','archived')), " +
                "created_at TEXT)"
        )

        db.execSQL(
            "CREATE TABLE user_personas (" +
                "persona_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "presentation TEXT NOT NULL, " +
                "status TEXT NOT NULL CHECK (status IN ('active','archived')), " +
                "created_at TEXT)"
        )

        // The five Zone 1 card columns (species..goals_drives) are the spec
        // §6a user RP-character core (v7); description/arc/played_by predate
        // the card system and stay for existing data.
        db.execSQL(
            "CREATE TABLE roleplay_characters (" +
                "roleplay_character_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "played_by TEXT NOT NULL, " +
                "description TEXT NOT NULL, " +
                "arc TEXT, " +
                "worlds_played_json TEXT DEFAULT '[]', " +
                "status TEXT NOT NULL CHECK (status IN ('active','archived')), " +
                "created_at TEXT, " +
                "species TEXT, " +
                "char_class TEXT, " +
                "core_personality TEXT, " +
                "physical_description TEXT, " +
                "goals_drives TEXT)"
        )

        // Campaign (roleplay continuity) layer — integration plan 📌 amendment.
        // Created before `memories` so the memories.campaign_id foreign key
        // resolves. Additive for existing installs (onUpgrade v3).
        // quest_anchor + active_scene (v7) are the campaign card's Zone 1
        // "bookmark" (spec §6d) — user-maintained, session-end updates only.
        db.execSQL(
            "CREATE TABLE campaigns (" +
                "campaign_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "world_id TEXT REFERENCES worlds(world_id), " +
                "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                "companion_id TEXT REFERENCES companions(companion_id), " +
                "status TEXT NOT NULL CHECK (status IN ('active','paused','ended','archived')), " +
                "story_so_far TEXT, " +
                "created_at TEXT, " +
                "quest_anchor TEXT, " +
                "active_scene TEXT)"
        )

        // Projects (owner_approved_rules §4): user-defined named buckets a memory
        // can be scoped to. Created before `memories` so the project_id FK
        // resolves. Additive for existing installs (onUpgrade v4).
        db.execSQL(
            "CREATE TABLE projects (" +
                "project_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')), " +
                "created_at TEXT, " +
                "updated_at TEXT)"
        )

        // scope holds the primary scope category (§1/§2); status gains 'draft'
        // (§9). always_load is retired (§10) — the column stays but nothing reads
        // or writes it. kind holds the Type (§5).
        db.execSQL(
            "CREATE TABLE memories (" +
                "memory_id TEXT PRIMARY KEY, " +
                "scope TEXT NOT NULL CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character')), " +
                "kind TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "embedding_text TEXT, " +
                "tags_json TEXT DEFAULT '[]', " +
                "importance INTEGER NOT NULL DEFAULT 3, " +
                "always_load INTEGER NOT NULL DEFAULT 0, " +
                "world_id TEXT REFERENCES worlds(world_id), " +
                "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                "campaign_id TEXT REFERENCES campaigns(campaign_id), " +
                "project_id TEXT REFERENCES projects(project_id), " +
                "protection_json TEXT, " +
                "mode_hints_json TEXT DEFAULT '[]', " +
                "provenance_source TEXT, " +
                "provenance_confidence TEXT, " +
                "provenance_noted_on TEXT, " +
                "provenance_context TEXT, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT, " +
                "status TEXT NOT NULL CHECK (status IN ('draft','active','archived','superseded')), " +
                "supersedes TEXT REFERENCES memories(memory_id), " +
                "origin TEXT NOT NULL DEFAULT 'user')"
        )

        db.execSQL(
            "CREATE TABLE memory_companions (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "companion_id TEXT NOT NULL REFERENCES companions(companion_id), " +
                "PRIMARY KEY (memory_id, companion_id))"
        )

        db.execSQL(
            "CREATE TABLE memory_entities (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "entity_id TEXT NOT NULL REFERENCES entities(entity_id), " +
                "PRIMARY KEY (memory_id, entity_id))"
        )

        // Named target scopes are multi-select (owner_approved_rules §2): a memory
        // may belong to several worlds/campaigns/RP characters/projects at once,
        // mirroring memory_companions. These join tables are the full multi-target
        // truth — the editor, the scoped-browser doors AND (since Stage 3.1) the
        // retrieval eligibility query all read them. The single memories.world_id/
        // campaign_id/roleplay_character_id/project_id columns remain as a
        // "primary target" mirror (the first selected) used only by the target
        // teardown paths (deleteCampaign/deleteProject) and legacy consumers.
        db.execSQL(
            "CREATE TABLE memory_worlds (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "world_id TEXT NOT NULL REFERENCES worlds(world_id), " +
                "PRIMARY KEY (memory_id, world_id))"
        )
        db.execSQL(
            "CREATE TABLE memory_campaigns (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "campaign_id TEXT NOT NULL REFERENCES campaigns(campaign_id), " +
                "PRIMARY KEY (memory_id, campaign_id))"
        )
        db.execSQL(
            "CREATE TABLE memory_roleplay_characters (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "roleplay_character_id TEXT NOT NULL REFERENCES roleplay_characters(roleplay_character_id), " +
                "PRIMARY KEY (memory_id, roleplay_character_id))"
        )
        db.execSQL(
            "CREATE TABLE memory_projects (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "project_id TEXT NOT NULL REFERENCES projects(project_id), " +
                "PRIMARY KEY (memory_id, project_id))"
        )

        db.execSQL(
            "CREATE TABLE change_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "memory_id TEXT REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "at TEXT NOT NULL, " +
                "actor TEXT NOT NULL CHECK (actor IN ('user','archivist','companion','system')), " +
                "action TEXT NOT NULL, " +
                "note TEXT, " +
                "prior_state_json TEXT)"
        )

        db.execSQL(
            "CREATE TABLE modes (" +
                "mode_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "purpose TEXT, " +
                "signals_json TEXT NOT NULL, " +
                "respond_json TEXT NOT NULL, " +
                "avoid_json TEXT NOT NULL, " +
                "transition_note TEXT, " +
                "overrides_json TEXT DEFAULT '[]', " +
                "scope TEXT NOT NULL DEFAULT 'global', " +
                "companion_ids_json TEXT DEFAULT '[]', " +
                "origin TEXT NOT NULL DEFAULT 'user')"
        )

        db.execSQL(
            "CREATE TABLE directives (" +
                "directive_id TEXT PRIMARY KEY, " +
                "text TEXT NOT NULL, " +
                "rationale TEXT, " +
                "applies_to_json TEXT DEFAULT '[]', " +
                "priority INTEGER DEFAULT 3, " +
                "origin TEXT NOT NULL DEFAULT 'user')"
        )

        db.execSQL(
            "CREATE TABLE archivist_settings (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "run_trigger TEXT NOT NULL, " +
                "harvest_generosity TEXT NOT NULL, " +
                "autonomy_json TEXT NOT NULL, " +
                "notes TEXT)"
        )

        db.execSQL(
            "CREATE TABLE proposals (" +
                "proposal_id TEXT PRIMARY KEY, " +
                "target_type TEXT NOT NULL, " +
                "target_id TEXT, " +
                "summary TEXT NOT NULL, " +
                "proposed_change_json TEXT, " +
                "rationale TEXT, " +
                "status TEXT NOT NULL CHECK (status IN ('pending','accepted','rejected')), " +
                "created_at TEXT NOT NULL, " +
                "resolved_at TEXT)"
        )

        db.execSQL(
            "CREATE TABLE transcripts (" +
                "transcript_id TEXT PRIMARY KEY, " +
                "chat_id TEXT, " +
                "companion_id TEXT REFERENCES companions(companion_id), " +
                "world_id TEXT REFERENCES worlds(world_id), " +
                "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                "user_persona_id TEXT REFERENCES user_personas(persona_id), " +
                "source TEXT NOT NULL DEFAULT 'live' CHECK (source IN ('live','imported')), " +
                "started_at TEXT, " +
                "ended_at TEXT, " +
                "content TEXT NOT NULL, " +
                "model_tag TEXT, " +
                "quick_settings_json TEXT, " +
                "review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending','processed','excluded')), " +
                "processed_at TEXT)"
        )

        db.execSQL(
            "CREATE TABLE retrieval_policy (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "policy_json TEXT NOT NULL)"
        )

        db.execSQL(
            "CREATE TABLE embeddings (" +
                "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "embedding_model TEXT NOT NULL, " +
                "vector BLOB NOT NULL, " +
                "embedded_at TEXT NOT NULL, " +
                "PRIMARY KEY (memory_id, embedding_model))"
        )

        db.execSQL(
            "CREATE TABLE deleted_ids (" +
                "record_type TEXT NOT NULL, " +
                "record_id TEXT NOT NULL, " +
                "deleted_at TEXT NOT NULL, " +
                "PRIMARY KEY (record_type, record_id))"
        )

        // Freshness cooldown (rules §10 / Stage 3.3): when each entry was last
        // injected, per chat, persisted so suppression survives process death.
        // No FK to memories — entry_id may point at other tables per
        // source_type (the 3.6 ledger), and a stale row is harmless (the
        // delete/edit paths clear them anyway). chat_turn_counters is the
        // per-chat monotonic turn clock the cooldown is measured against.
        db.execSQL(
            "CREATE TABLE injection_cooldowns (" +
                "chat_id TEXT NOT NULL, " +
                "source_type TEXT NOT NULL, " +
                "entry_id TEXT NOT NULL, " +
                "last_injected_turn INTEGER NOT NULL, " +
                "last_injected_at TEXT, " +
                "PRIMARY KEY (chat_id, source_type, entry_id))"
        )
        db.execSQL(
            "CREATE TABLE chat_turn_counters (" +
                "chat_id TEXT PRIMARY KEY, " +
                "turn INTEGER NOT NULL)"
        )

        // Roleplay cards + tags (Stage 3.6a, roleplay_cards_and_tags_spec.md).
        // Card content lives HERE, never in memories rows; retrieval over it is
        // trigger-matched, never embedded (the embeddings table stays
        // memories-only). Nothing ships pre-populated — no sample cards, no
        // starter tags, ever.

        // NPC party members (spec §4/§6b): a top-level roster; campaigns LINK
        // members via the join table (join, not ownership). `status` is the
        // four-state fiction status that gates Zone 1 injection; `archived` is
        // the separate card-lifecycle flag for the §5 Archive section.
        db.execSQL(
            "CREATE TABLE party_members (" +
                "party_member_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "species TEXT, " +
                "char_class TEXT, " +
                "core_personality TEXT, " +
                "physical_description TEXT, " +
                "goals_drives TEXT, " +
                "speech_style TEXT, " +
                "status TEXT NOT NULL DEFAULT 'alive' CHECK (status IN ('alive','incapacitated','dead','enemy')), " +
                "archived INTEGER NOT NULL DEFAULT 0, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT)"
        )
        db.execSQL(
            "CREATE TABLE campaign_party_members (" +
                "campaign_id TEXT NOT NULL REFERENCES campaigns(campaign_id), " +
                "party_member_id TEXT NOT NULL REFERENCES party_members(party_member_id), " +
                "PRIMARY KEY (campaign_id, party_member_id))"
        )

        // Zone 2 card entries (spec §6): one polymorphic table for all four
        // card types — the retrieval machinery is section-agnostic (named
        // entries in containers); only the columns a section defines are used
        // (see CardEntryRecord). The parent/world-entry/party-member reference
        // columns are deliberately soft (no FK): §5 rules that surviving
        // references to a gone card render "(archived card)"/"(deleted card)"
        // instead of vanishing, so a dangling id plus its deleted_ids
        // tombstone is the intended representation, not corruption.
        db.execSQL(
            "CREATE TABLE card_entries (" +
                "entry_id TEXT PRIMARY KEY, " +
                "card_type TEXT NOT NULL CHECK (card_type IN ('rp_character','party_member','world','campaign')), " +
                "card_id TEXT NOT NULL, " +
                "section TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "description TEXT, " +
                "entry_kind TEXT, " +
                "quantity INTEGER, " +
                "parent_entry_id TEXT, " +
                "world_entry_id TEXT, " +
                "party_member_id TEXT, " +
                "holder TEXT, " +
                "significance TEXT, " +
                "cast_identity TEXT, " +
                "cast_disposition TEXT, " +
                "cast_status TEXT, " +
                "location_condition TEXT, " +
                "location_changes TEXT, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT)"
        )

        // The roleplay-realm tag pool (spec §3): ONE pool for the whole
        // roleplay module and ONLY the roleplay module — real-life memory tags
        // stay in memories.tags_json; the realm wall is structural. Tag names
        // are deduplicated case-insensitively in code (getOrCreateRpTag), not
        // by a UNIQUE constraint, so an import can name-match instead of
        // failing. auto_trigger defaults ON; OFF = browse/organize-only.
        db.execSQL(
            "CREATE TABLE rp_tags (" +
                "tag_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "auto_trigger INTEGER NOT NULL DEFAULT 1, " +
                "created_at TEXT)"
        )
        // Polymorphic links: a tag can point at a memory, a card entry, or a
        // whole card (the bridge between the two storage cabinets).
        db.execSQL(
            "CREATE TABLE rp_tag_links (" +
                "tag_id TEXT NOT NULL REFERENCES rp_tags(tag_id) ON DELETE CASCADE, " +
                "target_type TEXT NOT NULL CHECK (target_type IN ('card_entry','rp_character','party_member','world','campaign','memory')), " +
                "target_id TEXT NOT NULL, " +
                "PRIMARY KEY (tag_id, target_type, target_id))"
        )

        // Model rules (Stage 4, owner_approved_rules §11): user-created
        // profiles (nickname + the model strings that count as that model)
        // and their rules. profile_id NULL = the pinned "Needs review"
        // section — unassigned drafts the system never files into an
        // invented profile. Starts EMPTY: the user creates every profile,
        // and rule drafts only ever arrive via Phase 6 filing.
        db.execSQL(
            "CREATE TABLE model_rule_profiles (" +
                "profile_id TEXT PRIMARY KEY, " +
                "nickname TEXT NOT NULL, " +
                "model_strings_json TEXT NOT NULL DEFAULT '[]', " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT)"
        )
        db.execSQL(
            "CREATE TABLE model_rules (" +
                "rule_id TEXT PRIMARY KEY, " +
                "profile_id TEXT REFERENCES model_rule_profiles(profile_id), " +
                "text TEXT NOT NULL, " +
                "status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('draft','active')), " +
                "source_model_string TEXT, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT)"
        )

        db.execSQL("CREATE INDEX idx_memories_status ON memories(status)")
        db.execSQL("CREATE INDEX idx_memories_always_load ON memories(always_load) WHERE always_load = 1")
        db.execSQL("CREATE INDEX idx_memories_world ON memories(world_id)")
        db.execSQL("CREATE INDEX idx_memories_rp_character ON memories(roleplay_character_id)")
        db.execSQL("CREATE INDEX idx_memories_campaign ON memories(campaign_id)")
        db.execSQL("CREATE INDEX idx_memories_project ON memories(project_id)")
        db.execSQL("CREATE INDEX idx_memcomp_companion ON memory_companions(companion_id)")
        db.execSQL("CREATE INDEX idx_memworlds_world ON memory_worlds(world_id)")
        db.execSQL("CREATE INDEX idx_memcampaigns_campaign ON memory_campaigns(campaign_id)")
        db.execSQL("CREATE INDEX idx_memrpchars_rp ON memory_roleplay_characters(roleplay_character_id)")
        db.execSQL("CREATE INDEX idx_memprojects_project ON memory_projects(project_id)")
        db.execSQL("CREATE INDEX idx_changelog_memory ON change_log(memory_id)")
        db.execSQL("CREATE INDEX idx_transcripts_queue ON transcripts(review_status) WHERE review_status = 'pending'")
        db.execSQL("CREATE INDEX idx_transcripts_chat ON transcripts(chat_id)")
        db.execSQL("CREATE INDEX idx_proposals_pending ON proposals(status) WHERE status = 'pending'")
        db.execSQL("CREATE INDEX idx_card_entries_card ON card_entries(card_type, card_id)")
        db.execSQL("CREATE INDEX idx_cpm_member ON campaign_party_members(party_member_id)")
        db.execSQL("CREATE INDEX idx_rp_tag_links_target ON rp_tag_links(target_type, target_id)")
        db.execSQL("CREATE INDEX idx_model_rules_profile ON model_rules(profile_id)")

        val now = nowIso()
        db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(META_SCHEMA_VERSION, "1.11.0"))
        // A fresh install is created at the latest schema, so db_migration
        // starts at the current DATABASE_VERSION (never re-runs onUpgrade steps).
        db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(META_DB_MIGRATION, DATABASE_VERSION.toString()))
        db.execSQL("INSERT INTO app_state (id) VALUES (1)")
        // Archivist defaults mirror the public template: memory work automatic,
        // anything touching rules or identity proposed.
        db.execSQL(
            "INSERT INTO archivist_settings (id, run_trigger, harvest_generosity, autonomy_json, notes) VALUES (1, ?, ?, ?, ?)",
            arrayOf(
                "manual", "generous",
                "{\"facts_and_episodes\":\"auto\",\"corrections\":\"auto\",\"pattern_harvest\":\"auto\"," +
                    "\"protection_marking\":\"auto\",\"relationship_notes\":\"auto\"," +
                    "\"companion_essence_and_limits\":\"propose\",\"modes_and_directives\":\"propose\"," +
                    "\"owner_profile\":\"propose\",\"archivist_settings\":\"propose\"}",
                "Defaults created at first launch ($now); a seed import replaces these."
            )
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first shipped schema; future migrations are additive steps
        // gated on oldVersion, each ending with an update of meta.db_migration.
        if (oldVersion < 2) {
            // v2 (July 2026): machine-readable record origin ('user' default;
            // 'archivist' reserved for Phase 6 proposals) so later phases can
            // tell user records from archivist-proposed ones. Rows predating
            // the column default to 'user'. (Kept as an already-shipped
            // migration; the app no longer bundles or auto-loads any seed
            // data — memories come only from real conversations and imports.)
            for (table in listOf("memories", "companions", "entities", "modes", "directives")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'")
            }
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "2")
            )
        }
        if (oldVersion < 3) {
            // v3 (July 2026, Phase 5): the Campaign (roleplay continuity) layer.
            // A new campaigns table plus a nullable memories.campaign_id so
            // game-state facts key to one playthrough. Additive; existing
            // memories default to campaign_id NULL (real-life / non-campaign).
            db.execSQL(
                "CREATE TABLE campaigns (" +
                    "campaign_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "world_id TEXT REFERENCES worlds(world_id), " +
                    "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                    "companion_id TEXT REFERENCES companions(companion_id), " +
                    "status TEXT NOT NULL CHECK (status IN ('active','paused','ended','archived')), " +
                    "story_so_far TEXT, " +
                    "created_at TEXT)"
            )
            db.execSQL("ALTER TABLE memories ADD COLUMN campaign_id TEXT REFERENCES campaigns(campaign_id)")
            db.execSQL("CREATE INDEX idx_memories_campaign ON memories(campaign_id)")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "3")
            )
        }
        if (oldVersion < 4) {
            // v4 (July 2026, Phase 5 Stage 2): the memory record restructure —
            // scope categories, Type, projects, statuses (owner_approved_rules
            // §§1–9). New scope values and a 'draft' status require loosening the
            // `memories` CHECK constraints, which SQLite can't do with ALTER, so
            // the table is rebuilt. Foreign keys are OFF here (see onConfigure),
            // so dropping `memories` does not cascade-delete its child rows; the
            // same memory_ids are re-inserted, leaving the children valid.
            db.execSQL(
                "CREATE TABLE projects (" +
                    "project_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')), " +
                    "created_at TEXT, " +
                    "updated_at TEXT)"
            )

            db.execSQL(
                "CREATE TABLE memories_new (" +
                    "memory_id TEXT PRIMARY KEY, " +
                    "scope TEXT NOT NULL CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character')), " +
                    "kind TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "embedding_text TEXT, " +
                    "tags_json TEXT DEFAULT '[]', " +
                    "importance INTEGER NOT NULL DEFAULT 3, " +
                    "always_load INTEGER NOT NULL DEFAULT 0, " +
                    "world_id TEXT REFERENCES worlds(world_id), " +
                    "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                    "campaign_id TEXT REFERENCES campaigns(campaign_id), " +
                    "project_id TEXT REFERENCES projects(project_id), " +
                    "protection_json TEXT, " +
                    "mode_hints_json TEXT DEFAULT '[]', " +
                    "provenance_source TEXT, " +
                    "provenance_confidence TEXT, " +
                    "provenance_noted_on TEXT, " +
                    "provenance_context TEXT, " +
                    "created_at TEXT NOT NULL, " +
                    "updated_at TEXT, " +
                    "status TEXT NOT NULL CHECK (status IN ('draft','active','archived','superseded')), " +
                    "supersedes TEXT REFERENCES memories(memory_id), " +
                    "origin TEXT NOT NULL DEFAULT 'user')"
            )

            // Migrate rows. Type: unrecognized kind -> 'fact' (single user; owner
            // approved this lossy simplicity). Scope: companion-scoped rows stay
            // companion; otherwise a campaign/world/rp link promotes the row to
            // that specific category; plain global stays global. project_id is
            // NULL for every pre-existing row (no project scope existed before).
            db.execSQL(
                "INSERT INTO memories_new (memory_id, scope, kind, title, content, embedding_text, " +
                    "tags_json, importance, always_load, world_id, roleplay_character_id, campaign_id, " +
                    "project_id, protection_json, mode_hints_json, provenance_source, provenance_confidence, " +
                    "provenance_noted_on, provenance_context, created_at, updated_at, status, supersedes, origin) " +
                    "SELECT memory_id, " +
                    "CASE " +
                    "WHEN scope = 'companion' THEN 'companion' " +
                    "WHEN campaign_id IS NOT NULL THEN 'campaign' " +
                    "WHEN roleplay_character_id IS NOT NULL THEN 'rp_character' " +
                    "WHEN world_id IS NOT NULL THEN 'world' " +
                    "WHEN scope = 'global' THEN 'global' " +
                    "ELSE 'global' END, " +
                    "CASE WHEN kind IN ('fact','preference','event','status','instruction','lore') THEN kind ELSE 'fact' END, " +
                    "title, content, embedding_text, tags_json, importance, always_load, world_id, " +
                    "roleplay_character_id, campaign_id, NULL, protection_json, mode_hints_json, " +
                    "provenance_source, provenance_confidence, provenance_noted_on, provenance_context, " +
                    "created_at, updated_at, status, supersedes, origin FROM memories"
            )

            db.execSQL("DROP TABLE memories")
            db.execSQL("ALTER TABLE memories_new RENAME TO memories")

            // Recreate the indexes that lived on the old memories table, plus the
            // new project index.
            db.execSQL("CREATE INDEX idx_memories_status ON memories(status)")
            db.execSQL("CREATE INDEX idx_memories_always_load ON memories(always_load) WHERE always_load = 1")
            db.execSQL("CREATE INDEX idx_memories_world ON memories(world_id)")
            db.execSQL("CREATE INDEX idx_memories_rp_character ON memories(roleplay_character_id)")
            db.execSQL("CREATE INDEX idx_memories_campaign ON memories(campaign_id)")
            db.execSQL("CREATE INDEX idx_memories_project ON memories(project_id)")

            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "4")
            )
        }
        if (oldVersion < 5) {
            // v5 (July 2026, Phase 5 Stage 2): named target scopes become
            // multi-select (owner_approved_rules §2). New join tables mirror
            // memory_companions; the single columns stay as the primary-target
            // mirror. Existing memories are backfilled from those columns so
            // every current link survives as a (single-element) target set.
            db.execSQL(
                "CREATE TABLE memory_worlds (" +
                    "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "world_id TEXT NOT NULL REFERENCES worlds(world_id), " +
                    "PRIMARY KEY (memory_id, world_id))"
            )
            db.execSQL(
                "CREATE TABLE memory_campaigns (" +
                    "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "campaign_id TEXT NOT NULL REFERENCES campaigns(campaign_id), " +
                    "PRIMARY KEY (memory_id, campaign_id))"
            )
            db.execSQL(
                "CREATE TABLE memory_roleplay_characters (" +
                    "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "roleplay_character_id TEXT NOT NULL REFERENCES roleplay_characters(roleplay_character_id), " +
                    "PRIMARY KEY (memory_id, roleplay_character_id))"
            )
            db.execSQL(
                "CREATE TABLE memory_projects (" +
                    "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "project_id TEXT NOT NULL REFERENCES projects(project_id), " +
                    "PRIMARY KEY (memory_id, project_id))"
            )
            db.execSQL("INSERT INTO memory_worlds (memory_id, world_id) SELECT memory_id, world_id FROM memories WHERE world_id IS NOT NULL")
            db.execSQL("INSERT INTO memory_campaigns (memory_id, campaign_id) SELECT memory_id, campaign_id FROM memories WHERE campaign_id IS NOT NULL")
            db.execSQL("INSERT INTO memory_roleplay_characters (memory_id, roleplay_character_id) SELECT memory_id, roleplay_character_id FROM memories WHERE roleplay_character_id IS NOT NULL")
            db.execSQL("INSERT INTO memory_projects (memory_id, project_id) SELECT memory_id, project_id FROM memories WHERE project_id IS NOT NULL")
            db.execSQL("CREATE INDEX idx_memworlds_world ON memory_worlds(world_id)")
            db.execSQL("CREATE INDEX idx_memcampaigns_campaign ON memory_campaigns(campaign_id)")
            db.execSQL("CREATE INDEX idx_memrpchars_rp ON memory_roleplay_characters(roleplay_character_id)")
            db.execSQL("CREATE INDEX idx_memprojects_project ON memory_projects(project_id)")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "5")
            )
        }
        if (oldVersion < 6) {
            // v6 (July 2026, Stage 3.3): the freshness cooldown — a persisted
            // record of when each entry last reached a prompt, per chat, plus
            // the per-chat turn clock it is measured against. Purely additive.
            db.execSQL(
                "CREATE TABLE injection_cooldowns (" +
                    "chat_id TEXT NOT NULL, " +
                    "source_type TEXT NOT NULL, " +
                    "entry_id TEXT NOT NULL, " +
                    "last_injected_turn INTEGER NOT NULL, " +
                    "last_injected_at TEXT, " +
                    "PRIMARY KEY (chat_id, source_type, entry_id))"
            )
            db.execSQL(
                "CREATE TABLE chat_turn_counters (" +
                    "chat_id TEXT PRIMARY KEY, " +
                    "turn INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "6")
            )
        }
        if (oldVersion < 7) {
            // v7 (July 2026, Stage 3.6a): the roleplay card + tag layer
            // (roleplay_cards_and_tags_spec.md §6, rescoped Stage 3.6 of the
            // RAG engine work order). Additive except the `worlds` rebuild:
            // its status CHECK gains 'archived' (for the §5 Archive sections),
            // which SQLite can't loosen with ALTER — same recipe as v4,
            // foreign keys are OFF during the migration (onConfigure) so the
            // drop doesn't cascade into campaigns/memories/memory_worlds/
            // transcripts; the same world_ids are re-inserted. (v7 originally
            // mapped premise/rules onto the spec's Premise-Vibe/Magic-Rules
            // fields — SUPERSEDED by the owner's July 7 ruling, spec §8a:
            // v8 adds fresh premise_vibe/magic_rules columns and the old
            // columns go dormant. The SQL below is unchanged history.)
            db.execSQL(
                "CREATE TABLE worlds_new (" +
                    "world_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "premise TEXT NOT NULL, " +
                    "rules TEXT, " +
                    "cosmology TEXT, " +
                    "companion_ids_json TEXT DEFAULT '[]', " +
                    "status TEXT NOT NULL CHECK (status IN ('active','dormant','ended','archived')), " +
                    "created_at TEXT)"
            )
            db.execSQL(
                "INSERT INTO worlds_new (world_id, name, premise, rules, cosmology, companion_ids_json, status, created_at) " +
                    "SELECT world_id, name, premise, rules, NULL, companion_ids_json, status, created_at FROM worlds"
            )
            db.execSQL("DROP TABLE worlds")
            db.execSQL("ALTER TABLE worlds_new RENAME TO worlds")

            // User RP-character card Zone 1 (spec §6a) — additive columns; the
            // pre-card description/arc columns stay untouched.
            for (column in listOf("species", "char_class", "core_personality", "physical_description", "goals_drives")) {
                db.execSQL("ALTER TABLE roleplay_characters ADD COLUMN $column TEXT")
            }

            // Campaign card Zone 1 "bookmark" (spec §6d).
            db.execSQL("ALTER TABLE campaigns ADD COLUMN quest_anchor TEXT")
            db.execSQL("ALTER TABLE campaigns ADD COLUMN active_scene TEXT")

            // NPC party-member roster + campaign links (spec §4/§6b).
            db.execSQL(
                "CREATE TABLE party_members (" +
                    "party_member_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "species TEXT, " +
                    "char_class TEXT, " +
                    "core_personality TEXT, " +
                    "physical_description TEXT, " +
                    "goals_drives TEXT, " +
                    "speech_style TEXT, " +
                    "status TEXT NOT NULL DEFAULT 'alive' CHECK (status IN ('alive','incapacitated','dead','enemy')), " +
                    "archived INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TEXT NOT NULL, " +
                    "updated_at TEXT)"
            )
            db.execSQL(
                "CREATE TABLE campaign_party_members (" +
                    "campaign_id TEXT NOT NULL REFERENCES campaigns(campaign_id), " +
                    "party_member_id TEXT NOT NULL REFERENCES party_members(party_member_id), " +
                    "PRIMARY KEY (campaign_id, party_member_id))"
            )

            // Zone 2 card entries — one polymorphic table for all four card
            // types; reference columns are soft by design (see onCreate).
            db.execSQL(
                "CREATE TABLE card_entries (" +
                    "entry_id TEXT PRIMARY KEY, " +
                    "card_type TEXT NOT NULL CHECK (card_type IN ('rp_character','party_member','world','campaign')), " +
                    "card_id TEXT NOT NULL, " +
                    "section TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "description TEXT, " +
                    "entry_kind TEXT, " +
                    "quantity INTEGER, " +
                    "parent_entry_id TEXT, " +
                    "world_entry_id TEXT, " +
                    "party_member_id TEXT, " +
                    "holder TEXT, " +
                    "significance TEXT, " +
                    "cast_identity TEXT, " +
                    "cast_disposition TEXT, " +
                    "cast_status TEXT, " +
                    "location_condition TEXT, " +
                    "location_changes TEXT, " +
                    "created_at TEXT NOT NULL, " +
                    "updated_at TEXT)"
            )

            // The roleplay-realm tag pool + polymorphic links (spec §3).
            // Starts EMPTY — no starter tags, ever.
            db.execSQL(
                "CREATE TABLE rp_tags (" +
                    "tag_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "auto_trigger INTEGER NOT NULL DEFAULT 1, " +
                    "created_at TEXT)"
            )
            db.execSQL(
                "CREATE TABLE rp_tag_links (" +
                    "tag_id TEXT NOT NULL REFERENCES rp_tags(tag_id) ON DELETE CASCADE, " +
                    "target_type TEXT NOT NULL CHECK (target_type IN ('card_entry','rp_character','party_member','world','campaign','memory')), " +
                    "target_id TEXT NOT NULL, " +
                    "PRIMARY KEY (tag_id, target_type, target_id))"
            )

            db.execSQL("CREATE INDEX idx_card_entries_card ON card_entries(card_type, card_id)")
            db.execSQL("CREATE INDEX idx_cpm_member ON campaign_party_members(party_member_id)")
            db.execSQL("CREATE INDEX idx_rp_tag_links_target ON rp_tag_links(target_type, target_id)")

            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "7")
            )
        }
        if (oldVersion < 8) {
            // v8 (July 2026, pre-3.6b): fresh world-core columns. The owner
            // ruled (spec §8a) that the new cards must NOT reuse or migrate
            // the old free-text blocks, superseding v7's premise/rules
            // mapping — so Premise/Vibe and Magic Rules get their own empty
            // columns and the old premise/rules go dormant (kept only so old
            // backups still import). Deliberately NO data copy.
            db.execSQL("ALTER TABLE worlds ADD COLUMN premise_vibe TEXT")
            db.execSQL("ALTER TABLE worlds ADD COLUMN magic_rules TEXT")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "8")
            )
        }
        if (oldVersion < 9) {
            // v9 (July 2026, Stage 4): model rules (owner_approved_rules §11).
            // Purely additive; both tables start empty — the user creates
            // every profile, and rule drafts arrive only with Phase 6 filing.
            // profile_id NULL = the "Needs review" (unassigned) section.
            db.execSQL(
                "CREATE TABLE model_rule_profiles (" +
                    "profile_id TEXT PRIMARY KEY, " +
                    "nickname TEXT NOT NULL, " +
                    "model_strings_json TEXT NOT NULL DEFAULT '[]', " +
                    "created_at TEXT NOT NULL, " +
                    "updated_at TEXT)"
            )
            db.execSQL(
                "CREATE TABLE model_rules (" +
                    "rule_id TEXT PRIMARY KEY, " +
                    "profile_id TEXT REFERENCES model_rule_profiles(profile_id), " +
                    "text TEXT NOT NULL, " +
                    "status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('draft','active')), " +
                    "source_model_string TEXT, " +
                    "created_at TEXT NOT NULL, " +
                    "updated_at TEXT)"
            )
            db.execSQL("CREATE INDEX idx_model_rules_profile ON model_rules(profile_id)")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "9")
            )
        }
    }

    /* ---------------------------------------------------------------------- */
    /* meta + health                                                          */
    /* ---------------------------------------------------------------------- */

    fun getMeta(key: String): String? {
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun setMeta(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            arrayOf(key, value)
        )
    }

    /** Returns null when healthy, otherwise a short description of what failed. */
    fun integrityCheck(): String? {
        return try {
            readableDatabase.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    if (result.equals("ok", ignoreCase = true)) null else result
                } else "integrity_check returned no rows"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Row counts for the status screen (label -> count). */
    fun counts(): LinkedHashMap<String, Int> {
        val tables = linkedMapOf(
            "companions" to "companions",
            "memories" to "memories",
            "entities" to "entities",
            "modes" to "modes",
            "directives" to "directives",
            "worlds" to "worlds",
            "user_personas" to "user_personas",
            "roleplay_characters" to "roleplay_characters",
            "campaigns" to "campaigns",
            "proposals" to "proposals",
            "transcripts" to "transcripts"
        )
        val out = LinkedHashMap<String, Int>()
        val db = readableDatabase
        for ((label, table) in tables) {
            db.rawQuery("SELECT COUNT(*) FROM $table", emptyArray<String>()).use {
                out[label] = if (it.moveToFirst()) it.getInt(0) else 0
            }
        }
        db.rawQuery("SELECT COUNT(*) FROM transcripts WHERE review_status = 'pending'", emptyArray<String>()).use {
            out["pending_transcripts"] = if (it.moveToFirst()) it.getInt(0) else 0
        }
        return out
    }

    /** Retrieval weights [similarity, importance, recency] from the stored
     *  retrieval_policy, or null to use the librarian's defaults. */
    fun getRetrievalWeights(): DoubleArray? {
        val json = readableDatabase.let { db ->
            db.query("retrieval_policy", arrayOf("policy_json"), "id = 1", null, null, null, null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } ?: return null
        return try {
            val w = org.json.JSONObject(json).optJSONObject("weights") ?: return null
            doubleArrayOf(
                w.optDouble("similarity", 0.6),
                w.optDouble("importance", 0.3),
                w.optDouble("recency", 0.1)
            )
        } catch (_: Exception) { null }
    }

    /** Distinct chats with captured memory activity since the last backup
     *  (all captured chats if no backup has run yet). */
    fun chatsSinceLastBackup(): Int {
        val last = getMeta(META_LAST_AUTO_EXPORT_AT)
        val db = readableDatabase
        val cursor = if (last.isNullOrBlank()) {
            db.rawQuery("SELECT COUNT(DISTINCT chat_id) FROM transcripts WHERE chat_id IS NOT NULL", emptyArray<String>())
        } else {
            db.rawQuery(
                "SELECT COUNT(DISTINCT chat_id) FROM transcripts WHERE chat_id IS NOT NULL AND ended_at > ?",
                arrayOf(last)
            )
        }
        cursor.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Distinct chats with a transcript still awaiting Archivist review. */
    fun pendingReviewCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT chat_id) FROM transcripts WHERE review_status = 'pending' AND chat_id IS NOT NULL",
            emptyArray<String>()
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun recordDeletion(recordType: String, recordId: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO deleted_ids (record_type, record_id, deleted_at) VALUES (?, ?, ?)",
            arrayOf(recordType, recordId, nowIso())
        )
    }

    /* ---------------------------------------------------------------------- */
    /* companions (bootstrap + persona sync support)                          */
    /* ---------------------------------------------------------------------- */

    fun findCompanionByAppCharacterId(appCharacterId: String): CompanionRecord? {
        if (appCharacterId.isBlank()) return null
        readableDatabase.query(
            "companions", null, "app_character_id = ?", arrayOf(appCharacterId), null, null, null
        ).use {
            return if (it.moveToFirst()) readCompanion(it, includeHistory = false) else null
        }
    }

    fun getCompanions(): ArrayList<CompanionRecord> {
        val out = ArrayList<CompanionRecord>()
        readableDatabase.query("companions", null, null, null, null, null, "created_at ASC").use {
            while (it.moveToNext()) out.add(readCompanion(it, includeHistory = true))
        }
        return out
    }

    fun insertCompanion(record: CompanionRecord) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("companions", null, companionValues(record), SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("companion_name_history", "companion_id = ?", arrayOf(record.companionId))
            for (h in record.nameHistory) insertNameHistory(db, record.companionId, h)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * One-way app -> store sync (integration plan D6): re-point the app link
     * after a persona rename (persona ids are Hash.hash(label), so renames
     * change the id), refresh the personality mirror, and keep name_history
     * honest when the visible name changed.
     */
    fun updateCompanionForPersona(companionId: String, appCharacterId: String, label: String, mirrorText: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = nowIso()
            var currentName = ""
            db.query("companions", arrayOf("current_name"), "companion_id = ?", arrayOf(companionId), null, null, null).use {
                if (it.moveToFirst()) currentName = it.getString(0)
            }
            val values = ContentValues().apply {
                put("app_character_id", appCharacterId)
                put("base_personality_mirror_text", mirrorText)
                put("base_personality_mirror_synced_at", now)
                if (label.isNotBlank() && label != currentName) put("current_name", label)
            }
            db.update("companions", values, "companion_id = ?", arrayOf(companionId))

            if (label.isNotBlank() && label != currentName) {
                val until = ContentValues().apply { put("effective_until", now) }
                db.update(
                    "companion_name_history", until,
                    "companion_id = ? AND effective_until IS NULL", arrayOf(companionId)
                )
                insertNameHistory(db, companionId, NameHistoryEntry(label, now, null))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertNameHistory(db: SQLiteDatabase, companionId: String, h: NameHistoryEntry) {
        val values = ContentValues().apply {
            put("companion_id", companionId)
            put("name", h.name)
            put("effective_from", h.effectiveFrom)
            if (h.effectiveUntil != null) put("effective_until", h.effectiveUntil)
        }
        db.insert("companion_name_history", null, values)
    }

    private fun companionValues(c: CompanionRecord) = ContentValues().apply {
        put("companion_id", c.companionId)
        put("current_name", c.currentName)
        put("essence", c.essence)
        put("relationship_notes", c.relationshipNotes)
        put("memory_participation", c.memoryParticipation)
        put("hard_limits_json", c.hardLimitsJson)
        put("app_character_id", c.appCharacterId)
        put("base_personality_mirror_text", c.mirrorText)
        put("base_personality_mirror_synced_at", c.mirrorSyncedAt)
        put("model_adaptations_json", c.modelAdaptationsJson)
        put("created_at", c.createdAt)
        put("status", c.status)
        put("origin", c.origin)
    }

    private fun readCompanion(c: Cursor, includeHistory: Boolean): CompanionRecord {
        val id = c.getString(c.getColumnIndexOrThrow("companion_id"))
        return CompanionRecord(
            companionId = id,
            currentName = c.getString(c.getColumnIndexOrThrow("current_name")),
            essence = c.getString(c.getColumnIndexOrThrow("essence")) ?: "",
            relationshipNotes = c.getStringOrNull("relationship_notes"),
            memoryParticipation = c.getString(c.getColumnIndexOrThrow("memory_participation")) ?: "full",
            hardLimitsJson = c.getStringOrNull("hard_limits_json") ?: "[]",
            appCharacterId = c.getStringOrNull("app_character_id"),
            mirrorText = c.getStringOrNull("base_personality_mirror_text"),
            mirrorSyncedAt = c.getStringOrNull("base_personality_mirror_synced_at"),
            modelAdaptationsJson = c.getStringOrNull("model_adaptations_json") ?: "[]",
            createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
            status = c.getString(c.getColumnIndexOrThrow("status")),
            nameHistory = if (includeHistory) readNameHistory(id) else emptyList(),
            origin = c.getStringOrNull("origin") ?: "user"
        )
    }

    private fun readNameHistory(companionId: String): List<NameHistoryEntry> {
        val out = ArrayList<NameHistoryEntry>()
        readableDatabase.query(
            "companion_name_history", null, "companion_id = ?", arrayOf(companionId), null, null, "id ASC"
        ).use {
            while (it.moveToNext()) {
                out.add(
                    NameHistoryEntry(
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        effectiveFrom = it.getString(it.getColumnIndexOrThrow("effective_from")) ?: "",
                        effectiveUntil = it.getStringOrNull("effective_until")
                    )
                )
            }
        }
        return out
    }

    /* ---------------------------------------------------------------------- */
    /* import (insert-if-absent; singletons only on first seed)               */
    /* ---------------------------------------------------------------------- */

    /**
     * Imports schema-shaped data. Records are matched by primary key: missing
     * ones are added, existing ones are skipped and reported — never silently
     * overwritten (the old roadmap's Phase 2 rule; real merge arrives with the
     * sync phase). Singletons (owner profile, archivist settings, retrieval
     * policy) are taken only when [overwriteSingletons] is set — i.e. on the
     * first seed import into a fresh store.
     */
    fun importData(data: MemoryStoreData, overwriteSingletons: Boolean): ImportReport {
        val report = ImportReport()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (overwriteSingletons) {
                data.ownerProfile?.let { o ->
                    val values = ContentValues().apply {
                        put("id", 1)
                        put("portrait", o.portrait)
                        put("standing_context", o.standingContext)
                        put("updated_at", o.updatedAt)
                    }
                    db.insertWithOnConflict("owner_profile", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    report.addAdded("owner profile")
                }
                data.archivistSettings?.let { a ->
                    val values = ContentValues().apply {
                        put("id", 1)
                        put("run_trigger", a.runTrigger)
                        put("harvest_generosity", a.harvestGenerosity)
                        put("autonomy_json", a.autonomyJson)
                        put("notes", a.notes)
                    }
                    db.insertWithOnConflict("archivist_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    report.addAdded("archivist settings")
                }
                data.retrievalPolicyJson?.let { p ->
                    val values = ContentValues().apply {
                        put("id", 1)
                        put("policy_json", p)
                    }
                    db.insertWithOnConflict("retrieval_policy", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    report.addAdded("retrieval policy")
                }
            }

            for (c in data.companions) {
                if (rowExists(db, "companions", "companion_id", c.companionId)) {
                    report.addSkipped("companions"); continue
                }
                db.insert("companions", null, companionValues(c))
                for (h in c.nameHistory) insertNameHistory(db, c.companionId, h)
                report.addAdded("companions")
            }

            for (e in data.entities) {
                if (rowExists(db, "entities", "entity_id", e.entityId)) {
                    report.addSkipped("entities"); continue
                }
                db.insert("entities", null, ContentValues().apply {
                    put("entity_id", e.entityId)
                    put("kind", e.kind)
                    put("name", e.name)
                    put("aliases_json", e.aliasesJson)
                    put("summary", e.summary)
                    put("status", e.status)
                    put("importance", e.importance)
                    put("last_touched", e.lastTouched)
                    put("origin", e.origin)
                })
                report.addAdded("entities")
            }

            for (w in data.worlds) {
                if (rowExists(db, "worlds", "world_id", w.worldId)) {
                    report.addSkipped("worlds"); continue
                }
                db.insert("worlds", null, ContentValues().apply {
                    put("world_id", w.worldId)
                    put("name", w.name)
                    put("premise", w.premise)
                    put("rules", w.rules)
                    put("cosmology", w.cosmology)
                    put("premise_vibe", w.premiseVibe)
                    put("magic_rules", w.magicRules)
                    put("companion_ids_json", w.companionIdsJson)
                    put("status", w.status)
                    put("created_at", w.createdAt)
                })
                report.addAdded("worlds")
            }

            for (p in data.userPersonas) {
                if (rowExists(db, "user_personas", "persona_id", p.personaId)) {
                    report.addSkipped("user personas"); continue
                }
                db.insert("user_personas", null, ContentValues().apply {
                    put("persona_id", p.personaId)
                    put("name", p.name)
                    put("presentation", p.presentation)
                    put("status", p.status)
                    put("created_at", p.createdAt)
                })
                report.addAdded("user personas")
            }

            for (r in data.roleplayCharacters) {
                if (rowExists(db, "roleplay_characters", "roleplay_character_id", r.roleplayCharacterId)) {
                    report.addSkipped("roleplay characters"); continue
                }
                db.insert("roleplay_characters", null, ContentValues().apply {
                    put("roleplay_character_id", r.roleplayCharacterId)
                    put("name", r.name)
                    put("played_by", r.playedBy)
                    put("description", r.description)
                    put("arc", r.arc)
                    put("worlds_played_json", r.worldsPlayedJson)
                    put("status", r.status)
                    put("created_at", r.createdAt)
                    put("species", r.species)
                    put("char_class", r.charClass)
                    put("core_personality", r.corePersonality)
                    put("physical_description", r.physicalDescription)
                    put("goals_drives", r.goalsDrives)
                })
                report.addAdded("roleplay characters")
            }

            // Party members before campaigns: campaign_party_members
            // foreign-keys both ways (3.6a).
            for (p in data.partyMembers) {
                if (rowExists(db, "party_members", "party_member_id", p.partyMemberId)) {
                    report.addSkipped("party members"); continue
                }
                db.insert("party_members", null, ContentValues().apply {
                    put("party_member_id", p.partyMemberId)
                    put("name", p.name)
                    put("species", p.species)
                    put("char_class", p.charClass)
                    put("core_personality", p.corePersonality)
                    put("physical_description", p.physicalDescription)
                    put("goals_drives", p.goalsDrives)
                    put("speech_style", p.speechStyle)
                    put("status", p.status)
                    put("archived", if (p.archived) 1 else 0)
                    put("created_at", p.createdAt)
                    put("updated_at", p.updatedAt)
                })
                report.addAdded("party members")
            }

            // Campaigns before memories: memories.campaign_id foreign-keys here.
            for (c in data.campaigns) {
                if (rowExists(db, "campaigns", "campaign_id", c.campaignId)) {
                    report.addSkipped("campaigns"); continue
                }
                db.insert("campaigns", null, campaignValues(c))
                // Party links ride the campaign record in the export shape;
                // only ids the store actually has are linked, so a hand-edited
                // file can't break the whole import on one dangling id.
                for (pmId in c.partyMemberIds) {
                    if (rowExists(db, "party_members", "party_member_id", pmId)) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO campaign_party_members (campaign_id, party_member_id) VALUES (?, ?)",
                            arrayOf(c.campaignId, pmId)
                        )
                    }
                }
                report.addAdded("campaigns")
            }

            // Projects before memories: memories.project_id foreign-keys here.
            for (p in data.projects) {
                if (rowExists(db, "projects", "project_id", p.projectId)) {
                    report.addSkipped("projects"); continue
                }
                db.insert("projects", null, projectValues(p))
                report.addAdded("projects")
            }

            for (m in data.memories) {
                if (rowExists(db, "memories", "memory_id", m.memoryId)) {
                    report.addSkipped("memories"); continue
                }
                db.insert("memories", null, ContentValues().apply {
                    put("memory_id", m.memoryId)
                    put("scope", m.scope)
                    put("kind", m.kind)
                    put("title", m.title)
                    put("content", m.content)
                    put("embedding_text", m.embeddingText)
                    put("tags_json", m.tagsJson)
                    put("importance", m.importance)
                    // Primary-target mirror; full sets go to the join tables below.
                    put("world_id", m.worldIds.firstOrNull())
                    put("roleplay_character_id", m.roleplayCharacterIds.firstOrNull())
                    put("campaign_id", m.campaignIds.firstOrNull())
                    put("project_id", m.projectIds.firstOrNull())
                    put("protection_json", m.protectionJson)
                    put("mode_hints_json", m.modeHintsJson)
                    put("provenance_source", m.provenanceSource)
                    put("provenance_confidence", m.provenanceConfidence)
                    put("provenance_noted_on", m.provenanceNotedOn)
                    put("provenance_context", m.provenanceContext)
                    put("created_at", m.createdAt)
                    put("updated_at", m.updatedAt)
                    put("status", m.status)
                    put("supersedes", m.supersedes)
                    put("origin", m.origin)
                })
                writeLinkSet(db, "memory_companions", "companion_id", m.memoryId, m.companionIds)
                writeLinkSet(db, "memory_entities", "entity_id", m.memoryId, m.entityRefs)
                writeLinkSet(db, "memory_worlds", "world_id", m.memoryId, m.worldIds)
                writeLinkSet(db, "memory_campaigns", "campaign_id", m.memoryId, m.campaignIds)
                writeLinkSet(db, "memory_roleplay_characters", "roleplay_character_id", m.memoryId, m.roleplayCharacterIds)
                writeLinkSet(db, "memory_projects", "project_id", m.memoryId, m.projectIds)
                for (l in m.changeLog) {
                    db.insert("change_log", null, ContentValues().apply {
                        put("memory_id", m.memoryId)
                        put("at", l.at)
                        put("actor", l.actor)
                        put("action", l.action)
                        put("note", l.note)
                        put("prior_state_json", l.priorStateJson)
                    })
                }
                report.addAdded("memories")
            }

            for (m in data.modes) {
                if (rowExists(db, "modes", "mode_id", m.modeId)) {
                    report.addSkipped("modes"); continue
                }
                db.insert("modes", null, ContentValues().apply {
                    put("mode_id", m.modeId)
                    put("name", m.name)
                    put("purpose", m.purpose)
                    put("signals_json", m.signalsJson)
                    put("respond_json", m.respondJson)
                    put("avoid_json", m.avoidJson)
                    put("transition_note", m.transitionNote)
                    put("overrides_json", m.overridesJson)
                    put("scope", m.scope)
                    put("companion_ids_json", m.companionIdsJson)
                    put("origin", m.origin)
                })
                report.addAdded("modes")
            }

            for (d in data.directives) {
                if (rowExists(db, "directives", "directive_id", d.directiveId)) {
                    report.addSkipped("directives"); continue
                }
                db.insert("directives", null, ContentValues().apply {
                    put("directive_id", d.directiveId)
                    put("text", d.text)
                    put("rationale", d.rationale)
                    put("applies_to_json", d.appliesToJson)
                    put("priority", d.priority)
                    put("origin", d.origin)
                })
                report.addAdded("directives")
            }

            for (p in data.proposals) {
                if (rowExists(db, "proposals", "proposal_id", p.proposalId)) {
                    report.addSkipped("proposals"); continue
                }
                db.insert("proposals", null, ContentValues().apply {
                    put("proposal_id", p.proposalId)
                    put("target_type", p.targetType)
                    put("target_id", p.targetId)
                    put("summary", p.summary)
                    put("proposed_change_json", p.proposedChangeJson)
                    put("rationale", p.rationale)
                    put("status", p.status)
                    put("created_at", p.createdAt)
                    put("resolved_at", p.resolvedAt)
                })
                report.addAdded("proposals")
            }

            for (t in data.transcripts) {
                if (rowExists(db, "transcripts", "transcript_id", t.transcriptId)) {
                    report.addSkipped("transcripts"); continue
                }
                db.insert("transcripts", null, ContentValues().apply {
                    put("transcript_id", t.transcriptId)
                    put("chat_id", t.chatId)
                    put("companion_id", t.companionId)
                    put("world_id", t.worldId)
                    put("roleplay_character_id", t.roleplayCharacterId)
                    put("user_persona_id", t.userPersonaId)
                    put("source", t.source)
                    put("started_at", t.startedAt)
                    put("ended_at", t.endedAt)
                    put("content", t.content)
                    put("model_tag", t.modelTag)
                    put("quick_settings_json", t.quickSettingsJson)
                    put("review_status", t.reviewStatus)
                    put("processed_at", t.processedAt)
                })
                report.addAdded("transcripts")
            }

            // Roleplay card entries (3.6a). Their card/parent references are
            // soft by design, so entries import cleanly in any order.
            for (e in data.cardEntries) {
                if (rowExists(db, "card_entries", "entry_id", e.entryId)) {
                    report.addSkipped("card entries"); continue
                }
                db.insert("card_entries", null, cardEntryValues(e))
                report.addAdded("card entries")
            }

            // Roleplay tags (3.6a): matched by id first, then by name
            // (case-insensitive — the pool's dedup rule), so re-importing a
            // backup never duplicates a tag; the incoming tag's links are
            // re-pointed at the matching existing tag.
            for (t in data.rpTags) {
                var effectiveId = t.tagId
                if (rowExists(db, "rp_tags", "tag_id", t.tagId)) {
                    report.addSkipped("roleplay tags")
                } else {
                    var existingByName: String? = null
                    db.rawQuery(
                        "SELECT tag_id FROM rp_tags WHERE name = ? COLLATE NOCASE LIMIT 1",
                        arrayOf(t.name.trim())
                    ).use { if (it.moveToFirst()) existingByName = it.getString(0) }
                    if (existingByName != null) {
                        effectiveId = existingByName!!
                        report.addSkipped("roleplay tags")
                    } else {
                        db.insert("rp_tags", null, ContentValues().apply {
                            put("tag_id", t.tagId)
                            put("name", t.name.trim())
                            put("auto_trigger", if (t.autoTrigger) 1 else 0)
                            put("created_at", t.createdAt)
                        })
                        report.addAdded("roleplay tags")
                    }
                }
                for ((targetType, targetId) in t.targets) {
                    // Unknown target types (a hand-edited file) would trip the
                    // CHECK and abort the whole import — skip them instead.
                    if (targetType !in RpTagTargetType.ALL) continue
                    db.execSQL(
                        "INSERT OR IGNORE INTO rp_tag_links (tag_id, target_type, target_id) VALUES (?, ?, ?)",
                        arrayOf(effectiveId, targetType, targetId)
                    )
                }
            }

            // Model rules (Stage 4). Profiles before rules (FK); a rule whose
            // profile is missing from both the file and the store imports as
            // unassigned ("Needs review") instead of tripping the FK and
            // aborting the whole import.
            for (p in data.modelRuleProfiles) {
                if (rowExists(db, "model_rule_profiles", "profile_id", p.profileId)) {
                    report.addSkipped("model rule profiles"); continue
                }
                db.insert("model_rule_profiles", null, modelRuleProfileValues(p))
                report.addAdded("model rule profiles")
            }
            for (r in data.modelRules) {
                if (rowExists(db, "model_rules", "rule_id", r.ruleId)) {
                    report.addSkipped("model rules"); continue
                }
                val safeProfileId = r.profileId?.takeIf {
                    rowExists(db, "model_rule_profiles", "profile_id", it)
                }
                db.insert("model_rules", null, modelRuleValues(r.copy(profileId = safeProfileId)))
                report.addAdded("model rules")
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return report
    }

    private fun rowExists(db: SQLiteDatabase, table: String, pkColumn: String, id: String): Boolean {
        db.rawQuery("SELECT 1 FROM $table WHERE $pkColumn = ? LIMIT 1", arrayOf(id)).use {
            return it.moveToFirst()
        }
    }

    /* ---------------------------------------------------------------------- */
    /* export                                                                  */
    /* ---------------------------------------------------------------------- */

    fun exportData(): MemoryStoreData {
        val db = readableDatabase

        var owner: OwnerProfile? = null
        db.query("owner_profile", null, "id = 1", null, null, null, null).use {
            if (it.moveToFirst()) {
                owner = OwnerProfile(
                    portrait = it.getString(it.getColumnIndexOrThrow("portrait")),
                    standingContext = it.getStringOrNull("standing_context"),
                    updatedAt = it.getStringOrNull("updated_at")
                )
            }
        }

        val entities = ArrayList<EntityRecord>()
        db.query("entities", null, null, null, null, null, "entity_id ASC").use {
            while (it.moveToNext()) {
                entities.add(
                    EntityRecord(
                        entityId = it.getString(it.getColumnIndexOrThrow("entity_id")),
                        kind = it.getString(it.getColumnIndexOrThrow("kind")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        aliasesJson = it.getStringOrNull("aliases_json") ?: "[]",
                        summary = it.getString(it.getColumnIndexOrThrow("summary")),
                        status = it.getStringOrNull("status"),
                        importance = it.getInt(it.getColumnIndexOrThrow("importance")),
                        lastTouched = it.getStringOrNull("last_touched"),
                        origin = it.getStringOrNull("origin") ?: "user"
                    )
                )
            }
        }

        val worlds = ArrayList<WorldRecord>()
        db.query("worlds", null, null, null, null, null, "world_id ASC").use {
            while (it.moveToNext()) {
                worlds.add(
                    WorldRecord(
                        worldId = it.getString(it.getColumnIndexOrThrow("world_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        premise = it.getString(it.getColumnIndexOrThrow("premise")),
                        rules = it.getStringOrNull("rules"),
                        cosmology = it.getStringOrNull("cosmology"),
                        premiseVibe = it.getStringOrNull("premise_vibe"),
                        magicRules = it.getStringOrNull("magic_rules"),
                        companionIdsJson = it.getStringOrNull("companion_ids_json") ?: "[]",
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getStringOrNull("created_at")
                    )
                )
            }
        }

        val userPersonas = ArrayList<UserPersonaRecord>()
        db.query("user_personas", null, null, null, null, null, "persona_id ASC").use {
            while (it.moveToNext()) {
                userPersonas.add(
                    UserPersonaRecord(
                        personaId = it.getString(it.getColumnIndexOrThrow("persona_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        presentation = it.getString(it.getColumnIndexOrThrow("presentation")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getStringOrNull("created_at")
                    )
                )
            }
        }

        val roleplayCharacters = ArrayList<RoleplayCharacterRecord>()
        db.query("roleplay_characters", null, null, null, null, null, "roleplay_character_id ASC").use {
            while (it.moveToNext()) {
                roleplayCharacters.add(
                    RoleplayCharacterRecord(
                        roleplayCharacterId = it.getString(it.getColumnIndexOrThrow("roleplay_character_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        playedBy = it.getString(it.getColumnIndexOrThrow("played_by")),
                        description = it.getString(it.getColumnIndexOrThrow("description")),
                        arc = it.getStringOrNull("arc"),
                        worldsPlayedJson = it.getStringOrNull("worlds_played_json") ?: "[]",
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getStringOrNull("created_at"),
                        species = it.getStringOrNull("species"),
                        charClass = it.getStringOrNull("char_class"),
                        corePersonality = it.getStringOrNull("core_personality"),
                        physicalDescription = it.getStringOrNull("physical_description"),
                        goalsDrives = it.getStringOrNull("goals_drives")
                    )
                )
            }
        }

        val memories = ArrayList<MemoryRecord>()
        db.query("memories", null, null, null, null, null, "created_at ASC").use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow("memory_id"))
                memories.add(
                    MemoryRecord(
                        memoryId = id,
                        scope = it.getString(it.getColumnIndexOrThrow("scope")),
                        kind = it.getString(it.getColumnIndexOrThrow("kind")),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        embeddingText = it.getStringOrNull("embedding_text"),
                        tagsJson = it.getStringOrNull("tags_json") ?: "[]",
                        importance = it.getInt(it.getColumnIndexOrThrow("importance")),
                        worldIds = readJoin(db, "memory_worlds", "world_id", id),
                        roleplayCharacterIds = readJoin(db, "memory_roleplay_characters", "roleplay_character_id", id),
                        campaignIds = readJoin(db, "memory_campaigns", "campaign_id", id),
                        projectIds = readJoin(db, "memory_projects", "project_id", id),
                        protectionJson = it.getStringOrNull("protection_json"),
                        modeHintsJson = it.getStringOrNull("mode_hints_json") ?: "[]",
                        provenanceSource = it.getStringOrNull("provenance_source"),
                        provenanceConfidence = it.getStringOrNull("provenance_confidence"),
                        provenanceNotedOn = it.getStringOrNull("provenance_noted_on"),
                        provenanceContext = it.getStringOrNull("provenance_context"),
                        createdAt = it.getString(it.getColumnIndexOrThrow("created_at")),
                        updatedAt = it.getStringOrNull("updated_at"),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        supersedes = it.getStringOrNull("supersedes"),
                        companionIds = readJoin(db, "memory_companions", "companion_id", id),
                        entityRefs = readJoin(db, "memory_entities", "entity_id", id),
                        changeLog = readChangeLog(db, id),
                        origin = it.getStringOrNull("origin") ?: "user"
                    )
                )
            }
        }

        val modes = ArrayList<ModeRecord>()
        db.query("modes", null, null, null, null, null, "mode_id ASC").use {
            while (it.moveToNext()) {
                modes.add(
                    ModeRecord(
                        modeId = it.getString(it.getColumnIndexOrThrow("mode_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        purpose = it.getStringOrNull("purpose"),
                        signalsJson = it.getStringOrNull("signals_json") ?: "[]",
                        respondJson = it.getStringOrNull("respond_json") ?: "[]",
                        avoidJson = it.getStringOrNull("avoid_json") ?: "[]",
                        transitionNote = it.getStringOrNull("transition_note"),
                        overridesJson = it.getStringOrNull("overrides_json") ?: "[]",
                        scope = it.getStringOrNull("scope") ?: "global",
                        companionIdsJson = it.getStringOrNull("companion_ids_json") ?: "[]",
                        origin = it.getStringOrNull("origin") ?: "user"
                    )
                )
            }
        }

        val directives = ArrayList<DirectiveRecord>()
        db.query("directives", null, null, null, null, null, "priority ASC, directive_id ASC").use {
            while (it.moveToNext()) {
                directives.add(
                    DirectiveRecord(
                        directiveId = it.getString(it.getColumnIndexOrThrow("directive_id")),
                        text = it.getString(it.getColumnIndexOrThrow("text")),
                        rationale = it.getStringOrNull("rationale"),
                        appliesToJson = it.getStringOrNull("applies_to_json") ?: "[]",
                        priority = it.getInt(it.getColumnIndexOrThrow("priority")),
                        origin = it.getStringOrNull("origin") ?: "user"
                    )
                )
            }
        }

        var archivist: ArchivistSettingsRecord? = null
        db.query("archivist_settings", null, "id = 1", null, null, null, null).use {
            if (it.moveToFirst()) {
                archivist = ArchivistSettingsRecord(
                    runTrigger = it.getString(it.getColumnIndexOrThrow("run_trigger")),
                    harvestGenerosity = it.getString(it.getColumnIndexOrThrow("harvest_generosity")),
                    autonomyJson = it.getString(it.getColumnIndexOrThrow("autonomy_json")),
                    notes = it.getStringOrNull("notes")
                )
            }
        }

        val proposals = ArrayList<ProposalRecord>()
        db.query("proposals", null, null, null, null, null, "created_at ASC").use {
            while (it.moveToNext()) {
                proposals.add(
                    ProposalRecord(
                        proposalId = it.getString(it.getColumnIndexOrThrow("proposal_id")),
                        targetType = it.getString(it.getColumnIndexOrThrow("target_type")),
                        targetId = it.getStringOrNull("target_id"),
                        summary = it.getString(it.getColumnIndexOrThrow("summary")),
                        proposedChangeJson = it.getStringOrNull("proposed_change_json"),
                        rationale = it.getStringOrNull("rationale"),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getString(it.getColumnIndexOrThrow("created_at")),
                        resolvedAt = it.getStringOrNull("resolved_at")
                    )
                )
            }
        }

        var retrievalPolicy: String? = null
        db.query("retrieval_policy", null, "id = 1", null, null, null, null).use {
            if (it.moveToFirst()) retrievalPolicy = it.getString(it.getColumnIndexOrThrow("policy_json"))
        }

        val transcripts = ArrayList<TranscriptRecord>()
        db.query("transcripts", null, null, null, null, null, "started_at ASC").use {
            while (it.moveToNext()) {
                transcripts.add(
                    TranscriptRecord(
                        transcriptId = it.getString(it.getColumnIndexOrThrow("transcript_id")),
                        chatId = it.getStringOrNull("chat_id"),
                        companionId = it.getStringOrNull("companion_id"),
                        worldId = it.getStringOrNull("world_id"),
                        roleplayCharacterId = it.getStringOrNull("roleplay_character_id"),
                        userPersonaId = it.getStringOrNull("user_persona_id"),
                        source = it.getString(it.getColumnIndexOrThrow("source")),
                        startedAt = it.getStringOrNull("started_at"),
                        endedAt = it.getStringOrNull("ended_at"),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        modelTag = it.getStringOrNull("model_tag"),
                        quickSettingsJson = it.getStringOrNull("quick_settings_json"),
                        reviewStatus = it.getString(it.getColumnIndexOrThrow("review_status")),
                        processedAt = it.getStringOrNull("processed_at")
                    )
                )
            }
        }

        val campaigns = readCampaigns(null, null)
        val projects = readProjects(null, null)

        // Roleplay cards + tags (3.6a): backups must carry the card layer or
        // the Reset-memories "save a backup first" path would lose it.
        val partyMembers = getPartyMembers(includeArchived = true)
        val cardEntries = ArrayList<CardEntryRecord>()
        db.query("card_entries", null, null, null, null, null, "card_type ASC, card_id ASC, section ASC, name ASC").use {
            while (it.moveToNext()) cardEntries.add(readCardEntry(it))
        }
        val rpTags = getAllRpTags().map { it.copy(targets = targetsForTag(it.tagId)) }

        // Model rules (Stage 4): user-authored, so they travel in backups too.
        val modelRuleProfiles = getModelRuleProfiles()
        val modelRules = ArrayList<ModelRuleRecord>()
        db.query("model_rules", null, null, null, null, null, "created_at ASC, rule_id ASC").use {
            while (it.moveToNext()) modelRules.add(readModelRule(it))
        }

        return MemoryStoreData(
            schemaVersion = getMeta(META_SCHEMA_VERSION) ?: "1.11.0",
            ownerProfile = owner,
            companions = getCompanions(),
            entities = entities,
            memories = memories,
            modes = modes,
            directives = directives,
            worlds = worlds,
            userPersonas = userPersonas,
            roleplayCharacters = roleplayCharacters,
            archivistSettings = archivist,
            proposals = proposals,
            retrievalPolicyJson = retrievalPolicy,
            transcripts = transcripts,
            campaigns = campaigns,
            projects = projects,
            partyMembers = partyMembers,
            cardEntries = cardEntries,
            rpTags = rpTags,
            modelRuleProfiles = modelRuleProfiles,
            modelRules = modelRules
        )
    }

    private fun readJoin(db: SQLiteDatabase, table: String, column: String, memoryId: String): List<String> {
        val out = ArrayList<String>()
        db.query(table, arrayOf(column), "memory_id = ?", arrayOf(memoryId), null, null, "$column ASC").use {
            while (it.moveToNext()) out.add(it.getString(0))
        }
        return out
    }

    private fun readChangeLog(db: SQLiteDatabase, memoryId: String): List<ChangeLogEntry> {
        val out = ArrayList<ChangeLogEntry>()
        db.query("change_log", null, "memory_id = ?", arrayOf(memoryId), null, null, "id ASC").use {
            while (it.moveToNext()) {
                out.add(
                    ChangeLogEntry(
                        at = it.getString(it.getColumnIndexOrThrow("at")),
                        actor = it.getString(it.getColumnIndexOrThrow("actor")),
                        action = it.getString(it.getColumnIndexOrThrow("action")),
                        note = it.getStringOrNull("note"),
                        priorStateJson = it.getStringOrNull("prior_state_json")
                    )
                )
            }
        }
        return out
    }

    /* ---------------------------------------------------------------------- */
    /* transcripts (Phase 2: capture queue for the Archivist)                 */
    /* ---------------------------------------------------------------------- */

    /**
     * Appends one completed turn to the chat's open transcript row (creating
     * one when needed). "Open" = the chat's newest unprocessed row, still
     * served by the same model and companion and under the size cap — a change
     * of model or companion, or an oversized row, starts a new row so each
     * transcript's model_tag/companion_id stay truthful for the Archivist.
     * [markExcluded] implements the memory kill switch: content is still
     * captured (so exclusion is reversible and the experiment can be
     * recovered) but the row is marked do-not-review.
     */
    /** Returns a short outcome string for the Event Log (capture is otherwise
     *  invisible): "inserted <id>", "appended <id>", or "insert failed (rc)". */
    fun appendTranscriptTurn(
        chatId: String,
        companionId: String?,
        userMessage: String,
        assistantMessage: String,
        modelTag: String,
        quickSettingsJson: String?,
        markExcluded: Boolean
    ): String {
        val now = nowIso()
        val db = writableDatabase
        db.beginTransaction()
        try {
            var rowId: String? = null
            var content = "[]"
            db.query(
                "transcripts", arrayOf("transcript_id", "content", "model_tag", "companion_id", "review_status"),
                "chat_id = ? AND processed_at IS NULL", arrayOf(chatId),
                null, null, "started_at DESC", "1"
            ).use {
                if (it.moveToFirst()) {
                    val sameModel = it.getStringOrNull("model_tag") == modelTag
                    val sameCompanion = it.getStringOrNull("companion_id") == companionId
                    val existing = it.getString(it.getColumnIndexOrThrow("content"))
                    if (sameModel && sameCompanion && existing.length < MAX_TRANSCRIPT_CHARS) {
                        rowId = it.getString(0)
                        content = existing
                    }
                }
            }

            val turns = org.json.JSONArray(content)
            turns.put(org.json.JSONObject().put("role", "user").put("content", userMessage).put("at", now))
            turns.put(org.json.JSONObject().put("role", "assistant").put("content", assistantMessage).put("at", now))

            val outcome: String
            if (rowId == null) {
                val newRowId = newId("t-")
                // insertOrThrow so a constraint failure surfaces as an exception
                // (and rolls back) instead of silently returning -1 — a silent
                // failed insert is exactly what "0 transcripts" would look like.
                db.insertOrThrow("transcripts", null, ContentValues().apply {
                    put("transcript_id", newRowId)
                    put("chat_id", chatId)
                    put("companion_id", companionId)
                    put("source", "live")
                    put("started_at", now)
                    put("ended_at", now)
                    put("content", turns.toString())
                    put("model_tag", modelTag)
                    put("quick_settings_json", quickSettingsJson)
                    put("review_status", if (markExcluded) "excluded" else "pending")
                })
                outcome = "inserted $newRowId (${if (markExcluded) "excluded" else "pending"})"
            } else {
                db.update("transcripts", ContentValues().apply {
                    put("content", turns.toString())
                    put("ended_at", now)
                    put("quick_settings_json", quickSettingsJson)
                    if (markExcluded) put("review_status", "excluded")
                }, "transcript_id = ?", arrayOf(rowId))
                outcome = "appended $rowId"
            }
            db.setTransactionSuccessful()
            return outcome
        } finally {
            db.endTransaction()
        }
    }

    /**
     * User exclusion toggle: excluding marks every unprocessed row
     * do-not-review; re-including re-queues them as pending (processed rows
     * are history and never change). Capture start/stop is the recorder's job.
     */
    fun setChatTranscriptsExcluded(chatId: String, excluded: Boolean) {
        if (excluded) {
            writableDatabase.execSQL(
                "UPDATE transcripts SET review_status = 'excluded' WHERE chat_id = ? AND review_status = 'pending'",
                arrayOf(chatId)
            )
        } else {
            writableDatabase.execSQL(
                "UPDATE transcripts SET review_status = 'pending' WHERE chat_id = ? AND review_status = 'excluded' AND processed_at IS NULL",
                arrayOf(chatId)
            )
        }
    }

    /* ---------------------------------------------------------------------- */
    /* enforcer (Phase 4): targeted single-purpose readers                     */
    /*                                                                         */
    /* The enforcer assembles a prompt on EVERY turn; it must never pay for    */
    /* exportData()'s full-store walk (transcripts alone can be megabytes).    */
    /* These readers fetch exactly what one assembly needs.                    */
    /* ---------------------------------------------------------------------- */

    fun getOwnerProfile(): OwnerProfile? {
        readableDatabase.query("owner_profile", null, "id = 1", null, null, null, null).use {
            if (!it.moveToFirst()) return null
            return OwnerProfile(
                portrait = it.getString(it.getColumnIndexOrThrow("portrait")),
                standingContext = it.getStringOrNull("standing_context"),
                updatedAt = it.getStringOrNull("updated_at")
            )
        }
    }

    /** Directives, most binding first (priority 5 outranks 1 in the packet). */
    fun getDirectives(): List<DirectiveRecord> {
        val out = ArrayList<DirectiveRecord>()
        readableDatabase.query("directives", null, null, null, null, null, "priority DESC, directive_id ASC").use {
            while (it.moveToNext()) {
                out.add(
                    DirectiveRecord(
                        directiveId = it.getString(it.getColumnIndexOrThrow("directive_id")),
                        text = it.getString(it.getColumnIndexOrThrow("text")),
                        rationale = it.getStringOrNull("rationale"),
                        appliesToJson = it.getStringOrNull("applies_to_json") ?: "[]",
                        priority = it.getInt(it.getColumnIndexOrThrow("priority")),
                        origin = it.getStringOrNull("origin") ?: "user"
                    )
                )
            }
        }
        return out
    }

    fun getModes(): List<ModeRecord> {
        val out = ArrayList<ModeRecord>()
        readableDatabase.query("modes", null, null, null, null, null, "mode_id ASC").use {
            while (it.moveToNext()) {
                out.add(
                    ModeRecord(
                        modeId = it.getString(it.getColumnIndexOrThrow("mode_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        purpose = it.getStringOrNull("purpose"),
                        signalsJson = it.getStringOrNull("signals_json") ?: "[]",
                        respondJson = it.getStringOrNull("respond_json") ?: "[]",
                        avoidJson = it.getStringOrNull("avoid_json") ?: "[]",
                        transitionNote = it.getStringOrNull("transition_note"),
                        overridesJson = it.getStringOrNull("overrides_json") ?: "[]",
                        scope = it.getStringOrNull("scope") ?: "global",
                        companionIdsJson = it.getStringOrNull("companion_ids_json") ?: "[]",
                        origin = it.getStringOrNull("origin") ?: "user"
                    )
                )
            }
        }
        return out
    }

    /** One-shot provisioning of the enforcer's operating defaults: only ever
     *  inserts when the policy row is empty, so a user-authored (or imported)
     *  policy is never overwritten. No default MODES are provisioned any more —
     *  the app pre-authors no memory content (owner_approved_rules.md §15); the
     *  modes table stays empty until the user fills it. */
    fun provisionOperatingDefaults(policyJson: String): Boolean {
        val db = writableDatabase
        var provisioned = false
        db.beginTransaction()
        try {
            val hasPolicy = db.rawQuery("SELECT 1 FROM retrieval_policy WHERE id = 1", emptyArray<String>())
                .use { it.moveToFirst() }
            if (!hasPolicy) {
                db.execSQL("INSERT INTO retrieval_policy (id, policy_json) VALUES (1, ?)", arrayOf(policyJson))
                provisioned = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return provisioned
    }

    fun getRetrievalPolicyJson(): String? {
        readableDatabase.query("retrieval_policy", arrayOf("policy_json"), "id = 1", null, null, null, null).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun getWorld(worldId: String): WorldRecord? =
        readWorlds("world_id = ?", arrayOf(worldId)).firstOrNull()

    fun getActiveWorlds(): List<WorldRecord> =
        readWorlds("status = 'active'", null)

    private fun readWorlds(selection: String?, args: Array<String>?): List<WorldRecord> {
        val out = ArrayList<WorldRecord>()
        readableDatabase.query("worlds", null, selection, args, null, null, "name ASC").use {
            while (it.moveToNext()) {
                out.add(
                    WorldRecord(
                        worldId = it.getString(it.getColumnIndexOrThrow("world_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        premise = it.getString(it.getColumnIndexOrThrow("premise")),
                        rules = it.getStringOrNull("rules"),
                        cosmology = it.getStringOrNull("cosmology"),
                        premiseVibe = it.getStringOrNull("premise_vibe"),
                        magicRules = it.getStringOrNull("magic_rules"),
                        companionIdsJson = it.getStringOrNull("companion_ids_json") ?: "[]",
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getStringOrNull("created_at")
                    )
                )
            }
        }
        return out
    }

    fun getUserPersona(personaId: String): UserPersonaRecord? =
        readUserPersonas("persona_id = ?", arrayOf(personaId)).firstOrNull()

    fun getActiveUserPersonas(): List<UserPersonaRecord> =
        readUserPersonas("status = 'active'", null)

    private fun readUserPersonas(selection: String?, args: Array<String>?): List<UserPersonaRecord> {
        val out = ArrayList<UserPersonaRecord>()
        readableDatabase.query("user_personas", null, selection, args, null, null, "name ASC").use {
            while (it.moveToNext()) {
                out.add(
                    UserPersonaRecord(
                        personaId = it.getString(it.getColumnIndexOrThrow("persona_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        presentation = it.getString(it.getColumnIndexOrThrow("presentation")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getStringOrNull("created_at")
                    )
                )
            }
        }
        return out
    }

    fun getRoleplayCharacter(id: String): RoleplayCharacterRecord? =
        readRoleplayCharacters("roleplay_character_id = ?", arrayOf(id)).firstOrNull()

    fun getActiveRoleplayCharacters(): List<RoleplayCharacterRecord> =
        readRoleplayCharacters("status = 'active'", null)

    private fun readRoleplayCharacters(selection: String?, args: Array<String>?): List<RoleplayCharacterRecord> {
        val out = ArrayList<RoleplayCharacterRecord>()
        readableDatabase.query("roleplay_characters", null, selection, args, null, null, "name ASC").use {
            while (it.moveToNext()) {
                out.add(
                    RoleplayCharacterRecord(
                        roleplayCharacterId = it.getString(it.getColumnIndexOrThrow("roleplay_character_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        playedBy = it.getString(it.getColumnIndexOrThrow("played_by")),
                        description = it.getString(it.getColumnIndexOrThrow("description")),
                        arc = it.getStringOrNull("arc"),
                        worldsPlayedJson = it.getStringOrNull("worlds_played_json") ?: "[]",
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getStringOrNull("created_at"),
                        species = it.getStringOrNull("species"),
                        charClass = it.getStringOrNull("char_class"),
                        corePersonality = it.getStringOrNull("core_personality"),
                        physicalDescription = it.getStringOrNull("physical_description"),
                        goalsDrives = it.getStringOrNull("goals_drives")
                    )
                )
            }
        }
        return out
    }

    /** D8: prefs are the source of truth for what's active; app_state is the
     *  derived mirror the enforcer/Archivist read. Refreshed at generation time. */
    fun updateAppState(
        companionId: String?,
        worldId: String?,
        roleplayCharacterId: String?,
        userPersonaId: String?
    ) {
        writableDatabase.execSQL(
            "UPDATE app_state SET active_companion_id = ?, active_world_id = ?, " +
                "active_roleplay_character_id = ?, active_user_persona_id = ? WHERE id = 1",
            arrayOf(companionId, worldId, roleplayCharacterId, userPersonaId)
        )
    }

    /** Entity-linked expansion (enforcer spec): summaries of entities referenced
     *  by the retrieved memories, each entity once, keyed by entity name. */
    fun entitySummariesForMemories(memoryIds: Collection<String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (memoryIds.isEmpty()) return out
        val placeholders = memoryIds.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT DISTINCT e.name, e.summary FROM entities e " +
                "JOIN memory_entities me ON me.entity_id = e.entity_id " +
                "WHERE me.memory_id IN ($placeholders) ORDER BY e.name ASC",
            memoryIds.toTypedArray()
        ).use {
            while (it.moveToNext()) out[it.getString(0)] = it.getString(1)
        }
        return out
    }

    /* ---------------------------------------------------------------------- */
    /* librarian: retrievable memories + embeddings sidecar (Phase 3)         */
    /* ---------------------------------------------------------------------- */

    /**
     * The single eligibility gate every retrieval (and Phase 4 injection) goes
     * through, rewritten in Stage 3.1 to the owner-approved seven-category
     * scope model (rules §1, §3, §4 rev 3). Scope isolation is enforced IN THE
     * QUERY (not by convention, per the spec's non-negotiable), and named
     * targets are read from the §2 multi-select join tables — a memory linked
     * to several worlds/campaigns/RP characters is eligible under each of them.
     *
     * Ordinary (non-roleplay) chat — no world/campaign/RP character selected:
     *  - global and real_life memories;
     *  - companion memories of the chat's active companion (and only when that
     *    companion is past 'draft' — an unapproved companion's memories never
     *    reach a live prompt);
     *  - ALL project memories: eligible on semantic relevance even with no
     *    project selected (§4 rev 3 — selection is a ranking boost upstream,
     *    never a gate here).
     *
     * Roleplay context — any of world/campaign/RP character selected:
     *  - global memories (that is what Global means, §3);
     *  - world/campaign/rp_character memories linked to the SELECTED targets;
     *  - real_life memories are BLOCKED — the fiction wall (§3, no exceptions;
     *    the Off/OOC-only/Always per-chat setting is future work);
     *  - project memories are BLOCKED by default (§4 rev 3);
     *  - companion memories only when [RetrievalScope.allowCompanionInRoleplay]
     *    — the narrator/GM match from Stage 3.0 or the global §3 toggle.
     *
     * Draft/archived/superseded rows are never eligible (§9): status='active'.
     */
    fun activeMemoriesForScope(scope: RetrievalScope): List<RetrievableMemory> {
        val out = ArrayList<RetrievableMemory>()
        val args = ArrayList<String>()
        val branches = ArrayList<String>()

        branches.add("m.scope = 'global'")

        val companionBranchAllowed =
            scope.companionId != null && (!scope.isRoleplay || scope.allowCompanionInRoleplay)
        if (companionBranchAllowed) {
            branches.add(
                "(m.scope = 'companion' AND EXISTS (SELECT 1 FROM memory_companions mc " +
                    "JOIN companions c ON c.companion_id = mc.companion_id " +
                    "WHERE mc.memory_id = m.memory_id AND mc.companion_id = ? AND c.status != 'draft'))"
            )
            args.add(scope.companionId!!)
        }

        if (scope.isRoleplay) {
            if (scope.worldId != null) {
                branches.add(
                    "(m.scope = 'world' AND EXISTS (SELECT 1 FROM memory_worlds mw " +
                        "WHERE mw.memory_id = m.memory_id AND mw.world_id = ?))"
                )
                args.add(scope.worldId)
            }
            if (scope.campaignId != null) {
                branches.add(
                    "(m.scope = 'campaign' AND EXISTS (SELECT 1 FROM memory_campaigns mcam " +
                        "WHERE mcam.memory_id = m.memory_id AND mcam.campaign_id = ?))"
                )
                args.add(scope.campaignId)
            }
            if (scope.roleplayCharacterId != null) {
                branches.add(
                    "(m.scope = 'rp_character' AND EXISTS (SELECT 1 FROM memory_roleplay_characters mrc " +
                        "WHERE mrc.memory_id = m.memory_id AND mrc.roleplay_character_id = ?))"
                )
                args.add(scope.roleplayCharacterId)
            }
        } else {
            branches.add("m.scope = 'real_life'")
            branches.add("m.scope = 'project'")
        }

        val sql = "SELECT m.memory_id, m.scope, m.title, m.content, m.embedding_text, " +
            "m.importance, m.created_at, m.world_id, m.provenance_confidence, " +
            "m.protection_json, m.provenance_source, m.kind, m.tags_json " +
            "FROM memories m WHERE m.status = 'active' AND (" +
            branches.joinToString(" OR ") + ")"
        readableDatabase.rawQuery(sql, args.toTypedArray()).use {
            while (it.moveToNext()) out.add(readRetrievable(it))
        }
        return out
    }

    /** Memory ids linked to a project via the §2 multi-select join table — the
     *  Stage 3.2 ranking boost for the chat's selected project reads this. */
    fun memoryIdsForProject(projectId: String): HashSet<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT memory_id FROM memory_projects WHERE project_id = ?", arrayOf(projectId)
        ).use {
            while (it.moveToNext()) out.add(it.getString(0))
        }
        return out
    }

    /* ---------------------------------------------------------------------- */
    /* freshness cooldown (rules §10 / Stage 3.3)                              */
    /* ---------------------------------------------------------------------- */

    /** Advance and return this chat's turn clock — one tick per assembled
     *  turn. Monotonic and persisted, so the cooldown survives process death. */
    fun nextTurnNumber(chatId: String): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO chat_turn_counters (chat_id, turn) VALUES (?, 1) " +
                    "ON CONFLICT(chat_id) DO UPDATE SET turn = turn + 1",
                arrayOf(chatId)
            )
            val turn = db.rawQuery(
                "SELECT turn FROM chat_turn_counters WHERE chat_id = ?", arrayOf(chatId)
            ).use { if (it.moveToFirst()) it.getLong(0) else 1L }
            db.setTransactionSuccessful()
            return turn
        } finally {
            db.endTransaction()
        }
    }

    /** The turn each entry was last injected on in this chat (absent = never —
     *  new entries always inject on first relevance, §10). */
    fun lastInjectedTurns(
        chatId: String, sourceType: String, entryIds: Collection<String>
    ): HashMap<String, Long> {
        val out = HashMap<String, Long>()
        if (entryIds.isEmpty()) return out
        val placeholders = entryIds.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT entry_id, last_injected_turn FROM injection_cooldowns " +
                "WHERE chat_id = ? AND source_type = ? AND entry_id IN ($placeholders)",
            (listOf(chatId, sourceType) + entryIds).toTypedArray()
        ).use {
            while (it.moveToNext()) out[it.getString(0)] = it.getLong(1)
        }
        return out
    }

    /** Stamp the entries that made it into this turn's prompt. */
    fun recordInjections(
        chatId: String, sourceType: String, entryIds: Collection<String>, turn: Long
    ) {
        if (entryIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = nowIso()
            for (id in entryIds) {
                db.execSQL(
                    "INSERT OR REPLACE INTO injection_cooldowns " +
                        "(chat_id, source_type, entry_id, last_injected_turn, last_injected_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    arrayOf(chatId, sourceType, id, turn, now)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** §10: an edited entry resets its clock and re-injects fresh — clears the
     *  entry's cooldown rows across every chat. Hooked into the memory edit
     *  paths below; must run inside their transaction when called from one. */
    private fun clearEntryCooldownTx(db: SQLiteDatabase, sourceType: String, entryId: String) {
        db.delete("injection_cooldowns", "source_type = ? AND entry_id = ?", arrayOf(sourceType, entryId))
    }

    /**
     * Debug inspector search (the Memory settings box): a plain LIKE scan
     * across EVERY record type — memories, companions, entities, roleplay
     * characters, worlds — not just memories, so the user can confirm anything
     * they put in the store actually landed. Returns (label, snippet) pairs.
     * This is intentionally separate from [Librarian.search], which stays
     * memories-only because only memories are injected into conversations.
     */
    fun debugSearchAll(query: String, limit: Int): List<Pair<String, String>> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val like = "%${q.replace("%", "").replace("_", "")}%"
        val out = ArrayList<Pair<String, String>>()
        val db = readableDatabase

        fun scan(sql: String, label: (android.database.Cursor) -> String, snippet: (android.database.Cursor) -> String) {
            db.rawQuery(sql, arrayOf(like, like)).use {
                while (it.moveToNext() && out.size < limit) out.add(label(it) to snippet(it))
            }
        }

        // Labels carry status + origin + provenance so the owner can tell a
        // real user memory from a seed example or an archived draft at a
        // glance (seed-safety audit requirement). Non-active memories are
        // shown too — with their status — so "archived but still present"
        // is verifiable from the debug box.
        scan(
            "SELECT title, content, scope, status, origin, provenance_source FROM memories " +
                "WHERE title LIKE ? OR content LIKE ? LIMIT $limit",
            {
                val status = it.getString(3)
                val origin = it.getString(4)
                val prov = it.getString(5)
                val marks = StringBuilder()
                if (status != "active") marks.append(" [").append(status).append("]")
                if (origin != "user") marks.append(" [").append(origin).append("]")
                if (!prov.isNullOrBlank()) marks.append(" (").append(prov).append(")")
                "Memory · ${it.getString(2)}$marks: ${it.getString(0)}"
            },
            { it.getString(1) }
        )
        scan(
            "SELECT current_name, essence, status, origin FROM companions " +
                "WHERE current_name LIKE ? OR essence LIKE ? LIMIT $limit",
            {
                val marks = StringBuilder()
                if (it.getString(2) != "active") marks.append(" [").append(it.getString(2)).append("]")
                if (it.getString(3) != "user") marks.append(" [").append(it.getString(3)).append("]")
                "Companion$marks: ${it.getString(0)}"
            },
            { it.getString(1) ?: "" }
        )
        scan(
            "SELECT name, summary, origin FROM entities WHERE name LIKE ? OR summary LIKE ? LIMIT $limit",
            {
                val marks = if (it.getString(2) != "user") " [${it.getString(2)}]" else ""
                "Entity$marks: ${it.getString(0)}"
            },
            { it.getString(1) ?: "" }
        )
        scan(
            "SELECT name, description FROM roleplay_characters WHERE name LIKE ? OR description LIKE ? LIMIT $limit",
            { "Roleplay character: ${it.getString(0)}" }, { it.getString(1) ?: "" }
        )
        scan(
            "SELECT name, premise FROM worlds WHERE name LIKE ? OR premise LIKE ? LIMIT $limit",
            { "World: ${it.getString(0)}" }, { it.getString(1) ?: "" }
        )
        return out.take(limit)
    }

    /** Every active memory, ignoring scope — used to (re)build the whole index. */
    fun allActiveMemories(): List<RetrievableMemory> {
        val out = ArrayList<RetrievableMemory>()
        readableDatabase.query(
            "memories",
            arrayOf("memory_id", "scope", "title", "content", "embedding_text",
                "importance", "created_at", "world_id", "provenance_confidence",
                "protection_json", "provenance_source", "kind", "tags_json"),
            "status = 'active'", null, null, null, "created_at ASC"
        ).use {
            while (it.moveToNext()) out.add(readRetrievable(it))
        }
        return out
    }

    private fun readRetrievable(c: Cursor): RetrievableMemory = RetrievableMemory(
        memoryId = c.getString(c.getColumnIndexOrThrow("memory_id")),
        scope = c.getString(c.getColumnIndexOrThrow("scope")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        embeddingText = c.getStringOrNull("embedding_text"),
        importance = c.getInt(c.getColumnIndexOrThrow("importance")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        worldId = c.getStringOrNull("world_id"),
        provenanceConfidence = c.getStringOrNull("provenance_confidence"),
        protectionJson = c.getStringOrNull("protection_json"),
        provenanceSource = c.getStringOrNull("provenance_source"),
        kind = c.getStringOrNull("kind") ?: "fact",
        tagsJson = c.getStringOrNull("tags_json") ?: "[]"
    )

    /** Stored vectors for [embeddingModel] over the active memories, keyed by
     *  memory id — the working set brute-force cosine search reads each turn. */
    fun activeEmbeddings(embeddingModel: String): HashMap<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        readableDatabase.rawQuery(
            "SELECT e.memory_id, e.vector FROM embeddings e " +
                "JOIN memories m ON m.memory_id = e.memory_id " +
                "WHERE e.embedding_model = ? AND m.status = 'active'",
            arrayOf(embeddingModel)
        ).use {
            while (it.moveToNext()) out[it.getString(0)] = it.getBlob(1)
        }
        return out
    }

    fun upsertEmbedding(memoryId: String, embeddingModel: String, vector: ByteArray) {
        writableDatabase.execSQL(
            "INSERT INTO embeddings (memory_id, embedding_model, vector, embedded_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(memory_id, embedding_model) DO UPDATE SET vector = excluded.vector, embedded_at = excluded.embedded_at",
            arrayOf(memoryId, embeddingModel, vector, nowIso())
        )
    }

    /** The archive rule: when a memory leaves 'active' its vectors go (the
     *  librarian can no longer see it); re-embed on reactivation. */
    fun deleteEmbeddings(memoryId: String) {
        writableDatabase.delete("embeddings", "memory_id = ?", arrayOf(memoryId))
    }

    /** Drop vectors from other models — called when the active model's tag
     *  differs from what's stored, so a model switch re-indexes cleanly. */
    fun deleteEmbeddingsNotModel(embeddingModel: String) {
        writableDatabase.delete("embeddings", "embedding_model != ?", arrayOf(embeddingModel))
    }

    /** How many active memories still lack a vector for this model (0 = index
     *  fully built) — drives the "rebuild needed" hint. */
    fun countMissingEmbeddings(embeddingModel: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM memories m WHERE m.status = 'active' AND NOT EXISTS " +
                "(SELECT 1 FROM embeddings e WHERE e.memory_id = m.memory_id AND e.embedding_model = ?)",
            arrayOf(embeddingModel)
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun hasAnyTranscriptForChat(chatId: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM transcripts WHERE chat_id = ? LIMIT 1", arrayOf(chatId)).use {
            return it.moveToFirst()
        }
    }

    /** Insert a pre-existing chat's history as one imported transcript (the
     *  backfill path). source='imported' per the spec's old-conversation rule.
     *  Returns true on success. */
    fun insertBackfillTranscript(
        chatId: String,
        companionId: String?,
        contentJson: String,
        modelTag: String?,
        markExcluded: Boolean
    ): Boolean {
        return try {
            val now = nowIso()
            writableDatabase.insertOrThrow("transcripts", null, ContentValues().apply {
                put("transcript_id", newId("t-"))
                put("chat_id", chatId)
                put("companion_id", companionId)
                put("source", "imported")
                put("started_at", now)
                put("ended_at", now)
                put("content", contentJson)
                put("model_tag", modelTag)
                put("review_status", if (markExcluded) "excluded" else "pending")
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Chats survive renames but transcripts are keyed by chat_id — re-point
     *  them whenever a chat id changes (auto-naming, manual rename). */
    fun repointChat(oldChatId: String, newChatId: String) {
        if (oldChatId == newChatId || oldChatId.isBlank() || newChatId.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE transcripts SET chat_id = ? WHERE chat_id = ?", arrayOf(newChatId, oldChatId)
            )
            // The cooldown state and turn clock are keyed by chat id too — a
            // rename must carry them or every memory re-injects and the clock
            // restarts (OR REPLACE: a pre-existing row under the new id loses).
            db.execSQL(
                "UPDATE OR REPLACE injection_cooldowns SET chat_id = ? WHERE chat_id = ?",
                arrayOf(newChatId, oldChatId)
            )
            db.execSQL(
                "UPDATE OR REPLACE chat_turn_counters SET chat_id = ? WHERE chat_id = ?",
                arrayOf(newChatId, oldChatId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Review-state summary per chat for the chat-list markers:
     * "pending" (unreviewed content, nothing processed), "partial" (processed
     * AND new unreviewed content — the partially-processed marker), "processed"
     * (everything reviewed), "excluded" (only excluded rows). Chats with no
     * transcripts are absent.
     */
    fun chatReviewStates(): HashMap<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery(
            "SELECT chat_id, " +
                "SUM(CASE WHEN review_status = 'pending' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN review_status = 'processed' THEN 1 ELSE 0 END) " +
                "FROM transcripts WHERE chat_id IS NOT NULL GROUP BY chat_id",
            emptyArray<String>()
        ).use {
            while (it.moveToNext()) {
                val chatId = it.getString(0) ?: continue
                val pending = it.getInt(1)
                val processed = it.getInt(2)
                out[chatId] = when {
                    pending > 0 && processed > 0 -> "partial"
                    pending > 0 -> "pending"
                    processed > 0 -> "processed"
                    else -> "excluded"
                }
            }
        }
        return out
    }

    /* ---------------------------------------------------------------------- */
    /* Phase 5 editor CRUD                                                     */
    /*                                                                         */
    /* Hand-editing surface for every record type. Deletions of synced record */
    /* types leave a deleted_ids tombstone (D10). Memory edits snapshot prior  */
    /* state into change_log and drop stale embeddings so the librarian never  */
    /* matches on out-of-date vectors (the index-rebuild hint then surfaces).  */
    /* ---------------------------------------------------------------------- */

    /* -------- owner profile -------- */

    fun upsertOwnerProfile(portrait: String, standingContext: String?) {
        writableDatabase.execSQL(
            "INSERT INTO owner_profile (id, portrait, standing_context, updated_at) VALUES (1, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET portrait = excluded.portrait, " +
                "standing_context = excluded.standing_context, updated_at = excluded.updated_at",
            arrayOf(portrait, standingContext, nowIso())
        )
    }

    /* -------- companions (user-editable fields; identity stays app-owned) -------- */

    fun getCompanion(companionId: String): CompanionRecord? {
        readableDatabase.query("companions", null, "companion_id = ?", arrayOf(companionId), null, null, null).use {
            return if (it.moveToFirst()) readCompanion(it, includeHistory = true) else null
        }
    }

    /** User edits are direct (the Archivist's essence/limit changes are
     *  proposal-bound instead). Never touches identity/mirror columns. */
    fun updateCompanionFields(
        companionId: String,
        essence: String,
        relationshipNotes: String?,
        memoryParticipation: String,
        hardLimitsJson: String,
        modelAdaptationsJson: String
    ) {
        writableDatabase.update("companions", ContentValues().apply {
            put("essence", essence)
            put("relationship_notes", relationshipNotes)
            put("memory_participation", memoryParticipation)
            put("hard_limits_json", hardLimitsJson)
            put("model_adaptations_json", modelAdaptationsJson)
        }, "companion_id = ?", arrayOf(companionId))
    }

    /** The companion detail page now writes only memory_participation — the
     *  essence/relationship/limits/adaptation columns stay but nothing edits
     *  them (owner decision July 2026: companion cards are author-only). */
    fun updateCompanionParticipation(companionId: String, memoryParticipation: String) {
        writableDatabase.update(
            "companions", ContentValues().apply { put("memory_participation", memoryParticipation) },
            "companion_id = ?", arrayOf(companionId)
        )
    }

    /** Draft -> active is the approve action; also used to rest/retire. */
    fun setCompanionStatus(companionId: String, status: String) {
        writableDatabase.update(
            "companions", ContentValues().apply { put("status", status) },
            "companion_id = ?", arrayOf(companionId)
        )
    }

    /**
     * Removes a companion from the memory system (its persona/character card is
     * app-owned and untouched — only the memory-side record goes). Follows the
     * [deleteRoleplayCharacter] pattern: tombstone for future cross-device
     * merge; [deleteMemories] decides whether this companion's memories go with
     * it or stay. memory_companions has ON DELETE CASCADE on the memory side but
     * nothing on the companion side, so its links to this companion are scrubbed
     * explicitly first (a dangling FK would block the companion delete).
     */
    fun deleteCompanion(companionId: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (deleteMemories) {
                // Every memory linked to this companion is removed; deleting the
                // memory rows cascades their memory_companions links away.
                deleteMemoriesWhere(
                    db,
                    "memory_id IN (SELECT memory_id FROM memory_companions WHERE companion_id = ?)",
                    arrayOf(companionId)
                )
            }
            // Scrub any surviving links to this companion (the whole set when
            // memories are kept; a no-op safety net when they were deleted) so
            // no memory references a companion that no longer exists.
            db.delete("memory_companions", "companion_id = ?", arrayOf(companionId))
            db.delete("companions", "companion_id = ?", arrayOf(companionId))
            recordDeletionTx(db, "companion", companionId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* -------- entities -------- */

    fun getEntities(): List<EntityRecord> = readEntities(null, null)

    fun getEntity(entityId: String): EntityRecord? = readEntities("entity_id = ?", arrayOf(entityId)).firstOrNull()

    private fun readEntities(selection: String?, args: Array<String>?): List<EntityRecord> {
        val out = ArrayList<EntityRecord>()
        readableDatabase.query("entities", null, selection, args, null, null, "name ASC").use {
            while (it.moveToNext()) {
                out.add(
                    EntityRecord(
                        entityId = it.getString(it.getColumnIndexOrThrow("entity_id")),
                        kind = it.getString(it.getColumnIndexOrThrow("kind")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        aliasesJson = it.getStringOrNull("aliases_json") ?: "[]",
                        summary = it.getString(it.getColumnIndexOrThrow("summary")),
                        status = it.getStringOrNull("status"),
                        importance = it.getInt(it.getColumnIndexOrThrow("importance")),
                        lastTouched = it.getStringOrNull("last_touched"),
                        origin = it.getStringOrNull("origin") ?: "user"
                    )
                )
            }
        }
        return out
    }

    fun upsertEntity(e: EntityRecord) {
        writableDatabase.insertWithOnConflict("entities", null, ContentValues().apply {
            put("entity_id", e.entityId)
            put("kind", e.kind)
            put("name", e.name)
            put("aliases_json", e.aliasesJson)
            put("summary", e.summary)
            put("status", e.status)
            put("importance", e.importance)
            put("last_touched", e.lastTouched ?: nowIso())
            put("origin", e.origin)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteEntity(entityId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // memory_entities has no ON DELETE for the entity side — scrub links first.
            db.delete("memory_entities", "entity_id = ?", arrayOf(entityId))
            db.delete("entities", "entity_id = ?", arrayOf(entityId))
            recordDeletionTx(db, "entity", entityId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* -------- modes -------- */

    fun getMode(modeId: String): ModeRecord? = getModes().firstOrNull { it.modeId == modeId }

    fun upsertMode(m: ModeRecord) {
        writableDatabase.insertWithOnConflict("modes", null, ContentValues().apply {
            put("mode_id", m.modeId)
            put("name", m.name)
            put("purpose", m.purpose)
            put("signals_json", m.signalsJson)
            put("respond_json", m.respondJson)
            put("avoid_json", m.avoidJson)
            put("transition_note", m.transitionNote)
            put("overrides_json", m.overridesJson)
            put("scope", m.scope)
            put("companion_ids_json", m.companionIdsJson)
            put("origin", m.origin)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteMode(modeId: String) {
        writableDatabase.delete("modes", "mode_id = ?", arrayOf(modeId))
        recordDeletion("mode", modeId)
    }

    /* -------- directives -------- */

    fun getDirective(directiveId: String): DirectiveRecord? =
        getDirectives().firstOrNull { it.directiveId == directiveId }

    fun upsertDirective(d: DirectiveRecord) {
        writableDatabase.insertWithOnConflict("directives", null, ContentValues().apply {
            put("directive_id", d.directiveId)
            put("text", d.text)
            put("rationale", d.rationale)
            put("applies_to_json", d.appliesToJson)
            put("priority", d.priority)
            put("origin", d.origin)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteDirective(directiveId: String) {
        writableDatabase.delete("directives", "directive_id = ?", arrayOf(directiveId))
        recordDeletion("directive", directiveId)
    }

    /* -------- user personas -------- */

    fun getAllUserPersonas(): List<UserPersonaRecord> = readUserPersonas(null, null)

    fun upsertUserPersona(p: UserPersonaRecord) {
        writableDatabase.insertWithOnConflict("user_personas", null, ContentValues().apply {
            put("persona_id", p.personaId)
            put("name", p.name)
            put("presentation", p.presentation)
            put("status", p.status)
            put("created_at", p.createdAt ?: nowIso())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setUserPersonaStatus(personaId: String, status: String) {
        writableDatabase.update(
            "user_personas", ContentValues().apply { put("status", status) },
            "persona_id = ?", arrayOf(personaId)
        )
    }

    fun deleteUserPersona(personaId: String) {
        writableDatabase.delete("user_personas", "persona_id = ?", arrayOf(personaId))
        recordDeletion("user_persona", personaId)
    }

    /* -------- roleplay characters (definition user-editable; arc read-only in UI) -------- */

    fun getAllRoleplayCharacters(): List<RoleplayCharacterRecord> = readRoleplayCharacters(null, null)

    fun upsertRoleplayCharacter(r: RoleplayCharacterRecord) {
        writableDatabase.insertWithOnConflict("roleplay_characters", null, ContentValues().apply {
            put("roleplay_character_id", r.roleplayCharacterId)
            put("name", r.name)
            put("played_by", r.playedBy)
            put("description", r.description)
            put("arc", r.arc)
            put("worlds_played_json", r.worldsPlayedJson)
            put("status", r.status)
            put("created_at", r.createdAt ?: nowIso())
            put("species", r.species)
            put("char_class", r.charClass)
            put("core_personality", r.corePersonality)
            put("physical_description", r.physicalDescription)
            put("goals_drives", r.goalsDrives)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setRoleplayCharacterStatus(id: String, status: String) {
        writableDatabase.update(
            "roleplay_characters", ContentValues().apply { put("status", status) },
            "roleplay_character_id = ?", arrayOf(id)
        )
    }

    /** Teardown: delete the character; its memories are either removed (their
     *  embeddings cascade) or freed by nulling the link. Tombstone left. */
    fun deleteRoleplayCharacter(id: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (deleteMemories) {
                deleteMemoriesWhere(db, "roleplay_character_id = ?", arrayOf(id))
            } else {
                db.update("memories", ContentValues().apply { putNull("roleplay_character_id") },
                    "roleplay_character_id = ?", arrayOf(id))
            }
            // Scrub multi-select links (§2) so the FK to roleplay_characters clears.
            db.delete("memory_roleplay_characters", "roleplay_character_id = ?", arrayOf(id))
            // The card's Zone 2 entries and tag links go with it (3.6a).
            deleteCardEntriesForCardTx(db, CardType.RP_CHARACTER, id)
            db.delete("rp_tag_links", "target_type = ? AND target_id = ?", arrayOf(RpTagTargetType.RP_CHARACTER, id))
            db.delete("roleplay_characters", "roleplay_character_id = ?", arrayOf(id))
            recordDeletionTx(db, "roleplay_character", id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* -------- worlds -------- */

    fun getAllWorlds(): List<WorldRecord> = readWorlds(null, null)

    fun upsertWorld(w: WorldRecord) {
        writableDatabase.insertWithOnConflict("worlds", null, ContentValues().apply {
            put("world_id", w.worldId)
            put("name", w.name)
            put("premise", w.premise)
            put("rules", w.rules)
            put("cosmology", w.cosmology)
            put("premise_vibe", w.premiseVibe)
            put("magic_rules", w.magicRules)
            put("companion_ids_json", w.companionIdsJson)
            put("status", w.status)
            put("created_at", w.createdAt ?: nowIso())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Archive (3.6f, spec §5 — REPLACES the old archive-all teardown):
     *  archiving only hides the card from active selectors. Every link stays
     *  intact and NO memory is touched — "archiving or deleting a card does
     *  not erase what the campaign remembers". Restorable in one tap. */
    fun archiveWorld(worldId: String) {
        writableDatabase.update(
            "worlds", ContentValues().apply { put("status", "archived") },
            "world_id = ?", arrayOf(worldId)
        )
    }

    fun restoreWorld(worldId: String) {
        writableDatabase.update(
            "worlds", ContentValues().apply { put("status", "active") },
            "world_id = ?", arrayOf(worldId)
        )
    }

    /**
     * Delete-all teardown for a world. [keepCharacterMemories] honours the
     * table plan's option: memories that ALSO belong to a roleplay character
     * are kept (their world link nulled) so the character walks away clean;
     * pure world memories are removed. When false, every world-scoped memory
     * is deleted.
     */
    fun deleteWorld(worldId: String, deleteMemories: Boolean, keepCharacterMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (deleteMemories) {
                if (keepCharacterMemories) {
                    db.update("memories", ContentValues().apply { putNull("world_id") },
                        "world_id = ? AND roleplay_character_id IS NOT NULL", arrayOf(worldId))
                    deleteMemoriesWhere(db, "world_id = ? AND roleplay_character_id IS NULL", arrayOf(worldId))
                } else {
                    deleteMemoriesWhere(db, "world_id = ?", arrayOf(worldId))
                }
            } else {
                db.update("memories", ContentValues().apply { putNull("world_id") },
                    "world_id = ?", arrayOf(worldId))
            }
            // Campaigns anchored to this world lose their anchor but survive.
            db.update("campaigns", ContentValues().apply { putNull("world_id") },
                "world_id = ?", arrayOf(worldId))
            // Scrub multi-select links (§2) so the FK to worlds clears.
            db.delete("memory_worlds", "world_id = ?", arrayOf(worldId))
            // The card's Zone 2 entries and tag links go with it (3.6a).
            // Campaign overlays pointing at those entries keep their dangling
            // world_entry_id on purpose — §5's "(deleted card)" rendering.
            deleteCardEntriesForCardTx(db, CardType.WORLD, worldId)
            db.delete("rp_tag_links", "target_type = ? AND target_id = ?", arrayOf(RpTagTargetType.WORLD, worldId))
            db.delete("worlds", "world_id = ?", arrayOf(worldId))
            recordDeletionTx(db, "world", worldId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* -------- campaigns (📌 amendment) -------- */

    fun getCampaigns(): List<CampaignRecord> = readCampaigns(null, null)

    fun getActiveCampaigns(): List<CampaignRecord> = readCampaigns("status = 'active'", null)

    fun getCampaign(campaignId: String): CampaignRecord? =
        readCampaigns("campaign_id = ?", arrayOf(campaignId)).firstOrNull()

    private fun readCampaigns(selection: String?, args: Array<String>?): List<CampaignRecord> {
        val out = ArrayList<CampaignRecord>()
        val db = readableDatabase
        db.query("campaigns", null, selection, args, null, null, "created_at ASC, name ASC").use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow("campaign_id"))
                out.add(
                    CampaignRecord(
                        campaignId = id,
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        worldId = it.getStringOrNull("world_id"),
                        roleplayCharacterId = it.getStringOrNull("roleplay_character_id"),
                        companionId = it.getStringOrNull("companion_id"),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        storySoFar = it.getStringOrNull("story_so_far"),
                        createdAt = it.getStringOrNull("created_at"),
                        questAnchor = it.getStringOrNull("quest_anchor"),
                        activeScene = it.getStringOrNull("active_scene"),
                        partyMemberIds = readCampaignPartyIds(db, id)
                    )
                )
            }
        }
        return out
    }

    private fun readCampaignPartyIds(db: SQLiteDatabase, campaignId: String): List<String> {
        val out = ArrayList<String>()
        db.query(
            "campaign_party_members", arrayOf("party_member_id"),
            "campaign_id = ?", arrayOf(campaignId), null, null, "party_member_id ASC"
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    // Deliberately does NOT write partyMemberIds: pre-3.6 save paths rebuild
    // the record from form fields, and a REPLACE that also rewrote the join
    // table would silently wipe a campaign's party. Links go through
    // linkPartyMemberToCampaign/unlinkPartyMemberFromCampaign only.
    private fun campaignValues(c: CampaignRecord) = ContentValues().apply {
        put("campaign_id", c.campaignId)
        put("name", c.name)
        put("world_id", c.worldId)
        put("roleplay_character_id", c.roleplayCharacterId)
        put("companion_id", c.companionId)
        put("status", c.status)
        put("story_so_far", c.storySoFar)
        put("created_at", c.createdAt ?: nowIso())
        put("quest_anchor", c.questAnchor)
        put("active_scene", c.activeScene)
    }

    fun upsertCampaign(c: CampaignRecord) {
        writableDatabase.insertWithOnConflict("campaigns", null, campaignValues(c), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setCampaignStatus(campaignId: String, status: String) {
        writableDatabase.update(
            "campaigns", ContentValues().apply { put("status", status) },
            "campaign_id = ?", arrayOf(campaignId)
        )
    }

    /** One selection implies the rest (📌 amendment #5): the active campaign's
     *  world, user character and DM companion, so Quick Settings needs one
     *  control, not three. Null id -> all null. */
    fun campaignScope(campaignId: String?): Triple<String?, String?, String?> {
        if (campaignId.isNullOrBlank()) return Triple(null, null, null)
        val c = getCampaign(campaignId) ?: return Triple(null, null, null)
        return Triple(c.worldId, c.roleplayCharacterId, c.companionId)
    }

    /** Archive (3.6f, spec §5 — REPLACES the old archive-all teardown):
     *  status only; links and memories untouched. */
    fun archiveCampaign(campaignId: String) {
        writableDatabase.update(
            "campaigns", ContentValues().apply { put("status", "archived") },
            "campaign_id = ?", arrayOf(campaignId)
        )
    }

    /** Delete-all teardown: the world and character walk away clean — only the
     *  campaign and (optionally) its campaign-scoped memories go. */
    fun deleteCampaign(campaignId: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (deleteMemories) {
                deleteMemoriesWhere(db, "campaign_id = ?", arrayOf(campaignId))
            } else {
                db.update("memories", ContentValues().apply { putNull("campaign_id") },
                    "campaign_id = ?", arrayOf(campaignId))
            }
            // Scrub multi-select links (§2) so the FK to campaigns clears.
            db.delete("memory_campaigns", "campaign_id = ?", arrayOf(campaignId))
            // Party-member links, the card's Zone 2 entries and tag links go
            // with it (3.6a). The party-member CARDS survive — the join is
            // link, not ownership (§4).
            db.delete("campaign_party_members", "campaign_id = ?", arrayOf(campaignId))
            deleteCardEntriesForCardTx(db, CardType.CAMPAIGN, campaignId)
            db.delete("rp_tag_links", "target_type = ? AND target_id = ?", arrayOf(RpTagTargetType.CAMPAIGN, campaignId))
            db.delete("campaigns", "campaign_id = ?", arrayOf(campaignId))
            recordDeletionTx(db, "campaign", campaignId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* -------- projects (§4) -------- */

    fun getProjects(): List<ProjectRecord> = readProjects(null, null)

    fun getActiveProjects(): List<ProjectRecord> = readProjects("status = 'active'", null)

    fun getProject(projectId: String): ProjectRecord? =
        readProjects("project_id = ?", arrayOf(projectId)).firstOrNull()

    private fun readProjects(selection: String?, args: Array<String>?): List<ProjectRecord> {
        val out = ArrayList<ProjectRecord>()
        readableDatabase.query("projects", null, selection, args, null, null, "name ASC").use {
            while (it.moveToNext()) {
                out.add(
                    ProjectRecord(
                        projectId = it.getString(it.getColumnIndexOrThrow("project_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdAt = it.getStringOrNull("created_at"),
                        updatedAt = it.getStringOrNull("updated_at")
                    )
                )
            }
        }
        return out
    }

    private fun projectValues(p: ProjectRecord) = ContentValues().apply {
        put("project_id", p.projectId)
        put("name", p.name)
        put("status", p.status)
        put("created_at", p.createdAt ?: nowIso())
        put("updated_at", p.updatedAt)
    }

    fun upsertProject(p: ProjectRecord) {
        writableDatabase.insertWithOnConflict("projects", null, projectValues(p), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setProjectStatus(projectId: String, status: String) {
        writableDatabase.update(
            "projects", ContentValues().apply { put("status", status); put("updated_at", nowIso()) },
            "project_id = ?", arrayOf(projectId)
        )
    }

    /** Delete a project; its project-scoped memories are deleted or unlinked
     *  per the flag (mirrors [deleteCampaign]). */
    fun deleteProject(projectId: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (deleteMemories) {
                deleteMemoriesWhere(db, "project_id = ?", arrayOf(projectId))
            } else {
                db.update("memories", ContentValues().apply { putNull("project_id") },
                    "project_id = ?", arrayOf(projectId))
            }
            // Scrub multi-select links (§2) so the FK to projects clears.
            db.delete("memory_projects", "project_id = ?", arrayOf(projectId))
            db.delete("projects", "project_id = ?", arrayOf(projectId))
            recordDeletionTx(db, "project", projectId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun memoriesForProject(projectId: String, includeArchived: Boolean): List<MemoryRecord> =
        memoriesForTarget("memory_projects", "project_id", projectId, includeArchived)

    /* -------- model rules (Stage 4, owner_approved_rules §11) --------
     * User-created profiles of model-specific patches, and their rules.
     * Until Phase 6's Archivist files drafts, everything here is user-edit
     * CRUD. Nothing is ever auto-applied: injection is a per-chat, user-
     * selected choice that defaults to none, and an unassigned rule
     * (profile_id NULL) sits in "Needs review" until the user accepts,
     * deletes, or moves it — the system never invents a profile. */

    fun getModelRuleProfiles(): List<ModelRuleProfileRecord> {
        val out = ArrayList<ModelRuleProfileRecord>()
        readableDatabase.query("model_rule_profiles", null, null, null, null, null, "nickname ASC").use {
            while (it.moveToNext()) out.add(readModelRuleProfile(it))
        }
        return out
    }

    fun getModelRuleProfile(profileId: String): ModelRuleProfileRecord? {
        readableDatabase.query(
            "model_rule_profiles", null, "profile_id = ?", arrayOf(profileId), null, null, null
        ).use { return if (it.moveToFirst()) readModelRuleProfile(it) else null }
    }

    private fun readModelRuleProfile(c: Cursor) = ModelRuleProfileRecord(
        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
        nickname = c.getString(c.getColumnIndexOrThrow("nickname")),
        modelStringsJson = c.getStringOrNull("model_strings_json") ?: "[]",
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        updatedAt = c.getStringOrNull("updated_at")
    )

    private fun modelRuleProfileValues(p: ModelRuleProfileRecord) = ContentValues().apply {
        put("profile_id", p.profileId)
        put("nickname", p.nickname)
        put("model_strings_json", p.modelStringsJson)
        put("created_at", p.createdAt.ifEmpty { nowIso() })
        put("updated_at", p.updatedAt)
    }

    fun upsertModelRuleProfile(p: ModelRuleProfileRecord) {
        writableDatabase.insertWithOnConflict(
            "model_rule_profiles", null, modelRuleProfileValues(p), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** §11's move flow: add [modelString] to the profile's list (deduped
     *  case-insensitively) so future drafts carrying that string file there
     *  automatically. No-op when the string is already on the profile. */
    fun addModelStringToProfile(profileId: String, modelString: String) {
        val trimmed = modelString.trim()
        if (trimmed.isEmpty()) return
        val profile = getModelRuleProfile(profileId) ?: return
        val arr = try { org.json.JSONArray(profile.modelStringsJson) } catch (_: Exception) { org.json.JSONArray() }
        for (i in 0 until arr.length()) {
            if (arr.getString(i).equals(trimmed, ignoreCase = true)) return
        }
        arr.put(trimmed)
        writableDatabase.update(
            "model_rule_profiles",
            ContentValues().apply { put("model_strings_json", arr.toString()); put("updated_at", nowIso()) },
            "profile_id = ?", arrayOf(profileId)
        )
    }

    /** Delete a profile. Its rules are deleted with it or sent back to
     *  "Needs review" (unassigned) per the flag — the caller's confirm
     *  dialog decides, mirroring the per-deletion choice pattern. */
    fun deleteModelRuleProfile(profileId: String, deleteRules: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (deleteRules) {
                val ids = ArrayList<String>()
                db.query(
                    "model_rules", arrayOf("rule_id"), "profile_id = ?", arrayOf(profileId), null, null, null
                ).use { while (it.moveToNext()) ids.add(it.getString(0)) }
                for (id in ids) recordDeletionTx(db, "model_rule", id)
                db.delete("model_rules", "profile_id = ?", arrayOf(profileId))
            } else {
                db.update(
                    "model_rules", ContentValues().apply { putNull("profile_id") },
                    "profile_id = ?", arrayOf(profileId)
                )
            }
            db.delete("model_rule_profiles", "profile_id = ?", arrayOf(profileId))
            recordDeletionTx(db, "model_rule_profile", profileId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Rules for one profile, or the unassigned "Needs review" rules when
     *  [profileId] is null. Deterministic order (oldest first, id tiebreak). */
    fun getModelRules(profileId: String?): List<ModelRuleRecord> {
        val out = ArrayList<ModelRuleRecord>()
        val selection = if (profileId == null) "profile_id IS NULL" else "profile_id = ?"
        val args = if (profileId == null) null else arrayOf(profileId)
        readableDatabase.query(
            "model_rules", null, selection, args, null, null, "created_at ASC, rule_id ASC"
        ).use { while (it.moveToNext()) out.add(readModelRule(it)) }
        return out
    }

    /** The injection read (Stage 4): a selected profile's ACTIVE rules only,
     *  in the same deterministic order every turn — the rendered block must
     *  be byte-identical across turns (prompt-layer contract). */
    fun getActiveModelRules(profileId: String): List<ModelRuleRecord> {
        val out = ArrayList<ModelRuleRecord>()
        readableDatabase.query(
            "model_rules", null, "profile_id = ? AND status = 'active'", arrayOf(profileId),
            null, null, "created_at ASC, rule_id ASC"
        ).use { while (it.moveToNext()) out.add(readModelRule(it)) }
        return out
    }

    private fun readModelRule(c: Cursor) = ModelRuleRecord(
        ruleId = c.getString(c.getColumnIndexOrThrow("rule_id")),
        profileId = c.getStringOrNull("profile_id"),
        text = c.getString(c.getColumnIndexOrThrow("text")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        sourceModelString = c.getStringOrNull("source_model_string"),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        updatedAt = c.getStringOrNull("updated_at")
    )

    private fun modelRuleValues(r: ModelRuleRecord) = ContentValues().apply {
        put("rule_id", r.ruleId)
        if (r.profileId != null) put("profile_id", r.profileId) else putNull("profile_id")
        put("text", r.text)
        put("status", r.status)
        put("source_model_string", r.sourceModelString)
        put("created_at", r.createdAt.ifEmpty { nowIso() })
        put("updated_at", r.updatedAt)
    }

    fun upsertModelRule(r: ModelRuleRecord) {
        writableDatabase.insertWithOnConflict(
            "model_rules", null, modelRuleValues(r), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteModelRule(ruleId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("model_rules", "rule_id = ?", arrayOf(ruleId))
            recordDeletionTx(db, "model_rule", ruleId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Accept a draft (§11): draft -> active. Acceptance never moves the
     *  rule — where it lives stays the user's separate choice. */
    fun acceptModelRule(ruleId: String) {
        writableDatabase.update(
            "model_rules", ContentValues().apply { put("status", "active"); put("updated_at", nowIso()) },
            "rule_id = ?", arrayOf(ruleId)
        )
    }

    /** Move a rule to [destinationProfileId] (null = back to Needs review).
     *  The §11 offer to add the rule's source model string to the destination
     *  is a separate, user-confirmed call to [addModelStringToProfile]. */
    fun moveModelRule(ruleId: String, destinationProfileId: String?) {
        writableDatabase.update(
            "model_rules",
            ContentValues().apply {
                if (destinationProfileId != null) put("profile_id", destinationProfileId) else putNull("profile_id")
                put("updated_at", nowIso())
            },
            "rule_id = ?", arrayOf(ruleId)
        )
    }

    /** Drafts + unassigned rules — what the pinned "Needs review" state and
     *  other surfaces' pointer rows count (§11 / work order). */
    fun countModelRulesNeedingReview(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM model_rules WHERE profile_id IS NULL OR status = 'draft'", null
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /* -------- roleplay cards + tags (Stage 3.6a, roleplay_cards_and_tags_spec.md) --------
     * Everything here is user-edit CRUD: per the no-mid-conversation-writes
     * law (spec §6d/§9), NO automatic process may call the write methods in
     * this section during a conversation — cards, entries and tags change
     * only from the card editors (or an import the user runs). */

    /* ---- NPC party members (spec §4/§6b) ---- */

    fun getPartyMembers(includeArchived: Boolean): List<PartyMemberRecord> {
        val out = ArrayList<PartyMemberRecord>()
        val selection = if (includeArchived) null else "archived = 0"
        readableDatabase.query("party_members", null, selection, null, null, null, "name ASC").use {
            while (it.moveToNext()) out.add(readPartyMember(it))
        }
        return out
    }

    fun getPartyMember(partyMemberId: String): PartyMemberRecord? {
        readableDatabase.query(
            "party_members", null, "party_member_id = ?", arrayOf(partyMemberId), null, null, null
        ).use { return if (it.moveToFirst()) readPartyMember(it) else null }
    }

    private fun readPartyMember(c: Cursor): PartyMemberRecord = PartyMemberRecord(
        partyMemberId = c.getString(c.getColumnIndexOrThrow("party_member_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        species = c.getStringOrNull("species"),
        charClass = c.getStringOrNull("char_class"),
        corePersonality = c.getStringOrNull("core_personality"),
        physicalDescription = c.getStringOrNull("physical_description"),
        goalsDrives = c.getStringOrNull("goals_drives"),
        speechStyle = c.getStringOrNull("speech_style"),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        archived = c.getInt(c.getColumnIndexOrThrow("archived")) == 1,
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        updatedAt = c.getStringOrNull("updated_at")
    )

    fun upsertPartyMember(p: PartyMemberRecord) {
        writableDatabase.insertWithOnConflict("party_members", null, ContentValues().apply {
            put("party_member_id", p.partyMemberId)
            put("name", p.name)
            put("species", p.species)
            put("char_class", p.charClass)
            put("core_personality", p.corePersonality)
            put("physical_description", p.physicalDescription)
            put("goals_drives", p.goalsDrives)
            put("speech_style", p.speechStyle)
            put("status", p.status)
            put("archived", if (p.archived) 1 else 0)
            put("created_at", p.createdAt.ifEmpty { nowIso() })
            put("updated_at", p.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The four-state fiction status (alive|incapacitated|dead|enemy) —
     *  user-editable at any time (§4). Death is a status change, NEVER a
     *  delete; the who-they-were summary memory is user-written (3.6f). */
    fun setPartyMemberStatus(partyMemberId: String, status: String) {
        writableDatabase.update(
            "party_members",
            ContentValues().apply { put("status", status); put("updated_at", nowIso()) },
            "party_member_id = ?", arrayOf(partyMemberId)
        )
    }

    /** Card-lifecycle archive (§5): hidden from active selection, links kept,
     *  restorable in one tap from the visible Archive section. */
    fun setPartyMemberArchived(partyMemberId: String, archived: Boolean) {
        writableDatabase.update(
            "party_members",
            ContentValues().apply { put("archived", if (archived) 1 else 0); put("updated_at", nowIso()) },
            "party_member_id = ?", arrayOf(partyMemberId)
        )
    }

    /** True delete (§5): campaign links and the card's own entries/tags are
     *  scrubbed; world-card NPC entries that point here via party_member_id
     *  keep the dangling pointer ON PURPOSE — the tombstone lets the UI
     *  render "(deleted card)" instead of a silent hole. */
    fun deletePartyMember(partyMemberId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("campaign_party_members", "party_member_id = ?", arrayOf(partyMemberId))
            deleteCardEntriesForCardTx(db, CardType.PARTY_MEMBER, partyMemberId)
            db.delete(
                "rp_tag_links", "target_type = ? AND target_id = ?",
                arrayOf(RpTagTargetType.PARTY_MEMBER, partyMemberId)
            )
            db.delete("party_members", "party_member_id = ?", arrayOf(partyMemberId))
            recordDeletionTx(db, "party_member", partyMemberId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* ---- campaign <-> party-member links (join, not ownership — §4) ---- */

    fun linkPartyMemberToCampaign(campaignId: String, partyMemberId: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO campaign_party_members (campaign_id, party_member_id) VALUES (?, ?)",
            arrayOf(campaignId, partyMemberId)
        )
    }

    fun unlinkPartyMemberFromCampaign(campaignId: String, partyMemberId: String) {
        writableDatabase.delete(
            "campaign_party_members", "campaign_id = ? AND party_member_id = ?",
            arrayOf(campaignId, partyMemberId)
        )
    }

    fun campaignIdsForPartyMember(partyMemberId: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query(
            "campaign_party_members", arrayOf("campaign_id"),
            "party_member_id = ?", arrayOf(partyMemberId), null, null, "campaign_id ASC"
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    fun partyMembersForCampaign(campaignId: String): List<PartyMemberRecord> {
        val out = ArrayList<PartyMemberRecord>()
        readableDatabase.rawQuery(
            "SELECT p.* FROM party_members p " +
                "JOIN campaign_party_members j ON j.party_member_id = p.party_member_id " +
                "WHERE j.campaign_id = ? ORDER BY p.name ASC",
            arrayOf(campaignId)
        ).use { while (it.moveToNext()) out.add(readPartyMember(it)) }
        return out
    }

    /* ---- Zone 2 card entries (spec §6) ---- */

    fun getCardEntry(entryId: String): CardEntryRecord? {
        readableDatabase.query(
            "card_entries", null, "entry_id = ?", arrayOf(entryId), null, null, null
        ).use { return if (it.moveToFirst()) readCardEntry(it) else null }
    }

    fun entriesForCard(cardType: String, cardId: String): List<CardEntryRecord> {
        val out = ArrayList<CardEntryRecord>()
        readableDatabase.query(
            "card_entries", null, "card_type = ? AND card_id = ?", arrayOf(cardType, cardId),
            null, null, "section ASC, name ASC"
        ).use { while (it.moveToNext()) out.add(readCardEntry(it)) }
        return out
    }

    fun entriesForSection(cardType: String, cardId: String, section: String): List<CardEntryRecord> {
        val out = ArrayList<CardEntryRecord>()
        readableDatabase.query(
            "card_entries", null, "card_type = ? AND card_id = ? AND section = ?",
            arrayOf(cardType, cardId, section), null, null, "name ASC"
        ).use { while (it.moveToNext()) out.add(readCardEntry(it)) }
        return out
    }

    private fun readCardEntry(c: Cursor): CardEntryRecord = CardEntryRecord(
        entryId = c.getString(c.getColumnIndexOrThrow("entry_id")),
        cardType = c.getString(c.getColumnIndexOrThrow("card_type")),
        cardId = c.getString(c.getColumnIndexOrThrow("card_id")),
        section = c.getString(c.getColumnIndexOrThrow("section")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        description = c.getStringOrNull("description"),
        entryKind = c.getStringOrNull("entry_kind"),
        quantity = c.getColumnIndexOrThrow("quantity").let { i -> if (c.isNull(i)) null else c.getInt(i) },
        parentEntryId = c.getStringOrNull("parent_entry_id"),
        worldEntryId = c.getStringOrNull("world_entry_id"),
        partyMemberId = c.getStringOrNull("party_member_id"),
        holder = c.getStringOrNull("holder"),
        significance = c.getStringOrNull("significance"),
        castIdentity = c.getStringOrNull("cast_identity"),
        castDisposition = c.getStringOrNull("cast_disposition"),
        castStatus = c.getStringOrNull("cast_status"),
        locationCondition = c.getStringOrNull("location_condition"),
        locationChanges = c.getStringOrNull("location_changes"),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        updatedAt = c.getStringOrNull("updated_at")
    )

    fun upsertCardEntry(e: CardEntryRecord) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("card_entries", null, cardEntryValues(e), SQLiteDatabase.CONFLICT_REPLACE)
            // An edited entry resets its freshness clock and re-injects fresh
            // (§10) — same contract as memory edits.
            clearEntryCooldownTx(db, COOLDOWN_SOURCE_CARD_ENTRY, e.entryId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun cardEntryValues(e: CardEntryRecord) = ContentValues().apply {
        put("entry_id", e.entryId)
        put("card_type", e.cardType)
        put("card_id", e.cardId)
        put("section", e.section)
        put("name", e.name)
        put("description", e.description)
        put("entry_kind", e.entryKind)
        if (e.quantity != null) put("quantity", e.quantity) else putNull("quantity")
        put("parent_entry_id", e.parentEntryId)
        put("world_entry_id", e.worldEntryId)
        put("party_member_id", e.partyMemberId)
        put("holder", e.holder)
        put("significance", e.significance)
        put("cast_identity", e.castIdentity)
        put("cast_disposition", e.castDisposition)
        put("cast_status", e.castStatus)
        put("location_condition", e.locationCondition)
        put("location_changes", e.locationChanges)
        put("created_at", e.createdAt.ifEmpty { nowIso() })
        put("updated_at", e.updatedAt)
    }

    fun deleteCardEntry(entryId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "rp_tag_links", "target_type = ? AND target_id = ?",
                arrayOf(RpTagTargetType.CARD_ENTRY, entryId)
            )
            db.delete("card_entries", "entry_id = ?", arrayOf(entryId))
            clearEntryCooldownTx(db, COOLDOWN_SOURCE_CARD_ENTRY, entryId)
            recordDeletionTx(db, "card_entry", entryId)
            // Entries referencing this one (geography children via
            // parent_entry_id, campaign overlays via world_entry_id) keep
            // their dangling pointer on purpose — §5's "(deleted card)"
            // rendering reads the tombstone; references never silently vanish.
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Card teardown helper: removes a card's own Zone 2 entries, their tag
     *  links, and leaves per-entry tombstones. Must run inside an open
     *  transaction (like the other teardown helpers). */
    private fun deleteCardEntriesForCardTx(db: SQLiteDatabase, cardType: String, cardId: String) {
        val ids = ArrayList<String>()
        db.query(
            "card_entries", arrayOf("entry_id"), "card_type = ? AND card_id = ?",
            arrayOf(cardType, cardId), null, null, null
        ).use { while (it.moveToNext()) ids.add(it.getString(0)) }
        for (id in ids) {
            db.delete("rp_tag_links", "target_type = ? AND target_id = ?", arrayOf(RpTagTargetType.CARD_ENTRY, id))
            recordDeletionTx(db, "card_entry", id)
        }
        db.delete("card_entries", "card_type = ? AND card_id = ?", arrayOf(cardType, cardId))
    }

    /* ---- the roleplay-realm tag pool (spec §3) ----
     * REALM WALL: these tables are the roleplay realm and nothing else.
     * Real-life memory tags live in memories.tags_json, keep the Memories
     * browser as their only door, and never link here — even for identical
     * words. No starter tags ship, ever; the pool fills only from the user's
     * own tag input (and, later, approved Phase 6 suggestions). */

    fun getAllRpTags(): List<RpTagRecord> {
        val out = ArrayList<RpTagRecord>()
        readableDatabase.query("rp_tags", null, null, null, null, null, "name ASC").use {
            while (it.moveToNext()) out.add(readRpTag(it))
        }
        return out
    }

    fun getRpTag(tagId: String): RpTagRecord? {
        readableDatabase.query("rp_tags", null, "tag_id = ?", arrayOf(tagId), null, null, null).use {
            return if (it.moveToFirst()) readRpTag(it) else null
        }
    }

    /** Case-insensitive name lookup — the pool's dedup rule lives here (and
     *  in import), not in a UNIQUE constraint, so imports can name-match. */
    fun findRpTagByName(name: String): RpTagRecord? {
        readableDatabase.query(
            "rp_tags", null, "name = ? COLLATE NOCASE", arrayOf(name.trim()), null, null, null
        ).use { return if (it.moveToFirst()) readRpTag(it) else null }
    }

    /** Tag input's confirm path (spec §3): reuse the existing tag when the
     *  name matches (case-insensitive), otherwise create it — auto_trigger
     *  defaults ON, the app never flips it itself. */
    fun getOrCreateRpTag(name: String): RpTagRecord {
        val trimmed = name.trim()
        findRpTagByName(trimmed)?.let { return it }
        val tag = RpTagRecord(tagId = newId("tag-"), name = trimmed, autoTrigger = true, createdAt = nowIso())
        writableDatabase.insert("rp_tags", null, ContentValues().apply {
            put("tag_id", tag.tagId)
            put("name", tag.name)
            put("auto_trigger", 1)
            put("created_at", tag.createdAt)
        })
        return tag
    }

    private fun readRpTag(c: Cursor): RpTagRecord = RpTagRecord(
        tagId = c.getString(c.getColumnIndexOrThrow("tag_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        autoTrigger = c.getInt(c.getColumnIndexOrThrow("auto_trigger")) == 1,
        createdAt = c.getStringOrNull("created_at")
    )

    /** The per-tag browse-only switch (spec §3): OFF silences ONLY the
     *  message-text trigger path; browsing, the "connected to:" line and
     *  one-hop pull-along keep working. User-flipped only. */
    fun setRpTagAutoTrigger(tagId: String, autoTrigger: Boolean) {
        writableDatabase.update(
            "rp_tags", ContentValues().apply { put("auto_trigger", if (autoTrigger) 1 else 0) },
            "tag_id = ?", arrayOf(tagId)
        )
    }

    fun deleteRpTag(tagId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // rp_tag_links rows cascade with the tag (ON DELETE CASCADE).
            db.delete("rp_tags", "tag_id = ?", arrayOf(tagId))
            recordDeletionTx(db, "rp_tag", tagId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addTagLink(tagId: String, targetType: String, targetId: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO rp_tag_links (tag_id, target_type, target_id) VALUES (?, ?, ?)",
            arrayOf(tagId, targetType, targetId)
        )
    }

    fun removeTagLink(tagId: String, targetType: String, targetId: String) {
        writableDatabase.delete(
            "rp_tag_links", "tag_id = ? AND target_type = ? AND target_id = ?",
            arrayOf(tagId, targetType, targetId)
        )
    }

    fun tagsForTarget(targetType: String, targetId: String): List<RpTagRecord> {
        val out = ArrayList<RpTagRecord>()
        readableDatabase.rawQuery(
            "SELECT t.* FROM rp_tags t JOIN rp_tag_links l ON l.tag_id = t.tag_id " +
                "WHERE l.target_type = ? AND l.target_id = ? ORDER BY t.name ASC",
            arrayOf(targetType, targetId)
        ).use { while (it.moveToNext()) out.add(readRpTag(it)) }
        return out
    }

    /** Every card-entry tag link in one query (entryId -> tagIds) — the 3.6d
     *  retrieval pass filters to the active cards' entries in memory rather
     *  than issuing one query per entry every turn. */
    fun cardEntryTagLinks(): HashMap<String, ArrayList<String>> {
        val out = HashMap<String, ArrayList<String>>()
        readableDatabase.query(
            "rp_tag_links", arrayOf("target_id", "tag_id"), "target_type = ?",
            arrayOf(RpTagTargetType.CARD_ENTRY), null, null, null
        ).use {
            while (it.moveToNext()) out.getOrPut(it.getString(0)) { ArrayList() }.add(it.getString(1))
        }
        return out
    }

    /** The read side of the §3 tag bridge for the cross-card view (3.6e):
     *  roleplay-scoped memories whose tag list carries [tagName]. THE REALM
     *  WALL HOLDS — only world/campaign/rp_character-scoped memories are
     *  searched, never real-life ones (their tags keep the Memories browser
     *  as their only door). LIKE narrows the scan; the JSON parse is the
     *  actual case-insensitive match. */
    fun roleplayMemoriesWithTag(tagName: String): List<MemoryRecord> {
        val db = readableDatabase
        val out = ArrayList<MemoryRecord>()
        db.query(
            "memories", null,
            "scope IN ('world','campaign','rp_character') AND tags_json LIKE ?",
            arrayOf("%$tagName%"), null, null, "created_at DESC"
        ).use {
            while (it.moveToNext()) {
                val record = readFullMemory(db, it)
                val match = try {
                    val arr = org.json.JSONArray(record.tagsJson)
                    (0 until arr.length()).any { i -> arr.getString(i).equals(tagName, ignoreCase = true) }
                } catch (_: Exception) { false }
                if (match) out.add(record)
            }
        }
        return out
    }

    /** All (targetType, targetId) pairs a tag points at — the cross-card tag
     *  view (3.6e) groups these by the predefined card/section categories. */
    fun targetsForTag(tagId: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.query(
            "rp_tag_links", arrayOf("target_type", "target_id"), "tag_id = ?", arrayOf(tagId),
            null, null, "target_type ASC, target_id ASC"
        ).use { while (it.moveToNext()) out.add(it.getString(0) to it.getString(1)) }
        return out
    }

    /* -------- memories (full editor CRUD) -------- */

    fun getMemory(memoryId: String): MemoryRecord? {
        val db = readableDatabase
        db.query("memories", null, "memory_id = ?", arrayOf(memoryId), null, null, null).use {
            return if (it.moveToFirst()) readFullMemory(db, it) else null
        }
    }

    /** Draft memories (§9), for the Pending screen. Newest first. */
    fun draftMemories(): List<MemoryRecord> {
        val db = readableDatabase
        val out = ArrayList<MemoryRecord>()
        db.query("memories", null, "status = 'draft'", null, null, null, "created_at DESC").use {
            while (it.moveToNext()) out.add(readFullMemory(db, it))
        }
        return out
    }

    /** Count of draft memories, for the Pending banner. */
    fun countDrafts(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memories WHERE status = 'draft'", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * "Reset memories" (owner_approved_rules approved UI decisions): empties
     * every memory-content table and leaves ONLY the empty structure the store
     * needs — never refilled with anyone's defaults. The schema singletons
     * (meta, archivist_settings, retrieval_policy) stay; app_state is blanked.
     * Deleting `memories` cascades its join/child rows; the remaining tables are
     * deleted in FK-safe order (children/referencing tables before the tables
     * they point at) so no foreign key blocks the wipe.
     */
    fun resetAllMemoryData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("memories", null, null) // cascades memory_* joins, change_log, embeddings
            db.delete("transcripts", null, null)
            db.delete("proposals", null, null)
            // Roleplay cards + tags (3.6a): links and entries before the
            // tables they reference; rp_tags cascades rp_tag_links.
            db.delete("card_entries", null, null)
            db.delete("campaign_party_members", null, null)
            db.delete("party_members", null, null)
            db.delete("rp_tags", null, null)
            db.delete("campaigns", null, null)
            db.delete("roleplay_characters", null, null)
            db.delete("worlds", null, null)
            db.delete("companions", null, null) // cascades companion_name_history
            db.delete("entities", null, null)
            db.delete("user_personas", null, null)
            db.delete("projects", null, null)
            db.delete("modes", null, null)
            db.delete("directives", null, null)
            db.delete("owner_profile", null, null)
            db.delete("deleted_ids", null, null)
            db.delete("injection_cooldowns", null, null)
            db.delete("chat_turn_counters", null, null)
            db.update("app_state", ContentValues().apply {
                putNull("active_companion_id"); putNull("active_world_id")
                putNull("active_roleplay_character_id"); putNull("active_user_persona_id")
            }, "id = 1", null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Text browser (title/content LIKE, else most-recent). Archived rows are
     *  hidden unless [includeArchived]. */
    fun browseMemories(query: String?, includeArchived: Boolean, limit: Int): List<MemoryRecord> {
        val db = readableDatabase
        val where = StringBuilder()
        val args = ArrayList<String>()
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) {
            val like = "%${q.replace("%", "").replace("_", "")}%"
            where.append("(title LIKE ? OR content LIKE ?)")
            args.add(like); args.add(like)
        }
        if (!includeArchived) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("status = 'active'")
        }
        val out = ArrayList<MemoryRecord>()
        db.query(
            "memories", null, where.toString().ifEmpty { null }, if (args.isEmpty()) null else args.toTypedArray(),
            null, null, "updated_at DESC, created_at DESC", limit.toString()
        ).use {
            while (it.moveToNext()) out.add(readFullMemory(db, it))
        }
        return out
    }

    // Scoped-browser doors (§2 multi-select): a memory shows under EVERY target
    // it is linked to, so these read the join tables, not the single columns.
    fun memoriesForWorld(worldId: String, includeArchived: Boolean): List<MemoryRecord> =
        memoriesForTarget("memory_worlds", "world_id", worldId, includeArchived)

    fun memoriesForCampaign(campaignId: String, includeArchived: Boolean): List<MemoryRecord> =
        memoriesForTarget("memory_campaigns", "campaign_id", campaignId, includeArchived)

    fun memoriesForRoleplayCharacter(id: String, includeArchived: Boolean): List<MemoryRecord> =
        memoriesForTarget("memory_roleplay_characters", "roleplay_character_id", id, includeArchived)

    fun memoriesForCompanion(companionId: String, includeArchived: Boolean): List<MemoryRecord> =
        memoriesForTarget("memory_companions", "companion_id", companionId, includeArchived)

    private fun memoriesForTarget(
        joinTable: String, column: String, targetId: String, includeArchived: Boolean
    ): List<MemoryRecord> {
        val db = readableDatabase
        val statusClause = if (includeArchived) "" else " AND m.status = 'active'"
        val out = ArrayList<MemoryRecord>()
        db.rawQuery(
            "SELECT m.* FROM memories m JOIN $joinTable j ON j.memory_id = m.memory_id " +
                "WHERE j.$column = ?$statusClause ORDER BY m.updated_at DESC, m.created_at DESC",
            arrayOf(targetId)
        ).use {
            while (it.moveToNext()) out.add(readFullMemory(db, it))
        }
        return out
    }

    private fun readFullMemory(db: SQLiteDatabase, it: Cursor): MemoryRecord {
        val id = it.getString(it.getColumnIndexOrThrow("memory_id"))
        return MemoryRecord(
            memoryId = id,
            scope = it.getString(it.getColumnIndexOrThrow("scope")),
            kind = it.getString(it.getColumnIndexOrThrow("kind")),
            title = it.getString(it.getColumnIndexOrThrow("title")),
            content = it.getString(it.getColumnIndexOrThrow("content")),
            embeddingText = it.getStringOrNull("embedding_text"),
            tagsJson = it.getStringOrNull("tags_json") ?: "[]",
            importance = it.getInt(it.getColumnIndexOrThrow("importance")),
            worldIds = readJoin(db, "memory_worlds", "world_id", id),
            roleplayCharacterIds = readJoin(db, "memory_roleplay_characters", "roleplay_character_id", id),
            campaignIds = readJoin(db, "memory_campaigns", "campaign_id", id),
            projectIds = readJoin(db, "memory_projects", "project_id", id),
            protectionJson = it.getStringOrNull("protection_json"),
            modeHintsJson = it.getStringOrNull("mode_hints_json") ?: "[]",
            provenanceSource = it.getStringOrNull("provenance_source"),
            provenanceConfidence = it.getStringOrNull("provenance_confidence"),
            provenanceNotedOn = it.getStringOrNull("provenance_noted_on"),
            provenanceContext = it.getStringOrNull("provenance_context"),
            createdAt = it.getString(it.getColumnIndexOrThrow("created_at")) ?: "",
            updatedAt = it.getStringOrNull("updated_at"),
            status = it.getString(it.getColumnIndexOrThrow("status")),
            supersedes = it.getStringOrNull("supersedes"),
            companionIds = readJoin(db, "memory_companions", "companion_id", id),
            entityRefs = readJoin(db, "memory_entities", "entity_id", id),
            changeLog = readChangeLog(db, id),
            origin = it.getStringOrNull("origin") ?: "user"
        )
    }

    private fun memoryValues(m: MemoryRecord) = ContentValues().apply {
        put("memory_id", m.memoryId)
        put("scope", m.scope)
        put("kind", m.kind)
        put("title", m.title)
        put("content", m.content)
        put("embedding_text", m.embeddingText)
        put("tags_json", m.tagsJson)
        put("importance", m.importance)
        // Primary-target mirror (§2 multi-select): the first of each target set
        // is kept in the legacy single column for the teardown paths; the full
        // set lives in the join tables (writeMemoryLinks), which is what the
        // Stage-3.1 retrieval eligibility query reads.
        put("world_id", m.worldIds.firstOrNull())
        put("roleplay_character_id", m.roleplayCharacterIds.firstOrNull())
        put("campaign_id", m.campaignIds.firstOrNull())
        put("project_id", m.projectIds.firstOrNull())
        put("protection_json", m.protectionJson)
        put("mode_hints_json", m.modeHintsJson)
        put("provenance_source", m.provenanceSource)
        put("provenance_confidence", m.provenanceConfidence)
        put("provenance_noted_on", m.provenanceNotedOn)
        put("provenance_context", m.provenanceContext)
        put("created_at", m.createdAt)
        put("updated_at", m.updatedAt)
        put("status", m.status)
        put("supersedes", m.supersedes)
        put("origin", m.origin)
    }

    private fun writeMemoryLinks(db: SQLiteDatabase, m: MemoryRecord) {
        writeLinkSet(db, "memory_companions", "companion_id", m.memoryId, m.companionIds)
        writeLinkSet(db, "memory_entities", "entity_id", m.memoryId, m.entityRefs)
        // §2 multi-select target sets (mirror kept in the single columns by
        // memoryValues; these join rows are the full truth).
        writeLinkSet(db, "memory_worlds", "world_id", m.memoryId, m.worldIds)
        writeLinkSet(db, "memory_campaigns", "campaign_id", m.memoryId, m.campaignIds)
        writeLinkSet(db, "memory_roleplay_characters", "roleplay_character_id", m.memoryId, m.roleplayCharacterIds)
        writeLinkSet(db, "memory_projects", "project_id", m.memoryId, m.projectIds)
    }

    /** Replace a memory's rows in a (memory_id, target) join table. */
    private fun writeLinkSet(
        db: SQLiteDatabase, table: String, column: String, memoryId: String, targets: List<String>
    ) {
        db.delete(table, "memory_id = ?", arrayOf(memoryId))
        for (t in targets) {
            db.insertWithOnConflict(table, null, ContentValues().apply {
                put("memory_id", memoryId); put(column, t)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** Insert a hand-written memory. Records a 'created' change-log entry.
     *  The new vector is filled in by a later index rebuild (or a targeted
     *  re-embed), so the caller should refresh the index. */
    fun insertMemory(m: MemoryRecord) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("memories", null, memoryValues(m))
            writeMemoryLinks(db, m)
            logChange(db, m.memoryId, "user", "created", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Edit an existing memory. Snapshots the prior state into change_log (undo
     * source for Phase 6) and drops the memory's embeddings when the embeddable
     * text changed, so the librarian re-embeds instead of matching a stale
     * vector. Sets updated_at (D10 sync requirement).
     */
    fun updateMemory(m: MemoryRecord, note: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prior = getMemory(m.memoryId)
            val updated = m.copy(updatedAt = nowIso())
            db.update("memories", memoryValues(updated), "memory_id = ?", arrayOf(m.memoryId))
            writeMemoryLinks(db, updated)
            logChange(db, m.memoryId, "user", "edited", note, prior?.let { snapshotMemoryJson(it) })
            val textChanged = prior == null ||
                prior.content != m.content || prior.title != m.title ||
                (prior.embeddingText ?: "") != (m.embeddingText ?: "")
            if (textChanged) db.delete("embeddings", "memory_id = ?", arrayOf(m.memoryId))
            // §10: an edit resets the freshness clock — the corrected version
            // re-injects on its next relevance instead of waiting out the old
            // mention's cooldown.
            clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, m.memoryId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Archive / activate / supersede a memory. Leaving 'active' drops its
     *  vectors (archive rule); re-activating clears them too so a rebuild
     *  re-embeds fresh. */
    fun setMemoryStatus(memoryId: String, status: String, note: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prior = getMemory(memoryId)
            db.update("memories", ContentValues().apply {
                put("status", status)
                put("updated_at", nowIso())
            }, "memory_id = ?", arrayOf(memoryId))
            db.delete("embeddings", "memory_id = ?", arrayOf(memoryId))
            logChange(db, memoryId, "user",
                if (status == "active") "activated" else status, note,
                prior?.let { snapshotMemoryJson(it) })
            // A status flip is an edit for §10 purposes: a re-activated memory
            // injects fresh instead of inheriting a pre-archive cooldown.
            clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, memoryId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Set (or clear, when [protectionJson] is null) a memory's protection
     *  object. Structurally the same edit path; recorded in the change log. */
    fun setMemoryProtection(memoryId: String, protectionJson: String?, note: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prior = getMemory(memoryId)
            db.update("memories", ContentValues().apply {
                put("protection_json", protectionJson)
                put("updated_at", nowIso())
            }, "memory_id = ?", arrayOf(memoryId))
            logChange(db, memoryId, "user",
                if (protectionJson == null) "unprotected" else "protected", note,
                prior?.let { snapshotMemoryJson(it) })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Hard delete (user-only action). Change-log rows cascade with the memory;
     *  a tombstone records the deletion for future cross-device merge. */
    fun deleteMemory(memoryId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("memories", "memory_id = ?", arrayOf(memoryId))
            recordDeletionTx(db, "memory", memoryId)
            clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, memoryId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getMemoryChangeLog(memoryId: String): List<ChangeLogEntry> =
        readChangeLog(readableDatabase, memoryId)

    private fun logChange(
        db: SQLiteDatabase, memoryId: String, actor: String, action: String,
        note: String?, priorStateJson: String?
    ) {
        db.insert("change_log", null, ContentValues().apply {
            put("memory_id", memoryId)
            put("at", nowIso())
            put("actor", actor)
            put("action", action)
            put("note", note)
            put("prior_state_json", priorStateJson)
        })
    }

    /** Compact snapshot of the editable memory fields for the change-log
     *  prior_state (device-local; never exported). Enough for a field-level
     *  undo in Phase 6 and a "before" view in the change-log screen. */
    private fun snapshotMemoryJson(m: MemoryRecord): String = org.json.JSONObject().apply {
        put("title", m.title)
        put("content", m.content)
        put("scope", m.scope)
        put("kind", m.kind)
        put("importance", m.importance)
        put("status", m.status)
        put("tags_json", m.tagsJson)
        m.protectionJson?.let { put("protection_json", it) }
    }.toString()

    /* -------- teardown helpers (must run inside an open transaction) -------- */

    private fun deleteMemoriesWhere(db: SQLiteDatabase, where: String, args: Array<String>) {
        // Tombstone each id first (change_log + embeddings cascade on delete).
        db.query("memories", arrayOf("memory_id"), where, args, null, null, null).use {
            while (it.moveToNext()) recordDeletionTx(db, "memory", it.getString(0))
        }
        db.delete("memories", where, args)
    }

    private fun archiveMemoriesWhere(db: SQLiteDatabase, where: String, args: Array<String>) {
        db.query("memories", arrayOf("memory_id"), where, args, null, null, null).use {
            while (it.moveToNext()) {
                val mid = it.getString(0)
                logChange(db, mid, "user", "archived", "teardown", null)
                // Vectors of no-longer-active memories go (archive rule). Done
                // per-id here, BEFORE the status flip below — the [where] clause
                // usually includes status = 'active', which would match nothing
                // once the rows are archived.
                db.delete("embeddings", "memory_id = ?", arrayOf(mid))
            }
        }
        db.update("memories", ContentValues().apply {
            put("status", "archived"); put("updated_at", nowIso())
        }, where, args)
    }

    private fun recordDeletionTx(db: SQLiteDatabase, recordType: String, recordId: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO deleted_ids (record_type, record_id, deleted_at) VALUES (?, ?, ?)",
            arrayOf(recordType, recordId, nowIso())
        )
    }
}

private fun Cursor.getStringOrNull(column: String): String? {
    val idx = getColumnIndexOrThrow(column)
    return if (isNull(idx)) null else getString(idx)
}
