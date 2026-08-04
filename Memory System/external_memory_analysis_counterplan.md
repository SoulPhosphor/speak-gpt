# Speak-GPT Memory Systems: Canonical Recovery Plan

**Revision 24, 2026-08-04**

This is the active memory-system plan. It records the owner's decisions, the required implementation order, the archiver contract, companion-memory behavior, conversation-level memory policies, and the completion gates for each phase. Existing code is evidence of what was built, not proof that the behavior was approved.

## 1. Product Goal

The memory system should reduce work, not create a second job.

It must:

1. review new conversation material after the last successful bookmark;
2. propose useful General memories, companion-specific memories, and, when selected, Model Rules;
3. check the existing database before filing duplicates or updates;
4. show everything the user is approving directly in Pending for fast scanning;
5. retrieve several relevant approved memories during chats with the on-device embedding model, within a bounded memory-context budget;
6. keep roleplay memory separate from non-roleplay memory;
7. keep companion-specific continuity and behavior guidance isolated to the intended companion;
8. let each conversation control which memory pools it may use and which proposal streams may be extracted from it;
9. work reliably with ordinary, less-expensive models and provider limits rather than assuming the largest advertised context window;
10. make analysis size, expected request count, selected analysis purpose, and processing method visible before a run.

Any field or mechanism that does not materially help those goals must not become required product behavior.

## 2. Authority and Reference Rules

1. The owner's direct decisions control the product.
2. Anything not approved remains open. An implementation agent may not choose it and report the choice afterward.
3. Green CI proves only that code compiled and tests passed.
4. Models and embeddings may propose and search. They never directly approve, replace, supersede, archive, or delete memories.
5. API analysis and computer-file analysis must create the same Pending memory experience.
6. This roadmap is binding. Do not skip ahead, combine phases into an uncontrolled rewrite, or redesign unrelated memory systems.
7. The archiver is **LangMem-inspired**, not a direct Python dependency. Adapt the useful architecture, prompts, schemas, evaluation methods, and separation of memory kinds to Speak-GPT's Kotlin, Android, SQLite, and local-embedding design.
8. Mem0 is a secondary reference for additive-only extraction, exact deduplication before semantic comparison, and evaluation discipline.
9. Speak-GPT remains the authority for storage, scope, Pending review, Possible Match, lifecycle, and human approval.
10. Do not claim parity with another project's hosted product, benchmark, or proprietary implementation.
11. If code or prompt text is copied rather than independently reproduced, preserve the source project's license and attribution requirements and record the exact source path and revision.
12. Companion-memory routing must not be hidden in a companion-profile default. Conversation-level policy and visible per-run choices control analysis and retrieval.
13. Deleting a companion permanently deletes every memory targeted specifically to that companion after an explicit destructive confirmation. Do not orphan, silently convert, or reassign those memories.

## Ordered Implementation Roadmap

This section is the required execution order. The detailed product rules later in this document define what each phase must preserve.

### Execution Rules

- Implement one phase at a time on a dedicated feature branch.
- Do not begin the next phase until the current phase's tests pass and its completion gate is satisfied.
- Keep migrations additive and reversible where practical. Never delete existing user data merely to simplify implementation, except where the approved companion-deletion action explicitly performs a confirmed permanent cascade.
- When legacy storage conflicts with the approved product, first stop the legacy field from affecting prompts, UI, matching, embeddings, and retrieval. Physical column removal comes later.
- If a phase encounters a genuine product decision not answered here, stop only that decision path and return one focused question. Continue unrelated work within the approved phase.
- At the end of every phase, record changed files, migrations, tests, unresolved items, and the exact commit.
- Do not redesign Lorebooks, roleplay cards, VAD, Whisper, logging, provider routing, or unrelated app systems while implementing this plan.
- Do not use a live user's paid API budget for broad prompt or chunk experiments without an explicit test run initiated by that user.

### Phase 0: Current-Code Audit and Migration Map

**Status:** [ ] Not Started

**Goal:** Establish the exact gap between the current implementation and this plan before changing behavior.

Required work:

1. Trace every read, write, prompt, parser, export, import, UI, embedding, retrieval, and ranking use of:
   - memory `title`;
   - fixed `kind` / Type values;
   - `lore` as an Associative Memory Type;
   - importance values and ranking weights;
   - source-chat fields and transcript lineage;
   - permanent transcript row processing states;
   - API/computer origin distinctions;
   - roleplay-specific Pending actions;
   - scope and target eligibility;
   - companion scope and companion target joins;
   - companion-memory retrieval and prompt placement;
   - companion deletion and its existing confirmation dialog;
   - conversation-level memory access settings;
   - conversation-level analysis settings or markers;
   - any conversation-level exclusion from memory retrieval or analysis.
2. Trace the full current archiver path:
   - conversation selection;
   - bookmark and frozen range behavior;
   - transcript formatting;
   - character, message, and token limits;
   - chunk boundaries and overlap;
   - selected endpoint and model;
   - system and user prompts;
   - output-token allowance;
   - structured-output support;
   - JSON parsing and repair;
   - finish-reason and truncation handling;
   - retry behavior;
   - partial filing behavior;
   - duplicate handling;
   - General versus companion target assignment;
   - Model Rule extraction and destination;
   - cancellation and process-death recovery.
3. Trace every current entry point for analysis, including batch analysis, Memory Assistant settings, conversation menus, and computer export/import.
4. Identify every hard-coded archiver assumption, including the current 200,000-character request ceiling and any fixed output count.
5. Identify the current database version and every upgrade path that touches the memory tables, companion tables, conversation metadata, and deletion cascades.
6. Identify all backup, restore, seed, export, and import formats that contain affected fields.
7. Identify tests that already protect memory, companion deletion, conversation metadata, and archiver behavior and gaps requiring new tests.
8. Commit a concise audit report at `Memory System/memory_implementation_audit.md` containing:
   - current behavior;
   - approved target behavior;
   - safe migration approach;
   - files involved;
   - risks and rollback notes;
   - current archiver request/response flow;
   - current maximum raw input, estimated token use, and output reserve;
   - every point where a failed chunk can create partial visible results;
   - the current companion deletion dialog and every data class it deletes;
   - whether conversations already store any memory-use or analysis policy.

Restrictions:

- No feature redesign.
- No destructive schema migration.
- No automatic conversion of legacy `lore` into Roleplay scope.
- Do not remove existing roleplay `Add to Card` behavior during the audit.
- Do not replace the current chunk constant with another arbitrary “large enough” constant during the audit.
- Do not alter companion deletion until the existing approved cascade and dialog are fully traced.

**Completion Gate:** The audit report exists, every affected code path is accounted for, the current archiver is diagrammed from transcript selection through Pending filing, the companion deletion path is documented, and no destructive migration remains unexplained.

### Phase 1: Storage Compatibility and Database Migration

**Status:** [ ] Not Started

**Goal:** Make the database capable of representing the approved memory shape and conversation policies without losing existing data.

Required work:

1. Add user-managed Memory Types with stable internal IDs.
2. Seed the starter Types once:
   - Fact;
   - Preference;
   - Event;
   - Status;
   - Instruction.
3. Allow an Associative Memory to have zero or one Type.
4. Migrate legacy fixed `kind` values:
   - recognized starter values map to their matching seeded Type;
   - legacy `lore` becomes **No Type** while preserving memory text, scope, targets, tags, lifecycle, and timestamps;
   - Type migration never changes scope.
5. Expand importance to the approved range 0 through 5.
6. Preserve every existing stored importance value. New memories default to 0.
7. Add the persistent `Use Importance Ratings` setting, default Off.
8. Keep legacy title storage only as compatibility baggage until it can be safely removed:
   - do not generate meaningful titles;
   - do not expose titles;
   - do not include titles in exports intended for the revised memory model;
   - if a legacy NOT NULL column temporarily requires a placeholder, it must remain internal and inert.
9. Stop attaching source-chat identity to new Pending or saved memories. Existing legacy source fields remain inert until cleanup.
10. Preserve current scope and target relationships, including multi-target joins.
11. Preserve and validate the existing Companion scope as a target-specific non-roleplay scope.
12. Store conversation-level policy separately from memories:
   - memory access: General memories On/Off and current-companion memories On/Off;
   - extraction: General Memories, Companion Memories, and Model Rules as independent selections;
   - `Do Not Analyze This Conversation`;
   - optional Analysis Note;
   - Use Default versus custom conversation policy;
   - preferred processing method where approved: API, Computer, or Ask Every Time.
13. A conversation policy is ordinary conversation metadata. It is not memory provenance and is never copied into a Pending or Active memory.
14. Add only the minimal short-lived run storage required by Section 8.10. Do not create a permanent provenance subsystem.
15. Ensure companion deletion can atomically cascade through Pending, Active, Archived, and Superseded memories targeted to that companion, their embeddings, target joins, and temporary candidates.
16. General memories remain untouched when a companion is deleted, even if they were proposed from that companion's conversations.
17. Add migration tests for:
   - a fresh install;
   - an existing database with every legacy Type;
   - existing importance values;
   - No Type;
   - General, Companion, and Roleplay scopes;
   - conversation policy defaults and custom overrides;
   - interrupted temporary analysis state;
   - companion deletion cascade;
   - backup/restore compatibility.

**Completion Gate:** Fresh and upgraded databases open successfully, no memory is lost, Types are user-owned in storage, importance supports 0, titles/source lineage are behaviorally inert, conversation policy is distinct from memory data, temporary run state cannot become permanent memory metadata, and confirmed companion deletion leaves no companion-targeted memory behind.

### Phase 2: Memory Domain Services and Shared Filing Path

**Status:** [ ] Not Started

**Goal:** Provide one trustworthy application layer that every memory route uses.

Required work:

1. Implement Type operations:
   - list;
   - add;
   - rename by stable ID;
   - count associated memories;
   - delete the Type assignment without deleting memories;
   - leave affected memories as No Type.
2. Queue affected embedding refreshes after Type rename or deletion.
3. Implement importance-setting access so Off makes the ranking contribution exactly zero without changing stored values.
4. Implement one canonical helper that derives browser grouping:
   - `world`, `campaign`, and `rp_character` appear in Roleplay;
   - every other existing scope, including Companion, appears in General.
5. Preserve underlying target boundaries. The Roleplay tab is not a generic retrieval scope, and Companion memories are not generic General memories during eligibility filtering.
6. Define Companion memory as an Associative Memory with Companion scope and one specific companion target.
7. A Companion memory may use any ordinary Type, including Instruction. It may contain shared history, companion-specific facts, relationship context, terminology, preferences, or behavior guidance intended to be retrieved only for that companion.
8. Create one canonical Pending filing path used by:
   - API Memory Assistant suggestions;
   - validated computer imports;
   - manual creation where applicable.
9. The canonical Pending object must contain the same approved fields regardless of origin.
10. Do not expose API/computer origin in the memory object or visible card.
11. Define separate validated candidate objects for:
   - General Associative Memory proposals;
   - Companion-targeted Associative Memory proposals;
   - Model Rule proposals.
12. Model Rules must not be disguised as Associative Memories, Types, or importance-rated facts.
13. Audit the existing Model Rule storage, review, and application path before changing it. Preserve approved behavior and return a focused question if the visible review contract is not already defined.
14. Implement one atomic companion-deletion service used by every UI path. It must count and delete all companion-targeted memories and related embeddings/joins after confirmation.

**Completion Gate:** Unit tests prove that all origins create the same Pending memory object, Type CRUD preserves memories, scope grouping does not collapse target boundaries, companion candidates cannot leak to another companion, Model Rule proposals remain a separate validated output stream, and every companion deletion route performs the same confirmed cascade.

### Phase 3: Archiver Evaluation Harness and Provisional Defaults

**Status:** [ ] Not Started

**Goal:** Test extraction quality, chunk sizes, schemas, conversation policies, and prompt profiles before choosing production defaults.

Required work:

1. Build a repeatable local test harness that can run sanitized or synthetic conversation fixtures without filing into the live memory database.
2. Include fixtures for:
   - dense product decisions;
   - long casual conversation;
   - changing facts and statuses;
   - preferences;
   - contradictions;
   - projects and plans;
   - companion-specific shared history;
   - companion-specific behavior guidance;
   - a conversation where the companion should not form memories about itself;
   - a sensitive or isolated conversation marked Do Not Analyze;
   - a conversation with General extraction enabled but Companion extraction disabled;
   - a conversation with Companion extraction enabled but General extraction disabled;
   - roleplay worlds, characters, and campaigns;
   - Model Rules;
   - mixed General Memories + Companion Memories + Model Rules;
   - conversations with almost nothing worth saving;
   - malformed, fenced, truncated, and prose-wrapped JSON responses.
3. Define expected facts and rules for each fixture without requiring identical model wording.
4. Test at least:
   - Broad, Balanced, and Conservative prompt profiles;
   - each extraction stream alone and in combinations;
   - multiple token-based chunk targets;
   - a lower-cost model and a stronger model where available;
   - structured output and plain-JSON fallback where supported;
   - API package and computer package validation where practical.
5. Record:
   - useful General memories found;
   - useful companion memories found;
   - missed memories;
   - invented or overinterpreted memories;
   - incorrect General versus Companion placement;
   - duplicates;
   - wrong scope or target;
   - invalid or unknown Type suggestions;
   - useful and noisy Model Rules;
   - violations of Do Not Analyze or Analysis Note;
   - malformed and truncated responses;
   - request count;
   - input and output token estimates;
   - latency;
   - estimated cost where the provider exposes pricing.
6. Preserve the current extraction prompt as a testable **Broad** candidate rather than deleting it before comparison.
7. Do not finalize the token values behind Small, Standard, or Large until the harness produces evidence.
8. Test retrieval with several simultaneously relevant General and companion memories under bounded token budgets.
9. Commit the fixture definitions, scoring method, and results summary under `Memory System/archiver_evaluation/`.

Restrictions:

- The harness does not write Active or Pending memories.
- A model accepting a large context window is not evidence that large chunks extract reliably.
- Do not choose defaults from one conversation or one model.
- Do not treat exact wording mismatch as automatic failure when the same supported memory was captured accurately.
- Do not treat companion-specific behavior guidance as automatically equivalent to a global Model Rule.

**Completion Gate:** The harness can compare prompt profiles, extraction streams, conversation policies, chunk sizes, and models; the provisional production defaults are documented with evidence; retrieval can combine several relevant memories without scope leakage; and failures are visible rather than collapsed into “no memories.”

### Phase 4: Archiver Request, Chunking, Consolidation, and Run UI

**Status:** [ ] Not Started

**Goal:** Implement a resilient, user-visible analysis engine based on Section 8 and the evidence from Phase 3.

Required work:

1. Add an **Analyze Conversation** action to every conversation.
2. The action opens one analysis setup screen rather than immediately spending API credit or exporting a package.
3. Replace fixed combination choices with independent **Analyze For** selections:
   - General Memories;
   - Companion Memories · `{current companion name}`, only when the conversation has a valid companion target;
   - Model Rules.
4. Allow any combination of the available streams, including one, two, or all three. At least one stream must be selected for a run.
5. Selecting both General and Companion does not create two copies of every fact. The extractor must assign each supported proposal to the appropriate destination.
6. Add **Do Not Analyze This Conversation** as a conversation-level exclusion. It overrides batch defaults and prevents analysis until the user changes it.
7. Add an optional **Analysis Note** for exceptional conversations. It guides only extraction and is never copied into memory, retrieval prompts, or provenance.
8. Add conversation policy inheritance:
   - Use Default;
   - Custom for this conversation.
9. App or batch defaults may select any combination of General Memories, Companion Memories, and Model Rules. A custom conversation policy overrides those defaults.
10. Remember visible prior choices for convenience without hiding them in a companion profile.
11. Add **Prompt Profile** choices:
   - Balanced;
   - Broad;
   - Conservative;
   - Custom.
12. Preserve separate editable custom instructions for memory extraction and Model Rule extraction where both are used.
13. Keep the response schema app-owned. Custom instructions and Analysis Notes may change what the model notices, but cannot replace the required output envelope or validation rules.
14. Add **Conversation Amount Per Request** choices:
   - Auto;
   - Small;
   - Standard;
   - Large;
   - Custom.
15. Add **Processing Method**:
   - API;
   - Computer;
   - Ask Every Time as an app-level default option.
16. The processing method remains visible and changeable before each run. Do not hide it in companion configuration.
17. Show before the run:
   - selected message count;
   - approximate transcript tokens;
   - approximate request count for the selected method where calculable;
   - selected model's known context limit, when available;
   - known provider request limit, when available;
   - a clear notice when a conservative fallback is being used.
18. If API is selected but no valid endpoint/model is configured, open the relevant setup rather than failing cryptically.
19. If Computer is selected, prepare the validated computer-analysis package and use a button label such as **Prepare Computer Analysis** rather than pretending the phone completed the remote work.
20. Replace character-based chunking with the token-budget rules in Section 8.5.
21. Preserve whole messages whenever possible. Handle one oversized message according to Section 8.6.
22. Collect every chunk's validated candidates in temporary run storage before filing anything into visible Pending.
23. Apply deterministic validation and exact deduplication first.
24. Consolidate overlapping candidates across chunks using the bounded process in Section 8.8.
25. Compare consolidated memory proposals with relevant existing memories and route candidates through Possible Match. The model may not directly mutate existing memories.
26. Use provider-supported structured output where available and the resilient JSON fallback in Section 8.7 everywhere else.
27. Distinguish truncation, malformed JSON, provider rejection, cancellation, and semantic no-result outcomes.
28. Retry only under the bounded rules in Section 8.9.
29. File the complete valid set through the Phase 2 canonical Pending path only after all required chunks and consolidation succeed.
30. Leave the bookmark unchanged after failure, cancellation, or process death.
31. Delete temporary run data after successful filing or explicit cancellation, subject to narrow interrupted-run recovery.
32. Add tests for:
   - every extraction stream alone and in combinations;
   - default and custom conversation policies;
   - Do Not Analyze;
   - Analysis Note isolation;
   - conversation with and without a companion;
   - API and Computer processing choices;
   - each prompt profile;
   - each chunk choice;
   - known and unknown model/provider limits;
   - oversized single messages;
   - structured output and JSON fallback;
   - truncation and repair;
   - process death;
   - no partial visible filing;
   - duplicate candidates across chunk boundaries.

**Completion Gate:** The user can launch analysis from any conversation, choose General, companion, and Model Rule streams independently, choose API or Computer processing, see the approximate size and cost shape of the run, preserve exceptional per-conversation policy, and trust that a failed final chunk cannot leave a half-analysis in Pending.

### Phase 5: Embeddings, Retrieval, and Ranking

**Status:** [ ] Not Started

**Goal:** Make database awareness and memory application work correctly before building the final review UI.

Required work:

1. Build embedding text from:
   - memory text;
   - optional visible Type name as a soft clue;
   - tags only where they do not overpower the memory text.
2. Exclude from embedding text:
   - title;
   - importance;
   - source-chat identity;
   - API/computer origin;
   - conversation memory-access policy;
   - conversation analysis policy.
3. Apply actual scope and target eligibility before semantic ranking.
4. Retrieve Active memories only for ordinary chats.
5. Keep Pending, Archived, and Superseded memories out of ordinary chat retrieval.
6. Remove fixed-Type authority behavior. A Type does not automatically transform a memory into a command.
7. Companion memories may include behavior guidance, but they remain semantically retrieved suggestions, not fixed companion-profile instructions and not global Model Rules.
8. For a conversation with a companion, eligible retrieval pools are independently controlled by **Memories Used in This Conversation**:
   - General Memories On/Off;
   - Companion Memories · `{current companion name}` On/Off.
9. A conversation may use either pool, both pools, or neither pool.
10. A conversation without a valid companion target cannot retrieve Companion memories.
11. Retrieve several relevant memories at once within a bounded memory-context token budget. Do not hard-code retrieval to one General and one Companion memory.
12. Protect a tested portion of the budget for relevant companion memories when that pool is enabled so a large General result set cannot always crowd out companion continuity. Unused protected capacity may spill over to the other enabled pool.
13. Rank within actual scope and target eligibility. A memory targeted to Ash must never be eligible for Slate merely because their text is similar.
14. Insert retrieved memories as a dedicated context block after fixed app safety and companion identity instructions and before the conversation transcript. Retrieved memory must not outrank or rewrite fixed system/developer rules.
15. Label General and companion memory groups clearly inside the generated context when both are present.
16. Remove title, source, and fixed-Type ranking bonuses.
17. When `Use Importance Ratings` is On:
   - apply a relevance floor first;
   - use importance only as a bounded secondary ordering signal among already relevant memories;
   - 0 adds no boost;
   - importance cannot make an irrelevant memory eligible.
18. When the setting is Off, the importance contribution is exactly zero.
19. Importance does not affect exact duplicate detection or Possible Match candidate generation.
20. Report embedding failure honestly. Do not silently present a semantic failure as no match.
21. Add focused tests for:
   - General and Roleplay eligibility;
   - companion target isolation;
   - General-only, Companion-only, both, and neither conversation access;
   - several simultaneously relevant memories;
   - memory-context budget and protected companion capacity;
   - companion behavior guidance retrieved only when relevant;
   - target isolation between worlds, campaigns, and characters;
   - Type soft clues;
   - importance On and Off;
   - inactive lifecycle exclusion;
   - embedding failure.

**Completion Gate:** Retrieval tests demonstrate relevant multi-memory application, companion target isolation, conversation-level access control, protected but bounded companion continuity, and optional importance behavior without title/source influence.

### Phase 6: Memory Controls UI

**Status:** [ ] Not Started

**Goal:** Give the user direct control over Types and optional importance before those fields appear in Pending.

Required work:

1. Implement the exact copy and structure in `Memory System/memory_controls_and_pending_ui_copy.md`.
2. Add the **Memory Types** section:
   - `Type` label;
   - blank entry field;
   - `Add` button;
   - bordered scrollable Type list;
   - row actions `Rename` and `Delete`.
3. Use Title Case for labels, buttons, and dialog titles.
4. Show the associated-memory count in the delete confirmation.
5. Deleting a Type never deletes a memory.
6. Add the **Use Importance Ratings** toggle with the approved subtext.
7. Default the toggle Off.
8. Preserve ratings when Off and restore them when On.
9. Add UI and domain tests for add, rename, delete, count wording, No Type, and toggle persistence.

**Completion Gate:** The user can manage Types and toggle importance without data loss, and the screen matches the approved wording.

### Phase 7: Pending Browser, General/Roleplay Tabs, and Fast Review

**Status:** [ ] Not Started

**Goal:** Make ordinary Pending memories safe to scan and approve without opening every item.

Required work:

1. Add the **General** and **Roleplay** tabs.
2. Derive Roleplay membership only from World, Roleplay Character, and Campaign scopes.
3. Companion-scoped memories appear in General but keep their actual Companion scope and target visible.
4. Keep actual scope and target visible on every card.
5. Every ordinary Pending card shows:
   - complete memory text;
   - actual scope and target;
   - Type or No Type;
   - all tags or No Tags;
   - importance only when enabled;
   - Information;
   - the approved actions.
6. No title appears.
7. No-match card actions remain:
   - Information top-right;
   - discard X immediately left of save/disk;
   - save/disk at far right;
   - no caution icon;
   - no Review button.
8. Possible-Match card actions remain:
   - caution icon top-left;
   - Information top-right;
   - Review bottom-right;
   - no save/disk;
   - no discard X.
9. Implement **Accept All** only for visible, ordinary, conflict-free proposals with valid placement.
10. Accept All never bypasses Possible Match Review or hidden unresolved data.
11. API and computer-imported proposals render identically.
12. Audit existing roleplay-specific `Add to Card` behavior before changing it:
   - do not remove it by implication;
   - if the new card layout conflicts with it, return one focused decision before altering that action.
13. Keep Model Rule review separate from ordinary memory cards unless an existing approved UI explicitly combines them.
14. Add UI tests for both tabs, companion target display, card variants, direct field visibility, and Accept All exclusions.

**Completion Gate:** The user can scan and safely approve ordinary General, Companion, and Roleplay memories, while conflict cards, Model Rules, and roleplay-specific actions remain protected.

### Phase 8: Possible Match Review and Lifecycle Actions

**Status:** [ ] Not Started

**Goal:** Resolve duplicates and updates using the existing database without automatic destructive decisions.

Required work:

1. Run exact normalized text matching without requiring embeddings.
2. Use the local embedding model for differently worded related memories.
3. Respect actual scope and target context when generating candidates.
4. A Companion proposal may compare only with eligible memories for the same companion target and lifecycle search rules.
5. Keep Type as only the same soft embedding clue used elsewhere.
6. Ignore importance during candidate generation.
7. Use temporary comparison vectors for Archived and Superseded memories and discard them immediately.
8. Never make inactive comparison vectors chat-retrievable.
9. Implement the approved Review page:
   - proposal first;
   - complete visible metadata;
   - matches below with checkboxes;
   - scrolling actions after the final card;
   - no floating or hidden controls.
10. Implement the approved actions atomically:
   - Save & Edit Old Memory;
   - Save & Supersede;
   - Save & Replace.
11. Revalidate the proposal and selected memories before committing.
12. Backing out leaves the proposal Pending.
13. Add tests for one and many matches, companion target isolation, lifecycle transitions, restore history, permanent replacement, stale selections, and transaction rollback.

**Completion Gate:** Possible Match reliably surfaces database conflicts without cross-companion leakage and every resolution is atomic, user-controlled, and correctly reflected in lifecycle state.

### Phase 9: Conversation Bookmark, API Analysis, and Computer Import

**Status:** [ ] Not Started

**Goal:** Make analysis resumable and ensure every route files suggestions through the same reviewed workflow.

Required work:

1. Store one durable bookmark per chat representing the last message successfully reviewed and safely filed.
2. Freeze the analysis end point when a run begins.
3. Leave later messages for the next run.
4. Advance the bookmark only after all valid suggestions from the frozen range are safely filed into Pending.
5. Do not advance after failure, cancellation, or process death.
6. Use short-lived run bookkeeping only for locking, chunk status, retry safety, duplicate prevention, and interrupted-run recovery.
7. Never copy the bookmark, chat ID, transcript row IDs, excerpts, source timestamps, run ID, chunk number, candidate hash, Analysis Note, or conversation policy into a memory.
8. API analysis and computer import both call the canonical Pending filing path from Phase 2.
9. The **Analyze Conversation** action uses the same frozen range, policy, prompt profile, and chunk plan whether API or Computer is selected.
10. API processing runs the bounded analysis on the configured endpoint/model.
11. Computer processing prepares a package containing only the information required to run the same selected extraction contract, then strictly validates the returned package before filing.
12. A valid imported suggestion receives no import badge, source label, special card, or separate review path.
13. Keep Lorebook import and retrieval separate from Associative Memory. Do not merge the two systems while repairing this route.
14. Apply the same fixed candidate schemas and validation rules to imported packages where applicable.
15. Batch analysis uses app/batch defaults only for conversations marked Use Default. Custom conversation policies override the batch defaults. Do Not Analyze conversations are skipped.
16. The batch progress/result screen reports skipped conversations without inventing or exposing sensitive topic labels.
17. Add tests for retry, cancellation, process death, duplicate import, messages arriving during analysis, API/computer equivalence, default/custom policies, and skipped conversations.

**Completion Gate:** Analysis resumes from the correct bookmark, failures do not skip material, per-conversation policy is respected in single and batch analysis, and API/computer suggestions become identical Pending memories.

### Phase 10: Legacy Cleanup, Backup Compatibility, and Release Verification

**Status:** [ ] Not Started

**Goal:** Remove obsolete behavior only after the replacement system is working and protected by tests.

Required work:

1. Re-audit legacy fields after Phases 1 through 9.
2. Remove obsolete reads and writes for:
   - titles;
   - fixed Type constants;
   - source-chat lineage;
   - API/computer presentation differences;
   - transcript row processing states made unnecessary by the bookmark;
   - the 200,000-character archiver ceiling;
   - obsolete archiver schemas and prompt fields;
   - partial filing from incomplete analysis runs;
   - hidden companion-profile memory-routing defaults;
   - any retrieval path that ignores conversation memory-access policy;
   - any companion deletion path that leaves targeted memories behind.
3. Remove or migrate physical database columns only when:
   - no approved behavior depends on them;
   - upgrade and rollback behavior is documented;
   - backup, restore, seed, export, and import tests pass.
4. Confirm older backups import without creating visible titles, invalid Types, source-chat memories, or broken companion targets.
5. Confirm new backups preserve:
   - user Types;
   - No Type;
   - tags;
   - importance values even while disabled;
   - scope and target relationships;
   - companion-specific memories;
   - conversation memory-access and analysis policies;
   - lifecycle and supersession history;
   - user prompt profiles and custom instructions;
   - archiver chunk preference and previous visible selections where intended;
   - processing-method preference;
   - no abandoned temporary run state as permanent memory data.
6. Run focused unit, migration, instrumentation, parser, archiver, deletion, retrieval, and UI tests.
7. Run Android Checks and require green CI.
8. Exercise the full device path:
   - analyze a short chat;
   - analyze a long dense chat with a lower-cost model;
   - inspect estimated tokens and request count;
   - run General Memories, Companion Memories, and Model Rules alone and in combinations;
   - mark a conversation Do Not Analyze and confirm batch analysis skips it;
   - set a custom Analysis Note and confirm it does not become memory metadata;
   - switch conversation memory access between General only, Companion only, both, and neither;
   - retrieve several relevant General and companion memories in one conversation;
   - prepare a Computer analysis package and import its result;
   - interrupt and resume an analysis;
   - review ordinary Pending memories;
   - use Accept All;
   - resolve a Possible Match;
   - verify General and Roleplay separation;
   - toggle importance Off and On;
   - add, rename, and delete a Type;
   - delete a companion and verify the confirmation count and complete companion-memory cascade;
   - export and restore.
9. Update the phase statuses and commit the final implementation report.

**Completion Gate:** CI is green, the owner has exercised the relevant device paths, long-conversation analysis is bounded and visible, companion memory is isolated and deletable with its companion, conversation policies are respected, backups are safe, and no legacy field or archiver assumption influences the revised product.

### Decisions That Do Not Block the Early Phases

These may remain open until the phase that touches them:

- the exact standalone tag-management screen;
- whether AI-assigned importance is ever offered as a future optional feature;
- additional editable placement fields in Save & Edit Old Memory;
- any change to roleplay-specific Add to Card behavior;
- the exact Small, Standard, and Large token targets until Phase 3 evaluation;
- the exact checkbox, switch, or row component used for the independent Analyze For streams;
- the final wording of Balanced, Broad, and Conservative prompts until Phase 3 evaluation;
- additional visible Model Rule review fields if the existing implementation does not already settle them;
- the exact memory-context token budget and protected companion share until Phase 3 evaluation;
- whether conversation policy receives a small visible marker in the conversation header or list, provided the settings remain discoverable from the conversation.

An implementation agent must not silently decide these.

## 3. Associative Memory Shape

An Associative Memory contains:

- the memory text;
- zero or one user-defined Type;
- separate optional tags;
- an optional stored importance rating from 0 through 5;
- an invisible stable memory ID;
- ordinary internal timestamps;
- its lifecycle state;
- only the minimum existing placement information needed to prevent unrelated contexts from bleeding together.

It does not contain a title, a durable source-chat history, a run ID, a chunk ID, evidence excerpts, or the conversation policy that allowed it to be proposed.

### 3.1 No Titles

No Associative Memory has a separate title.

- models do not generate titles;
- prompts and schemas do not request titles;
- cards and editors do not display title fields;
- exact matching does not compare titles;
- embedding documents do not include titles;
- retrieval has no title bonus;
- any legacy title column is compatibility baggage only and must not affect product behavior.

### 3.2 No Source-Chat Memory or Provenance Feature

A saved or Pending memory does not remember which chat, request, or chunk produced it.

Do not attach or expose:

- chat name or chat ID;
- conversation UID;
- transcript row IDs;
- turn numbers;
- source timestamps or excerpts;
- quote hashes;
- links back to the source conversation;
- durable source-evidence tables derived from the chat;
- analysis run IDs;
- chunk numbers;
- candidate hashes;
- API/computer origin;
- the conversation's Analysis Note;
- the conversation's memory access or extraction policy.

Do not build a “provenance” subsystem merely because temporary retry bookkeeping is useful. Minimal run bookkeeping may exist outside the memory only long enough to finish or safely recover analysis/import. It does not become part of the candidate shown to the user or the saved memory.

## 4. Existing Scopes Determine Roleplay Grouping

Do not create a new generic `roleplay` scope.

The app already has the scopes that identify roleplay memory. A memory is grouped into the **Roleplay** side of the Memory Browser when its existing scope is any of:

- **World** (`world`);
- **Roleplay Character** (`rp_character`);
- **Campaign** (`campaign`).

This is a derived browser and retrieval grouping rule, not a fourth roleplay scope stored on the memory.

All other existing scopes, including Companion, remain in the **General** side unless the owner later changes a specific scope's meaning.

The Memory Browser has two tabs:

- **General**;
- **Roleplay**.

- Pending, Active, Archived, and Superseded memories with World, Roleplay Character, or Campaign scope appear in the Roleplay tab;
- memories with other scopes appear in the General tab;
- API and computer-created suggestions use the same rule;
- Type names and tags never decide which tab a memory belongs in;
- changing a Type never moves a memory between General and Roleplay.

### 4.1 The Roleplay Tab Is a Grouping, Not a Collapsed Scope

The three underlying scopes retain their own meanings inside the Roleplay tab.

- a World memory remains a World memory;
- a Roleplay Character memory remains tied to the appropriate roleplay character;
- a Campaign memory remains tied to the appropriate campaign;
- unrelated worlds, characters, and campaigns must not be compared or retrieved as though they share one generic roleplay context;
- duplicate display names must not silently select the first target.

Searching and filtering may operate across the visible Roleplay tab for the human, but chat retrieval and Possible Match must continue to respect the actual scope and target boundaries required to prevent fictional contexts from bleeding together.

Types may still be fantasy-specific. A user may create Types such as Character Detail, Quest, Spell, Campaign Note, or anything else, but those names do not create or remove Roleplay grouping.

Internal links to companions, projects, worlds, campaigns, or characters may remain only where needed to preserve their existing context. They do not automatically justify a new visible scope taxonomy or a required scope dropdown on every memory card.

### 4.2 Companion Memories

A Companion memory is an Associative Memory with:

- Companion scope;
- one specific companion target;
- ordinary memory text;
- any optional user-owned Type and tags.

A Companion memory is eligible only while speaking with its target companion and only when that conversation allows Companion Memories.

Companion memories may be used creatively. They may contain:

- shared history;
- relationship continuity;
- terms, rituals, jokes, and agreements;
- facts learned by that companion;
- companion-specific preferences;
- behavior guidance that should surface only when semantically relevant to that companion.

For example, an Instruction memory scoped to Ash may suggest that Ash avoid unsolicited checklists when the user is overwhelmed. It is retrieved when relevant rather than injected into every Ash turn.

A Companion memory is not:

- a hidden edit to the companion profile;
- a global Model Rule;
- automatically authoritative merely because its Type is Instruction;
- available to another companion;
- duplicated into General unless the analyzer separately finds a genuinely General memory.

Companion memories appear in the General browser tab because Companion is not a Roleplay scope, but the card must show the actual companion target.

### 4.3 Multiple Memories and Prompt Placement

A chat may receive several relevant memories from every enabled and eligible pool, subject to a bounded token budget.

- do not limit retrieval to one General and one Companion memory;
- use semantic relevance as the primary signal;
- keep a tested protected capacity for relevant companion memories when enabled;
- allow unused protected capacity to spill into another enabled pool;
- keep fixed app safety, developer rules, and fixed companion identity above retrieved memory context;
- place retrieved memory in a dedicated context block before the conversation transcript;
- clearly distinguish General and companion-specific groups when both are present;
- treat retrieved memory as context for the model, not as permission to overwrite higher-priority instructions.

### 4.4 Conversation Memory Access

Each conversation may control which approved memories can be retrieved into it.

**Memories Used in This Conversation:**

- General Memories On/Off;
- Companion Memories · `{current companion name}` On/Off, only when a valid companion is assigned.

A conversation may enable:

- both;
- General only;
- Companion only;
- neither.

This controls live retrieval only. It does not decide what the analyzer may extract from the conversation.

Use cases include:

- ordinary companion chats using both pools;
- a temporary conversation that uses General information but no companion continuity;
- a companion-specific continuity chat that avoids unrelated General context;
- an isolated or sensitive conversation that uses neither pool.

### 4.5 Conversation Analysis Policy

Each conversation separately controls which proposals may be created from it.

**Create From This Conversation:**

- General Memories;
- Companion Memories · `{current companion name}`, only when a valid companion is assigned;
- Model Rules.

These are independent selections. Any combination is valid.

Additional controls:

- **Use Default** applies the current app or batch defaults;
- **Custom for This Conversation** stores the selected streams for that conversation;
- **Do Not Analyze This Conversation** prevents single or batch analysis until changed;
- optional **Analysis Note** gives extraction-only guidance for an exceptional conversation.

The Analysis Note:

- may say, for example, not to treat a fictional scenario as user information or not to create memories about the companion's identity;
- is sent only to the analyzer;
- is never injected into ordinary chat;
- is never saved as memory or Model Rule;
- is never displayed as provenance;
- cannot redefine the response schema or lifecycle permissions.

Do not require topic labels such as sexual, medical, private, or sensitive. The user may exclude or guide a conversation without telling the app why.

Conversation policy belongs to the conversation, not the companion profile. The same companion may have different policies in different conversations.

### 4.6 Companion Deletion

Deleting a companion permanently deletes every memory targeted specifically to that companion.

The destructive confirmation must state the consequence before deletion and include the current count where available. Base wording:

> **Delete {Companion Name}?**  
> This will permanently delete {Companion Name} and {count} companion memories. This cannot be undone.

Use correct singular and plural wording. If current approved deletion also removes conversations, avatars, cards, or other companion-owned data, keep those consequences in the same confirmation rather than replacing them with a narrower message.

The deletion transaction must include:

- Pending companion memories;
- Active companion memories;
- Archived companion memories;
- Superseded companion memories;
- embeddings and target joins for those memories;
- temporary analysis candidates targeting that companion.

It must not delete General memories merely because they were extracted from conversations with that companion.

Do not orphan, silently convert to General, or silently reassign companion memories.

## 5. User-Owned Types

Type is approved as a human-owned category system.

### 5.1 Starter Types

Speak-GPT ships with:

- **Fact**
- **Preference**
- **Event**
- **Status**
- **Instruction**

**Lore** is not an Associative Memory Type.

**Roleplay** is not a required Type because the Roleplay tab is derived from World, Roleplay Character, and Campaign scopes. A user may create a Type named Roleplay or any more specific fantasy category if they find it useful, but it remains an ordinary Type.

These are starter choices, not a permanent ontology. A user may create categories such as Likes, Dislikes, Classic Cars, Health, Writing, Pets, Character Detail, Quest, or anything else useful to them.

### 5.2 Type Behavior

- an Associative Memory may have zero or one selected Type;
- the Memory Assistant normally suggests one Type and may choose only from the user's current Type list;
- an absent or invalid AI Type suggestion becomes No Type rather than causing the supported memory text to be discarded;
- the proposed Type is shown directly on the Pending card;
- the user can change or remove the Type before saving;
- ordinary memory editing can change or remove the Type;
- Type is available for human browsing and filtering;
- Type does not determine scope or Roleplay grouping;
- Type does not determine whether a memory is General or companion-specific;
- Type does not determine whether a memory is true, important, or authoritative;
- Type does not automatically change a memory into a special command or alter how the receiving model must obey it;
- a mistaken Type must not make Accept All dangerous.

### 5.3 Type and Embeddings

The embedding document may include the visible Type name as a soft semantic clue alongside the memory text.

For example, a Type named **Classic Cars** may help related memories cluster together.

However:

- the memory text remains the primary semantic content;
- actual scope and target eligibility are applied before semantic ranking;
- Type is not a hard retrieval gate;
- Type cannot override a poor textual match;
- Type does not receive a separate ranking bonus;
- renaming or removing a Type refreshes affected embeddings because the soft clue changed;
- exact duplicate matching remains based on memory text and necessary context, not the Type name alone.

### 5.4 Type Management in Memory Controls

Memory Controls contains a **Memory Types** area.

At the top is one compact entry row:

- **Label:** `Type`;
- an empty text field;
- **Button:** `Add`.

Adding a Type happens directly on Memory Controls. It does not open a separate Add dialog or navigate away.

Directly beneath the entry row is a bordered, scrollable box containing the complete Type list.

- each Type appears as an ordinary list row;
- tapping a row offers `Rename` and `Delete`;
- labels, buttons, and dialog titles use Title Case.

Renaming opens:

- **Title:** `Rename Type`;
- **Field Label:** `Type`;
- the current name prefilled;
- **Buttons:** `Cancel` and `Save`.

Rename uses a stable internal Type ID so every associated memory reflects the new name without rewriting each memory relationship individually.

Deleting opens:

> **Delete This Type?**  
> Used by {count} memories. This will remove the type from those memories. The memories will not be deleted.

Use correct singular wording for one memory and plural wording for multiple memories.

**Buttons:** `Cancel` and destructive `Delete`.

Deleting a Type:

- never deletes a memory;
- removes that Type assignment from associated memories;
- leaves those memories as **No Type** rather than silently guessing a replacement;
- refreshes their embeddings using the remaining memory text and tags.

No Type is a valid state. The user may assign another Type later.

## 6. Tags Remain Separate

Tags are not Types.

- one memory has zero or one Type but may have multiple tags;
- Types provide broad user-owned organization;
- tags provide smaller cross-cutting words or themes;
- tags do not determine scope, companion target, or Roleplay grouping;
- Type management and tag management remain separate;
- neither tags nor Types may become mandatory ranking weights that overpower semantic relevance.

The exact tag-management UI is not redesigned by this document.

## 7. Optional Importance Ratings

Importance is an optional user-controlled ranking aid, not part of the embedding itself.

### 7.1 Memory Controls Toggle

Memory Controls contains one master toggle.

**Toggle Label:** `Use Importance Ratings`

**Subtext:**

> Memories can be rated from 0 to 5. Completely neutral is 0. Higher importance may take precedence when multiple memories apply.

**Recommended Default:** Off.

When Off:

- importance controls are hidden from Pending, Review, and ordinary memory editing;
- retrieval ignores importance completely;
- stored importance values remain unchanged in the database;
- turning the feature back on restores the previous values;
- memories created while Off store the neutral value 0.

When On:

- importance is visible and editable wherever the user reviews or edits a memory;
- allowed values are **0 through 5**;
- **0 is neutral**;
- new API suggestions, computer-imported suggestions, and manually created memories start at 0;
- existing stored values reappear.

The visible neutral value is `0 · Neutral`.

There is no separate “start all ratings at zero” toggle. Zero is simply the default and a valid permanent value.

### 7.2 Who Chooses Importance

The Memory Assistant and computer reviewer do **not** assign importance in the initial implementation.

Every generated proposal starts at 0. The user may change it while scanning Pending or later while editing a memory.

Do not add a second “allow AI to choose importance” toggle now. That would add prompt complexity and subjective AI judgment to a feature intended to reflect the user's priorities. It may be reconsidered later only if real use shows a need.

### 7.3 How Importance Affects Retrieval

Importance is not included in the embedding document and does not require re-embedding when changed or toggled.

When importance ratings are On:

- semantic relevance remains the primary retrieval signal;
- actual scope and target eligibility are applied first;
- a relevance floor is applied before importance;
- importance may act only as a bounded secondary ordering signal among already relevant memories;
- importance cannot make an irrelevant memory eligible;
- 0 adds no boost;
- higher values may gently prefer one relevant memory over another when multiple memories apply.

When importance ratings are Off, the importance contribution is exactly zero even though stored values remain.

Importance does not affect exact duplicate detection or Possible Match candidate generation.

## 8. Archiver Request, Chunking, and Consolidation Contract

The archiver is a bounded extraction pipeline, not one giant summarization request.

It is primarily inspired by LangMem's storage-independent memory manager concepts: customizable instructions, structured schemas, insertion-only operation, existing-memory awareness, and separate extraction/consolidation stages. Mem0 is a secondary reference for additive-only extraction and exact deduplication before more expensive semantic work.

Speak-GPT does not import the Python packages into Android. It reproduces the relevant logic in the existing app architecture.

### 8.1 Analysis Is Additive and Human-Reviewed

The model may propose new General memory candidates, companion-targeted memory candidates, and Model Rules.

It may not automatically:

- update an existing memory;
- delete an existing memory;
- archive an existing memory;
- supersede an existing memory;
- replace an existing memory;
- approve its own proposal.

A proposal that may conflict with an existing memory is routed through Possible Match. The human chooses what happens.

### 8.2 Analyze For

Each analysis pass uses independent selections:

- **General Memories**;
- **Companion Memories · `{current companion name}`**, when available;
- **Model Rules**.

Any combination is allowed. At least one available stream must be selected unless the conversation is marked Do Not Analyze.

The app may remember prior visible choices for convenience but keeps them visible and changeable for every pass. It must not silently derive them from the companion profile.

General and Companion selection means classify proposals by destination, not duplicate every candidate into both pools.

Model Rules are procedural behavior instructions. They remain separate from Associative Memories and do not receive a Memory Type or importance rating merely because they came from the same conversation.

#### 8.2.1 Conversation Defaults and Overrides

- app or batch defaults may select any combination of the available extraction streams;
- a conversation marked Use Default inherits current defaults;
- a custom conversation policy overrides those defaults;
- Do Not Analyze overrides every batch selection;
- Analysis Note is applied only to that conversation's extraction;
- single-conversation analysis shows the effective policy before starting.

#### 8.2.2 Processing Method

Each run offers:

- **API**;
- **Computer**.

The app-level default may be API, Computer, or Ask Every Time. The selected method remains visible before every run.

API uses the configured endpoint/model. Computer prepares a package for external processing and later validates the returned package through the same schema and Pending path.

### 8.3 Prompt Profiles

Each analysis pass offers:

- **Balanced**;
- **Broad**;
- **Conservative**;
- **Custom**.

The exact built-in prompt wording is selected only after Phase 3 evaluation.

Intent:

- **Balanced:** capture useful durable information without aggressively interpreting every pattern.
- **Broad:** capture more implicit observations and possible long-term context. The current “wise friend” style prompt remains available as a Broad test candidate.
- **Conservative:** favor information directly supported by the conversation and avoid personality, motive, diagnosis, or recurring-pattern inference unless explicitly stated.
- **Custom:** allow the user to edit the extraction instructions.

Custom instructions control what the model looks for. They do not control the JSON envelope, required field names, validation, retry policy, bookmark behavior, lifecycle, or permission to mutate the database.

Where several streams are selected, the app may use separate instructions for General memories, companion memories, and Model Rules while keeping one user-visible Prompt Profile selection.

### 8.4 Conversation Amount Per Request

Each analysis pass offers:

- **Auto**;
- **Small**;
- **Standard**;
- **Large**;
- **Custom**.

Suggested user-facing explanation:

> Controls how much conversation text is sent in each AI request. Smaller amounts work with more models and providers but require more requests.

The exact token values for Small, Standard, and Large are chosen from Phase 3 evidence. They are not hard-coded from intuition.

Before starting, show:

- selected message count;
- approximate transcript tokens;
- approximate request count;
- selected processing method;
- known model context limit, when available;
- known provider request limit, when available;
- whether a conservative fallback is being used.

Message count may be displayed for human understanding, but token budget governs chunking.

### 8.5 Token Budget

For each request, the maximum transcript budget is the minimum of:

- the user's selected chunk target;
- the selected model's known context limit;
- the provider's known request or context limit;
- any documented endpoint-profile limit.

Then subtract:

- system and developer prompt estimate;
- output-token reservation;
- tool or structured-output overhead;
- Analysis Note estimate where used;
- a conservative safety margin.

Do not use the model's advertised context limit as the target chunk size.

When a model or provider limit is unknown:

- use a conservative fallback derived from Phase 3 testing;
- say that the limit is unknown;
- do not assume a million-token model or headline specification is fully available through the selected provider.

Do not retain the current 200,000-character ceiling as a fallback.

### 8.6 Chunk Boundaries

- Preserve complete messages whenever possible.
- Do not split merely because a fixed number of messages was reached.
- If one message exceeds the safe transcript budget, split that message at paragraph boundaries, then sentence boundaries if necessary.
- Preserve speaker identity and original ordering.
- Use only a small bounded overlap if Phase 3 demonstrates that it improves boundary recall enough to justify duplicate risk and cost.
- Mark overlap internally for deduplication, not as permanent memory metadata.
- Do not summarize an earlier raw chunk and silently replace the source material unless that separate strategy has been evaluated and approved.

### 8.7 App-Owned Response Contract

Use provider-supported structured output or tool calling when available. Generic OpenAI-compatible and custom endpoints must still work through a plain-JSON fallback.

The logical combined response envelope is:

```json
{
  "general_memories": [
    {
      "content": "The user prefers...",
      "scope": "real_life",
      "target": null,
      "suggested_type": "Preference",
      "tags": []
    }
  ],
  "companion_memories": [
    {
      "content": "When the user is overwhelmed, avoid unsolicited checklists.",
      "companion_target": "current_companion_id",
      "suggested_type": "Instruction",
      "tags": []
    }
  ],
  "model_rules": [
    {
      "content": "Avoid rewriting text unless explicitly requested."
    }
  ]
}
```

The exact serialized target representation may use a validated internal ID or package-safe reference. It must never silently target a companion by ambiguous display-name matching.

Rules:

- omit or return an empty array for an output stream not selected by Analyze For;
- no title;
- no AI importance;
- no provenance or source field;
- no chat ID, row ID, quote, evidence excerpt, run ID, or chunk ID;
- no Analysis Note or conversation policy in a candidate;
- no automatic lifecycle action;
- no card placement field beyond the approved validated scope/target contract;
- `suggested_type` may be null and may only name a current user Type;
- an invalid or unknown Type becomes No Type rather than deleting an otherwise valid candidate;
- scope and target must pass existing placement validation;
- companion proposals must target the conversation's current valid companion and cannot select an unrelated companion;
- invalid placement cannot be accepted by Accept All;
- tags are normalized and validated independently from Type;
- a schema or safety cap must never silently discard excess valid output. If output is incomplete, report it and retry with smaller work units.

The exact Model Rule schema beyond `content` must follow the audited existing Model Rule workflow. Do not invent new visible fields without approval.

### 8.8 Candidate Collection and Consolidation

Do not file each successful chunk directly into visible Pending.

For one frozen conversation range:

1. validate every chunk response;
2. hold candidates in temporary run storage;
3. preserve General, companion, and Model Rule streams separately;
4. normalize whitespace and exact-match keys;
5. remove exact duplicates, including duplicates caused by overlap;
6. group strongly related candidates with scope and target boundaries preserved;
7. never consolidate a General candidate and companion candidate merely because their wording is similar;
8. consolidate only the grouped candidate text, not the entire raw conversation again;
9. keep genuinely separate facts separate;
10. compare final memory candidates with relevant existing memories;
11. mark Possible Match candidates without allowing the model to decide the resolution;
12. file the complete valid set through the canonical Pending and Model Rule review paths;
13. advance the bookmark only after filing succeeds.

Deterministic exact deduplication happens before model-assisted or semantic consolidation.

Model-assisted consolidation should be bounded to small candidate groups. It must not become another giant transcript pass.

### 8.9 Failure, Truncation, and Retry

The app must distinguish:

- a valid empty result;
- malformed JSON;
- a response truncated by output limit;
- a request rejected for context or payload size;
- provider/model failure;
- computer package validation failure;
- cancellation;
- process death.

Required behavior:

- accept fenced JSON and harmless surrounding prose when the outer object can be isolated safely;
- validate each entry independently after the outer response parses;
- for a complete but malformed response, allow one bounded repair attempt using the fixed schema;
- for truncation or context rejection, retry with a smaller transcript chunk and appropriate output reserve;
- prevent infinite repair or shrink loops;
- after bounded retries fail, mark the run incomplete, show the real failure, file nothing from that conversation range, and leave the bookmark unchanged;
- never display semantic failure as “no memories found.”

### 8.10 Minimal Temporary Run Bookkeeping

Temporary analysis state may contain only what is needed to finish or recover the run, such as:

- run ID;
- chat ID and frozen end marker outside the memory object;
- effective conversation policy;
- selected processing method, prompt profile, and chunk setting;
- calculated budgets;
- chunk ordinal and success state;
- temporary validated candidates;
- candidate hashes for deduplication;
- retry counters.

This is not a provenance feature.

Temporary run data:

- is not shown as memory metadata;
- is not embedded;
- is not exported as part of a saved memory;
- is not copied into Pending or Active memories;
- is removed after successful filing or explicit cancellation;
- may persist narrowly across process death only to prevent skipped or duplicated work.

### 8.11 Evaluation Determines Defaults

Production defaults must be supported by Phase 3 evidence across multiple conversation styles and at least one lower-cost model.

Evaluate extraction quality and operational cost together. A profile that finds slightly more memories but doubles hallucinations, malformed output, incorrect General/Companion placement, or request cost is not automatically better.

Keep the evaluation harness available for future prompt, provider, model, and retrieval-budget changes.

## 9. Conversation Review Uses a Bookmark

For each chat:

1. store one bookmark representing the last message successfully reviewed and safely filed;
2. read from immediately after that bookmark through a frozen end point captured when the run begins;
3. leave messages added after the frozen end point for the next run;
4. apply the effective conversation analysis policy to the frozen range;
5. advance the bookmark only after every required chunk succeeds, candidates are consolidated, and valid suggestions are safely filed into Pending and the Model Rule review destination;
6. do not advance it after failure, cancellation, or process death;
7. never copy the bookmark, source-chat identity, conversation policy, or Analysis Note into a memory.

Do not require permanent per-row pending, processed, excluded, or claimed states merely to know where analysis stopped. A short-lived run lock, frozen end marker, and chunk-state record may exist invisibly while a run is active or recoverable.

## 10. Embeddings and Retrieval

The on-device embedding model may use:

- memory text;
- the user-visible Type name as an optional soft clue when a Type is assigned;
- tags only where they do not overpower the text.

It does not use:

- a title;
- importance;
- source-chat identity;
- API/computer origin;
- analysis mode, prompt profile, run ID, or chunk information;
- conversation memory-access or analysis policy;
- Analysis Note.

Retrieval rules:

- derive the General or Roleplay browser group from the memory's existing scope;
- preserve actual Companion, World, Roleplay Character, Campaign, and other scope/target boundaries during eligibility filtering;
- apply the conversation's enabled memory pools before ranking;
- retrieve Active memories only;
- retrieve several relevant memories within the tested memory-context token budget;
- preserve tested capacity for relevant companion memories when enabled;
- semantic relevance is the primary signal;
- no title bonus, source bonus, or fixed-Type bonus;
- optional importance is applied only under Section 7;
- Pending, Archived, and Superseded memories never enter normal chats;
- model failure is reported honestly.

## 11. Possible Match

Possible Match finds candidates. It does not decide whether something is a duplicate, update, contradiction, replacement, or supersession.

- exact normalized text matching works without an embedding model;
- exact candidate deduplication occurs before semantic comparison;
- differently worded related memories use the local embedding model;
- a proposal is shown in the Roleplay tab when its scope is World, Roleplay Character, or Campaign;
- a Companion proposal appears in General but compares only within the same companion target and allowed lifecycle set;
- candidates must respect the proposal's actual scope and target context;
- Active memories may use stored vectors;
- Archived and Superseded memories may be embedded temporarily and immediately discarded;
- inactive comparison vectors never become retrieval-eligible;
- comparison is lazy per proposal;
- semantic failure cannot be shown as no match;
- Type may contribute only through the same soft embedding clue;
- importance does not affect candidate generation;
- the archiver may flag possible related memories but may not choose a destructive resolution;
- every resolution revalidates before committing.

## 12. Lifecycle

| State | Meaning | Enters Normal Chats? |
|---|---|---:|
| **Pending** | Proposal awaiting the user. | No. |
| **Active** | Approved current memory. | Yes, when eligible and allowed by the conversation. |
| **Archived** | Shelved memory. | No. |
| **Superseded** | Retained historical version replaced by a newer memory. | No. |
| **Deleted** | Permanently removed. | No. |

Superseded memories remain browsable, restorable, and permanently deletable. One new memory may supersede several selected old memories.

The Superseded Memories filter remains:

- **Hide**, default;
- **Include**;
- **Only**.

Companion deletion is a separate confirmed cascade. It permanently deletes every lifecycle state targeted to that companion.

## 13. Pending Is Designed for Fast Scanning

The Pending screen shows all user-relevant data that will be saved. It must not hide fields behind endless per-memory editing screens.

Every ordinary card shows:

- complete memory text;
- selected Type or No Type;
- tags, when present;
- its actual scope and target, including companion name where applicable;
- its General/Roleplay destination through the tab it appears in;
- importance only when **Use Importance Ratings** is On;
- all other approved visible fields that will be saved;
- no title;
- no source-chat, Analysis Note, conversation-policy, or analysis-run metadata.

The Type, tags, and visible importance value can be corrected directly without leaving the review flow.

A bulk **Accept All** action may approve only ordinary, conflict-free proposals currently visible in the selected tab. It must never bypass Possible Match Review or approve hidden data the user could not scan.

Because generated memories start at importance 0, enabling importance does not make Accept All depend on trusting an AI-generated rating.

### No Possible Match

- top-left caution position empty;
- top-right Information control;
- full memory contents in the body;
- bottom-right discard X immediately left of save/disk;
- save/disk at the far right;
- no caution icon and no Review button.

### One or More Possible Matches

- top-left unlabeled caution icon;
- top-right Information control;
- full memory contents in the body;
- one labeled Review action at bottom-right;
- no save/disk and no discard X;
- the entire card is not secretly the Review control.

API and computer-imported suggestions use the exact same cards.

Model Rule proposals use their existing separate review destination. They do not silently appear as ordinary Associative Memory cards.

## 14. Possible Match Review UI

- dedicated full-page screen;
- proposal first, full width, no checkbox, Information at top-right;
- proposal scrolls normally and is not pinned;
- actual scope and target, including companion target, remain visible;
- proposed Type and tags are visible and editable;
- importance is visible and editable only when the master toggle is On;
- existing matches below in normal full-width cards;
- checkbox top-left and Information top-right on each existing match;
- suggested matches may begin checked, but the user can change every selection;
- no second selection screen;
- actions after the final card and scrolling with the page;
- no floating buttons, hidden swipe actions, sticky overlays, or controls over text;
- no titles.

Action order:

1. **Save & Edit Old Memory**, exactly one selected memory.
2. **Save & Supersede**.
3. **Save & Replace**, destructive styling.

No resolution is allowed with zero selected memories.

### Save & Edit Old Memory

- save the proposal as Active;
- keep the selected old memory Active;
- edit the old memory on the Review screen;
- allow Type and tags to be edited;
- allow importance to be edited only when the master toggle is On;
- preserve valid scope and target boundaries;
- no title field;
- any other editable placement fields require a focused decision rather than an implementation guess.

### Save & Supersede

- save the proposal as Active;
- mark all checked old memories Superseded;
- preserve history and relationships;
- keep old memories restorable and deletable;
- keep them out of chats.

### Save & Replace

- save the proposal as Active;
- permanently delete all checked old memories;
- Replace is destructive;
- Supersede is the history-preserving alternative.

All resolutions are atomic and revalidated. Backing out leaves the proposal Pending.

## 15. API and Computer Workflows

Every conversation offers **Analyze Conversation**. The setup screen uses the same conversation policy, prompt profile, chunk plan, fixed schema, and Pending path regardless of processing method.

### API

- use the configured endpoint and model;
- show estimated transcript tokens and request count before starting;
- run the bounded chunking, consolidation, and validation pipeline;
- if configuration is incomplete, route to setup instead of issuing a doomed request.

### Computer

- freeze the same conversation range;
- include the effective extraction selections, prompt instructions, Analysis Note, token/chunk guidance, and fixed response schema in the package as needed for processing;
- do not treat package metadata as permanent memory provenance;
- strictly validate the returned package;
- run the same deduplication, Possible Match, and Pending filing steps;
- allow computer processing to remain export/import based unless a separately approved desktop automation system exists.

After strict import validation, a computer-created suggestion becomes the same Pending object as an API Memory Assistant suggestion.

It has the same:

- General or companion memory shape;
- suggested Type behavior;
- existing scope and target behavior;
- Roleplay-tab grouping derived from scope;
- importance default of 0;
- card and Review UI;
- Possible Match rules;
- actions and confirmations;
- final lifecycle.

It receives no visible import badge, source label, special category, different Information panel, or computer-specific controls.

Temporary import IDs may exist outside the memory solely to prevent duplicate import and recover interrupted import.

Computer packages must not bypass the app-owned candidate schema by supplying titles, AI importance, lifecycle actions, provenance, or automatic replacement instructions.

## 16. Open-Source Reference Boundary

### Primary Reference: LangMem

Use LangMem as the main architectural reference for:

- storage-independent extraction primitives;
- customizable instructions;
- custom structured schemas;
- separate insertion, update, and delete permissions;
- insertion-only operation for Speak-GPT;
- extraction and consolidation stages;
- separate semantic memory and procedural prompt/rule concepts;
- evaluation of extraction quality.

Official references:

- https://github.com/langchain-ai/langmem
- https://langchain-ai.github.io/langmem/reference/memory/
- https://langchain-ai.github.io/langmem/concepts/conceptual_guide/

LangMem is MIT-licensed at the time of this revision. Verify the current license and source revision before copying code or prompt text.

### Secondary Reference: Mem0

Use Mem0 selectively for:

- additive-only extraction;
- no model-controlled UPDATE or DELETE in the extraction pass;
- exact deduplication before semantic work;
- retrieval and memory-quality evaluation ideas.

Official reference:

- https://github.com/mem0ai/mem0

Mem0 is Apache-2.0-licensed at the time of this revision. Verify the current license, NOTICE requirements, and source revision before copying code or prompt text.

### Do Not Import Unneeded Architecture

Do not copy merely because another framework contains it:

- hosted services;
- Python or LangGraph runtime dependencies;
- external vector databases;
- graph memory;
- automatic destructive updates;
- permanent source-conversation history;
- entity pipelines not justified by Speak-GPT tests;
- temporal-reasoning model passes not justified by Speak-GPT tests;
- benchmark claims from proprietary or hosted implementations.

Adapt the smallest useful method to the existing Android app.

## 17. Phase 0 Audit Checklist

The Phase 0 report must account for every use of:

- memory title;
- importance ranges that exclude 0;
- importance ranking that cannot be disabled;
- AI-assigned importance;
- hard-coded Type lists;
- `lore` or `roleplay` treated as a routing Type;
- a newly invented generic `roleplay` scope instead of deriving the tab from World, Roleplay Character, and Campaign;
- Roleplay-tab grouping that accidentally collapses underlying scope or target boundaries;
- Companion scope or target handling;
- companion memories retrieved for the wrong companion;
- companion-specific behavior forced into every prompt instead of semantically retrieved;
- hidden companion-profile memory-routing defaults;
- companion deletion that orphans, converts, or fails to delete targeted memories;
- a deletion dialog that omits companion-memory consequences;
- conversation memory-access settings or their absence;
- conversation analysis policy, Do Not Analyze, and Analysis Note storage or their absence;
- batch analysis that ignores custom conversation policy;
- Type-dependent authority behavior;
- tags or Types used as excessive ranking bonuses;
- title bonuses;
- source-chat lineage attached to memories;
- permanent transcript processing states where one bookmark plus temporary run state would suffice;
- World, Roleplay Character, or Campaign memories appearing in the General tab;
- API/computer origin shown in memory UI;
- 200,000-character or other character-based chunk ceilings;
- request sizing that ignores model or provider limits;
- missing output-token reservation;
- raw-message splitting behavior;
- prompts that request title, AI importance, provenance, card placement, or automatic lifecycle changes;
- JSON parsing that treats truncation as malformed output or no result;
- fixed candidate limits that silently discard output;
- successful early chunks filed before the complete conversation range succeeds;
- Model Rules mixed into ordinary memories;
- analysis controls that hide approximate tokens, expected request count, or processing method;
- any provenance subsystem beyond minimal temporary run bookkeeping.

For each item, report:

1. where it exists;
2. what visible, extraction, deletion, or retrieval behavior it changes;
3. whether it can be neutralized without a database migration;
4. the narrow safe implementation or migration path;
5. the test that will prove the old behavior is gone.

Do not delete database columns blindly. First stop unapproved fields from affecting prompts, UI, matching, embedding, ranking, chunking, filing, conversation policy, and companion deletion.

## 18. Explicitly Forbidden Claims

Future agents must not claim:

- memories need titles;
- importance is required for embeddings;
- turning importance Off should erase stored ratings;
- the AI must assign importance;
- a second default-zero toggle is required;
- the starter Type list is permanently fixed;
- Lore is an Associative Memory Type;
- Type determines Roleplay grouping;
- Type determines whether a memory is General or companion-specific;
- Roleplay requires a new generic `roleplay` scope;
- the Roleplay tab allows unrelated worlds, characters, or campaigns to share retrieval context;
- users cannot create arbitrary Types;
- every memory must have a Type;
- a Type must control model obedience or truth;
- tags and Types are the same system;
- World, Roleplay Character, or Campaign memories belong mixed into the General tab;
- Companion memories should be available to every companion;
- Companion behavior memories must be injected every turn;
- a companion profile should silently decide all conversation extraction and retrieval routing;
- deleting a companion should preserve orphaned companion memories;
- companion memories should silently convert to General when a companion is deleted;
- one conversation policy must apply to every conversation with the same companion;
- a sensitive conversation requires a visible topic label;
- Analysis Note is memory provenance or ordinary chat context;
- a computer-imported memory needs different UI;
- every transcript row needs permanent processing states;
- permanent provenance is required for safe chunking;
- a model's advertised context window is the correct chunk size;
- character count is sufficiently accurate for model/provider budgeting;
- a one-million-token context window means the provider allows one million tokens;
- all chunks may be filed independently without affecting review safety;
- malformed or truncated JSON means the conversation contained no memories;
- custom prompts may redefine the app's response schema or lifecycle powers;
- Model Rules should be ordinary Associative Memories;
- LangMem or Mem0 must be imported wholesale;
- another project's benchmark automatically applies to Speak-GPT;
- existing code retroactively proves approval.

## 19. Completion Standard

A memory feature is complete only when:

- its full user workflow exists;
- all data the user is approving is visible in the review flow;
- Type is user-owned;
- the Roleplay tab is derived from World, Roleplay Character, and Campaign scopes;
- underlying fictional scope and target boundaries remain intact;
- Companion memories are isolated to their target companion and can provide semantically retrieved continuity or behavior guidance;
- conversations can independently allow General memories, companion memories, both, or neither;
- conversations can independently create General memories, companion memories, and Model Rules, or be excluded from analysis;
- deleting a companion clearly warns about and permanently deletes all companion-targeted memories;
- retrieval can provide several relevant memories within a tested bounded context budget;
- optional importance can be disabled without losing stored values;
- generated changes remain proposals until approved;
- the analysis purpose, processing method, and approximate request size are visible before a run;
- long conversations are split by conservative token budgets rather than a giant character ceiling;
- a failed chunk cannot create a hidden partial analysis or advance the bookmark;
- JSON and truncation failures are reported honestly;
- prompt profiles, chunk defaults, and retrieval budgets are supported by the evaluation harness;
- Model Rules remain a separate reviewable output stream;
- focused tests pass;
- Android Checks is green;
- the owner exercises the relevant device path.

The goal is a memory system the user can scan and trust, not a taxonomy maintenance hobby or a transcript-eating context-window contest.
