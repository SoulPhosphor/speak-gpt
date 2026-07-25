# UI Style Guide

This file is the usage map for the app's shared visual system.

The actual style values live in `app/src/main/res/values/themes.xml`. This guide explains which shared style to use and when to use it. Git history preserves rollout history and old fixes; they do not belong here.

## Core rule

Reuse an existing shared style or shared layout instead of copying visual attributes into a new layout.

Repeated styles are architecture. They allow buttons, rows, headers, fields, dialogs, and other components to change together when the app gains new themes or palettes.

Before creating a new visual pattern:

1. inspect the comparable current screens;
2. check this guide and `themes.xml` for an existing style family;
3. use the existing family when it matches;
4. ask the owner before inventing a new shared pattern or changing an existing one.

Do not hardcode repeated colors, sizes, typography, shapes, or spacing in Kotlin when a shared resource can control them.

## Theme and palette contract

Shared styles should resolve colors through theme roles or shared color resources rather than screen-specific values.

Every `ThemeOverlay.Phosphor.*` palette must define:

- `appRowTitleColor`
- `appRowSubtitleColor`

These attributes supply the shared row title, subtitle, and chevron colors.

A change to a shared style may alter every screen using it. Treat that as an app-wide visual decision, not a local cleanup.

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

A button does not become secondary or destructive because it is shorter, narrower, placed beside another button, or shown in a dialog. Size variants must inherit the semantic style so the same role remains visually consistent throughout the app.

Do not create a new appearance merely to obtain a different button width or length.

### Default button

`App.Button`

Use as the app-wide default `MaterialButton` appearance when no explicit semantic button style is assigned. It supplies the shared semi-square button shape.

New UI should prefer the named semantic styles below when the button's role is known.

### Primary action

`AppButton.Primary`

Use for the main affirmative or committing action on a screen, dialog, or section, such as Save, Import, Export, Continue, Create, or Confirm.

A group should normally have one clearly primary action.

### Secondary action

`AppButton.Secondary`

Keep this semantic role available for a neutral alternative action that is not the main commitment and is not a cancel, discard, remove, reset, revert, or delete action.

The owner has not approved a distinct visual treatment for secondary buttons. Do not invent one. The current implementation may resemble another button role until its appearance is reviewed separately.

Possible uses include Preview, Test, Learn More, Choose Another Source, or another neutral alternate path when appropriate to the feature.

### Destructive, cancel, or back-out action

`AppButton.Destructive`

Use for Cancel and other actions that back out of a pending operation, as well as Remove, Reset, Revert, Discard, or Delete.

The exact wording and consequence still determine whether confirmation is required. The style alone does not authorize destructive behavior.

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

Example: the standard discard-changes dialog.

### Inline actions

`AppButton.Primary.Inline`

`AppButton.Destructive.Inline`

Use when actions should size to their labels rather than fill the available width.

For the established right-aligned Cancel-then-Save dialog row, use `layout/dialog_two_actions_end.xml` with these inline styles.

A future inline secondary button should inherit `AppButton.Secondary` and change only its geometry. Do not give it a new visual identity merely because it is inline.

## Dialogs

### Standard dialog theme

`App.MaterialAlertDialog`

Use for every `MaterialAlertDialogBuilder` unless an approved feature-specific dialog requires a different theme.

This theme supplies the standard dialog appearance and centers dialog titles.

### Title and explanatory text

Use `setTitle` for the dialog's heading or its single short question.

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

`Widget.App.Row.Subtitle` is one line with end ellipsis by default. Override the line count only when the approved content genuinely needs more room.

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

## Screen headers

### Header container

`Widget.App.ActionBar`

Use as the shared container for a full-screen activity or panel header.

### Simple screen header

Use:

- `Widget.App.ActionBar.BackButton`
- `Widget.App.ActionBar.Title`

Use when the header contains only a back button and title. The title is centered.

The back button style expects the view id `btn_back`.

### Header with trailing action icons

Use:

- `Widget.App.ActionBar.BackButton`
- `Widget.App.ActionBar.Title.NearBack`
- one or more `Widget.App.ActionBar.SecondaryButton` views

Use when the header contains Save, Delete, Help, Debug, Edit, or another trailing icon action.

`Title.NearBack` is left-aligned after the back button and ellipsizes before the first trailing icon. Each layout must set its end constraint to the trailing icon nearest the title.

Use `SecondaryButton` for the icon anchored to the end of the header. Additional icons may chain before it while retaining the same shared geometry.

`Widget.App.ActionBar.SecondaryButton` is a positional header-icon style. It is not the same concept as the semantic `AppButton.Secondary` action role.

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

- `inputType`
- line count
- gravity
- character limits
- any extra spacing unique to that field

### Small inline number field

`Widget.App.Field.NumberBlank`

Use for a short whole-number input that sits on the same line as its label.

Set `android:ems` and `android:maxLength` on the individual field according to the number of digits it must accept.

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

Use these pieces in order for each attachment shown above the chat message box:

1. `Widget.App.Include.Row`
2. `Widget.App.Include.Icon`
3. `Widget.App.Include.Label`
4. `Widget.App.Include.Name`
5. `Widget.App.Include.Weight`
6. `Widget.App.Include.Menu`

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
- lists of every converted or unconverted screen;
- superseded decisions;
- branch names or commit narratives;
- feature behavior unrelated to choosing and composing the style.

When the guide and current style definitions disagree, inspect `themes.xml`, verify the intended current behavior, and correct the stale documentation before using it as authority.