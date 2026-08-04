# Speak-GPT Memory Systems: Canonical Recovery Plan

**Revision 18, 2026-08-04**

This is the active memory-system plan. It records the owner's decisions and a small implementation baseline. Existing code is evidence of what was built, not proof that the behavior was approved.

## 1. Product goal

The memory system should reduce work, not create a second job.

It must:

1. review new conversation material after the last successful bookmark;
2. propose useful memories;
3. check the existing database before filing duplicates or updates;
4. show everything that will be saved directly in Pending for fast scanning;
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
- an invisible stable memory ID;
- ordinary internal timestamps;
- its lifecycle state;
- only the minimum existing placement information needed to prevent unrelated contexts from bleeding together.

It does not contain a title, importance score, or durable source-chat history.

### 3.1 No titles

No Associative Memory has a separate title.

- models do not generate titles;
- prompts and schemas do not request titles;
- cards and editors do not display title fields;
- exact matching does not compare titles;
- embedding documents do not include titles;
- retrieval has no title bonus;
- any legacy title column is compatibility baggage only and must not affect product behavior.

### 3.2 No importance score

Importance was not approved and is not part of the product.

- no importance control appears in memory UI;
- models do not assign importance;
- prompts and imports do not request it;
- semantic or lexical ranking does not use it;
- an existing importance column is neutralized until a safe cleanup is designed.

### 3.3 No source-chat memory

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

## 4. User-owned Types

Type is approved as a human-owned category system.

### 4.1 Starter Types

Speak-GPT ships with:

- **Fact**
- **Preference**
- **Event**
- **Status**
- **Instruction**
- **Roleplay**

The old **Lore** Type is removed and replaced by **Roleplay**.

These are starter choices, not a permanent ontology. A user may create categories such as Likes, Dislikes, Classic Cars, Health, Writing, Pets, or anything else useful to them.

### 4.2 Type behavior

- an Associative Memory may have zero or one selected Type;
- the Memory Assistant normally suggests one Type, and may choose only from the user's current Type list;
- the proposed Type is shown directly on the Pending card;
- the user can change or remove the Type before saving;
- ordinary memory editing can change or remove the Type;
- Type is available for human browsing and filtering;
- Type does not determine whether a memory is true, important, or authoritative;
- Type does not automatically change a memory into a special command or alter how the receiving model must obey it;
- a mistaken Type must not make Accept All dangerous.

### 4.3 Type and embeddings

The embedding document may include the visible Type name as a soft semantic clue alongside the memory text.

For example, a Type named **Classic Cars** may help related memories cluster together.

However:

- the memory text remains the primary semantic content;
- Type is not a hard retrieval gate, except for Roleplay separation;
- Type cannot override a poor textual match;
- Type does not receive a separate ranking bonus;
- renaming or removing a Type refreshes affected embeddings because the soft clue changed;
- exact duplicate matching remains based on the memory text and necessary context, not the Type name alone.

### 4.4 Type management in Memory Controls

Memory Controls contains a **Memory Types** area.

It shows the complete current Type list as ordinary list rows.

The user can:

- add a Type;
- rename a Type;
- delete an ordinary Type.

Rename uses a stable internal Type ID so every associated memory reflects the new name without rewriting each memory relationship individually.

Before deleting a Type, show a confirmation dialog:

> **Delete this type?**
> This will remove it from the associated memories.

The dialog should include the number of associated memories. A local indexed count is inexpensive even for hundreds or thousands of memories.

Deleting an ordinary Type:

- never deletes a memory;
- removes that Type assignment from associated memories;
- leaves those memories as **No Type** rather than silently guessing a replacement;
- refreshes their embeddings using the remaining memory text and tags.

No Type is a valid state. The user may assign another Type later.

### 4.5 Roleplay is the routing Type

The **Roleplay** starter Type has one special product function: it routes memories to the Roleplay memory space.

- memories with the Roleplay Type appear in the Roleplay tab;
- they do not appear in the non-roleplay tab;
- normal chat retrieval and Possible Match do not cross between Roleplay and non-roleplay spaces;
- API and computer-created Roleplay suggestions use the same Roleplay Pending area;
- the visible Roleplay Type name may be renamed while its stable internal routing identity remains Roleplay;
- deleting or replacing the Roleplay routing Type is not decided by this document;
- no deletion flow may silently move Roleplay memories into the non-roleplay tab or strip their separation.

This is the only approved hard behavior attached to a Type. The exact delete/replacement flow for the routing Type must return to the owner as one focused decision.

## 5. Tags remain separate

Tags are not Types.

- one memory has zero or one Type but may have multiple tags;
- Types provide broad user-owned organization;
- tags provide smaller cross-cutting words or themes;
- tags do not determine Roleplay routing;
- Type management and tag management remain separate;
- neither tags nor Types may become mandatory ranking weights that overpower semantic relevance.

The exact tag-management UI is not redesigned by this document.

## 6. Memory Browser separation

The Memory Browser has a dedicated **Roleplay** tab and a separate non-roleplay tab.

- all Roleplay Pending, Active, Archived, and Superseded memories belong in Roleplay;
- all other Types and No Type belong in the non-roleplay tab;
- the final label for the non-roleplay tab remains a user-facing wording decision;
- searching, filtering, bulk actions, and Possible Match remain inside the selected tab;
- the embedding search does not compare or retrieve across the Roleplay boundary.

Internal links to companions, projects, worlds, campaigns, or characters may remain only where needed to prevent unrelated contexts from bleeding together. They do not justify a new visible scope taxonomy.

## 7. Conversation review uses a bookmark

For each chat:

1. store one bookmark representing the last message successfully reviewed and safely filed;
2. read from immediately after that bookmark through a frozen end point captured when the run begins;
3. leave messages added after the frozen end point for the next run;
4. advance the bookmark only after valid suggestions are safely filed into Pending;
5. do not advance it after failure, cancellation, or process death;
6. never copy the bookmark or source-chat identity into a memory.

Do not require permanent per-row pending, processed, excluded, or claimed states merely to know where analysis stopped. A short-lived run lock or frozen end marker may exist invisibly while a run is active.

## 8. Embeddings and retrieval

The on-device embedding model uses:

- memory text;
- the user-visible Type name as an optional soft clue when a Type is assigned;
- tags only where the existing approved embedding design uses them without overpowering the text.

It does not use:

- a title;
- importance;
- source-chat identity;
- API/computer origin.

Retrieval rules:

- choose Roleplay or non-roleplay space first;
- retrieve Active memories only;
- semantic relevance is the primary signal;
- no importance weight, title bonus, source bonus, or fixed-Type bonus;
- Pending, Archived, and Superseded memories never enter normal chats;
- model failure is reported honestly.

## 9. Possible Match

Possible Match finds candidates. It does not decide whether something is a duplicate, update, contradiction, replacement, or supersession.

- exact normalized text matching works without an embedding model;
- differently worded related memories use the local embedding model;
- comparisons stay within Roleplay or non-roleplay space;
- Active memories may use stored vectors;
- Archived and Superseded memories may be embedded temporarily and immediately discarded;
- inactive comparison vectors never become retrieval-eligible;
- comparison is lazy per proposal;
- semantic failure cannot be shown as no match;
- every resolution revalidates before committing.

## 10. Lifecycle

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

## 11. Pending is designed for fast scanning

The Pending screen shows all user-relevant data that will be saved. It must not hide fields behind endless per-memory editing screens.

Every ordinary card shows:

- complete memory text;
- selected Type or No Type;
- tags, when present;
- all other approved visible fields that will be saved;
- no title;
- no importance score.

The Type and other editable visible fields can be corrected directly without leaving the review flow.

A bulk **Accept All** action may approve only ordinary, conflict-free proposals currently visible in the selected tab. It must never bypass Possible Match Review or approve hidden data the user could not scan.

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

## 12. Possible Match Review UI

- dedicated full-page screen;
- proposal first, full width, no checkbox, Information at top-right;
- proposal scrolls normally and is not pinned;
- proposed Type and tags are visible and editable;
- existing matches below in normal full-width cards;
- checkbox top-left and Information top-right on each existing match;
- suggested matches may begin checked, but the user can change every selection;
- no second selection screen;
- actions after the final match and scrolling with the page;
- no floating buttons, hidden swipe actions, sticky overlays, or controls over text;
- no titles or importance controls.

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
- no title or importance field;
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

## 13. API and computer workflows

After strict import validation, a computer-created suggestion becomes the same Pending object as an API Memory Assistant suggestion.

It has the same:

- memory shape;
- suggested Type behavior;
- Roleplay/non-roleplay destination;
- card and Review UI;
- Possible Match rules;
- actions and confirmations;
- final lifecycle.

It receives no visible import badge, source label, special category, different Information panel, or computer-specific controls.

Temporary import IDs may exist outside the memory solely to prevent duplicate import and recover interrupted import.

## 14. Immediate implementation audit

Audit current code for every use of:

- memory title;
- importance;
- hard-coded Type lists;
- `lore` as an Associative Memory Type;
- Type-dependent rendering or authority behavior;
- tags or Types used as excessive ranking bonuses;
- title bonuses;
- source-chat lineage attached to memories;
- permanent transcript processing states where one bookmark would suffice;
- mixed Roleplay and non-roleplay UI or search corpora;
- API/computer origin shown in memory UI.

For each item, report:

1. where it exists;
2. what visible or retrieval behavior it changes;
3. whether it can be neutralized without a database migration;
4. the narrow safe implementation or migration path.

Do not delete database columns blindly. First stop unapproved fields from affecting prompts, UI, matching, embedding, and ranking.

## 15. Explicitly forbidden claims

Future agents must not claim:

- memories need titles;
- importance is required for embeddings or retrieval;
- the starter Type list is permanently fixed;
- Lore remains an Associative Memory Type;
- users cannot create arbitrary Types;
- every memory must have a Type;
- a Type must control model obedience or truth;
- tags and Types are the same system;
- Roleplay belongs mixed with non-roleplay memory;
- a computer-imported memory needs different UI;
- every transcript row needs permanent processing states;
- existing code retroactively proves approval.

## 16. Completion standard

A memory feature is complete only when:

- its full user workflow exists;
- all data the user is approving is visible in the review flow;
- Type is user-owned;
- Roleplay and non-roleplay remain separated;
- generated changes remain proposals until approved;
- focused tests pass;
- Android Checks is green;
- the owner exercises the relevant device path.

The goal is a memory system the user can scan and trust, not a taxonomy maintenance hobby.