# Memory System: Phase 0 Current-Code Audit and Migration Map

**Companion document to:**
- `Memory System/external_memory_analysis_counterplan.md` (Revision 24, 2026-08-04) — the canonical recovery plan.
- `Memory System/memory_retrieval_and_analysis_ui_copy.md` (2026-08-04) — the canonical wording/behavior contract for live Memory Retrieval controls (Use Model-Aware Limits, Maximum Memories Per Response, Maximum Memory Context, Memory Priority, Memory Match Strictness, Current Retrieval Limits, Context Window Override) and the Conversation Amount Per Request chunk choices. Added to `main` after the initial Phase 0 pass; §21-§28 below extend the original audit to cover it. Nothing in §21-§28 implements any of that document's controls — this remains investigation and documentation only.

**Scope:** This report is investigation and documentation only, per the canonical plan's Phase 0 restrictions. It changes no application behavior, database schema, prompt, UI, retrieval, companion-deletion behavior, archiving behavior, or memory logic. It traces the current implementation against the canonical plan's Phase 0 checklist (plan §17) and required work list (plan "Phase 0" section), and records the gap.

**How to read this report:** each numbered item states (1) where the behavior lives in the code, (2) what it currently does, (3) how it differs from the canonical plan, (4) whether it can be neutralized (stopped from affecting prompts/UI/matching/embedding/ranking) without a destructive database migration, (5) the narrow recommended implementation path, and (6) the tests needed to prove the future change is correct. Nothing here is a product decision — where the canonical plan leaves a choice open, this report says so and does not choose.

---

## 0. Current Database Version and Migration Path

- **File:** `app/src/main/java/org/teslasoft/assistant/preferences/memory/MemoryStore.kt`
- **Engine:** SQLCipher (`net.zetetic.database.sqlcipher`), one encrypted database `companion_memory.db`, separate from `lorebook.db`.
- **`DATABASE_VERSION = 20`** (line 64). `SQLiteOpenHelper.onUpgrade` runs 19 additive `if (oldVersion < N)` blocks (lines 824–1430-ish), one per version 2 through 20. No block drops or destructively rewrites the `memories`, `companions`, or `transcripts` tables after their initial shape stabilized; two blocks (v4, v7) recreate the `memories` and `worlds` tables to *relax* a `CHECK` constraint (SQLite cannot loosen a `CHECK` with `ALTER`), copying all rows across — additive in effect, not destructive.
- A **separate** version concept, `meta.schema_version`, mirrors the JSON table-plan revision (`Memory System/sqlite_table_plan.md`, currently "1.11.0") and is informational only — exports/diagnostics read it; it does not gate `onUpgrade`. `meta.db_migration` mirrors `DATABASE_VERSION` for the same informational purpose. **A Phase 1 migration must bump `DATABASE_VERSION` again and add one more additive `if (oldVersion < 21)` block** — the existing pattern the app already uses for every prior schema change (Type table, importance range widening, conversation-policy columns, etc. would all land as version 21+ additive blocks).
- Foreign keys: `onConfigure` disables FK enforcement only while migrating an old (`< DATABASE_VERSION`) database, to protect the v4/v7 table-recreate steps from cascading deletes; `onOpen` re-enables enforcement afterward. This mechanism is safe to reuse for a Phase 1 migration that must also recreate `memories` (e.g., to relax `kind` or drop `title NOT NULL`).
- **No destructive migration exists today.** Nothing in Phase 1 is blocked by legacy data loss risk in the migration mechanism itself — the risk is entirely in *what* Phase 1 chooses to do with existing `kind`, `title`, and `importance` values (see §2–§4 below).

---

## 1. Memory Title Usage

**Where it exists:**
- Schema: `memories.title TEXT NOT NULL` (`MemoryStore.kt` line 359, unchanged since `onCreate`).
- Data model: `MemoryRecord.title: String` (`MemoryData.kt` line 412), `RetrievableMemory.title` (line 673), `MemoryComparisonDoc.title` (line 705).
- Archiver prompt: `ArchivistPrompt.kt` line 52 — the model is explicitly asked for `"title": "short human title"` on every proposed memory.
- Archiver parser: `ArchivistResponseParser.kt` line 108, 113 — `title` is read and **required**; a blank title drops the entire candidate memory (`title.isEmpty()` → whole row dropped, not defaulted).
- Embedding: `Librarian`/`RetrievalDocument.semanticDocument(title, content, embeddingText, tags)` — used both for the chat-retrieval index (`Librarian.kt`) and for Possible Match comparison documents (`PossibleMatchFinder.kt` line 92). **Title is embedded text** in both paths.
- Prompt rendering: `PromptAssembler.renderMemoryLine` (`enforcer/PromptAssembler.kt` line 57-60) renders every retrieved memory to the model as `"- (marker) {title}: {content}"` — **title is sent to the model on every turn**.
- UI: `MemoryEditorActivity.kt` — `fieldTitle` is a required, always-visible field (line 77, 143); `MemoryRowAdapter.kt` — Pending cards render `row.title` as `pending_title` (line 272) and the debug/no-match rows also show `row.title` (line 174) as "the strong first line" (adapter doc comment, line 33).
- Export/import: `MemorySeedCodec.kt` reads/writes `title` on every memory row (parse ~line 160-ish region, serialize ~line 534 region) — titles ride every backup verbatim.

**What it currently does:** Title is a first-class, required, always-visible, always-embedded, always-prompt-rendered field. It is generated by the model on every Archivist proposal and cannot be blank.

**How it differs from the canonical plan:** Plan §3.1 is unconditional: "No Associative Memory has a separate title... models do not generate titles; prompts and schemas do not request titles; cards and editors do not display title fields; exact matching does not compare titles; embedding documents do not include titles; retrieval has no title bonus." Every one of those six sub-rules is currently violated.

**Can it be neutralized without a DB migration?** Partially. The `NOT NULL` constraint means the column cannot be *dropped* without a migration, but every *behavior* — prompt request, required-field validation, embedding inclusion, prompt-rendering inclusion, UI display, exact-match comparison — is application code, not schema, and can be stopped without touching the column. A migration is needed only to (a) make the column nullable or give it an inert placeholder default so new memories can be created with no meaningful title, and (b) decide what happens to the ~existing title text already stored (see Recommendation).

**Recommended implementation path (Phase 1/2, not Phase 0):**
1. Migration: relax `title` to nullable (or keep `NOT NULL DEFAULT ''` as inert placeholder — SQLite cannot drop `NOT NULL` without a table rebuild, but can widen a default; a table rebuild is available via the existing v4/v7 pattern if the owner prefers a true nullable column).
2. Stop the Archivist prompt/parser from requesting or requiring `title`.
3. Stop `RetrievalDocument.semanticDocument` and `PossibleMatchFinder` from including title text.
4. Stop `PromptAssembler.renderMemoryLine` from rendering title.
5. Remove the title field from `MemoryEditorActivity` and `MemoryRowAdapter`.
6. Existing stored titles become inert (never displayed, never embedded, never sent) but are not deleted — satisfies plan §3.1's "compatibility baggage only" framing without any data loss.

**Tests needed:** parser test proving a title-less/blank-title candidate is no longer dropped; embedding-document test proving title text does not affect the generated document; prompt-render test proving no title line appears; UI test proving no title field renders in editor or Pending cards; migration test proving existing titled memories open cleanly post-migration with titles inert.

---

## 2. Fixed Memory Types and Legacy "lore"

**Where it exists:**
- Schema: `memories.kind TEXT NOT NULL` (no `CHECK` constraint on `kind` itself — the six-value enforcement is entirely application-side).
- **Three independent hard-coded copies of the same six-value list**, which must all agree today and would all need to change together:
  1. `ArchivistResponseParser.KINDS = setOf("fact", "preference", "event", "status", "instruction", "lore")` (line 41) — gates what the Archivist parser accepts; an unrecognized `kind` **drops the whole candidate memory**, it does not become "No Type."
  2. `MemoryEditorActivity.TYPE_KEYS = listOf("fact", "preference", "event", "status", "instruction", "lore")` (line 126) — the Type dropdown's only options.
  3. `MemoryFilterPanelActivity.kt` line 376 — the same six values, for the Type filter chips in the memory browser.
- Archiver prompt: `ArchivistPrompt.kt` lines 80-86 explicitly documents and requests exactly these six Types, including `"lore: fictional/world/roleplay information."`
- Import/seed: `MemorySeedCodec.kt` round-trips whatever `kind` string is present without validating against the six-value set (parse-time it just reads the string; the six-value gate is enforced only by the Archivist parser and the editor's dropdown, not by the store or the codec).

**What it currently does:** `kind` is a fixed, hard-coded six-value enumeration duplicated in three files that must be kept in sync by hand. `lore` is one of the six values and is the only Type ever assigned to `world`/`campaign`/`rp_character`-scoped memories in practice (the prompt tells the model to use `lore` for those scopes).

**How it differs from the canonical plan:** Plan §5 requires Types to be **user-owned** (a database-backed list the user can add/rename/delete), seeded with **five** starter Types (Fact, Preference, Event, Status, Instruction — no Lore), with **"Lore is not an Associative Memory Type"** stated explicitly (§5.1, and reinforced in the forbidden-claims list §18). The Roleplay tab is derived from *scope* (World/Campaign/RP Character), never from a Type value (§4). Today: no Types table exists; the list is fixed at exactly six including Lore; an AI Type suggestion outside the six values is discarded together with the entire memory rather than degrading to "No Type" (plan §5.2: "an absent or invalid AI Type suggestion becomes No Type rather than causing the supported memory text to be discarded").

**Can it be neutralized without a DB migration?** The *fixed-list* behavior cannot be fully neutralized without a migration, because there is no Types table to migrate to — this is additive schema work, not a behavior flag. However, the **prompt/parser dependency on `lore` as a routing signal** can be examined independently: nothing today reads `kind == "lore"` to make a scope or retrieval decision (confirmed by full-text search — `kind`/`type` is never used as a gate outside the parser's accept-list and the two UI dropdowns). Scope, not kind, already drives Roleplay-vs-General grouping in the code that exists today (see §6). This means the migration is lower-risk than it might appear: retiring `lore` as a Type does not require touching any retrieval or scope logic, only the Type list and what a legacy `kind="lore"` value maps to.

**Recommended implementation path (Phase 1):**
1. Add a `memory_types` table (id, name, `created_at`) seeded once with Fact/Preference/Event/Status/Instruction.
2. Add `memories.type_id` (nullable FK) alongside the existing `kind` column; do not drop `kind` yet.
3. Migrate existing `kind` values: `fact`/`preference`/`event`/`status`/`instruction` → matching seeded Type id; `kind='lore'` → `type_id = NULL` ("No Type"), memory text/scope/targets/tags/lifecycle/timestamps untouched (canonical plan explicitly requires this exact behavior, plan Phase 1 item 4).
4. Update the three hard-coded lists to read from `memory_types` instead of a literal set.
5. Update the Archivist parser to accept any current Type name (or none) and to degrade an unrecognized suggestion to "No Type" rather than dropping the memory.

**Tests needed:** migration test for every legacy `kind` value including `lore` → No Type, preserving all other fields; Type CRUD tests (add/rename/delete leaves memories at No Type, never deletes memories); parser test proving an unrecognized `suggested_type` no longer drops the candidate memory.

---

## 3. Importance Storage and Ranking

**Where it exists:**
- Schema: `memories.importance INTEGER NOT NULL DEFAULT 3` (line 363); `entities.importance INTEGER DEFAULT 3` (unrelated table, same pattern).
- Archiver: `ArchivistPrompt.kt` line 56/88-89 — the model is asked for `"importance": 1-5` with a five-point scale description ("1 low ... 5 critical"), **no 0/neutral value exists**.
- Parser: `ArchivistResponseParser.kt` line 133 — `o.optInt("importance", 3).coerceIn(1, 5)` — clamps to **1..5**, default 3 when absent. The Archivist run also enforces a **minimum-importance floor** (`Archivist.kt` line 414, 545: `prefs.getArchivistMinImportance()`) that silently drops candidates below the configured floor before they ever reach Pending.
- **Ranking:** `enforcer/DefaultOperatingData.kt` line 44 — the default retrieval policy weights are `{"similarity": 0.6, "importance": 0.3, "recency": 0.1}`. `RetrievalPolicy.kt` (`DEFAULT_W_IMP = 0.3`, line 57) bounds/validates these weights but **there is no code path that sets importance's weight to 0**, and the comment at line 32-34 states plainly: "no UI writes these values... the defaults are unchanged." Importance is a mandatory ~30%-weighted ranking input on every retrieval, for every user, with no way to disable it today.
- UI: `MemoryEditorActivity.kt` — `btnImportance` is an always-visible five-step dropdown (`currentImportance: Int = 3` default, line 95).

**What it currently does:** Importance is a mandatory 1–5 rating (default 3), assigned by the AI on every Archivist proposal, editable by the user, and **always** contributes ~30% of the retrieval ranking score for every memory in every chat. There is no "Use Importance Ratings" toggle anywhere in the codebase.

**How it differs from the canonical plan:** Plan §7 requires: range **0 through 5** with **0 = neutral**; a persistent **"Use Importance Ratings" toggle, default Off**; when Off, importance contributes **exactly zero** to ranking while stored values are preserved; the Memory Assistant **does not** assign importance — every proposal starts at 0 (§7.2, explicit prohibition in §18: "the AI must assign importance" is a forbidden claim). Every one of these is currently the opposite of what's built: no 0 value, no toggle, importance is AI-assigned by default, and importance ranking cannot be turned off.

**Can it be neutralized without a DB migration?** The **ranking weight** can be neutralized without a migration — `RetrievalPolicy`/`DefaultOperatingData` are application code and JSON policy data, not schema; a toggle could set the stored `importance` weight to 0 in the `retrieval_policy` row without touching the `memories` table at all. The **range widening (1–5 → 0–5) and the AI-assignment-at-0 behavior** also do not strictly require a migration (`importance INTEGER` already accepts 0; only the parser's `.coerceIn(1, 5)` and the prompt's "1-5" text need to change), but the **existing default value of 3** for all historical AI-authored memories will look identical to a hand-set "notable" rating once 0 becomes the neutral default — this is a data-semantics question, not a schema question (see Unresolved Decisions).

**Recommended implementation path (Phase 1/2):**
1. No migration required for the column itself (`INTEGER` already holds 0); a migration is only needed if the owner wants a fresh `NOT NULL DEFAULT 0` constraint instead of the current `DEFAULT 3` for clarity — cosmetic, optional.
2. Add the `Use Importance Ratings` boolean setting (new `meta` key or `retrieval_policy` field), default Off.
3. When Off: `RetrievalPolicy` treats the importance weight as 0 regardless of what's stored in `retrieval_policy.policy_json`; hide importance from Pending/Review/editor.
4. Stop the Archivist prompt from requesting importance; stop the parser from clamping to 1..5 — accept 0..5, default 0 for new candidates.
5. Leave every existing stored importance value untouched (plan explicitly requires preservation, Phase 1 item 6).

**Tests needed:** toggle On/Off behavior (ranking contribution exactly 0 when Off, values preserved either way); parser accepts 0 and defaults new candidates to 0; existing importance values survive migration unchanged; UI hides/shows importance controls correctly.

---

## 4. Source-Chat / Provenance Fields

**Where it exists:**
- Schema: `memories.provenance_source`, `provenance_confidence`, `provenance_noted_on`, `provenance_context`, `source_chat_id` (all nullable `TEXT`, `MemoryStore.kt` lines 371-374, 385-389).
- `MemoryData.kt` — `MemoryRecord.provenanceSource/Confidence/NotedOn/Context: String?` (lines 429-432) and `sourceChatId: String?` (line 453, documented as "Device-local, not exported").
- Set at file time: `Archivist.kt` lines 926-934 — every Archivist-authored memory gets `provenanceSource = "user_stated"|"inferred"`, `provenanceConfidence`, `provenanceNotedOn = now`, `provenanceContext = conversation.chatName` ("§14: the editor shows which chat a draft came from and when" — comment at line 929), and `sourceChatId = conversation.chatId` used for **rejected-draft dedup** (`store.isDraftRejected(title, content, chatId)`, line 891) so a deleted draft is not silently re-proposed on the next run of the same chat.
- `RetrievableMemory.provenanceConfidence`/`provenanceSource` (`MemoryData.kt` lines 678, 684) ride into the retrieval row and are used by the enforcer for the "told/observed/guessed" provenance marker rendered in every prompt line (`PromptAssembler.PROVENANCE_LEGEND`, line 42, and `renderMemoryLine`'s `m.provenanceMarker`, line 59).
- **Not embedded:** provenance fields are not part of `RetrievalDocument.semanticDocument` — confirmed absent from the embedding-document construction call sites found (`Librarian`, `PossibleMatchFinder`).
- Export: `MemorySeedCodec.kt` round-trips the four `provenance_*` fields (they are part of the exported `MemoryRecord` shape); **`sourceChatId` is explicitly commented "Device-local, not exported"** in `MemoryData.kt` line 453 — needs verification the codec actually omits it (see Unresolved/Verify item below).

**What it currently does:** Every AI-authored memory carries a provenance marker (stated/inferred, certain/tentative) that is rendered to the model on every turn as part of the prompt line, **plus** a human-readable `provenance_context` (the source chat's display name) and a rename-safe `source_chat_id` used only for local rejected-draft deduplication — never shown to the user directly as "this came from chat X," never sent to the model, never embedded.

**How it differs from the canonical plan:** Plan §3.2 forbids "a saved or Pending memory does not remember which chat, request, or chunk produced it" and explicitly lists `provenance_context`-equivalent fields (chat name, source timestamps) as things that must not be attached or exposed. However, the plan's own §8.10 permits "minimal temporary run bookkeeping" including "chat ID and frozen end marker **outside the memory object**" for exactly the purpose the current code uses `sourceChatId` for (recovery/dedup) — the difference is that the plan wants that bookkeeping to live **outside** the saved memory row, in short-lived run state, not as a permanent column on `memories` itself. The **`provenance_source`/`provenance_confidence`/"told/observed/guessed" marker rendered to the model every turn** is not addressed anywhere in the canonical plan's Associative Memory Shape (§3) — it is not one of the explicitly forbidden fields, but it is also not one of the plan's approved fields (content, Type, tags, importance, id, timestamps, lifecycle, minimum placement info). This is a genuine gap the plan does not resolve (see Unresolved Decisions).

**Can it be neutralized without a DB migration?** Yes, mostly. `provenance_context` and `source_chat_id` are not read by retrieval or embedding today — they can be stopped from being **set** on new memories (leaving old values inert) without a migration; the only migration need is if the owner wants the columns physically removed later (Phase 10 territory, not Phase 1). The provenance-marker-in-prompt behavior (`told/observed/guessed`) is pure application code (`PromptAssembler`) and can be changed or removed without any schema change — but doing so is a **product decision** (does the plan's memory shape want a provenance marker at all?), not a Phase 0 action.

**Recommended implementation path:** Flag as an unresolved decision for Phase 1 (below) rather than a determined migration — the plan does not say whether the stated/inferred marker survives. If it is dropped: stop setting `provenance_source`/`confidence` on new memories, stop rendering the marker in `PromptAssembler`, leave old columns inert. If it is kept: no change needed beyond moving `source_chat_id`/`provenance_context` off the permanent memory row and into short-lived run bookkeeping per §8.10, with the existing `rejected_drafts` table (keyed by content hash + chat id, **already** a separate device-local table, not on `memories`) as the template for how that data already correctly lives outside the memory object today.

**Tests needed:** rejected-draft dedup continues to work if `sourceChatId` moves off the memory row; embedding-document test proving provenance fields never entered the document (regression guard); prompt-render test matching whatever the owner decides about the provenance marker.

---

## 5. Transcript Processing Markers and Bookmarks

**Where it exists:**
- Schema: `transcripts` table (`MemoryStore.kt` lines 519-538) — `review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending','processed','excluded'))`, `processed_at`, `claim_run_id` (analysis-run claim seal, added DB v17).
- A chat's history is captured as **one or more transcript rows**, not a single scalar "last reviewed message" bookmark: `TranscriptRecorder.recordTurn` (`TranscriptRecorder.kt`) appends to the current open row per chat; `MemoryStore.MAX_TRANSCRIPT_CHARS = 200_000` (line 97) closes a row and opens a new one once it grows past 200k characters. Each row independently carries its own `review_status`.
- Eligibility for analysis: `Archivist.eligibleConversations` (`Archivist.kt` line 178) queries **all currently-`pending`, unclaimed transcript rows** grouped by `chat_id` — a live query over current state, not a stored per-chat watermark.
- Claim/lock mechanism (DB v17, "counterplan §4(a)" per in-code comments): `archivist_runs.status='running'` **is** the durable "a run is in flight" record; `transcripts.claim_run_id` seals which rows a specific run selected; `MemoryStore.reconcileInterruptedAnalysisRuns()` releases claims from a dead run at the next startup/run so a process death cannot lose or double-count material. This is a materially different mechanism from the plan's "one bookmark plus temporary run state," but appears to satisfy the same safety properties (no advance on failure/cancellation/death — verified: `markTranscriptsProcessed` is only called after a conversation's chunks all succeed, `Archivist.kt` line 567-574).
- `review_status='excluded'`: set by `MemoryStore.setChatTranscriptsExcluded(chatId, excluded)`, called from the "Archive this chat" Quick Settings switch (see §10 below) — this is a **per-chat**, not per-message, exclusion applied at capture time going forward; it is a distinct concept from `review_status='processed'`.

**What it currently does:** Transcript rows are a size-bounded (200k char), per-chat append log with a three-state `review_status` per row, guarded by a claim-based locking scheme for crash/cancellation safety. There is no single scalar "bookmark" column anywhere (no `last_reviewed_message_id` or equivalent) — eligibility is always computed live from row state.

**How it differs from the canonical plan:** Plan §9 and Phase 0 checklist item explicitly ask whether "permanent transcript row processing states" exist "where one bookmark plus temporary run state would suffice" (plan §17). They do exist, in exactly that form — every transcript row permanently carries `review_status`, and that state (not a bookmark) is what analysis eligibility is computed from. The plan's model is: one durable bookmark per **chat**, a frozen end-point captured at run start, and everything else (locks, chunk status, retry safety) as short-lived run bookkeeping (§8.10, §9). The current model does not have a per-chat high-water mark at all; it has durable per-row state that plays the same *safety* role but is architecturally different, and is explicitly named in the plan's checklist as a thing to examine for necessity.

**Can it be neutralized without a DB migration?** No — and this is not simply a matter of stopping a behavior. The row-based queue is *how* analysis selects its input today; there is no parallel bookmark mechanism sitting unused beside it that could be "turned on" instead. Replacing it is itself the architectural change, not a neutralization of an extra field.

**Recommended implementation path:** This is a genuine open architecture question, not a determined migration — recorded in Unresolved Decisions below. Two narrow options either preserve safety:
- **(a) Keep the row-queue, treat it as the "run bookkeeping" the plan permits.** The plan does not forbid the *mechanism*, only "permanent... states where one bookmark... would suffice" — one reading is that the existing `pending`/`processed`/`excluded` states plus claim locking already **are** an implementation of "frozen range" (a batch of currently-pending rows, sealed at claim time) and "short-lived run bookkeeping" (the claim itself), and the only real gap is that eligibility is recomputed live rather than advanced past a bookmark — which is arguably *more* conservative, not less, since it can never skip a row. Under this reading, Phase 1 would add nothing here beyond whatever new columns the chunking/policy rework needs (see §14-§16).
- **(b) Add a true per-chat bookmark** (e.g., `chat_bookmarks(chat_id, last_processed_transcript_id, updated_at)`) that advances only after a full frozen range files successfully, and downgrade `review_status='processed'` to an operational/diagnostic marker rather than the authoritative eligibility gate. This is closer to the plan's literal wording but is a larger, riskier rewrite of working, tested-by-construction crash-recovery logic for arguably the same net behavior.

**Tests needed (once a direction is chosen):** interrupted-run recovery (existing `reconcileInterruptedAnalysisRuns` behavior must not regress); no-skip and no-duplicate guarantees across a captured/claimed/released/reclaimed row; a bookmark-based approach would need its own advance/non-advance tests mirroring the ones already implicitly covered by the claim mechanism.

---

## 6. General, Companion, and Roleplay Scopes and Targets

**Where it exists:**
- Schema: `memories.scope CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character'))` (line 357). **There is no separate `roleplay` scope value** — the schema already matches the canonical plan's core architectural requirement (plan §4: "Do not create a new generic `roleplay` scope").
- Target join tables (source of truth for ownership, per in-code doc comment lines 406-415): `memory_companions`, `memory_worlds`, `memory_campaigns`, `memory_roleplay_characters`, `memory_projects` — all many-to-many. The single mirror columns on `memories` (`world_id`, `roleplay_character_id`, `campaign_id`, `project_id`) are documented as "primary target... display-only" and teardown logic never trusts them for deletion decisions (`TargetTeardownPlanner.kt` doc comment, confirmed by `deleteCompanion`/world-deletion code paths reading the join tables).
- **Roleplay grouping already exists as a derived rule in one place**: `MemoryBrowserActivity.kt` line 682, `ROLEPLAY_SCOPES = setOf("world", "campaign", "rp_character")` — used today to decide which Pending drafts get the roleplay-only "Add to Card" action and the "needs a roleplay target" note (lines 260-268, 683). **It is not yet used to build two browser tabs** — there is no `TabLayout`/`ViewPager` splitting General vs Roleplay anywhere in `ui/activities/memory` (confirmed by search — only `RoleplayHubActivity.kt`, a separate navigation hub, references "Roleplay" in a screen-identity sense).
- **Companion memories can target more than one companion**: `memory_companions` is many-to-many (schema, line 392-397) and `MemoryRecord.companionIds: List<String>` (line 437) is a list, not a single nullable id. `Archivist.kt` line 867-868 only ever assigns **one** companion (the conversation's own) to a new draft, but the schema and store both support a memory later being linked to a second companion (e.g. manually in the editor, or in a future emergence flow) — see Unresolved Decisions.

**What it currently does:** Scope grouping already follows the plan's "derive Roleplay from World/Campaign/RP-Character scope, don't invent a fourth scope" rule at the schema level and in one piece of UI logic (Add-to-Card gating). Companion, World, Campaign, RP Character, Project all use real multi-target join tables, not single foreign keys, with teardown logic that already respects "only delete when this is the sole owner."

**How it differs from the canonical plan:**
- **Matches already:** no invented `roleplay` scope (§4); join-table-based multi-target ownership with sole-owner-aware teardown (§4.6, `TargetTeardownPlanner`).
- **Gap:** no General/Roleplay **tab** UI exists yet (Phase 7, not started — expected, not a violation).
- **Genuine tension:** plan §4.2 describes a Companion memory as having "one specific companion target" (singular), but the schema/store already support **many** companions per memory. The plan does not say whether multi-companion "shared" memories are an approved pattern to keep, or an unintended flexibility that should be constrained to one companion per memory going forward. This directly affects companion-deletion semantics (§7 below: today, a memory shared by two companions survives deletion of either one, with only the deleted companion's link removed) — flagged as an unresolved decision.

**Can it be neutralized without a DB migration?** The tab UI absence needs no neutralization (nothing to stop — it's simply unbuilt, Phase 7 work). The multi-companion-target question needs no *schema* migration to preserve the status quo; it would need one only if the owner decides to constrain `memory_companions` to at most one row per memory.

**Recommended implementation path:** No Phase 0 action. Phase 7 builds the General/Roleplay tabs directly on the existing `ROLEPLAY_SCOPES` derivation rule already proven correct in `MemoryBrowserActivity`. The multi-companion question needs an owner decision before Phase 1/2 finalizes companion-memory candidate validation and companion-deletion semantics.

**Tests needed (future phases):** tab membership tests for all seven scopes; multi-companion-target behavior tests once the open question is resolved (whichever direction).

---

## 7. Companion-Memory Retrieval and Prompt Placement

**Where it exists:**
- Eligibility: `Enforcer.assembleTurn` (`enforcer/Enforcer.kt` lines 127-230) builds a `RetrievalScope(companionId, worldId, campaignId, roleplayCharacterId, allowCompanionInRoleplay)` and passes it to `Librarian.search`, which is the single gate for scope/target eligibility (per `MemoryData.kt` doc comment on `RetrievalScope`, lines 633-661). Draft companions never contribute (`companion?.status == "draft"` → treated as no companion, line 154); `memory_participation = 'none'` stops the whole turn's memory assembly (line 155); `'global_only'` keeps the companion branch out of the eligibility query while still allowing General retrieval (line 149, 157).
- Roleplay door: a companion's memories are eligible inside a roleplay scene only via `narratorMatch` (the scene's own GM companion) **or** a global "Allow active companion memories in roleplay" toggle, default off (lines 190-193) — this already implements something close to plan §4.4's per-conversation companion-pool gating, but as a **single global toggle**, not a per-conversation one.
- Multi-memory retrieval: `RetrievalBackfill.select(pool, policy.topK, scanCap) { ... }` (line 414) retrieves **several** ranked candidates (default `topK = 8`, `RetrievalPolicy.DEFAULT_TOP_K`), backfilling past cooldown/near-duplicate/budget rejections — **not** hardcoded to one memory. This already satisfies plan §4.3's "do not limit retrieval to one General and one Companion memory."
- Budget: `policy.charBudget` (default 6000 chars, `PromptAssembler.DEFAULT_CHAR_BUDGET`) is a **shared** budget across lore notes, card entries, and memories together — there is no reserved/protected sub-budget specifically for companion memories (plan §4.3, §5.12 require "a tested protected capacity for relevant companion memories when enabled").
- Prompt placement: `PromptAssembler.render` (lines 81-154) places retrieved memories in a `## Things you know` section **after** the scene/companion-identity block and **before** lore notes and card entries — this already satisfies plan §4.3's ordering requirement (fixed safety/identity first, retrieved memory next). **However**, General and Companion memories are rendered into the **same undifferentiated list**, with no visual grouping distinguishing them (plan §4.3: "clearly distinguish General and companion-specific groups when both are present" — not done).
- Instruction-Type memories are split out into their own `## Handling rules from the user` section (lines 111, 119-125) rather than being forced into every turn — consistent with plan §4.2's "retrieved when relevant... rather than injected into every turn."

**What it currently does:** A materially sophisticated, already-multi-memory, already-budgeted, already-ordered retrieval and prompt-assembly pipeline exists, with cooldown suppression, near-duplicate-vs-lore suppression, and Instruction-memory-as-rule rendering already built. The two concrete gaps against the plan are: (a) no reserved companion-memory sub-budget, and (b) no visible General/Companion labeling in the rendered prompt section.

**How it differs from the canonical plan:** Mostly aligned already (this is the area of *closest* current-to-target alignment in the whole audit). Gaps: reserved companion capacity (§4.3, §5.12) and General/Companion group labeling (§4.3) are unbuilt. The roleplay-companion door is a single global toggle rather than the plan's envisioned per-conversation control (though the plan's own §4.4 "Memories Used in This Conversation" list doesn't explicitly reconcile with this existing roleplay-specific toggle — see Unresolved Decisions).

**Can it be neutralized without a DB migration?** N/A — nothing here needs to be *stopped*; the gaps are additive features (protected budget, group labels), not conflicting behavior.

**Recommended implementation path:** Phase 5 work as scoped in the plan: add a protected companion-capacity carve-out inside `RetrievalBackfill`/budget accounting; add General/Companion section labels in `PromptAssembler.render` when both groups are present in the selection.

**Tests needed:** protected-capacity tests (companion pool gets its reserved share even when General crowds the ranked pool); label-rendering tests when both groups present vs. one only; regression tests for the existing cooldown/near-dup/budget backfill logic (already covered by `RetrievalBackfillTest.kt`, `PromptAssemblerTest.kt` — extend, don't replace).

---

## 8. Companion Deletion, Its Confirmation Dialog, and Exactly What Data It Deletes

**This is the most consequential Phase 0 finding in this audit.** There are **two independent user-facing "delete a companion" flows** that behave differently.

### 8a. The real product-level delete (persona/character deletion)

- **Where:** `EditPersonaActivity.confirmDelete()` (`ui/activities/EditPersonaActivity.kt` lines 568-596) → `PersonasListActivity` handles `ACTION_DELETE` → `PersonaPreferences.kt` line 193, `sync.onPersonaDeleted(id)` → `MemoryCompanionSync.onPersonaDeleted` (`preferences/memory/MemoryCompanionSync.kt` lines 136-147) → `MemoryStore.deleteCompanion(companionId, deleteMemories = true)` **(hardcoded `true` — unconditional)**.
- **Confirmation dialog:** title `R.string.persona_delete_title` = *"Delete this companion?"*; body `R.string.persona_delete_body` = *"The profile and all associated memories that aren't shared with another companion will be permanently deleted."* No name interpolation, no memory count shown.
- **What it deletes:** via `MemoryStore.deleteCompanion` → `TargetTeardownPlanner.plan` (sole-owner rule) → `deleteMemoriesWhere` (`MemoryStore.kt` lines 5758-5770), which deletes matching `memories` rows **regardless of `status`** (draft/active/archived/superseded all match — the `WHERE memory_id = ?` clause has no status filter), and relies on `ON DELETE CASCADE` (re-enabled post-migration per `onOpen`) to remove `embeddings`, `memory_companions`, `memory_worlds`/`campaigns`/`roleplay_characters`/`projects`, and `change_log` rows for each deleted memory. A memory linked to **more than one** companion (multi-target `memory_companions`) survives with only the deleted companion's link removed — its content, other companion's link, and lifecycle state are untouched.

### 8b. The Memory System's own "delete companion" (memory-record-only)

- **Where:** `CompanionDetailActivity.confirmDelete()` (`ui/activities/memory/CompanionDetailActivity.kt` lines 223-238) → `deleteCompanion(checkDeleteMemories.isChecked)` (line 240) → same `MemoryStore.deleteCompanion(id, deleteMemories)`, but here **`deleteMemories` is a user-controlled checkbox, unchecked (off) by default** (doc comment line 219-222: "checkbox, off by default — deleting the memories is the more destructive choice").
- **Confirmation dialog:** title `R.string.mem_comp_delete_title` = *"Delete companion?"*; body `R.string.mem_comp_delete_body` = *"This removes %1$s from the memory system. Its persona/character card is not affected."* — this screen explicitly does **not** delete the app persona/character; it only removes the memory-store companion record (relevant for memory-side-only or archivist-proposed companion records that were never linked to an app persona, or for cleaning up a stray record).
- **What it deletes:** identical mechanism to 8a, but **defaults to leaving companion-targeted memories in place**, merely unlinked from the deleted companion record (`db.delete("memory_companions", "companion_id = ?", ...)` still runs unconditionally at line 3901 regardless of the checkbox — so the *link* is always removed, but the *memory* itself survives when the checkbox is unchecked, orphaned from that companion with no companion link at all if it had no other companion target).

**How it differs from the canonical plan:** Plan §2.13 and §4.6 are unconditional: "Deleting a companion permanently deletes every memory targeted specifically to that companion after an explicit destructive confirmation... Do not orphan, silently convert, or reassign those memories." Flow 8a already satisfies this (unconditional `deleteMemories = true`) for the deletion path a normal user reaches by deleting their companion/persona. **Flow 8b directly violates it** — its default behavior orphans companion-targeted memories (they lose their only companion link and become companion-scoped memories with no target, which is not a valid, meaningful state and is not reachable through any purposeful "convert to General" action either). Neither dialog matches the plan's required wording (`**Delete {Companion Name}?** This will permanently delete {Companion Name} and {count} companion memories. This cannot be undone.` — name interpolation and a memory count are both required by plan §4.6 and neither current dialog has them).

**Can it be neutralized without a DB migration?** Yes, entirely — both flows are pure application code. No schema change is required to (a) make `CompanionDetailActivity`'s deletion unconditional like the persona-deletion path, or (b) update either dialog's wording to include the companion name and a live memory count.

**Recommended implementation path (Phase 1/2, requires an owner decision first — see Unresolved Decisions):**
1. **Decide** whether `CompanionDetailActivity`'s separate "delete companion" affordance should still exist at all now that the plan requires unconditional memory deletion — if it does, its checkbox must be removed (deletion becomes unconditional, matching 8a) or the screen must be re-scoped to something other than "delete."
2. Update both confirmation dialogs to the plan's required wording, with `{Companion Name}` interpolated and a live `{count}` of that companion's memories (a simple `COUNT(*)` against `memory_companions` before showing the dialog — no schema change).
3. Resolve the orphaned-memory question for any companion memory that currently has **zero** companion links post-8b-deletion under the old behavior — a one-time repair pass may be needed, or the plan's rule can simply apply going forward (owner decision).

**Tests needed:** unconditional-deletion test for `CompanionDetailActivity`'s flow (once unified with 8a); count-in-dialog test; cascade test proving Pending/Active/Archived/Superseded companion memories, their embeddings, and their joins are all removed (currently **untested** — `MemoryStore` has no JVM test harness per its own doc comment, and there is no `androidTest` coverage of `deleteCompanion` either — this is a coverage gap independent of the behavior gap, see §18); shared-memory-survival test (memory linked to two companions survives one's deletion) — behavior exists today but is unverified by any automated test.

---

## 9. Conversation-Level Memory Access Settings

**Where it exists:**
- Per-chat setting key `memory_enabled` (`PerChatSettingKeys.kt` line 88; storage/accessors `Preferences.kt` lines 1520-1546): a **tri-state** string — `""` (unset), `"true"`, `"false"`. `getChatMemoryEnabled()` resolves the effective boolean; `""` means "not yet decided for this chat" and callers fall back to `getDefaultMemoryEnabled()` (`Preferences.kt` line 1541-1546, app-wide default, itself defaulting to `true`).
- Applied at the very top of the enforcer call chain in `ChatActivity` (not the enforcer itself — the enforcer doc comment at `Enforcer.kt` line 47-49 states "the caller gates on the memory engine tier, the per-chat kill switch, and store existence").

**What it currently does:** One combined on/off switch per chat, with a tri-state "use app default vs. override" shape already built in — structurally similar to the plan's "Use Default vs Custom" concept, but gating **all** memory retrieval (General **and** Companion together) as a single unit.

**How it differs from the canonical plan:** Plan §4.4 requires **two independent** toggles per conversation — "General Memories On/Off" and "Companion Memories · {name} On/Off" — so a conversation can use General-only, Companion-only, both, or neither. Today there is exactly one combined toggle; a chat cannot currently enable General retrieval while disabling Companion retrieval, or vice versa, short of the separate global "Allow companion memories in roleplay" toggle described in §7 above (which is roleplay-specific and global, not this conversation's General/Companion split).

**Can it be neutralized without a DB migration?** N/A — this is an additive gap (a second toggle needs to be added), not a conflicting behavior to stop. The existing `memory_enabled` per-chat setting lives in the chat's own settings file (not the memory database), so adding a second key (`memory_companion_enabled` or similar) is a `PerChatSettingKeys` + `Preferences.kt` change with **no** `MemoryStore` schema migration at all.

**Recommended implementation path (Phase 1, storage; Phase 5, enforcement):** Add a second tri-state per-chat key mirroring `memory_enabled`'s shape for the companion pool specifically; thread both into `RetrievalScope` construction in `Enforcer.assembleTurn` so General and Companion eligibility can be gated independently, replacing today's single all-or-nothing gate.

**Tests needed:** four-combination test (General-only, Companion-only, both, neither) once built; regression test that the existing single-toggle behavior for chats with no companion is unaffected (General-only chats have no companion toggle to show, per plan §4.4: "only when a valid companion is assigned").

---

## 10. Conversation-Level Analysis Settings, Exclusions, or Markers

**Where it exists:**
- Per-chat setting key `memory_excluded` (`PerChatSettingKeys.kt` line 90; `Preferences.kt` lines 1771-1776, plain boolean, default `false`).
- User-facing control: `QuickSettingsBottomSheetDialogFragment.kt` — a **positively-framed** "Archive this chat" switch (lines 828-868): checked = archiving on (capture happens), which maps to the **inverted** stored value `memory_excluded = !archive`. Toggling it calls `MemoryStore.setChatTranscriptsExcluded(chatId, excluded)` to retroactively mark that chat's transcript rows' `review_status = 'excluded'` (going forward; see §5).
- Enforcement at capture time: `TranscriptRecorder.recordTurn` (line 92-95) — `excludedByUser` is checked **before any row is written at all**; an excluded chat's turns are never captured, so they can never be analyzed. This is a capture-time gate, not merely an analysis-time filter.
- Deliberately **decoupled from retrieval**: doc comment in `TranscriptRecorder.kt` lines 40-45 states explicitly that "the per-chat memory (injection) switch is deliberately NOT an input to live capture... storage and injection are independent" — i.e. `memory_enabled` (§9) and `memory_excluded` (this section) are already two separate, independently-settable concerns in the current code, which is directionally consistent with the plan's separation of "Memories Used in This Conversation" from "Create From This Conversation" (§4.4 vs §4.5).

**What it currently does:** A working, already-shipped per-chat "don't capture/analyze this conversation" exclusion exists, framed positively in the UI ("Archive this chat," on by default) and enforced at the earliest possible point (capture, not just analysis-selection).

**How it differs from the canonical plan:** This is the closest 1:1 match to a single named plan requirement found anywhere in this audit — plan §4.5's "Do Not Analyze This Conversation" is, functionally, what `memory_excluded`/"Archive this chat" already is, just with inverted framing (the plan doesn't specify a UI framing, so this is not itself a conflict). **What's missing**: (a) no independent per-stream analysis selection — the plan's §4.5 "Create From This Conversation: General Memories / Companion Memories / Model Rules" as three independent checkboxes does not exist; today a non-excluded chat's analysis always attempts all three outputs at once, gated only by the single fixed Archivist prompt (see §14); (b) no **Analysis Note** field exists anywhere in the schema, UI, or Archivist prompt-building code; (c) no "Use Default vs Custom" **per-conversation** analysis-policy distinction beyond the single exclusion boolean — there is no batch-vs-custom-override concept for analysis streams because there are no separate streams to override.

**Can it be neutralized without a DB migration?** N/A — again additive. `memory_excluded` needs no change. The three-stream selection, Analysis Note, and Use-Default/Custom distinction are all new fields; per plan Phase 1 item 12, they belong in **conversation policy storage separate from memories** (the plan is explicit that this is "ordinary conversation metadata... never copied into a Pending or Active memory," item 13) — this could live in the chat's own settings file (`PerChatSettingKeys`) exactly like `memory_excluded` does today, requiring no `MemoryStore` migration, or in a new small per-chat table in the memory database if the owner prefers it colocated with other memory-system state. Either is schema-additive, not destructive.

**Recommended implementation path (Phase 1 storage, Phase 4 enforcement):** Add per-chat keys for the three independent extraction-stream selections (default: inherit app/batch default), an optional Analysis Note string, and a Use-Default/Custom flag — following the existing `memory_excluded`/`memory_enabled` pattern exactly (chat settings file, survives rename via `PerChatSettingKeys.ALL`, no memory-database migration needed unless the owner prefers colocation).

**Tests needed:** stream-independence tests (General-only / Companion-only / Model-Rules-only / combinations) once the fixed all-in-one prompt is split (see §14); Analysis Note isolation test (never becomes memory content, never sent to ordinary chat, never persisted as memory metadata); Do-Not-Analyze-still-works regression test.

---

## 11. Model Rule Extraction, Review, Storage, and Application

**Where it exists:**
- Storage: `model_rules` table (`MemoryStore.kt` lines 756-764) — `rule_id`, `text`, `model_strings_json`, `status CHECK IN ('draft','active')`, `source_model_string`, timestamps. Separate `model_rule_tags` / `model_rule_tag_links` tables for organization. `ModelRuleRecord` (`MemoryData.kt` lines 367-381).
- Extraction: the Archivist's combined JSON response includes a `model_rules` array *alongside* `memories` in the **same** model call (`ArchivistPrompt.kt` lines 64-66, 106-107; `ArchivistResponseParser.DraftRule`, line 72). `Archivist.fileRuleDrafts` (lines 976-1007) dedupes against existing rule text (case-sensitive-trimmed) and inserts as `status='draft'` with `sourceModelString` seeded from the conversation's model tag — the user assigns the actual `model_strings_json` list on review/accept (per `MemoryData.kt` doc comment line 375-378).
- Review UI: `ModelRulesActivity.kt` / `ModelRuleEditorActivity.kt` / `ModelRuleTagsActivity.kt` / `ModelRuleTagViewActivity.kt` (all present, full CRUD + tagging UI already built) — **not traced line-by-line in this pass**; existence and table wiring confirmed, detailed review-screen field audit is a narrow follow-up if Phase 2's "audit the existing Model Rule storage, review, and application path before changing it" (plan item, Phase 2 §13) needs more than table/flow confirmation.
- Application: **entirely separate from the Enforcer/memory-retrieval path.** `ChatActivity.kt` lines 8001-8015 and 8279-8292 — gated by the per-chat `apply_model_rules` toggle (`PerChatSettingKeys.kt` line 99) — calls `store.getActiveModelRulesForModel(selectedModel)` (model-string match via `ModelRuleMatcher.kt`, case-insensitive contains with provider-prefix stripping) and injects matched active rules' text as a system-message prefix (`R.string.model_rules_injection_header`). This is unconditional injection for every matching rule on every turn (not semantic retrieval, not budget-limited the way memories are) — appropriate for the plan's description of Model Rules as broadly-applicable "procedural behavior instructions," distinct in kind from semantically-retrieved memories.

**What it currently does:** Model Rules are already structurally isolated from Associative Memories at every layer — separate table, separate extraction array (though extracted in the same model call as memories — see §14), separate draft/active review lifecycle, separate injection mechanism (model-string match, not semantic retrieval), separate per-chat toggle. This already satisfies plan §2.4/§8.2's "Model Rules must not be disguised as Associative Memories" and the completion standard's "Model Rules remain a separate reviewable output stream."

**How it differs from the canonical plan:** No structural violation found. The one open item is procedural, not architectural: plan Phase 2 item 13 requires auditing "the existing Model Rule storage, review, and application path before changing it... return a focused question if the visible review contract is not already defined" — this audit confirms the tables, extraction, and application paths exist and are wired consistently, but does not certify every review-screen field against the plan's (currently unstated) Model Rule review requirements, since the plan itself defers those details ("the exact Model Rule schema beyond `content` must follow the audited existing Model Rule workflow," §8.7).

**Can it be neutralized without a DB migration?** N/A — no conflicting behavior identified to neutralize.

**Recommended implementation path:** None required for Phase 0/1 correctness. When Phase 2 begins, a narrow, single-purpose review of `ModelRuleEditorActivity.kt`'s exact fields (beyond `text`) against whatever the owner confirms as the "visible review contract" is the only remaining follow-up.

**Tests needed:** existing coverage — `ModelRuleMatcherTest.kt` (unit-tested matcher logic). No test exists for the draft→active review flow or the `apply_model_rules` injection path itself (both are `ChatActivity`/UI-integrated, outside the JVM test harness) — a gap consistent with the rest of the app's UI-layer test coverage (see §18).

---

## 12. API Analysis

**Where it exists:** `Archivist.analyze()` / `Archivist.rerun()` (`archivist/Archivist.kt` lines 230-270) — both funnel through the private `run`/`runLocked` engine (lines 272-775). Configuration is read from `Preferences` (`getArchivistEndpointId`, `getArchivistModel`, `getArchivistRoutingType`, `getArchivistCustomPrompt`, `getArchivistLorebookPrompt`, `getArchivistMaxSuggestions`, `getArchivistMinImportance`, `getArchivistTemperature`, `getArchivistCardSuggestions`). Missing endpoint/model configuration returns `outcome = "not_configured"` and routes to setup rather than attempting a doomed request (already satisfies plan §4 item 18's intent, for the batch flow — no per-conversation flow exists to apply this to, see §13 below).

**What it currently does:** A single, app-wide "Analyze" action (see §13, entry points) runs the full engine over every currently-eligible (pending, unclaimed) transcript row across every chat, batched for display purposes only (`ArchivistBatchPlanner.planBatches`), with per-conversation model calls (further split by `splitIntoRequests` when a single conversation is oversized).

**How it differs from the canonical plan:** Functionally this **is** "API analysis" as the plan uses the term (as opposed to "Computer" — see §13), and the engine mechanics (temperature, endpoint/model selection, structured-vs-plain response handling) map to plan §8.2.2/§15's "API" processing method description. The gap is entirely about **scope and visibility**, covered in §13-§16 below (no per-conversation trigger, no independent stream selection, no visible size/cost estimate before running, no Prompt Profile choice, no token-aware chunking).

**Can it be neutralized without a DB migration?** N/A — this is the correctly-scoped existing engine; nothing to neutralize.

**Recommended implementation path:** Phase 4 builds directly on this engine (add per-conversation entry, independent streams, size/cost preview, Prompt Profile) rather than replacing it.

**Tests needed:** covered in §14-§16.

---

## 13. Computer Export/Import Analysis

**Where it exists:** **Nowhere.** Full-text search for a "computer package" / external-processing export-then-import analysis flow (distinct from the whole-store backup format) found no such feature. `MemoryExporter.kt` (`buildExportJson`, `writeBackupNow`, `autoExportIfDue`) produces/writes a **complete store backup** (every table), not a scoped per-conversation analysis package. `MemorySeedCodec.parse`/`serialize` round-trip that same whole-store shape (also used for the bundled `seed_public_template.json` example content). Neither has any concept of "package the frozen range of one conversation, plus the effective extraction selections/prompt/schema, for a user to run through an external tool and import the result."

**What it currently does:** N/A — feature does not exist yet.

**How it differs from the canonical plan:** Plan §8.2.2 requires "Computer" as a coequal processing-method choice to "API," and §15 describes its package/import contract in detail (freeze the range, include extraction selections/prompt/Analysis Note/schema, strictly validate the returned package, file through the same canonical Pending path as API suggestions with **no** visible difference in the resulting card). None of this exists today.

**Can it be neutralized without a DB migration?** N/A — nothing to neutralize; this is unbuilt, not conflicting.

**Recommended implementation path:** Phase 4/9 work, built after the canonical Pending filing path (Phase 2) and the chunking/policy rework (Phase 4) exist, since the Computer package's job is to carry exactly the same frozen-range/policy/schema contract API analysis will use — building it first would mean building it twice.

**Tests needed (future phase):** package validation (malformed/tampered package rejected), duplicate-import prevention, API/Computer output-shape equivalence (identical Pending card, no visible origin difference) — all explicitly required by plan §15.

---

## 14. Every Current Analysis Entry Point

**Confirmed entry points (exhaustive, by call-site search on `Archivist.analyze(` / `Archivist.rerun(`):**

1. **`MemoryAssistantActivity.kt`** → starts `MemoryAnalysisForegroundService` (`service/MemoryAnalysisForegroundService.kt` lines 214-215), which calls `Archivist.analyze(appContext, analysisType, onProgress)` for a fresh run, or `Archivist.rerun(appContext, rerunOfRunId, analysisType, onProgress)` for the "Rerun" action on a past run row. This is the **only** UI path that starts analysis.
2. **No per-conversation entry point exists** — no "Analyze This Conversation" action was found in `ChatActivity.kt` or any conversation list/menu code. `eligibleConversations()` always operates over **every** chat with pending, unclaimed transcript rows app-wide; there is no way today to select and analyze a single chat outside of the "Rerun" path (which re-feeds a **past** run's chats, not an arbitrary single current conversation).
3. `analysisType` is a binary switch between `"associative"` (ordinary memory + Model Rule drafts) and `"lorebook"` (Lorebook Memories, Step 1.7 — keyword-triggered lore entries, an entirely separate output type from Associative Memories, out of the canonical plan's scope per plan §15's "Keep Lorebook import and retrieval separate from Associative Memory").

**How it differs from the canonical plan:** Plan §4 item 1 requires "Add an Analyze Conversation action to every conversation" — this does not exist. Today analysis is exclusively a global/batch operation from a dedicated settings screen.

**Can it be neutralized without a DB migration?** N/A — additive UI work only.

**Recommended implementation path:** Phase 4 adds the per-conversation action, reusing the existing `Archivist.run` engine with a `selectConversations` lambda that returns exactly one conversation (the engine already accepts an arbitrary `selectConversations: () -> List<Conversation>` — `rerun`'s implementation, lines 263-269, is a precedent for a non-"all eligible" selector).

**Tests needed:** per-conversation trigger test (only the selected chat's rows are claimed/processed); existing batch behavior regression test (unaffected by the new single-conversation path).

---

## 15. Full Archiver Flow: Conversation Selection Through Pending Filing

Traced end-to-end in `Archivist.kt` (see §12, §14 above for surrounding context). Sequence for one run:

1. **Config gate** (`run`, lines 279-333): endpoint/model/routing must be fully configured or the run returns `not_configured` without starting.
2. **Single-run lock** (`liveRun` AtomicBoolean, in-process) plus a **durable cross-process lock** (`archivist_runs.status='running'` row) — `reconcileInterruptedAnalysisRuns()` clears any dead run's claims before a new one starts (lines 358-366).
3. **Selection + claim** (`runLocked`, lines 376-404): `selectConversations()` (either "all eligible" or a past run's chat set for Rerun) → `store.beginAnalysisRun` atomically writes the `running` row **and** stamps `claim_run_id` on every selected transcript row in one transaction — a row claimed by a concurrently-started run simply drops out of this run's set.
4. **Display batching** (`ArchivistBatchPlanner.planBatches`, presentation only, does not change request shape) and **per-conversation request chunking** (`ArchivistBatchPlanner.splitIntoRequests`, `MAX_REQUEST_CHARS = 200_000` **characters**, whole-transcript-row atoms, no overlap) — see §16 for full detail.
5. **Per-chunk model call** (lines 487-499): one `ChatCompletionRequest` per chunk, system prompt = the fixed built-in prompt or the user's saved custom override (no Prompt Profile choice), user message = `ArchivistPrompt.userMessage(...)` (plain rendered transcript, incomplete-assistant-turn-excluded).
6. **Per-chunk response handling**: `ArchivistResponseParser.parse(raw)` (brace-matched JSON extraction, §16) → importance floor filter → per-conversation cap → **`fileMemoryDrafts` is called once per chunk, immediately, inside the chunk loop** (lines 560-566) → drafts are written to the store (`store.insertArchivistDraftMemory`) **as each chunk succeeds**, before the conversation's remaining chunks (if any) have run.
7. **Only after all of a conversation's chunks succeed**: `store.markTranscriptsProcessed(...)` (line 571) advances that conversation's rows to `processed`.
8. **Per-conversation failure isolation** (lines 594-610 in the exception handler): one conversation's exception is caught, the conversation is added to `failedChats`, and the loop **continues to the next conversation** — it does not abort the whole run.
9. **Terminal bookkeeping**: `releaseAnalysisClaims(runId)` returns any still-claimed-but-unprocessed rows to the pool; the `running` row is finalized to `complete`/`failed` with outcome/failure classification; a `RunOutcome` is returned to the UI.

**Partial-filing risk — confirmed, concrete:** step 6 is the finding. For a **single oversized conversation** split into N>1 chunks (`chunks.size > 1`, logged at line 472-474), chunk 1's candidate memories are inserted into the visible Pending queue (`store.insertArchivistDraftMemory`, immediately visible to the user) **before** chunk 2 is even requested. If chunk 2 then fails (malformed JSON, provider error, truncation, cancellation), the whole conversation is marked failed and its transcript rows are **not** advanced to `processed` (correct — they'll be retried), but **chunk 1's drafts already exist in Pending and are never rolled back.** A user who reviews Pending mid-run, or after a run that reports "partially failed," will see a partial slice of what one oversized conversation actually contained, with no signal that more was coming. This is the exact failure mode plan §8.8 ("Do not file each successful chunk directly into visible Pending... hold candidates in temporary run storage" until the full frozen range succeeds) and the Phase 4 completion gate ("a failed final chunk cannot leave a half-analysis in Pending") are written to prevent.

**How it differs from the canonical plan:** The claim/lock/failure-isolation machinery (steps 2-4, 8-9) is already close to what plan §9 and §8.10 want structurally (see §5 above). The **chunk-then-file-immediately** behavior in step 6 is a direct, concrete violation of plan §8.8/Phase 4's completion gate, and is the single highest-value correctness fix identified in this audit — it can be fixed by buffering a conversation's chunk candidates in memory (or a temporary table) and calling `fileMemoryDrafts` **once**, after all of that conversation's chunks have parsed successfully, rather than per-chunk. This requires no schema change — it is a control-flow change inside `runLocked`.

**Can it be neutralized without a DB migration?** Yes — this is pure control flow in `Archivist.kt`; no schema change is needed to buffer candidates across a conversation's chunks before filing.

**Recommended implementation path:** Phase 4 item 22/29 ("Collect every chunk's validated candidates in temporary run storage before filing anything into visible Pending" / "File the complete valid set... only after all required chunks and consolidation succeed") — restructure `runLocked`'s inner chunk loop to accumulate `ArchivistResponseParser.DraftMemory`/`DraftRule` lists across all of a conversation's chunks, and move the `fileMemoryDrafts`/`fileRuleDrafts` calls to after the chunk loop, gated on every chunk in that conversation having succeeded.

**Tests needed:** a test conversation split into 2+ chunks where chunk 2 fails must result in **zero** visible Pending drafts from chunk 1 (currently the code would produce chunk 1's drafts — this is a regression test to write *for* the fix, proving the bug is fixed, not a test of current-correct behavior).

---

## 16. Chunk Limits, Token Estimation, Output-Token Allowance, JSON Parsing, Retry, Truncation, Partial-Filing Risk

| Concern | Current behavior | Location |
|---|---|---|
| Chunk unit | **Characters**, not tokens. `MAX_REQUEST_CHARS = 200_000` chars per model call. | `ArchivistBatchPlanner.kt` line 39 |
| Token estimation | **None.** No token counting anywhere in the archiver path (the on-device tokenizer, `HfTokenizer.kt`, exists only for the embedding model — the librarian — and is never used by the Archivist). | confirmed by absence |
| Model/provider context-limit awareness | **None.** The 200,000-char figure is a fixed constant, not derived from the selected model's or provider's advertised limits. | `ArchivistBatchPlanner.kt` |
| Output-token reservation | **None.** No output budget is subtracted from the request budget; the request relies entirely on whatever the provider/model does with an unbounded completion request. | confirmed by absence |
| Chunk boundary | Whole transcript **rows** only (never mid-message splitting); a single row larger than the budget travels alone (comment, line 51-52) — rows are themselves capped at `MemoryStore.MAX_TRANSCRIPT_CHARS = 200_000` chars at capture time, so an oversized row is only possible from legacy/imported data. | `ArchivistBatchPlanner.splitIntoRequests`, `MemoryStore.kt` line 97 |
| Chunk overlap | **None.** No overlap between adjacent chunks. | confirmed by absence |
| Structured output / tool calling | **None.** Plain chat completion, prose-tolerant JSON extraction. | `Archivist.kt` line 487-499 |
| JSON extraction | `extractJsonObject` (`ArchivistResponseParser.kt` lines 199-204) takes the substring from the **first** `{` to the **last** `}` in the raw response — tolerates surrounding prose/fencing but has no brace-balance awareness (a `}` inside a string value earlier than the true closing brace, or a genuinely truncated response missing its final `}`, both throw `no JSON object in response` or hand `JSONObject(...)` a string it cannot parse). | `ArchivistResponseParser.kt` |
| Truncation detection | **None.** A truncated response is not distinguished from a malformed one — both surface as a `JSONException`/`IllegalArgumentException` from the constructor above, caught and re-tagged as `ArchivistFailure.UNREADABLE` (`Archivist.kt` lines 507-511, 533-537). There is no separate "response was cut off by output limit" classification, no `finish_reason` inspection. | `Archivist.kt` |
| Per-entry validation independence | Partial — each memory/rule object in the array is validated independently (a bad object increments `dropped` and is skipped, the rest of the array still processes, `ArchivistResponseParser.kt` lines 105-141), but this only matters if the **outer** object parsed at all; a truncated outer object fails wholesale. | `ArchivistResponseParser.kt` |
| Repair attempt | **None.** No bounded repair/retry-with-fix-prompt exists — a parse failure is terminal for that chunk/conversation this run (it becomes a `failedChat`, transcripts stay pending, retried whole on the next run). | confirmed by absence |
| Retry on context/size rejection | **None.** No shrink-and-retry logic exists; a request rejected for size fails the conversation outright. | confirmed by absence |
| Silent discard of valid output | **None found** — the per-conversation cap (`maxSuggestions`) is logged when it truncates a candidate list ("cap $maxSuggestions reached, N draft(s) not filed," `Archivist.kt` line 550-551), and the importance floor is logged too (line 556-557) — these are visible, intentional, user-configured limits, not silent drops. | `Archivist.kt` |
| Partial filing | **Confirmed present** — see §15 above; the single highest-priority correctness gap. | `Archivist.kt` |
| Cancellation | Handled: `CancellationException` is caught at the run level (not per-conversation), recorded as `outcome = "cancelled"`, claims released, nothing already-filed is rolled back (correct — filed drafts are legitimately-completed work; the risk is only the mid-conversation partial case above). | `Archivist.kt` lines 613-621 |
| Process death | Handled via the durable `running` row + claim reconciliation at next startup/run (`reconcileAtStartup`, `reconcileInterruptedAnalysisRuns`) — released rows return to `pending`, nothing is double-counted. | `Archivist.kt` lines 216-224, `MemoryStore.reconcileInterruptedAnalysisRuns` |

**How it differs from the canonical plan:** Plan §8.5/§8.6/§8.9 require token-budget-based chunking (minimum of user choice, model limit, provider limit, minus system/output/safety reserves), truncation-vs-malformed distinction, bounded repair-then-retry, and the "never file a chunk directly into Pending" rule already covered in §15. The current implementation satisfies the "preserve whole messages" and "no silent discard" requirements already, and has solid cancellation/process-death handling, but has **no** token awareness, **no** output reservation, **no** truncation classification, and **no** retry/repair — this is the largest single cluster of Phase 3/4 work implied by the audit.

**Can it be neutralized without a DB migration?** Yes for all items in this table — everything here is `Archivist`/`ArchivistBatchPlanner`/`ArchivistResponseParser` application code; no schema is involved in chunk sizing, token estimation, or JSON handling.

**Recommended implementation path:** Explicitly deferred to Phase 3 (evaluation harness must produce evidence before token targets are chosen, per plan §8.5/§8.11 — "Do not finalize the token values... until the harness produces evidence") and Phase 4 (implementation). No Phase 0/1 action beyond recording this table.

**Tests needed (future phases):** token-budget-derivation tests (min of user/model/provider/safety-margin); truncation-vs-malformed classification tests; bounded-repair-attempt tests (exactly one retry, never a loop); shrink-and-retry tests for context-rejected requests.

---

## 17. Backup, Restore, Seed, Export, and Import Formats

**Where it exists:**
- `MemoryExporter.kt` — `buildExportJson`/`writeBackupNow`/`autoExportIfDue`: whole-store JSON backup (every table via `MemorySeedCodec.serialize`), auto-export-if-due scheduling, chat-storage-degraded guard.
- `MemorySeedCodec.kt` (864 lines) — `parse(jsonText): MemoryStoreData` and `serialize(...)`: the single source of truth for the whole-store JSON shape, covering every record type in `MemoryData.kt` (§0 above) — companions, entities, memories, modes, directives, worlds, user personas, roleplay characters, campaigns, projects, party members, card entries, rp tags, model rules + tags/links, archivist settings, proposals, retrieval policy, transcripts.
- `Memory System/seed_public_template.json` — a bundled example-content template, imported the same way as a backup but tagged `origin='seed'` and gated out of retrieval (per `CompanionRecord.origin` doc comment, `MemoryData.kt` line 58-60: "'seed' is gated out of retrieval and targeted by the purge").
- Portable/device-transfer backup: `preferences/backup/portable/PortableRecoveryWriter.kt`, `ChatLogicalSerializer.kt` — referenced in the earlier entry-point grep (§14) as consumers of `review_status`/transcript data; **not traced in this pass beyond confirming their existence and relevance** — a narrow follow-up if Phase 10's "backup, restore, seed, export, and import tests" need portable-format specifics beyond the JSON codec.

**What it currently does:** One whole-store JSON round-trip format serves backup, restore, and seed-template import uniformly; transcripts (operational, not part of the JSON "schema" record types per `MemoryData.kt` line 567-570 comment) ride along in exports "per app_adaptation_notes 11c" as "the raw material the whole store can be re-derived from" — i.e., transcripts **are** exported today, unlike `archivist_runs`/embeddings which are explicitly device-local/never-exported (per multiple doc comments, e.g. `MemoryData.kt` lines 499-505).

**How it differs from the canonical plan:** Every field this audit has flagged as needing to become inert or change shape (`title`, fixed `kind` including `lore`, 1–5 `importance`, `provenance_context`/`source_chat_id`) currently rides through this **same single codec** — meaning a Phase 1 migration that changes any of those fields' meaning must also update `MemorySeedCodec.parse`/`serialize` in the same change, or older backups will re-import stale-shape data that the rest of the app no longer expects. Plan Phase 1 item 17 ("backup/restore compatibility" migration test) and Phase 10 items 4-5 (older backups must not create visible titles/invalid Types/source-chat memories; new backups must preserve Types, No Type, importance-while-disabled, conversation policies, etc.) both depend directly on this single codec file being kept in lockstep with every schema/behavior change identified above.

**Can it be neutralized without a DB migration?** The codec itself needs no migration (it is a JSON (de)serializer, not schema), but it is the **single choke point** through which every other migration's data-compatibility risk flows — every Phase 1 field change has a corresponding "what does an old backup's stale value do on import" question that must be answered here.

**Recommended implementation path:** No Phase 0 action beyond this documentation. Treat `MemorySeedCodec.kt` as a required-touch file for every Phase 1 schema item, and add one round-trip test per changed field (old-shape JSON in → new-shape store state out, matching each field's documented migration rule).

**Tests needed:** existing `MemorySeedCodecTest.kt` (JVM-testable, confirms the codec is already unit-tested at the parse/serialize level) — extend with pre/post-migration shape fixtures for title, kind/Type, importance range, and provenance/source-chat fields once Phase 1 lands.

---

## 18. Existing Tests and Missing Tests

**Existing JVM unit tests** (`app/src/test/java/org/teslasoft/assistant/preferences/memory/`, pure-Kotlin/no-Android-dependency classes only — confirmed 17 files):

`MemoryMatchTest`, `MemorySeedCodecTest`, `RetrievalPolicyTest`, `TargetTeardownPlannerTest`, `TranscriptRecorderPolicyTest`, `archivist/ArchivistBatchPlannerTest`, `archivist/ArchivistFailureCategoryTest`, `archivist/ArchivistPromptTest`, `archivist/ArchivistResponseParserTest`, `enforcer/CardRetrievalTest`, `enforcer/ModelRuleMatcherTest`, `enforcer/PromptAssemblerTest`, `enforcer/RetrievalBackfillTest`, `librarian/HfTokenizerTest`, `librarian/LibrarianRankingTest`, `librarian/LibrarianSearchCoreTest`, `librarian/RetrievalDocumentTest`.

**Not tested at all (no JVM test, no `androidTest` instrumented test found under `app/src/androidTest` for any memory/companion/archivist path):**
- `MemoryStore.kt` itself — the actual SQLCipher-backed CRUD/migration/cascade layer (5,800 lines) has **zero** automated test coverage. This is by the codebase's own design constraint, noted in-code ("the store itself is SQLCipher and has no JVM harness" — `TargetTeardownPlanner.kt` doc comment) — pure decision logic is extracted into JVM-testable objects (`TargetTeardownPlanner`, `MemoryMatch`, etc.) specifically so it *can* be tested, but the store's actual SQL execution, `onCreate`/`onUpgrade` migrations, and `deleteCompanion`'s end-to-end cascade are exercised by nothing but manual/device testing.
- `Archivist.kt`'s `run`/`runLocked` engine end-to-end (the partial-filing risk in §15 is a live, unverified-by-test bug in exactly this untested code).
- `Enforcer.kt`'s `assembleTurn` end-to-end (the pure-logic pieces it calls — `RetrievalBackfill`, `PromptAssembler`, `CardRetrieval`, `ModelRuleMatcher` — are tested; the orchestration in `Enforcer` itself is not).
- Companion deletion cascade (both flows in §8) — no automated test proves Pending/Active/Archived/Superseded companion memories, their embeddings, and their joins are actually removed, or that a shared (multi-companion) memory survives correctly.
- Any database migration path (`onUpgrade` blocks 2 through 20) — no fresh-install-vs-upgraded-install equivalence test exists.
- UI-layer behavior: `MemoryEditorActivity`, `CompanionDetailActivity`, `MemoryBrowserActivity`, Pending card rendering, dialog wording — no instrumented UI tests found for any memory-system screen.
- Model Rule draft→active review flow and `apply_model_rules` injection (`ChatActivity` lines 8001-8015/8279-8292) — matcher logic is unit-tested (`ModelRuleMatcherTest`), the injection call site itself is not.

**How this differs from the canonical plan:** Every phase of the plan (1 through 10) specifies its own required tests as a completion-gate condition — e.g., Phase 1's migration tests, Phase 2's cascade tests, Phase 4's chunking/truncation/partial-filing tests. None of those categories currently exist for the equivalent current-code behavior, which means **every one of those future tests is new work, not an extension of existing coverage** for the store/archiver/UI layers specifically (the pure-logic layer — parser, matcher, batch planner, retrieval backfill, prompt assembler — is comparatively well covered and those existing tests are a solid foundation to extend rather than replace).

**Recommended path:** No Phase 0 action. This section exists so Phase 1 onward budgets test-writing time accurately: the plan's "add migration tests," "add cascade tests," "add chunking tests" items are not incremental additions to a tested store — they are the *first* automated tests that layer will ever have.

---

## 19. Safe Migration Recommendations and Rollback Risks

**General migration safety observations:**
- The existing `onUpgrade` pattern (19 additive, never-edited-after-ship blocks) is a good foundation and should be continued for Phase 1 — one new `if (oldVersion < 21)` block, additive only, following the v4/v7 precedent for any column that needs a `CHECK`-relaxing table rebuild (e.g., if `title NOT NULL` needs to become nullable).
- **No column identified in this audit needs to be dropped in Phase 1.** Every behavior change identified (title, Type/lore, importance, provenance) can be achieved by (a) adding new columns/tables alongside the old ones, (b) migrating/mapping old values into the new shape, and (c) making the old columns behaviorally inert in application code — exactly the pattern plan §Execution Rules already mandates ("stop the legacy field from affecting prompts, UI, matching, embeddings, and retrieval. Physical column removal comes later").
- **Rollback risk is concentrated in `MemorySeedCodec.kt`** (§17): because one codec serves backup, restore, and seed import uniformly, any Phase 1 field-shape change must ship a codec update in the *same* change, or an older-format backup restored after the migration will silently reintroduce stale-shape data (e.g., a pre-migration backup's `kind: "lore"` memories, or its 1-5 importance values with no Type table entries) with no path currently in place to re-run the migration mapping on import. **Recommendation:** the Phase 1 migration mapping logic (kind→Type, including the `lore`→No-Type rule) should be written as a reusable function callable both from `onUpgrade` (existing installs) and from `MemorySeedCodec.parse` (any backup/seed import, including one taken *before* the device ever upgraded) — otherwise restoring an old backup onto an already-migrated app reintroduces exactly the state Phase 1 was meant to retire.
- **Companion-deletion behavior (§8) is the one area with a live behavioral risk today**, independent of any future migration: `CompanionDetailActivity`'s optional-checkbox deletion path can already leave companion-scoped memories with no companion link (a state with no clear meaning in either the current app or the canonical plan) for any user who unchecks the box today, before any Phase 1 work begins. This is not something Phase 0 fixes, but it is worth the owner's awareness that the inconsistency exists in production now, not only as a future-migration concern.
- **The Archivist partial-filing risk (§15) is also a live-today correctness issue**, not a migration risk — it requires no schema change and could be fixed independently of the rest of Phase 1 if the owner wants it addressed sooner than the full Phase 4 rework.

**Rollback path if a Phase 1 migration needs to be reverted:** Because no column is dropped and every change is additive-with-mapping, a rollback is straightforward at the schema level (older app code simply never reads the new columns/tables) **provided** the mapping step (kind→Type, etc.) is non-destructive to the *old* columns — i.e., Phase 1 must not clear or overwrite `memories.kind` when populating `memories.type_id`; both should coexist so a downgrade still has a fully-populated legacy shape to fall back to. This audit found no current code that would prevent that coexistence — `kind` is read-only by legacy code paths that a Phase 1 migration would be updating anyway, not written by anything that would need `kind` to disappear.

---

## 20. Unresolved Decisions (Not Chosen Here — Flagged for the Owner)

Per Phase 0's restriction against making product decisions, the following genuine open questions surfaced during this audit and are **not** resolved by the canonical plan's current text:

1. **Multi-companion memory targets** (§6): the schema/store already allow one memory to target several companions (`memory_companions` many-to-many), but plan §4.2 describes a Companion memory as having "one specific companion target" (singular). Should Phase 1/2 constrain this to exactly one companion per memory (simpler, matches the plan's literal wording, but is a behavior change/possible data change for any memory that already has multiple companion links today), or should the plan's wording be treated as describing the common case while the schema's existing flexibility is kept? This directly affects companion-deletion semantics (a shared memory currently survives either companion's deletion).
2. **The provenance marker rendered to the model** (§4): `provenance_source`/`provenance_confidence` (stated/inferred, certain/tentative) are rendered into every prompt line today ("told/observed/guessed"). The canonical plan's Associative Memory Shape (§3) does not list this as an approved field, but does not explicitly forbid it either — it simply never mentions it. Does this marker survive into the revised system, get folded into memory content text (as §3.2's "a sensitive fact keeps any needed care in its own text" pattern already does for handling/protection text), or get removed?
3. **Transcript bookmark architecture** (§5): should Phase 1 replace the existing per-row `review_status`/claim-lock queue with a literal single per-chat bookmark column, or is the existing mechanism an acceptable (arguably more conservative) implementation of the same "frozen range + short-lived run bookkeeping" requirement? This is a substantial rewrite either way and the plan's wording does not clearly force one answer.
4. **`CompanionDetailActivity`'s separate delete affordance** (§8): once companion deletion becomes unconditional (per plan §2.13/§4.6), does this second delete flow (for memory-store-only companion records never linked to an app persona) still need to exist as a distinct screen action, get merged into the same unconditional-deletion behavior as the persona-delete path, or get removed entirely? Someone must decide before Phase 1/2 touches `CompanionDetailActivity.confirmDelete()`.
5. **Roleplay-companion-memories global toggle vs. per-conversation** (§7): the existing "Allow active companion memories in roleplay" toggle is global and roleplay-specific. Plan §4.4's "Memories Used in This Conversation" is per-conversation and scope-agnostic. Does the new per-conversation Companion-pool toggle (§9's recommended addition) subsume/replace the existing roleplay-specific global toggle, coexist with it, or does the roleplay door remain a separate, additional gate on top of the new per-conversation toggle?
6. **Existing stored `importance = 3` defaults** (§3): once 0 becomes the neutral default, the ~large body of existing AI-authored memories that hold `importance = 3` (the current default, not necessarily a deliberate "notable" rating) will look identical to a memory someone deliberately rated 3/5. Should Phase 1 leave these values as-is (plan's explicit instruction: "Preserve every existing stored importance value"), or is a one-time note/flag warranted so the user understands why older memories may show a non-neutral default they never actually chose? The plan says preserve values; it does not address the ambiguity ration-ale.
7. **Whether `Maximum Memory Context` gets a dedicated memory-only budget or continues to share space with lore notes and card entries** (§23): today's `charBudget` is one pool for all three; the new spec's wording describes memory content specifically. Splitting it is a larger change (a second budget dimension inside `Enforcer.assembleTurn`'s existing char-accounting) than keeping the shared pool and simply renaming/retyping it to tokens.
8. **Whether `Memory Priority` and the original counterplan's "protected companion capacity" (Phase 5, plan §4.3/§5.12) are the same mechanism or two** (§25): both describe letting Companion memories avoid being crowded out by General ones under a shared budget. Building them as one mechanism avoids maintaining two overlapping budget-allocation systems, but no document yet says they are the same feature.
9. **Which wiring path connects model/provider context-window information into retrieval** (§30): extending `Enforcer.TurnInput` (changes the Enforcer's contract, lets retrieval itself be model-aware) versus a post-assembly trim step between the Enforcer and the existing `RequestCapacity` check (no Enforcer contract change, but retrieval stays model-blind and a second pass re-does work the first already did). Both satisfy the new spec's plain requirements; neither is clearly mandated by it.

---

## Phase 0 Coverage Checklist

Cross-referenced against the canonical plan's own Phase 0 "Required work" list (§ items 1-8) and §17 audit checklist:

- [x] Title usage (schema, prompt, parser, embedding, rendering, UI, export) — §1
- [x] Fixed Type/kind values and legacy `lore` — §2
- [x] Importance storage and ranking (including the always-on weight) — §3
- [x] Source-chat/provenance fields — §4
- [x] Permanent transcript row processing states / bookmark — §5
- [x] API/computer origin distinctions — §13 (computer path absent; API origin is never exposed in the UI today, confirmed by the shared `MemoryRowAdapter` card path having no origin-conditional rendering)
- [x] Roleplay-specific Pending actions (Add to Card) — §6
- [x] Scope and target eligibility — §6, §7
- [x] Companion scope and companion target joins — §6, §8
- [x] Companion-memory retrieval and prompt placement — §7
- [x] Companion deletion and its existing confirmation dialog(s) — §8
- [x] Conversation-level memory access settings — §9
- [x] Conversation-level analysis settings/markers — §10
- [x] Full archiver path (selection, bookmark/frozen-range analog, transcript formatting, limits, chunking, endpoint/model, prompts, output allowance, structured output, JSON parsing/repair, truncation, retry, partial filing, dedup, General-vs-companion assignment, Model Rule destination, cancellation, process-death recovery) — §5, §11, §12, §15, §16
- [x] Every current analysis entry point (batch, Memory Assistant settings, conversation menus, computer export/import) — §14 (batch/settings confirmed as the only entry point; conversation-menu and computer paths confirmed absent)
- [x] Hard-coded archiver assumptions (200,000-char ceiling, fixed output count) — §16 (no fixed output *count* found — the `maxSuggestions` cap is a user-configurable setting, not hard-coded; the 200,000-char ceiling is confirmed hard-coded in two places: `ArchivistBatchPlanner.MAX_REQUEST_CHARS` and `MemoryStore.MAX_TRANSCRIPT_CHARS`)
- [x] Current database version and every upgrade path touching memory/companion/transcript/deletion-cascade tables — §0
- [x] Backup/restore/seed/export/import formats — §17
- [x] Existing tests and gaps — §18
- [x] Companion deletion dialog and every data class it deletes — §8
- [x] Whether conversations already store memory-use or analysis policy — §9, §10 (memory-use: yes, single combined toggle; analysis: yes for exclusion only, no per-stream policy)

**All eight Phase 0 "Required work" items and every §17 checklist category have a corresponding section above.** No destructive migration was found anywhere in the current codebase. No legacy `lore` auto-conversion to Roleplay scope was performed by this audit (none exists to perform — Type and scope are already independent today, `lore` is a Type value, not a scope value). No roleplay `Add to Card` behavior was altered. No chunk constant was replaced. Companion deletion was traced, not altered.

**Phase 0 Completion Gate assessment (original pass):** the canonical plan's gate requires "the audit report exists, every affected code path is accounted for, the current archiver is diagrammed from transcript selection through Pending filing, the companion deletion path is documented, and no destructive migration remains unexplained." This report satisfies all four conditions as written above.

---

## 21. Addendum: `memory_retrieval_and_analysis_ui_copy.md` — Scope of This Extension

The newly-added canonical document specifies exact wording and behavior for controls that do not exist in the app today: **Use Model-Aware Limits**, **Maximum Memories Per Response**, **Maximum Memory Context**, **Memory Priority**, **Memory Match Strictness**, the **Current Retrieval Limits** read-only status area, and a per-endpoint/model **Context Window Override** — plus a revision of the existing **Conversation Amount Per Request** archiver-chunking control's exact token targets (Small ≈4,000 / Standard ≈8,000 / Large ≈16,000 / Custom, superseding the placeholder framing in the original counterplan's Phase 0 audit, §16 above).

Sections 22-28 below trace, for each item the follow-up instruction named, exactly where the current code already does something relevant, where it does nothing at all, and where two currently-separate subsystems will need to be connected. **No control described in the new document is implemented here.** Where this addendum recommends an implementation path, that recommendation is deferred to Phase 1+ exactly like every recommendation in §1-§19 above.

---

## 22. Every Current Cap on How Many Memories Can Be Retrieved for One Response

**Where it exists:** `RetrievalPolicy.kt` — `DEFAULT_TOP_K = 8`, `MIN_TOP_K = 1`, `MAX_TOP_K = 64` (lines 36-45). `RetrievalPolicy.boundTopK(raw)` bounds a `top_k` value read from the stored `retrieval_policy.policy_json` row; `Enforcer.assembleTurn` reads `policy.topK` (parsed via this bound) and passes it to `Librarian.search`, which caps the ranked pool at `topK` (`Librarian.rank`/`rankLexical`, `.take(topK)`) after `RetrievalBackfill.select` walks the pool consuming survivors until `topK` are kept, the pool is exhausted, or the scan cap (`topK + RetrievalBackfill.SCAN_MARGIN(64)`) is reached.

**What it currently does:** A single count cap, **8 by default**, shared by every enabled pool (General, Companion, Roleplay-eligible scopes) together — there is no separate per-pool count. The bound (1-64) exists only to reject corrupt/malformed stored policy data (doc comment, `RetrievalPolicy.kt` lines 31-34: "no UI writes these values and the defaults are unchanged... a user can only notice them if a stored policy row is malformed").

**How it relates to the new spec:** This is exactly the mechanism `Maximum Memories Per Response` needs to become user-facing — the bound, the default, and the enforcement point already exist; only a settings UI that writes `retrieval_policy.policy_json.top_k` (or an equivalent new field) does not.

**Can it be neutralized/extended without a DB migration?** No migration needed — `retrieval_policy` is a JSON blob column (`retrieval_policy.policy_json TEXT NOT NULL`) already capable of holding a new field with no schema change; `RetrievalPolicy.boundTopK` already has the exact bounding logic a new "Maximum Memories Per Response" field needs.

**Recommended path (not Phase 0):** Expose `top_k` through the new UI, reusing `RetrievalPolicy.boundTopK`; decide whether the single shared cap is retained as the sum-across-pools limit or split into independent per-pool maxima (the new spec's wording — "the maximum number of relevant memories that can be included with one AI response" — reads as one combined cap, consistent with the current single-`topK` architecture; **Memory Priority**, §24 below, is the mechanism that governs how that one shared cap is divided between pools, not a second cap).

**Tests needed:** see §28.

---

## 23. Every Current Token or Character Limit Applied to Retrieved Memory

**Where it exists:** `PromptAssembler.DEFAULT_CHAR_BUDGET = 6000` (**characters**, not tokens). `RetrievalPolicy.MIN_CHAR_BUDGET = 500`, `MAX_CHAR_BUDGET = 60_000` bound a `memory_char_budget` value from the same `retrieval_policy.policy_json` row (`RetrievalPolicy.boundCharBudget`). In `Enforcer.assembleTurn`, `policy.charBudget` is **shared** across lore notes, directly-fired card entries, and retrieved memories together (lines 373-381, 407 in the earlier trace): lore notes and directly-fired cards charge first, memories absorb whatever remains (`memoryAvailable = (policy.charBudget - loreChars - directChars).coerceAtLeast(0)`).

**What it currently does:** One shared, **character-counted** (not token-counted) budget across three different kinds of injected content, with retrieved memories getting whatever is left after lore/cards. `PromptAssembler.memoryCost(m)` (title + content + handling + never-assume line lengths) is the atomic unit charged per memory; a memory that doesn't fit is skipped whole (never truncated), and `RetrievalBackfill` backfills from the ranked pool so a skipped memory's slot goes to the next-best candidate rather than silently shrinking the result.

**How it relates to the new spec:** `Maximum Memory Context` is described as token-based ("the maximum amount of retrieved memory... in tokens" is implied by parity with `Maximum Memories Per Response` and the archiver's own token-based `Conversation Amount Per Request`, §3 of the new spec). The current budget is **characters**. The new spec's rule 7 ("add memories until the count or token maximum is reached, whichever occurs first") already matches the current code's dual-limit shape (`topK` **and** `charBudget` both gate the backfill walk today) — only the **unit** (chars → tokens) and the **exclusivity** (today the memory budget is *shared* with lore/cards, not a dedicated memory-only maximum) differ.

**Can it be neutralized/extended without a DB migration?** No migration needed — same `retrieval_policy.policy_json` blob; `IncludeTextPolicy.estimateTokens` (`preferences/includes/IncludeTextPolicy.kt` line 48) is an existing, already-used-elsewhere chars-to-tokens estimator (ASCII/non-ASCII aware) that could be reused for a token-based memory budget without inventing a second estimation heuristic.

**Recommended path:** Convert `PromptAssembler`'s memory-specific budget accounting to tokens (via `IncludeTextPolicy.estimateTokens` or an equivalent), and decide whether the new `Maximum Memory Context` field governs memories exclusively (a dedicated sub-budget, separate from lore/card accounting) or continues to share the same pool — the new spec's wording ("the maximum amount of retrieved memory that can be included," "leave more room for the current conversation") describes memory content specifically and does not mention lore/cards, suggesting a dedicated memory-only budget is the closer reading, but this is a product decision, not something this audit resolves (see §27's unresolved-decision note).

**Tests needed:** see §28.

---

## 24. Current Semantic-Match Threshold and Whether It Is Configurable

**Where it exists:** `Librarian.kt` line 57 — `private const val MIN_SIMILARITY = 0.30f`, a hard-coded cosine-similarity floor applied in `Librarian.rank` (called from `searchCore`, line 231: `rank(queryVec, ..., weights, topK, MIN_SIMILARITY)`). This is the **only** relevance floor applied to ordinary chat retrieval. It is distinct from the two other cosine thresholds already documented in the original audit's §7/§11-adjacent code: `PossibleMatchFinder.SEMANTIC_COSINE_THRESHOLD = 0.80f` (duplicate/update detection, unrelated to chat retrieval) and `enforcer.NearDuplicate.COSINE_THRESHOLD = 0.85f` (memory-vs-lore-note suppression, also unrelated to the relevance gate itself).

**What it currently does:** Every candidate below 0.30 cosine similarity to the query is excluded from ranking entirely (`if (sim < minSimilarity) return@mapNotNull null`, `Librarian.kt` line 154) before scope boosts, importance, or recency are applied — i.e., the floor is applied first, exactly as canonical plan §7.3 requires ("a relevance floor is applied before importance"). The lexical fallback path (`rankLexical`, used only when the vector index is incomplete or no model is installed) has a **different** relevance gate — `hits == 0` (zero whole-token overlap) rather than a cosine value — because it has no similarity score to threshold.

**Is it configurable today?** **No.** `MIN_SIMILARITY` is a `private const val` inside `Librarian`, not read from `retrieval_policy.policy_json`, not bounded by `RetrievalPolicy.kt` (which bounds `top_k`, `memory_char_budget`, and the three ranking weights, but has no similarity-threshold field at all), and not exposed in any settings UI. There is exactly one fixed threshold used for every user, every conversation, every memory pool.

**How it relates to the new spec:** `Memory Match Strictness` (Strict / Balanced / Broad) requires **three** distinct thresholds where **one** fixed threshold exists today. The new spec explicitly frames `0.30` conceptually as roughly the "Balanced" tier's "normal relevance threshold" (spec: "Balanced uses the normal relevance threshold") — i.e., the existing hard-coded 0.30 is a reasonable candidate default for the middle tier, with Strict needing a higher floor and Broad a lower one. The spec's rule "the ordinary UI does not expose an unexplained raw decimal threshold" means whatever values Strict/Balanced/Broad map to must be chosen behind the three labeled options, not surfaced as a raw number — consistent with how `MIN_SIMILARITY` is invisible today (just not adjustable).

**Can it be neutralized/extended without a DB migration?** No migration needed — `MIN_SIMILARITY` is pure application code; adding a `match_strictness` (or equivalent numeric threshold) field to the existing `retrieval_policy.policy_json` blob, bounded by a new `RetrievalPolicy.boundMatchThreshold`-equivalent function, requires no schema change.

**Recommended path:** Add a bounded threshold field to `RetrievalPolicy`/`retrieval_policy.policy_json` (three fixed values behind the Strict/Balanced/Broad labels, or a bounded custom range if the owner later wants finer control — the current spec only asks for three labeled tiers); thread it into `Librarian.rank`'s `minSimilarity` parameter (already present and already accepts a caller-supplied value — `searchCore` currently hard-codes `MIN_SIMILARITY` at its one call site, so this is a narrow, already-parameterized change) in place of the constant. Exact threshold values for Strict/Broad are evaluation work (consistent with the original counterplan's Phase 3 "do not finalize... until the harness produces evidence" instruction, applied here to a threshold rather than a chunk size) — not a Phase 0/1 decision.

**Tests needed:** see §28.

---

## 25. How General and Companion Memories Currently Compete for Retrieval Space

**Where it exists:** `Librarian.retrievalBoost` (lines 91-99, 116-131) — a fixed, non-configurable **scope-specificity ladder**, applied as an additive score boost after the similarity/importance/recency blend: `campaign` +0.12, `rp_character` +0.10, `world` +0.08, `project` +0.06, **`companion` +0.04**, `real_life` +0.02, **`global` +0.0**. A doc comment (lines 85-88) states the design intent explicitly: this is "a soft nudge among comparably relevant entries, never a hard sort tier... even the maximum stacked boost (~0.26) cannot let a weakly-relevant specific entry beat a strongly-relevant broader one... the `MIN_SIMILARITY` floor still gates everything."

**What it currently does:** General memories (`real_life` +0.02, `global` +0.0) and Companion memories (`companion` +0.04) are ranked into **one shared pool**, competing for the **same** `topK` count cap and the **same** shared `charBudget` (§22, §23) — there is no reserved/protected capacity for either pool. The only asymmetry is the small, fixed +0.04-vs-+0.02/0.0 scope boost, which the code's own comment says is deliberately too small to let a merely-present Companion memory outrank a more relevant General one, or vice versa — it is a tie-breaker among near-equally-relevant candidates, not a competition-resolution mechanism.

**How it relates to the new spec:** `Memory Priority` (Balanced / General Memories First / Companion Memories First) requires a **user-selectable** mechanism to let one pool win when both have relevant results competing for limited space, while still (per the spec's own rules) letting "unused capacity from one pool... be used by another enabled pool." **Nothing like this exists today** — the fixed scope-boost ladder is a single always-on ordering nudge, not a pool-priority selector, and it treats Companion as just one tier among seven scope values (behind campaign/rp_character/world), not as one side of a two-pool balance against General.

**Can it be neutralized/extended without a DB migration?** No migration needed — `retrievalBoost` is pure application code inside `Librarian`; adding a `memory_priority` field to `retrieval_policy.policy_json` and consuming it inside (or alongside) `retrievalBoost`/the backfill walk requires no schema change.

**Recommended path:** This likely needs new logic beyond a boost tweak, since "Balanced... without allowing one pool to consume all limited context before the other is considered" and "unused capacity... may spill over" describe **budget allocation**, not ranking order — closer in shape to the companion-protected-capacity mechanism the original counterplan's Phase 5 already calls for (original audit §7: "a tested protected capacity for relevant companion memories... unused protected capacity may spill over"). Recommend building **one** mechanism that serves both the counterplan's "protected companion capacity" requirement and this spec's "Memory Priority" control, rather than two overlapping systems — a genuine design question for whoever scopes Phase 5, flagged here rather than decided.

**Tests needed:** see §28.

---

## 26. Whether Several Memories Can Be Retrieved at Once

**Confirmed yes, already.** `RetrievalBackfill.select` (§7 of the original audit, `enforcer/RetrievalBackfill.kt`) walks a ranked candidate pool keeping up to `topK` (default 8, §22 above) survivors, backfilling past cooldown/near-duplicate/budget rejections so a filtered-out candidate frees its slot for the next-ranked one rather than shrinking the result. This is not new territory opened by the follow-up instruction — it restates and cross-references original-audit §7's finding that retrieval is **already** multi-memory, not hardcoded to one General and one Companion memory. No further action needed for this item beyond this cross-reference.

---

## 27. Where Retrieved Memories Are Inserted Into the Final Model Request

**Where it exists:** `ChatActivity.buildFrozenRegularRequest` (traced in detail for this addendum; the original audit's §7 covered `PromptAssembler`'s internal section ordering but not this outer message-list assembly). The system-message list is built in this fixed order, one `ChatMessage(role = ChatRole.System, ...)` per stage:

1. Companion persona + chat system instructions (`effectiveSystemMessage`, lines 7983-7997).
2. Model Rules injection, when `apply_model_rules` is on (lines 7999-8024).
3. Memory assembly — the Enforcer's full output (retrieved memories, Instruction-memory rules, user lore notes, fired card entries; §7/§27 above), when `memory_enabled` is on (lines 8074-8115).
4. Lore-book matches (classic lorebook tier, separate from the Enforcer's own lore-note handling; lines 8117-8123).
5. Conversation-summary injection, when the summarizer is active (lines 8125-8129).
6. **Then**, and only then, the actual conversation history / transcript / current user input (`resolveImagePartsForSend(requestMessages, requestIncludes)`, line 8135) — everything above is system messages that precede every history/user/assistant turn in the final array sent to the model.

**How it relates to the new spec:** Retrieval Behavior rule 9-10 ("keep fixed app safety, developer instructions, and fixed companion identity above retrieved memory context... insert retrieved memory before the conversation transcript") is **already satisfied** by this ordering — persona/system content is stage 1, memory is stage 3, and every stage precedes the transcript at stage 6. No gap found here; this section exists to give the follow-up instruction's question a direct, cited answer rather than to report a problem.

---

## 28. How the App Currently Determines Model Context Windows

This is the largest cluster of new findings in this addendum, spanning several of the follow-up instruction's bullets together because they are all facets of the same two currently-disconnected subsystems.

### 28a. The two subsystems, and why they don't talk to each other today

- **Subsystem A — memory retrieval budgeting** (`RetrievalPolicy`, `PromptAssembler`, `Librarian`, all traced in §22-§25): entirely **character**-based, entirely **model-blind**. Nothing in this subsystem reads the selected model, the selected endpoint, or any context-window value. `Enforcer.TurnInput.modelTag` (`enforcer/Enforcer.kt` line 109) is passed in but is used only for the card-retrieval/companion-name lookup path — never for a context-window decision.
- **Subsystem B — whole-request capacity checking** (`util/RequestCapacity.kt`, invoked from `ChatActivity` lines 6511-6600, §28c below): **token**-estimated (approximate, via `IncludeTextPolicy.estimateTokens`), **model-aware** (reads `apiEndpointObject.contextWindowTokens`), but runs **after** the entire request — including whatever Subsystem A already retrieved — has been fully assembled into a `FrozenChatPayload`. It can only **Send / Block / Warn**; it has no mechanism to feed a "please retrieve less" signal back into Subsystem A.

**This is the central wiring gap the new spec's "Use Model-Aware Limits" control needs to close**: today, a verified or manual context-window value exists (Subsystem B) but cannot influence how much memory gets retrieved in the first place (Subsystem A); it can only block or warn about the fully-assembled result after the fact.

### 28b. Verified/reported context-window metadata: none ingested from any provider

Full-text search across `providers/` (`ProviderEndpointInfo.kt`, `ProviderEndpointsParser.kt` — the OpenRouter/Choose-Provider discovery code) and the endpoint preference layer found **no** ingestion of a provider-reported context-length/context-window field from any API (`context_length`, `contextLength`, `context_window`, `contextWindow`, `max_context`, `maxContextTokens` — zero matches in the provider-discovery files). `ProviderEndpointInfo.kt` captures **pricing** per token (for the Choose Provider discovery chart) but nothing about context size. **No provider integration in the app today reports verified context-window metadata into any stored value.** The new spec's "(Reported)" vs "(Manual)" distinction in `Current Retrieval Limits` therefore has, today, no "(Reported)" data source at all — only the manual path (§28c) exists.

### 28c. The existing manual Context Window Override — already substantially built

`ApiEndpointObject.kt` lines 68-74:
```
var contextWindowTokens: Int? = null   // null = unknown, never blocks Send
var contextWindowModelId: String = ""  // exact model id this value belongs to
```
Editable in `ApiEndpointEditorActivity.kt` (field `field_context_window`, lines 246, 360-361, 576-580) and the endpoint quick-edit `EditApiEndpointDialogFragment.kt` (lines 53-54, 71-74, 145, 185-190, 280-284) — a plain user-typed token count, per endpoint profile, **tied to a specific model id** so a stale value from a previously-selected model does not silently apply after a model switch (`endpoint.contextWindowModelId == endpoint.model` / `== selectedModel` checks at both the editor and the consuming site).

**This already is, functionally, most of the new spec's `Context Window Override` control** — "accepts a user-entered token count," "a manual value overrides... until cleared," "leaving the field blank... leaves the model context Unknown" (an absent/zero `contextWindowTokens` already resolves to `null`, which `ModelContextCapacity.decide` already treats as "never blocks Send," i.e. Unknown) are all already true today. What's new relative to the spec is exposing this value under the `Memory Retrieval` section too (the spec: "may also be linked from Memory Retrieval") and — see §28a — actually using it to influence retrieval, not only the post-hoc whole-request check.

### 28d. Whether reported metadata can be absent, stale, or wrong

- **Absent:** yes, the common case — `contextWindowTokens` defaults to `null`, and `ModelContextCapacity.decide` treats `null` (or `<= 0`) as `Send` unconditionally (line 357: `contextWindow?.takeIf { it > 0 } ?: return ModelContextDecision.Send`) — i.e., **unknown context never blocks or warns today**, consistent with the new spec's "missing or unknown context information does not authorize an invented fallback."
- **Stale:** guarded against for the one stale case the current code models — a manual value entered for a previously-selected model is prevented from silently applying to a newly-selected model via the `contextWindowModelId` match check (§28c). There is no other staleness concern today because there is no "reported" data source (§28b) that could go stale independently of user action.
- **Wrong:** no validation beyond a plain integer field — a user-mistyped value is accepted as-is and used exactly like a correct one; nothing in the current code cross-checks a manual value against anything.

### 28e. Whether the app currently reduces request content automatically

**No.** Traced in full at `ChatActivity.kt` lines 6511-6658: `buildFrozenRegularRequest` assembles the **complete** request (system prompts, Model Rules, full memory retrieval, lore, summary, full history) with no awareness of context window at any point in that assembly. Only afterward does `RequestCapacity.measure` + `ModelContextCapacity.decide` evaluate the finished payload against `contextWindowTokens`, producing exactly one of:
- `Send` — nothing shown, request proceeds as assembled.
- `Block` — a hard, non-dismissable-except-OK dialog (`showRequestHardBlock`, `request_context_exceeded_title/body`); **the request is not sent, and nothing is trimmed** — the user must manually change something (shorten the message, switch models, adjust settings) and retry.
- `WarnRange` / `WarnApproximate` — a dialog with **"Send Anyway"** or **"Cancel"** (`showRequestWarning`, lines 6645-6658); choosing "Send Anyway" sends the **unmodified** over-budget payload; choosing "Cancel" sends nothing. **No automatic reduction occurs in either branch.**

This confirms the new spec's rule that model-aware behavior may only ever *reduce the selected memory limits proactively before assembly* (when Use Model-Aware Limits is On) — there is no precedent in the current code for the app silently cutting content on the user's behalf; every existing context-capacity outcome is either fully automatic pass-through (`Send`), a full stop requiring the user to act (`Block`), or an explicit choice (`Send Anyway`/`Cancel`). A future "Use Model-Aware Limits: On" implementation that proactively shrinks the memory count/token maximum **before** assembly would be a new category of behavior — automatic, silent (from the request-sending perspective) reduction — that today's design has deliberately avoided everywhere else in the request pipeline; the new spec's requirement to "show the reported or manual context limit and any reduction it applies" (rather than reducing silently) is consistent with that existing design posture and should guide the Phase 1+ implementation.

---

## 29. Every Place a New Memory Retrieval Setting Would Need Storage, Backup, Restore, Export, or UI Support

Three storage tiers already exist in the app, each with different backup coverage — a new Memory Retrieval setting's storage location determines which of these it inherits:

| Tier | Example of existing use | Backed up today? |
|---|---|---|
| **`retrieval_policy` table** (inside the encrypted `companion_memory.db`, one JSON blob row) | `top_k`, `memory_char_budget`, `{similarity, importance, recency}` weights (all read via `RetrievalPolicy.kt`) | **Yes** — `MemoryStoreData.retrievalPolicyJson` (`MemoryData.kt` line 617) is part of the whole-store shape `MemorySeedCodec.parse`/`serialize` already round-trips (original audit §17); rides every memory-database backup/restore/seed-import automatically. |
| **Per-chat settings file** (`settings.<chatId>`, plain `SharedPreferences`-backed, one file per chat) | `memory_enabled`, `memory_excluded`, `apply_model_rules` (original audit §9-§11) | Survives chat rename (via `PerChatSettingKeys.ALL`, original audit §9) and travels with the chat's own portable backup/export path (`preferences/backup/portable/*`, not traced in depth in this pass) — **not** part of the memory-database backup at all; a new per-chat retrieval setting would need its key added to `PerChatSettingKeys.ALL` (already an enforced, test-checked registry per that file's own doc comment) but needs no `MemoryStore` migration. |
| **Global app `SharedPreferences`** (`Preferences.getGlobalBoolean`/`getGlobalString`, device-wide, not per-chat, not in the encrypted memory database) | `default_memory_enabled` (app-wide default for the per-chat toggle) | **No memory-backup coverage found** — `default_memory_enabled` appears only in `MemoryControlsActivity.kt` (UI) and `Preferences.kt` (storage); it is not referenced by `MemoryExporter.kt` or `MemorySeedCodec.kt`. This is a real gap for *any* global app setting, not new to this addendum, but directly relevant: **`Use Model-Aware Limits`, `Maximum Memories Per Response`, `Maximum Memory Context`, `Memory Priority`, and `Memory Match Strictness` read, in the new spec's wording, as global/device-level settings** (no per-chat framing anywhere in the new document, unlike `memory_enabled`), which would put them in this least-backed-up tier by default unless deliberately placed in `retrieval_policy` instead. |
| **Per-endpoint profile fields** (`ApiEndpointObject`, via `ApiEndpointPreferences`) | `contextWindowTokens`, `contextWindowModelId` (§28c) — the existing Context Window Override | Endpoint profiles are not part of `MemoryStoreData` either; their backup path was not traced in this pass (outside the memory system's own scope) — flagged as a place to verify before Phase 1 ships a Memory-Retrieval-linked view of this field, since the spec explicitly says the control "may also be linked from Memory Retrieval." |

**Recommendation (not a decision — a placement question for whoever scopes Phase 1's storage):** given every new control in the new spec's §1 is global/device-scoped and several already have a natural, already-backed-up home in `retrieval_policy.policy_json` (which already stores conceptually identical settings — `top_k`, a char/token budget, ranking weights), placing `Use Model-Aware Limits`, `Maximum Memories Per Response`, `Maximum Memory Context`, `Memory Priority`, and `Memory Match Strictness` there (extending `RetrievalPolicy.kt`'s bounding functions with matching new ones) would give them backup/restore/export coverage for free and keep one settings shape instead of three. This is flagged as a recommendation, not chosen — placement is an implementation detail Phase 1 should confirm, not something Phase 0 decides.

**UI touchpoints identified (existing screens that would need new sections, none altered in this pass):** `MemoryControlsActivity.kt` (already hosts `default_memory_enabled` and other device-wide memory defaults — the closest existing precedent for a global "Memory Retrieval" section) and/or `AdvancedMemorySettingsActivity.kt` (already hosts Archivist/analysis-side settings including per-chat-adjacent `getArchivistMaxSuggestions`/`getArchivistMinImportance`/etc. — the closest existing precedent for the `Conversation Amount Per Request` chunk-size revision). Neither screen currently has any retrieval-limit or match-strictness control; both are named here only as the most structurally similar existing screens, not as a placement decision.

---

## 30. How the Existing Selected Model and Provider Information Reaches the Retrieval Layer

**Today: it does not**, beyond one unused string. Traced explicitly for this addendum:

- `ChatActivity` knows the selected model (`selectedModel: String`) and the selected endpoint (`apiEndpointObject: ApiEndpointObject?`, carrying `contextWindowTokens`/`contextWindowModelId`) at the point it calls `buildFrozenRegularRequest`.
- `buildFrozenRegularRequest` passes `modelTag = selectedModel` into `Enforcer.TurnInput` (§28a) — but **not** the endpoint object, and not any context-window value.
- `Enforcer.assembleTurn` never reads `input.modelTag` for anything context-window-related (confirmed by full-text trace of `Enforcer.kt` — `modelTag` is read only where the Archivist/retrieval-diagnostics code paths use a model tag as an embedding-index cache key, an unrelated concern).
- The context-window value (`apiEndpointObject.contextWindowTokens`) is read for the **first and only time** back in `ChatActivity`, **after** `buildFrozenRegularRequest` already returned a fully-assembled payload (§28e) — i.e., by the time any context-window information is consulted, retrieval has already happened with none of it.

**How this relates to the new spec:** implementing `Use Model-Aware Limits` requires threading model/endpoint context-window information **into** the retrieval call, not just consulting it afterward. Two structurally different paths would satisfy this, presented as options rather than a choice made here:
1. **Extend `Enforcer.TurnInput`** with a context-window value (and the `Use Model-Aware Limits` on/off state) so `Enforcer.assembleTurn` can compute an effective `topK`/`charBudget` (or token budget, per §23) *before* calling `Librarian.search` — the more thorough approach, but changes the Enforcer's public contract and requires `ChatActivity` to resolve the effective context window (reported-or-manual-or-unknown) *before* calling it, reordering today's "assemble first, check capacity after" flow specifically for the memory stage.
2. **Leave `Enforcer` unchanged and add a Model-Aware reduction step between memory assembly and the final `RequestCapacity` check** — re-run or post-trim the memory system message specifically when the whole-request check would otherwise warn/block, using the already-known context window at that later point. Simpler to wire (no `Enforcer` contract change) but means memory retrieval itself stays model-blind and a second pass has to un-pick what the first pass already assembled.

Neither is chosen here; this is exactly the kind of architecture question the original audit's Unresolved Decisions section (§20) exists to surface rather than resolve.

---

## 31. Tests Required for the New Controls

None of the following exist today (there is no existing test suite for any of these controls, since none of them exist in application code yet). Recommended coverage, organized by control:

**Use Model-Aware Limits — On vs Off:**
- On, verified/reported context available (once a reporting source exists, §28b) → memory limits reduce only when the assembled request would not fit; reduction is visible (selected vs effective values both shown, per spec §"Current Retrieval Limits").
- On, no context available (Unknown) → **no** reduction applied, no invented fallback value used, status area shows `Unknown`, and the user's selected limits are used exactly as if the toggle were Off for that turn (spec: "missing or unknown context information does not authorize an invented fallback for live memory retrieval").
- On, manual override present → status area shows `(Manual)` and the manual value is used as the effective limit source, taking precedence over any reported value (spec: "a manual value overrides reported metadata until cleared").
- Off → selected limits are used unconditionally regardless of any known/reported/manual context value; no reduction, ever, even when the assembled request would clearly exceed a known context window (matches spec: "a request may fail... but this remains the user's choice").

**Reported / manual / unknown context — resolution order:**
- Manual set + reported available → manual wins (once a reporting source exists).
- Manual blank + reported available → reported value used.
- Manual blank + no reported source → Unknown (today's actual state for every provider, per §28b).
- Model switched after a manual value was set for a different model → the existing `contextWindowModelId` mismatch guard continues to degrade to Unknown, not a stale carried-over number (regression test against `ApiEndpointEditorActivity`/`EditApiEndpointDialogFragment`'s existing behavior, §28c).

**Maximum Memories Per Response (count limit):**
- Retrieval never exceeds the selected count regardless of how many eligible/relevant memories exist.
- A count lower than the number of eligible relevant memories does not pad with irrelevant ones to reach the count (spec: "This is a maximum, not a required count").
- Interaction with the token/char maximum: whichever limit is hit first stops further additions (spec rule 7; extends the existing dual-limit shape already covered by `RetrievalBackfillTest`/`LibrarianRankingTest`'s current topK-only coverage).

**Maximum Memory Context (token limit):**
- Token estimation for the memory block matches whatever estimator is chosen (reuse-`IncludeTextPolicy.estimateTokens` regression test, §23) within its documented approximation.
- A memory that doesn't fit remaining budget is skipped whole, never truncated (extends existing `PromptAssemblerTest`/`RetrievalBackfillTest` coverage of today's char-based equivalent).

**Memory Priority (Balanced / General First / Companion First):**
- Balanced: with both pools enabled and more relevant results than fit, neither pool is starved to zero when both have relevant candidates (spec: "without allowing one pool to consume all limited context before the other is considered").
- General First / Companion First: the named pool's relevant results are seated before the other pool's when they compete for the last available slots/tokens.
- Priority never seats an irrelevant memory (below Match Strictness) merely because its pool has priority (spec: "priority never makes an irrelevant memory eligible").
- Unused capacity from a low-priority (or disabled) pool is still available to the other enabled pool (spec: "unused capacity from one pool may be used by another enabled pool") — regression-relevant since today's shared-budget behavior (§25) already does this by default and must not regress once priority is added.

**Memory Match Strictness (Strict / Balanced / Broad):**
- Each tier's threshold is applied as a hard floor before ranking/importance/scope-boost, exactly like today's single `MIN_SIMILARITY` floor (extends `LibrarianRankingTest`/`LibrarianSearchCoreTest`'s existing floor-ordering coverage, §24, to three values instead of one).
- Strict excludes candidates Balanced would include; Broad includes candidates Balanced would exclude — basic monotonicity across the three tiers.
- The lexical (no-embedding-model) fallback path's zero-hits gate is not accidentally bypassed or double-gated when a strictness tier is layered on top of it (today's `rankLexical` has its own independent relevance gate, §24 — a new strictness setting must not silently apply a cosine-shaped threshold to a path that has no cosine score).

**Conversation Amount Per Request (revised token targets, §3 of the new spec — analysis chunking, not live retrieval):**
- Small/Standard/Large resolve to approximately 4,000/8,000/16,000 transcript tokens respectively (once implemented on top of the original audit's §16 token-estimation gap — today's `ArchivistBatchPlanner` is character-based with no token awareness at all, so this is new work, not a variant of an existing test).
- Auto resolves using verified/manual limits when available and degrades safely (not to the retired 200,000-character ceiling, which the new spec explicitly says "is not retained as a fallback") when they are not.
- Whole messages remain preserved (regression test against the existing `ArchivistBatchPlannerTest` row-atom guarantee, original audit §16).

---

## Addendum Coverage Note

Every bullet in the follow-up instruction that added `Memory System/memory_retrieval_and_analysis_ui_copy.md` to scope is addressed above: retrieval count cap (§22), token/char limit on retrieved memory (§23), semantic-match threshold and its configurability (§24), General/Companion competition for retrieval space (§25), multi-memory retrieval confirmation (§26), prompt-insertion point (§27), model-context-window determination including verified-provider-metadata coverage, manual-override support, and absent/stale/wrong metadata handling (§28), automatic content reduction (§28e), storage/backup/restore/export/UI touchpoints (§29), how model/provider information reaches the retrieval layer today (§30), and the required test matrix (§31). No control described in the new document was implemented, no schema was changed, and no product decision was made — three additional genuine open questions raised specifically by this addendum are recorded in §20 (items 7-9) alongside the original six. This extends, and does not replace, the original Phase 0 Completion Gate assessment above: the audit report continues to exist, every code path the new instruction named is accounted for, and no destructive migration was introduced by this pass either.
