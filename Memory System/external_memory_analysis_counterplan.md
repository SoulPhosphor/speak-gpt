# External Memory Analysis + RAG Compatibility — Counter Plan (Revision 4, reconciled)

**Document type:** Planning/architecture only — no code, database, prompt, UI,
or string changes were made with this document.
**Baseline:** verified against this repository through commit `510ae69`
(branch `claude/memory-archive-analysis-ih8z5x`), 2026-07-24.
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
overlapping exclusion flags, toggle semantics decided before Phase 2 ships,
stable IDs in the target catalog, and "Possible match" wording). This
document is the canonical roadmap; the audit plan remains the detailed
package/UI/test reference, read with the `plot_ledger` correction in §2.
Revision 4 supersedes the overlapping-flags proposal with the simpler
storage/injection separation in §4(f) and §5.4.
**Revision 3 (2026-07-24, same day):** the separate UI-copy proposal has
been folded into §6 so the owner and future agents have one canonical plan.
Phase 1 receives only the few labels required by visible behavior; the
larger computer-workflow vocabulary is reserved for Phase 2. A fresh-agent
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
extraction, ask the model for ADD-only candidates (never update/delete
authority), deterministic dedup in the app, human review as the final gate.
Those patterns are adopted here too.

What does NOT exist is drop-in code: these are server-side Python frameworks
built around cloud vector databases. Nothing ships as an Android library
against SQLCipher. So we inherit their *ideas* — the architecture here
already matches them more closely than most shipping products (drafts-only,
Pending review, origin tracking) — and write our own small implementation.

---

## 4. The external-workflow plan: three phases plus one answer

The audit plan's Stage 0–11 program front-loads a generalized run
coordinator, an item state machine, claim leases, an exclusion-reason table,
an acceptance-validator rework, and a semantic layer before the export
button exists. Every one of those has value, but in a codebase where CI is
the only compile gate and regressions land on one user's daily driver, that
much rebuilt machinery is itself the biggest risk. This plan cuts to what
the file feature actually requires.

### Phase 1 — Make the existing engine truthful (small, independent fixes)

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
  replacement). Additive migration, version bump.
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
  one record. The external package's result contract is IDs-only (§Phase
  2); the API route can accept names transitionally.
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
  must land before Phase 2 ships because export eligibility depends on it.
- **(g) Truthful failure wording** — the full-failure path reports the real
  draft count instead of a hardcoded zero. (Status wording is owner-approved
  text — any visible change goes through the owner.)

### Phase 2 — The file package, lean v1

The interface the owner actually asked for. Adopt the audit plan's package
design (§11: ZIP with README, prompt, schema, per-conversation JSON,
`targets.json`, `existing_memories.jsonl`, read-only lore/card references,
result template) and its import rules (strict parse, reject-don't-coerce,
drafts only, per-conversation atomic commit, replay = no-op) — those sections
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
- **claimed rows stay frozen** (Phase 1a's stamp) while a package is
  outstanding; new turns accumulate in new rows and are simply not in the
  package;
- **one outstanding package at a time**;
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
  **untrusted data, never instructions**. The external agent proposes
  ADD-only drafts; it cannot update, delete, merge, activate, or retarget an
  existing record. It must use the existing six memory kinds, write
  self-contained atomic memories with named referents rather than ambiguous
  pronouns, and decline unsupported placement instead of guessing.
- keep one raw searchable memory file plus a compact manifest/search guide.
  Do not create parallel keyword, scope, and embedding exports in v1. If a
  benchmark later proves that a very large library is unwieldy, partition the
  JSONL by scope/target without changing the result contract.

**No desktop database is required in v1.** The Android app remains the
source of truth for reviewed transcript IDs, current memories, renames,
status, and rejection history. Each package carries a frozen searchable
snapshot and revision/hash; the desktop agent searches the files and places
only relevant records in its working context. A persistent desktop sidecar
would add synchronization, stale-delete, rename, multi-computer, and privacy
problems without removing the need for app-side validation. Consider one
only after measured package-search failures, not to save hypothetical tokens.

What v1 deliberately does NOT build (deferred until real use demands it):
the generalized run-coordinator framework, claim leases/expiry, the
broad acceptance-validator framework beyond the shared exact/target checks
defined here, per-message revision IDs, canonical-JSON hashing per RFC 8785,
cross-device import, encrypted packages, and multiple outstanding packages.

UI for v1 is intentionally minimal and 100% owner-designed before build:
one entry row on the Memory Assistant, one screen with
create-package / outstanding-package / import-result, a confirmation dialog
with the privacy disclosure, and a persistent result summary. The audit
plan's §14 is a reasonable starting sketch for that conversation — every
string in it is unapproved until the owner says otherwise.

**Privacy, stated plainly (unchanged from the audit plan, worth repeating):**
the package is the user's conversations and memory list in readable
plaintext. Once it leaves the app, what happens to it depends entirely on
the tool that reads it — an agentic desktop app working for a cloud AI
sends what it reads to that provider. The app's job is to say that clearly
at export time and include nothing beyond what analysis needs (no API keys,
no database, no embeddings).

### Phase 3 — Only if wanted: the same package for other transports

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
When no embedding model is installed, this degrades to exact identity and
deterministic text overlap, the same safe degradation principle the
Librarian uses. The **exact** duplicate/status check and acceptance-time
revalidation are Phase 1 correctness work. The broader semantic comparison
screen can follow the file loop, but it should land before large backfills
or a broad release make memory hygiene expensive. Its screens and wording
are an owner-approval conversation of their own.

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

- Recommendation: permit Saved memories without an embedding model. First
  enable provisions the store/companion links independently of model setup,
  then uses deterministic lexical matching over the complete eligible set.
  Show a persistent state such as **Matching: Keywords** with an optional
  **Improve Matching** setup action. Do not use a disappearing toast.
- The stricter alternative is to require a model before first enable. It
  produces better initial relevance but excludes low-resource devices and
  makes the already-supported fallback harder to explain. Even under that
  policy, a later model failure must degrade to keywords without silently
  turning the source off or claiming memory is unavailable.
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

### 5.10 Delivery slices and gates

Keep the implementation small and independently reversible:

1. **R0 — source semantics and migration:** two global defaults, matching
   per-chat controls, Saved-memories-led store provisioning,
   injection/archive separation, legacy migration, and four-mode tests.
2. **R1 — retrieval correctness:** complete-set fallback, relevance-before-
   top-K, post-filter backfill, policy clamps, updated freshness, title/tag
   lexical and embedding documents.
3. **R2 — index lifecycle and performance:** derived missing-vector repair
   (using the narrow approved portion of the existing health design), scoped
   vector loads, cached/reused overlap vectors, persistent diagnostics.
4. **R3 — provenance and hygiene:** shared filing/acceptance validation,
   evidence lineage, truthful source labels, status-aware exact matching,
   Possible Match service.
5. **R4 — lore query/diagnostic cleanup:** joined/cached trigger retrieval,
   exact cross-book content dedup, injected-versus-cut logs.
6. **R5 — external package:** ship Phase 2 only after R0, the release-
   blocking parts of R1, and evidence/shared filing from R3 are complete.

R2 performance optimizations can move later if measurement shows no current
latency problem, but the partial-index correctness fallback cannot. Semantic
Possible Match UI may follow the first package prototype, but acceptance-
time exact validation cannot.

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
user-facing text.

### Shared vocabulary

Use these terms consistently in new UI:

| Concept | User-facing term |
|---|---|
| The feature | **Memory Assistant** |
| RAG / Enforcer source | **Saved memories** |
| Classic trigger source | **Lore books** |
| Transcript review consent | **Archive this chat** |
| Desktop/file route | **Analyze on a Computer** |
| Exported ZIP | **Memory Review Package** or **review package** |
| Returned result | **Memory Suggestions File** or **suggestions file** |
| Model output | **Suggestions** |
| Review destination | **Pending** |
| Near-duplicate signal | **Possible Match** |
| Existing API route | **In App** when a route label is necessary |
| File-based route | **On Computer** when a route label is necessary |

Do not use **Archivist**, **external analysis**, **analysis packet**,
**result payload**, **archive database**, or **import memories** in new
primary UI. Do not display a similarity percentage as if it were a
probability.

### Phase 1 copy — the only wording needed before the file workflow

Most of Phase 1 is invisible correctness work and needs no labels. Only the
following visible situations need owner-approved wording.

#### A. Analysis in progress

Place a persistent notice beneath the existing Analyze Conversations
control while a run is active:

> **Analyzing Conversations**
>
> Keep this screen open while conversations are being analyzed. Chats are
> unavailable until analysis finishes. If analysis is interrupted,
> unfinished conversations will remain available to analyze again.

Do not rely on a toast for this state.

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

**Owner review required for Phase 1/R0:** the two source labels/helpers in
§5.3, the archive re-enable default, and the four short status/dialog groups
above. No owner wording review is needed for internal database, retrieval,
index, and run-integrity changes.

### Phase 2 copy — reserve now, approve when Phase 2 begins

The computer route is a subordinate row on the existing Memory Assistant,
not a replacement for Analyze Conversations.

Entry row:

- **Title:** Analyze on a Computer
- **Subtitle:** Let an AI on your computer review conversations, then bring
  its suggestions back to Pending.

Screen introduction:

> Create a review package, give it to an AI that can work with files, then
> import its suggestions. Suggestions are added to Pending for your review
> and do not become saved memories on their own.

Privacy notice:

> The review package is not encrypted. It contains conversation text and a
> reference copy of your existing memories so the AI can avoid duplicate
> suggestions. No API keys are included.
>
> When you give the package to another AI app, that app's provider may
> receive the contents. Use a service you trust.

#### The three-step screen

1. **Create a Review Package**

   Supporting state: **N conversations ready** or **No conversations
   waiting**.

   Primary action: **Create Review Package**

2. **Review It on Your Computer**

   > Move the ZIP to your computer and open it with an AI that can work
   > with files. Ask the AI to follow `README.md`. It will create a
   > suggestions file for Speak GPT.

3. **Import Suggestions**

   > Bring the suggestions file back to this device. Speak GPT will check
   > it before anything is added to Pending.

   Primary action: **Import Suggestions**

Creation confirmation:

> **Create Review Package?**
>
> This package will contain N conversations and a reference copy of your
> existing memories. It is not encrypted. No API keys are included.

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
> Speak GPT checked the suggestions file. New suggestions will be added to
> Pending for your review.

Use only categories that describe a deterministic app result:

- **New Suggestions**
- **Possible Matches**
- **Already Saved**
- **Couldn't Be Used**
- **Conversations to Retry**
- **No Longer Eligible**

Primary action: **Add N to Pending**. If N is zero: **Finish Import**.

Success:

> **Suggestions Added to Pending**
>
> N suggestions were added to Pending. Review them before they become
> active memories.

Actions: **View Pending Memories**, **View Details**

Valid import with nothing new:

> **No New Suggestions**
>
> Speak GPT checked the file, but every suggestion was already saved,
> already pending, no longer eligible, or could not be used.

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
| **Suggestions File Is Out of Date** | Some conversations or targets changed after this package was created. Nothing was imported for the affected conversations. |
| **Suggestions Do Not Match** | One or more results do not match the conversations in this package. Nothing was imported for the affected conversations. |
| **Already Imported** | These suggestions were already checked. No duplicate suggestions were added. |

Recent-run route labels, when needed, are **In App** and **On Computer**.
An outstanding computer run uses **Import Suggestions**, not **Run Again**.

**Owner review required for Phase 2:** approve this screen as one coherent
flow when Phase 2 starts. There is no reason to approve every Phase 2 label
during Phase 1.

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

1. **Approve the small user contract:** the two source labels/helpers,
   archive-history choice, and legacy preference migration in §5.3–5.4.
2. **Land run/data integrity:** Phase 1 (a)–(c), (g), plus source/archive
   separation in (f). These close live silent-loss and consent ambiguity.
3. **Land read-path correctness:** R1's complete-set fallback,
   relevance-before-top-K, backfill, policy bounds, retrieval documents, and
   updated freshness. These are required before calling the existing RAG
   robust.
4. **Land targeting/evidence:** Phase 1 (d)–(e), shared filing and
   acceptance validation, source lineage, and provenance labels. This
   improves the API route immediately and freezes a sound file contract.
5. **Measure and harden:** the narrow index-repair lifecycle, scoped vector
   loads, lore query/log cleanup, four-mode integration tests, real-device
   benchmark, and golden retrieval corpus. Performance work follows
   evidence; partial-index correctness does not wait for a benchmark.
6. **Owner approval of the Phase 2 flow in §6**, then build the lean file
   package/import loop.
7. **Semantic Possible Match comparison** before broad backfill/release;
   additional transports only by demand.

This sequence fixes what users experience today and still reaches the
subscription-agent workflow without adopting the audit plan's generalized
coordinator or rebuilding the memory system.

## 8. Decisions and review gates

### What the owner genuinely needs to review

1. **Source-control presentation:** approve or revise **Saved memories** and
   **Lore books**, their helper text, and the two-switch global/per-chat
   placement in §5.3, including the archive helper and aggregate active-scene
   line. The four combinations themselves are the recommended behavior and
   are already supported per chat.
2. **No-model behavior:** recommendation is to allow Saved memories with
   transparent keyword matching and offer model setup as an improvement.
   The alternative is to require a model only at first enable while keeping
   keyword fallback for later failure.
3. **Archive history choice:** when **Archive this chat** is re-enabled,
   confirm **Include Earlier Messages** as the recommended default versus
   New Messages Only. Source switches never alter this queue.
4. **Phase 2 go/no-go:** when implementation reaches it, approve or revise
   the complete screen/privacy/wording flow in §6.
5. **Plain-http LAN endpoints** (optional Phase 3 side path): enable with
   the existing warning or leave blocked.
6. **Possible Match timing:** recommendation is exact/status-aware checks
   now and semantic comparisons before broad backfill/release.

### Technical gates that do not require routine owner input

- Phase 1 run integrity and shared filing tests pass.
- The partial-index, relevance-floor, and backfill regressions in §5.5 have
  reproducing tests and verified fixes.
- All four source modes pass final-prompt integration tests and independent
  failure tests.
- Scope leakage is zero in the golden corpus.
- API and computer results share evidence, validation, Pending, and
  acceptance behavior.
- On-device benchmark results are recorded and no release blocker is hidden
  behind "works in unit tests."
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
8. Check that the Phase 1 wording covers every newly visible state and that
   Phase 2 wording matches the planned state machine without exposing
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
