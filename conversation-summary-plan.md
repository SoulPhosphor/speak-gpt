# Conversation Summary & Context Control — Research and Proposal

**Status: APPROVED PLAN, READY FOR IMPLEMENTATION — all product decisions
in §5 were approved by the owner (July 28–29 2026). User-facing wording
and prompts finalized July 29. §6 lists two stated defaults the owner can
veto. No code has been changed on this branch.**

Date: July 28–29 2026. Branch: `claude/conversation-summary-research-89akp5`.

---

## 1. The problem this solves

The app currently sends the **entire stored conversation** to the API on
every turn. `rebuildModelProjection()` in `ChatActivity` converts every
stored message into the model-facing list, and the frozen-request pipeline
sends all of it. The only protection is the context-window check
(`ModelContextCapacity.decide`), which can warn or block — it never trims.

Consequences for long conversations:

- **Cost grows with the square of conversation length.** Turn 200 re-sends
  turns 1–199. On expensive API models, an hour of chat late in a long
  conversation can cost many times what the same hour cost early on.
- **Eventually the conversation hits the context wall** and the app can only
  warn or block. There is no built-in way to continue a long conversation
  except starting a new chat and losing continuity.

Goal: let the user control **how much recent conversation the model receives
verbatim**, and represent everything older by a **summary the user can see,
edit, and control** — so long conversations stay affordable and never hit a
dead end, without silently discarding anything the user wrote.

This is separate from the Memory System. The Archivist extracts durable
cross-conversation memories from *finished* chats into drafts. This feature
manages what the model sees of the *current* chat, turn by turn. They
complement each other and share no storage.

---

## 2. What already exists in this app that this can build on

- **Auxiliary summarization calls already exist.** The document-includes
  feature already makes small background chat-completion calls to condense
  documents and caption images (`IncludeAuxiliaryRequestPolicy`), including
  the pattern of choosing a model and a max-token budget for the auxiliary
  call. A conversation summarizer is the same kind of call.
- **A single choke point for what gets sent.** The frozen-request pipeline
  (`buildFrozenRegularRequest` + `rebuildModelProjection`) is the one place
  the model-facing history is assembled. Trimming and summary injection slot
  in there without touching storage — stored messages are never altered.
- **Per-endpoint context-window knowledge and token estimation** already
  exist and can drive honest "what is being sent" displays.

---

## 3. Research: how existing systems do it

### 3a. SillyTavern built-in Summarize extension

The most widely used ready-made design. One rolling **"Current summary"**
text per chat:

- Updated automatically **every X messages or every X words** (user-set),
  or manually with a "Summarize now" button.
- The **summary is visible in an edit box** — the user can rewrite it, and a
  **Pause** toggle stops automatic updates so a hand-written summary is
  never overwritten. A "Restore previous" undoes a bad update.
- **Injection position is user-chosen**: before the system prompt, after
  it, or in-chat at a chosen depth.
- The **summarization prompt is editable**, with a target length in words
  and a separate response-token limit for the summary call.
- Their docs warn plainly that model-written summaries "may lose important
  details or contain hallucinations" and advise reviewing/correcting — the
  editable, pausable summary box is the mitigation.

Weakness (widely discussed in that community): each update **rewrites the
whole summary**, so details drift or vanish over time, and one bad
generation can damage a previously good summary (hence Pause/Restore).

### 3b. Qvink MessageSummarize (community extension, the current favorite)

A finer-grained design:

- **Each message gets its own small summary**, attached to that message.
  Editing or deleting a message affects only its own summary.
- **Short-term window**: the most recent messages are sent in full, up to a
  user-set budget. Beyond that, the *summaries* stand in for the messages.
- **Long-term pinning**: the user can mark any message as important (a
  "brain" icon); its summary stays in context past the short-term window,
  up to a separate budget.
- Everything is **visibly color-coded** in the chat (in window / pinned /
  fallen out), and every summary is individually editable.
- The summary call can use a **separate connection/model** — i.e. a cheap
  model writes summaries while the expensive model chats.

Strengths: precise user control, edits are localized, and what the model
receives is fully deterministic and inspectable. Cost: many small summary
calls (one per message, batchable) and more UI surface.

### 3c. Rolling "summary buffer" (LangChain `ConversationSummaryBufferMemory`, Claude Code compaction)

The standard programmatic pattern:

- Keep a **buffer of recent messages** under a token/message budget.
- When the buffer overflows, **fold the evicted oldest messages into a
  running summary** with a "progressive summarization" prompt: *existing
  summary + newly evicted lines → updated summary*. Old messages are
  summarized **once**, not re-summarized every time — cheaper and more
  stable than SillyTavern's whole-rewrite approach.
- Claude Code's auto-compact is the same idea at a coarser grain: near the
  context limit, compress the older conversation into a structured summary
  and continue with summary + recent tail.

### 3d. Retrieval (vector/RAG) approaches — noted, not recommended here

SillyTavern's Vector Storage and similar systems embed old messages and
retrieve "relevant" ones per turn instead of summarizing. Rejected as the
primary mechanism for this feature: retrieval is opaque (the user cannot
predict or easily inspect what the model was given), needs embedding
infrastructure, and this app's planned RAG effort already belongs to the
Memory System. Could be revisited later as a supplement.

---

## 4. Approved overall shape (owner, July 28 2026)

A hybrid of 3a's user-facing transparency and 3c's progressive mechanics,
with 3b's pinning as a possible later phase:

1. **User-controlled recent window.** A per-chat setting (with an app
   default) for how much recent conversation is sent verbatim — expressed
   in **messages** (simple, matches how the owner phrased the request),
   optionally capped by tokens later. Everything inside the window goes to
   the model exactly as today.

2. **Progressive rolling summary for everything older.** When messages age
   out of the window, they are folded into the chat's stored summary by a
   small background API call (existing summary + departing messages →
   updated summary). Each message is summarized into the rolling summary
   once. Stored messages are never modified or deleted — the summary is a
   parallel record, and widening the window later simply sends the real
   messages again.

3. **Summary kind and length are user choices.** A style selector (e.g.
   narrative "story so far" vs. factual key-points) and a target length.
   Mechanically these select different summarizer prompts and token
   budgets. The prompt texts themselves are wording and need owner
   approval separately.

4. **The summary is visible, editable, and pausable** — SillyTavern's
   proven mitigation for summary drift, and consistent with this app's
   honesty standard: the user can always see exactly what stands in for
   their older messages, correct it, or freeze it. A manual
   "update summary now" action complements the automatic fold-in.

5. **Summarizer model choice.** Default to the chat's own endpoint/model,
   with an option to pick a cheaper model for summary calls (mirrors the
   existing includes condense-model pattern and Qvink's separate-connection
   design). This is where most of the cost savings for expensive models
   compounds.

6. **Off by default.** Current behavior (send everything) remains the
   default until the user turns the feature on for a chat or globally —
   no silent change to what existing conversations send.

Cost effect: with a fixed window of W messages, per-turn input cost stops
growing with conversation length (O(W + summary) instead of O(n)), and the
summarization calls are small and can run on a cheap model. For a very long
conversation on an expensive model this is routinely a 5–20× input-cost
reduction, more the longer the chat runs.

---

## 5. Approved decisions (owner, July 28 2026)

1. **Overall shape approved**: user-set recent window of messages sent
   verbatim + a progressive rolling summary for everything older, visible,
   editable, and pausable. Stored messages are never altered. Off by
   default for existing behavior safety.

2. **Summarizer Settings screen.** A dedicated screen reached from the
   regular settings page. Settings row: title "Summarizer Settings",
   subtitle "Settings related to the summarizer feature." The screen
   holds:
   - **Summary Model** — the API endpoint and model picker for summary
     calls, modeled on the Memory Assistant's endpoint picker (same
     interaction shape);
   - **Complete Messages** — the default recent-window message count
     (the value new chats start with, so the user doesn't retype it).
     Hint: "Last number of complete messages sent to the model before
     summarizing."
   - a toggle for whether the summarizer is on by default for new chats;
   - **Summary Length** — a words value. Hint: "Maximum amount of words
     the summary can become."
   - **Summary Prompt** — the summarizer prompt area at the bottom,
     revealed for editing, with a revert button that restores the app's
     default prompt. The dropdown label is "Prompt".

3. **Quick Settings (per chat).** A toggle labeled "Use Summarizer" turns
   the summarizer on or off for the current chat — no subtitle or hint.
   When on, it reveals a number box labeled "Complete Messages" (no
   hint) for how many recent messages are always handed to the model in
   full. The box is prefilled from the Summarizer Settings default; the
   user can change it for this chat but never has to. Turning the toggle
   off hides the number box and is the pause action — there is no
   separate pause control elsewhere.

4. **Window unit is a message count** (not tokens).

5. **Shipped summarizer prompts (owner-authored, July 29 2026).**
   Two prompts ship in slots one and two. `{length}` is replaced at
   runtime with the Summary Length value from Summarizer Settings.

   **Slot 1 — "Storyteller":**

   > You maintain a concise narrative recap of this conversation. Below
   > are the existing recap and the messages that have just moved out of
   > the recent-message window. Integrate the new information into one
   > coherent updated recap.
   >
   > Preserve:
   >
   > - the main events and their order;
   > - the user's goals and concerns;
   > - important reasoning or context behind decisions;
   > - decisions that were made;
   > - unresolved questions, problems, or next steps;
   > - the current state of active topics.
   >
   > Remove repetition, casual filler, and details that no longer affect
   > the conversation. When newer information replaces or corrects older
   > information, update the recap rather than preserving both versions.
   > Do not present suggestions, guesses, or possibilities as settled
   > facts.
   >
   > Write in clear, compact prose under {length} words. Reply only with
   > the updated recap.

   **Slot 2 — "Reporter":**

   > You maintain compact reference notes for this conversation. Below
   > are the existing notes and the messages that have just moved out of
   > the recent-message window. Update the notes using short, standalone
   > statements.
   >
   > Keep only information that may be needed later, including:
   >
   > - names and relevant identifying details;
   > - explicit preferences, requirements, and constraints;
   > - decisions that were actually made;
   > - current plans and commitments;
   > - unresolved tasks or questions;
   > - important facts stated by the user;
   > - the present status of active work.
   >
   > Do not preserve conversational flow, emotional narration, repeated
   > explanations, abandoned ideas, or temporary details with no likely
   > future use. Do not turn an assistant suggestion into a decision. If
   > newer information corrects, replaces, completes, or cancels an older
   > item, revise or remove the older item.
   >
   > Use brief bullet points, with one fact per bullet. Keep the complete
   > list under {length} words. Reply only with the updated list.

6. **Five renameable prompt slots (owner idea, July 28 2026).** The
   Summary Prompt area holds five named slots selected by the app's
   standard dropdown field (label: "Prompt"). Whichever slot is selected
   on this screen is the prompt in use — the choice does not appear in
   Quick Settings. Slot one ships as "Storyteller" and slot two as
   "Reporter" (owner-authored prompts in decision 5). Slots three to
   five start empty; users experiment by copying text with ordinary text
   selection — no dedicated copy/paste buttons (owner ruling,
   July 28 2026). Users can rename any slot. Revert buttons exist only
   under Storyteller and Reporter and restore their shipped prompts;
   the empty slots have no revert. Different styles are prompt text
   only — the wiring is identical for every slot.

7. **Empty-prompt guard on leaving the screen.** If the user leaves
   Summarizer Settings (header back control or system back gesture)
   while the selected slot's prompt is empty, a standard dialog blocks
   the exit. Approved wording (owner-authored, typos corrected with
   approval): "Model summary prompts can't be empty. Last good prompt
   has been selected." Okay backs out with the fallback applied; Cancel
   stays on the screen so the user can fix it themselves. Fallback rule
   (owner-approved): select the most recently used slot that still has
   text; if none exists, restore slot one's shipped prompt and select
   it, so summarizing can never run on empty instructions. The dialog
   uses the standard dialog theme and two-action pattern.

8. **No-model state.** The Quick Settings "Use Summarizer" toggle is
   not shown until a summarizer endpoint/model is set up — without a
   model the toggle is pointless, and hiding it means the feature can
   never be switched on in a broken state, so no exit-blocking popup is
   needed on the Summarizer Settings screen. Instead the screen opens
   with an explanatory paragraph at the top. Approved wording (owner-authored,
   typos corrected with approval): "The summarizer sends the specified
   number of messages to the model each turn. Anything beyond that
   amount is summarized using the AI model selected in these settings.
   At any time, the summarizer can be turned off in Quick settings,
   which will allow all messages to be sent to the AI again." The
   paragraph uses the `Widget.App.Screen.Intro` style per the style
   guide.

9. **Summary storage and lifecycle (owner, July 28 2026).** The summary
   is stored on the device as part of its chat's data — along with the
   fold-in bookmark and pause state — because regenerating it would cost
   API money, user edits must survive, and the bookmark depends on it.
   It lives and dies with the chat: deleting the conversation deletes
   the summary and all summarizer state, on every deletion path. Because
   the summary is condensed conversation content, it receives the same
   storage protection as the chat's messages (encrypted if they are) and
   follows the chat through renames.

10. **Pause/resume catch-up (owner, July 28 2026).** Turning the per-chat
    toggle off keeps the summary and its fold-in bookmark untouched while
    the app returns to sending everything in full. Turning it back on
    folds only the messages between the bookmark and the current window
    edge into the existing summary, in batched background calls. Nothing
    is ever re-summarized from scratch. The same mechanism handles the
    first enable on an already-long chat (one big catch-up).

11. **Summary view (owner, July 29 2026).** The chat screen's top icon
    row gains the Material Symbols "subject" icon, visible only while
    the summarizer is on for that chat. Tapping it opens the summary
    view: the summary text (editable) and the manual update action.
    There is no pause switch here — turning off the Quick Settings
    "Use Summarizer" toggle is the pause (see decision 3).

12. **Scope (owner, July 29 2026).** Regular chat requests only. The
    Playground, image-generation commands, and the function-calling /
    fine-tuned-model paths are excluded — they keep today's full-history
    behavior. No pinning of individual messages (not now).

13. **Summary Length (owner, July 29 2026).** A words value in
    Summarizer Settings with a recommended default of 300 words
    (delegated pick). Label: "Summary Length". Hint: "Maximum amount
    of words the summary can become." The value feeds the length limit
    in the summarizer prompt.

14. **Injection position (delegated decision; owner refinement
    July 29 2026).** The summary is sent as its own system-role message,
    labeled as a summary of the earlier conversation. It comes after
    everything else that's injected — system prompt, document and image
    include summaries, memory material — as the very last item before
    the oldest full message. The user's stored words are never mixed
    with generated text.

15. **Lag tolerance (owner, July 29 2026).** Transmission is
    bookmark-based: each turn sends the summary plus every message after
    the bookmark, and the bookmark advances only when a fold-in call
    succeeds. A slow or failing summarizer therefore never blocks,
    delays, or drops conversation content — requests are just
    temporarily larger until catch-up succeeds.

    **Batched fold-ins for prompt-cache safety (July 29 2026).** Fold-in
    calls run in batches: the summarizer waits until a chunk of messages
    (internal default: ten, not a user setting) has accumulated past the
    window, then folds them in one call. Between batches the summary
    text is unchanged, so the request prefix stays identical and
    provider prompt-cache discounts keep applying — and fewer summarizer
    calls are made. The bookmark rule makes the waiting safe.

16. **Error surfacing — Summarizer Errors (owner, July 29 2026).** Summarizer trouble is
    shown in app chrome only and is never injected into the conversation
    or any API request, so the main model never sees it. A dedicated
    error sound (distinct from other app sounds) plays when a summarizer
    call fails — on the first failure of an episode, not every retry —
    so the user notices without looking.

    **Icon order:** the Material Symbols "data_alert" icon comes first
    in the chat's top icon row, then the "subject" summary icon, then
    the existing icons.

    **Per-chat error log (owner, July 29 2026).** Each chat keeps its
    own last five summarizer errors — no other chat's. Each entry is
    stamped with the date and a 12-hour hour:minute time (no seconds).
    The data_alert icon is visible while the log has entries. Tapping it
    opens a popup dialog with the honest detail per the error rules:
    what failed, why when known, that no messages were lost and
    unsummarized messages are being sent in full, and what the user can
    do. At the bottom: a Copy button and a Delete button. Delete clears
    the chat's error log and hides the icon. The dialog and buttons use
    the standard shared dialog and button-role styles.

    **New-error indicator (owner approved, July 29 2026):** a small
    numeric badge on the data_alert icon shows the stored-error count
    (1–5). The changing number signals a new error without relying on
    color, and persists for users who weren't looking when the sound
    played.

17. **Wording process.** User-facing strings are approved as they come
    up during design and implementation — not deferred to a single batch.
    All labels and titles use Title Caps. All wording uses contractions
    where possible and follows the no-app-name rule. Error detail text
    is drafted during implementation when the exact failure cases are
    known.

18. **UI construction rules.** All UI uses the shared style families from
   `ui-style-guide.md` (toggle rows, label-above-box and inline number
   fields, dropdown fields, section titles/hints, action-bar headers,
   shared button roles). No hardcoded colors, typography, or geometry —
   everything must stay compatible with the app-wide theme work. Titles
   and labels use Title Caps ("Summarizer Settings", "Summary Model",
   "Complete Messages", etc.).

## 6. Remaining items

1. **Backups**: the summary rides along in internal backups and
   restores (it's part of the chat's stored data per decision 9).
   Human-readable chat exports do not include the summary — those
   exports contain only the actual conversation messages.
2. **Edited/deleted folded messages**: fold-once design means the
   summary doesn't auto-update when an already-folded message is later
   edited or deleted; hand-editing the summary is the remedy. Stated to
   the owner as the accepted default; flag if more is wanted.

---

## 7. Sources

- SillyTavern Summarize docs: https://docs.sillytavern.app/extensions/summarize/
  (also `SillyTavern/SillyTavern-Docs`, `extensions/Summarize.md`)
- Qvink MessageSummarize: https://github.com/qvink/SillyTavern-MessageSummarize
- LangChain ConversationSummaryBufferMemory (progressive summary-buffer
  pattern); Claude Code auto-compaction (same pattern, coarser grain).
