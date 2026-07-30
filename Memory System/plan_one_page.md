# The Memory Plan — the only page the owner needs

The long counterplan file in this folder is agent machinery. Agents read
it. The owner never has to open it, and no agent may send her to it,
quote its section numbers at her, or ask her to manage it.

*(Status recorded 2026-07-30. Agents: update this page whenever the state
changes — it must always be true.)*

## What already works (on main)

- Chats are recorded for review while **Archive this chat** is on.
- **Memory Assistant** reads them and suggests memories. Everything lands
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
- Before adding or replacing Memory Assistant wording, inventory the exact
  text already in the app and when it appears. Reuse the existing status
  surface rather than inventing a parallel set of progress or result messages.
- **Lorebook suggestion review structure is approved.** Lorebook Suggestions
  are reviewed in the Lorebooks area, not the Memory Browser. The ordinary
  Lorebooks screen does not show a pending-suggestions split menu. That split
  menu appears only while one or more Lorebook Suggestions are pending and
  disappears when none remain. Each suggestion shows the proposed entry text
  and trigger keywords — no separate title — plus a drop-down labeled
  **Assign Lorebook** for choosing an existing Lorebook or creating a new one
  through the normal full-page flow. The user may edit, approve, or delete each
  suggestion. Nothing is written to a Lorebook until that suggestion is
  individually approved, and this flow never edits or deletes existing
  Lorebook entries.
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

## What's left — in any order, or never

- **Self-repairing search** — background housekeeping so search stays
  fast and fixes its own index. Needs nothing from you.
- **The duplicate screen** — when a new memory looks like one you have,
  a side-by-side where you pick keep / replace / delete.
- **Faster Lorebooks** — speed and better logs. Needs nothing from you.
- **No-model mode** — a notice that Associative Search can't enter chats yet,
  plus an optional **Lorebook Memories** analysis type that creates Lorebook
  entry suggestions with trigger keywords instead. The review structure,
  analysis-type labels, helper wording, and row layout are approved.
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
