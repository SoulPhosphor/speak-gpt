# Computer Memory Review — Binding Owner Rulings

These rulings supersede any conflicting provisional computer-workflow wording in older planning documents.

## Existing-memory audit boundary

The existing-memory audit is database housekeeping. It reviews the existing associative-memory catalog itself. It does not analyze current, new, unprocessed, or archived conversations for additional memories.

Lorebooks and roleplay cards may be included only as read-only comparison material so the external AI can notice overlap. The audit does not edit them.

The external AI may flag possible duplicates, contradictions, outdated information, unclear wording, weak placement, missing evidence, or memories that may need to be edited, merged, archived, or split. These are proposals, not declarations of truth.

The AI may use timestamps, status or event wording, evidence, and conflicts between records to flag that one memory may supersede another. It must preserve both records and their evidence for review. It must not silently decide which memory is correct. For example, a memory saying someone is single and another saying they are dating may deserve review, but the user decides whether either memory should be changed, merged, archived, or left alone.

## Import section

The Computer Memory Review screen places Export at the top and Import directly below it.

Title:

**Import**

Helper text:

**Upload the result file created by the external AI to add its suggestions to Pending.**

Idle button:

**Import**

While processing, disable the button and change its label to:

**Importing Memories…**

Directly beneath the button, reuse the API Memory Assistant inline progress pattern with an indeterminate spinner and the same status text:

**Importing Memories…**

Do not say **Importing Conversations**. Conversations remain in the exported review package and are not imported back into the app.

## Inline import errors

The area directly beneath the Import button is also the required error surface. Errors remain visible there until the user chooses another file or retries. Do not rely only on a toast, snackbar, dialog, or log.

Use the most specific plain-language message available:

- Wrong extension or file type: **Wrong file type. Select the JSON result file created by the external AI.**
- JSON that is not a recognizable computer-memory result: **This file does not contain any recognizable memories.**
- Malformed, incomplete, or damaged JSON: **This result file could not be read. It may be incomplete or damaged.**
- Result that does not match a review package created by this app or workspace: **This result file does not match a review package created by this app.**
- Unsupported result-format version: **This result file uses a version this app cannot import.**
- Result exceeding safe import limits: **This result file is too large to import.**
- Result already imported: **This result file has already been imported.**

Never expose raw JSON, enum names, stack traces, or exception text in this area. Those details belong only in local logs.

If a structurally valid result contains both valid and invalid proposals, import the valid proposals instead of failing the entire file. Show the normal success count plus an inline secondary warning stating how many suggestions could not be imported, with access to details.

## Successful import

When import and validation finish, remove the spinner and show:

**Potential Memories Found: N**

Show a **View** button. Route it according to the result type:

- Associative-memory results: **Memories → Pending**
- Lorebook-memory results: **Lorebooks → Pending**
- Existing-memory audit results: **Memories → Pending**

Nothing is approved automatically. The phone validates the file, identifiers, placement, evidence, duplicates, and current phone state before creating Pending items.

## Memory Auditor entry and screen structure

Add a separate row to the Memory Manager titled **Memory Auditor**. This row owns existing-memory database housekeeping. It is separate from **API Memory Assistant**, which finds memories in conversations, and **Computer Memory Review**, which exports conversations for an external AI to review.

The exact Memory Auditor row subtitle remains owner-approval wording.

The Memory Auditor screen contains these three sections in this order:

1. **Analyze Using the Memory Assistant Model**
   - Button: **Audit**
   - Show live status directly beneath this section.
2. **Audit Using AI on Computer**
   - Button: **Export Memories for Audit**
   - The exported `.sgmemory` package includes `README.md`, `instructions/agent_workflow.md`, `instructions/safety_and_scope.md`, and a proposals template so the external AI knows what to inspect and how to write the result.
3. **Import Memory Audit Results**
   - Button: **Import**
   - Show live status and all import errors directly beneath this section.

Both audit routes inspect the existing associative-memory catalog, not conversations. Their findings are staged in **Memories → Pending** as reviewable proposals. Nothing is applied automatically.

## Background and interruption behavior

The **Analyze Using the Memory Assistant Model** audit must use the same durable foreground-service pattern as API Memory Assistant analysis. The user may leave the screen, continue chatting, or turn the screen off without cancelling the audit. A process restart must recover the durable run rather than silently losing it or pretending it completed.

Creating an audit package and importing an audit result must also be durable operations. The screen is not required to stay open. Verified progress and completion remain visible when the user returns. Import staging is resumable and idempotent: validated but uncommitted items resume after interruption, while replayed committed proposals do not create duplicates.

Directly after the Memory Auditor screen introduction and before the first audit section, show this approved foreground-work notice:

**You may continue using the app and move between screens while the audit runs. It will continue working in the foreground as long as the app remains open.**

The notice describes normal navigation and screen-off behavior. Force-stopping the app ends the live service. If the process ends unexpectedly, the durable run record must recover the unfinished audit when the app starts again; the UI must not claim that work continued while the process was dead.

## Active audit status and progress

While the Memory Assistant model audit is running, show an indeterminate spinner with this exact status text directly beneath the **Audit** button:

**Auditing Memories**

Once the auditor has frozen the finite memory snapshot and divided it into fixed audit batches, show a determinate progress bar beneath the spinner and status. Show the completed percentage beneath the bar as:

**X%**

The percentage must be calculated from completed audit batches divided by the fixed total number of audit batches. It must not estimate model-token progress, advance on a timer, or count a batch as complete before its result has been received and durably recorded. Before the total batch count is known, show only the spinner and **Auditing Memories**.

Reaching 100% means every audit batch has completed, but the success result must not replace the running state until final parsing, validation, deduplication, and Pending staging have also finished successfully.

## Durable foreground-service notification wording

The Memory Auditor model audit uses a silent, ongoing, low-priority foreground-service notification while the audit is active.

Notification title:

**Auditing Memories**

When determinate progress is available, the notification mirrors the same progress bar and shows:

**X% complete**

Before determinate progress is available, the notification uses an indeterminate progress state and no invented percentage. Tapping the notification opens the **Memory Auditor** screen. The notification ends when the run reaches a terminal success, empty-result, failure, or interruption state; do not create a separate completion notification.

Exact success, empty-result, and failure wording for the new Memory Auditor sections remains owner-approval material.
