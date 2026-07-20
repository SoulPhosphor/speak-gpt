# UI Style Guide — shared style resources

This file documents this app's shared, reusable Android `<style>`
resources (buttons, chevron rows, screen headers, dialogs, form fields)
— the pieces meant to be applied consistently across many screens
instead of hand-copied per layout. It was split out of `CLAUDE.md` on
July 20 2026 to keep that onboarding manual from growing without bound
as the style system grows; `CLAUDE.md`'s Coding rules section keeps a
short pointer here instead of the full history. Read this file before
adding or touching any shared style, and add new styles' documentation
and rollout notes here — not back in `CLAUDE.md`.

- **Dialog text hierarchy — title vs. subtext (owner ruling, July 20
  2026).** A `MaterialAlertDialogBuilder` dialog has two text roles, and
  they must not be collapsed into one: `setTitle` is the TITLE (large,
  bold) and `setMessage` is the SUBTEXT (smaller, regular weight) that
  explains the title. **When a dialog is just one short question with no
  further explanation** (e.g. "Discard changes?"), that question goes in
  `setTitle` and `setMessage` is left unset — it must render at title size,
  not shrink down to subtext size just because it was the only text in the
  dialog. Only use `setMessage` when there is genuinely separate
  explanatory text under a title (e.g. `framing_load_error_title` +
  `framing_load_error_body`). A single-question dialog's title should also
  be centered (see `DiscardChangesDialog` for the pattern: after
  `dialog.show()`, `dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.gravity = Gravity.CENTER`
  — a per-dialog runtime tweak, not a change to the shared
  `App.MaterialAlertDialog` theme, so it never affects other dialogs' left-
  aligned titles/bodies).
- **The standard discard-changes dialog (owner ruling, July 20 2026;
  restyled same day) — use this by name, don't re-describe it.** Any
  full-screen editor with an explicit Save action must confirm before
  letting the user back out (toolbar back button or the system back
  gesture) with unsaved changes. Call
  `org.teslasoft.assistant.ui.util.DiscardChangesDialog.show(context) {
  onDiscard }` — it builds the one approved shape: `@string/discard_changes_q`
  ("Discard changes?") as the dialog's TITLE (see the title/subtext rule
  above — it's a single question, so no `setMessage`), centered over two
  real buttons built from `dialog_two_actions.xml`, primary action first/
  start (`@string/okay` "Okay", `AppButton.Primary.DialogAction` — runs the
  discard callback, finishes the screen, changes lost) then destructive
  action second/end (`@string/btn_cancel` "Cancel",
  `AppButton.Destructive.DialogAction` — only dismisses the dialog and
  leaves the screen exactly as it was). Neither button is a flat
  `setPositiveButton`/`setNegativeButton` text button — both are real
  `AppButton.Primary`/`AppButton.Destructive`-styled `MaterialButton`s, so
  they retheme the same way every other primary/destructive button in the
  app does. Themed with `R.style.App_MaterialAlertDialog`. Do not reword or
  restyle it without asking first. First use: `CampaignDetailActivity` (both `btnBack`'s click
  listener and an `onBackPressedDispatcher.addCallback` for the system
  back gesture route through one `attemptExit()` that compares a field
  snapshot against the snapshot taken at load/last-save). Also applied,
  same day, to the other roleplay-card editors sharing that exact
  bottom-Save-button shape: `CharacterCardActivity` (serves both the
  Roleplay Character and Party Member cards — one screen, one fix) and
  `WorldDetailActivity`. Second use overall: `ApiEndpointEditorActivity`,
  converted the same day — it already had an equivalent hand-rolled
  discard-changes dialog with "Yes"/"No" buttons instead of "Okay"/
  "Cancel"; the owner asked for it to match, so its `attemptExit()` now
  calls the shared helper too. (`R.string.yes`/`R.string.no` stay in use
  elsewhere for ordinary delete-confirmation dialogs, a different kind of
  prompt — this ruling is about the unsaved-changes case specifically,
  not a blanket Yes/No → Okay/Cancel sweep.) `CampaignDetailActivity` is
  covered by this same standing rule but doesn't need a fresh listing here
  each time it's touched.
- **The top-right disc (floppy-disk) save icon (owner ruling, July 20
  2026).** For a full-screen editor whose Save action moves from an
  inline/bottom button into the header, put a floppy-disk icon
  (`@drawable/ic_save`, the app's existing save glyph) in the action bar's
  top-right corner using `Widget.App.ActionBar.SecondaryButton` (the "icon
  on top" pattern — see that style's doc comment in `themes.xml`) — the
  same icon button geometry as every other action-bar shortcut icon in the
  app. It performs the same save-in-place action the old button did (does
  not also close the screen). First use: `CampaignDetailActivity` (removed
  the bottom `btn_campaign_save` MaterialButton; the icon reuses that
  view's id, now an `ImageButton` in `action_bar`, recolored for AMOLED the
  same way `btnBack` already is). Same day, same treatment on
  `CharacterCardActivity` (`btn_card_save`, covers both Roleplay Character
  and Party Member) and `WorldDetailActivity` (`btn_world_save`) — every
  bottom-Save-button roleplay card editor now matches.
- **App button styles (owner naming, July 18 2026 — supersedes any earlier
  button-styling instruction unless the owner directs otherwise).** Three
  named button styles, `values/themes.xml`, each a standalone style a
  `MaterialButton` opts into with `style="@style/AppButton.<Name>"` — none
  of them apply silently to unstyled buttons app-wide (that stays the job
  of the separate, pre-existing `materialButtonStyle`/`App.Button` theme
  default, untouched by this ruling):
  - `AppButton.Primary` — filled, same look as the app's plain default
    `MaterialButton` (`Widget.Material3.Button`, `colorPrimary` fill), 4dp
    corner via `@dimen/button_corner_radius`. Applied to the Import/Export
    buttons on Memory Backup & Restore.
  - `AppButton.Destructive` — outlined/unfilled
    (`Widget.Material3.Button.OutlinedButton`), 4dp corner via the same
    dimen. Applied to the Reset button on Memory Backup & Restore.
  - `AppButton.Secondary` — **NOT YET DEFINED.** The owner has not decided
    its look. Do not invent it or guess at a look for it — stop and ask.
  These replace the earlier `Widget.App.Button.Sharp`/
  `ShapeAppearance.App.Button.Sharp` names from the same day, which were
  never applied to any button and are retired.
  Both defined styles share the corner radius via `@dimen/button_corner_radius`
  (`values/dimens.xml`) — change that one dimen to reshape both at once.
  - `AppButton.Primary.Dialog` — **the dialog action button (owner ruling,
    July 19 2026).** A child of `AppButton.Primary` that inherits everything
    (fill, corner, text appearance, theme color roles) and overrides ONLY the
    width: a full-width primary button is too wide inside a dialog, so this
    variant sizes to a **percentage of the dialog content width** (best
    practice: percentage, never a hard px/dp), done via `layout_width=0dp` +
    `layout_constraintWidth_percent` (currently `0.6`) + centring constraints
    baked into the style. The percentage lives in that ONE style item — change
    it there to reshape every dialog action button. Requires a
    `ConstraintLayout` parent; use the shared `layout/dialog_single_action.xml`
    (a ConstraintLayout holding one `@id/btn_dialog_action` button with this
    style) as a dialog's `setView`, alongside the dialog's own themed
    title/message. First use: the Framing screen's "Unable to Open Image"
    load-error dialog.
  - `AppButton.Primary.DialogAction` / `AppButton.Destructive.DialogAction`
    — **the two-button dialog action row (owner ruling, July 20 2026).**
    Same percentage-width idea as `AppButton.Primary.Dialog` above, but for
    a primary + a destructive button side by side instead of one centered
    button — each is `layout_width=0dp` + a shared
    `layout_constraintWidth_percent` (currently `0.42`), with the chain
    between the two buttons (primary first/start, destructive second/end)
    defined in the shared layout rather than the style, since a chain needs
    both buttons' ids. Use the shared `layout/dialog_two_actions.xml`
    (`@id/btn_dialog_primary_action` then `@id/btn_dialog_destructive_action`)
    as a dialog's `setView`. First use: `DiscardChangesDialog`.
- **Dialog theme (standardization, July 19 2026).** `App.MaterialAlertDialog`
  (`values/themes.xml`, parent `MaterialAlertDialog.Material3`; referenced in
  code as `R.style.App_MaterialAlertDialog`) is THE standard dialog theme —
  build every `MaterialAlertDialogBuilder` with it so window/shape/title/body
  colors resolve through Material3 roles and future palettes retheme dialogs
  with no per-dialog changes. It wires the text-action buttons via
  `App.PositiveButtonStyle`/`App.NegativeButtonStyle` — that flat-text-button
  pairing is still correct for ordinary dialogs (e.g. plain Yes/No delete
  confirmations). For a filled primary action inside a dialog use a custom
  `setView` with `AppButton.Primary.Dialog` (above) instead of a bare
  `setPositiveButton`; for a primary+destructive pair (e.g. an unsaved-
  changes confirm) use a custom `setView` with
  `AppButton.Primary.DialogAction`/`AppButton.Destructive.DialogAction` via
  `dialog_two_actions.xml` (above) instead of a bare
  `setPositiveButton`/`setNegativeButton` pair.
- **Shared chevron-row styles (owner ruling, July 18 2026; expanded same
  day — leading-icon variant, list-screen manager/picker split).** Every
  "chevron row" in this app (a tappable settings-style row: an optional
  leading icon, a title, an optional subtitle, and a right-facing chevron)
  used to be hand-copied raw XML in every screen's layout file — no shared
  style existed, so screens only matched each other by luck/care, not by
  anything enforced. There is now one set of shared styles, values taken
  from Memory Manager's rows (`activity_memory_manager.xml` — the owner's
  named reference copy). A row is built from up to 6 pieces — **apply them
  together, in order**, to build a matching row:
  - Outer container — pick ONE of two variants (both share the same base
    geometry: 56dp minimum height, 12dp vertical padding, ripple
    background, horizontal `LinearLayout`):
    - `Widget.App.Row.WithSubtitle` — row has a subtitle line.
    - `Widget.App.Row.TitleOnly` — row has no subtitle.
  - `Widget.App.Row.Icon` (optional, added July 18 2026) — a leading 36dp
    icon/avatar slot, the row's FIRST child when present, before the text
    column. Sized to match the leading identity icon already used on
    memory rows (`view_memory_row.xml`). Carries **no tint of its own** —
    today's plain vector glyphs set `android:tint` per instance (usually
    `?attr/colorPrimary`), so a future avatar/photo (Characters, Personas
    don't have one yet) can sit in the same slot unforced into one color.
  - `Widget.App.Row.TextColumn` — the inner vertical column holding the
    title + subtitle (a `LinearLayout` inside the container).
  - `Widget.App.Row.Title` — the title `TextView`. **Always this exact
    style, regardless of subtitle or icon presence** (owner requirement,
    July 18 2026) — every row variant points at the one shared definition,
    never a per-variant copy, so a title's font/size/color can never drift
    between variants.
  - `Widget.App.Row.Subtitle` — the subtitle `TextView` (omit the view
    entirely for a row with no subtitle). One-line ellipsis by default; a
    genuinely long description (e.g. the Model Rules row on AI System
    Settings) overrides `android:maxLines`/`android:ellipsize` on that one
    instance rather than truncating real content.
  - `Widget.App.Row.Chevron` — the trailing chevron `ImageView`. Bakes in
    the icon (`ic_chevron_right`) and its tint; an instance only needs its
    own `contentDescription`.
  A separate, parallel shape (added July 18 2026) for rows that flip a
  boolean instead of navigating anywhere — **not built from the pieces
  above**, since it needs neither a chevron nor the clickable/ripple/
  minHeight geometry (only the switch itself is the tap target, not the
  row):
  - `Widget.App.Row.Toggle` — the outer container in place of
    `WithSubtitle`/`TitleOnly`. Not clickable, no ripple, no minHeight —
    matches, unchanged, how the app's one existing toggle row looked
    before conversion. Always paired with a subtitle; there's no
    title-only toggle variant today.
  - Still use `Widget.App.Row.TextColumn` / `Title` / `Subtitle` inside it
    (same styles, same theming, same drift-proofing as every other row).
  - `Widget.App.Row.Switch` — a `MaterialSwitch` in place of the chevron,
    trailing the text column.
  First (and so far only) use: the "Automatically Apply Model Rules" row,
  AI System Settings.
  Per-row content (`android:id`, text, content descriptions, click
  listener) stays on the instance as always; only the repeated look
  attributes move into the styles. The very first row on a screen
  additionally gets a manual `android:layout_marginTop` on the container —
  spacing below the screen's header, not part of the row's own look,
  deliberately not baked into the shared styles.
  **Plain-language shorthand (owner's own words, so the owner never has to
  say a style class name):** when the owner asks for a "title only row",
  build it from `Widget.App.Row.TitleOnly` + `Widget.App.Row.TextColumn` +
  `Widget.App.Row.Title` + `Widget.App.Row.Chevron`. When the owner asks
  for a "row with a subtitle" / "row with title and subtitle", swap in
  `Widget.App.Row.WithSubtitle` and add `Widget.App.Row.Subtitle`. Either
  one "with an icon" / "with an image" additionally means add
  `Widget.App.Row.Icon` as the first child, before the text column. A
  "row with a toggle and subtitle" means `Widget.App.Row.Toggle` +
  `Widget.App.Row.TextColumn` + `Widget.App.Row.Title` +
  `Widget.App.Row.Subtitle` + `Widget.App.Row.Switch` — no chevron, ever.
  These five phrases are the complete vocabulary — there is no sixth or
  seventh row shape today.
  **Fragile gotcha — already crashed CharactersActivity once (July 18
  2026):** `Widget.App.Row.Title`/`Subtitle`/`Chevron` resolve color
  through custom theme attributes `appRowTitleColor`/`appRowSubtitleColor`
  (`values/attrs.xml`). These MUST be given a value on
  **`ThemeOverlay.Phosphor.Violet`** — the palette overlay
  `ThemeManager.applyPalette` forces on top of everything at runtime, in
  `onCreate` right after `super.onCreate()`. Defining them only on
  `Theme.App` is not enough: the overlay is the last theme layer applied
  and the only one guaranteed present when a row resolves its color. A
  future palette (a second `ThemeOverlay.Phosphor.*`) must define these
  same two items too, or its screens crash the same way the moment they
  render a row.
  **List-screen pattern (System Prompts / Activation Prompts libraries,
  July 18 2026):** both are `ListView`+`BaseAdapter` screens with two
  distinct uses — manager mode (browse/edit, opened from a settings
  screen) and pick mode (choosing one for a chat, opened from Quick
  Settings). Manager mode uses `Widget.App.Row.TitleOnly` + chevron (no
  content preview — any description/preview text is dropped, owner
  decision). Pick mode is UNTOUCHED: it keeps its own "checked tile"
  layout (the currently-picked item drawn highlighted/filled). Each mode
  has its own item layout XML; the adapter picks one via a `pickMode:
  Boolean` fixed for the adapter's whole lifetime (read once from the
  activity's intent extra), so the two never mix within one instance's
  recycled views. Don't touch pick mode when converting a manager-mode
  list unless the owner asks for that specifically.
  **The Personas/Companions list is DIFFERENT (owner ruling, July 19 2026
  evening — supersedes the July 18 description above, which wrongly
  called it a "checked tile" pick-mode scheme with no chevron):**
  `PersonasListActivity`/`view_persona_item.xml` is a single screen with
  no `pickMode` split, used identically whether reached from Characters,
  Quick Settings, or ChatActivity's "create first companion" prompt. It
  never shows which companion is "currently active" — no highlight, no
  color inversion, ever; that concept lives entirely in Quick Settings and
  the last-used-companion logic (see "New-chat companion selection" below
  for how last-used is recorded). Tapping a row always opens that
  Companion's edit screen (`EditPersonaActivity`) — matching System
  Prompts' manager-mode row exactly (`Widget.App.Row.TitleOnly` +
  `TextColumn` + `Title` + `Chevron`) but with a leading
  `Widget.App.Row.ProfileImage` for the Companion's picture — there is no
  separate "select" tap action on the row at all. Picking a companion from
  Quick Settings still works: opening a row's editor and hitting Save
  calls the same `finishWithActive` the row tap used to call directly, so
  the result still flows back to Quick Settings/Characters — it just takes
  an extra explicit step (open, then Save) instead of an instant tap.
  **Caveat the owner should know:** a style only fixes how a view looks —
  it can't guarantee a new row's structure is right (that all pieces
  exist, in order, each with the correct style). Using these styles makes
  a mismatched row far less likely than raw copy-paste, but it does not
  make one impossible; nothing yet enforces the structure itself (that
  would need a shared reusable layout template, not yet built).
  **Rollout status (July 18 2026).** Converted: `activity_characters.xml`
  (3 rows, WithSubtitle, no icon); `activity_ai_system_settings.xml` — ALL
  4 rows: API endpoint profiles, System prompts, Model Specific Rules
  (WithSubtitle, no icon), plus Automatically Apply Model Rules
  (`Widget.App.Row.Toggle`, the first and only use of the toggle shape so
  far); the System Prompts library's manager mode
  (`view_system_prompt_item_row.xml`, TitleOnly + chevron); the Activation
  Prompts library's manager mode (`view_activation_prompt_item_row.xml`,
  TitleOnly + chevron, no preview text); the four "General" entries on the
  main Settings screen (`activity_settings.xml` — Characters, AI System
  Settings, Memory Manager, Roleplay — WithSubtitle **+ Icon**, the first
  and only use of the icon variant so far, a deliberately small review
  slice); the Memory Manager hub's own rows (`activity_memory_manager.xml`
  — 6 rows, all WithSubtitle except Memory Backup & Restore, which stays
  TitleOnly since no subtitle wording has ever been owner-approved for
  it); the Roleplay hub's own rows (`activity_roleplay_hub.xml` — 5 rows,
  all WithSubtitle); the Profile Image Properties screen's own rows
  (`activity_profile_image_properties.xml` — 3 rows, all WithSubtitle; built
  this way from its Phase 6 commit, predating this rollout note — flagged
  July 19 2026 as already matching, not newly converted). **Not converted:**
  every sub-screen reached from
  Memory Manager or the Roleplay hub (Memory Browser, Memory Assistant,
  Lorebooks, Memory Controls, Advanced Memory Settings, the roleplay card
  lists, etc. — none of these were touched); every other tile on the main
  Settings screen (Image Generation, Appearance, Experimental, Voice,
  Other, Debug categories) — those are built on `TileFragment`, which
  supports checkable/toggle tiles with long-press-for-description
  dialogs, not just a plain on/off switch (checked state also swaps
  title/subtitle text and fades between two background drawables) —
  `Widget.App.Row.Toggle` covers a plain switch row, not that richer
  behavior, so converting those tiles still needs its own decision, not
  just reuse of this style as-is — deliberately deferred pending the
  owner's review of the slices already built. Do not roll this out
  further without the owner's go-ahead.
  **Rollout status update (July 19 2026, evening).** Converted the
  Personas/Companions list (`view_persona_item.xml`,
  `PersonaListItemAdapter.kt`) from its old raw hand-built row (a custom
  `ConstraintLayout` with a manually swapped background/text-color
  "selected" state and a plain `ImageButton` chevron) to the shared
  styles: `Widget.App.Row.TitleOnly` + `Widget.App.Row.ProfileImage` (the
  leading Companion picture, unchanged in size/position — still 48dp) +
  `Widget.App.Row.TextColumn` + `Widget.App.Row.Title` +
  `Widget.App.Row.Chevron`. This is the **first real use of `TitleOnly` +
  `Icon` together** (every prior icon/avatar row use — the four "General"
  Settings entries — paired the icon with `WithSubtitle`); the
  "with an icon" shorthand already covered this combination, it just
  hadn't been built yet. See "The Personas/Companions list is DIFFERENT"
  above for the interaction-model change (no highlight, tap opens the
  editor) that came with this — it is not a pure visual conversion like
  the other rows in this list.
- **Shared screen-header styles (owner ruling, July 19 2026).** The solid
  bar with a back chevron and a title at the top of a full-screen activity
  used to be hand-copied raw XML in every screen, same problem the row
  styles fixed for rows. `values/themes.xml` now has
  `Widget.App.ActionBar` (the bar container) +
  `Widget.App.ActionBar.BackButton` + `Widget.App.ActionBar.Title` — a
  screen only gives each piece an id (plus its own title text); size,
  color, padding and the pin-to-parent/centering constraints all live in
  the styles. Title size is **24sp** (bumped up same day from the 20sp it
  first shipped at on Profile Image Properties/Default Images, to match
  the size every other screen's title already used — one dimen-like style
  item, change it once and every screen using the style follows).
  **Rollout status:** `ProfileImagePropertiesActivity`,
  `DefaultImagesActivity`, `activity_characters.xml` (Characters),
  `activity_persona_list.xml` (Companions), and
  `activity_activation_prompt_list.xml` (Activation Prompts) all use it
  directly — same three style references, same look.
  **`MemoryScreenActivity`-based screens** (Memory Browser, Model Rules,
  Tags, Worlds, Campaigns, Party Members, Roleplay Characters, My
  Personas, etc. — 12 screens total) all share one scaffold layout,
  `activity_memory_list.xml`, whose header reserves trailing space for an
  action icon, a secondary action icon, a filter button and a mode
  toggle — several screens (e.g. Memory Browser) genuinely need that, so
  the shared file couldn't just be restyled without affecting them. Six of
  the twelve never wire any of that (no action icon, no secondary action,
  no filter, no mode toggle): **My Personas, Roleplay Characters, Worlds,
  Party Members, Campaigns, and Tags.** For those six,
  `MemoryScreenActivity` gained one small override hook,
  `contentLayoutRes()` (default `R.layout.activity_memory_list`), and each
  of the six overrides it to point at a second, shared scaffold instead —
  **`activity_memory_list_simple.xml`** — which uses the plain
  `Widget.App.ActionBar` header (same as Characters/Companions/Activation
  Prompts) instead of the button-aware title. The other six
  `MemoryScreenActivity` screens are untouched and still get the original
  scaffold. `activity_memory_list_simple.xml` keeps every id the base
  class reads for the pieces these six screens actually use (action bar/
  back/title, search bar, empty view, list view, add FAB) and simply omits
  the ids for pieces none of them use (action/secondary-action buttons,
  filter bar/button, mode-toggle row) — safe because the base class's
  `findViewById` calls for those are all null-tolerant and only reachable
  when a screen opts into that feature; it's one file so a header tweak
  still only needs one place changed, matching the six screens exactly
  the same way the row styles keep rows in sync. Before adding a new
  `contentLayoutRes()` override to a screen, check it doesn't override
  `actionIcon()`/`secondaryActionIcon()`/`showFilterBar()`/
  `showFilterButton()`/`showModeToggle()` first — any of those means it
  still needs the original scaffold. Also converted directly (plain
  screens, same three-style pattern as Characters/Companions/Activation
  Prompts, no structural quirks): `activity_alert_debug_menu.xml`
  (Alerts, Errors & Logs), `activity_voice_advanced.xml` (Advanced Voice
  Settings), `activity_audio_debugging.xml` (Voice Debugging tile).
  **The "icon on top" pattern (owner ruling, July 19 2026):** the first
  pass through this rollout flagged two screens as not fitting the plain
  header — both were then actually handled, not left as-is, and the fix
  in each case is now the standing pattern:
  - `activity_logs.xml` (the four log pages — Crash/Error, Event/Voice
    Debug, Memory, Performance — all share this one layout via
    `LogsActivity`) had no `action_bar` bar (title floated directly on the
    screen background, no colored bar) and, on the Event log only, a real
    second icon button (`btn_voice_advanced`, the terminal-icon jump to
    Advanced Voice Settings) that plain `Widget.App.ActionBar` has no slot
    for. Fixed with a new style, **`Widget.App.ActionBar.SecondaryButton`**
    (`values/themes.xml`, right next to `.Title`) — same geometry/
    background as `.BackButton`, anchored to the bar's END instead of its
    START; `android:src`/`contentDescription`/`tooltipText` stay on the
    instance since the icon differs per screen. Logs now uses the full
    trio (`Widget.App.ActionBar` + `.BackButton` + `.Title`, title
    unchanged — still centered across the WHOLE bar, ignoring the icon,
    exactly like it already floated before conversion) plus
    `.SecondaryButton` for the voice-advanced shortcut.
    **Any future screen that needs a trailing icon alongside the back
    button should use `.SecondaryButton` the same way — that is the
    pattern, not hand-copied XML.** `LogsActivity.kt`'s AMOLED recolor was
    also brought in line with the standard tint-list approach (`actionBar`
    field + `backgroundTintList`, dropping the old `getDisabledDrawable`/
    `getDisabledColor` "muted" look that predated the shared style).
  - `activity_settings.xml` (the main Settings screen, `title_control_center`
    — renamed from "Control center" to "Settings" July 19 2026) was a
    slide-in side panel whose back button + title lived INSIDE the same
    big scrollable `ConstraintLayout` as every category header and tile,
    so the header scrolled away with the body — the owner then asked for
    it pinned. Restructured: a new `action_bar` (the standard
    `Widget.App.ActionBar` trio) is now a sibling ABOVE the `ScrollView`
    (itself now wrapped, with the `ScrollView`, in a new `content`
    ConstraintLayout so the style's `0dp`-width-via-constraints trick has
    a `ConstraintLayout` parent — it doesn't work inside the outer
    `LinearLayout`), and `scrollable`'s first child now anchors to its own
    top instead of to the old (now-relocated) title view. `btn_back` and
    `activity_new_settings_title` kept their exact ids so the screen's
    ~50-entry shared-element-transition exclude list needed only one
    addition (`R.id.action_bar`, mirroring how `R.id.scrollable` was
    already excluded) — nothing else in that list changed. The status-bar
    inset that used to land on `scrollable` now lands on `action_bar`
    instead (`adjustPaddings()` split into two `WindowInsetsUtil` calls);
    the nav-bar + 48dp bottom inset stays on `scrollable`, unchanged.
    AMOLED recolor for the new bar/back-button follows the same
    tint-list pattern as everywhere else (and drops the same
    `getDisabledDrawable`/`getDisabledColor` "muted" look Logs also had —
    this pre-shared-style convention appeared in both places and is now
    retired in favor of the standard look).
  **Next batch (July 19 2026), after an app-wide audit for every screen
  with a raw header — chat screens excluded, they're inherently
  different):**
  - `LocalWhisperManageActivity`/`LocalWhisperModelsActivity`
    (`activity_local_whisper_manage.xml`/`activity_local_whisper_models.xml`)
    — the same old floating-title-no-bar look Logs had; converted the same
    way, `getDisabledDrawable`/`getDisabledColor` dropped from both in
    favor of the tint-list pattern.
  - `LoreBookEntriesActivity`/`LoreBooksListActivity`
    (`activity_lorebook_entries.xml`/`activity_lorebooks_list.xml`) — both
    already used the exact tint-list AMOLED pattern and
    `btn_accent_icon_large_100`-based icon geometry, so this was a pure
    XML swap with zero Kotlin changes. Entries has two chained icons
    (edit-book, debug); per `Widget.App.ActionBar.SecondaryButton`'s own
    doc comment, only the edge-anchored one (`btn_debug`) uses the style —
    the inner one (`btn_edit_book`) keeps its geometry written out
    directly since it chains `layout_constraintEnd_toStartOf` the other
    icon instead of the parent edge.
  - `LogitBiasConfigListActivity`/`LogitBiasConfigActivity`
    (`activity_logit_bias_config_list.xml`/`activity_logit_bias_list.xml`)
    — same trio + `SecondaryButton` for the help icon; also dropped a
    redundant `android:backgroundTint="?attr/colorSurfaceContainerHigh"`
    on the back/help buttons that the `btn_accent_icon_large_100` drawable
    already bakes in as its own fill color — removing it changes nothing
    visually. `LogitBiasConfigActivity` (the single-set editor) does no
    AMOLED recoloring at all for this screen and still doesn't; that gap
    predates this change and wasn't introduced by it.
  - `VoiceSettingsActivity` (`activity_voice_settings.xml`, "Voice &
    Speech") — the same `expandable_window` slide-in-panel header-scrolls-
    away shape Settings had, fixed the same way (header pinned above the
    `ScrollView`, `adjustPaddings()` split between `action_bar` and
    `scrollable`). Only one child under `expandable_window` here (no
    `thread_loading`-style overlay to preserve stacking order for), so the
    outer `LinearLayout` converts to `ConstraintLayout` directly instead
    of needing Settings' extra `content` wrapper. This screen previously
    had no AMOLED recolor at all for its header bar/back button (there
    was no bar); both now follow the standard tint-list pattern like
    every other converted screen.
  **Deliberately kept on raw XML (owner ruling, July 19 2026) — the
  Profile Images gallery** (`activity_profile_images.xml`): the owner
  reviewed it and likes the current look, so it stays as-is rather than
  being converted to the named styles. Its header is genuinely more
  complex than any converted screen: ONE set of slots serves TWO
  mutually exclusive modes (normal: back arrow + centered "Avatar Image
  Gallery" title; selection mode: a cancel-X in the back slot + a live
  "N Selected" count in the title slot), toggled by visibility in
  `ProfileImagesActivity` — not a shape any current style variant
  covers. Verified it still properly connects to the same underlying
  tokens as every converted screen even though it doesn't reference the
  named styles: `?attr/colorSurfaceContainerHigh` bar background,
  `@drawable/btn_accent_icon_large_100` buttons, the same `ic_back`/
  `ic_close` icons. One real divergence: its title is still **20sp**,
  one step behind the 24sp the rest of the app was bumped to same day —
  left alone since changing it would be a visual change beyond what was
  asked. Also notable (not a defect, a preview of where the app is
  headed): `ProfileImagesActivity` never calls
  `ThemeManager.applyTheme(this, amoled)`, only `applyPalette` — like
  `ProfileImagePropertiesActivity`/`DefaultImagesActivity`, the whole
  Profile Images area was built without the legacy per-Activity AMOLED
  pitch-black recolor logic the `Widget.App.ActionBar` guard comment
  says is being phased out, rather than carrying it in and later
  removing it.
  **The "close panel" pattern (owner ruling, July 19 2026) — Memory
  Filters panel** (`activity_memory_filter_panel.xml`): unlike the
  screens above, this one intentionally does NOT match the back+
  centered-title look — it's a slide-out filter panel closed with an X,
  not a screen navigated back from, and the owner liked that look as
  distinct. Formalized into its own named style family instead of
  staying hand-copied, so the next filter-style pop-out has something to
  reuse: `Widget.App.ActionBar.CloseButton` (mirrors `.BackButton`'s
  geometry, end-anchored, bakes in the close icon + generic
  `@string/btn_close`, overridable per instance — Memory Filters keeps
  its more specific `@string/mem_filter_close`, "Close Memory Filters")
  and `Widget.App.ActionBar.Title.LeftAligned` (left-aligned, ellipsized,
  20sp — deliberately not a size step of the main rollout, a distinct
  choice for this family — and NOT a child style of the plain `.Title`:
  a ConstraintLayout view can't cleanly carry both an inherited
  `layout_constraintEnd_toEndOf` and an added
  `layout_constraintEnd_toStartOf`, so the shared color/weight items are
  duplicated rather than inherited). `.Title.LeftAligned` hardcodes
  `layout_constraintEnd_toStartOf="@+id/btn_close"` — any screen using
  it must name its close button `btn_close`, the same convention
  `.BackButton` assumes for `btn_back`. The container stays plain
  `Widget.App.ActionBar`, unchanged. `MemoryFilterPanelActivity`'s
  Kotlin already used the full standard tint-list AMOLED pattern before
  this change, so only the XML moved to the new styles — no Kotlin
  edits needed.
- **Shared field label + box styles (owner ruling, July 20 2026).** The
  same "hand-copied attributes on every field" problem that button/row/
  header styles fixed above also existed for the label-above-a-box form
  fields first built on the roleplay character card
  (`activity_character_card.xml` — Species, Class, Core Personality,
  etc.): every field repeated the same label `TextView` attributes and
  the same `TextInputEditText` background/padding by hand. Two styles in
  `values/themes.xml` now own that:
  - `Widget.App.Field.Label` — the field-name `TextView` above the box:
    `?attr/colorPrimary`, 15sp, bold. Every field label uses this exact
    style so font/size/color can never drift between fields, the same
    way `Widget.App.Row.Title` already works for rows.
  - `Widget.App.Field.Box` — the input itself: `@drawable/bg_field_box`
    background (transparent fill, thin `colorOutline` stroke, ~4dp
    corners) + 12dp padding + 6dp top margin under the label. Height
    stays `wrap_content` per instance, never fixed by the style, so a
    one-line field and a five-line field share the same look without
    fighting each other's height — only the skin (background/padding) is
    shared, never the size.
  Deliberately **no Material floating/embedded hint labels anywhere** —
  the field name is always its own separate `Widget.App.Field.Label`
  `TextView` above the box, never a `TextInputLayout` hint. Per-field
  specifics (`inputType`, `minLines`, `gravity="top"`, `singleLine`, a
  field's own extra top margin) stay on the instance, same as every
  other shared style in this file.
  Because the box style points at a drawable rather than hardcoding a
  shape inline, a future visual change (thicker stroke, a shadow, a
  themed look for roleplay screens) is a change to that one drawable (or
  to which drawable the style points at), not a hunt through every
  field.
  - `Widget.App.Field.Hint` (added July 20 2026) — an optional small
    explanatory line under the Label, above the Box: 13sp,
    `@color/text_subtitle`, not bold, matching the size/color this app
    already uses for secondary text elsewhere (row subtitles, the
    character card's zone subtext). A field with no hint simply omits
    the `TextView`, the same way a subtitle-less row omits
    `Widget.App.Row.Subtitle`.
  **Rollout status:** `activity_character_card.xml` (reference example;
  none of its fields have a hint line, so `Widget.App.Field.Hint` isn't
  used there) and `activity_edit_persona.xml` ("Edit Companion" — Company
  name/Prompt/Activation prompt/Core lorebook, 3 of the 4 with a hint
  line; Company name has none, matching its pre-conversion look). Edit
  Companion previously used Material `TextInputLayout` with an embedded
  floating hint and `app:helperText`; both are gone now. One functional
  piece rode along with that removal: the Company-name field's empty-name
  validation used `TextInputLayout.error` — replaced with a plain
  persistent `TextView` (`text_field_label_error`, `?attr/colorError`,
  13sp, `GONE` until `EditPersonaActivity.save()` sets it), matching this
  file's own "field errors set on the input itself, never a toast"
  house rule instead of Material's built-in error styling.
  **Second use (July 20 2026):** `activity_edit_user_persona.xml`
  ("Edit Persona" / "Persona Creation" — the My Personas editor,
  `EditUserPersonaActivity`, replacing the old `EditUserPersonaDialogFragment`
  pop-up). Same three field styles, same header trio +
  `Widget.App.ActionBar.SecondaryButton` (Save) + the Edit-Companion-style
  `btn_delete` icon chained off it, same `DiscardChangesDialog` +
  `dialog_two_actions` delete-confirm shape. Deliberately no picture at the
  top (unlike Edit Companion) — the first field label keeps Edit Companion's
  exact top offset (138dp) reserved for one anyway, so a future persona
  picture doesn't need a relayout. One field is new to this pattern: Short
  Description, a `Widget.App.Field.Box` capped at the My Personas list row's
  3-line subtitle limit — read live off the box's own `Layout` (line-start of
  the would-be 4th line) rather than a guessed character constant, since no
  fixed character maximum existed anywhere to reuse. It only recolors a
  counter + blocks Save with a Snackbar, never blocks typing. Not yet wired
  to storage (no backing column yet). Not yet applied to any other
  form-field screen beyond these two.
