# Compaction Fix and Version Tabs: Approved Decisions (November 2 2026)

This records the owner's decisions for this change. It does not authorize
anything not listed here.

## Root cause being fixed

Compaction asked the AI to send back the whole compacted text rewritten
together with the new messages, then replaced the stored text with whatever
the AI returned. When an AI left out earlier text, that text was lost.

## Compaction behavior

- Compactions are additive. A compaction summarizes only the messages after
  the last compacted message. The app keeps all earlier compacted text word
  for word and adds the new part after it. The AI's reply never replaces
  earlier text.
- A failed compaction, recompaction, or redo saves nothing. The existing
  compacted text and the "Compacted" marker stay exactly as they were, and the
  error is shown. No partial saves.
- No change to any text sent to the AI: not the visible prompts, not the
  hidden text the app adds around them, and no first-person instruction.
- The Summarizer is not changed. It already adds new sections without
  rewriting earlier ones.

## Gear menu and Compact to Here

- Compact is removed from the chat's gear menu.
- Compact to Here stays in the AI message menu, but does not appear on
  messages that are already compacted (at or above the "Compacted" marker).

## Version tabs on the Compaction Summary screen (compaction only)

- Each tab holds the whole compacted text as it stood after one compaction.
  Tabs appear only as compactions happen: one compaction, one tab; up to four.
  When a fifth would be made, the oldest is dropped.
- Tab title: the number of the last message that version covers (for
  example 5, 15).
- Appearance comes from the prompt tab style in `ui-style-guide.md`, with no
  plus button. If a different style is needed, a new shared style based on it
  is created and added to the guide.
- The tab in use holds exactly what is sent to the main AI and what the next
  compaction builds on. It is editable. Removing text there means future
  compactions do not receive it.
- Tabs not in use are read-only, and their text can be selected and copied.
- Kebab menu on a tab not in use: Use This Summary. That tab becomes the one
  in use and editable again, and the "Compacted" marker in the chat moves to
  just after that tab's last message.
- Newer tabs left behind by Use This Summary get a red outline and the word
  Unused before their number (for example "Unused 20"). Their kebab menu also
  offers Use This Summary, so they can be reinstated.
- Compacting again while an older tab is in use deletes the Unused tabs.
- Kebab menu on the newest tab: redo only that newest compaction step with the
  currently selected summarizer AI. Success replaces that tab's text; failure
  changes nothing.

## Recompact Messages from Scratch

- Button labeled exactly "Recompact Messages from Scratch", centered below the
  screen's intro text, styled from `ui-style-guide.md`.
- Confirmation pop-up, owner-approved wording, unchanged:
  - Title: Recompact From Scratch
  - Body: This will be run the summarizer AI from the last compacted message to
    the beginning of the conversation. All previous compacted summarized text
    will be deleted and replaced.
  - Buttons: Cancel (left), Okay (right).
- After Okay, a new compacting screen comes up (the compacting progress
  pop-up). It compacts everything from the start of the conversation through
  the last compacted message, leaving out messages hidden from the AI.
- On success it deletes all tabs and leaves exactly one tab: the new
  compaction. On failure nothing changes and the error is shown.

## Still open

- Label for the newest tab's redo option.
- Whether a pop-up should ask before that redo replaces edits made in the
  newest tab.
