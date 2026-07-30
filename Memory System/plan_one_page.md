# The Memory Plan — the only page the owner needs

The long counterplan files are agent machinery. The owner does not need to read them. This page records the approved product decisions and user-facing wording.

*(Status recorded 2026-07-30. Agents: keep this page true whenever approved decisions change.)*

## What already works on main

- Chats are recorded for review while **Archive this chat** is on.
- The current Memory Assistant analyzes eligible conversations and sends suggestions to **Pending**. Nothing is saved, changed, or deleted without approval.
- Analysis continues when the user leaves the screen or turns the screen off, and durable runs recover after interruption.
- A deleted suggestion stays deleted even if the chat is renamed.
- Search finds approved memories by meaning when the embedding model is installed and by keywords when it is not.
- The **Memory Engine** picker controls which memory systems feed chats.

## Binding corrections throughout the app

- **Lorebook** is always one word in user-facing text, including **Lorebooks**, **Lorebook Memories**, and **Lorebook Suggestions**.
- Turning **Archive this chat** off pauses archiving without resetting, advancing, erasing, or replacing its truthful bookmark.
- Turning it back on silently processes every eligible message not already fully processed. Never show an **Include Earlier Messages?** prompt.
- Each time Memory Browser opens without an embedding model, show the dismissible reminder:

  **Associative Search can't be used in chats until an embedding model is installed.**

  Action: **Okay**

- Do not use a permanent missing-model banner.

## Memory Analysis Type

API Memory Assistant and Computer Memory Review use the same existing two-column settings-row pattern.

Left-column title:

**Memory Analysis Type**

Left-column subtitle, shown as three paragraphs with visible spacing:

**Choose which memory system this analysis should create suggestions for.**

**Associative Memories use an embedding model to surface memories connected to the ideas and topics being discussed.**

**Lorebook Memories are activated by specific keywords and do not require an embedding model.**

The top-aligned right column contains a dropdown with exactly:

- **Associative Memories**
- **Lorebook Memories**

**Associative Memories** is the default. There is no **Both** option. One run or exported review package creates one kind of suggestion.

Memory Auditor does not use this picker because it audits the existing associative-memory catalog only.

## Lorebook Suggestions

Lorebook Suggestions are reviewed in the Lorebooks area, not Memory Browser.

The ordinary Lorebooks screen has no split control. While one or more Lorebook Suggestions are pending, show a top split control matching Memory Browser:

**Lorebooks | Pending**

The split disappears when no Lorebook Suggestions remain.

Each suggestion shows:

- proposed entry text;
- trigger keywords;
- no separate title;
- dropdown: **Assign Lorebook**;
- edit, approve, and delete actions.

The destination dropdown chooses an existing Lorebook or creates a new one through the normal full-page flow. Nothing is written until the individual suggestion is approved. This flow never edits or deletes existing Lorebook entries.

When analysis finds Lorebook suggestions, use the existing result surface:

**Potential Lorebook Memories found: N**

Button: **View**

**View** opens **Lorebooks → Pending**.

## API Memory Assistant

Rename the existing Memory Manager row from **Memory Assistant** to:

**API Memory Assistant**

It analyzes eligible conversations using the selected Memory Assistant model.

While analysis is active, show:

1. spinner;
2. **Analyzing Conversations**;
3. determinate progress bar once a fixed batch total is known;
4. **X%** beneath the bar.

Before the total is known, use indeterminate progress and no invented percentage. Keep the spinner visible while active. The percentage is completed sealed batches divided by the fixed total claimed for the run. New messages wait for a later run and do not change the denominator.

Android foreground notification:

- title: **Analyzing Conversations**;
- indeterminate progress before the total is known;
- determinate bar with **X% complete** afterward;
- tapping opens API Memory Assistant.

Remove the old **X of Y conversations** wording. Reaching 100% is not success until parsing, validation, duplicate checking, and Pending staging finish.

## Computer Memory Review

Add a Memory Manager row directly beneath API Memory Assistant:

**Computer Memory Review**

Subtitle:

**Use an AI on your computer to review chats for new memories.**

Screen introduction, two paragraphs:

**Create a review package for an AI on your computer. It can review chats for new Associative Memories or Lorebook Memories.**

**Once complete, import the result file. Your memory manager will organize the suggestions into the correct Pending area so you can review and confirm them at your own pace.**

This screen is conversation analysis only. It does not audit the existing memory database.

The screen keeps the already-decided export function at the top and Import directly below it. Do not replace it with a two-task-card design and do not add another choice between chat review and database audit.

The export area uses the exact **Memory Analysis Type** picker above. The selected type determines what the package asks the external AI to create and which Pending area the result opens. Retain the already-decided eligible-chat selection and package-export behavior without redesigning it.

The exported `.sgmemory` package includes its own README, AI workflow instructions, safety and scope instructions, result schema, and proposals template. The user does not need to invent the AI instructions.

### Computer Memory Review import

Title:

**Import**

Helper text:

**Upload the result file created by the external AI to add its suggestions to Pending.**

Idle button:

**Import**

While processing, disable the button and show:

**Importing Memories…**

Do not say **Importing Conversations**. Conversations remain in the exported package and are not imported back into the app.

Successful import:

**Potential Memories Found: N**

Button: **View**

Routing:

- Associative results → **Memories → Pending**
- Lorebook results → **Lorebooks → Pending**

Nothing is approved automatically.

## Memory Auditor

Add a separate Memory Manager row:

**Memory Auditor**

Subtitle:

**Review existing memories for possible duplicates, conflicts, outdated information, and other cleanup suggestions.**

Screen introduction:

**Audit your existing associative memories for possible duplicates, conflicts, outdated information, unclear wording, or items that may need to be edited, merged, archived, or split. All findings are sent to Pending for review.**

Directly beneath it:

**You may continue using the app and move between screens while the audit runs. It will continue working in the foreground as long as the app remains open.**

The audit examines the existing associative-memory catalog itself. It does not analyze current, new, eligible, unprocessed, or archived conversations for new memories.

The external AI or Memory Assistant model may flag possible duplicates, contradictions, records that appear superseded, unclear wording, weak placement, missing evidence, or memories that may need to be edited, merged, archived, or split. These are proposals, not declarations of truth. The user decides what is correct.

### Analyze Using the Memory Assistant Model

Helper text:

**Use your selected Memory Assistant model to inspect your existing memories and recommend possible changes.**

Button:

**Audit**

Show all live status directly beneath this section.

While active:

- spinner;
- **Auditing Memories**;
- determinate progress bar once the finite memory snapshot is divided into fixed batches;
- **X%** beneath the bar.

Foreground notification:

- title: **Auditing Memories**;
- indeterminate progress before total batch count is known;
- determinate bar with **X% complete** afterward;
- tapping opens Memory Auditor.

The audit uses the same durable foreground-service pattern as API Memory Assistant. The user may move between screens, continue chatting, or turn the screen off. A durable run recovers after process interruption.

### Audit Using AI on Computer

Helper text:

**Export your existing memories with instructions for a file-capable AI. The AI will create a JSON result file for you to import below.**

Button:

**Export Memories for Audit**

This package contains memories and relevant audit reference material, not conversations for new-memory discovery.

The package includes:

- `README.md`;
- `instructions/agent_workflow.md`;
- `instructions/safety_and_scope.md`;
- result schema and proposals template.

After export, show:

**Give this package to a file-capable AI and ask it to open the package, read README.md, follow instructions/agent_workflow.md, and create proposals.json.**

Show export status directly beneath this section.

### Import Memory Audit Results

Helper text:

**Import the JSON result file created by the external AI. Suggested changes will be added to Pending for review.**

Idle button:

**Import**

Active button and spinner text:

**Importing Memories…**

Show status and all errors directly beneath this section. Successful results go to **Memories → Pending**.

### Inline result-file errors

Use the most specific message available beneath the Import button:

- **Wrong file type. Select the JSON result file created by the external AI.**
- **This file does not contain any recognizable memories.**
- **This result file could not be read. It may be incomplete or damaged.**
- **This result file does not match a review package created by this app.**
- **This result file uses a version this app cannot import.**
- **This result file is too large to import.**
- **This result file has already been imported.**

Errors remain visible until another file is chosen or the user retries. Do not rely only on a toast, snackbar, dialog, or log. Never expose raw JSON, enum names, stack traces, or exception text.

Successful import:

**Potential Memories Found: N**

Button: **View** → **Memories → Pending**

Nothing is applied automatically.

## Roleplay Memory Budget Calculator

Add a row at the bottom of the Roleplay screen:

**Memory Budget Calculator**

Introduction:

**Estimate the token footprint of static memories included in every prompt. Select active Lorebooks, worlds, or characters below to preview their text and calculate their combined impact on your context window.**

Live title-size total:

**Total Estimated Tokens: X**

The total includes current on-screen selections and unsaved edits. Selector changes, Revert, and Save update immediately. Text edits update after a 300 ms event-driven debounce. Do not poll or recalculate on every keystroke.

Sections, in order:

1. **Lorebooks**
2. **World**
3. **Campaign**
4. **Roleplay Character**
5. **Party Members**
6. **Glamour**

Every selector starts at **None** and retains the app's existing single-select or multi-select behavior.

Count only static text included every turn. For Lorebooks, count always-active or core text only; exclude keyword-triggered entries.

Selected items show editable data matching their real card editor, with a live section token count and **Revert** and **Save**.

When leaving or replacing a selection with unsaved edits:

**Discard all changes?**

**The following sections have unsaved changes:**

List each affected section on its own row.

Actions:

- **Save All**
- **Discard All**
- **Continue Editing**

The calculator must reuse card-editor components or shared field and text styles rather than maintaining a copied layout.

## Owner wording status

The approved wording is recorded above. Do not add the unapproved terminal-state wording proposed later in chat. Do not reopen these decisions or ask the owner to repeat them.

## Remaining engineering work

- implement the Memory Analysis Type and Lorebook suggestion flow;
- implement the Memory Budget Calculator;
- implement Computer Memory Review package export and result import;
- implement Memory Auditor model and computer routes;
- implement Pending cards for edits, merges, archive recommendations, splits, and conflicts;
- implement and test durable progress, restart recovery, malformed files, duplicate prevention, and stale-record conflicts;
- reconcile the long counterplan against this page before implementation.

No app code has been changed by this planning update.
