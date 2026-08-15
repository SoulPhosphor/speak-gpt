# Memory Controls and Pending UI Copy

**2026-08-04**

This focused specification accompanies `external_memory_analysis_counterplan.md`. Both documents are intended to live on `main` as the authoritative memory implementation plan. This file supplies the exact user-facing wording and layout contract for Roadmap Phases 6 through 8. If implementation order or product behavior is in question, follow the canonical plan; for the controls described here, follow this wording and layout.

## 1. Memory Controls

### Memory Types

**Section Title:** `Memory Types`

**Description:**

> Create the types used to organize memories. The Memory Assistant can suggest any type in this list.

Use Title Case for labels, buttons, and dialog titles in this section.

#### Add Type Row

The top of the section contains one compact horizontal entry row rather than a separate Add dialog:

- **Field Label:** `Type`
- an empty text field;
- **Button:** `Add`

The user enters a type name and taps **Add**. The new type then appears in the list below.

The Add button should not submit blank or whitespace-only text.

#### Type List

Directly beneath the Add Type row is a bordered, scrollable box containing the complete current Type list.

- each Type appears as an ordinary list row;
- the list scrolls when it exceeds the available height;
- tapping a Type row opens its available actions;
- row actions are `Rename` and `Delete`.

The list remains visible while the Add Type row stays above it. Adding a Type does not navigate away from Memory Controls.

#### Rename Type

Tapping **Rename** opens a small dialog:

- **Title:** `Rename Type`
- **Field Label:** `Type`
- the current name is prefilled;
- **Buttons:** `Cancel` and `Save`.

Rename uses a stable internal Type ID so every associated memory reflects the new name without rewriting each memory relationship individually.

#### Delete Type

Tapping **Delete** opens:

**Title:** `Delete This Type?`

**Body:**

> Used by {count} memories. This will remove the type from those memories. The memories will not be deleted.

Use correct singular wording for `1 memory` and plural wording for multiple memories.

**Buttons:** `Cancel` and destructive `Delete`.

Deleting a Type:

- removes only the Type assignment;
- never deletes a memory;
- leaves affected memories as `No Type`;
- refreshes their embeddings using their remaining memory text and tags.

### Importance Ratings

**Toggle Label:** `Use Importance Ratings`

**Subtext:**

> Memories can be rated from -2 to +3. Completely neutral is 0. Negative ratings reduce priority, positive ratings increase it, and +3 is always included when the memory is relevant.

**Recommended Default:** On.

There is no second toggle for default ratings and no initial AI-rating toggle.

When Off:

- importance controls are hidden;
- retrieval ignores every importance value;
- stored values remain unchanged;
- new memories store 0.

When On:

- importance controls appear in Pending, Possible Match Review, and ordinary memory editing;
- allowed values are -2, -1, 0, +1, +2, and +3;
- new memories begin at 0;
- stored ratings reappear.

**Neutral Value:** `0 · Neutral`

Values -2 through +2 are ranking preferences around neutral. `+3 · Always include` is special: after scope and semantic/lexical relevance make the memory eligible, it must be included even when doing so exceeds the normal memory-count maximum. The normal maximum is therefore a soft cap only for eligible +3 memories.

Do not invent additional semantic labels such as `Critical`, `Minor`, or `Essential` unless the owner later approves them.

Importance is considered only after scope and relevance have already made a memory eligible. Negative and positive ratings may reorder applicable memories, but no importance value can make an irrelevant or out-of-scope memory apply.

## 2. Memory Browser Tabs

The approved tab labels are:

- `General`
- `Roleplay`

The **Roleplay** tab is a grouped browser view of memories whose existing scope is:

- World;
- Roleplay Character;
- Campaign.

It is not a new generic Roleplay scope.

The underlying scope and target remain intact and visible. A Campaign memory does not become a generic Roleplay memory, and unrelated worlds, characters, or campaigns do not share retrieval context merely because they appear in the same tab.

The **General** tab contains all remaining existing scopes.

## 3. Pending Screen Structure

The previously approved controls still fit. Do not redesign their meanings or positions.

Each Pending card shows all user-relevant data that would be approved:

1. complete memory text;
2. actual scope and target;
3. selected Type or `No Type`;
4. tags or `No Tags`;
5. importance only when `Use Importance Ratings` is On;
6. the existing Information control;
7. the existing save/discard or Review controls.

No title appears.

### Scope Display

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

### Type Display

**Field Label:** `Type`

The current value is visible directly on the card and can be changed in place from the user's current Type list.

Empty value: `No Type`.

Type never determines the Roleplay tab.

### Tags Display

**Field Label:** `Tags`

Show every assigned tag directly on the card using the app's existing tag presentation.

Empty value: `No Tags`.

Editing may expand inline or open the existing compact tag editor, but assigned tags must remain visible without opening Information.

### Importance Display

Shown only while the master toggle is On.

**Field Label:** `Importance`

Default and neutral display: `0 · Neutral`.

The selected value must be visible without opening another screen. A compact dropdown is preferred over six permanently visible buttons because the Pending list is intended for scanning.

## 4. Existing Pending Actions

### No Possible Match

Keep the approved layout:

- no caution icon in the top-left;
- Information at top-right;
- full memory data in the card body;
- discard X immediately left of save/disk at bottom-right;
- save/disk at far right;
- no Review button.

Accessibility labels:

- Information: `Memory Details`
- discard X: `Discard Memory`
- save/disk: `Save Memory`

### One or More Possible Matches

Keep the approved layout:

- unlabeled caution icon at top-left;
- Information at top-right;
- full memory data in the card body;
- one labeled `Review` action at bottom-right;
- no save/disk;
- no discard X;
- the whole card is not secretly the Review control.

The caution icon's accessibility description is `Possible Match Found`.

## 5. Accept All

**Screen Action:** `Accept All`

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

## 7. Design Rule

The Pending interface is a review sheet, not a series of mystery envelopes.

Every value the user is approving must be visible while scanning. Information may explain technical or secondary details, but it must not conceal the actual memory text, scope/target, Type, tags, or enabled importance value.