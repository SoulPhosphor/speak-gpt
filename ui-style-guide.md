# UI Style Guide

This file is the usage map for the app's shared visual system.

The actual style values live in `app/src/main/res/values/themes.xml`. This guide explains which shared style or shared layout to use and when to use it. Conversion status belongs in `ui-style-adoption.md`. Git history preserves rollout history and old fixes.

## AMOLED / theme work is paused

The owner has paused AMOLED and palette/theme work (ruling, July 26 2026) until they reinstate it. Do not add, extend, fix, or polish AMOLED-specific styling anywhere — new screens or existing ones — until the owner says otherwise. Do not delete or break the AMOLED code already in place; just stop spending further effort on it.

## Terms

### Shared style

A shared style is the CSS-like layer. It controls repeated visual properties such as color, typography, shape, size, spacing, and component geometry.

Examples: `AppButton.Primary`, `Widget.App.Field.Label`, and `Widget.App.ActionBar.Title`.

### Shared layout or scaffold

A shared layout or scaffold is a reusable XML template that supplies an arrangement of views for more than one screen.

Changing it may change every screen that uses the template. Before editing one, identify all current users and explain the visible effect on each.

### Shared behavior

Shared behavior is reusable Kotlin or another shared code path used by more than one screen. It is not the same as a shared visual style or shared layout.

### Do not say only "the screen is shared"

State exactly what is shared:

- a visual style;
- an XML layout or scaffold;
- behavior;
- data;
- or some combination.

A screen may use shared styles while retaining its own layout. It may also use a shared layout while adding local controls or behavior.

## Core rule

Reuse shared visual rules for repeated components. Do not force screens to have identical structure when the approved product needs differ.

Before creating or changing UI:

1. inspect the target screen;
2. check this guide and `themes.xml` for an existing style family;
3. check `ui-style-adoption.md` before treating another screen as a reference;
4. use the existing family when it matches;
5. ask the owner before inventing a new shared pattern or changing an existing one.

Do not hardcode repeated colors, sizes, typography, shapes, spacing, or geometry in Kotlin or XML when a shared resource should control them.

If an existing shared style cannot represent the approved design, stop before copying attributes. Explain the missing shared variant and obtain approval for the shared solution.

### Reuse supports the product

A shared layout is not a reason to reject a needed control or force it onto every related screen.

When one screen needs an element that the others do not:

- keep it local while using shared visual styles when it is genuinely unique;
- add an optional slot or approved variant when the pattern is reusable but not universal;
- extend the shared layout only when every user should receive the change;
- split the screen into its own layout when its structure has genuinely diverged.

Do not make an entire screen or behavior shared merely because it is new. Share stable repeated patterns.

## Theme and palette contract

Shared styles should resolve colors through theme roles or shared color resources rather than screen-specific values.

Every `ThemeOverlay.Phosphor.*` palette must define:

- `appRowTitleColor`
- `appRowSubtitleColor`

These attributes supply the shared row title, subtitle, and chevron colors.

A change to a shared style or shared layout may alter every screen using it. Treat that as an app-wide visual decision, not a local cleanup.

Legacy per-screen AMOLED recoloring is not part of the future theme system. Its current status is recorded in `ui-style-adoption.md`.

## Buttons

### Button meaning and button size are separate

Choose the semantic role first:

- primary;
- secondary;
- destructive.

Then use the size or placement variant required by the screen:

- ordinary screen or section button;
- inline button sized to its label;
- single dialog action;
- two-button dialog action row.

A button does not become secondary or destructive because it is shorter, narrower, beside another button, or inside a dialog. Size variants must inherit the semantic style.

Do not create a new appearance merely to obtain a different button width or length.

### Default button

`App.Button`

Use as the app-wide default `MaterialButton` appearance when no explicit semantic button style is assigned. It supplies the shared semi-square button shape.

New UI should prefer a named semantic style when the role is known.

### Primary action

`AppButton.Primary`

Use for the main affirmative or committing action on a screen, dialog, or section, such as Save, Import, Export, Continue, Create, or Confirm.

A group should normally have one clearly primary action.

### Secondary action

`AppButton.Secondary`

Use for a neutral alternative action that is not the main commitment and is not Cancel, Discard, Remove, Reset, Revert, or Delete.

The owner has not approved a distinct visual treatment for secondary buttons. Do not invent one. The current implementation may resemble another role until reviewed separately.

Possible uses include Preview, Test, Learn More, or Choose Another Source when appropriate to the feature.

### Destructive, cancel, or back-out action

`AppButton.Destructive`

Use for Cancel and other actions that back out of a pending operation, as well as Remove, Reset, Revert, Discard, or Delete.

The style does not authorize destructive behavior. The wording and consequence still determine whether confirmation is required.

### Single dialog action

`AppButton.Primary.Dialog`

Use for one centered filled primary action inside a custom dialog.

Required shared layout: `layout/dialog_single_action.xml`.

This style requires a `ConstraintLayout` parent because its width is percentage-based.

### Two dialog actions

`AppButton.Primary.DialogAction`

`AppButton.Destructive.DialogAction`

Use for the established two-button confirmation shape with the primary action first and the destructive or cancel action second.

Required shared layout: `layout/dialog_two_actions.xml`.

### Inline actions

`AppButton.Primary.Inline`

`AppButton.Destructive.Inline`

Use when actions should size to their labels rather than fill the available width.

For the established right-aligned Cancel-then-Save dialog row, use `layout/dialog_two_actions_end.xml` with these inline styles.

A future inline secondary button should inherit `AppButton.Secondary` and change only its geometry.

## Dialogs

### Standard dialog theme

`App.MaterialAlertDialog`

Use for every `MaterialAlertDialogBuilder` unless an approved feature-specific dialog requires a different theme.

This theme supplies the standard dialog appearance and centers dialog titles.

### Title and explanatory text

Use `setTitle` for the dialog heading or its single short question.

Use `setMessage` only for separate explanatory text beneath the title.

A dialog containing only a short question should place that question in the title and omit the message.

### Standard discard-changes dialog

Use `DiscardChangesDialog.show(context) { onDiscard }` for a full-screen editor with unsaved changes.

Do not rebuild or reword this dialog at individual call sites.

### Ordinary text-button dialogs

Ordinary Yes/No or similar dialogs may use the text action buttons supplied by `App.MaterialAlertDialog`.

Use the custom button layouts above when the approved design calls for filled, outlined, or specially arranged actions.

## Navigation and settings rows

A navigation row is assembled from shared pieces. Do not copy a completed row's raw XML into another screen.

### Title-only navigation row

Use these pieces in order:

1. `Widget.App.Row.TitleOnly`
2. optional `Widget.App.Row.Icon` or `Widget.App.Row.ProfileImage`
3. `Widget.App.Row.TextColumn`
4. `Widget.App.Row.Title`
5. `Widget.App.Row.Chevron`

Use when the row opens another screen and needs no explanatory subtitle.

### Navigation row with subtitle

Use these pieces in order:

1. `Widget.App.Row.WithSubtitle`
2. optional `Widget.App.Row.Icon` or `Widget.App.Row.ProfileImage`
3. `Widget.App.Row.TextColumn`
4. `Widget.App.Row.Title`
5. `Widget.App.Row.Subtitle`
6. `Widget.App.Row.Chevron`

Use when the row opens another screen and the subtitle helps explain the destination or current state.

`Widget.App.Row.Subtitle` is one line with end ellipsis by default. Override the line count only when approved content genuinely needs more room.

### Leading image choices

`Widget.App.Row.Icon`

Use for a normal leading glyph or small image. The style does not apply a tint; set tint on the individual vector icon when needed.

`Widget.App.Row.ProfileImage`

Use for a larger identity or profile picture in the same leading slot.

### Toggle row

Use these pieces in order:

1. `Widget.App.Row.Toggle`
2. `Widget.App.Row.TextColumn`
3. `Widget.App.Row.Title`
4. `Widget.App.Row.Subtitle`
5. `Widget.App.Row.Switch`

Use for a setting that changes a Boolean value directly instead of navigating to another screen.

A toggle row has no chevron. The row container is not the tap target; the switch is.

A toggle may exist on only one screen. Its use of shared row and switch styling does not require adding the toggle to other screens.

## Screen headers

### Header container

`Widget.App.ActionBar`

Use as the shared visual container for a full-screen activity or panel header.

Using this style does not require several screens to share the same XML layout.

### Simple screen header

Use:

- `Widget.App.ActionBar.BackButton`
- `Widget.App.ActionBar.Title`

Use when the header contains only a back button and centered title.

The back button style expects the view id `btn_back`.

### Header with one trailing action icon

Use:

- `Widget.App.ActionBar.BackButton`
- `Widget.App.ActionBar.Title.NearBack`
- `Widget.App.ActionBar.SecondaryButton`

Use when the header contains one trailing Save, Delete, Help, Debug, Edit, or similar icon action.

`Title.NearBack` is left-aligned after the back button and ellipsizes before the trailing icon. The layout must set the title's end constraint to that icon.

`Widget.App.ActionBar.SecondaryButton` is a positional header-icon style. It is unrelated to the semantic `AppButton.Secondary` role.

### Header with two or more trailing action icons

No complete approved shared pattern currently covers additional chained icons. `Widget.App.ActionBar.SecondaryButton` directly covers only the end-anchored icon.

Do not copy its geometry into the additional icon and call the header converted. Present the missing shared variant or shared header-layout decision for owner approval.

Until that gap is resolved, a screen with locally repeated additional-icon geometry is Partial in `ui-style-adoption.md`.

### Close-panel header

Use:

- `Widget.App.ActionBar.Title.LeftAligned`
- `Widget.App.ActionBar.CloseButton`

Use for a slide-out or modal-style panel that closes with an X rather than navigating back.

The close button must use the id `btn_close` because the title style constrains itself to that id.

## Form fields

### Standard label-above-box field

Use these pieces in order:

1. `Widget.App.Field.Label`
2. optional `Widget.App.Field.Hint`
3. `Widget.App.Field.Box`

Use for editable text fields with a visible label above the input.

Do not replace the separate label with a floating `TextInputLayout` hint when using this pattern.

Keep field-specific behavior on the individual input, including:

- `inputType`;
- line count;
- gravity;
- character limits;
- spacing unique to that field.

### Small inline number field

`Widget.App.Field.NumberBlank`

Use for a short whole-number input that sits on the same line as its label.

Set `android:ems` and `android:maxLength` on the individual field according to the digits it must accept.

## Screen sections

### Section title

`Widget.App.Section.Title`

Use for the heading of a settings-style screen section.

### Section explanation

`Widget.App.Section.Hint`

Use for plain-language explanation or warnings belonging to that section.

The required order is:

1. section title;
2. all section explanation or warning text;
3. the section's controls.

The user must receive the explanation before reaching the control that depends on it. Do not place explanatory text beneath the button, switch, field, or other control it explains.

## Screen intro text

### Standalone top-of-screen paragraph

`Widget.App.Screen.Intro`

Use for a plain explanatory paragraph at the top of a screen that has no title of its own above it. 14sp, regular weight, full-strength `text_title` color.

Distinct from `Widget.App.Section.Title`/`Widget.App.Section.Hint`, which are a heading-plus-explanation pair belonging to one section within a screen, and from `Widget.App.Row.Subtitle`, which is muted 13sp text describing a single row. Use `Screen.Intro` only when the text is not paired with a heading directly above it.

## Multiple-choice dropdowns

### Standard dropdown field

`Widget.App.Dropdown.Label`

`Widget.App.Dropdown.Value`

Use for a normal multiple-choice field where a label and current selection appear on the same line.

The value is the tap target and should open an anchored `ListPopupWindow`. This pattern has no separate edit button.

### Summoning Circle quick tiles

`Widget.App.QuickTile.Label`

`Widget.App.QuickTile.Value`

`Widget.App.QuickTile.EditButton`

Use only for the established Summoning Circle tile pattern: category label, current selection, and a separate edit button that opens the manager for that category.

Do not use the QuickTile family as the general app-wide dropdown pattern. General multiple-choice fields use `Widget.App.Dropdown.*`.

## Attached-document strip

`Widget.App.Include.Container`

Use for the attachment tile above the chat message box. Its
`bg_attachment_tile` background uses the canonical
`@dimen/button_corner_radius` (4dp), matching the app's semi-square buttons.
Use the same background for the pending image preview.

Use these pieces in order for each attachment shown above the chat message box:

1. `Widget.App.Include.Row`
2. `Widget.App.Include.Label`
3. `Widget.App.Include.Icon`
4. `Widget.App.Include.Name`
5. `Widget.App.Include.Weight`
6. `Widget.App.Include.Action`

The action slot is state-specific: an unsent attachment uses a direct X whose
only action is Remove; a sent attachment may use the three-dots menu for its
available post-send actions. Do not offer Condense, Reduce to Text Only, or
Edit for an unsent attachment.

Use `Widget.App.Include.Notice` for persistent explanatory or size-warning text beneath the row.

Shared layouts:

- `layout/view_include_row.xml`
- `layout/view_include_collapsed.xml`
- `layout/view_include_summary.xml`
- `layout/view_include_summary_item.xml`

Do not assign an id to an XML `<include>` tag that includes these layouts. Android replaces the included root id with the `<include>` id, which breaks code expecting the root's original id.

## Maintaining this guide

Keep this file as a current reference, not a development log.

For each style family, document only:

- the exact style name;
- what visual or structural role it controls;
- when to use it;
- required composition or parent-layout constraints;
- at most one useful current example when the pattern would otherwise be unclear.

Do not add:

- rollout histories;
- dated corrections;
- old bugs;
- lists of converted or unconverted screens;
- superseded decisions;
- branch names or commit narratives;
- feature behavior unrelated to choosing and composing the style.

When this guide and current style definitions disagree, inspect `themes.xml`, verify the intended behavior, and correct the stale documentation before using it as authority.
