# Project Plan

*Status recorded 2026-08-03. This is the only active plan for this project.
It replaces the roadmap in `Memory System/plan_one_page.md`, the phase list
in `memory-system-integration-plan.md`, and the Step 1.1–1.7 delivery list
in `Memory System/external_memory_analysis_counterplan.md`. Those files are
no longer plans: the one-page file remains the record of owner-approved
wording and behavior rulings that this plan cites; the other two remain
historical/technical reference only. Agents: keep THIS file true and do not
resurrect a superseded roadmap.*

## How this plan works

- **One stage at a time.** A stage is started only when the previous one is
  finished. Nothing is built ahead, in parallel, or "while we're in there."
- **A stage is finished only when** (1) the change is pushed and the Android
  Checks workflow is green, and (2) the owner confirms the stage's "Done
  means" result on the test device. Code that compiles is not a finished
  stage.
- **Nothing user-facing is "already decided" unless a recorded owner ruling
  says so.** Each stage below lists which rulings it builds from and which
  decisions are still open. An open decision is a stop point: the agent asks
  in chat, one question at a time, before writing anything that encodes it.
  UI layout, wording, defaults, and behavior are never invented because a
  plan seemed to imply them.
- **Difficulty and model.** Every stage carries a rating:
  - **Easy → Sonnet** — mechanical, well-specified, low blast radius.
  - **Medium → Opus** — real design-in-the-small or multi-file work.
  - **Hard → Fable** — fragile paths (chat generation, capture, migrations,
    import trust boundaries) or work where a wrong choice is expensive.
- **Stage order within a phase is fixed. Phase order after Phase 2 is the
  owner's choice** — say the word and Phases 3, 4, and 5 reorder.

## Current verified status (owner-supplied analysis, 2026-08-03)

Built: progress rework (old 1.3), self-repairing search (old 1.4), Possible
Match machinery and Review screen (old 1.5), faster Lorebooks and logs
(old 1.6), no-model mode and Lorebook Pending (old 1.7).

Partial: Archive This Chat behavior (old 1.1), Memory Manager rows,
Lorebook one-word cleanup.

Not built: Memory Budget Calculator, computer review package, computer
proof run, import and reconciliation, Memory Auditor.

Stage 1.1 verifies the "Built" claims before anything relies on them.

---

## Phase 1 — Make what exists true and finished

The prior plans marked steps finished that were missing the wiring to make
them functional. Phase 1 closes that gap before any new feature starts.

### 1.1 Verify the "Built" claims end to end

**Goal:** Confirm on the device that each item marked Built actually works
as a user experiences it — analysis progress, self-repairing search,
Possible Match review, Lorebook speed/logs, no-model mode with Lorebook
Pending. Produce a short gap list of anything that exists in code but is
not reachable, not wired, or not behaving as the recorded rulings describe.

**Done means:** the owner has a one-page checklist of what was tested and
the result, and every gap found is either fixed in this stage (if small and
already-ruled) or added to this plan as its own numbered stage. No gap is
fixed by inventing behavior.

**Open decisions:** none expected; any found gap whose fix needs a ruling
stops and asks.

**Difficulty: Medium → Opus.**

### 1.2 Finish Archive This Chat behavior

**Goal:** Complete the partially built archive toggle semantics exactly as
already ruled (recorded in the one-page rulings file, "Binding owner
corrections"): turning Archive This Chat off pauses archiving without
erasing, resetting, advancing, or replacing the last truthful archive
bookmark; turning it back on silently processes every eligible message not
already fully processed; no "Include Earlier Messages?" prompt or any
equivalent choice ever appears.

**Done means:** the owner can flip the toggle off, chat, flip it on, run
analysis, and see exactly the unprocessed messages get reviewed — nothing
lost, nothing double-processed, no prompt.

**Open decisions:** none — the behavior is fully ruled. Anything ambiguous
found in the code stops and asks rather than guessing.

**Difficulty: Hard → Fable** (touches transcript capture next to the chat
generation path, where regressions land on the owner's daily driver).

### 1.3 Rename the Memory Assistant row

**Goal:** Rename the existing **Memory Assistant** row in Memory Manager to
**API Memory Assistant** (approved wording). The other two approved rows —
**Computer Memory Review** and **Memory Auditor** — are NOT added here:
a row must never appear before its screen exists, so each row lands in the
stage that builds its screen (4.1 and 5.1).

**Done means:** the Memory Manager list shows **API Memory Assistant** and
everything behind the row works unchanged.

**Open decisions:** none.

**Difficulty: Easy → Sonnet.**

### 1.4 Lorebook one-word cleanup

**Goal:** Finish the approved ruling that **Lorebook** is always one word in
user-facing text everywhere in the app — including plurals and compounds
(**Lorebooks**, **Lorebook Memories**, **Lorebook Suggestions**). Sweep all
user-facing strings; fix remaining two-word occurrences. Code identifiers
and internal logs are not user-facing and are left alone.

**Done means:** no screen, dialog, notification, or helper text anywhere
shows "lore book" or "lore books."

**Open decisions:** none.

**Difficulty: Easy → Sonnet.**

---

## Phase 2 — UI and UX reconciliation

The owner was told UI/UX was decided. It was not. Screens shipped with
designs an AI chose on its own. This phase finds every such choice and puts
the owner back in charge of each one — without rebuilding anything before
the owner has ruled.

### 2.1 Inventory of undecided design choices

**Goal:** Walk every screen the memory work created or changed (Memory
Manager, Memory Browser and filters, Pending, Review/comparison, Memory
Settings, Advanced Memory Settings, Memory Controls, API Memory Assistant,
Lorebooks including Pending, roleplay card editors, Quick Settings memory
rows). For each screen, list which visible choices are backed by a recorded
owner ruling and which were AI-invented (layout, controls, wording, icons,
defaults). This is a document only — no code changes.

**Done means:** the owner has a screen-by-screen list, in product language,
of what was actually decided versus what was assumed. Each undecided item
is a one-line question the owner can answer with a sentence.

**Open decisions:** the inventory produces them; it does not answer them.

**Difficulty: Medium → Opus.**

### 2.2 Owner rulings, one screen at a time

**Goal:** The owner goes through the 2.1 inventory in chat at their own
pace — one screen, sometimes one item, per exchange. Rulings are recorded
in the rulings file. No code changes in this stage.

**Done means:** every item in the inventory is either ruled on or
explicitly deferred by the owner.

**Difficulty: — (conversation, no agent build work).**

### 2.3 Apply the rulings, screen by screen

**Goal:** Implement the 2.2 rulings. Each screen is its own bounded
sub-stage (2.3.1, 2.3.2, … in the order the owner ruled), finished and
confirmed on device before the next screen starts.

**Done means:** per screen — the screen matches the rulings, CI is green,
and the owner confirms it on the device.

**Open decisions:** none by construction; only ruled items are built.

**Difficulty: Easy → Sonnet per screen; any screen touching ChatActivity
or Quick Settings generation-adjacent code escalates to Hard → Fable.**

---

## Phase 3 — Memory Budget Calculator

The calculator is fully specified by recorded rulings (screen text, live
total, debounce behavior, section list and order, Revert/Save, the
Discard-all confirmation, and the shared-editor requirement). No design
decisions remain — only implementation.

### 3.1 Shared card-editor component

**Goal:** Make the existing card editors usable as an embedded component,
so the calculator shows each selected item using the same fields, labels,
styling, and validation as the real editor — with no copied second layout
and no duplicated line-height values, per the ruling. No visible change to
the existing card screens.

**Done means:** the card editors look and behave exactly as before, and the
same component can be hosted inside another screen.

**Open decisions:** none user-facing. If a card editor turns out to be
structurally impossible to reuse without visible change, stop and present
the tradeoff.

**Difficulty: Medium → Opus.**

### 3.2 The calculator screen

**Goal:** Build the Roleplay screen row and the calculator exactly per the
recorded ruling: intro text verbatim, live **Total Estimated Tokens**,
the six selectors in the ruled order (Glamour last), per-section token
counts using the app's shared token-estimation utility, Revert/Save per
section, and the Save All / Discard All / Continue Editing confirmation.

**Done means:** the owner can open the calculator, select items, see live
totals, edit and save a card there, and see the change reflected on the
normal card screen.

**Open decisions:** none — wording and behavior are ruled.

**Difficulty: Medium → Opus.**

---

## Phase 4 — Computer Memory Review (the file feature)

Lets the owner use a computer AI they already pay for instead of API
tokens: export a review package, have the computer AI propose memories,
import the result into Pending. Approved wording and screen structure are
recorded; the export/import machinery is specified in the counterplan's
technical sections (reference only — its roadmap is superseded).

### 4.1 Export: the review package

**Goal:** The **Computer Memory Review** row and screen (approved wording),
with the export function producing the `.sgmemory` package: eligible
conversations, the searchable existing-memories reference, target catalog
with stable IDs, and the package's own README and AI instructions. Includes
the approved Memory Analysis Type picker controlling whether the package
asks for Associative Memories or Lorebook Memories. Exported conversations
are claimed/frozen so the phone and the package cannot disagree about what
was reviewed.

**Done means:** the owner can export a package, open it on a computer, and
see readable instructions and data; the app shows the outstanding package
truthfully.

**Open decisions:** export-completion and failure wording is NOT approved
(recorded as awaiting approval) — stop and ask before writing any terminal
status text.

**Difficulty: Medium → Opus.**

### 4.2 Import and reconciliation

**Goal:** Import the result file: strict validation (file type, version,
package match, size, already-imported), evidence checks against the frozen
conversations, duplicate and Possible Match checks through the same filing
boundary the API route uses, per-item durable import ledger so an
interrupted import resumes honestly and a repeat import never duplicates,
and staging into the correct Pending area. The approved plain-language
import error list is already ruled and is used verbatim.

**Done means:** a valid result file lands as Pending suggestions with
**Potential Memories Found: N** and **View**; every bad-file case shows its
ruled error message under the Import button; importing twice changes
nothing.

**Open decisions:** none expected beyond already-ruled text; anything new
stops and asks.

**Difficulty: Hard → Fable** (a trust boundary writing into the memory
store; the most expensive place in the project to be wrong).

### 4.3 Proof run

**Goal:** Prove the loop end to end with a real file-capable AI on a real
computer: export, run, import. Fix what the proof exposes (instruction
clarity, schema friction, validation gaps) within existing rulings.

**Done means:** the owner (or the agent, documented step by step) has
completed one real export → computer review → import → Pending cycle, and
the friction found is fixed or filed as new stages.

**Open decisions:** any fix needing new wording or behavior stops and asks.

**Difficulty: Medium → Opus.**

---

## Phase 5 — Memory Auditor

Housekeeping for existing memories — duplicates, conflicts, outdated
records — never a second review of conversations. Row subtitle, screen
introduction, both routes, progress presentation, and import error
handling are already ruled; terminal-state wording is not.

### 5.1 Audit with the Memory Assistant model

**Goal:** The **Memory Auditor** row and screen (approved wording), and the
API route: freeze a snapshot of the associative-memory catalog, audit it in
fixed batches under the same durable foreground-service pattern as API
Memory Assistant (approved notification title **Auditing Memories**,
percentage rules as ruled), and stage every finding into
**Memories → Pending** as proposals.

**Done means:** the owner can start an audit, leave the screen, come back,
watch truthful progress, and review the findings in Pending. Nothing is
ever applied automatically.

**Open decisions:** success / no-findings / interruption / failure wording
is NOT approved — stop and ask before writing any of it.

**Difficulty: Hard → Fable** (durable background run plus proposals that
target existing records).

### 5.2 Audit with a computer AI

**Goal:** The export/import audit route, reusing Phase 4's package and
import machinery: export existing memories with read-only reference
material and the ruled instructions, import `proposals.json` through the
same validation and ledger, stage to Pending. Approved helper text and
button labels are used verbatim.

**Done means:** one real export → computer audit → import → Pending cycle
works, with the ruled error messages on bad files.

**Open decisions:** same terminal-wording stop point as 5.1.

**Difficulty: Medium → Opus.**

---

## Parked — not scheduled, not forgotten

These exist as specs or rulings but are not in the active phases. None may
be started without the owner scheduling them:

- **AMOLED / theme / palette work** — paused by owner ruling (July 26
  2026) until reinstated.
- **Broad UI redesign** (`ui-redesign-plan.md`) — separate later effort;
  Phase 2 here is reconciliation of what shipped, not the redesign.
- **Android ⇄ Windows sync** — file-based sync design is recorded in the
  superseded integration plan (D10); build only on owner request.
- **Local speech** (`whisper-local-plan.md`), **document includes**
  (`document-includes-plan.md`), **image generation rebuild**
  (`image-generation-rebuild-plan.md`), **profile images**
  (`profile-images-plan.md`) — separate feature specs, scheduled only when
  the owner says so.

## How to start anything

One line in chat, in your words: *"do 1.1"*, *"start the calculator"*,
*"skip to the computer feature."* The agent maps it to the stage, works
that one stage to its "Done means," and stops at every open decision.
