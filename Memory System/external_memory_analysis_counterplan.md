# External Memory Analysis — Counter Plan

**Document type:** Planning/architecture only — no code, database, prompt, UI,
or string changes were made with this document.
**Baseline:** verified against this repository at commit `0e9cc1a`
(branch `claude/memory-archive-analysis-ih8z5x`), 2026-07-24.
**Relationship to other documents:** this is a response to
`Speak GPT External Memory Analysis and Deduplication Plan` (the ChatGPT-
written plan the owner uploaded, 2026-07-24 — "the audit plan" below). It
adopts that plan's verified findings, corrects one of them, and replaces its
11-stage delivery program with a smaller one.

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
| Re-enabling "Use memory in this chat" only writes the preference — rows excluded while it was off are never re-queued (the "Don't archive" toggle DOES re-queue, and re-queues everything, including rows excluded for the other reason) | `QuickSettingsBottomSheetDialogFragment` (~line 726 vs ~737), `MemoryStore.setChatTranscriptsExcluded` |
| Capture stamps no scene context — `quick_settings_json` holds model + sampling params only, so the Archivist cannot tell which world/campaign a roleplay conversation belonged to | `ChatActivity.recordTranscriptTurn` (~line 4883) |
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

## 4. The counter plan: three phases plus one answer

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

- **(a) Seal claimed transcript rows.** One new column
  (`claim_run_id` or equivalent) on `transcripts`. When a run (or later, an
  export) selects rows, it stamps them in one transaction;
  `appendTranscriptTurn` never appends into a stamped row (a new turn starts
  a new row); `markTranscriptsProcessed` only advances rows still carrying
  that run's stamp; failure/interruption clears the stamp. This closes the
  mid-run race with a localized change instead of the audit plan's full
  `analysis_items` state machine. Additive migration, version bump.
- **(b) Normalize the duplicate check.** Compare
  case-folded/whitespace-collapsed content (title excluded — models retitle
  the same fact) against all memory statuses including drafts. Exact-match
  semantics stay deterministic; no similarity guessing here.
- **(c) Key rejected drafts to the chat ID, not the chat name**, keeping the
  owner's deliberately-narrow suppression semantics otherwise unchanged.
- **(d) Send the valid target catalog in the prompt.** The runner already
  loads live worlds/campaigns/characters/projects to resolve names; include
  those names in the user message so the model stops guessing. Log (never
  silently pick) when a proposed name matches more than one record.
- **(e) Stamp scene context at capture.** Add the chat's
  world/campaign/RP-character/persona selections into the existing
  `quick_settings_json` blob (no schema change) so analysis knows what room
  the conversation happened in.
- **(f) Fix the eligibility toggles** — "Use memory" re-enable re-queues the
  rows it excluded; the two exclusion causes stop overwriting each other.
  Smallest honest version: a third `review_status` value or a reason column
  distinguishing "memory off" from "user excluded" (NOT the audit plan's
  reason table). Behavior change → owner sign-off on the intended semantics
  first.
- **(g) Truthful failure wording** — the full-failure path reports the real
  draft count instead of a hardcoded zero. (Status wording is owner-approved
  text — any visible change goes through the owner.)

### Phase 2 — The file package, lean v1

The interface the owner actually asked for. Adopt the audit plan's package
design (§11: ZIP with README, prompt, schema, per-conversation JSON,
`targets.json`, `existing_memories.jsonl`, result template) and its import
rules (strict parse, reject-don't-coerce, drafts only, per-conversation
atomic commit, replay = no-op) — those sections are good and this plan
incorporates them by reference rather than restating them.

What v1 keeps from the audit plan's machinery:

- a **packet ledger** — one small table: packet id, run id, created/imported
  timestamps, status, content hash. Enough for "this result belongs to that
  export" and "importing the same file twice is a no-op";
- **claimed rows stay frozen** (Phase 1a's stamp) while a package is
  outstanding; new turns accumulate in new rows and are simply not in the
  package;
- **one outstanding package at a time**;
- import funnels through the SAME draft filing as the API path
  (`insertArchivistDraftMemory` — status='draft' and origin='archivist'
  enforced at the store), never through backup import.

What v1 deliberately does NOT build (deferred until real use demands it):
the generalized `analysis_items` state machine and join tables, claim
leases/expiry, the acceptance-validator rework, per-message revision IDs,
canonical-JSON hashing per RFC 8785 (a plain hash of the file bytes is
enough for same-device replay detection), cross-device import, encrypted
packages, and multiple outstanding packages.

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
review time and attach "possibly the same as: <memory>" to the Pending row,
opening a side-by-side comparison where the user picks **Keep both /
Replace the old one / Delete this draft** ("Replace" = the existing
`supersedes` + status machinery, which is built but has no UI yet).

**What it must never do is decide alone.** A high similarity score cannot
distinguish a duplicate from a negation ("likes X" vs "no longer likes X"),
an update (old status vs new status), or the same sentence in two different
fictional worlds. Embeddings treat all of those as "very similar." So the
score selects what to SHOW the user; the user's tap is the decision — which
is also exactly the owner's standing authority rule for the memory system.
When no embedding model is installed, this degrades to keyword overlap, the
same degradation ladder the Librarian already uses. This phase is
independent of Phases 1–2 and can come later; its screens and wording are
an owner-approval conversation of their own.

---

## 5. Suggested order and why

1. **Phase 1 (a)–(c), (g)** — small diffs, fix live defects, prerequisites
   for the package. Lowest risk, immediate honesty gains.
2. **Phase 1 (d)–(e)** — better targeting and scene context; improves the
   API route now and the package content later.
3. **Owner conversation on Phase 2 UI + wording**, then **Phase 2**.
4. **Phase 1 (f)** whenever the owner settles the toggle semantics.
5. **Phase 3 / embeddings phase** — by demand, after the loop is real.

This ordering delivers the subscription-agent workflow after two small
phases instead of six stages, and every step before it is independently
valuable to the app as it is today.

## 6. Decisions the owner is asked for (in chat, whenever ready)

1. **Go/no-go on Phase 1 (a)–(e), (g)** — behavior-safe correctness fixes.
2. **Phase 1 (f) semantics** — what re-enabling each toggle should do to
   previously captured rows.
3. **Phase 2 go/no-go** and, when it starts, the screen/wording
   conversation (the audit plan's §14 as the starting sketch).
4. **Plain-http LAN endpoints** (Phase 3 side path): enable or leave
   blocked.
5. **Embedding-assisted duplicate review** (the "65%" feature): wanted at
   all, and if so, after Phase 2?
6. Reaffirm: `plot_ledger` card-section suggestions stay (no change needed —
   listed only so the audit plan's contrary instruction is formally
   overruled).
