# The Memory Plan — the only page the owner needs

The long counterplan file in this folder is agent machinery. Agents read
it. The owner never has to open it, and no agent may send her to it,
quote its section numbers at her, or ask her to manage it.

*(Status recorded 2026-07-30. Agents: update this page whenever the state
changes — it must always be true.)*

## What already works (on main)

- Chats are recorded for review while **Archive this chat** is on.
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

## The one tap between you and memories in your chats

Install the embedding model in **Advanced Memory Settings**. That's it.
(Your own ruling: memories can't enter chats without it. Everything else
already works without it.)

## Binding owner corrections

- **Lorebook** is always one word in user-facing text throughout the app,
  including plurals and compound labels: **Lorebook**, **Lorebooks**,
  **Lorebook Memories**, and **Lorebook Suggestions**. Never display
  **lore book** or **lore books** as two words.
- Turning **Archive this chat** off pauses archiving without erasing,
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

The Memory Manager list contains two separate rows for the two analysis routes:

1. Rename the existing **Memory Assistant** row to **API Memory Assistant**.
2. Directly beneath it, add a row titled **Computer Memory Review**.

Approved subtitle for **Computer Memory Review**:

> **Use an AI on your computer to review chats or check existing memories.**

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
- a new memory suggestion carries a proposed scope and target;
- the phone validates every ID, placement, and piece of evidence against its
  current state before anything reaches Pending;
- invalid placement is rejected or returned for correction, and the computer
  never writes directly into the memory store;
- every usable result still requires user review and approval in Pending.

The AI may propose the wrong placement for a new memory, but it cannot silently
save it there. Exact edits and merges are tied to existing IDs; new placements
remain visible proposals that the user can correct before approval.

This file-based computer workflow is separate from any tentative future web or
desktop client that mirrors the phone app. A custom client may be considered
later, but it is not required for the first working computer-review route.

## What's left — in any order, or never

- **Self-repairing search** — background housekeeping so search stays
  fast and fixes its own index. Needs nothing from you.
- **The duplicate screen** — when a new memory looks like one you have,
  a side-by-side where you pick keep / replace / delete.
- **Faster Lorebooks** — speed and better logs. Needs nothing from you.
- **No-model mode** — a notice that Associative Search can't enter chats yet,
  plus an optional **Lorebook Memories** analysis type that creates Lorebook
  entry suggestions with trigger keywords instead. The review structure,
  analysis-type labels, helper wording, row layout, result wording, and Pending
  navigation are approved.
- **The computer feature** — the original goal: export your chats as a
  package, let an AI on your computer suggest memories from them, bring
  the suggestions back into Pending. Needs the duplicate-screen
  foundations first; nothing else.

## How to start anything

One line in chat, in your words: *"do the duplicate screen"*, *"do the
computer feature"*, *"fix the name"*, *"make this screen less ugly."*
The agent maps it to the machinery. You read nothing, and you are not
asked questionnaires — where a screen genuinely needs your words, the
agent builds everything else first and asks one thing, once.
