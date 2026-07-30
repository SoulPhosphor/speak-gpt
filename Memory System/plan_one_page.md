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
- **Lorebook suggestion review structure is approved.** Lore book suggestions
  are reviewed in the lore book area, not the Memory Browser. A split menu
  appears only while suggestions are pending. Each suggestion shows the
  proposed entry text and trigger keywords — no separate title — plus a
  destination drop-down for choosing an existing lore book or creating a new
  one through the normal full-page flow. The user may edit, approve, or delete
  each suggestion. Nothing is written to a lore book until that suggestion is
  individually approved, and this flow never edits or deletes existing lore
  book entries. The **Memory Analysis Type** control uses a drop-down with
  **Associative Search** and **Lorebooks Only**. **Associative Search** is the
  default. Do not replace these owner-chosen terms with "saved memories,"
  "semantic search," or other substitute wording.

## What's left — in any order, or never

- **Self-repairing search** — background housekeeping so search stays
  fast and fixes its own index. Needs nothing from you.
- **The duplicate screen** — when a new memory looks like one you have,
  a side-by-side where you pick keep / replace / delete.
- **Faster lore books** — speed and better logs. Needs nothing from you.
- **No-model mode** — a notice that Associative Search can't enter chats yet,
  plus an optional **Lorebooks Only** analysis type that creates lore book
  entry suggestions with trigger keywords instead. The review structure and
  analysis-type labels are approved.
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