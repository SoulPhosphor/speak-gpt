# Memory Controls and Pending UI Copy

**2026-08-04**

This focused specification accompanies `external_memory_analysis_counterplan.md` on branch `codex/memory-plan-truth-repair`. It pins down the user-facing wording and confirms how the revised memory data fits the already-approved Pending and Possible Match controls.

## 1. Memory Controls

### Memory Types

**Section title:** `Memory Types`

**Description:**

> Create the categories used to organize memories. The Memory Assistant can suggest any type in this list.

The section shows the complete current list as ordinary rows.

**Primary action:** `Add type`

Each row allows:

- `Rename`
- `Delete`

#### Add dialog

**Title:** `Add type`

**Field label:** `Type name`

**Buttons:** `Cancel` and `Add`

#### Rename dialog

**Title:** `Rename type`

**Field label:** `Type name`

**Buttons:** `Cancel` and `Save`

#### Delete dialog

**Title:** `Delete this type?`

**Body:**

> This will remove “{type name}” from {count} associated memories. The memories will not be deleted.

Use correct singular/plural wording for `1 memory` and multiple `memories`.

**Buttons:** `Cancel` and destructive `Delete`

Deleting a Type removes only the assignment. Affected memories become `No Type`.

### Importance Ratings

**Toggle label:** `Use importance ratings`

**Summary:**

> Rate memories from 0 to 5. Ratings only reorder memories that are already relevant and are preserved when turned off.

**Recommended default:** Off.

There is no second toggle for default ratings and no initial AI-rating toggle.

When Off:

- importance controls are hidden;
- retrieval ignores every importance value;
- stored values remain unchanged;
- new memories store 0.

When On:

- importance controls appear in Pending, Possible Match Review, and ordinary memory editing;
- allowed values are 0 through 5;
- new memories begin at 0;
- stored ratings reappear.

**Neutral value copy:** `0 · Not rated`

Values 1 through 5 are displayed as numbers. Do not invent semantic labels such as `critical`, `minor`, or `essential` unless the owner later approves them.

## 2. Memory Browser tabs

**Approved tab:** `Roleplay`

The Roleplay tab is a grouped browser view of memories whose existing scope is:

- World;
- Roleplay Character;
- Campaign.

It is not a new generic Roleplay scope.

The underlying scope and target remain intact and visible. A Campaign memory does not become a generic Roleplay memory, and unrelated worlds, characters, or campaigns do not share retrieval context merely because they appear in the same tab.

**Non-roleplay tab label remains unresolved.**

Recommended wording options, in order:

1. `General`, clearest and least likely to exclude a valid existing scope;
2. `Everyday`, warmer but potentially less accurate for projects and companion memories;
3. `Main`, neutral but less descriptive.

Do not lock one into code until the owner selects it.

## 3. Pending screen structure

The previously approved controls still fit. Do not redesign their meanings or positions.

Each Pending card shows all user-relevant data that would be approved:

1. complete memory text;
2. actual scope and target;
3. selected Type or `No Type`;
4. tags or `No tags`;
5. importance only when `Use importance ratings` is On;
6. the existing Information control;
7. the existing save/discard or Review controls.

No title appears.

### Scope display

The tab communicates only the broad grouping. The card must still show the actual scope and target in human-readable form.

Examples:

- `World · The Glass Expanse`
- `Roleplay Character · Mara Venn`
- `Campaign · Ashes of the North`
- `Project · Soul Phosphor Website`
- `Companion · Slate`
- `Real Life`
- `Global`

Do not show raw keys such as `rp_character` to the user.

Scope and target are visible directly on the card. If they need correction, the row may open the existing selector, but the current value must never be hidden behind Information or a separate edit screen.

### Type display

**Field label:** `Type`

The current value is visible directly on the card and can be changed in place from the user's current Type list.

Empty value: `No Type`

Type never determines the Roleplay tab.

### Tags display

**Field label:** `Tags`

Show every assigned tag directly on the card using the app's existing tag presentation.

Empty value: `No tags`

Editing may expand inline or open the existing compact tag editor, but assigned tags must remain visible without opening Information.

### Importance display

Shown only while the master toggle is On.

**Field label:** `Importance`

Default and neutral display: `0 · Not rated`

The selected value must be visible without opening another screen. A compact dropdown is preferred over six permanently visible buttons because the Pending list is intended for scanning.

## 4. Existing Pending actions

### No Possible Match

Keep the approved layout:

- no caution icon in the top-left;
- Information at top-right;
- full memory data in the card body;
- discard X immediately left of save/disk at bottom-right;
- save/disk at far right;
- no Review button.

Accessibility labels:

- Information: `Memory details`
- discard X: `Discard memory`
- save/disk: `Save memory`

### One or more Possible Matches

Keep the approved layout:

- unlabeled caution icon at top-left;
- Information at top-right;
- full memory data in the card body;
- one labeled `Review` action at bottom-right;
- no save/disk;
- no discard X;
- the whole card is not secretly the Review control.

The caution icon's accessibility description is `Possible match found`.

## 5. Accept All

**Screen action:** `Accept All`

Accept All applies only to ordinary, conflict-free proposals currently visible in the selected tab.

It must not approve:

- a proposal with one or more Possible Matches;
- a proposal with invalid or unresolved required placement;
- hidden data the user could not scan on the card.

Generated memories begin at importance 0, so enabling importance does not require trusting an AI-selected rating before using Accept All.

A wrong Type is correctable directly on the card and does not alter the memory's truth, authority, scope, or Roleplay grouping.

## 6. Possible Match Review

The existing full-page Review design still fits the revised memory model.

The proposed memory appears first and shows:

- complete memory text;
- actual scope and target;
- editable Type;
- editable tags;
- editable importance only when enabled;
- Information at top-right;
- no checkbox;
- no title.

Existing matches appear below with the same visible data, checkbox at top-left, and Information at top-right.

Keep the approved resolution order:

1. `Save & Edit Old Memory`
2. `Save & Supersede`
3. `Save & Replace`

No new resolution button is required by user-owned Types, tags, scope-derived Roleplay grouping, or optional importance.

## 7. Design rule

The Pending interface is a review sheet, not a series of mystery envelopes.

Every value the user is approving must be visible while scanning. Information may explain technical or secondary details, but it must not conceal the actual memory text, scope/target, Type, tags, or enabled importance value.