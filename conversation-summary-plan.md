# Conversation Summary & Context Control — Research and Proposal

**Status: RESEARCH PROPOSAL — nothing here is approved. No code has been
changed. Every product decision below requires owner approval before any
implementation.**

Date: July 28 2026. Branch: `claude/conversation-summary-research-89akp5`.

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

## 4. Recommended shape (for discussion, not approved)

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

## 5. Decisions the owner must make before any design or code

Listed here for the record; to be asked in chat one at a time, not as a
wall.

1. **Approve or redirect the overall shape** (§4: recent window +
   progressive editable rolling summary), or prefer a different research
   direction (e.g. Qvink-style per-message summaries from the start).
2. Window unit: count of messages, token budget, or both.
3. Which summary styles/kinds to offer, and default length.
4. Where the summary is injected (system-side preamble vs. a stand-in
   message at the top of the history).
5. Whether pinning individual messages to stay in context (Qvink's brain
   icon) is in scope now, later, or never.
6. Summarizer model selection UI and default.
7. Per-chat vs. global settings precedence.
8. All user-facing wording (settings labels, the summary view, status and
   failure text) — after the behavior above is decided.
9. Failure behavior when a summary call fails (this app's error-honesty
   rules apply; the specific behavior and wording need approval).

---

## 6. Sources

- SillyTavern Summarize docs: https://docs.sillytavern.app/extensions/summarize/
  (also `SillyTavern/SillyTavern-Docs`, `extensions/Summarize.md`)
- Qvink MessageSummarize: https://github.com/qvink/SillyTavern-MessageSummarize
- LangChain ConversationSummaryBufferMemory (progressive summary-buffer
  pattern); Claude Code auto-compaction (same pattern, coarser grain).
