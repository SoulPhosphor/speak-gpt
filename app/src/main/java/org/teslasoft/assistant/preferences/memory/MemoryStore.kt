Warning: truncated output (original token count: 95230)
Total output lines: 7590

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
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.CorruptionErrorHandlers
import org.teslasoft.assistant.preferences.backup.DatabaseDegradedException
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.models.ModelIdentityCodec
import org.teslasoft.assistant.preferences.models.LegacyModelTargetResolver
import org.teslasoft.assistant.util.Hash
import java.time.Instant
import java.util.UUID

/**
 * The companion memory store: a SEPARATE SQLCipher database
 * (companion_memory.db), NOT an extension of lorebook.db — lorebooks stay the
 * independent low-RAM tier.
 *
 * THIS FILE — [onCreate] for a fresh install, [onUpgrade] for every
 * migration since — is the authoritative source of truth for the live
 * schema, not `Memory System/sqlite_table_plan.md`. That document is the
 * pre-revision v1.11 baseline and is kept for historical reference only
 * (see its own banner); the schema has diverged from it extensively since,
 * well beyond the four deviations once tracked here. Most notably, the
 * Phase 1/2 memory-system rework (`external_memory_analysis_counterplan.md`,
 * Revision 24, binding-clarified by `revision_25_binding_clarifications.md`)
 * retired `memories.title`, `memories.kind` and its fixed six-value Type
 * enumeration, and every permanent `memories.provenance_*`/`source_chat_id`
 * field (DB v21, v24, v25) in favor of user-owned Types (`memory_types` +
 * `memories.type_id`), 0-5 importance defaulting to neutral 0, and no
 * permanent provenance at all. `rejected_drafts.chat_key` was dropped the
 * same way in v26 — content-hash dedup never needed source-chat identity.
 *
 * Migrations: SQLiteOpenHelper's version drives onUpgrade; meta.db_migration
 * mirrors the applied number for exports/diagnostics. Always bump both, always
 * additive steps, never edit old blocks (same rule as LoreBookStore).
 */
class MemoryStore private constructor(context: Context, password: ByteArray, databaseName: String = DATABASE_NAME) :
    SQLiteOpenHelper(
        context.applicationContext, databaseName, password, null, DATABASE_VERSION, 0,
        // Explicit corruption handler (Build Phase 3): the library DEFAULT
        // deletes a corrupt database file; ours flags the store degraded and
        // preserves the file for quarantine + repair. Never pass null here.
        CorruptionErrorHandlers.Cipher(context, BackupType.MEMORY),
        null, true
    ) {

    private val appContext = context.applicationContext

    companion object {
        const val DATABASE_NAME = "companion_memory.db"
        private const val DATABASE_VERSION = 29

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
        /** One-way Stage-B authority switch. The bookmark runtime is allowed
         *  to read eligibility only after this marker and every initial
         *  bookmark were committed in the same migration transaction. */
        const val META_ASSOCIATIVE_BOOKMARK_CUTOVER = "associative_bookmark_cutover_v1"

        // A scoped vector load passes the eligible memory ids as bound
        // parameters; chunk them so the IN(...) list stays well under
        // SQLite's ~999 bound-variable ceiling (one slot is the model tag).
        private const val EMBEDDING_ID_CHUNK = 400
        // Set once the one-time purge of pre-written origin='system' modes has
        // run (owner_approved_rules.md §15 — the app pre-authors no modes).
        const val META_SYSTEM_MODES_PURGED = "system_modes_purged"

        // Companion & Roleplay Backup restore pivot (companion-roleplay-
        // backup-plan.md §6.3): while a restore is applying, its token is
        // written into meta INSIDE the replace transaction, so the token is
        // durable if and only if that transaction committed. The startup
        // recovery journal compares its own token against this row to decide
        // whether an interrupted restore rolls forward (token present) or
        // back (absent). Cleared when the restore, or its recovery, finishes.
        const val META_COMPANION_RESTORE_TOKEN = "companion_roleplay_restore_token"

        // The §2.4 record sets of the Companion & Roleplay Backup, in a
        // parents-before-children order (inserts run in this order; deletes in
        // reverse). Defined once in the pure format object so the codec, the
        // validator, and this store can never disagree about what the backup
        // carries. rp_tag_links rows whose target is a memory are NOT part of
        // the backup — see exportRoleplayTables/replaceRoleplayTables.
        val ROLEPLAY_BACKUP_TABLES: List<String> =
            org.teslasoft.assistant.preferences.backup.companion
                .CompanionBackupFormat.ROLEPLAY_TABLES

        // A transcript row past this size closes and a new row opens: keeps the
        // per-turn parse-append-write affordable and Archivist inputs bounded.
        private const val MAX_TRANSCRIPT_CHARS = 200_000

        @Volatile
        private var instance: MemoryStore? = null

        @Volatile
        private var libraryLoaded = false

        fun getInstance(context: Context): MemoryStore {
            // Degraded gate (Database Health Build Phase 3, §15.2a): once
            // damage is CONFIRMED the store is genuinely OFF — reads and
            // writes both — until a repair/restore succeeds, because reading
            // a corrupt SQLite file is itself unsafe. Checked before the
            // cached instance too, so a reference obtained pre-confirmation
            // stops being handed out the moment the flag is set. Same failure
            // envelope as the locked-key IllegalStateException below, so
            // every existing best-effort call site degrades identically.
            if (DatabaseHealthState.isDegraded(context.applicationContext, BackupType.MEMORY)) {
                throw DatabaseDegradedException(BackupType.MEMORY)
            }
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

        /**
         * Close and forget the cached helper so the database FILE can be
         * replaced underneath (repair swap / restore / fresh start —
         * DatabaseRepairManager only). The next [getInstance] reopens against
         * whatever file then exists.
         */
        fun invalidateInstance() {
            synchronized(this) {
                try { instance?.close() } catch (_: Exception) { }
                instance = null
            }
        }

        /** True once the database file exists — used to keep hooks and startup
         *  housekeeping from creating the store before the user opts in. */
        fun isProvisioned(context: Context): Boolean =
            context.getDatabasePath(DATABASE_NAME).exists()

        fun nowIso(): String = Instant.now().toString()

        fun newId(prefix: String): String = prefix + UUID.randomUUID().toString()

        /**
         * Open an ISOLATED store on a caller-named database file with a
         * caller-supplied key — for instrumentation tests only, so a test can
         * exercise onCreate/onUpgrade/import/deletion against a throwaway file
         * without touching the real singleton or the real companion_memory.db.
         * Production code must always use [getInstance].
         */
        @androidx.annotation.VisibleForTesting
        fun openForTest(context: Context, databaseName: String, password: ByteArray): MemoryStore {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
            return MemoryStore(context.applicationContext, password, databaseName)
        }
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

        // image_ref (v15): bare Profile Images hash, or NULL for none. Only
        // references profile_images.db; opening the memory store never touches
        // that catalog.
        // short_description (v16): the user-authored one-liner shown as the My
        // Personas list-row subtitle. NULL/blank means the row has no subtitle.
        db.execSQL(
            "CREATE TABLE user_personas (" +
                "persona_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "presentation TEXT NOT NULL, " +
                "status TEXT NOT NULL CHECK (status IN ('active','archived')), " +
                "created_at TEXT, " +
                "image_ref TEXT, " +
                "short_description TEXT)"
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
                "goals_drives TEXT, " +
                // image_ref (v15): bare Profile Images hash, or NULL for none.
                "image_ref TEXT)"
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

        // The user-owned Memory Types table (§5) is defined here, immediately
        // before `memories`, because memories.type_id references it.
        db.execSQL(
            // User-owned Memory Types (canonical recovery plan §5, Phase 1):
            // a human-owned category system with a stable internal id. Rename
            // edits only `name`; every memory's type_id keeps resolving. Seeded
            // once with the five starter Types (Fact/Preference/Event/Status/
            // Instruction); Lore is deliberately not a Type. A memory carries
            // zero or one Type (memories.type_id, nullable = No Type).
            "CREATE TABLE memory_types (" +
                "type_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "created_at TEXT NOT NULL)"
        )

        db.execSQL(
            "CREATE TABLE memories (" +
                "memory_id TEXT PRIMARY KEY, " +
                "scope TEXT NOT NULL CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character')), " +
                // User-owned Type (§5): nullable = No Type. The retired legacy
                // kind/title columns are gone (v25); a legacy kind from an old
                // backup is translated to a Type at import, titles are dropped.
                "type_id TEXT REFERENCES memory_types(type_id), " +
                "content TEXT NOT NULL, " +
                "embedding_text TEXT, " +
                "tags_json TEXT DEFAULT '[]', " +
                // Optional importance 0–5 (§7): 0 = neutral, the default for
                // new memories. Existing values are preserved across migration.
                "importance INTEGER NOT NULL DEFAULT 0, " +
                "always_load INTEGER NOT NULL DEFAULT 0, " +
                "world_id TEXT REFERENCES worlds(world_id), " +
                "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                "campaign_id TEXT REFERENCES campaigns(campaign_id), " +
                "project_id TEXT REFERENCES projects(project_id), " +
                "protection_json TEXT, " +
                "mode_hints_json TEXT DEFAULT '[]', " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT, " +
                "status TEXT NOT NULL CHECK (status IN ('draft','active','archived','superseded')), " +
                "supersedes TEXT REFERENCES memories(memory_id), " +
                "origin TEXT NOT NULL DEFAULT 'user', " +
                "suggested_card_type TEXT, " +
                "suggested_card_id TEXT, " +
                "suggested_section TEXT)"
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
        // mirroring memory_companions. These join tables are the SOURCE OF TRUTH
        // for ownership — the editor, the scoped-browser doors, the retrieval
        // eligibility query (Stage 3.1) AND (since the July 8 2026 owner ruling,
        // `roleplay_memory_deletion_fix.md`) the target teardown paths all read
        // them. The single memories.world_id/campaign_id/roleplay_character_id/
        // project_id columns remain as a "primary target" mirror (the first
        // selected), display-only; teardowns reassign it to a surviving owner
        // and never trust it to decide what gets deleted.
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

        // Possible Match resolution history (Step 1.5): one new memory may
        // supersede SEVERAL checked old memories (owner ruling), so the single
        // memories.supersedes column cannot be the source of truth. This
        // many-to-many table records "new_memory_id superseded old_memory_id".
        // BOTH sides cascade on delete, so a superseded memory the user later
        // deletes permanently takes its history rows with it and never blocks
        // the delete (the legacy supersedes column, a plain reference, is
        // separately nulled out in deleteMemoryTx).
        db.execSQL(
            "CREATE TABLE memory_supersessions (" +
                "new_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "old_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "at TEXT NOT NULL, " +
                "PRIMARY KEY (new_memory_id, old_memory_id))"
        )

        // Stage-D review routing. These validated Archivist relationships live
        // outside the memory object and disappear when the Pending draft is
        // accepted/deleted. They are hints only; deterministic and semantic
        // Possible Match checks still run and retain priority.
        db.execSQL(
            "CREATE TABLE memory_possible_match_hints (" +
                "draft_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "existing_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "created_at TEXT NOT NULL, " +
                "PRIMARY KEY (draft_memory_id, existing_memory_id))"
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

        // campaign_id/project_id complete the typed scene context (DB v17,
        // counterplan §4(e)); claim_run_id is the analysis claim seal (DB
        // v17, §4(a)) — deliberately FK-less like the ids stamped at capture:
        // a deleted card or finished run must never block transcript writes.
        db.execSQL(
            "CREATE TABLE transcripts (" +
                "transcript_id TEXT PRIMARY KEY, " +
                "chat_id TEXT, " +
                "companion_id TEXT REFERENCES companions(companion_id), " +
                "world_id TEXT REFERENCES worlds(world_id), " +
                "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                "user_persona_id TEXT REFERENCES user_personas(persona_id), " +
                "campaign_id TEXT, " +
                "project_id TEXT, " +
                "source TEXT NOT NULL DEFAULT 'live' CHECK (source IN ('live','imported')), " +
                "started_at TEXT, " +
                "ended_at TEXT, " +
                "content TEXT NOT NULL, " +
                "model_tag TEXT, " +
                "quick_settings_json TEXT, " +
                "review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending','processed','excluded')), " +
                "processed_at TEXT, " +
                "claim_run_id TEXT)"
        )

        // Stage B (DB v27): the sole permanent eligibility authority after
        // cutover. The nullable boundary means the chat has no terminal prefix
        // yet. skipped_transcript_ids_json is a cutover/exclusion snapshot,
        // never a second read of mutable review_status state.
        db.execSQL(
            "CREATE TABLE analysis_chat_bookmarks (" +
                "chat_id TEXT PRIMARY KEY, " +
                "last_started_at TEXT, " +
                "last_transcript_id TEXT, " +
                "skipped_transcript_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "archive_paused INTEGER NOT NULL DEFAULT 0 CHECK (archive_paused IN (0,1)), " +
                "updated_at TEXT NOT NULL)"
        )

        // One row per independently frozen chat range. A multi-chat run owns
        // several rows and may commit each one separately. Rows are short-lived
        // recovery/concurrency state and are never exported.
        db.execSQL(
            "CREATE TABLE analysis_chat_ranges (" +
                "range_id TEXT PRIMARY KEY, " +
                "run_id TEXT NOT NULL, " +
                "chat_id TEXT NOT NULL, " +
                "frozen_end_started_at TEXT, " +
                "frozen_end_transcript_id TEXT NOT NULL, " +
                "status TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running','committed')), " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT, " +
                "UNIQUE(run_id, chat_id))"
        )

        // Rejected drafts (Phase 6, DB v14; rekeyed DB v17; chat_key dropped
        // DB v26): deleting a Memory Assistant draft rejects it — a rerun must
        // not refile the exact same draft. Specific by design: exact content
        // hash, never broad similarity suppression. No source-chat identity
        // (§3.2). Device-local, never exported.
        db.execSQL(
            "CREATE TABLE rejected_drafts (" +
                "content_hash TEXT PRIMARY KEY, " +
                "deleted_at TEXT NOT NULL)"
        )

        // Archivist run history (Phase 6, DB v11): powers the Memory
        // Assistant's "Recent Memory Analysis" list and its Rerun action.
        // Device-local operational data — never exported (like embeddings),
        // no tombstones. Since DB v17 a 'running' row IS the durable
        // active-run record (counterplan §4(a)): written when a run starts,
        // finalized on completion, reconciled to failed/interrupted at the
        // next startup or run if the process died mid-run. transport is
        // 'api' today; reserved for the future computer review package,
        // whose claims the reconcile must NOT auto-release.
        db.execSQL(
            "CREATE TABLE archivist_runs (" +
                "run_id TEXT PRIMARY KEY, " +
                "started_at TEXT NOT NULL, " +
                "finished_at TEXT, " +
                "status TEXT NOT NULL CHECK (status IN ('running','complete','failed')), " +
                "chat_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "transcript_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "memory_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "rule_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "found_count INTEGER NOT NULL DEFAULT 0, " +
                "failed_chat_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "error TEXT, " +
                "outcome TEXT, " +
                "failure_reason TEXT, " +
                "transport TEXT NOT NULL DEFAULT 'api', " +
                "analysis_type TEXT NOT NULL DEFAULT 'associative')"
        )

        // Minimal temporary analysis-run storage (canonical recovery plan
        // §8.10, Phase 1 item 14). This is NOT a provenance subsystem: it holds
        // only what is needed to finish or safely recover a run — the frozen
        // end marker, effective policy, budgets, chunk progress — and is
        // cleared after successful filing or explicit cancellation. It is
        // device-local, never embedded, never exported, and never copied into a
        // Pending or saved memory. `filed` flips true only once the run's
        // consolidated candidates are safely in Pending and the bookmark
        // advanced; an unfiled row is an interrupted run whose candidates are
        // discarded on the next startup (see AnalysisRunReconciler).
        db.execSQL(
            "CREATE TABLE analysis_run_state (" +
                "run_id TEXT PRIMARY KEY, " +
                "chat_id TEXT, " +
                "frozen_end_marker TEXT, " +
                "effective_policy_json TEXT, " +
                "processing_method TEXT, " +
                "prompt_profile TEXT, " +
                "chunk_setting TEXT, " +
                "budgets_json TEXT, " +
                "chunk_ordinal INTEGER NOT NULL DEFAULT 0, " +
                "chunk_success_json TEXT NOT NULL DEFAULT '[]', " +
                "retry_count INTEGER NOT NULL DEFAULT 0, " +
                "filed INTEGER NOT NULL DEFAULT 0, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT)"
        )

        // Temporary validated candidates for an in-flight run (§8.10): held
        // outside visible Pending until the whole frozen range succeeds, so a
        // failed final chunk cannot leave a half-analysis in Pending. target_type
        // /target_id let a companion deletion atomically discard candidates aimed
        // at that companion (Phase 1 item 15). candidate_hash is the exact-dup
        // key. Cleared with its run; never exported.
        db.execSQL(
            "CREATE TABLE analysis_candidates (" +
                "candidate_id TEXT PRIMARY KEY, " +
                "run_id TEXT NOT NULL REFERENCES analysis_run_state(run_id) ON DELETE CASCADE, " +
                "stream TEXT NOT NULL DEFAULT 'general', " +
                "target_type TEXT, " +
                "target_id TEXT, " +
                "candidate_hash TEXT, " +
                "chunk_ordinal INTEGER NOT NULL DEFAULT 0, " +
                "candidate_ordinal INTEGER NOT NULL DEFAULT 0, " +
                "payload_json TEXT NOT NULL, " +
                "created_at TEXT NOT NULL)"
        )

        // Durable companion-deletion markers (DB v22, review finding 3). A row
        // here means "a confirmed companion deletion was requested but not yet
        // proven complete." The shared deletion service writes the marker BEFORE
        // running the cascade and clears it only on success, so a cascade that
        // fails or is interrupted (e.g. the best-effort persona-delete hook,
        // where the app persona is already gone and the flow cannot block) leaves
        // a durable marker the reconcile retries. No FK to companions: the marker
        // must outlive the companion row it is deleting. Device-local.
        db.execSQL(
            "CREATE TABLE pending_companion_deletions (" +
                "companion_id TEXT PRIMARY KEY, " +
                "requested_at TEXT NOT NULL)"
        )

        // Generated Pending drafts (DB v23). A row here means "this Pending draft
        // was produced by the Memory Assistant / computer analysis" — separate,
        // non-memory bookkeeping so deleting it can record a content rejection
        // (so a rerun does not refile the exact proposal) WITHOUT stamping any
        // source/route metadata on the memory object itself. Manual Pending
        // creation is never marked here, so it is never misclassified as a
        // rejection. Device-local; cascades away with the memory it references.
        db.execSQL(
            "CREATE TABLE generated_pending_drafts (" +
                "memory_id TEXT PRIMARY KEY REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                "created_at TEXT NOT NULL)"
        )

        // Lorebook suggestions (Step 1.7, DB v18): a Memory Assistant run in
        // "Lorebook Memories" analysis type files its proposed keyword-triggered
        // lore book entries here instead of as memory drafts. Lives in the same
        // database as transcripts/archivist_runs so a suggestion is filed and
        // its source conversation marked processed under the run's own
        // durability, exactly like a memory draft. Reviewed in the Lorebooks
        // Pending area; nothing reaches a real lore book until the user approves
        // an individual suggestion (which writes a LoreBookEntry and consumes
        // the row). assigned_lorebook_id is the destination the user picked at
        // review time (NULL until assigned). Device-local operational data.
        db.execSQL(
            "CREATE TABLE lorebook_suggestions (" +
                "suggestion_id TEXT PRIMARY KEY, " +
                "run_id TEXT, " +
                "content TEXT NOT NULL, " +
                "triggers_json TEXT NOT NULL DEFAULT '[]', " +
                "source_chat_id TEXT, " +
                "source_chat_name TEXT, " +
                "assigned_lorebook_id TEXT, " +
                "created_at TEXT NOT NULL)"
        )

        // Rejected lorebook suggestions (Step 1.7, DB v18): deleting a pending
        // lore book suggestion rejects it, so a rerun of the same conversation
        // does not refile the exact same suggestion. Mirrors rejected_drafts —
        // exact content hash + the source chat id (rename-safe). Never exported.
        db.execSQL(
            "CREATE TABLE rejected_lore_suggestions (" +
                "content_hash TEXT NOT NULL, " +
                "chat_key TEXT NOT NULL, " +
                "deleted_at TEXT NOT NULL, " +
                "PRIMARY KEY (content_hash, chat_key))"
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

        // Model rules (owner_approved_rules §11 Revision 6): user-written
        // patches for specific endpoint/model identities. Exact targets live
        // in model_targets_json; model_strings_json preserves unresolved
        // pre-Revision-6 fuzzy strings only. Each rule also carries any number of
        // tags (organizing labels, own pool). status='draft' = a Phase 6
        // Archivist suggestion awaiting review. Starts EMPTY: rules are hand-
        // written or arrive as Phase 6 drafts; tags are created inline.
        db.execSQL(
            "CREATE TABLE model_rules (" +
                "rule_id TEXT PRIMARY KEY, " +
                "text TEXT NOT NULL, " +
                "model_strings_json TEXT NOT NULL DEFAULT '[]', " +
                "model_targets_json TEXT NOT NULL DEFAULT '[]', " +
                "status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('draft','active')), " +
                "source_model_string TEXT, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT)"
        )
        db.execSQL(
            "CREATE TABLE model_rule_tags (" +
                "tag_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "created_at TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE model_rule_tag_links (" +
                "rule_id TEXT NOT NULL, " +
                "tag_id TEXT NOT NULL, " +
                "PRIMARY KEY (rule_id, tag_id))"
        )

        db.execSQL("CREATE INDEX idx_memories_status ON memories(status)")
        db.execSQL("CREATE INDEX idx_memories_type ON memories(type_id)")
        db.execSQL("CREATE INDEX idx_analysis_candidates_target ON analysis_candidates(target_type, target_id)")
        db.execSQL("CREATE INDEX idx_analysis_candidates_run ON analysis_candidates(run_id)")
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
        db.execSQL("CREATE INDEX idx_supersessions_old ON memory_supersessions(old_memory_id)")
        db.execSQL("CREATE INDEX idx_possible_match_hints_existing ON memory_possible_match_hints(existing_memory_id)")
        db.execSQL("CREATE INDEX idx_changelog_memory ON change_log(memory_id)")
        db.execSQL("CREATE INDEX idx_transcripts_queue ON transcripts(review_status) WHERE review_status = 'pending'")
        db.execSQL("CREATE INDEX idx_transcripts_chat ON transcripts(chat_id)")
        db.execSQL("CREATE INDEX idx_transcripts_chat_order ON transcripts(chat_id, started_at, transcript_id)")
        db.execSQL("CREATE INDEX idx_analysis_chat_ranges_run ON analysis_chat_ranges(run_id)")
        db.execSQL("CREATE INDEX idx_proposals_pending ON proposals(status) WHERE status = 'pending'")
        db.execSQL("CREATE INDEX idx_card_entries_card ON card_entries(card_type, card_id)")
        db.execSQL("CREATE INDEX idx_cpm_member ON campaign_party_members(party_member_id)")
        db.execSQL("CREATE INDEX idx_rp_tag_links_target ON rp_tag_links(target_type, target_id)")
        db.execSQL("CREATE INDEX idx_model_rules_status ON model_rules(status)")
        db.execSQL("CREATE INDEX idx_model_rule_tag_links_tag ON model_rule_tag_links(tag_id)")

        val now = nowIso()
        db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(META_SCHEMA_VERSION, "1.11.0"))
        // A fresh install is created at the latest schema, so db_migration
        // starts at the current DATABASE_VERSION (never re-runs onUpgrade steps).
        db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(META_DB_MIGRATION, DATABASE_VERSION.toString()))
        db.execSQL(
            "INSERT INTO meta (key, value) VALUES (?, ?)",
            arrayOf(META_ASSOCIATIVE_BOOKMARK_CUTOVER, "complete")
        )
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
        // Seed the five starter Memory Types once (§5.1). Fresh installs get
        // them here; upgraded installs get them in the v21 migration block.
        seedStarterMemoryTypes(db, now)
    }

    /**
     * Insert the five starter Memory Types (§5.1) if they are not already
     * present. Idempotent (INSERT OR IGNORE on the stable ids), so it is safe
     * to call from both onCreate and the v21 migration, and a re-run never
     * duplicates or overwrites a Type the user has since renamed. Types the
     * user later deletes are NOT re-seeded — the starter list is a one-time
     * convenience, not a permanent ontology.
     */
    private fun seedStarterMemoryTypes(db: SQLiteDatabase, now: String) {
        for (t in MemoryTypeMigration.STARTER_TYPES) {
            db.execSQL(
                "INSERT OR IGNORE INTO memory_types (type_id, name, created_at) VALUES (?, ?, ?)",
                arrayOf(t.typeId, t.name, now)
            )
        }
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
        if (oldVersion < 10) {
            // v10 (July 2026, Stage 4 redesign, §11 Revision 5): the owner
            // replaced the profile/group model with a model-string-primary +
            // tags model. The v9 tables never shipped a way to create data
            // (no UI, no Phase 6 filing), so this rebuild simply drops them and
            // creates the new shape — no data to preserve. model_rules gains
            // model_strings_json and loses profile_id; model_rule_profiles is
            // dropped; model_rule_tags + model_rule_tag_links are added.
            db.execSQL("DROP INDEX IF EXISTS idx_model_rules_profile")
            db.execSQL("DROP TABLE IF EXISTS model_rules")
            db.execSQL("DROP TABLE IF EXISTS model_rule_profiles")
            db.execSQL(
                "CREATE TABLE model_rules (" +
                    "rule_id TEXT PRIMARY KEY, " +
                    "text TEXT NOT NULL, " +
                    "model_strings_json TEXT NOT NULL DEFAULT '[]', " +
                    "status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('draft','active')), " +
                    "source_model_string TEXT, " +
                    "created_at TEXT NOT NULL, " +
                    "updated_at TEXT)"
            )
            db.execSQL(
                "CREATE TABLE model_rule_tags (" +
                    "tag_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE model_rule_tag_links (" +
                    "rule_id TEXT NOT NULL, " +
                    "tag_id TEXT NOT NULL, " +
                    "PRIMARY KEY (rule_id, tag_id))"
            )
            db.execSQL("CREATE INDEX idx_model_rules_status ON model_rules(status)")
            db.execSQL("CREATE INDEX idx_model_rule_tag_links_tag ON model_rule_tag_links(tag_id)")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "10")
            )
        }
        if (oldVersion < 11) {
            // v11 (July 2026, Phase 6): Archivist run history. Purely
            // additive; starts empty — rows are written only by real analysis
            // runs. See the onCreate comment for what it powers.
            db.execSQL(
                "CREATE TABLE archivist_runs (" +
                    "run_id TEXT PRIMARY KEY, " +
                    "started_at TEXT NOT NULL, " +
                    "finished_at TEXT, " +
                    "status TEXT NOT NULL CHECK (status IN ('complete','failed')), " +
                    "chat_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "transcript_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "memory_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "rule_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "found_count INTEGER NOT NULL DEFAULT 0, " +
                    "failed_chat_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "error TEXT)"
            )
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "11")
            )
        }
        if (oldVersion < 12) {
            // v12 (July 2026, Phase 6): the status/failure wording spec needs
            // each run row to carry its display outcome and dominant failure
            // reason (archivist_status_wording_spec.md). Additive.
            db.execSQL("ALTER TABLE archivist_runs ADD COLUMN outcome TEXT")
            db.execSQL("ALTER TABLE archivist_runs ADD COLUMN failure_reason TEXT")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "12")
            )
        }
        if (oldVersion < 13) {
            // v13 (July 2026, Phase 6): Archivist card-placement suggestions
            // (phase6_card_suggestions_and_icons_design.md §2/§7 + the July 8
            // evening rulings). A roleplay DRAFT may carry a proposed card +
            // section, pre-selecting the Add-to-Card / Link dropdowns and
            // giving the row its outline treatment. Draft-only metadata: it
            // is cleared when the draft is accepted without a card and dies
            // with the row on convert/delete; never exported. Additive.
            db.execSQL("ALTER TABLE memories ADD COLUMN suggested_card_type TEXT")
            db.execSQL("ALTER TABLE memories ADD COLUMN suggested_card_id TEXT")
            db.execSQL("ALTER TABLE memories ADD COLUMN suggested_section TEXT")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "13")
            )
        }
        if (oldVersion < 14) {
            // v14 (July 2026, Phase 6): rejected-draft tracking (owner
            // preference, round-3 rulings). Deleting a Memory Assistant
            // DRAFT counts as rejecting it: rerunning the same analysis must
            // not immediately recreate the exact same draft. Rejection is
            // deliberately SPECIFIC — exact title+content hash, scoped to
            // the source conversation — never a broad similarity rule that
            // could swallow useful memories. Device-local, never exported,
            // emptied by Reset memories. Additive.
            db.execSQL(
                "CREATE TABLE rejected_drafts (" +
                    "content_hash TEXT NOT NULL, " +
                    "chat_key TEXT NOT NULL, " +
                    "deleted_at TEXT NOT NULL, " +
                    "PRIMARY KEY (content_hash, chat_key))"
            )
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATIO…55230 tokens truncated…_strings_json", stringArrayJson(resolution.unresolved))
                            put("model_targets_json", ModelIdentityCodec.encode(targets))
                            put("updated_at", nowIso())
                        },
                        "rule_id = ?",
                        arrayOf(rule.ruleId)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Remove exact unavailable targets from every affected rule. */
    fun removeModelTargets(targetsToRemove: Set<ModelIdentity>): ModelRuleTargetRemoval {
        if (targetsToRemove.isEmpty()) return ModelRuleTargetRemoval(0, 0, 0)
        var removedTargets = 0
        var updatedRules = 0
        var deletedRules = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val rules = ArrayList<ModelRuleRecord>()
            db.query("model_rules", null, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) rules.add(readModelRule(cursor))
            }
            for (rule in rules) {
                val before = ModelIdentityCodec.decode(rule.modelTargetsJson)
                val after = before.filterNot { it in targetsToRemove }
                val removedHere = before.size - after.size
                if (removedHere == 0) continue
                removedTargets += removedHere
                if (after.isEmpty()) {
                    db.delete("model_rule_tag_links", "rule_id = ?", arrayOf(rule.ruleId))
                    db.delete("model_rules", "rule_id = ?", arrayOf(rule.ruleId))
                    recordDeletionTx(db, "model_rule", rule.ruleId)
                    deletedRules++
                } else {
                    db.update(
                        "model_rules",
                        ContentValues().apply {
                            put("model_targets_json", ModelIdentityCodec.encode(after))
                            // A touched rule is now governed only by the exact
                            // endpoint/model targets retained above.
                            put("model_strings_json", "[]")
                            put("updated_at", nowIso())
                        },
                        "rule_id = ?",
                        arrayOf(rule.ruleId)
                    )
                    updatedRules++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ModelRuleTargetRemoval(removedTargets, updatedRules, deletedRules)
    }

    private fun parseStringArray(json: String): List<String> = try {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.isNotEmpty() }
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun stringArrayJson(values: Collection<String>): String =
        org.json.JSONArray(values.toList()).toString()

    /** Accept a draft (§11): draft -> active. The user assigns the model
     *  strings and tags separately (via the editor) before accepting. */
    fun acceptModelRule(ruleId: String) {
        writableDatabase.update(
            "model_rules", ContentValues().apply { put("status", "active"); put("updated_at", nowIso()) },
            "rule_id = ?", arrayOf(ruleId)
        )
    }

    /** Draft rules — what the pinned Pending banner and other pointer rows
     *  count (§11 / work order). Stays 0 until Phase 6 files drafts. */
    fun countModelRuleDrafts(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM model_rules WHERE status = 'draft'", null
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /* ---- model-rule tags (own pool; plain labels, no colors) ---- */

    fun getModelRuleTags(): List<ModelRuleTagRecord> {
        val out = ArrayList<ModelRuleTagRecord>()
        readableDatabase.query("model_rule_tags", null, null, null, null, null, "name ASC").use {
            while (it.moveToNext()) out.add(readModelRuleTag(it))
        }
        return out
    }

    private fun readModelRuleTag(c: Cursor) = ModelRuleTagRecord(
        tagId = c.getString(c.getColumnIndexOrThrow("tag_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: ""
    )

    fun upsertModelRuleTag(t: ModelRuleTagRecord) {
        writableDatabase.insertWithOnConflict(
            "model_rule_tags",
            null,
            ContentValues().apply {
                put("tag_id", t.tagId)
                put("name", t.name)
                put("created_at", t.createdAt.ifEmpty { nowIso() })
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Inline tag creation: return the existing tag with this name (case-
     *  insensitive) or create one. Names are the identity users see, so we
     *  never make a second tag for the same word. */
    fun findOrCreateModelRuleTag(name: String): ModelRuleTagRecord? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        readableDatabase.query(
            "model_rule_tags", null, "name = ? COLLATE NOCASE", arrayOf(trimmed), null, null, null
        ).use { if (it.moveToFirst()) return readModelRuleTag(it) }
        val rec = ModelRuleTagRecord(tagId = newId("mrt_"), name = trimmed, createdAt = nowIso())
        upsertModelRuleTag(rec)
        return rec
    }

    /** Delete a tag and scrub its links; the rules themselves are untouched. */
    fun deleteModelRuleTag(tagId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("model_rule_tag_links", "tag_id = ?", arrayOf(tagId))
            db.delete("model_rule_tags", "tag_id = ?", arrayOf(tagId))
            recordDeletionTx(db, "model_rule_tag", tagId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getTagsForRule(ruleId: String): List<ModelRuleTagRecord> {
        val out = ArrayList<ModelRuleTagRecord>()
        readableDatabase.rawQuery(
            "SELECT t.tag_id, t.name, t.created_at FROM model_rule_tags t " +
                "JOIN model_rule_tag_links l ON l.tag_id = t.tag_id " +
                "WHERE l.rule_id = ? ORDER BY t.name ASC",
            arrayOf(ruleId)
        ).use { while (it.moveToNext()) out.add(readModelRuleTag(it)) }
        return out
    }

    /** Replace a rule's tag set with [tagIds] (deduped by the join PK). */
    fun setTagsForRule(ruleId: String, tagIds: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("model_rule_tag_links", "rule_id = ?", arrayOf(ruleId))
            for (tagId in tagIds.distinct()) {
                db.execSQL(
                    "INSERT OR IGNORE INTO model_rule_tag_links (rule_id, tag_id) VALUES (?, ?)",
                    arrayOf(ruleId, tagId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Rules carrying [tagId] (the "tap a tag → everything" view). */
    fun getModelRulesForTag(tagId: String): List<ModelRuleRecord> {
        val out = ArrayList<ModelRuleRecord>()
        readableDatabase.rawQuery(
            "SELECT r.* FROM model_rules r " +
                "JOIN model_rule_tag_links l ON l.rule_id = r.rule_id " +
                "WHERE l.tag_id = ? ORDER BY r.created_at ASC, r.rule_id ASC",
            arrayOf(tagId)
        ).use { while (it.moveToNext()) out.add(readModelRule(it)) }
        return out
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
            db.delete("analysis_chat_ranges", null, null)
            db.delete("analysis_chat_bookmarks", null, null)
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
            // Run history describes conversations/memories that no longer
            // exist after a reset — it empties with everything else, as does
            // the rejected-draft record.
            db.delete("archivist_runs", null, null)
            db.delete("rejected_drafts", null, null)
            // Step 1.7: pending lore book suggestions and their rejection
            // record describe conversations that no longer exist after a
            // reset — they empty with everything else.
            db.delete("lorebook_suggestions", null, null)
            db.delete("rejected_lore_suggestions", null, null)
            db.update("app_state", ContentValues().apply {
                putNull("active_companion_id"); putNull("active_world_id")
                putNull("active_roleplay_character_id"); putNull("active_user_persona_id")
            }, "id = 1", null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Text browser (content LIKE, else most-recent). Archived rows are
     *  hidden unless [includeArchived]. */
    fun browseMemories(query: String?, includeArchived: Boolean, limit: Int): List<MemoryRecord> {
        val db = readableDatabase
        val where = StringBuilder()
        val args = ArrayList<String>()
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) {
            val like = "%${q.replace("%", "").replace("_", "")}%"
            where.append("content LIKE ?")
            args.add(like)
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
            createdAt = it.getString(it.getColumnIndexOrThrow("created_at")) ?: "",
            updatedAt = it.getStringOrNull("updated_at"),
            status = it.getString(it.getColumnIndexOrThrow("status")),
            supersedes = it.getStringOrNull("supersedes"),
            companionIds = readJoin(db, "memory_companions", "companion_id", id),
            entityRefs = readJoin(db, "memory_entities", "entity_id", id),
            changeLog = readChangeLog(db, id),
            origin = it.getStringOrNull("origin") ?: "user",
            suggestedCardType = it.getStringOrNull("suggested_card_type"),
            suggestedCardId = it.getStringOrNull("suggested_card_id"),
            suggestedSection = it.getStringOrNull("suggested_section"),
            typeId = it.getStringOrNull("type_id")
        )
    }

    private fun memoryValues(m: MemoryRecord) = ContentValues().apply {
        put("memory_id", m.memoryId)
        put("scope", m.scope)
        // User-owned Type (§5); null = No Type.
        put("type_id", m.typeId)
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
        put("created_at", m.createdAt)
        put("updated_at", m.updatedAt)
        put("status", m.status)
        put("supersedes", m.supersedes)
        put("origin", m.origin)
        put("suggested_card_type", m.suggestedCardType)
        put("suggested_card_id", m.suggestedCardId)
        put("suggested_section", m.suggestedSection)
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
            // §5.5: the condensed embedding_text is derived, model/import-
            // provided data — no editor exposes it as a user field. When any
            // source text field changes it is stale by definition and is
            // cleared, so a corrected memory can never stay discoverable by
            // its old condensed wording. Only content and tags affect the
            // semantic document.
            val sourceTextChanged = prior == null ||
                prior.content != m.content || prior.tagsJson != m.tagsJson
            val updated = m.copy(
                updatedAt = nowIso(),
                embeddingText = if (sourceTextChanged) null else m.embeddingText
            )
            db.update("memories", memoryValues(updated), "memory_id = ?", arrayOf(m.memoryId))
            writeMemoryLinks(db, updated)
            logChange(db, m.memoryId, "user", "edited", note, prior?.let { snapshotMemoryJson(it) })
            val textChanged = sourceTextChanged ||
                (prior?.embeddingText ?: "") != (updated.embeddingText ?: "")
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
                // A status change is the user's decision on the draft — any
                // unactioned card-placement suggestion is discarded with it
                // (suggestions are draft-only metadata; §7's outline drops).
                putNull("suggested_card_type")
                putNull("suggested_card_id")
                putNull("suggested_section")
            }, "memory_id = ?", arrayOf(memoryId))
            db.delete("embeddings", "memory_id = ?", arrayOf(memoryId))
            // Accepting a generated draft (draft → active) clears the route
            // bookkeeping so the memory's later deletion as an Active memory
            // is never misclassified as a Pending-proposal rejection.
            if (status == "active") {
                db.delete("generated_pending_drafts", "memory_id = ?", arrayOf(memoryId))
                db.delete(
                    "memory_possible_match_hints", "draft_memory_id = ?", arrayOf(memoryId)
                )
            }
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
            deleteMemoryTx(db, memoryId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The delete body, callable from a resolution transaction (Save & Replace)
     *  as well as the standalone [deleteMemory]. */
    private fun deleteMemoryTx(db: SQLiteDatabase, memoryId: String) {
        // Deleting a Memory Assistant / computer-generated DRAFT is a rejection
        // (owner preference, July 9 2026): remember it so a rerun does not refile
        // the exact same draft. Keyed on the memory CONTENT only (canonical
        // recovery plan §3.2 / item 1): a memory never remembers which chat
        // produced it, so the rejection carries no source-chat identity and no
        // title (§3.1). Whether the draft was generated is read from the separate
        // generated_pending_drafts bookkeeping — NOT from any source field on the
        // memory (the canonical record has none). Manual creation is never marked,
        // so deleting a manual draft registers nothing.
        val prior = getMemory(memoryId)
        if (prior != null && prior.status == "draft" && isGeneratedPendingDraftTx(db, memoryId)) {
            db.execSQL(
                "INSERT OR REPLACE INTO rejected_drafts (content_hash, deleted_at) VALUES (?, ?)",
                arrayOf(draftContentHash(prior.content), nowIso())
            )
        }
        // Clear the generated marker for this id (also handled by the FK cascade
        // on the memories delete below; explicit here for clarity/robustness).
        db.delete("generated_pending_drafts", "memory_id = ?", arrayOf(memoryId))
        // Supersession safety (Step 1.5): the legacy memories.supersedes column
        // is a plain reference with no ON DELETE rule, so a newer memory still
        // pointing here would block this delete — breaking the rule that a
        // superseded memory stays permanently deletable. Clear those pointers
        // first. The memory_supersessions rows cascade on their own foreign
        // keys and need no manual cleanup.
        db.execSQL("UPDATE memories SET supersedes = NULL WHERE supersedes = ?", arrayOf(memoryId))
        db.delete("memories", "memory_id = ?", arrayOf(memoryId))
        recordDeletionTx(db, "memory", memoryId)
        clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, memoryId)
    }

    /* ---------------------------------------------------------------------- */
    /* Possible Match detection + resolution (Step 1.5)                        */
    /* ---------------------------------------------------------------------- */

    /** All target IDs a memory row carries, across every scope's join table —
     *  the placement half of its Possible Match identity. */
    private fun allTargetIds(db: SQLiteDatabase, memoryId: String): List<String> =
        readJoin(db, "memory_companions", "companion_id", memoryId) +
            readJoin(db, "memory_worlds", "world_id", memoryId) +
            readJoin(db, "memory_campaigns", "campaign_id", memoryId) +
            readJoin(db, "memory_roleplay_characters", "roleplay_character_id", memoryId) +
            readJoin(db, "memory_projects", "project_id", memoryId)

    private fun unionTargets(m: MemoryRecord): List<String> =
        m.companionIds + m.worldIds + m.campaignIds + m.roleplayCharacterIds + m.projectIds

    /** Every memory in the same scope as the candidate — the only rows that can
     *  be an exact or (fiction-wall-respecting) near match. [excludeId] drops a
     *  draft comparing against itself. */
    private fun loadComparableLibrary(scope: String, excludeId: String?): List<MemoryMatch.Existing> {
        val db = readableDatabase
        val out = ArrayList<MemoryMatch.Existing>()
        db.rawQuery(
            "SELECT memory_id, content, scope, type_id, status FROM memories WHERE scope = ?",
            arrayOf(scope)
        ).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                if (id == excludeId) continue
                out.add(
                    MemoryMatch.Existing(
                        memoryId = id,
                        content = it.getString(1),
                        scope = it.getString(2),
                        typeId = if (it.isNull(3)) null else it.getString(3),
                        status = it.getString(4),
                        targetIds = allTargetIds(db, id)
                    )
                )
            }
        }
        return out
    }

    /**
     * The staging gate (counterplan §5.2(b)): classify a proposed memory against
     * the library so the runner suppresses a true duplicate and files everything
     * else. Runs when a suggestion is staged. Lexical similarity only — the
     * honest signal with or without an embedding model.
     */
    fun classifyCandidate(candidate: MemoryMatch.Candidate): MemoryMatch.Outcome =
        MemoryMatch.classify(candidate, loadComparableLibrary(candidate.scope, excludeId = null))

    /**
     * The DETERMINISTIC exact Possible Matches for a still-pending draft:
     * different-type or archived/superseded exact matches. Recomputed on demand
     * so it is always current, which satisfies the counterplan's "revalidate
     * when the user resolves it". The differently-worded semantic layer is added
     * on top by [org.teslasoft.assistant.preferences.memory.PossibleMatchFinder]
     * using the embedding model; this method never uses similarity. Empty for a
     * non-existent or non-draft id.
     */
    fun deterministicMatchesForDraft(draftId: String): List<MemoryMatch.Match> {
        val m = getMemory(draftId) ?: return emptyList()
        if (m.status != "draft") return emptyList()
        val candidate = MemoryMatch.Candidate(m.content, m.scope, m.typeId, unionTargets(m))
        return when (val o = MemoryMatch.classify(candidate, loadComparableLibrary(m.scope, excludeId = draftId))) {
            is MemoryMatch.Outcome.Possible -> o.matches
            else -> emptyList()
        }
    }

    /** Validated Archivist relationship hints for a still-Pending draft.
     * Stored separately from memory data and re-read live so deleted targets
     * disappear automatically. */
    fun relationshipHintsForDraft(draftId: String): List<MemoryMatch.Match> {
        val draft = getMemory(draftId) ?: return emptyList()
        if (draft.status != "draft") return emptyList()
        val out = ArrayList<MemoryMatch.Match>()
        readableDatabase.rawQuery(
            "SELECT h.existing_memory_id FROM memory_possible_match_hints h " +
                "JOIN memories m ON m.memory_id = h.existing_memory_id " +
                "WHERE h.draft_memory_id = ? ORDER BY h.created_at ASC, h.existing_memory_id ASC",
            arrayOf(draftId)
        ).use {
            while (it.moveToNext()) {
                out.add(MemoryMatch.Match(it.getString(0), MemoryMatch.Relation.AI_RELATED))
            }
        }
        return out
    }

    /** Active memory ids whose placement is comparable to the draft's (same
     *  scope, sharing a target or both untargeted) — the bounded working set the
     *  embedding layer scores for semantic Possible Matches using the STORED
     *  index. Excludes the draft itself; empty for a non-existent draft. */
    fun comparableActiveMemoryIds(draftId: String): List<String> {
        val m = getMemory(draftId) ?: return emptyList()
        val cTargets = unionTargets(m)
        val db = readableDatabase
        val out = ArrayList<String>()
        db.rawQuery(
            "SELECT memory_id FROM memories WHERE scope = ? AND status = 'active'",
            arrayOf(m.scope)
        ).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                if (id == draftId) continue
                if (MemoryMatch.comparablePlacement(m.scope, cTargets, m.scope, allTargetIds(db, id))) {
                    out.add(id)
                }
            }
        }
        return out
    }

    /** Archived and superseded memories whose placement is comparable to the
     *  draft's — the Possible Match comparison is allowed to reach them (owner
     *  ruling, Step 1.5), even though they are barred from chat retrieval. They
     *  hold NO stored vector (the archive rule), so their embedding text rides
     *  along for the finder to regenerate a vector on demand, purely for
     *  comparison; nothing is persisted and their retrieval-eligibility is
     *  untouched. Excludes the draft; empty for a non-existent draft. */
    fun comparableInactiveDocsForDraft(draftId: String): List<MemoryComparisonDoc> {
        val m = getMemory(draftId) ?: return emptyList()
        val cTargets = unionTargets(m)
        val db = readableDatabase
        val out = ArrayList<MemoryComparisonDoc>()
        db.rawQuery(
            "SELECT memory_id, content, embedding_text, tags_json FROM memories " +
                "WHERE scope = ? AND status IN ('archived','superseded')",
            arrayOf(m.scope)
        ).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                if (id == draftId) continue
                if (MemoryMatch.comparablePlacement(m.scope, cTargets, m.scope, allTargetIds(db, id))) {
                    out.add(
                        MemoryComparisonDoc(
                            memoryId = id,
                            content = it.getString(1),
                            embeddingText = it.getStringOrNull("embedding_text"),
                            tagsJson = it.getStringOrNull("tags_json") ?: "[]"
                        )
                    )
                }
            }
        }
        return out
    }

    /** The old memories a given new memory superseded (resolution history). */
    fun supersededMemoryIds(newMemoryId: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT old_memory_id FROM memory_supersessions WHERE new_memory_id = ? ORDER BY at ASC",
            arrayOf(newMemoryId)
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    /** The exact recorded relationship timestamp for an old Superseded memory.
     * A defensive MAX handles a history imported with more than one newer row. */
    fun supersededAt(oldMemoryId: String): String? = readableDatabase.rawQuery(
        "SELECT MAX(at) FROM memory_supersessions WHERE old_memory_id = ?",
        arrayOf(oldMemoryId)
    ).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }

    /** Outcome of an atomic Possible Match resolution. */
    sealed class ResolutionResult {
        /** Applied. [reindexMemoryIds] are now-active memories whose embeddings
         *  were dropped and should be re-indexed by the caller (the proposal,
         *  plus an edited old memory). */
        data class Applied(val reindexMemoryIds: List<String>) : ResolutionResult()

        /** The proposal no longer exists or was already resolved since the user
         *  opened Review — nothing was changed. The caller re-reads Pending. */
        object StaleProposal : ResolutionResult()
    }

    /** Flip a still-pending proposal to active inside an open transaction.
     *  Returns false (leaving the transaction to be rolled back) when the
     *  proposal vanished or is no longer a draft — the resolution-time
     *  revalidation. Mirrors [setMemoryStatus]'s active-path bookkeeping:
     *  clears card-placement suggestions, drops embeddings for a fresh re-index,
     *  logs the activation, and resets the freshness cooldown. */
    private fun activateProposalTx(db: SQLiteDatabase, proposalId: String): Boolean {
        val p = getMemory(proposalId) ?: return false
        if (p.status != "draft") return false
        db.update("memories", ContentValues().apply {
            put("status", "active")
            put("updated_at", nowIso())
            putNull("suggested_card_type")
            putNull("suggested_card_id")
            putNull("suggested_section")
        }, "memory_id = ?", arrayOf(proposalId))
        db.delete("embeddings", "memory_id = ?", arrayOf(proposalId))
        // Acceptance clears the generated-draft route marker here too, exactly
        // as in setMemoryStatus: no route/source bookkeeping may stay attached
        // to an Active memory, whichever way the proposal was accepted.
        db.delete("generated_pending_drafts", "memory_id = ?", arrayOf(proposalId))
        db.delete(
            "memory_possible_match_hints", "draft_memory_id = ?", arrayOf(proposalId)
        )
        logChange(db, proposalId, "user", "activated", null, snapshotMemoryJson(p))
        clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, proposalId)
        return true
    }

    /** Mark one old memory superseded inside an open transaction and record the
     *  many-to-many history link to the new memory. */
    private fun supersedeMemoryTx(db: SQLiteDatabase, oldId: String, byNewId: String) {
        val prior = getMemory(oldId) ?: return
        db.update("memories", ContentValues().apply {
            put("status", "superseded")
            put("updated_at", nowIso())
            putNull("suggested_card_type")
            putNull("suggested_card_id")
            putNull("suggested_section")
        }, "memory_id = ?", arrayOf(oldId))
        db.delete("embeddings", "memory_id = ?", arrayOf(oldId))
        logChange(db, oldId, "user", "superseded", null, snapshotMemoryJson(prior))
        clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, oldId)
        db.insertWithOnConflict("memory_supersessions", null, ContentValues().apply {
            put("new_memory_id", byNewId)
            put("old_memory_id", oldId)
            put("at", nowIso())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * **Save & Edit Old Memory** (single match): save the proposal as active and
     * keep the old memory active, applying the user's edits to it. Pass the
     * edited old memory in [editedOld]; pass null to keep it unchanged. Both
     * writes happen in one transaction.
     */
    fun resolveSaveAndEditOld(proposalId: String, editedOld: MemoryRecord?): ResolutionResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!activateProposalTx(db, proposalId)) return ResolutionResult.StaleProposal
            val reindex = arrayListOf(proposalId)
            if (editedOld != null) {
                val prior = getMemory(editedOld.memoryId)
                if (prior != null) {
                    // Same rules as updateMemory: a changed source field clears
                    // the derived embedding_text and drops the vector so the
                    // corrected memory can never stay discoverable by stale
                    // wording. The old memory stays active.
                    // Titles are retired (§3.1) and are not part of the embedding,
                    // so only content and tags count as a source-text change.
                    val sourceTextChanged =
                        prior.content != editedOld.content || prior.tagsJson != editedOld.tagsJson
                    val updated = editedOld.copy(
                        status = "active",
                        updatedAt = nowIso(),
                        embeddingText = if (sourceTextChanged) null else editedOld.embeddingText
                    )
                    db.update("memories", memoryValues(updated), "memory_id = ?", arrayOf(updated.memoryId))
                    writeMemoryLinks(db, updated)
                    logChange(db, updated.memoryId, "user", "edited", null, snapshotMemoryJson(prior))
                    val textChanged = sourceTextChanged ||
                        (prior.embeddingText ?: "") != (updated.embeddingText ?: "")
                    if (textChanged) {
                        db.delete("embeddings", "memory_id = ?", arrayOf(updated.memoryId))
                        reindex.add(updated.memoryId)
                    }
                    clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, updated.memoryId)
                }
            }
            db.setTransactionSuccessful()
            return ResolutionResult.Applied(reindex)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * **Save & Replace**: save the proposal as active and permanently delete the
     * checked old memories. One or several olds; all in one transaction. An old
     * memory that vanished since Review opened is skipped, never resurrected.
     */
    fun resolveReplace(proposalId: String, oldMemoryIds: List<String>): ResolutionResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!activateProposalTx(db, proposalId)) return ResolutionResult.StaleProposal
            for (oldId in oldMemoryIds.distinct()) {
                if (oldId == proposalId) continue
                if (getMemory(oldId) == null) continue
                deleteMemoryTx(db, oldId)
            }
            db.setTransactionSuccessful()
            return ResolutionResult.Applied(listOf(proposalId))
        } finally {
            db.endTransaction()
        }
    }

    /**
     * **Save & Supersede**: save the proposal as active and mark the checked old
     * memories superseded, retaining them as history. One or several olds; the
     * many-to-many [supersededMemoryIds] link records every one. The legacy
     * single supersedes column is set only when exactly one old is superseded
     * (best-effort for existing single-pointer readers); the join table is the
     * source of truth for the multiple case.
     */
    fun resolveSupersede(proposalId: String, oldMemoryIds: List<String>): ResolutionResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!activateProposalTx(db, proposalId)) return ResolutionResult.StaleProposal
            val superseded = ArrayList<String>()
            for (oldId in oldMemoryIds.distinct()) {
                if (oldId == proposalId) continue
                if (getMemory(oldId) == null) continue
                supersedeMemoryTx(db, oldId, proposalId)
                superseded.add(oldId)
            }
            if (superseded.size == 1) {
                db.execSQL(
                    "UPDATE memories SET supersedes = ? WHERE memory_id = ?",
                    arrayOf(superseded[0], proposalId)
                )
            }
            db.setTransactionSuccessful()
            return ResolutionResult.Applied(listOf(proposalId))
        } finally {
            db.endTransaction()
        }
    }

    /** Whether an identical draft (exact content) was deleted before — the
     *  runner skips refiling it. */
    fun isDraftRejected(content: String): Boolean {
        readableDatabase.query(
            "rejected_drafts", arrayOf("content_hash"),
            "content_hash = ?",
            arrayOf(draftContentHash(content)), null, null, null
        ).use { return it.moveToNext() }
    }

    private fun draftContentHash(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
        put("content", m.content)
        put("scope", m.scope)
        m.typeId?.let { put("type_id", it) }
        put("importance", m.importance)
        put("status", m.status)
        put("tags_json", m.tagsJson)
        m.protectionJson?.let { put("protection_json", it) }
    }.toString()

    /* -------- teardown helpers (must run inside an open transaction) -------- */

    /**
     * Handles a deleted target's memories per the owner's July 8 2026 ruling
     * (`Memory System/roleplay_memory_deletion_fix.md`): ownership is read
     * from the JOIN table, never the mirror column. Shared memories are always
     * kept (their link to the dead target removed, mirror reassigned to a
     * surviving owner); [deleteMemories] hard-deletes only sole-owned ones.
     * [keepCharacterMemories] (worlds only) keeps memories that have any
     * memory_roleplay_characters row — the join-based reading of "a
     * character's memory too". Ends with two safety sweeps so neither a join
     * row nor a mirror value can survive pointing at the deleted target
     * (a leftover mirror would break the FK when the target row goes).
     */
    private fun teardownTargetMemoriesTx(
        db: SQLiteDatabase,
        joinTable: String,
        idColumn: String,
        targetId: String,
        deleteMemories: Boolean,
        keepCharacterMemories: Boolean = false
    ) {
        val owned = ArrayList<TargetTeardownPlanner.OwnedMemory>()
        db.query(
            joinTable, arrayOf("memory_id"), "$idColumn = ?", arrayOf(targetId),
            null, null, "memory_id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val memoryId = c.getString(0)
                val others = ArrayList<String>()
                db.query(
                    joinTable, arrayOf(idColumn), "memory_id = ? AND $idColumn != ?",
                    arrayOf(memoryId, targetId), null, null, "$idColumn ASC"
                ).use { oc -> while (oc.moveToNext()) others.add(oc.getString(0)) }
                var mirror: String? = null
                db.query(
                    "memories", arrayOf(idColumn), "memory_id = ?", arrayOf(memoryId),
                    null, null, null
                ).use { mc -> if (mc.moveToNext() && !mc.isNull(0)) mirror = mc.getString(0) }
                var hasCharacterLink = false
                if (keepCharacterMemories) {
                    db.query(
                        "memory_roleplay_characters", arrayOf("memory_id"),
                        "memory_id = ?", arrayOf(memoryId), null, null, null
                    ).use { rc -> hasCharacterLink = rc.moveToNext() }
                }
                owned.add(TargetTeardownPlanner.OwnedMemory(memoryId, others, mirror, hasCharacterLink))
            }
        }
        val plan = TargetTeardownPlanner.plan(targetId, owned, deleteMemories, keepCharacterMemories)
        for (memoryId in plan.deleteMemoryIds) {
            deleteMemoriesWhere(db, "memory_id = ?", arrayOf(memoryId))
        }
        for (memoryId in plan.unlinkMemoryIds) {
            db.delete(joinTable, "memory_id = ? AND $idColumn = ?", arrayOf(memoryId, targetId))
        }
        for ((memoryId, newMirror) in plan.mirrorReassignments) {
            db.update("memories", ContentValues().apply {
                if (newMirror == null) putNull(idColumn) else put(idColumn, newMirror)
            }, "memory_id = ?", arrayOf(memoryId))
        }
        // Safety sweeps: harmless after the plan ran; they also catch rows the
        // join-driven plan can't see (a mirror-only memory with no join row —
        // join-as-truth says it isn't owned, so it survives with the mirror
        // cleared rather than dangling at a deleted card).
        db.delete(joinTable, "$idColumn = ?", arrayOf(targetId))
        db.update(
            "memories", ContentValues().apply { putNull(idColumn) },
            "$idColumn = ?", arrayOf(targetId)
        )
    }

    private fun deleteMemoriesWhere(db: SQLiteDatabase, where: String, args: Array<String>) {
        // Tombstone each id first (change_log + embeddings cascade on delete).
        // Cooldown rows have no FK, so they're cleared per id here — same
        // contract as the single-memory deleteMemory path.
        db.query("memories", arrayOf("memory_id"), where, args, null, null, null).use {
            while (it.moveToNext()) {
                val memoryId = it.getString(0)
                recordDeletionTx(db, "memory", memoryId)
                clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, memoryId)
            }
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

    /* ---------------------------------------------------------------------- */
    /* Companion & Roleplay Backup (companion-roleplay-backup-plan.md)         */
    /* ---------------------------------------------------------------------- */

    /** True when any §2.4 roleplay-structure row exists — drives the restore's
     *  replace-confirmation (§6.2). */
    fun hasAnyRoleplayStructure(): Boolean {
        val db = readableDatabase
        for (table in ROLEPLAY_BACKUP_TABLES) {
            db.rawQuery("SELECT 1 FROM $table LIMIT 1", emptyArray<String>()).use {
                if (it.moveToFirst()) return true
            }
        }
        return false
    }

    /**
     * Every §2.4 row as a column -> value map, exactly as stored (all
     * columns). rp_tag_links rows whose target is a memory are excluded
     * (memories are not in this backup; §2.4). The restore side maps these
     * rows onto whatever the current schema is (§3), which is why the shape
     * is a plain map and never a raw database copy.
     */
    fun exportRoleplayTables(): LinkedHashMap<String, List<Map<String, Any?>>> {
        val db = readableDatabase
        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (table in ROLEPLAY_BACKUP_TABLES) {
            val rows = ArrayList<Map<String, Any?>>()
            val sql = if (table == "rp_tag_links") {
                "SELECT * FROM rp_tag_links WHERE target_type != 'memory'"
            } else {
                "SELECT * FROM $table"
            }
            db.rawQuery(sql, emptyArray<String>()).use { c ->
                while (c.moveToNext()) {
                    val row = LinkedHashMap<String, Any?>()
                    for (i in 0 until c.columnCount) {
                        row[c.getColumnName(i)] = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB ->
                                // No §2.4 table defines a BLOB column. Failing
                                // loudly beats silently dropping a value the
                                // user expects the backup to carry.
                                throw IllegalStateException(
                                    "unsupported blob column in $table"
                                )
                            else -> c.getString(i)
                        }
                    }
                    rows.add(row)
                }
            }
            out[table] = rows
        }
        return out
    }

    /**
     * The §6.3 step-2 replace: delete the existing §2.4 record sets, insert
     * [tables]' rows, apply the §6.4 resolution rules, write the restore
     * token into meta, run [beforeCommit] (the caller's staged app-settings
     * apply), and only then commit — all one transaction with foreign-key
     * enforcement deferred to commit. Any exception (including one thrown by
     * [beforeCommit]) rolls the whole database change back automatically.
     *
     * Memories are never deleted or modified here beyond the §6.4 rules:
     * join rows and mirror/context ids that no longer resolve are removed or
     * cleared; everything that resolves is kept.
     */
    fun replaceRoleplayTables(
        tables: Map<String, List<Map<String, Any?>>>,
        restoreToken: String,
        beforeCommit: () -> Unit
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // FK checks deferred to commit (§6.3.2): the replace order stays
            // simple and the backup's internal references are verified in one
            // shot when the transaction commits.
            db.execSQL("PRAGMA defer_foreign_keys = ON")

            // Existing tag -> memory links survive the replace when their tag
            // id does (§6.4). Deleting rp_tags cascades every link, so capture
            // them first and re-add the survivors after the insert.
            val memoryTagLinks = ArrayList<Pair<String, String>>()
            db.rawQuery(
                "SELECT tag_id, target_id FROM rp_tag_links WHERE target_type = 'memory'",
                emptyArray<String>()
            ).use {
                while (it.moveToNext()) memoryTagLinks.add(it.getString(0) to it.getString(1))
            }

            for (table in ROLEPLAY_BACKUP_TABLES.asReversed()) {
                db.delete(table, null, null)
            }

            for (table in ROLEPLAY_BACKUP_TABLES) {
                val liveColumns = tableColumns(db, table)
                for (row in tables[table].orEmpty()) {
                    val values = ContentValues()
                    for ((column, value) in row) {
                        // A field the current schema no longer has is dropped;
                        // a column the backup predates takes the schema default
                        // (§3: restore maps fields to the current schema).
                        if (column !in liveColumns) continue
                        when (value) {
                            null -> values.putNull(column)
                            is Long -> values.put(column, value)
                            is Int -> values.put(column, value.toLong())
                            is Double -> values.put(column, value)
                            is Boolean -> values.put(column, if (value) 1L else 0L)
                            else -> values.put(column, value.toString())
                        }
                    }
                    if (values.size() == 0) {
                        throw IllegalStateException("row for $table has no usable columns")
                    }
                    db.insertOrThrow(table, null, values)
                }
            }

            for ((tagId, targetId) in memoryTagLinks) {
                db.execSQL(
                    "INSERT OR IGNORE INTO rp_tag_links (tag_id, target_type, target_id) " +
                        "SELECT ?, 'memory', ? WHERE EXISTS (SELECT 1 FROM rp_tags WHERE tag_id = ?)",
                    arrayOf(tagId, targetId, tagId)
                )
            }

            // §6.4: a link that resolves after restore is kept; a link that no
            // longer resolves is removed. The memory rows themselves are
            // untouched. project links/mirrors are not re-checked — projects
            // are outside this backup and were not replaced.
            db.execSQL("DELETE FROM memory_companions WHERE companion_id NOT IN (SELECT companion_id FROM companions)")
            db.execSQL("DELETE FROM memory_worlds WHERE world_id NOT IN (SELECT world_id FROM worlds)")
            db.execSQL("DELETE FROM memory_campaigns WHERE campaign_id NOT IN (SELECT campaign_id FROM campaigns)")
            db.execSQL("DELETE FROM memory_roleplay_characters WHERE roleplay_character_id NOT IN (SELECT roleplay_character_id FROM roleplay_characters)")

            // Primary-target mirror columns are display-only (join tables are
            // the source of truth): cleared when the target is gone.
            db.execSQL("UPDATE memories SET world_id = NULL WHERE world_id IS NOT NULL AND world_id NOT IN (SELECT world_id FROM worlds)")
            db.execSQL("UPDATE memories SET campaign_id = NULL WHERE campaign_id IS NOT NULL AND campaign_id NOT IN (SELECT campaign_id FROM campaigns)")
            db.execSQL("UPDATE memories SET roleplay_character_id = NULL WHERE roleplay_character_id IS NOT NULL AND roleplay_character_id NOT IN (SELECT roleplay_character_id FROM roleplay_characters)")

            // Transcript context ids are nullable context; content untouched.
            db.execSQL("UPDATE transcripts SET companion_id = NULL WHERE companion_id IS NOT NULL AND companion_id NOT IN (SELECT companion_id FROM companions)")
            db.execSQL("UPDATE transcripts SET world_id = NULL WHERE world_id IS NOT NULL AND world_id NOT IN (SELECT world_id FROM worlds)")
            db.execSQL("UPDATE transcripts SET roleplay_character_id = NULL WHERE roleplay_character_id IS NOT NULL AND roleplay_character_id NOT IN (SELECT roleplay_character_id FROM roleplay_characters)")
            db.execSQL("UPDATE transcripts SET user_persona_id = NULL WHERE user_persona_id IS NOT NULL AND user_persona_id NOT IN (SELECT persona_id FROM user_personas)")

            // Active selections: kept when they resolve, cleared otherwise.
            db.execSQL("UPDATE app_state SET active_companion_id = NULL WHERE active_companion_id IS NOT NULL AND active_companion_id NOT IN (SELECT companion_id FROM companions)")
            db.execSQL("UPDATE app_state SET active_world_id = NULL WHERE active_world_id IS NOT NULL AND active_world_id NOT IN (SELECT world_id FROM worlds)")
            db.execSQL("UPDATE app_state SET active_roleplay_character_id = NULL WHERE active_roleplay_character_id IS NOT NULL AND active_roleplay_character_id NOT IN (SELECT roleplay_character_id FROM roleplay_characters)")
            db.execSQL("UPDATE app_state SET active_user_persona_id = NULL WHERE active_user_persona_id IS NOT NULL AND active_user_persona_id NOT IN (SELECT persona_id FROM user_personas)")

            // The crash pivot: durable exactly when this transaction commits.
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_COMPANION_RESTORE_TOKEN, restoreToken)
            )

            beforeCommit()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): HashSet<String> {
        val out = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", emptyArray<String>()).use {
            val nameIdx = it.getColumnIndexOrThrow("name")
            while (it.moveToNext()) out.add(it.getString(nameIdx))
        }
        return out
    }
}

private fun Cursor.getStringOrNull(column: String): String? {
    val idx = getColumnIndexOrThrow(column)
    return if (isNull(idx)) null else getString(idx)
}
