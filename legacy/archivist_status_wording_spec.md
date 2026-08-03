# Archivist Run Failure And Status Wording (owner-sanctioned, July 8 2026)

*The owner had another AI draft this wording spec and handed it over in chat
with explicit permission: implement it as written, and where a case is missing,
match its professional tone without a separate approval pass ("This is one case
you don't need approval for wording"). This is the ONLY area with that
dispensation — everywhere else the Owner's Words Rule applies unchanged.*

*Owner corrections that ride with it (same message): the Recent Memory
Analysis row is date → information → **Rerun on the far RIGHT** ("a nice
organized chart"), superseding the design doc's far-left note.*

## Core rules

- Never silently reset the button on failure; the user must understand what
  happened on the screen itself.
- Archivist failure / partial-failure records are ALWAYS written to the Memory
  Debug Log, even with memory debugging off — they are user-relevant recovery
  information, not optional debug noise.
- Not-configured state shows ABOVE the run button (button disabled); all
  post-run statuses show BENEATH the button.
- Title Case for labels/badges/buttons; sentence case for explanations. Clear,
  professional, low-stress; never "Something went wrong"; never color alone;
  never make the user guess whether to retry, change settings, or ignore.
- Theme-safe colors: disabled styling for not-ready; error token for full
  failure; warning/caution token for partial; neutral/success otherwise.

## Visible states (labels, messages, reasons — use verbatim)

1. **Archivist Not Ready** (above disabled button)
   "Memory Archivist needs a model before it can run."
   Button: "Set Up Archivist Model" (enabled iff it can open the settings).
   Log always: category, message, which required value was missing.

2. **Run Fully Failed** — "Memory extraction failed. No memories were created
   from this run." + one most-specific reason:
   A "Archivist could not reach the selected AI service. Check your connection or try again later."
   B "Archivist could not access the selected AI service. Check the endpoint settings, API key, or model access."
   C "Archivist hit a usage limit before memories could be created. Try again later or choose fewer conversations."
   D "Archivist returned a response the app could not read. No memories were saved from this run."
   E "Archivist found possible memories, but the app could not save them. No unsaved memories were added."
   F "Memory extraction was interrupted before it could finish. No memories were created from this run."
   G "Memory extraction failed for an unknown reason. No memories were created from this run."
   Button: "Try Again" (retryable: network, limits, interrupted, unreadable,
   unknown) / "Check Archivist Settings" (endpoint/key/model-access causes).
   Log always: category, on-screen message, reason, technical details,
   timestamp, conversations selected/processed, memories created: 0.

3. **Run Partially Failed** — "Memory extraction finished with some skipped
   conversations. Some memories were created, but a few conversations could
   not be processed." + counts ("Created memories: N / Processed
   conversations: N / Skipped conversations: N") + most useful reason(s):
   A "Some conversations were skipped because Archivist could not reach the selected AI service."
   B "Some conversations were skipped because the selected AI service rejected access. Check endpoint settings, API key, or model access."
   C "Archivist hit a usage limit before all conversations could be processed. Saved memories were kept."
   D "Some conversations returned responses the app could not read. Saved memories from the rest of the run were kept."
   E "Archivist found possible memories for some conversations, but the app could not save those items. Other saved memories were kept."
   F "The run was interrupted before all conversations could be processed. Saved memories were kept."
   G "Some conversations could not be processed for an unknown reason. Saved memories from the rest of the run were kept."
   Button: "Try Again" (retry failed-only if supported, else the set).
   Log always: category, message, reasons, technical details, timestamp, all
   counts, failed conversation ids if safe.

4. **Nothing To Extract** — "No eligible conversations were found for this
   run." Details if known ("No conversations were selected." / "The current
   filters did not match any conversations." / "The selected conversations
   were not eligible for memory extraction." / "The selected conversations
   were already processed or skipped by current settings.")
   Button: "Change Selection" if a selection UI is reachable, else "Try Again".
   Log optional.

5. **No New Memories Added** — "Archivist finished the run, but no new
   memories were added." Details if known ("Archivist did not find anything
   new to save." / "Archivist found only memories that already exist." /
   "Memory settings skipped the available results." / "The selected
   conversations did not contain memory-worthy material.")
   Button: "Run Again". Log optional. Never styled as a scary error.

6. **Run Interrupted** — "Memory extraction was interrupted before it could
   finish." Details: "Saved memories were kept. Some conversations may not
   have been processed." (some saved) / "No memories were created from this
   run." (none). Button: "Try Again".
   Log always: category, message, cause (user-canceled / system / unknown),
   timestamp, counts.

7. **Blocked By Memory Settings** — "No memories were added because current
   memory settings blocked this run." Only when settings are confidently the
   cause. Button: "Review Memory Settings". Log always.
   *(Implementation note: the current engine excludes ineligible conversations
   up front, so this state has no trigger path today; the wording is reserved.)*

8. **Some Memories Deleted Later** (recent-run row badge, never a failure) —
   expanded explanation: "This run created memories that no longer exist
   because they were deleted after extraction." Never imply the run deleted
   them.

## Recent run row labels

Completed · Run Fully Failed · Run Partially Failed · Nothing To Extract ·
No New Memories Added · Run Interrupted · Blocked By Memory Settings, plus the
"Some Memories Deleted Later" secondary badge where applicable.

## Do not

No silent failures; no log-only failures; no debug-toggle gating of Archivist
failure records; no color-only signaling; no vague wording; no calling partial
success a full failure; no scary "No New Memories Added"; no guessing.
