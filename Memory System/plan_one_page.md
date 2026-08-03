# The Memory Plan — the only page the owner needs

The long counterplan file in this folder is agent machinery. Agents read
it. The owner never has to open it, and no agent may send her to it,
quote its section numbers at her, or ask her to manage it.

This page is the single source of truth for the current plan and the
owner's approved wording and behavior rulings. Where it conflicts with the
long counterplan or any other planning draft in this folder, this page
wins. `owner_approved_rules.md` remains in force for the underlying memory
data rules it covers; if a newer ruling recorded here contradicts an older
rule there, the newer ruling wins.

*(Status recorded 2026-08-01. Agents: update this page whenever the state
changes — it must always be true.)*

## What already works (on main)

- Chats are recorded for review while **Archive This Chat** is on.
- **API Memory Assistant** reads them and suggests memories. Everything lands
  in **Pending**. Nothing is ever saved, changed, or deleted without your
  approval.
- Analysis keeps running if you leave the screen or the screen turns off,
  and recovers on its own if the app is killed. It can no longer mark a
  chat "reviewed" that it never actually read.
- A suggestion you delete stays deleted — renaming the chat can't bring
  it back.
- Search is fixed: approved memories are found by meaning when the
  embedding model is installed, by keywords when it isn't, and they no
  longer silently vanish from search.
- The **Memory Engine** picker in Settings chooses what feeds your chats.
- **No-model mode** works: with no embedding model installed, a notice explains
  Associative Search can't enter chats yet, and the **Lorebook Memories**
  analysis type suggests keyword-triggered Lorebook entries into **Pending**
  instead.

## The one tap between you and memories in your chats

Install the embedding model in **Advanced Memory Settings**. That's it.
(Your own ruling: memories can't enter chats without it. Everything else
already works without it.)

## Binding owner corrections

- **Lorebook** is always one word in user-facing text throughout the app,
  including plurals and compound labels: **Lorebook**, **Lorebooks**,
  **Lorebook Memories**, and **Lorebook Suggestions**. Never display
  **lore book** or **lore books** as two words.
- Turning **Archive This Chat** off pauses archiving without erasing,
  resetting, advancing, or replacing the last truthful archive bookmark.
  Turning it back on silently processes every eligible message not already
  fully processed. Never show an **Include Earlier Messages?** prompt or any
  equivalent choice.
- Each time Memory Browser opens without an embedding model, show a
  dismissible reminder for that visit: **Associative Search can't be used in
  chats until an embedding model is installed.** Action: **Okay**. Show it
  again on the next visit while the model is still missing; do not use a
  permanent inline banner.
- Before adding or replacing API Memory Assistant wording, inventory the exact
  text already in the app and when it appears. Reuse the existing status
  surface rather than inventing a parallel set of progress or result messages.
- **Lorebook suggestion review structure is approved.** Lorebook Suggestions
  are reviewed in the Lorebooks area, not the Memory Browser. The ordinary
  Lorebooks screen does not show the split control. While one or more Lorebook
  Suggestions are pending, show a split control at the top matching the Memory
  Browser pattern, with **Lorebooks** and **Pending**. The split disappears when
  no Lorebook Suggestions remain. Each suggestion shows the proposed entry text
  and trigger keywords — no separate title — plus a drop-down labeled
  **Assign Lorebook** for choosing an existing Lorebook or creating a new one
  through the normal full-page flow. The user may edit, approve, or delete each
  suggestion. Nothing is written to a Lorebook until that suggestion is
  individually approved, and this flow never edits or deletes existing
  Lorebook entries.
- When Lorebook analysis finds suggestions, use the existing API Memory Assistant
  result surface and show **Potential Lorebook Memories found: N** with a
  **View** button. **View** opens the Lorebooks area directly on **Pending**.
- The **Memory Analysis Type** control uses the app's existing two-column
  settings-row pattern, matching the **Memory Engine** row rather than creating
  a new layout:
  - Left column title: **Memory Analysis Type**
  - Left column subtitle, shown as three paragraphs with visible spacing between them:

    **Choose which memory system this analysis should create suggestions for.**

    **Associative Memories use an embedding model to surface memories connected to the ideas and topics being discussed.**

    **Lorebook Memories are activated by specific keywords and do not require an embedding model.**
  - Right-hand column: a drop-down aligned at the top with exactly two choices:
    **Associative Memories** and **Lorebook Memories**.
  - **Associative Memories** is the default.
  There is no **Both** option: one run creates one kind of suggestion. Users
  may use both memory systems in chats or run each analysis type separately,
  but a single analysis run must not create both kinds at once.

## API Memory Assistant progress

While conversation analysis is active, use the existing inline status surface
and show:

1. An indeterminate spinner.
2. **Analyzing Conversations**
3. Once a fixed analysis batch total is known, a determinate progress bar.
4. **X%** beneath the bar.

Keep the spinner visible while analysis is active. Before the fixed total is
known, do not invent a percentage. Calculate progress from completed sealed
analysis batches divided by the fixed total claimed for that run. New messages
that arrive after the run begins wait for a later run and do not change the
denominator.

The Android foreground-service notification uses:

- Title: **Analyzing Conversations**
- Indeterminate progress before the total is known.
- A determinate progress bar with **X% complete** once the total is known.
- Tapping the notification opens **API Memory Assistant**.

Remove the old **X of Y conversations** wording. Reaching 100% is not success
until every batch result is durably recorded and final parsing, validation,
duplicate checking, and Pending staging finish.

## Roleplay Memory Budget Calculator

Remove only the proposed active-scene word or token feature from Chat → Quick
Settings. Quick Settings itself remains unchanged. Do not add an
**Always-active scene** total or memory-budget notice there. The dedicated
calculator below is the only approved location for this feature.

Add a row at the bottom of the **Roleplay** screen:

- Title: **Memory Budget Calculator**
- Tapping the row opens a dedicated calculator and editor screen.

Screen introduction, verbatim:

> Estimate the token footprint of static memories included in every prompt.
> Select active Lorebooks, worlds, or characters below to preview their text
> and calculate their combined impact on your context window.

Directly beneath the introduction and before the selectable sections, show a
live total using the app's title-size text style:

> **Total Estimated Tokens: X**

The total reflects the current on-screen selections and text, including edits
that have not been saved yet. Saving is not required for the preview total to
change. Dropdown selection changes update it immediately. Text edits trigger an
event-driven recalculation after a 300 ms debounce from the most recent edit;
do not continuously poll or recalculate on every keystroke. Revert and Save
also update the total immediately.

The selectable sections appear vertically, one beneath another. Every selector
starts at **None** and lists the existing named items currently available in the
app:

1. **Lorebooks**
2. **World**
3. **Campaign**
4. **Roleplay Character**
5. **Party Members**
6. **Glamour** — place this selector last.

Use the app's existing selection behavior for each type. Types that currently
allow one active item remain single-select; types that currently allow several
active items retain their existing multi-selection behavior.

Only static text that would be included every turn is counted. For Lorebooks,
count only always-active or core text. Keyword-triggered entries are excluded
from the static total because they are not present in every prompt.

When an item is selected, immediately show its editable data using the same
field order, section layout, labels, text styling, spacing, line height,
validation, and behavior as its actual card editor. Example section header:

> **World: Sparktown**    **500 Tokens**

Each selected section shows its own live approximate token count. The per-section
counts and the combined total use the app's shared token-estimation utility
rather than introducing a separate counting formula.

Each selected section has:

- **Revert** — discard unsaved edits in that section and restore the last saved
  card data.
- **Save** — save that section's changes to the underlying card so the normal
  card screen and all other uses immediately reflect them.

If the user tries to leave the calculator or replace a selection while any
section contains unsaved edits, show a dedicated confirmation box:

> **Discard all changes?**
>
> The following sections have unsaved changes:
>
> [Each affected section appears on its own row.]

Actions:

1. **Save All** — save every listed section, then continue the action the user
   attempted.
2. **Discard All** — discard every listed section's unsaved edits, then continue
   the action the user attempted.
3. **Continue Editing** — close the box, cancel the attempted navigation or
   selection change, and return to the calculator exactly as it was. Nothing is
   saved, discarded, or otherwise changed.

The calculator must not contain a copied second version of the card layout or
hard-coded duplicate line-height values. Reuse the same card-editor component
where practical, or the same shared field and text styles where a shared
component is not possible. A later card-layout or line-height change must update
both the normal card and calculator without separate maintenance.

## Computer Memory Review

The Memory Manager list contains three separate rows:

1. Rename the existing **Memory Assistant** row to **API Memory Assistant**.
2. Directly beneath it, add **Computer Memory Review**.
3. Add a separate row titled **Memory Auditor**.

**Computer Memory Review** is only for reviewing conversations for new memory
suggestions. Existing-memory housekeeping belongs to **Memory Auditor**.

Subtitle for **Computer Memory Review**:

> **Use an AI on your computer to review chats.**

Introduction at the top of the screen, shown as two paragraphs with visible
spacing between them:

> Create a review package for an AI on your computer. It can review chats for new memories.
>
> Once complete, import the result file. Your memory manager will organize all flagged suggestions into their respective slots so you can review and confirm them at your own pace.

The screen order remains:

1. The existing export function at the top.
2. The import function directly below it.

Do not redesign the existing conversation export flow. The export area uses the
same approved two-column **Memory Analysis Type** picker described above. The
selected type determines whether the exported review package asks the computer
AI to create **Associative Memories** or **Lorebook Memories**. Retain the
already-decided eligible-chat selection and package-export behavior.

The exported `.sgmemory` package includes its own README, AI workflow
instructions, safety and scope instructions, result schema, and proposals
template. The user does not need to invent separate instructions for the AI.

### Import

Title:

> **Import**

Helper text:

> Upload the result file created by the external AI to add its suggestions to Pending.

Button:

> **Import**

After the user chooses a result file, reuse the same inline progress pattern as
the API Memory Assistant. Disable the button and show an indeterminate spinner
with:

> **Importing Memories…**

Do not say **Importing Conversations**. Conversations remain in the review
package and are not imported into the app.

When import and validation finish successfully, remove the spinner and show:

> **Potential Memories Found: N**

Show a **View** button beside or directly beneath the result. **View** opens the
appropriate Pending area based on the result file's declared analysis type:

- Associative-memory results open **Memories → Pending**.
- Lorebook-memory results open **Lorebooks → Pending**.

The inline import-error rules listed under **Import Memory Audit Results**
below apply to this Import section as well: every import error appears
directly beneath the Import button in plain language and remains visible
until the user chooses another file or retries.

The import may finish quickly, but the app must still validate the file, IDs,
placements, evidence, duplicates, and current phone state before creating Pending
items. The computer never writes directly into the memory store, and nothing is
approved automatically.

## Memory Auditor

Memory Manager row subtitle:

> **Review existing memories for possible duplicates, conflicts, outdated information, and other cleanup suggestions.**

Screen introduction:

> Audit your existing associative memories for possible duplicates, conflicts, outdated information, unclear wording, or items that may need to be edited, merged, archived, or split. All findings are sent to Pending for review.

Directly beneath the introduction, show:

> **You may continue using the app and move between screens while the audit runs. It will continue working in the foreground as long as the app remains open.**

Both audit routes inspect the existing associative-memory catalog itself. They
do not analyze current, new, eligible, unprocessed, or archived conversations
for new-memory discovery. Lorebooks and roleplay cards may be included only as
read-only comparison material so overlap can be detected.

Memory Auditor has no **Memory Analysis Type** picker. Both audit routes work
on associative memories only, so there is no type to choose.

The auditor may flag possible duplicates, contradictions, records that appear
superseded, unclear wording, weak placement, missing evidence, or memories that
may need to be edited, merged, archived, or split. These are proposals, not
declarations of truth. Both records and their evidence remain visible for review,
and the user decides what is correct.

### Analyze Using the Memory Assistant Model

Helper text:

> **Use your selected Memory Assistant model to inspect your existing memories and recommend possible changes.**

Button:

> **Audit**

Show all live status directly beneath this section.

While active, show:

- an indeterminate spinner;
- **Auditing Memories**;
- once the frozen memory snapshot is divided into fixed audit batches, a
  determinate progress bar;
- **X%** beneath the bar.

Before the total number of batches is known, do not invent a percentage. The
percentage is completed audit batches divided by the fixed total in the frozen
snapshot. Do not advance it on a timer or estimate model-token progress.
Reaching 100% is not success until final parsing, validation, duplicate checking,
and Pending staging finish.

The audit uses the same durable foreground-service pattern as API Memory
Assistant. The user may leave the screen, continue chatting, or turn the screen
off without cancelling the audit. A durable run recovers after process
interruption. If the process is force-stopped or dies, the durable record
recovers the unfinished audit when the app starts again; the UI must not claim
that work continued while the process was dead.

Creating an audit package and importing an audit result are also durable
operations. The screen does not need to stay open, and verified progress and
completion remain visible when the user returns. A resumed or repeated import
must not create duplicate Pending proposals.

The Android foreground-service notification uses:

- Title: **Auditing Memories**
- Indeterminate progress before the total is known.
- A determinate progress bar with **X% complete** once the total is known.
- Tapping the notification opens **Memory Auditor**.
- No separate completion notification.

### Audit Using AI on Computer

Helper text:

> **Export your existing memories with instructions for a file-capable AI. The AI will create a JSON result file for you to import below.**

Button:

> **Export Memories for Audit**

Show export status directly beneath this section.

The exported `.sgmemory` package contains the existing memories and relevant
read-only audit reference material, not conversations for new-memory discovery.
It includes:

- `README.md`;
- `instructions/agent_workflow.md`;
- `instructions/safety_and_scope.md`;
- the result schema and proposals template.

After export, show:

> **Give this package to a file-capable AI and ask it to open the package, read README.md, follow instructions/agent_workflow.md, and create proposals.json.**

### Import Memory Audit Results

Helper text:

> **Import the JSON result file created by the external AI. Suggested changes will be added to Pending for review.**

Button:

> **Import**

After the user selects a result file, disable the button and show the spinner
and active button text:

> **Importing Memories…**

Show all status and errors directly beneath this section.

Use the most specific plain-language import error available:

- **Wrong file type. Select the JSON result file created by the external AI.**
- **This file does not contain any recognizable memories.**
- **This result file could not be read. It may be incomplete or damaged.**
- **This result file does not match a review package created by this app.**
- **This result file uses a version this app cannot import.**
- **This result file is too large to import.**
- **This result file has already been imported.**

Errors remain visible until the user chooses another file or retries. Do not
rely only on a toast, snackbar, dialog, or log. Never expose raw JSON, enum
names, stack traces, or exception text in this area.

If a structurally valid result contains both valid and invalid proposals,
import the valid proposals rather than failing the entire file. Show how many
suggestions could not be imported and provide access to details.

When import and validation finish successfully, show:

> **Potential Memories Found: N**

Show **View**, which opens **Memories → Pending**.

The result file contains proposals only. The phone validates and organizes them
into Pending cards for the user to review, correct, accept, or reject. Nothing
is applied automatically.

### Wording still awaiting owner approval

The exact success, no-findings, export-completion, interruption, and failure
messages for the audit sections and for review-package creation have **not**
been approved. Suggestions were drafted in chat but the owner has not ruled on
them. Do not write, implement, or treat any terminal-state wording as decided
without the owner's approval. The structure and approved text above are
sufficient to build everything else.

### Existing-memory housekeeping boundary and package safety

The existing-memory review is database housekeeping, not a second review of
current conversations. It exports the existing associative-memory catalog for
an external AI to inspect. Lorebooks and roleplay cards may be included as
read-only comparison material so overlap can be detected, but this job does not
edit them.

This housekeeping package does not include new, current, or unprocessed
conversations for memory discovery. It may include evidence or provenance
already attached to existing memories, but it does not scan chats for additional
memories.

The initial computer workflow does not require a dedicated desktop application
or custom desktop UI. The user opens the exported package in an existing
file-capable AI application on the computer and asks it to follow the package's
included instructions. The AI writes a structured suggestions file for the
phone to import.

The package, not a visual desktop card editor, preserves placement:

- existing memories and other records carry stable IDs, current revisions,
  scopes, targets, and evidence;
- an edit, merge, or archive suggestion names the exact existing record or
  records it concerns;
- the phone validates every ID, placement, and piece of evidence against its
  current state before anything reaches Pending;
- invalid placement is rejected or returned for correction, and the computer
  never writes directly into the memory store;
- every usable result still requires user review and approval in Pending.

This file-based computer workflow is separate from any tentative future web or
desktop client that mirrors the phone app. A custom client may be considered
later, but it is not required for the first working computer-review route.

## What's left — in any order, or never

- **Self-repairing search** — background housekeeping so search stays
  fast and fixes its own index. Needs nothing from you.
- **The duplicate screen** — when a new memory looks like one you have,
  a side-by-side where you pick keep / replace / delete.
- **Faster Lorebooks** — speed and better logs. Needs nothing from you.
- **The computer feature** — export eligible chats as a review package, let an
  AI on a computer suggest new Associative Memories or Lorebook Memories, and
  import the result into the appropriate Pending area.
- **Memory Auditor** — audit the existing associative-memory catalog using the
  Memory Assistant model or a file-capable AI on a computer. Every result goes
  to **Memories → Pending** for review.
- **Memory Budget Calculator** — implement the approved calculator and shared
  card-editor behavior described above.

## How to start anything

One line in chat, in your words: *"do the duplicate screen"*, *"do the
computer feature"*, *"fix the name"*, *"make this screen less ugly."*
The agent maps it to the machinery. You read nothing, and you are not
asked questionnaires — where a screen genuinely needs your words, the
agent builds everything else first and asks one thing, once.
