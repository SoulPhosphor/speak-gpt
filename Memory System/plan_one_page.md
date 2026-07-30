# The Memory Plan — authoritative owner instructions

This is the authoritative owner-facing instruction file for all memory work.
Agents must read it before `external_memory_analysis_counterplan.md` and must
follow it wherever the longer document, source comments, old plans, or pull
request descriptions conflict with it.

The long counterplan is implementation machinery. It may break work into as
many internal steps as needed, but it may not create new product decisions,
new wording, or new owner obligations. The owner never has to open it, quote
its section numbers, reconcile its revisions, or manage its substeps.

*(Status corrected 2026-07-30. Keep this page current and truthful.)*

## The actual three-part plan

1. **Finish and repair phone memory.** Make capture, analysis, search,
   duplicate handling, indexing, and recovery reliable on the phone.
2. **Export and prove the computer workflow.** Create a portable package and
   verify that file-capable AIs can search it and return valid suggestions.
3. **Bring suggestions back for review.** Import computer suggestions into
   Pending with duplicate/conflict handling and safe approval or rollback.

Internal steps such as 1.1–1.7 are engineering subdivisions, not additional
owner phases.

## What already works on main

- Chats are recorded for review while **Archive this chat** is on.
- **Memory Assistant** reads them and creates suggestions in **Pending**.
  Nothing is approved, changed, or deleted without user action.
- Analysis can continue after leaving the screen or turning the screen off.
- A deleted suggestion remains rejected for that source conversation.
- The **Memory Engine** picker chooses which memory sources feed chats.
- The app already contains Memory Assistant progress, completion,
  interruption, partial-failure, full-failure, nothing-found, and no-new-memory
  status surfaces.

Existing strings are not automatically owner-approved merely because they are
already in code. Before changing or adding Memory Assistant wording, inventory
the exact existing text and the state that invokes it. Reuse the existing
status surface instead of creating a second vocabulary.

## Binding behavior decisions

### Archive this chat

Turning **Archive this chat** off pauses archiving. It must not erase, reset,
advance, or replace the last truthful archive bookmark/cursor.

Turning it back on silently includes every eligible message that has not
already been fully processed, starting from the preserved bookmark and
continuing through the current chat history.

There is no confirmation dialog, snackbar, toast, or choice. Do not implement
**Include Earlier Messages?**, **Include Earlier Messages**, **New Messages
Only**, **Earlier Messages Unavailable**, or any equivalent flow.

### Missing embedding model in Memory Browser

Memory Browser and Memory Assistant still work without an embedding model.
Saved memories remain viewable and editable, and analysis may still create
Pending suggestions. What is unavailable is using saved memories inside chats.

Each time Memory Browser is opened without an embedding model installed, show
a dismissible snackbar-style reminder for that visit:

> Saved memories can't be used in chats until an embedding model is installed.

Action: **Okay**

After **Okay**, hide it for the current visit. Show it again the next time
Memory Browser opens while the model is still missing. Stop showing it once a
model is installed. Do not replace this with a permanent inline banner.

### Lorebook-only analysis and suggestion review

The lorebook-only analysis and lorebook-suggestion review design in the long
counterplan is **not approved**. Do not build it, treat its labels as pending
copy, or imply that its proposed screen came from the owner.

The proposal currently means: an analysis mode would create suggested lore
book entries with trigger keywords; the suggestions would be reviewed in the
lore book area, assigned to a destination lore book, and written only after
individual approval. Its split menu, destination selector, create-new-book
path, approval cards, summary links, behavior, and wording all remain
undecided.

## Work still remaining

- Self-repairing search and index housekeeping.
- Duplicate comparison and resolution.
- Lore book speed and logging improvements.
- The computer export/search/import workflow.
- Any no-model lorebook-analysis mode only after its product flow is actually
  discussed and approved.

## Implementation discipline

Internal database, retrieval, indexing, and recovery work may proceed without
new copy when it creates no new visible state.

Do not invent visible wording to describe internal machinery. When an existing
screen already represents the state, extend it minimally. A genuinely new
visible state stops at the wording boundary for one focused owner decision,
not a questionnaire and not a wall of draft copy.
