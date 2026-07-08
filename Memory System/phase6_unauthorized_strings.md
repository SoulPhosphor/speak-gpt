# Phase 6: Unauthorized Strings

> **✅ RESOLVED (July 8 2026).** All 13 strings below have been removed from
> `app/src/main/res/values/strings.xml` (the Memory Assistant screen they
> lived on was torn down). The only `memory_assistant_*` string in the app
> today is `memory_assistant_coming_soon` ("Coming soon.") — an
> owner-approved placeholder on the new `MemoryAssistantActivity`. When the
> real Memory Assistant page is built (owner is walking through the design
> next), its strings must be the owner's words, gathered in chat. This file
> is kept as a record of the violation and its fix.

These 13 strings were written by an AI agent without the owner's approval.
They are user-visible text on the Memory Assistant and Advanced Settings
screens. Every one of them violates the Owner's Words Rule. The owner was
never walked through what these screens do, never shown the strings, and
never given the chance to approve or write them.

The strings remain in `app/src/main/res/values/strings.xml` and must be
replaced with the owner's words before the feature is considered approved.

## Memory Assistant page

1. **memory_assistant_hint** — Description at the top of the page.
   "Reads finished conversations and suggests memories. Suggestions are drafts — review them under Pending memories in the Memories browser."

2. **memory_assistant_row_subtitle** — Subtitle under "Memory Assistant" on the Memory Settings screen.
   "Suggests memories from finished conversations"

3. **memory_assistant_status_fmt** — Shows how many conversations are waiting.
   "Conversations waiting: %1$d"

4. **memory_assistant_progress_fmt** — Shows during analysis.
   "Analyzing conversation %1$d of %2$d…"

5. **memory_assistant_done_fmt** — Shows when analysis finishes with results.
   "%1$d suggestions filed from %2$d conversations."

6. **memory_assistant_done_none** — Shows when analysis finds nothing.
   "No suggestions filed."

7. **memory_assistant_done_failures_fmt** — Shows when some conversations failed.
   "%1$d conversations could not be read and stay in the queue."

8. **memory_assistant_error_fmt** — Shows when the run fails entirely.
   "Run stopped: %1$s"

9. **memory_assistant_not_configured** — Shows when the AI endpoint isn't set up.
   "Set the Archivist endpoint and model in Memory settings first."

10. **memory_assistant_nothing_pending** — Shows when there's nothing to analyze.
    "No conversations waiting."

## Advanced Settings page

11. **memory_assistant_advanced_hint** — Description at the top of Advanced Settings.
    "For advanced users. Adjust these if your model mishandles memory extraction."

12. **memory_assistant_max_suggestions_hint** — Label inside the number field.
    "Suggestion limit"

13. **memory_assistant_saved** — Toast after saving.
    "Saved."
