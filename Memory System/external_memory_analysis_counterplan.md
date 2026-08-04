# Memory Systems Canonical Plan: Owner-Approved Rules Only

**Revision 15, 2026-08-04**

This is the active memory-system plan. It records direct owner decisions, verified implementation facts, and clearly labeled open questions. An agent may not turn an implementation accident, an older document, or its own recommendation into product approval.

## 1. Authority rules

1. The owner's direct decisions control the product.
2. Anything not directly approved remains open. It may exist in code, but it is not approved design.
3. Green CI proves that code compiled and tests passed. It does not prove that the UI or behavior was approved on-device.
4. Models, embeddings, computer agents, and auditors may propose. They never directly mutate authoritative memories.
5. No implementation agent may invent user-facing fields, labels, controls, layouts, destinations, retention rules, or action meanings.
6. When the owner has not approved something, stop at that boundary. Do not choose for her and report the choice afterward.

## 2. Memory shape: no titles, ever

No memory has a separate title.

This applies to every memory route and state:

- manually entered memories;
- API Memory Assistant suggestions;
- computer-review suggestions;
- Memory Auditor suggestions;
- Pending memories;
- Active memories;
- Archived memories;
- Superseded memories;
- imported memories;
- roleplay-related memories;
- Lorebook Memory suggestions where the approved Lorebook entry design also has no separate title.

A memory consists of its memory text plus whatever existing non-title metadata the app already requires. An agent may not invent a title because it thinks titles improve search, display, export, or editing.

Consequences:

- models do not generate titles;
- prompts and result schemas do not request titles;
- Pending and Review cards do not display titles;
- editors do not expose title fields;
- exact matching does not compare titles;
- semantic embedding documents do not depend on titles;
- computer packages and imports do not require titles;
- Memory Auditor proposals do not create titles;
- any legacy/internal title column is compatibility baggage only and must not become visible or required.

Any current title control or title-dependent path is an implementation defect to audit. It is not an approved feature.

## 3. A saved memory does not remember its source chat or import route

A saved or Pending memory must not store or expose the conversation it came from as part of the memory.

Do not attach to a memory or proposal:

- source chat name;
- source chat ID;
- conversation UID;
- transcript row IDs;
- turn numbers;
- chat timestamps;
- chat excerpts;
- quote hashes;
- a link back to the originating conversation;
- any equivalent durable conversation lineage.

A computer-imported suggestion must not retain or display a special computer origin after it enters Pending. It is the same kind of suggestion as one produced by API Memory Assistant.

Do not add to the memory or its visible UI:

- a computer-imported badge;
- an API-created badge;
- a route/source label;
- a special Information row;
- a separate filter or Pending category;
- computer-specific fields;
- different actions or styling.

Temporary run/import bookkeeping may exist outside the memory record only as needed to prevent duplicate processing, recover interrupted work, and make import replay-safe. It must not become memory provenance, must not appear in the memory UI, and must not survive merely because the memory was approved.

The exact contents of the Information control are not fully approved. It must not show a source chat or distinguish API suggestions from computer-imported suggestions. No agent may fill the gap by inventing an evidence-lineage or source-label system.

## 4. Existing placement behavior is preserved, not newly approved

The app already has placement/context behavior for different memories and fictional settings. The owner has not approved a new scope taxonomy or a redesign through this document.

Therefore:

- preserve the app's existing placement boundaries unless the owner starts a focused placement discussion;
- do not add new scope categories, target types, placement screens, or placement requirements;
- do not rename existing concepts as an architecture decision;
- do not claim the owner approved technical words such as scope, target catalog, placement identity, or conversation lineage merely because code uses them;
- Possible Match must not compare unrelated fictional contexts as though they are the same memory;
- duplicate display names must not silently select the first record.

The final set of editable non-title fields on the Review screen remains open. An agent may not decide it.

## 5. Binding memory lifecycle

| State | Meaning | Used in normal chats? |
|---|---|---:|
| **Pending** | Proposal awaiting the user's decision. | No. |
| **Active** | Approved current memory. | Yes, when Associative Search is enabled and usable. |
| **Archived** | Shelved memory. | No. |
| **Superseded** | Retained historical memory replaced by a newer memory. | No. |
| **Deleted** | Permanently removed. | No. |

### Superseded behavior

- Superseded means history only.
- Superseded memories never enter normal chats.
- They remain browsable, restorable, and permanently deletable.
- One new memory may supersede several selected old memories.
- The history relationship is many-to-many.

### Superseded Memories filter

The Memory Browser filter has exactly:

- **Hide**, default;
- **Include**;
- **Only**.

Archived and Superseded remain different states.

## 6. Binding Possible Match behavior

Possible Match finds candidates. It never decides what the relationship means.

### Exact matching

Exact matching remains deterministic and works without an embedding model.

It uses normalized memory text and the app's existing placement/type/status behavior. It does not use a title.

Required outcomes:

- an already-existing equivalent Active or Pending memory does not create a second identical draft;
- a type difference may become a Possible Match rather than a silent overwrite;
- an equivalent Archived or Superseded memory becomes a Possible Match rather than being silently restored, skipped, or overwritten;
- unrelated fictional contexts are not collapsed together;
- duplicate names never resolve by choosing the first result.

### Semantic matching

Differently worded but related Associative Memories use the installed on-device embedding model.

- no external/API AI performs phone-side semantic matching;
- token overlap or Jaccard is not semantic matching;
- cosine similarity is not shown as a probability or percentage;
- embedding results are candidates only;
- similarity never automatically merges, deletes, replaces, archives, or supersedes;
- Active memories may use stored vectors;
- Archived and Superseded memories may be embedded temporarily for comparison and then discarded;
- inactive comparison vectors are not persisted;
- chat retrieval remains Active-only;
- comparison runs lazily per proposal rather than embedding the whole archive when Pending opens;
- a short-lived comparison cache may exist only for the current UI session;
- stale asynchronous work must not update the wrong card;
- without a model, exact matching still works and semantic matching reports unavailable honestly;
- semantic failure must not silently become no match.

Every resolution rechecks the proposal and selected memories at commit time. A stale result changes nothing and leaves the proposal recoverable.

## 7. Binding Associative Pending card UI

Pending remains inside the existing Memory Browser and uses the normal memory-card visual language.

Every card shows the complete memory text. It does not show a title.

The card is identical whether the suggestion arrived from API Memory Assistant or Computer Memory Review.

### No Possible Match

- top-left caution position empty;
- top-right Information control;
- full memory text in the card body;
- bottom-right discard **X** immediately left of the save/disk icon;
- save/disk at the far right;
- no caution icon;
- no Review button.

### One or more Possible Matches

- top-left caution icon, unlabeled and without words beside it;
- top-right Information control;
- full memory text in the card body;
- one labeled **Review** action at bottom-right;
- no save/disk icon;
- no discard X;
- the entire card is not the Review control;
- Review is not placed in the top row.

All icon-only controls have accessibility labels.

## 8. Binding Possible Match Review UI

Review is a dedicated full-page screen.

It is identical for API Memory Assistant suggestions and computer-imported suggestions.

- proposed memory first, full width, no checkbox, Information at top-right;
- proposed memory scrolls normally and is not pinned;
- existing possible matches appear below in full-width normal memory cards;
- checkbox top-left and Information top-right on each existing match;
- suggested matches may begin checked, but the user can change every selection;
- no separate selection screen;
- resolution actions appear after the final match and scroll with the page;
- no floating buttons, hidden swipe actions, unlabeled resolution icons, sticky overlays, or controls over memory text;
- no memory title appears anywhere.

Action order:

1. **Save & Edit Old Memory**, available only when exactly one old memory is selected.
2. **Save & Supersede**.
3. **Save & Replace**, using destructive styling.

No resolution is allowed when no old memory is selected.

### Save & Edit Old Memory

- save the proposal as Active;
- keep the selected old memory Active;
- edit the old memory on the Review screen;
- single-selection only;
- no title field;
- the final set of editable non-title fields is still an owner decision.

### Save & Supersede

- save the proposal as Active;
- mark every checked old memory Superseded;
- record every old-to-new relationship;
- preserve the old memories for history, restoration, and permanent deletion;
- keep Superseded memories out of chats.

### Save & Replace

- save the proposal as Active;
- permanently delete every checked old memory;
- Replace is intentionally destructive;
- Save & Supersede is the history-preserving alternative.

Each resolution is atomic. Backing out leaves the proposal Pending. A stale or missing record applies nothing.

The older required action set of Keep Both / Keep Existing / archive-style Replace / Delete Suggestion is superseded and must not be restored as the canonical design.

## 9. Roleplay Pending remains unresolved, not approved as an exception

The current code reportedly leaves roleplay-scoped Pending rows on an older Accept / Delete / Edit / Add to Card flow while ordinary Associative Pending uses the new cards.

The owner agreed only to inspect the ordinary cards first. She did not approve a permanent roleplay exception.

Therefore:

- do not treat the split as settled design;
- do not remove sanctioned roleplay actions such as Add to Card without approval;
- do not extend the ordinary UI into roleplay by guessing where those actions belong;
- record what exists during device testing and return for one focused decision when necessary.

## 10. API and computer suggestions are the same Pending memory

A computer-imported Associative suggestion is not a separate memory type, source presentation, or review product.

After strict import validation, it becomes the exact same Pending memory object and uses the exact same behavior as a suggestion produced by API Memory Assistant.

It must have the same:

- record shape;
- memory text and approved non-title metadata;
- Pending destination;
- card layout;
- Information control behavior;
- Possible Match detection;
- loading and retry behavior;
- Review screen;
- selected-match behavior;
- editor;
- actions;
- confirmation behavior;
- acceptance transaction;
- final Active/Archived/Superseded/Deleted lifecycle.

It must not have:

- a different card;
- a source/import badge;
- a special label;
- a different Information panel;
- a separate Pending category;
- a different editor;
- extra or missing actions;
- different styling;
- different Possible Match rules;
- different final memory behavior.

The import mechanism may retain invisible replay/session state outside the memory long enough to complete or safely resume import. That state does not become part of the memory and does not alter the visible product.

Lorebook Memory proposals use the existing Lorebooks → Pending system. The same principle applies there: after validation, a computer-imported Lorebook suggestion must look and behave exactly like an API-created Lorebook suggestion, not a computer-specific variant.

## 11. Embedding and memory-system distinction

The phone has two different memory retrieval systems:

- **Associative Search** uses the on-device embedding model for semantic retrieval and differently worded Possible Match discovery.
- **Lorebooks** use explicit keyword triggers and do not require an embedding model.

Pending, Archived, and Superseded Associative Memories never enter normal chats.

The conversation archive is source material for analysis. It is not itself a prompt-memory source merely because it was retained for analysis.

The exact Archive This Chat and Memory Participation behavior remains governed by existing approved work. This document does not add new source-chat retention to memories.

## 12. Computer Memory Review

The intended complete loop is:

```text
Phone exports a review package
  → a file-capable computer AI proposes memories
  → phone validates the returned file
  → the valid proposal is filed exactly as API Memory Assistant would file it
  → the ordinary existing Pending UI appears
  → user decides
```

The computer never edits the phone database directly.

The package must not contain credentials, databases, recovery secrets, embedding vectors, model files, or unrelated private data.

Associative proposal records contain the same memory text and approved non-title metadata expected from API Memory Assistant. They do not contain a title or durable source-chat lineage.

The computer result may have temporary import identifiers required to validate and replay the file safely. Those identifiers remain import bookkeeping and are removed or ignored when the ordinary Pending memory is created.

The exact package schema, privacy disclosure, and transfer workflow must be approved when that work unit begins. Older plans do not grant automatic approval to conversation UID, transcript lineage, quote hashes, source excerpts, permanent evidence tables, special origin labels, or computer-specific Pending UI.

Import must be strict, replay-safe, and proposal-only. After validation it must call the same filing path used by API Memory Assistant, not a parallel computer filing path that merely tries to imitate it.

## 13. Memory Auditor: approved direction, not direct mutation

Memory Auditor reviews existing Associative Memories and may propose candidates involving duplicates, outdated information, possible contradiction/negation, unclear wording, weak placement, edit, merge, archive, replace, or supersession.

It may run through the configured API model or the same computer package/import route.

Auditor findings:

- contain no titles;
- retain no source chat;
- go to Memories → Pending;
- remain proposals;
- use the same phone-side matching and resolution authority;
- never directly mutate the library.

Lorebooks and roleplay cards may be read-only comparison material only after that behavior is explicitly approved for the relevant work unit.

## 14. Verified code versus approved design

The following repository work exists and may be device-tested:

- Superseded Memories filter: `ddfcf07aab7af78f8dda2f299b43918b629e108f`
- deterministic and embedding Possible Match foundation: `7894ffce34b7dd8bbc251c8988f6f3af8359afd2`
- Archived/Superseded semantic comparison: `64c38a4940377829c922e70d810300b2fc05976c`
- recorded Pending/Review rulings: `f626e41d789496422a8be9190dc69e9bf6a6489a`
- Pending/Review UI implementation: `b0f253bde9146ffdd8b98052e53574945cf384a8`
- Step 1.5 merge to `main`: `a17bd427edcdfaeb30cf3e24a819c8faecca68b7`

Code that differs from this plan is a defect or an unresolved implementation fact. It is not retroactive approval.

## 15. Immediate repair and verification queue

### A. Audit title leakage

Find every memory title field, prompt key, parser key, database use, editor field, card rendering, matching input, embedding input, export field, import field, and Auditor field.

Classify each as:

- invisible legacy compatibility that can safely remain temporarily;
- visible/required behavior that violates the no-title rule;
- dead code.

Do not remove schema columns blindly. Remove title from product behavior and define a safe migration only after the audit.

### B. Audit source-chat and import-route leakage

Find every place a memory or proposal stores or displays chat name, chat ID, conversation UID, transcript row, turn reference, excerpt, quote hash, API/computer route, import badge, or equivalent lineage/source distinction.

Separate:

- temporary run/import bookkeeping required to avoid data loss or duplicate processing;
- durable memory/proposal data or visible UI that violates the no-source-chat and identical-UI rules.

Do not expand lineage or source presentation. Report the current behavior and the narrow removal/migration needed.

### C. Device-test the merged Step 1.5 UI

Inspect:

- ordinary Pending card layout;
- title leakage;
- Information contents;
- semantic loading/retry;
- one and multiple matches;
- Save & Edit Old;
- Save & Supersede and history filter;
- destructive Save & Replace;
- roleplay Pending behavior.

Fix concrete bugs incrementally. Do not use device testing to approve unasked design choices.

### D. Continue the remaining roadmap one bounded unit at a time

After the audits and device findings:

1. finish Archive This Chat / Memory Participation behavior already approved elsewhere;
2. freeze Computer Memory Review package behavior only after explicit review;
3. build export;
4. prove real computer-agent output;
5. build strict import;
6. route each valid imported proposal through the exact API filing path and ordinary Pending UI;
7. build Memory Auditor routes;
8. prove complete end-to-end loops.

No unit may invent titles, permanent source-chat lineage, route-specific memory UI, new scope architecture, or a second memory authority.

## 16. Explicitly forbidden claims

Future agents must not claim:

- memories have or need titles;
- a title improves retrieval and is therefore allowed;
- memories must remember which chat they came from;
- source chat lineage was owner-approved;
- conversation UID, transcript references, excerpts, or quote hashes are automatically required product fields;
- technical scope/target language is owner-approved merely because the current database uses it;
- the roleplay Pending split is permanent;
- title/content-only editing is an approved final design;
- token overlap is semantic matching;
- Save & Replace preserves old memories;
- Superseded memories may enter chats;
- a computer-imported memory needs a special card, badge, source label, category, editor, or action set;
- a computer route may use a parallel filing or Pending system as long as it looks similar;
- import origin belongs in the memory's visible Information panel;
- an agent may resolve an open design question because implementation is easier that way.

## 17. Completion standard

A memory feature is complete only when:

- its full user workflow exists;
- no visible control leads to unfinished work;
- it matches direct owner decisions;
- unapproved behavior is labeled and not promoted;
- focused tests pass;
- Android Checks is green;
- the owner exercises the relevant device path;
- concrete bugs are fixed without reopening or rewriting settled meanings.

The purpose of this plan is not to make every old document sound consistent. It is to prevent anything the owner did not approve from quietly becoming the product.