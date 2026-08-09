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
import org.teslasoft.assistant.preferences.Preferences
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
        private const val DATABASE_VERSION = 28

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

        // Model rules (Stage 4, owner_approved_rules §11 Revision 5): user-
        // written patches for a specific AI model's habits. The model string
        // is the primary identity — no profiles/groups. Each rule carries its
        // own model_strings_json (the models it applies to) and any number of
        // tags (organizing labels, own pool). status='draft' = a Phase 6
        // Archivist suggestion awaiting review. Starts EMPTY: rules are hand-
        // written or arrive as Phase 6 drafts; tags are created inline.
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
                arrayOf(META_DB_MIGRATION, "14")
            )
        }
        if (oldVersion < 15) {
            // v15 (July 2026, Profile Images): a My Persona and a user-side
            // Roleplay Character may each reference a saved Profile Image by
            // its bare hash. The image catalog/files live in the separate
            // unencrypted profile_images.db — this only stores the reference.
            // NULL means no image. Additive.
            db.execSQL("ALTER TABLE user_personas ADD COLUMN image_ref TEXT")
            db.execSQL("ALTER TABLE roleplay_characters ADD COLUMN image_ref TEXT")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "15")
            )
        }

        if (oldVersion < 16) {
            // v16 (July 2026, Profile Images phase 8): a My Persona carries a
            // short description shown as its list-row subtitle. The editor's
            // Short Description field existed before this column and was
            // discarded on save; it now persists here. NULL means none.
            // Additive.
            db.execSQL("ALTER TABLE user_personas ADD COLUMN short_description TEXT")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "16")
            )
        }

        if (oldVersion < 17) {
            // v17 (July 2026, external-memory counterplan Step 1.3): run
            // integrity and rename-safe rejection identity.
            //
            // transcripts: campaign_id/project_id complete the typed scene
            // context stamped at capture (§4(e)); existing rows stay NULL —
            // scene identity is never inferred after the fact. claim_run_id
            // is the analysis claim seal (§4(a)); NULL = unclaimed.
            db.execSQL("ALTER TABLE transcripts ADD COLUMN campaign_id TEXT")
            db.execSQL("ALTER TABLE transcripts ADD COLUMN project_id TEXT")
            db.execSQL("ALTER TABLE transcripts ADD COLUMN claim_run_id TEXT")
            // memories: the source chat id of learned-from-chat records —
            // the rename-safe rejected-draft anchor (§4(c)). Legacy rows
            // stay NULL; deletion falls back to hashing the filing-time
            // chat name in provenance_context (exactly as reliable as the
            // old behavior, no worse).
            db.execSQL("ALTER TABLE memories ADD COLUMN source_chat_id TEXT")
            // archivist_runs: allow status 'running' (the durable active-run
            // record) and add the claim transport. SQLite cannot loosen a
            // CHECK with ALTER, so the table is rebuilt (same pattern as v4/
            // v7; FKs are off during migration via onConfigure).
            db.execSQL(
                "CREATE TABLE archivist_runs_v17 (" +
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
                    "transport TEXT NOT NULL DEFAULT 'api')"
            )
            db.execSQL(
                "INSERT INTO archivist_runs_v17 (run_id, started_at, finished_at, status, " +
                    "chat_ids_json, transcript_ids_json, memory_ids_json, rule_ids_json, " +
                    "found_count, failed_chat_ids_json, error, outcome, failure_reason) " +
                    "SELECT run_id, started_at, finished_at, status, chat_ids_json, " +
                    "transcript_ids_json, memory_ids_json, rule_ids_json, found_count, " +
                    "failed_chat_ids_json, error, outcome, failure_reason FROM archivist_runs"
            )
            db.execSQL("DROP TABLE archivist_runs")
            db.execSQL("ALTER TABLE archivist_runs_v17 RENAME TO archivist_runs")
            // rejected_drafts: rekey from the mutable chat NAME to the chat
            // id (§4(c)). Chat ids are the SHA-256 of the name, so existing
            // rows convert exactly; a rename before this migration already
            // defeated the old key, so nothing is lost that was not already
            // lost. OR REPLACE guards the (content_hash, chat_key) PK.
            val rekey = ArrayList<Pair<String, String>>()
            db.rawQuery(
                "SELECT DISTINCT chat_key FROM rejected_drafts WHERE chat_key != ''",
                emptyArray<String>()
            ).use {
                while (it.moveToNext()) {
                    val old = it.getString(0) ?: continue
                    rekey.add(old to Hash.hash(old))
                }
            }
            for ((old, new) in rekey) {
                db.execSQL(
                    "UPDATE OR REPLACE rejected_drafts SET chat_key = ? WHERE chat_key = ?",
                    arrayOf(new, old)
                )
            }
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "17")
            )
        }
        if (oldVersion < 18) {
            // v18 (Step 1.7): the Lorebook Memories analysis type. A run in
            // that mode files keyword-triggered lore book entry suggestions
            // into lorebook_suggestions (reviewed in the Lorebooks Pending
            // area) instead of memory drafts; rejected_lore_suggestions is the
            // rename-safe rejection anchor so a rerun never refiles a deleted
            // suggestion. Both additive; existing installs get empty tables.
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
            db.execSQL(
                "CREATE TABLE rejected_lore_suggestions (" +
                    "content_hash TEXT NOT NULL, " +
                    "chat_key TEXT NOT NULL, " +
                    "deleted_at TEXT NOT NULL, " +
                    "PRIMARY KEY (content_hash, chat_key))"
            )
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "18")
            )
        }
        if (oldVersion < 19) {
            // v19 (Step 1.7): each run row records which analysis type produced
            // it — 'associative' (saved-memory drafts) or 'lorebook' (keyword-
            // triggered lore book entry suggestions). The Recent Memory Analysis
            // list and Rerun read the run's OWN type instead of the picker's
            // current selection, so a Lorebook run always reads back as one and a
            // rerun never silently converts. Additive; every pre-existing run
            // predates the Lorebook type and is therefore 'associative'.
            db.execSQL("ALTER TABLE archivist_runs ADD COLUMN analysis_type TEXT NOT NULL DEFAULT 'associative'")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "19")
            )
        }
        if (oldVersion < 20) {
            // v20 (Step 1.5): Possible Match resolution history. One new memory
            // may supersede SEVERAL checked old memories (owner ruling), which
            // the single memories.supersedes column cannot represent. Additive;
            // existing installs get an empty table and keep any legacy single
            // supersedes pointer untouched. Both foreign keys cascade on delete
            // so a superseded memory stays permanently deletable.
            db.execSQL(
                "CREATE TABLE memory_supersessions (" +
                    "new_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "old_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "at TEXT NOT NULL, " +
                    "PRIMARY KEY (new_memory_id, old_memory_id))"
            )
            db.execSQL("CREATE INDEX idx_supersessions_old ON memory_supersessions(old_memory_id)")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "20")
            )
        }
        if (oldVersion < 21) {
            // v21 (Phase 1: Storage Compatibility, canonical recovery plan §5,
            // §7, §8.10). Non-destructive: no memory is deleted and every row's
            // values (importance, targets, lifecycle, timestamps, the legacy
            // kind/title baggage) are preserved. The memories table is rebuilt
            // only to change column DEFAULTS and add type_id, so an upgraded
            // store ends up schema-identical to a fresh v21 install.
            val now = nowIso()

            // 1) User-owned Memory Types, seeded with the five starters (§5.1).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memory_types (" +
                    "type_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL)"
            )
            seedStarterMemoryTypes(db, now)

            // 2) Rebuild `memories` so an upgraded database has the SAME schema
            //    as a fresh v21 install: add type_id, set importance DEFAULT 0
            //    (so new memories default to neutral 0 on both fresh and upgraded
            //    stores, never the legacy 3), and give the retired title/kind
            //    columns an inert '' default. Foreign keys are OFF during this
            //    migration (see onConfigure), so dropping `memories` does not
            //    cascade-delete its child rows; the same memory_ids are
            //    re-inserted, leaving embeddings, target joins, supersessions,
            //    and change-log rows valid — the v4/v7 precedent. Every existing
            //    row is copied verbatim: importance, title, kind, scope, targets,
            //    lifecycle, and timestamps are preserved unchanged.
            db.execSQL(
                "CREATE TABLE memories_new (" +
                    "memory_id TEXT PRIMARY KEY, " +
                    "scope TEXT NOT NULL CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character')), " +
                    "kind TEXT NOT NULL DEFAULT '', " +
                    "type_id TEXT REFERENCES memory_types(type_id), " +
                    "title TEXT NOT NULL DEFAULT '', " +
                    "content TEXT NOT NULL, " +
                    "embedding_text TEXT, " +
                    "tags_json TEXT DEFAULT '[]', " +
                    "importance INTEGER NOT NULL DEFAULT 0, " +
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
                    "origin TEXT NOT NULL DEFAULT 'user', " +
                    "suggested_card_type TEXT, " +
                    "suggested_card_id TEXT, " +
                    "suggested_section TEXT, " +
                    "source_chat_id TEXT)"
            )
            // Copy every row, computing type_id from the legacy kind: recognized
            // starter kinds map to their seeded Type id; legacy `lore` and any
            // unrecognized or blank kind become type_id = NULL (No Type). This
            // CASE mirrors MemoryTypeMigration.typeIdForLegacyKind exactly.
            db.execSQL(
                "INSERT INTO memories_new (memory_id, scope, kind, title, content, embedding_text, " +
                    "tags_json, importance, always_load, world_id, roleplay_character_id, campaign_id, " +
                    "project_id, protection_json, mode_hints_json, provenance_source, provenance_confidence, " +
                    "provenance_noted_on, provenance_context, created_at, updated_at, status, supersedes, " +
                    "origin, suggested_card_type, suggested_card_id, suggested_section, source_chat_id, type_id) " +
                    "SELECT memory_id, scope, kind, title, content, embedding_text, " +
                    "tags_json, importance, always_load, world_id, roleplay_character_id, campaign_id, " +
                    "project_id, protection_json, mode_hints_json, provenance_source, provenance_confidence, " +
                    "provenance_noted_on, provenance_context, created_at, updated_at, status, supersedes, " +
                    "origin, suggested_card_type, suggested_card_id, suggested_section, source_chat_id, " +
                    "CASE lower(trim(kind)) " +
                    "WHEN 'fact' THEN 'mtype-fact' " +
                    "WHEN 'preference' THEN 'mtype-preference' " +
                    "WHEN 'event' THEN 'mtype-event' " +
                    "WHEN 'status' THEN 'mtype-status' " +
                    "WHEN 'instruction' THEN 'mtype-instruction' " +
                    "ELSE NULL END " +
                    "FROM memories"
            )
            db.execSQL("DROP TABLE memories")
            db.execSQL("ALTER TABLE memories_new RENAME TO memories")
            // Recreate the indexes that lived on memories, plus the new type index.
            db.execSQL("CREATE INDEX idx_memories_status ON memories(status)")
            db.execSQL("CREATE INDEX idx_memories_type ON memories(type_id)")
            db.execSQL("CREATE INDEX idx_memories_always_load ON memories(always_load) WHERE always_load = 1")
            db.execSQL("CREATE INDEX idx_memories_world ON memories(world_id)")
            db.execSQL("CREATE INDEX idx_memories_rp_character ON memories(roleplay_character_id)")
            db.execSQL("CREATE INDEX idx_memories_campaign ON memories(campaign_id)")
            db.execSQL("CREATE INDEX idx_memories_project ON memories(project_id)")

            // 4) Minimal temporary analysis-run storage (§8.10). Empty until the
            //    archiver rework populates it; present now so companion deletion
            //    can already cascade temporary candidates.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS analysis_run_state (" +
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
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS analysis_candidates (" +
                    "candidate_id TEXT PRIMARY KEY, " +
                    "run_id TEXT NOT NULL REFERENCES analysis_run_state(run_id) ON DELETE CASCADE, " +
                    "stream TEXT NOT NULL DEFAULT 'general', " +
                    "target_type TEXT, " +
                    "target_id TEXT, " +
                    "candidate_hash TEXT, " +
                    "payload_json TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_analysis_candidates_target ON analysis_candidates(target_type, target_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_analysis_candidates_run ON analysis_candidates(run_id)")

            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "21")
            )
        }
        if (oldVersion < 22) {
            // v22 (Phase 2 review finding 3): a durable companion-deletion marker.
            // Additive only — a new table, no existing data touched. Lets a
            // confirmed companion deletion whose cascade failed or was interrupted
            // be reliably retried instead of silently left incomplete.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS pending_companion_deletions (" +
                    "companion_id TEXT PRIMARY KEY, " +
                    "requested_at TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "22")
            )
        }
        if (oldVersion < 23) {
            // v23 (Phase 2 review): separate non-memory bookkeeping that marks a
            // Pending draft as Memory Assistant / computer generated, so deleting
            // it records a content rejection without any source metadata on the
            // memory object. Additive only.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS generated_pending_drafts (" +
                    "memory_id TEXT PRIMARY KEY REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "created_at TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "23")
            )
        }
        if (oldVersion < 24) {
            // v24 (Phase 2): drop the five retired provenance/chat-identity
            // columns from the memories table. SQLite does not support DROP
            // COLUMN on older versions — rebuild the table without them.
            // Data in those columns is discarded (no code reads them anymore).
            db.execSQL(
                "CREATE TABLE memories_v24 (" +
                    "memory_id TEXT PRIMARY KEY, " +
                    "scope TEXT NOT NULL CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character')), " +
                    "kind TEXT NOT NULL DEFAULT '', " +
                    "type_id TEXT REFERENCES memory_types(type_id), " +
                    "title TEXT NOT NULL DEFAULT '', " +
                    "content TEXT NOT NULL, " +
                    "embedding_text TEXT, " +
                    "tags_json TEXT DEFAULT '[]', " +
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
                "INSERT INTO memories_v24 (memory_id, scope, kind, type_id, title, content, embedding_text, " +
                    "tags_json, importance, always_load, world_id, roleplay_character_id, campaign_id, " +
                    "project_id, protection_json, mode_hints_json, created_at, updated_at, status, supersedes, " +
                    "origin, suggested_card_type, suggested_card_id, suggested_section) " +
                    "SELECT memory_id, scope, kind, type_id, title, content, embedding_text, " +
                    "tags_json, importance, always_load, world_id, roleplay_character_id, campaign_id, " +
                    "project_id, protection_json, mode_hints_json, created_at, updated_at, status, supersedes, " +
                    "origin, suggested_card_type, suggested_card_id, suggested_section FROM memories"
            )
            db.execSQL("DROP TABLE memories")
            db.execSQL("ALTER TABLE memories_v24 RENAME TO memories")
            db.execSQL("CREATE INDEX idx_memories_status ON memories(status)")
            db.execSQL("CREATE INDEX idx_memories_type ON memories(type_id)")
            db.execSQL("CREATE INDEX idx_memories_always_load ON memories(always_load) WHERE always_load = 1")
            db.execSQL("CREATE INDEX idx_memories_world ON memories(world_id)")
            db.execSQL("CREATE INDEX idx_memories_rp_character ON memories(roleplay_character_id)")
            db.execSQL("CREATE INDEX idx_memories_campaign ON memories(campaign_id)")
            db.execSQL("CREATE INDEX idx_memories_project ON memories(project_id)")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "24")
            )
        }
        if (oldVersion < 25) {
            // v25: drop the retired legacy `kind` and `title` columns. Their
            // behavior was already fully retired (kind → user-owned type_id in
            // v21; titles never generated, shown, embedded, ranked, or exported
            // since Phase 1) — this removes the last physical trace so nothing
            // in the schema can suggest the old features back into existence.
            // Rebuild-and-copy per the v4/v7/v21/v24 precedent; every kept
            // column is copied verbatim, no memory row is deleted.
            db.execSQL(
                "CREATE TABLE memories_v25 (" +
                    "memory_id TEXT PRIMARY KEY, " +
                    "scope TEXT NOT NULL CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character')), " +
                    "type_id TEXT REFERENCES memory_types(type_id), " +
                    "content TEXT NOT NULL, " +
                    "embedding_text TEXT, " +
                    "tags_json TEXT DEFAULT '[]', " +
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
                "INSERT INTO memories_v25 (memory_id, scope, type_id, content, embedding_text, " +
                    "tags_json, importance, always_load, world_id, roleplay_character_id, campaign_id, " +
                    "project_id, protection_json, mode_hints_json, created_at, updated_at, status, supersedes, " +
                    "origin, suggested_card_type, suggested_card_id, suggested_section) " +
                    "SELECT memory_id, scope, type_id, content, embedding_text, " +
                    "tags_json, importance, always_load, world_id, roleplay_character_id, campaign_id, " +
                    "project_id, protection_json, mode_hints_json, created_at, updated_at, status, supersedes, " +
                    "origin, suggested_card_type, suggested_card_id, suggested_section FROM memories"
            )
            db.execSQL("DROP TABLE memories")
            db.execSQL("ALTER TABLE memories_v25 RENAME TO memories")
            db.execSQL("CREATE INDEX idx_memories_status ON memories(status)")
            db.execSQL("CREATE INDEX idx_memories_type ON memories(type_id)")
            db.execSQL("CREATE INDEX idx_memories_always_load ON memories(always_load) WHERE always_load = 1")
            db.execSQL("CREATE INDEX idx_memories_world ON memories(world_id)")
            db.execSQL("CREATE INDEX idx_memories_rp_character ON memories(roleplay_character_id)")
            db.execSQL("CREATE INDEX idx_memories_campaign ON memories(campaign_id)")
            db.execSQL("CREATE INDEX idx_memories_project ON memories(project_id)")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "25")
            )
        }
        if (oldVersion < 26) {
            // v26: drop the retired chat_key column from rejected_drafts.
            // Source-chat identity was retired for rejection dedup back in
            // Phase 1 (§3.2) — chat_key had stood empty (or, on an
            // in-between device, holding only pre-Phase-1 legacy values)
            // ever since; this removes the last physical trace, matching the
            // memories.kind/title cleanup in v25. Rejections are collapsed
            // to one row per content_hash, keeping the most recent
            // deleted_at for any hash that had rows under several old keys.
            db.execSQL(
                "CREATE TABLE rejected_drafts_v26 (" +
                    "content_hash TEXT PRIMARY KEY, " +
                    "deleted_at TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO rejected_drafts_v26 (content_hash, deleted_at) " +
                    "SELECT content_hash, MAX(deleted_at) FROM rejected_drafts GROUP BY content_hash"
            )
            db.execSQL("DROP TABLE rejected_drafts")
            db.execSQL("ALTER TABLE rejected_drafts_v26 RENAME TO rejected_drafts")
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "26")
            )
        }
        if (oldVersion < 27) {
            // Stage B: reconcile stale API claims BEFORE deriving the initial
            // per-chat bookmarks. Otherwise a dead run could make a pending
            // row look temporarily unavailable while the one-way cutover is
            // being fixed in place.
            val now = nowIso()
            db.execSQL(
                "UPDATE transcripts SET claim_run_id = NULL WHERE claim_run_id IN (" +
                    "SELECT run_id FROM archivist_runs WHERE status = 'running' AND transport = 'api')"
            )
            db.execSQL(
                "UPDATE archivist_runs SET status = 'failed', outcome = 'interrupted', " +
                    "failure_reason = 'interrupted', finished_at = ?, " +
                    "error = 'process ended before bookmark migration' " +
                    "WHERE status = 'running' AND transport = 'api'",
                arrayOf(now)
            )
            db.execSQL(
                "UPDATE transcripts SET claim_run_id = NULL WHERE claim_run_id LIKE 'run-%' " +
                    "AND claim_run_id NOT IN (SELECT run_id FROM archivist_runs)"
            )
            db.execSQL(
                "CREATE TABLE analysis_chat_bookmarks (" +
                    "chat_id TEXT PRIMARY KEY, " +
                    "last_started_at TEXT, " +
                    "last_transcript_id TEXT, " +
                    "skipped_transcript_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "archive_paused INTEGER NOT NULL DEFAULT 0 CHECK (archive_paused IN (0,1)), " +
                    "updated_at TEXT NOT NULL)"
            )
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
            db.execSQL(
                "CREATE INDEX idx_transcripts_chat_order ON transcripts(chat_id, started_at, transcript_id)"
            )
            db.execSQL("CREATE INDEX idx_analysis_chat_ranges_run ON analysis_chat_ranges(run_id)")

            initializeBookmarkCutoverTx(db, replaceExisting = true)
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "27")
            )
        }
        if (oldVersion < 28) {
            // Stage D: keep AI-supplied existing-memory relationships as
            // separate Pending-review hints, never as permanent memory data.
            db.execSQL(
                "CREATE TABLE memory_possible_match_hints (" +
                    "draft_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "existing_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "created_at TEXT NOT NULL, " +
                    "PRIMARY KEY (draft_memory_id, existing_memory_id))"
            )
            db.execSQL(
                "CREATE INDEX idx_possible_match_hints_existing " +
                    "ON memory_possible_match_hints(existing_memory_id)"
            )
            db.execSQL(
                "ALTER TABLE analysis_candidates ADD COLUMN chunk_ordinal INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE analysis_candidates ADD COLUMN candidate_ordinal INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "INSERT INTO meta (key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(META_DB_MIGRATION, "28")
            )
        }
    }

    /**
     * Derive initial bookmarks from a stable snapshot of the legacy queue and
     * install the one-way cutover marker last. The caller owns the transaction;
     * SQLiteOpenHelper wraps onUpgrade in one, and test/import callers do the
     * same explicitly. If any row fails, neither bookmarks nor the marker can
     * commit, so runtime authority is never ambiguous.
     */
    private fun initializeBookmarkCutoverTx(
        db: SQLiteDatabase,
        replaceExisting: Boolean,
        interruptBeforeMarkerForTest: Boolean = false
    ) {
        db.delete("meta", "key = ?", arrayOf(META_ASSOCIATIVE_BOOKMARK_CUTOVER))
        if (replaceExisting) db.delete("analysis_chat_bookmarks", null, null)

        val rowsByChat = linkedMapOf<String, MutableList<AnalysisBookmark.LegacyRow>>()
        db.query(
            "transcripts",
            arrayOf("chat_id", "transcript_id", "started_at", "review_status"),
            "chat_id IS NOT NULL", null, null, null,
            "chat_id ASC, COALESCE(started_at, '') ASC, transcript_id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val chatId = c.getString(0) ?: continue
                rowsByChat.getOrPut(chatId) { ArrayList() }.add(
                    AnalysisBookmark.LegacyRow(
                        transcriptId = c.getString(1),
                        startedAt = c.getStringOrNull("started_at"),
                        reviewStatus = c.getString(3)
                    )
                )
            }
        }

        val now = nowIso()
        for ((chatId, rows) in rowsByChat) {
            if (!replaceExisting && rowExists(db, "analysis_chat_bookmarks", "chat_id", chatId)) continue
            val archivePaused = try {
                Preferences.getPreferences(appContext, chatId).isChatExcludedFromMemory()
            } catch (_: Exception) {
                false
            }
            val plan = AnalysisBookmark.planMigration(rows, archivePaused)
            db.insertOrThrow("analysis_chat_bookmarks", null, ContentValues().apply {
                put("chat_id", chatId)
                put("last_started_at", plan.boundary?.startedAt)
                put("last_transcript_id", plan.boundary?.transcriptId)
                put("skipped_transcript_ids_json", stringsToJson(plan.skippedTranscriptIds))
                put("archive_paused", if (archivePaused) 1 else 0)
                put("updated_at", now)
            })
        }

        if (interruptBeforeMarkerForTest) {
            throw IllegalStateException("simulated bookmark migration interruption")
        }

        db.execSQL(
            "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            arrayOf(META_ASSOCIATIVE_BOOKMARK_CUTOVER, "complete")
        )
    }

    private fun requireBookmarkCutover(db: SQLiteDatabase) {
        val complete = db.rawQuery(
            "SELECT value FROM meta WHERE key = ?", arrayOf(META_ASSOCIATIVE_BOOKMARK_CUTOVER)
        ).use { it.moveToFirst() && it.getString(0) == "complete" }
        check(complete) { "Associative bookmark migration is incomplete" }
    }

    /** Instrumentation seam for the real encrypted cutover transaction. */
    @androidx.annotation.VisibleForTesting
    fun rebuildBookmarkCutoverForTest(interruptBeforeMarker: Boolean = false) {
        reconcileInterruptedAnalysisRuns()
        val db = writableDatabase
        // Establish the same pre-v27 state (no bookmark rows and no marker) in
        // its own committed transaction. The migration transaction below must
        // either install both or leave both absent.
        db.beginTransaction()
        try {
            db.delete("analysis_chat_bookmarks", null, null)
            db.delete("meta", "key = ?", arrayOf(META_ASSOCIATIVE_BOOKMARK_CUTOVER))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.beginTransaction()
        try {
            initializeBookmarkCutoverTx(
                db, replaceExisting = true,
                interruptBeforeMarkerForTest = interruptBeforeMarker
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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

    fun deleteMeta(key: String) {
        writableDatabase.delete("meta", "key = ?", arrayOf(key))
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
        out["pending_transcripts"] = bookmarkEligibleTranscripts().size
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
    fun pendingReviewCount(): Int =
        bookmarkEligibleTranscripts().mapNotNull { it.chatId }.toSet().size

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
     * One-way app -> store sync (integration plan D6): refresh the app link and
     * personality mirror, and keep name_history honest when the visible name
     * changed. Since the July 2026 stable-id fix a persona rename no longer
     * changes its id, so [appCharacterId] normally already matches the stored
     * link; it is still written so a legacy record created under an old id can
     * be reconciled. name_history is what makes a rename visible here.
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

            // Memory Types before memories (§5): a memory's type_id must resolve
            // to a real Type. A backup's own Types are upserted by stable id (a
            // renamed Type keeps its id, so associated memories stay correct);
            // pre-Phase-1 backups carry none and the seeded starter Types remain.
            for (t in data.memoryTypes) {
                db.execSQL(
                    "INSERT INTO memory_types (type_id, name, created_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT(type_id) DO UPDATE SET name = excluded.name",
                    arrayOf(t.typeId, t.name, t.createdAt.ifBlank { nowIso() })
                )
            }
            // Authoritative restore (item 7): when a Type-aware backup is restored
            // into a fresh store (overwriteSingletons — the first-seed path), the
            // store's Type set must MATCH the backup exactly, or a starter Type
            // the user deleted would be silently resurrected by the fresh-install
            // seeding. Remove any Type not present in the backup; a memory that
            // referenced a removed Type degrades to No Type (§5.4). A merge import
            // (overwriteSingletons = false) keeps the current user's Type set and
            // only upserts, so it never deletes a Type the user still has. A
            // pre-Phase-1 backup carries no Types and never triggers this.
            if (overwriteSingletons && data.memoryTypes.isNotEmpty()) {
                val keepIds = data.memoryTypes.map { it.typeId }.toHashSet()
                val toRemove = ArrayList<String>()
                db.query("memory_types", arrayOf("type_id"), null, null, null, null, null).use {
                    while (it.moveToNext()) {
                        val id = it.getString(0)
                        if (id !in keepIds) toRemove.add(id)
                    }
                }
                for (id in toRemove) {
                    // Detach memories first (no ON DELETE rule on type_id) so the
                    // Type removal can never fail or orphan a reference.
                    db.execSQL("UPDATE memories SET type_id = NULL WHERE type_id = ?", arrayOf(id))
                    db.delete("memory_types", "type_id = ?", arrayOf(id))
                }
            }
            // The set of Type ids that actually exist after the import: a memory
            // whose resolved Type is not among these degrades to No Type rather
            // than failing the import (§5.2 — an invalid Type never drops a
            // memory).
            val knownTypeIds = HashSet<String>()
            db.query("memory_types", arrayOf("type_id"), null, null, null, null, null).use {
                while (it.moveToNext()) knownTypeIds.add(it.getString(0))
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
                // Resolve the memory's Type (§5): the codec already translated
                // a legacy backup's kind into a type_id at parse time; an id
                // that no longer exists degrades to No Type.
                val resolvedTypeId = m.typeId?.takeIf { it in knownTypeIds }
                db.insert("memories", null, ContentValues().apply {
                    put("memory_id", m.memoryId)
                    put("scope", m.scope)
                    put("type_id", resolvedTypeId)
                    put("content", m.content)
                    put("embedding_text", m.embeddingText)
                    put("tags_json", m.tagsJson)
                    put("importance", m.importance)
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

            // The permanent bookmark travels with transcripts. A pre-Stage-B
            // backup has no bookmark array, so missing chats are derived once
            // from their imported legacy states using the same contiguous-
            // prefix rule as the v27 migration. Existing device bookmarks are
            // never overwritten by a merge import.
            for (bookmark in data.analysisBookmarks) {
                if (!rowExists(db, "transcripts", "chat_id", bookmark.chatId)) continue
                val boundaryIsValid = bookmark.lastTranscriptId == null || db.rawQuery(
                    "SELECT 1 FROM transcripts WHERE chat_id = ? AND transcript_id = ? LIMIT 1",
                    arrayOf(bookmark.chatId, bookmark.lastTranscriptId)
                ).use { it.moveToFirst() }
                if (!boundaryIsValid) continue
                val validSkips = bookmark.skippedTranscriptIds.filter { id ->
                    db.rawQuery(
                        "SELECT 1 FROM transcripts WHERE chat_id = ? AND transcript_id = ? LIMIT 1",
                        arrayOf(bookmark.chatId, id)
                    ).use { it.moveToFirst() }
                }
                val values = ContentValues().apply {
                    put("chat_id", bookmark.chatId)
                    put("last_started_at", bookmark.lastStartedAt)
                    put("last_transcript_id", bookmark.lastTranscriptId)
                    put("skipped_transcript_ids_json", stringsToJson(validSkips))
                    put("archive_paused", if (bookmark.archivePaused) 1 else 0)
                    put("updated_at", bookmark.updatedAt.ifBlank { nowIso() })
                }
                if (overwriteSingletons) {
                    db.insertWithOnConflict(
                        "analysis_chat_bookmarks", null, values, SQLiteDatabase.CONFLICT_REPLACE
                    )
                } else {
                    db.insertWithOnConflict(
                        "analysis_chat_bookmarks", null, values, SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            }
            initializeBookmarkCutoverTx(db, replaceExisting = false)

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

            // Model rules (Stage 4, §11 Revision 5): rules, their tags, and the
            // links between them. Each is id-keyed and de-duped on import.
            for (r in data.modelRules) {
                if (rowExists(db, "model_rules", "rule_id", r.ruleId)) {
                    report.addSkipped("model rules"); continue
                }
                db.insert("model_rules", null, modelRuleValues(r))
                report.addAdded("model rules")
            }
            for (t in data.modelRuleTags) {
                if (rowExists(db, "model_rule_tags", "tag_id", t.tagId)) {
                    report.addSkipped("model rule tags"); continue
                }
                db.insert("model_rule_tags", null, ContentValues().apply {
                    put("tag_id", t.tagId)
                    put("name", t.name)
                    put("created_at", t.createdAt.ifEmpty { nowIso() })
                })
                report.addAdded("model rule tags")
            }
            for (link in data.modelRuleTagLinks) {
                // Only link rows whose rule and tag both exist, so a partial
                // file can't strand a dangling link.
                if (!rowExists(db, "model_rules", "rule_id", link.ruleId)) continue
                if (!rowExists(db, "model_rule_tags", "tag_id", link.tagId)) continue
                db.execSQL(
                    "INSERT OR IGNORE INTO model_rule_tag_links (rule_id, tag_id) VALUES (?, ?)",
                    arrayOf(link.ruleId, link.tagId)
                )
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
                        createdAt = it.getStringOrNull("created_at"),
                        imageRef = it.getStringOrNull("image_ref"),
                        shortDescription = it.getStringOrNull("short_description")
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
                        goalsDrives = it.getStringOrNull("goals_drives"),
                        imageRef = it.getStringOrNull("image_ref")
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
                        campaignId = it.getStringOrNull("campaign_id"),
                        projectId = it.getStringOrNull("project_id"),
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
        val modelRules = ArrayList<ModelRuleRecord>()
        db.query("model_rules", null, null, null, null, null, "created_at ASC, rule_id ASC").use {
            while (it.moveToNext()) modelRules.add(readModelRule(it))
        }
        val modelRuleTags = getModelRuleTags()
        val modelRuleTagLinks = ArrayList<ModelRuleTagLink>()
        db.query("model_rule_tag_links", null, null, null, null, null, "rule_id ASC, tag_id ASC").use {
            while (it.moveToNext()) modelRuleTagLinks.add(
                ModelRuleTagLink(
                    ruleId = it.getString(it.getColumnIndexOrThrow("rule_id")),
                    tagId = it.getString(it.getColumnIndexOrThrow("tag_id"))
                )
            )
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
            modelRules = modelRules,
            modelRuleTags = modelRuleTags,
            modelRuleTagLinks = modelRuleTagLinks,
            memoryTypes = getMemoryTypes(),
            analysisBookmarks = getAnalysisBookmarks()
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
     * served by the same model, companion, and scene, unclaimed, and under
     * the size cap — a change of model, companion, or scene context, an
     * oversized row, or an analysis claim on the row starts a new row so
     * each transcript's model_tag/companion_id/scene columns stay truthful
     * for the Archivist. A CLAIMED row is sealed (counterplan §4(a)): a turn
     * arriving mid-analysis can never slip into a row a run has selected and
     * be marked processed without ever being read — it starts a fresh
     * pending row instead. Scene context (§4(e)) is stamped in the typed
     * columns at capture time, never inferred later.
     * [markExcluded] is reserved for a permanent policy exclusion such as a
     * companion whose memory participation is `none`. [archivePaused] is the
     * reversible per-chat Archive toggle: content is captured as pending while
     * the bookmark keeps it ineligible, ready to be reviewed after resume.
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
        markExcluded: Boolean,
        archivePaused: Boolean = false,
        assistantComplete: Boolean = true,
        worldId: String? = null,
        campaignId: String? = null,
        roleplayCharacterId: String? = null,
        userPersonaId: String? = null,
        projectId: String? = null
    ): String {
        val now = nowIso()
        val db = writableDatabase
        db.beginTransaction()
        try {
            requireBookmarkCutover(db)
            ensureAnalysisBookmarkTx(db, chatId)
            db.update("analysis_chat_bookmarks", ContentValues().apply {
                put("archive_paused", if (archivePaused) 1 else 0)
                put("updated_at", now)
            }, "chat_id = ?", arrayOf(chatId))
            val bookmark = analysisBookmarkTx(db, chatId)
            var rowId: String? = null
            var content = "[]"
            db.query(
                "transcripts",
                arrayOf(
                    "transcript_id", "content", "model_tag", "companion_id", "review_status",
                    "claim_run_id", "world_id", "campaign_id", "roleplay_character_id",
                    "user_persona_id", "project_id", "started_at"
                ),
                "chat_id = ? AND processed_at IS NULL", arrayOf(chatId),
                null, null, "COALESCE(started_at, '') DESC, transcript_id DESC", "1"
            ).use {
                if (it.moveToFirst()) {
                    val sameModel = it.getStringOrNull("model_tag") == modelTag
                    val sameCompanion = it.getStringOrNull("companion_id") == companionId
                    // Sealed: the newest unprocessed row is claimed by a run
                    // (or a future review package) — never append into it.
                    val unclaimed = it.getStringOrNull("claim_run_id") == null
                    val afterBookmark = AnalysisBookmark.isAfter(
                        AnalysisBookmark.Boundary(
                            it.getStringOrNull("started_at"), it.getString(0)
                        ),
                        bookmarkBoundary(bookmark)
                    )
                    val sameExclusion =
                        (it.getString(it.getColumnIndexOrThrow("review_status")) == "excluded") == markExcluded
                    // Scene identity is part of the row's truth: a scene
                    // change closes the row like a model change does.
                    val sameScene = it.getStringOrNull("world_id") == worldId &&
                        it.getStringOrNull("campaign_id") == campaignId &&
                        it.getStringOrNull("roleplay_character_id") == roleplayCharacterId &&
                        it.getStringOrNull("user_persona_id") == userPersonaId &&
                        it.getStringOrNull("project_id") == projectId
                    val existing = it.getString(it.getColumnIndexOrThrow("content"))
                    if (sameModel && sameCompanion && unclaimed && afterBookmark && sameExclusion && sameScene &&
                        existing.length < MAX_TRANSCRIPT_CHARS
                    ) {
                        rowId = it.getString(0)
                        content = existing
                    }
                }
            }

            val turns = org.json.JSONArray(content)
            turns.put(org.json.JSONObject().put("role", "user").put("content", userMessage).put("at", now))
            // "complete": false marks an assistant reply that did not finish
            // streaming, so the Archivist never mines a truncated fragment as a
            // reliable fact. Absent (the common case) means complete — backward
            // compatible with every already-stored transcript.
            val assistantTurn = org.json.JSONObject()
                .put("role", "assistant").put("content", assistantMessage).put("at", now)
            if (!assistantComplete) assistantTurn.put("complete", false)
            turns.put(assistantTurn)

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
                    put("world_id", worldId)
                    put("campaign_id", campaignId)
                    put("roleplay_character_id", roleplayCharacterId)
                    put("user_persona_id", userPersonaId)
                    put("project_id", projectId)
                    put("source", "live")
                    put("started_at", now)
                    put("ended_at", now)
                    put("content", turns.toString())
                    put("model_tag", modelTag)
                    put("quick_settings_json", quickSettingsJson)
                    put("review_status", if (markExcluded) "excluded" else "pending")
                })
                rowId = newRowId
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
            if (markExcluded) excludeTranscriptIdsTx(db, chatId, listOfNotNull(rowId))
            db.setTransactionSuccessful()
            return outcome
        } finally {
            db.endTransaction()
        }
    }

    /** User Archive toggle. This is a reversible eligibility pause, never a
     * terminal exclusion: the completed boundary and waiting transcript rows
     * are left untouched so resume exposes the whole post-bookmark range. */
    fun setChatTranscriptsExcluded(chatId: String, excluded: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            requireBookmarkCutover(db)
            ensureAnalysisBookmarkTx(db, chatId)
            db.update("analysis_chat_bookmarks", ContentValues().apply {
                put("archive_paused", if (excluded) 1 else 0)
                put("updated_at", nowIso())
            }, "chat_id = ?", arrayOf(chatId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Archivist (Phase 6): eligibility readers, draft filing, run history     */
    /*                                                                         */
    /* The Archivist only ever files DRAFTS (owner rules: it proposes, the     */
    /* user decides). Memory drafts are memories.status='draft' rows — their   */
    /* ONE home (§14); the dormant proposals table is never used for them.     */
    /* Stage B eligibility is the durable per-chat bookmark plus temporary     */
    /* claims. Deleted chats are filtered by the caller against app chat state,*/
    /* which this store cannot see.                                             */
    /* ---------------------------------------------------------------------- */

    private fun readTranscript(it: Cursor): TranscriptRecord = TranscriptRecord(
        transcriptId = it.getString(it.getColumnIndexOrThrow("transcript_id")),
        chatId = it.getStringOrNull("chat_id"),
        companionId = it.getStringOrNull("companion_id"),
        worldId = it.getStringOrNull("world_id"),
        roleplayCharacterId = it.getStringOrNull("roleplay_character_id"),
        userPersonaId = it.getStringOrNull("user_persona_id"),
        campaignId = it.getStringOrNull("campaign_id"),
        projectId = it.getStringOrNull("project_id"),
        source = it.getString(it.getColumnIndexOrThrow("source")),
        startedAt = it.getStringOrNull("started_at"),
        endedAt = it.getStringOrNull("ended_at"),
        content = it.getString(it.getColumnIndexOrThrow("content")),
        modelTag = it.getStringOrNull("model_tag"),
        quickSettingsJson = it.getStringOrNull("quick_settings_json"),
        reviewStatus = it.getString(it.getColumnIndexOrThrow("review_status")),
        processedAt = it.getStringOrNull("processed_at"),
        claimRunId = it.getStringOrNull("claim_run_id")
    )

    private fun readAnalysisBookmark(c: Cursor) = AnalysisChatBookmark(
        chatId = c.getString(c.getColumnIndexOrThrow("chat_id")),
        lastStartedAt = c.getStringOrNull("last_started_at"),
        lastTranscriptId = c.getStringOrNull("last_transcript_id"),
        skippedTranscriptIds = jsonToStrings(c.getStringOrNull("skipped_transcript_ids_json")),
        archivePaused = c.getInt(c.getColumnIndexOrThrow("archive_paused")) != 0,
        updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun ensureAnalysisBookmarkTx(db: SQLiteDatabase, chatId: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO analysis_chat_bookmarks " +
                "(chat_id, last_started_at, last_transcript_id, skipped_transcript_ids_json, archive_paused, updated_at) " +
                "VALUES (?, NULL, NULL, '[]', 0, ?)",
            arrayOf(chatId, nowIso())
        )
    }

    private fun analysisBookmarkTx(db: SQLiteDatabase, chatId: String): AnalysisChatBookmark {
        ensureAnalysisBookmarkTx(db, chatId)
        db.query(
            "analysis_chat_bookmarks", null, "chat_id = ?", arrayOf(chatId),
            null, null, null
        ).use {
            check(it.moveToFirst()) { "Missing analysis bookmark for $chatId" }
            return readAnalysisBookmark(it)
        }
    }

    /** Add intentionally excluded rows to the bookmark-owned skip snapshot and
     *  immediately advance through any that are now contiguous. */
    private fun excludeTranscriptIdsTx(db: SQLiteDatabase, chatId: String, ids: Collection<String>) {
        val bookmark = analysisBookmarkTx(db, chatId)
        val skipped = (bookmark.skippedTranscriptIds + ids).toSet()
        val rows = transcriptsForChatTx(db, chatId)
        var boundary = bookmarkBoundary(bookmark)
        val remaining = skipped.toMutableSet()
        for (row in rows) {
            val rowBoundary = transcriptBoundary(row)
            if (!AnalysisBookmark.isAfter(rowBoundary, boundary)) continue
            if (row.claimRunId != null) break
            if (row.transcriptId !in remaining) break
            remaining.remove(row.transcriptId)
            boundary = rowBoundary
        }
        db.update("analysis_chat_bookmarks", ContentValues().apply {
            put("last_started_at", boundary?.startedAt)
            put("last_transcript_id", boundary?.transcriptId)
            put("skipped_transcript_ids_json", stringsToJson(remaining.sorted()))
            put("updated_at", nowIso())
        }, "chat_id = ?", arrayOf(chatId))
    }

    fun getAnalysisBookmark(chatId: String): AnalysisChatBookmark? {
        readableDatabase.query(
            "analysis_chat_bookmarks", null, "chat_id = ?", arrayOf(chatId),
            null, null, null
        ).use { return if (it.moveToFirst()) readAnalysisBookmark(it) else null }
    }

    fun getAnalysisBookmarks(): List<AnalysisChatBookmark> {
        val out = ArrayList<AnalysisChatBookmark>()
        readableDatabase.query(
            "analysis_chat_bookmarks", null, null, null, null, null, "chat_id ASC"
        ).use { while (it.moveToNext()) out.add(readAnalysisBookmark(it)) }
        return out
    }

    private fun bookmarkBoundary(bookmark: AnalysisChatBookmark): AnalysisBookmark.Boundary? =
        bookmark.lastTranscriptId?.let { AnalysisBookmark.Boundary(bookmark.lastStartedAt, it) }

    private fun transcriptBoundary(row: TranscriptRecord) =
        AnalysisBookmark.Boundary(row.startedAt, row.transcriptId)

    private fun transcriptsForChatTx(db: SQLiteDatabase, chatId: String): List<TranscriptRecord> {
        val out = ArrayList<TranscriptRecord>()
        db.query(
            "transcripts", null, "chat_id = ?", arrayOf(chatId), null, null,
            "COALESCE(started_at, '') ASC, transcript_id ASC"
        ).use { while (it.moveToNext()) out.add(readTranscript(it)) }
        return out
    }

    /**
     * The one post-cutover eligibility reader. It deliberately does not inspect
     * review_status or processed_at. Bookmark position, snapshotted skips, and
     * temporary claims/ranges are the complete authority.
     */
    private fun eligibleTranscriptsForChatTx(db: SQLiteDatabase, chatId: String): List<TranscriptRecord> {
        val bookmark = analysisBookmarkTx(db, chatId)
        if (bookmark.archivePaused) return emptyList()
        val ordered = transcriptsForChatTx(db, chatId)
        val unskipped = AnalysisBookmark.eligibleRange(
            rows = ordered,
            boundary = bookmarkBoundary(bookmark),
            skippedTranscriptIds = bookmark.skippedTranscriptIds.toSet(),
            idOf = { it.transcriptId },
            boundaryOf = { transcriptBoundary(it) }
        )
        // A claim is a temporary concurrency seal. Stop at it; never jump over
        // an in-flight range and claim later history out of order.
        return unskipped.takeWhile { it.claimRunId == null }
    }

    /** Everything the next API run may freeze, chronological and bookmark-led. */
    fun bookmarkEligibleTranscripts(): List<TranscriptRecord> {
        val db = writableDatabase
        val out = ArrayList<TranscriptRecord>()
        db.beginTransaction()
        try {
            requireBookmarkCutover(db)
            val chatIds = ArrayList<String>()
            db.rawQuery(
                "SELECT DISTINCT chat_id FROM transcripts WHERE chat_id IS NOT NULL ORDER BY chat_id",
                emptyArray<String>()
            ).use { while (it.moveToNext()) chatIds.add(it.getString(0)) }
            for (chatId in chatIds) out.addAll(eligibleTranscriptsForChatTx(db, chatId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        out.sortWith(compareBy({ it.startedAt ?: "" }, { it.transcriptId }))
        return out
    }

    /** Compatibility name for existing callers; no legacy state is read. */
    fun pendingUnprocessedTranscripts(): List<TranscriptRecord> = bookmarkEligibleTranscripts()

    /** Rerun support: re-fetch exactly the rows a past run fed, regardless of
     *  their current review state. Rows deleted since simply drop out. */
    fun transcriptsByIds(ids: List<String>): List<TranscriptRecord> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<TranscriptRecord>()
        val db = readableDatabase
        for (id in ids) {
            db.query(
                "transcripts", null, "transcript_id = ?", arrayOf(id),
                null, null, null
            ).use { if (it.moveToNext()) out.add(readTranscript(it)) }
        }
        out.sortWith(compareBy({ it.startedAt ?: "" }, { it.transcriptId }))
        return out
    }

    /**
     * Open a durable multi-chat run and freeze each selected chat independently.
     * The supplied ids are the eligibility snapshot the caller displayed; rows
     * appended after that snapshot are deliberately not claimed and belong to
     * the next run. All claims and frozen-range records are sealed beside the
     * running history row in one transaction.
     */
    fun beginAnalysisRun(
        run: ArchivistRunRecord,
        transcriptIdsByChat: Map<String, List<String>>
    ): Map<String, FrozenChatRange> {
        val db = writableDatabase
        val frozen = linkedMapOf<String, FrozenChatRange>()
        db.beginTransaction()
        try {
            requireBookmarkCutover(db)
            db.insertWithOnConflict(
                "archivist_runs", null, archivistRunValues(run), SQLiteDatabase.CONFLICT_REPLACE
            )
            for ((chatId, requestedIds) in transcriptIdsByChat) {
                if (requestedIds.isEmpty()) continue
                val requested = requestedIds.toHashSet()
                val eligible = eligibleTranscriptsForChatTx(db, chatId)
                val selected = ArrayList<TranscriptRecord>()
                // Freeze only the requested contiguous prefix. A late row is
                // the first non-requested row and stays unclaimed for next time.
                for (row in eligible) {
                    if (row.transcriptId !in requested) break
                    val claimed = db.update(
                        "transcripts",
                        ContentValues().apply { put("claim_run_id", run.runId) },
                        "transcript_id = ? AND chat_id = ? AND claim_run_id IS NULL",
                        arrayOf(row.transcriptId, chatId)
                    )
                    if (claimed != 1) break
                    selected.add(row.copy(claimRunId = run.runId))
                }
                if (selected.isEmpty()) continue

                val end = selected.last()
                val range = FrozenChatRange(
                    rangeId = newId("range-"),
                    runId = run.runId,
                    chatId = chatId,
                    transcripts = selected,
                    frozenEndStartedAt = end.startedAt,
                    frozenEndTranscriptId = end.transcriptId
                )
                db.insertOrThrow("analysis_chat_ranges", null, ContentValues().apply {
                    put("range_id", range.rangeId)
                    put("run_id", range.runId)
                    put("chat_id", range.chatId)
                    put("frozen_end_started_at", range.frozenEndStartedAt)
                    put("frozen_end_transcript_id", range.frozenEndTranscriptId)
                    put("status", "running")
                    put("created_at", nowIso())
                })
                insertCandidateCollectionTx(
                    db = db,
                    collectionId = range.rangeId,
                    chatId = range.chatId,
                    frozenEndMarker = listOfNotNull(
                        range.frozenEndStartedAt, range.frozenEndTranscriptId
                    ).joinToString("\u0000")
                )
                frozen[chatId] = range
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return frozen
    }

    private fun insertCandidateCollectionTx(
        db: SQLiteDatabase,
        collectionId: String,
        chatId: String,
        frozenEndMarker: String?
    ) {
        db.insertWithOnConflict(
            "analysis_run_state", null, ContentValues().apply {
                put("run_id", collectionId)
                put("chat_id", chatId)
                put("frozen_end_marker", frozenEndMarker)
                put("processing_method", "api")
                put("chunk_ordinal", 0)
                put("chunk_success_json", "[]")
                put("retry_count", 0)
                put("filed", 0)
                put("created_at", nowIso())
                put("updated_at", nowIso())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Reruns do not own a bookmark range, but still use the same encrypted
     * temporary candidate boundary until their complete chat output commits. */
    fun beginCandidateCollection(collectionId: String, chatId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertCandidateCollectionTx(db, collectionId, chatId, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Persist one successfully parsed chunk's validated memory/rule outputs.
     * Exact identities already staged for this chat range are not duplicated. */
    fun stageAnalysisCandidates(
        collectionId: String,
        chunkOrdinal: Int,
        candidates: List<StagedAnalysisCandidate>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(rowExists(db, "analysis_run_state", "run_id", collectionId)) {
                "Candidate collection is not active"
            }
            for ((candidateOrdinal, candidate) in candidates.withIndex()) {
                val exists = db.rawQuery(
                    "SELECT 1 FROM analysis_candidates " +
                        "WHERE run_id = ? AND stream = ? AND candidate_hash = ? LIMIT 1",
                    arrayOf(collectionId, candidate.stream, candidate.candidateHash)
                ).use { it.moveToFirst() }
                if (exists) continue
                db.insertOrThrow("analysis_candidates", null, ContentValues().apply {
                    put("candidate_id", newId("cand-"))
                    put("run_id", collectionId)
                    put("stream", candidate.stream)
                    put("target_type", candidate.targetType)
                    put("target_id", candidate.targetId)
                    put("candidate_hash", candidate.candidateHash)
                    put("chunk_ordinal", chunkOrdinal)
                    put("candidate_ordinal", candidateOrdinal)
                    put("payload_json", candidate.payloadJson)
                    put("created_at", nowIso())
                })
            }
            val completed = db.rawQuery(
                "SELECT chunk_success_json FROM analysis_run_state WHERE run_id = ?",
                arrayOf(collectionId)
            ).use { c -> if (c.moveToFirst()) jsonToStrings(c.getString(0)) else emptyList() }
                .plus(chunkOrdinal.toString()).distinct()
            db.update("analysis_run_state", ContentValues().apply {
                put("chunk_ordinal", chunkOrdinal)
                put("chunk_success_json", stringsToJson(completed))
                put("updated_at", nowIso())
            }, "run_id = ?", arrayOf(collectionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun discardCandidateCollection(collectionId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("analysis_candidates", "run_id = ?", arrayOf(collectionId))
            db.delete("analysis_run_state", "run_id = ?", arrayOf(collectionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Release every claim a run still holds (terminal cleanup: completion,
     *  failure, or interruption). Processed rows already dropped theirs. */
    fun releaseAnalysisClaims(runId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val affectedChats = ArrayList<String>()
            val candidateCollections = ArrayList<String>()
            db.rawQuery(
                "SELECT DISTINCT chat_id FROM transcripts WHERE claim_run_id = ? AND chat_id IS NOT NULL",
                arrayOf(runId)
            ).use { while (it.moveToNext()) affectedChats.add(it.getString(0)) }
            db.rawQuery(
                "SELECT range_id FROM analysis_chat_ranges WHERE run_id = ?",
                arrayOf(runId)
            ).use { while (it.moveToNext()) candidateCollections.add(it.getString(0)) }
            db.execSQL(
                "UPDATE transcripts SET claim_run_id = NULL WHERE claim_run_id = ?",
                arrayOf(runId)
            )
            db.delete("analysis_chat_ranges", "run_id = ? AND status = 'running'", arrayOf(runId))
            for (collectionId in candidateCollections) {
                db.delete("analysis_candidates", "run_id = ?", arrayOf(collectionId))
                db.delete("analysis_run_state", "run_id = ?", arrayOf(collectionId))
            }
            // A rerun has no analysis_chat_ranges row. Its temporary candidate
            // collection deliberately uses the parent run id, so terminal
            // cleanup owns it directly as well as the startup reaper's
            // unconditional temporary-state sweep after a hard process kill.
            db.delete("analysis_candidates", "run_id = ?", arrayOf(runId))
            db.delete("analysis_run_state", "run_id = ?", arrayOf(runId))
            for (chatId in affectedChats) excludeTranscriptIdsTx(db, chatId, emptyList())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertPreparedOutputsTx(
        db: SQLiteDatabase,
        memories: List<MemoryRecord>,
        rules: List<ModelRuleRecord>,
        lorebookSuggestions: List<LorebookSuggestionRecord>,
        relationshipHintsByMemoryId: Map<String, List<String>>
    ) {
        for (memory in memories) {
            insertPendingMemoryTx(db, memory, generated = true)
            for (existingId in relationshipHintsByMemoryId[memory.memoryId].orEmpty().distinct()) {
                if (existingId == memory.memoryId ||
                    !rowExists(db, "memories", "memory_id", existingId)
                ) continue
                db.insertWithOnConflict(
                    "memory_possible_match_hints", null, ContentValues().apply {
                        put("draft_memory_id", memory.memoryId)
                        put("existing_memory_id", existingId)
                        put("created_at", nowIso())
                    }, SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        }
        for (rule in rules) db.insertOrThrow("model_rules", null, modelRuleValues(rule))
        for (suggestion in lorebookSuggestions) {
            db.insertOrThrow("lorebook_suggestions", null, ContentValues().apply {
                put("suggestion_id", suggestion.suggestionId)
                put("run_id", suggestion.runId)
                put("content", suggestion.content)
                put("triggers_json", stringsToJson(suggestion.triggers))
                put("source_chat_id", suggestion.sourceChatId)
                put("source_chat_name", suggestion.sourceChatName)
                put("assigned_lorebook_id", suggestion.assignedLorebookId)
                put("created_at", suggestion.createdAt)
            })
        }
    }

    /**
     * Atomically expose every staged output from one frozen chat and advance
     * only that chat's bookmark. A failure at any insert or verification point
     * rolls back Memory drafts, Model Rule drafts, Lorebook suggestions, legacy
     * compatibility stamps, and the bookmark together.
     */
    fun commitFrozenChatRange(
        range: FrozenChatRange,
        memories: List<MemoryRecord>,
        rules: List<ModelRuleRecord>,
        lorebookSuggestions: List<LorebookSuggestionRecord>,
        relationshipHintsByMemoryId: Map<String, List<String>> = emptyMap(),
        runProgress: ArchivistRunRecord? = null
    ): CommittedChatOutputs {
        val db = writableDatabase
        db.beginTransaction()
        try {
            requireBookmarkCutover(db)
            val storedRange = db.query(
                "analysis_chat_ranges", null,
                "range_id = ? AND run_id = ? AND chat_id = ? AND status = 'running'",
                arrayOf(range.rangeId, range.runId, range.chatId), null, null, null
            ).use { c ->
                check(c.moveToFirst()) { "Frozen chat range is no longer active" }
                Pair(c.getStringOrNull("frozen_end_started_at"), c.getString(c.getColumnIndexOrThrow("frozen_end_transcript_id")))
            }
            check(storedRange.first == range.frozenEndStartedAt &&
                storedRange.second == range.frozenEndTranscriptId) {
                "Frozen chat range boundary changed"
            }

            val claimed = ArrayList<TranscriptRecord>()
            db.query(
                "transcripts", null, "chat_id = ? AND claim_run_id = ?",
                arrayOf(range.chatId, range.runId), null, null,
                "COALESCE(started_at, '') ASC, transcript_id ASC"
            ).use { while (it.moveToNext()) claimed.add(readTranscript(it)) }
            check(claimed.map { it.transcriptId } == range.transcripts.map { it.transcriptId }) {
                "Frozen chat range claim set changed"
            }
            check(claimed.lastOrNull()?.transcriptId == range.frozenEndTranscriptId) {
                "Frozen chat range is incomplete"
            }

            val bookmark = analysisBookmarkTx(db, range.chatId)
            val allRows = transcriptsForChatTx(db, range.chatId)
            val expectedPrefix = AnalysisBookmark.eligibleRange(
                rows = allRows,
                boundary = bookmarkBoundary(bookmark),
                skippedTranscriptIds = bookmark.skippedTranscriptIds.toSet(),
                idOf = { it.transcriptId },
                boundaryOf = { transcriptBoundary(it) }
            ).take(claimed.size)
            check(expectedPrefix.map { it.transcriptId } == claimed.map { it.transcriptId }) {
                "Frozen chat range no longer begins at the bookmark"
            }

            insertPreparedOutputsTx(
                db, memories, rules, lorebookSuggestions, relationshipHintsByMemoryId
            )

            val frozenEnd = AnalysisBookmark.Boundary(
                range.frozenEndStartedAt, range.frozenEndTranscriptId
            )
            val (advanced, remainingSkips) = AnalysisBookmark.advanceAfterCommit(
                rows = allRows,
                frozenEnd = frozenEnd,
                skippedTranscriptIds = bookmark.skippedTranscriptIds.toSet(),
                idOf = { it.transcriptId },
                boundaryOf = { transcriptBoundary(it) }
            )
            val now = nowIso()
            db.update("analysis_chat_bookmarks", ContentValues().apply {
                put("last_started_at", advanced.startedAt)
                put("last_transcript_id", advanced.transcriptId)
                put("skipped_transcript_ids_json", stringsToJson(remainingSkips.sorted()))
                put("updated_at", now)
            }, "chat_id = ?", arrayOf(range.chatId))

            // Compatibility/history only. Runtime eligibility no longer reads
            // either column after cutover.
            for (row in claimed) {
                db.update("transcripts", ContentValues().apply {
                    put("review_status", "processed")
                    put("processed_at", now)
                    putNull("claim_run_id")
                }, "transcript_id = ? AND claim_run_id = ?", arrayOf(row.transcriptId, range.runId))
            }
            db.delete("analysis_chat_ranges", "range_id = ?", arrayOf(range.rangeId))
            db.update("analysis_run_state", ContentValues().apply {
                put("filed", 1)
                put("updated_at", now)
            }, "run_id = ?", arrayOf(range.rangeId))
            db.delete("analysis_candidates", "run_id = ?", arrayOf(range.rangeId))
            db.delete("analysis_run_state", "run_id = ?", arrayOf(range.rangeId))
            runProgress?.let {
                db.insertWithOnConflict(
                    "archivist_runs", null, archivistRunValues(it), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return CommittedChatOutputs(
            memoryIds = memories.map { it.memoryId },
            ruleIds = rules.map { it.ruleId },
            lorebookSuggestionIds = lorebookSuggestions.map { it.suggestionId }
        )
    }

    /** Rerun rows are deliberately outside permanent eligibility. Their staged
     *  outputs still commit as one chat transaction, but no bookmark moves. */
    fun commitRerunChatOutputs(
        memories: List<MemoryRecord>,
        rules: List<ModelRuleRecord>,
        lorebookSuggestions: List<LorebookSuggestionRecord>,
        relationshipHintsByMemoryId: Map<String, List<String>> = emptyMap(),
        candidateCollectionId: String? = null,
        runProgress: ArchivistRunRecord? = null
    ): CommittedChatOutputs {
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertPreparedOutputsTx(
                db, memories, rules, lorebookSuggestions, relationshipHintsByMemoryId
            )
            candidateCollectionId?.let { collectionId ->
                db.update("analysis_run_state", ContentValues().apply {
                    put("filed", 1)
                    put("updated_at", nowIso())
                }, "run_id = ?", arrayOf(collectionId))
                db.delete("analysis_candidates", "run_id = ?", arrayOf(collectionId))
                db.delete("analysis_run_state", "run_id = ?", arrayOf(collectionId))
            }
            runProgress?.let {
                db.insertWithOnConflict(
                    "archivist_runs", null, archivistRunValues(it), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return CommittedChatOutputs(
            memoryIds = memories.map { it.memoryId },
            ruleIds = rules.map { it.ruleId },
            lorebookSuggestionIds = lorebookSuggestions.map { it.suggestionId }
        )
    }

    /**
     * Startup/next-run recovery (counterplan §4(a), the RenameJournal
     * pattern): a killed process runs no cleanup code, so any 'running' API
     * run found here is dead. Its unfinished claims are released (the rows
     * stay pending and are picked up by the next run — unseen text is never
     * marked processed) and the run row is finalized as interrupted, with
     * whatever per-conversation progress was durably recorded before death.
     * Deliberately scoped to transport='api': a future computer review
     * package's claims wait for import, cancel, or replacement — they are
     * the one kind never auto-released. Returns how many dead runs were
     * recovered.
     */
    fun reconcileInterruptedAnalysisRuns(): Int {
        val db = writableDatabase
        val deadRunIds = ArrayList<String>()
        db.beginTransaction()
        try {
            db.query(
                "archivist_runs", arrayOf("run_id"),
                "status = 'running' AND transport = 'api'", null, null, null, null
            ).use { while (it.moveToNext()) deadRunIds.add(it.getString(0)) }
            val now = nowIso()
            for (runId in deadRunIds) {
                db.execSQL(
                    "UPDATE transcripts SET claim_run_id = NULL WHERE claim_run_id = ?",
                    arrayOf(runId)
                )
                db.delete("analysis_chat_ranges", "run_id = ?", arrayOf(runId))
                db.update("archivist_runs", ContentValues().apply {
                    put("status", "failed")
                    put("outcome", "interrupted")
                    put("failure_reason", "interrupted")
                    put("finished_at", now)
                    put("error", "process ended before the run finished")
                }, "run_id = ?", arrayOf(runId))
            }
            // Belt: an API claim whose run row is gone entirely (e.g. the
            // record write itself died) is unrecoverable operational state —
            // release it so the rows return to the queue. Scoped to the API
            // run id prefix so future non-API claims are never touched.
            db.execSQL(
                "UPDATE transcripts SET claim_run_id = NULL WHERE claim_run_id LIKE 'run-%' " +
                    "AND claim_run_id NOT IN (SELECT run_id FROM archivist_runs)"
            )
            db.execSQL(
                "DELETE FROM analysis_chat_ranges WHERE run_id NOT IN (" +
                    "SELECT run_id FROM archivist_runs WHERE status = 'running')"
            )
            // Minimal temporary analysis-run recovery (§8.10): an unfiled run
            // is interrupted — discard its temporary candidates and state so
            // nothing half-analysed is left behind and the frozen range is
            // reanalysed cleanly. A filed run's temporary rows are also cleared
            // (finished bookkeeping). The decision is the pure
            // AnalysisRunReconciler so it is unit-tested off-device.
            val tempRuns = ArrayList<AnalysisRunReconciler.RunState>()
            db.query(
                "analysis_run_state", arrayOf("run_id", "filed"), null, null, null, null, null
            ).use {
                while (it.moveToNext()) {
                    tempRuns.add(AnalysisRunReconciler.RunState(it.getString(0), it.getInt(1) != 0))
                }
            }
            // Both interrupted and completed temporary runs are removed here;
            // candidates cascade with their run_state row (ON DELETE CASCADE),
            // and are deleted explicitly as a belt in case enforcement is off.
            for (runId in AnalysisRunReconciler.interruptedRunIds(tempRuns) +
                AnalysisRunReconciler.completedRunIds(tempRuns)) {
                db.delete("analysis_candidates", "run_id = ?", arrayOf(runId))
                db.delete("analysis_run_state", "run_id = ?", arrayOf(runId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deadRunIds.size
    }

    /**
     * "Add to Card" (owner ruling, July 8 2026 evening): linking MOVES the
     * memory onto the lore card — a content-derived name becomes the entry
     * name, its content the description, and the memory row is deleted
     * (tombstoned).
     * From then on the content lives and dies with the card ("think of it
     * like a d&d sheet… if you put it in the trash and take it out it's
     * never coming back") — it never returns to the browser. Returns false
     * when the memory no longer exists.
     */
    fun convertMemoryToCardEntry(
        memoryId: String, cardType: String, cardId: String, section: String
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val m = getMemory(memoryId) ?: return false
            db.insertOrThrow("card_entries", null, cardEntryValues(
                CardEntryRecord(
                    entryId = newId("ce-"),
                    cardType = cardType,
                    cardId = cardId,
                    section = section,
                    // Titles are retired (§3.1): a card entry still needs a name
                    // (its trigger word), so derive one from the memory content.
                    name = m.content.trim().take(80),
                    description = m.content,
                    createdAt = nowIso()
                )
            ))
            db.delete("memories", "memory_id = ?", arrayOf(memoryId))
            recordDeletionTx(db, "memory", memoryId)
            clearEntryCooldownTx(db, COOLDOWN_SOURCE_MEMORY, memoryId)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /* ---------------------------------------------------------------------- */
    /* lorebook suggestions (Step 1.7 — the Lorebook Memories analysis type)  */
    /* ---------------------------------------------------------------------- */

    /** File one pending lore book suggestion (Step 1.7). Called by the
     *  Archivist run in Lorebook Memories mode, under the same run durability
     *  as a memory draft. */
    fun insertLorebookSuggestion(s: LorebookSuggestionRecord) {
        writableDatabase.insertOrThrow("lorebook_suggestions", null, ContentValues().apply {
            put("suggestion_id", s.suggestionId)
            put("run_id", s.runId)
            put("content", s.content)
            put("triggers_json", stringsToJson(s.triggers))
            put("source_chat_id", s.sourceChatId)
            put("source_chat_name", s.sourceChatName)
            put("assigned_lorebook_id", s.assignedLorebookId)
            put("created_at", s.createdAt)
        })
    }

    /** All pending lore book suggestions, newest first (the Pending list). */
    fun getLorebookSuggestions(): List<LorebookSuggestionRecord> {
        val out = ArrayList<LorebookSuggestionRecord>()
        readableDatabase.query(
            "lorebook_suggestions", null, null, null, null, null,
            "created_at DESC, suggestion_id ASC"
        ).use { while (it.moveToNext()) out.add(readLorebookSuggestion(it)) }
        return out
    }

    /** How many lore book suggestions are pending — drives the Lorebooks split
     *  control and the Memory Assistant "Potential Lorebook Memories found: N"
     *  result. */
    fun lorebookSuggestionCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM lorebook_suggestions", emptyArray()).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getLorebookSuggestion(id: String): LorebookSuggestionRecord? {
        readableDatabase.query(
            "lorebook_suggestions", null, "suggestion_id = ?", arrayOf(id), null, null, null
        ).use { return if (it.moveToFirst()) readLorebookSuggestion(it) else null }
    }

    /** Edit a suggestion's proposed text and triggers in place (review-time
     *  edit; nothing is written to a real book until approval). */
    fun updateLorebookSuggestion(id: String, content: String, triggers: List<String>) {
        writableDatabase.update("lorebook_suggestions", ContentValues().apply {
            put("content", content)
            put("triggers_json", stringsToJson(triggers))
        }, "suggestion_id = ?", arrayOf(id))
    }

    /** Record the destination book the user assigned to a suggestion. */
    fun assignLorebookSuggestion(id: String, lorebookId: String?) {
        writableDatabase.update("lorebook_suggestions", ContentValues().apply {
            if (lorebookId.isNullOrBlank()) putNull("assigned_lorebook_id")
            else put("assigned_lorebook_id", lorebookId)
        }, "suggestion_id = ?", arrayOf(id))
    }

    /** Approval consumes the suggestion without recording a rejection — the
     *  caller has already written the LoreBookEntry into the chosen book. */
    fun consumeLorebookSuggestion(id: String) {
        writableDatabase.delete("lorebook_suggestions", "suggestion_id = ?", arrayOf(id))
    }

    /** Deleting a pending suggestion rejects it: the row is removed AND a
     *  rejection is recorded so a rerun of the same conversation does not
     *  refile the exact same suggestion (mirrors the memory-draft rule). */
    fun rejectLorebookSuggestion(id: String) {
        val s = getLorebookSuggestion(id) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!s.sourceChatId.isNullOrBlank()) {
                db.execSQL(
                    "INSERT OR REPLACE INTO rejected_lore_suggestions (content_hash, chat_key, deleted_at) VALUES (?, ?, ?)",
                    arrayOf(loreSuggestionHash(s.content), s.sourceChatId, nowIso())
                )
            }
            db.delete("lorebook_suggestions", "suggestion_id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** True when this exact suggestion text from this chat was already
     *  rejected — the run must not refile it. */
    fun isLorebookSuggestionRejected(content: String, chatKey: String): Boolean {
        readableDatabase.query(
            "rejected_lore_suggestions", arrayOf("content_hash"),
            "content_hash = ? AND chat_key = ?",
            arrayOf(loreSuggestionHash(content), chatKey), null, null, null
        ).use { return it.moveToNext() }
    }

    /** Content-level dedup so an interrupted-then-rerun conversation doesn't
     *  file the same suggestion twice: true when a pending suggestion with
     *  this exact text from the same chat already exists. */
    fun lorebookSuggestionExists(content: String, chatKey: String): Boolean {
        readableDatabase.query(
            "lorebook_suggestions", arrayOf("suggestion_id"),
            "content = ? AND source_chat_id = ?",
            arrayOf(content, chatKey), null, null, null
        ).use { return it.moveToNext() }
    }

    private fun readLorebookSuggestion(c: android.database.Cursor): LorebookSuggestionRecord =
        LorebookSuggestionRecord(
            suggestionId = c.getString(c.getColumnIndexOrThrow("suggestion_id")),
            runId = c.getString(c.getColumnIndexOrThrow("run_id")),
            content = c.getString(c.getColumnIndexOrThrow("content")),
            triggers = jsonToStrings(c.getString(c.getColumnIndexOrThrow("triggers_json"))),
            sourceChatId = c.getString(c.getColumnIndexOrThrow("source_chat_id")),
            sourceChatName = c.getString(c.getColumnIndexOrThrow("source_chat_name")),
            assignedLorebookId = c.getString(c.getColumnIndexOrThrow("assigned_lorebook_id")),
            createdAt = c.getString(c.getColumnIndexOrThrow("created_at"))
        )

    private fun loreSuggestionHash(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun stringsToJson(items: List<String>): String {
        val arr = org.json.JSONArray()
        for (i in items) arr.put(i)
        return arr.toString()
    }

    private fun jsonToStrings(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun archivistRunValues(run: ArchivistRunRecord) = ContentValues().apply {
        put("run_id", run.runId)
        put("started_at", run.startedAt)
        put("finished_at", run.finishedAt)
        put("status", run.status)
        put("chat_ids_json", run.chatIdsJson)
        put("transcript_ids_json", run.transcriptIdsJson)
        put("memory_ids_json", run.memoryIdsJson)
        put("rule_ids_json", run.ruleIdsJson)
        put("found_count", run.foundCount)
        put("failed_chat_ids_json", run.failedChatIdsJson)
        put("error", run.error)
        put("outcome", run.outcome)
        put("failure_reason", run.failureReason)
        put("transport", run.transport)
        put("analysis_type", run.analysisType)
    }

    /** Insert-or-replace one run row. Since v17 this is also the incremental
     *  progress write for a live 'running' row, so a process death loses at
     *  most the conversation in flight — never the whole run's bookkeeping. */
    fun insertArchivistRun(run: ArchivistRunRecord) {
        writableDatabase.insertWithOnConflict(
            "archivist_runs", null, archivistRunValues(run), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Persist a refined terminal outcome onto a run the engine already
     *  finalized (Step 1.7, Fix #6). The engine reports every in-process stop
     *  as the generic 'cancelled'; only the service knows whether it was the
     *  user's Cancel ('cancelled_user') or the Android 15+ runtime limit
     *  ('stopped_time_limit'), and it learns that after the engine returns. This
     *  writes that distinction back so the Recent Memory Analysis history shows
     *  what really happened instead of a generic stop. [failureReason] is
     *  cleared for a neutral user cancel — a user stop is not a failure. */
    fun updateArchivistRunOutcome(runId: String, outcome: String, failureReason: String?) {
        writableDatabase.update(
            "archivist_runs",
            ContentValues().apply {
                put("outcome", outcome)
                put("failure_reason", failureReason)
            },
            "run_id = ?", arrayOf(runId)
        )
    }

    /** Newest first, for the "Recent Memory Analysis" list. A live 'running'
     *  row is the durable active-run record, not history — excluded. */
    fun getArchivistRuns(limit: Int): List<ArchivistRunRecord> {
        val out = ArrayList<ArchivistRunRecord>()
        readableDatabase.query(
            "archivist_runs", null, "status != 'running'", null, null, null,
            "started_at DESC, run_id DESC", limit.toString()
        ).use {
            while (it.moveToNext()) {
                out.add(
                    ArchivistRunRecord(
                        runId = it.getString(it.getColumnIndexOrThrow("run_id")),
                        startedAt = it.getString(it.getColumnIndexOrThrow("started_at")),
                        finishedAt = it.getStringOrNull("finished_at"),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        chatIdsJson = it.getStringOrNull("chat_ids_json") ?: "[]",
                        transcriptIdsJson = it.getStringOrNull("transcript_ids_json") ?: "[]",
                        memoryIdsJson = it.getStringOrNull("memory_ids_json") ?: "[]",
                        ruleIdsJson = it.getStringOrNull("rule_ids_json") ?: "[]",
                        foundCount = it.getInt(it.getColumnIndexOrThrow("found_count")),
                        failedChatIdsJson = it.getStringOrNull("failed_chat_ids_json") ?: "[]",
                        error = it.getStringOrNull("error"),
                        outcome = it.getStringOrNull("outcome"),
                        failureReason = it.getStringOrNull("failure_reason"),
                        transport = it.getStringOrNull("transport") ?: "api",
                        analysisType = it.getStringOrNull("analysis_type") ?: "associative"
                    )
                )
            }
        }
        return out
    }

    fun getArchivistRun(runId: String): ArchivistRunRecord? =
        getArchivistRuns(Int.MAX_VALUE).firstOrNull { it.runId == runId }

    /** Which of these memory ids still exist — the complement is "deleted
     *  since that run" for the Recent Memory Analysis rows. */
    fun existingMemoryIds(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<String>()
        val db = readableDatabase
        for (id in ids) {
            db.query("memories", arrayOf("memory_id"), "memory_id = ?", arrayOf(id), null, null, null)
                .use { if (it.moveToNext()) out.add(id) }
        }
        return out
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
                        createdAt = it.getStringOrNull("created_at"),
                        imageRef = it.getStringOrNull("image_ref"),
                        shortDescription = it.getStringOrNull("short_description")
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
                        goalsDrives = it.getStringOrNull("goals_drives"),
                        imageRef = it.getStringOrNull("image_ref")
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

        val sql = "SELECT m.memory_id, m.scope, m.content, m.embedding_text, " +
            "m.importance, m.created_at, m.updated_at, m.world_id, " +
            "m.protection_json, m.type_id, m.tags_json " +
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

        scan(
            "SELECT content, scope, status, origin FROM memories " +
                "WHERE content LIKE ? OR embedding_text LIKE ? LIMIT $limit",
            {
                val status = it.getString(2)
                val origin = it.getString(3)
                val marks = StringBuilder()
                if (status != "active") marks.append(" [").append(status).append("]")
                if (origin != "user") marks.append(" [").append(origin).append("]")
                "Memory · ${it.getString(1)}$marks: ${it.getString(0).substringBefore('\n').take(48)}"
            },
            { it.getString(0) }
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
            arrayOf("memory_id", "scope", "content", "embedding_text",
                "importance", "created_at", "updated_at", "world_id",
                "protection_json", "type_id", "tags_json"),
            "status = 'active'", null, null, null, "created_at ASC"
        ).use {
            while (it.moveToNext()) out.add(readRetrievable(it))
        }
        return out
    }

    private fun readRetrievable(c: Cursor): RetrievableMemory = RetrievableMemory(
        memoryId = c.getString(c.getColumnIndexOrThrow("memory_id")),
        scope = c.getString(c.getColumnIndexOrThrow("scope")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        embeddingText = c.getStringOrNull("embedding_text"),
        importance = c.getInt(c.getColumnIndexOrThrow("importance")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        worldId = c.getStringOrNull("world_id"),
        protectionJson = c.getStringOrNull("protection_json"),
        typeId = c.getStringOrNull("type_id"),
        tagsJson = c.getStringOrNull("tags_json") ?: "[]",
        updatedAt = c.getStringOrNull("updated_at")
    )

    /** Stable target display names for every active memory — its linked
     *  worlds, campaigns, roleplay characters, companions, and projects —
     *  keyed by memory id. The memory-doc-v2 lexical document includes them
     *  so a memory is findable by the name of the thing it is about
     *  (counterplan §10 A.2). Five fixed queries, no per-memory N+1. */
    fun activeMemoryTargetNames(): Map<String, List<String>> {
        val out = HashMap<String, ArrayList<String>>()
        fun scan(sql: String) {
            readableDatabase.rawQuery(sql, emptyArray()).use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    if (name.isNotBlank()) out.getOrPut(it.getString(0)) { ArrayList() }.add(name)
                }
            }
        }
        scan(
            "SELECT mw.memory_id, w.name FROM memory_worlds mw " +
                "JOIN worlds w ON w.world_id = mw.world_id " +
                "JOIN memories m ON m.memory_id = mw.memory_id WHERE m.status = 'active'"
        )
        scan(
            "SELECT mc.memory_id, c.name FROM memory_campaigns mc " +
                "JOIN campaigns c ON c.campaign_id = mc.campaign_id " +
                "JOIN memories m ON m.memory_id = mc.memory_id WHERE m.status = 'active'"
        )
        scan(
            "SELECT mrc.memory_id, rc.name FROM memory_roleplay_characters mrc " +
                "JOIN roleplay_characters rc ON rc.roleplay_character_id = mrc.roleplay_character_id " +
                "JOIN memories m ON m.memory_id = mrc.memory_id WHERE m.status = 'active'"
        )
        scan(
            "SELECT mco.memory_id, co.current_name FROM memory_companions mco " +
                "JOIN companions co ON co.companion_id = mco.companion_id " +
                "JOIN memories m ON m.memory_id = mco.memory_id WHERE m.status = 'active'"
        )
        scan(
            "SELECT mp.memory_id, p.name FROM memory_projects mp " +
                "JOIN projects p ON p.project_id = mp.project_id " +
                "JOIN memories m ON m.memory_id = mp.memory_id WHERE m.status = 'active'"
        )
        return out
    }

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

    /** Stored vectors for [embeddingModel] over just the given active memory
     *  ids, keyed by memory id — the scoped working-set load a retrieval turn
     *  uses instead of [activeEmbeddings], so it reads only the current scene's
     *  eligible candidates rather than every active vector in the library
     *  (counterplan Step 1.4). A candidate with no current vector is simply
     *  absent from the map. Ids are chunked to respect the bound-variable
     *  limit; an empty request reads nothing. */
    fun embeddingsForMemories(memoryIds: Collection<String>, embeddingModel: String): HashMap<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        if (memoryIds.isEmpty()) return out
        val ids = memoryIds.toList()
        val db = readableDatabase
        var i = 0
        while (i < ids.size) {
            val chunk = ids.subList(i, minOf(i + EMBEDDING_ID_CHUNK, ids.size))
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<String>(chunk.size + 1)
            args.add(embeddingModel)
            args.addAll(chunk)
            db.rawQuery(
                "SELECT e.memory_id, e.vector FROM embeddings e " +
                    "JOIN memories m ON m.memory_id = e.memory_id " +
                    "WHERE e.embedding_model = ? AND m.status = 'active' " +
                    "AND e.memory_id IN ($placeholders)",
                args.toTypedArray()
            ).use {
                while (it.moveToNext()) out[it.getString(0)] = it.getBlob(1)
            }
            i += EMBEDDING_ID_CHUNK
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

    /** The derived missing-vector inventory: active memories that still lack a
     *  vector for [embeddingModel], oldest first, with the columns the semantic
     *  document needs. The background repair re-embeds exactly these
     *  (counterplan Step 1.4); it is computed fresh each pass, never a stored
     *  queue that could drift from the truth. */
    fun activeMemoriesMissingEmbedding(embeddingModel: String): List<RetrievableMemory> {
        val out = ArrayList<RetrievableMemory>()
        readableDatabase.rawQuery(
            "SELECT m.memory_id, m.scope, m.content, m.embedding_text, " +
                "m.importance, m.created_at, m.updated_at, m.world_id, " +
                "m.protection_json, m.type_id, m.tags_json " +
                "FROM memories m WHERE m.status = 'active' AND NOT EXISTS " +
                "(SELECT 1 FROM embeddings e WHERE e.memory_id = m.memory_id AND e.embedding_model = ?) " +
                "ORDER BY m.created_at ASC",
            arrayOf(embeddingModel)
        ).use {
            while (it.moveToNext()) out.add(readRetrievable(it))
        }
        return out
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
        markExcluded: Boolean,
        archivePaused: Boolean = false
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            requireBookmarkCutover(db)
            ensureAnalysisBookmarkTx(db, chatId)
            val now = nowIso()
            db.update("analysis_chat_bookmarks", ContentValues().apply {
                put("archive_paused", if (archivePaused) 1 else 0)
                put("updated_at", now)
            }, "chat_id = ?", arrayOf(chatId))
            val transcriptId = newId("t-")
            db.insertOrThrow("transcripts", null, ContentValues().apply {
                put("transcript_id", transcriptId)
                put("chat_id", chatId)
                put("companion_id", companionId)
                put("source", "imported")
                put("started_at", now)
                put("ended_at", now)
                put("content", contentJson)
                put("model_tag", modelTag)
                put("review_status", if (markExcluded) "excluded" else "pending")
            })
            if (markExcluded) excludeTranscriptIdsTx(db, chatId, listOf(transcriptId))
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
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
            db.execSQL(
                "UPDATE OR REPLACE analysis_chat_bookmarks SET chat_id = ? WHERE chat_id = ?",
                arrayOf(newChatId, oldChatId)
            )
            db.execSQL(
                "UPDATE OR REPLACE analysis_chat_ranges SET chat_id = ? WHERE chat_id = ?",
                arrayOf(newChatId, oldChatId)
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
            // Rejected-draft dedup is keyed on memory content alone (§3.2 /
            // item 1) — no chat identity to carry across a rename.
            // Step 1.7: pending lore book suggestions and their rejection
            // anchors are keyed by the same name-derived chat id and must ride
            // a rename too, or a rerun after rename would refile a rejected
            // suggestion (same reasoning as the memory-draft rows above).
            db.execSQL(
                "UPDATE lorebook_suggestions SET source_chat_id = ? WHERE source_chat_id = ?",
                arrayOf(newChatId, oldChatId)
            )
            db.execSQL(
                "UPDATE OR REPLACE rejected_lore_suggestions SET chat_key = ? WHERE chat_key = ?",
                arrayOf(newChatId, oldChatId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Review-state summary derived from the Stage-B bookmark authority. */
    fun chatReviewStates(): HashMap<String, String> {
        val out = HashMap<String, String>()
        val eligibleByChat = bookmarkEligibleTranscripts()
            .mapNotNull { row -> row.chatId?.let { it to row } }
            .groupBy({ it.first }, { it.second })
        val db = readableDatabase
        val chatIds = ArrayList<String>()
        db.rawQuery(
            "SELECT DISTINCT chat_id FROM transcripts WHERE chat_id IS NOT NULL ORDER BY chat_id",
            emptyArray<String>()
        ).use { while (it.moveToNext()) chatIds.add(it.getString(0)) }
        for (chatId in chatIds) {
            val bookmark = getAnalysisBookmark(chatId)
            val hasReviewedPrefix = bookmark?.lastTranscriptId != null
            val hasEligible = eligibleByChat[chatId].orEmpty().isNotEmpty()
            out[chatId] = when {
                hasEligible && hasReviewedPrefix -> "partial"
                hasEligible -> "pending"
                else -> "processed"
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
     * app-owned and untouched — only the memory-side record goes). Tombstone
     * for future cross-device merge. Memories follow the owner's sole-owner
     * rule (answer 5, `phase6_owner_answers_2026-07-08.md`): [deleteMemories]
     * removes only memories owned SOLELY by this companion — "if the other
     * companion that it's linked to is still active or existing then the
     * memory should not be deleted." Shared memories keep, with this
     * companion's link removed. memory_companions has ON DELETE CASCADE on the
     * memory side but nothing on the companion side, so surviving links are
     * scrubbed explicitly (a dangling FK would block the companion delete).
     * Companions have no mirror column on memories, so the planner's mirror
     * reassignments are always empty here.
     */
    fun deleteCompanion(companionId: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val owned = ArrayList<TargetTeardownPlanner.OwnedMemory>()
            db.query(
                "memory_companions", arrayOf("memory_id"), "companion_id = ?",
                arrayOf(companionId), null, null, "memory_id ASC"
            ).use { c ->
                while (c.moveToNext()) {
                    val memoryId = c.getString(0)
                    val others = ArrayList<String>()
                    db.query(
                        "memory_companions", arrayOf("companion_id"),
                        "memory_id = ? AND companion_id != ?", arrayOf(memoryId, companionId),
                        null, null, "companion_id ASC"
                    ).use { oc -> while (oc.moveToNext()) others.add(oc.getString(0)) }
                    owned.add(TargetTeardownPlanner.OwnedMemory(memoryId, others, mirrorId = null))
                }
            }
            val plan = TargetTeardownPlanner.plan(companionId, owned, deleteMemories)
            for (memoryId in plan.deleteMemoryIds) {
                deleteMemoriesWhere(db, "memory_id = ?", arrayOf(memoryId))
            }
            // Scrub every remaining link to this companion (the kept/shared
            // memories' rows plus any stragglers) so no memory references a
            // companion that no longer exists.
            db.delete("memory_companions", "companion_id = ?", arrayOf(companionId))
            // Discard any in-flight temporary analysis candidates aimed at this
            // companion (§8.10 / Phase 1 item 15): they must never survive the
            // companion or later file as an orphaned draft. General candidates
            // (no companion target) are untouched.
            db.delete("analysis_candidates", "target_type = ? AND target_id = ?", arrayOf("companion", companionId))
            db.delete("companions", "companion_id = ?", arrayOf(companionId))
            recordDeletionTx(db, "companion", companionId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * How many memories are targeted to this companion, across every lifecycle
     * state (Pending/draft, Active, Archived, Superseded). Reads the
     * memory_companions join — the source of truth for companion ownership.
     */
    fun companionMemoryCount(companionId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM memory_companions WHERE companion_id = ?", arrayOf(companionId)
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * How many memories a companion deletion will ACTUALLY delete: the memories
     * this companion SOLELY owns (canonical recovery plan §4.6 / item 6). A
     * memory also targeted to another companion survives (its link is removed,
     * the memory is not) and must NOT be counted as permanently deleted. This is
     * the number to disclose in the destructive confirmation — the same
     * sole-owner rule the deletion cascade applies.
     */
    fun companionSoleOwnedMemoryCount(companionId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM memory_companions mc WHERE mc.companion_id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM memory_companions o " +
                "WHERE o.memory_id = mc.memory_id AND o.companion_id != ?)",
            arrayOf(companionId, companionId)
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /* -------- durable companion-deletion markers (review finding 3) -------- */

    /** Record that a confirmed companion deletion is in progress (DB v22). The
     *  shared deletion service writes this BEFORE the cascade (its own committed
     *  statement) so a cascade that then fails or is interrupted leaves a durable
     *  marker to retry — a confirmed deletion is never silently left incomplete. */
    fun markCompanionPendingDeletion(companionId: String) {
        writableDatabase.execSQL(
            "INSERT INTO pending_companion_deletions (companion_id, requested_at) VALUES (?, ?) " +
                "ON CONFLICT(companion_id) DO UPDATE SET requested_at = excluded.requested_at",
            arrayOf(companionId, nowIso())
        )
    }

    /** Clear a companion-deletion marker once its cascade has completed. */
    fun clearCompanionPendingDeletion(companionId: String) {
        writableDatabase.delete("pending_companion_deletions", "companion_id = ?", arrayOf(companionId))
    }

    /** Companion ids whose confirmed deletion has not yet been proven complete —
     *  the retry queue the reconcile drains. */
    fun pendingCompanionDeletionIds(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query(
            "pending_companion_deletions", arrayOf("companion_id"), null, null, null, null, "requested_at ASC"
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    /* -------- memory types (§5) -------- */

    /** Every user-owned Memory Type, seed order preserved by created_at then
     *  name so the starter Types lead and user additions follow. */
    fun getMemoryTypes(): List<MemoryTypeRecord> {
        val out = ArrayList<MemoryTypeRecord>()
        readableDatabase.query(
            "memory_types", null, null, null, null, null, "created_at ASC, name ASC"
        ).use {
            while (it.moveToNext()) {
                out.add(
                    MemoryTypeRecord(
                        typeId = it.getString(it.getColumnIndexOrThrow("type_id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        createdAt = it.getString(it.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return out
    }

    /** Insert or update a Memory Type by its stable id. Used by backup/restore
     *  import; a rename keeps the id so associated memories are unaffected. */
    fun upsertMemoryType(type: MemoryTypeRecord) {
        writableDatabase.execSQL(
            "INSERT INTO memory_types (type_id, name, created_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(type_id) DO UPDATE SET name = excluded.name",
            arrayOf(type.typeId, type.name, type.createdAt.ifBlank { nowIso() })
        )
    }

    /**
     * Add a new user-owned Memory Type with a fresh stable id (canonical
     * recovery plan Phase 2, item 1). The id is generated here and never
     * changes; a later rename edits only the name. Returns the created record so
     * the caller can reference it by id.
     */
    fun addMemoryType(name: String): MemoryTypeRecord {
        val record = MemoryTypeRecord(newId("mtype-"), name.trim(), nowIso())
        writableDatabase.execSQL(
            "INSERT INTO memory_types (type_id, name, created_at) VALUES (?, ?, ?)",
            arrayOf(record.typeId, record.name, record.createdAt)
        )
        return record
    }

    /**
     * Rename a Memory Type by its stable id (Phase 2, item 1). Only the display
     * name changes; the id is untouched, so every memory's `type_id` keeps
     * resolving and no memory row is rewritten. Returns the ids of the memories
     * assigned to this Type whose embeddings were queued for refresh (Phase 2,
     * item 2): the stale vectors are dropped inside the same transaction so the
     * librarian's background self-repair re-embeds them off the UI thread — old
     * Type wording can never remain indefinitely in an active embedding
     * document. Provenance/source metadata is deliberately NOT written.
     */
    fun renameMemoryType(typeId: String, newName: String): List<String> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE memory_types SET name = ? WHERE type_id = ?", arrayOf(newName.trim(), typeId))
            val affected = queueEmbeddingRefreshForTypeTx(db, typeId)
            db.setTransactionSuccessful()
            return affected
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Delete a Memory Type without deleting any of its memories (Phase 2, item
     * 1). Atomic: affected memories are set to No Type (`type_id = NULL`) and the
     * Type row is removed in one transaction, so the two can never diverge. A
     * deleted Type's memories keep their content, scope, targets, tags,
     * importance, lifecycle, and timestamps — nothing is archived, superseded,
     * or altered. Returns the affected memory ids, whose embeddings are queued
     * for refresh (item 2) exactly as in [renameMemoryType]. Deleting a starter
     * Type does not re-seed it (item 1 / §5 item 7): the row simply goes.
     */
    fun deleteMemoryType(typeId: String): List<String> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Capture the affected ids and drop their stale vectors BEFORE the
            // reassignment, then detach (no ON DELETE rule on type_id) and delete.
            val affected = queueEmbeddingRefreshForTypeTx(db, typeId)
            db.execSQL("UPDATE memories SET type_id = NULL WHERE type_id = ?", arrayOf(typeId))
            db.delete("memory_types", "type_id = ?", arrayOf(typeId))
            db.setTransactionSuccessful()
            return affected
        } finally {
            db.endTransaction()
        }
    }

    /** How many memories are currently assigned to a Memory Type (Phase 2, item
     *  1) — every lifecycle state, the source of truth being memories.type_id. */
    fun countMemoriesForType(typeId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM memories WHERE type_id = ?", arrayOf(typeId)
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Drop the embeddings of every memory assigned to [typeId] and return their
     *  ids (Phase 2, item 2). Dropping a vector queues that memory for the
     *  librarian's existing background missing-vector repair — controlled,
     *  off-UI-thread re-embedding — rather than a synchronous re-embed here. */
    private fun queueEmbeddingRefreshForTypeTx(db: SQLiteDatabase, typeId: String): List<String> {
        val affected = ArrayList<String>()
        db.query("memories", arrayOf("memory_id"), "type_id = ?", arrayOf(typeId), null, null, "memory_id ASC")
            .use { while (it.moveToNext()) affected.add(it.getString(0)) }
        for (id in affected) db.delete("embeddings", "memory_id = ?", arrayOf(id))
        return affected
    }

    /**
     * File one validated candidate as a canonical Pending (draft) Associative
     * Memory (canonical recovery plan Phase 2, item 8). This is the ONE storage
     * path every filing origin converges on after validation — the API Memory
     * Assistant, a validated computer-file import, and manual pending creation
     * all reach Pending storage here, so none maintains its own filing behavior.
     * The record must arrive with status='draft' (a bug upstream must not file an
     * active memory without review). Never writes protection (the filing path
     * does not emit handling fields).
     */
    fun insertPendingMemory(m: MemoryRecord, generated: Boolean = false) {
        require(m.status == "draft") { "Pending memories must be drafts" }
        val safe = m.copy(protectionJson = null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertPendingMemoryTx(db, safe, generated)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Same canonical filing operation, reusable by the Stage-B chat-range
     *  transaction so drafts and bookmark advance commit together. */
    private fun insertPendingMemoryTx(db: SQLiteDatabase, m: MemoryRecord, generated: Boolean) {
        require(m.status == "draft") { "Pending memories must be drafts" }
        val safe = m.copy(protectionJson = null)
        db.insertOrThrow("memories", null, memoryValues(safe))
        writeMemoryLinks(db, safe)
        // The canonical candidate carries no source authorship, so the
        // change-log actor is a fixed "user" — origin is not read here (R2).
        logChange(db, safe.memoryId, "user", "proposed", null, null)
        if (generated) {
            db.execSQL(
                "INSERT OR IGNORE INTO generated_pending_drafts (memory_id, created_at) VALUES (?, ?)",
                arrayOf(safe.memoryId, nowIso())
            )
        }
    }

    /** True when a Pending draft was filed by the Memory Assistant / computer
     *  analysis (marked in the separate generated_pending_drafts bookkeeping),
     *  not by manual creation. Read inside the deletion transaction. */
    private fun isGeneratedPendingDraftTx(db: SQLiteDatabase, memoryId: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM generated_pending_drafts WHERE memory_id = ?", arrayOf(memoryId)
        ).use { return it.moveToFirst() }
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
            put("image_ref", p.imageRef)
            put("short_description", p.shortDescription)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setUserPersonaStatus(personaId: String, status: String) {
        writableDatabase.update(
            "user_personas", ContentValues().apply { put("status", status) },
            "persona_id = ?", arrayOf(personaId)
        )
    }

    /** Commit ONLY the image for an existing My Persona, by its stable id
     *  (Profile Images immediate-save, July 21 2026): a narrow UPDATE of the
     *  image_ref column so picking a picture in the editor persists at once
     *  without writing back the name/presentation/short-description draft the
     *  user may still be editing. A blank hash clears it. */
    fun setUserPersonaImageRef(personaId: String, imageRef: String?) {
        writableDatabase.update(
            "user_personas", ContentValues().apply { put("image_ref", imageRef?.ifEmpty { null }) },
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
            put("image_ref", r.imageRef)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setRoleplayCharacterStatus(id: String, status: String) {
        writableDatabase.update(
            "roleplay_characters", ContentValues().apply { put("status", status) },
            "roleplay_character_id = ?", arrayOf(id)
        )
    }

    /** Teardown: delete the character. Its memories are handled join-first
     *  (owner ruling July 8 2026, `roleplay_memory_deletion_fix.md`): shared
     *  memories always survive with their link removed and mirror reassigned;
     *  [deleteMemories] removes only the card's sole-owned ones. */
    fun deleteRoleplayCharacter(id: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            teardownTargetMemoriesTx(
                db, "memory_roleplay_characters", "roleplay_character_id", id, deleteMemories
            )
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
     * Delete-all teardown for a world, join-first (owner ruling July 8 2026,
     * `roleplay_memory_deletion_fix.md`): memories shared with another world
     * always survive. [keepCharacterMemories] honours the table plan's
     * option — a memory that ALSO belongs to a roleplay character (any row in
     * memory_roleplay_characters, not the old mirror-column read) is kept so
     * the character walks away clean; pure sole-owned world memories are
     * removed when [deleteMemories] is set.
     */
    fun deleteWorld(worldId: String, deleteMemories: Boolean, keepCharacterMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            teardownTargetMemoriesTx(
                db, "memory_worlds", "world_id", worldId, deleteMemories, keepCharacterMemories
            )
            // Campaigns anchored to this world lose their anchor but survive.
            db.update("campaigns", ContentValues().apply { putNull("world_id") },
                "world_id = ?", arrayOf(worldId))
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
     *  campaign and (optionally) its sole-owned campaign memories go, join-first
     *  (owner ruling July 8 2026, `roleplay_memory_deletion_fix.md`). */
    fun deleteCampaign(campaignId: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            teardownTargetMemoriesTx(
                db, "memory_campaigns", "campaign_id", campaignId, deleteMemories
            )
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

    /** Delete a project; its memories are handled join-first per the same
     *  owner ruling as the roleplay teardowns ("projects share the shape",
     *  `roleplay_memory_deletion_fix.md`): shared memories survive, only
     *  sole-owned ones go when [deleteMemories] is set. */
    fun deleteProject(projectId: String, deleteMemories: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            teardownTargetMemoriesTx(
                db, "memory_projects", "project_id", projectId, deleteMemories
            )
            db.delete("projects", "project_id = ?", arrayOf(projectId))
            recordDeletionTx(db, "project", projectId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun memoriesForProject(projectId: String, includeArchived: Boolean): List<MemoryRecord> =
        memoriesForTarget("memory_projects", "project_id", projectId, includeArchived)

    /* -------- model rules (Stage 4, owner_approved_rules §11 Revision 5) --------
     * User-written patches for a specific AI model's habits. The MODEL STRING
     * is the primary identity — no profiles/groups. Each rule carries its own
     * model_strings_json (which models it applies to) and any number of tags
     * (organizing labels only — a separate pool that never decides injection).
     * A rule with status='draft' is a Phase 6 Archivist suggestion awaiting
     * review. Injection matches by model string; the on/off decision is the
     * global default + per-chat toggle in Preferences, never in this store. */

    /** All rules, or only those with [status] ('active' | 'draft'). Oldest
     *  first, id tiebreak — the browser and Pending screens share this order. */
    fun getModelRules(status: String? = null): List<ModelRuleRecord> {
        val out = ArrayList<ModelRuleRecord>()
        val selection = if (status == null) null else "status = ?"
        val args = if (status == null) null else arrayOf(status)
        readableDatabase.query(
            "model_rules", null, selection, args, null, null, "created_at ASC, rule_id ASC"
        ).use { while (it.moveToNext()) out.add(readModelRule(it)) }
        return out
    }

    fun getModelRule(ruleId: String): ModelRuleRecord? {
        readableDatabase.query(
            "model_rules", null, "rule_id = ?", arrayOf(ruleId), null, null, null
        ).use { return if (it.moveToFirst()) readModelRule(it) else null }
    }

    /** The injection read (Stage 4): every ACTIVE rule whose model-strings
     *  list matches [chatModelId] (case-insensitive contains, provider prefix
     *  ignored — ModelRuleMatcher). Matching is fuzzy, so it can't live in
     *  SQL; we pull the active rows in deterministic order and filter in
     *  Kotlin. The order is fixed (oldest first, id tiebreak) so the rendered
     *  block is byte-identical across turns (prompt-layer contract). Rules are
     *  never truncated here — §11 forbids silently dropping matches. */
    fun getActiveModelRulesForModel(chatModelId: String): List<ModelRuleRecord> {
        if (chatModelId.isBlank()) return emptyList()
        return getModelRules("active").filter {
            org.teslasoft.assistant.preferences.memory.enforcer.ModelRuleMatcher
                .profileMatchesModel(it.modelStringsJson, chatModelId)
        }
    }

    private fun readModelRule(c: Cursor) = ModelRuleRecord(
        ruleId = c.getString(c.getColumnIndexOrThrow("rule_id")),
        text = c.getString(c.getColumnIndexOrThrow("text")),
        modelStringsJson = c.getStringOrNull("model_strings_json") ?: "[]",
        status = c.getString(c.getColumnIndexOrThrow("status")),
        sourceModelString = c.getStringOrNull("source_model_string"),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        updatedAt = c.getStringOrNull("updated_at")
    )

    private fun modelRuleValues(r: ModelRuleRecord) = ContentValues().apply {
        put("rule_id", r.ruleId)
        put("text", r.text)
        put("model_strings_json", r.modelStringsJson)
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
            db.delete("model_rule_tag_links", "rule_id = ?", arrayOf(ruleId))
            db.delete("model_rules", "rule_id = ?", arrayOf(ruleId))
            recordDeletionTx(db, "model_rule", ruleId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

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
