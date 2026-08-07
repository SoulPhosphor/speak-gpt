# Speak-GPT Associative Memory Repair Contract

**Revision 26, 2026-08-06**

## Authority and scope

This document is the binding technical contract for the owner-reported Associative Memory repair. It is **not** a second app-wide roadmap. `project-plan.md` at the repository root remains the only app-wide scheduling roadmap.

The root roadmap already contains the rule that built features stay closed unless the owner reports a specific problem. The owner has now reported a specific problem in the existing API Memory Assistant: the Archivist analyzes conversation text without being given a bounded set of relevant existing memories, so it cannot reliably know what is already known, what changed, what conflicts, or what should be treated as a duplicate before it creates proposals. This document defines that narrow repair and the dependency order required to make it work end to end.

Revision 26 incorporates the still-binding product decisions from Revision 24 and `revision_25_binding_clarifications.md`. Where older phase wording, evaluation-harness wording, or implementation order conflicts with this document, **Revision 26 controls**. `revision_25_binding_clarifications.md` remains a historical decision record and must not override a later Revision 26 rule.

Current code on `main` is the truth about what already exists. Exact user-facing wording remains controlled by the focused copy specifications named below. Do not ask the owner to redesign already-approved controls unless a real implementation conflict is found.

### Must-read implementation references

- `project-plan.md` — app-wide scheduling and the rule that a feature ships whole.
- `Memory System/memory_controls_and_pending_ui_copy.md` — exact Memory Controls, Pending, and Possible Match wording/layout.
- `Memory System/memory_retrieval_and_analysis_ui_copy.md` — exact live retrieval and archiver chunk-choice wording.
- `Memory System/owner_approved_rules.md` — still-binding owner rules where they are not superseded by later decisions.
- `Memory System/revision_25_binding_clarifications.md` — historical record of decisions already incorporated here.
- `MemoryStore.kt` — live database schema and migrations.
- `Librarian.kt` — existing on-device embedding/retrieval implementation.
- `Archivist.kt` / `ArchivistPrompt.kt` / `ArchivistResponseParser.kt` — current API analysis path to repair.
- `PossibleMatchFinder.kt` and `MemoryPossibleMatchReviewActivity.kt` — existing local conflict detection and human resolution path. Do not redesign them as part of this repair.

## 1. What already exists and must not be rebuilt

As of current `main` (`f02374edeaa9db9eeb9ac573e6e0a294ea6a6ad6`), the repair starts from these existing foundations:

- SQLCipher memory database through v26;
- user-owned Memory Types with No Type support;
- 0-5 optional importance, default 0;
- retired Associative Memory title, fixed kind, and permanent provenance/source-chat columns;
- Companion targeting and companion-deletion cascade work;
- canonical Pending filing/domain services;
- on-device Librarian semantic search with scope/target filtering, `topK`, and complete-set lexical fallback when embeddings are unavailable;
- exact duplicate classification;
- local semantic Possible Match detection;
- Pending browser and the approved review flow;
- `Save & Edit Old Memory`, `Save & Supersede`, and `Save & Replace` atomic resolution actions;
- supersession history that records which new memory superseded which old memory and the exact `at` timestamp.

These are dependencies, not future phases. Do not rebuild, redesign, or re-audit them unless a specific failing behavior is encountered while wiring the repair.

## 2. Verified defects this repair must correct

### 2.1 Archivist is currently memory-blind

The current `Archivist.kt` sends the model:

1. the Archivist system prompt plus current Memory Types; and
2. the rendered conversation chunk.

It does **not** send relevant existing Associative Memories to the model before extraction.

After the model responds, the app performs exact duplicate suppression and later Possible Match comparison. Those are useful safety layers, but they do not let the Archivist reason about existing memory while deciding what to propose.

### 2.2 Current chunk execution can file visible partial results

The current Archivist can file drafts from an earlier successful chunk and then report a later chunk/conversation failure. The revised target is a complete filing boundary for one frozen conversation range: temporary candidates may accumulate during the run, but visible Pending filing occurs only after all required work for that frozen range succeeds.

### 2.3 Bookmark/run-state dependency was placed too late

The approved target remains **one durable bookmark per chat plus minimal temporary run state**. The previous plan asked earlier archiver phases to behave correctly on failure/process death while postponing the durable bookmark machinery until a later phase. That order was invalid. Bookmark/frozen-range behavior is foundation work for the repaired archiver, not late polish.

### 2.4 The standalone Phase 3 evaluation harness is not part of the product path

The branch `claude/phase-3-eval-harness-16p9m2` and its large standalone evaluation harness are **not a completion dependency and must not be merged as part of this repair**.

The approved initial chunk choices are already decided:

- `Auto`
- `Small · About 4,000 Tokens`
- `Standard · About 8,000 Tokens`
- `Large · About 16,000 Tokens`
- `Custom`

Real on-device use may justify later tuning. A separate multi-model laboratory, paid model tournament, or synthetic benchmark campaign is not required before the product can work.

## 3. Required architecture

### 3.1 The core pipeline

For Associative Memory analysis, the required path is:

```text
frozen new conversation range
        ↓
token-based conversation chunks
        ↓
LOCAL Librarian search for relevant existing memories
        ↓
ONE Archivist model call for that chunk containing:
  - the new conversation chunk
  - the bounded relevant existing-memory context
  - current allowed Memory Types
  - effective extraction policy / Analysis Note when applicable
  - bounded already-proposed-this-run context when needed
        ↓
validated additive proposals + related existing-memory IDs
        ↓
temporary run candidate collection
        ↓
exact dedup + existing local Possible Match safety checks
        ↓
canonical Pending filing only after the frozen range succeeds
        ↓
human review: Save / Discard / Edit Old / Supersede / Replace
```

This is the architecture to implement. Do not replace it with a second paid reconciliation model call by default, a graph database, or a model scan of the full memory library.

### 3.2 Cost boundary

The on-device embedding model is the database search layer.

- The paid Archivist model must **never** be sent the entire Associative Memory database merely to find relevant records.
- Do not make one paid model call per existing memory.
- Do not add a second paid reconciliation call for the normal path.
- The normal cost shape is **one Archivist request per conversation chunk**.
- Local embedding/lexical search selects the small existing-memory context before that request.
- If the Librarian falls back to lexical retrieval because the embedding model is unavailable or the vector index is incomplete, continue with the bounded lexical result set and record truthful diagnostics. Do not compensate by sending the whole library to the paid model.

### 3.3 Relevant existing-memory retrieval

Before each Associative Memory Archivist request:

1. Use the conversation chunk itself as the semantic search query. Do not spend an LLM call generating a search query.
2. Apply actual scope and target eligibility before ranking.
3. Respect Companion target isolation. A chunk from a conversation with Slate must never retrieve Ash-only memories for Archivist context.
4. Retrieve Active Associative Memories only for the pre-extraction context. Existing Pending/Archived/Superseded items remain protected by deterministic duplicate checks and Possible Match after extraction rather than being dumped into every Archivist prompt.
5. Initial retrieval target: **up to 10 highest-ranking relevant memories**. The implementation may request up to a hard ceiling of 15 when necessary, but the prompt includes only as many complete memories as fit the reserved input budget. Never truncate a memory into a misleading fragment merely to hit the count.
6. Send only fields needed for reasoning:
   - stable memory ID;
   - complete memory text;
   - actual scope/target;
   - visible Type name or No Type when useful.
7. Do not send importance, provenance, source chat, run metadata, embedding values, or hidden ranking scores to the Archivist.

The current Mem0 V3 pipeline is the architecture reference for this bounded pre-retrieval pattern: it embeds the new messages, vector-searches existing memory with `top_k=10`, and includes those existing memories in a single extraction call. Speak-GPT borrows the architecture only; no Mem0 code or dependency is required.

### 3.4 Archivist behavior with existing memory

The Archivist remains additive and human-reviewed.

For each new supported fact/context item, it should:

- emit nothing when the information is already adequately represented by an existing memory;
- emit a new proposal when genuinely new information is worth saving;
- emit a new proposal linked to the relevant existing memory ID when the conversation updates, contradicts, narrows, extends, or meaningfully continues an existing memory;
- never directly update, delete, archive, supersede, replace, or approve an existing memory.

Each Associative Memory proposal may therefore include:

```json
{
  "content": "...",
  "scope": "...",
  "target": null,
  "suggested_type": null,
  "tags": [],
  "related_existing_memory_ids": []
}
```

`related_existing_memory_ids` is a review hint, not permission to mutate those memories. IDs must come only from the existing-memory context supplied by the app. Unknown IDs are discarded during validation.

The local exact matcher and `PossibleMatchFinder` still run after extraction. They are defense in depth and may surface a related memory the model missed. An AI-provided relationship hint must never suppress a stronger local safety check.

### 3.5 Cross-chunk duplicate prevention without another paid pass

When one conversation requires several chunks, later chunk prompts may include a **small bounded list of already validated proposals from earlier chunks in the same run** so the model does not keep re-proposing the same fact.

This temporary list:

- is not stored as permanent provenance;
- is not sent to ordinary chat;
- is removed with run state;
- exists only to reduce duplicate output across chunk boundaries.

After all chunks succeed, perform deterministic exact deduplication again before visible filing. Do not require a second model-assisted consolidation pass merely to merge the run.

## 4. Bookmark, frozen range, and transaction rules

These rules are foundational and are implemented before the repaired analysis path is considered complete.

1. Store one durable bookmark per chat representing the last message successfully reviewed and safely filed.
2. When analysis begins, freeze the end of the eligible range.
3. Messages arriving after that frozen end belong to the next run.
4. Use minimal short-lived run state for locking, chunk status, retry counters, temporary candidates, candidate hashes, and interrupted-run recovery.
5. Do not copy bookmark IDs, chat IDs, transcript row IDs, source excerpts, run IDs, chunk numbers, candidate hashes, Analysis Notes, or conversation policy into a Pending or Active memory.
6. Do not advance the bookmark until the complete valid proposal set for the frozen range has been safely filed.
7. On provider failure, unreadable/truncated output after bounded retry, cancellation, or process death:
   - file no new visible Pending items from the incomplete frozen range;
   - leave the bookmark unchanged;
   - preserve only the minimum recovery state needed to resume or safely restart;
   - show the real failure rather than claiming no memories were found.
8. Once filing succeeds, advance the bookmark and clean temporary run state.

Migration from the current transcript-row queue may reuse existing rows as implementation scaffolding, but the product end state remains the bookmark architecture. Do not keep permanent per-row processing state as a second competing source of truth after the bookmark replacement is live.

## 5. Chunking and request budget

Use the already-approved UI choices from `memory_retrieval_and_analysis_ui_copy.md`.

- Small targets ~4,000 transcript tokens.
- Standard targets ~8,000 transcript tokens.
- Large targets ~16,000 transcript tokens.
- Custom uses the user's entered transcript-token target.
- Auto uses verified model/provider/endpoint/manual limits when available. With no verified limit, Auto should use the ordinary Standard-sized target rather than inventing a giant-context strategy.

The selected chunk size is the **conversation-text target**, not total request size. Reserve room for:

- Archivist system/instruction prompt;
- current Memory Types;
- bounded existing-memory context;
- bounded already-proposed-this-run context;
- structured output / JSON schema overhead;
- Analysis Note when present;
- expected output;
- safety margin.

Preserve whole messages whenever possible. If one message exceeds the safe transcript budget, split at paragraph boundaries, then sentence boundaries if necessary. Do not keep the old 200,000-character ceiling as the request-sizing fallback.

For a verified context rejection or clear truncation, use bounded shrink-and-retry. Never loop indefinitely.

## 6. Existing UI and review behavior stays authoritative

Do not redesign the existing review experience while repairing the backend.

The binding UI contract remains `memory_controls_and_pending_ui_copy.md`, including:

- complete memory content visible in Pending;
- actual scope/target visible;
- Type/No Type visible and editable;
- tags visible;
- importance visible only when enabled;
- ordinary Pending save/discard controls;
- Possible Match `Review` control;
- `Accept All` exclusions;
- proposal first on the Possible Match Review page;
- selectable existing matches;
- resolution order:
  1. `Save & Edit Old Memory`
  2. `Save & Supersede`
  3. `Save & Replace`

### 6.1 Supersession date

Supersession already records an exact timestamp in `memory_supersessions.at`, and the superseded memory's `updated_at` is also refreshed when the action is applied.

When a Superseded memory or supersession history is shown to the user, **show the recorded supersession date using the app's existing date presentation**. Do not invent a second timestamp system or new date format. Backup/restore must preserve the existing supersession relationship and timestamp.

## 7. Implementation order for the repair

This is **one feature** under the root roadmap's “feature ships whole” rule. The stages below are dependency order inside one repair branch. They are not separate shipped phases and none may be reported to the owner as “Memory fixed” by itself.

### Stage A — truthful foundation/status cleanup

- Treat database v26, Librarian, Pending, Possible Match, and resolution UI as existing foundations.
- Remove the old Phase 3 evaluation harness from the required path.
- Remove stale statements that Phase 2/v24-v26 are not on `main`.
- Do not merge `claude/phase-3-eval-harness-16p9m2` for this repair.

### Stage B — bookmark and frozen-run foundation

- Implement/migrate to one durable bookmark per chat.
- Freeze the run end.
- Add only the minimum temporary run state required for restart safety and all-or-nothing visible filing.
- Prove messages arriving during a run are left for the next run.

### Stage C — bounded local existing-memory awareness

- Reuse `Librarian.search` rather than creating a new retrieval engine.
- Retrieve the bounded relevant set before each Associative Memory Archivist call.
- Render those memories with stable IDs into the existing Archivist request.
- Extend the app-owned response schema/parser with validated `related_existing_memory_ids`.
- Keep the network cost at one Archivist call per chunk.

### Stage D — complete candidate boundary and Possible Match integration

- Hold validated candidates in temporary run state until the frozen range succeeds.
- Carry bounded prior-run candidates forward between chunks for duplicate prevention.
- Exact-deduplicate after collection.
- Feed AI relationship hints plus existing deterministic/semantic checks into the existing Possible Match path.
- File the complete valid set through the canonical Pending path only after success.
- Preserve the existing user-controlled resolution actions.
- Surface the recorded supersession date on Superseded/history UI where that lifecycle is shown.

### Stage E — retry/failure integration and existing UI

- Keep existing user-approved Memory Assistant status/error wording where applicable.
- Distinguish genuine empty extraction from provider failure, context rejection, truncation, malformed output, cancellation, and interruption.
- Bounded shrink/repair only.
- No partial visible filing and no bookmark advance on incomplete runs.
- Do not add new setup screens or controls unless an already-approved control is missing from the current app.

### Stage F — verification before any completion claim

Automated tests must cover at least:

1. **Already known:** existing Active memory “favorite color is green”; conversation repeats green; no duplicate proposal reaches Pending.
2. **Changed fact:** existing Active memory says green; conversation clearly says favorite color is purple now; the Archivist receives the green memory, creates the purple proposal, links the old ID, and the existing green memory is surfaced for Review.
3. **New unrelated fact:** no relevant existing memory; new proposal reaches ordinary Pending without a fabricated conflict.
4. **Companion isolation:** a Companion memory for one companion is never supplied as existing-memory context for another companion.
5. **Multiple topics:** one chunk containing several memorable topics does not stop after the first.
6. **Multi-chunk duplicate prevention:** repeated information across chunk boundaries does not create duplicate visible drafts.
7. **Late chunk failure:** if an earlier chunk succeeds and the final required chunk fails, the frozen range leaves no new visible Pending items and the bookmark does not advance.
8. **Cancellation/process interruption:** bookmark remains truthful and no material is silently skipped.
9. **Embedding unavailable/incomplete:** bounded lexical fallback is used; the entire database is never sent to the paid model.
10. **Supersession:** Save & Supersede records the relationship/timestamp and the Superseded/history surface can show the recorded date.
11. **Existing Possible Match safety:** a locally detected semantic match still reaches Review even if the Archivist omitted its ID.

### End-to-end device proof

The repair is not complete until a real on-device test proves the changed-fact path end to end:

1. save an Active memory such as `The user's favorite color is green.`;
2. have a conversation that clearly changes it to purple;
3. run the API Memory Assistant using the configured app endpoint/model;
4. verify debug/test evidence that the local Librarian retrieved the green memory before the Archivist request;
5. verify the Archivist request contained only the bounded relevant memory context, not the full database;
6. verify the purple proposal reaches Pending and the green memory appears in Possible Match Review;
7. choose a resolution and verify the resulting lifecycle state and supersession date when applicable.

CI green alone is not completion. A coding model's statement that the architecture is present is not completion. The tested end-to-end behavior is the completion gate.

## 8. What this repair explicitly does not build

Do not expand this feature into:

- a graph database or Graphiti/Zep port;
- entity/relationship extraction infrastructure;
- a second LLM reconciliation pass in the normal API path;
- a whole-database LLM audit;
- a standalone prompt/chunk evaluation laboratory;
- model-vs-model paid benchmarking;
- new Memory Browser or Pending redesign;
- new Possible Match resolution actions;
- new provenance fields;
- new companion quotas or protected-capacity controls;
- a replacement Librarian/vector engine;
- changes to Lorebooks, roleplay cards, VAD, Whisper, provider routing, or unrelated app features.

A graph layer may be considered later only as a separate future feature after ordinary Associative Memory works end to end. Existing stable memory IDs, scopes/targets, timestamps, and supersession history should be preserved so a future graph layer can reference them without throwing this work away.

## 9. Computer Memory Review compatibility

`project-plan.md` keeps Computer Memory Review as a separate later feature. Do not pull that entire workflow into this repair.

When Computer Memory Review is eventually built, it must preserve the same logical memory-awareness contract: the external reviewer must have a bounded/searchable read-only representation of existing memories and valid target/type catalogs so it can distinguish new information from existing, changed, or conflicting information. Imported results still go through the same validation, Possible Match, and Pending review boundaries. The computer route never writes directly to Active memory.

## 10. Research basis

The architecture choice is not a bespoke experiment.

Current Mem0 OSS V3 (`mem0/memory/main.py`, V3 phased batch pipeline) performs local/vector-store retrieval of existing memories with `top_k=10` before a single LLM extraction call. Its additive extraction prompt (`mem0/configs/prompts.py`) supplies Existing Memories to the model, skips already-captured information, and lets new memories reference related existing-memory IDs for updated preferences, continuations, and contradictions.

Speak-GPT deliberately keeps a stronger human-approval boundary than Mem0: relationship detection may help route review, but the model never directly updates/deletes/supersedes an existing memory.

No external library is required to implement this. Reuse the Kotlin/Android/SQLCipher/Librarian code already in the app.

## 11. Completion language

Use these terms precisely when reporting work:

- **Documented** — requirement exists in the current spec.
- **Implemented** — corresponding code exists on the repair branch.
- **Tested** — an automated test exercises the behavior.
- **Verified end to end** — the actual app path has been exercised through the expected result.
- **Missing/Broken** — the behavior does not yet meet this contract.

Do not say **fixed**, **complete**, **done**, or equivalent for the Associative Memory repair until Stage F and the on-device changed-fact proof have passed.
