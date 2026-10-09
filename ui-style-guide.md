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
status/outcome values, plus accessibility and control-naming strings such as
`contentDescription` values, tooltip text, and similar labels when they name a
control or action.

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

Full-screen settings activities color their window and `Widget.App.ActionBar` header by calling `ScreenChrome.apply(activity, actionBar, backButton, ...headerButtons)` (`org.teslasoft.assistant.ui.util`); trailing header icons such as Save are passed after the back button. It is the one place those colors are set, so moving them onto theme attributes later is a change to that file alone. Do not copy `SurfaceColors` window/header code into a screen. Current users: Appearance, Name Style, Chat Behavior, and Summarizer Prompts; the other settings screens still carry their own copy until they are moved over.

### Icons have no background of their own (owner ruling, Oct 9 2026)

An icon or icon button shows only the icon: no filled shape, tonal circle,
or colored background behind it, unless the owner has specified one for that
control. The touch ripple is allowed. Use a borderless icon style such as
`Widget.App.QuickTile.EditButton`. Existing icons that already have a
background are not to be changed on sight; each needs the owner's decision.

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

Button text is always centered within the button, whatever the button's
size or position (owner ruling, Oct 9 2026). `AppButton.Primary`, which every
semantic button style inherits, sets this; never left- or right-align a
button's text. The owner generally prefers label-sized buttons to buttons that
stretch across the screen; ask before making a new button full width.

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

Two-button dialog actions should be centered as a pair by default.

Button order is fixed by role, not by feature wording (owner ruling,
September 5 2026): the affirmative / action button is always on the RIGHT, and
the Cancel or back-out action is always on the LEFT. This holds for every
two-button dialog, including a system `MaterialAlertDialog` (its negative button
is the left one, its positive button the right — so Cancel is the negative
button and the action is the positive button). Cancel/back-out actions use the
Destructive style; affirmative actions use the Primary style.

Use `layout/dialog_two_actions_cancel_first.xml` for this cancel-left,
action-right order. `layout/dialog_two_actions.xml` (action-first) predates this
ruling: do not use it for new dialogs, and move an existing dialog onto the
cancel-first order when that screen is next revised rather than in a blind
app-wide reorder.

### Three dialog actions

`AppButton.Primary.DialogStacked`

`AppButton.Destructive.DialogStacked`

Use `layout/dialog_three_actions_cancel_first.xml` when three complete action
labels would be cramped in one horizontal row. The shared layout stacks the
actions in their approved top-to-bottom order: cancel/back-out first, alternate
commitment second, final commitment third. It owns spacing and constraints;
the shared styles own width, shape, typography, and theme color roles.

The chat/image deletion choice is the first consumer: **Cancel**, **Delete Chat
Only**, **Delete All**. Do not reorder those actions or reproduce the stacked
geometry in a feature-local layout.

### Inline actions

`AppButton.Primary.Inline`

`AppButton.Destructive.Inline`

Use when actions should size to their labels rather than fill the available width.

`AppButton.Primary.Inline.Centered` is the same label-sized button centered on
its line, for vertical (LinearLayout) hosts. In a ConstraintLayout, center
`AppButton.Primary.Inline` with start and end constraints instead.

Two-button dialogs use a centered shared layout by default. The existing
right-aligned Cancel-then-Save row uses `layout/dialog_two_actions_end.xml`
only where that arrangement is explicitly approved by the feature spec; it is
not the general default.

A future inline secondary button should inherit `AppButton.Secondary` and change only its geometry.

## Dialogs

### Standard dialog theme

`App.MaterialAlertDialog`

Use for every `MaterialAlertDialogBuilder` unless an approved feature-specific dialog requires a different theme.

This theme supplies the standard dialog appearance and centers dialog titles.

Parameter information boxes use `ParameterInfoDialog` and the shared
`view_parameter_info_title.xml` heading, styled by `Widget.App.ParameterInfo.*`.
The ordinary title TextView inherits the standard dialog title typography and
centering, uses the space beside the information icon, and wraps without a
line limit. It does not use Android's `DialogTitle`, which can shrink a longer
heading during measurement and retain that smaller size. Keep the body and
Close action in the standard Material dialog; do not special-case individual
parameter headings or set their text size in Kotlin.

### Title and explanatory text

Use `setTitle` for the dialog heading or its single short question.

Use `setMessage` only for separate explanatory text beneath the title.

A dialog containing only a short question should place that question in the title and omit the message.

### Save confirmation on a header Save icon

Use `SaveIconFlash.flash(button)` (`org.teslasoft.assistant.ui.util`) after a successful save from a header Save icon, together with the save toast. The icon itself turns green, then returns to its normal tint; the button background is never recolored (owner ruling, Oct 3 2026). Current users: Edit Companion and Summarizer Prompts.

### Confirmation with a "hide this hint" switch

Use `HintConfirmDialog.show(...)` (`org.teslasoft.assistant.ui.util`) for an explanatory, non-destructive confirmation that the user may choose to stop seeing. Title and message use the standard dialog, the actions use `dialog_two_actions_cancel_first.xml` (Cancel left, Okay right), and the shared `layout/view_dialog_hint_toggle.xml` puts the hide switch on its own line beneath the buttons in ordinary body text (`Widget.App.CheckOption.Label`), on by default (owner ruling, Oct 3 2026). Current users: the Conversation Summary / Compaction Summary screen's Unsummarize/Uncompact and Resummarize/Recompact.

### Standard discard-changes dialog

Use `DiscardChangesDialog.show(context) { onDiscard }` for a full-screen editor with unsaved changes.

Do not rebuild or reword this dialog at individual call sites.

### Ordinary text-button dialogs

Ordinary Yes/No or similar dialogs may use the text action buttons supplied by `App.MaterialAlertDialog`.

Use the custom button layouts above when the approved design calls for filled, outlined, or specially arranged actions.

### The affirmative word is always "Okay", never "OK" (owner ruling, Aug 26 2026)

Every affirmative confirmation label in the app is spelled out as **Okay**.
The two-letter form **OK** is never used, anywhere — dialog buttons, banners,
toasts, helper text, or any other user-facing string, in any language.

Use the shared `@string/btn_ok` string (its value is `Okay`) for affirmative
dialog buttons rather than the platform `android.R.string.ok`, so a single
resource carries the approved spelling everywhere. Do not hardcode the literal
`OK` in a layout, a Kotlin string, or a translation.

## Conversation navigation motion

New Chat and saved-chat selections in the drawer share
`ChatDrawerController.openConversationFromDrawer`: suppress the activity's
window animation and reveal the conversation by closing its drawer. Selecting
the currently open conversation only closes the existing drawer.

Settings and its internal destination pages inherit `SettingsPageActivity`
(directly or through `MemoryScreenActivity` / `TtsPickerActivity`).
They enter from the right and leave toward the right, keeping the underlying
screen still. Both directions use the shared `settings_slide_*` XML resources
and `settings_page_slide_duration` in `values/integers.xml` (600 ms). Adjust this
one token to change both speeds; do not hard-code per-page timing. The chat
drawer keeps its independent `drawer_slide_duration`.

The policy follows the navigation stack rooted at Settings, including Companions,
Activation Prompts, Glamour Studio, their editors, and nested memory, roleplay,
model, voice, appearance, image, backup, and diagnostics pages. Internal explicit
activity launches inherit the policy through the shared base, including Activity
Result launchers. Quick Settings launches do not opt in; their managers, editors,
and filter panels keep the existing transitions. Legacy filter animation overrides
must only run outside the Settings stack so they cannot replace the shared timing.
Do not change external document/image picker or other system-window transitions.

Returning to Chat must retain
the current activity, transcript, composer, and loaded presentation; do not
recreate it from a Settings result callback. Resume refreshes changed request
settings and presentation in place. Unchanged row appearance, avatars, and
identity styles must not trigger full message rebinds.

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

### Title information icons

When an information icon explains a title, label, or setting name, it appears
immediately after that text. The title and icon are one left-aligned unit; the
icon must not be pushed to the opposite side of the row or separated from the
words it defines unless an approved design explicitly requires another
placement.

Use `ParameterSectionHeader` for the standard title-and-information pairing.
Its icon opens the shared information dialog. The host supplies only the title
and explanation strings; it must not reproduce the icon, spacing, dialog, or
placement locally.

## Selector rows and pick-list rows

### Selector row

`Widget.App.Row.Selector`

`Widget.App.Row.Selector.Label`

`Widget.App.Row.Selector.Value`

`Widget.App.Row.Selector.VoiceLanguage`

`Widget.App.Row.Selector.Flush`

`Widget.App.Row.Selector.Label.Flush`

Use for a row that shows the current value of a setting and opens a picker (a dialog or another screen) when tapped. The current value sits at the end, ellipsizing with a marquee. There is no chevron — the value itself is the affordance. The label's typography depends on the variant: the base `Widget.App.Row.Selector.Label` is bold `colorPrimary`, reserved for the Quick Settings card where the compact surface needs the accent; `.Label.Flush` uses ordinary row-title typography (normal weight, `appRowTitleColor`, matching `Widget.App.Row.Title`).

Use the `.Flush` row style (with `.Label.Flush` for its label) on a plain settings screen where the selector should read as an ordinary row rather than a card tile: it drops the tonal pill background and the label's leading inset, and the label matches the screen's other row titles in weight and color, so the row does not stand out mid-list. The screen still supplies its own horizontal padding to match its rows. The base `Widget.App.Row.Selector` (indented, pill-backed, bold-accent label) stays reserved for the Quick Settings card. Current `.Flush` examples: the AI Model row on Select API Voice Models and the language selectors on Voice & Speech.

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

### Field error and counter lines

`Widget.App.Field.Error`

`Widget.App.Field.Counter`

Use `Field.Error` for an inline validation or warning line directly under a field's box, and `Field.Counter` for a live character count right-aligned under the box. Both own their color (`colorError` / `appSubtleTextColor`), text size, and top spacing, so a theme or font change reaches every field at once. The instance sets only its text, visibility, width/margins, and constraints; Kotlin sets text and visibility only, never color. Current example: Edit Glamour (`activity_edit_user_persona.xml`).

### Bounded, internally-scrolling variant

Set `minLines`/`maxLines` and `android:scrollbars="vertical"` (plus `android:isScrollContainer="true"`, or the field never actually scrolls) on the individual `Widget.App.Field.Box` input to pin it to a fixed number of visible lines instead of letting it grow the dialog taller — the same bounded-height-scrolls-past-that idea as the Prompt Editor's `field_prompt`/`bg_prompt_editor` skin above, applied here to the standard `bg_field_box` skin instead. Current example: `dialog_edit_chat_title.xml` (ChatActivity's title-edit dialog, opened by tapping the chat header title) — a 4-line field, since an AI-generated chat title can run far longer than the header ever shows.

### Small inline number field

`Widget.App.Field.NumberBlank`

Use for a short whole-number input that sits on the same line as its label.

Set `android:ems` and `android:maxLength` on the individual field according to the digits it must accept.

### Editable sampling slider

Use `SamplingParameterControl` for model sampling values that need both direct
numeric entry and slider adjustment. It renders the slider and editable value
box on the same line; typing a valid number moves the slider, and sliding
updates the box.
Direct input is normalized on Done or focus loss and cannot leave the control
outside its parameter's supported range.

The shared appearance family is:

- `Widget.App.SamplingSlider.Container`
- `Widget.App.SamplingSlider.Value`
- `Widget.App.SamplingSlider.Control`

The component skin lives in `values/themes.xml`; its reusable width, height,
spacing, and text-size tokens live together under `sampling_slider_*` in
`values/dimens.xml`. The value box inherits the canonical `Field.Box` outline,
uses `appTextColor`, and the Material slider continues to resolve its colors
from the active theme. Screens must not restate those dimensions, colors,
padding, slider label behavior, even guide-line treatment, end-stop treatment,
or field typography.

Parameter ranges and numeric precision are behavior, not appearance. They live
in `SamplingParameterSpec` / `SamplingParameterValuePolicy`: Temperature
0–2, Top P 0–1, both penalties -2–2, with 0.01 steps and at most two displayed
decimal places. Add or reuse a spec there rather than multiplying values in a
screen controller or placing range/default numbers in layout XML.

The slider precedes its compact editable value field: the value stays on the
right, with a shared horizontal inset keeping its outline inside the card.
The field reserves the full signed range at the supported decimal precision
using its styled font metrics; do not use a wide fixed box or size it only for
the current value. This shared control intentionally retains left-to-right
placement so the value remains on the right.

`SamplingRulerSlider` draws 21 evenly spaced guide lines below the actual
track, with longer endpoint and quarter marks. Its geometry and mark counts
come from shared dimens/integers; its contrast comes from
`colorOnSurfaceVariant`. These are visual guides, independent of the numeric
step. Do not use a stretched vector background or faint per-step dots.

Current canonical uses are the four model controls in Quick Settings and both
API Endpoint editor layouts. Each host supplies only a view id, the shared
container style, constraints if required, and an accessibility description;
its controller calls `configure(spec, initialValue, onValueChanged)`.

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

The tab row and editor frame are one shared layout, `layout/view_prompt_variant_editor.xml`, driven by one shared controller, `PromptVariantEditor` (`org.teslasoft.assistant.ui.util`). Edit Companion and Summarizer Prompts include the layout; do not copy its XML or menu code into a screen.

The frame is a `ConstraintLayout` containing:

1. **Tab name** — a `TextView` showing the active variant's name, left-aligned. The default prompt's name is prefixed with a green dot (`light_green`).
2. **Three-dot menu** — an `ImageButton` (36x36dp, `ic_more_vert`) anchored to the trailing edge, opening a `PopupMenu` with: Make Default, Rename, Copy From…, Duplicate, Copy, Clear, Revert, Delete. Revert returns only the open prompt's text to its text at the last save (owner ruling, Oct 3 2026). A screen may mark prompts that cannot be deleted; Delete is disabled while one of them is open.
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

## Summary sections

`Widget.App.SummarySection.Header` — a summary section's protected, generated date/time header on the Conversation Summary screen: left-aligned text in a quiet rounded surface (`bg_summary_section_header`, theme colors only), tappable to open the conversation preview. It is metadata, not a button, and never looks disabled.

`Widget.App.SummarySection.Flag` — the Material bookmark flag (`ic_bookmark_flag`) beside the header of the section reached from the chat, and in the conversation preview at a section's starting point. In the chat it sits directly right of a reply's info button (`btn_summary_bookmark`) on the reply that opens a section.

Each section is `layout/view_summary_section.xml`; the shared editing behaviors (Save disc, Revert, read-only lock, Unsummarize/Resummarize) are the screen's existing ones. The conversation preview is `layout/sheet_conversation_preview.xml`, a nearly full-height bottom sheet closed by the double chevron down or a swipe.

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

#### Font previews in a dropdown

`AppDropdown.show(..., optionTypeface = ...)` renders each option in its own typeface, so a font picker previews every font. Omitting it keeps the shared option typography, so no other dropdown changes. The screen may also set the closed control's typeface to the chosen font. Current example: Name Style's Font dropdown.

### Summoning Circle placement

`Widget.App.QuickTile.Label`

`Widget.App.QuickTile.Value`

`Widget.App.QuickTile.EditButton`

`Widget.App.QuickSettings.Segment.Top`

`Widget.App.QuickSettings.Segment.Middle`

`Widget.App.QuickSettings.Segment.Bottom`

`Widget.App.QuickSettings.Segment.Standalone`

The Summoning Circle has an approved separate edit button that opens the manager
for that category. Its label, dropdown, and edit button therefore need local
layout constraints, but this is not a separate dropdown design.

`Widget.App.QuickTile.Label` and `Widget.App.QuickTile.Value` must inherit the
canonical `Widget.App.Dropdown` label and value appearance. They may contain
only placement differences required by the edit-button column. Never duplicate
or override dropdown colors, border, background, height, internal padding,
chevron, sizing rules, open-state behavior, disabled treatment, or touch
feedback in the QuickTile family.

Every Quick Settings group follows the connected structure established by the
Companion / Glamour / Activation / System Prompt block. The first visible row
uses `Segment.Top`, internal rows use `Segment.Middle`, and the last row uses
`Segment.Bottom`. A separate outlined card uses `Segment.Standalone`. These
styles and their shared drawables own the surface, outline, corner geometry,
horizontal margins, vertical padding, and gaps. Do not put a local background,
background tint, border color, corner size, or copied segment spacing into a
Quick Settings layout or its Kotlin controller.

The segment fill is `?attr/colorSurfaceContainerHigh`, the neutral elevated
surface role, for every group and the standalone card (owner correction,
Oct 8 2026). Do not use `colorSecondaryContainer`: that accent role produced
the unwanted olive-green backgrounds when the connected groups were redone.
Keep the fill in the shared segment drawables; do not restore the old
per-view Kotlin surface tints or hardcode a blue for one palette.

The current vertical order is intentional: identity and character choices;
this chat's Always Speak Responses, a self-contained `Segment.Standalone`
toggle (owner ruling, Oct 9 2026; it becomes a connected group if more rows
join it); model/provider/endpoint routing; memory controls; independent roleplay context;
the summarizer and its Summary, Compaction, and Image prompts (owner ruling,
Oct 3 2026); generation parameters; Logit Bias and Seed; usage/cost; Save to Profile. Keep
that order unless the owner explicitly changes it.

The Lorebooks segment may expand internally. While lorebooks are enabled, its
centered **Add Lorebook** action is always available. That action opens a pure
selection list: rows select books and do not expose edit/open actions. Books
added there are temporary chat-level specialty books; they never change the
selected companion's permanent links. Do not impose an arbitrary book-count
maximum.

When the selected companion has multiple available books—or the chat has added
a specialty book—the segment contains one regular-sized heading and one
switch-and-gear row for the companion's default book, that companion's linked
books, and the chat's added books only. Every row starts on when first added;
the switch controls use in this chat and the gear opens that exact book. Those
rows remain inside the Lorebooks segment and never receive separate segment
backgrounds. With only the companion's default/core book, no redundant toggle
list is shown, but Add Lorebook remains visible. Turning the master Lorebooks
switch off hides both the list and Add Lorebook.

## Voice Browser

The full-screen Voice Browser composes the existing `Widget.App.ActionBar`,
`Widget.App.Dropdown.CanonicalLabel`/`.CanonicalValue`, `Widget.App.Section.*`,
and `Widget.App.Row.*` families. Its provider and metadata filters use the
canonical dropdown behavior; no provider owns a separate selector layout.

The few controls unique to browsing voices use one documented family:

- `Widget.App.VoiceBrowser.Segment` — the equal-width All / On-device / Network
  single-choice group. Checked and unchecked colors come from theme roles via
  `voice_browser_segment_*` state lists.
- `Widget.App.VoiceBrowser.Row` — the selectable voice row container and its
  minimum accessible height.
- `Widget.App.VoiceBrowser.SelectedIcon` — the independent selected-state mark.
- `Widget.App.VoiceBrowser.Action` — the independent 56dp Preview or Download
  action target. Selection never replaces or absorbs this control.
- `Widget.App.VoiceBrowser.Remove` — the small X shown immediately before
  Preview only on a manually saved Voice ID row. Every other row keeps it
  `gone`, so ordinary rows are unchanged and Preview stays at the right edge.

The manual Voice ID entry (shown only for an API source with no usable voice
list) is the standard label-above-box field with an `AppButton.Primary.Inline`
Add action beside the box, placed below the long-press hint and above Voices.

These styles deliberately contain no screenshot-derived literal colors. New
provider metadata may add canonical dropdown fields without adding another
Voice Browser style or provider-specific screen.

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

## Stacked single-choice radio row

`Widget.App.Row.Radio`

Use for a stacked, single-choice list of options shown as radio buttons on a
settings screen — the whole row is a tappable `RadioButton` whose label matches
the shared row-title typography (16sp, `appRowTitleColor`) and whose control
tint resolves from the theme (`colorPrimary`). Put the `RadioButton`s directly
in a vertical `RadioGroup` so it manages single-selection; the group supplies
the horizontal padding that aligns the rows with the screen's other rows. All
size, color, and geometry live in the style, never in the layout or Kotlin.
First use: the Voice Input engine choice on Voice & Speech.

This is for a persistent on-screen choice. It is not the "checked tile"
pick-list (`Widget.App.PickList.Row`) used inside a Select pop-up, and it is
not the equal-width horizontal segmented control
(`Widget.App.VoiceBrowser.Segment`).

## Chat composer host and surface

### Chat composer host and surface

Use:

- `Widget.App.Chat.ComposerHost` on the outer bottom composer host. It keeps the space behind the floating oblong transparent.
- `Widget.App.Chat.ComposerSurface` on the live composer surface. It uses `@drawable/bubble_in`, so the editor and its controls resolve the same app-owned theme surface as the incoming AI bubble.
- `Widget.App.Chat.ComposerAction` on bare primary composer actions such as Add and the conditional conversation-tools gear. It owns their 48dp geometry, transparent background, centered icon, and theme-resolved tint.
- `Widget.App.Chat.ComposerContentToggle` on both Expand Content and Collapse Content. It owns their 48dp icon-button geometry, transparent background, centered icon, and theme-resolved tint. The layout owns only their different placement and visibility. Do not replace this background in Kotlin, including legacy theme handling.

The initial empty composer keeps the one-row editor between the bottom controls. Focusing it moves that same editor above the controls and allows natural growth up to eight lines. The expand control is shown only for an active non-empty draft; expanded mode uses the bounded space below the app header and the collapse control restores the previous mode. Keep the controls in this order: Add, conditional conversation tools, conditional persistent Includes, Expand content, microphone, Send.

Do not assign phone/dynamic-system colors or a second local composer palette in XML or Kotlin. The host remains transparent and the surface resolves through the shared drawable/theme roles.

### Chat action pop-ups

Use the complete `Widget.App.Chat.ActionMenu.*` family for the labeled action pop-ups above the composer:

- `Popup` for placement and elevation;
- `Card` and `Surface` for the rounded transparent/blurred container;
- `Content` for the vertical action stack;
- `Row`, `Icon`, and `Label` for every available action.

Camera/Image/Document and the conditional Compact/Create Image menu share this family. Availability and click behavior remain in the owning chat screen; appearance must not be restated or recolored there.

Their shared runtime blur radius is `@dimen/chat_action_menu_blur_radius`; keep it centralized with this family rather than placing a numeric radius in `ChatActivity`.

### Manual compaction marker

`Widget.App.Chat.CompactionMarker` is the centered **Compacted** title between message units. It inherits the shared section-title typography and owns its spacing and alignment. Both user and assistant row layouts carry the same normally hidden marker slot; the adapter shows exactly one slot at the persisted manual boundary without inserting a synthetic conversation message.

## Chat Thinking disclosure

Use the complete `Widget.App.Chat.Thinking.*` family for the provider-supplied Thinking disclosure on assistant replies:

- `Container` for the disclosure block;
- `Header` for the tappable label/chevron row;
- `Label` for the bold Thinking label;
- `Chevron` for its outline expand/collapse glyph;
- `Body` for the selectable reasoning text.

The family owns the component's size, spacing, typography, default theme colors, and borderless header interaction. Message layouts must not repeat those attributes inline. `ChatAdapter` may recolor the label, body, and chevron together through its single bubble-foreground path so they retain contrast when bubble appearance changes; that path resolves semantic theme attributes rather than fixed palette colors. Expanded/collapsed rotation and visibility remain behavior owned by `ChatAdapter`.

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
actions and a direct X removes that individual image or document. Sent-row
text and actions follow the message foreground so they remain readable inside
the bubble. Removed items retain their original row with `(removed)` after the
filename; their removal controls are hidden. Removal confirmation uses
`App.MaterialAlertDialog` with a title, explanatory message, and
`dialog_two_actions_cancel_first.xml` labeled Cancel and Okay. Do not offer Condense, Reduce to Text Only, or Edit in the composer.

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

## Usage & Cost cards

`Widget.App.Usage.SectionPill`, `Widget.App.Usage.ModelCard`,
`Widget.App.Usage.ModelHeader`, `Widget.App.Usage.ModelHeaderRow`,
`Widget.App.Usage.ModelName`, `Widget.App.Usage.ModelTotal`,
`Widget.App.Usage.ModelMeta`, `Widget.App.Usage.ModelFunctions`,
`Widget.App.Usage.ProviderGap`, `Widget.App.Usage.Stack`

Screen frame: `Widget.App.Usage.Header`, `HeaderBar`, `HeaderTitle`,
`Scroll`, `Content`, `TotalBlock`, `TotalLabel`, `TotalCost`, `TotalMeta`,
`Sections`. Only the header bar stays fixed; the conversation total block
scrolls with the sections (owner ruling, Oct 9 2026).

The screen header must inherit `Widget.App.ActionBar` and its shared title/back
button styles, with `ScreenChrome.apply` supplying the same header chrome as
Settings. Do not override it with a Usage-specific accent background or title
color. Conversation total spacing uses `usage_total_top_gap` and
`usage_total_bottom_gap`: move space from below the total to above it, keeping
the summary area's overall height unchanged.

Section title pills alone use a solid `colorSurfaceContainerHigh` fill (the
Quick Settings panel surface), `colorOnSurface` text, and a `colorOutlineVariant`
outline with the shared `quick_settings_segment_stroke_width`. No gradient or
lighter accent-container fill behind these titles. This rule does not change
the separate card-zone color roles described below.

Provider block: `Widget.App.Usage.ProviderHeader`, `ProviderNameColumn`,
`ProviderName`, `ProviderMeta`, `ProviderTotalColumn`, `ProviderTotal`,
`ProviderTotalLabel`; chart `Table`, `TableHeader`, `TableHeaderLabel`,
`TableHeaderQuantity`, `TableHeaderCost`, `TableRow`, `TableLabel`,
`TableQuantity`, `TableCost`, `TableDivider`; `CacheRate`, `CacheRateLabel`,
`CacheRateValue`; pricing footer `PricingFooter`, `PriceFacts`, `PriceFact`,
`PriceFactLabel`, `PriceFactValue`, `PriceCaption`.

Use only on the Usage & Cost screen (owner ruling, October 6 2026).

Every layout on this screen holds structure only (owner ruling, October 7
2026): no size, spacing, text size, color, alignment, or background is written
on a view. Every provider block in every section (Chat, Summarizing, TTS, and
the rest) is built from the same styles, so they always match. Shared
measurements are dimens: `usage_card_corner_radius`, `usage_pill_corner_radius`,
`usage_cache_rate_corner_radius`, `usage_card_padding`,
`usage_quantity_column_width`, `usage_cost_column_width`,
`usage_table_divider_height`.

Composition, top to bottom, per section:

1. one centered `SectionPill` (`view_usage_section_pill.xml`) holding only the
   section title;
2. one `ModelCard` per model (`view_usage_model_section.xml`): the model header
   (`view_usage_model_summary.xml`, rich solid accent surface) with name and total on one
   line, the request count below, and, in Summarizing only, the
   `ModelFunctions` line;
3. inside that card, one provider block per provider
   (`view_usage_provider_block.xml`), separated by a `ProviderGap` that is hidden
   above the first. Only the last provider's pricing footer uses the rounded
   `bg_usage_pricing_footer`; the others use `bg_usage_pricing_footer_inner`.

Chat alone uses `view_usage_chat_table`: retain Usage / Tokens / Cost columns,
show Input tokens as the inclusive total, then indent Cached, Not cached, and
Cache Hit Rate beneath it; Output is a separate top-level row. Indent only the
labels, so token and cost columns stay aligned. Child separators are partial
lines starting at `usage_chat_detail_indent`; group separators span the padded
table. Cache Hit Rate is an ordinary child row with a percentage in the quantity
column and no cost; hide the separate cache pill only in Chat. Preserve the
existing table layouts of all other categories. Use the stored input total and
its known/unknown flags, rather than relabeling uncached input as total input.

Card zones are centrally mapped in `Theme.App` and every palette overlay:
`appUsageModelBackgroundColor` defaults to `colorPrimaryContainer`, with
`appUsageModelTextColor` mapped to `colorOnPrimaryContainer`. Model headers
and Cache Hit Rate use this exact same solid fill and matching text role.
`appUsageProviderBackgroundColor` defaults to `colorSurfaceContainerHigh` for
provider headers. Pricing footers instead share `appUsageModelBackgroundColor`
and `appUsageModelTextColor` with the model headers in every section. These roles provide a richer model zone
against a quieter provider zone without fixing a literal purple or green color.

The model card perimeter and `Widget.App.Usage.ZoneDivider` use
`colorOutlineVariant` and `quick_settings_segment_stroke_width`, matching the
Quick Settings section outlines. Full-width dividers sit below the model header,
below each provider header, and above each provider's pricing footer. Keep them
outside the padded content so they meet the perimeter. The section title pills
also match Quick Settings' solid fill, outline color, and stroke width exactly.

Theme readiness: every color in these styles and in `bg_usage_section_pill`,
`bg_usage_model_header`, `bg_usage_provider_header`, and the two pricing
footers is a theme role (`colorPrimaryContainer`, `colorSecondaryContainer`,
`colorSurfaceContainerHigh`, and so on). Corner radii are dimens. Recoloring
or resizing the screen is a change to these styles, dimens and drawables
only; nothing is colored or sized in code.

## Maintaining this guide

## Conversation mode segmented selector

`Widget.App.ConversationModeSelector`

Use this named style with `ConversationModeSelector` for a mutually exclusive
Chat / Playground choice on an unsaved conversation. Do not recreate its pill,
capsule, text colors, spacing, or animation in an activity layout.

The complete theme/palette mapping is:

- outer pill: `colorSurfaceContainerHigh`;
- selected capsule: `colorSecondaryContainer`;
- selected label: `colorOnSecondaryContainer`;
- unselected label: `colorOnSurfaceVariant`.

Geometry and motion are centralized in
`conversation_mode_selector_*` resources in `dimens.xml` and `integers.xml`.
The custom view owns the sliding/resizing selected capsule and exposes the two
labels as mutually exclusive accessible choices. A host supplies only current
mode, visibility, and a selection listener.

## Compact action popup

Use `CompactActionPopup` for small anchored management menus. It applies
`Widget.App.CompactActionPopup`, which owns the shared surface treatment.
Callers provide only ordered actions and enabled state; they must not set a
screen-local popup background, palette, spacing, or typography. Generated
Image Gallery long-press actions are the reference composition, and the drawer
chat/folder menus reuse the same component.

Keep this file as a current reference, not a development log.

## Flat chat identity row

Use the complete `Widget.App.FlatChatRow*` family with
`layout/view_flat_chat_row.xml` for drawer chat rows and Search results:

- `Widget.App.FlatChatRow` owns the flat, card-free row spacing and touch target;
- `.Title` owns the one-line chat/folder identity;
- `.Metadata` owns optional model, memory-state, and date lines;
- `.Snippet` owns Search's matching-context line only.

The shared layout has optional leading icon, companion-image, bookmark overlay,
chevron, metadata, snippet, and date slots. Every adapter bind must reset every
slot. When companion images are disabled the image frame is `GONE`, so the row
reserves no empty column. Folder children add only the centralized
`drawer_nested_indent`; they do not create a second row style.

## Name-entry dialog

Use `layout/dialog_name_entry.xml` with `Widget.App.NameEntry.Layout` and
`Widget.App.NameEntry.Field` for simple Add/Rename name dialogs. The family owns
outlined-field geometry, text appearance, padding, and inline error placement.
The dialog host owns the title, current value, validation policy, and cancel-first
actions. Add Folder and Rename Folder must use this one composition.

## Name Style

The header uses `Widget.App.ActionBar` and `ScreenChrome`. Dropdowns remain
available in any order. Before a name is chosen, typography edits stay in an
unassigned preview draft; the Name dropdown can list names grouped by Type.
Each selected name keeps its own draft. Nothing writes to preferences or the
identity store until Save. The centered fixed-bottom `NameStyle.SaveButton`
inherits `AppButton.Primary`; back navigation offers saving all assigned drafts,
discarding, or keeping editing. Unassigned edits require selecting a name first.
Drafts and selection survive activity recreation through saved instance state.

`Widget.App.NameStyle.SavedPanel` sits directly below Name and stays visible
before selection. Its fill and outline match Quick Settings
(`colorSurfaceContainerHigh`, `colorOutlineVariant`,
`quick_settings_segment_stroke_width`); its corners use the same
`dropdown_corner_radius` as the dropdowns. `NameStyle.SavedValues` uses normal
body text and shows the last saved values, not the preview draft. It reserves
no blank lines: the box is as tall as its text plus the button (owner ruling,
Oct 9 2026). Labels are Default Companion Style, Default User Style, and
Custom Settings. Companions inherit the companion default; Glamour and
Roleplay inherit the user default. For custom settings, also show the
matching default values for comparison.

`NameStyle.RestoreOriginal`, labeled **Restore Original Style**, is a normal
label-sized primary button centered at the bottom of the saved-style box
(`AppButton.Primary.Inline.Centered`; owner ruling, Oct 9 2026, replacing the
earlier text-only treatment). It has no confirmation popup. Clicking restores
the selected name's last saved overrides into its draft and preview; empty
overrides retain true inheritance from the appropriate default. It does not
save, reset to factory values, or replace custom saved settings with defaults.
Before a name is selected, the button's space is kept but the button is
hidden.

`Widget.App.NameStyle.Preview` is the centered live preview under the controls.
Typography comes from `ChatNameStyle.apply`, the same resolver used by chat.
All placement, text appearance, shapes, and spacing belong to XML styles/dimens.

## Chat Signature Style

One shared section, `layout/view_chat_signature.xml` driven by
`ChatSignatureSection` (`org.teslasoft.assistant.ui.util`), used on Edit
Companion (under Companion Name), Edit Glamour (under Display Name), and the
Roleplay Character card (under Name, above Species; hidden for party members).
Owner rulings, Oct 9 2026. Do not copy its XML into a screen; include it and
set only the include's width and placement.

In order: the **Chat Signature Style** heading (`Widget.App.Section.Title`);
the name centered in `Widget.App.Signature.Preview`; and the **Change Chat
Name Style** button, `Widget.App.Signature.ChangeButton`, a label-sized
primary button centered on the screen (`AppButton.Primary.Inline.Centered`).
The preview inherits `Widget.App.NameStyle.Preview`; its typography comes from
`ChatNameStyle.apply` with that identity's saved Name Style override over the
right default (companion default for companions, user default for Glamours
and Roleplay Characters) — exactly what chat shows. The preview text is the
name chat shows: the Companion Name, the Glamour's Display Name (not its
Name), or the Roleplay Character's Name. The button opens Name Style with
that identity already chosen; back returns to the editor. Editors have no
font or size controls of their own, and saving an editor never rewrites the
Name Style override. Spacing lives in `signature_gap` and `signature_host_inset`.

## Edit Companion: linked lorebooks

Each linked (additional) lorebook is a `Widget.App.CompanionEditor.LoreBookCard`:
the Quick Settings standalone segment surface and outline
(`bg_quick_settings_segment_standalone`: `colorSurfaceContainerHigh` fill,
`colorOutlineVariant` stroke). Never use `colorSecondaryContainer` or the
device accent for this card. The book name uses `LoreBookName` (normal app
text color, bold); the count/tag/description line uses `LoreBookDetails`
(the shared row subtitle color). The gear, unlink, and delete actions use
`LoreBookAction`, a bare borderless icon inheriting `Widget.App.QuickTile.EditButton`
(no background shape). Spacing lives in the `companion_editor_*` and
`companion_lorebook_*` dimens.

## Message Details popup

The ⓘ message action opens `layout/view_details_popup.xml`:
`Widget.App.MessageDetails.Popup` (rounded dialog surface), with bare values in
`MessageDetails.FirstValue` / `MessageDetails.Value` and the empty line in
`MessageDetails.Empty`. The box is as wide as its longest line plus its
padding, with no minimum width (owner ruling, Oct 9 2026). Token values use
Title Case: "1,234 Tokens", "512 Reasoning Tokens".

## Search status

`Widget.App.Search.Status` is the centered subordinate status text used for
preparing, incomplete, unavailable, and empty Search states. It resolves through
the shared subtle-text theme role and must not carry query text or snippets.

## Drawer bottom actions

`Widget.App.DrawerBottomAction` is the drawer's fixed-bottom action. Three of
them sit across one row — Settings, New Folder, New Chat — each taking an equal
share of the drawer width.

It inherits `Widget.App.FlatChatRow.Title`, so all three read at the same size
as the single Settings action the drawer used before, and it supplies the equal
weight, centred gravity, single line, and the shared selectable-item touch
feedback. These actions are deliberately text-only; do not add a start drawable
or any other icon to them.

Use it only for the drawer's fixed-bottom row. A full-width bottom row is what
makes three equal actions legible; do not reuse it for narrow containers.

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
