# Phase 6 — Owner Answers & Pending/Editor Redesign (July 8 2026, evening)

*The owner answered the Phase 6 open questions and designed the pending-memory
approval flow and memory-editor rework in chat. These are owner decisions —
where anything below differs from earlier documents (including
`memory_assistant_design.md` §3 and `phase6_card_suggestions_and_icons_design.md`
§4/§6), **this wins**. Owner phrasing is quoted; use quoted words verbatim.*

## Memory Assistant answers (design doc §3, now resolved)

1. **Third facts line: "Date of Last Run"** (not "Date of last backup").
2. **"View Pending Memories" opens the Memory Browser filtered to Pending**
   (not the separate Pending screen). Style correction: action/link text is
   Title Case per the house style ("View Pending Memories").
3. **Recent Memory Analysis shows the last 5 runs.**
4. **Run scope: the user may run any number at once; batch the display and
   protect the AI's context window.** Owner wording for the running state when
   the run is large:
   > This may take a while.
   > Conversations will be batched due to size.
   > Batch One
   > x of x Conversations
   > Batch Two
   > x of x Conversations
   (repeating per batch.) The owner also flagged token limits: very long
   conversations (30+ pages) may need to be "sent one at a time". Engine note:
   each conversation already goes to the model in its own request; the added
   work is size-aware splitting of a single oversized conversation across
   multiple requests, plus the batch-grouped progress display above.
5. **Companion deletion follows the same sole-owner rule as roleplay cards:**
   "if the other companion that it's linked to is still active or existing
   then the memory should not be deleted."
6. **The extraction prompt must be exposed in Memory Settings.** The owner is
   writing up the "advanced memory section" design separately — build the
   prompt exposure when that arrives, not before.

## Pending memories — the approval flow (owner design, verbatim wording)

- The browser gets a **two-word centered header toggle** with a separator;
  tapping a word switches views ("memories are the ones that are all
  approved"):
  > Memories | Pending
  > 5 Memories Pending
  The "N Memories Pending" count line is hidden when nothing is pending.
- **The Status section is REMOVED from the Memory Filters panel** — the
  toggle makes it a duplicate.
- **In Pending view, every pending memory row carries action words across its
  bottom** — larger than normal text and strong (bold) so they're easy to see:
  - Regular memories: **Accept  Delete  Edit**
  - Roleplay memories: **Accept  Delete  Edit  Add to Card**
- **Accept** → the memory disappears from Pending and becomes a regular
  (active) memory.
- **Delete** → warning pop-up ("are you sure, this can't be undone" shape) with
  OK / Cancel.
- **Edit** → opens the memory editor.
- **Add to Card** → accepts the memory AND links it to a lore card, via a
  pop-up showing the suggested card in a dropdown (pre-selected when the
  Archivist suggested one; changeable to any card). Pop-up text:
  > Accept Memory and Link to Lore Card?

## Memory editor rework (owner design)

- **Style (app-wide correction, owner is angry about the pill pattern):**
  the entire line must never be in a box. Only DROPDOWNS are boxes, with
  barely any corner curve (~5, not pills). A selected item appears below as a
  box with the same small curve and an **× at the far right** to remove it;
  multiple selections go across. Boxes must not wrap to their own line away
  from their label.
- **Never show internal identifiers** (c-8f2a…): companions and lore cards
  always display their CURRENT name, even after renames.
- The companion target section is reworked. The sentence "Which ones does this
  belong to? Pick one or more." is GONE. Instead:
  > Associated Companion
  followed by a dropdown of all current companion names; selected companions
  appear below as the ×-removable boxes described above. The same pattern
  applies to the roleplay card pickers. Dropdown default text: **Select**
  (unless a lore-card suggestion exists — then show the suggestion).
- **Roleplay memories distinguish LINKED vs ASSOCIATED lore cards** in the
  editor:
  > Linked Lore Card [dropdown]
  > Warning: This action can not be undone. Linked cards will have this memory
  > directly added to the card and will no longer be a normal memory.
  and
  > Associated Lore Card [dropdown]
  > This will associate this memory with a specific lore card and when the
  > specific lore card gets deleted or archived this memory will also be
  > assigned the same status.
- **In pending mode the editor's bottom button reads:**
  > Approve All As Shown
  Tapping it approves the draft with everything as shown, closes the editor,
  and returns to the pending screen.
- Editing a roleplay draft must include the card slot:
  > Link to Lore Card: [dropdown]

## Supersessions this creates

- `phase6_card_suggestions_and_icons_design.md` §4 (two bulk Accept-All
  buttons; three-action rows) → REPLACED by the per-row actions above. No
  bulk accept is designed.
- `phase6_card_suggestions_and_icons_design.md` §6 "copy, not link" →
  REVISED: a **Linked** memory is added to the card and "will no longer be a
  normal memory" (owner's words); **Associated** is the lifecycle-following
  relationship. Exact storage semantics: see open questions.
- `owner_approved_rules.md` §14's "roleplay card suggestions decided
  individually, never bulk-accepted" still holds (nothing bulk-accepts card
  placements).

## Still open (asked of the owner, not yet answered)

1. **Linked-memory visibility:** after linking, does the memory still show in
   the browser with the book (`book_5`) icon, or vanish from the browser
   entirely? ("no longer be a normal memory" supports either.)
2. **Card section for a linked memory:** card entries live under named card
   sections — when linking manually with no Archivist suggestion, where does
   it land (user picks a section? a default?).
3. **Associated + deletion vs the morning's ruling:** earlier on July 8 the
   owner ruled shared memories always survive card deletion and "delete
   memories" removes sole-owned only. The new Associated wording says the
   memory is "assigned the same status" when the card is deleted or archived.
   Confirm how these compose (e.g. archive-cascade is new and uncontested;
   deletion still per the morning ruling?).
4. **Failure wording** for the Memory Assistant (three real cases exist:
   endpoint/model not configured; the whole run failing; individual
   conversations failing while others succeed). Owner words needed.
5. **The old separate Pending screen** (`MemoryPendingActivity` + the browser
   banner): assumed replaced by the in-browser Pending view — confirm removal.
6. **"N Memories Pending" singular** — is "1 Memories Pending" acceptable or
   should the singular read differently? (Owner gave the plural form only.)
7. **The advanced memory section** (extraction prompt exposure) — owner is
   writing it up; do not build ahead of it.
