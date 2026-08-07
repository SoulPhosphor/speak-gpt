# Speak-GPT Associative Memory Repair Contract

**Revision 26, 2026-08-06; hardening clarifications added 2026-08-07**

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

As of current `main`, the repair starts from these existing foundations:

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
- supersession history that records which new memory superseded which old memory and the exact `at` timestamp;
- durable Archivist run records, transcript claim sealing, and interrupted-run reconciliation that can be reused while replacing the old permanent per-row processed-state model.

These are dependencies, not future phases. Do not rebuild, redesign, or re-audit them unless a specific failing behavior is encountered while wiring the repair.

## 2. Verified defects this repair must correct

### 2.1 Archivist is currently memory-blind

The current `Archivist.kt` sends the model:

1. the Archivist system prompt plus current Memory Types; and
2. the rendered conversation chunk.

It does **not** send relevant existing Associative Memories to the model before extraction.

After the model responds, the app performs exact duplicate suppression and later Possible Match comparison. Those are useful safety layers, but they do not let the Archivist reason about existing memory while deciding what to propose.

### 2.2 Current chunk execution can file visible partial results

The current Archivist can file drafts from an earlier successful chunk and then report a later chunk/conversation failure. It can also file Model Rule drafts from an Associative run before the full conversation succeeds. The revised target is a complete filing boundary for one frozen chat range: temporary proposals may accumulate during the run, but **no user-visible output from that chat range** is committed until all required work for that frozen range succeeds.

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

### 2.5 Target awareness is incomplete

The current API Archivist receives the current Memory Type list, but it is not given a compact catalog of valid named targets. Its current output names a target in prose and the app later tries an exact case-insensitive name match. A miss can therefore become an untargeted proposal even though the app already knows the valid Companion / Project / World / Campaign / RP Character records.

The repaired API path must give the Archivist bounded, app-owned target context before extraction and must validate any returned target reference against that supplied catalog. The model never creates a target record by naming one.

## 3. Required architecture

### 3.1 The core pipeline

For Associative Memory analysis, the required path is:

```text
frozen new chat range
        ↓
token-based conversation chunks / scene segments
        ↓
LOCAL Librarian retrieval using the stamped scene eligibility context
        ↓
ONE Archivist model call for that chunk containing:
  - the new conversation chunk
  - the bounded relevant existing-memory context
  - the bounded valid target catalog needed for that chunk
  - current allowed Memory Types
  - effective extraction policy / Analysis Note when applicable
  - bounded already-proposed-this-run context when needed
        ↓
validated additive proposals + normalized related-existing-memory references
        ↓
temporary per-chat run proposal collection
        ↓
exact dedup + existing local Possible Match safety checks
        ↓
atomic user-visible filing only after that frozen chat range succeeds
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
- Local target-catalog assembly and validation do not require an LLM call.
- If the Librarian falls back to lexical retrieval because the embedding model is unavailable or the vector index is incomplete, continue with the bounded lexical result set and record truthful diagnostics. Do not compensate by sending the whole library to the paid model.

### 3.3 Relevant existing-memory retrieval

Before each Associative Memory Archivist request:

1. Use the conversation text itself for local retrieval. Do not spend an LLM call generating a search query.
2. Apply actual scope and target eligibility before ranking.
3. Respect Companion target isolation. A segment associated with one companion must never retrieve another companion's private memories unless an already-approved eligibility rule explicitly allows it.
4. Respect the real-life / roleplay wall and the stamped scene context captured on transcript rows. Do **not** assign one companion/world/campaign/project context to an entire chat merely because it appears first. If one network chunk contains transcript rows with different stamped scene contexts, evaluate retrieval separately for those scene-consistent segments and combine only the bounded results that are valid for the corresponding segment. The implementation may instead split the network chunk at a scene boundary when that is simpler and stays within the approved cost behavior.
5. Retrieve Active Associative Memories only for the pre-extraction context. Existing Pending/Archived/Superseded items remain protected by deterministic duplicate checks and Possible Match after extraction rather than being dumped into every Archivist prompt.
6. Initial prompt target: **up to 10 highest-relevance memories**. The local retrieval step may examine/union a slightly broader bounded set, with a hard ceiling of 15 prompt candidates, when needed to preserve recall across a multi-topic chunk. The prompt includes only as many complete memories as fit the reserved input budget. Never truncate a memory into a misleading fragment merely to hit the count.
7. Multi-topic chunks must not silently collapse retrieval onto only the dominant topic. If a single whole-chunk query demonstrably misses a separately discussed topic with a strong relevant existing memory, use bounded local message/window searches and union/deduplicate their results under the same 10/15 prompt ceiling. This is still local retrieval and does not add a paid model call.
8. Reuse the Librarian's eligibility, embedding, vector-index, lexical-fallback, and ranking machinery. **Do not blindly inherit live chat-injection policy as the reconciliation policy.** Live cooldowns, live memory count/token limits, Memory Priority, and other delivery controls must not become hard gates for Archivist awareness. Reconciliation is relevance-first: importance/recency/context boosts may break close ties, but they must not cause a materially stronger semantic or lexical match to be omitted in favor of a weaker one.
9. Send only fields needed for reasoning:
   - request-local memory reference;
   - complete memory text;
   - actual scope/target;
   - visible Type name or No Type when useful.
10. Do not send importance, provenance, source chat, run metadata, embedding values, or hidden ranking scores to the Archivist.

The current Mem0 V3 pipeline is the architecture reference for this bounded pre-retrieval pattern: it embeds the new messages, vector-searches existing memory with `top_k=10`, and includes those existing memories in a single extraction call. Speak-GPT borrows the architecture only; no Mem0 code or dependency is required.

### 3.4 Valid target catalog and request-local references

Before each Associative Archivist call, assemble a compact target catalog from current phone state for the scopes that can legitimately apply to that chunk/scene. The catalog may include Companion, Project, World, Campaign, and RP Character records as relevant, with:

- stable target ID kept by the app;
- display name;
- target kind/scope;
- lifecycle/status when needed to prevent an invalid active placement.

The model must select only from supplied targets. Unknown or stale references are rejected/quarantined; they never fall back to a different scope or silently become a different target. If the conversation suggests a named target that cannot be resolved safely, preserve it as an unresolved placement for human review rather than inventing or auto-creating a record. Existing Pending rules for unresolved placement remain authoritative, including exclusion from bulk acceptance where applicable.

For API prompts, the app **may map stable database IDs to short request-local aliases** such as `M1`, `M2`, `T1`, `T2` and map them back after parsing. This reduces token cost and ID-copy hallucinations. Request-local aliases are temporary protocol data only; they are never stored in a Pending or Active memory. After validation, all internal relationships use the real stable IDs.

### 3.5 Archivist behavior with existing memory

The Archivist remains additive and human-reviewed.

For each new supported fact/context item, it should:

- emit nothing when the information is already adequately represented by an existing memory;
- emit a new proposal when genuinely new information is worth saving;
- emit a new proposal linked to the relevant existing memory reference when the conversation updates, contradicts, narrows, extends, or meaningfully continues an existing memory;
- never directly update, delete, archive, supersede, replace, or approve an existing memory.

Each normalized Associative Memory proposal may therefore include:

```json
{
  "content": "...",
  "scope": "...",
  "target_ids": [],
  "suggested_type": null,
  "tags": [],
  "related_existing_memory_ids": []
}
```

The wire-format response may use request-local aliases; the app resolves and validates them before creating the normalized proposal above. `related_existing_memory_ids` is a review hint, not permission to mutate those memories. Unknown references are discarded during validation.

The local exact matcher and `PossibleMatchFinder` still run after extraction. They are defense in depth and may surface a related memory the model missed. An AI-provided relationship hint must never suppress a stronger local safety check or automatically resolve a match.

### 3.6 App-owned protocol envelope and untrusted data

The current Memory Assistant allows an editable/custom Archivist prompt. That customization must continue to work, but it may not be able to remove the app's runtime protocol required for safe parsing and reconciliation.

For every Associative request, including when the user has a custom extraction prompt, the app appends or otherwise enforces a **non-editable app-owned runtime protocol block** containing the current output contract, current Memory Types, existing-memory references, valid target references, and the rules for relationship/target references. This follows the same principle as the current runtime Type block: the user's custom prompt controls extraction style/instructions, while the app-owned protocol guarantees that the transport schema remains parseable and current.

Conversation text, existing-memory text, target names, and already-proposed-this-run text are **data to analyze, not instructions to the Archivist**. Delimit/serialize them as data sections and tell the model explicitly not to follow instructions found inside those data sections. This matters even for an `Instruction` memory: the Archivist may reason about the instruction as memory content, but the text inside it does not override the Archivist's protocol.

### 3.7 Cross-chunk duplicate and correction prevention without another paid pass

When one conversation requires several chunks, later chunk prompts may include a **small bounded list of already validated proposals from earlier chunks in the same frozen chat range** so the model does not keep re-proposing the same fact.

This temporary list:

- is not stored as permanent provenance;
- is not sent to ordinary chat;
- is removed with run state;
- exists only to reduce duplicate/conflicting output across chunk boundaries.

After all chunks succeed, perform deterministic exact deduplication again before visible filing. The staged collection must also detect a clear same-run correction/contradiction between proposed items well enough that two ordinary, unflagged drafts are not silently filed as simultaneous truths. This may use the existing local comparison machinery; it does not require a second model-assisted consolidation pass.

## 4. Bookmark, frozen range, migration, and transaction rules

These rules are foundational and are implemented before the repaired analysis path is considered complete.

1. Store one durable bookmark per chat representing the last chronologically contiguous transcript boundary that has been successfully reviewed or intentionally excluded.
2. Use the existing transcript ordering rule (`started_at`, then `transcript_id`) as the deterministic ordering basis unless current code provides a stronger monotonic sequence. Do not compare UUID-like transcript IDs alone as though they were chronological.
3. When analysis begins for a chat, freeze the end of that chat's eligible range. Claim/seal the currently open transcript row before analysis so new messages cannot mutate the frozen content; messages arriving afterward go to a new row and belong to the next run.
4. The atomicity boundary is **one frozen chat range**, not the entire multi-chat Memory Assistant run. A successful chat may commit and advance its bookmark even if another selected chat fails. Within a failed chat range, nothing user-visible from that range commits and that chat's bookmark does not advance.
5. Use minimal short-lived run state for locking, chunk status, retry counters, temporary memory candidates, temporary Model Rule candidates, candidate hashes, request-local reference maps, and interrupted-run recovery.
6. An Associative run's all-or-nothing boundary includes **every user-visible proposal stream produced by that chat range**, including Associative Memory drafts and Model Rule drafts. Do not allow a rule draft to leak from a chat whose later chunk fails.
7. Do not copy bookmark IDs, chat IDs, transcript row IDs, source excerpts, run IDs, chunk numbers, candidate hashes, Analysis Notes, request-local aliases, or conversation policy into a Pending or Active memory.
8. Do not advance the bookmark until the complete valid proposal set for that frozen chat range has been safely filed.
9. On provider failure, unreadable/truncated output after bounded retry, cancellation, or process death:
   - file no new visible Pending items from the incomplete frozen chat range;
   - leave that chat's bookmark unchanged;
   - preserve only the minimum encrypted recovery state needed to resume or safely restart;
   - show the real failure rather than claiming no memories were found.
10. Once one chat range files successfully, advance that chat's bookmark and clean its temporary run state.
11. Preserve the already-approved analysis-type behavior. One run has one selected analysis type; this repair does not add a `Both` mode or silently run a second analysis type.

### 4.1 Migration from the current transcript queue

The current queue has `pending`, `processed`, and `excluded` review states plus claim stamps. Migration must not guess a bookmark from the newest row and must not force a mass re-analysis.

Before initializing bookmarks:

1. reconcile/release stale dead-run claims using the existing recovery machinery;
2. order each chat's transcript rows by the existing deterministic order;
3. initialize the bookmark only through the **contiguous terminal prefix** of that ordered history, where terminal means already `processed` or intentionally `excluded`;
4. stop at the first still-pending row; never jump a bookmark across a pending gap merely because a later row is already processed/excluded;
5. if there is no pending gap, the bookmark may advance through the last terminal row;
6. after the migration, when a pending gap is successfully processed, the bookmark may advance through any already-terminal rows immediately following it without re-analyzing those terminal rows.

This preserves every currently pending item exactly once while avoiding a paid replay of already-reviewed history.

The old per-row columns may remain temporarily as migration/recovery scaffolding while Stage B is being proven, but after the bookmark path is live they must not remain a second competing source of eligibility truth.

## 5. Chunking and request budget

Use the already-approved UI choices from `memory_retrieval_and_analysis_ui_copy.md`.

- Small targets ~4,000 transcript tokens.
- Standard targets ~8,000 transcript tokens.
- Large targets ~16,000 transcript tokens.
- Custom uses the user's entered transcript-token target.
- Auto uses verified model/provider/endpoint/manual limits when available. With no verified limit, Auto should use the ordinary Standard-sized target rather than inventing a giant-context strategy.

The selected chunk size is the **conversation-text target**, not total request size. Reserve room for:

- Archivist system/instruction prompt and app-owned protocol envelope;
- current Memory Types;
- bounded target catalog;
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

- Reuse the existing durable run/claim/recovery machinery where it already satisfies this contract.
- Implement/migrate to one durable bookmark per chat using the contiguous-prefix migration rule in §4.1.
- Freeze and seal each chat range independently.
- Make the user-visible commit boundary per frozen chat range, not per chunk and not all selected chats as one giant transaction.
- Stage every user-visible Associative output stream, including Model Rule drafts, until that chat range succeeds.
- Prove messages arriving during a run are left for the next run.

### Stage C — bounded local existing-memory and target awareness

- Reuse the Librarian's existing local eligibility/vector/lexical machinery rather than creating a new retrieval engine.
- Add an Archivist/reconciliation retrieval path or policy where needed so live chat-delivery settings do not become inappropriate reconciliation gates.
- Respect transcript-stamped scene context when retrieving; do not infer one context for the whole chat from the first row.
- Retrieve the bounded relevant set before each Associative Memory Archivist call.
- Assemble the bounded valid target catalog before the call.
- Render existing-memory and target references through the app-owned protocol envelope, using request-local aliases if useful, and normalize them back to stable IDs after parsing.
- Extend the app-owned response schema/parser with validated related-existing-memory and target references.
- Keep the network cost at one Archivist call per chunk.

### Stage D — complete candidate boundary and Possible Match integration

- Hold validated memory and Model Rule candidates in temporary run state until the frozen chat range succeeds.
- Carry bounded earlier candidates forward between chunks for duplicate/correction prevention.
- Exact-deduplicate after collection and prevent clear same-run contradictions from becoming two ordinary unflagged drafts.
- Feed AI relationship hints plus existing deterministic/semantic checks into the existing Possible Match path.
- File the complete valid set through the canonical Pending/model-rule draft paths only after success, with rollback/no bookmark advance if the chat-range commit fails.
- Preserve the existing user-controlled resolution actions.
- Surface the recorded supersession date on Superseded/history UI where that lifecycle is shown.

### Stage E — retry/failure integration and existing UI

- Keep existing user-approved Memory Assistant status/error wording where applicable.
- Keep custom Archivist prompts compatible by enforcing the app-owned runtime protocol envelope after/beside custom text.
- Treat transcript/memory/target text as delimited data, not executable Archivist instructions.
- Distinguish genuine empty extraction from provider failure, context rejection, truncation, malformed output, cancellation, interruption, and final filing failure.
- Bounded shrink/repair only.
- No partial visible filing and no bookmark advance on incomplete chat ranges.
- Do not add new setup screens or controls unless an already-approved control is missing from the current app.

### Stage F — verification before any completion claim

Automated tests must cover at least:

1. **Already known:** existing Active memory “favorite color is green”; conversation repeats green; no duplicate proposal reaches Pending.
2. **Changed fact:** existing Active memory says green; conversation clearly says favorite color is purple now; the Archivist receives the green memory, creates the purple proposal, links the old ID, and the existing green memory is surfaced for Review.
3. **New unrelated fact:** no relevant existing memory; new proposal reaches ordinary Pending without a fabricated conflict.
4. **Companion isolation:** a Companion memory for one companion is never supplied as existing-memory context for another companion unless an already-approved eligibility rule explicitly permits it.
5. **Scene transition:** one chat changes companion/project/world/campaign context inside the frozen range; retrieval honors each transcript row's stamped scene context and does not apply the first row's context to the whole chat.
6. **Multiple topics:** one chunk containing several memorable topics does not stop after the first.
7. **Multi-topic retrieval recall:** when two distinct topics in one chunk each have a strong relevant existing memory and both fit the bounded context, both are available to the Archivist rather than only the dominant topic's memory.
8. **Multi-chunk duplicate prevention:** repeated information across chunk boundaries does not create duplicate visible drafts.
9. **Same-run correction:** an earlier chunk proposes one state and a later chunk clearly corrects it; the final filing does not contain two ordinary unflagged contradictory drafts.
10. **Late chunk failure:** if an earlier chunk succeeds and the final required chunk fails, that frozen chat range leaves no new visible Memory or Model Rule drafts and its bookmark does not advance.
11. **Multi-chat partial run:** one selected chat succeeds and another fails; the successful chat commits/advances independently while the failed chat commits nothing and keeps its bookmark.
12. **Cancellation/process interruption:** bookmark remains truthful and no material is silently skipped.
13. **Bookmark migration gap:** processed/excluded history followed by a pending gap and then later terminal rows initializes before the gap; no pending row is skipped and no already-terminal history is unnecessarily re-analyzed.
14. **Embedding unavailable/incomplete:** bounded lexical fallback is used; the entire database is never sent to the paid model.
15. **Reconciliation policy:** a materially stronger old-memory match is not displaced solely by live delivery settings, importance, recency, cooldown, or Memory Priority.
16. **Target catalog:** a valid supplied target resolves to its stable ID; an unknown/stale target cannot silently become another target or auto-create a record.
17. **Custom prompt compatibility:** a saved custom extraction prompt still receives/enforces the current app-owned output/target/existing-memory protocol and produces parseable normalized candidates.
18. **Prompt-injection resistance:** instructions embedded inside transcript text or existing-memory content cannot replace the Archivist protocol or cause direct mutation.
19. **Supersession:** Save & Supersede records the relationship/timestamp and the Superseded/history surface can show the recorded date.
20. **Existing Possible Match safety:** a locally detected semantic match still reaches Review even if the Archivist omitted its relationship reference.

### End-to-end device proof

The repair is not complete until a real on-device test proves the changed-fact path end to end:

1. save an Active memory such as `The user's favorite color is green.`;
2. have a conversation that clearly changes it to purple;
3. run the API Memory Assistant using the configured app endpoint/model;
4. verify debug/test evidence that the local Librarian retrieved the green memory before the Archivist request;
5. verify the Archivist request contained only the bounded relevant memory/target context, not the full database;
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

A graph layer may be considered later only as a separate future feature after ordinary Associative Memory works end to end. Existing stable memory IDs, scopes/targets, timestamps, and supersession history should be preserved so a future graph layer can reference existing data without throwing this work away.

## 9. Computer Memory Review compatibility

`project-plan.md` keeps Computer Memory Review as a separate later feature. Do not pull that entire workflow into this repair.

When Computer Memory Review is eventually built, it must preserve the same logical memory-awareness contract: the external reviewer must have a bounded/searchable read-only representation of existing memories and valid target/type catalogs so it can distinguish new information from existing, changed, or conflicting information. Imported results still go through the same validation, Possible Match, and Pending review boundaries. The computer route never writes directly to Active memory.

## 10. Research basis

The architecture choice is not a bespoke experiment.

Current Mem0 OSS V3 (`mem0/memory/main.py`, V3 phased batch pipeline) performs local/vector-store retrieval of existing memories with `top_k=10` before a single LLM extraction call. It also maps database memory UUIDs to short request-local integer IDs before sending them to the model, reducing ID-copy hallucination risk. Its additive extraction prompt (`mem0/configs/prompts.py`) supplies Existing Memories to the model and skips already-captured information.

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