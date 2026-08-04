# Speak-GPT Memory Systems: Canonical Recovery Baseline

**Revision 16, 2026-08-04**

This document replaces invented memory architecture with a small researched baseline plus the owner's direct decisions. It is the active plan for repairing and continuing the memory system.

## 1. Authority

1. The owner's direct decisions control the product.
2. Existing code proves only what was implemented. It does not prove that the behavior was requested or approved.
3. Anything not directly approved is absent from the product baseline. An implementation agent may not fill gaps with its preferred architecture.
4. Research is used to choose a proven default so the owner is not forced to design every database field and ranking weight.
5. Product-specific deviations from the baseline require a reason that matters to the user, not merely an implementation preference.
6. Models, embeddings, computer agents, and auditors may propose memories. They never directly mutate authoritative memories.

## 2. Research baseline

Speak-GPT uses a **flat-memory baseline modeled primarily after Mem0**, not a custom ontology assembled from several unrelated systems.

The baseline concept is simple:

- one memory is primarily a piece of memory text;
- it has an invisible stable identifier and ordinary internal timestamps;
- relevant memories are found through semantic retrieval, with exact matching available for duplicates;
- extra metadata is optional and added only when Speak-GPT has an approved reason for it;
- updates, deletes, and retrieval operate on the memory record rather than a graph of invented concepts.

Speak-GPT deliberately adds only the product behavior the owner approved:

- every generated memory enters Pending for human review;
- embeddings run on-device;
- Active, Archived, Superseded, Pending, and Deleted have distinct lifecycle meanings;
- Possible Match is advisory and user-resolved;
- roleplay memories are visibly separated from non-roleplay memories;
- API and computer analysis produce the same Pending memory experience.

Do not import Graphiti/Zep-style episode provenance, temporal knowledge graphs, entity-edge ontologies, or conversation lineage merely because those systems support them. Do not import Letta-style always-visible memory blocks as the Associative Memory model. Do not import LangGraph's semantic/episodic/procedural taxonomy as mandatory fields on each memory.

## 3. Memory shape

### 3.1 No titles, ever

No memory has a separate title.

This applies to manually entered memories, generated suggestions, imported suggestions, Pending, Active, Archived, Superseded, roleplay memories, non-roleplay memories, and Memory Auditor proposals.

Consequences:

- models do not generate titles;
- prompts and schemas do not request titles;
- cards and editors do not display title fields;
- exact matching does not compare titles;
- embedding documents do not include titles;
- retrieval has no title bonus;
- any legacy title column is compatibility baggage only and must not affect product behavior.

### 3.2 No importance score

Importance was not owner-approved and is not part of the baseline.

- no 1-5 importance control appears in memory UI;
- models do not assign importance;
- prompts and import schemas do not request it;
- semantic or lexical ranking does not use importance;
- importance cannot make a weaker match outrank a more relevant memory;
- any existing importance column is neutralized for compatibility until a safe cleanup is designed.

### 3.3 No individual memory type taxonomy

The owner did not approve `fact`, `preference`, `event`, `status`, `instruction`, or `lore` as required types on every Associative Memory.

- no type picker appears on ordinary memory cards or editors;
- models do not classify every memory into those labels;
- matching, embedding, and retrieval do not depend on those labels;
- an existing `kind` column is not allowed to alter rendering or ranking while this audit is unresolved;
- if a genuinely different product behavior later requires a distinct record class, that behavior must be presented and approved directly.

The distinction between **Associative Memories** and **Lorebooks** is not an individual memory type. They are separate memory systems with different retrieval mechanisms. Whether one analysis run may propose to both systems remains a focused workflow question and must not be decided by an implementation agent.

### 3.4 No source-chat memory

A saved or Pending memory does not remember which chat produced it.

Do not attach or expose:

- chat name or chat ID;
- conversation UID;
- transcript row IDs;
- turn numbers;
- timestamps or excerpts from the source chat;
- quote hashes;
- links back to the source conversation;
- durable evidence/provenance tables derived from the source chat.

Temporary run bookkeeping may exist outside the memory only long enough to finish or safely recover the current analysis/import. It does not become part of the memory.

### 3.5 API and computer origin disappears after filing

After strict import validation, a computer-created suggestion becomes the same Pending object as an API Memory Assistant suggestion.

No memory or visible card receives:

- an API/computer badge;
- a route/source label;
- a special category or filter;
- a different Information panel;
- computer-specific fields, controls, or styling.

## 4. Memory Browser separation

### 4.1 Roleplay has its own tab

**Roleplay memories must appear in their own top-level Memory Browser tab. They are not mixed with real-life/human memories.**

This is a binding owner decision.

- all roleplay Pending, Active, Archived, and Superseded memories belong in the Roleplay tab;
- non-roleplay memories belong in the other Memory Browser tab;
- the final user-facing label for the non-roleplay tab is not decided by this document;
- searching, filtering, bulk actions, and Possible Match must remain within the selected memory space;
- the embedding search must not compare or retrieve roleplay memories while operating in the non-roleplay space, or vice versa;
- a computer-imported roleplay suggestion lands in the same Roleplay Pending area as an API-created roleplay suggestion.

The database may use an internal partition key to enforce this boundary. That internal key does not justify a user-facing scope taxonomy.

### 4.2 Existing placement details are not redesigned here

The app already has links between some memories and companions, projects, worlds, campaigns, or roleplay characters. This document does not approve a new hierarchy of scopes or targets.

- preserve only existing behavior required to keep unrelated fictional contexts separate;
- do not add new placement categories or screens;
- do not silently choose records by duplicate display name;
- bring any proposed placement redesign to the owner as a focused product question.

## 5. Conversation review uses a bookmark

The owner-approved model is a bookmark, not permanent processing labels spread across chat transcript rows.

For each chat:

1. Store one bookmark representing the last message successfully reviewed and safely filed.
2. A new analysis reads from immediately after that bookmark through a frozen end point captured when the run begins.
3. Messages added after that frozen end point wait for the next run.
4. Advance the bookmark only after the run's valid suggestions have been safely filed into Pending.
5. If the run fails, is cancelled, or the app dies before filing completes, do not advance the bookmark.
6. The bookmark is analysis progress, not memory provenance. It is never copied into a memory.

Do not require permanent per-row `pending`, `processed`, `excluded`, or claimed states merely to know where analysis stopped. A short-lived run lock or frozen end marker may exist invisibly while a run is active, but the durable user model remains one bookmark per chat.

Any chat-level eligibility toggle, such as Archive This Chat, decides whether the chat participates. It does not create a second transcript ontology.

## 6. Embeddings and retrieval

### 6.1 What the embedding model uses

The on-device embedding model embeds the memory text. It does not need or receive:

- a title;
- importance;
- the rejected type taxonomy;
- source-chat identity;
- API/computer origin.

Derived vectors are internal search data, not authoritative memory content.

### 6.2 Retrieval order

- choose the correct memory space first: Roleplay or non-roleplay;
- retrieve Active memories only;
- semantic relevance is the primary ranking signal;
- no importance weight, type weight, title bonus, or source bonus is allowed;
- Archived, Pending, and Superseded memories never enter normal chats;
- model failure must be reported honestly and must not silently pretend semantic search succeeded.

### 6.3 Possible Match

Possible Match finds candidates. It does not decide whether something is a duplicate, update, contradiction, replacement, or supersession.

- exact normalized text matching works without an embedding model;
- differently worded related memories use the local embedding model;
- Active memories may use stored vectors;
- Archived and Superseded memories may be embedded temporarily for comparison and immediately discarded;
- inactive comparison vectors are never made retrieval-eligible;
- comparison is lazy per proposal rather than eagerly embedding the entire archive;
- a semantic failure cannot be shown as `no match`;
- every resolution revalidates the proposal and selected memories before committing.

## 7. Binding lifecycle

| State | Meaning | Enters normal chats? |
|---|---|---:|
| **Pending** | Proposal awaiting the owner. | No. |
| **Active** | Approved current memory. | Yes, when eligible and retrieval is usable. |
| **Archived** | Shelved memory. | No. |
| **Superseded** | Retained historical version replaced by a newer memory. | No. |
| **Deleted** | Permanently removed. | No. |

Superseded memories:

- remain browsable, restorable, and permanently deletable;
- never enter chats;
- may be linked many-to-many when one new memory supersedes several old memories.

The Superseded Memories filter remains:

- **Hide**, default;
- **Include**;
- **Only**.

## 8. Binding ordinary Pending card UI

Every Pending card shows the complete memory text and no title.

### No Possible Match

- top-left caution position empty;
- top-right Information control;
- full memory text in the body;
- bottom-right discard **X** immediately left of save/disk;
- save/disk at the far right;
- no caution icon and no Review button.

### One or more Possible Matches

- top-left unlabeled caution icon;
- top-right Information control;
- full memory text in the body;
- one labeled **Review** action at bottom-right;
- no save/disk and no discard X;
- the entire card is not secretly clickable as Review.

API and computer-imported suggestions use the exact same card.

## 9. Binding Possible Match Review UI

- dedicated full-page screen;
- proposal first, full width, no checkbox, Information at top-right;
- proposal scrolls normally and is not pinned;
- existing matches below in normal full-width cards;
- checkbox top-left and Information top-right on each existing match;
- suggested matches may begin checked, but the user can change every selection;
- no second selection screen;
- actions after the final match and scrolling with the page;
- no floating buttons, hidden swipe actions, sticky overlays, or controls over text;
- no titles, importance controls, or type controls.

Action order:

1. **Save & Edit Old Memory**, exactly one selected memory.
2. **Save & Supersede**.
3. **Save & Replace**, destructive styling.

No resolution is allowed with zero selected memories.

### Save & Edit Old Memory

- save the proposal as Active;
- keep the selected old memory Active;
- edit the old memory on the Review screen;
- no title, importance, or rejected type field;
- the exact remaining editable placement fields are not decided by this document.

### Save & Supersede

- save the proposal as Active;
- mark all checked old memories Superseded;
- preserve history and relationships;
- keep old memories restorable and deletable;
- keep them out of chats.

### Save & Replace

- save the proposal as Active;
- permanently delete all checked old memories;
- Replace is intentionally destructive;
- Supersede is the history-preserving alternative.

All resolutions are atomic and revalidated. Backing out leaves the proposal Pending.

## 10. Roleplay Pending behavior

Roleplay belongs in its own tab. The earlier choice to leave roleplay rows mixed into an old flow is not an approved permanent exception.

Roleplay-specific actions such as **Add to Card** may still be needed because roleplay cards are a separate authored system. Their exact placement and relationship to ordinary memory approval must be reviewed inside the Roleplay tab rather than guessed.

No agent may:

- mix roleplay Pending rows into the non-roleplay tab;
- remove Add to Card without approval;
- copy the ordinary action set into roleplay while ignoring roleplay-specific needs;
- use this unresolved action detail to delay the binding tab separation.

## 11. API and computer workflows

A valid computer import calls the same filing path used by API Memory Assistant.

It must produce the same:

- record shape;
- tab destination based on roleplay/non-roleplay content;
- Pending card;
- Possible Match behavior;
- Review UI;
- actions and confirmations;
- acceptance transaction;
- final lifecycle.

Temporary import IDs may exist outside the memory solely to prevent duplicate import and recover interrupted import. They disappear or become irrelevant when the ordinary Pending memory is filed.

## 12. Immediate audit before further architecture

Audit current code for every use of:

- memory title;
- importance;
- `kind` or individual memory type;
- tags/categories used for ranking;
- title bonuses;
- source-chat IDs, transcript lineage, excerpts, or evidence hashes attached to memories;
- permanent transcript row processing states where one bookmark would suffice;
- API/computer origin shown in memory UI;
- roleplay and non-roleplay memories mixed in one tab or search corpus.

For each item, report:

1. where it exists;
2. what visible or retrieval behavior it currently changes;
3. whether it can be neutralized without a database migration;
4. the narrow safe removal path.

Do not delete database columns blindly. First stop unapproved fields from affecting prompts, UI, matching, embedding, and ranking. Migrate or remove storage only after the behavior is understood.

## 13. Explicitly forbidden claims

Future agents must not claim:

- memories need titles;
- importance is required for embeddings or good retrieval;
- every memory must have a fact/preference/event/status/instruction/lore type;
- memories must remember their source chat;
- every transcript row must permanently carry a processing state;
- roleplay memories belong mixed with real-life/human memories;
- a computer-imported memory needs different UI or metadata;
- Graphiti, LangGraph, Letta, or any other researched system's optional architecture became owner-approved merely because it was mentioned;
- existing code retroactively proves approval.

## 14. Completion standard

A memory feature is complete only when:

- its full user workflow exists;
- it matches direct owner decisions and the flat-memory baseline;
- no visible control leads to unfinished work;
- roleplay and non-roleplay memories remain separated;
- generated changes remain proposals until approved;
- focused tests pass;
- Android Checks is green;
- the owner exercises the relevant device path.

The recovery goal is not to ask the owner to redesign memory theory. It is to use a proven simple baseline and bring forward only the few Speak-GPT-specific choices that materially affect her experience.