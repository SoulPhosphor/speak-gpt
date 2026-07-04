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
        private const val DATABASE_VERSION = 1

        // meta keys
        const val META_SCHEMA_VERSION = "schema_version"
        const val META_DB_MIGRATION = "db_migration"
        const val META_SEED_IMPORTED_AT = "seed_imported_at"
        const val META_BOOTSTRAP_DONE = "bootstrap_done"
        const val META_AUTO_EXPORT_ENABLED = "auto_export_enabled"
        const val META_LAST_AUTO_EXPORT_AT = "last_auto_export_at"
        const val META_INDEX_MODEL_TAG = "index_model_tag"

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
        db.setForeignKeyConstraintsEnabled(true)
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
                "status TEXT NOT NULL CHECK (status IN ('draft','active','resting','retired')))"
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
                "last_touched TEXT)"
        )

        db.execSQL(
            "CREATE TABLE worlds (" +
                "world_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "premise TEXT NOT NULL, " +
                "rules TEXT, " +
                "companion_ids_json TEXT DEFAULT '[]', " +
                "status TEXT NOT NULL CHECK (status IN ('active','dormant','ended')), " +
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

        db.execSQL(
            "CREATE TABLE roleplay_characters (" +
                "roleplay_character_id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "played_by TEXT NOT NULL, " +
                "description TEXT NOT NULL, " +
                "arc TEXT, " +
                "worlds_played_json TEXT DEFAULT '[]', " +
                "status TEXT NOT NULL CHECK (status IN ('active','archived')), " +
                "created_at TEXT)"
        )

        db.execSQL(
            "CREATE TABLE memories (" +
                "memory_id TEXT PRIMARY KEY, " +
                "scope TEXT NOT NULL CHECK (scope IN ('global','companion')), " +
                "kind TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "embedding_text TEXT, " +
                "tags_json TEXT DEFAULT '[]', " +
                "importance INTEGER NOT NULL DEFAULT 3, " +
                "always_load INTEGER NOT NULL DEFAULT 0, " +
                "world_id TEXT REFERENCES worlds(world_id), " +
                "roleplay_character_id TEXT REFERENCES roleplay_characters(roleplay_character_id), " +
                "protection_json TEXT, " +
                "mode_hints_json TEXT DEFAULT '[]', " +
                "provenance_source TEXT, " +
                "provenance_confidence TEXT, " +
                "provenance_noted_on TEXT, " +
                "provenance_context TEXT, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT, " +
                "status TEXT NOT NULL CHECK (status IN ('active','archived','superseded')), " +
                "supersedes TEXT REFERENCES memories(memory_id))"
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
                "companion_ids_json TEXT DEFAULT '[]')"
        )

        db.execSQL(
            "CREATE TABLE directives (" +
                "directive_id TEXT PRIMARY KEY, " +
                "text TEXT NOT NULL, " +
                "rationale TEXT, " +
                "applies_to_json TEXT DEFAULT '[]', " +
                "priority INTEGER DEFAULT 3)"
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

        db.execSQL("CREATE INDEX idx_memories_status ON memories(status)")
        db.execSQL("CREATE INDEX idx_memories_always_load ON memories(always_load) WHERE always_load = 1")
        db.execSQL("CREATE INDEX idx_memories_world ON memories(world_id)")
        db.execSQL("CREATE INDEX idx_memories_rp_character ON memories(roleplay_character_id)")
        db.execSQL("CREATE INDEX idx_memcomp_companion ON memory_companions(companion_id)")
        db.execSQL("CREATE INDEX idx_changelog_memory ON change_log(memory_id)")
        db.execSQL("CREATE INDEX idx_transcripts_queue ON transcripts(review_status) WHERE review_status = 'pending'")
        db.execSQL("CREATE INDEX idx_transcripts_chat ON transcripts(chat_id)")
        db.execSQL("CREATE INDEX idx_proposals_pending ON proposals(status) WHERE status = 'pending'")

        val now = nowIso()
        db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(META_SCHEMA_VERSION, "1.11.0"))
        db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(META_DB_MIGRATION, "1"))
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
            nameHistory = if (includeHistory) readNameHistory(id) else emptyList()
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
                })
                report.addAdded("roleplay characters")
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
                    put("always_load", if (m.alwaysLoad) 1 else 0)
                    put("world_id", m.worldId)
                    put("roleplay_character_id", m.roleplayCharacterId)
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
                })
                for (cid in m.companionIds) {
                    db.insertWithOnConflict("memory_companions", null, ContentValues().apply {
                        put("memory_id", m.memoryId)
                        put("companion_id", cid)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                for (eid in m.entityRefs) {
                    db.insertWithOnConflict("memory_entities", null, ContentValues().apply {
                        put("memory_id", m.memoryId)
                        put("entity_id", eid)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
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
                        lastTouched = it.getStringOrNull("last_touched")
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
                        createdAt = it.getStringOrNull("created_at")
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
                        alwaysLoad = it.getInt(it.getColumnIndexOrThrow("always_load")) == 1,
                        worldId = it.getStringOrNull("world_id"),
                        roleplayCharacterId = it.getStringOrNull("roleplay_character_id"),
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
                        changeLog = readChangeLog(db, id)
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
                        companionIdsJson = it.getStringOrNull("companion_ids_json") ?: "[]"
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
                        priority = it.getInt(it.getColumnIndexOrThrow("priority"))
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
            transcripts = transcripts
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
    fun appendTranscriptTurn(
        chatId: String,
        companionId: String?,
        userMessage: String,
        assistantMessage: String,
        modelTag: String,
        quickSettingsJson: String?,
        markExcluded: Boolean
    ) {
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

            if (rowId == null) {
                db.insert("transcripts", null, ContentValues().apply {
                    put("transcript_id", newId("t-"))
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
            } else {
                db.update("transcripts", ContentValues().apply {
                    put("content", turns.toString())
                    put("ended_at", now)
                    put("quick_settings_json", quickSettingsJson)
                    if (markExcluded) put("review_status", "excluded")
                }, "transcript_id = ?", arrayOf(rowId))
            }
            db.setTransactionSuccessful()
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
    /* librarian: retrievable memories + embeddings sidecar (Phase 3)         */
    /* ---------------------------------------------------------------------- */

    /**
     * Active memories visible to a conversation, with scope isolation enforced
     * IN THE QUERY (not by convention, per the spec's non-negotiable): global
     * memories plus those scoped to [companionId], and — for world sessions —
     * only memories with no world or this world; real-life (world-less)
     * memories remain available inside a world. [worldId] null = ordinary chat
     * (world-tagged fiction is excluded).
     */
    fun activeMemoriesForScope(companionId: String?, worldId: String?): List<RetrievableMemory> {
        val out = ArrayList<RetrievableMemory>()
        val args = ArrayList<String>()
        val sb = StringBuilder(
            "SELECT DISTINCT m.memory_id, m.scope, m.title, m.content, m.embedding_text, " +
                "m.importance, m.always_load, m.created_at, m.world_id, m.provenance_confidence " +
                "FROM memories m LEFT JOIN memory_companions mc ON mc.memory_id = m.memory_id " +
                "WHERE m.status = 'active' AND (m.scope = 'global'"
        )
        if (companionId != null) {
            sb.append(" OR mc.companion_id = ?")
            args.add(companionId)
        }
        sb.append(")")
        if (worldId == null) {
            sb.append(" AND m.world_id IS NULL")
        } else {
            sb.append(" AND (m.world_id IS NULL OR m.world_id = ?)")
            args.add(worldId)
        }
        readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use {
            while (it.moveToNext()) out.add(readRetrievable(it))
        }
        return out
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
            "SELECT title, content, scope FROM memories WHERE status='active' AND (title LIKE ? OR content LIKE ?) LIMIT $limit",
            { "Memory · ${it.getString(2)}: ${it.getString(0)}" },
            { it.getString(1) }
        )
        scan(
            "SELECT current_name, essence FROM companions WHERE current_name LIKE ? OR essence LIKE ? LIMIT $limit",
            { "Companion: ${it.getString(0)}" }, { it.getStringOrNull(1) ?: "" }
        )
        scan(
            "SELECT name, summary FROM entities WHERE name LIKE ? OR summary LIKE ? LIMIT $limit",
            { "Entity: ${it.getString(0)}" }, { it.getStringOrNull(1) ?: "" }
        )
        scan(
            "SELECT name, description FROM roleplay_characters WHERE name LIKE ? OR description LIKE ? LIMIT $limit",
            { "Roleplay character: ${it.getString(0)}" }, { it.getStringOrNull(1) ?: "" }
        )
        scan(
            "SELECT name, premise FROM worlds WHERE name LIKE ? OR premise LIKE ? LIMIT $limit",
            { "World: ${it.getString(0)}" }, { it.getStringOrNull(1) ?: "" }
        )
        return out.take(limit)
    }

    /** Every active memory, ignoring scope — used to (re)build the whole index. */
    fun allActiveMemories(): List<RetrievableMemory> {
        val out = ArrayList<RetrievableMemory>()
        readableDatabase.query(
            "memories",
            arrayOf("memory_id", "scope", "title", "content", "embedding_text",
                "importance", "always_load", "created_at", "world_id", "provenance_confidence"),
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
        alwaysLoad = c.getInt(c.getColumnIndexOrThrow("always_load")) == 1,
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        worldId = c.getStringOrNull("world_id"),
        provenanceConfidence = c.getStringOrNull("provenance_confidence")
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

    /** Chats survive renames but transcripts are keyed by chat_id — re-point
     *  them whenever a chat id changes (auto-naming, manual rename). */
    fun repointChat(oldChatId: String, newChatId: String) {
        if (oldChatId == newChatId || oldChatId.isBlank() || newChatId.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE transcripts SET chat_id = ? WHERE chat_id = ?", arrayOf(newChatId, oldChatId)
        )
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
}

private fun Cursor.getStringOrNull(column: String): String? {
    val idx = getColumnIndexOrThrow(column)
    return if (isNull(idx)) null else getString(idx)
}
