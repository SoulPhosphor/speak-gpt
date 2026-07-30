# Computer Memory Review and Memory Auditor — Binding Owner Rulings

These rulings supersede any conflicting provisional computer-workflow wording in older planning documents.

## Three separate Memory Manager rows

The Memory Manager contains three separate routes:

1. **API Memory Assistant** — analyzes eligible conversations using the selected Memory Assistant model.
2. **Computer Memory Review** — exports eligible conversations for a file-capable AI on a computer to analyze.
3. **Memory Auditor** — examines the existing associative-memory catalog for housekeeping recommendations. It does not analyze conversations.

Do not merge these routes or move the Memory Auditor into Computer Memory Review.

## Memory Auditor row and introduction

Row title:

**Memory Auditor**

Proposed row subtitle:

**Review existing memories for possible duplicates, conflicts, outdated information, and other cleanup suggestions.**

Approved screen introduction:

**Audit your existing associative memories for possible duplicates, conflicts, outdated information, unclear wording, or items that may need to be edited, merged, archived, or split. All findings are sent to Pending for review.**

Directly beneath the introduction, show this approved foreground-work notice:

**You may continue using the app and move between screens while the audit runs. It will continue working in the foreground as long as the app remains open.**

Do not add a Memory Analysis Type picker to Memory Auditor. Both audit routes inspect associative memories only.

## Memory Auditor screen structure

The screen contains these three sections in this order.

### Analyze Using the Memory Assistant Model

Helper text:

**Use your selected Memory Assistant model to inspect your existing memories and recommend possible changes.**

Button:

**Audit**

Show live status directly beneath this section.

### Audit Using AI on Computer

Helper text:

**Export your existing memories with instructions for a file-capable AI. The AI will create a JSON result file for you to import below.**

Button:

**Export Memories for Audit**

The exported `.sgmemory` package contains the existing associative-memory catalog, relevant evidence and history, read-only Lorebook and roleplay-card comparison material where applicable, and all instructions needed by the external AI. It does not contain conversations for new-memory discovery.

The package includes:

- `README.md`
- `instructions/agent_workflow.md`
- `instructions/safety_and_scope.md`
- the result schema and a proposals template

After export, show this instruction:

**Give this package to a file-capable AI and ask it to open the package, read README.md, follow instructions/agent_workflow.md, and create proposals.json.**

Show export progress, completion, and errors directly beneath this section.

### Import Memory Audit Results

Helper text:

**Import the JSON result file created by the external AI. Suggested changes will be added to Pending for review.**

Idle button:

**Import**

While processing, disable the button and change its label to:

**Importing Memories…**

Show live status and all import errors directly beneath this section.

## Existing-memory audit boundary

The audit is database housekeeping. It reviews the existing associative-memory catalog itself. It does not analyze current, new, unprocessed, eligible, or archived conversations for additional memories.

Lorebooks and roleplay cards may be included only as read-only comparison material so the AI can notice overlap. The audit does not edit them.

The AI may flag possible duplicates, contradictions, outdated information, unclear wording, weak placement, missing evidence, or memories that may need to be edited, merged, archived, or split. These are proposals, not declarations of truth.

The AI may use timestamps, status or event wording, evidence, and conflicts between records to flag that one memory may supersede another. It must preserve both records and their evidence for review. It must not silently decide which memory is correct. For example, a memory saying someone is single and another saying they are dating may deserve review, but the user decides whether either memory should be changed, merged, archived, or left alone.

Both audit routes send their findings to **Memories → Pending** as reviewable proposals. Nothing is applied automatically.

## Memory Auditor background and progress behavior

The **Analyze Using the Memory Assistant Model** audit uses the same durable foreground-service pattern as API Memory Assistant analysis. The user may leave the screen, continue chatting, or turn the screen off without cancelling the audit.

A process restart must recover the durable unfinished run rather than silently losing it or pretending it completed. Force-stopping the app ends the live service; the UI must not claim that work continued while the process was dead.

Creating an audit package and importing an audit result are also durable operations. The screen is not required to stay open. Verified progress and completion remain visible when the user returns. Import staging is resumable and idempotent: validated but uncommitted items resume after interruption, while replayed committed proposals do not create duplicates.

While the model audit is running, show:

1. An indeterminate spinner.
2. Exact status text: **Auditing Memories**
3. Once a fixed audit-batch total is known, a determinate progress bar.
4. Exact percentage text beneath the bar: **X%**

Keep the spinner visible while the audit is actively working. Before the total batch count is known, show only the spinner and **Auditing Memories**.

The percentage is completed audit batches divided by the fixed total number of batches in the frozen memory snapshot. It must not estimate model-token progress, advance on a timer, or count a batch before its result has been received and durably recorded.

Reaching 100% does not replace the running state with success until final parsing, validation, duplicate checking, and Pending staging have completed.

Foreground-service notification:

- Title: **Auditing Memories**
- Before the total is known: indeterminate progress
- After the total is known: determinate progress bar with **X% complete**
- Tapping the notification opens **Memory Auditor**
- No separate completion notification

Exact success, empty-result, export-completion, and failure wording remains available for a later visual-polish pass. The structure and approved text above are sufficient for implementation.

## Inline audit-result import errors

The area directly beneath the Import button is the required error surface. Errors remain visible until the user chooses another file or retries. Do not rely only on a toast, snackbar, dialog, or log.

Use the most specific plain-language message available:

- Wrong extension or file type: **Wrong file type. Select the JSON result file created by the external AI.**
- JSON that is not a recognizable computer-memory result: **This file does not contain any recognizable memories.**
- Malformed, incomplete, or damaged JSON: **This result file could not be read. It may be incomplete or damaged.**
- Result that does not match a package created by the app: **This result file does not match a review package created by this app.**
- Unsupported result-format version: **This result file uses a version this app cannot import.**
- Result exceeding safe import limits: **This result file is too large to import.**
- Result already imported: **This result file has already been imported.**

Never expose raw JSON, enum names, stack traces, or exception text in this area. Those details belong only in local logs.

If a structurally valid result contains both valid and invalid proposals, import the valid proposals instead of failing the entire file. Show the normal success count plus an inline secondary warning stating how many suggestions could not be imported, with access to details.

Successful import:

**Potential Memories Found: N**

Show a **View** button that opens **Memories → Pending**. Nothing is approved automatically. The phone validates the file, identifiers, placement, evidence, duplicates, and current phone state before creating Pending items.

## Computer Memory Review is conversation analysis only

Computer Memory Review exports conversations for new-memory discovery. It does not perform existing-memory housekeeping. Memory Auditor owns all existing-memory audit work.

The Computer Memory Review export uses the exact same two-column **Memory Analysis Type** row already approved for API Memory Assistant:

- Left column title: **Memory Analysis Type**
- Left column subtitle, shown as three paragraphs with visible spacing:

  **Choose which memory system this analysis should create suggestions for.**

  **Associative Memories use an embedding model to surface memories connected to the ideas and topics being discussed.**

  **Lorebook Memories are activated by specific keywords and do not require an embedding model.**

- Right column: top-aligned dropdown with exactly **Associative Memories** and **Lorebook Memories**
- **Associative Memories** is the default
- No **Both** option

The selected type determines what the exported conversation package asks the computer AI to produce and which Pending area the imported result opens.

Do not redesign the already-decided conversation export flow, add a new task-card choice, or make the user choose between chat review and database audit on this screen. The audit is a separate Memory Auditor row.

## Computer Memory Review import

The Computer Memory Review screen places its already-decided export function at the top and Import directly below it.

Import title:

**Import**

Helper text:

**Upload the result file created by the external AI to add its suggestions to Pending.**

Idle button:

**Import**

While processing, disable the button and show:

**Importing Memories…**

Do not say **Importing Conversations**. Conversations remain in the exported review package and are not imported back into the app.

Successful import:

**Potential Memories Found: N**

Show **View** and route according to the selected analysis type:

- Associative-memory results: **Memories → Pending**
- Lorebook-memory results: **Lorebooks → Pending**

All applicable inline import-error behavior above also applies to Computer Memory Review imports.

## API Memory Assistant conversation-analysis progress

Replace the Android-facing conversation-count wording, **Memory Assistant is reviewing X of Y conversations**, with the same progress pattern used by Memory Auditor. Do not show the raw number of conversations as the primary progress display.

While conversation analysis is active, show:

1. An indeterminate spinner.
2. Exact status text: **Analyzing Conversations**
3. Once a fixed analysis-batch total is known, a determinate progress bar.
4. Exact percentage text beneath the bar: **X%**

Keep the spinner visible while analysis is actively working. Before the total number of analysis batches is known, show the spinner and **Analyzing Conversations** without an invented percentage.

The percentage is completed sealed analysis batches divided by the fixed total number of batches claimed for that run. Conversations or messages added after the run begins remain outside that frozen denominator and wait for a later analysis.

API Memory Assistant foreground-service notification:

- Title: **Analyzing Conversations**
- Before the total is known: indeterminate progress
- After the total is known: determinate progress bar with **X% complete**
- Tapping the notification opens API Memory Assistant

Remove the notification sentence that reports **X of Y conversations**. Reaching 100% does not replace the running state with success until every batch result has been durably recorded and final parsing, validation, duplicate checking, and Pending staging have completed.