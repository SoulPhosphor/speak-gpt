# Companion Memory System — Integration Plan

Status: **proposed, awaiting owner answers** (see "Open questions" at the bottom).
Written July 2026 against the design package in `Memory System/` (schema v1.11)
and the current codebase. This plan supersedes the phase list in
`Memory System Plans June 1 2026` from Phase 2 onward — Phase 1 of that older
roadmap (the lorebook) is built and stays as the low-RAM tier, exactly as the
new design intends. Where this plan and the spec documents disagree, the spec
documents win; where the spec is silent, this plan records the code-level
decision so future agents don't re-litigate it.

## What we're building, in one paragraph

A second, encrypted SQLite store (`companion_memory.db`) that holds the
companion memory system from `Memory System/sqlite_table_plan.md`: companions
(mapped 1:1 to the app's existing personas), global and companion-scoped prose
memories with protection/provenance, entities, modes, directives, roleplay
worlds/characters, user personas, transcripts, proposals, and a change log.
Around it: a **librarian** (on-device EmbeddingGemma embeddings + brute-force
cosine retrieval), an **enforcer** (per-turn prompt assembly + change-set
validation, living behind the existing `generateResponse` →
`regularGPTResponse` funnel), and an **Archivist** (an LLM run manually by the
user that reviews pending transcripts and maintains the store under autonomy
dials, with one-tap undo). Lore books remain untouched as the lightweight tier
and outrank system memories at injection.

## Ground rules carried over from CLAUDE.md (they all still apply)

- CI is the compile gate. Every phase below ends in a pushed, green,
  user-testable state. No phase may leave `main`-bound code half-wired.
- All cross-cutting request changes go through the single funnel in
  `ChatActivity` (`generateResponse` → `regularGPTResponse`). The memory
  system's injection joins the existing lorebook block there (~line 4060);
  it must NOT reorder or merge the stable first system message (prefix
  caching) — retrieved memories go in separate system messages after it,
  like lorebook matches do today.
- Any new per-chat preference must be added to the auto-naming copy block in
  `ChatActivity`, or it silently vanishes when a chat is auto-renamed.
- New strings in `res/values/strings.xml` only; Views/XML UI matching current
  style (the UI redesign is a later, separate effort — build these screens as
  plain functional Material 3 Views screens; keep feature logic out of
  layouts/adapters so the redesign can restyle them later).
- Voice pipeline untouched. Nothing here touches `stt/` except *reusing* the
  Whisper downloader pattern for the embedding model.

## Key architecture decisions (made here, from the code)

**D1 — Separate database, not an extension of `lorebook.db`.**
CLAUDE.md previously assumed vector memory would extend `lorebook.db`; the
v1.11 package supersedes that with its own table plan and mandates SQLCipher.
Mixing an encrypted and unencrypted schema in one file isn't possible, and the
lorebook is deliberately the independent low-RAM tier. So: new file
`companion_memory.db` (SQLCipher), new `preferences/memory/MemoryStore.kt`
singleton (`getInstance(context)` with `applicationContext`, same as
`LoreBookStore`), `meta.db_migration` numbered migrations per the table plan.
`lorebook.db` stays exactly as it is. CLAUDE.md gets updated to say so.

**D2 — Encryption key management.** SQLCipher passphrase is a random 32-byte
key generated on first use, wrapped by an Android Keystore AES key, stored in
`EncryptedPreferences` (the existing androidx security-crypto wrapper). No
user-facing password (losing it would orphan the store; the JSON export is the
recovery path, and exports are plain JSON by design — the user's own encrypted
backups are their responsibility, per the README).

**D3 — Embedding runtime: ONNX Runtime.** `onnxruntime-android:1.20.0` is
already a dependency (Silero VAD). EmbeddingGemma-308M has official ONNX
exports in several quantizations; we offer them as downloadable variants
through a model manager cloned from the `LocalWhisperModels` /
`LocalWhisperDownloader` / `LocalWhisperStorage` pattern (Hugging Face URLs,
user picks what fits, removable). Tier 2 requires one installed. No new native
code; if ORT can't load the model on a device, tier 2 refuses to enable with a
plain-words message (mirroring Silero's graceful fallback precedent).

**D4 — Vector search is brute-force cosine in Kotlin.** Read active vectors,
score in memory. The table plan itself recommends this below ~50k memories;
zero new dependencies. The `embeddings` table shape supports moving to
sqlite-vec later without schema change.

**D5 — Chats vs. "conversations": transcripts use a watermark.** The spec
assumes discrete conversations that end; the app has persistent, resumable
chats. Resolution: each chat maps to at most one *open* transcript row.
A per-chat message-index watermark records how far the Archivist has
processed. When the user runs the Archivist, each chat with unprocessed
messages contributes its unprocessed tail as the transcript content, then the
watermark advances and the row is marked processed (a new row opens if the
chat continues). Exclusion and the kill switch mark the chat so its tail is
never captured. *(Open question Q7 — owner may prefer different semantics.)*

**D6 — Companion mapping.** `companions.app_character_id` stores the app's
`personaId` (= `Hash.hash(label)`). Because renaming a persona changes its id
(edit = delete + recreate, a documented invariant), the persona edit path gets
a sync hook that (a) re-points `app_character_id`, (b) appends to
`companion_name_history`, and (c) refreshes `base_personality_mirror_text`.
This makes the store's stable `companion_id` the thing that survives renames —
which is exactly what the schema was designed for.

**D7 — The Archivist and the compressor are ordinary chat-completions calls**
through the existing `com.aallam.openai` client against an endpoint profile +
model the user picks in Archivist settings (reusing `ApiEndpointPreferences`).
Manual trigger only, per spec. Response must be the single JSON object from
`archivist_prompt.md`; the enforcer validates every operation against the
schema and autonomy dials before applying — invalid or over-permissioned ops
are dropped to the run report, never silently mutated.

**D8 — Where "active world / roleplay character / user persona" live.**
Per-chat state through `Preferences` (added to the auto-naming copy block),
mirrored into the `app_state` table at generation time so the enforcer and
Archivist read one place. Prefs are the source of truth; `app_state` is
derived.

## Phases

Each phase is a separate branch/PR, ends green in CI, and produces something
the owner can see working on the phone. Order follows the README's build
order (storage → librarian → enforcer → editor UI → Archivist → proposals UI)
with one deliberate change: transcript capture is pulled forward to Phase 2 so
raw material accumulates while the rest is being built.

### Phase 0 — Repo hygiene (tiny, do first)
- Add to `.gitignore`: `example_seed*.json`, `*.db`, memory exports and
  transcript files, any personal seed patterns — per the README's "NEVER
  commit a personal seed" rule.
- Remove `Memory System/example_seed.json` from the repo **pending owner
  confirmation (Q2)** — it reads as derived from real life (protected family
  memory, PTSD context, the real companion Slate). `seed_public_template.json`
  stays; it is the only seed that belongs in the repo.
- Move the design docs into `design/` (README's recommendation) or leave in
  `Memory System/` — cosmetic either way; default: leave, just fix hygiene.
- Chase the two missing spec documents (Q1). Phases 4 and 6 have soft
  dependencies on them.

### Phase 1 — Storage: `MemoryStore` + migrations + seed + export
- `MemoryStore.kt` (SQLCipher, WAL, `PRAGMA foreign_keys=ON`, launch-time
  `PRAGMA integrity_check` surfaced loudly on failure), full DDL from
  `sqlite_table_plan.md`, migration runner keyed on `meta.db_migration`.
- SQLCipher dependency (`net.zetetic:sqlcipher-android`), key management per D2.
- Seed import: parse a schema-shaped JSON file (ship
  `seed_public_template.json` as the bundled default) into the tables.
  Draft companions arrive as drafts.
- Export: walk tables back to schema shape (embeddings never exported;
  transcripts included per adaptation note 11c). One-tap manual export to
  user-accessible storage + rotating automatic exports (WorkManager, keep
  last N). Import = the new-phone flow; sets a "rebuild index needed" flag.
- Bootstrap migration: on first tier-2 enable, create an **active** companion
  record for every existing app persona (generate `companion_id`, set
  `app_character_id`, sync `base_personality_mirror`).
- Persona-edit sync hook (D6) in `PersonaPreferences` call sites.
- Unit tests in `app/src/test` for migrations, seed round-trip
  (import → export → import), and the bootstrap mapping.
- **Visible result:** a hidden-ish "Memory (experimental)" settings entry
  showing store status, seed import, export now, and the companion records
  created from personas.

### Phase 2 — Transcript capture, review markers, kill switch
- Capture per D5: hook at the end of the `generateResponse` funnel (single
  path, both voice and typed) appending the turn to the chat's open
  transcript row with `companion_id`, `model_tag`, quick-settings snapshot,
  timestamps, and world/rp/persona ids when set (null fine).
- Chat-list marker for `review_status` (pending / processed / excluded) and
  an exclude toggle on each chat (reversible; excluded ≠ deleted).
- **Memory off** kill switch: per-chat quick-settings toggle + global
  default. Off ⇒ nothing injected from the store *and* the transcript is
  auto-marked excluded. Per-chat toggle joins the auto-naming copy block.
- Per-companion `memory_participation` (full / global-only / none) surfaced
  later in Phase 5's companion page; stored from day one.
- **Visible result:** markers in the chat list; transcripts visibly queuing
  in the Phase 1 status screen.

### Phase 3 — Librarian: embedding model manager + index
- Embedding model manager cloned from the Whisper pattern (D3): variant list,
  download/remove, active-model tag.
- `Librarian.kt`: embed text → vector; store in `embeddings` keyed
  (memory_id, embedding_model); cosine top-k over active memories with scope
  filters **in the query** (status='active', world isolation, companion
  isolation) — isolation is enforced in queries, not convention.
- The archive rule: status leaving 'active' deletes embedding rows;
  reactivation re-embeds.
- **Rebuild memory index** settings button; same routine runs at first
  tier-2 enable, after import, and automatically when the installed model
  tag differs from stored vectors' tags.
- Unit tests for cosine ranking, scope filtering, archive rule.
- **Visible result:** a debug search box in the memory area — type a phrase,
  see ranked memories with scores.

### Phase 4 — Enforcer: tiers + prompt assembly
- **Memory engine** setting: `none` / `lore books` / `full system`. Tier
  gate: full requires an installed embedding model.
- Per-turn assembly in `regularGPTResponse`, after the stable first system
  message and alongside the existing lorebook block: standing packet
  (directives, owner profile, always_load memories, active companion essence +
  hard limits + relationship notes + model adaptations for the serving model,
  modes), then retrieved memories for the latest user message, then lorebook
  matches — user-authored lore book entries outrank system memories, and
  protected memories always travel with their handling + never_assume lists
  (structural, not best-effort: one object, rendered together or not at all).
- Injection budgets analogous to the lorebook's (entries + chars), plus the
  debug injection log extended to show memory-system injections (which, why,
  scores, what was skipped and why).
- Quick-settings additions (tier 2 only): active world / roleplay character /
  user persona selectors, all nullable (D8; copy block!).
- **Soft dependency:** the exact packet skeleton and the standing-packet
  compressor live in the missing `prompt_assembly_template.md` (Q1). Until it
  arrives, assemble conservatively from the schema + archivist spec and mark
  the assembly function with a TODO-spec comment; retrofit verbatim once the
  doc lands.
- **Visible result:** with tier 2 on, a companion demonstrably knows seeded
  facts; debug view shows exactly what was injected.

### Phase 5 — Memory editor + companions/worlds/personas UI
- Memory browser/editor: search (librarian-backed + plain text), view, add,
  edit, protect/unprotect (editing handling + never_assume), archive, delete
  (delete = user-only, Material confirm dialog), change-log view per memory.
- Characters area: Personas renamed **Companions** in UI strings (Q4);
  companion page gains draft badge + approve action, `memory_participation`
  selector, essence/hard-limits editor (user edits are direct; only the
  Archivist is proposal-bound), model-adaptations list.
- New areas: **My Personas** (user personas), **Roleplay Characters**
  (definition user-editable, arc read-only), **Worlds** (list → world page:
  premise/rules editable, characters, world-scoped memory browser, teardown —
  archive-all or delete-all, one action, confirm dialog, "keep character
  memories" option per the table plan).
- Entity browser (living summaries), owner-profile editor, directives and
  modes viewers (user-editable; Archivist changes remain proposal-only).
- **Visible result:** the whole store is inspectable and editable by hand —
  the troubleshooting guide's workflows all have a place to happen.

### Phase 6 — Archivist pipeline + proposals + run reports
- Archivist settings screen: endpoint profile + model (D7), autonomy dials
  (`archivist_settings.autonomy_json`), harvest generosity, run trigger
  (manual per spec).
- "Process conversations" button with pending count. Run: build inputs
  (pending transcripts chronological; working set of the store;
  `archivist_prompt.md` verbatim with injections), call the model, parse the
  single JSON object, validate each operation against schema + dials
  (drop/downgrade invalid ones to the report), apply in order with
  `change_log` prior-state snapshots, advance watermarks, mark processed.
- Run report screen: the Archivist's plain-language report + every
  auto-applied change listed with **one-tap undo** (restore snapshot, log
  'reverted', re-embed if needed). Tentative-memory quarantine: never
  always_load, reduced retrieval weight, each one rejectable in one action,
  rejections remembered.
- Proposals screen: pending proposals with summaries, accept/reject;
  accepted ops applied by the enforcer with the same validation.
- Emergence support: `create_world` / `create_roleplay_character` ops
  back-tag fiction memories; records appear in the tabs and quick-settings
  selectors even though the user never created them.
- Imported-transcript backfill: import old conversations as
  `source='imported'` transcripts queued like any others.
- **Soft dependency:** the enforcer-side validation details are partly in the
  missing `enforcer_librarian_spec.md` (Q1); the archivist spec + prompt +
  table plan cover most of it, but the missing doc is authoritative.
- **Visible result:** the full loop — talk, run the Archivist, read the run
  report, watch memories appear, undo one, reject a pattern, accept a
  proposal.

### Phase 7 — Hardening + docs
- End-to-end pass over the troubleshooting guide: every symptom's "where to
  look / how to fix" must actually exist in the UI.
- Failure-mode sweep: mid-conversation degradation (store locked, model
  missing, embedding load failure) never blocks generation — tier drops to
  lore-books-only for that turn with an event-log line.
- CLAUDE.md updated: storage section (new DB), feature list, fragile list
  (enforcer assembly joins the "system-message assembly" do-not-reorder
  warning), this plan referenced from the roadmap section.

## Sizing and agent use

Phases 1–2 and 3 are medium; Phase 4 touches `ChatActivity`'s fragile
generation path and should be done carefully by a strong model (Opus-class)
with the smallest possible diff; Phases 5–6 are large but mostly additive new
screens (Sonnet-class parallelizable per screen, one reviewer pass after).
No agents were needed to produce this plan.

## Open questions for the owner (blocking marked ⛔, the rest have defaults)

- **Q1 ⛔ (Phases 4 & 6):** `enforcer_librarian_spec.md` (doc 7) and
  `prompt_assembly_template.md` (doc 10) are listed in the package README but
  are not in the `Memory System/` folder. Please add them. Everything else
  can start without them.
- **Q2 ⛔ (Phase 0):** `example_seed.json` appears to contain personal
  material and per the README should never be committed. Remove and gitignore?
  And should git history be purged, or is deletion going forward enough?
- **Q3:** Archivist model = a picker over your existing endpoint profiles +
  a model name (default: your usual endpoint). OK? (Default: yes.)
- **Q4:** Rename "Personas" → "Companions" in the UI, per the adaptation
  notes' recommendation? (Default: yes.)
- **Q5:** EmbeddingGemma via ONNX Runtime with Hugging Face downloads, like
  Whisper models. OK? (Default: yes.)
- **Q6:** The new store is SQLCipher-encrypted, but existing chat history in
  `ChatPreferences` (and the lorebook) stays plaintext as today — encrypting
  those is out of scope here. Acceptable? (Default: yes.)
- **Q7:** Transcript semantics for persistent chats (D5): the Archivist
  processes each chat's *unprocessed tail* on every run, rather than waiting
  for a chat to "end" (chats here never really end). OK? (Default: yes.)
