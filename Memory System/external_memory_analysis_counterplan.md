# Speak-GPT Memory Systems: Canonical Recovery Plan

**Revision 22, 2026-08-04**

This is the active memory-system plan. It records the owner's decisions, the required implementation order, and the completion gates for each phase. Existing code is evidence of what was built, not proof that the behavior was approved.

## 1. Product Goal

The memory system should reduce work, not create a second job.

It must:

1. review new conversation material after the last successful bookmark;
2. propose useful memories;
3. check the existing database before filing duplicates or updates;
4. show everything the user is approving directly in Pending for fast scanning;
5. retrieve relevant approved memories during chats with the on-device embedding model;
6. keep roleplay memory separate from non-roleplay memory.

Any field or mechanism that does not materially help those goals must not become required product behavior.

## 2. Authority Rules

1. The owner's direct decisions control the product.
2. Anything not approved remains open. An implementation agent may not choose it and report the choice afterward.
3. Green CI proves only that code compiled and tests passed.
4. Models and embeddings may propose and search. They never directly approve, replace, supersede, archive, or delete memories.
5. API analysis and computer-file analysis must create the same Pending memory experience.
6. This roadmap is binding. Do not skip ahead, combine phases into an uncontrolled rewrite, or redesign unrelated memory systems.

## Ordered Implementation Roadmap

This section is the required execution order. The detailed product rules later in this document define what each phase must preserve.

### Execution Rules

- Implement one phase at a time on a dedicated feature branch.
- Do not begin the next phase until the current phase's tests pass and its completion gate is satisfied.
- Keep migrations additive and reversible where practical. Never delete existing user data merely to simplify implementation.
- When legacy storage conflicts with the approved product, first stop the legacy field from affecting prompts, UI, matching, embeddings, and retrieval. Physical column removal comes later.
- If a phase encounters a genuine product decision not answered here, stop only that decision path and return one focused question. Continue unrelated work within the approved phase.
- At the end of every phase, record changed files, migrations, tests, unresolved items, and the exact commit.
- Do not redesign Lorebooks, roleplay cards, VAD, Whisper, logging, provider routing, or unrelated app systems while implementing this plan.

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
   - scope and target eligibility.
2. Identify the current database version and every upgrade path that touches the memory tables.
3. Identify all backup, restore, seed, export, and import formats that contain affected fields.
4. Identify tests that already protect memory behavior and gaps requiring new tests.
5. Commit a concise audit report at `Memory System/memory_implementation_audit.md` containing:
   - current behavior;
   - approved target behavior;
   - safe migration approach;
   - files involved;
   - risks and rollback notes.

Restrictions:

- No feature redesign.
- No destructive schema migration.
- No automatic conversion of legacy `lore` into Roleplay scope.
- Do not remove existing roleplay `Add to Card` behavior during the audit.

**Completion Gate:** The audit report exists, every affected code path is accounted for, and no destructive migration remains unexplained.

### Phase 1: Storage Compatibility and Database Migration

**Status:** [ ] Not Started

**Goal:** Make the database capable of representing the approved memory shape without losing existing data.

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
11. Add migration tests for:
   - a fresh install;
   - an existing database with every legacy Type;
   - existing importance values;
   - No Type;
   - General and Roleplay scopes;
   - backup/restore compatibility.

**Completion Gate:** Fresh and upgraded databases open successfully, no memory is lost, Types are user-owned in storage, importance supports 0, and titles/source lineage are behaviorally inert.

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
   - every other existing scope appears in General.
5. Preserve underlying target boundaries. The Roleplay tab is not a generic retrieval scope.
6. Create one canonical Pending filing path used by:
   - API Memory Assistant suggestions;
   - validated computer imports;
   - manual creation where applicable.
7. The canonical Pending object must contain the same approved fields regardless of origin.
8. Do not expose API/computer origin in the memory object or visible card.

**Completion Gate:** Unit tests prove that all origins create the same Pending object, Type CRUD preserves memories, and scope grouping does not collapse target boundaries.

### Phase 3: Embeddings, Retrieval, and Ranking

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
   - API/computer origin.
3. Apply actual scope and target eligibility before semantic ranking.
4. Retrieve Active memories only for ordinary chats.
5. Keep Pending, Archived, and Superseded memories out of ordinary chat retrieval.
6. Remove fixed-Type authority behavior. A Type does not automatically transform a memory into a command.
7. Remove title, source, and fixed-Type ranking bonuses.
8. When `Use Importance Ratings` is On:
   - apply a relevance floor first;
   - use importance only as a bounded secondary ordering signal among already relevant memories;
   - 0 adds no boost;
   - importance cannot make an irrelevant memory eligible.
9. When the setting is Off, the importance contribution is exactly zero.
10. Importance does not affect exact duplicate detection or Possible Match candidate generation.
11. Report embedding failure honestly. Do not silently present a semantic failure as no match.
12. Add focused tests for:
   - General and Roleplay eligibility;
   - target isolation between worlds, campaigns, and characters;
   - Type soft clues;
   - importance On and Off;
   - inactive lifecycle exclusion;
   - embedding failure.

**Completion Gate:** Retrieval tests demonstrate relevant memory application, target isolation, and optional importance behavior without title/source influence.

### Phase 4: Memory Controls UI

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

### Phase 5: Pending Browser, General/Roleplay Tabs, and Fast Review

**Status:** [ ] Not Started

**Goal:** Make ordinary Pending memories safe to scan and approve without opening every item.

Required work:

1. Add the **General** and **Roleplay** tabs.
2. Derive Roleplay membership only from World, Roleplay Character, and Campaign scopes.
3. Keep actual scope and target visible on every card.
4. Every ordinary Pending card shows:
   - complete memory text;
   - actual scope and target;
   - Type or No Type;
   - all tags or No Tags;
   - importance only when enabled;
   - Information;
   - the approved actions.
5. No title appears.
6. No-match card actions remain:
   - Information top-right;
   - discard X immediately left of save/disk;
   - save/disk at far right;
   - no caution icon;
   - no Review button.
7. Possible-Match card actions remain:
   - caution icon top-left;
   - Information top-right;
   - Review bottom-right;
   - no save/disk;
   - no discard X.
8. Implement **Accept All** only for visible, ordinary, conflict-free proposals with valid placement.
9. Accept All never bypasses Possible Match Review or hidden unresolved data.
10. API and computer-imported proposals render identically.
11. Audit existing roleplay-specific `Add to Card` behavior before changing it:
   - do not remove it by implication;
   - if the new card layout conflicts with it, return one focused decision before altering that action.
12. Add UI tests for both tabs, card variants, direct field visibility, and Accept All exclusions.

**Completion Gate:** The user can scan and safely approve ordinary Pending memories, while conflict cards and roleplay-specific actions remain protected.

### Phase 6: Possible Match Review and Lifecycle Actions

**Status:** [ ] Not Started

**Goal:** Resolve duplicates and updates using the existing database without automatic destructive decisions.

Required work:

1. Run exact normalized text matching without requiring embeddings.
2. Use the local embedding model for differently worded related memories.
3. Respect actual scope and target context when generating candidates.
4. Keep Type as only the same soft embedding clue used elsewhere.
5. Ignore importance during candidate generation.
6. Use temporary comparison vectors for Archived and Superseded memories and discard them immediately.
7. Never make inactive comparison vectors chat-retrievable.
8. Implement the approved Review page:
   - proposal first;
   - complete visible metadata;
   - matches below with checkboxes;
   - scrolling actions after the final card;
   - no floating or hidden controls.
9. Implement the approved actions atomically:
   - Save & Edit Old Memory;
   - Save & Supersede;
   - Save & Replace.
10. Revalidate the proposal and selected memories before committing.
11. Backing out leaves the proposal Pending.
12. Add tests for one and many matches, lifecycle transitions, restore history, permanent replacement, stale selections, and transaction rollback.

**Completion Gate:** Possible Match reliably surfaces database conflicts and every resolution is atomic, user-controlled, and correctly reflected in lifecycle state.

### Phase 7: Conversation Bookmark, API Analysis, and Computer Import

**Status:** [ ] Not Started

**Goal:** Make analysis resumable and ensure every route files suggestions through the same reviewed workflow.

Required work:

1. Store one durable bookmark per chat representing the last message successfully reviewed and safely filed.
2. Freeze the analysis end point when a run begins.
3. Leave later messages for the next run.
4. Advance the bookmark only after all valid suggestions from the frozen range are safely filed into Pending.
5. Do not advance after failure, cancellation, or process death.
6. Use short-lived run bookkeeping only for locking, retry safety, duplicate prevention, and interrupted-run recovery.
7. Never copy the bookmark, chat ID, transcript row IDs, excerpts, or source timestamps into a memory.
8. API analysis and computer import both call the canonical Pending filing path from Phase 2.
9. Strictly validate computer packages before filing.
10. A valid imported suggestion receives no import badge, source label, special card, or separate review path.
11. Keep Lorebook import and retrieval separate from Associative Memory. Do not merge the two systems while repairing this route.
12. Add tests for retry, cancellation, process death, duplicate import, messages arriving during analysis, and identical API/computer results.

**Completion Gate:** Analysis resumes from the correct bookmark, failures do not skip material, and API/computer suggestions become identical Pending memories.

### Phase 8: Legacy Cleanup, Backup Compatibility, and Release Verification

**Status:** [ ] Not Started

**Goal:** Remove obsolete behavior only after the replacement system is working and protected by tests.

Required work:

1. Re-audit legacy fields after Phases 1 through 7.
2. Remove obsolete reads and writes for:
   - titles;
   - fixed Type constants;
   - source-chat lineage;
   - API/computer presentation differences;
   - transcript row processing states made unnecessary by the bookmark.
3. Remove or migrate physical database columns only when:
   - no approved behavior depends on them;
   - upgrade and rollback behavior is documented;
   - backup, restore, seed, export, and import tests pass.
4. Confirm older backups import without creating visible titles, invalid Types, or source-chat memories.
5. Confirm new backups preserve:
   - user Types;
   - No Type;
   - tags;
   - importance values even while disabled;
   - scope and target relationships;
   - lifecycle and supersession history.
6. Run focused unit, migration, instrumentation, and UI tests.
7. Run Android Checks and require green CI.
8. Exercise the full device path:
   - analyze a chat;
   - review ordinary Pending memories;
   - use Accept All;
   - resolve a Possible Match;
   - verify General and Roleplay separation;
   - toggle importance Off and On;
   - add, rename, and delete a Type;
   - export and restore.
9. Update the phase statuses and commit the final implementation report.

**Completion Gate:** CI is green, the owner has exercised the relevant device paths, backups are safe, and no legacy field influences the revised product.

### Decisions That Do Not Block the Early Phases

These may remain open until the phase that touches them:

- the exact standalone tag-management screen;
- whether AI-assigned importance is ever offered as a future optional feature;
- additional editable placement fields in Save & Edit Old Memory;
- any change to roleplay-specific Add to Card behavior.

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

It does not contain a title or durable source-chat history.

### 3.1 No Titles

No Associative Memory has a separate title.

- models do not generate titles;
- prompts and schemas do not request titles;
- cards and editors do not display title fields;
- exact matching does not compare titles;
- embedding documents do not include titles;
- retrieval has no title bonus;
- any legacy title column is compatibility baggage only and must not affect product behavior.

### 3.2 No Source-Chat Memory

A saved or Pending memory does not remember which chat produced it.

Do not attach or expose:

- chat name or chat ID;
- conversation UID;
- transcript row IDs;
- turn numbers;
- source timestamps or excerpts;
- quote hashes;
- links back to the source conversation;
- durable source-evidence tables derived from the chat.

Temporary run bookkeeping may exist outside the memory only long enough to finish or safely recover analysis/import. It does not become part of the memory.

## 4. Existing Scopes Determine Roleplay Grouping

Do not create a new generic `roleplay` scope.

The app already has the scopes that identify roleplay memory. A memory is grouped into the **Roleplay** side of the Memory Browser when its existing scope is any of:

- **World** (`world`);
- **Roleplay Character** (`rp_character`);
- **Campaign** (`campaign`).

This is a derived browser and retrieval grouping rule, not a fourth roleplay scope stored on the memory.

All other existing scopes remain in the **General** side unless the owner later changes a specific scope's meaning.

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
- the proposed Type is shown directly on the Pending card;
- the user can change or remove the Type before saving;
- ordinary memory editing can change or remove the Type;
- Type is available for human browsing and filtering;
- Type does not determine scope or Roleplay grouping;
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
- tags do not determine scope or Roleplay grouping;
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

## 8. Conversation Review Uses a Bookmark

For each chat:

1. store one bookmark representing the last message successfully reviewed and safely filed;
2. read from immediately after that bookmark through a frozen end point captured when the run begins;
3. leave messages added after the frozen end point for the next run;
4. advance the bookmark only after valid suggestions are safely filed into Pending;
5. do not advance it after failure, cancellation, or process death;
6. never copy the bookmark or source-chat identity into a memory.

Do not require permanent per-row pending, processed, excluded, or claimed states merely to know where analysis stopped. A short-lived run lock or frozen end marker may exist invisibly while a run is active.

## 9. Embeddings and Retrieval

The on-device embedding model may use:

- memory text;
- the user-visible Type name as an optional soft clue when a Type is assigned;
- tags only where they do not overpower the text.

It does not use:

- a title;
- importance;
- source-chat identity;
- API/computer origin.

Retrieval rules:

- derive the General or Roleplay browser group from the memory's existing scope;
- preserve the actual World, Roleplay Character, Campaign, or other scope and target boundaries during eligibility filtering;
- retrieve Active memories only;
- semantic relevance is the primary signal;
- no title bonus, source bonus, or fixed-Type bonus;
- optional importance is applied only under Section 7;
- Pending, Archived, and Superseded memories never enter normal chats;
- model failure is reported honestly.

## 10. Possible Match

Possible Match finds candidates. It does not decide whether something is a duplicate, update, contradiction, replacement, or supersession.

- exact normalized text matching works without an embedding model;
- differently worded related memories use the local embedding model;
- a proposal is shown in the Roleplay tab when its scope is World, Roleplay Character, or Campaign;
- candidates must still respect the proposal's actual scope and target context rather than comparing every Roleplay-tab memory together;
- Active memories may use stored vectors;
- Archived and Superseded memories may be embedded temporarily and immediately discarded;
- inactive comparison vectors never become retrieval-eligible;
- comparison is lazy per proposal;
- semantic failure cannot be shown as no match;
- Type may contribute only through the same soft embedding clue;
- importance does not affect candidate generation;
- every resolution revalidates before committing.

## 11. Lifecycle

| State | Meaning | Enters Normal Chats? |
|---|---|---:|
| **Pending** | Proposal awaiting the user. | No. |
| **Active** | Approved current memory. | Yes, when eligible. |
| **Archived** | Shelved memory. | No. |
| **Superseded** | Retained historical version replaced by a newer memory. | No. |
| **Deleted** | Permanently removed. | No. |

Superseded memories remain browsable, restorable, and permanently deletable. One new memory may supersede several selected old memories.

The Superseded Memories filter remains:

- **Hide**, default;
- **Include**;
- **Only**.

## 12. Pending Is Designed for Fast Scanning

The Pending screen shows all user-relevant data that will be saved. It must not hide fields behind endless per-memory editing screens.

Every ordinary card shows:

- complete memory text;
- selected Type or No Type;
- tags, when present;
- its General/Roleplay destination through the tab it appears in;
- importance only when **Use Importance Ratings** is On;
- all other approved visible fields that will be saved;
- no title.

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

## 13. Possible Match Review UI

- dedicated full-page screen;
- proposal first, full width, no checkbox, Information at top-right;
- proposal scrolls normally and is not pinned;
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

## 14. API and Computer Workflows

After strict import validation, a computer-created suggestion becomes the same Pending object as an API Memory Assistant suggestion.

It has the same:

- memory shape;
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

## 15. Phase 0 Audit Checklist

The Phase 0 report must account for every use of:

- memory title;
- importance ranges that exclude 0;
- importance ranking that cannot be disabled;
- AI-assigned importance;
- hard-coded Type lists;
- `lore` or `roleplay` treated as a routing Type;
- a newly invented generic `roleplay` scope instead of deriving the tab from World, Roleplay Character, and Campaign;
- Roleplay-tab grouping that accidentally collapses underlying scope or target boundaries;
- Type-dependent authority behavior;
- tags or Types used as excessive ranking bonuses;
- title bonuses;
- source-chat lineage attached to memories;
- permanent transcript processing states where one bookmark would suffice;
- World, Roleplay Character, or Campaign memories appearing in the General tab;
- API/computer origin shown in memory UI.

For each item, report:

1. where it exists;
2. what visible or retrieval behavior it changes;
3. whether it can be neutralized without a database migration;
4. the narrow safe implementation or migration path.

Do not delete database columns blindly. First stop unapproved fields from affecting prompts, UI, matching, embedding, and ranking.

## 16. Explicitly Forbidden Claims

Future agents must not claim:

- memories need titles;
- importance is required for embeddings;
- turning importance Off should erase stored ratings;
- the AI must assign importance;
- a second default-zero toggle is required;
- the starter Type list is permanently fixed;
- Lore is an Associative Memory Type;
- Type determines Roleplay grouping;
- Roleplay requires a new generic `roleplay` scope;
- the Roleplay tab allows unrelated worlds, characters, or campaigns to share retrieval context;
- users cannot create arbitrary Types;
- every memory must have a Type;
- a Type must control model obedience or truth;
- tags and Types are the same system;
- World, Roleplay Character, or Campaign memories belong mixed into the General tab;
- a computer-imported memory needs different UI;
- every transcript row needs permanent processing states;
- existing code retroactively proves approval.

## 17. Completion Standard

A memory feature is complete only when:

- its full user workflow exists;
- all data the user is approving is visible in the review flow;
- Type is user-owned;
- the Roleplay tab is derived from World, Roleplay Character, and Campaign scopes;
- underlying fictional scope and target boundaries remain intact;
- optional importance can be disabled without losing stored values;
- generated changes remain proposals until approved;
- focused tests pass;
- Android Checks is green;
- the owner exercises the relevant device path.

The goal is a memory system the user can scan and trust, not a taxonomy maintenance hobby.
