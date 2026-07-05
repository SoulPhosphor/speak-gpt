# Companion Memory System — Integration Plan

Status: **approved by the owner (July 2026) — execution in progress.**
Written against the complete design package in `Memory System/` (schema v1.11,
all eleven documents present) and the current codebase. This file is
self-sufficient: an agent in a fresh session should be able to read this plan
plus the `Memory System/` docs and CLAUDE.md, pick the next unchecked phase,
and build it. **Before starting any phase, read this whole file, the spec
documents a phase cites, and CLAUDE.md.** Where this plan and the spec
documents disagree, the specs win; where the specs are silent, this plan
records the code-level decision so future agents don't re-litigate it.
Update the checkboxes and notes here as phases land.

This plan supersedes the phase list in `Memory System Plans June 1 2026` from
Phase 2 onward — Phase 1 of that older roadmap (the lorebook) is built and
stays as the low-RAM tier, exactly as the new design intends.

## What we're building, in one paragraph

A second, encrypted SQLite store (`companion_memory.db`) that holds the
companion memory system from `Memory System/sqlite_table_plan.md`: companions
(mapped 1:1 to the app's existing personas), global and companion-scoped prose
memories with protection/provenance, entities, modes, directives, roleplay
worlds/characters, user personas, transcripts, proposals, and a change log.
Around it: a **librarian** (on-device embeddings + brute-force cosine
retrieval, model swappable, EmbeddingGemma the default), an **enforcer**
(per-turn prompt assembly per `prompt_assembly_template.md` + change-set
validation per `enforcer_librarian_spec.md`, living behind the existing
`generateResponse` → `regularGPTResponse` funnel), and an **Archivist** (an
LLM run manually by the user that reviews pending transcripts and maintains
the store under autonomy dials, with one-tap undo). Lore books remain
untouched as the lightweight tier and outrank system memories at injection.
Existing stores (lorebook DB, chat history) also get encrypted at rest.

## Decision log (owner answers, July 2026)

- **Archivist model** is a user choice: a global app setting (endpoint
  profile picker over the existing `ApiEndpointPreferences` profiles + a
  model-name field). Today it points at z.ai; it may point at another
  endpoint or a local server later — nothing may assume a specific provider.
- **Librarian** defaults to EmbeddingGemma but MUST be swappable/updatable:
  code against an embedding-model abstraction, never a hardcoded model (D3).
- **Rename "Personas" → "Companions"** in the UI: approved.
- **ONNX Runtime + Hugging Face downloads** for embedding models: approved.
- **Encryption scope expanded**: the owner wants the existing lorebook DB and
  chat history encrypted too, not just the new store → Phase 1b.
- **Transcript watermark model** (D5): approved, with two additions — a
  visible **partially processed** state, and the user must be able to stop
  further archiving of a chat even after part of it was processed (exclusion
  applies from the watermark forward; already-written memories stay).
- **`example_seed.json`** was removed from the repo by the owner. It remains
  in git history; a history purge is the owner's call, on request. The
  gitignore rules from the package README are Phase 0 work.
- **Cross-device future (D10)**: the owner wants chats & memories eventually
  shared seamlessly between Android and a future Windows build (possibly via
  a user-provided synced folder such as Google Drive), with Windows using a
  different, higher-quality embedding model. v1 stays local-only but every
  design choice must keep that door open.
- **Agents/usage**: implementation sessions should be frugal — delegate
  mechanical, well-specified work to cheaper models where quality allows;
  keep fragile areas (anything in `ChatActivity`'s generation/voice paths,
  encryption/migrations) with a strong model.

## Ground rules carried over from CLAUDE.md (they all still apply)

- CI is the compile gate (no local SDK; `dl.google.com` blocked in sandboxes).
  Statically verify before pushing; every phase ends pushed and green on the
  `Android Checks` workflow. Every phase leaves the app fully usable.
- All cross-cutting request changes go through the single funnel in
  `ChatActivity` (`generateResponse` → `regularGPTResponse`). The memory
  system's injection joins the existing lorebook block there (~line 4060);
  it must NOT reorder or merge the stable first system message (prefix
  caching) — everything the enforcer adds goes in separate system messages
  after it, like lorebook matches do today.
- Any new per-chat preference must be added to the auto-naming copy block in
  `ChatActivity`, or it silently vanishes when a chat is auto-renamed.
- New strings in `res/values/strings.xml` only; Views/XML UI matching current
  style (the UI redesign in `ui-redesign-plan.md` is a later, separate
  effort — build these screens as plain functional Material 3 Views screens;
  keep feature logic out of layouts/adapters so the redesign can restyle them).
- Voice pipeline untouched. Nothing here touches `stt/` except *reusing* the
  Whisper downloader pattern for the embedding model.
- Dependencies come from Maven Central / the repos already configured —
  verify a new artifact exists there before depending on it.

## Key architecture decisions

**D1 — Separate database, not an extension of `lorebook.db`.**
New file `companion_memory.db` (SQLCipher), new
`preferences/memory/MemoryStore.kt` singleton (`getInstance(context)` with
`applicationContext`, same as `LoreBookStore`), `meta.db_migration` numbered
migrations, DDL exactly per `sqlite_table_plan.md`. `lorebook.db` keeps its
own schema and store class (it gains encryption in Phase 1b, nothing else).

**D2 — Encryption key management.** One random 32-byte key per encrypted
database, generated on first use and stored in `EncryptedPreferences`
(androidx security-crypto, already used for API keys — its master key lives
in the Android Keystore). No user-facing password: losing it would orphan the
store; the JSON export is the recovery path (exports are plain JSON by
design — the user's own encrypted backups are their responsibility, per the
package README).

**D3 — Librarian is an abstraction; EmbeddingGemma is the default.**
A small interface (suggested `stt/`-style package `memory/librarian/`):
`EmbeddingModel { val tag: String; val dimensions: Int; fun embed(text): FloatArray }`
plus a catalog of downloadable variants cloned from the
`LocalWhisperModels`/`LocalWhisperDownloader`/`LocalWhisperStorage` pattern
(Hugging Face URLs, user picks what fits, removable). Runtime: ONNX Runtime
(`onnxruntime-android:1.20.0`, already a dependency for Silero). Default
catalog entries: EmbeddingGemma-308M ONNX quantizations at 256-dim output
(Matryoshka truncation — the enforcer spec's recommendation). The sidecar
`embeddings` table is keyed `(memory_id, embedding_model)`, so adding e.g. a
nomic-embed variant later, or a higher-quality model on Windows, is a catalog
entry + re-embed — never a schema or code-structure change. Everything
outside `librarian/` talks to the interface only.

**D4 — Vector search is brute-force cosine in Kotlin.** Read active vectors,
score in memory. The table plan recommends this below ~50k memories; zero new
dependencies. The `embeddings` table shape supports moving to sqlite-vec
later without schema change.

**D5 — Chats vs. "conversations": transcripts use a watermark.** The spec
assumes discrete conversations that end; the app has persistent, resumable
chats. Resolution: each chat maps to at most one *open* transcript row.
A per-chat message-index watermark records how far the Archivist has
processed. When the user runs the Archivist, each chat with unprocessed
messages contributes its unprocessed tail as the transcript content, then the
watermark advances and the row is marked processed (a new row opens if the
chat continues). Chat-list review markers show four states:
**pending** (unprocessed content, nothing processed yet), **partially
processed** (watermark > 0 and new content since), **processed** (watermark
at the end), **excluded**. Exclusion can be applied at any time, including
after partial processing: it stops capture *from the watermark forward* —
messages already processed into memories stay (removing those is a memory-
editor action), and no further messages are ever captured while excluded.
Reversible: flipping back to pending resumes capture from the exclusion
point (the excluded span is not retroactively captured).

**D6 — Companion mapping.** `companions.app_character_id` stores the app's
`personaId` (= `Hash.hash(label)`). Because renaming a persona changes its id
(edit = delete + recreate, a documented invariant), the persona edit path gets
a sync hook that (a) re-points `app_character_id`, (b) appends to
`companion_name_history`, and (c) refreshes `base_personality_mirror_text`.
The store's stable `companion_id` is what survives renames — exactly what the
schema was designed for. Slot 1 of prompt assembly injects the APP's persona
prompt when the link exists (essence is never a second personality — see the
essence-injection guardrail in `enforcer_librarian_spec.md`).

**D7 — The Archivist and the standing-packet compressor are ordinary
chat-completions calls** through the existing `com.aallam.openai` client,
against a **global app setting**: an endpoint profile (from
`ApiEndpointPreferences`) + model name that the user picks in Archivist
settings. Works with z.ai today, any OpenAI-compatible endpoint (including a
future local server) tomorrow. Manual trigger only, per spec. The response
must be the single JSON object from `archivist_prompt.md`; the enforcer
validates every operation against the schema and autonomy dials before
applying — invalid or over-permissioned ops are dropped to the run report,
never silently mutated.

**D8 — Where "active world / roleplay character / user persona" live.**
Per-chat state through `Preferences` (added to the auto-naming copy block),
mirrored into the `app_state` table at generation time so the enforcer and
Archivist read one place. Prefs are the source of truth; `app_state` is
derived.

**D9 — Encrypting the existing stores (owner-requested expansion).**
- `lorebook.db`: migrate the existing plaintext SQLite file to SQLCipher
  once, via `sqlcipher_export()` into a new encrypted file, verify row
  counts, then atomically swap and delete the plaintext file. On any
  failure: keep the plaintext DB and retry next launch — never lose data to
  a half-finished migration (same spirit as the `ChatPreferences`
  parse-failure invariant).
- Chat history + per-chat settings (`ChatPreferences`, `Preferences`):
  migrate to `EncryptedSharedPreferences` (security-crypto is already a
  dependency), file-per-chat layout unchanged, with a one-time copy
  migration and the same keep-plaintext-on-failure rule. The corrupt-data
  preservation path in `ChatPreferences` must survive the move untouched.
- SQLCipher dependency: `net.zetetic:sqlcipher-android` (Maven Central) +
  `androidx.sqlite:sqlite`. arm64-only APK is fine (app is arm64-only).

**D10 — Android ⇄ Windows sync (future; design for it now, build later).**
The bridge is the **schema-shaped JSON export**, never the database file
(SQLCipher files are device-keyed; the export is the portable artifact — this
is already the spec's position). Decisions that keep the door open, binding
on every phase:
- Embeddings are per-device and never exported. Windows using a
  higher-quality embedding model is already supported by the model-keyed
  sidecar (D3): each device embeds the shared memories with its own model.
- Every mutable record carries `updated_at` (schema already does); writes
  must always set it. Deletions of synced record types must leave a
  tombstone from Phase 1 on (a `deleted_ids` table: record type, id,
  deleted_at) so a future merge can distinguish "deleted here" from "never
  had it". Cheap now, impossible to retrofit later.
- Exports include transcripts and chat history (chats are prefs, not the
  store — the exporter serializes them alongside, in a documented envelope:
  `{schema export} + {app_chats: [...]}`), so "chats & memories" both travel.
- Phase 8 builds the actual sync: rotating exports written to a
  user-chosen folder via Storage Access Framework (a Google Drive folder
  works through SAF without any Drive API dependency), import-with-merge on
  the other side (per old roadmap Phase 8 rules: missing → add, newer
  `updated_at` → offer update, both-changed → surface conflict, never
  silently overwrite). Live/continuous sync only after file-based sync is
  boringly reliable.

## Phases

Each phase = one branch/PR, green in CI, visible result on the phone. Order
follows the package README's build order (storage → librarian → enforcer →
editor UI → Archivist → proposals UI) with transcript capture pulled forward
so raw material accumulates while the rest is built.

Execution notes for whoever builds a phase: read the spec docs cited in the
phase; follow CLAUDE.md's coding rules (style, strings, singletons,
confirm-dialogs, `finally`-restored UI state); statically verify every `R.*`
reference and call-site before pushing; update this file's checkbox + a
one-line landing note, and CLAUDE.md's feature/storage sections, in the same
PR that lands the phase.

### ☑ Phase 0 — Repo hygiene (landed with this plan)
- `.gitignore`: `example_seed*.json`, `*.db`, `memory-export*.json`,
  `transcripts-export*` — per the package README's "never commit a personal
  seed / database / export / transcript" rule. (`example_seed.json` itself
  was already removed by the owner; history purge only on owner request.)

### ☑ Phase 1 — Storage: `MemoryStore` + migrations + seed + export
**Landed July 2026.** What shipped vs. the outline below, so later phases
build on what exists rather than what was planned: the store is
`preferences/memory/` (MemoryStore + MemorySeedCodec + MemoryData +
DatabaseKeys + MemoryCompanionSync + MemoryExporter); the entry point is
a **"Memory system" tile in the Characters hub** (next to Lorebooks — the
main Settings grid needed constraint-chain surgery, Characters didn't), which
opens `MemorySettingsActivity`. The rotating automatic export runs from
`MainApplication` at app start (background thread, 24h throttle via meta,
keeps 5 in `getExternalFilesDir/memory_backups`) instead of adding a
WorkManager dependency — Phase 8 moves it to a user-chosen SAF folder.
Singletons (owner profile / archivist settings / retrieval policy) import
only on the FIRST seed import (`meta.seed_imported_at`). Schema deviations
from the table plan are documented in MemoryStore's header (draft status,
transcripts.chat_id + user_persona_id, nullable transcript companion_id,
deleted_ids tombstones). Unit tests cover the codec (template parse,
lossless round-trip, export envelope); DB-level behavior is verified
on-device — SQLCipher can't run in plain JVM tests. CI gotcha for future
phases: sqlcipher-android's POM declares `androidx.sqlite` at runtime scope
only, so it must stay an explicit `implementation` dependency or nothing
touching the zetetic classes compiles.
Specs: `sqlite_table_plan.md`, `companion_memory_schema.json`,
`seed_public_template.json`, app_adaptation_notes §Bootstrap, §Data care.
- SQLCipher dependency (D9 note) + key management (D2).
- `preferences/memory/MemoryStore.kt`: WAL, `PRAGMA foreign_keys=ON`,
  launch-time `PRAGMA integrity_check` surfaced loudly on failure (a
  Material dialog on next activity, not a silent log), full DDL from the
  table plan **plus** the `deleted_ids` tombstone table (D10), migration
  runner keyed on `meta.db_migration`.
- Seed import: parse a schema-shaped JSON file into the tables (ship
  `seed_public_template.json` as the bundled default seed). Draft
  companions arrive as drafts.
- Export: walk tables back to schema shape + the `app_chats` envelope (D10;
  embeddings never exported; transcripts included). One-tap manual export
  (SAF document picker) + rotating automatic exports (WorkManager, keep
  last N, default 5). Import = the new-phone flow; sets a
  "rebuild index needed" flag for Phase 3 to honor.
- Bootstrap migration (idempotent, runs at first tier-2 enable): create an
  **active** companion record for every existing app persona
  (`companion_id` generated, `app_character_id` set, mirror synced).
- Persona-edit sync hook (D6) in the persona save path.
- Unit tests (`app/src/test`, Robolectric if needed for SQLCipher — else
  gate DB tests to androidTest and unit-test the JSON mapping pure-Kotlin):
  migrations, seed round-trip (import → export → import), bootstrap mapping,
  tombstone writes.
- Settings entry: a "Memory (experimental)" screen showing store status,
  seed import, export now, auto-export toggle, and the companion records.
- **Visible result:** import the template seed, see companions/memories in
  the status screen, export a JSON and open it in a file manager.

### ☑ Phase 1b — Encrypt the existing stores (owner-requested)
**Landed July 2026.** What shipped: `preferences/SecurePrefs.kt` wraps
`EncryptedSharedPreferences` files (`enc.<name>`) for `chat_list`,
`chat_<id>` and `settings.<id>`, with an idempotent copy → verify → clear
migration on first access and a loud plaintext fallback if the Keystore is
unavailable (chats LOOK missing, never ARE lost). ALL direct
`getSharedPreferences` call sites for those names were rerouted
(ChatPreferences, Preferences, ChatActivity.saveSettings, MainActivity,
ChatsListFragment) — future code must go through SecurePrefs or data splits.
`lorebook.db` converted to SQLCipher: `LoreBookEncryption.obtainPassword`
runs the one-time in-place migration (sqlcipher_export → integrity +
row-count + version verify → swap with the original kept aside), returns an
empty password (= keep operating plaintext, retry next start) on any
failure, and only throws when the DB is already encrypted but its key is
unreadable. `MemoryDatabaseKey` generalized to `DatabaseKeys` (one key per
database; the memory key keeps its legacy pref name so existing installs
keep working). ChatActivity's two lorebook call sites are try/catch-guarded
to degrade to no-lore rather than crash. The global `settings` file and
other non-chat prefs stay plaintext (out of scope). No JVM tests —
Keystore/SQLCipher paths verify on-device.

### ☑ Phase 2 — Transcript capture, review markers, kill switch
**Landed July 2026.** What shipped: capture hooks into the `finally` of
`generateResponse` — the one place every turn (typed or voice, success,
cancel or error) passes exactly once with the user's message in scope —
via `ChatActivity.recordTranscriptTurn` → `TranscriptRecorder` (policy) →
`MemoryStore.appendTranscriptTurn` (a chat's newest unprocessed row is
"open"; a model or companion change, or the 200k-char cap, starts a new row
so each row's model_tag/companion_id stay truthful). Content is a JSON
array of {role, content, at} turns. Semantics as decided: user exclusion
("Don't archive this chat", Quick Settings) stops capture entirely and
flips the chat's queued rows; the memory kill switch ("Use memory in this
chat", Quick Settings; global default in Characters → Memory system) keeps
capturing but marks rows excluded, so an experiment can be recovered by
re-including; companions with memory_participation='none' capture
pre-excluded. Both per-chat prefs are in the auto-naming copy block, and
chat renames (auto-naming + manual `ChatPreferences.editChat`) re-point
transcripts.chat_id. Chat-list rows show the review marker (waiting /
partially archived / archived / excluded) fed by
`MemoryStore.chatReviewStates()`, loaded off-thread in ChatListAdapter.
Enforcer-side injection skipping for the kill switch is Phase 4 (there is
nothing to inject yet). Note for Phase 6: the Archivist consumes pending
rows per-row (no separate watermark — a row IS the unprocessed unit;
"partially processed" = chat has both processed and pending rows).

### ☑ Phase 3 — Librarian: embedding model manager + index
**Landed July 2026.** What shipped, in `preferences/memory/librarian/`:
`EmbeddingModel` interface (D3 — nothing outside the package depends on a
concrete model); `EmbeddingModels` catalog of EmbeddingGemma-300M ONNX
variants (**q4 is the default**, int8 optional — owner decision; 256-dim
Matryoshka; prompt prefixes, pooling and token budget are per-model catalog
fields so a future BGE-M3 is a catalog entry, not code) +
`EmbeddingModelStorage` (a dir per model = transformer + tokenizer.json) +
`EmbeddingModelDownloader` (two-file download, Whisper pattern);
`OnnxEmbeddingModel` runs the transformer via ONNX Runtime, tokenized by
**`HfTokenizer` — a pure-Kotlin encoder for the repo's own `tokenizer.json`**
(BPE with byte-fallback for the Gemma family, Unigram for XLM-R/BGE-M3;
Gson-streamed parse since Gemma's file is ~33 MB; unit-tested with synthetic
fixtures). The earlier ONNX-Runtime-Extensions tokenizer-graph approach was
DROPPED (July 2026): the required `tokenizer.onnx` doesn't exist upstream
and generating+bundling one would make the app (and its auto-published
GitHub releases) redistribute a Gemma-licensed artifact — owner decided
against that. Downloading the real tokenizer.json at runtime keeps the
distribution clean; do not reintroduce a bundled tokenizer or the
extensions dependency. `VectorMath` (cosine, L2, float32
BLOB codec) and `Librarian` (brute-force cosine top-k, scope filters in the
SQL query, score = w_sim·cos + w_imp·imp/5 + w_rec·recency with
retrieval_policy weights, tentative×0.6 dampening, keyword fallback,
`rebuildIndex`, model-tag-mismatch detection). MemoryStore gained the
embeddings CRUD, scope-isolated `activeMemoriesForScope`, and the archive-rule
helpers. UI: a "Librarian" section in Memory settings (download/remove model
rows with progress, Rebuild index button + status, debug search box).
Unit-tested pure-Kotlin core (`VectorMath`, `Librarian.rank`, `HfTokenizer`).
Lore-entry embedding for Phase 4 near-dup suppression is deferred to Phase 4
(nothing consumes it yet).

**Wrong-tokenization safety net** (layered, so bad embeddings can never
silently poison the index): unknown tokenizer.json constructs throw at load;
`OnnxEmbeddingModel.create` sanity-checks basic encodes (non-empty,
in-range, distinct); and `Librarian.ensureModel` runs a one-time **semantic
self-check** per installed model — embed two related and one unrelated
sentence and require the obvious cosine ordering with margin, plus
non-degenerate finite vectors. Pass writes a `.selfcheck_ok` marker in the
model dir (cleared when a download starts, gone when the model is deleted);
fail logs the reason to MemoryLog, disables the model, and retrieval stays
on keyword fallback.

⚠ **ON-DEVICE VALIDATION PENDING (the flagged risk):** huggingface.co is
blocked in the build sandbox, so the download URLs, the real ONNX graph's
tensor I/O names, and `HfTokenizer` against the real 262k-entry Gemma
tokenizer.json could not be exercised — only synthetic fixtures ran. On the
Pixel: download the q4 model from Memory settings, watch MemoryLog for the
"Embedding self-check passed" line (a failure line means tokenizer or graph
mismatch — fix from the logged reason), then Rebuild index and try the debug
search box for a semantic (not keyword) hit. Everything compiles and the
retrieval/scoring/storage half is solid; the self-check verdict on-device is
the remaining acceptance gate.

Two on-device findings already folded back in (July 2026): the real Gemma
tokenizer.json uses a Split pre-tokenizer with behavior MergedWithPrevious
(all five SplitDelimiterBehavior variants are now implemented + tested), and
the q4 export keeps its weights in ONNX **external data**
(`model_q4.onnx_data`, referenced by that exact name from inside the graph
— catalog `ExtraFile`, required for q4, optional/unverified for int8). The
downloader now skips already-complete files and keeps what landed on
failure, so a partial install shows "not installed" and re-downloading only
fetches the missing pieces.

### ☐ Phase 4 — Enforcer: tiers + prompt assembly
Specs: `prompt_assembly_template.md` (the literal skeleton — follow it
verbatim, including the assembly rules section), `enforcer_librarian_spec.md`
(turn loop, mode detection with stickiness + protective tie-break, lore-book
coexistence, essence guardrail, failure behavior); D7 (compressor), D8.
⚠ Touches `ChatActivity`'s generation path — strong model, smallest diff.
- **Memory engine** setting: none / lore books / full system. Full requires
  an installed embedding model.
- Per-turn assembly in `regularGPTResponse` after the stable first system
  message: the template's slots 1–6 rendered exactly as specified; retrieved
  memories with provenance markers; protected memories structurally
  inseparable from handling (one render function, no separate code path);
  lore entries in their own labeled slot before retrieved memories, with
  near-duplicate suppression and contradiction flagging for the next run
  report; activation prompt last, verbatim.
- Standing-packet compressor: rendered via the Archivist-model setting,
  cached, invalidated when any component changes; raw records as fallback
  if the compressor call fails.
- Mode detection per spec (signal embeddings at index time, per-turn scoring,
  ≤2 modes, stickiness for protective modes, suggested_mode activation).
- Budgets from `retrieval_policy`, cut lowest-scored retrieved memories
  first, never hard limits / standing packet / handling.
- Failure behavior: librarian down → keyword fallback; store unreadable →
  app materials alone + one soft notification; never block generation.
- Quick-settings additions (tier 2): active world / roleplay character /
  user persona selectors, nullable (D8; copy block!).
- Extend the lorebook debug injection view to show the full assembly:
  what was injected, why, scores, what was cut and why.
- **Visible result:** with tier 2 on, a companion demonstrably knows seeded
  facts; the debug view shows the exact assembled packet.

### ☐ Phase 5 — Memory editor + companions/worlds/personas UI
Specs: app_adaptation_notes §Tab structure, §Worlds UI, §Characters area,
§New areas, §Required memory UI; `enforcer_librarian_spec.md` §Manual
authority.
- Memory browser/editor: search (librarian + text), view, add, edit,
  protect/unprotect (handling + never_assume editors), archive, delete
  (user-only, Material confirm), change-log view per memory.
- Characters area: "Personas" renamed **"Companions"** in UI strings;
  companion page gains draft badge + approve action, `memory_participation`
  selector, essence/hard-limits editor (user edits direct; Archivist is
  proposal-bound), model-adaptations list.
- New areas: **My Personas** (user personas), **Roleplay Characters**
  (definition user-editable, arc read-only), **Worlds** (list → world page:
  premise/rules, characters, world-scoped memory browser, teardown —
  archive-all or delete-all, one action, confirm dialog, "keep character
  memories" option per the table plan).
- Entity browser (living summaries), owner-profile editor, directives and
  modes editors (user-editable).
- **Visible result:** the whole store inspectable and editable by hand —
  every troubleshooting_guide workflow has a place to happen.

### ☐ Phase 6 — Archivist pipeline + proposals + run reports
Specs: `archivist_spec.md`, `archivist_prompt.md` (send verbatim with
injections), `enforcer_librarian_spec.md` §Applying change-sets; D7.
- Archivist settings screen (global): endpoint profile + model, autonomy
  dials, harvest generosity, run trigger (manual per spec).
- "Process conversations" button with pending count. Run: build inputs
  (pending transcript tails chronological, store working set, settings),
  call the model, parse the single JSON object, validate each op against
  schema + dials (drop/downgrade to report), apply in order with
  `change_log` prior-state snapshots, maintain the index, advance
  watermarks, set processed/partially-processed states.
- Run report screen: the report text + every auto-applied change with
  **one-tap undo** (restore snapshot, log 'reverted', fix index).
  Tentative-memory quarantine: never always_load, dampened retrieval,
  one-action reject, rejections remembered.
- Proposals screen: pending proposals, accept/reject; accepted ops applied
  through the same validation path. Personality proposals presented as
  exact text for the user to paste into the app persona (mirror updates on
  next sync).
- Emergence: `create_world` / `create_roleplay_character` ops back-tag
  fiction memories; records appear in tabs and quick-settings selectors.
- Imported-transcript backfill: import old conversations as
  `source='imported'` transcripts queued like any others.
- **Visible result:** the full loop — talk, run the Archivist, read the run
  report, watch memories appear, undo one, reject a pattern, accept a
  proposal.

### ☐ Phase 7 — Hardening + docs
- End-to-end pass over `troubleshooting_guide.md`: every symptom's "where to
  look / how to fix" must actually exist in the UI.
- Failure-mode sweep: mid-conversation degradation (store locked, model
  missing, embedding load failure) never blocks generation — tier drops to
  lore-books-only for that turn with an Event-log line.
- CLAUDE.md fully updated: storage section (new DB + encrypted stores),
  feature list, fragile list (enforcer assembly joins the
  "system-message assembly" do-not-reorder warning).

### ☐ Phase 8 — Android ⇄ Windows sync (file-based, later)
Specs: D10; old roadmap Phase 8 merge rules.
- Rotating exports to a user-chosen SAF folder (Google Drive folder works
  with zero Drive-API dependency); import-with-merge using `updated_at` +
  tombstones; conflicts surfaced, never silently overwritten.
- Windows-side is a separate future project reading the same export format
  with its own (higher-quality) embedding model — nothing to build here
  beyond keeping the export format documented and stable.

## Sizing and delegation guidance

Phases 1, 1b, 4 are fragile-or-foundational (encryption, migrations, the
generation funnel): strong model, small diffs, no incidental refactors.
Phases 2, 3 are medium and well-specified. Phases 5, 6 are large but mostly
additive new screens — parallelizable per screen by cheaper (Sonnet-class)
agents against this plan + the cited specs, with one reviewer pass after.
