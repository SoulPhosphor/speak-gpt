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

> Total conversations since last run: `x`
> Conversations pending review: `x`
> Date of last backup: `July 5, 2026`

**Resolved (owner, July 8 2026): the first line counts conversations since the
last ANALYSIS RUN, not the last backup.** A *run* is an analysis pass — the
Archivist reading conversations and filing suggested memories into Pending; a
*backup* is a separate thing entirely (exporting the store to a JSON file, in
Memory Settings) and never pulls or suggests anything. Keying the count off the
run is what lets the user re-run a grouping even when it previously found
nothing. This count excludes deleted conversations (§2).

⚠️ **Small remaining sub-question (§3.1):** the first line is now "since last
run" but the third line is still "Date of last **backup**". Backup date is a
genuine safety fact (what isn't exported yet), but the framing is mixed —
confirm whether the third line should stay "Date of last backup", change to
"Date of last run", or show both.

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

1. ~~"since last backup" vs "since last run"~~ **RESOLVED (July 8 2026): the
   first line counts since the last analysis RUN.** Sub-question still open:
   should the third line stay "Date of last backup", become "Date of last
   run", or show both? (See §1.)
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

This screen is the trigger + report UI for the Archivist run.

> **Status (July 8 2026, Phase 6 branch): the backend below is BUILT** —
> `preferences/memory/archivist/Archivist.kt` (analyze/rerun/eligibility),
> `ArchivistPrompt.kt` (the rewritten extraction prompt),
> `ArchivistResponseParser.kt` (validation gate, unit-tested), the DB v11
> `archivist_runs` history table, and the MemoryStore draft-filing CRUD
> (`insertArchivistDraftMemory` — enforces draft + origin='archivist', never
> writes protection). Drafts land in `memories.status='draft'` (§14 one home)
> and Model rules' Pending. Card placements are NOT built (owner: that flow
> isn't designed yet). The SCREEN is not built; the §3 questions below are
> still the owner's to answer.

### Facts block — data sources (mostly already in `MemoryStore`)
- "Conversations pending review" → `MemoryStore.pendingReviewCount()` (exists).
- "Date of last backup" → `getMeta(META_LAST_AUTO_EXPORT_AT)` (exists).
- "Total conversations since last run" → **NEW metric** (owner chose "since
  last run", NOT the backup). This is the count of conversations eligible to
  analyze that haven't been analyzed since the last run — in practice the same
  eligibility set as below (`transcripts` pending & unprocessed, existing
  chats only), which is exactly what the button would process next. Do NOT use
  `chatsSinceLastBackup()` for this line. (`chatsSinceLastBackup()` stays
  available only if the third line keeps the backup framing.)

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
