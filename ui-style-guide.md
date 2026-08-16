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

## Label capitalization (owner ruling, July 29 2026)

Labels are written in Title Caps. This applies to every label-like string:
button and action labels, row and tile titles, dialog and screen titles,
section headings, toggle names, log entry titles and field labels, and
status/outcome values.

Examples: Edit Prompt, Change Settings, Image Request Completed, Provider
Request ID, Maximum Logs Saved.

Short connecting words (a, an, and, the, of, to, for, or) stay lowercase
inside a title unless they are the first word. Literal command names such as
`/imagine` keep their exact form.

Sentence case is for explanatory prose: subtitles, hints, messages, body
text, and spoken announcements — anything that explains rather than names.

When writing or proposing any new label, apply this rule. Do not carry
sentence case from drafts, examples, or upstream strings into a label.

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

The canonical palette contract is the designer's semantic zone list, recorded in `ui-redesign-plan.md` Section 4.5 (owner ruling, July 30 2026). A palette — whether a compiled preset overlay or a future user-saved custom theme — defines values for those zones. Custom user themes are a committed future goal with restart-to-apply semantics; shared styles must not close that route off.

Shared styles resolve repeated colors through theme attributes (zone attributes or mapped Material roles), never through palette-specific `@color/` values that a palette cannot override. New custom-drawn backgrounds — including the future outline-gradient and glow treatments — must read theme attributes, and exceptional visuals (bubbles, button state lists, icon tints, dialogs) get their colors from shared drawables or one shared code path, never from color-handling code copied into individual screens.

The zone attributes implemented so far, which every `ThemeOverlay.Phosphor.*` palette must define:

- `appRowTitleColor` — shared row titles and chevrons
- `appRowSubtitleColor` — shared row subtitles
- `appTextColor` — default text (dropdown/tile values, number fields, attachment names)
- `appSubtleTextColor` — muted secondary text (field hints, section explanations, size readouts)
- `appTitleTextColor` — screen and header titles, screen intro paragraphs

Every theme that defines one of these must define all of them, including the night themes and every palette overlay — a style resolving an attribute that no theme layer carries crashes at inflation. They are the pattern the remaining zones follow when theme work resumes.

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

Possible uses include Preview, Test, Learn More, or Choose Another Source when appropriate to the feature.

No distinct visual treatment for Secondary is approved yet (owner ruling, July 29 2026). Ignore that gap: reference `AppButton.Primary` directly (and its `Dialog`/`DialogAction`/`Inline` variants for sizing) for any secondary-role button. Do not invent a new appearance and do not block on the missing style. This is deliberately temporary — a distinct Secondary look can be designed and swapped in later without new decisions about which buttons are secondary, since the role is already correctly assigned by meaning.

### Destructive, cancel, or back-out action

`AppButton.Destructive`

Use for Cancel and other actions that back out of a pending operation, as well as Remove, Reset, Revert, Discard, or Delete.

The style does not authorize destructive behavior. The wording and consequence still determine whether confirmation is required.

No distinct visual treatment for Destructive is approved yet either (owner ruling, July 29 2026): it renders identically to `AppButton.Primary` — same fill, shape, and text appearance, inherited directly. It keeps its own style name rather than being replaced with direct `AppButton.Primary` references in layouts, so it can be redesigned app-wide later by changing one style instead of every layout that uses a destructive button. Changeable later; that is the point of the ruling.

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

## Selector rows and pick-list rows

### Selector row

`Widget.App.Row.Selector`

`Widget.App.Row.Selector.Label`

`Widget.App.Row.Selector.Value`

`Widget.App.Row.Selector.VoiceLanguage`

Use for a row that shows the current value of a setting and opens a picker (a dialog or another screen) when tapped. The label sits at the start (bold, `colorPrimary`); the current value sits at the end, ellipsizing with a marquee. There is no chevron — the value itself is the affordance.

Distinct from the Dropdown family, which opens an anchored inline menu in place rather than a separate picker.

The container's default background is the tonal pill (`@drawable/btn_accent_tonal`, tinted `colorSecondaryContainer`). Voice Language uses `Widget.App.Row.Selector.VoiceLanguage`: the same geometry and typography with its distinct background resolved through `colorSurfaceContainerHigh`. A screen may still override `android:background` for placement — Quick Settings keeps `@drawable/btn_accent_top` so AI Model reads as the top of that settings card.

Current examples: the AI Model row in Quick Settings, and the Voice Language row on the Voice & Speech screen. Adopting this style must never change what opens when the row is tapped, or the shape/corners of an existing background — only the label/value typography and color resolution.

### Pick-list row

`Widget.App.PickList.Row`

`TextAppearance.App.PickList.Unselected`

`TextAppearance.App.PickList.Selected`

Use for each row of a single-select list where the current selection is shown as a "checked tile" (a filled pill on the selected row, an outline-less tonal pill on the rest) — currently the Select Language dialog's list of languages. `Widget.App.PickList.Title` themes the custom dialog title without changing its geometry. `Widget.App.PickList.Row` carries only the shared geometry (height, padding, typography size) and does not force truncation; the two selection states are applied per-row by whatever code owns the rows (an adapter, or direct view lookups), since only that code knows which item is currently selected:

- unselected: `android:background = @drawable/btn_accent_tonal_selector_v3`, text appearance `TextAppearance.App.PickList.Unselected`;
- selected: `android:background = @drawable/btn_accent_tonal_selector_v4`, text appearance `TextAppearance.App.PickList.Selected`.

The selected and unselected text appearances define color only, preserving the RadioButton's existing typography. Apply them with `view.setTextAppearance(...)` rather than resolving `?attr/colorOnPrimary` (or any other Material color-role attribute) directly from `com.google.android.material.R.attr` in Kotlin — that lookup path has a known CI resolution failure in this project (see `ProfileImageGalleryAdapter.kt` / `MemoryScreenActivity.kt`). Resolving the same attribute through an XML text appearance instead avoids it entirely.

Neither background drawable needs a runtime tint: both already resolve their fill from a theme attribute (`colorSurfaceContainerHigh` / `colorPrimary`). Tinting them again in Kotlin with a hard-coded color is the mistake this family exists to prevent — it is what made the Select Language pop-up (and, separately, the still-unconverted Select AI Model list) render a fixed color instead of following the active theme/palette.

This style does not change a picker's presentation (dialog vs. full screen, search box, button layout) — only how each row's checked/unchecked state resolves its color. The Select AI Model list (`view_model.xml`) still uses its own pre-existing local version of this same pattern and has not been migrated onto this shared family — see `ui-style-adoption.md`.

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

Use:

- `Widget.App.ActionBar.SecondaryButton` for the last icon, anchored to the bar's end
- `Widget.App.ActionBar.ChainedButton` for every additional icon before it (owner approval, July 30 2026)

`ChainedButton` supplies the same geometry and background as `SecondaryButton` but bakes in no end anchor. Each instance sets `app:layout_constraintEnd_toStartOf` pointing at its right-hand neighbor, and keeps its own icon, content description, tooltip, and visibility.

Do not hand-copy the icon geometry; that is what this style exists to prevent.

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

### Bounded, internally-scrolling variant

Set `minLines`/`maxLines` and `android:scrollbars="vertical"` (plus `android:isScrollContainer="true"`, or the field never actually scrolls) on the individual `Widget.App.Field.Box` input to pin it to a fixed number of visible lines instead of letting it grow the dialog taller — the same bounded-height-scrolls-past-that idea as the Prompt Editor's `field_prompt`/`bg_prompt_editor` skin above, applied here to the standard `bg_field_box` skin instead. Current example: `dialog_edit_chat_title.xml` (ChatActivity's title-edit dialog, opened by tapping the chat header title) — a 4-line field, since an AI-generated chat title can run far longer than the header ever shows.

### Small inline number field

`Widget.App.Field.NumberBlank`

Use for a short whole-number input that sits on the same line as its label.

Set `android:ems` and `android:maxLength` on the individual field according to the digits it must accept.

## Removable form chips

`Widget.App.Chip.Removable`

Use for a selected form value that appears as a chip and can be removed
individually with its trailing X. Inflate `layout/view_app_removable_chip.xml`
through `AppRemovableChip` so programmatically generated chips keep this shared
geometry instead of copying padding and height in Kotlin.

The chip may add a leading semantic status icon when the feature requires one.
That status icon does not replace the trailing removal control.

## Prompt tabs

Used on the Edit Companion screen for the multiple-prompt variant feature. Each companion can have several named prompt variants displayed as wrapping tabs above the prompt editor. Shape and color reworked per owner design, Aug 16 2026: tabs now read as angled file tabs, and the active tab's fill matches the prompt editor frame instead of standing out as a bright accent block.

### Angled file-tab shape

`PromptTabBackground` (`org.teslasoft.assistant.ui.util`)

A plain XML `<shape>` cannot draw a non-rectangular edge, so both the inactive and active tab backgrounds are drawn by this small `Drawable` class instead of a drawable resource: vertical left edge, horizontal top/bottom edges, and a fixed-width diagonal cut on the trailing edge (narrower at the top, full width at the bottom) so every tab reads as an angled file tab regardless of its own text width. The cut width comes from `@dimen/prompt_tab_slant_width` (10dp) and the stroke width from `@dimen/prompt_tab_stroke_width` (1dp) — both shared dimens, not inline numbers. The fill and stroke colors are never hardcoded in the drawable itself; the caller resolves them from theme attributes (`colorOutline`, `colorSurfaceContainerHigh`) and passes them in, so the shape stays theme/palette-ready. Corners are sharp by design (no rounding), matching the requested "edgy" look.

### Inactive prompt tab

`Widget.App.PromptTab`

36dp tall, 14dp horizontal padding, 14sp `appTextColor` text, 160dp max width then ellipsizes. Background: `PromptTabBackground` with a transparent fill and `colorOutline` stroke.

### Active prompt tab

`Widget.App.PromptTab.Active`

Inherits all sizing from the inactive tab. Background: `PromptTabBackground` with a `colorSurfaceContainerHigh` fill (the same tone as `bg_prompt_editor`, so the selected tab visually continues into the prompt box beneath it) and a `colorOutline` stroke. Text is `appTitleTextColor` and bold — the bright/bold text carries the "this one's selected" signal now that the fill is a muted surface tone rather than a bright accent color.

### Add-tab button

`Widget.App.PromptTab.Add`

Fixed 36x36dp square, centered "+" label. Outlined box (`bg_prompt_tab` drawable, plain rectangle) — intentionally kept as a plain square rather than the angled tab shape, since it's a small icon control and not a labeled file tab. Always placed at the end of the wrapping row.

### Prompt tab row

A `ChipGroup` with `singleLine="false"` and `chipSpacingHorizontal/Vertical="10dp"`. The tabs are plain `TextView` views constructed in code with the style passed as the `defStyleRes` constructor argument (`TextView(context, null, 0, styleRes)`), not `setTextAppearance` — `setTextAppearance` only applies text color/size/weight and was silently dropping the style's padding, gravity, maxWidth, maxLines, ellipsize, clickable and focusable. The `ChipGroup` provides only the wrapping-row layout; it does not use Material `Chip` widgets. Do not duplicate style properties (dimensions, padding, gravity, maxLines, ellipsize, maxWidth) in Kotlin; the styles own those values, and the background/colors are resolved once per render pass and handed to `PromptTabBackground`.

### Prompt editor frame

A `ConstraintLayout` containing:

1. **Tab name** — a `TextView` showing the active variant's name, left-aligned. The default prompt's name is prefixed with a green dot (`light_green`).
2. **Three-dot menu** — an `ImageButton` (36x36dp, `ic_more_vert`) anchored to the trailing edge, opening a `PopupMenu` with: Make Default, Rename, Copy From…, Duplicate, Clear, Delete.
3. **Text field** — the `field_prompt` `TextInputEditText`, `minLines="8"` and `maxLines="8"` with `scrollbars="vertical"`, transparent background, bordered by `bg_prompt_editor`: a `colorSurfaceContainerHigh` fill (matching the active tab) with a 1dp `colorOutline` stroke and 4dp corners. The bounded height makes the field scroll internally when content overflows.

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

### Canonical dropdown control

`Widget.App.Dropdown.CanonicalLabel`

`Widget.App.Dropdown.CanonicalValue`

Every dropdown in the app uses this one visual family. A screen may arrange the
control differently when its context requires it, but it must not fork the
dropdown's border, background, height, typography, spacing, chevron, open-state
geometry, disabled treatment, or interaction feedback.

`Widget.App.Dropdown.CanonicalValue` is the closed control and tap target. It uses the
shared dropdown background drawable and opens the shared anchored dropdown
menu behavior. `Widget.App.Dropdown.CanonicalLabel` is the optional field label.

Migration is deliberately screen-by-screen. The older
`Widget.App.Dropdown.Label` and `Widget.App.Dropdown.Value` names temporarily
preserve the existing appearance on screens that have not yet been reviewed.
Do not use those legacy styles for new work. Once every dropdown has been
reviewed and migrated, remove the legacy definitions and give the canonical
styles the short names.

#### Closed control

- Draw one static, faint 1dp border around the complete control, including the
  value and chevron. The border color must come from the canonical dropdown
  theme role; never hardcode a light/dark color in a layout or screen.
- Use the app's default background color through a theme role. All dropdowns
  use the same background; do not derive it from the surrounding card, row, or
  tile.
- Use one shared height everywhere.
- Left-align the value with deliberate internal breathing room on the left.
  Reserve separate space on the right for the chevron so text can never overlap
  it.
- Use a downward-facing V chevron. Do not use a filled triangle or the Material
  `arrow_drop_down` glyph.
- Provide no ripple, pressed color, row highlight, selection flash, or other
  touch feedback.
- In a labeled row, let the label use its natural width, leave the shared gap,
  and make the control fill the remaining row width up to the trailing edit
  action or the row's proper outer edge. This gives every option a stable,
  predictable text area. Memory Backup & Repair's Backup Style, Format, and
  Backup Frequency use this placement.
- Only a standalone dropdown with no label sizes itself to the longest available
  option plus the shared internal padding and chevron space. Cap that measured
  width at the available screen width and ellipsize an option that cannot fit.
- Show the actual current value. When the field has a default, show that default
  from the beginning. Never invent a placeholder in place of a default.
- Use `Select` only when a single-choice field is genuinely neutral until the
  user chooses an option. A multi-select dropdown may keep `Select` as its
  permanent closed-control text.

#### Placement

- When a label is present, keep the label and dropdown on the same line. Align
  the label to the left, leave the shared gap after it, and fill the rest of the
  line with the dropdown up to a trailing edit action or the proper right edge.
- When no label belongs on the line, center the correctly measured dropdown.
  Choose Provider is an example of this standalone arrangement.
- A standalone dropdown may also sit inside a plain dialog's custom view when the
  choice is dialog-scoped rather than screen-scoped — Quick Settings' required
  companion-recovery picker (shown when the chat's active companion no longer
  exists) is the current example: a `Widget.App.Dropdown.CanonicalValue` control
  sized with `AppDropdown.sizeToOptions`, opened with `AppDropdown.show`, no
  dropdown label since the dialog's title supplies the context.
- A form screen may explicitly approve a full-width stacked-field dropdown.
  Keep its existing label and help text above the control, then fill the form's
  content column between its standard left and right margins. Edit Companion's
  Activation Prompt and Core Lorebook fields, plus Choose Provider's Routing
  Type and Choose Model fields, and Memory Backup & Repair's Recovery Type and
  Database Type to Restore fields are canonical examples. This is a placement
  exception only, not permission to make unrelated dropdowns full width.
- A managed stacked field may place its dropdown on the line beneath its title
  and help text, filling the space up to a trailing management icon. The
  dropdown changes the selection in place; the icon alone opens the selected
  item's editor or the broader management picker. Memory Assistant Advanced
  Settings uses this arrangement for Endpoint and Model.
- Layout containers may differ to support a trailing edit action or other
  approved screen structure. Those differences are placement only; the control
  must still inherit the canonical dropdown appearance and behavior.
- The profile-image gallery filter is a compact, fixed-width exception. Use
  `Widget.App.Dropdown.GalleryFilter` so its floating `Filter` label may remain
  inside the outline while its faint 1dp box, background, text, V chevron, and
  disabled colors use the canonical dropdown theme roles. Preserve the
  gallery's approved width; do not expand this filter to fill its row. The same
  shared gallery layout serves Default AI Avatar, Default Personal Avatar, and
  Avatar Image Gallery, so this variant must not be copied into separate
  screen-specific styles.

#### Open control

The anchor and its option list read as one continuous outlined rectangle:

- while open, remove the anchor's bottom stroke and bottom corner rounding;
- attach the option list directly beneath the anchor with no visual gap;
- continue the same 1dp border down the menu's left and right sides;
- draw the bottom stroke and bottom corners only beneath the final option;
- do not draw borders or divider lines between individual options;
- give options the same background as the closed control;
- keep the currently selected value in the anchor as the top option, render it
  with slightly bolder text and no background highlight, and do not repeat it
  in the attached option list or treat it as a field label;
- keep every option on one line; ellipsize only when the available row width is
  genuinely too narrow;
- provide no ripple, pressed color, highlight flash, or other touch feedback on
  menu options.

#### Disabled control

Keep the same size, border shape, and background so disabling a field never
shifts the layout. Mute the value text, chevron, and border through canonical
disabled theme roles. A disabled control is not clickable and provides no touch
feedback.

#### Theme contract

Dropdown border, background, value/chevron, label, and disabled colors are
semantic theme roles. Every base theme and every palette overlay must define
them. Dropdown drawables and styles resolve only those roles; layouts and Kotlin
must not supply local dropdown colors. This is what allows a palette to restyle
every closed dropdown and open menu without editing individual screens.

### Summoning Circle placement

`Widget.App.QuickTile.Label`

`Widget.App.QuickTile.Value`

`Widget.App.QuickTile.EditButton`

The Summoning Circle has an approved separate edit button that opens the manager
for that category. Its label, dropdown, and edit button therefore need local
layout constraints, but this is not a separate dropdown design.

`Widget.App.QuickTile.Label` and `Widget.App.QuickTile.Value` must inherit the
canonical `Widget.App.Dropdown` label and value appearance. They may contain
only placement differences required by the edit-button column. Never duplicate
or override dropdown colors, border, background, height, internal padding,
chevron, sizing rules, open-state behavior, disabled treatment, or touch
feedback in the QuickTile family.

## Provider chart

`Widget.App.Chart.Row`

`Widget.App.Chart.HeaderCell`

`Widget.App.Chart.Cell`

Use for the horizontally scrollable provider table on the Choose Provider screen (and any future tabular data chart).

Composition:

1. a `HorizontalScrollView` holding a vertical `LinearLayout`;
2. one `Chart.Row` of `Chart.HeaderCell` views for the column labels;
3. one `Chart.Row` per data row of `Chart.Cell` views, built in code.

The header and data rows must take their cell widths from one shared column table in the owning activity so the columns stay aligned. Values render in the default text color (`appTextColor`); unknown values render as `?`. The chart is theme-ready: all colors resolve through theme attributes, so per-column value colors can be added later by extending the cell styles, not by hardcoding colors in code.

The Ignore control at a row's end uses `bg_ignore_square_off` / `bg_ignore_square_on` with `ic_ignore_x`, tinted at runtime via `appSubtleTextColor` (unmarked) and `colorError`/`colorOnError` (marked).

## Plain checkbox option row

`Widget.App.CheckOption.Row`

`Widget.App.CheckOption.Label`

Use for a checkbox option where the whole line is the tap target but must read as a normal line of text — no background, no tile or button look (owner spec, Aug 2 2026; first use: the provider Filters panel's capability checkboxes).

Distinct from `Widget.App.Row.Toggle`, which is a switch row with a subtitle.

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

The composer strip contains unsent attachments only. Its action is a direct X
whose only action is Remove. Sent attachments move to
`view_include_summary_item.xml`, where the three-dots menu exposes post-send
actions. Do not offer Condense, Reduce to Text Only, or Edit in the composer.

When at least one pending item is a document, the strip shows the persistent
document-cost helper above the rows. It uses `Widget.App.Include.Notice` with
the strip's 12dp leading inset; image-only pending state does not show it.

Use `Widget.App.Include.Notice` for persistent explanatory or size-warning text beneath the row.

Shared layouts:

- `layout/view_include_row.xml`
- `layout/view_include_collapsed.xml`
- `layout/view_include_summary.xml`
- `layout/view_include_summary_item.xml`
- `layout/dialog_include_condense_hint.xml`
- `layout/dialog_include_condense_progress.xml`

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
