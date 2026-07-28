# External Memory Analysis + RAG Compatibility — Counter Plan (Revision 6, independently reviewed)

**Document type:** Planning/architecture only — no code, database, prompt, UI,
or string changes were made with this document.
**Baseline:** verified against the current repository and remote branch state
through `f188184` (`claude/memory-archive-analysis-ih8z5x`) and main
`0e9cc1a`, 2026-07-24. The document-only audit work itself is newer than the
app-code baseline; no app code was changed for this plan.
**Relationship to other documents:** this is a response to
`Speak GPT External Memory Analysis and Deduplication Plan` (the ChatGPT-
written plan the owner uploaded, 2026-07-24 — "the audit plan" below). It
adopts that plan's verified findings, corrects one of them, and replaces its
11-stage delivery program with a smaller one.
**Revision 2 (2026-07-24, same day):** the audit plan's author reviewed
Revision 1 of this document against the branch and returned ten amendments;
the owner relayed them in chat. All ten were re-verified against the code
and are incorporated below (durable run record + crash recovery, the
minimal packet-item ledger, the shared filing boundary, placement-aware
dedup, rename-safe rejected-draft identity, populating the EXISTING typed
transcript context columns — Revision 1 wrongly said they didn't exist —
overlapping exclusion flags, toggle semantics decided before the file phase,
stable IDs in the target catalog, and "Possible match" wording). This
document is the canonical roadmap; the audit plan remains the detailed
package/UI/test reference, read with the `plot_ledger` correction in §2.
Revision 4 supersedes the overlapping-flags proposal with the simpler
storage/injection separation in §4(f) and §5.4.
**Revision 3 (2026-07-24, same day):** the separate UI-copy proposal has
been folded into §6 so the owner and future agents have one canonical plan.
The correctness phase receives only the few labels required by visible
behavior; the larger computer-workflow vocabulary is reserved for the file
phases. A fresh-agent
correctness review gate was added in §8. No app strings are approved or
changed by this revision.
**Revision 4 (2026-07-24, same day):** a repository-backed audit of the
existing RAG read path, classic lorebook path, provenance, indexing, and
source controls has been added in §5. It confirms that the app already has a
substantial RAG engine and that its per-chat prompt path already supports all
four source combinations. It also finds correctness defects that can hide
eligible memories, confusing global controls that cannot express
saved-memories-only, and an architectural coupling between prompt injection
and transcript archiving. The plan now preserves both lore systems, separates
read controls from write consent, defines the minimum RAG corrections and
evaluation matrix, and rejects unnecessary schema/ontology expansion. No app
code or strings are changed by this revision.
**Revision 5 (2026-07-24, same day):** §9 turns the earlier lean file idea
into a concrete computer-agent architecture and is authoritative wherever it
adds detail to, or narrows, §4. It defines the exact exchange package,
record/revision/tombstone/evidence contracts, a shared retrieval
specification, a disposable desktop file workspace, proposal operations for
add/revise/merge/retire, stale-package and conflict behavior, incremental
full/delta exchange, encryption and lost-key behavior, crash-safe import,
compatibility rules, UI states, and a Phase A–D implementation path. It also
records two repository constraints that earlier revisions did not resolve:
the current portable JSON does not export `deleted_ids` and imports existing
IDs as add-or-skip rather than reconcile; and the separate `LoreBookStore`
has stable UUIDs and timestamps but no export, revision feed, or tombstones.
The existing portable JSON and encrypted recovery package remain separate
products. No app code or strings are changed by this revision.
**Revision 6 (2026-07-24, same day):** a fresh agent read all of Revision 5
and spot-checked its load-bearing claims against the repository. The review
confirmed the technical findings and end-state architecture, then found
nine document-level corrections that are incorporated here: analysis no
longer claims chats are unavailable or asks the user to keep a screen open;
the API run moves under a dedicated foreground-service pattern; the existing
embedding-model prerequisite is preserved pending an explicit owner decision;
the duplicate Phase A numbering is replaced with named workstreams; retired
Protected/handling machinery is removed from the live design; exchange
journals remain dormant until a computer workspace is first created; the
working user term for an internal `retire` proposal is consistently
**Archive Suggestion**; plaintext-transfer services are included in the
privacy warning; and API/computer-run coexistence is explicit. All visible
copy remains provisional and the owner has deferred wording approval until
the architecture is complete. No app code or strings are changed by this
revision.

> **Approval note:** nothing in this document is approved wording or an
> approved screen. Every user-facing decision here goes through the owner in
> chat before it is built, per `owner_approved_rules.md` and the OWNER
> APPROVAL GATE in `CLAUDE.md`. Plan documents are never approval.

---

## 1. The goal, in the owner's words

The owner wants the Memory Assistant's archive work (reading finished
conversations, proposing memory drafts) to be doable by **an agentic AI on
the user's own computer** — Claude Cowork / Claude Code, ChatGPT's desktop
agent, and similar apps that can read and write local files — so that people
can use the **subscription they already pay for** instead of a per-token API,
and so the feature survives instability in any one AI provider.

This is not the "local LLM" case (LM Studio / Ollama), and it is not the
old "upload a file to a chat window" case. The target is an agent with file
tools. That has a direct design consequence:

**Files are the right interface.** A self-describing package the agent can
read, search, and answer with a result file is exactly how these apps work.
The audit plan's core proposal (export a package, import a strict result
file, drafts only) is therefore the correct shape, and this counter plan
keeps it. What this counter plan changes is the *amount of machinery* built
around it.

A second consequence, worth stating because it is an advantage the API route
does not have: an agentic AI can be *instructed to search* the existing-
memories reference file before proposing anything. A plain API call cannot
look anything up — it only knows what is pasted into its prompt. So the
duplicate problem is structurally easier on the file route than on the API
route, provided the package ships a searchable memory snapshot and
instructions to use it.

---

## 2. What was verified from the audit plan (and one correction)

The audit plan's most serious findings were checked directly against the
code at the baseline commit. These are REAL:

| Verified finding | Where |
|---|---|
| A turn appended mid-run can be marked processed without ever being analyzed (append goes into the newest unprocessed row; the run marks whole rows processed afterwards) | `MemoryStore.appendTranscriptTurn` (~line 2209), `Archivist.run` → `markTranscriptsProcessed` |
| The only "one run at a time" guard is a screen-instance boolean | `MemoryAssistantActivity.running` (line ~100) |
| Response parsing takes first `{` to last `}`; a missing `memories`/`model_rules` array counts as a valid empty result; importance is clamped, not rejected | `ArchivistResponseParser` |
| Duplicate suppression is exact, case-sensitive title+content only | `MemoryStore.memoryExistsWithText` |
| Rejected-draft suppression is keyed to the mutable chat NAME, so a rename defeats it | `Archivist.fileMemoryDrafts` → `isDraftRejected(title, content, chatName)` |
| Proposed target names resolve to the first case-insensitive match; duplicate names resolve silently | `Archivist.resolveTarget` (`.take(1)`) |
| "Use memory in this chat" is doing two unrelated jobs: it controls prompt injection and also causes newly captured transcripts to be marked excluded. Re-enabling it only changes the preference and does not re-queue those rows, while "Archive this chat" can re-queue all excluded rows without knowing why they were excluded. | `Preferences.getChatMemoryEnabled`, `ChatActivity.recordTranscriptTurn`, `TranscriptRecorder`, `QuickSettingsBottomSheetDialogFragment` (~lines 722–747), `MemoryStore.setChatTranscriptsExcluded` |
| Capture stamps no scene context — the transcripts table HAS typed `world_id` / `roleplay_character_id` / `user_persona_id` columns (schema lines ~494–496), but `appendTranscriptTurn` never populates them, and `quick_settings_json` holds model + sampling params only. (Revision 1 of this document wrongly said the columns didn't exist; the audit plan had this right.) | `MemoryStore` transcripts schema, `ChatActivity.recordTranscriptTurn` (~line 4883) |
| The full-failure log line hardcodes "No memories were created … memories=0" even when earlier chunks of the run did file drafts (the run RECORD's count is accurate; the wording is not) | `Archivist.run`, "full_failed" branch |

**Correction — `plot_ledger` is NOT a conflict.** The audit plan flags the
extraction prompt's `plot_ledger` card section as contradicting owner rules
and demands a decision to remove it (its finding 4.2, Stage 0 item 3, owner
decision 7). That is a misreading of this repository's history. What the
owner retired is the *automatic* updating of a campaign's `story_so_far` /
Plot Ledger **columns** by any AI. The `plot_ledger` card **section**
(campaign Zone 2, DB v7) is live, and user-approved card-placement
SUGGESTIONS to it are explicitly the sanctioned path (owner rulings, July 9
2026 — see the Phase 6 scope limits in `CLAUDE.md`). The prompt is correct
as written. **No agent should "fix" this based on the audit plan.**

Also noted: several audit-plan "defects" are deliberate owner-era choices,
not bugs — the lenient JSON extraction (sloppy models wrap JSON in prose),
importance clamping, and the deliberately-narrow rejected-draft key ("never
broad similarity suppression" is an owner rule). Stricter versions are right
for the new *external* trust boundary; changing the existing API-path
behavior is a decision, not a repair.

## 3. Prior art ("hasn't someone solved this?")

Yes and no. Mem0, LangMem, Letta/MemGPT, and Zep/Graphiti have all published
designs for exactly this problem, and the audit plan already borrowed the
right patterns from them: retrieve related existing memories *before*
extraction, give the model proposal authority rather than mutation
authority, perform deterministic dedup in the app, and keep human review as
the final gate. Those patterns are adopted here too.

What does NOT exist is drop-in code: these are server-side Python frameworks
built around cloud vector databases. Nothing ships as an Android library
against SQLCipher. So we inherit their *ideas* — the architecture here
already matches them more closely than most shipping products (drafts-only,
Pending review, origin tracking) — and write our own small implementation.

---

## 4. The external-workflow intent (mapped to the authoritative A–D plan in §§9–10)

The audit plan's Stage 0–11 program front-loads a generalized run
coordinator, an item state machine, claim leases, an exclusion-reason table,
an acceptance-validator rework, and a semantic layer before the export
button exists. Every one of those has value, but in a codebase where CI is
the only compile gate and regressions land on one user's daily driver, that
much rebuilt machinery is itself the biggest risk. This plan cuts to what
the file feature actually requires. Revision 5 keeps these product
constraints but replaces this section's compressed delivery outline with
the complete architecture and Phase A–D path in §§9–10. In particular, the computer may
propose maintenance operations as well as additions, but it still has
**proposal authority only**.

### Phase A precursor — Make the existing engine truthful (small, independent fixes)

Each item below is a small, separately shippable change to the CURRENT API
Archivist. They fix real defects whether or not the external feature ever
ships, and (a)–(c) are hard prerequisites for it.

- **(a) Seal claimed transcript rows, with a durable run record and crash
  recovery.** One new column (`claim_run_id` or equivalent) on
  `transcripts`. When a run (or later, an export) selects rows, it stamps
  them in one transaction; `appendTranscriptTurn` never appends into a
  stamped row (a new turn starts a new row); `markTranscriptsProcessed`
  only advances rows still carrying that run's stamp; failure/interruption
  clears the stamp. Because a killed process runs no cleanup code, the
  stamp alone is not enough: a small durable **active-run record** (in the
  store, not an activity field) marks the one API run allowed to be live,
  and a startup/next-run reconcile releases claims whose run is no longer
  live — the same recover-at-startup pattern `RenameJournal.reconcile`
  already established. An externally-exported package's claims are the one
  kind deliberately NOT auto-released (they wait for import, cancel, or
  replacement).

  The activity must not own the long-running analysis coroutine. Add a
  dedicated **Memory Analysis foreground service** following the proven
  `GenerationForegroundService` keep-alive pattern, but declare the
  appropriate data-sync service type rather than reusing its media-playback
  service. The service owns the run, CPU/Wi-Fi keep-alive, progress
  notification, and terminal cleanup; `MemoryAssistantActivity` launches or
  observes the durable run and may be destroyed without cancelling it. A
  notification tap returns to Memory Assistant. If the service cannot start,
  do not claim rows or begin an Activity-owned fallback run: show a durable
  failure and leave all work retryable. Process/service death still uses the
  durable reconcile above. Additive migration, manifest/service work, version
  bump.
- **(b) Normalize the duplicate check, placement-aware.** Compare
  versioned Unicode-NFKC, locale-independent case-folded,
  whitespace-collapsed content (title excluded — models retitle the same
  fact; punctuation/negation is not stripped) **plus scope and the sorted
  stable target IDs**. The same
  sentence in two different fictional worlds is legitimately two memories,
  so placement is part of the identity. A match against an active or draft
  record with the same kind is a deterministic duplicate; if the proposed
  kind would change rendering semantics (especially fact ↔ instruction),
  treat it as a **Possible Match**, not a silent metadata overwrite. A match
  against an archived or superseded record is also a Possible Match requiring
  the user's restore / replace / keep-separate decision, not a silent skip or
  resurrection.
  Re-run identity and target validation when a draft is accepted or edited,
  because the library may have changed since filing. Exact-match semantics
  stay deterministic; no similarity guessing here.
- **(c) Make rejected-draft identity rename-safe.** Key rejected drafts to
  the chat ID instead of the chat name — and because chat IDs are
  name-derived hashes that change on rename, add `rejected_drafts` to the
  set of tables `MemoryStore.repointChat` carries across a rename (it
  already carries transcripts and cooldowns). The owner's
  deliberately-narrow suppression semantics stay otherwise unchanged.
- **(d) Send the valid target catalog in the prompt — stable IDs alongside
  names.** The runner already loads live worlds/campaigns/characters/
  projects to resolve names; include them in the user message so the model
  stops guessing, with their stable IDs, and prefer results that reference
  IDs. Log (never silently pick) when a proposed name matches more than
  one record. The external package's result contract is IDs-only (§9.4);
  the API route can accept names transitionally.
- **(e) Stamp scene context at capture, in the typed columns that already
  exist.** The transcripts table already has `world_id`,
  `roleplay_character_id`, and `user_persona_id` columns — populate them in
  `appendTranscriptTurn`, and add `campaign_id`/`project_id` columns in the
  same migration as (a). Scene identity does not belong muddled into the
  sampling-settings JSON.
- **(f) Separate source injection from archive consent.** The owner-approved
  rule is that storage and injection are independent: who writes a thing and
  when it is delivered are separate decisions. Therefore:
  - **Use saved memories in this chat** controls RAG/scene/card retrieval and
    prompt injection only.
  - **Use lore books in this chat** controls classic trigger-based lorebook
    injection only.
  - **Archive this chat** alone controls whether new turns are eligible for
    API or computer review.
  Turning either source off must not mark transcript rows excluded, and
  turning it back on must not alter the review queue. This removes the need
  for an ongoing "memory off" exclusion flag. Preserve legacy rows that were
  already excluded under the old coupling; do not silently reinterpret or
  re-queue them. When the user re-enables **Archive this chat**, use the
  explicit Include Earlier Messages / New Messages Only choice in §6. That
  is simpler and more truthful than overlapping exclusion machinery, and it
  must land before Phase B ships because export eligibility depends on it.
- **(g) Truthful failure wording** — the full-failure path reports the real
  draft count instead of a hardcoded zero. (Status wording is owner-approved
  text — any visible change goes through the owner.)

### Phases B–D precursor — The file package and safe return loop

The interface the owner actually asked for. Adopt the audit plan's package
design (§11: ZIP with README, prompt, schema, per-conversation JSON,
`targets.json`, `existing_memories.jsonl`, read-only lore/card references,
result template) and its import rules (strict parse, reject-don't-coerce,
proposals only, per-conversation atomic commit, replay = no-op) — those sections
are good and this plan incorporates them by reference rather than restating
them. The package is a searchable reference corpus, not a copy of the app
database or vector index.

What v1 keeps from the audit plan's machinery:

- a **packet ledger plus a minimal packet-item ledger**. The packet table
  holds packet id, run id, created/imported timestamps, status, and a plain
  hash of the exported file bytes (no RFC-canonical-JSON machinery). The
  packet-item table maps each conversation item to its frozen transcript
  IDs, its input hash, and an item status
  (awaiting / committed / failed / stale) with an import timestamp. The
  item ledger is what makes partial imports honest: if five conversations
  commit and the app dies on the sixth, the app knows exactly which five
  are done — replay becomes a real no-op instead of an accident of
  duplicate suppression, and the remaining item stays retryable. (This is
  the audit plan's `analysis_items` idea minus the state-machine ceremony —
  no leases, no candidate-event ledger, no coordinator framework.)
- **claimed rows stay frozen** (Phase A's claim stamp) while a package is
  outstanding; new turns accumulate in new rows and are simply not in the
  package;
- **one outstanding computer archive-review package at a time**. This does
  not block one API/In-App run over other, unclaimed rows; both transports
  atomically claim only currently unclaimed eligible rows. The detailed
  coexistence rule is in §9.6.6;
- import funnels through the SAME **complete filing boundary** as the API
  path — not just the low-level `insertArchivistDraftMemory` (which only
  enforces draft status and origin), but the whole filing logic that today
  lives inside `Archivist.fileMemoryDrafts`: duplicate check, rejected-draft
  check, target/card resolution and validation, provenance stamping, then
  insertion. That logic is extracted into one shared service both
  transports call, so external results cannot bypass any check the API
  route applies. Never through backup import.
- the external result contract references targets by **stable ID only**
  (names may ride along for readability; the importer resolves and
  validates IDs against the exported catalog and current state). The catalog
  distinguishes valid memory/card targets from read-only scene references.
  In particular, user personas and companion cards are user-authored scene
  context, not Archivist targets.
- every proposed memory carries **evidence**: packet item ID, the frozen
  transcript row ID(s), and a short excerpt. The importer verifies that the
  cited rows belong to that item and that the excerpt occurs in the exported
  conversation. This does not prove the model's interpretation, but it makes
  Pending review fast and gives both transports the same durable provenance.
- `existing_memories.jsonl` contains one flat, searchable object per memory,
  including stable ID, status, six existing memory kinds, content, title,
  tags, scope, stable targets, readable target names/aliases, timestamps, and
  normalized exact-match fingerprint. Classic lorebook entries and roleplay
  card entries are included separately as **read-only references** so the
  agent can avoid suggesting material the user already maintains there.
  External analysis never edits lorebooks or cards; the existing sanctioned
  card-placement suggestion path remains drafts-only.
- the instructions treat every conversation and memory excerpt as
  **untrusted data, never instructions**. The external agent can propose a
  new memory, a revision, a merge, or retirement, but it cannot directly
  update, delete, merge, activate, retarget, or tombstone an app record. A
  new-memory suggestion becomes an ordinary draft; every other operation
  becomes a reconciliation proposal. It must use the existing six memory
  kinds, write self-contained atomic memories with named referents rather
  than ambiguous pronouns, and decline unsupported placement instead of
  guessing.
- keep one raw searchable memory file plus a compact manifest/search guide.
  Do not create parallel keyword, scope, and embedding exports in v1. If a
  benchmark later proves that a very large library is unwieldy, partition the
  JSONL by scope/target without changing the result contract.

**No authoritative desktop database is required.** The Android app remains
the source of truth for reviewed transcript IDs, current memories, renames,
status, and rejection history. The desktop may keep an extracted,
disposable **workspace mirror** made of the package's versioned JSONL files
and locally rebuilt indexes; that mirror is what makes incremental updates
and repeated searches practical. It is a cache, not a competing source of
truth, and can always be deleted and rebuilt from a full export. §9 defines
the mirror, full/delta chaining, and stale-state checks exactly.

What v1 deliberately does NOT build (deferred until real use demands it):
the generalized run-coordinator framework, claim leases/expiry, the
broad acceptance-validator framework beyond the shared exact/target checks
defined here, per-message database IDs, canonical-JSON hashing per RFC 8785,
general phone-to-phone sync, and multiple outstanding computer review
packages. A
separate optional encryption wrapper and incremental computer-workspace
updates are now part of Phase D (§§9–10), not vague prerequisites for the first
working package.

UI for v1 is intentionally minimal and 100% owner-designed before build:
one entry row on the Memory Assistant, one screen with
create-package / outstanding-package / import-result, a confirmation dialog
with the privacy disclosure, and a persistent result summary. The audit
plan's §14 is a reasonable starting sketch for that conversation — every
string in it is unapproved until the owner says otherwise.

**Privacy, stated plainly (the audit-plan warning plus transfer-channel risk):**
the package is the user's conversations and memory list in readable
plaintext. Once it leaves the app, what happens to it depends entirely on
the tool that reads it — an agentic desktop app working for a cloud AI
sends what it reads to that provider. The app's job is to say that clearly
at export time and include nothing beyond what analysis needs (no API keys,
no database, no embeddings). A plaintext email attachment or cloud-drive
upload also exposes the package to that transfer/storage service before an
AI reads it; recommend USB or a trusted local transfer where practical.

### After Phase D — Only if wanted: the same contract through other transports

Once the package exists, other consumers come nearly free and need no new
app code paths: a local LLM harness that can read files, a home-server
script, or a future desktop helper all speak "read package, write
results.json". One genuinely-cheap alternative also exists for local-model
users specifically: the Archivist endpoint setting already accepts any
OpenAI-compatible server, so a phone pointed at a desktop LM Studio/Ollama
over Wi-Fi reuses the entire existing pipeline. Caveat discovered during
this review: the endpoint editor permits `http://` hosts after a consent
dialog (built for exactly this), but the manifest never enables cleartext
traffic, so on Android 9+ a plain-http LAN endpoint should fail at request
time with a network error. Enabling it is a one-line manifest /
network-security-config decision — an owner security call, listed below. It
is a side path, not the main goal.

### The "65% match" answer — on-device embedding-assisted duplicate review

The owner asked whether the on-device model (EmbeddingGemma) could catch
near-duplicates and ask the user things like "this is 65% similar to a
memory you already have — delete or replace?"

**Yes, this is feasible, and the plumbing already exists.** The Librarian
already embeds memories and ranks by cosine similarity for retrieval; the
same math can score a new draft against existing memories at filing or
review time and attach a **"Possible match"** note to the Pending row,
opening a side-by-side comparison where the user picks **Keep both /
Keep existing / Replace the old one / Delete this draft** ("Replace" = the
existing `supersedes` + status machinery, which is built but has no UI
yet). Deliberately NOT shown as a percentage: cosine similarity is not a
probability, and "65% similar" reads as far more authoritative than the
number actually is. (Final wording is the owner's, as always.)

**What it must never do is decide alone.** A high similarity score cannot
distinguish a duplicate from a negation ("likes X" vs "no longer likes X"),
an update (old status vs new status), or the same sentence in two different
fictional worlds. Embeddings treat all of those as "very similar." So the
score selects what to SHOW the user; the user's tap is the decision — which
is also exactly the owner's standing authority rule for the memory system.
When the embedding model is unavailable, this degrades to exact identity and
deterministic text overlap, the same safe degradation principle the
Librarian uses. That reliability fallback does not decide the separate
owner question of whether Saved memories may be first-enabled without a
model. The **exact** duplicate/status check and acceptance-time revalidation
are Phase A correctness work. The broader semantic comparison screen can
follow the file loop, but it should land before large backfills or a broad
release make memory hygiene expensive. Its screens and wording are an
owner-approval conversation of their own.

---

## 5. RAG and lore compatibility audit

### 5.1 Direct answer: strengthen the existing engine; do not replace it

The app was not built with "no RAG." It already has a substantial read path:

- `MemoryStore.activeMemoriesForScope` performs relational eligibility
  filtering for active status, scope, fictional-world isolation, current
  companion/campaign/character context, and the owner-approved project
  behavior.
- `Librarian` supports on-device EmbeddingGemma retrieval, deterministic
  keyword fallback, importance/recency/context scoring, and a debug search.
- `Enforcer` combines retrieved memories with the active roleplay scene,
  deterministic card retrieval, classic lorebook matches, cooldown, and a
  character budget.
- `PromptAssembler` renders one memory system message. Its retrieved
  memories, lore notes, and Zone 2 card entries share a dynamic character
  budget; owner-authored Zone 1 cores are deliberately complete and uncapped,
  with word-count warnings in their editors.
- `AssemblyLog`, `LoreBookInjectionLog`, and the opt-in Memory log provide
  partial diagnostics.

That is a sound skeleton. The correct recommendation is **not** a new RAG
framework, server vector database, knowledge graph, or AI query planner.
The strongest scope and authority decisions are already present. The work is
to close several correctness holes, make source controls match what users
actually experience, preserve the two lore systems, and test the end-to-end
behavior on a real device.

The audit also rejects the assumption that every useful RAG field is
missing. The current schema already represents scope, the six
owner-approved memory kinds, tags, importance, origin/provenance, status,
supersession, target joins, and embedding model tags. Do not add a second
ontology merely because an external review proposed one.

### 5.2 There are three prompt sources, not one vague "memory" feature

The product must name and test these separately:

| Source | Current implementation | What it contributes | User-facing control |
|---|---|---|---|
| **Saved memories** | `MemoryStore` → `Librarian` → `Enforcer` | Relevant active memories plus the active roleplay scene, card cores, and deterministic Zone 2 card entries | **Saved memories** |
| **Classic lore books** | `LoreBookStore` + `LoreBookTriggerMatcher` | User-authored trigger-based notes from the persona's core book and checked additional books | **Lore books** |
| **Conversation archive** | `TranscriptRecorder` → Archivist/API or review package | Source material from which future drafts may be proposed | **Archive this chat** |

Within the first source, roleplay cards are a deliberate structured lore
system. They do not replace classic lorebooks and should not be folded into
the **Lore books** toggle. Classic lorebooks remain hand-authored,
trigger-based, persona-linked notes. Structured cards remain scene-aware
world/campaign/character material assembled by the Enforcer. Both survive
this plan.

One assumption in the outside review is deliberately not adopted: a **user
persona is not an eighth memory scope** in the owner-approved model. User
personas and companion cards are authored only by the user. The selected
`user_persona_id` belongs in transcript/scene context and its presentation
may enter the active scene, but neither API nor computer analysis may propose
persona edits or attach ordinary memories to a persona. Adding persona-
scoped memories would be a product-rule redesign, not a RAG repair.

The current per-chat prompt route already supports the four combinations:

| Saved memories | Lore books | Required prompt behavior |
|---:|---:|---|
| Off | Off | Inject neither source. Normal system instructions, persona, activation prompt, model rules, and conversation continue normally. |
| Off | On | Search and inject matching classic lorebook notes only. Do not open the memory store for prompt retrieval or run embeddings. |
| On | Off | Run scoped saved-memory retrieval and active roleplay scene/cards. Do not search or inject classic lorebooks. |
| On | On | Use both. Lorebook notes remain user-authored authority: they receive budget priority and win an overlap with a retrieved memory. |

This is already how `Preferences.getChatMemoryEnabled`,
`getChatLoreBooksEnabled`, and `ChatActivity` are intended to route a turn.
The global Settings UI is what fell behind the engine: it still presents
the legacy single-choice **None / Lore Books / Full** tier plus a separate
default-memory toggle. That cannot directly express a saved-memories-only
default, and **Full** conceals which two independent sources it enables.

### 5.3 Correct source-control UX

Replace the global tier picker and its redundant default-memory toggle with
two independent switches.

**Settings → Memory Controls**

Section title:

> **Use in New Chats**

Rows:

1. **Saved memories**

   > Finds relevant saved memories. In roleplay, it also uses the active
   > scene and cards.

2. **Lore books**

   > Uses trigger-based notes from lore books linked to the chat.

Any combination is valid. Do not display **RAG** in ordinary UI; it is the
technical name for the Saved memories path. No extra "mode" summary is
needed because the two switches state the behavior directly.

Retain **Allow active companion memories in roleplay** as a separate
advanced scope permission, default off. It is not a fifth source mode and
never forces injection; it only opens the already-approved companion scope
door when Saved memories is on. Dim it with a clear dependency when Saved
memories is off rather than deleting its saved value.

**Chat → Quick Settings**

Section title:

> **Memory Sources**

Rows:

- **Use saved memories in this chat**
- **Use lore books in this chat**

Keep **Archive this chat** in the Memory Assistant/review area rather than
presenting it as a third prompt source. Scene selectors appear when Saved
memories is on. Lorebook selections stay selected while Lore books is off;
the list may be dimmed/collapsed with a short "Selections are kept" note,
but turning the source off must not erase configuration.

Archive helper:

> Allows Memory Assistant to review this chat for memory suggestions.
> Turning it off does not delete the chat.

When a roleplay scene is active, add a read-only aggregate beneath its
selectors:

> **Always-active scene: about N words**
>
> Large active scenes leave less room for conversation history.

This is a dashboard, not a cap. The owner has already ruled that Zone 1 card
cores are injected in full and are never silently truncated; the aggregate
simply makes the multiplied world + campaign + character + party cost visible
where the user assembles the scene; include the selected user-persona
presentation in that total.

The existing core-book description must not say a book is unconditionally
"always active." Accurate wording is:

> **Core: [book name] — active whenever Lore books is on**

The owner needs to review the two labels/helpers and final placement, not the
underlying retrieval mechanics.

**First-enable and unavailable states**

- The current owner ruling remains authoritative unless deliberately changed:
  first enabling the full Saved memories engine requires an installed
  embedding model, with persistent inline setup guidance if the user declines
  or no compatible model is available. Phase A must not silently reverse that
  product rule.
- A keyword-only supported mode is a technically sound alternative for
  low-resource devices, but enabling it would reverse the standing ruling.
  It is therefore a narrow owner decision, recorded in §§8 and 13, rather
  than a technical default hidden in this plan.
- Regardless of that first-enable policy, a previously enabled engine must
  degrade safely when a model later fails, is removed, is rebuilding, or has
  only a partial index: use deterministic lexical matching over the complete
  eligible set for that turn. Do not silently turn the source off, hide
  vectorless memories, or claim the library is unavailable. Show a
  persistent diagnostic/recovery route rather than a disappearing toast.
- If one source fails during a turn, continue with the other source and
  normal chat. Do not silently flip the user's saved switch. Show a durable
  status/details route when the failure persists.
- **Neither** means neither memory source enters the prompt. It does not
  disable persona instructions, activation prompts, model rules, or ordinary
  chat history.

**Migration from the legacy global preference**

| Existing state | New Saved memories default | New Lore books default |
|---|---:|---:|
| `memory_engine = none` | Off | Off |
| `memory_engine = lorebooks` | Off | On |
| `memory_engine = full`, `default_memory_enabled = true` | On | On |
| `memory_engine = full`, `default_memory_enabled = false` | Off | On |

Existing explicit per-chat tri-state values remain unchanged. An unset chat
follows both new globals. Chat rename, auto-naming, duplication, and
save-to-profile paths must preserve the raw per-chat values without turning
an inherited default into an explicit override.

### 5.4 Read controls and write consent are independent

The current code calls `getChatMemoryEnabled()` during transcript capture and
passes it as an exclusion cause. That means turning off retrieval also turns
off future learning, despite a separate **Archive this chat** control. It is
both confusing and contrary to the owner-approved storage/injection law.

The corrected ownership matrix is:

| Control | Changes current/future prompts | Changes transcript capture or review eligibility |
|---|---:|---:|
| Saved memories | Yes — RAG/scene/cards only | No |
| Lore books | Yes — classic lore only | No |
| Archive this chat | No | Yes — API and computer review |
| Analyze Conversations / create package | No | Claims already-eligible archived material for review |

Turning Archive off stops new review-eligible capture at the declared
boundary. Turning it back on offers **Include Earlier Messages** or **New
Messages Only** where earlier history is still recoverable. Legacy rows
excluded because the old Saved memories switch was off remain excluded until
the user makes that explicit choice; migration must not silently expand what
an AI may read.

This separation also simplifies the external workflow. Package eligibility
depends only on archive consent and durable review state, never on whether
the user happens to want memories injected into that chat today.

### 5.5 Verified RAG correctness defects and minimum repairs

The following are code-path findings, not theoretical RAG advice.

#### Release-blocking correctness

| Finding | Evidence and user-visible failure | Minimum correction |
|---|---|---|
| **A partial current-model index hides eligible memories.** | `Librarian.search` builds `withVectors`; if even one vector exists, it searches only that subset. A newly imported/edited memory without a current vector disappears. Keyword fallback happens only when the subset is empty. | Use semantic retrieval only when every eligible candidate has a valid current-model vector. While the eligible set is incomplete or the model tag is dirty, keyword-search the **complete eligible set**. Repair may continue in the background, but chat correctness cannot depend on repair finishing. |
| **The relevance floor runs after top-K.** | `rank(..., topK)` takes the highest blended scores, then `search` removes hits below `MIN_SIMILARITY`. Importance/recency can let low-relevance rows consume slots; filtering them returns fewer results even when relevant candidates exist below them. | Apply the semantic relevance gate before final top-K. Importance, recency, project, and tag signals may rank a relevant hit; they must not convert an irrelevant hit into an eligible one. |
| **Post-retrieval filters do not backfill.** | Librarian returns final top-K before Enforcer removes lore overlaps, cooldown hits, and budget cuts. A removed item is not replaced by the next relevant candidate. | Return a larger ranked candidate pool (or all scoped candidates while stores are small). Enforcer applies overlap/cooldown eligibility, then takes final top-K and budget. Keep selection deterministic. |
| **Malformed retrieval policy can disable the whole RAG assembly.** | `Enforcer.parsePolicy` accepts stored/imported `top_k` and character budget without range checks; retrieval weights are also consumed without finite/range normalization. A negative `top_k`, extreme budget, or non-finite weight can throw or produce nonsense and fall the turn back to lore-only. | Normalize policy at import and again at use: finite non-negative weights, bounded top-K/budget, safe defaults for malformed values. Record that defaults were substituted. |
| **A user edit can be indexed under the draft's old meaning.** | `MemoryEditorActivity` preserves `prior.embeddingText` while changing title/content. `updateMemory` deletes the old vector, then `reindexMemory` prefers that still-present `embeddingText`, so the corrected memory can remain discoverable by obsolete wording and missed by its new subject. Tag edits are not part of the store's `textChanged` test either. | Make the retrieval document derived from the authoritative current title/content/tags. At minimum, clear condensed `embedding_text` whenever any source field changes and rebuild it deterministically; never trust imported/model-generated derived text after a user edit. |
| **Draft acceptance is not a second filing boundary.** | Exact duplicate/target checks happen before Pending, but a user can edit a draft or the library can change before acceptance. Acceptance can therefore create a new collision or stale placement. | Re-run placement-aware identity, target existence, and status checks at acceptance. Present a conflict and preserve the draft; never silently overwrite, merge, or activate. |

#### Retrieval quality and lifecycle

| Finding | Evidence and user-visible failure | Minimum correction |
|---|---|---|
| **Keyword fallback does not actually search tags and uses substring hits.** | `keywordFallback` builds its haystack from title + `textToEmbed()` only and calls `contains`; comments promise keyword/tag behavior. A tag-only memory is missed, while a short term may match inside an unrelated word. | Build one deterministic lexical document from title, effective content, and tags. Match normalized whole tokens and explicit phrases; keep the algorithm small and testable. |
| **Keyword fallback bypasses most of the approved ranking contract.** | It scores token hits plus importance only; scope specificity, selected-project boost, recency, tag hints, and tentative-confidence damping used by semantic ranking do not apply. Low-resource/no-model users therefore receive materially different source priorities. | Treat lexical overlap as the relevance signal, then apply the same bounded non-semantic ranking features before final top-K. Relevance remains the gate so a scope boost cannot inject an unrelated memory. |
| **Stored vectors omit title and tags.** | `RetrievableMemory.textToEmbed()` returns `embedding_text` or content. User-entered memories commonly have no condensed text, so a useful title/tag can be invisible to semantic search. | Define one versioned embedding document: title + effective content + normalized tags. Reindex when that document changes. No schema expansion is required. |
| **Edited facts remain old in the recency score.** | `recencyMap` sorts only `createdAt`, although memories already have `updated_at`. A corrected memory does not become fresher than its obsolete peers. | Carry `updatedAt` in `RetrievableMemory` and rank by `updatedAt ?: createdAt`. Do not invent in-world story time for this fix. |
| **Index state is detectable but not safely integrated with retrieval.** | `indexNeedsRebuild` can detect tag mismatch/missing rows, and editors reindex some paths, but model install/switch, restore/import, interrupted rebuild, and individual failures can leave holes. The existing Round 5 health document proposes a derived missing-vector queue but is not built or approved. | Approve/reuse the narrow derived-queue design rather than inventing a second queue: active memories lacking the active tag are repair inventory. Trigger bounded background repair after model availability, edit/activation, import/restore, and startup. Regardless of repair state, use complete-set keyword fallback as above. A model switch may remain an explicit rebuild action if auto-rebuilding is too expensive. |
| **Cooldown is based on a fixed turn count, not actual sent context.** | A memory is suppressed for ten turns even if the model's context window or chat trimming has already dropped the turn that contained it. The model may need a fact that RAG considers "recently injected." | Track whether the prior injection is still represented in the actual history sent to the model, or store a bounded injection reference alongside sent chat history. Until that exists, prefer a conservative refresh over starving a needed memory. |
| **Recent context is unlabeled and gives assistant text equal influence.** | `recentTurnsContext()` joins up to four prior messages without speaker labels, then Enforcer combines it with the current user message. An assistant hallucination can become a retrieval cue. | Add role labels and deterministic weighting/ordering so current and recent user text dominate. Do not add an LLM intent classifier or pronoun resolver in v1. |
| **The dynamic memory budget is not a whole-request context budget.** | Lore, retrieved memories, and Zone 2 cards share `memory_char_budget`, but Zone 1 scene cores, persona/system text, model rules, chat history, and reserved output are outside it. This is partly intentional—owner-authored always-on material must remain complete—but a multiplied scene can leave too little room or cause a provider context error. | Preserve the no-truncation rule for Zone 1 and matching model rules. Compute and log the final prompt-layer estimate; show the aggregate scene count in Quick Settings. When a reliable model context limit is known, reduce only the dynamic RAG/lore/card allowance to remaining headroom. If fixed user-authored layers alone exceed it, show a truthful actionable error/warning rather than silently shearing them. |

#### Phone performance and diagnostic accuracy

| Finding | Evidence and user-visible failure | Minimum correction |
|---|---|---|
| **Every semantic turn loads all active vectors across all scopes.** | `MemoryStore.activeEmbeddings(tag)` selects every active memory vector, after which Librarian discards vectors for ineligible candidates. This scales with the whole library rather than the current scene. | Query vectors only for the already-eligible memory IDs, ideally in one scoped/joined query. Benchmark before adding an approximate-nearest-neighbor index; a scoped brute-force cosine pass is reasonable on-device at expected sizes. |
| **Lore-overlap comparison re-embeds both sides every turn.** | Enforcer embeds each injected lore note and each retrieved memory again for overlap suppression rather than reusing stored memory vectors or cached lore vectors. | Carry the retrieved vector when available and cache lore vectors by content hash + model tag. Invalidate on edit/model change. Keep deterministic text overlap as fallback. |
| **The debug records describe candidates incompletely.** | Classic lore logging records raw matches before final budget truncation. RAG logging shows some cooldown/budget reasons but cannot explain partial-index starvation, policy substitution, or a source that failed before assembly. | For each source record: searched, eligible, ranked/matched, injected, and omitted counts; stable IDs; and deterministic omission reason. Diagnostics must never require conversation text unless the existing opt-in content log is enabled. |

**Ranking contract after repair**

1. SQL is the authority gate: active status, fiction wall, scope, targets.
2. Build the complete eligible candidate set.
3. Choose semantic scoring only if the eligible set has complete valid
   vectors; otherwise run deterministic lexical scoring over that complete
   set.
4. Apply the semantic relevance floor.
5. Blend bounded importance, recency, project boost, and tag/context hints to
   order relevant candidates.
6. Over-fetch into Enforcer; apply lore overlap and actual-history cooldown.
7. Take final top-K and the character budget.
8. Record why every considered item was injected or omitted.

Project selection remains the owner-approved **boost**, not an eligibility
gate. Tests must prove that behavior and its residual cross-project risk;
this plan does not silently redesign it.

### 5.6 Lore-system findings and preservation rules

Classic lorebooks should remain deterministic and user-authored. The RAG
repair must not turn them into embeddings-only data or auto-merge their
contents.

| Finding | Minimum correction |
|---|---|
| `LoreBookStore.queryEntries` calls `getTriggers` once per entry, and `ChatActivity` calls `findMatches` once per active book: an N+1 query pattern on every lore-enabled turn. | Fetch enabled entries and ordered triggers for all active book IDs in one joined query, or maintain an invalidation-safe in-memory index. Preserve core-book-first then user-selected-book order. |
| The same normalized lore content can be injected twice from two active books. | Exact-normalized dedup before the character budget; first occurrence wins, so the core book retains priority. Do not semantic-merge or delete user-authored notes. |
| A trigger storm is silently truncated, while `LoreBookInjectionLog` records the raw matches rather than what entered the prompt. | Log matched, injected, and cut entries separately with `entry_id`, `book_id`, and a reason such as entry limit, character budget, duplicate content, or source failure. |
| Lore triggers inspect only the latest user message. | Keep this behavior for v1 because it is fast and predictable. Document it in the product behavior; changing lore triggers to a rolling window is a separate owner choice with false-trigger and repetition costs. |
| Enforcer's `0.85` semantic threshold labels every memory/lore overlap a "contradiction," stores flags that are not part of ordinary review, and has not been calibrated on the shipping model/corpus. | Rename the internal/user concept to **overlap** or **possible conflict**. Lore still wins that turn, but similarity never asserts logical contradiction. Surface the pair in review/debug and calibrate against a small on-device corpus. |

Roleplay card cores and Zone 2 entries remain part of the Saved memories
path:

- card cores use the existing fixed scene order;
- Zone 2 continues deterministic trigger/tag retrieval, not embeddings;
- dead/enemy and other owner-approved status rules remain unchanged;
- the sanctioned `plot_ledger` card-section suggestion remains;
- automatic AI updates to retired Plot Ledger columns remain forbidden.

### 5.7 Memory creation, provenance, and hygiene must serve retrieval

Better retrieval starts with better records, but the model must not invent a
new user-facing classification system. API and computer analysis should use
the existing six kinds: **fact, preference, event, status, instruction,
lore**.

Each suggestion should be:

- one coherent durable item rather than a bundle of unrelated facts;
- self-contained, with the important entity/topic named in title or content;
- free of ambiguous pronouns when a stable referent is known;
- scoped and targeted with stable IDs, never guessed from a duplicate name;
- accompanied by confidence/evidence without treating confidence as
  authority;
- a draft until the user approves it.

**Provenance correction**

`origin` answers **who created this record**. `provenance_source` answers
**what kind of evidence supported it**. The current UI sometimes uses
`provenance_source = user_stated` to label an Archivist-created record as
"Entered by hand," and the Enforcer does not carry `origin` into its
provenance marker. Those are different facts and must not be conflated.

Use the owner-approved user-facing source labels:

- **Entered by hand** — `origin = user`
- **Imported** — backup/package/manual import origin as applicable
- **Learned from chat** — API Archivist or computer analysis origin

Evidence quality (stated/observed/inferred/tentative) may appear separately
where useful; it must not rewrite creator/source history.

For learned-from-chat drafts, preserve the minimum durable lineage:

- source chat ID and display name captured at filing time;
- source transcript row ID(s);
- source timestamp;
- short evidence excerpt;
- transport/run/packet item identity.

The current API path stores mainly the mutable chat name in
`provenance_context`; that is insufficient for rename-safe evidence. Per-
message IDs do not need to be invented for v1: frozen transcript row IDs and
an excerpt provide a practical evidence anchor. Pending and the editor need
a **View Source** or **Details** route so the user can see why a draft exists
before accepting it.

**Hygiene behavior**

| Situation | App behavior |
|---|---|
| Exact normalized content + same placement + same kind matches active/draft | Mark **Already saved/pending**; create no second draft. |
| Exact content/placement but kind changes rendering semantics | Mark **Possible Match**; user decides whether to edit/replace the existing record. |
| Exact match is archived/superseded | Mark **Possible Match**; user chooses restore/replace/keep separate. |
| Semantic/text-near match | Show **Possible Match**; never auto-delete, auto-merge, or call it a duplicate. |
| Content is already represented in a classic lorebook or roleplay card | Show the read-only lore/card item as a **Possible Match**. The user may keep both, keep the authored lore/card only, or keep the draft for a different scope; analysis never edits the authored item. |
| Possible contradiction/update/negation | Show the old and new evidence; choices include keep both, keep existing, replace existing, delete suggestion, and—where applicable—confirm alternate-world placement. |
| Draft was edited or library changed before acceptance | Revalidate exact identity, placement, targets, and status. Keep the draft pending if conflict needs a decision. |
| User rejected a prior draft | Keep the existing narrow, rename-safe exact suppression. The result may say "similar suggestion rejected before," but rejection must not become broad negative learning. |

Active contradictions already in the library cannot be solved safely by
cosine similarity. A later maintenance scan can identify comparison
candidates using the same scoped Possible Match service; the user remains
the authority. Superseded records are retained for history but excluded by
the existing active-status retrieval gate.

### 5.8 Minimal data/package changes; avoid schema inflation

Before adding a column, map the need to what exists:

| Proposed concept | Existing representation / minimal action |
|---|---|
| Memory type | Existing six-value `kind`; keep it. |
| Scope and targets | Existing `scope`, world/companion mirrors, and target join tables; make stable IDs complete and query them consistently. |
| Retrieval exclusion | Existing status gate (`active` only); use archived/superseded rather than a second boolean. |
| User approval | Existing draft → active transition; human approval remains activation boundary. |
| Embedding version | Existing embedding model tag; add an embedding-document version only if title/tag document changes require distinguishing old vectors. |
| Normalized duplicate identity | Prefer one shared normalization/fingerprint function; persist a fingerprint only if profiling shows repeated computation matters. |
| Updated freshness | Existing `updated_at`; carry it into the retrieval row. |
| Evidence | Add stable transcript lineage and excerpt fields/object because the current mutable context string cannot represent them. |

Do **not** add the proposed large new taxonomy, automatic relationship graph,
contradiction graph, LLM query-intent pass, pronoun resolver, automatic
story-time inference, or a suite of redundant package index files in this
phase. Story chronology is genuinely useful for mutable fiction, but it is
not honest to infer reliably from every chat. Use existing event/status
memories, cards, Plot Ledger section, `updated_at`, and supersession first.
Add explicit story-time metadata only after concrete retrieval failures and
a user-editable UX are designed.

### 5.9 Evaluation and acceptance matrix

Unit tests for cosine math and individual trigger matching are not enough.
Add repository/instrumented tests around the actual stores and one
small owner-authored golden corpus run on the shipping on-device model.
Current coverage is concentrated in isolated helpers such as
`LibrarianRankingTest`, `PromptAssemblerTest`, card retrieval, and lore
trigger matching; it does not exercise the full SQL scope → partial index →
post-filter → final prompt path or the four source modes.

#### Source-control matrix

For every row, inspect the final prompt and source access:

| Mode | Assertions |
|---|---|
| Both on | Scoped RAG/scene/cards and matching lore appear; lore wins overlap/budget; both diagnostics identify injected and omitted items. |
| Saved memories only | No classic lore DB search or lore injection; RAG/scene/cards remain functional. |
| Lore books only | No memory retrieval/vector work; classic core + checked books remain functional. |
| Neither | Neither source is queried for prompt assembly; ordinary system/persona/model rules and chat history are unchanged. |

Also test legacy-global migration, inherited versus explicit per-chat values,
chat rename, auto-naming, duplication, save-to-profile, and source switches
across process restart.

#### Retrieval correctness

- no model installed → lexical search over every eligible memory;
- one current vector out of many → no vectorless eligible memory disappears;
- model tag change / restore / interrupted rebuild → complete lexical
  retrieval until a verified complete current index exists;
- title-only, content-only, and tag-only query hits;
- semantic relevance floor occurs before final top-K;
- lore/cooldown removal backfills from lower-ranked relevant candidates;
- edit/activation/import produces repair inventory and eventually a vector;
- editing a draft's meaning cannot leave its old condensed retrieval text;
- updated memory receives updated freshness;
- malformed policy safely defaults;
- draft/archived/superseded never enters ordinary retrieval;
- an oversized scene reduces/cuts only dynamic material, never silently
  truncates Zone 1 or matching model rules, and reports the final source
  counts/context warning.

#### Scope and fiction wall

- World A cannot retrieve World B, even for identical text/character names;
- the same content in two worlds remains two valid identities;
- campaign, companion, and roleplay-character gates match the owner-approved
  rules; selected user-persona presentation is correct scene context but is
  never treated as an Archivist memory target;
- real-life memory cannot leak into a fictional scene unless an explicitly
  approved global rule allows it;
- project selection boosts rather than gates, and tests document the allowed
  unselected-project behavior;
- duplicate target names never resolve by first match.

#### Lore and roleplay

- core book precedes checked books;
- multi-book ordering is deterministic;
- exact duplicate lore content injects once without deleting either note;
- a trigger storm records matched/injected/cut accurately;
- latest-user-message trigger behavior is stable;
- card cores and Zone 2 still work with Lore books off;
- lore-store failure does not disable Saved memories, and memory-store/index
  failure does not disable Lore books.

#### Provenance and review

- Entered by hand / Imported / Learned from chat labels use origin correctly;
- API and computer drafts expose source chat, timestamp, transcript IDs, and
  evidence excerpt;
- malformed or cross-packet evidence is rejected;
- active/draft exact collision, archived/superseded Possible Match, and
  acceptance-time race each produce the designed recoverable state;
- exact overlap with a classic lorebook/card is shown without mutating that
  user-authored source;
- no parser, importer, or reviewer action activates a memory without the
  user's explicit acceptance.

#### Real-device performance and quality

Benchmark representative encrypted stores on at least one lower/mid-range
Pixel-class device at approximately 1,000 / 5,000 / 10,000 memories and
small/large lorebooks. Record p50/p95 prompt-preparation latency, peak memory,
database/vector bytes read, and a coarse battery/thermal observation. Set a
release threshold from measurements rather than inventing one in this plan.

The golden retrieval corpus should contain expected IDs and forbidden IDs
for:

- exact entity/topic recall;
- cross-world non-leakage;
- renamed target aliases;
- edited and superseded facts;
- global preference versus fictional scope;
- current status versus old status;
- title/tag discovery;
- lore precedence.

Report at least must-include recall, forbidden-scope leakage (required zero),
superseded/stale retrieval rate, and lexical-fallback parity. Evaluate
retrieval selection before judging final model prose, so generation variance
does not hide a read-path defect.

### 5.10 Named workstreams and gates

Keep the implementation small and independently reversible:

1. **Phase A / Source semantics:** two global defaults, matching
   per-chat controls, Saved-memories-led store provisioning,
   injection/archive separation, legacy migration, and four-mode tests.
2. **Phase A / Retrieval correctness:** complete-set fallback, relevance-before-
   top-K, post-filter backfill, policy clamps, updated freshness, title/tag
   lexical and embedding documents.
3. **Phase A / Index lifecycle and performance:** derived missing-vector repair
   (using the narrow approved portion of the existing health design), scoped
   vector loads, cached/reused overlap vectors, persistent diagnostics.
4. **Phase A / Provenance and hygiene:** shared filing/acceptance validation,
   evidence lineage, truthful source labels, status-aware exact matching,
   Possible Match service.
5. **Phase A / Lore query and diagnostics:** joined/cached trigger retrieval,
   exact cross-book content dedup, injected-versus-cut logs.
6. **Phase B — full portable package:** ship only after **Source semantics**,
   the release-blocking parts of **Retrieval correctness**, and the
   evidence/shared-filing foundation from **Provenance and hygiene** are
   complete.
7. **Phase C — computer workflow proof:** validate the shared retrieval/result
   contract with real file-capable agents before app mutation code.
8. **Phase D — reconciliation and deltas:** strict import, conflicts, Pending,
   approval/rollback, incremental workspace updates, then optional encryption.

Index-lifecycle performance optimizations can move later if measurement
shows no current latency problem, but the partial-index correctness fallback
cannot. Semantic Possible Match UI may mature during Phases C/D, but
acceptance-time exact validation cannot.

### 5.11 Residual risks that cannot be designed away completely

- Similar text does not reveal whether two claims are duplicates, updates,
  negations, contradictions, or alternate-world facts. Scope, evidence, and
  user choice reduce the risk; no similarity threshold solves it.
- A transcript without reliable scene context cannot always be placed
  correctly. The agent must be allowed to leave placement unresolved rather
  than guess.
- Token/context budgets necessarily omit some eligible facts. Deterministic
  selection, backfill, diagnostics, and evaluation make the tradeoff visible;
  they cannot make context infinite.
- An external agent may fail to search a reference file or may misunderstand
  evidence. Strict schemas and app-side checks constrain its authority but
  cannot make its reasoning infallible.
- Story order is not always stated and cannot always be inferred honestly.
  User-editable status/supersession is safer than automatic chronology.
- A desktop cloud agent receives the plaintext review package. Clear
  disclosure and data minimization are mandatory; the Android app cannot
  control that provider after export.

These are reasons for drafts, evidence, recoverable state, and human review—
not reasons to abandon either RAG or the computer workflow.

---

## 6. Proposed user-facing wording, separated by phase

This section is the copy source for implementation planning. It deliberately
does not turn internal machinery into UI. Claims, run IDs, hashes, stable
target IDs, exclusion flags, packet ledgers, and dedup keys remain behind
the scenes. Technical details belong behind **View Details** or in the
Memory Debug Log.

Nothing below is approved merely because it appears in this document. The
owner approves visible wording in chat before implementation. Missing cases
return for copy review; an implementation agent must not improvise new
user-facing text. The owner has explicitly deferred final user-facing wording
decisions until the architecture is complete, so every string below remains
a working placeholder and does not block behind-the-scenes Phase A work.

### Shared vocabulary

Use these terms consistently in new UI:

| Concept | User-facing term |
|---|---|
| The feature | **Memory Assistant** |
| RAG / Enforcer source | **Saved memories** |
| Classic trigger source | **Lore books** |
| Transcript review consent | **Archive this chat** |
| Desktop/file route | **Review on a Computer** |
| Exported ZIP | **Memory Review Package** or **review package** |
| Returned result | **Memory Suggestions File** or **suggestions file** |
| Model output | **Suggestions** |
| Review destination | **Pending** |
| Near-duplicate signal | **Possible Match** |
| Internal `retire` proposal | **Archive Suggestion** |
| Existing API route | **In App** when a route label is necessary |
| File-based route | **On Computer** when a route label is necessary |

Do not use **Archivist**, **external analysis**, **analysis packet**,
**result payload**, **archive database**, or **import memories** in new
primary UI. Do not display a similarity percentage as if it were a
probability. `retire` remains the schema/implementation operation name; do
not expose **Retire** or **Retirement** as a competing user term.

### Phase A copy — the only wording needed before the file workflow

Most of Phase A is invisible correctness work and needs no labels. Only the
following visible situations need owner-approved wording.

#### A. Analysis in progress

Place a persistent notice beneath the existing Analyze Conversations
control while a run is active, and mirror progress in the foreground-service
notification:

> **Analyzing Conversations**
>
> Memory Assistant is reviewing N of M conversations. You can keep chatting
> or leave this screen. New messages will be included in a later review. If
> analysis is interrupted, unfinished conversations will remain available to
> analyze again.

Do not rely on a toast for this state, block chat access, or make Activity
lifetime the run lifetime.

#### B. Interrupted run recovered

> **Previous Analysis Interrupted**
>
> Speak GPT recovered the unfinished review. No unfinished conversations
> were marked as reviewed.

If some conversations committed before interruption, the detailed view may
also show:

- **Memory drafts created:** N
- **Conversations fully processed:** N
- **Conversations remaining:** N

#### C. Partial or full failure

> **Run Partially Failed**
>
> Memory extraction stopped before every conversation was fully processed.
> Saved memory drafts were kept.

Show truthful counts:

- **Memory drafts created:** N
- **Conversations fully processed:** N
- **Conversations remaining:** N

For a full failure, use the same factual pattern with **Run Failed**. Never
claim that zero drafts were created unless the stored count is actually
zero.

#### D. Re-enabling chat archiving

When the user turns **Archive this chat** back on and earlier eligible
messages still exist:

> **Include Earlier Messages?**
>
> This chat was not being archived. Speak GPT can include messages already
> in this chat the next time Memory Assistant runs, or begin with new
> messages only.

Actions:

1. **Include Earlier Messages** — recommended default.
2. **New Messages Only**
3. **Cancel**

If earlier messages are no longer available:

> **Earlier Messages Unavailable**
>
> Speak GPT will begin archiving new messages from this point.

Action: **Okay**

Re-enabling **Use saved memories in this chat** does not need a dialog and
does not change archived/reviewable history. It changes prompt injection
only. The same is true of **Use lore books in this chat**.

**Owner review required near the end of Phase A:** the two source labels/helpers in
§5.3, the archive re-enable default, and the four short status/dialog groups
above. No owner wording review is needed for internal database, retrieval,
index, and run-integrity changes.

### Phases B–D copy — reserve now, approve when the file workflow begins

The computer route is a subordinate row on the existing Memory Assistant,
not a replacement for Analyze Conversations.

Entry row:

- **Title:** Review on a Computer
- **Subtitle:** Use an AI on your computer to review chats or check your
  existing memories.

Screen introduction:

> Create a review package, give it to an AI that can work with files, then
> bring its suggestions back to Pending. It can suggest new memories, edits,
> merges, or memories that may be outdated. Nothing changes until you review
> and approve it.

Privacy notice:

> The review package is not encrypted. It contains conversation text and a
> reference copy of the memories, lore books, and lore cards needed for this
> review. No API keys, passwords, or database keys are included.
>
> When you give the package to another AI app, that app's provider may
> receive the contents. Email, cloud drives, and other services used to move
> an unencrypted package may receive it too. Use services you trust; a cable
> or trusted local transfer exposes it to fewer parties.

#### The four-step screen

1. **Choose What to Review**

   - **Review Chats**
     - **Helper:** Find memories worth keeping in selected or new chats.
   - **Check Existing Memories**
     - **Helper:** Look for possible duplicates, outdated information, and
       memories that could be clearer.

2. **Create a Review Package**

   Supporting state: **N conversations ready** or **No conversations
   waiting** for chat review; **N memories selected** for a library check.

   Primary action: **Create Review Package**

3. **Review It on Your Computer**

   > Move the ZIP to your computer and open it with an AI that can work
   > with files. A cable or trusted local transfer is the most private
   > option. Ask the AI to follow `README.md`. It will create a suggestions
   > file for Speak GPT.

4. **Import Suggestions**

   > Bring the suggestions file back to this device. Speak GPT will check
   > its evidence and compare it with the current phone before anything is
   > added to Pending.

   Primary action: **Import Suggestions**

Creation confirmation:

> **Create Review Package?**
>
> This package will contain the selected review material and the current
> memories, lore books, and lore cards needed for comparison. It is not
> encrypted. Any email, cloud drive, or transfer service used to move it can
> read it. No API keys, passwords, or database keys are included.

Actions: **Choose Save Location**, **Cancel**

Progress labels:

- **Preparing Conversations…**
- **Adding Existing Memories…**
- **Checking Review Package…**
- **Saving Review Package…**

Outstanding-package state:

- **Waiting for Suggestions**
- **Import Suggestions**
- **Replace Package**
- **Cancel Package**
- **View Instructions**

An outstanding computer package does not disable **Analyze Conversations**
for other unclaimed chats. Its eligible count excludes the package's frozen
rows. If no other rows are available, show that conversations are reserved
in the computer package rather than presenting a generic analysis failure.
Exact helper wording remains deferred with the rest of this section.

Replacement confirmation:

> **Replace Review Package?**
>
> The current package will stop accepting suggestions. Its conversations
> will be placed in a new package with any other eligible conversations.

Actions: **Replace Package**, **Keep Current Package**

Cancellation confirmation:

> **Cancel Review Package?**
>
> No suggestions will be imported from this package. Its conversations
> will be available for a future analysis.

Actions: **Cancel Package**, **Keep Package**

#### Import review

Before committing anything, show:

> **Review Import**
>
> Speak GPT checked the suggestions file against the current phone.
> Suggestions you continue with will be added to Pending for your review.

Use only categories that describe a deterministic app result:

- **New Memories**
- **Suggested Edits**
- **Suggested Merges**
- **Archive Suggestions**
- **Possible Matches**
- **Already Saved**
- **No Changes Needed**
- **Changed on This Phone**
- **Couldn't Be Used**
- **Conversations to Retry**
- **No Longer Eligible**

Primary action: **Send N to Pending**. If N is zero: **Finish Import**.

Success:

> **Suggestions Added to Pending**
>
> N suggestions were added to Pending. Review them before any memory is
> created, edited, merged, or archived.

Actions: **View Pending Memories**, **View Details**

Valid import with nothing new:

> **No New Suggestions**
>
> Speak GPT checked the file, but there was nothing new to add to Pending.
> Details show items that were unchanged, already pending, no longer
> eligible, or could not be used.

Partial completion:

> **Import Partially Completed**
>
> Suggestions from N conversations were added to Pending. M conversations
> still need attention and remain available to retry.

#### Import errors

Each failure says what happened and confirms that nothing unsafe changed:

| Title | Message |
|---|---|
| **Different Review Package** | This file belongs to a different review package. Nothing was imported. |
| **Review Package No Longer Active** | This package was canceled or replaced. Nothing was imported. |
| **Suggestions File Could Not Be Read** | The file is missing required information or is not in the expected format. Nothing was imported. |
| **Some Suggestions Are Out of Date** | Related memories, conversations, or targets changed on this phone after the package was created. Unaffected suggestions can continue; affected suggestions are shown as conflicts. |
| **Suggestions Do Not Match** | One or more results do not match the conversations in this package. Nothing was imported for the affected conversations. |
| **Already Imported** | These suggestions were already checked. No duplicate suggestions were added. |

Recent-run route labels, when needed, are **In App** and **On Computer**.
An outstanding computer run uses **Import Suggestions**, not **Run Again**.

**Owner review required for Phases B–D:** approve this screen as one coherent
flow when Phase B starts. There is no reason to approve every file-workflow
label during Phase A.

### Possible Match copy — approve when the feature is scheduled

Embedding-assisted review uses a **Possible Match** badge and a
**Compare Memories** action. The comparison screen offers:

- **Keep Both**
- **Keep Existing**
- **Replace Existing**
- **Delete Suggestion**

Replacement confirmation:

> **Replace Existing Memory?**
>
> The existing memory will be marked as replaced by this suggestion. You
> can review both records in memory history.

Actions: **Replace Existing**, **Cancel**

Do not show a percentage. The score chooses what comparison to present; it
does not make the user's decision.

The LAN endpoint side path should reuse the repository's existing approved
unencrypted-endpoint warning rather than inventing another warning.

### Copy behavior rules for every phase

- Suggestions always go to **Pending** and never become active without user
  approval.
- Persistent work and recoverable failures are not communicated only by a
  toast.
- Every failure summary states what changed, what did not change, and what
  the user can do next.
- Technical IDs, hashes, JSON paths, and database states stay out of the
  main UI.
- Never create a second Pending screen for the computer route.
- Do not let a model invent user-facing categories or organization.

---

## 7. Suggested order and why

Revision 5 uses the explicit sequence in §14:

1. **Phase A — RAG correctness repairs:** source/archive semantics, run
   integrity, complete-set retrieval, filtering/backfill, edit/index
   lifecycle, keyword ranking, context budget, evidence foundation, and
   end-to-end evaluation.
2. **Phase B — Portable memory package:** stable exchange identities,
   hashes/revisions/tombstones, full searchable export, read-only
   lore/card/chat evidence, and verified package ledgers.
3. **Phase C — Desktop agent workflow:** shared retrieval spec, materialized
   file workspace, actual search/analysis instructions, strict
   add/revise/merge/retire proposal output, and cross-agent trials.
4. **Phase D — Safe reconciliation:** import, conflict detection, Pending
   review, atomic approval/rollback, deltas, optional encryption, and
   compatibility.

Semantic Possible Match is candidate support across these phases, never an
automatic merge rule. Future CLI/MCP/live transports come only after the
file loop is boringly reliable.

## 8. Decisions and review gates

### What the owner genuinely needs to review

1. **Source-control presentation:** approve or revise **Saved memories** and
   **Lore books**, their helper text, and the two-switch global/per-chat
   placement in §5.3, including the archive helper and aggregate active-scene
   line. The four combinations themselves are the recommended behavior and
   are already supported per chat.
2. **Archive history copy/default:** when **Archive this chat** is re-enabled,
   confirm **Include Earlier Messages** as the recommended default versus
   New Messages Only. Source switches never alter this queue.
3. **Saved-memories model prerequisite:** preserve the standing rule that an
   embedding model is required to enable the full engine (the default until
   explicitly changed), or deliberately support a keyword-only enabled mode
   for low-resource devices. Complete lexical fallback after model/index
   failure is required either way and is not part of this choice. This does
   not block internal correctness repairs; it must be settled before the new
   first-enable UI ships.
4. **Phase B plaintext disclosure/default scope:** approve the exact privacy
   warning and selected-chat-review default before the first exchange export.
5. **Phase D maintenance wording:** approve the visible Edit/Merge/Archive
   Suggestion labels and stale-conflict choices.
6. **Optional encryption timing:** accept plaintext-first interoperability
   (recommended) or require the separate encrypted wrapper/helper before
   broader release.

Final wording review is intentionally deferred until its phase approaches
UI implementation. Until decision 3 is made, implementation preserves the
existing first-enable requirement. Plain-http LAN endpoints are unrelated to
the file workflow and remain an optional future security decision.

### Technical gates that do not require routine owner input

- Phase A run integrity and shared filing tests pass.
- The partial-index, relevance-floor, and backfill regressions in §5.5 have
  reproducing tests and verified fixes.
- All four source modes pass final-prompt integration tests and independent
  failure tests.
- Scope leakage is zero in the golden corpus.
- API and computer results share evidence, validation, Pending, and
  acceptance behavior.
- On-device benchmark results are recorded and no release blocker is hidden
  behind "works in unit tests."
- Full package → desktop result → Pending → approval → delta passes replay,
  stale-state, process-death, and lost-workspace tests before Phase D exits.
- Reaffirm: `plot_ledger` card-section suggestions stay; no decision or
  change is needed despite the audit plan's contrary instruction.

### Fresh-agent correctness review after this revision

Before implementation, give this branch to a new capable agent that has not
participated in the prior plan debate. This is a final independent
correctness audit, not another product-design exercise.

The reviewer should:

1. Inspect the current code and owner rules directly; do not accept either
   plan's factual claims without verification.
2. Trace capture → eligibility → claim/export → analysis → parse → shared
   filing boundary → Pending → approval for both the API and file routes.
3. Trace both read paths from the global/per-chat switches through scope
   filtering, vector/keyword candidate selection, ranking, lore/card
   coexistence, cooldown, budget, final prompt, and diagnostics. Verify all
   four source combinations.
4. Reproduce or disprove every release-blocking RAG finding in §5.5,
   especially partial-index starvation, relevance-after-top-K, and missing
   backfill.
5. Try to find silent data-loss, false-completion, duplicate, replay,
   rename, source/archive coupling, stale-target, partial-import,
   crash-recovery, scope leakage, stale-memory, and cross-route consistency
   failures.
6. Separate **must-fix correctness/privacy issues** from **optional
   hardening** and **complexity with no demonstrated v1 value**.
7. Check that every failure leaves deterministic recoverable state and that
   no model output can become an active memory without owner action.
8. Check that the Phase A wording covers every newly visible state and that
   Phases B–D wording matches the planned state machine without exposing
   internal machinery.

Requested output:

- confirmed findings;
- new holes, with code evidence and a concrete failure scenario;
- contradictions or ambiguous requirements;
- minimum recommended amendment;
- explicitly unnecessary machinery;
- any issue that cannot be solved completely, with the residual risk.

The fresh reviewer should amend this canonical document only after each new
claim is verified. Product wording remains an owner decision even when the
reviewer proposes alternatives.

---

## 9. Authoritative desktop-agent integration architecture

This section answers the missing half directly: **how an agent on a computer
actually uses, inspects, and helps maintain the phone's memory/RAG system.**
It is the normative implementation plan for Revision 5. Earlier sections
remain the product and correctness rationale; where their compressed phase
labels or ADD-only wording conflict with this section, this section wins.

### 9.1 Recommended end state in one page

The recommended architecture has five boundaries:

1. **The Android app is the only authoritative store.** Active memories,
   lorebooks, cards, transcript review state, deletions, and user approvals
   live on the phone. A computer never edits the SQLCipher databases or
   recovery backups.
2. **A versioned Memory Exchange Package is the file interface.** It is a
   ZIP (or an optionally encrypted wrapper around that ZIP) containing UTF-8
   JSON/JSONL, JSON Schemas, a retrieval specification, evidence, and agent
   instructions. It is a new contract, not a rename of
   `companion-memory-export-v1` and not the `PHOSBKP2` recovery format.
3. **The computer may maintain a disposable workspace mirror.** A first
   full package materializes a directory of records. Later delta packages
   update those files by stable ID and tombstone. The mirror may have local
   lexical or vector indexes, but it is a rebuildable cache, never a second
   source of truth.
4. **Phone and computer share retrieval rules, not implementation code.**
   Scope eligibility, status gates, normalization, document construction,
   and evidence rules are versioned testable specifications. Android keeps
   its Kotlin implementation; a desktop agent, future CLI, or MCP server
   implements the same contract. Semantic ranking may use a different model
   and need not produce identical scores. The app rechecks every imported
   proposal against current phone state.
5. **The computer returns proposals, never mutations.** It may propose
   `add`, `revise`, `merge`, or `retire`. Additions become ordinary memory
   drafts. Revisions, merges, and retirements become reconciliation
   proposals. Nothing becomes active, edited, superseded, archived, or
   deleted until the user approves it on the phone.

The practical loop is:

```text
Phone: full export once
  → Computer: materialize read-only workspace and build local indexes
  → Phone: later review job and/or delta export
  → Computer agent: scope-filter, search, inspect evidence, write proposals
  → Phone: validate, stage, show Pending/conflicts
  → User: edit/approve/reject
  → Phone: apply approved changes and emit them in the next delta
```

This solves the owner's token concern without pretending the phone can
delegate trust. A capable file agent searches the local mirror and sends
only relevant files/chunks to its model. The user can use a subscription
agent, a local model, or a future helper against the same contract.

### 9.2 Repository facts that constrain the design

The following are verified against the current branch and are design inputs,
not hypothetical future problems:

- `MemorySeedCodec.EXPORT_FORMAT` is
  `companion-memory-export-v1`. It exports schema-shaped memory data,
  transcripts, and an `app_chats` envelope. It deliberately omits
  embeddings and change-log `prior_state`.
- `MemoryStore.importData` is add-or-skip by stable ID. It does not reconcile
  edits, merges, tombstones, or concurrent changes.
- `MemoryStore.deleted_ids` already records tombstones for many memory-store
  record types, but `MemoryStore.exportData` /
  `MemorySeedCodec.serialize` do not export those rows.
- `memories.updated_at`, stable memory/target IDs, memory target join tables,
  memory `change_log`, status, `supersedes`, and model-keyed embeddings
  already exist. They should be reused.
- The current `change_log` is memory-only, its prior snapshot is deliberately
  device-local, and it cascades away on hard memory deletion. It is useful
  history/undo input but is not an incremental synchronization journal.
- The current `proposals` table is dormant for Archivist memory drafts;
  memory drafts live in `memories.status='draft'`. The dormant table is a
  suitable small foundation for non-add reconciliation proposals after it is
  given explicit operation/base/evidence/conflict fields.
- `LoreBookStore` is a separate SQLCipher database. Lorebooks and entries
  have stable UUIDs and created/updated epoch milliseconds, but the store has
  no portable export, tombstones, change feed, or revision history.
- Chats are encrypted SharedPreferences. Current `chat_id` values are hashes
  of mutable names; rename code repoints transcripts and cooldowns. A
  name-derived ID is not a safe long-lived desktop identity.
- Transcript rows do have stable UUIDs. Their `content` is a JSON turn list,
  but turns have no durable message IDs. Claimed transcript rows can be made
  immutable; a stable exported turn reference can therefore be derived from
  transcript ID plus turn ordinal without adding message IDs to every chat
  record.
- `ReadableChatFormats` already demonstrates a verified incremental baseline
  based on per-chat SHA-256 fingerprints. That pattern can be reused for chat
  deltas, but it does not preserve deletion history by itself.
- `PortablePackageFormat` / `PackageCrypto` protect recovery packages with
  bounded AES-256-GCM/PBKDF2 machinery. Recovery packages may contain
  installation material and are the wrong trust boundary for an AI agent.
  The exchange package may reuse reviewed crypto primitives, but never its
  recovery key, database keys, magic, or implied restore authority.
- `Librarian` currently embeds only `embedding_text` or content, uses any
  available subset of vectors, applies its similarity floor after top-K,
  and falls back to a simple substring keyword search. `Enforcer` then
  removes lore-near-duplicates and cooling items without asking for
  replacements. These are Phase A correctness issues, not desktop-only work.

### 9.3 Architectural decisions that must be made now

These decisions prevent the RAG repair from making computer integration
harder later. They are technical recommendations, not open-ended owner
questions.

#### 9.3.1 Authority and mutability

- The phone is authoritative.
- Exported current-state records are read-only.
- Agent output contains proposals only.
- Imported proposals never run through backup import.
- Lorebooks, roleplay cards, companion definitions, user personas, and model
  rules are read-only references for the first computer workflow. The agent
  may flag overlap or recommend that the user inspect one, but it cannot
  submit a lore/card/persona mutation.
- A suggested deletion is represented as `retire`, not a tombstone. The phone
  creates a real tombstone only after a user performs an actual hard delete.
- Merges default to keeping a survivor and marking source memories
  `superseded`, not hard-deleting them. This is reversible and preserves
  history.

#### 9.3.2 Stable identity

- Existing memory, world, campaign, roleplay-character, project, companion,
  lorebook, lore-entry, card, and card-entry IDs remain opaque stable IDs.
  Never derive identity from a display name and never regenerate IDs during
  export.
- Add an immutable `conversation_uid` (UUID/opaque random ID) to chat
  metadata and transcript lineage. Existing name-hash `chat_id` can remain
  an internal compatibility key while call sites migrate. Backfill one UID
  per current chat and preserve it across rename, duplication rules, and
  export/import.
- Display names and aliases are search aids only. Imported target references
  are IDs-only.
- A frozen exported turn uses
  `turn_ref = "<transcript_id>:<zero-based-turn-index>"`. The package also
  carries a quote hash. This avoids a high-risk per-message migration while
  making evidence deterministic within a sealed transcript.

#### 9.3.3 Revision and conflict identity

Timestamps are useful to users but are not sufficient concurrency control.
Clock skew and two edits within one timestamp resolution make
`newer updated_at wins` unsafe.

Every exported mutable record therefore carries:

- `record_type`;
- stable `record_id`;
- store-local monotonic `revision_seq`;
- RFC 3339 UTC `created_at` / `updated_at` where available;
- `semantic_hash`;
- `hash_algorithm` (initially `speakgpt-semantic-v1`);
- deletion state, if this line is a tombstone.

The semantic hash is not a hash of pretty-printed JSON and does not require
RFC 8785. `speakgpt-semantic-v1` is:

1. a fixed type/version marker;
2. the stable ID;
3. each semantic field in schema order, encoded as length-prefixed UTF-8;
4. unordered ID/tag/trigger sets normalized, deduplicated, and sorted;
5. order-sensitive fields kept in order;
6. SHA-256 over the resulting byte sequence.

The exact field list and normalization fixtures ship in
`spec/normalization_spec.json` and repository tests. An edit proposal names
the base `revision_seq` and `semantic_hash`; the phone compares both to
current state. `updated_at` is never the sole conflict key.

This revision hash is deliberately different from duplicate identity:

- `semantic_hash` includes record type and stable record ID. It answers
  “is this exact record still the revision the agent saw?”
- `duplicate_fingerprint` excludes record ID, title, and kind, and hashes
  normalized content plus scope and sorted placement IDs. It answers “is the
  same claim already filed in the same place?” The app then compares kind:
  same kind is deterministic duplicate; a kind difference is a Possible
  Match because it may change rendering semantics.
- `proposed_payload_hash` hashes an agent's complete proposed semantic body
  before the phone assigns an authoritative ID to a new draft.

Never use one of these values for another purpose.

#### 9.3.4 Change feeds and cursors

One global transaction cannot span `companion_memory.db`,
`lorebook.db`, and encrypted chat preferences. The manifest therefore uses
a **cursor vector**, not a pretend global revision:

```json
{
  "memory_seq": 418,
  "lore_seq": 92,
  "chat_baseline": "cb-8e1b...",
  "target_catalog_seq": 133
}
```

- Add an append-only `exchange_changes` journal to `MemoryStore` for exported
  memory-store types, plus an explicit exchange-tracking-enabled state.
  Journal hooks are dormant for users who have never created a computer
  workspace. Once enabled, every authoritative mutation writes its record
  type, ID, operation (`upsert`/`delete`), sequence, resulting hash, actor
  class, and timestamp in the same SQL transaction.
- Add the equivalent gated journal and tombstone table to `LoreBookStore`.
- The first full export enables tracking **before** its source snapshot is
  read, initializes baseline revisions/high-water marks, and therefore
  journals mutations that race with export. Only a successfully written and
  verified package creates the active workspace/cursor. If that first export
  fails and no workspace exists, disable tracking and discard rows/tombstones
  needed only by the failed attempt.
- Keep tracking only while a paired workspace is active. Explicitly removing
  the workspace disables the hooks and permits all exchange-only journal
  history/tombstones to be pruned; a future full export establishes a new
  honest baseline. Thus people who never use computer review pay the schema
  migration cost, not an unbounded write/storage cost on every memory edit.
- Chats use an app-held per-workspace fingerprint baseline plus a durable set
  of known conversation UIDs. A missing previously-known UID emits a chat
  tombstone in the next delta. The baseline advances only after the export
  file is atomically written and verified, matching the existing readable
  backup safety pattern.
- A full snapshot establishes a new baseline. History before that baseline is
  not invented.
- While a workspace is active, change feeds keep enough rows to satisfy its
  acknowledged cursor.
  If required rows were pruned or a delta in the chain is missing, the app
  requires a new full export.

#### 9.3.5 Shared retrieval specification

Do not try to run Android Kotlin on the desktop and do not freeze behavior in
an English prompt alone. Publish a versioned, machine-readable retrieval
specification plus golden fixtures.

The specification separates:

- **Eligibility (must match exactly):** active status; source enabled;
  fiction wall; companion/world/campaign/roleplay-character/project target
  rules; archived/superseded/draft exclusion; lorebook selection; enabled
  lore entries; read-only card status rules.
- **Document construction (must match):** which title, content,
  `embedding_text`, tags, aliases, target names, and kind labels form lexical
  and semantic documents; document version.
- **Normalization (must match):** Unicode normalization, locale-independent
  case handling, word boundaries, whitespace, exact fingerprint fields.
- **Ranking guidance (compatible, not byte-identical):** semantic relevance,
  lexical/entity hits, importance, updated freshness, scope boosts, and
  tentative dampening.
- **Context selection (must preserve invariants):** relevance floor before
  final top-K, post-filter backfill, source precedence, atomic entry cuts,
  cooldown, and budget reporting.

Android is the conformance reference for eligibility and acceptance, not for
model scores. A desktop model may produce different semantic ordering. That
is acceptable because semantic scores select candidates for inspection;
they never grant mutation authority. Shared fixtures assert eligible and
forbidden IDs, exact hashes, lexical hits, and evidence validation.

#### 9.3.6 Evidence and provenance

The current four provenance strings cannot represent multiple sources, a
merge, or rename-safe evidence. Add a normalized `memory_evidence` table (or
equivalent child table) keyed by evidence ID, with:

- memory ID when accepted, or proposal ID while pending;
- immutable conversation UID;
- transcript ID;
- turn start/end ordinal;
- short excerpt and excerpt SHA-256;
- source timestamp when known;
- source kind (`chat`, `manual`, `import`, `agent_analysis`);
- transport/run/package/item/proposal IDs;
- recorded-at timestamp.

The existing `origin` and provenance columns remain for backward-compatible
labels. `origin` continues to answer who created the record;
`memory_evidence` answers why it exists. A merge may preserve several
evidence rows. The app verifies that cited transcript IDs belong to the
exported review item and that the excerpt/quote hash matches exported text.

### 9.4 The Memory Exchange Package

#### 9.4.1 Container and package purposes

Use extension `.sgmemory` for the ordinary ZIP and `.sgmemory.enc` for the
optional encrypted wrapper. ZIP entry names are fixed ASCII paths; JSON is
UTF-8 with LF line endings. JSONL has one complete object per line and no
multi-line records. No executable file is required in the package.

The same format supports two explicit purposes:

- `archive_review`: selected/claimed, unreviewed transcripts plus the
  current records needed to analyze them. This is the normal
  “review chats on my computer” route.
- `library_maintenance`: the current memory catalog, read-only lore/card
  references, lineage metadata, and optional retained evidence for finding
  duplicates, stale facts, weak wording, and missing scope.

A package can be `full` or `delta`. An `archive_review` package remains
self-contained for its frozen review items even if it also contains a
workspace delta. A bare delta is only valid against the exact preceding
desktop workspace snapshot named in its manifest.

#### 9.4.2 Exact package tree

```text
manifest.json
checksums.sha256
README.md

spec/
  manifest.schema.json
  record.schema.json
  proposals.schema.json
  retrieval_spec.json
  normalization_spec.json
  capabilities.json

instructions/
  agent_workflow.md
  safety_and_scope.md

data/
  targets.jsonl
  memories.jsonl
  lorebooks.jsonl
  lore_entries.jsonl
  roleplay_cards.jsonl
  card_entries.jsonl
  source_conversations.jsonl
  tombstones.jsonl
  rejected_fingerprints.jsonl
  changes.jsonl

history/
  record_events.jsonl

evidence/
  transcripts/<transcript_id>.jsonl

jobs/
  review_items.jsonl

output/
  proposals.template.json
```

Rules:

- Every path appears in `manifest.json` with byte length, SHA-256, record
  count where applicable, required/optional status, and completeness state.
- `checksums.sha256` allows ordinary tools to detect accidental corruption.
  In a plaintext package this is **not authentication**; an attacker can
  recompute it.
- Empty required datasets exist as empty files. Omission never ambiguously
  means “empty.”
- `changes.jsonl` is empty in a full package and contains only the delta
  operation index in a delta package. Current upsert bodies still live in
  their type file; deletes live in `tombstones.jsonl`.
- `rejected_fingerprints.jsonl` contains only the existing narrow,
  conversation-scoped rejection identity needed to avoid an exact repeat.
  It does not expose rejected draft text and does not become broad negative
  learning.
- `history/record_events.jsonl` contains revision events and hashes, not every
  historic body by default. Full before-images remain phone-side for
  rollback. This keeps the package useful without multiplying sensitive
  copies.
- The package never contains a SQLCipher database, SharedPreferences file,
  database/recovery key, API key, auth token, recovery package, image cache,
  embedding vector, or model file.

#### 9.4.3 Manifest

Minimum manifest shape:

```json
{
  "format": "speakgpt-memory-exchange",
  "format_major": 1,
  "format_minor": 0,
  "schema_version": "1.0.0",
  "retrieval_spec_version": "1.0.0",
  "normalization_version": "speakgpt-semantic-v1",
  "package_id": "pkg-...",
  "package_kind": "full",
  "purpose": "archive_review",
  "workspace_id": "ws-...",
  "snapshot_id": "snap-...",
  "base_snapshot_id": null,
  "created_at": "2026-07-24T18:00:00Z",
  "producer": {
    "app_id": "org.teslasoft.assistant",
    "app_version": "...",
    "database_schema": 16
  },
  "cursor": {
    "memory_seq": 418,
    "lore_seq": 92,
    "chat_baseline": "cb-...",
    "target_catalog_seq": 133
  },
  "source_completeness": {
    "memory": "complete",
    "lore": "complete",
    "chats": "complete",
    "evidence": "selected"
  },
  "required_capabilities": [
    "proposal-add-v1",
    "proposal-revise-v1",
    "proposal-merge-v1",
    "proposal-retire-v1"
  ],
  "import_binding": "128-bit-random-value",
  "files": []
}
```

`import_binding` prevents accidentally importing a result for another
package. It is a bearer value present in the package, so it is not described
as strong authentication against someone who already has the package.

The app records `workspace_id`, `package_id`, snapshot/cursor vector,
binding, file hash, claimed transcript IDs, and lifecycle state in its packet
ledger. Opaque identifiers are not displayed in the normal UI.

#### 9.4.4 Record envelopes

Every JSONL line uses a common envelope:

```json
{
  "record_type": "memory",
  "record_id": "m-...",
  "revision_seq": 418,
  "semantic_hash": "sha256:...",
  "hash_algorithm": "speakgpt-semantic-v1",
  "created_at": "2026-07-01T12:00:00Z",
  "updated_at": "2026-07-20T09:15:00Z",
  "status": "active",
  "data": {},
  "source_refs": []
}
```

`data` for a memory carries the existing six-kind model, not a new ontology:

- `kind`: fact / preference / event / status / instruction / lore;
- title, content, optional `embedding_text`, tags, importance;
- scope and sorted stable companion/world/campaign/roleplay-character/
  project IDs;
- entity references;
- origin and existing provenance labels;
- supersedes/superseded relationship;
- the placement-aware `duplicate_fingerprint`;
- readable target names/aliases as redundant search labels only.

Lorebook, lore-entry, card, card-entry, target, conversation, and event
records use the same envelope. Target catalog lines include type, canonical
name, available aliases/name history, parent IDs, status, and whether the
target is writable by an Archivist proposal or read-only context.

Tombstones are explicit:

```json
{
  "record_type": "memory",
  "record_id": "m-...",
  "delete_seq": 421,
  "deleted_at": "2026-07-24T18:04:00Z",
  "last_semantic_hash": "sha256:...",
  "reason": "user_deleted"
}
```

The agent applies a tombstone to its mirror and never resurrects that ID.
An agent result cannot manufacture one.

#### 9.4.5 Conversations and evidence files

`source_conversations.jsonl` contains immutable conversation UID, current
display name, previous display names when known, availability/completeness,
scene target IDs, first/last source timestamps, and included transcript IDs.

Each `evidence/transcripts/<transcript_id>.jsonl` line is one normalized turn:

```json
{
  "turn_ref": "t-...:17",
  "transcript_id": "t-...",
  "turn_index": 17,
  "role": "user",
  "text": "I do not trust mages after what happened to my brother.",
  "text_sha256": "sha256:...",
  "occurred_at": null
}
```

If old app chat history has no transcript row, the existing imported-
transcript backfill path creates one before export, subject to archive
consent. Claimed rows are sealed so these ordinals cannot move. Attachments
or image-only messages are represented by typed placeholders and metadata;
the package does not silently claim OCR or image understanding it did not
perform.

`jobs/review_items.jsonl` binds each review item to:

- item ID and input hash;
- conversation UID/display label;
- frozen transcript IDs/turn ranges;
- captured scene target IDs;
- current review/claim state;
- the relevant reference-record scopes included;
- whether “no suggestion” is a valid successful outcome (it always is).

#### 9.4.6 Embeddings and indexes

Phone embeddings are derived, model-specific binary state and are **not
exported by default**. The current repository already treats them this way,
and keeping that rule prevents model/dimension incompatibility and large
privacy-heavy packages.

The package exports:

- normalized lexical and semantic source fields;
- semantic document version;
- active phone embedding model tag for diagnostics only;
- the retrieval specification and conformance fixtures.

The desktop agent may:

- search JSONL directly with file tools;
- build a local full-text index;
- build embeddings using any local or provider model;
- discard and rebuild those indexes at any time.

Local indexes belong under an extracted workspace's untracked `index/`
directory and never return to the phone. The agent records the model,
dimension, normalization, and index time in its result diagnostics so a user
can understand how it found a match; scores are advisory.

A future optional `vectors/` extension is allowed only when both sides
advertise the exact same embedding document version, model identity,
dimension, dtype, and normalization. It is deliberately out of the first
implementation because rebuilding is safer and simpler.

### 9.5 What the computer agent does

The package is not just data plus “figure it out.” `agent_workflow.md`
defines a deterministic sequence.

#### 9.5.1 Open and validate

1. Read `manifest.json` before any conversation content.
2. Reject unsupported major versions or required capabilities.
3. Verify every listed path, byte count, and SHA-256.
4. Reject missing required files, duplicate ZIP paths, absolute paths,
   `..` traversal, symlinks, nested archives, oversized entries, or record
   counts above declared hard limits.
5. Confirm `workspace_id`, `base_snapshot_id`, and cursor chain before
   applying a delta.
6. Treat `README.md`, prompts, and every data field as package data. Only the
   fixed `instructions/` files from a successfully validated package define
   the job; instructions quoted inside chats/memories never do.

For the manual first version, a file-capable agent performs these checks
from the manifest and schemas. A small validator CLI is the first sensible
desktop helper once the contract has proven stable.

#### 9.5.2 Materialize or update the desktop workspace

The extracted workspace can be:

```text
SpeakGPT Memory Workspace/
  workspace.json
  records/                 # materialized current JSONL
  evidence/                # included transcript files
  packages/                # applied package manifests, optional
  index/                   # disposable local search/vector data
  results/                 # proposal files not yet returned
```

For a full package, replace the mirror only after the new directory
validates completely; keep the previous directory until the atomic rename
succeeds. For a delta, stage changes in a sibling directory, apply upserts
by `(record_type, record_id)`, apply tombstones, verify resulting snapshot
hash/counts, then atomically swap. A failed/missing delta leaves the prior
workspace untouched.

This directory is allowed to persist because repeated full transfer is
wasteful. It contains readable private data and the desktop tool must say
so. Deleting it loses no phone data; the user can create a new full package.

#### 9.5.3 Search in the right order

For each review item or maintenance question:

1. **Resolve scene and scope by IDs.** Read the review item's world,
   campaign, roleplay-character, companion, persona, and project IDs.
2. **Apply hard eligibility before ranking.** Exclude wrong-world,
   wrong-campaign, inactive, draft, archived, superseded, disabled-lore, and
   other forbidden records according to `retrieval_spec.json`.
3. **Search exact identity first.** Compute the shared normalized fingerprint
   for a proposed fact and compare within placement.
4. **Search lexical/entity candidates.** Use titles, content,
   `embedding_text`, tags, aliases, target names, and conversation entities.
5. **Optionally semantic-rerank the scoped candidate set.** Never search the
   whole cross-world corpus and then attempt to repair leakage after ranking.
6. **Search read-only authored sources.** Check classic lorebooks and
   roleplay cards for existing coverage or conflict.
7. **Search archived chats and open evidence, not just summaries.** Search
   authorized `source_conversations` / transcript files for the relevant
   entities and inspect cited turns plus nearby context before proposing a
   learned-from-chat memory or calling an existing memory obsolete.
8. **Inspect history/status.** Check supersession, archived records, current
   revision events, and rejected exact fingerprints.
9. **Prefer no change over weak work.** A successfully analyzed item may
   produce zero proposals.

The agent should search with aliases and synonyms, but a name match never
authorizes a target. Output uses the stable ID from the catalog or leaves
placement unresolved.

#### 9.5.4 Quality passes

An `archive_review` job runs:

- evidence extraction;
- durable/atomic-memory test;
- exact and near-match search;
- placement validation;
- authored lore/card overlap check;
- proposal generation.

A `library_maintenance` job may additionally flag:

- exact same-placement duplicates;
- likely near-duplicates;
- active records apparently superseded by later active status/event records;
- possible contradiction/negation;
- orphaned or archived target links;
- ambiguous title/content that will retrieve badly;
- stale derived `embedding_text`;
- missing evidence or provenance;
- a broad memory that should be split into atomic memories;
- two atomic memories that are truly the same claim and may be merged.

These are candidate-generation categories, not automatic conclusions.
Similarity cannot decide duplicate versus update versus contradiction.

#### 9.5.5 Result contract

The agent writes one strict `proposals.json`:

```json
{
  "format": "speakgpt-memory-proposals",
  "format_major": 1,
  "format_minor": 0,
  "workspace_id": "ws-...",
  "package_id": "pkg-...",
  "analyzed_snapshot_id": "snap-...",
  "import_binding": "...",
  "agent": {
    "name": "user-supplied label",
    "retrieval_implementation": "file-tools",
    "embedding_model": null
  },
  "items": [],
  "proposals": []
}
```

Each job item returns exactly one terminal result:

- `complete` with proposal IDs;
- `no_change`;
- `failed` with a bounded plain-language reason.

That makes “nothing found” different from “the agent skipped this
conversation,” and lets the phone advance only successfully analyzed frozen
items.

Every proposal has:

- globally unique proposal ID;
- operation: `add`, `revise`, `merge`, or `retire`;
- record type (memory only in the first workflow);
- base record references with revision/hash where applicable;
- complete proposed final memory body for add/revise/merge, not an opaque
  command or executable patch;
- phone-recomputed `proposed_payload_hash` and
  `duplicate_fingerprint` fields (the agent's copies are checked, never
  trusted);
- reason category;
- concise rationale;
- evidence/source references and excerpts;
- related possible-match IDs and why they were retrieved;
- stable target IDs;
- confidence label (`low`/`medium`/`high`) as advisory metadata;
- proposal schema version.

For `add`, the agent supplies a proposal ID but not an authoritative memory
ID; the phone generates the normal `m-...` ID only when it stages the draft.
For `revise` and `retire`, the existing memory ID is fixed. For `merge`, the
result either names one existing survivor or requests a new survivor whose
ID the phone generates.

The importer rejects unknown required fields/operations rather than
guessing. Unknown optional diagnostic fields may be ignored.

#### 9.5.6 Operation semantics

| Agent operation | Agent means | Phone stages | Approval effect |
|---|---|---|---|
| `add` | A supported memory is absent. | Existing `memories.status='draft'`, through the shared filing boundary. | User edit/accept activates it. |
| `revise` | One current memory would be clearer or more correct. | Reconciliation proposal with base revision/hash and a complete proposed replacement. | Transactional update; full prior state logged; evidence preserved; vector invalidated. |
| `merge` | Two or more current memories represent one claim. | Reconciliation proposal naming every source/base and proposed survivor body. | Transactionally create/update survivor and mark sources superseded; no default hard delete. |
| `retire` | A current memory appears obsolete or should stop retrieving. | Reconciliation proposal with base revision/hash and reason/evidence. | Archive/supersede by explicit user choice. Hard delete stays a separate user action. |

An unchanged record is not copied back. It is represented by the job item's
`no_change` outcome. A revise/merge proposal whose proposed final payload
already matches current phone state is an import no-op. An add whose
duplicate fingerprint matches an existing same-placement/same-kind record
is already saved/pending, not a new ID.

#### 9.5.7 Provenance explanation

The phone must be able to answer:

- What did the agent propose?
- Which current records did it compare?
- Which chat turns support the claim?
- Which package/snapshot did it use?
- What changed on the phone after that snapshot?
- Which retrieval method found the possible match?

Therefore the result carries record IDs/hashes and exact source references,
not only model prose. The UI can show a short rationale but should let the
user open **Evidence**, **Existing record**, and **Package details**. Agent
chain-of-thought is neither requested nor stored; concise reasons and
verifiable references are sufficient.

### 9.6 Safe reconciliation on the phone

#### 9.6.1 Parse and stage before touching the store

Import is a two-boundary process:

1. Copy the selected result into app-private staging.
2. Enforce file/byte/record/depth/string limits.
3. Parse strict JSON and validate the result schema.
4. Match format, workspace, package, binding, snapshot, item IDs, input
   hashes, target IDs, and evidence.
5. Compute every proposed payload hash and duplicate fingerprint
   independently.
6. Classify every proposal against **current** phone state.
7. Persist the import session and staged items.
8. Only then create draft/reconciliation rows, one idempotent transaction
   per item or atomic merge group.

No partially parsed model output reaches `MemoryStore.importData`.

#### 9.6.2 Classification

| Current comparison | Classification | Behavior |
|---|---|---|
| Proposal ID/package item already committed | Replay | No-op; report already imported. |
| Add duplicate fingerprint and kind match current active/draft | Already saved/pending | No second draft. |
| Add matches archived/superseded or same content with semantic kind difference | Possible Match | Stage a user decision; do not resurrect/overwrite. |
| Revise/retire base revision and hash equal current | Clean | Stage proposal. |
| Merge every base revision/hash equals current | Clean | Stage one atomic merge proposal. |
| Revise/merge proposed payload equals current semantic body | Unchanged | No-op. |
| Base record changed since export | Stale conflict | Keep proposal visible but unapplied; show base/current/proposed. |
| Base/target was deleted or tombstoned | Deleted conflict | Never recreate implicitly; offer refresh/dismiss. |
| Target ID exists but status/placement is no longer valid | Target conflict | Require user correction or refreshed package. |
| Evidence item/turn/hash does not match package | Invalid evidence | Reject that proposal; do not degrade to uncited. |
| Unsupported schema/capability | Incompatible | Reject before store changes. |

Unrelated phone edits do not invalidate an entire result. Conflict checking
is per referenced record and item. A stale package can still yield clean add
proposals if they remain unique and their targets/evidence remain valid.

#### 9.6.3 Pending is one user surface

Do not create a second memory library for computer work.

- Add proposals appear as ordinary Pending memory drafts with a source badge
  such as **From computer review** (wording unapproved).
- Revise, merge, and retire proposals use the extended dormant `proposals`
  table and appear in the same Pending mode, with operation-specific cards.
- A Pending filter may distinguish New / Edit / Merge / Archive / Conflict,
  but the default remains one chronological queue.
- Approving a proposal always revalidates current revisions, exact identity,
  targets, and evidence. Opening a proposal for hours cannot bypass a change
  made in another screen.
- Rejection stores the existing narrow exact suppression where applicable;
  it does not train a broad negative classifier.

#### 9.6.4 Approval and rollback

All approved operations go through one semantic mutation service that:

- validates target/scope/kind/status;
- writes full target joins;
- updates `updated_at`;
- increments the exchange journal when exchange tracking is active;
- clears stale embeddings and cooldowns;
- preserves/links evidence;
- writes a complete before-image and after-hash to an apply batch;
- creates tombstones only for explicit hard deletes;
- commits the whole operation atomically.

Add `reconciliation_apply_batches` (or equivalent) with batch ID, proposal
ID, applied timestamp, operation, before-state JSON, after hashes, and
rollback status. A merge batch includes every affected record and is one
transaction.

Rollback rules:

- Before approval, rollback/import undo simply removes or rejects the staged
  draft/proposal rows for that import session.
- After approval, **Undo** restores the complete before state only if the
  affected records still have the expected after hashes. If they changed
  again, undo becomes a conflict instead of overwriting newer user work.
- Hard-delete rollback is not promised. The recommended computer path uses
  archive/supersede so normal maintenance remains recoverable.

#### 9.6.5 Interrupted and partial imports

The packet ledger and item ledger are durable. Suggested states:

- package: `exporting`, `outstanding`, `import_staged`, `partially_imported`,
  `imported`, `cancelled`, `failed`;
- item: `awaiting`, `validated`, `committed`, `no_change`, `conflict`,
  `failed`;
- proposal: `pending`, `accepted`, `rejected`, `conflict`, `withdrawn`.

The process writes state transitions in the same transaction as their data
effects. On process restart:

- unverified staging files are discarded;
- validated but uncommitted items resume;
- committed proposal IDs are idempotent no-ops on replay;
- failed/conflict items remain retryable without duplicating committed ones;
- frozen transcripts are marked processed only for items reported
  `complete`/`no_change` and successfully committed;
- `failed` items remain pending;
- package cancellation releases its claims but does not delete accumulated
  new transcript rows.

#### 9.6.6 Incremental export/import

The first computer workspace export is full. The phone records the resulting
workspace/snapshot/cursor. A later **Update computer workspace** export:

1. reads changes after each stored cursor;
2. emits current bodies for upserts;
3. emits tombstones for deletes;
4. emits changed/new evidence and chat fingerprints;
5. names the required base snapshot;
6. advances the phone's “last exported” cursor only after an atomic verified
   write.

The computer applies deltas in order. It returns the analyzed snapshot ID.
The phone does not assume the computer applied a delta merely because a file
was created.

If the user loses a delta, changes computers, deletes the mirror, or the
phone has pruned required journal rows, the recovery action is **Create new
full package**. No data merge is attempted from an unknown mirror.

Only one paired desktop workspace and one outstanding computer
archive-review package are needed initially. Multiple workspaces require
per-workspace cursor retention and materially increase tombstone/history
storage; defer them until there is real demand.

An outstanding computer package does **not** block the In-App/API analysis
route. V1 allows at most one active API run and one outstanding computer
package at the same time. Both use the same atomic claim operation and can
select only eligible rows with no existing claim:

- computer-claimed transcript rows remain sealed for that package;
- an API run analyzes only other unclaimed rows;
- if no unclaimed rows remain, the API UI says so rather than treating the
  package as an error;
- messages created during either job enter new unclaimed rows and wait for a
  later review;
- library-maintenance packages claim no transcripts, but phone edits after
  export still use the revision/conflict rules.

This concurrency is safe because the shared filing boundary rechecks exact
identity and targets when proposals are filed and again when accepted. Do
not serialize the two routes merely to simplify screen copy.

#### 9.6.7 Stale package workflow

When the phone changed after export:

- clean unrelated proposals still import;
- exact replays become no-ops;
- affected revise/merge/retire proposals become conflicts;
- the UI shows **Phone now**, **Computer reviewed**, and **Suggested** values;
- recommended action is **Keep phone version** or **Refresh computer
  package**;
- advanced manual resolution may copy/edit the suggestion into a new
  current-base proposal, but automatic three-way semantic merging is not
  attempted.

This is intentionally conservative. Text fields are easy to merge
syntactically and hard to merge semantically; silently combining a negation
or changed scope is worse than asking.

#### 9.6.8 Version compatibility

- Major format mismatch: reject.
- Newer minor version: accept only if every `required_capability` is
  supported; ignore only fields explicitly marked optional.
- Older supported minor: migrate in app-private staging, never rewrite the
  user's source file.
- `schema_version` describes exchange records, not the SQL database version.
- `producer.database_schema` is diagnostic only.
- Schemas are bundled in the package and duplicated in repository fixtures;
  the importer trusts its compiled supported schemas, not arbitrary rules
  supplied by a package.
- Unknown record types or operations are rejected unless declared optional.
- Stable IDs and semantic hashes survive app upgrades.
- A semantic document/normalization change increments its version and causes
  local index rebuild; it does not rewrite memory content.
- Compatibility tests retain golden packages for every supported major/minor
  and adversarial future-version samples.

### 9.7 Security, privacy, and trust

#### 9.7.1 Plaintext is the interoperable default

A general file-capable desktop agent needs readable files. The first useful
implementation should therefore support a plaintext `.sgmemory` ZIP with an
explicit pre-export summary:

- selected chats/evidence included;
- memories/lore/cards included;
- that a cloud-backed desktop agent may send read material to its provider;
- that deleting the exported file does not delete phone data.

Default data minimization:

- `archive_review` includes only claimed conversations and memory/lore/card
  reference records eligible for their scopes, plus global records needed
  for dedup.
- `library_maintenance` is an explicit broader export.
- Supporting evidence not needed for the selected purpose is omitted.
- No keys, credentials, vectors, raw databases, or unrelated settings.

The Android app cannot control retention by Claude, ChatGPT, or another
provider after the user hands it plaintext. That risk is not technically
solvable by the package. The transfer path is also a recipient: attaching
the plaintext package to email, placing it in a cloud drive, or using another
hosted transfer service gives that service access under its own policies
before the desktop agent opens it. The export disclosure and package
`README.md` recommend a cable or trusted local transfer where practical.
The optional encrypted wrapper protects storage/transfer only until the user
decrypts it for an agent.

#### 9.7.2 Optional encrypted wrapper

Phase D may add `.sgmemory.enc` for local storage/transfer:

- separate exchange magic/version;
- fresh random data-encryption key and nonce;
- AES-256-GCM over the complete ZIP;
- bounded password KDF parameters;
- authenticated header;
- app-private staging and no parsing before tag verification;
- hard size/decompression limits.

The implementation may reuse reviewed `PackageCrypto` patterns, but uses a
separate exchange key namespace and never the phone database key, recovery
secret, or `PHOSBKP2` format. A small local decrypt/materialize helper is
required before a desktop agent can read it.

If the exchange password/key is lost, that exported package is
unrecoverable. Phone data is unaffected; make a new full export. There is no
support backdoor and no reason to risk the phone's recovery key to make one.

Encryption protects storage and transfer only. Once decrypted for a cloud
agent, that provider receives plaintext.

#### 9.7.3 Authentication and tamper claims

- Plaintext checksums detect accidental damage, not malicious alteration.
- Package/binding/snapshot IDs prevent accidental cross-import, not an
  attacker who possesses and rewrites the whole plaintext package.
- AES-GCM authenticates an encrypted package under its exchange key.
- A file-only agent has no durable cryptographic identity; putting a signing
  secret inside the same package would not create one.
- The importer's strict schema, current-state checks, proposal-only
  authority, and user approval are the real safety boundary.
- A future paired CLI/MCP/localhost protocol can add device authentication
  and session authorization. Do not falsely label the v1 file route
  “authenticated.”

#### 9.7.4 Import attack surface

Treat agent files as hostile:

- no path extraction outside staging;
- no symlinks/hard links;
- no executable launch;
- cap compressed size, expanded size, file count, nesting, JSON depth,
  string length, evidence length, and proposal count;
- reject duplicate keys where the parser permits ambiguity;
- reject NaN/infinity and out-of-range importance;
- verify referenced package records and targets;
- treat all displayed model strings as text;
- never interpret Markdown/HTML from the result as privileged UI;
- clean staging on cancel/failure and after retention expires.

### 9.8 UI/UX contract

All wording remains owner-approval material, but the interaction model should
be settled before implementation.

#### 9.8.1 Entry point

Use one row in Memory Assistant, working label **Review on a Computer**. It
opens one workflow screen; do not bury the feature in Backup & Restore,
because the exchange package is analysis/reconciliation, not a recovery
backup.

The first screen presents two task cards:

- **Review chats** — analyze selected/new conversations and propose memories.
- **Check memory library** — inspect existing memories for duplicates,
  outdated facts, weak wording, or missing evidence.

Below them, a plain source summary shows:

- chats selected / awaiting review;
- memory reference scopes included;
- lorebooks/cards are read-only references;
- whether the output will be plaintext.

#### 9.8.2 Export flow

1. Choose task.
2. For chat review, choose eligible chats or “new chats,” respecting
   **Archive this chat**. A chat excluded from archiving is not shown as
   exportable unless the user changes that setting.
3. For library maintenance, choose all or selected scopes/targets.
4. Review **What this file contains**.
5. Confirm the privacy disclosure.
6. Choose destination through SAF.
7. Show persistent verified success with filename, package date, and next
   action; no success state relies only on a toast.

If there is an outstanding job, the screen shows:

- created time;
- number of frozen chat items;
- new turns waiting outside the package;
- **Share package**, **Import suggestions**, **Cancel package**, and
  **Replace with a newer package**;
- cancel consequences before confirmation.

The ordinary In-App analyze action stays available for unclaimed
conversations. Its count excludes computer-claimed rows; if that leaves zero,
the UI identifies the conversations reserved by the outstanding package
instead of implying that chat access or Memory Assistant as a whole is
blocked.

#### 9.8.3 Desktop instructions

`README.md` gives short provider-neutral instructions:

1. Put/extract this package in a private folder.
2. Transfer it privately where practical: prefer a cable or trusted local
   transfer; email/cloud-drive services can read a plaintext package.
3. Ask the desktop agent to read `instructions/agent_workflow.md`.
4. Let it validate/search the package and write `proposals.json`.
5. Return only `proposals.json` to the phone.
6. Keep or delete the desktop workspace according to the user's privacy
   preference.

Provider-specific copy/paste prompts may be offered as optional examples,
not as a different data contract.

#### 9.8.4 Import summary

Before opening individual Pending items, show:

- new memory drafts;
- suggested edits;
- suggested merges;
- archive suggestions;
- already saved / no-change items;
- conflicts;
- failed/skipped review items.

For partial success, say exactly which items remain awaiting review. Never
mark the whole package “complete” because one valid array parsed.

#### 9.8.5 Proposal cards

- **New memory:** proposed content, target chips, source label, evidence,
  possible matches; actions Edit / Accept / Reject.
- **Suggested edit:** current versus suggested field diff, evidence and
  rationale; actions Keep current / Edit suggestion / Apply.
- **Suggested merge:** every source memory, proposed survivor, placement and
  evidence; actions Keep separate / Edit merge / Merge.
- **Archive suggestion:** current memory, reason and later evidence;
  actions Keep / Archive (recommended) / inspect existing Delete flow.
- **Conflict:** Base at export / Phone now / Suggested, with Keep phone /
  Refresh package / manually create a new proposal.

Do not show cosine as a percentage. Use **Possible match** and explain the
reason: same normalized text, related wording, later status, different
scope, or authored lore/card overlap.

#### 9.8.6 Source controls remain independent

Global and per-chat controls must offer all four prompt-source modes:

| Saved memories | Lore books | Result |
|---|---|---|
| On | On | Both systems may inject. |
| On | Off | RAG/scene/cards only. |
| Off | On | Classic lorebooks only. |
| Off | Off | Neither source injects. |

**Archive this chat** is separate and controls review/export eligibility
only. Changing injection must not silently store, exclude, requeue, or mark
transcripts processed.

## 10. Phased implementation path

The implementation order is **A → B → C → D**. Each phase is independently
testable. Do not begin app import/reconciliation while the current phone RAG
can still hide eligible memories.

### Phase A — RAG correctness repairs

#### A.1 Exact scope

Phase A fixes the user-visible read path and the capture/approval invariants
that both API and computer analysis depend on:

1. four independent source modes and archive consent;
2. complete-set retrieval when vectors are partial/missing/stale;
3. relevance floor before final top-K;
4. backfill after lore-overlap, cooldown, or other post-ranking filters;
5. edit/status/import index invalidation and repair;
6. lexical ranking across title, current content, optional condensed text,
   tags, aliases/entities where available;
7. updated freshness rather than creation-only freshness;
8. bounded/defaulted retrieval policy parsing;
9. final assembled-context budgeting and truthful diagnostics;
10. capture of typed scene context;
11. sealed transcript claims, a durable run record, and a dedicated
    foreground service so API analysis survives Activity destruction,
    app-switching, and screen-off operation;
12. shared placement-aware exact identity and acceptance-time validation;
13. rename-safe evidence/rejected-draft identity;
14. golden end-to-end retrieval tests.

This phase does not add the computer export button.

#### A.2 Required retrieval behavior

##### Complete-set vector fallback

For the eligible candidate set of a query:

- if every candidate has a current vector for the effective model +
  document-version tag, use semantic/hybrid ranking;
- if any eligible candidate lacks a current vector, use lexical ranking over
  **all** eligible candidates for that turn and enqueue missing-vector repair;
- never use “whatever vectors happen to exist” as the candidate universe;
- an interrupted rebuild or model/document-version change cannot hide
  vectorless memories.

This is the simplest correctness-first policy. A later measured optimization
may blend semantic and lexical scores for partial indexes, but only if tests
prove vectorless recall remains complete.

##### Relevance before top-K

`Librarian.rank` must score, remove candidates below the relevance floor,
then take top-K. The current take-then-filter order can return fewer useful
items while relevant lower-ranked candidates exist.

##### Post-filter backfill

Retrieval produces a ranked stream/page, not a final fixed list that
`Enforcer` can only shrink. `Enforcer` consumes candidates until:

- final top-K eligible items survive;
- relevance is exhausted; or
- a documented scan cap is reached.

Lore overlap, cooldown, status changes, and context
budget cuts record reasons and allow lower-ranked candidates to fill open
slots. A bounded paged/overscan implementation is acceptable; a hard-coded
single overscan multiplier is not treated as proof of correctness.

##### Retrieval documents

Define `memory-doc-v2`:

- lexical document: title + current content + optional `embedding_text` +
  tags + stable target display aliases;
- semantic document: title + current content + tags, using
  `embedding_text` only as an additional condensed hint, never as a
  replacement for newly edited content;
- query terms: Unicode word tokens with deterministic boundary handling;
- no substring hit for `cat` in `catalog`;
- title/tag/entity hits receive bounded boosts; importance breaks close
  ties rather than overwhelming relevance.

Include document version in the effective embedding key, for example
`<model-tag>|memory-doc-v2`, using the existing model-keyed embedding table.
No new vector table is required.

##### Edit invalidation

When title, content, kind, tags, scope/targets, status, or derived condensed
text changes:

- update `updated_at`;
- invalidate the current vector;
- clear an auto-derived stale `embedding_text` when the source text changes
  and it was not explicitly edited as an independent user field;
- clear relevant cooldown;
- add the record to derived missing-vector inventory;
- re-embed off the main thread when a model is available.

Every mutation path—including approval, import, conversion, restore, and
future reconciliation—uses the same invalidation helper. UI-specific calls
to `reindexMemory` remain an optimization, not the correctness boundary.

##### Context budget

Budget the **rendered combined dynamic memory message**, not only the
retrieved-memory list.

1. Render/measure required scene cores and current model rules first.
2. If required content alone exceeds the configured dynamic budget, include
   it atomically, omit optional dynamic entries, and emit an explicit
   `required_context_over_budget` diagnostic. Never substring an atomic
   memory, lore entry, or card core.
3. Admit optional authored lore/card entries and retrieved memories as atomic
   units in owner-approved precedence/order.
4. Backfill smaller/lower-ranked eligible entries when a larger item does
   not fit.
5. Record final rendered character count, selected/cut IDs, and reasons.

Character budgeting remains an approximation to tokens; it must be named
honestly. Model-context overflow handling at the overall request layer is a
separate guard.

#### A.3 Affected components and likely files

Primary:

- `preferences/memory/librarian/Librarian.kt`
- `preferences/memory/MemoryStore.kt`
- `preferences/memory/MemoryData.kt`
- `preferences/memory/enforcer/Enforcer.kt`
- `preferences/memory/enforcer/PromptAssembler.kt`
- `preferences/memory/enforcer/AssemblyLog.kt`
- `preferences/memory/TranscriptRecorder.kt`
- `preferences/memory/archivist/Archivist.kt`
- `preferences/memory/archivist/ArchivistPrompt.kt`
- new `service/MemoryAnalysisForegroundService.kt`, following the existing
  generation keep-alive pattern but using the correct data-sync declaration;
- `AndroidManifest.xml` (the data-sync permission already exists; add the
  dedicated service declaration/type) and notification resources for
  analysis progress;
- `ui/activities/MemoryAssistantActivity.kt`, changed from run owner to
  launcher/observer of durable state;
- `ui/activities/ChatActivity.kt`
- `ui/fragments/dialogs/QuickSettingsBottomSheetDialogFragment.kt`
- `preferences/Preferences.kt`
- `ui/activities/memory/MemoryEditorActivity.kt`
- Pending acceptance paths in `MemoryBrowserActivity.kt`

Likely new pure helpers:

- `MemoryIdentity.kt`
- `RetrievalDocument.kt`
- `RetrievalPolicy.kt`
- `ContextBudgeter.kt` if extending `PromptAssembler` would conflate
  selection and rendering.

Tests:

- existing `LibrarianRankingTest`, `PromptAssemblerTest`, lore/card tests;
- new store/instrumented retrieval pipeline tests;
- four-source-mode final-prompt tests;
- golden corpus fixtures.

#### A.4 Data model and migration

Minimum additive changes:

- `transcripts.claim_run_id` plus a durable active-run/claim record;
- `transcripts.campaign_id` and `project_id`;
- source/archive preference migration so legacy global choice maps to two
  defaults without erasing explicit per-chat values;
- carry existing `memories.updated_at` into `RetrievableMemory` and use
  updated-at-or-created-at freshness;
- effective embedding document version in existing model tag/meta;
- rename-safe rejected-draft source identity until immutable
  `conversation_uid` lands in Phase B.

No new memory kind, graph, story-time inference, relationship ontology, or
vector database.

Backfill:

- existing transcript scene fields remain null when history cannot prove
  them; never infer a world from names;
- existing vectors under the old document key are stale derived data and
  rebuild lazily/background;
- existing excluded transcript rows retain their state until the user
  explicitly chooses Include Earlier Messages;
- malformed retrieval policy falls back to bounded defaults and is logged.

#### A.5 Failure states and recovery

- No embedding model / load failure / partial index: complete lexical
  retrieval, repair notice/log, generation continues.
- Rebuild interrupted: old/partial vectors never become an incomplete hard
  gate; next run resumes repair.
- Memory/lore store degraded independently: available source continues; UI
  and diagnostics identify the missing source.
- Context required zone exceeds budget: optional entries omitted, required
  entries not silently truncated, diagnostic persisted.
- API Archivist process death: startup/next-run reconciles active run and
  claims; externally claimed rows are not auto-released.
- foreground-service start refused: no rows are claimed, a persistent error
  explains that analysis did not start, and retry remains available.
- Activity destruction/background/screen-off: the foreground service
  continues and the reopened activity renders progress from durable state.
- service/process killed after claiming: startup/next-run reconciliation
  keeps committed items, releases only unfinished API claims, and never marks
  unseen text processed.
- Mid-run append: sealed claimed row cannot change; new turn starts a new
  pending row.

#### A.6 Tests and exit gate

Required tests reproduce and then close:

- one vector among many does not hide vectorless eligible memories;
- floor-before-top-K returns lower-ranked relevant items;
- cooldown/lore filtering backfills;
- current title/content/tag edits are searchable immediately by lexical
  fallback and eventually by rebuilt vector;
- stale `embedding_text` cannot override edited content;
- updated freshness changes ranking;
- malformed/negative/extreme policy values are bounded;
- all four source combinations reach the final prompt correctly;
- archive switch affects capture/review only;
- World A never retrieves World B;
- drafts/archived/superseded never enter normal retrieval;
- final assembled context obeys atomicity/precedence and logs overflow;
- duplicate API starts cannot create overlapping runs;
- Activity recreation, app-switching, and screen-off do not cancel a healthy
  service-owned run;
- foreground-service start refusal leaves no claimed rows;
- run/service crash and mid-run append cannot mark unseen text processed.

Exit requires the golden corpus's forbidden-scope leakage to be zero and
must-include recall to meet an explicitly recorded threshold on the
shipping model and lexical fallback. Performance is measured on a real
phone; no guessed millisecond threshold is written into architecture.

#### A.7 Privacy and dependencies

No new export occurs. Transcript capture/consent behavior becomes clearer,
which is itself a privacy repair. The foreground notification shows progress
counts/status, not chat text or proposed-memory content. This phase depends
only on the existing stores; final source/status wording may be approved near
the end and does not block internal repairs.

#### A.8 Deliberately out of scope

- desktop package/import;
- semantic auto-dedup or contradiction resolution;
- new lorebook editing authority;
- sqlite-vec or a server vector database;
- exact score parity with a future desktop model;
- full phone-to-phone synchronization.

### Phase B — Portable memory package

#### B.1 Exact scope

Build a complete **full** `.sgmemory` export for `archive_review` and
`library_maintenance`, with:

- manifest, schemas, checksums, instructions, retrieval/normalization specs;
- stable record envelopes and semantic hashes;
- memory catalog and target catalog;
- read-only lorebook/card references;
- selected/authorized transcripts and normalized evidence turns;
- current tombstones and revision events;
- packet/item ledger and verified atomic SAF write;
- a result template but no applying result to records yet.

This phase makes the package useful to a desktop agent even before the phone
can reconcile its proposals.

#### B.2 Affected components and likely files

Do not overload the existing backup codecs. Likely new package:

- `preferences/memory/exchange/MemoryExchangeManifest.kt`
- `MemoryExchangeWriter.kt`
- `MemoryExchangeSchemas.kt`
- `MemoryExchangeHasher.kt`
- `MemoryExchangeLedger.kt`
- `MemoryExchangeEvidence.kt`
- `MemoryExchangeLimits.kt`

Existing integrations:

- `MemoryStore.kt` / `MemoryData.kt`
- `LoreBookStore.kt`
- `ChatPreferences.kt`
- `TranscriptRecorder.kt`
- `MemoryAssistantActivity.kt`
- new `ComputerMemoryWorkflowActivity.kt` (working name)
- SAF/file helpers and `AtomicFileWriter`
- repository resources/assets for schemas and agent instructions.

Do not replace:

- `MemoryExporter.kt` / `MemorySeedCodec.kt` portable data copy;
- `backup/portable/PortablePackage*` recovery backups;
- human-readable chat backup.

#### B.3 Data model and migration

Add:

- immutable `conversation_uid` and mapping/backfill;
- `memory_evidence`;
- memory-store `exchange_changes` plus exchange-tracking-enabled state;
- lore-store `exchange_changes`, lore tombstones, and matching tracking state;
- `exchange_workspaces`;
- `exchange_packages`;
- `exchange_items`;
- enough target-catalog change tracking to update renamed/archived targets;
- package/source IDs in learned-from-chat provenance.

Revision strategy:

- on the first full export, enable tracking before reading source snapshots,
  initialize each exported record's baseline revision/high-water mark, and
  let the full package itself represent the baseline; do not append a
  synthetic journal row per current record merely to restate that snapshot;
- keep mutation hooks dormant before that first workspace and disable/prune
  them again if creation fails or the only workspace is explicitly removed;
- preserve current stable IDs;
- compute semantic hashes from current state;
- import existing `deleted_ids` into the exchange tombstone view;
- preserve memory change-log event summaries without exporting device-local
  prior-state bodies.

Backfill limitations stated honestly:

- Past lorebook/card/chat deletions made before their tombstone tracking
  existed cannot be reconstructed. A first full snapshot establishes the
  baseline; only later deletions are portable.
- Legacy chats receive a new immutable conversation UID. The current name
  and name-hash ID are recorded as aliases/legacy keys, not portable
  identity.
- Missing scene context remains null.
- Existing evidence stored only in mutable `provenance_context` is migrated
  when it can be tied unambiguously to a transcript; otherwise it remains a
  legacy note, not fabricated proof.

#### B.4 Failure states and recovery

- Memory store unavailable: block packages requiring memory and preserve any
  prior verified export.
- Lore store unavailable: block library-maintenance completeness; an
  archive-review package may proceed only if its manifest says lore
  `unavailable` and the confirmation states dedup coverage is incomplete.
- Chat storage locked/corrupt: never serialize a masked empty chat set.
  Library-only export may proceed; chat review cannot.
- One selected chat unavailable: require deselection or mark the package
  incomplete and keep its item unclaimable; recommended default is block that
  review item.
- Write/verification failure: do not advance baseline/cursors or leave a new
  package as outstanding; if this was the first attempted workspace, undo its
  tracking activation and discard exchange-only rows created for the failed
  attempt.
- App death while writing: temp file is ignored/cleaned; ledger reconciles.
- Package exceeds cap: offer narrower scopes/chats, not silent omission.

#### B.5 Tests and exit gate

- deterministic semantic hash fixtures across field order/Unicode/target set
  order;
- full package schema validation and exact record counts;
- stable IDs survive rename and repeated export;
- tombstones included;
- every evidence ref resolves and quote hash matches;
- archive consent excludes unauthorized chats;
- an outstanding computer package and one API run may coexist but can never
  claim the same transcript row;
- new turns created during either route remain unclaimed for later review;
- unavailable chat is not represented as empty;
- ZIP traversal/symlink/duplicate-path/zip-bomb adversarial tests;
- atomic write/baseline advancement;
- mutation racing the first full export appears either in the verified
  snapshot or in the next journal delta, never neither;
- users with no computer workspace do not accumulate journal/tombstone rows;
- failed first export returns tracking to its dormant state;
- package contains no keys, databases, vectors, or unrelated settings;
- old portable backup/import remains byte/behavior compatible.

Exit: two independent file-capable agents can open the same fixture package,
find specified memories/lore/evidence by following the included workflow,
and produce schema-valid sample results without repository knowledge.

#### B.6 Privacy and dependencies

Depends on Phase A's source semantics, stable eligibility, evidence rules,
and sealed claims. The export confirmation is an owner wording gate.
Plaintext is permitted only after explicit disclosure and a visible content
summary.

#### B.7 Deliberately out of scope

- applying returned proposals;
- delta packages;
- encryption wrapper;
- multiple desktop workspaces;
- phone-to-phone merge;
- desktop executable/MCP server;
- exported embeddings;
- external lore/card mutations.

### Phase C — Desktop agent workflow

#### C.1 Exact scope

Make the Phase B package operational with a provider-neutral desktop
workflow:

- validated full workspace materialization;
- deterministic scope/lexical search instructions;
- optional desktop semantic index;
- archive-review and library-maintenance playbooks;
- strict `proposals.json` for add/revise/merge/retire;
- evidence/provenance requirements;
- complete/no-change/failed item reporting;
- a repository validator/linter and golden examples.

The phone may validate/display a result preview in this phase, but it does
not apply it until Phase D.

#### C.2 Desktop implementation choice

First implementation: **shared retrieval spec + file-capable agent**.

- The agent uses ordinary file search and may create local indexes.
- It does not call Android code.
- It must conform to hard eligibility/normalization fixtures.
- Its semantic model/ranking may differ.

Recommended next helper after real package trials: a small cross-platform
`sg-memory` CLI that:

- validates/decrypts/materializes packages;
- applies deltas;
- builds a SQLite FTS or equivalent local index;
- exposes `search`, `show`, `evidence`, and `lint-result`;
- never edits authoritative records.

The CLI is not required for Phase C exit if mainstream file agents can
perform the workflow reliably. It becomes justified by measured search,
schema, or delta-application failures.

#### C.3 Affected components and likely files

Repository assets:

- package `instructions/agent_workflow.md`
- `instructions/safety_and_scope.md`
- JSON schemas/specs and golden fixtures
- pure validator/result parser tests
- optional `tools/sg-memory/` only if the no-helper trial fails.

Android preview:

- exchange result model/parser;
- schema/capability validator;
- workflow activity's **Check result file** state;
- no `MemoryStore` mutation calls.

#### C.4 Data model and migration

No additional authoritative record migration should be necessary beyond
Phase B. The output contract is file schema. If a validator history is kept,
store only package/result IDs, hashes, validation status, and bounded error
summaries; do not duplicate full result bodies unnecessarily.

#### C.5 Failure states and recovery

- Agent skips a review item: item lacks terminal status; result invalid or
  item remains awaiting, never processed.
- Agent invents ID/target/evidence: proposal rejected.
- Agent wraps JSON in prose: strict external result rejects it; the user asks
  the agent to run the linter/fix the file.
- Agent uses stale/missing workspace: package/snapshot mismatch caught.
- Agent semantic model unavailable: lexical workflow remains complete.
- Agent claims no changes: explicit `no_change` accepted only for a known
  frozen item/input hash.
- Local index corrupt: delete `index/` and rebuild; record files remain.

#### C.6 Tests and exit gate

- golden search questions return required and never forbidden IDs;
- cross-world and archived/superseded leakage tests;
- exact/near/lore/card match examples;
- evidence-neighborhood retrieval;
- valid examples for every operation;
- malformed/unknown operation, bad hash, stale snapshot, fake target, fake
  quote, duplicated proposal ID, excessive file tests;
- two different desktop embedding models may rank differently but both obey
  hard scope fixtures;
- no-change is distinguishable from failed/skipped.

Exit: a user can export, hand the package to a supported file-capable agent
with no custom API key, receive a schema-valid result, and see a trustworthy
phone-side validation summary without any memory changing.

#### C.7 Privacy and dependencies

Depends on Phase B. The desktop folder is explicitly private/readable. Agent
instructions tell cloud-agent users that read files may leave the computer.
No credentials or provider-specific authentication are introduced.

#### C.8 Deliberately out of scope

- automatic phone mutation;
- background cloud upload;
- live phone/desktop connection;
- guaranteed semantic equivalence across models;
- agent edits to lorebooks/cards/personas/model rules;
- multi-agent consensus as a product requirement.

### Phase D — Safe reconciliation and incremental synchronization

#### D.1 Exact scope

Complete the round trip:

- strict result import;
- per-item idempotent staging;
- add drafts and reconciliation proposals;
- per-record stale/conflict classification;
- evidence review;
- owner approval;
- atomic apply and conditional rollback;
- transcript completion/claim release;
- full-to-delta workspace updates and tombstones;
- interrupted/partial recovery;
- optional encrypted wrapper;
- compatibility/migration support.

#### D.2 Affected components and likely files

New/expanded:

- `MemoryExchangeImporter.kt`
- `MemoryExchangeResultParser.kt`
- `MemoryReconciliationService.kt`
- `MemoryProposalStore.kt` or a focused expansion of dormant `proposals`
- `MemoryMutationService.kt` shared by editor, API filing, and approved
  computer operations
- `MemoryExchangeDeltaWriter.kt`
- optional `MemoryExchangeCrypto.kt`
- Pending list/row/detail/diff/conflict UI
- workflow import summary and outstanding-package recovery UI.

Existing:

- `MemoryStore.kt`
- `MemoryData.kt`
- `Archivist.kt` filing extraction
- `MemoryBrowserActivity.kt`
- `MemoryEditorActivity.kt`
- `MemoryAssistantActivity.kt`
- Librarian invalidation/repair hooks
- lore/chat change feeds from Phase B.

#### D.3 Data model and migration

Expand dormant `proposals` or add a narrowly equivalent table with:

- operation;
- package/item/import-session IDs;
- base refs/revision/hash JSON;
- proposed final record JSON;
- evidence/related-record refs;
- conflict state/details;
- result semantic hash;
- status/resolution timestamps;
- apply batch ID.

Add:

- import session/item ledgers if not fully present from Phase B;
- `reconciliation_apply_batches`;
- complete rollback before-images for affected semantic records/joins;
- per-workspace acknowledged cursor/snapshot chain;
- result replay keys;
- proposal source/transport labels.

New-memory additions continue using `memories.status='draft'` after the
shared filing boundary. Do not duplicate them in two pending stores merely
for symmetry.

Migration/backfill:

- existing dormant proposal rows preserve their old meaning and receive a
  legacy operation/capability value;
- no existing memory is converted into a computer proposal;
- one full export is required before deltas;
- old `companion-memory-export-v1` remains backup/import input, not accepted
  as a reconciliation result.

#### D.4 Failure states and recovery

- malformed/incompatible result: no store changes; actionable persistent
  error.
- partial import/app death: resume from validated item ledger; committed IDs
  no-op.
- stale base: conflict, never overwrite.
- missing/deleted target: conflict.
- duplicate current content: no duplicate draft or Possible Match.
- approval race: revalidate at tap/commit.
- apply failure inside merge: transaction rolls back all source/survivor
  changes.
- rollback after later edit: conflict, not overwrite.
- missing delta/base snapshot: require full refresh.
- lost exchange key: re-export full; phone data unaffected.
- pruned change journal before desktop catches up: require full refresh.

#### D.5 Tests and exit gate

- every classification row in §9.6.2;
- replay after full and partial import;
- process death at each stage/commit boundary;
- add/edit/merge/retire approval and rejection;
- merge atomicity and superseded retrieval exclusion;
- full prior-state rollback and rollback conflict;
- vector/cooldown invalidation after approval;
- transcript processed only for committed complete/no-change items;
- stale package with unrelated clean proposals;
- phone rename/edit/delete between export and import;
- full + ordered deltas, missing delta, duplicate delta, tombstone, pruned
  journal, deleted desktop mirror;
- plaintext and encrypted adversarial packages;
- supported old/new minor versions and unsupported major versions;
- no API/computer route can activate memory without explicit user action.
- API and computer routes remain independently usable while their disjoint
  claims/workspaces are outstanding.

Exit: the full export → desktop analysis → import → Pending → approval →
next delta loop survives replay, app death, stale records, and a lost
desktop cache without silent data loss or duplicate active memories.

#### D.6 Privacy and dependencies

Depends on A, B, and C. Import retains only result/provenance/rollback data
needed for review and recovery. Staging files have an expiry/cleanup policy.
Encryption wording and key-loss behavior need owner approval before that
option is exposed.

#### D.7 Deliberately out of scope

- live continuous sync;
- automatic conflict merge;
- multiple paired desktops;
- remote account service;
- phone listening on a LAN port;
- external direct write/delete authority;
- universal desktop agent authentication;
- full revision-body export;
- roleplay/lorebook mutation proposals.

## 11. Future CLI, MCP, or localhost API

The file contract is deliberately transport-independent.

Recommended evolution:

1. **Files only** (Phases B–D): works with Claude, ChatGPT, local agents, and
   ordinary scripts without accounts or networking.
2. **Cross-platform CLI**: validate, decrypt, materialize, delta-apply,
   search, show evidence, and lint proposals. This removes repetitive agent
   token use and makes the same retrieval rules executable.
3. **MCP-style server on the computer**: wrap the CLI/workspace with read-only
   tools such as `search_memories`, `get_memory`, `search_lore`,
   `search_chats`, `get_evidence`, and `submit_proposal_file`. Submission
   writes a result file; it still cannot approve phone changes.
4. **Optional paired companion protocol** only if file transfer is a proven
   pain: short-lived pairing, explicit user-visible session, authenticated
   transport, read scopes, and proposal-only writes.

A phone localhost/LAN API is not recommended for the first implementation:
Android background lifecycle, network exposure, pairing, TLS/cert handling,
firewall/router behavior, and cloud-agent sandbox access add substantial
work while files already solve the target use case. An MCP server belongs on
the computer, where agents can reach it, and should use the same exchange and
retrieval specs rather than invent a second contract.

## 12. What cannot be solved completely

- **Semantic ambiguity:** no embedding or LLM can reliably decide that
  similar claims are duplicate, update, contradiction, negation, or
  alternate-world fact. Evidence/scope narrow the question; the user decides.
- **Unstated chronology:** later file order does not prove later story time.
  Do not infer a complete temporal model from chat order.
- **External-provider privacy:** once plaintext is given to a cloud desktop
  agent, the Android app cannot enforce that provider's retention or
  training policy.
- **File-agent identity:** a standalone result file cannot prove which model
  wrote it. Binding and hashes prevent mistakes; user review limits
  authority. Strong identity needs a paired future protocol.
- **Old deletion recovery:** deletions that happened before a source had
  tombstone/change tracking cannot be reconstructed. The first full snapshot
  is the honest baseline.
- **Lost exchange key:** encrypted exports cannot be recovered without the
  key/password. Re-exporting from the phone is the recovery.
- **Exact cross-model ranking parity:** different embedding models produce
  different candidate order. Hard eligibility/identity can match exactly;
  semantic scores cannot.
- **Infinite context:** some eligible memories must be omitted under a finite
  budget. Deterministic selection, backfill, and diagnostics make that
  tradeoff observable.
- **Arbitrary concurrency without conflict:** a stale desktop proposal and a
  phone edit may both be valid. The system can preserve and show both; it
  cannot always merge their meaning safely.

These are residual risks, not excuses for silent behavior.

## 13. Genuine owner decisions that block implementation

Keep these narrow. The repository provides enough evidence for the rest.

1. **Phase A visible source-control copy:** approve the final labels/helper
   text for Saved memories, Lore books, Archive this chat, and Include Earlier
   Messages near the end of the phase. The owner has deferred wording review;
   the four-mode behavior and independence are technically recommended, not
   an open architecture question.
2. **Saved-memories first-enable requirement:** keep the existing ruling that
   the full engine requires an installed embedding model (the default unless
   deliberately changed), or permit keyword-only enablement on low-resource
   devices. Complete lexical fallback for a model/index failure after enable
   remains required either way. This decision can wait until the source-
   control/first-enable UI is implemented; it does not block the internal
   Phase A repairs.
3. **Phase B plaintext disclosure/default scope:** approve the exact warning
   and whether the first screen defaults to selected chat review (recommended)
   or full library maintenance. Both remain explicit choices; no background
   export to a provider.
4. **Phase D maintenance action wording:** approve user-facing names for
   Edit, Merge, and Archive Suggestions and the three-way stale conflict
   screen. Internal `revise`/`merge`/`retire` operations remain proposal-only,
   with archive/supersede preferred over delete.
5. **Optional encryption timing:** ship plaintext interoperability first
   (recommended), or require the encrypted wrapper/helper before broader
   release. Encryption cannot protect content after a cloud agent reads it.

Not owner blockers:

- use stable IDs and a conversation UID;
- exclude embeddings/keys/databases;
- separate backup/recovery/exchange formats;
- use per-source cursor vectors and semantic hashes;
- make lore/cards read-only in the first workflow;
- strict parse, evidence verification, idempotency, conflict checks;
- keep all imported work Pending;
- require a new full export when the delta chain is unavailable;
- defer live API/MCP until the file loop is reliable.

## 14. Final recommended phase order

1. **Phase A — repair phone RAG correctness first.** This is user value even
   if desktop work stops.
2. **Phase B — freeze and implement the portable package contract.** A full,
   searchable, verifiable package establishes the first desktop baseline.
3. **Phase C — prove the actual computer-agent workflow.** Test at least two
   file agents and lexical/no-model behavior before building mutation logic.
4. **Phase D — add safe reconciliation and deltas.** Import only after the
   result contract is proven; then add encryption and helper tooling by
   measured need.
5. **Fresh independent correctness review.** Re-run the §8 review against
   the implemented phase boundary and adversarial fixtures before broad use.

This is the smallest design that gives the owner both things requested:
a RAG system that behaves correctly on the phone, and a practical
computer-agent route that can search the same knowledge, inspect its
evidence, propose maintenance, and return work without becoming a second
authority.
