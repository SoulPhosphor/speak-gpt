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