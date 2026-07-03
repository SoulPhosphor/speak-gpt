# SQLite Table Plan — Companion Memory System (schema v1.11)

This translates the JSON schema (v1.11) into concrete SQLite tables for Android. Same concepts, normalized where querying demands it, JSON columns where it doesn't. The JSON schema remains the canonical *export/import format*; these tables are its storage shape. No new behavior is introduced here.

## Design decisions

**JSON columns vs. join tables.** Arrays that are only ever read alongside their parent (hard limits, tags, handling lists, mode signals) are stored as JSON text columns — fewer joins, simpler code, and SQLite's json functions can reach inside when needed. Arrays that get *queried across* (which memories belong to this companion? which reference this entity?) become join tables, because those are the hot filters in retrieval.

**Dates** are ISO 8601 TEXT throughout (SQLite has no date type; ISO strings sort correctly).

**Deletes.** Foreign keys use ON DELETE CASCADE only where a child is meaningless without its parent (embeddings, change log entries, join rows). Deleting companions, worlds, roleplay characters, or memories is a user-only action performed by the enforcer, never by raw cascade from the Archivist.

**Encryption.** Use SQLCipher for the whole database. The protection categories in this system are exactly the data that must never sit in plaintext on a device.

## Tables

```sql
PRAGMA foreign_keys = ON;

-- ---------- system ----------
CREATE TABLE meta (
  key   TEXT PRIMARY KEY,          -- 'schema_version', 'db_migration'
  value TEXT NOT NULL
);

CREATE TABLE app_state (             -- what's active right now (enforcer runtime)
  id                  INTEGER PRIMARY KEY CHECK (id = 1),
  active_companion_id TEXT,
  active_world_id     TEXT,          -- null = ordinary conversation
  active_roleplay_character_id TEXT,
  active_user_persona_id TEXT
);

-- ---------- the person ----------
CREATE TABLE owner_profile (
  id               INTEGER PRIMARY KEY CHECK (id = 1),   -- single row
  portrait         TEXT NOT NULL,
  standing_context TEXT,
  updated_at       TEXT
);

-- ---------- companions ----------
CREATE TABLE companions (
  companion_id       TEXT PRIMARY KEY,
  current_name       TEXT NOT NULL,
  essence            TEXT NOT NULL,   -- STABLE CORE: written only by user action or accepted proposal
  relationship_notes TEXT,
  memory_participation TEXT NOT NULL DEFAULT 'full' CHECK (memory_participation IN ('full','global_only','none')),
  hard_limits_json   TEXT NOT NULL DEFAULT '[]',
  app_character_id   TEXT,               -- link to the app's own character config (app owns personality)
  base_personality_mirror_text TEXT,     -- read-only one-way synced snapshot of app config (drift detection)
  base_personality_mirror_synced_at TEXT,
  model_adaptations_json TEXT DEFAULT '[]',  -- per-chat-model tuning notes [{model_tag, notes}]
  created_at         TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN ('active','resting','retired'))
);

CREATE TABLE companion_name_history (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  companion_id    TEXT NOT NULL REFERENCES companions(companion_id) ON DELETE CASCADE,
  name            TEXT NOT NULL,
  effective_from  TEXT NOT NULL,
  effective_until TEXT
);

-- ---------- entities (projects, people, practices...) ----------
CREATE TABLE entities (
  entity_id    TEXT PRIMARY KEY,
  kind         TEXT NOT NULL,        -- project|person|practice|place|creature|other
  name         TEXT NOT NULL,
  aliases_json TEXT DEFAULT '[]',
  summary      TEXT NOT NULL,        -- living narrative state
  status       TEXT,
  importance   INTEGER DEFAULT 3,
  last_touched TEXT
);

-- ---------- roleplay ----------
CREATE TABLE worlds (
  world_id           TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  premise            TEXT NOT NULL,
  rules              TEXT,
  companion_ids_json TEXT DEFAULT '[]',
  status             TEXT NOT NULL CHECK (status IN ('active','dormant','ended')),
  created_at         TEXT
);

CREATE TABLE user_personas (                 -- presentation variants of the ONE user; never a memory scope
  persona_id   TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  presentation TEXT NOT NULL,
  status       TEXT NOT NULL CHECK (status IN ('active','archived')),
  created_at   TEXT
);

-- world-agnostic by design: roleplay characters travel between worlds
CREATE TABLE roleplay_characters (
  roleplay_character_id       TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  played_by          TEXT NOT NULL,  -- 'user' or a companion_id
  description        TEXT NOT NULL,  -- USER-OWNED definition: traits, appearance, voice (editable in app tab)
  arc                TEXT,            -- ARCHIVIST-MAINTAINED story-so-far (auto); never overwrites description
  worlds_played_json TEXT DEFAULT '[]',
  status             TEXT NOT NULL CHECK (status IN ('active','archived')),
  created_at         TEXT
);

-- ---------- memories ----------
CREATE TABLE memories (
  memory_id             TEXT PRIMARY KEY,
  scope                 TEXT NOT NULL CHECK (scope IN ('global','companion')),
  kind                  TEXT NOT NULL,
  title                 TEXT NOT NULL,
  content               TEXT NOT NULL,       -- the prose. source of truth
  embedding_text        TEXT,                -- optional condensed text for the librarian
  tags_json             TEXT DEFAULT '[]',
  importance            INTEGER NOT NULL DEFAULT 3,
  always_load           INTEGER NOT NULL DEFAULT 0,   -- 0/1; tentative memories may never be 1
  world_id              TEXT REFERENCES worlds(world_id),
  roleplay_character_id          TEXT REFERENCES roleplay_characters(roleplay_character_id),
  protection_json       TEXT,                -- {is_protected, reasons[], handling[], never_assume[], casual_mention_ok, suggested_mode}
  mode_hints_json       TEXT DEFAULT '[]',
  provenance_source     TEXT,                -- user_stated|inferred|imported|companion_observed|archivist
  provenance_confidence TEXT,                -- certain|likely|tentative
  provenance_noted_on   TEXT,
  provenance_context    TEXT,
  created_at            TEXT NOT NULL,
  updated_at            TEXT,
  status                TEXT NOT NULL CHECK (status IN ('active','archived','superseded')),
  supersedes            TEXT REFERENCES memories(memory_id)
);

CREATE TABLE memory_companions (               -- which companions a scoped memory belongs to
  memory_id    TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE,
  companion_id TEXT NOT NULL REFERENCES companions(companion_id),
  PRIMARY KEY (memory_id, companion_id)
);

CREATE TABLE memory_entities (
  memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE,
  entity_id TEXT NOT NULL REFERENCES entities(entity_id),
  PRIMARY KEY (memory_id, entity_id)
);

CREATE TABLE change_log (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  memory_id        TEXT REFERENCES memories(memory_id) ON DELETE CASCADE,
  at               TEXT NOT NULL,
  actor            TEXT NOT NULL CHECK (actor IN ('user','archivist','companion','system')),
  action           TEXT NOT NULL,   -- created|edited|protected|corrected|superseded|archived|flagged|reverted
  note             TEXT,
  prior_state_json TEXT             -- full snapshot of the record BEFORE the change; powers one-tap undo
);

-- ---------- behavior rules ----------
CREATE TABLE modes (
  mode_id            TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  purpose            TEXT,
  signals_json       TEXT NOT NULL,
  respond_json       TEXT NOT NULL,
  avoid_json         TEXT NOT NULL,
  transition_note    TEXT,
  overrides_json     TEXT DEFAULT '[]',
  scope              TEXT NOT NULL DEFAULT 'global',
  companion_ids_json TEXT DEFAULT '[]'
);

CREATE TABLE directives (
  directive_id    TEXT PRIMARY KEY,
  text            TEXT NOT NULL,
  rationale       TEXT,
  applies_to_json TEXT DEFAULT '[]',   -- empty = all companions
  priority        INTEGER DEFAULT 3
);

-- ---------- archivist ----------
CREATE TABLE archivist_settings (
  id                 INTEGER PRIMARY KEY CHECK (id = 1),
  run_trigger        TEXT NOT NULL,     -- 'manual'|'auto'|'auto_plus_manual' ('trigger' is a SQL keyword)
  harvest_generosity TEXT NOT NULL,
  autonomy_json      TEXT NOT NULL,     -- {"facts_and_episodes":"auto", ...}
  notes              TEXT
);

CREATE TABLE proposals (
  proposal_id          TEXT PRIMARY KEY,
  target_type          TEXT NOT NULL,
  target_id            TEXT,
  summary              TEXT NOT NULL,
  proposed_change_json TEXT,
  rationale            TEXT,
  status               TEXT NOT NULL CHECK (status IN ('pending','accepted','rejected')),
  created_at           TEXT NOT NULL,
  resolved_at          TEXT
);

CREATE TABLE transcripts (                       -- the Archivist's queue AND the audit archive
  transcript_id TEXT PRIMARY KEY,
  companion_id  TEXT NOT NULL REFERENCES companions(companion_id),
  world_id      TEXT REFERENCES worlds(world_id),
  roleplay_character_id  TEXT REFERENCES roleplay_characters(roleplay_character_id),
  source        TEXT NOT NULL DEFAULT 'live' CHECK (source IN ('live','imported')),
  started_at    TEXT,
  ended_at      TEXT,
  content       TEXT NOT NULL,                   -- full conversation text (or JSON message array)
  model_tag     TEXT,                            -- which chat model served this conversation
  quick_settings_json TEXT,                      -- snapshot of sampling settings (gospel) at time of chat
  review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending','processed','excluded')),
  -- 'excluded' = user marked do-not-review: the Archivist never reads it, zero memories result; reversible by setting back to 'pending'
  processed_at  TEXT
);

-- ---------- retrieval config ----------
CREATE TABLE retrieval_policy (
  id          INTEGER PRIMARY KEY CHECK (id = 1),
  policy_json TEXT NOT NULL                      -- the whole retrieval_policy object from the seed
);

-- ---------- the librarian's sidecar (swappable) ----------
CREATE TABLE embeddings (
  memory_id       TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE,
  embedding_model TEXT NOT NULL,                 -- e.g. 'embeddinggemma-308m-q4'
  vector          BLOB NOT NULL,                 -- float32 array
  embedded_at     TEXT NOT NULL,
  PRIMARY KEY (memory_id, embedding_model)
);

-- ---------- indexes (the hot paths) ----------
CREATE INDEX idx_memories_status      ON memories(status);
CREATE INDEX idx_memories_always_load ON memories(always_load) WHERE always_load = 1;
CREATE INDEX idx_memories_world       ON memories(world_id);
CREATE INDEX idx_memories_rp_character   ON memories(roleplay_character_id);
CREATE INDEX idx_memcomp_companion    ON memory_companions(companion_id);
CREATE INDEX idx_changelog_memory     ON change_log(memory_id);
CREATE INDEX idx_transcripts_queue    ON transcripts(review_status) WHERE review_status = 'pending';
CREATE INDEX idx_proposals_pending    ON proposals(status) WHERE status = 'pending';
```


## Operational rules the code must honor

**The archive rule (closed stacks).** When a memory's status leaves 'active' — or its world is marked 'ended', or its character archived — delete its rows from `embeddings`. The record stays; the librarian simply can no longer see it. On reactivation, re-embed. Retrieval queries always filter `memories.status = 'active'` AND (world_id IS NULL OR world_id = active world) as a second guarantee.

**World teardown (user-only, one action).**
```sql
-- archive flavor:
UPDATE memories SET status='archived', updated_at=:now WHERE world_id=:w;
UPDATE worlds SET status='ended' WHERE world_id=:w;
-- delete flavor: DELETE the memories (cascades clean embeddings/logs/joins), then the world.
-- Roleplay-character teardown is identical using roleplay_character_id. A memory carrying BOTH ids:
-- world teardown archives it unless the user chooses "keep character memories,"
-- in which case rows where roleplay_character_id refers to a kept roleplay character survive.
```

**One-tap undo.** Every Archivist write stores the record's prior state in `change_log.prior_state_json` ('created' stores null — undo of a create is archive). Revert = restore the snapshot, log action 'reverted', re-embed if needed.

**Ownership and caching.** The APP owns character configuration (visible name, avatar, greeting, base prompt, personality settings); when app_character_id is set, that config is what gets injected as base personality. The STORE owns continuity: companion_id, essence (continuity summary only — never the canonical persona prompt when an app link exists), memories, relationship notes, companion lore, hard limits, protection, provenance, proposals, and model adaptations. base_personality_mirror_* columns are a read-only one-way copy of app config (app → store) for drift detection; nothing in the store ever writes back to app config. If either side caches the other's data for speed, the cache is derived and rebuilt, never edited directly.

**Export / backup.** Export walks the tables back into the JSON schema shape (the schema file IS the export format) — one file the user owns, importable on any future device or the eventual PC setup. Embeddings are NOT exported; they're rebuilt by whatever librarian imports it. Recommend automatic periodic export to user-accessible storage.

**Import / backfill.** Old conversations (e.g., exported chats from previous AI systems) are inserted as transcripts with source='imported' and queued for the Archivist like any other conversation. Resulting memories carry provenance_source='imported'. This is how a past companion's history can be rebuilt.

**Vector search.** Options, in order of recommendation for Android: (1) sqlite-vec extension — vectors searchable inside this same database; (2) brute-force in app code — read active vectors, cosine similarity in memory; completely fine below ~50k memories and zero dependencies; (3) a dedicated index later if scale ever demands it. Start with (2) or (1); the embeddings table shape supports all three.

**Migrations.** `meta.db_migration` tracks the applied migration number. Every schema change ships as a numbered migration script; never mutate tables ad hoc.

## What this document deliberately does not contain

App code, the enforcer logic, prompt assembly, or the librarian implementation — those come next and consume this file plus the JSON schema, seed, and Archivist spec as their requirements.
