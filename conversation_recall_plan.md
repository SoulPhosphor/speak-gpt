# Memory development plan

**The single authoritative plan for all memory work:** saved memories and
their search, the Memory Browser, the Memory Assistant, context assistance,
and conversation recall. Consolidated on 2026-11-16 at the owner's request.
The file keeps its old name, `conversation_recall_plan.md`, because code
comments and tests cite its section numbers.

- **Part M** (below) is the build order, the status, the provenance design,
  where each setting goes on screen (M.11), and the record of what was
  superseded. It is the only place that decides
  what is built next.
- **Sections G.0 to G.19** are the design of context assistance and
  conversation recall, the work orders for the numbered Steps (G.16), and
  the open decisions (G.17). "Part A" or "Part E" in those sections means
  the memory plan below. Numbered Steps always mean the work orders in G.16.
- **`Memory System/dynamic_memory_plan.md`** is reference material: the
  measured diagnosis and design of saved-memory search (Parts A and B), the
  designs for later saved-memory features (Part C Phases 2–6, Part D), the
  D-decisions (Part E) and research (Part F). Its status and ordering
  sections are historical. Where it disagrees with this plan, this plan is
  current (M.7).
- **`rag alteration notes November 14.md`** is a historical record of the
  owner's November 14–16 direction. Everything in it is carried into this
  plan (M.8).
- **`memory baseline.md`** remains the evidence record for every
  saved-memory measurement. Results are never copied into this plan.

**Current handoff · November 23, 2026.** Stages 1–6 merged in [#209](https://github.com/SoulPhosphor/speak-gpt/pull/209), [#210](https://github.com/SoulPhosphor/speak-gpt/pull/210), [#211](https://github.com/SoulPhosphor/speak-gpt/pull/211), [#212](https://github.com/SoulPhosphor/speak-gpt/pull/212), [#213](https://github.com/SoulPhosphor/speak-gpt/pull/213), and [#214](https://github.com/SoulPhosphor/speak-gpt/pull/214). Stage 6 delivered the recall test chats; **Stage 6A — reliable reading and resume** merged in [#232](https://github.com/SoulPhosphor/speak-gpt/pull/232), **Stage 6B.1 — working-draft corrections and bounded context** in [#240](https://github.com/SoulPhosphor/speak-gpt/pull/240), and **Stage 6B.2 — scoped matching engine and comparison cache** and **Stage 6B.3 — reconciliation and safe filing** in their own PRs (Build record). Stages 6C and 6D merged (Build record). G35 is approved and Stage 7 is split into **Stage 7A — the passage cutter** and **Stage 7B — the passage stores and per-message updating**, one session and one PR each; read **Conversation index: Stages 7A and 7B** in M.2 first. M.2 owns sequencing and the [November 18 scanner proposal](<Memory Scanner update proposal nov 18.md>) owns the scanner design.

# Part M: Roadmap, status, and consolidation

## M.0 How to read Part M

- **M.2 is the only build order.** G.16's former execution table, the memory
  plan's "Next steps", Part C phase list and D.13 order, and section 4 of the
  RAG notes no longer set the order.
- **Labels are the same as G.0.** **Owner** is a decision the owner made,
  with its date. **Proposed** is an agent recommendation the owner has not
  approved. **Open** is a question waiting for the owner. Nothing becomes a
  decision because an earlier document called it approved.
- **A stage is a coherent piece of work** that is normally delivered as one
  pull request (`CLAUDE.md`: one session's changes go in one PR). A stage
  may contain several numbered Steps. The Steps keep their numbers because
  code cites them; their work orders in G.16 still apply in full.
- **Order and independence.** Stages are built in M.2 order. When the next
  stage is waiting only for an owner decision, the owner may assign a later
  stage whose "Needs" column is already satisfied. No builder skips ahead on
  their own.
- **Verification is kept, research is not repeated.** Every stage keeps its
  tests and its on-device checks. The investigations closed in M.1 are not
  run again unless the owner reopens them.

## M.1 Already built and closed

| Work | Where | Record |
|---|---|---|
| Saved-memory search repair: meaning plus keyword search, common words ignored, the message searched alone, the relevance gate, importance as a capped nudge, the +3 rule, no cooldown; the Memory Search Test screen (memory plan Phase 1, steps 1.3–1.11 and 1.4) | #180 | Memory plan, section 1 of the historical status |
| Inside Joke type and the per-memory vibe (D56, D58); memory database version 34 | #182 | Memory plan, section 3 of the historical status |
| Step 0: saved-memory benchmark, 86 samples | — | `memory baseline.md` |
| Step 1: token counting, one embedding queue, stored memories read up to 2,048 tokens | #199 | `memory baseline.md` |
| Step 2: long and dictated messages searched in sections; repairs restore the original quality gate and calibrate Stand-Out Strength 2.53 and Minimum Spread 0.034 | #200, #201, #202 | `memory baseline.md` |
| Step 3: previous-message search helper, not connected to sending; repair applies the owner's +3 rule to section matches | #203, #205 | `memory baseline.md` |
| Carry-over evaluation on a development and a held-out set with Android similarities | #206 | `memory baseline.md`; decisions G37, G40 |
| Stages 1–3: conversation sources, code-based context assistance, and the API context helper | #209–211 | M.3, M.4 and Build record; the API helper's model-and-cost-dependent evaluation remains a separate item |
| Stages 4–5: token-capacity meter and saving check, Archived view and Memory Handling Preferences | #212–213 | G34 and M.10 |
| Stage 6: conversation-recall test chats and fixture validation | #214 | Step 6 and Build record |

**Closed investigations. Do not repeat them:**
- Android ONNX Runtime 1.29 and forced FP32 activations: no accuracy
  benefit, rejected. The app stays on Android ONNX Runtime 1.20.0.
- Whole-message gate calibration: done (2.53 and 0.034). Further retuning
  is not part of any stage below.
- Searching with the previous messages (G.9a), "similar to new message",
  and the plain lower +3 bar: measured and not used (G37, G40).
- The 23 MB on-phone re-ranker on saved memories (memory plan A.10): did
  not work. A re-ranker for conversation passages stays a separate later
  question (G.6 retrieval stage 5a).

## M.2 The build order

| Order | Stage | Contains | Needs | Approve first | The user sees |
|---:|---|---|---|---|---|
| 1 | **Memory provenance** | M.3: source storage and database migration; the conversation recorded by the Memory Assistant; backups, imports, restores and merges; conversation participants, dates and link in the memory editor | Nothing unbuilt | Nothing (G43 decided) | The conversation a memory came from: its participants, its dates, and a link to it |
| 2 | **Context assistance: Off and Code-based** | Step 4a: the context-assistance interface, no helper and Code-based, the +3 top-match rule, Memory Context Helper Settings with its toggle and dropdown | #205 and #206 merged (done) | The Step 4a questions in M.10 | Follow-ups keep their memories; the context-assistance setting |
| 3 | **Context assistance: API AI** | Step 4b, with the Memory Context Helper Tool API screen in AI Settings (M.4) | Stage 2 | The Step 4b questions in M.10 | The API AI choice and the Memory Context Helper Tool API screen |
| 4 | **Memory length limit** | Step 5: informational word count, live full-input token meter, exact token saving check, lossless Memory Assistant splitting | Step 1 (done) | Nothing (corrected G34 decided) | Word count, token usage and filling bar |
| 5 | **Data control** | Memory plan Phase 2: the view dropdown, Archived, Memory Handling Preferences, Delete Moves to Archived, with the setting in backups | Stage 1 (both are expected to change the memory database; building them one after the other avoids competing migrations) | Wording and layout; D44 default | Archived view and Memory Handling Preferences |
| 6 | **Conversation test chats** | Step 6 | Nothing unbuilt | Nothing | Nothing |
| 6A | **Reliable reading and resume** | M.2 scanner work below | Existing scanner and encrypted staging | Existing approval rules for any changed wording | Reliable short and long scans through the existing controls |
| 6B.1 | **Working-draft corrections and bounded context** | Scan-local draft edits, checkpointed identities, bounded repair and earlier-chat allowance (M.2 below) | 6A | Answered (**Stage 6B.1 owner decisions**) | Later information can correct temporary drafts |
| 6B.2 | **Scoped memory matching and comparison cache** | Eligible Active/Pending/Archived and working-draft lookup, embedding/keyword search, bounded vectors and validated match data (M.2 below) | 6B.1 | Answered (**Stage 6B.2 owner decisions**) | No new UI; safe matching foundation |
| 6B.3 | **Reconciliation and safe filing** | Retelling detection, linked updates, evidence, cap decisions, publication rechecks and unresolved alternatives; showing Pending, Archived and draft matches to the scanner AI (M.2 below) | 6B.2 | Answered (**Stage 6B.3 owner decisions**) | Accurate nonduplicate Pending proposals |
| 6C | **Browser match cards and comparison** | M.2 scanner work below | Stage 6B.3 match data | Card, comparison layout and labels | Open exact matched pair and return to same list position |
| 6D | **Browser review actions and end-to-end evaluation** | M.2 scanner work below | Stage 6C pair view and Stage 6B.3 conflict data | New review actions and wording | Resolve Pending alternatives safely and complete scan-to-review workflow |
| 7A | **Conversation index: passage cutter** | Step 7 | Stage 6D | Nothing (G35 approved) | Nothing |
| 7B | **Conversation index: passage stores and per-message updating** | Step 8 | Stage 7A and Step 1 | Nothing (G35 approved) | Nothing |
| 8 | **Conversation index: embedding and upkeep** | Steps 9 and 10 | Stage 7B | Nothing after G35 | Nothing |
| 9 | **Conversation index: settings, progress, after close** | Steps 11, 12 and 13 | Stage 8 | G20, G28 and G31 decided for Step 11 (2026-11-24); Step 13's mechanism, notices and stopped-for-the-day wording | Conversation Index settings, progress icon and pop-up, notification |
| 10 | **Recall retrieval and inspector** | Steps 14 and 15 | Stages 2 and 8 | G36; inspector labels | Conversation Recall Test screen |
| 11 | **Conversation Recall in real requests** | Step 16 | Stage 10 | AI-facing text, "what was sent" wording, recall-room setting, debug lines, G24 | Conversation Recall works |
| 12 | **The Always tab and bring-up setting** | Memory plan Phase 3 | Stage 5 | Wording, screens, AI-facing text; D17 | Always tab |
| 13 | **Today's date and dates on memories** | Memory plan Phase 4 | Stage 12 | D55 first; D1–D5, D12–D14 wording and AI-facing text | Dates tab, important dates |
| 14 | **Fading** | Memory plan Phase 5 | Stage 13 | D8, D19, D45, D49, D50 details | Fading markers |
| 15 | **Memory Assistant date proposals** | Memory plan Phase 6 | Stage 13 | D33, AI-facing text | Proposed dates in Pending |
| Later | Not ordered | Optional re-ranker; live AI worker (G.11, G8); recall across chats (G9); Local AI context assistance; other embedding models (G41); background reviewer (D51); recent-chat continuity (D38); companion journal (D40); Model Rules adjustments (D.13); linking a memory to a Glamour (M.6); search tuning revisited; vector size, automatic tags and variety (memory plan steps 1.14–1.16, only if testing shows a need) | The re-ranker, live AI worker and cross-chat recall need stage 11 | Separate owner decisions | — |
| Any time | Not a stage of its own | **Automatic companion linking** (M.6, Owner 2026-11-16): every companion the user creates is linked to the memory system automatically | Nothing unbuilt | Nothing; the behavior is decided | Nothing new; companions appear in the memory system as soon as they are saved |
| Any time | Not a stage of its own | The memory-use instruction (D41) can be added to any stage that touches the memory instructions once its exact text is approved | — | D41 text | Changed AI behavior |

### Conversation index: Stages 7A and 7B

**Owner (2026-11-23):** G35 is approved, and Stage 7 is split into two coding stages, one session and one PR each, built in order. Do not combine them. Step 7 and Step 8 below keep their full work orders; this section fixes where each stage starts and ends.

**Stage 7A — the passage cutter (Step 7).**
- **Starts:** from current `main` after Stage 6D, with G35 approved.
- **Builds:** new plain-Kotlin files only, in a new `conversation/index` package, and their unit tests. A cutter takes one message's text, a target size and a supplied size-measuring function, and returns each passage's start and end positions in order, together with the cutter version. It splits by paragraph, then line, then between words, then between two characters (never inside a surrogate pair, and never at periods), and joins short neighbouring pieces up to the target size (G.5b cutter rules 1–3 and Versions).
- **Ends:** every test under G.5b's "With the cutter, in Step 7" passes. Nothing in the app calls the cutter yet, so nothing changes for the user.
- **Not in 7A:** any database or storage; search text, overlap or speaker labels; connecting the real embedding token counter; eligibility, scope or exchange stamping; any hookup to chats; any screen, wording or log line.

**Stage 7B — the passage stores and per-message updating (Step 8).**
- **Starts:** after the Stage 7A PR merges, from current `main`. Uses the 7A cutter through its public function without changing its rules; a change to the cutting rules raises the cutter version (G.5b, Versions).
- **Builds:** everything in Step 8: the separate encrypted index database with its four stores (G.5); keyword storage with no readable copy of message text (G.5, Storage design); one-transaction-per-message updates against a recorded chat version, comparing by permanent ID and original-text fingerprint (G.5a rule 4); eligibility (G.5a rules 1 and 7); search text with same-message overlap and the speaker label (G.5b cutter rules 5 and 7), sized with the Step 1 token counter passed into the cutter; scope stamping from values passed in (G.7); and vector rows created as "waiting".
- **Confirms in code and records here:** which stored field holds the reply version the AI is sent.
- **Before its PR:** the **Changes during waits** table required by `CLAUDE.md`, covering background updates that finish after the chat changed.
- **Ends:** Step 8's "Done when" passes. No vectors are made and nothing in the app triggers indexing yet; those are Steps 9 and 10 (Stage 8).

### Conversation index: Stage 8, Steps 9 and 10

**Owner (2026-11-23):** Stage 8 is built as two coding sessions, one PR each, in order: Step 9 first, then Step 10 after the Step 9 PR merges. The Step and Stage numbers do not change; Step 9 and Step 10 keep their full work orders.

- **Step 9 (background embedding of passages):** the worker takes a chat's current messages through a supplied interface (`IndexMessageSource`). It keeps a model's vectors until the replacement model's index is complete (G.5a rule 3). Nothing in the app starts it.
- **Step 10 (keeping the index in step with the chats):** builds the saved-chat reader that supplies those messages, and every G.12 hookup.

**Owner (2026-11-24), for Step 10:**
- The hookups stay inert until Step 11's Index Conversations setting turns them on: nothing indexes in normal use before that setting exists. The switch is internal (`ConversationIndexActivation`), is not saved and has no wording; only tests turn it on.
- The search-text overlap (G.5b cutter rule 5) is **20 words**, the smaller value, to be tested first in Steps 14–15. It is fixed, not a user setting. The target size stays at about 256 tokens.
- G.12's table is Step 10's required behavior. "The user deletes the index": Step 10 builds and tests only the background removal; the control and its wording are Step 11. "Chat settings changed": later messages carry the scope recorded when they were sent; messages already indexed keep the scope they had.
- A message hidden from the AI loses its passages and any vector nothing else uses, as Step 8 built it; showing it again reads it again.

### Scanner work between Stages 6 and 7

The owner prioritized the scanner on November 18. **6B.1, 6B.2 and 6B.3 are separate coding stages, one session and one PR each.** This split follows the size of Stage 6A (#232). Keep every original 6B requirement and its approval gate; do not combine the substages. The numbered Steps in G.16 keep their original identities. Scanner work reads the original chat storage (**The chat is the source**, below) plus local memory records and existing embedding facilities. The conversation passage index built in Stages 7A and 7B serves later live recall. A broader Memory Browser redesign can proceed separately.

1. **Stage 6A — the chat as the source, reliable reading and resume.** Use the existing single-chat and batch entry points. Replace the duplicated transcript store with direct reading of the original chat storage, and migrate existing data, as decided in **The chat is the source** and **Removing the duplicate transcript store** below. Assign missing message UUIDs with `MessageIdentity.ensure()` in the original chat storage before scanning. Verify current message content by UUID and fingerprint before sections, retries, resume and publication, and invalidate after edits, regeneration, hiding or deletion. Implement the short/long request paths, stable message and fragment references, bounded excerpts reread from the chat, cache-friendly request order, and lossless retry of an oversized message. Use paragraph, line and word boundaries before Unicode-safe character splitting, as in G.5b. Persist the validated working state and exact next unread position (message UUID and offset) together. Preserve cancellation, progress reporting, and automatic-backup coordination from #216–217. Add the Pause and Resume controls below. Demonstrate that killing the process after a completed section resumes from its checkpoint, that split fragments cover the entire original message, and that the migration and regression tests below pass.
2. **Stage 6B.1 — working-draft corrections and bounded context.** Add stable scan-local draft IDs and validated create, edit, combine and withdraw operations; narrow repair of invalid/conflicting edits; and chronological corrections when later source clarifies earlier drafts. Persist the entire working collection, edits, passage references and exact next unread position atomically through the existing 6A checkpoint so pause, interruption and resume lose nothing. New unread source and drafts take precedence over earlier chat text; preceding dialogue and optional excerpts share a separately bounded token allowance. Measure token use; the setting and AI-facing instructions follow the **Stage 6B.1 owner decisions**. Keep original chat storage authoritative and existing scanning functional. **Not in 6B.1:** embedding comparison cache, filed-memory suppression, new Browser review actions. **Done when:** tests demonstrate a late reply correcting an early provisional draft, lossless edit/withdraw/combine behavior, invalid operations not destroying valid work, full unread-source coverage and faithful checkpoint recovery.
3. **Stage 6B.2 — scoped matching engine and comparison cache.** Build pre- and post-extraction lookup against eligible Active, Pending, Archived and current working drafts. Apply scope/target/status eligibility first; include stored `activeMemoryAttribution` from chat replies as clues only when still eligible. Cover long messages with bounded overlapping windows, preserve the ends of turns, and support exact/keyword fallback when embeddings or vectors are absent (no fabricated similarity score). Reuse vectors by fingerprint/model, route new work through the existing lower-priority embedding queue, bound query count, missing-vector generation, comparisons and cache size. **Approve the separate encrypted scanner comparison-cache design and migration before any schema change.** Keep the cache separate from active chat retrieval and return validated IDs, status, matching method, score when available, and unresolved links. **Not in 6B.2:** suppression/filing decisions or new Browser actions. **Done when:** tests demonstrate correct scope and status boundaries, missing/stale vector behavior, deleted/changed memories, keyword-only results, bounded queue work, and long-turn search coverage.
4. **Stage 6B.3 — reconciliation and safe filing.** Combine draft corrections and matching evidence to distinguish pure retellings from genuine developments, reconcile chronology and unresolved conflicts, and rank distinct proposals across the full scan under the user's cap (do not truncate by arrival order). Add the compact AI-returned `already covered` source-reference and existing-memory-ID entries, with owner-approved AI-facing format; validate and persist them with the checkpoint. Show the scanner AI the Pending, Archived and working-draft matches that 6B.2 collects, with their status, using owner-approved AI-facing wording (moved here from 6B.2 by the **Stage 6B.2 owner decisions**). At filing, recheck original chat source and all relevant existing memories, including edits, deletions, status/scope changes, approvals and discards made during or after the scan; perform model comparisons outside the transaction and atomically commit only validated results and bookmark. Never silently activate, overwrite or revive a filed memory. Expose linked alternatives and real match metadata to existing Possible Match review where representable, including a linked pair occupying one cap slot. **New human review actions wait for 6D.** **Done when:** tests show retellings create no extra card, real updates remain reviewable, in-flight changes cannot cause stale filing or false completion, cap and linked pair behavior is safe, and a paused scan revalidates changing dependencies before publication.
5. **Stage 6C — Browser match cards and comparison.** Add the related-memory preview, status, match method and available similarity score to Pending cards. Open the exact pair by ID, show the full wording of both records, expose additional matches, and restore the user’s list position on return. Reuse the comparison screen and existing review actions where applicable. Cover changed or missing targets, and present keyword matches accurately when embeddings are unavailable. Keep the Stage 4 meter in the existing creating, editing and reading views.
6. **Stage 6D — Browser review actions and end-to-end evaluation.** Add the decisions for two pending drafts and unresolved alternatives, including a linked pair that occupies one review choice at a cap of one. Keep both pending, edit or discard the redundant proposal, or apply an existing approval action according to the user's choice. Revalidate before applying a decision so reviewing a pair never activates or overwrites a memory accidentally. Exercise the complete scan-to-review path with and without embeddings and measure quality, token usage, conditional comparisons, total scan duration, battery consumption, embedding queue contention, cold/warm vector reuse and phone review effort as specified in the scanner proposal. Resume at **Stage 7A** (Step 7), then **Stage 7B** (Step 8).

**The chat is the source (Owner decision, 2026-11-21; final).**
- **Rule.** The original chat is the single source of truth. The scanner reads directly from the original chat storage: the same current messages the user sees when opening that chat. No second persistent copy of conversation text exists for scanning, pausing, resuming, indexing or recovery, and an outdated saved copy is never used in place of the chat. Text may be held in working memory while an AI request is built; it is never saved. This decision is made. Builders implement around it and do not reopen it.
- **What is read.** Message UUIDs (`MessageIdentity.kt`) are permanent; an edit changes a message's content, not its identity. The scanner reads the latest content of each UUID. A deleted message is absent from the chat, is not scanned, and is never recovered from an old copy. A hidden message is excluded by the existing visibility rules. For an assistant reply, only the selected, completed version the AI is sent is eligible. Messages are read in the chat's own order. Messages without a UUID get one from `MessageIdentity.ensure()`, saved in the original chat storage, before scanning. No text matching is used to identify messages.
- **What the scanner may save.** Chat IDs and message UUID references; its position, including an offset inside a long message; a bookmark (high-water mark) per chat; scan status, settings and progress; validated working drafts and their IDs; fingerprints and other metadata that show when source content changed. It never saves conversation text or supporting excerpts. Memories, Pending suggestions, working drafts and Lorebook suggestions are memory records, not transcripts. The Memory Browser stores memories, suggestions and source-conversation references (M.3), never chat histories. On resume, the scanner rebuilds its context by rereading the messages by UUID.
- **Checking before use.** UUIDs identify messages; fingerprints show whether their content changed. The scanner rechecks before each section, retry, resume and publication, including changes made while an AI request was in flight. When an already-read message changes while a scan runs or is paused, the affected temporary results are discarded and that chat's unfinished scan restarts from its bookmark.
- **When chat storage cannot be read.** If the original chat storage is temporarily unavailable or damaged, the scan reports that the conversation could not be fully scanned, keeps valid progress, and retries when the chat can be read again. It never invents missing text, never reports false completion, and never stops other conversations from being scanned. The existing incomplete-scan outcome is used; any new wording is approved first.
- **Scope of each message.** Today the only record of the companion, world, campaign, character, persona and project in effect for each turn is the copied transcript row (`TranscriptRecorder.recordTurn` → `MemoryStore.appendTranscriptTurn`). The chat's own messages store the companion's display name, model, provider and time, but not these IDs. Stage 6A records these IDs as metadata on each message in the original chat storage when its turn finishes, whether or not the memory database is set up. The scanner, and later the conversation index (G.7), read scope from there. For older messages, scope comes from the migration where a transcript turn names that message's UUID; otherwise it is "unknown". Scope is never guessed from the chat's present settings.
- **Archive and companion settings.** The chat's Archive (pause) setting and a companion whose memory participation is "none" keep their current effect. They are applied when the scanner reads the chat instead of when a turn is copied.

**Removing the duplicate transcript store (Stage 6A).** Today `TranscriptRecorder.recordTurn` copies every finished turn into the `transcripts` table of the memory database, and `TranscriptRecorder.backfillExistingChats` once copied each existing chat as an "imported" transcript. Bookmarks (`analysis_chat_bookmarks`), frozen scan ranges (`analysis_chat_ranges`), claims (`transcripts.claim_run_id`) and run history (`archivist_runs.transcript_ids_json`) point at transcript rows. Memory backups export and restore a `transcripts` array (`MemorySeedCodec.kt`; `MemoryCategoryPlanner.kt` and `CompanionMemoryRestorePlanner.kt` in `backup/portable/`). Stage 6A removes all of this, in this order:
1. **Stop making copies.** Remove the capture and backfill writes. The per-message scope recording above replaces the capture hook. `TranscriptRecorder` is also one of the places that creates a memory-system companion the first time it is chatted with (M.6); that creation is kept.
2. **Migrate, keeping legacy data until each result is recorded.**
   - Assign missing UUIDs in the original chats with `MessageIdentity.ensure()`.
   - For each transcript turn that names a message UUID still present in its chat, record that turn's scope on the message, and convert completed, skipped and excluded progress to a per-chat UUID bookmark.
   - Where no UUID mapping exists, progress may be mapped by stored message times only when every message involved has a time and the result is unambiguous. Otherwise the chat is listed as needing a rescan. Its completed state is not reset or silently discarded, and nothing is rescanned automatically. The report's wording is asked when Stage 6A starts (M.10).
   - Text of a message that is no longer in its chat is never copied back into the chat or anywhere else.
   - Archive settings, run history, claims and frozen ranges convert to chat IDs and message UUID references.
   - Scans running or staged at upgrade time are treated as interrupted: they resume from the original chat where their checkpoint maps to UUIDs, and otherwise restart that chat from its bookmark. Staged drafts are kept where they map.
   - Saved memories, Pending suggestions, Lorebooks and Lorebook suggestions, and source-conversation references (M.3) are not changed.
   - Only after these results are recorded is the transcript text deleted and the table removed. No compatibility copy of the text is kept.
3. **Backups.** Exports stop including transcripts. Restoring an older backup that contains transcripts uses them only for the mapping above against the restored chats, then discards the text. A restore never recreates a transcript store.
4. **Tests.** Migration tests cover: a turn with a UUID; a turn without one; a deleted message; an edited message; a hidden message; a different selected reply; an Archive-paused chat; a companion with participation "none"; scope that changed partway through a chat; a scan interrupted at upgrade; and restoring an older backup. Regression tests show that no code path writes conversation text into the memory database and that a deleted message is never resurrected.

**Stage 6A requirements (Owner, 2026-11-21).**
- **Four ways a scan ends.** Today `reconcileInterruptedAnalysisRuns()` and `releaseAnalysisClaims()` in `MemoryStore.kt` delete the temporary candidates and run state whenever a run stops, so checkpoints alone cannot resume anything. Stage 6A must treat the endings separately: **interrupted** (app killed, phone off) keeps the checkpoint and resumes; **paused** keeps the checkpoint and waits for the user; **cancelled** and **finished** clear it. When already-read source changes, restart that conversation's unfinished scan from its bookmark. Do not keep a history of earlier checkpoints.
- **Pause and resume.**
  - The scanner notification gets a **Pause** button. While the scan is paused, it shows **Resume** instead.
  - On the Memory Scanner Tool screen, **Analyze Conversations** becomes **Pause Conversations** while a scan runs and **Resume Conversations** while it is paused.
  - The chat header's background-work icon (`ChatBackgroundActivity.kt`) gets the same controls. While a scan runs, its pop-up line reads **Memory Scanner 85%** (the current percentage) with a **Pause** button. While a scan is paused, the icon shows in every open chat, new or old, and the same line reads **Memory Scanner** with a **Resume** button, so a paused scan is not forgotten. The text sits on the left and the button on the right. The summarizing line is unchanged. Appearance comes from `ui-style-guide.md`.
  - A paused scan stays paused until the user taps Resume, including after the app is closed or the phone restarts.
  - An interrupted scan resumes automatically, but only after the user's first chat message since the app started has been sent and its reply has finished. Resuming must never delay or block starting a chat.

**Stage 6A owner decisions (Owner, 2026-11-21).**
- **Progress that cannot be migrated.** The affected conversation is rescanned in full automatically, with no confirmation; saved memories and Pending suggestions are kept, existing duplicate prevention applies, deleted messages are never resurrected, and the Memory Scanner Tool reports which conversations needed it and whether it finished.
- **Unknown scope.** Messages with unknown scope are scanned normally and their suggestions are general; no association is invented, an imported transcript's companion is not trusted per message, and no counter is shown.
- **Settings note.** "You may continue chatting while the scan runs. If the app closes, your progress is saved. Scanning resumes after your next chat reply finishes. A scan you paused stays paused until you choose Resume."
- **Paused notification.** Title **Memory Scanner Paused**, the last percentage as "85% complete", and a **Resume** button. It can be dismissed without cancelling the scan and returns when the app next opens, with no boot permission.
- **Pause takes effect at once.** The section in progress is sent again after Resume.
- **Memory Scanner Tool while paused or interrupted.** The progress bar holds its last completed percentage; **Resume Conversations** and **Cancel** are shown; Cancel discards unfinished progress; Rerun stays unavailable. An interrupted scan can also be resumed by hand, and resumes automatically after the next completed chat reply; the chat header shows nothing for it until it resumes.
- **Another scan while one is paused.** A separate chat can be scanned. Scanning one chat of a paused batch takes only that chat out of the batch, with its checkpoint, and never resumes the batch.
- **System stops.** Android's time limit and other system stops are interruptions: progress is kept, the existing outcomes are shown, the same Recent Memory Analysis entry continues after resuming, and a paused scan is never listed as completed or cancelled.
- **Damaged chats.** The scan of that conversation stops, the existing damaged-chat detection and repair run, and only a verified repair lets it continue. The Memory Scanner Tool lists each damaged conversation with "A problem was found with [chat name]. The conversation was repaired, and scanning resumed. See the Damaged Chat Log for details." or "A problem was found with [chat name]. The conversation could not be repaired, and its scan is incomplete. See the Damaged Chat Log for details."
- **Logging.** Event Log lines for transcript copying and backfilling are removed with those operations. Recovery reads "Found N interrupted scans; kept their progress to resume." Migration failures, unconvertible progress and scanner failures stay logged, never with conversation text.
- **Backups.** A paused scan never holds up automatic backups. Temporary scan progress is not backed up, and a restore never brings back transcripts or an outdated paused scan.
- **Scanner request wording.** A split message's pieces read "Message 12 (part 1 of 3) — User: …"; parts never get their own message numbers. Earlier, previously analyzed messages reread from the chat come under "Earlier messages (context):" and the section under "New messages (analyze for memories):", with the instruction "Use earlier messages to understand new information and to correct or update working memories when later messages change their meaning. Do not create duplicate memory suggestions from earlier material alone. Analyze all new messages for information worth remembering."
- **Rescan notices** on the Memory Scanner Tool: "Rescan scheduled after upgrade: [chat names]. Previous scan progress couldn't be recovered. These conversations will be rescanned automatically.", "Rescanning after upgrade: [chat names].", "Rescan completed after upgrade: [chat names]." (only after a successful scan) and "Rescan incomplete after upgrade: [chat names]. See scan results for details."
- **Damaged Chat Log history.** A verified repair keeps the incident in the existing Damaged Chat Log as resolved repair history, under **Repaired Chats**; the chat no longer counts as damaged. Chats whose direct repair failed are listed under **Chats Where Direct Repair Failed**; the word "irreparable" is not used.
- **Permanent message numbers.** Every message slot has a permanent sequential number beside its UUID, starting at 1 per conversation. Edits and regenerated versions keep the number; deletions never renumber; numbers are never reused, because the highest number ever assigned is stored with the chat's settings and travels with its backups. Existing messages are numbered in their current order. Merges keep UUIDs and renumber only a conflicting later message. The message information pop-up's Show Message Number shows this number. The scanner AI sees "Message 4 — User: …"; UUIDs are never sent to identify messages, and numbers are not memory source references.

**Stage 6B requirements (Owner, 2026-11-21; preserved across 6B.1–6B.3).**
- **Already-covered entries (6B.3).** When the scanner leaves out a retelling because an existing memory already covers it, the same extraction response names the source passage reference and the existing memory ID in a compact "already covered" entry. Kotlin validates it and saves it with the checkpoint, so the filing-time recheck knows what was left out and why. Its exact format is part of the AI-facing instruction approval for 6B.3. Before filing, also check memories created or changed while the scan ran, not only matches found earlier.
- **Memories the chat already used are clues (6B.2).** Each AI reply records the memories that reached its request (`activeMemoryAttribution`, shown by the reply's Active Memories icon). When the scanner reads a section, it adds those recorded memories to the memories it compares against, alongside the normal search results. Each one still passes the current scope, eligibility and status checks, and a memory that has since been deleted is skipped. Replies saved before this record existed have none; the normal search covers them.
- **A limit on repeated earlier chat text (6B.1).** Earlier chat text, reread from the original chat and repeated for context, gets a bounded token allowance per request. The new section and the working drafts come first; earlier text gets only the room left. The user sets the allowance with the two Earlier Context settings on **Memory Scanner Tool Settings** (**Stage 6B.1 owner decisions**, below). Every unread section is still processed.

**Stage 6B.1 owner decisions (Owner, 2026-11-21).**
- **Earlier Context settings.** Two number fields directly after **Maximum Memory Scanner Token Amount**: **Earlier Context Average Token Amount** ("The average amount of earlier conversation text sent to the memory scanner AI to help understand new messages and correct working memories. All new messages are still scanned.") and **Maximum Earlier Context Token Amount** ("The maximum amount of earlier conversation text that can be included in a single scanner request. Allows additional context when needed without exceeding this limit."). Defaults 2,000 and 4,000; positive whole numbers, the maximum at least the average; no zero/off value.
- **How earlier text is chosen.** Whole earlier messages, newest first, until about the average; the average may be exceeded to finish a message, the maximum never. A message is cut, keeping its end, only when it cannot fit under the maximum. No per-message limit. New unread messages and working drafts always come first; earlier text fits the model's remaining capacity and never causes unread source to be skipped.
- **Measurement.** Token use and request sizes are measured on the Stage 6 test chats (`EarlierContextMeasurementTest`). Real provider caching and cost measurements move to 6D; no paid calls in 6B.1.
- **Technical AI instructions.** The mandatory draft-editing protocol, its JSON fields, draft references, validation and repair-request wording are app-owned engineering, separate from the editable Associative Memory prompt, and work whether or not the user customized that prompt.
- **Failed repair.** The original draft is kept unchanged; independently valid new wording is kept as a separate draft linked to it for 6B.3, never replacing, activating or discarding anything. An invalid withdrawal without valid content does nothing.
- **Event Log.** "chat=<id>: N draft edit(s) failed validation and were not applied", never with conversation text or draft contents.
- **Default Associative Memory prompt.** Adds "Keep the decisions, reasons, details, and outcomes that will matter later. When the situation changed, preserve meaningful history and make the latest supported status clear." Customized prompts are not changed.

**Stage 6B.2 owner decisions (Owner, 2026-11-22).**
- **Comparison cache and migration.** Approved as proposed: two tables in the encrypted memory database (`scanner_comparison_vectors` for filed Pending and Archived memories, `scanner_draft_vectors` for one scan range's working drafts), database version 37 → 38, additive only, at most 5,000 entries together, least recently used removed first. Vectors only, keyed by memory or draft, embedding model and a fingerprint of the exact text; a deleted memory or a finished, cancelled or restarted range takes its rows with it; chat retrieval never reads them.
- **Backups (Owner, 2026-11-22).** Memory exports and portable backups never include the cache. The automatic encrypted whole-database memory backup copies the entire database file, so it does include both cache tables, and restoring that copy brings them back; backup behavior is otherwise unchanged. A restored entry is reused only while its memory ID, embedding model and text fingerprint still match the current memory or draft; anything else is ignored and replaced.
- **What the scanner AI sees.** Unchanged in 6B.2: the existing Active-only memory list and wording. Memories a section's replies used (`activeMemoryAttribution`) that are still Active and eligible join that list (up to 3, directly after the top search results). Pending, Archived and working-draft matches are collected as evidence only; showing them to the AI, and every reconciliation decision, belong to 6B.3.
- **Wording.** No new user-facing wording, AI-facing wording or Event Log lines.

**Stage 6B.2 as built.** Code is in `memory/matching/`.
- **Eligibility first.** `MemoryStore.scannerComparableMemories` returns Active, Pending and Archived memories under exactly the scope and target rules of chat retrieval (one shared SQL builder); Superseded is excluded; every Type and No Type is included. Working drafts follow the same rule (`ScannerDraftEligibility`): global always, real-life and project outside roleplay, targeted drafts only for their own target.
- **Two lookups** (`ScannerMatchEngine`, `ScannerMatching`). Before each section is sent, the section is searched in overlapping windows sized to the model's query allowance as counted by its tokenizer (`ScannerQueryWindows`; the last window ends at the end of the section; text without spaces is cut into 48-character pieces at code-point boundaries, and a piece that alone exceeds the allowance is halved again, so no window is silently cut short). A window match needs similarity ≥ 0.48 and a shared informative word (Step 2's section rule). After the reply is applied, each new or changed draft is searched by its own wording; a match needs similarity ≥ 0.80 (the Possible Match bar). Exact wording counts without a model. Records whose meaning was not compared get keyword evidence (at least two shared informative words, or all of a shorter record's), never a score, and are counted as `unexamined`.
- **Starting limits** (measured on the phone in 6D): query windows read 8 per batch with a cancellation check between batches; 12 missing vectors read per lookup; 3,000 records compared per lookup, those sharing words first; 8 matches per lookup (4 keyword); 12 section matches kept per draft; 20 draft lookups per section, the rest deferred to later sections. Active memories without a search-index vector are not read by the scanner (the index repair owns them); they fall back to keywords.
- **Queue.** Every scanner read goes through the single embedding queue at background priority (`Librarian.scannerEmbedder`), so live sends go first.
- **Evidence** (`MatchEvidence`, saved in each draft's encrypted checkpoint payload): record ID or draft ID, status (`active`, `pending`, `archived`, `working_draft`), method (`exact`, `embedding`, `keyword`, `chat_clue`, `ai_related`, `alternative`), score for `embedding` only, the looked-up text's fingerprint and model key, the `unexamined` count, and unresolved links (AI-named related memories and alternative drafts still eligible). A draft's evidence fingerprint covers its comparison document and its links (AI-named related memories and alternative drafts); evidence whose fingerprint or model key no longer matches the draft is deferred, not complete. A failed lookup never stops a scan; its evidence is simply missing (deferred).

**Stage 6B.3 handoff.**
- **Read the evidence, don't trust it.** 6B.2 evidence is a snapshot. Before any decision, recheck each named memory's current status, scope, targets and text (it may have been edited, approved, discarded, archived or deleted since). Draft IDs in evidence can point at drafts later combined or withdrawn; resolve them against the current working collection.
- **Deferred lookups.** A draft whose `proposal` evidence is missing or out of date (`DraftMatchEvidence.needsLookup`) has not been compared yet. Run its lookup (`ScannerMatching`) before filing; never treat it as "no match".
- **Not exhaustive.** `unexamined > 0` or a null model key means meaning was not fully compared; keyword or missing evidence must not suppress a proposal.
- **AI-facing work.** Showing Pending, Archived and draft matches to the scanner AI needs owner-approved wording together with the "already covered" format, presented in one message when 6B.3 starts. Until then the request stays Active-only.
- **Not yet done.** No suppression, filing, linking, cap ranking or Possible Match review changes were made in 6B.2. Filing still uses the pre-6B.2 path.

**Stage 6B.3 owner decisions (Owner, 2026-11-22).**
- **What the scanner AI sees.** Related Pending and Archived memories join the existing-memory list with a `status` of `"active"`, `"pending"` or `"archived"`, within the same limit of 10; direct Active and Pending matches come first and strong Archived matches fill any remaining space. The protocol's sentence "Emit no proposal when the information is already adequately represented by an existing memory." is replaced by the approved status instruction (`ArchivistRuntimeProtocol.EXISTING_STATUS_INSTRUCTION`). Working drafts need no new wording.
- **Already covered.** The reply's `"already_covered": [{ "messages": [12, 14], "memory_ref": "M3" }]` with the approved instruction (`ArchivistRuntimeProtocol.ALREADY_COVERED_INSTRUCTION`). Entries are validated, saved with the checkpoint and rechecked before filing; a genuinely new fact is never suppressed merely because similar information exists.
- **Two extra requests, only when needed, at most one of each per conversation:** the comparison check for close matches the AI did not see while writing a draft, and cap ranking when distinct suggestions exceed Maximum Suggestions Per Conversation, with the approved wording (`ScanComparisonRequest`, `ScanRankingRequest`). A failed comparison files each proposal linked for review; a failed ranking ranks on the device by topic coverage.
- **Event Log (approved exactly):** "chat=<id>: N repeated suggestion(s) not filed; already covered by saved memories", "chat=<id>: N suggestion(s) rechecked because a matched memory changed during the scan", "chat=<id>: comparison request failed; N suggestion(s) filed for review with their matches", "chat=<id>: ranking request failed; suggestions ranked on the device". No conversation or memory text.
- **Screen wording.** The existing interface stays. The scan outcome adds "Some memory suggestions could not be reconciled and were kept for review." only when a filed suggestion had a draft edit that could not be applied or is one of a linked pair of alternatives.

**Stage 6B.3 as built.** Code is in `archivist/ScanReconciliation.kt` with small hookups in `Archivist.kt`.
- **Before filing** (`scanRange`): every "already covered" entry is rechecked (`MemoryDependencies.holds`): it stands while the covering memory keeps its wording, scope and targets in Active, Pending or Archived, or after the user discarded exactly that wording (its rejection hash); otherwise its passages are read again as a normal scanner request (at most twice; then the conversation is left for a later scan, never falsely completed). Deferred lookups are run (`ScannerMatching.completeLookups`).
- **Reconciliation** (`ScanFilingPlanner`): exact and meaning matches from a draft's own lookup are the only evidence that can suppress it. The same wording in the same placement as an Active or Pending memory is a repeat without a request, whatever its Type. Other exact or meaning matches the AI did not see or link go to the one comparison request (at most 12 pairs, 2 per proposal, 256 tokens of reread wording each, 3,072 in all); pairs beyond that, and every pair when the request fails, are filed linked for review. Keyword matches never suppress. Linked alternatives that both remain are one cap unit. Units are ranked only when over the cap; a unit that drops out at filing lets the next through.
- **Filing:** model requests happen outside the transaction. `commitScanRange` rechecks every memory a decision relied on inside the transaction and, if one changed, writes nothing and throws `ScannerDependencyChangedException`; the decisions are made again without new requests (answers are kept per memory version), at most three times. Alternatives of a linked pair point at each other in `memory_possible_match_hints`, so either Pending card's Possible Match review shows the other.
- **Not done here:** match cards, scores on cards and new review actions (6C and 6D); the scanner AI's own working-draft matches are still resolved by its draft edits, not by a separate request.

**Stage 6C handoff.**
- **Match data available per Pending suggestion:** `memory_possible_match_hints` holds the AI-named related memories, the memories a comparison found genuinely updated ("new"), unexamined close matches beyond the request limit, and the other wording of a linked pair. The method and score are not stored on filed suggestions; 6C must compute or carry them (the 6B.2 evidence lives only in the scan checkpoint and is cleared at filing).
- **Linked pairs:** two Pending suggestions linking to each other are one unresolved choice; 6D adds the actions for them.

**6B boundaries and handoffs:** 6B.1 delivers scan-local draft editing without the comparison cache or final suppression logic. 6B.2 delivers tested, scoped match evidence without deciding which filed memories to alter or suppress. 6B.3 combines both, validates every dependency at filing, and passes unresolved reviews to 6C/6D. Every substage receives its own code PR, full relevant tests, Codex review for code changes, and a handoff; existing scanner paths remain usable between substages. This split does not weaken or drop any original 6B requirement.

The screen formerly called Memory Assistant is named **Memory Scanner Tool** (Owner, 2026-11-21). In sentences it is written in lower case: "the memory scanner tool".

Temporary scanner passage references are message UUIDs and offsets, never text, and support editing and recovery inside a scan. M.3 remains authoritative for permanent source information in the editor and backups. Archived memory comparison is scanner-only. The installed-model requirement in G.16 applies to Conversation Recall; the scanner also supports local keyword fallback. Stage 6’s frozen corpus remains the reference for recall, with scanner-specific expected results evaluated separately. Stage 15 still owns proposals for important dates.

**Original order rationale (G42, November 16; scanner priority added November 18)**
- **Provenance first.** The owner wants to build a memory collection now.
  Every memory saved before provenance exists has no recorded source, and
  that source cannot be recovered later. Provenance does not depend on any
  search or recall work, and Step 4a does not depend on it, so swapping
  stages 1 and 2 causes no rework.
- **Context assistance next,** because it fixes a measured live problem
  (follow-ups lose their memories) and its prerequisites are merged.
- **Step 5 and data control before the conversation index,** because they
  protect and improve the memory collection the owner is building, are
  smaller, and touch only the memory editor, the Memory Browser, the
  Memory Assistant and the memory database.
- **Conversation recall before Phases 3–6.** The owner asked for recall
  (G.1) and it has been the active roadmap since 2026-11-10; Phases 2–6 were designed but each waits until the owner
  asks for it (memory plan, Next steps item 7). Phase 2 is the exception
  above because it directly serves the Memory Browser. If the owner prefers
  Always and dates sooner, stages 12–15 can move ahead of stages 6–11:
  they use different databases and files.
- **Fewer, larger stages.** Steps 7–8, 9–10, 11–13 and 14–15 are grouped
  because each group is one coherent part of the system that can be
  reviewed and tested together. Each Step's tests and approval gates are
  unchanged. A builder may still split a stage if one PR would be too
  large to review safely, and says why.

## M.3 Memory provenance (Owner decisions of 2026-11-15 and 2026-11-16)

### What the owner decided

- **The source is the whole conversation (Owner, 2026-11-16).** A memory
  from the Memory Scanner records the conversation it was read from, which
  the app knows for certain. The scanner is never asked which message a
  memory came from: an inexpensive model choosing message numbers is not
  reliable, and correct details of the wrong message are still wrong
  attribution. Participant-specific attribution of a memory is not built now
  and is a separate future question.
- **No carried sources (Owner, 2026-11-16).** Save & Replace, Save &
  Supersede and a Possible Match merge do not copy old memories' sources onto
  the resulting memory. Each memory keeps only its own source; a superseded
  memory keeps its own as history.
- **A memory the user writes** records the user's own entry, dated when the
  user created it. It has no conversation.
- **Existing memories stay intact,** and sources survive edits, Pending
  acceptance, backup and restore, and embedding-model changes.
- **Stored provenance is not embedding input** and is never sent to an AI.
- **No excerpts:** no copy of message text is stored with a source.
- **The important date** (the former "event date") is one field, built with
  memory plan Phase 4 (stage 13), not in this stage.
- **The Lorebook is untouched (Owner, 2026-11-16).** The Lorebook prompt and
  lorebook suggestions get no sources.

### What the memory editor shows (Owner, 2026-11-16)

Directly under **Tags** in the memory editor opened from the Memory Browser.
For a memory from the Memory Scanner:

> **User:** Lena, Captain Vale
> **Companion:** Ada
> **Date:** November 10, 2025
> **Last Message Date:** November 12, 2025
> **Source Conversation**

- **User** and **Companion** list every distinct name the conversation
  displays for that role, in order of first appearance, separated by commas.
  They follow the chat screen's own name rules (`ChatSpeakerNames`): the
  chat's own username replaces every user name; a message without a saved
  name shows "User", and a reply without one shows the chat's current
  companion, else "Assistant". Names are the ones saved on the messages, so a
  deleted companion or Glamour never makes them disappear. Nothing is
  resolved from companion or Glamour IDs. A role with no messages has no
  line.
- **Date** is the date of the first message currently in the conversation.
  **Last Message Date** is the date of the newest message currently in the
  conversation, shown only when it falls on a different calendar day. Both
  describe the conversation, not the memory's extraction, and are read live:
  if the conversation continues, Last Message Date moves. Each is that
  exact message's own date and is never taken from another message. When
  the first message has no saved date, Date reads **Unavailable**; when the
  newest message has none, the Last Message Date line is hidden. Dates are
  fully spelled out ("November 10, 2025").
- **Source Conversation** opens the chat. When the chat was deleted it reads
  **Source Conversation: Deleted**; when chat storage cannot be read it reads
  **Source Conversation: Unavailable**. The memory itself is never changed or
  removed because its conversation changed.
- A memory the user wrote shows only **Date**, the date it was created.
- A memory with no recorded source (saved before this stage, or an import
  without sources) shows no lines.
- Appearance comes from the `Widget.App.SourceLine` styles in
  `ui-style-guide.md`.

### The design

**Storage.** Memory database version 35 adds `memory_sources`, one row per
source, removed with its memory: the kind (conversation, user's own entry,
or kept for later use: chat message, import, unknown), the chat ID, and when
and how it was attached. The message, speaker, Glamour and statement-time
columns are kept available for a later, more reliable scanner and are not
filled now. The migration gives existing memories no source rows; creation
and edit times are unchanged.

**Conversation details.** User, Companion, Date and Last Message Date come
from the chat list's details index (`ChatListIndex`), which already keeps
derived per-chat values and updates them in the background whenever a chat
is saved. It also stores each role's saved names and the first and newest
messages' own times. (Its existing earliest-known time, which the chat list
uses, is not used for Date.) Its database version 3 rebuilds
every chat's entry once from the chats (Owner approved, 2026-11-16). The
editor shows the last indexed details at once, prepares a chat not yet
indexed, and redraws when the index changes; it never reads the chat
history itself and never blocks.

**Chat IDs.** A chat rename updates sources through the existing rename path
(`repointChat`). A chat whose ID changes some other way reads Deleted.

**Backups, imports, restores and merges.** The table is in
`MemoryPortableRowFormat.memorySpecs` and the shared restore rows, and
sources ride each memory in the memory backup JSON. Older backups without
sources restore with none. A memory ID remapped during import keeps its
sources under the new ID.

**Tests.** Migration from version 34 with existing memories unchanged;
sources kept through Pending, acceptance and edits; Replace and Supersede
carrying nothing; every backup path round-trips and older backups restore
without sources; the user-entry date; the index's participant names and
dates; the editor lines, including Deleted, Unavailable and a same-day Last
Message Date.

## M.4 Context assistance: correction for API AI (Owner, 2026-11-16)

**Owner, 2026-11-16 (this consolidation's assignment):** the initial
rollout of context assistance supports **Off, Code-based and API AI**.
Local AI is supported by the architecture and built later. **API AI has a
dedicated configuration screen,** not merely a model picker. That screen
holds **its own API model selection, independent of the chat, Summarizer
and embedding models.**

This corrects G.9b and the Step 4b work order, which described only "its
own model selector". The interface, the helper's role, and the rules every
choice keeps are unchanged (G.9, G.9b).

**Decided by the owner, 2026-11-16:**
- **Name and place.** The screen is called **Memory Context Helper Tool
  API**. It is its own screen, opened from a row in **AI Settings**, beside
  Memory Scanner Tool API and Embedding Model.
- **What it supports now:** the user chooses their own API model for the
  helper, from their configured providers (never a built-in list). Nothing
  else. Local AI is not built and not shown; the code keeps room to add it
  later without redesign, and its settings will go with the helper's when
  it is.
- **How it works and looks:** the same as the Memory Scanner Tool API
  screen. The owner authorizes reusing that screen's structure and shared
  components for this one: the header with the Save disc, the endpoint,
  model, View All Models and provider routing rows, **every model parameter
  exposed** through the shared parameter list, Reset to Recommended, the
  same saving behavior (endpoint, model and routing at once; parameters on
  Save, with the green disc and toast), and the same leave-without-saving
  question. No new appearance is invented.
- **Other helper settings** go on **Memory Context Helper Settings** in the
  Memory Manager (M.11), not on this screen.

**Memory Context Helper defaults (Owner, 2026-11-16; the owner delegated
the remaining technical decisions and approved these defaults).** Each is
tested during implementation and its result reported; none is retuned
without the owner.
1. **Conversation input (corrected by the owner, 2026-11-16).** The new
   message and up to the number of recent user/assistant exchanges from
   the same chat set in **Number of Past Messages to Send** (below; four
   unless the user changes it). AI replies are included, because they may hold what a follow-up
   refers to. There is **no fixed token cap** on this history: the helper
   is an API model, separate from the on-device embedding model, and does
   not inherit that model's 2,048-token limit. Exchanges are kept whole
   and in chronological order as far as the selected API model's actual
   context window allows, after the fixed instructions and the response
   room its parameters set; when the window cannot hold all four, the
   oldest whole exchanges are left out first, and an exchange is never
   cut. When the app has no context window for that model, every exchange
   the setting asks for is sent, and a provider refusal for size is
   handled as a failure (item 4).

   **Number of Past Messages to Send (Owner, 2026-11-16).** A setting on
   Memory Context Helper Settings, shown while API AI is the Helper Method:
   the label **Number of Past Messages to Send** and a field that takes one
   whole number, filled in with **4**. The number is how many recent
   exchanges (a user message and the reply to it) the helper receives with
   the new message. The user may enter any whole number from 0 to 999 (the
   field holds three digits); the app adds no other limit, and the
   context-window rule above still applies. An empty or invalid entry is not saved and the field shows the
   saved number again (the shared number field's behavior).

   **Strict sliding window and caching (Owner, 2026-11-16).** The helper
   receives exactly the newest exchanges the setting asks for, moving
   forward one exchange each turn, so an older subject drops out and cannot
   pollute the current one. The request is ordered so a provider's prompt
   cache can reuse its start: the fixed instructions, then each earlier
   message as its own chat message in order, then the newest message last.
   On OpenRouter it carries the chat's stable cache key; it adds no explicit
   cache marker, since short helper requests rarely reach a provider's
   caching minimum and some providers charge to write one. A window that
   stays anchored and grows to reuse the cache is not built.
2. **Instructions.** A fixed, app-managed prompt. It resolves references
   and produces one standalone search query. It never answers the user,
   invents facts, or changes the actual message. No prompt editor at first.
3. **Time allowance.** 8 seconds in text chat, 3 seconds in hands-free
   voice. No automatic retries. Latency is checked in Step 4b and the
   result reported, saying whether these limits work.
4. **Failure (corrected by the owner, 2026-11-16).** The original message
   is sent normally, without helper context; a helper failure never stops,
   cancels or delays the chat request or hands-free voice. The failure is
   shown in a **persistent pop-up** that stays until the user presses
   **Okay** (no Snackbar, temporary notice or separate Details button),
   because a hands-free user may not be looking at the screen. It follows
   the app's error-detail standard (`conversation-summary-errors.md`
   sections 1 and 2, `GenerationErrorMessages.kt`): the title **Memory
   Context Helper Error**; the failure category as a heading; a plain
   explanation; once the reply has finished, that the message was still sent
   using normal memory search; then the actual sanitized provider or
   technical error (provider message, HTTP status, codes) whenever there is
   one, never replaced by a generic summary and never a guessed cause; and
   **Copy** and **Okay**. The categories are Model Configuration Needed,
   Time Allowance Exceeded, AI Service Unreachable, Connection Timed Out,
   Response Timed Out, Access Rejected, Model Unavailable, Rate Limit
   Reached, Quota Reached, Request Too Large, Request Rejected, AI Service
   Error, Response Unusable and Unexpected Error, each chosen only from what
   the app observed. API keys, credentials and conversation text are removed
   from every detail. The pop-up does not depend on Log Chat Failures. One
   pop-up per failure episode, as for Summarizer Errors: a repeat of the same
   failure shows nothing until the helper works again or fails differently.
   The exact wording is in `strings.xml` (Owner, 2026-11-16).
5. **Cost.** Actual provider-reported usage and cost through the existing
   usage system; "Not Reported" when the provider gives none. Unknown cost
   is never shown as zero.
6. **No separate privacy warning (Owner, 2026-11-16).** The app is an
   API chat application: the user enters their own key and assigns this
   model themselves, so choosing API AI shows no disclosure. No API helper
   call is ever made while API AI is not selected. A meaningful difference
   in what is sent, or in which provider receives it, is raised with the
   owner when it arises; ordinary API use is not treated as exceptional.
7. **Its own model only; no model selected.** The helper uses only the
   model set on Memory Context Helper Tool API. It never uses, copies or
   falls back to the chat's model, the Summarizer's, the Memory Scanner's or
   any other. API AI cannot be chosen until a model is set there, and the
   user is directed there. If the selected model becomes unavailable, the
   turn goes ahead with no helper.
8. **Logging.** Writing helper failures to the Provider Failure Log under
   the function name **Memory Context Helper**, and any debug-view label for
   memories found through the helper, need the owner's separate approval of
   their exact content first (`CLAUDE.md` section 7). Neither is built until
   then; the error pop-up (item 4) is how the user sees a failure.
9. **Starting state.** The helper starts off. When first turned on,
   Code-based is selected. API AI is only ever chosen by the user.
10. **Testing.** Test these defaults in implementation: time limits,
    invalid responses, provider failure, and no model. Use the existing
    frozen carry-over sets for retrieval quality. No new open-ended
    research, no claim that API AI is better without measurements, and no
    paid benchmark calls without a model and cost the owner approves.

**No placeholder screens (Owner, 2026-11-16).** Memory Context Helper
Settings is built in Step 4a and Memory Context Helper Tool API in Step 4b,
each using the screen arrangements already established.

## M.5 Embedding models stay replaceable

G.9c is the owner's decision. EmbeddingGemma 300M q4 is the current
default, not a permanent restriction. In addition:

- **Already true in the code:** each stored memory vector is stamped with
  its model's tag, and the `embeddings` table is keyed by memory and
  model, so vectors from two models can exist side by side while a change
  is made. The passage index of Steps 8–9 follows the same rule (G.5,
  G.5a rule 3).
- **Memory text, provenance and the Memory Browser never depend on the
  embedding model.** Changing the model can require re-embedding, never
  rewriting or losing memories or sources.
- **A gap to close before any change of model files or runtime** (found in
  the Step 2 audit, `memory baseline.md`): the stored vector identity holds
  the model ID, dimensions and document format, but not the runtime version
  or the model files' hashes, and the model download address is not pinned
  to a version. A change of runtime or model files would otherwise mix old
  and new vectors. No stage in M.2 changes either, so this is a requirement
  for that future work, not a current stage.
- **Matching numbers belong to a model** (G.9c, Proposed): another model is
  offered only after its own values are measured on the Step 0 and
  carry-over sets.
- **API embedding models** send memory and message text to a provider; their
  privacy and cost are decided first (G41). Local and API embedding models
  are Later in M.2.
- **An API language model that writes a search query** (context assistance)
  is a different choice from an API embedding model. They are configured
  separately.

## M.6 Saved-memory features carried in from the memory plan

- **Phases 2–6** keep their designs in the memory plan (Part C, Part D) and
  their decisions in Part E. They are built in M.2 order. Before each, the
  owner sees its exact wording, screens and AI-facing text, as Part E
  requires.
- **Database versions.** Phase 3 in the memory plan says "from version 33".
  Provenance used version 35 and Stage 5 used version 36. Each
  stage takes the next version when it is built.
- **Provenance and later phases.** Archived memories keep their sources.
  Fading and archiving preserve sources. Provenance's event date and
  Phase 4's important date are one field (Owner, 2026-11-16), built in
  Phase 4.
- **Step 1.13** shipped with Stage 2 under G45. See its Build record for the shared lorebook selection and duplicate check.
- **Step 1.2** is replaced by the Step 0 set and the two carry-over sets.
- **Step 1.12 and B.5's carry-over rule** are replaced by context assistance
  (G.9, G.9b).
- **Steps 1.14–1.16** stay conditional: only if testing shows a need.
- **D41, the memory-use instruction,** stays Proposed. Its exact text is
  approved first.
- **Automatic companion linking (Owner, 2026-11-16).** Creating a companion
  must link it to the memory system automatically; the user never has to
  press a setup button. The "Create Companions From Personas" button and its
  "Setup / Repair" heading were removed from Memory Repair Settings the same
  day. **Checked in the code on 2026-11-16:** `MemoryCompanionSync.onPersonaSaved`
  creates the memory-system companion for a newly saved companion only when
  that button has been used once (`META_BOOTSTRAP_DONE`); otherwise the
  companion is created the first time it is chatted with
  (`ensureCompanionForPersona`, from `Enforcer` and `TranscriptRecorder`).
  Nothing is created before the memory database exists. **To build:** saving
  a companion creates its memory-system record whenever the memory database
  exists, without that flag, and companions saved before the memory database
  existed are linked when it is first set up, without the button. The owner's
  own installation is already linked. The System Status line "Personas linked
  to companions" reports the old flag and is reviewed with this change.
- **Linking a memory to a Glamour (Owner, 2026-11-16: later, to be
  designed).** A memory can already be linked to a Roleplay Character
  (`memory_roleplay_characters`, which also controls which chats see it).
  **Checked in the code on 2026-11-16:** nothing links a memory to a
  Glamour. Open when designed: whether a Glamour link is only a label
  reminding the user which Glamour the memory is about, or a visibility
  rule that limits the memory to chats where that Glamour is active. Not
  built until the owner decides. This is separate from the User line on a
  memory's source, which only lists the names the conversation displays.
- **D58's open question** (whether a replacing memory keeps the old vibe)
  stays open. Sources are not carried to a replacing memory (M.3); the vibe
  is a separate question.

## M.7 Superseded and corrected instructions

| Earlier instruction | Where | Now |
|---|---|---|
| "Where things stand", sections 0–4, and "Next steps, in order" | Memory plan, top | Historical. Status is M.1; order is M.2. References to `beta/new-client` are obsolete: `main` is the integration branch |
| Carry-over, step 1.12 and B.5's carry-over rule (previous message's memories re-checked against the AI's reply) | Memory plan | Replaced by context assistance (G.9, G.9b; G37) |
| Section 3b "on hold" list | Memory plan | Each item placed in M.6 |
| D.13's build order and the "Phase 1 in pull requests" list | Memory plan | Replaced by M.2 |
| Phase 3's migration "from version 33" | Memory plan | The next free version when built (M.6) |
| Strict execution order table | G.16 | Replaced by M.2. The work orders stay in G.16 |
| "Each Step is one pull request" and "build exactly one numbered Step" | G.16, G.19 | One stage, normally one PR, per assignment (M.0; `CLAUDE.md`) |
| Step 6 needing Steps 0–5 complete | G.16 | Step 6 needs nothing unbuilt; Step 5 is an independent stage |
| API AI "chosen in its own selector" | G.9b, Step 4b | A dedicated configuration screen (M.4) |
| Searching with the previous messages as the carry-over method, and "the previous one or two" messages | G.1 item 18, G.15, G.18a | Measured and not used (G.9a, G37) |
| An optional live AI worker after Step 16 as the only AI help | RAG notes section 4 | The API AI context helper is in the initial rollout (stage 3); the live AI worker stays Later |
| Screen names and placements: Memory Controls, Advanced Memory Settings (Librarian / Embeddings, Debug), Advanced Memory Scanner Settings | This plan before 2026-11-16, memory plan | The 2026-11-16 screens and placements in M.11 |
| Code-based offered only if the held-out evaluation shows a benefit | RAG notes section 1 | Evaluated; the owner included it (G37) |
| Step 3 repair and evaluation "not merged" | RAG notes section 3 | Merged in #205 and #206 |

## M.8 Where the RAG notes went

| RAG notes section | Now |
|---|---|
| 1, context assistance choices and the helper's role | G.9, G.9b, M.4; decisions G37–G39, G44 |
| 1, embedding models are a separate choice | G.9c, M.5; G41 |
| 1A, Memory Browser provenance | M.3; G43 |
| 2, +3 relevance | G.10; G40 |
| 3, finish the evaluation | Done (#206); results in `memory baseline.md` |
| 4, build order and scope | M.0, M.2 |
| 5, still open | G38, G39, G41, G43, G44 |
| 6, repository handling | `CLAUDE.md` |

## M.9 Documents

| Document | Role |
|---|---|
| This plan | Authoritative build order and status for memory work, with the numbered recall work orders |
| [November 18 scanner proposal](<Memory Scanner update proposal nov 18.md>) | Detailed scanner behavior, comparison UI and evaluation for 6A, 6B.1–6B.3, 6C and 6D in M.2 |
| `memory baseline.md` | Evidence for every saved-memory measurement |
| `conversation_recall_baseline.md` | Created by Step 14 for conversation-recall results (G.16) |
| `Memory System/dynamic_memory_plan.md` | Reference: saved-memory search design and later saved-memory feature designs, D-decisions, research |
| `rag alteration notes November 14.md` | Historical record of owner direction, fully carried into this plan |

Where settings go on screen is M.11.

## M.10 Questions to ask the owner when a step starts

**Owner, 2026-11-16:** the owner is not asked design or engineering
questions ahead of time. Technical choices belong to the builder, using
the decisions recorded in this plan and the existing measurements. A
question that only the owner can answer (user-facing wording, or a product
choice the plan leaves open) is asked **immediately when that step starts**,
one at a time, and is never acted on before it is answered. A builder who
finds something missed adds it here rather than guessing.

| Step | Ask the owner when it starts |
|---|---|
| Stage 1, memory sources | Nothing open. Decided in G43 |
| Stage 2, Step 4a | Answered by the owner on 2026-11-16: G45 and G44 are decided; the Memory Context Helper Settings wording is in `strings.xml`; the debug view marks carried memories `[carried from earlier subject]` and top-match memories `[+3 top match]` |
| Stage 3, Step 4b | Answered by the owner on 2026-11-16: the error pop-up and its wording, the no-model message, the API AI choice and its explanation, the Memory Context Helper Tool API labels, the model picker title, the model cleanup line and the usage section are in `strings.xml`; no privacy disclosure (M.4 item 6). Still to ask: the model and cost for the one scored run on the frozen sets, and the exact content of any helper log lines (M.4 item 8) |
| Stage 5, data control | Answered by the owner on 2026-11-17: D44 is on by default; Retired and archived are one state, named Archived everywhere (the view, the setting and the toast); Delete moves an in-use memory to Archived with no pop-up and shows a toast; permanent deletes and Discard keep a confirmation pop-up; superseded memories stay behind the Superseded filter (owner ruling, August 3 2026); Pending suggestions are never archived; the wording is in `strings.xml` |
| Stage 6A, scanner reading and resume | Answered by the owner on 2026-11-21 (see **Stage 6A owner decisions** in M.2). Nothing open |
| Stage 6B.1, working-draft corrections | Answered by the owner on 2026-11-21 (see **Stage 6B.1 owner decisions** in M.2). Nothing open |
| Stage 6B.2, scoped matching and cache | Answered by the owner on 2026-11-22 (see **Stage 6B.2 owner decisions** in M.2). Nothing open |
| Stage 6B.3, reconciliation and filing | Answered by the owner on 2026-11-22 (see **Stage 6B.3 owner decisions** in M.2). Nothing open |
| Stage 6C, Browser match cards | Answered by the owner on 2026-11-22: card and comparison layout, labels and match order as built (Build record); only the opened match is preselected; Pending matches are review-only until 6D; no keyword layer. Nothing open |
| Stage 6D, Browser review and evaluation | Owner approved dedicated Edit Suggestion / Save Pending Changes / Discard Suggestion / Save Suggestion controls on the opened Pending pair, Keep Both Pending before Additional Matches, Review This Pair for other Pending matches, confirmations and messages in `strings.xml`; keep both linked; every Pending match stays outside legacy approval actions. Evaluation uses frozen credential-free extraction responses and controlled failure cases; physical-phone battery/review effort and provider billing/cache telemetry remain separate acceptance items. |
| Later stages | The approvals listed in M.2's "Approve first" column |

## M.11 Where settings go (Owner, 2026-11-16)

The owner reorganized the memory screens on 2026-11-16. Every new setting
in this plan is placed by this rule: **choosing an AI model or provider
belongs in AI Settings; how the memory system behaves belongs on its screen
in the Memory Manager; checking and fixing the memory system belongs in
Memory Repair Settings.** A stage that adds a setting puts it on the screen
below. Its exact position on that screen, label and wording are still shown
to the owner first.

**Memory screens and entry points:**

| Screen | Opened from | Holds |
|---|---|---|
| Memory Browser | Memory Manager | Memories, Pending, Archived, the memory editor and Possible Match review |
| Lorebook Browser | Memory Manager | Lorebooks |
| Memory Scanner Tool | Memory Manager | Running the Memory Scanner (Memory Assistant); the chat menu also offers Scan for Memories |
| Memory Scanner Tool Settings | Memory Manager, Settings section | How scans behave: suggestion limit, token amounts, Minimum Importance, the Associative and Lorebook Memory Prompts |
| RAG Active Memory Settings (formerly Memory Controls) | Memory Manager, Settings section | How memories are searched and sent in chats: Memory Defaults, Memory Engine; Memory Search Test last |
| Memory Repair Settings (formerly Advanced Memory Settings) | Memory Manager, Settings section, last | System Status and Rebuild memory index |
| Memory Scanner Tool API | AI Settings | The Memory Scanner's endpoint, model, provider routing and parameters |
| Embedding Model | AI Settings | Downloading and removing on-device embedding models |
| Memory Backup & Restore | Main Settings | Memory backups, restores, Check Database Integrity |

**Where this plan's settings go:**

| Setting or screen | Screen | Status |
|---|---|---|
| Context-assistance choice (G38) and every other context-helper setting, Code-based included | **Memory Context Helper Settings**: its own screen, opened from a row in the Memory Manager's Settings section | Owner decision of 2026-11-16; position in the section and wording open |
| API helper model and parameters (M.4) | **Memory Context Helper Tool API**, its own screen opened from AI Settings | Owner decision of 2026-11-16 |
| Number of Past Messages to Send (M.4 item 1) | Memory Context Helper Settings, shown while API AI is the Helper Method | Owner decision of 2026-11-16; default 4 |
| Conversation Recall setting (G.8b) | RAG Active Memory Settings | Owner decision of 2026-11-11; the screen was renamed |
| Room for recalled text (G.8b) | RAG Active Memory Settings | Moved from Advanced Memory Settings by the 2026-11-16 reorganization |
| Conversation Index section: Index Conversations, status, Delete Conversation Index (G.16a) | RAG Active Memory Settings, above the test rows | Moved from Advanced Memory Settings by the 2026-11-16 reorganization; wording unchanged |
| Conversation Recall Test (G21) | A row beside Memory Search Test at the bottom of RAG Active Memory Settings | Moved from Advanced Memory Settings, Debug, by the 2026-11-16 reorganization |
| Where the user installs a model in no-model notices (G.13b, G31) | AI Settings, Embedding Model | Location updated; the wording is still a proposal |
| Conversation index line on Check Database Integrity (G.13a) | Memory Backup & Restore | Unchanged |
| Source lines: User, Companion, Date, Last Message Date, Source Conversation, then the important date (M.3) | Memory editor opened from the Memory Browser, directly under Tags | Owner decision of 2026-11-16 |
| Word count and token-capacity meter (Step 5) | Top of memory creation, editing and reading views | Owner decision of 2026-11-17, corrected G34 |
| Memory Handling Preferences (memory plan Phase 2) | Its own screen, opened from a row in the Memory Manager's Settings section, after Memory Context Helper Settings | Owner decision of 2026-11-17; wording in `strings.xml` |
| Always, Archived and Dates views (Phases 2–4) | Memory Browser view dropdown | Owner design |
| Other embedding models, local or API (G41) | AI Settings, Embedding Model | Placement by the rule; Later |
| Live AI worker's model (G.11) | AI Settings | Placement by the rule; Later |

# Part G: Context assistance and conversation recall

Written 2026-11-10 as Part G of the memory plan. Order and status are in
Part M.

## G.0 Status and how to read this plan

- **This plan is a proposal.** It was written on 2026-11-10 from the current
  code, the review of #180, and published research. The Build record and M.2 give current status and execution order.
- **Two labels are used throughout:**
  - **Owner** means the owner said it on the date shown, or on 2026-11-10
    where no date is given. These are decisions.
  - **Proposed** means the agent recommends it. The owner has not approved
    it.
- **Names here are planning names,** not on-screen wording. Every label,
  explanation, and AI-facing text is shown to the owner before it is built.
- **Three kinds of statement, and which one a builder follows:**
  - **A work order** (a numbered Step in G.16, or a stage design in Part M)
    is the instruction. It says what to build, what not to build, and when
    the work is done.
  - **A design section** (G.5 to G.15) explains the work order. Where it
    is marked Proposed, it becomes binding only for the stages whose
    "Approve first" entry in M.2 is satisfied.
  - **A decision** (Owner, or a row in G.17) overrides both.
- **If two parts of this plan disagree, or a Step cannot meet all of its
  own conditions, the builder stops and says which parts.** A builder
  never picks one side quietly.
- **Rows and sections have similar names.** "G10" with no dot is row 10 of
  the decisions table in G.17. "G.10" with a dot is section 10.
- **The design covers the whole system,** including parts built later.
  M.2 decides the build order. It does not leave anything out of the
  design.

Words used in this plan:

- **Embedding model:** the on-phone model that turns text into numbers that
  stand for its meaning, so texts with similar meaning can be found.
- **Exchange:** one message from the user and the reply or replies that
  follow it, up to the user's next message.
- **Index:** a separate list of entries stored beside the chats. Each entry
  points to a place in a conversation. The conversation itself is never cut,
  moved, or rewritten.
- **Index entry:** one searchable piece, about a paragraph long, with labels.
- **Recall:** finding an earlier exchange through the index and adding a copy
  of it to what the AI reads for the current message.
- **Carry-over:** keeping what was relevant to the previous message while
  the conversation stays on that subject.
- **Scope:** the rule for which chats may see something (ordinary, companion,
  project, or roleplay).

## G.1 What the owner asked for (2026-11-10)

1. **Owner:** recall only ever adds. The full chat keeps being sent as it is
   today. Nothing is removed from what the AI receives in order to recall
   something else.
2. **Owner:** the Summarizer is a continuity engine, not a fact compressor.
   It carries the tone and depth of the conversation in the form the user
   chose. This work does not change it, does not use its summaries as raw
   material, and does not replace it.
3. **Owner:** recalled conversation is handed over in its original words.
   Tone is never stripped out.
4. **Owner:** chunking and the index must be designed properly, from
   research, before anything is built.
5. **Owner:** an extra paid AI model is optional. The system must work fully
   without one. The foundation must still be able to support the
   Summarizer's model doing message-level work while the user is chatting.
   The same model can do several jobs; the summarizing job stays its own
   job.
6. **Owner:** keep it cheap. Future AI prices are unknown. The Memory
   Assistant is not to be run live just to build the index.
7. **Owner:** recall within the current chat comes first. Recall from other
   chats is wanted later, and it must be controlled by scope: a roleplay
   chat must not reach companion-only material.
8. **Owner:** users with an embedding model come first. Support for users
   without one can come later.
9. **Owner:** no carry-over work until this plan is solid.
10. **Owner:** rely on published research where it exists. Spend effort on
    our own testing only where research gives no answer.
11. **Owner (2026-11-11):** when a chat is summarized or compacted, recall
    must still be able to bring back the original exchanges, even though
    the chat AI is otherwise given only the summary. This makes up for
    what a summary or compaction leaves out.
12. **Owner (2026-11-11):** existing chats are indexed in the background.
    The user never waits for indexing when opening a chat.
13. **Owner (2026-11-11):** a deeper system for giving the AI access to
    larger documents is wanted some day, not now. The foundation should
    not rule it out.
14. **Owner (2026-11-11):** recall is turned on with one setting in a
    settings menu. It is not something the user turns on in each
    conversation.
15. **Owner (2026-11-11):** conversations can be indexed without an
    embedding model. Index Conversations can be turned on with no model
    installed; the index is then built for keyword search. When a model is
    installed later, meaning search is added to the same index without
    rebuilding it.
16. **Owner (2026-11-12):** conversation recall requires the embedding
    model. This replaces item 15. Lorebook keyword matching is separate,
    is not changed by this work, and keeps working whenever the lorebook
    is on, with or without a model.
17. **Owner (2026-11-12):** where the right decision is not known, use
    what established, proven systems do to decide (G.18a). Custom design
    is acceptable where it is needed.
18. **Owner (2026-11-12):** the agent's recommendations of that day are
    accepted: overlapping pieces for long and dictated messages (G29),
    the simpler cutter, searching with the previous messages, and leaving
    borrowed context out of the first build. **Superseded in part
    (Owner, 2026-11-16):** searching with the previous messages was
    measured and is not used (G.9a, G37).

## G.2 What the review of #180 found

What #180 got right, checked in the code:

- common words no longer match every memory;
- importance is a small nudge and cannot outrank a real match;
- removing the cooldown was correct: the memory text is attached only to
  the message being sent and is never kept in the chat history
  (`ChatActivity.kt`, both send paths);
- the tuning numbers are stored, bounded, and adjustable on the test
  screen;
- the Memory Assistant's own search is untouched.

Gaps that are live on the beta:

- **Follow-up messages lose their memories.** Search uses only the latest
  message. "What should I feed her?" does not find the Pepper memory that
  the previous message found. Carry-over was planned to cover this and is
  not built.
- **Long messages miss memories.** A memory passes on keywords only when it
  covers half of the message's meaningful words, so one name in a long
  message never passes that way. Meaning search weakens too, because a long
  message blends its subjects into one average. Part A.11's own numbers
  show it: 8 missed of 26, up from 5.
- **The original numbers were guesses.** Step 0 now measures the current
  saved-memory search on 86 invented samples with the real q4 model. The
  results are in `memory baseline.md`. The separate conversation-recall
  test set is still built later in Step 6.

Smaller issues:

- "move" does not match "moving", and "run" does not match "running";
- when more than half of a library relates to the message, meaning search
  can reject all of it, because none stands out;
- every message re-reads the words of every memory. Nobody has measured
  this on a phone with thousands of memories;
- one log label says "no model" when the real cause was that the message
  failed to embed;
- meaning search reads only the first 512 tokens of a memory
  (`EmbeddingModels.kt`), about 375 words, so a longer memory is partly
  invisible to it. A memory's tags are placed last in the text the model
  reads (`RetrievalDocument.kt`), so on a long memory they are cut off
  too. The model itself can read 2,048 tokens, about 1,500 words. No
  reason for 512 is recorded in the code. See "The reading limit" below;
- the embedding model must run one request at a time, and `Librarian.kt`
  has a lock for that (`embedLocked`). The live chat search embeds the
  user's message without the lock (`searchInternal`), so it can run at the
  same moment as a background repair. Found in review on 2026-11-10; Step 1
  fixes it under G.5a rule 6.

**The reading limit, measured on 2026-11-12.** The model file the app
downloads (the q4 variant) was run directly, outside the app, on a cloud
machine. Phone times will be slower and are not measured yet.

| Text length | Result | Time on the cloud machine |
|---|---|---|
| 32 tokens | Read | 35 ms |
| 128 tokens | Read | 123 ms |
| 512 tokens (today's limit) | Read | 478 ms |
| 1,024 tokens | Read | 1,060 ms |
| 2,048 tokens | Read | 2,532 ms |
| 2,049 tokens | Refused by the model | |

- The model file accepts any length up to 2,048 tokens. The 512 limit is
  set in the app's own code, not by the model.
- Time follows the real length of the text. A short text costs the same
  whatever the limit is set to.
- **Proposed (G33):** raise the limit to 2,048 for stored text, meaning
  saved memories. They are read in the background, never while a message
  is being sent. The message being sent keeps a short whole-message pass
  and is covered by pieces (G.10), because one pass over a very long
  message is slow and blurs its details.
- **Owner (2026-11-12):** a memory the model cannot read in full must not
  be allowed silently. Memories are limited to what can be read, and the
  user is shown how long a memory is (G34).

## G.3 The whole system at a glance

| Part | What it does | Exists today? | Cost to the user |
|---|---|---|---|
| Conversation store | Keeps every chat whole and in order | Yes. Untouched | None |
| Chunking | Cuts each message into passages the embedding model can read in full (G.5b) | No | None |
| Index entries | One entry per passage, pointing to the whole exchange, with labels (G.5) | No. Useful parts exist (G.4) | None |
| Retrieval | Finds the right passages by meaning and by keywords, and rejects what is not relevant (G.6) | Parts exist for saved memories and for chat search | None |
| Scope walls | Decide which entries a chat may see | Yes, for saved memories | None |
| Context assembly | Chooses which exchanges are sent and writes them, labelled, beside the newest message (G.8) | Partly. Saved memories are placed this way | More text per message, up to recall's own size limit (G.8). With the starting values, up to 3 earlier exchanges |
| Carry-over | Keeps what was relevant while the subject continues | No | None |
| Live AI worker | Optional. The Summarizer's model does message-level work during a chat | No. The model connection exists | Paid, only when turned on |
| Upkeep | Keeps the index correct after edits, deletions, restores | Yes, for chat search | None |
| Testing | Saved-memory and conversation-recall test sets, plus the Memory Search Test screen | Saved-memory baseline and screen yes. Conversation-recall set no | None |

Recall from other chats is not a separate part. It is the same index and
search, with the scope walls deciding how far the search may look.

## G.4 What the app already has, and what it means for this work

Every point was read in the current code (2026-11-10 and 2026-11-11). A
builder must re-read the named files before relying on a point, because
code changes.

**Where chats are stored.**
- Two stores exist. Today every installation uses the older one: each chat
  is one encrypted file holding all its messages. A newer encrypted
  database with one row per message (`chatstore/ChatStore.kt`) is built
  but not switched on yet (`chatstore/ChatStorageAuthority.kt`).
- **Consequence:** the index must read chats through the app's existing
  chat-reading layer and identify a message only by its permanent message
  ID. It must never depend on which store is in use, on a database row
  number, or on a message's position in a file.

**How messages and chats are identified.**
- Every message has a permanent ID that survives edits, regenerated
  replies, and renames (`MessageIdentity.kt`). New messages get one when
  written. Older chats get theirs the first time they are opened.
- A chat has an ID too. For older chats that never had a stored ID, the ID
  is derived from the chat's title, so **renaming such a chat changes its
  chat ID** (`ChatPreferences.storedChatId`, `ChatRenameTransaction.kt`).
- **Consequence:** a rename must move the chat's index entries to the new
  chat ID (G.12). An index that ignores this would orphan the entries.

**What a stored message holds.**
- Its text, who wrote it, a time (missing on some old messages), and for
  AI replies a completion state: still streaming, done, or failed
  (`MessageCompletionState.kt`). A failed reply's error text is stored
  apart from the reply's words.
- A reply can have several versions (the stored fields `variants`,
  `displayVariant`, `canonicalVariant`).
- A message can be hidden from the AI (`MessageVisibility.kt`).
- Attachments are separate from the user's typed words.

**What the AI is actually sent for each message.**
- The Summarizer already builds, for every stored message, its permanent
  ID and its "model-facing" text: the exact words the AI would be sent, or
  nothing for a message that is not sent (`SummarizerController.Snapshot`,
  `Entry`).
- The chat search feature builds a different list for the user's own
  search (`SearchableMessageProjection.kt`). It skips unfinished replies
  but does **not** skip hidden messages.
- **Consequence:** the index is built from the model-facing text, not from
  the chat search list. What may be recalled is exactly what the AI could
  have been sent.

**The existing chat search index.**
- One entry per message, keywords only, in its own encrypted database
  (`chatsearch/ChatSearchStore.kt`).
- It is kept in step by `ChatSearchIndexManager.kt`: a chat is refreshed
  after it is saved, deleted chats are removed, a restore triggers a
  rebuild, and stale results are refused using a per-chat revision and a
  journal (`ChatSearchIndexJournal.kt`).
- When a chat changes, it **replaces every entry of that chat**. That is
  cheap for keywords. It would be far too slow for passages, which need
  cutting and embedding.
- **Consequence:** the triggers and the revision method are reused. The
  "replace the whole chat" method is not. Passages are updated one message
  at a time (G.5a rule 4).

**The embedding model.**
- EmbeddingGemma, in a q4 or int8 variant the user picks, producing 256
  numbers per text (`EmbeddingModels.kt`). It reads at most 512 tokens per
  text; the model itself allows 2,048.
- Text is given an instruction prefix before it is read
  (`OnnxEmbeddingModel.kt`): one prefix for a search, another for a stored
  text. The stored-text prefix has a title slot that is always "none"
  today.
- The tokenizer (`HfTokenizer.kt`) cuts off anything past the limit without
  saying so, and has no way to report how many tokens a text needs.
- The model must run one request at a time. `Librarian.kt` has a lock for
  that, and one call skips it (G.2).
- The model is downloaded by the user. It can be absent, and it can be
  changed.
- **Consequences:** a token-counting function must be added, and
  the cutter uses it (G.5b cutter rule 4). Vectors must record which model made
  them.

**Saved-memory search.** Meaning and keyword search with a relevance gate
(`LiveRetrieval.kt`, `KeywordText.kt`). It is written for saved memories.
Its keyword word rules are for English.

**Scope.**
- Saved memories are filtered by scope inside the database query
  (`RetrievalScope`, `MemoryStore.activeMemoriesForScope`).
- Today, when a turn finishes, the chat's companion, world, campaign,
  character, and project at that moment are recorded only in a copied
  transcript row, together with the message IDs
  (`TranscriptRecorder.recordTurn`, called from one place in
  `ChatActivity.kt`). This happens only when the memory system is set up,
  and the row groups several turns.
- **Consequence:** Stage 6A removes that copy (M.2, **The chat is the
  source**) and records the scope as metadata on each message in the
  original chat storage when its turn finishes. The index reads scope from
  there (G.7). The migration fills in older messages only where a
  transcript turn names the message's UUID.

**An exchange already has a definition.** A finished turn is one user
message and the reply to it. The Summarizer also never separates a message
from its reply (`SummarySections.kt`).

**Restore and merge.** A backup can replace the phone's chats or be merged
into them (`ChatMergePlanner.kt`). A restore changes a "generation" value
that the chat search index watches to know it must rebuild
(`ChatSetSourceGeneration`).

**Existing text splitters.** `ConversationTokenChunks.kt` splits text at
sentence ends for compaction and the Memory Assistant, using an estimate of
tokens, and deliberately leaves an oversized sentence whole.
`CompactionBatches.kt` groups exchanges into large batches. Neither fits
this work: one estimates, and both are built for a different size and
purpose. They are not changed.

**Other parts reused later:** placement of added text just before the
newest message, the Memory Space limit, the request-size check, the record
of what was sent with the previous message, the Summarizer's model
connection, and the Memory Search Test screen.

**Summary.** Neither existing index is the one this plan describes. The
chat search index holds one keyword entry per message and includes hidden
messages. The vector store holds saved memories only. The passages, their
vectors, their scope labels, the cutter, and recall are new.

## G.5 Index entries (Approved, G35)

**What one entry is.** An entry, called a passage, covers a stretch of one
message: usually a few short paragraphs or one long one. It points to its
message and its exchange. A short message is one passage. A long message
becomes several, so a small detail has a passage of its own and can be
found.

**Two texts for every passage.** This distinction runs through the whole
design:

- **The original text** is the stretch of the message itself. It is never
  changed. It is what the AI is given.
- **The search text** is what is handed to the embedding model to find the
  passage. It is the original text plus, where it helps, a little
  surrounding context (G.5b). It is never shown to the user and never sent
  to the chat AI.

**What a passage records:**

| Field | Why |
|---|---|
| Source kind | Today always "chat message". Lets a later document system share the stores |
| Chat ID | Which conversation. Lets search stay in one chat or cross chats later |
| Message ID (permanent) | Points back to the original message |
| Start and end inside the message text | Exactly which characters the passage covers |
| Order within the message | First, second, third passage of that message |
| Exchange it belongs to | The user message ID that opens the exchange |
| Previous and next passage | So nearby context can be added on a match |
| Who wrote it | The user or the AI |
| Time written, and position in the chat | Time for dates. Position because some old messages have no time |
| Fingerprint of the original text | Detects edits |
| Fingerprint of the search text | Finds the right vector |
| Messages the search text borrowed from | Not in the first build (G.5b cutter rule 6). Needed only if borrowed context is built later |
| Scope labels | Companion, project, world, campaign, character, or "unknown" (G.7) |
| Cutter version | Which version of the cutting rules made it (G.5b) |
| Vector state | Waiting, made, or failed, and for which model |

AI notes (G.11) are separate rows that point to a passage or an exchange.
They are never mixed into a passage.

**What is indexed.**
- The model-facing text of each message: what the AI would be sent (G.4).
- Only finished replies. A reply that is still streaming, or that failed,
  is not indexed. A failed reply's error text is never indexed.
- Only the canonical reply version used in AI requests. Browsing an
  alternative reply changes neither index eligibility nor the canonical
  version. Confirm the canonical field and promotion hooks in Steps 7–13.
  **Confirmed in code (Step 8):** the reply version the AI is sent is the
  one the stored message's top-level fields mirror. `message` (with
  `state`, `memoryScope` and the other per-version fields in
  `ChatAdapter.VARIANT_FIELDS`) always holds the version at
  `canonicalVariant` for a regenerated reply, and the primary slot's
  `canonicalVersionId` for a parallel response group
  (`ResponseGroupCodec` refuses a row whose top-level fields do not match).
  Browsing changes only `displayVariant` or the group's foreground choice.
  The Summarizer snapshot's `Entry.text` is built from that top-level
  `message`, and the index reads the stored message's top-level `state`,
  hidden mark and `memoryScope` (`IndexSourceMessage.from`). Promotion
  hooks are not connected until Step 10.
- Never a message hidden from the AI (G.5a rule 1).

**Attached documents and images are not indexed in this work (Owner,
2026-11-11).** An attached document is sent to the AI with every message
until the user condenses it, reduces it, or removes it
(`document-includes-plan.md`), so the AI already has it in full. Once the
user condenses or removes a document, recall does not bring the removed
text back. The owner noted that some documents may deserve to become saved
memories; that belongs to the Memory Assistant and is not part of this
plan.

**Small passages, whole delivery.** Search runs on passages. What the AI
receives is the whole exchange the passage belongs to, or a marked excerpt
when the exchange is too large (G.8).

**Storage design.** Four linked stores, in an encrypted database on the
phone, separate from the stored chats and from the saved-memory database:

| Store | One row is | Key | Points to |
|---|---|---|---|
| Passages | One passage | chat ID, message ID, order within the message | The message, by its permanent ID |
| Passage vectors | One vector | search-text fingerprint, model, vector size | Nothing. Any passage with that fingerprint uses it |
| Passage keywords | The searchable words of one passage | the passage row | The passage |
| Index state | One row per chat | chat ID | Which saved version of the chat the index describes, how many passages wait for a vector, and whether the chat is fully indexed |

- The stored chat stays the only home of the conversation. A passage holds
  positions, not a copy of the message.
- The keyword store keeps only the word index needed to search, never a
  readable copy of the message text (for example, a full-text index
  without stored content). It is encrypted, derived, and removed with its
  passage. Search text built for the embedding model is held in working
  memory only; just its fingerprint and vector are saved. Retrieval reads
  the current eligible text from the original chat storage
  (M.2, **The chat is the source**). If the database cannot keep a word
  index without a readable copy, the builder documents that in this plan
  and asks before storing text.
- A vector is keyed by the search text's fingerprint, not by the passage
  row. Making a vector is the slow step, so the same search text is never
  embedded twice, including after passages are rebuilt.
- Passages, keywords, and index state can be dropped and rebuilt from the
  chats at any time. Vectors are kept through a rebuild.

**Reused as it is:** permanent message IDs; the Summarizer's model-facing
text for each message; the embedding model and vector maths; the keyword
word rules; the chat index's triggers, revision check, and journal method.

**Extended:** the embedding model gains a token count. The scoring in
`LiveRetrieval.kt` is generalized so it can score passages as well as
saved memories, without changing any saved-memory result. Every use of the
embedding model moves onto one queue (G.5a rule 6).

**New:** the four stores, the passage cutter (G.5b), background embedding
of passages, and recall.

**Not changed:** the stored chats, the chat search feature and its index,
the saved-memory vector table, the Summarizer, and the existing text
splitters.

## G.5a Rules the index must guarantee (Approved, G35)

Added after outside reviews and an audit of the code on 2026-11-10 and
2026-11-11. Each point was checked in the code. This section, with G.5,
G.5b, and G.7, is the design of the conversation index. The owner
approved it as a whole (G35, 2026-11-23); these are requirements for
Steps 7–13, not options. Items G.7 marks as open stay open.

**1. Only what the AI may be sent can be recalled.**
- The app lets the user hide a message from the AI
  (`MessageVisibility.kt`). Everything that sends conversation to a model
  must skip it. The chat search index does not apply that rule, so it is
  not reused for recall as it is.
- Rule: a message is eligible only when it is visible to the AI, finished,
  and the version the AI is sent. Eligibility is checked twice: when
  indexing, and again at the moment of recall against the stored message.
  Hiding a message therefore takes effect at once, even when background
  indexing has not caught up. The same holds for a deleted message.
- No search text and no vector may contain words from a message the AI
  may not be sent. In the first build a passage's search text holds words
  from its own message only, because borrowed context is not built (G.5b
  cutter rule 6).
- **Text is never taken by position without a check.** A passage records
  positions inside the message's text, not the text itself. At the moment
  of recall, the stored message's text fingerprint is compared with the
  passage's. If they differ, the passage is not used and the chat is
  marked for a refresh. A change in how message text is prepared can
  therefore never cut an excerpt in the wrong place.
- A test proves that the set of messages the index may recall is the same
  as the set the chat request may send.

**2. No text is ever cut off silently.**
- The tokenizer drops everything past the model's limit without saying so
  (`HfTokenizer.encode`).
- Rule: before a vector is made, the search text is measured with the real
  tokenizer, counting the instruction prefix and special tokens. The
  cutter already cut to real sizes (G.5b cutter rule 4), so this is a safety
  check: a search text that does not fit is recorded as failed, never
  embedded cut off. A passage is recorded as embedded only when all of its
  search text was read.

**3. Vectors have an owner and a lifetime.**
- A vector is reused only when the exact text handed to the embedding
  model (the search text, with its speaker label), the embedding model,
  and the vector size all match. The cutter version is not part of this:
  when the cutting rules change and a passage's search text comes out the
  same, its vector is kept (G.5b, Versions).
- When the last passage that uses a vector is deleted or changed, the
  vector is removed.
- Vectors made by a model that is no longer selected are removed once the
  new model's vectors are complete.
- Deleting a chat removes everything derived from it, including vectors no
  other chat uses.
- The user must be able to delete the whole index. Its wording and place
  are recorded in G.16a.

**4. An index update is correct or it does not happen.**
- Passages are updated **one message at a time**, never by replacing the
  whole chat. When a chat is saved, its messages are compared with the
  index by permanent ID and original-text fingerprint. Only messages that
  are new, changed, or gone are touched.
- The passages and keywords of one message are replaced together, in one
  database transaction, against a recorded version of the chat. Before
  publishing, the version is checked again. A background job that finishes
  after the chat changed throws its result away.
- A vector that arrives for search text that no longer exists is
  discarded.
- A failure leaves the previous correct state in place and the chat marked
  as needing a refresh. It never leaves half-updated entries.

**5. Search stays fast as conversations grow.**
- Saved-memory search compares the message against every vector, which its
  own notes say suits up to about 50,000 memories (`Librarian.kt`).
  Conversation passages will outnumber saved memories many times over.
- Rule for the first conversation-recall build, Steps 7–16: meaning search looks only at the current chat's
  passages. Keyword search uses the database's own keyword index. Vectors
  are read in bounded batches, never all chats at once.
- Memory use is bounded too: as each batch is scored, only the best
  candidates so far are kept and the batch is released.
- Passage counts, indexing progress, and search time are recorded so they
  can be shown and measured.
- Recall from other chats needs a different search method for large
  collections. That choice is made when that later work is designed, and the
  storage is shaped so it can be added without rebuilding.
- Size, for reference: one vector is about 1 KB, so 100,000 passages are
  about 100 MB before overhead. Smaller vector storage is measured during Steps 7–13.

**6. One path runs the embedding model.**
- Rule: every use of the model goes through one queue: the live message,
  saved memories, and conversation indexing. A live chat request goes
  ahead of every background job that is waiting.
- The model reads one text at a time, so a live message that arrives
  while a background text is being read waits for that one read. It never
  waits for more than one. Background work is done one text at a time for
  that reason.
- That wait is measured (Step 1). One read of a full-length saved memory
  took 2.5 seconds on a cloud machine and will be slower on a phone. If
  the wait is long enough to feel, the background read is stopped when a
  live message arrives and done again afterwards, where the model's
  runtime allows a read to be stopped.
- This also fixes the unlocked call noted in G.2.

**7. Only messages with a permanent ID are indexed.**
- Every new message receives a permanent ID, and an older chat's messages
  receive theirs the first time that chat is opened. So the chat a user is
  in always has IDs, and recall inside the current chat loses nothing.
- **Owner (2026-11-11):** no migration is built to give IDs to old chats
  that have never been reopened. Those chats are simply not indexed until
  the user opens them.

**8. Restored and merged chats are checked, never assumed.**
- **Owner (2026-11-11):** after a restore, every message must be checked
  for whether it is already indexed. A user may also combine the history
  of two phones.
- Rule: after any restore or merge, each affected chat is compared with
  its index state, message by message, using the permanent message ID and
  the text fingerprint:
  - already indexed and unchanged: left alone, and its vector is reused;
  - new to this phone: indexed;
  - same ID but different text: re-indexed, and the old entries removed;
  - indexed here but no longer in the chat: its entries are removed.
- A message is never indexed twice, and a merged chat never ends up with
  entries from both phones for the same message.

**9. The index never depends on how chats are stored.**
- Two chat stores exist and the app will move from one to the other
  (G.4). The index reads through the app's chat-reading layer and keys on
  permanent IDs only, so the move needs no change to the index.
- When a rename changes a chat's ID, its entries move to the new ID in the
  same step, or the chat is marked for a refresh.

**10. Indexing never harms the chat.**
- Indexing runs off the main thread, never delays typing, scrolling,
  sending, or opening a chat, and never blocks a save.
- If the index is missing, damaged, or behind, the chat is sent exactly as
  it is today.
- An index failure is never shown as a chat error.

**The indexing steps for one new or changed message:**

1. Check that it may be recalled: visible to the AI, finished, the version
   the AI is sent, and holding a permanent ID.
2. Take its model-facing text and cut it into passages (G.5b), recording
   exactly where each sits in the message.
3. Stamp scope and the exchange it belongs to.
4. Replace that message's passages and keywords in one transaction.
5. Build each passage's search text. Reuse any vector that already
   matches. Queue the rest.
6. Make queued vectors in the background, behind any live request,
   measuring each with the real tokenizer first.
7. Mark the chat's index state complete when nothing is waiting.

## G.5b Chunking: how a message is cut into passages (Approved, G35)

Chunking decides what each index entry means, so it is settled before the
index is built. It runs on the phone, costs the user nothing, and uses no
AI model beyond the embedding model.

**What the research supports.** A simple rule based on paragraphs, lines,
and words. A study of automatic "semantic chunking" found it did no better
than plain sizes on realistic text, and splitting by topic only helped when
a capable paid model did it (G.18). So the cutter is a plain rule, made
careful, not a clever one.

**The goals, in order.**
1. Every character of the message is in exactly one passage.
2. Every passage can be read in full by the embedding model.
3. A passage is large enough to mean something on its own.
4. A small detail is not buried in a passage about something else.
5. Nothing that belongs together is lost at a boundary.

**Cutter rule 1: split the message into natural pieces.** In this order:
paragraphs (a blank line); then lines, which keeps code, lists, and tables
intact; then between words; and last, between two characters, never
through the middle of one. A piece is only split further when it is too
large for a passage.

Periods are not used as cut points. Speech-to-text leaves them out of
whole paragraphs and also puts them in the middle of a thought (Owner,
2026-11-12). SillyTavern and LangChain's standard splitter cut the same
way: paragraph, line, then between words (G.18a).

**Cutter rule 2: join short neighbouring pieces.** Short pieces next to each other
in the same message are joined, in order, until the passage reaches the
target size. Four one-line paragraphs become one passage, not four vague
ones. Pieces are never joined across two messages.

**Cutter rule 3: respect the two sizes.**
- **The target size** is what the cutter aims for. Starting value: about
  256 tokens, the figure most often used in published work. A smaller
  target finds small details better and a larger one carries more meaning;
  the right value for this app is measured in Steps 14–15 and is adjustable.
- **The hard limit** is the embedding model's limit for stored text (512
  tokens today, 2,048 after Step 1), counting the instruction prefix and
  special tokens. No passage's search
  text may exceed it.

**Cutter rule 4: sizes are counted with the model's own counter.**
- Indexing is only on when an embedding model is installed (G30). So the
  cutter is given that model's real token counter (Step 1) and cuts to
  real sizes the first time.
- An earlier version estimated sizes and cut a message again when the
  estimate proved wrong. That was removed on 2026-11-12: it meant reading
  and decrypting the chat a second time, and on text with no spaces (a
  long link, packed code) the estimate could stay wrong however small the
  target became.
- A stretch with no spaces that is still too large is cut between
  characters until it fits.
- Before a vector is made, the search text is counted once more as a
  safety check. One that does not fit is recorded as failed (G.13a). It is
  never embedded cut off, and never retried in a loop.
- When the user changes to a model with a different limit or a different
  way of counting, chats are re-cut in the background (G.12).

**Cutter rule 5: carry meaning across boundaries.** Two protections, both wanted:
- **Overlap in the search text only.** The search text of a passage begins
  with the last words of the passage before it, up to a set number, when
  both are in the same message and it fits. The original text is not
  overlapped, so no character is delivered twice.
- **Neighbour links.** Each passage records the one before and after it, so
  recall can add the neighbour when a match cannot be understood alone.

**Cutter rule 6 is not in the first build (2026-11-12).** No established chat
app adds a neighbouring message's words to a short reply before indexing
it (G.18a), and it causes most of the upkeep work in this plan: every
hide, edit, deletion, or scope change has to rebuild its neighbours. Cutter
rule 6 and the borrowed-context rules below stay here as the design to use if
the Steps 14–15 measurements show short replies are being missed. Until then,
a short reply is found through its exchange, and the only overlap is
between passages of the same message (cutter rule 5).

**Cutter rule 6: give short replies their question.** "It needs to survive. Don't
clear the composer." means little alone. When a passage is short, its
search text also includes the start of the message it answers: for an AI
reply, the user's message; for a short user message, the end of the AI
reply before it. This uses no AI call. The context is only in the search
text; the original text and what the AI receives are unchanged.
- It applies only below a size threshold. Adding context to a long passage
  would blur it.
- Because the search text then depends on the neighbouring message, an
  edit to that message also remakes the search text of the passages that
  borrowed from it.

**Borrowed context obeys the same rules as the passage itself.** This
covers the overlap in cutter rule 5 and the context in cutter rule 6.
- Text is borrowed only from a message that is itself eligible: visible to
  the AI, finished, the version the AI is sent, and not deleted. A passage
  can be eligible while the message beside it is not; then nothing is
  borrowed from that message.
- Text is borrowed only from a message with the same scope. If the chat's
  companion, project, or roleplay settings changed between the two
  messages, nothing is borrowed across that change.
- When a message is hidden, edited, deleted, or changes scope, every
  passage that borrowed from it has its search text rebuilt without the
  old text, and its vector remade. The old vector is removed under G.5a
  rule 3.
- Until that rebuild finishes, a passage whose borrowed context is no
  longer allowed is not matched by its old vector. It can still be found
  by its own words.
- Each passage records which messages its search text borrowed from, so
  these passages can be found at once when one of those messages changes.

**Cutter rule 7: record who is speaking.** The search text states whether the
user or the AI said it, using the embedding model's own title slot, so
"I moved to Leeds" from the user is not confused with the AI saying it.
Whether this improves results is measured in Steps 14–15; it is dropped if it
does not.

**Very short messages.** "ok", "thanks", and "exactly" have no meaning to
search for. Each is still a passage, so every character is covered, and it
is found through its exchange. On its own it has no informative words and
cannot pass the relevance gate.

**Versions.** The cutting rules have a version number, stored on every
passage. When the rules or the target size change, chats are re-cut in the
background and the old passages are replaced. Unchanged search text keeps
its vector.

**Languages.** Word boundaries are found with the platform's
own language-aware tools, so cutting works in any language. The keyword
word rules are written for English; other languages rely more on meaning
search. That limit is stated, not hidden.

**What the cutter never does:** change a message; drop a character; join
two messages; call a paid model; or leave a passage the embedding model
cannot read in full.

**Tests, and the Step each belongs to.**

With the cutter, in Step 7:
- joining every passage of a message, in order, gives back the message
  exactly, for ordinary text, very long text, text with no spaces, text
  with no punctuation, code, tables, emoji, and non-English text;
- no passage is larger than the size it was asked for, using the size
  function it was given;
- passages come out in order and never overlap;
- short neighbouring paragraphs share a passage;
- a detail in the middle of a long message has a passage that contains it;
- the same message always cuts the same way.

With the search text, in Step 8:
- no search text exceeds the model's limit, measured with the real
  tokenizer;
- no search text contains words from any message other than its own
  (borrowed context is not in the first build, cutter rule 6);
- the overlap holds only words from the passage before it in the same
  message.

Before a vector is made, in Step 9:
- a search text that does not fit is recorded as failed and is never read
  cut off.

## G.6 Retrieval: how the right conversation is found (Proposed)

A good index can still give bad recall if this part is loose. It is
specified here step by step. The numbers named are starting values,
measured and adjusted in Steps 14–15. They are engineering numbers; the owner
is not asked to choose them.

**What the existing code offers, and what is not copied.**
- Saved-memory search (`LiveRetrieval.kt`) scores every memory in the app's
  own code, re-reading every memory's words for each message. That suits a
  few hundred short memories. It is not copied for conversation passages,
  which number in the thousands and are longer.
- The chat search database already ranks keyword matches with the
  database's own keyword ranking (`ChatSearchStore.kt`). Passage keyword
  search uses that same database feature.
- Chat search orders results for a person browsing: titles first, then
  exact words, then newest. That ordering is for the search screen and is
  not used for recall.
- The ideas kept from saved-memory search are the relevance gate, "nothing
  qualifies" as a real result, and the rule that common words never count.

**Retrieval stage 1: decide what to search with.**
- The user's latest message, on its own.
- A message longer than one piece is also searched in overlapping pieces
  (G.10), up to a limit. Periods play no part.
- Follow-ups use the mode-specific recall contract to be decided in G36
  below. API AI can supply an additional validated query. Code-based
  supplies saved-memory candidates, which are not a recall query.
  Earlier messages are never glued onto the search text.
- Each of these is a separate search. A passage qualifies when it passes
  for any one of them.

**Retrieval stage 2: read a time reference, when there is one.**
- "Yesterday", "last week", "on Tuesday", "in March", or a written date is
  turned into a date range by plain rules, with no AI call.
- A recognized range favours passages written inside it. It does not
  exclude others, so a misread date cannot hide the right answer.
- Only clear phrases are read. Anything unclear is ignored, and time then
  plays no part. Published results show date-aware search helps time
  questions, and that a weak reader of dates does not (G.18), so this is
  kept simple and is measured before it is relied on.
- Time comes from the message's stored time. A passage with no stored time
  is not affected by a date range.

**Retrieval stage 3: gather candidates two ways, inside the current chat.**
- **Keywords:** the database's keyword index returns the best-matching
  passages for the message's informative words. Common words are dropped
  first, by the same rule saved-memory search uses.
- **Meaning:** the message's vector is compared with the chat's passage
  vectors, in bounded batches, keeping only the best so far (G.5a rule 5).
- A passage with no vector yet is not a candidate until its vector is
  made (G30).
- A passage from a message that is no longer eligible (hidden, deleted,
  unfinished, or not the version the AI is sent; G.5a rule 1) is excluded
  here, before it can take a candidate place or count toward a chat's
  typical similarity in retrieval stage 4. Retrieval stage 7 still checks
  again before delivery.
- Each way keeps a bounded list of distinct exchanges while collecting
  passages. Starting value: the best 50 exchanges per method. Keep the
  strongest matching passage and bounded supporting passages for each
  exchange. Apply that method's relevance gate to passage evidence.
  A long exchange's many passages cannot consume the candidate list
  before retrieval stage 6 groups the results. Do not first truncate to
  50 raw passages. Measure keyword and semantic candidate coverage.

**Retrieval stage 4: the relevance gate. Being the best match is not the same as being
relevant.** A candidate passes only when one of these is true:
- **Meaning:** its similarity clears a fixed minimum **and** stands clearly
  above this chat's typical similarity for this message. A chat about one
  project has a high typical similarity, so only a passage that is closer
  than the general run of the chat passes.
  - "Typical" is worked out over **every eligible passage of the chat** as its
    vectors are read, never over the 50 best. Measuring it over the best
    matches would raise the bar and reject good ones.
  - **A certain match always passes.** When a chat is about one subject
    throughout, nothing stands out, and the rule above would reject
    everything. That is the fault G.2 lists for saved-memory search. So a
    passage whose similarity clears a second, higher bar passes whether
    or not it stands out. That bar is measured in Steps 14–15.
- **Keywords:** it contains enough of the message's informative words,
  weighted by how rare each word is in this chat. One accidental shared
  word in a long message does not pass.

When nothing passes, nothing is recalled. Empty is a correct result.

**Two bars, not one.** Candidates are sorted into three groups:
- **clearly relevant:** passes the gate above;
- **borderline:** misses the gate narrowly, by a set margin;
- **not relevant:** everything else, dropped here.

Without a re-ranker, only the clearly relevant go forward and the
borderline are dropped, so behaviour is exactly the gate above. The
borderline group exists so that a better judge, when one is present, can
look again at a near miss (retrieval stage 5a).

The gate uses absolute measures on purpose. A method that only compares
ranks cannot say "nothing here is relevant", because something is always
ranked first.

**Retrieval stage 5: combine the two ways into one order.**
- Each passing candidate gets one relevance score: a weighted blend of its
  meaning score and its keyword score, each first put on the same 0 to 1
  scale. Starting weights: 0.7 meaning, 0.3 keywords, the split reported
  for Redis's tested system.
- Why a blend and not rank fusion. Reciprocal rank fusion, which merges
  two ranked lists by position, is widely recommended because it needs no
  tuning. A published comparison found the weighted blend more accurate,
  both on familiar and unfamiliar text, less sensitive to its settings, and
  tunable from a handful of examples (G.18). The blend also keeps real
  scores, which the gate and the size decisions need.
- Rank fusion is run alongside during the tests in Steps 14–15 as a comparison. If it
  does better on this app's conversations, it replaces the blend for
  ordering. The gate stays either way.
- A passage found both ways usually scores higher than one found one way,
  but the blend does not guarantee it: a strong match one way can
  outscore a weak match both ways. No bonus is added to force it.

**How each score is put on the 0 to 1 scale.** The two scores are not
comparable as they come, so each is converted by a fixed rule, never by
comparing against the other candidates in the list (which would make the
best candidate look perfect even when it is poor):
- **Meaning score:** 0 at the fixed minimum similarity, 1 at a "full
  strength" similarity, in a straight line between, and capped at both
  ends. Both similarities are settings for the embedding model in use.
- **Keyword score:** the share of the message's informative words the
  passage contains, weighted by rarity. It is already between 0 and 1.
  The database's raw keyword ranking number is used only to pick
  candidates, never as the score, because it cannot be compared from one
  message to the next.

**When a passage has no vector yet.** It is not recalled until its vector
is made (G30). The inspector shows it as waiting, so a missing result is
never mistaken for a poor match.

**Retrieval stage 5a: an optional re-ranker (a place is kept for it; not built
yet).**
- A re-ranker is a second model that reads the user's message and one
  candidate passage **together** and judges how well they fit. The
  embedding model never does that: it turns each text into numbers
  separately and compares the numbers. A re-ranker is slower and usually
  more accurate.
- It does not have to be an outside or paid model. It can run on the
  phone, downloaded by the user like the embedding model.
- **What is known.** The memory plan (A.10) records one test: a 23 MB
  on-phone re-ranker trained on web search did poorly on short saved
  memories. That test used a handful of invented memories and one model,
  and it was not run on conversation passages, which are longer and closer
  to the text such models are trained on. So it does not settle the
  question for this work. Published results do show a re-ranker helping:
  in Anthropic's test it took missed retrievals from 49% fewer to 67%
  fewer (G.18).
- **What it costs.** A download of tens to hundreds of megabytes, and
  time: it runs once per candidate. One small model is reported at about
  39 pairs a second on a desktop processor, so on a phone, re-ranking 20
  candidates could add a second or more to sending.
- **The design.** A re-ranker, when present, runs here, on a bounded
  number of the best candidates: the clearly relevant **and the
  borderline** (retrieval stage 4). It reads each with the message and makes the
  final relevance decision. It can reorder, it can reject a candidate the
  first search liked, and it can accept a borderline candidate the first
  search narrowly missed.
- It never sees the "not relevant" group, so it cannot bring back
  something far off, and its work stays bounded.
- "Nothing is relevant" remains a possible answer after re-ranking.
- With no re-ranker, this retrieval stage is skipped: the clearly relevant go
  forward and the borderline are dropped. Nothing else changes.
- **When it is decided.** After Steps 14–15 show what retrieval gets wrong on
  this app's conversations. If the mistakes are ones a re-ranker fixes,
  candidate models are tested on those same conversations, on the phone,
  and the owner decides whether to offer it as an optional download.

**Retrieval stage 6: group passages into exchanges.**
- Passages are grouped by the exchange they belong to. An exchange's score
  is its best passage's score, with a small addition when several of its
  passages matched.
- Several matching paragraphs of one exchange therefore count once and
  cannot crowd out other exchanges.

**Retrieval stage 7: remove what should not be recalled.**
- An exchange the AI is already reading close to the newest message: the
  messages the Summarizer keeps in full, or the last few exchanges when
  the whole chat is sent.
- An exchange containing a message that is no longer eligible, checked
  against the stored message at this moment (G.5a rule 1).
- The current message itself.

**Retrieval stage 8: avoid repeated text conservatively.**
- In the first build, collapse only exchanges whose complete text and
  speaker order are identical after whitespace normalization. Retain the
  later occurrence by chat position. Preserve wording, negation, numbers,
  names and dates when comparing.
- Semantic similarity and word overlap cannot establish that two claims
  agree. Keep both eligible exchanges when their wording differs,
  including an earlier wait and a later reply. Deliver selected exchanges
  chronologically under G.8.
- G36 may propose broader repeat detection only with its own calibrated
  passage-to-passage criteria and the correction cases below. It must
  never discard a later development in favor of an earlier account.

**Retrieval stage 9 is not in the first build (2026-11-12).** No established system
does it (G.18a), and review found two faults in it as written:
- its extra exchange was not put through retrieval stage 7, so it could add an
  exchange the AI is already reading, or one that is no longer eligible;
- it compares one stored passage with another and applies the gate from
  retrieval stage 4. That gate is set for comparing a message with a passage. The
  embedding model prepares a message and a stored text differently, so
  the two kinds of comparison give different numbers and need separate
  bars.

Steps 14–15 measure how often a changed decision is missed without it. If it
is built, its candidates go through retrieval stage 7 and it gets bars of its own.
The text below is kept as that design.

**Retrieval stage 9: follow a subject to its later turns.** A decision is often made
in one place and changed in another: "Let's use model A for summaries",
then much later, "Actually, switch the summarizer to model B". A question
about it may match only one of the two. Keeping both when both happen to
be found is not enough, so retrieval looks for the other on purpose:
- For each exchange that will be offered, the chat is searched once more
  using that exchange's own matched passage as the search text. This
  finds other exchanges on the same subject, however the user's question
  was worded.
- From those, the **most recent** one that clears the relevance gate
  against the passage, and is not a near-repeat of it, is added as a
  related exchange. Most recent, because the latest word on a subject is
  what the AI most needs.
- At most one related exchange is added for each offered exchange, and it
  counts against recall's size limit.
- Both are delivered in date order with their dates (G.8), so the AI sees
  what was said first and what was said later.
- **What this is and is not.** It is a rule that actively looks for later
  turns on the same subject. It is not a guarantee: it finds what the
  embedding model and keywords judge to be the same subject, and a change
  worded very differently can be missed. Its hit rate is measured in
  Steps 14–15 on the changed-decision test conversations, and reported.
- It uses vectors already stored, so it adds a comparison pass and no new
  embedding work.

**What retrieval hands on.** A short list of exchanges, best first, each
with its message IDs, which passages matched and where, its scores, the
matched words, and its date. Nothing is written into the request at this
point. G.8 decides what is sent.

**What retrieval must never do:** return something because the list was
empty; search another chat (until that later work is built); read a hidden,
deleted, or unfinished message; make a paid call; or delay sending beyond
the time measured and accepted in Steps 14–15.

**A time limit.** Retrieval has a fixed time allowance within G36's total
pre-send budget, set from the Steps 14–15 phone measurements. Recall is never skipped merely because searching
takes some time. Only if it has not finished within the allowance, the message is
sent exactly as it is today, without recall, and the "what was sent" view
shows that recall was skipped. A slow phone or a very long chat can
therefore make recall miss a message, but can never hold a message back.
This matters most in hands-free voice chat, where a delay is felt at
once.

## G.7 Scope walls (Approved, G35, on the owner's requirement)

**Owner:** recall across chats must be controlled. A roleplay chat must not
reach companion-only material.

- **Every entry is stamped with scope labels from the first day,** even
  while recall only looks inside the current chat. Adding other chats later
  is then a change to where search looks, not a rebuild.
- **Recall inside the current chat needs no wall.** The chat is already
  sending that conversation.
- **Recall across chats uses the same wall saved memories use,** enforced in
  the database query, not afterwards:
  - a roleplay chat sees only entries from chats with the same world,
    campaign, or character;
  - companion entries are seen only with that companion, and in roleplay
    only through the existing door (the narrator match or the existing
    setting);
  - real-life and project entries never enter roleplay.
- **Open:** which ordinary chats may see each other, and whether cross-chat
  recall is chosen per chat or per companion (G9).
- **Where a passage's scope comes from.** A chat's companion, project, or
  roleplay settings can change during its life, so the chat's settings
  today are not a safe label for its older messages.
  - **New messages:** scope is the metadata Stage 6A records on each
    message in the original chat storage when its turn finishes, whether
    or not the memory system is set up (M.2, **The chat is the source**).
  - **Older messages:** scope is what the Stage 6A migration recorded on
    the message, which exists only where a copied transcript turn named
    that message's UUID.
  - **Everything else:** scope is "unknown".
- **Unknown scope is private to its own chat.** Such a passage can be
  recalled inside its own chat and is never offered to another chat.
- **Scope is never guessed** from the chat's present settings.
- **To verify during Steps 7–13:** how much existing conversation has a
  recorded scope.
  **Confirmed in the code (Step 10, 2026-11-24):**
  - Every turn that ends after the Stage 6A update, whether the reply
    finished, failed or was stopped, records the scope in effect on the
    reply (and on its current version) and on the user message it answers
    when that message has none yet (`ChatTurnMemoryScope.stamp`, called once
    per turn by the chat screen). This happens whether or not the
    saved-memory database is set up. Only finished replies are indexed.
  - Not recorded: an earlier user message in a run of several sent before
    one reply (only the last is stamped), and every message whose turn ended
    before the Stage 6A update, except as below.
  - Older messages: the Stage 6A migration (`ScanSourceMigration`) records a
    scope only on a message that a live-captured transcript turn names by
    its permanent ID. It runs only when the saved-memory database is set up,
    and imported transcript rows record nothing. So a phone that never set
    up saved memories has no recorded scope on any message from before the
    update.
  - The share of a particular phone's history this covers depends on when
    the update was installed and is not measured here. Messages without a
    recorded scope are indexed as unknown and stay private to their own chat.
- **Glamour reference (Owner, 2026-11-23; confirmed in code in Step 8).**
  The memory system is to support optional Glamour associations later; this
  is preparation only and changes no visibility, retrieval or Glamour rule.
  Glamours, roleplay characters and companions stay distinct, and are always
  referred to by permanent ID, never by a display name.
  - **In the code a Glamour is the memory store's user persona record**
    (Glamour Studio edits it through `EditUserPersonaActivity`; the chat's
    active Glamour is `Preferences.getChatUserPersonaId`). There is no separate
    user-persona concept beside it.
  - **Recorded now:** when a turn finishes, the active Glamour's ID is saved in
    each message's `memoryScope` as `userPersona`, whether or not its name was
    displayed (`ChatTurnMemoryScope.current`). Each regenerated version keeps
    its own. A user message also records `userNameSource`
    (`glamour:<id>` or `roleplay:<id>`), but only for the identity whose name
    was shown, so a Glamour hidden by a roleplay name is not in it; the index
    does not use `userNameSource` for identity.
  - **In the index:** each passage stores that ID as `scope_user_persona`, or
    nothing. A message with a recorded scope and no Glamour stores nothing with
    `scope_known` set; a message with no recorded scope stays unknown. No
    historical Glamour is guessed or taken from a name.
  - **Not covered yet:** messages whose turn never finished (failed, stopped,
    interrupted replies are not indexed) and older messages without migrated
    scope have no Glamour. Linking a Glamour to memories, and any use of the
    reference in retrieval, needs its own design and approval.
- **Speaker labels (Owner, 2026-11-23):** the embedding model's title slot
  says **Human** for the user's messages and **Companion** for the AI's
  (G.5b cutter rule 7). These words are used only in that slot and in
  approved user-facing wording; API message roles and existing internal role
  values are unchanged, and the Memory Context Helper keeps the providers'
  normal roles, with identity context supplied separately where needed.
  Step 8 uses them as the default title (`SpeakerTitlePolicy.DEFAULT`).
- **Per-message titles (Owner, 2026-11-23):** each message's passages get
  their own title from a title policy, and the title is recorded on the
  passages, so identity-aware titles can be tested without redesigning the
  index. An identity-aware title uses the speaker of that message and the
  name recorded on the message when it was written
  (`IndexSourceMessage.recordedSpeakerName`, from `userName` or
  `companionName`), not the identity's current display name, so a later
  rename does not re-embed the identity's history unless that is decided
  explicitly. The title is supplementary search information; permanent IDs
  remain authoritative for associations and scope. Which titles are used is
  decided by the Steps 14–15 evaluation.

## G.8 Context assembly: what the AI actually receives (Proposed)

Retrieval finds exchanges. Assembly decides which of them are sent, in what
form, and where.

**The order of work.**
1. The request is built exactly as today: the full chat, or the summary
   plus recent messages when the Summarizer or Compact has condensed the
   older ones.
2. Retrieval (G.6) returns its list of exchanges for the latest message.
3. Assembly chooses from that list, within recall's limits.
4. The chosen exchanges are written into a clearly marked section just
   before the newest message, beside the saved memories. The stored
   conversation is not changed.
5. The whole request is checked once for size.

**Summarized and compacted chats (Owner, 2026-11-11).** This is where
recall matters most. Checked in the code: summarizing or compacting changes
only what is sent. The original messages stay stored on the phone, with
their permanent IDs. The index is built from those stored originals, so an
exchange that the chat AI now sees only as part of a summary can still be
found and added in its original words. The summary itself is not changed
and is not searched.

**When the whole chat is already sent.** The certain benefit of recall is
in summarized and compacted chats, above. When the whole chat is sent, a
recalled exchange is a second copy of text the AI already has, and it is
paid for twice. The evidence that the copy helps is mixed: one study found
that models attend least to the middle of what they are given, and another
found that moving passages to "preferred" positions did no better than
random order (G.18). So this benefit is measured in Step 16 before it is
relied on, and reported to the owner together with its cost.

**Choosing which exchanges are sent.** When more exchanges qualify than
fit:
- Exchanges are taken in order of relevance score, best first.
- An exchange is taken only if it fits what is left of recall's size limit
  and count limit.
- An exchange that does not fit whole is added as an excerpt (below) when
  an excerpt fits. Otherwise it is skipped and the next one is tried, so
  one very large exchange cannot block smaller ones.
- A neighbouring exchange is added only when the chosen exchange cannot be
  understood alone: its user message is only a few words and names no
  subject ("exactly", "and then?"). A neighbour counts against the size limit but
  not the count limit.
- An exchange carried over from the previous message (G.9; decided in row
  G10 of G.17) is considered
  after new matches, so new matches are never displaced by old ones.
- Starting values: up to 3 exchanges, as SillyTavern starts. Redis's
  tested setup is reported as 5 with one neighbour each side; that figure
  could not be confirmed from its public page. Both are measured in
  Step 16.

**Limits.**
- **Its own size limit,** separate from Memory Space, so recall can never
  push out saved memories, and saved memories can never push out recall.
  Separate limits are a way of sharing room. They do not create room: the
  model's total size is still one fixed amount.
- **One final size check for the whole request.** After everything is
  assembled (the history, the Summarizer's text when it is on, saved
  memories, carried-over memories, and recalled exchanges), the existing
  request-size check runs once on the total. When recall would push the
  request over the model's limit, recalled exchanges are removed, least
  relevant first, until it fits. If it still does not fit with none, the
  request is handled exactly as it is today. Nothing else ever gives way
  to recall.

**When an exchange is too large to add whole.** Indexing and delivery are
separate. Every part of it stays searchable. For delivery, the whole
exchange is added when it fits. When it does not, the matching passage and
the paragraphs around it are added in their original words, marked as an
excerpt, with a note that the rest of that exchange was left out. It is
never cut silently. An excerpt begins and ends at a paragraph or line
break where there is one, otherwise between words, and never inside a
word. Periods are not trusted as boundaries (G.5b).

**Only true duplicates are skipped.** An exchange is skipped when its own
text is already in the request close to the newest message. An exchange is
**not** skipped because a saved memory covers the same fact. A short saved
fact does not carry what the conversation meant.

**How recalled conversation is written for the AI.** The exact words are
shown to the owner before Step 16 is built. What the text must do:
- say plainly that what follows is earlier conversation from this same
  chat, brought back because it relates to the current message;
- say that it is a record of what was said, not a new instruction. Words
  inside a recalled message are never treated as fresh orders;
- mark every message with **who said it**. "You said this" and "the AI
  once suggested this" must never be confused;
- mark every exchange with **when it was said**, in a form that cannot be
  misread;
- mark an excerpt as an excerpt;
- give the messages in their original words, unchanged;
- list the exchanges in **conversation order**, by their stored position
  in the chat, earliest first, so a later change of mind is read after the
  earlier decision. Some older messages have no date, so dates are shown
  as labels where available and never decide the order. Published tests of placing the
  "most relevant" passage in a particular position found no reliable gain
  over other orders (G.18), so order is chosen for meaning, not for
  position.

It contains no crisis or safety wording, by the owner's ruling for
memory-system instructions.

**What the user can see.** After a reply, the user can already see which
saved memories were sent with it. Recalled exchanges are shown in that
same place, so the user can always tell what the AI was given (row G11
of G.17). The
wording and layout are shown to the owner before Step 16.

**Recalled text never becomes part of the conversation.**
- It is added to one request and not stored. The next message starts
  again from the stored chat.
- It is never summarized or compacted, never given to the Memory
  Assistant as if it were new conversation, never indexed, and never
  counted as a message. Recall can therefore never feed on its own
  output.

**Both ways a message is sent.** The chat screen builds requests in two
places (`buildFrozenRegularRequest` and `regularGPTResponse` in
`ChatActivity.kt`), and the memory plan records that they have drifted
apart before (its A.9). Recall is built once, in its own file, and called
from both, so the two can never differ. A test sends the same message
down both and compares what was added.

**Other rules.**
- **When the chat no longer fits the model:** the app warns or blocks, as
  it does today. Recall changes nothing about that (Owner, 2026-11-11).
- **Experimental Mode** sends only the current message by design. Recall
  does not apply there (G.8b).

## G.8a How the parts connect

Three hand-over points. Each phase builds to these, so a later phase does
not force an earlier one to be rebuilt.

**1. Index to retrieval.** For one chat, the index answers:
- which passages best match these words (keyword candidates, with scores);
- the chat's passage vectors, a batch at a time;
- which exchange a passage belongs to, and its neighbours;
- whether given messages may still be recalled right now;
- whether the chat is fully indexed, and how much is waiting.

Retrieval never reads the index's storage directly.

**2. Retrieval to assembly.** A list of exchanges, best first. For each:
its message IDs in order, who wrote each, when, which passages matched and
where in the message, the meaning score, how far it stood out, the keyword
score and matched words, and the combined score.

**3. Assembly to the request.** The text to add; the message IDs that were
included, for the "what was sent" view and for carry-over; how much room
was used; and what qualified but was left out, with the reason (no room,
duplicate, limit reached).

The Step 15 inspector shows the retrieval scores and reasons from point 2.
Point 3 comes from assembly, which is built only in Step 16: that Step
extends the same inspector with what was included, what was left out
because of no room, a duplicate, or a count limit, and what was actually
sent. Step 15 must not pretend to know assembly outcomes or implement
assembly early. This staging lets wrong or missing recall be explained
without duplicating assembly logic.

## G.8b The recall setting and where recall applies (Owner, 2026-11-11)

Decided by the owner on 2026-11-11.

**The setting.**
- It is in **RAG Active Memory Settings** (the screen formerly called
  Memory Controls; M.11), is called **Conversation Recall**, and
  starts **off**. It is one setting for all chats, not chosen per
  conversation.
- Its explanation reads: "Brings back earlier parts of the current chat
  when they relate to your message, including parts that were summarized.
  Recalled text is included in your normal AI requests."
- Turning it on may also turn on Index Conversations, so one switch is
  enough.
- It **never downloads an embedding model by itself.** With no model
  installed it cannot be turned on, and the user is told why and where to
  install one (G30, G31).
- If the user later turns Index Conversations off, indexing is **not
  silently restarted**, by recall or by anything else.

**Where recall applies.**
- **A chat whose memory is turned off** with the existing per-chat switch:
  recall is off in that chat too. That switch stays the one way to keep
  the memory system out of a chat.
- **Roleplay chats:** yes. Recall only ever reads the same chat.
- **Experimental Mode:** no. That mode sends only the current message.
- **Hands-free voice chat:** yes. It has a small, measured time allowance.
  Recall is not skipped merely because searching takes some time; it is
  skipped only when the allowance is exceeded.
- **A chat that no longer fits the AI model:** nothing changes. The app
  warns or blocks as it does today.

**Room for recalled text.** A starting amount is set through testing. It
is adjustable in **RAG Active Memory Settings** (M.11; placed in Advanced
Memory Settings before the 2026-11-16 reorganization), not only on the test
screen. Its label and explanation are shown to the owner before Step 16.

**What the user sees.** Recalled exchanges are listed on the existing
screen that shows which memories were sent with a reply, as a second
group.

**Follow-ups.** The owner's intent is continuity through follow-ups.
G36 must define recall behavior for each G.9b mode before Step 14.
Saved-memory carry-over alone does not implement exchange continuity.
Use the mode-specific contract below.

**The recall test screen** is opened from a row beside Memory Search Test at
the bottom of **RAG Active Memory Settings** (M.11; it was in Advanced
Memory Settings, Debug section, before the 2026-11-16 reorganization), and
is called **Conversation Recall Test**.

**Debug information.** Recall and carry-over may add information to the
existing memory debugging view, so a wrong result can be explained. This
does not authorize any new general application log entries. The exact
lines are shown to the owner before Step 16.

## G.9 Carry-over for saved memories (Owner, 2026-11-16)

**The problem.** The saved-memory text is rebuilt for every message and is
not kept in the chat history. A memory found for one message is gone for
the next unless that message finds it again.

**How it is solved.** Through context assistance (G.9b): one app-wide
choice of Off, Code-based, API AI (in the initial rollout) or Local AI
(later). Searching with the previous messages (G.9a) was measured and is
not used (Owner, 2026-11-16).

**Rules every option keeps.**
- The new message is searched as today. Its own results come first and
  are never changed by assistance; assistance only adds candidates after
  them.
- Every added memory goes through the enforcer's selection like any
  other: scope, Maximum Memories, the +3 rule, lore overlap and memory
  space. Nothing is kept merely because it was sent before.
- A carried memory is read again from the store when the message is sent:
  an edit applies at once, and a deleted, disabled or out-of-scope memory
  is never carried.
- **Off means no context assistance (Owner, 2026-11-16).** It does not
  freeze other approved retrieval improvements, such as the +3 top-match
  rule (G.10), which apply under every option.

**Code-based: subject tracking (Owner, 2026-11-16, as evaluated).**
- A message sets the subject when it has a confident match of its own: a
  memory it matched by meaning and keywords together, by meaning with a
  stand-out of at least 4.0, or through a section of a long message
  (G.10). Those memories become the chat's current subject.
- A message with no confident match of its own is sent its own matches,
  then the current subject's memories it did not already find.
- A later confident match replaces the subject. There is no turn limit;
  the three-turn variant gave no benefit.
- This is exactly the method `subject_anchor` fixed in
  `app/src/test/resources/recall_carryover_holdout/final_methods.json`.
  Its numbers are not changed. Results are in `memory baseline.md`.
- Only the user's messages set the subject. A carried memory is not tested
  again against the follow-up; it passed the relevance gate against the
  message that set the subject.
- **Known limit, measured.** A message that names nothing and is not a
  follow-up ("Tell me a joke about penguins") receives the previous
  subject's memory. Code cannot tell it apart from a follow-up.
- **Proposed:** a +3 memory matched only through the top-match rule
  (G.10) does not by itself set the subject, so Code-based stays as
  evaluated. The combined effect is measured in Step 4a.

**API AI and Local AI** resolve the follow-up into a search query instead
(G.9b).

**Relation to recall.** Carry-over and conversation recall are separate.
They share the context assistance (G.9b) and the final size check (G.8),
and nothing else. Each has its own size limit.

The owner requested equivalent follow-up continuity for recalled exchanges
(2026-11-11; G.8b). Its mode-specific implementation remains part of G36.
Saved-memory IDs and exchange IDs refer to different records.

## G.9a Searching with the previous messages (built in Step 3; not used, Owner 2026-11-16)

Step 3 built `PreviousMessageSearch`, which searches the new message and
0–3 previous user messages separately. On both carry-over sets it found
more expected memories but carried many wrong ones (held-out set, one
previous message: 15 more correct, 45 more incorrect; `memory baseline.md`).
The owner decided it is not the carry-over method and it is not one of the
context-assistance choices. Step 4a does not connect it to sending.
Removing it from the code is a separate owner decision.

These rules from its design still apply wherever a later Step keeps
earlier-message embeddings:

- **Prepare in the background, through Step 1's shared embedding queue,**
  once per unchanged message, never on every keystroke, never the
  unfinished draft.
- **Keep only in a small, bounded in-memory cache** for the app process's
  lifetime. Navigating to settings or another chat does not clear it.
  Never persist it to disk. Key entries to the chat, the user message's ID
  and content, and the embedding model tag. Discard an entry when its
  message is edited or deleted, its chat is deleted, or the embedding
  model changes.
- **Never cache a search result.** The search runs again for every message
  sent, against the memories, scope and chat as they exist then.

## G.9b Context assistance (Owner, 2026-11-16)

Context assistance helps a follow-up find what it refers to. It is
optional: saved memories and recall work in full with it Off, and no
choice makes a paid call the only route to continuity.

| Choice | What it does | Built in |
|---|---|---|
| Off | Nothing. Each message is searched on its own, as today | Step 4a |
| Code-based | Subject tracking (G.9). On the phone, no cost | Step 4a |
| API AI | An AI model writes a self-contained search query from the recent conversation. It has a dedicated configuration screen with its own model selection from the user's configured API providers, separate from the chat, Summarizer and embedding models (M.4) | Step 4b, part of the initial rollout |
| Local AI | The same role and contract as API AI, with an on-device model | Later; the interface must accept it without change |

**One interface.** Each choice is its own implementation of one contract,
called once for each message sent, before saved-memory search:
- **Input:** the new message, and the recent turns of the same chat that
  the choice may read (for API AI, G39).
- **Output:** nothing (Off, or when the choice has nothing to add);
  memories to carry (Code-based); or one self-contained search query (API
  AI, Local AI).
- **Unchanged downstream:** the search, relevance gate, enforcer
  selection, scope, Maximum Memories, the +3 rule and prompt assembly.
- Conversation recall (Step 14) uses the same output for its stage 1
  (G.6).

**What the API AI helper does (Owner, 2026-11-16).** It resolves
references in short follow-ups and subject changes ("she", "that one",
"the same thing") from the recent conversation and writes one
self-contained search query. It never answers the user's message, never
changes the message or what the chat model receives, and never adds a fact
that is not in the conversation it was given.

**How the API AI helper's query is used (Proposed, engineering).**
- Its query is searched in addition to the new message: the new message's
  own results stay first and unchanged, and memories found only through
  the query follow, through the same relevance gate.
- Its output is checked before use: one query of plain text, within the
  embedding model's message limit. Anything else is discarded and the turn
  goes ahead with no helper.
- Its other behavior (what it reads, instructions, time allowance, failure,
  cost, privacy, its own model, logging, starting state and testing) is decided:
  M.4, "Memory Context Helper defaults".
- Before it is described as better than Code-based, it is scored once on
  the two frozen carry-over sets exactly as `CarryOverFinalEvaluationTest`
  scores the other methods, with a model and cost the owner approves.

**The setting (Owner, 2026-11-16).** The context helper is optional and
**off until the user turns it on** with a toggle on the **Memory Context
Helper Settings** screen (M.11). With the toggle off, there is no helper:
each message is searched on its own, as today. With it on, the user
chooses Code-based or API AI from a **dropdown**. Only one runs at a time:
API AI does not use Code-based, and Local AI, when built, becomes another
choice in the same dropdown. The toggle starts off for everyone; when
first turned on, Code-based is selected. The API AI screen is Memory
Context Helper Tool API (M.4).

## G.9c Embedding models stay replaceable (Owner, 2026-11-16)

The embedding model is a separate choice from context assistance. An API
language model that rewrites a question is not an API embedding model that
computes vectors.

- EmbeddingGemma 300M q4 stays the default. Other local and API embedding
  models must be possible without redesign. None is built in this plan's
  current Steps.
- **Already in the code:** the `EmbeddingModel` interface, whose `tag`
  stamps every stored memory vector (`embeddings.embedding_model`); a new
  tag re-embeds. Model-specific tokenizer, prefix, dimensions, token
  limits and normalization stay behind that interface, not in retrieval
  code.
- **Every new store or cache of vectors** (the earlier-message cache, the
  passage index of Steps 8–9) records the model tag, keeps the original
  text so vectors can be rebuilt, and never compares vectors from
  different models.
- **Proposed:** the matching numbers (similarity floor, stand-out, the
  section threshold, and the 4.0 and 2.0 stand-outs of G.9 and G.10) were
  measured for EmbeddingGemma and belong to that model. Another model is
  offered only after its own values are measured on the Step 0 and
  carry-over sets.
- An API embedding model sends memory and message text to a provider. Its
  privacy and cost are decided before it is built (G41).
- Quality is reported separately from speed, memory use, battery and cost.
  Today's phone is a benchmark, not a permanent limit.

## G.10 A small detail inside a larger message (Step 2 built; repair merged in #202)

**The problem.** A small detail in a message about several things does not
find its saved memory or its earlier exchange (G.2).

**Why it happens.** Long and short messages are not handled differently.
One rule is applied to every message, and it weakens as a message covers
more subjects:
- **On keywords,** a memory is used only when it covers about half of the
  message's meaningful words. In "Tell me about Pepper", Pepper is all of
  them. In a message of two hundred words that mentions Pepper once,
  Pepper is a small fraction, so the memory fails.
- **On meaning,** the whole message is turned into one summary of what it
  means. A message about one thing gives a sharp summary. A message about
  five things gives a blur that is not close to any one memory.

**The model reads only the start of a long text.** The embedding model
reads about the first 350 to 400 words of a text and ignores the rest
(G.4). So today, meaning search sees only the beginning of a longer
message; only keyword search reads all of it. Searching in pieces is what
fixes this: every word of the message is inside at least one piece.

**The owner's concern (2026-11-12).** Periods cannot be trusted in
dictated text: speech-to-text leaves them out of whole paragraphs and also
puts them in the middle of a thought. Cutting a thought in half changes
what it means.

**What established systems do (G.18a).** None has a clean answer. They do
one of two things:
- search with pieces of the message as well as the whole. Pieces are cut
  at a paragraph, a line, or between words, never at periods, and each
  piece overlaps the one before it;
- or have an AI model rewrite the message into a search, at the cost of a
  paid call for every message.

**Approved rule: overlapping sections, used only for finding (G29).**
Step 2 applies this to saved memories only. Earlier-exchange recall remains future work.
- The whole message is searched first using the shared saved-memory matcher. The current repair may recalibrate its existing defaults under the frozen quality requirements below.
- A message longer than one piece is also searched in pieces. A piece is
  cut at a paragraph or a line where there is one, otherwise between
  words. Periods play no part.
- Each piece overlaps the one before it by half. Any thought shorter than
  half a piece is therefore whole inside at least one piece.
- **A piece is only ever used to find a memory. It is never shown to the
  AI.** What the AI receives is the whole saved memory, or the whole
  earlier exchange.
- A message short enough to be one piece uses only that whole-message pass.
  There is no line between "long" and "short" beyond that.

**When a piece's match counts.** A wrong memory is worse than a missed
one. Established systems send their best few matches even when none fits
well; this app does not.
- A section must pass the existing relevance gate (meaning OR keywords),
  AND have semantic similarity at least **0.48**, AND share at least one
  meaningful normalized word with the memory. All three requirements apply.
  Common words are excluded by `KeywordText`. A high semantic similarity
  alone, a shared word alone, or the usual whole-message gate alone is not enough.
- This owner-approved combined rule replaced the original proposal after
  that proposal added 29 incorrect matches on the original test set.
- The **80-word size, eight-section maximum, and 0.48 threshold are all
  provisional**: selection and evaluation used the same 32-memory set.
  This is not independent validation and does not guarantee zero additional
  incorrect matches on other libraries. Do not retune during repairs.
- **With no embedding model, or for a memory that has no vector yet,**
  nothing can confirm the match, so only the whole-message search runs, as
  today.

**Order and room.**
- Whole-message matches under the current tuning retain their order. Sections never push them out. The quality repair preserves every originally correct whole-message match and may remove incorrect matches; gold answers are unchanged.
- Memories found only through a piece come after those, best first, in
  whatever room the memory limit leaves. Entire memories are sent.
- **+3 importance (Owner, 2026-11-15; replaces the earlier section-only
  exception).** Maximum Memories applies normally to every matching memory,
  +2 and +3 included, and +2 and +3 rank alike. After the highest-ranked
  memories up to that limit are selected, every further matching +3 memory
  is added, each once. +2 never passes the limit. +3 always passes the count
  limit, subject to the existing relevance, scope and physical
  request-capacity requirements. This is the same for whole-message,
  section and previous-message matches: a +3 memory found only through a
  section is no longer held to Maximum Memories. Example: Maximum Memories
  5, ten matching +2 memories and three further matching +3 memories send
  eight.
- **+3 top match (Owner, 2026-11-16; G40).** A +3 memory that fails the
  relevance gate in a message's own whole-message search still matches
  when all three hold: its similarity is at least the ordinary similarity
  floor, its stand-out is at least 2.0, and it is the single most similar
  memory to that message. It is then a matching +3 memory under the rule
  above. The unrestricted lower bar, without the top-match condition, is
  rejected. Measured as `lower_bar_top_match_only` (`memory baseline.md`).
  It applies under every context-assistance choice, Off included.

**Time.**
- The number of pieces searched has a limit. When a message has more
  pieces than the limit, the pieces holding the rarest words (names and
  unusual terms) are searched first.
- The provisional allowance is **1,500 ms**. Queue waiting is bounded;
  during inference the queue requests cooperative ONNX termination at the
  deadline. Native inference must unwind before the model is released.
  This is **not a guaranteed hard deadline**: tokenization, native cancellation
  latency, section preparation, and scoring can extend total elapsed time.
  No detached embedding task may race a later message. Preserve whole-message
  results and successfully completed sections on timeout or section failure.
- The words of every memory are read once per message and reused, so the
  keyword work does not multiply.
- **The message is read by the embedding model once.** The whole message
  and its pieces are cut and read one time per message sent. Saved-memory
  search and conversation recall share those results. Neither reads the
  message again.
- When the limit or the time allowance leaves part of a message
  unsearched, the Memory Search Test screen says which part.
- Size, cap, similarity threshold and allowance are provisional engineering
  numbers. The CI x86_64 Android emulator measured a 1,507 ms additional
  search (one attempt, no completed section) after a 7,899 ms whole-message
  embedding. These are emulator observations, not physical-phone timings.
  The test screen separates queue waiting, model work and total section-read
  elapsed time (including section preparation and relevance scoring); report-only diagnostics and rendering are outside that line.

**Runtime validation.** The original Python ORT 1.29 fixture baseline remains
69 correct / 6 missed / 19 incorrect. Android ORT 1.20 measured 54/21/21 for
the whole message and 69/6/21 with sections, with no additional incorrect
matches from sections. All 313 input-token hashes matched independent
references. Small native-runtime vector differences change three whole-message
results; the original gold answers and fixtures remain unchanged. Recorded
Android observations are evidence only: accepting the worse result in PR #201
was an acceptance error. The repair branch `repair/step2-contract-audit` restores
original per-message quality checks for every ABI without changing gold labels
or acceptance requirements; newly wrong memories must fail. The initial repair
control, isolated Android 1.29 comparison, and explicit FP32-activation comparison
on Android 1.20 all failed that gate with 69/6/21. Neither experimental change
was adopted.

After reviewing later-step scope, the existing whole-message defaults were
calibrated to Stand-Out Strength 2.53 and Minimum Spread 0.034. Real Android
1.20 validation at `4d4c646` (run `43669339130`) now passes the original
per-message quality gate: **54/21/17 whole only; 69/6/17 with sections**. Every
original required correct match is retained and no newly incorrect ID is sent.
The independent set stays 4/5/0. All 85 Android tests passed, including telemetry
opt-out, queue/store/prompt and screen checks. Runtime, original model/weights,
tokenizer and dimensions remain unchanged; saved custom tuning is retained.
Quality evaluation searches all sections without a time allowance; a separate
timed emulator example completed one section before the cooperative limit.
Physical-phone timing remains unmeasured. Details and every changed sample are
in `memory baseline.md`. This repair is merged in #202.

**What the user can see.** The Memory Search Test screen must show which
piece of the message a memory matched, and when the search ran out of
time. Without that, the screen can no longer explain why a memory was
sent. Approved wording is recorded in G.13b. The repair also shows evaluated
section near-misses, all failed relevance requirements, attempted/successful
counts, processing failures and deadline cancellation. Unsearched sections are
not labelled as relevance failures.

**When the embedding model cannot read a piece.** That piece adds nothing.
This is reported, never silent, using the existing Memory Debug Log line
for this failure with its plain wording (G26). No log line is added or
changed without the owner's approval.

**The paid rewrite** is not built. It can be offered later as an optional
setting if the pieces prove not to be enough.

**The same rule serves recall** (G.6 retrieval stage 1).

## G.11 The optional live AI worker (Proposed, on the owner's requirement)

**Owner:** the foundation must support the Summarizer's model doing
message-level work while the user chats, as an option.

**Not the context helper.** The API AI context helper (G.9b) is a
separate job, run once for each message sent with its own model, and is
built in Step 4b. This worker stays Later.

- **What it is.** A background job that reads new exchanges as they are
  indexed and writes notes about them. It is off unless the user turns it
  on. With it off, everything else in this plan works in full.
- **Which model.** The connection and settings the Summarizer already has.
  It is a separate job with its own instruction. It never reads or writes
  the Summarizer's summaries.
- **Jobs it could do,** each one the owner's decision:
  - mark which exchanges hold something that matters (a decision, a date, a
    promise), which the embedding model cannot judge;
  - label the topic of a stretch of conversation. Published results favour
    topic-sized pieces, but only when a capable model does the grouping
    (G.18);
  - propose saved memories into Pending during the chat, for the user to
    review later.
- **What the foundation provides for it:** a place on each index entry for
  AI notes, kept separate from the original text and always marked as
  AI-written; a queue of exchanges waiting to be read; and upkeep that
  discards a note when its exchange is edited or deleted.
- **What is always delivered** is the original exchange. An AI note only
  helps find it.
- **What Steps 7–13 must leave in place for it,** so adding the worker later
  changes nothing already built: every passage belongs to an exchange with
  a stable ID; every passage records its source kind; the stores can gain
  a notes table without altering the existing ones; and upkeep already
  knows when an exchange was edited or deleted.
- **What is designed only when the owner starts that later work:** which jobs
  it does, the exact instruction it is given, the cost limits, and what
  the user sees. None of that is fixed here.
- **Cost.** It runs once per stretch of conversation, not once per message
  sent. The app shows what the service reports and never a built-in price.
- **Cost controls, required before it is built:** off by default; chosen by
  the user; a limit the user sets on how much it may read; it stops, and
  says so, when the limit is reached; it never re-reads an exchange that
  has not changed; and existing chats are never sent to it in bulk without
  the user asking.
- **Everything about it that the user sees or the AI is told** is shown to
  the owner before it is built.

## G.12 Upkeep (Proposed)

| Event | What happens to the index |
|---|---|
| New message | Its entries are added in the background |
| Message edited | Passages for that message are replaced. Unchanged search text keeps its vector |
| Reply regenerated or another version made canonical | Entries follow the canonical version used in requests. Browsing another version alone does nothing |
| Message deleted | Its entries and any AI notes on them are removed, and any vector nothing else uses |
| Message hidden from the AI, or made visible again | Takes effect on recall at once (G.5a rule 1) |
| Chat deleted | All its entries are removed, and any vector nothing else uses |
| Chat renamed | For older chats a rename changes the chat's ID. Its entries move to the new ID (G.5a rule 9) |
| Reply finishes streaming | Indexed then, not while streaming. A failed reply is not indexed |
| Cutting rules or target size changed | Chats are re-cut in the background. Unchanged search text keeps its vector |
| The user deletes the index | Everything derived is removed. The chats are untouched |
| Backup restored, or a backup merged into the phone's chats | Each affected chat is checked message by message (G.5a rule 8). Nothing is indexed twice |
| Embedding model changed | Vectors are remade in the background, and chats are re-cut first when the new model's size limit or way of counting differs (G.5b cutter rule 4). A chat is recalled from again once its vectors are ready |
| Chat settings changed (companion, project, roleplay) | Scope labels on later messages follow the new settings |
| Existing chats, first time | **Owner (2026-11-11):** indexed in the background, a little at a time, newest first. Opening a chat never waits for it |
| Already indexed text | Stays indexed. It is redone only when the message is edited, the embedding model is changed, or the index is rebuilt after a restore |

The index is derived: it can always be rebuilt from the stored chats, and
losing it loses nothing the user wrote.

## G.13 When a part is unavailable (Proposed)

**Owner (2026-11-12):** an automatic repair that succeeds does not need a
snack bar, dialog, or other interruption. The repair is recorded in the
Error Log and may appear in an existing status or progress view. The user
is notified when a failure changes what happened for their message, stops
a feature, prevents a setting from being used, or gives the user something
real they can do to fix it.

| Condition | What still works | User interruption |
|---|---|---|
| The embedding model is not installed | Ordinary chat and lorebook matching work as today. Index Conversations and Conversation Recall are unavailable | When the user tries to turn either feature on, explain what is needed and provide a direct way to open the model settings |
| The embedding model was removed or cannot be loaded | Ordinary chat still works. Saved-memory search may use keywords; Conversation Recall stops because it requires the model | One notice for the ongoing condition, with a direct settings action. Do not repeat it every turn |
| Recall fails for one message | The message is sent normally without recalled conversation | Tell the user that this turn was affected, why in plain words, and what to do if an action exists. If no action is needed, say so |
| Some index work is waiting or is being retried | Chatting continues. The app repairs or resumes it in the background | No snack bar or dialog. Show it only in the Error Log and existing progress/status views |
| Index damage is found and the rebuild succeeds | Chatting continues; the derived index is rebuilt from stored chats | No snack bar or dialog. A user who started Check Database Integrity still receives that check's result |
| The index cannot be rebuilt or indexing cannot continue | Chatting continues, but Index Conversations and Conversation Recall stop | A persistent notice explains what stopped, confirms that conversations are safe, and gives a real recovery action |
| Search or indexing reaches its time allowance | The message is sent or finished work is kept | Not an error. Record the effect in the relevant test/status or "what was sent" view; interrupt only when the user can take an action, such as reopening the app to continue indexing |
| The optional live AI worker or its network is unavailable | Everything except the optional AI notes | Follow that feature's separately approved failure behavior when it is designed |

Failures are never shown as a generic error. The same ongoing failure does
not create a new notice on every message. It may be shown again only after
the condition recovered and failed again, or when the user explicitly asks
for a check.

## G.13a Damage, failures, logs, and notices (Owner direction, 2026-11-11 and 2026-11-12)

**Owner direction.** A damaged index must be detectable without the user
having to start anything; it also belongs on the existing database check
button; and whatever repair is possible follows. Errors are written to the
Error Log. Provider logs stay accurate when a request fails. A user-facing
notice follows G.13: automatic repairs stay quiet, while affected turns,
stopped features, and actionable failures are explained.

**How damage is found.**
- **By itself.** The database reports damage whenever the app opens or
  reads it, the way the app's other encrypted databases already do. The
  index is also compared with the stored chats during upkeep; a chat whose
  index does not match is re-indexed.
- **On request.** The existing Check Database Integrity button (Backup &
  Restore, Database Health section) also checks the conversation index and
  reports it as its own line. Checked in the code on 2026-11-11: today that
  button checks the memory, lorebook, and user image databases.

**How it is repaired.** The index is derived from the stored chats, so
repair is a rebuild, started automatically. No backup is needed, no choice
is asked of the user, and nothing the user wrote can be lost. Stored
vectors are kept when they can still be read, so a rebuild does not always
mean embedding everything again.

**Where failures are written.**
- **Error Log:** failures and automatic repairs of indexing and recall:
  damage found, rebuild started or failed, a chat that could not be
  indexed, meaning search that could not be prepared, and recall failing
  for a message.
- **Provider Failure Log:** unchanged. Indexing and recall make no provider
  requests of their own. Recalled text travels inside the ordinary chat
  request; if that request fails it is logged exactly as today, under the
  chat's own function name. The optional AI worker, when it is built, logs
  its failures under a function name of its own.
- **Never logged:** the text of any message or recalled conversation.
- A search time limit is not an error. It is shown in the relevant test,
  status, or "what was sent" view instead.

**Error Log wording (Owner, 2026-11-24, built in Step 11).** Templates,
filled only with what the code actually knows; the technical cause includes
the exception type and its details. No message or conversation text is
written. A failure that goes on is written once, not on every retry; a
permanently failed passage is recorded as permanent.

| When | Error Log entry |
|---|---|
| Damage detected | Conversation index database problem detected: {technical cause}. Automatic recovery started. |
| Rebuild completed | Conversation index rebuild completed. {number} conversations queued for indexing. {number} existing vectors retained. (When vectors could not be kept, the entry says why and that they are made again.) |
| Rebuild failed | Conversation index rebuild failed: {technical cause}. Indexing was turned off. Retry: turn indexing on again in RAG active memory settings. |
| A chat could not be indexed | Conversation indexing failed for chat {identifier}: {technical cause}. Index remains incomplete. Retry: {actual retry condition}. |
| Vectors could not be made | Conversation search vector preparation failed for {number} passages: {technical cause}. Effect: {actual impact}. Retry: {actual retry condition or permanent-failure status}. |
| Model unavailable | Conversation indexing paused because the on-device search model is unavailable: {technical cause}. Indexing will resume when the model is available. |
| Recall fails for one message (Step 16) | Conversation Recall could not check earlier messages for this turn: (technical cause). The message was sent normally without recalled conversation. |

Damage is only a corrupt or unreadable index file, or an index whose key is
gone. A key store that cannot be read now, a locked or busy database, a full
disk or a file that cannot be opened for now are passing failures and never
lead to a rebuild. Recovery keeps every vector it can still read, sets the
damaged file aside, and removes it only after the new index passes its check.
The crash-triggered startup check is not extended to the index.

**What produces a notice.**

| Situation | Notice behavior |
|---|---|
| Automatic rebuild, retry, or missing-vector repair succeeds or is still progressing | No snack bar or dialog |
| One message is sent without recall | One plain notice for the affected turn or ongoing condition; the "what was sent" view also records it |
| The model is missing, removed, or unusable | Notice when the user tries the setting, or once when an installed feature becomes unavailable; include a direct model-settings action |
| Indexing or recall is turned off because recovery failed | Persistent notice with a direct recovery/settings action |
| The user runs Check Database Integrity | Its result reports healthy, rebuilding, or stopped, because the user requested the check |
| Android pauses background indexing until the app is reopened | The indexing notification/status explains that opening the app continues it; this is not logged as damage |

The exact snackbar, dialog, notification, and button wording below remains
subject to the owner's approval before Step 11 or Step 13 builds it. A
notice is shown once per unchanged condition, not once per message.

**Check Database Integrity wording (Owner, 2026-11-24, built in Step 11):**

| Part | Wording |
|---|---|
| Hint under the button, replacing today's | Checks the memory, lorebook, user image, and conversation index databases for problems. Saved chats are not checked. |
| Name in the report | Conversation Index Database |
| Healthy | No problems found. |
| Could not be checked | Could not be checked. Follow with the known plain-language cause, an appropriate retry action, and a reference to the error log. Unknown causes are not guessed. The exception and cause chain are logged, never inserted into the report |
| Automatic repair running | The conversation index is being rebuilt. |
| Repair failed | The conversation index could not be rebuilt. Indexing is off. Open RAG active memory settings and turn indexing on again to retry. |

Result messages use sentence case after the title-case name. The index uses
its automatic rebuild, never the other databases' Repair and Revert
controls; an index that was never made has nothing to damage and reports no
problems.

The former rule that every index error raises a persistent snack bar is
superseded by the owner's 2026-11-12 decision above. Step 11 may create a
shared snack-bar style if its approved notices need one; it does not create
or show snackbars for log-only automatic repairs.

## G.13b Plain wording, approved Step 2 messages, and later-step proposals

**Owner (2026-11-12):** wording must be plain English, direct, and
understandable. When something cannot happen, the user is told why it
cannot happen and what they can do to fix it.

Every user-facing failure message follows this order:

1. what did not work;
2. what changed for this message or feature;
3. what is safe and unaffected;
4. the exact action the user can take, when one exists;
5. when no action is needed, say that instead of inventing one.

Additional rules:
- Planning words never reach the user on a screen or in a user-readable
  log when they obscure the operation. Embedding, embedding model and embedding
  index are explicitly allowed for accurate diagnostics. Prefer context-specific
  language over obsolete implementation shorthand.
- Raw exception text is never placed in a snackbar, dialog, notification,
  or inline setting message. It goes in the Error Log. User-facing text
  uses a plain cause the code can actually distinguish.
- "See the Error Log" may be offered for technical detail, but is never the
  only recovery instruction.
- A button opens the exact screen that can help. Do not say "check your
  settings" when the app can take the user there.
- Identical ongoing failures are not repeated every turn. Per-turn effects
  remain visible in the "what was sent" record.

**Owner (2026-11-24):** titles, labels, headings and buttons use title case;
sentences, statuses and Error Log entries use sentence case. No reassurance
such as "Your conversations are safe and unchanged" is added where indexing
could not have changed them. Conversation Recall is not named as an active
feature before Step 16. Each new ongoing failure gets one notice, shown when
the app next becomes active if it arose in the background; closing it keeps
the condition, which is cleared only once recovery is verified; an automatic
repair that succeeds has no pop-up.

**User-facing wording (approved rows are marked; the rest remain proposals):**

| Situation | Proposed wording and actions |
|---|---|
| Turning on Index Conversations with no model installed | **Approved (Owner, 2026-11-24, built in Step 11):** **Title:** On-Device Search Model Needed. **Text:** Indexing conversations requires an on-device search model. Install one in AI settings > embedding model, then turn indexing on. **Buttons:** Close, Open Model Settings (opens Embedding Model). The switch stays off. The wording for Conversation Recall is proposed with Step 16 |
| The installed model was removed or no longer loads, with Index Conversations on | **Approved (Owner, 2026-11-24, built in Step 11):** **Title:** Indexing Paused. **Removed:** Indexing has paused because the on-device search model is no longer installed. Reinstall it in AI settings > embedding model. Indexing will resume automatically when the model is available. **Cannot load:** Indexing has paused because the installed on-device search model could not be loaded. Reinstall it in AI settings > embedding model. Indexing will resume when the model can be loaded. **Buttons:** Close, Open Model Settings. The setting stays on, finished work is kept, and indexing resumes by itself |
| Recall fails for one turn and no user action is available | Conversation Recall could not check earlier messages this time. Your message was sent normally without recalled conversation. No action is needed unless this keeps happening. |
| Recall fails for one turn and the code knows an action | Conversation Recall could not check earlier messages because (plain cause). Your message was sent normally without recalled conversation. (Specific action.) |
| The index cannot be rebuilt | **Approved (Owner, 2026-11-24, built in Step 11):** **Title:** Indexing Stopped. **Text:** The conversation index could not be rebuilt, so indexing was turned off. Open RAG active memory settings and turn indexing on again to retry. **Buttons:** Close, Open RAG Active Memory Settings. Turning Index Conversations on again starts a new recovery attempt |
| Recall reaches its sending-time allowance | **What was sent:** Conversation Recall did not finish in time, so this message was sent normally without recalled conversation. |
| Android pauses background indexing until the app is reopened | **Notification/status:** Background indexing is paused. Open the app to continue. Finished work was saved. |
| Long-message section search reaches its allowance | **Approved Step 2:** Time limit reached while searching your message in sections. The full-message search still ran, but only {checked} of {total} additional sections were checked. |
| The embedding model cannot process one section | **Approved Step 2:** The embedding model couldn't process one additional section. That section was skipped, but the full-message search still ran. Error: {cause} |

**Approved Step 2 wording and implemented locations:**

| Situation | Final approved wording / location |
|---|---|
| Section match | Match found while searching section {number} of {total}, starting with “{first few words}…” — Memory Search Test result card |
| Section cap | Section search limit reached. Checked {checked} of {total} additional sections. The full-message search still ran. — Memory Search Test |
| Section timing | Additional section search: {checked} sections in {ms} ms. — Memory Search Test; Android plurals use “1 section” |
| Index load failure | Couldn't load the embedding index. Used keyword search instead. Error: {cause} — Librarian, Memory Debug Log |
| Model unavailable | Keyword search only (embedding model unavailable) — Enforcer summary fragment; punctuation belongs to the containing sentence |
| Live incomplete index | {missing} of {total} eligible memories aren't ready for embedding search. They were searched by keyword instead. — Librarian, Memory Debug Log |
| Preparation suffix | The app is preparing them in the background. — append only when a preparation pass is running or scheduled |
| Live query failure | Memory search could not read this message with the on-device model, so it searched by keywords only. Cause: {cause}. — Librarian |
| Background query failure | Memory Assistant could not process its background search with the on-device model, so it searched by keywords only. Cause: {cause}. — Librarian reconciliation |

The repair aggregates multiple section failures with section numbers and causes,
separates failed processing from timeout/cap, and uses context-specific Memory
Assistant complete-set fallback wording. The complete-set Assistant search is
unchanged. Diagnostics do not claim that an unsearched section failed relevance.
Near-miss labels explain the existing gate, similarity and meaningful-word rules;
they do not change those rules. Physical-phone timing remains pending;
the emulator measurements are recorded in `memory baseline.md`.

**Step 11 corrective wording (Owner, 2026-11-24).** The owner authorized the
failure wording and diagnostics in this correction without further wording
questions. The two existing Librarian **Memory Debug Log** entries become:
- self-check: The on-device embedding model failed its self-check.
  Saved-memory search is using keywords only. Model: {identifier}. Cause: {details}.
- model load: The on-device embedding model could not be loaded.
  Saved-memory search is using keywords only. Model: {identifier}. Cause: {details}.

Model failure entries include exception types, causes, and any related cleanup
failures. A self-check that throws is reported as a self-check failure, and the
unpublished model is closed. If the model was already published and only
background reread scheduling fails, the diagnostic identifies that preparation
failure and states that meaning search remains available. These entries remain under the existing memory diagnostics switch;
they are not messages sent to the companion and do not announce later recall
features as active.

The current settings screen uses a separate inline stopped status:
"The conversation index could not be rebuilt. Turn indexing on again to retry."
A notice elsewhere still includes the direct settings action. An unreadable
count says "Conversation index count is unavailable. Reopen this screen to
retry." It never becomes zero or a stale count.

A failed user-requested deletion shows a persistent Material snackbar with
**Okay**: "Could not finish deleting the conversation index." Then the known
plain cause, "Indexing is off.", the relevant recovery action, and "Try deleting
the index again. Technical details are available in the error log." The toggle
and delete button wait for the outstanding deletion to finish. The count is
read again regardless of the result. Verified deletion clears the obsolete failed
or pending rebuild condition; a failed deletion does not clear it. An incomplete cleanup can coexist with a
real zero count if the index data was deleted but recovery files remain.

Plain causes distinguish a busy database, full device storage, storage I/O,
an inaccessible encryption key, denied access, a read-only database, and a
file that could not be opened. Full storage asks the user to free space; key
access asks them to reopen the app. A key-access failure alone does not prove
that the device is locked. Other failures offer
retry. The app does not infer corruption, permanent key loss, or a specific
cause from an unclassified failure or arbitrary words in an exception.

Additional **Error Log** entries identify the failed operation (integrity
check, count read, index upkeep, vector-worker startup, or deletion), its
technical cause, actual effect, and retry condition. Deletion entries name the
failed step: index data, damaged index files, or recovery files. Related
exceptions are retained with cycle protection and a bounded diagnostic chain.
Read/startup failures are deduplicated per operation and cause until that
operation succeeds; each explicitly requested deletion failure is reported.
An unreadable waiting-passage count is logged as unknown, never zero. No chat
content is added to logs, no provider logs change, and automatic repairs do not
acquire pop-ups.

Before Step 11, the builder lists every failure cause the code can
distinguish and maps each one to: automatic repair with no notice; affected
turn with a one-time notice; or stopped/actionable feature with a recovery
action. A builder never fills a user-facing "(plain cause)" or "(specific
action)" with a guess.

## G.14 Cost, speed, storage, privacy (Proposed)

- **Tokens.** A recalled exchange is sent once more in that request. Its
  own size limit bounds the cost. With the starting values, up to 3 earlier
  exchanges can be added to every message. The size limit is not set yet
  (G.8b). Until it is, the cost per message is not known and is not
  described as small.
- **Speed.** Indexing runs in the background and must not slow typing,
  scrolling, or sending. Search time is added to sending, and is measured
  on the owner's Pixel 8. No public figure exists for this model on a
  phone's ordinary processor.
- **Battery.** Indexing existing chats is heavy work. **Owner
  (2026-11-11):** it runs in the background, the user never waits for it,
  and it may keep running after the app is closed. On Android that
  requires a visible notification while it runs. It is mostly a one-time
  job, repeated only after a large restore or a change of embedding model.
  **Owner (2026-11-11):** indexing does not pause for battery saver. Some
  phones are always in battery saver.
- **Storage.** A 256-number vector is about 1 KB per entry.
- **Privacy.** Three separate things, stated separately:
  - **Indexing is entirely on the phone.** The index is encrypted like the
    chat search database, and building it sends nothing anywhere.
  - **Recall sends text to the chat AI.** When recall is on,
    the recalled exchanges are part of the ordinary request to whichever
    AI service that chat uses. For a summarized chat this means original
    wording is sent that the summary alone would not have sent. Any
    wording shown to the user must say this plainly.
  - **The optional AI worker sends conversation text to its model,** and
    only when the user has turned it on.
- **Backups.** **Owner (2026-11-11):** backups do not carry the index. It is
  rebuilt from the chats after a restore.

## G.15 Testing and measurement

Correctness tests are written with the component they test, in the same
pull request. The samples for the quality tests are built first, so no
Step's code is judged by samples written after it: saved-memory samples
in Step 0, and conversation samples in Step 6.

**Three kinds of test.**

- **Correctness tests** come with every Step. They either pass or the
  Step is not finished. Examples: every character of a message lands in a
  passage; an edit replaces only what it touched; a deleted message leaves
  nothing behind; a hidden message is never recalled and its words are in
  no neighbour's search text.
- **Quality tests** use written sample conversations with known right
  answers. They are scored, and the scores are tracked across measurement Steps
  so a change that makes recall worse is seen at once.
- **Measurements** set the numbers research does not give, with the real
  embedding model: the passage target size, the gate, the blend weights,
  how many exchanges to recall, how much less an earlier message counts,
  and speed on the phone.

**What every quality test checks, at three points:**
1. **What was found:** the right exchange is in retrieval's list.
2. **What was left out:** nothing unrelated is in it.
3. **What was sent:** the text added to the request is the right exchange,
   in its original words, correctly labelled, within the limits.

Step 14 measures retrieval results; Step 15 displays and measures the
retrieval results (points 1 and 2). Point 3 and its assembly-specific
reasons are measured and added to the inspector only in Step 16, when
context assembly exists. Do not claim a real "sent" result in Step 15.

**The situations the sample conversations must cover:**

| Situation | What must happen |
|---|---|
| An exact detail ("Which model did we choose?") | The original decision is recalled |
| A follow-up ("Why did we choose that?") | The same discussion is recalled, through the chosen context assistance (G.9b) |
| A time reference ("What did we change last week?") | Exchanges from that period are favoured |
| A summarized or compacted chat | The original exchange is recalled although the AI otherwise has only the summary |
| Nothing relevant | Nothing is added |
| A long message with one small important detail | The exchange about that detail is recalled |
| A long dictated message with no periods and one small important detail | The exchange or memory about that detail is found |
| A short sentence that shares one accidental word with an unrelated memory | That memory is not sent |
| A hidden message | It is never recalled |
| An edited message | The new text is recalled, never the old |
| A deleted message | It is never recalled |
| A chat that is about one subject throughout | Only exchanges closer than the general run of the chat are recalled |
| A roleplay chat and a companion chat | Each recalls only its own conversation |
| A very long exchange | An excerpt is sent, marked as one, and nothing is cut silently |
| More matches than fit | The most relevant are sent, and the rest are reported as left out |
| A decision made early and changed later, asked about in words that match only one | How often both are recalled is measured and reported. The result decides whether G.6 retrieval stage 9 is built |
| The same message sent down both request paths | The same text is added by both |
| Retrieval runs out of time | The message is sent as today, and recall is shown as skipped |
| A passage with no vector yet | It is not recalled, and is shown as waiting for its vector |
| No embedding model installed | Index Conversations and Conversation Recall cannot be turned on, and the user is told why |
| The model is changed after indexing | When the new model counts and limits text the same way, nothing is re-cut and recall resumes once the new vectors are made. When it does not, the affected messages are re-cut first (G.5b cutter rule 4). Both cases are tested |

**How quality is judged.** No pass mark is invented in advance. The first
run at each measurement Step sets the baseline. After that, a change must not lower
"found" or raise "wrongly added" without the owner being told and
agreeing. The owner ranks a wrong memory as worse than a missed one, so
"wrongly added" is watched first.

**These are engineering numbers.** The owner is not asked to choose them.
Each has a starting value from measurement and is adjustable afterwards.

## G.16 Build order and work orders

The design covers everything. Building is split into numbered Steps small
enough to hand to an agent: "build Step 7". **The order, status, grouping
into stages, and approval gates of every Step are in M.2.** A stage is
normally one pull request with its own tests, and each leaves the app
working.

**How to use this section.** Give the agent this plan and the stage or Step.
The agent reads Part M and G.19 first, then the sections the Step names,
then the code files the Step names. It builds only what was assigned.

**Difficulty** is marked so the owner can choose which model does a Step:
- **Routine:** self-contained, little risk. A lighter model can do it.
- **Careful:** touches shared code or has several cases to get right.
- **Hard:** many moving parts, or touches the chat screen. Use the
  strongest model available, and expect more than one review round.

### Steps, difficulty, and model

The former strict execution table is replaced by M.2 (2026-11-16). Step 0
is complete: do not rebuild it, regenerate its vectors, or rewrite its
expected answers. Its authoritative results are in
[memory baseline](<memory baseline.md>). Steps 1 and 2 were implemented in PRs #199 and #200; the first Step 2 repair is merged in #201, whose checks incorrectly accepted worse native accuracy. The follow-up, merged in #202, restores the original quality gate and passes real Android validation at 69/6/17 with the original runtime/model; the original quality target remains binding. Step 3 was merged in #203; its repair, merged in #205, corrects how its helper relates to the enforcer's selection and applies the owner's +3 rule (results in `memory baseline.md`).

| Step | What is built | Difficulty | Model |
|---:|---|---|---|
| 0 | Saved-memory test set with the real model's vectors | Routine with written instructions | Lighter |
| 1 | Token counting, one embedding queue, and the 2,048-token stored-text limit | Careful | Middle |
| 2 | Long and dictated messages find their saved memories | Hard | Strongest |
| 3 | Search primitive for previous user messages | Careful | Middle |
| 4a | Context assistance with Off and Code-based; the +3 top-match rule | Careful | Strongest |
| 4b | API AI context helper with its own model selector | Hard | Strongest |
| 5 | Memory word count and length limit | Careful | Middle |
| 6 | Conversation-recall test chats with known answers | Routine with written instructions | Lighter |
| 7 | Passage cutter | Routine | Lighter |
| 8 | Passage stores and per-message updating | Hard | Strongest |
| 9 | Background embedding of passages | Hard | Strongest |
| 10 | Keep the index synchronized with chats | Hard | Strongest |
| 11 | Settings, status, deletion, database check, and errors | Careful | Middle |
| 12 | Progress icon and pop-up with pause/resume | Careful | Middle |
| 13 | Indexing after the app closes | Hard | Strongest |
| 14 | Retrieval | Hard | Strongest |
| 15 | Inspector and measured starting values | Careful | Middle |
| 16 | Context assembly in real requests | Hard | Strongest |

**Difficulty and model labels.** Lighter means a small, inexpensive model is
enough. Middle means a mid-range model. Strongest means the most capable
available model and likely more than one review round.

**Result destinations are fixed:**

| Work | Authoritative record |
|---|---|
| Step 0 and all saved-memory benchmark comparisons in Steps 1–4 | `memory baseline.md` |
| Exact per-sample saved-memory acceptance state | `app/src/test/resources/recall_test_set/baseline.json`; change only for a verified improvement, never to hide a regression |
| Step 6 sample counts | Step 6 pull-request description and the Build record only; no search has run yet |
| Conversation-recall quality results from Steps 14–16 | `conversation_recall_baseline.md`, created by Step 14 and updated by Steps 15–16 |
| Every completed Step | One concise line in the Build record at the end of this plan |

Do not put measurement tables into the work-order text. Do not copy
`memory baseline.md` results into this plan. A builder reads the relevant
record before changing the code and writes new results back to that same
record.

**Phase names used in the design sections.** The index-foundation phase is
Steps 7–13. The retrieval-measurement phase is Steps 14–15. The
context-assembly phase is Step 16. These names describe groups; they do not
change the numbered execution order.

For the conversation index, nothing changes for any user until Step 11.
Steps 7–10 add code that stays switched off because Index Conversations
starts off and cannot be turned on until Step 11.

**The embedding model is required (Owner, 2026-11-12; G30).** Index
Conversations and Conversation Recall require an installed embedding
model. Keywords are still used together with meaning (G.6), but
keyword-only conversation recall is not built. The no-model and
model-removed wording is approved before Step 11 (G31).

**Why the first user-visible fix is Step 2.** The owner chose the
long-message saved-memory fix first. Step 0 had to establish the benchmark
and is complete. Step 1 must put every embedding request through one queue
before Step 2 runs the model several times for one message. This preserves
the owner's priority without skipping either safety prerequisite.

**Builder reading rule.** Follow the build order in M.2. Each checklist highlights the assigned Step; every instruction and test in the rest of that Step and its referenced design sections still applies. A checklist never permits skipping details or prerequisites. Stop before dependent work if an approval is missing. Do not change another Step, the design, or existing decisions to make the assigned task easier.

### Step 0: saved-memory test set — COMPLETE (Routine with these instructions)

**Completed result.** This work order is retained as the audit record for
Step 0. Do not run it again unless the owner explicitly reopens Step 0.
The authoritative measurements are in `memory baseline.md`.

**Goal.** Sample memories and messages with known right answers, run in
the normal unit tests with the real embedding model's numbers. Steps 2–4 are judged by this set, so it is written before any of their
code. Conversation recall has its own set, Step 6.

**Rules for the completed Step 0 audit record.**
- Add new files only. Change no app code and no existing test.
- **Step 0 results go in `memory baseline.md`.** This plan is changed
  only to point its Step 0 results instructions to that file.
- Create no Markdown file.
- Step 0 adds test code, so it follows the code workflow in
  `CLAUDE.md` like any other Step.
- Write every expected answer **before** running anything. Never change an
  expected answer to make a result look better. If an expected answer
  turns out to be wrong, say so in the pull request description and leave
  the decision to the owner.
- All sample content is invented. Use a made-up person. Use nothing from
  any real conversation.
- Do not commit the model files. Download them to a temporary folder.
- If any part below cannot be done, stop and say which one. Never make up
  numbers.

**Files to create.**

| File | What it is |
|---|---|
| `app/src/test/resources/recall_test_set/cases.json` | The sample memories and messages, written by hand |
| `app/src/test/resources/recall_test_set/generate_vectors.py` | The script that turns each text into the real model's numbers |
| `app/src/test/resources/recall_test_set/vectors.json` | The script's output |
| `app/src/test/resources/recall_test_set/baseline.json` | How today's search scores on the set |
| `app/src/test/java/org/teslasoft/assistant/preferences/memory/librarian/RecallTestSetTest.kt` | The unit test that runs the set |

**Part 1: write `cases.json`.**

Memories: at least 30, each with an `id`, `content`, and `tags`. One to
three sentences each. Spread them over these subjects: a pet, health, work,
family, home and moving, hobbies, food, and plans with dates. Include
pairs that share one ordinary word but are about different things, such
as a move planned for spring and nothing else about spring.

Messages: each with an `id`, a `kind`, its `text`, `expect` (memory ids
that should be sent), and `must_not` (memory ids that must not be sent).

| Kind | At least | What it is |
|---|---|---|
| `short` | 10 | One short message about one memory |
| `long_typed` | 8 | 150 to 300 words about several things, with normal punctuation, and one small detail that belongs to one memory |
| `long_dictated` | 8 | 150 to 400 words with **no periods at all**, as speech-to-text writes them, with one small detail that belongs to one memory |
| `dictated_bad_periods` | 5 | The same, with periods in the middle of thoughts |
| `very_long` | 4 | More than 450 words, with the detail placed after the 400th word |
| `accidental_word` | 8 | A short message that shares exactly one ordinary word with an unrelated memory. `expect` is empty and that memory is in `must_not` |
| `nothing_relevant` | 8 | About something no memory covers. `expect` is empty |
| `two_subjects` | 5 | About two memories at once. Both are in `expect` |

Conversations: at least 9, each a list of user messages in order. Each
turn has its own `expect` and `must_not`.
- At least 6 have three turns. The first names a subject. The second is a
  follow-up that does not name it ("What should I feed her?"). The third
  changes to a subject that has its own memory. The third turn's
  `must_not` holds the first subject's memory, so a memory carried into a
  new subject is counted.
- At least 3 have four turns: a subject, then two follow-ups in a row that
  do not name it, then a change of subject.

**Part 2: write `generate_vectors.py`.** It must do exactly what the app
does (`OnnxEmbeddingModel.kt`, `EmbeddingModels.kt`,
`RetrievalDocument.kt`). Read those three files first.

1. Download `onnx/model_q4.onnx`, `onnx/model_q4.onnx_data`, and
   `tokenizer.json` from the address in `EmbeddingModels.kt` into a
   temporary folder.
2. Use Python with the `onnxruntime` and `tokenizers` packages.
3. For a memory, the text is its content, then a new line, then its tags
   joined with a comma and a space (`RetrievalDocument.semanticDocument`).
   Put `title: none | text: ` in front of it.
4. For a message, put `task: search result | query: ` in front of it.
5. Turn the text into token ids the way `HfTokenizer.encode` does. The
   tokenizer puts one marker at the start and one at the end. Those two
   markers are always kept. When the text is too long, it is the text's
   own tokens that are cut, to 510, so the whole input is never more than
   512 tokens and always ends with the end marker. Do not simply keep the
   first 512 ids: that drops the end marker on long text.
6. Run the model with `input_ids` and an `attention_mask` of ones. Take
   the output named `sentence_embedding`.
7. Keep its first 256 numbers, then scale them so their length is 1.
8. Write every vector to `vectors.json`, keyed by the memory or message
   id, rounded to 5 decimal places.

**Part 3: prove the script is right before using its output.** The script
must print these checks and stop if any is off by more than 0.01. It must
also check that every input, long ones included, begins with the start
marker, ends with the end marker, and holds at most 512 ids. The
reference values were measured on 2026-11-12 with this recipe:

| Check | Value |
|---|---|
| "I love drinking coffee in the morning." against "My favorite morning drink is coffee." (both as stored text) | 0.9304 |
| The first sentence against "The spaceship landed on the distant planet." (both as stored text) | 0.4371 |
| The message "What should I feed Pepper?" against the stored text "Pepper is the user's cat. She has kidney disease and eats a prescription diet." | 0.6208 |

**Part 4: write `RecallTestSetTest.kt`.**
- Read the JSON files with `org.json`, which the unit tests already have.
- For each message, call `LiveRetrieval.search` with the default
  `LiveRetrieval.Params()`, the message's vector, every memory as a
  candidate with its vector and tags, and a limit of 5. Read
  `LiveRetrievalTest.kt` to see how candidates are made. "Sent" means the
  memory ids in the result's ranked list.
- For conversations, search each turn on its own, as the app does today.
- The test prints, for every message and every conversation turn, the
  expected ids that were sent and every other id that was sent, whether
  or not it is listed in `must_not`.

**Part 5: write `baseline.json`.** It records today's result for every
sample one by one, not only totals. For each message id and each
conversation turn it holds two lists: `found` (expected ids that were
sent) and `wrongly_sent` (every other id that was sent). Fill it from
the test's printed output, without changing any expected answer. Until
`baseline.json` exists, the test prints the results and fails with the
message that the baseline is not recorded yet.

**Part 6: what makes the test pass or fail.** Each sample is checked on
its own. Totals alone can hide one sample getting worse while another
gets better.
- The test fails if, for any sample, an id in the baseline's `found` list
  is no longer sent.
- The test fails if, for any sample, an id it did not expect is sent that
  is not in the baseline's `wrongly_sent` list.
- The failure message names each sample and each memory id that got
  worse.
- The test does not require every expected memory to be found. Today's
  search is known to miss the long messages. Those misses are what the
  later Steps are measured against.
- The test also prints the totals for the whole set and for each kind:
  expected memories found, expected memories missed, and `must_not`
  memories sent.
- A later Step that improves a sample updates that sample's entry in
  `baseline.json` in its own pull request and lists every changed entry
  in the pull request description. An entry is never changed to hide a
  sample that got worse.

**Done when:**
- the script's checks pass;
- `./gradlew testDebugUnitTest` passes with the new test included;
- the pull request description lists the totals for each kind, and any
  expected answer the builder now doubts;
- the totals and per-sample results are recorded in `memory baseline.md`.

**Baseline.** See [memory baseline](<memory baseline.md>) for Step 0
measurements and verification results.

### Step 1: token counting, one embedding queue, and the reading limit (Careful)

**Agent checklist for this step**
- **Prerequisites and approval:** Step 0 complete; see G33. This step adds no screen or other visible control.
- **Change:** Count tokens including prefixes and markers without cutting text; route **every** embedding call (including the unlocked saved-memory search call) through a single priority queue; raise the stored-memory reading limit to 2,048 tokens.
- **Preserve:** A live request outranks queued background work and waits for at most one already-running read. Keep the live whole-message pass at 512 tokens. Preserve vectors and search results for memories that fitted inside the old 512 limit; regenerate only over-limit ones.
- **Verify and report:** Existing tests unchanged; token counts match real encoding; priority/stop/retry behavior is tested; long memories are fully read. Report measured wait and read times, and distinguish build-environment measurements from phone measurements still needed.

- **Goal:** the app can ask how many tokens a text needs, and every use of
  the embedding model goes through one queue where a live chat request
  goes first (G.5a rules 2 and 6).
- **Read:** G.5a rules 2 and 6; G.2's last point;
  `memory/librarian/HfTokenizer.kt`, `OnnxEmbeddingModel.kt`,
  `EmbeddingModel.kt`, `Librarian.kt`.
- **Build:** a counting function on the tokenizer and the model that
  counts the instruction prefix and special tokens and never cuts
  anything off. One queue for all embedding work with two priorities. Move
  the unlocked call in `Librarian.searchInternal` onto it. Raise the
  reading limit for stored text to 2,048 tokens (G33), and read again only
  the saved memories that were longer than the old limit, so no other
  memory loses its place in meaning search while that happens.
- **The two limits.** Stored text (a saved memory) is read up to 2,048
  tokens. The message being sent keeps today's 512 for its whole-message
  pass, because that pass happens while the user waits.
- **Must not change:** the search result or the stored vector of any
  memory that fitted inside the old 512 limit, or what the tokenizer
  returns for text that fits. Only memories longer than the old limit are
  read again, and only their results may change.
- **The wait behind a running read (G.5a rule 6).** A live message can
  wait for one background read that has already started. The builder
  measures that wait for the longest text, reports it, and, where the
  model's runtime allows a read to be stopped, stops the background read
  when a live message arrives and does it again afterwards.
- **How a read might be stopped.** The ONNX Runtime library the app uses
  (1.20.0) offers `OrtSession.RunOptions.setTerminate(true)`; the app does
  not use it today. The builder checks that it stops a read safely with
  this app's model and session before relying on it. If it does not, the
  live message waits for the running read, and the pull request says so.
  A stopped background job goes back into the queue and is done again
  later: no finished work is lost and the queue is never left broken.
- **Measurements.** The builder records in `memory baseline.md` the time
  to read a full-length
  text, how long a live message waits behind a running read, and, where
  stopping works, how long a stop takes. Each number is labelled either as
  measured by the automated tests in the build environment or as one that
  must be measured on the owner's Pixel 8.
- **No screen.** Step 1 adds no screen or visible control without the
  owner's approval.
- **Done when:** existing tests pass unchanged; new tests show the count
  matches the real encoding, that a live request is served before every
  waiting background job and waits for at most one running read, and that
  a memory longer than 512 tokens is now read in full. The time and memory use of reading a 2,048-token memory on a
  phone cannot be measured in the build environment; the pull request
  says so plainly, and the owner is told how to see it on the phone.

### Step 2: long messages in saved-memory search (Hard)

**Status:** Implemented in #200 with an initial repair in #201. The follow-up on `repair/step2-contract-audit` passes the restored original per-message quality gate on real Android 1.20: 69 correct / 6 missed / 17 incorrect, retaining all originally required correct matches. No acceptance requirement or gold answer was weakened. All 85 Android tests passed at `4d4c646`; the repair is merged in #202. Runtime/model stay unchanged, and rejected runtime/precision experiments remain documented in `memory baseline.md`. Physical-phone timing is still unmeasured.

**Repair scope after later-step review:** Steps 3–4 add separate previous-message searches; Steps 14–15 build and measure conversation-passage recall. Neither is a later work order to recalibrate this existing saved-memory gate. The existing gate was already built in #180. The current repair may therefore adjust its existing default calibration while preserving all original correct matches and rejecting newly incorrect matches per sample. Android 1.20, the original q4 model, vector size, keyword rule, section size/cap/0.48 threshold and gold answers remain fixed. No later Step is started here.

**Agent checklist for this step**
- **Prerequisites:** Steps 0 and 1. **Approval gate:** Resolved by the owner; final wording is in G.13b. Routine repair corrections are authorized.
- **Change:** G.10's bounded overlapping search pieces for typed and dictated messages, using the shared embedding queue and a minimal hookup. Whole-message search still runs first; each section must pass the relevance gate, similarity ≥0.48 and meaningful shared-word overlap.
- **Preserve:** Every originally correct whole-message match and the relative order of retained matches; Memory Assistant search, lorebooks, and current sending when no piece can qualify. The existing whole-message defaults may be recalibrated under the repair scope below. Do not add a paid rewrite or trust periods as sentence boundaries.
- **Verify and report:** Step 0's per-sample quality improves for long messages **without increased wrong memories**; existing tests pass; report time allowance and any missing on-phone performance proof.

- **Goal:** a small detail in a long message finds its saved memory,
  whether the message was typed or dictated, without more wrong memories
  being sent (G.10).
- **Needs first:** Step 0 and Step 1.
- **Approved:** Memory Search Test and saved-memory diagnostics in G.13b; G26 is resolved for Step 2. Model-load notices remain Step 11.
- **Read:** G.2, G.10; `LiveRetrieval.kt`, `Librarian.kt`,
  `SearchTestReport.kt`.
- **Build:** G.10, in its own file, with a small hookup. Every use of the
  embedding model goes through Step 1's queue.
- **Must not change:** originally correct whole-message matches or the relative order of retained matches; the Memory Assistant's own search; the lorebook. Removing incorrect matches through the existing default calibration is permitted only under the unchanged per-message quality gate.
- **Results:** record the before/after totals and every changed sample in
  `memory baseline.md`. Update `baseline.json` only for verified
  improvements, never to permit a regression.
- **Done when:** on Step 0's set, more right memories are found in long
  messages, dictated ones included, and the number of wrong memories sent
  has not risen; existing saved-memory tests pass unchanged; and timing, queue/cancellation behavior and verification limitations are
  reported accurately. The provisional allowance is cooperative, not a hard
  wall-clock guarantee. Measure real Android queue/model/total timing when
  hardware or an emulator is available; never substitute Python timings.

### Step 3: searching with the previous messages (Careful)

**Agent checklist for this step**
- **Prerequisite:** Step 0 test cases and baseline. No new UI or owner wording is required here.
- **Change only:** A standalone Kotlin search helper following G.9a, taking 0–3 previous **user** messages as an input. Search each independently and give newer matches priority; do not join messages.
- **Preserve:** Existing live search: this helper is **not connected to sending** in Step 3. Accept/reuse earlier-message vectors where available and respect changing memory/scope eligibility when the helper is later used. Composition-triggered preparation and app-process cache wiring belong to Step 4, not Step 3.
- **Verify and report:** Run Step 0 conversations at 0, 1, 2, and 3 previous messages. Record first and later follow-ups kept, carry-over after subject changes, and every unexpected memory sent.

- **Goal:** the method in G.9a, built on its own, so the fix for follow-up
  messages (Step 4) does not wait for the conversation index.
- **Read:** G.9a; `LiveRetrieval.kt`, `KeywordText.kt`.
- **Build:** plain Kotlin in its own file, connected to nothing yet, with
  no setting and nothing on any screen. It takes the number of previous
  messages as an input. How much less an earlier message counts is
  measured on Step 0's conversations.
- **Done when:** Step 0's conversations have been scored with the number
  of previous messages at 0, 1, 2, and 3, and for each `memory baseline.md`
  records:
  first follow-ups that kept their memory; second and later follow-ups in
  a row that kept it; memories of the old subject sent after the subject
  changed; and any other memory sent that the sample did not expect.

### Step 4a: context assistance, Off and Code-based (Careful)

**Agent checklist for this step**
- **Prerequisites:** the Step 3 repair (#205) and the carry-over
  evaluation (#206) are merged. **Approval gate:** the Step 4a questions in
  M.10, asked when the step starts (G38 is decided). G45 decides whether the
  frozen-path lorebook check (M.6) is included.
- **Change:** the context-assistance interface (G.9b) in its own files,
  with a small connection where a message is sent; Off and Code-based
  (G.9); the +3 top-match rule (G.10) in the saved-memory search, so the
  Memory Search Test shows the same result; and the app-wide setting (G38).
- **Preserve:** with Off, the request is the same as before Step 4a except
  for the +3 top-match rule. Code-based is exactly the evaluated
  `subject_anchor`; its numbers are not tuned. `PreviousMessageSearch` is
  not connected to sending. Search results are never cached.
- **Verify and report:** see below.

- **Goal:** a saved memory stays through follow-up messages and gives way
  when the subject changes (G.9).
- **Read:** G.9, G.9b, G.9c, G.10; `enforcer/Enforcer.kt`;
  `CarryOverFinalEvaluationTest.kt`; the injection record in
  `MemoryStore.kt`.
- **Build:**
  - The interface, Off and Code-based behind it. The Code-based subject is
    kept per chat in memory for the app process, keyed to the chat and the
    embedding model tag, dropped when the chat is deleted or the model
    changes. Carried memories are read again from the store at send.
  - How the subject is rebuilt after the app restarts is proposed to the
    owner, with its cost, as part of G38, and built only as approved.
  - The +3 top-match rule.
  - The setting, on a new **Memory Context Helper Settings** screen opened
    from the Memory Manager's Settings section (M.11), using the shared
    styles (`ui-style-guide.md`).
- **Tests:**
  - With the +3 top-match rule switched off inside the test, the app's
    Off and Code-based reproduce the recorded `none` and `subject_anchor`
    results of both carry-over sets exactly.
  - With the rule on, the combined results on both carry-over sets and
    Step 0 are recorded in `memory baseline.md`.
  - A deleted, disabled, edited or out-of-scope memory is never carried in
    its old form; switching chats never carries another chat's subject.
- **On Android:** leaving and re-entering a chat, switching chats, a
  restart, Maximum Memories 1 and 8, and lore overlap and memory space
  with carried memories. Send-time cost on a physical phone is reported;
  the owner confirms the device behavior.
- **Done when:** the tests above pass, Off sends what it sent before apart
  from the +3 top-match rule, and the results are recorded.

### Step 4b: API AI context helper (Hard)

**Agent checklist for this step**
- **Prerequisites:** Step 4a merged. **Approval gate:** the Step 4b questions in M.10, asked when the step starts (G39 is decided).
- **Change:** the API AI choice behind the Step 4a interface, with the
  **Memory Context Helper Tool API** screen in AI Settings (M.4): its own
  endpoint and model selection filled from the user's configured providers
  (never a built-in model list), every model parameter, and the same
  structure and saving behavior as Memory Scanner Tool API, reused through
  its shared components as the owner authorized; every other item exactly
  as approved in G39; the approved instruction, the output check, and the
  approved behavior on failure. The screen uses the shared styles
  (`ui-style-guide.md`).
- **Preserve:** the chat, Summarizer and embedding models and their
  settings are untouched; the user's message is unchanged; no request is
  made unless the user chose API AI; the helper uses only its own model
  from Memory Context Helper Tool API, never the chat's model (M.4 item 7);
  its conversation input has no fixed token cap and keeps whole exchanges
  within the selected model's context window (M.4 item 1); no privacy
  disclosure (M.4 item 6).
- **Verify and report:** unit tests with a stand-in provider for a valid
  query, discarded output, timeout, provider error and no network; the
  conversation input keeping whole exchanges within a model's context
  window, honoring Number of Past Messages to Send, and sending every
  requested exchange when no window is known; one
  scored run on the two frozen carry-over sets with the model and cost the
  owner approved, recorded in `memory baseline.md`; send-time delay on a
  physical phone. Local AI is not built, but nothing in the interface
  needs to change to add it.

### Step 5: memory word count and exact token capacity (Careful)

**Owner (2026-11-17):** the restriction is the selected embedding model's actual
reading capacity, not a word count. No 1,500-word or 1,250-word saving cap and
no word-limit warnings are built.

**Agent checklist for this step**
- **Prerequisite:** Step 1 complete. **Approval:** the corrected counter,
  meter, saving behavior and error below are decided; do not reopen them.
- **Change:** show the informational count exactly as "348 Words", alongside
  actual usage such as "1,042 / 2,048 Tokens" and a filling bar. Put the meter
  at the top while creating, editing or reading a memory; keep it visible in
  the expanded editor. Show it for each memory in the browser, Pending and
  Possible Match Review, including that screen's inline editor.
- **Authoritative calculation:** use the complete retrieval document from
  `RetrievalDocument.semanticDocument`: content, any extra searchable text and
  tags. Include the selected embedding model's document prefix and tokenizer
  special tokens, using its exact `countTokens()` and stored-text capacity.
  Titles, vibe and fields the embedding model does not read are not counted.
  The word counter counts only the content and never controls saving.
- **Live behavior:** calculate off the UI thread and update as relevant fields
  change. Cancel obsolete work and never display an older result as current.
  Refresh after a model change. The displayed usage and the Save gate use the
  same calculation; Save checks a fresh snapshot before any write. Editing
  predicts the exact stored document: the existing source-change rule clears
  a derived search hint when content or tags change. Reading an unchanged
  stored memory still counts its complete retained hint.
- **Saving:** permit a document that fits the actual token capacity, including
  one whose word count exceeds the old estimate. Block one that exceeds the
  token capacity, keeping every field and all entered text intact. Show only:
  **Memory Cannot Be Saved** / "This memory exceeds the embedding model's
  reading limit." / **Okay**. No advice or instructions are added.
- **Unavailable model:** never show a fabricated zero or estimate. Without an
  installed model the token figures are unavailable; preserve the existing
  ability to create memories without an embedding model. A tokenizer failure
  with an installed model must not bypass the Save check.
- **Preserve:** do not automatically alter, shorten, discard or migrate any
  existing memory text. Existing/imported text remains readable in full; the
  meter shows its actual usage. No separate legacy-memory warning scheme.
- **Memory Assistant:** tell it to split large subjects into separate proposed
  memories without losing information. Also enforce this in code before the
  proposed memories are stored: split oversized generated content losslessly
  against the same complete-input calculation. Preserve scope, Type, targets,
  tags, relationship hints and whole-conversation sources on the resulting
  proposals. Deduplicate final parts within the request as well as against
  stored memories, merging additional tags and relationship hints when they
  fit; preserve separate metadata groups if their union exceeds capacity.
  A proposal's parts must not be cut off by Maximum Suggestions.
  Do not alter the lorebook analysis mode or stored memories.
- **Read:** `ui-style-guide.md` (fields, counters, progress bars and dialogs),
  `ui-style-adoption.md`, the affected editor/reader layouts and code,
  `RetrievalDocument.kt`, `HfTokenizer.kt`, `EmbeddingModels.kt`,
  `OnnxEmbeddingModel.kt`, and the Memory Assistant's `archivist/` path.
- **Verify and report:** live updates to text and tags; metadata, prefixes and
  special tokens included; exact capacity accepted and overflow blocked; stale
  calculations discarded; model changes reflected; Save and display agree;
  all oversized proposal text and metadata preserved across parts. List the
  affected editors/readers in the PR. Run `testDebugUnitTest`, `assembleBeta`
  and `assembleDebugAndroidTest`; verify the Beta identity. Write evidence to
  `memory baseline.md`, the G.16 destination for saved-memory work.


### Step 6: the conversation test set (Routine with these instructions)

**Review additions for upcoming work.** Stage 6 remains completed. Add
invented cases alongside its frozen corpus before implementing the affected
behavior. Keep the original expected answers fixed. Write each new
expected answer before running retrieval or tuning it.

| Additional case | Expected result | Due |
|---|---|---|
| Browse an alternative reply without making it canonical | Only the canonical reply supports the indexed exchange | Before Steps 7–10 index eligibility work |
| Promote another reply, edit a message, or delete/hide it while work is running | Only current eligible wording survives use-time checks | Scanner 6A and recall Steps 8–10 |
| One long repetitive exchange and a short later correction | Candidate collection retains the later exchange despite the earlier exchange's many matching passages | Before Step 14 retrieval |
| Similar exchanges with opposite outcomes, such as waiting and receiving a reply | Preserve both eligible accounts when relevant and deliver in chronological order | Before Step 14 retrieval |
| Exact repeated exchanges with matching speaker order | Collapse identical text conservatively, retaining the later occurrence | Before Step 14 retrieval |
| Follow-up with and without a confident saved-memory subject in each assistance mode | Follow the approved G36 contract and clear stale exchange continuity on a subject change | Before Step 14 retrieval |



**Agent checklist for this step**
- **Prerequisites and approval:** Step 0 complete (done); position in M.2; no additional approval. Build invented test samples and validation, not the recall engine.
- **Change only:** Add `conversations.json` and a unit test that validates every situation and minimum below.
- **Preserve:** No application-code changes, existing-test changes, real conversation content, or changed expected answers. Do not create Markdown.
- **Verify and report:** Run the validation test; report each situation's sample count in the PR. Step 14 handles vectors and retrieval scoring later. Follow the code workflow in `CLAUDE.md`.

**Goal.** Sample chats with known right answers for conversation recall,
written before any index or retrieval code, so that code is judged by
samples that existed first. Step 0 covers saved memories only; it cannot
judge recall of earlier exchanges.

**Rules.** The same as Step 0: new files only, no app code changed, no
Markdown file created, all content invented, expected answers written
before anything is run and never changed to improve a result, and the
code workflow in `CLAUDE.md`.

**File to create:**
`app/src/test/resources/recall_test_set/conversations.json`, and a unit
test that only checks the file is well formed: every id is unique, every
expected id exists, every situation below has its minimum number of
samples, and each sample really has what its situation says: for
example, the word counts, no periods in a dictated message, the hidden,
edited, failed, or summarized message it is about, chats of more than 60
messages, and an expected answer that is empty where nothing is
relevant. No search is run in Step 6, because the index does not exist
yet. Step 14 adds the vectors and the scoring.

**What each chat holds.** An `id`, its scope (a companion, a project, a
roleplay world, or none), and its messages in order. Each message has a
permanent-style `id`, who wrote it (`user` or `ai`), its `text`, a
`time`, and its state: `visible` or `hidden` from the AI; `finished`,
`failed`, or `unfinished`. A message may carry an `edited_from` text, to
test that only the new text is found. A chat may carry `summarized_up_to`,
the id of the last message the AI now sees only as a summary.

**What each question holds.** The chat it is asked in, the user's new
message, `expect` (the ids of the user messages that open the exchanges
that should be recalled), and `must_not` (exchanges that must not be).

**Situations, with the least number of questions for each.** These are
the rows of the table in G.15 that concern recall:

| Situation | At least |
|---|---|
| An exact detail from far back in a long chat | 6 |
| A follow-up that does not name its subject | 4 |
| A time reference ("last week", "in March") | 4 |
| A chat with `summarized_up_to`, asking about something before that point | 5 |
| Nothing relevant in the chat | 6 |
| A long typed message with one small detail | 4 |
| A long dictated message with no periods | 4 |
| A hidden message that would otherwise be the best match | 4 |
| An edited message, asked about in the words of the old text | 3 |
| A failed or unfinished reply that would otherwise match | 3 |
| A chat about one subject throughout | 4 |
| A roleplay chat and a companion chat holding similar words | 4 |
| An exchange of more than 1,500 words | 3 |
| More matching exchanges than the limit of 3 | 3 |
| A decision made early and changed later | 4 |

At least 8 chats. At least 3 of them have more than 60 messages, so that
"far back" is real.

**Done when:** the file exists with the minimums met, the well-formed
test passes, and the pull request description lists the number of
questions for each situation.

### Step 7: the passage cutter (Routine)

**Agent checklist for this step**
- **Stage:** 7A (M.2, **Conversation index: Stages 7A and 7B**). G35, the full index design in G.5, G.5a, G.5b, and G.7, is approved.
- **Change only:** New plain-Kotlin cutter files and their tests. Input is one message, a target size, and a supplied size-measuring function; output is ordered original-text start/end positions and the cutter version.
- **Preserve:** Every original character exactly once, with no changes to chat text. Use paragraphs, lines, words, then characters when necessary; **never split at periods**. No storage, screen, embedding call, search text, or overlap in this step.
- **Verify:** Run every test under G.5b's “With the cutter, in Step 7”; the other G.5b test groups belong to later steps.

- **Goal:** a function that takes one message's text and returns its
  passages, exactly as G.5b describes.
- **Read:** G.5b; `util/ConversationTokenChunks.kt` to see how the
  platform's language-aware boundary tool is used (do not change or call
  it).
- **Build:** new files only, plain Kotlin with no Android screen code. The
  cutter takes the text, a target size, and a function that measures
  size, and returns each passage's start and end. In the app that
  function is the real token counter from Step 1, passed in by Step 8;
  Step 7's own tests pass in a simple one. It includes splitting, joining
  short pieces, the last-resort cut, and the version number.
- **Not in Step 7:** search text and overlap (Step 8), real
  token counts (Step 1), any storage.
- **Done when:** every test listed under "With the cutter, in Step 7" at
  the end of G.5b passes. The search-text tests there belong to Step 8
  and are not part of Step 7.

### Step 8: the passage stores and per-message updating (Hard)

**Agent checklist for this step**
- **Stage:** 7B (M.2, **Conversation index: Stages 7A and 7B**), after the Stage 7A PR merges.
- **Prerequisites:** Steps 1 and 7. G35 is approved.
- **Change:** The separate encrypted index's four stores; per-message ID/fingerprint comparisons and transactional updates; eligibility, same-message search-text overlap, scope stamps, and waiting-vector rows. Confirm the AI-facing reply-version field in code and record it in this plan where required.
- **Preserve:** Stored chats, existing chat search and its database, saved memories. **Do not** implement borrowed context from a neighbouring message (G.5b cutter rule 6), or generate vectors here.
- **Verify:** New/edited/deleted/hidden/unfinished/failed cases, G.5b's step-8 search-text tests, and interrupted updates leaving the previous correct state intact.

- **Goal:** the four stores exist, and one chat's passages can be brought
  up to date one message at a time (G.5, G.5a rules 1, 3, 4, 7, 9).
- **Read:** G.4, G.5, G.5a, G.5b cutter rules 5 and 7, G.7;
  `chatsearch/ChatSearchStore.kt` and `ChatSearchIndexManager.kt` for the
  encrypted-database and revision method; `SummarizerController.kt`
  (`Snapshot`, `Entry`) for the model-facing text; `MessageVisibility.kt`,
  `MessageCompletionState.kt`, `MessageIdentity.kt`.
- **Build:** the encrypted database with the four stores. A function that
  takes a chat's current messages, compares them with the index by
  permanent ID and fingerprint, and replaces only what changed, in one
  transaction per message. Eligibility. Search text, including overlap
  between passages of the same message. Borrowed context from a
  neighbouring message is not built (G.5b cutter rule 6). Scope stamping from
  values passed in.
  Vector rows are created as "waiting"; no vectors are made in Step 8.
- **Confirm in the code and record in this plan:** which stored field
  holds the reply version the AI is sent.
- **Must not change:** the chat search feature or its database, the stored
  chats, the saved-memory database.
- **Done when:** correctness tests pass for new, edited, deleted, hidden,
  unfinished, and failed messages; the tests listed under "With the
  search text, in Step 8" at the end of G.5b pass; and a run interrupted
  halfway leaves the previous correct state.

### Step 9: background embedding of passages (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 1 and 8 and their queue/storage interfaces.
- **Change:** Embed waiting passages behind live work, check the complete search text with the real tokenizer, reuse exact-match vectors, remove unreferenced or obsolete-model vectors, and store per-chat progress. Pause/resume and retain work across app close/reopen.
- **Preserve:** A passage too large to embed is marked failed rather than silently truncated. An edit during embedding invalidates the stale result. Running work **continuously after the app closes** belongs to Step 13, not this step.
- **Verify:** Never embed truncated text; identical search text is embedded once; stale vectors are discarded; live work waits behind at most one running background read.

- **Goal:** waiting passages get their vectors in the background, and
  vectors are reused and removed correctly (G.5a rules 2, 3, 5, 6).
- **Read:** G.5a; the Step 1 queue; `Librarian.kt` for how missing
  saved-memory vectors are repaired in the background today.
- **Build:** a background worker that takes waiting passages one at a
  time, checks the search text with the real tokenizer, records as failed
  any that does not fit, makes the vector, and stores it by
  search-text fingerprint. Vector reuse. Removal of vectors nothing uses
  and of an old model's vectors. Index state per chat. It can be paused
  and resumed, and it survives the app being closed and reopened.
- **Not in Step 9:** running after the app is closed (Step 13), any screen.
- **Done when:** tests show no search text is ever cut off, an edit during
  embedding discards the stale vector, identical search text is embedded
  once, and a live request waits for at most one background read (G.5a
  rule 6).

### Step 10: keeping the index in step with the chats (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 8–9. Read every event in G.12 before adding hookups.
- **Change:** Minimal background-index connections for save, completed reply, hide/show, delete, rename, restore/merge, and model change; index eligible existing chats newest first. Read message scope from the metadata Stage 6A records on each message; do not add another scope record.
- **Preserve:** Existing behavior and responsiveness of each affected flow; no synchronous indexing in a chat operation. Confirm and record how much older conversation has stored scope.
- **Verify:** A test for each G.12 upkeep event, including both-phone history merges, old-chat renames, and turns with an unprovisioned saved-memory database.

- **Goal:** every event in G.12 updates the index correctly.
- **Read:** G.12, G.5a rules 4, 8, 9, G.7;
  `chatsearch/ChatSearchIndexManager.kt` and where it is called from
  `ChatPreferences.kt`; `ChatRenameTransaction.kt`; `chatdeletion/`;
  `backup/portable/ChatMergePlanner.kt`; `ChatSetSourceGeneration`;
  the Stage 6A per-message scope recording.
- **Build:** the hookups, each as small as possible and each calling code
  in the new files: after a chat is saved; when a reply finishes; when a
  message is hidden or shown; when a chat is deleted or renamed; after a
  restore or merge; when the embedding model changes. Scope comes from the
  metadata Stage 6A records on each message in the original chat storage,
  which is recorded whether or not the saved-memory database is set up.
  Indexing existing chats, newest first.
- **Confirm in the code and record in this plan:** how much existing
  conversation has a recorded scope.
- **Must not change:** how any of those flows behave or how long they
  take. Indexing is always handed to the background.
- **Done when:** a test for each row of the G.12 table passes, including
  restore, merge of two histories, and rename of an older chat.

### Step 11: the setting, status, delete, and error notices (Careful)

**Agent checklist for this step**
- **Prerequisites:** Steps 9 and 10. **Stop for owner decisions G20, G28, and G31 before building their wording or behavior.**
- **Change:** Only the Conversation Index settings and messages in G.16a and G.13a/G.13b: setting/status, database-integrity line, deletion confirmation, and approved error/no-model explanations. Use shared styles. G27 is decided: automatic repairs do not interrupt the user; deduplicate notices for the same ongoing condition.
- **Preserve:** All other RAG Active Memory Settings behavior. Index Conversations starts **off** and cannot turn on without an installed embedding model.
- **Verify:** Enabling starts indexing while the app is open; deletion removes derived index data and turns indexing off; approved errors explain causes and next actions. Update `ui-style-adoption.md` with the UI change.

- **Goal:** the Conversation Index section of G.16a, the conversation
  index's line on the Check Database Integrity button (G.13a), and the
  error notices (G20).
- **Before building, the owner approves:** the final wording and actions
  for every user-facing notice (G20, G31), written to G.13b; and G28.
  G27 is decided: automatic repairs do not interrupt the user.
- **Read:** G.16a; G.13a; G.13b; `ui-style-guide.md` in full for the parts
  used (toggle rows, section headings, destructive buttons, two-action
  dialogs); `ui-style-adoption.md`.
- **Build:** exactly what G.16a lists for RAG Active Memory Settings, with
  the approved wording; the notification classification in G.13a; and
  one-notice-per-ongoing-condition deduplication. Every visible value comes from a shared style; a
  missing style is created and added to the guide. `ui-style-adoption.md`
  is updated.
- **Must not change:** any other part of RAG Active Memory Settings.
- **With no embedding model:** the setting cannot be turned on, and the
  user is told why and where to install one. That wording is approved
  first (G31).
- **Done when:** turning the setting on starts indexing while the app is
  open; deleting the index removes everything derived and turns the
  setting off.

### Step 12: the progress icon and pop-up (Careful)

**Existing background indicator.** Reusing the app's shared background-work
indicator is an option to assess before Stage 9. G.16a's separate header
icon is owner-approved. Show any proposed replacement to the owner before
changing that design. This review does not authorize a replacement.



**Agent checklist for this step**
- **Prerequisite:** Step 11. The icon and pop-up's placement and wording are already specified in G.16a.
- **Change:** Add the chat-header progress icon and shared-style pop-up in new files, with only the small header hookup needed; allow pause and resume.
- **Preserve:** The rest of the chat header. Show the icon **only** while indexing is active or paused; paused icon is still, running icon spins.
- **Verify:** The percentage reflects actual completed work, the correct running/paused states appear, and pause/resume work.

- **Goal:** the chat header icon and the progress pop-up of G.16a, with
  pause and resume.
- **Read:** G.16a; `ui-style-guide.md` in full for the parts used
  (Progress pop-up, header action icons); `ui-style-adoption.md`.
- **Build:** the icon and pop-up in their own files, with only the small
  hookup the chat header needs.
- **Must not change:** any other part of the chat header.
- **Done when:** the icon appears only while indexing runs or is paused;
  the percentage comes from work actually done; pause and resume work.

### Step 13: indexing after the app is closed (Hard)

**Agent checklist for this step**
- **Prerequisite:** Step 12. **Approval gate:** The user-visible message for indexing stopping for the day must be shown to and approved by the owner before implementation.
- **Before code:** Check **current** Android guidance on foreground-service limits and WorkManager quotas; choose the supported approach and explain the choice and tradeoffs to the owner. The limits recorded below are reference information, not a substitute for current verification.
- **Change:** Continue indexing after the app closes with the G.16a notification and a real percentage; handle Android stop signals and subsequent resumption.
- **Preserve:** Previously finished work. Never crash, discard completed progress, or start a service when its allowed running time is exhausted.
- **Verify:** The app-closed work, visible notification, safe system stop, and restart from the last completed point.

- **Goal:** indexing continues after the app is closed, with the
  notification in G.16a.
- **Read:** G.14 (Battery); `MemoryAnalysisForegroundService.kt` for how
  the app already keeps work running with a notification.
- **Build:** the background service and its notification.
- **Android's limit (checked against Android's documentation,
  2026-11-12).** For apps built for Android 15 or later, as this one is,
  background work of the "data sync" type may run for 6 hours in any 24.
  Android counts each type separately, and the 6 hours are shared by
  every service of that one type. Four parts of this app are already of
  that type and draw on the same 6 hours: Summarizer, image generation,
  Memory Assistant analysis, and restore. When
  the time is up the system tells the service, which must stop within a
  few seconds or the app is crashed. Opening the app resets the clock.
- **So:** indexing must stop cleanly when told, keep everything it
  finished, and continue the next time the app is opened. It must not
  start when the day's time is already used up.
  `MemoryAnalysisForegroundService.kt` already handles this stop signal.
- **To decide at the start of Step 13, before any code:** whether to
  use this kind of service or Android's WorkManager, which Android's
  documentation suggests instead. WorkManager has its own limit (checked
  against Android's documentation, 2026-11-12): from Android 16, a job's
  running time counts against a quota set by how often the app is used,
  even when the job started while the app was open or runs alongside a
  foreground service, and the system stops a job that runs out. The
  builder reads Android's current limits for both and reports the choice
  and the reason to the owner. Whichever is chosen must stop safely when
  told, keep everything finished, and continue later. What the user is told when indexing stops for the day
  is shown to the owner first.
- **Done when:** indexing continues with the app closed; the notification
  shows a real percentage; a stop by the system loses no finished work;
  and a test shows indexing picks up where it stopped.

### G36 review requirements before retrieval implementation

**One total pre-send allowance.** Define a shared deadline spanning optional
context assistance, embedding queue waits and reads, saved-memory search,
and conversation recall. Record the actual order of those operations from
both send paths and avoid embedding the same query twice. Existing
per-component limits remain upper bounds within the combined allowance.
Set separate typed and hands-free voice budgets from phone measurements.
Specify cancellation and which optional work gives way first, preserving
the current message's primary search and the normal send behavior. Do not
start optional work that cannot finish within the remaining allowance.
Changes to already approved helper waits or degradation behavior belong
in the combined G36 approval.

Step 15 measures the existing pre-send path on the owner's phone alongside
isolated recall time, including queue contention and the API-helper share
where that mode is configured. It can model the combined budget before
assembly exists. Step 16 verifies the actual combined deadline and total
delay in both outgoing request paths. Report build-machine timing separately.

**Recall follow-ups by context-assistance mode.** Define and test the
following contract in G36 before coding. These are proposed implementation
details for the owner's continuity requirement.

- **Off.** Search the newest message and its bounded pieces. No automatic
  carry-over from an earlier exchange.
- **API AI.** Reuse the helper's validated additional query for recall,
  with lower priority than the newest message. Do not make another helper
  call or assume a saved-memory list is a query. Apply the helper's existing
  failure fallback. Define explicitly whether any previously delivered
  exchange is retained when the query is unavailable.
- **Code-based.** Current code supplies saved-memory matches and a subject
  signal. Specify a separate chat-local set of previously delivered recall
  exchange IDs, its bounds, and the continuity signal that can retain it.
  A saved-memory ID cannot stand in for an exchange ID. Set delivery state
  only after successful request assembly/send, recheck source eligibility
  each time, and clear it when the subject changes. Define the case where
  recall found an exchange but saved-memory search established no confident
  subject. A conservative proposal is newest-message retrieval only in that
  case. Evaluate its effect on the owner's follow-up requirement before
  approving it.
- **Local AI.** Remains a later addition. Document its query contract when
  it is built.

This contract replaces the ambiguous instruction to copy saved-memory
carry-over. It does not reactivate Step 3's previous-message search.

### Step 14: retrieval (Hard)

**Agent checklist for this step**
- **Prerequisites:** Step 6 and its review additions, Steps 1 and 7–10, and the implemented G.9b context-assistance contract from Stages 2–3. **Approval gate:** G36 must approve the complete G.6/G.8 retrieval and delivery design, mode-specific follow-ups and total send-time budget before coding.
- **Change:** The current-chat-only retrieval engine behind G.8a interfaces: implement G.6 retrieval stages **1–8** with G.9b/G.10 query handling, scoring, gates, eligibility, and measured time allowance.
- **Preserve:** No cross-chat recall, no request assembly, no paid calls, no forced match when nothing qualifies. **G.6 retrieval stage 9 and the optional re-ranker are not in this build.**
- **Verify and report:** Use Step 6's prewritten questions and the real embedding model. Score each question individually for relevant exchanges found and unrelated exchanges left out; record results as the work order requires.

- **Goal:** given a chat and a message, return the exchanges to offer,
  with every score and reason (G.6, G.9b, G.8a points 1 and 2).
- **Read:** G.6, G.8a, G.9b, G.10; `LiveRetrieval.kt` and
  `KeywordText.kt`; Step 6's `conversations.json`.
- **Build:** retrieval stages 1 through 8 of G.6 (retrieval stage 9 is not
  in the first build), using the approved G36 mode-specific contract,
  as plain Kotlin behind the G.8a interfaces. G.9a's previous-message
  search remains retired from sending. Implement the approved time limit.
  Nothing is added to any request.
- **Done when:** Step 6's questions are scored with the real model's
  numbers, each sample checked on its own as in Step 0, and
  `conversation_recall_baseline.md` is created with every sample's
  "found" and "left out" results plus totals by situation.
- **Speaker-title evaluation (Owner, 2026-11-23).** Index the same chats
  three ways and score each with the real model: **A** generic titles only
  (Human/Companion); **B** titles that also carry the speaker's recorded name
  (for example "Companion: Ash"); **C** generic titles with the search limited
  by the permanent identity IDs when a question names an identity; and B with
  C together. Measure (1) questions that name a companion, Glamour or
  roleplay identity, (2) ordinary topic questions with no name, to see
  whether names cost accuracy, and (3) whether ID filtering, names, or both
  work best. The test chats must include an identity that changes inside a
  conversation, an identity renamed later, two characters with the same
  name, and a roleplay name replacing a Glamour name; Step 6's set is
  extended where these are missing. Results go in
  `conversation_recall_baseline.md`; the title policy is chosen from them,
  and Human/Companion stay the default until then.

### Step 15: the inspector and the measurements (Careful)

**Agent checklist for this step**
- **Prerequisite:** Step 14, with Step 6 quality cases available. **Approval gate:** Show all inspector-screen labels to the owner as one list before implementation.
- **Change:** The **Conversation Recall Test** inspector, opened from RAG Active Memory Settings beside Memory Search Test (G.8b), showing which exchanges a typed question would find and why; measure the G.15 retrieval starting values. Do not show assembly exclusions or "what was sent" as if computed; Step 16 adds those after assembly exists.
- **Preserve:** This is a debugging and measurement step, **not** live request injection. Do not add recall to outgoing requests until Step 16.
- **Verify and report:** Record the measured starting values in `conversation_recall_baseline.md`, and distinguish build-environment measurements from actual search time on the owner's phone.

- **Goal:** a way to type a message for a chat and see what would be
  recalled and why, and measured starting values for the numbers.
- **Placement and name are decided (G.8b).** Before building, the screen's
  labels are shown to the owner as one list.
- **Done when:** the retrieval-related numbers in G.15 have measured
  starting values recorded in `conversation_recall_baseline.md`, with
  search time on the owner's phone. The speaker-title evaluation in Step 14 is
  shown in the inspector with which title each passage was read under. Assembly-related measurements and
  inspector fields wait for Step 16.

### Step 16: context assembly (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 14 and 15. **Approval gate:** Obtain the AI-facing text, “what was sent” wording, recall-room control wording, debug lines, and the G24 decision **before** coding any undecided behavior.
- **Change:** One shared assembly implementation connected minimally to **both** request-building paths. Add only eligible original exchanges, within recall's independent limits; label speaker/date/excerpts, show what was sent, and perform the final whole-request size check. Extend Step 15's inspector with actual assembly outcomes, including exclusions and their reasons.
- **Preserve:** When recall is off, the request is **identical** to today's request. Never displace history, summaries, saved memories, or other content to make room for recall; never store recalled text as new chat content.
- **Verify:** Every applicable G.15 “what was sent” test passes, both request paths add identical recall text, and timeout/overflow produce the existing request without losing its other content.

- **Goal:** recall is added to real requests (G.8, G.8a point 3).
- **Before building, the owner approves:** the exact text given to the
  AI; the wording for the "what was sent" group, the recall room setting,
  and the debug view lines; and G24. Everything else for Step 16 is
  decided (G.8b).
- **Read:** G.8, G.8a; both request builders in `ChatActivity.kt`;
  `memory/enforcer/PromptAssembler.kt`; `util/RequestCapacity.kt`.
- **Build:** assembly in its own file, called from both request builders
  with the smallest possible hookup. The size limits, the final size
  check, excerpts, date order, labels, and the "what was sent" view.
  Extend the Step 15 inspector using this shared assembly's real outcomes,
  not a second implementation or guessed pre-assembly exclusions.
- **Must not change:** anything a request contains when recall is off.
- **Done when:** all quality tests in G.15 pass at the "sent" point, both
  request paths add the same text, and the final "sent" results are
  recorded in `conversation_recall_baseline.md`.

## G.16a The approved screens for indexing (Owner, 2026-11-11)

Approved by the owner on 2026-11-11: placement, wording, and that Index
Conversations starts off.

**In the chat header.** A small indexing icon, beside the existing icon
that appears only when needed. It is shown only while indexing is running
or paused. It spins while running and is still when paused. Tapping it
opens the progress pop-up.

**The progress pop-up,** using the shared Progress pop-up style:

| Part | Wording |
|---|---|
| Title | Indexing Conversations |
| Percentage | A real percentage, based on messages processed |
| Status line, running | 128 of 310 conversations indexed (real numbers) |
| Status line, paused | Paused |
| Explanation | This runs in the background. You can keep chatting. |
| Buttons, running | Pause, Close |
| Buttons, paused | Resume, Close |

**The notification while indexing runs after the app is closed:**

| Part | Wording |
|---|---|
| Title | Indexing Conversations |
| Text | 42% done. Tap to open. (real percentage) |

**In RAG Active Memory Settings** (M.11; placed in Advanced Memory Settings
after Librarian / Embeddings before the 2026-11-16 reorganization), a new
section above the test rows at the bottom:

| Part | Wording |
|---|---|
| Section heading | Conversation Index |
| Toggle | Index Conversations |
| Toggle explanation | Builds a searchable index of your conversations on this device. Indexing is entirely local. |
| Status | 310 conversations indexed (real number) |
| Button | Delete Conversation Index |
| Confirmation title | Delete Conversation Index? |
| Confirmation text | This deletes the searchable index stored on this device. You can rebuild it by turning indexing on again. (Owner, 2026-11-24, replacing the 2026-11-11 text) |
| Confirmation buttons | Cancel, Delete |

**Owner (2026-11-24), built in Step 11:** the section sits between Memory
Engine and Memory Search Test. The status line shows the approved count and,
when any exist, "12 conversations need to be opened before they can be
indexed." (G28). A conversation counts as indexed only when every eligible
message and every search vector is done; a failed vector is never counted as
indexed; conversations with nothing to index are in neither count. The count
is shown while the setting is off too, and turning the setting off keeps the
index. Delete Conversation Index stays visible and is enabled except while its deletion is running, stops indexing,
removes all derived data and updates the count, with no success pop-up; a
failed deletion never shows zero. An ongoing condition (rebuilding, paused,
stopped) stays visible beneath the status after its notice is closed.

**With no embedding model installed.** The earlier "Keyword search only"
status is withdrawn. On 2026-11-12 the owner decided that conversation
recall requires the embedding model (G30), so the toggle cannot be turned
on without one. The wording that tells the user why is G31.

**Behaviour approved with it:**
- Index Conversations starts off. Turning it on starts background
  indexing.
- Deleting the index also turns the setting off, so it is not rebuilt
  against the user's wishes.

**A sentence that was removed.** The approved explanation had a third
sentence saying recall is turned on "for a chat". That described a
per-conversation setting the owner had not chosen, and on 2026-11-11 the
owner ruled that recall is one setting for all chats (G1). The sentence is
removed and is not built. A sentence telling the user that recall sends
earlier conversation to the AI is still needed; its wording is proposed to
the owner with the recall setting (G17).

## G.17 Open decisions for the owner

Asked one at a time, and only when the Step that needs the answer is
about to start. Each has a recommendation.

| # | Question | Recommendation | Needed before |
|---|---|---|---|
| G1 | Is recall on for every chat, or chosen per chat? | **Decided (owner, 2026-11-11):** one setting in a settings menu, applying to all chats. Not chosen per conversation. Which menu, its wording, and whether it starts on or off are G17 | Decided |
| G2 | How large is recall's own size limit, and can the user set it? | **Decided (owner, 2026-11-11):** a starting amount from testing, adjustable in RAG Active Memory Settings (G.8b, M.11). Its label is shown to the owner before Step 16 | Decided |
| G3 | When a chat no longer fits the model, does anything change? | **Decided (owner, 2026-11-11):** no. It warns or blocks as today | Decided |
| G4 | Does recall apply in Experimental Mode? | **Decided (owner, 2026-11-11):** no | Decided |
| G5 | Are attached files and images indexed? | **Decided (owner, 2026-11-11):** no, not in this work | Decided |
| G6 | When are existing chats indexed? | **Decided (owner, 2026-11-11):** in the background, never on opening a chat. Proposed details: newest first, with progress shown and a way to pause | Decided |
| G7 | Do backups carry the index? | **Decided (owner, 2026-11-11):** no. It is rebuilt after a restore | Decided |
| G8 | Which jobs does the live AI worker do, and how is it turned on? | Open. Start with marking what matters. The owner chose one app-wide setting for recall, not a per-conversation one | The live AI worker |
| G9 | Across chats, which ordinary chats may see each other? | Same companion or same project only, at first | Recall from other chats |
| G10 | Is a recalled exchange carried over like a saved memory? | **Decided (owner, 2026-11-11):** continuity through follow-ups. G36 must specify how each current context-assistance mode implements it, including Off and the absence of a saved-memory subject | Intent decided; implementation in G36 |
| G11 | What does the user see when recall adds something? | **Decided (owner, 2026-11-11):** listed on the existing screen that shows which memories were sent, as a second group. Its wording is shown to the owner before Step 16 | Decided |
| G12 | Did the owner make the "seen and not objected to counts as approved" rule recorded in Part E of the memory plan? | **Decided (owner, 2026-11-11):** silence does not count as approval. Explicitly approved decisions are kept; everything else in the memory plan is relabelled as proposed | Decided |
| G14 | Does background indexing of existing chats continue after the app is closed? | **Decided (owner, 2026-11-11):** yes. The notification this requires is part of G15 | Decided |
| G15 | Where does the user see indexing progress, pause it, and delete the index, and what does the notification say? | **Decided (owner, 2026-11-11):** as recorded in G.16a. Index Conversations starts off | Decided |
| G16 | Does recall apply in hands-free voice chat? | **Decided (owner, 2026-11-11):** yes, with a small measured time allowance. Skipped only when the allowance is exceeded | Decided |
| G17 | Which settings screen holds the recall setting, what is it called, what does its explanation say, and does it start on or off? | **Decided (owner, 2026-11-11):** as recorded in G.8b | Decided |
| G18 | Does the per-chat memory switch also stop recall in that chat? | **Decided (owner, 2026-11-11):** yes | Decided |
| G19 | The exact Error Log lines, the database check's line and hint for the conversation index, and the wording that tells the user of a failure | **Decided (owner, 2026-11-11):** as recorded in G.13a | Decided |
| G20 | Which failures receive a user-facing notice, and what does each notice say? | **Decided for Step 11 (owner, 2026-11-24):** automatic retries have no pop-up; the model becoming unavailable (removed or cannot load) pauses indexing with one Indexing Paused notice; a recovery that fails turns indexing off with one Indexing Stopped notice; wording, behavior and Error Log templates in G.13a and G.13b. Step 13's notices are still approved before Step 13 | Step 13 |
| G21 | Where the recall test screen goes, and its name | **Decided (owner, 2026-11-11):** Conversation Recall Test. Placement updated by the owner's 2026-11-16 reorganization: a row beside Memory Search Test at the bottom of RAG Active Memory Settings (M.11) | Decided |
| G22 | May recall and carry-over add to the existing memory debugging view? | **Decided (owner, 2026-11-11):** yes. No new general application log entries | Decided |
| G23 | Which Step is built first? | **Decided (owner, 2026-11-11):** the long-message fix is the first user-visible fix. In the corrected numbering it is Step 2, after completed Step 0 and the Step 1 queue-safety prerequisite (G.16) | Decided |
| G24 | If the user turns Index Conversations off while Conversation Recall is on, what happens to recall? Also undecided: whether turning Conversation Recall on turns Index Conversations on (G.8b says only that it "may"), and what happens to recall when the index is deleted | Open. Indexing is never silently restarted (G.8b) | Step 16 |
| G25 | Withdrawn on 2026-11-12. With no embedding model nothing can confirm a match, so messages are searched as today (G.10). Nothing changes for those users, so there is nothing to decide | Withdrawn | |
| G26 | What replaces inaccurate saved-memory diagnostic lines? | **Decided for Step 2:** G.13b records approved wording and its locations, including context-specific repair corrections. Technical embedding terminology is allowed. Model-load and self-check notices remain Step 11 | Decided for Step 2 |
| G27 | Which failures interrupt the user? | **Decided (owner, 2026-11-12):** automatic repairs do not raise a notice. Notify the user when the failure affected a turn, stopped a feature, prevented a setting, or has a real action the user can take. Say what happened, what is safe, and the action when one exists. Do not repeat the same ongoing failure every turn | Decided |
| G28 | Chats that have never been reopened cannot be indexed until they are opened (G.5a rule 7). What does the progress count show for them? | **Decided (owner, 2026-11-24):** the approved count plus "12 conversations need to be opened before they can be indexed."; indexed only when every eligible message and vector is done; chats with nothing to index in neither count; shown while the setting is off too (G.16a) | Decided |
| G29 | To find a small detail in a long or dictated message, should the app also search with overlapping pieces of the message, cut between words and never at periods (G.10)? | **Decided (owner, 2026-11-12):** yes, on the agent's recommendation | Decided |
| G30 | Is recall offered without an embedding model? | **Decided (owner, 2026-11-12):** no. Conversation recall requires the embedding model. Lorebook keyword matching is separate and keeps working whenever the lorebook is on | Decided |
| G31 | What the user sees when the required on-device model is missing, removed, or unusable | **Decided (owner, 2026-11-24):** as recorded in G.13b: On-Device Search Model Needed when turning Index Conversations on with no installed model (an installed-model file check; the switch stays off), and Indexing Paused, with the cause, when the model is removed or cannot load | Decided |
| G32 | Can the user choose how many of their previous messages are searched for follow-ups (G.9a)? | **Superseded (owner, 2026-11-16):** searching with the previous messages is not the carry-over method and is not a context-assistance choice. Replaced by G37 and G38 | Superseded |
| G33 | Is the embedding model's reading limit for saved memories raised from 512 tokens to the model's real 2,048 (G.2)? | **Decided:** Step 1 reads saved memories up to the model's full 2,048-token capacity. G34 uses the actual model capacity, not a word-count approximation. Speed and memory use on the phone are checked before it ships | Step 1 |
| G34 | How is a saved memory limited to what the model can read, and how is its length shown? | **Decided (owner, 2026-11-17):** informational content count exactly "348 Words"; actual complete-input tokens against the selected embedding model's capacity, plus a filling bar at the top of creating/editing/reading views. Exact local token counting off the UI thread, including content, extra searchable text, tags, document prefix and special tokens; the same fresh calculation gates Save. No word-based saving cap or warnings. Overflow pop-up: **Memory Cannot Be Saved** / "This memory exceeds the embedding model's reading limit." / **Okay**, without advice; all entered and stored text preserved. The Memory Assistant splits oversized proposals without losing information; splitting is also enforced in code. No separate existing-overlength-memory design. See the corrected Step 5 work order | Decided |
| G35 | The design of the conversation index as a whole (G.5, G.5a, G.5b, G.7): a new encrypted database on the phone holding where each piece of a conversation is, its searchable words, and the embedding model's numbers; what is and is not stored; and how it is kept correct after edits, deletions, and restores | **Decided (owner, 2026-11-23):** approved as written in G.5, G.5a, G.5b and G.7 | Decided |
| G36 | The design of retrieval and delivery (G.6, G.8), mode-specific follow-ups, conservative repeat handling and total pre-send time budget | Open. Resolve the review requirements before Step 14, explain the combined design in plain terms, and obtain one approval before Step 14 | Step 14 |
| G37 | How do follow-ups keep their saved memories? | **Decided (owner, 2026-11-16):** through context assistance: Off, Code-based subject tracking, API AI with its own model in the initial rollout, and Local AI later; separate from the embedding model (G.9, G.9b, G.9c) | Decided |
| G38 | The context helper's setting | **Decided (owner, 2026-11-16):** on Memory Context Helper Settings (M.11); optional, an on/off toggle that starts off; when on, a dropdown of Code-based or API AI, one at a time; Code-based selected when first turned on; Local AI not shown now; no placeholder screens. Labels and explanations are asked when Step 4a starts (M.10). How Code-based rebuilds the subject after a restart is an engineering choice for the builder | Decided |
| G39 | The Memory Context Helper Tool API (M.4) | **Decided (owner, 2026-11-16):** the name and place, the user's own API model, every parameter, Memory Scanner Tool API's arrangement, and the ten defaults in M.4 (input, fixed instructions, 8 s / 3 s time allowance, failure fallback, cost, no separate privacy warning, its own model only, logging as "Memory Context Helper", starting state, testing). Corrected by the owner on 2026-11-16: no fixed token cap on the conversation input, and no privacy disclosure. Wording of the notices and labels, and the model and cost of the scored run, are asked when Step 4b starts (M.10) | Decided |
| G40 | May a +3 memory pass without the relevance gate's full stand-out? | **Decided (owner, 2026-11-16):** only under the top-match condition in G.10. The unrestricted lower bar is rejected | Decided |
| G41 | Which other embedding models are offered, local or API, and their privacy and cost | Open. The architecture is required now (G.9c, M.5) | Later |
| G42 | The build order in M.2 | **Decided (owner, 2026-11-16):** as M.2. Provenance first, so the Memory Scanner records sources before the owner builds a memory collection. Updated November 18: complete the scanner update after Stage 6 and before Stage 7 (M.2). Updated November 23: Stage 7 is split into Stages 7A and 7B (M.2) | Decided |
| G43 | The memory provenance design (M.3) and its details | **Decided (owner, 2026-11-16):** the source is the whole conversation, never a scanner-chosen message; the memory editor shows User, Companion, Date, Last Message Date and Source Conversation as M.3 describes; no sources are carried through Replace, Supersede or merge; a memory the user writes shows its creation date; the user cannot edit sources or dates; the important date is built in Phase 4. Participant-specific attribution and user-added sources are possible later features | Decided |
| G44 | Release Step 4a's setting before Step 4b adds API AI | **Decided (owner, 2026-11-16):** yes; the setting ships with On-device (Code-Based) only, shown as plain text until API AI makes it a dropdown | Decided |
| G45 | Include the frozen-path lorebook repeat check (memory plan step 1.13, A.9) in Step 4a | **Decided (owner, 2026-11-16):** yes, on typed sending, voice and resending alike. With it the owner decided that every way of sending uses the same lorebook rules: every match after duplicate removal up to Maximum Lorebook Entries (Lorebook Settings, default 20, 1–99, no unlimited), an optional character limit (off, up to 9,999), an optional entry limit on RAG Active Memory Settings that replaces it while memory is on and a valid number is entered, and lorebook text never uses Memory Space | Decided |

## G.18 Research behind this plan (2026-11-10)

Read by the agent from the sources named. Figures are the sources' own.

| Question | Finding | Source |
|---|---|---|
| Is sending a whole long history best? | No. Whole history scored 54.15; retrieved topic segments scored 71.57. Another study found large drops with a 113,000-token history against a focused excerpt | Microsoft SeCom (ICLR 2025); Chroma, "Context Rot" |
| Where do models attend? | Most to the start and end of the input, least to the middle | "Lost in the Middle" (Liu et al., 2023) |
| What size should a piece be? | Topic segments 71.57, single turns 65.58, whole sessions 63.16 | Microsoft SeCom |
| Who can split by topic? | GPT-4 did. With a small model the score fell to 61.84, and on a second test it lost to single turns | Microsoft SeCom, Table 11 |
| Does automatic semantic chunking help? | Not consistently. On realistic text, fixed-size chunks did as well or better | Qu, Tu and Bao, NAACL 2025 Findings |
| Meaning or keywords? | Both. Keyword search beat meaning search by up to 11.98 points on single turns and sessions; with topic segments the gap was about 2 | Microsoft SeCom |
| Saved facts or the original conversation? | Both. Facts alone 71.2%; with the original conversation 86.1% | Redis, LongMemEval study |
| Does linking facts to conversation help? | Yes. Searching with extracted facts attached raised recall about 9.4% and answers 5.4% | LongMemEval (ICLR 2025) |
| Do dates help? | Date-aware search raised recall 6.8% to 11.3% on time questions, using a strong paid model | LongMemEval |
| Does a line of context on each piece help? | Missed retrievals fell 35%, and 49% with keyword search. It used a paid call per piece | Anthropic, "Contextual Retrieval" |
| Do summary layers help? | Up to 20 points on one long-document test, built with paid summaries | RAPTOR (ICLR 2024) |
| Do knowledge graphs help? | 71.2% against 60.2% in the maker's own test; an independent test measured 63.8% | Zep paper; third-party comparison |
| How good is the app's embedding model? | Ranked first among models under 500 million parameters by its maker. English score 69.67 at full size, 68.37 at the app's 256 numbers, 69.31 for the q4 variant | EmbeddingGemma model card |
| Does a re-ranker help? | Yes in published tests: it took Anthropic's missed retrievals from 49% fewer to 67% fewer. It runs once per candidate, so it adds time | Anthropic, "Contextual Retrieval"; Jina and Mixedbread model reports |
| How should meaning and keyword results be combined? | A weighted blend of the two scores beat reciprocal rank fusion on familiar and unfamiliar text, was less sensitive to its settings, and could be tuned from a handful of examples | Bruch, Gai and Ingber, "An Analysis of Fusion Functions for Hybrid Retrieval" |
| Does the order of retrieved passages matter? | Not reliably. Rearranging passages into "preferred" positions did no better than random order in one study, and results differ between models | "Do RAG Systems Really Suffer From Positional Bias?" (EMNLP 2025) |
| Is there one best system? | No. Top scores are self-reported and no neutral test compares the designs under the same conditions | LongMemEval and LoCoMo comparisons |
| What does a working chat client do? | Searches with the last 2 messages, protects the last 5, inserts 3, cuts long messages at 400 characters, stores each message separately, and places recalled messages prominently even when they are already in the request | SillyTavern documentation |

Two cautions:

- SillyTavern searches with the last 2 messages. #180 measured that gluing
  recent messages onto the search hurt saved-memory search in this app, so
  that setting is not adopted without testing.
- The widely repeated "256 tokens with 50 overlap" figure could not be
  confirmed from its named source, which studied documents, not chats.

Sources:

- Microsoft SeCom: https://arxiv.org/abs/2502.05589
- LongMemEval: https://arxiv.org/abs/2410.10813
- Redis study: https://redis.github.io/redis-ai-research-public/longmemeval-agent-memory/
- Semantic chunking study: https://arxiv.org/abs/2410.13070
- Chroma, "Context Rot": https://research.trychroma.com/context-rot
- Anthropic, "Contextual Retrieval": https://www.anthropic.com/engineering/contextual-retrieval
- RAPTOR: https://arxiv.org/abs/2401.18059
- EmbeddingGemma model card: https://huggingface.co/google/embeddinggemma-300m
- SillyTavern chat vectorization: https://docs.sillytavern.app/extensions/chat-vectorization/
- Fusion functions for hybrid retrieval: https://arxiv.org/abs/2210.11934
- Positional bias in retrieval: https://arxiv.org/abs/2505.15561

## G.18a What established open-source systems do (2026-11-12)

**Owner (2026-11-12):** where the right decision is not known, look at
what established, proven systems do and use that to decide. Custom design
is acceptable. Credit can be given in the app's About screen for anything
shared. The app is never to be sold, by the owner or by anyone else.

Read on 2026-11-12 from each project's documentation or source code.

| Decision | What they do | This plan |
|---|---|---|
| Cutting text into pieces | SillyTavern: 400 characters, cut at a paragraph, a line, or between words, no overlap for chat messages. GPT4All: 512 characters. LangChain's standard splitter: paragraph, line, then between words, with overlap | Same cut points (G.5b) |
| What the AI is handed | SillyTavern finds a piece and hands over the whole original message, labelled with who said it, under "Past events" | The whole exchange (G.8) |
| What to search with | SillyTavern: the last 2 messages joined. RisuAI: the last 3 messages, each searched on its own, later ones counting more. AnythingLLM: the latest message only | The newest message, plus what context assistance supplies (G.9b). Searching the previous messages (G.9a) was measured and not used |
| How many are sent | SillyTavern 3, GPT4All 3, AnythingLLM 4 | 3 (G.8) |
| The cut-off | SillyTavern: a fixed 25% score, then the best 3 | A stricter gate, this app's own, because a wrong result is worse than a missed one (G.6) |
| Keywords as well as meaning | SillyTavern, GPT4All, AnythingLLM, RisuAI: meaning only. Open WebUI and Mem0: both, with an optional re-ranker | Both (G.6) |
| No embedding model | None of the chat apps offers recall without one | The embedding model is required (G30) |
| Follow-up messages | AnythingLLM fills empty room with what was used in recent replies, newest first, with no relevance check. Open WebUI and LlamaIndex have an AI model rewrite the follow-up, a paid call | Context assistance: Code-based subject tracking, or an optional API AI rewrite (G.9b) |
| A long message about several things | RisuAI's experimental mode searches each paragraph of the message separately. Others offer a paid rewrite | Overlapping pieces (G.10) |
| Keeping the index correct | SillyTavern keeps a fingerprint per message; on every edit, deletion, or new message it adds what is missing and deletes what is gone | The same idea (G.5a rule 4) |
| Prompt caching | SillyTavern's documentation warns that recall works against prompt caching, because it changes text early in the request | Recalled text sits beside the newest message, which leaves the earlier text unchanged. To be confirmed with a caching provider in Step 16 |

**Not verified:** Open WebUI's default numbers.

**Licences.** Only approaches are taken from these projects, which no
licence restricts. Code is a different matter:
- SillyTavern and RisuAI are understood to be under licences (AGPL and
  GPL) that would require this whole app to be released under the same
  terms, which allow others to sell it. Their code is never copied.
- AnythingLLM, LlamaIndex, LangChain, GPT4All, and Mem0 are understood to
  be under permissive licences that allow copying with credit.
- These licences were not re-read on 2026-11-12. Before any code is
  copied from any project, its licence file is read and the owner is
  told.

Sources:

- SillyTavern chat vectorization: https://docs.sillytavern.app/extensions/chat-vectorization/
- SillyTavern vector extension source: https://github.com/SillyTavern/SillyTavern/blob/release/public/scripts/extensions/vectors/index.js
- RisuAI HypaMemory V3 source: https://github.com/kwaroran/RisuAI/blob/main/src/ts/process/memory/hypav3.ts
- AnythingLLM chat helper source: https://github.com/Mintplex-Labs/anything-llm/blob/master/server/utils/helpers/chat/index.js
- GPT4All settings: https://docs.gpt4all.io/gpt4all_desktop/settings.html
- LangChain recursive text splitter: https://docs.langchain.com/oss/python/integrations/splitters/recursive_text_splitter
- LlamaIndex chat engines: https://developers.llamaindex.ai/python/framework-api-reference/chat_engines/condense_plus_context/
- Mem0 search: https://docs.mem0.ai/core-concepts/memory-operations/search
- Open WebUI retrieval: https://docs.openwebui.com/features/rag

## G.19 Rules for whoever builds a Step

A Step may be built by a different agent or model than the one that wrote
this plan. These rules keep that safe.

- **Read this plan and the files it names for your stage.** The memory
  plan (`Memory System/dynamic_memory_plan.md`) is background design for
  saved-memory work (its Parts B–E for Phases 2–6) and is not needed for
  the conversation index.
- **Build only the assigned stage or Step from M.2.** Do not start the
  next one. A session's changes go in one pull request (`CLAUDE.md`).
  Stages 1–6 have merged. Use the current handoff in Part M and the detailed specification linked for the assigned stage. Every stage follows the code
  workflow in `CLAUDE.md`.
- **Do not start a stage out of order or while its "Approve first" entry in
  M.2 is not satisfied.** Say what is waiting and stop.
- **Write results only to the destination table in G.16.** This plan may
  otherwise be edited only where the Step's work order explicitly says to
  record a confirmed code fact, and in the Build record.
- **Only "Owner" items are decisions.** Anything marked "Proposed" that
  the user would see, read, or pay for is shown to the owner before it is
  built: wording, screens, settings, defaults, and text sent to the AI.
- **Never change:** the stored chats; the Summarizer and its summaries; the
  existing chat search feature; saved-memory search results (except Step 1
  only for memories that exceeded the old 512-token reading limit, and
  Steps 2 and 4 as their work orders specify); the lorebook; what a
  request contains when recall is off. In Step 1, stored vectors and search
  results for memories that fitted inside the old limit remain unchanged.
- **Never remove anything from a request to make room for recall.**
- **Never add or change a log line** without the owner's approval. The
  owner has ruled which logs are used (G.13a). The lines recorded there
  are re-proposed under the plain-wording rule (G.13b) before the Step
  that writes them.
- **Put new code in its own files.** The chat screen's files are too large
  to change safely; add only the small hookup they need.
- **Never copy another screen or file** as a pattern. Follow
  `ui-style-guide.md` for anything visible.
- **If the code does not match what this plan says about it, stop and say
  so.** Do not guess, and do not copy another part of the app to fill the
  gap.
- **A Step is finished** when its tests pass, the local checks and Android
  Checks pass, its results are written to the required record, and one line
  saying what was built is added under "Build record" at the end of this
  plan. That is the only other place a builder
  may edit.

## Build record

One line per finished Step: the Step, what was built, and the pull
request. Detailed results stay in the file named by G.16.

- Step 0 — saved-memory benchmark completed for 86 samples; detailed
  results are in `memory baseline.md`.
- Step 1 — token counting, one priority queue for every embedding read
  (a live read can stop a running background read), and saved memories
  read up to 2,048 tokens with a resumable re-read of memories cut at 512;
  pull request #199. Measurements are in `memory baseline.md`.
- Step 2 — a message longer than 80 words is also searched in overlapping
  80-word sections (at most 8, provisional 1,500 ms allowance) after the whole message; PR #200. The allowance was initially soft; repair requests cooperative termination and bounds queue waiting, not native unwind time.
  By owner decision a section's match counts only when its meaning match
  is at least 0.48 and it shares a meaningful word with the
  memory, in addition to the existing relevance gate. Size, cap and threshold
  are provisional; this replaced the original "same gate as a whole message" proposal, which sent
  more wrong memories on the test set. The Memory Search Test and Memory
  Debug Log wording is the owner-approved wording, not the G.13b proposals.
  Results are in `memory baseline.md`.

- Step 2 repair audit (#201 and follow-up branch) — accurate section thresholds/near-misses/failures, bounded queue waiting with cooperative termination, native model lifetime protection, shared live/test matching and retrieval-to-prompt coverage. ONNX startup/API telemetry opt-outs also cover Silero. Original Python fixture baseline 69/6/19 is preserved. Initial Android ORT 1.20 results were 54/21/21 before sections and 69/6/21 with sections. The follow-up retains the same runtime/model and calibrates existing defaults: real Android results are now 54/21/17 and 69/6/17, with the restored per-message quality gate passing. Independent evaluation stays 4/5/0. All 313 token references and all 85 Android tests pass. Merged in #202; physical-phone timing remains unmeasured. Full evidence and timing limits are in `memory baseline.md`.

- Step 3 — a standalone search helper (`PreviousMessageSearch.kt`) that searches the new message and 0–3 previous user messages separately through the existing saved-memory search and returns candidates for the enforcer's selection: the new message's candidates first and unchanged, then older messages' in order of age, each memory once. It is connected to nothing: no setting, no screen, no change to sending. PR #203.

- Step 3 repair — the helper as merged in #203 described and measured its limit as Maximum Memories; it now only supplies candidates, and Maximum Memories, backfill and the +3 rule stay in the enforcer's selection. The owner's +3 rule (G.10) is applied to section matches in the search, the enforcer and the Memory Search Test, replacing the section-only exception. A message searched without its embedding is reported instead of passing unnoticed. The Step 0 conversations are scored through the enforcer's selection at Maximum Memories 1, 2, 3, 5 and 8 and checked against the expected answers. Results, costs and what Step 4 must verify are in `memory baseline.md`.

- Carry-over evaluation (before Step 4) — methods and +3 rules fixed in advance and scored on a development set and a held-out set with Android ONNX Runtime 1.20 similarities and desktop vectors. Results are in `memory baseline.md`; the owner's decisions are G37 and G40.

- Stage 2, Step 4a — context assistance behind one interface (`memory/context/`): Off and On-device (Code-Based) subject tracking, exactly `subject_anchor`, with the subject kept per chat and embedding model in memory, dropped when the chat is deleted or renamed, and rebuilt after a restart from up to three earlier user messages only when a message has no confident match. The +3 top-match rule (G.10) in the whole-message search, so the Memory Search Test shows it. Memory Context Helper Settings (off by default) from the Memory Manager. With the owner's G45 decision: typed sending, voice and resending use the same lorebook selection (Lorebook Settings and the RAG Active Memory Settings override), lorebook entries are always their own request layer, take no Memory Space, and every way of sending drops a memory that repeats one. With the rule off, the app's Off and Code-based reproduce the recorded carry-over results exactly; results are in `memory baseline.md`.

- Stage 3, Step 4b — the API AI context helper behind the Step 4a interface (`memory/context/ApiContextHelper.kt`): its own model on the new Memory Context Helper Tool API screen in AI Settings (own endpoint, model, routing and every parameter, kept in the model settings backup), never the chat's or any other model; the new message and a strict sliding window of the last Number of Past Messages to Send exchanges (default 4), whole exchanges within the model's context window and no fixed token cap; requests ordered for prompt caching with OpenRouter's stable cache key and no explicit cache marker; 8 s / 3 s time allowance, no retries; output checked before use; provider-reported usage in its own Memory Context Helper usage section; failures shown in a persistent error pop-up with the actual sanitized error details, one per failure episode, without stopping or delaying the chat or hands-free voice. No diagnostic logging (awaits approval, M.4 item 8). The one scored run on the frozen carry-over sets awaits the owner's model and cost, so API AI is not described as better than On-device.

- Stage 6, Step 6 — the conversation test set: `app/src/test/resources/recall_test_set/conversations.json` with 8 invented chats (3 over 60 messages) and 62 questions covering every Step 6 situation at or above its minimum, and `ConversationTestSetTest`, which checks ids, references, counts and each sample's actual structure. No search or scoring; that is Step 14.

- Stage 6B.1 — scan-local working drafts with stable IDs (`ArchivistWorkingDrafts.kt`): the model sees this scan's drafts as D references and may edit, combine or withdraw them; Kotlin validates every operation against the references shown, applies the valid ones, sends one narrow repair request for failed operations that named a shown draft, and otherwise keeps the original plus any valid new wording as a linked alternative for 6B.3. The whole working collection, its passage references (message UUIDs and offsets) and the next draft ID are saved with the 6A checkpoint in one transaction. Earlier chat text follows the two Earlier Context settings (defaults 2,000 / 4,000). Measured on the Stage 6 test chats, no model calls: at the default 8,000-token section every chat is one request with no earlier text; at 2,000-token sections, 12 requests, 4 with earlier text averaging 2,061 tokens (42% added input); at 1,000-token sections, 20 requests, 12 with earlier text averaging 1,390 tokens (largest 2,447; 85% added input). Caching and cost are measured in 6D. PR #240.

- Stage 6B.2 — scoped matching and the encrypted comparison cache (`memory/matching/`): before each section and after each reply, the scanner looks for related Active, Pending and Archived memories and its own working drafts under chat scope and target rules, by meaning with overlapping query windows when the on-device model is available and by exact wording and keywords otherwise, within fixed bounds and at background queue priority. Pending, Archived and draft vectors are reused from two new encrypted tables (database version 38, at most 5,000 entries). Evidence (IDs, status, method, score only for meaning matches, unexamined count and unresolved links) is saved with each draft in the checkpoint for 6B.3; the scanner AI still sees Active memories only, now including still-eligible memories the chat's replies used. Unit tests cover scope and status boundaries, missing and stale vectors, deleted and changed records, keyword-only results, bounded reads and long-turn coverage; `ScannerComparisonCacheInstrumentedTest` covers the migration and cache rules on a device. Query windows were verified with the real EmbeddingGemma tokenizer at the 512-token query allowance (prefix and special tokens included; `ScannerQueryWindowsGemmaTest`, run when `GEMMA_TOKENIZER_JSON` points at the downloaded file, skipped in CI). Seven deliberately introduced defects (a score on keyword matches, a zero unexamined count, real-life drafts eligible in roleplay, unbounded vector reads, the last window stopping short, no halving of an over-allowance piece, evidence not refreshed on a new link) were each caught by the tests and reverted.

- Stage 6B.3 — reconciliation and safe filing (`archivist/ScanReconciliation.kt`): the scanner AI now sees related Pending and Archived memories with their status and returns "already covered" entries, which are saved with the checkpoint and rechecked before filing (a changed covering memory sends its passages back through the scanner). A proposal that only repeats a saved memory is left out; a genuine update is filed linked to it; one comparison request and one ranking request per conversation at most, each only when needed and with safe fallbacks; linked alternatives take one cap slot and point at each other; every decision that relied on a saved memory is checked again inside the filing transaction. The scan outcome reports suggestions kept for review because their edits could not be reconciled. Unit tests (`ScanReconciliationTest`) cover retellings, updates, keyword-only evidence, failures, request limits, retries, ranking, pairs and the approved wording; five deliberately introduced defects were each caught and reverted. `ScanFilingInstrumentedTest` covers the transaction recheck, discards and pair links on a device.
- Stage 6C — Browser match cards and comparison (`PendingMatchPresentation.kt`, `PendingMatchLoader.kt`, `PendingMatchText.kt`): Pending cards with Possible Matches show the closest related memory, its status (Active, Pending, Archived or Superseded), how it was found (Exact Match, Alternative Suggestion, Similarity Match with its live score, or Linked by the Scanner; no keyword layer) and the number of additional matches, in that priority order. Tapping the preview opens that exact pair by memory ID in Possible Match Review (Related Memory, then Additional Matches); only the opened match starts ticked and Pending matches are review-only until 6D. A tapped memory that changed into a non-match or was removed shows the current matches with a notice; every resolution is refused inside the store transaction if the proposal or a selected memory changed after it was shown. Returning restores the list position. No database migration. Unit tests (`PendingMatchPresentationTest`) and `MemoryStoreInstrumentedTest` cover ordering, labels, scores, selection and stale decisions.

- Stage 6D — dedicated Pending-pair review actions (`PendingPairReviewController`, `PendingPairSnapshot`), preserving links and Pending status on keep/edit, discarding only the named suggestion, and activating only an explicitly saved suggestion; both displayed records and relationship sets are rechecked atomically, and legacy approval actions reject Pending targets. Thirteen independent synthetic extraction responses are replayed through production prompt/parsing/draft/matching/filing code; native embedding/cache and encrypted-store review coverage runs in ordinary Android Checks. Detailed results and outstanding phone/provider measurements are in `memory baseline.md`. PR: [Stage 6D branch](https://github.com/SoulPhosphor/speak-gpt/pulls?q=is%3Apr+head%3Afeature%2Fscanner-stage6d). Stage 7 is not implemented.
- Stage 7A, Step 7 — the passage cutter (`conversation/index/PassageCutter.kt`): one message's text, a target size and a supplied size function give ordered, non-overlapping passage positions covering every character exactly once, with the cutter version. Pieces are split only when too large, at paragraphs, then lines, then between words (the platform's word boundaries for writing without spaces), then between user-perceived characters, and last between code points; never at periods; neighbouring pieces are joined while the measured text fits. Connected to nothing: no storage, search text, token counter, screen or log. Every G.5b "With the cutter, in Step 7" test is in `PassageCutterTest`. Stage 7B (Step 8) is next.
- Stage 7B, Step 8 — the passage stores and per-message updating (`conversation/index/`): a separate SQLCipher database (`conversation_index.db`, its own key) with passages (positions, exchange, speaker, time, chat position, fingerprints, scope or unknown, cutter version, vector model), waiting vector rows keyed by search-text fingerprint, model and size, a word index with no stored content and no word positions (FTS5 `content=''`, `detail=none`), and one index-state row per chat. `ConversationIndexUpdater` compares eligible messages (permanent ID, visible to the AI, finished, the canonical version) by ID and original-text fingerprint and replaces, restamps or removes one message per transaction, rechecking the recorded chat version and the compared state inside it; stale or failed runs leave the chat marked for refresh. Search text is the passage with up to a given number of words from the passage before it in the same message, measured with the model's tokenizer, with a per-message title in the model's title slot (default Human/Companion; the name recorded on each message is available for a later identity-aware title) recorded on its passages; the active Glamour's ID recorded on each message is stored as its nullable Glamour reference; vectors unused by any passage are removed. No vectors are made and nothing calls the updater yet. The overlap word count is passed in and is decided before Step 10 connects indexing. Tests: `IndexEligibilityTest`, `PassageSearchTextTest`, `ConversationIndexUpdaterTest`, `PassageSearchTextGemmaTest` (real EmbeddingGemma tokenizer), `ConversationIndexStoreInstrumentedTest` (encrypted store on a device).
- Stage 8, Step 9 — background embedding of passages (`conversation/index/PassageEmbeddingWorker.kt`): one waiting vector at a time, each passage's search text is rebuilt from the message as stored now (permanent ID, original-text fingerprint, recorded title and cutting settings) and must give back the stored fingerprint of the exact model input; that input is counted whole with the model's tokenizer, prefix and special tokens included, and one that does not fit is recorded as failed, never read cut off and never retried for the same text. Vectors are read through the one embedding queue at background priority (a live read waits for at most the running one) with a new exact-input read that refuses rather than cuts, and stored in one transaction that rechecks the row, the passage and the message; a result for changed, hidden or deleted text is thrown away and the chat marked for refresh. Identical input is read once and shared; a model's vectors are kept until the new model's index is complete, then removed. Per-chat progress counts made, waiting and failed passages; the pause is saved and finished vectors persist, so reopening resumes without reading anything twice. No database version change was needed. A temporary model failure stops the pass and leaves the work waiting. Messages come through a supplied interface; the saved-chat reader and every hookup are Step 10, and nothing in the app starts the worker. Tests: `PassageEmbeddingWorkerTest`, `PassageEmbeddingGemmaTest` (real EmbeddingGemma tokenizer), `PassageEmbeddingInstrumentedTest` (encrypted store and the real model on a device).
- Stage 8, Step 10 — keeping the index in step with the chats (`conversation/index/`): `SavedChatIndexSource` reads each chat through the app's chat-reading layer, never writing to it, and gives the index each message's AI-facing text, built by the same functions the chat screen now uses for its requests and Summarizer snapshot (`ui/chat/ModelFacingMessageText.kt`, moved out of the chat screen without change), with eligibility and the scope recorded on the message; a message without a permanent ID is never eligible, so a chat not opened since IDs were introduced has no passages until opening gives its messages IDs and saves it, and it is not read again until then. Its stored version is the chat file's size and time and the restore generation, recorded with the index so an unchanged chat is not read again after a restart; a count of changes reported while the app runs is checked alongside it during an update, so a save is seen at once. One-line hookups after a chat is saved (new messages, finished replies, edits, deletions, hiding, showing, another version), after a chat is deleted or an older chat's rename changes its ID, after a restore or merge, when the embedding model changes, and at app start hand the work to `ConversationIndexCoordinator` on a background thread: a changed chat is compared message by message once it has had no change for 3 seconds (so a streaming reply is compared once, after it finishes), existing chats are caught up newest first, skipped without being read when already up to date under the current settings and model, and passed over while still settling, a deleted chat's entries and unshared vectors are removed in one transaction, a renamed chat's entries move to the new ID, a restore marks every chat for a refresh and removes entries of chats that are gone, and a vector pass is requested afterwards (`IndexVectorPasses`). New store operations: delete a chat, move a chat, mark all for refresh, delete everything. Overlap 20 words, target 256 tokens (Owner, 2026-11-24). Everything is inert until Step 11's setting turns it on. Recorded scope coverage is in G.7. Tests: `ConversationIndexCoordinatorTest` (every G.12 row), `SavedChatIndexReaderTest`, `ConversationIndexUpkeepInstrumentedTest` (real chat storage and encrypted store on a device).
- Stage 9, Step 11 — the Index Conversations setting, status, deletion, database check and notices (`conversation/index/`, `ui/conversationindex/`): the Conversation Index section of RAG Active Memory Settings with the approved wording and shared styles (new `Widget.App.Section.Status` and `AppButton.Destructive.Inline.Centered.BelowStatus`); the saved setting (`ConversationIndexSetting`, off by default) replaces the internal switch, so turning it on catches up existing chats while the app is open, turning it off stops at the next safe point and keeps the index, and deleted or renamed chats are still followed while an index exists; no on-device model blocks turning it on with On-Device Search Model Needed. A failed vector no longer counts as fully indexed (both `finishUpdate` and `recountChatsUsing`); the status counts only chats whose index describes them as stored now under the current settings and model with every vector made, and counts chats not opened since permanent IDs from a new per-chat count (index database version 2; derived stores rebuilt, vectors kept). Delete Conversation Index turns the setting off and removes everything derived, then re-reads the counts. Automatic recovery tells damage (a corrupt file, a missing key) from passing failures (key store unavailable, cannot open now), keeps the vectors it can read, sets the damaged file aside and removes it only after the new index passes its check; recovery failure turns the setting off, and turning it on again retries. Check Database Integrity gains the Conversation Index Database line (no problems, could not be checked, being rebuilt, could not be rebuilt). Indexing Paused (model removed or cannot load) and Indexing Stopped are shown once per ongoing condition on the screen in front, kept in the settings status until recovery is verified. Error Log entries follow the G.13a templates, each ongoing failure once. Tests: `ConversationIndexCountsTest`, `ConversationIndexConditionsTest`, `ConversationIndexRecoveryTest`, new cases in `ConversationIndexCoordinatorTest`, `ConversationIndexSettingsInstrumentedTest` (encrypted store, real damage, recovery and the settings screen on a device).
- Stage 9, Step 11 correction — separate inline stopped status, an unavailable-count explanation, and a persistent deletion-failure snackbar using Material 3 with a shared wrapping-only variant. Database checks show known plain-language causes and retry actions. Error Log entries retain the failed operation, exception chain and related cleanup failures; distinguish deletion of index data, damaged files and recovery files; deduplicate ongoing read/startup failures; and report an unreadable waiting count as unknown. Model diagnostics distinguish loading, self-check and background preparation failures with the actual search consequence. Verified deletion clears obsolete failed/pending rebuild state. Coverage: 12 new JVM regressions and four added cases in `ConversationIndexSettingsInstrumentedTest`; existing exact log assertions updated without reducing coverage. The shared style guide and adoption record are updated; physical-device visual review remains pending.
