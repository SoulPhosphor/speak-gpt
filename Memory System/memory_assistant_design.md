# Memory Assistant — Design & Build Notes

*Owner decisions captured in chat **July 8 2026**. This is the design for the
**Memory Assistant** screen — the user-facing surface of the Phase 6 Archivist.
These decisions are approved and current; where anything older disagrees, this
plus `owner_approved_rules.md` (and its July 8 addendum) win.*

**Status: SPEC ONLY — not built.** There is no Archivist pipeline in code yet
(only dormant storage: the `proposals` table, `origin='archivist'`, and the
Archivist endpoint/model settings in Memory Settings). The screen today is a
"Coming soon" placeholder (`MemoryAssistantActivity`). Build this in a fresh
Phase 6 conversation — the piping is fragile, so read this whole file plus
`owner_approved_rules.md`, `archivist_spec.md` (pre-revision banner), and the
storage notes in `CLAUDE.md` first.

---

## 1. The screen, top to bottom (owner-approved wording — use verbatim)

### Helper text (top of page)

> Use AI to analyze conversations for possible memories. Found memories will
> be set to pending in the memory browser.

### Facts block (three lines)

> Total conversations since last backup: `x`
> Conversations pending review: `x`
> Date of last backup: `July 5, 2026`

⚠️ **Open question — resolve with owner before building (see §3.1).** The first
line says "since last backup", but the behavior note in §2 says "since last
**run**". These are different numbers. Confirm which the top line means.

### The button

- **Idle label:** `Analyze Conversations`
- **While running, label changes to:** `Analyzing History…`
  - Sub-text under the button while active:
    > This may take a moment.
    > Conversation 3 of 10
    (the "3 of 10" is live per-conversation progress.)
- **On completion, label changes to:** `Complete!`
  - The app does **NOT** auto-navigate to the browser. Instead the sub-text
    under the button becomes one of:
    - Found something:
      > `x` memories found.
      > View pending memories.
      ("View pending memories" is a link — see §2.)
    - Found nothing:
      > No new memory candidates found.
    - (Choose whichever fits the run's result.)
- **On failure:** the sub-text says there was a failure and what happened, and
  offers a rerun if appropriate. Exact failure wording is **TBD/owner-approved
  later** — the owner can't enumerate error cases up front, but graceful,
  plain-language failure reporting + a rerun option is required. Do not ship
  invented error copy without owner approval.

### "Recent Memory Analysis" section (always present, under the button)

- A labelled separator: **Recent Memory Analysis**.
- Under it, a short list of the **last ~3–5 runs** (exact count TBD), each row:
  - the **date** of the run,
  - the **result** ("5 found", "None found", etc.),
  - if any memories created by that run have since been **deleted**, list/flag
    those,
  - on the **far left** of the row, a **Rerun** button that re-analyzes that
    run's conversations.

---

## 2. Behavior rules (owner-approved)

- **"View pending memories" → the Memory Browser**, pre-sorted/filtered so the
  user sees **everything in pending** (i.e. Status = Pending/draft). **Do NOT
  separate the roleplay drafts from the rest** — show them all together; the
  scope icons already tell them apart.
  - Implementation: set `MemoryBrowserFilterState` to Status = {`draft`}, clear
    other filters, then open `MemoryBrowserActivity`.
  - ⚠️ Note (§3.2): there is already a dedicated `MemoryPendingActivity` + a
    "Pending memories (N)" banner on the browser. The owner asked specifically
    for the **browser filtered to Pending** here. Reconcile the two surfaces
    with the owner before building (probably: this link uses the filtered
    browser; the Pending screen stays as its own thing).
- **Deleted conversations don't count.** "Total conversations since last run"
  excludes any conversation that has been deleted.
- **Button resets on revisit.** When the page is re-opened, the button label
  goes back to `Analyze Conversations` **if** there are no new conversations
  and none newly marked to be analyzed. (A just-finished `Complete!` state is
  not sticky across a fresh visit once there's nothing new to do.)
- **Re-enabled conversations must be caught.** A conversation older than the
  last-analyzed date may have been marked *don't save / don't archive* at the
  time and **later turned back on**. The eligibility logic must re-include
  those — eligibility is a **live query on current state**, not "created after
  the last watermark". (The store already re-queues excluded transcripts to
  `pending` when a chat is re-included — see plumbing.)

---

## 3. Open questions to settle with the owner before building

1. **"since last backup" vs "since last run"** on the facts block's first line
   (see §1). Which metric, and is the label right?
2. **"View pending memories" target**: the filtered browser (as written) vs the
   existing `MemoryPendingActivity`. Confirm.
3. **Recent-runs count**: 3 or 5 (or a fixed 5)?
4. **Run scope**: "Analyze Conversations" processes ALL currently-eligible
   pending conversations in one batch (the "N of M" progress implies this).
   Confirm one-batch-all vs a capped batch.
5. **Failure copy**: gather real failure cases during the build and get the
   owner's words for each before shipping.

---

## 4. Plumbing notes (implied backend — for the Phase 6 builder, NOT owner-approved UI)

This screen is the trigger + report UI for the Archivist run. None of this
exists yet.

### Facts block — data sources (mostly already in `MemoryStore`)
- "Conversations pending review" → `MemoryStore.pendingReviewCount()` (exists).
- "Date of last backup" → `getMeta(META_LAST_AUTO_EXPORT_AT)` (exists).
- "Total conversations since last backup" → `chatsSinceLastBackup()` (exists) —
  BUT see the open question; if the owner means "since last **run**", that's a
  NEW metric off the run history below, and it must exclude deleted chats.

### Eligibility (what a run analyzes)
- Source is the `transcripts` table: `review_status` ∈ {`pending`,`processed`,
  `excluded`}, plus `processed_at`. Eligible = `review_status='pending' AND
  processed_at IS NULL`, for chats that still exist.
- Excluded→pending re-queue already happens on re-include
  (`UPDATE transcripts SET review_status='pending' … WHERE review_status=
  'excluded' AND processed_at IS NULL`), which is the mechanism behind the
  "turned back on later" rule — verify it covers the owner's case.
- Deleted chats: ensure their transcript rows are gone or filtered so they
  don't inflate counts (the "deleted don't count" rule).

### The run itself (the Archivist — Phase 6 core)
- Model + endpoint come from Memory Settings (`getArchivistEndpointId()` /
  `getArchivistModel()`, exist).
- Per-conversation loop with a progress callback → drives "Conversation N of M".
- Parse proposed memories; write them as **`memories.status='draft'`** so they
  land in Pending (rules §14). Roleplay drafts go in the SAME queue (no
  separate surface). **Never write a protection/handling field** (retired,
  July 8). Advance each processed transcript's watermark
  (`review_status='processed'`, set `processed_at`).
- Obey `owner_approved_rules.md` + the July 8 addendum for what may be
  proposed (no companion/persona content, no modes/directives, etc.).

### NEW storage the design requires (does not exist)
- **A run-history record** to power "Recent Memory Analysis": per run, store
  the date, the result count, and the set of conversation ids analyzed (so
  **Rerun** can re-feed them) and the memory ids created (so "deleted since"
  can be computed by checking those ids against current existence /
  `deleted_ids` tombstones). This is a new table (or a structured meta blob) —
  a schema decision for the builder.
- The `proposals` table is dormant and is NOT the home for these drafts (rules
  §14: memory drafts live in `memories.status='draft'`, never a second store).

### Navigation
- "View pending memories" sets the browser filter state (Status = draft) and
  launches `MemoryBrowserActivity`.

---

*End of Memory Assistant design. All UI strings in §1–§2 are the owner's exact
words (July 8 2026); §4 is builder guidance, not user-facing copy. Failure
wording and the §3 open questions are deliberately left for the owner.*
