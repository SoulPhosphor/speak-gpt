# Roadmap

*Recorded 2026-08-03. This is the only active planning document in this
repository.*

The structure of truth is fixed:

- **Current code on `main`** is the truth about what already exists.
- **This roadmap** is the truth about what remains and the order it will be
  built.
- **Narrowly relevant technical specifications** (in `Memory System/`) are
  implementation reference only.

No other document may call itself the roadmap, canonical plan, single
source of truth, current phase list, or execution plan. Superseded
roadmaps, work orders, and design documents for the same features this
roadmap now covers are archived in **`legacy/`** at the repository root —
consult that folder only if something built needs troubleshooting that
requires the original design reasoning; never as a plan.

## Ground rules

**Built features are closed.** Everything already built stays as it is —
including the Memory Browser, the Pending systems, Possible Match review,
Lorebook Pending, search repair, analysis progress behavior, and the faster
Lorebook work. No agent reopens, redesigns, inventories, audits, or
"reconciles" them, and no agent asks the owner to rule on them again. The
owner's own use of the app is the audit. If the owner finds a problem, the
owner will report that specific problem; it is fixed then, narrowly.

**One feature at a time.** Exactly one feature from the list below may be
active. Future features are locked: no work on them, no preparation for
them, no refactoring for them, until the active feature is fully working
end to end, tested, merged into `main`, and confirmed by the owner.

**A feature ships whole.** A feature is complete only when its entire
user-facing workflow works from beginning to end. Never deliver: empty
rows, placeholder screens, controls that lead to unfinished workflows,
export without working import, import without working Pending handling,
shared foundations shipped alone and called completed, or preparation for
later features. The lettered internal steps inside each feature are build
order on that feature's branch — they are not independently shipped
roadmap stages and are not reported as separate accomplishments. The
feature branch merges to `main` only when the whole workflow works.

**Internal refactors** are allowed only when the active feature requires
them. They are part of that feature's work, not separate accomplishments.

**Wording.** All approved user-facing wording for the remaining features is
inlined below, verbatim. Where this document says wording is **not
approved**, that is a stop point: the agent asks the owner in chat before
writing that text into any file, in any form. Nothing user-facing beyond
what is written here is invented.

**Models.** The assignments below are the owner's and are not downgraded
without asking:

- Existing Memory Assistant Corrections — archive behavior: **High, Fable**;
  naming and wording corrections: **Low, Sonnet**
- Memory Budget Calculator: **High, Fable**
- Computer Memory Review: **Hardest, Fable**
- Memory Auditor: **Hardest, Fable**
- Final verification of each completed feature: **Opus**

## Feature order — fixed

1. **Existing Memory Assistant Corrections**
2. **Memory Budget Calculator**
3. **Computer Memory Review**
4. **Memory Auditor**

Why this order is technically necessary: the archive bookmark rules in
Feature 1 define which messages are *eligible* for review, and Computer
Memory Review freezes eligible messages into its export package — building
the package on unfinished archive semantics would bake wrong eligibility
into every exported package; the rename must also land before later
screens reference the name "API Memory Assistant." The Memory Budget
Calculator shares no machinery with the file workflow, so it sits before
the two file features rather than between them, keeping them adjacent.
Memory Auditor is last because its computer route reuses the package
format, import validation, durable import ledger, and Pending staging that
Computer Memory Review builds; building the Auditor first would mean
building that machinery inside the wrong feature.

---

## Feature 1 — Existing Memory Assistant Corrections

Three narrow, known corrections to already-shipped behavior. Nothing else
about the Memory Assistant is touched.

### Internal steps

**A. Finish Archive This Chat behavior** *(High, Fable)* — the approved
semantics, already ruled:

- Turning **Archive This Chat** off pauses archiving without erasing,
  resetting, advancing, or replacing the last truthful archive bookmark.
- Turning it back on silently processes every eligible message not already
  fully processed.
- Never show an **Include Earlier Messages?** prompt or any equivalent
  choice.

The standing law that storage and injection are independent
(`Memory System/owner_approved_rules.md`) governs: whether memories enter
a chat and whether a chat may be archived are separate controls.

**B. Rename the row** *(Low, Sonnet)* — the Memory Manager row **Memory
Assistant** becomes **API Memory Assistant**. Row title only; the screen
behind it is unchanged. (The approved end state of the Memory Manager list
is three rows — API Memory Assistant, Computer Memory Review directly
beneath it, and a separate Memory Auditor row — but the two new rows land
inside Features 3 and 4 respectively, because a row never appears before
its workflow works.)

**C. Lorebook one-word cleanup** *(Low, Sonnet)* — finish the ruled sweep:
**Lorebook** is always one word in user-facing text throughout the app,
including plurals and compound labels: **Lorebook**, **Lorebooks**,
**Lorebook Memories**, **Lorebook Suggestions**. Never display **lore
book** or **lore books** as two words. Code identifiers and internal logs
are not user-facing and are left alone.

**D. Final verification** *(Opus)* — on device: toggle Archive off, chat,
toggle it on, run analysis, and confirm exactly the unprocessed messages
are reviewed with nothing lost, nothing double-processed, and no prompt;
confirm the renamed row; confirm no two-word "lore book" remains anywhere
user-facing.

### Complete when

All three corrections work as ruled, CI is green, the branch is merged to
`main`, and the owner has confirmed on the device.

---

## Feature 2 — Memory Budget Calculator

*(High, Fable; final verification Opus.)* Fully specified by owner
rulings; no design decisions remain. The full approved specification:

Remove only the proposed active-scene word or token feature from Chat →
Quick Settings. Quick Settings itself remains unchanged. Do not add an
**Always-active scene** total or memory-budget notice there. The dedicated
calculator below is the only approved location for this feature.

Add a row at the bottom of the **Roleplay** screen:

- Title: **Memory Budget Calculator**
- Tapping the row opens a dedicated calculator and editor screen.

Screen introduction, verbatim:

> Estimate the token footprint of static memories included in every prompt.
> Select active Lorebooks, worlds, or characters below to preview their text
> and calculate their combined impact on your context window.

Directly beneath the introduction and before the selectable sections, show
a live total using the app's title-size text style:

> **Total Estimated Tokens: X**

The total reflects the current on-screen selections and text, including
edits that have not been saved yet. Saving is not required for the preview
total to change. Dropdown selection changes update it immediately. Text
edits trigger an event-driven recalculation after a 300 ms debounce from
the most recent edit; do not continuously poll or recalculate on every
keystroke. Revert and Save also update the total immediately.

The selectable sections appear vertically, one beneath another. Every
selector starts at **None** and lists the existing named items currently
available in the app:

1. **Lorebooks**
2. **World**
3. **Campaign**
4. **Roleplay Character**
5. **Party Members**
6. **Glamour** — place this selector last.

Use the app's existing selection behavior for each type. Types that
currently allow one active item remain single-select; types that currently
allow several active items retain their existing multi-selection behavior.

Only static text that would be included every turn is counted. For
Lorebooks, count only always-active or core text. Keyword-triggered entries
are excluded from the static total because they are not present in every
prompt.

When an item is selected, immediately show its editable data using the same
field order, section layout, labels, text styling, spacing, line height,
validation, and behavior as its actual card editor. Example section header:

> **World: Sparktown**    **500 Tokens**

Each selected section shows its own live approximate token count. The
per-section counts and the combined total use the app's shared
token-estimation utility rather than introducing a separate counting
formula.

Each selected section has:

- **Revert** — discard unsaved edits in that section and restore the last
  saved card data.
- **Save** — save that section's changes to the underlying card so the
  normal card screen and all other uses immediately reflect them.

If the user tries to leave the calculator or replace a selection while any
section contains unsaved edits, show a dedicated confirmation box:

> **Discard all changes?**
>
> The following sections have unsaved changes:
>
> [Each affected section appears on its own row.]

Actions:

1. **Save All** — save every listed section, then continue the action the
   user attempted.
2. **Discard All** — discard every listed section's unsaved edits, then
   continue the action the user attempted.
3. **Continue Editing** — close the box, cancel the attempted navigation or
   selection change, and return to the calculator exactly as it was.
   Nothing is saved, discarded, or otherwise changed.

The calculator must not contain a copied second version of the card layout
or hard-coded duplicate line-height values. Reuse the same card-editor
component where practical, or the same shared field and text styles where a
shared component is not possible. A later card-layout or line-height change
must update both the normal card and calculator without separate
maintenance.

### Internal steps

**A.** Make the existing card editors hostable as an embedded component
with no visible change to their normal screens (required by the
no-duplicate-layout rule above; this refactor is part of this feature).

**B.** The Roleplay row and the calculator screen: introduction, live
total, the six selectors in the ruled order.

**C.** Per-section embedded editors with live token counts, Revert and
Save wired to the real cards.

**D.** The unsaved-changes confirmation and navigation guard.

**E. Final verification** *(Opus)* — on device: select items, watch totals
update per the debounce rules, edit and save a card in the calculator, and
confirm the change on the normal card screen; confirm the discard dialog's
three actions behave exactly as ruled.

### Complete when

The whole calculator workflow works as specified, CI is green, merged to
`main`, owner-confirmed on device.

---

## Feature 3 — Computer Memory Review

*(Hardest, Fable; final verification Opus.)* Lets the owner use an AI on
their computer — the subscription already paid for — instead of API
tokens: export a review package, have the computer AI propose memories,
import the results into Pending. The feature is complete only when the
entire export → computer review → import → Pending path works.

### Approved requirements (verbatim rulings)

The Memory Manager gains the row **Computer Memory Review**, directly
beneath **API Memory Assistant** (the row lands with this feature, never
before the workflow works). Subtitle:

> **Use an AI on your computer to review chats.**

Introduction at the top of the screen, shown as two paragraphs with
visible spacing between them:

> Create a review package for an AI on your computer. It can review chats
> for new memories.
>
> Once complete, import the result file. Your memory manager will organize
> all flagged suggestions into their respective slots so you can review and
> confirm them at your own pace.

The screen order:

1. The existing export function at the top.
2. The import function directly below it.

Do not redesign the existing conversation export flow. Retain the
already-decided eligible-chat selection and package-export behavior.

The export area uses the approved two-column **Memory Analysis Type**
picker, matching the **Memory Engine** row's two-column settings-row
pattern rather than creating a new layout:

- Left column title: **Memory Analysis Type**
- Left column subtitle, shown as three paragraphs with visible spacing
  between them:

  **Choose which memory system this analysis should create suggestions
  for.**

  **Associative Memories use an embedding model to surface memories
  connected to the ideas and topics being discussed.**

  **Lorebook Memories are activated by specific keywords and do not
  require an embedding model.**

- Right-hand column: a drop-down aligned at the top with exactly two
  choices: **Associative Memories** and **Lorebook Memories**.
- **Associative Memories** is the default.

There is no **Both** option: one run creates one kind of suggestion. The
selected type determines whether the exported review package asks the
computer AI to create **Associative Memories** or **Lorebook Memories**.

The exported `.sgmemory` package includes its own README, AI workflow
instructions, safety and scope instructions, result schema, and proposals
template. The user does not need to invent separate instructions for the
AI.

Import section — title:

> **Import**

Helper text:

> Upload the result file created by the external AI to add its suggestions
> to Pending.

Button:

> **Import**

After the user chooses a result file, reuse the same inline progress
pattern as the API Memory Assistant. Disable the button and show an
indeterminate spinner with:

> **Importing Memories…**

Do not say **Importing Conversations**. Conversations remain in the review
package and are not imported into the app.

When import and validation finish successfully, remove the spinner and
show:

> **Potential Memories Found: N**

Show a **View** button beside or directly beneath the result. **View**
opens the appropriate Pending area based on the result file's declared
analysis type:

- Associative-memory results open **Memories → Pending**.
- Lorebook-memory results open **Lorebooks → Pending**.

Every import error appears directly beneath the Import button in plain
language and remains visible until the user chooses another file or
retries. Use the most specific plain-language import error available:

- **Wrong file type. Select the JSON result file created by the external
  AI.**
- **This file does not contain any recognizable memories.**
- **This result file could not be read. It may be incomplete or damaged.**
- **This result file does not match a review package created by this
  app.**
- **This result file uses a version this app cannot import.**
- **This result file is too large to import.**
- **This result file has already been imported.**

Do not rely only on a toast, snackbar, dialog, or log. Never expose raw
JSON, enum names, stack traces, or exception text in this area. If a
structurally valid result contains both valid and invalid proposals,
import the valid proposals rather than failing the entire file; show how
many suggestions could not be imported and provide access to details.

The import may finish quickly, but the app must still validate the file,
IDs, placements, evidence, duplicates, and current phone state before
creating Pending items. The computer never writes directly into the memory
store, and nothing is approved automatically.

Creating a review package and importing a result are durable operations:
the screen does not need to stay open, verified progress and completion
remain visible when the user returns, and a resumed or repeated import
must not create duplicate Pending proposals.

**Wording NOT approved (stop point):** the exact success, no-findings,
export-completion, interruption, and failure messages for review-package
creation. Ask the owner before writing any terminal-state wording.

### Technical requirements (preserved from the retired planning documents)

- Exported conversation rows are claimed/frozen in one transaction; new
  turns go to new rows and are simply not in the package. One outstanding
  computer review package at a time; this does not block an API run over
  other, unclaimed rows.
- A packet ledger plus a per-conversation item ledger (item status:
  awaiting / committed / failed / stale) makes partial and repeated
  imports honest: replay is a true no-op and an interrupted import knows
  exactly what committed.
- Import funnels through the same complete filing boundary as the API
  route — duplicate check, rejected-draft check, target resolution and
  validation, provenance stamping — never through backup import. External
  results cannot bypass any check the API route applies.
- The result contract references targets by stable ID only; the importer
  validates IDs against the exported catalog and current state. Every
  proposed memory carries evidence (package item ID, frozen conversation
  row IDs, a short excerpt) verified at import.
- Package instructions treat every conversation and memory excerpt as
  untrusted data, never as instructions to the importing app.
- The package contains no API keys, no database file, and no embeddings.
  Export shows a plain privacy disclosure: the package is the user's
  conversations and memory list in readable plaintext, and what happens to
  it depends entirely on the tool that reads it.

### Internal steps

**A.** Define and generate the package.
**B.** Create the screen and controls.
**C.** Test the computer-AI instructions with a real file-capable AI.
**D.** Build validation and import.
**E.** Send valid results to Pending (both destinations).
**F.** Test the complete export-to-import path end to end.
**G. Final verification** *(Opus)* — a full real cycle on device:
export, computer review, import, review in Pending; every error message
verified reachable and correct.

### Complete when

The entire export → computer → import → Pending workflow works with a real
computer AI, CI is green, merged to `main`, owner-confirmed on device.

---

## Feature 4 — Memory Auditor

*(Hardest, Fable; final verification Opus.)* Housekeeping for existing
memories — never a second review of conversations. Complete only when both
audit routes work end to end.

### Approved requirements (verbatim rulings)

The Memory Manager gains a separate row titled **Memory Auditor** (the row
lands with this feature). Row subtitle:

> **Review existing memories for possible duplicates, conflicts, outdated
> information, and other cleanup suggestions.**

Screen introduction:

> Audit your existing associative memories for possible duplicates,
> conflicts, outdated information, unclear wording, or items that may need
> to be edited, merged, archived, or split. All findings are sent to
> Pending for review.

Directly beneath the introduction, show:

> **You may continue using the app and move between screens while the
> audit runs. It will continue working in the foreground as long as the
> app remains open.**

Both audit routes inspect the existing associative-memory catalog itself.
They do not analyze current, new, eligible, unprocessed, or archived
conversations for new-memory discovery. Lorebooks and roleplay cards may
be included only as read-only comparison material so overlap can be
detected; this job does not edit them.

Memory Auditor has no **Memory Analysis Type** picker. Both audit routes
work on associative memories only, so there is no type to choose.

The auditor may flag possible duplicates, contradictions, records that
appear superseded, unclear wording, weak placement, missing evidence, or
memories that may need to be edited, merged, archived, or split. These are
proposals, not declarations of truth. Both records and their evidence
remain visible for review, and the user decides what is correct.

**Route 1 — Analyze Using the Memory Assistant Model.** Helper text:

> **Use your selected Memory Assistant model to inspect your existing
> memories and recommend possible changes.**

Button:

> **Audit**

Show all live status directly beneath this section. While active, show:

- an indeterminate spinner;
- **Auditing Memories**;
- once the frozen memory snapshot is divided into fixed audit batches, a
  determinate progress bar;
- **X%** beneath the bar.

Before the total number of batches is known, do not invent a percentage.
The percentage is completed audit batches divided by the fixed total in
the frozen snapshot. Do not advance it on a timer or estimate model-token
progress. Reaching 100% is not success until final parsing, validation,
duplicate checking, and Pending staging finish.

The audit uses the same durable foreground-service pattern as API Memory
Assistant. The user may leave the screen, continue chatting, or turn the
screen off without cancelling the audit. A durable run recovers after
process interruption. If the process is force-stopped or dies, the durable
record recovers the unfinished audit when the app starts again; the UI
must not claim that work continued while the process was dead.

The Android foreground-service notification uses:

- Title: **Auditing Memories**
- Indeterminate progress before the total is known.
- A determinate progress bar with **X% complete** once the total is known.
- Tapping the notification opens **Memory Auditor**.
- No separate completion notification.

**Route 2 — Audit Using AI on Computer.** Helper text:

> **Export your existing memories with instructions for a file-capable AI.
> The AI will create a JSON result file for you to import below.**

Button:

> **Export Memories for Audit**

Show export status directly beneath this section. The exported `.sgmemory`
package contains the existing memories and relevant read-only audit
reference material, not conversations for new-memory discovery. It
includes:

- `README.md`;
- `instructions/agent_workflow.md`;
- `instructions/safety_and_scope.md`;
- the result schema and proposals template.

After export, show:

> **Give this package to a file-capable AI and ask it to open the package,
> read README.md, follow instructions/agent_workflow.md, and create
> proposals.json.**

**Import Memory Audit Results.** Helper text:

> **Import the JSON result file created by the external AI. Suggested
> changes will be added to Pending for review.**

Button:

> **Import**

After the user selects a result file, disable the button and show the
spinner and active button text:

> **Importing Memories…**

Show all status and errors directly beneath this section. The same
plain-language import error list and visibility rules as Feature 3 apply
verbatim. When import and validation finish successfully, show:

> **Potential Memories Found: N**

Show **View**, which opens **Memories → Pending**.

The result file contains proposals only. The phone validates and organizes
them into Pending cards for the user to review, correct, accept, or
reject. Nothing is applied automatically.

Creating an audit package and importing an audit result are durable
operations. The screen does not need to stay open, and verified progress
and completion remain visible when the user returns. A resumed or repeated
import must not create duplicate Pending proposals.

**Wording NOT approved (stop point):** the exact success, no-findings,
export-completion, interruption, and failure messages for the audit
sections. Ask the owner before writing any terminal-state wording.

### Internal steps

**A.** API-model audit route: frozen snapshot, fixed batches, durable
foreground service, findings staged to Pending.
**B.** Computer audit route export, reusing Feature 3's package machinery.
**C.** Audit-result import through the same validation and ledger; no
duplicate Pending items on resume or retry.
**D.** End-to-end proof of both routes with real runs.
**E. Final verification** *(Opus)* — both routes exercised on device,
findings reviewed in Pending, error messages verified.

### Complete when

Both audit routes work end to end, CI is green, merged to `main`,
owner-confirmed on device.

---

## Not on this roadmap

Parked feature specs elsewhere in the repository (`ui-redesign-plan.md`,
`whisper-local-plan.md`, `document-includes-plan.md`,
`image-generation-rebuild-plan.md`, profile-images documents) are not
scheduled and are not to be started, prepared for, or refactored toward.
AMOLED/theme work remains paused by owner ruling. New work enters this
roadmap only when the owner adds it in chat.
