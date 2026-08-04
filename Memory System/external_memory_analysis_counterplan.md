# Speak-GPT Memory Systems: Canonical Recovery Plan

**Revision 21, 2026-08-04**

This is the active memory-system plan. It records the owner's decisions and a small implementation baseline. Existing code is evidence of what was built, not proof that the behavior was approved.

## 1. Product goal

The memory system should reduce work, not create a second job.

It must:

1. review new conversation material after the last successful bookmark;
2. propose useful memories;
3. check the existing database before filing duplicates or updates;
4. show everything the user is approving directly in Pending for fast scanning;
5. retrieve relevant approved memories during chats with the on-device embedding model;
6. keep roleplay memory separate from non-roleplay memory.

Any field or mechanism that does not materially help those goals must not become required product behavior.

## 2. Authority rules

1. The owner's direct decisions control the product.
2. Anything not approved remains open. An implementation agent may not choose it and report the choice afterward.
3. Green CI proves only that code compiled and tests passed.
4. Models and embeddings may propose and search. They never directly approve, replace, supersede, archive, or delete memories.
5. API analysis and computer-file analysis must create the same Pending memory experience.

## 3. Associative Memory shape

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

### 3.1 No titles

No Associative Memory has a separate title.

- models do not generate titles;
- prompts and schemas do not request titles;
- cards and editors do not display title fields;
- exact matching does not compare titles;
- embedding documents do not include titles;
- retrieval has no title bonus;
- any legacy title column is compatibility baggage only and must not affect product behavior.

### 3.2 No source-chat memory

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

## 4. Existing scopes determine Roleplay grouping

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

### 4.1 The Roleplay tab is a grouping, not a collapsed scope

The three underlying scopes retain their own meanings inside the Roleplay tab.

- a World memory remains a World memory;
- a Roleplay Character memory remains tied to the appropriate roleplay character;
- a Campaign memory remains tied to the appropriate campaign;
- unrelated worlds, characters, and campaigns must not be compared or retrieved as though they share one generic roleplay context;
- duplicate display names must not silently select the first target.

Searching and filtering may operate across the visible Roleplay tab for the human, but chat retrieval and Possible Match must continue to respect the actual scope and target boundaries required to prevent fictional contexts from bleeding together.

Types may still be fantasy-specific. A user may create Types such as Character Detail, Quest, Spell, Campaign Note, or anything else, but those names do not create or remove Roleplay grouping.

Internal links to companions, projects, worlds, campaigns, or characters may remain only where needed to preserve their existing context. They do not automatically justify a new visible scope taxonomy or a required scope dropdown on every memory card.

## 5. User-owned Types

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

### 5.2 Type behavior

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

### 5.3 Type and embeddings

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

### 5.4 Type management in Memory Controls

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

## 6. Tags remain separate

Tags are not Types.

- one memory has zero or one Type but may have multiple tags;
- Types provide broad user-owned organization;
- tags provide smaller cross-cutting words or themes;
- tags do not determine scope or Roleplay grouping;
- Type management and tag management remain separate;
- neither tags nor Types may become mandatory ranking weights that overpower semantic relevance.

The exact tag-management UI is not redesigned by this document.

## 7. Optional importance ratings

Importance is an optional user-controlled ranking aid, not part of the embedding itself.

### 7.1 Memory Controls toggle

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

### 7.2 Who chooses importance

The Memory Assistant and computer reviewer do **not** assign importance in the initial implementation.

Every generated proposal starts at 0. The user may change it while scanning Pending or later while editing a memory.

Do not add a second “allow AI to choose importance” toggle now. That would add prompt complexity and subjective AI judgment to a feature intended to reflect the user's priorities. It may be reconsidered later only if real use shows a need.

### 7.3 How importance affects retrieval

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

## 8. Conversation review uses a bookmark

For each chat:

1. store one bookmark representing the last message successfully reviewed and safely filed;
2. read from immediately after that bookmark through a frozen end point captured when the run begins;
3. leave messages added after the frozen end point for the next run;
4. advance the bookmark only after valid suggestions are safely filed into Pending;
5. do not advance it after failure, cancellation, or process death;
6. never copy the bookmark or source-chat identity into a memory.

Do not require permanent per-row pending, processed, excluded, or claimed states merely to know where analysis stopped. A short-lived run lock or frozen end marker may exist invisibly while a run is active.

## 9. Embeddings and retrieval

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

| State | Meaning | Enters normal chats? |
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

## 12. Pending is designed for fast scanning

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

### One or more Possible Matches

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
- actions after the final match and scrolling with the page;
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

## 14. API and computer workflows

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

## 15. Immediate implementation audit

Audit current code for every use of:

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

## 16. Explicitly forbidden claims

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

## 17. Completion standard

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
