# Work order: theme-readiness audit and conversion

This is a work order for a fresh session. It is not a feature specification.
Read `CLAUDE.md` first and follow it. The owner's current instructions outrank
this file, and so does `CLAUDE.md`.

## The goal, in the owner's terms

The owner intends to recolor the whole app later. Before that happens, every
color, size, and visual treatment must come from XML styles, theme attributes,
and resource files, so the recolor is a change in one place and not a hunt
through code.

This pass is **preparation only. Nothing should look different afterward.**
The conversion is a success when the app looks exactly as it does today and
every visual value has moved out of Kotlin and out of inline layout attributes.

A previous model already attempted this and the owner keeps finding problems
it missed. Do not trust any earlier claim that a screen is converted,
including the statuses in `ui-style-adoption.md`. Verify everything in the
current code.

## Rules that apply throughout

- Branch from `beta/new-client`. Never touch `main`.
- Read `ui-style-guide.md`, `ui-style-adoption.md`, and the
  "Everything uses styles; never hard-code appearance" ruling in `CLAUDE.md`.
- **AMOLED work is paused.** Leave every AMOLED branch and AMOLED drawable in
  place and working. Do not convert, fix, or extend it. When code has an
  AMOLED branch and a normal branch, convert only the normal branch, and make
  sure switching AMOLED off still restores the normal look.
- **No wording changes.** Do not touch user-facing text.
- **No logging changes** of any kind without asking first.
- If a change would visibly alter any screen, stop and ask the owner before
  making it, with a description of the before and after.
- Do not spawn a swarm of subagents. Ask before using more than one, or
  before using a high-end model for one.
- Ask in ordinary chat, one focused question at a time.

## What "properly done" means for one element

An element is converted only when **all** of these hold:

1. Its layout entry holds structure only: ID, text and content references,
   and constraints or positions relative to other elements, plus a `style=`.
2. Every visual value (colors, backgrounds, tints, sizes, margins, padding,
   text size, weight and color, corner shapes, minimum heights) comes from
   that style, a theme attribute, a `dimens.xml` entry, or a drawable or color
   resource that itself uses theme attributes.
3. No Kotlin code sets its appearance, except to restore the XML look after
   the paused AMOLED branch has overridden it.
4. Elements that show the same kind of thing use the same style.

## Known ways this app sets appearance outside XML

Look for every one of these. Each is a place a recolor would miss.

1. **Tinting a shared drawable from code.** `ThemeManager.applyTheme` used to
   tint `btn_accent_tonal_v4`, `btn_accent_tonal_v5`, and
   `btn_accent_icon_large_100` at runtime. Because Android shares one state
   among every copy of a drawable, tinting one copy without `.mutate()`
   repaints that drawable **everywhere in the app**. That means the XML color
   in such a drawable may not be what the owner actually sees. Before
   converting anything that uses a tinted drawable, work out what color is
   really on screen. Search for `DrawableCompat.setTint`, `setTint(`,
   `setTintList(`, and `backgroundTintList =`, and check whether `.mutate()` is
   used.
2. **Material elevation colors computed in code.**
   `SurfaceColors.SURFACE_0` through `SURFACE_5` appear in about 60 Kotlin
   files. `ScreenChrome.apply` centralizes the settings-screen header and
   window colors, but still computes them in code.
3. **Colors resolved and applied in Kotlin.** `setBackgroundColor(`,
   `setTextColor(`, `getColor(R.color.`, `ColorStateList.valueOf(`,
   `window.statusBarColor`, `window.navigationBarColor`. At the time of
   writing there were about 370 color-setting calls across 74 Kotlin files.
   Some only apply a theme attribute and may be acceptable; each must still
   be checked and listed.
4. **Inline values in layouts.** `android:background=`, `android:tint=`,
   `app:tint=`, `android:textColor=`, `android:textSize=`, and fixed `dp`/`sp`
   sizes written directly on views instead of in a style.
5. **Hard-coded colors.** `#RRGGBB` values in layouts, drawables, and styles,
   and fixed `@color/` palette entries where a theme attribute should be used.
6. **Icons that depend on code for their color.** Some vector icons have a
   black `fillColor` and no `android:tint`. They only look right because code
   or a style tints them. `ic_edit_square` and `ic_more_vert` are examples.
   Find every icon used in the app and confirm where its color comes from.
7. **Adapters and custom views.** `ModelListAdapter` and
   `FavoriteModelListAdapter` carry their own copy of the old tinting helper.
   Check every adapter, custom view, dialog builder, and pop-up for code-set
   appearance, not just activities.

## Reproducing a code-computed surface color in XML

The chat header shows the pattern. `bg_surface_level4.xml` and
`bg_surface_level4_rounded.xml` layer `?attr/colorPrimary`, at the opacity
Material uses for that elevation, over `?attr/colorSurface`, using the color
resource `color/surface_level4_overlay.xml`. That reproduces
`SurfaceColors.SURFACE_4` in XML.

The opacity for each level is `4.5 × ln(elevation in dp + 1) + 2` percent:

| Level | Elevation | Overlay opacity |
|---|---|---|
| SURFACE_0 | 0dp | none (plain `colorSurface`) |
| SURFACE_1 | 1dp | 5.12% |
| SURFACE_2 | 3dp | 8.24% |
| SURFACE_3 | 6dp | 10.76% |
| SURFACE_4 | 8dp | 11.89% |
| SURFACE_5 | 12dp | 13.54% |

Reuse the existing level-4 resources. Add the other levels only when needed,
following the same naming. This rests on the Material 3 theme using
`colorPrimary` as the elevation overlay color with no accent overlay. That
could not be checked against the library source from the work environment,
so the owner's on-device check is the confirmation. Say so when reporting.

## Headers

The owner has seen screens whose header is not the standard header,
including headers that do not reach the top edge of the screen. For every
full-screen activity, check:

- the header uses the `Widget.App.ActionBar` family described in
  `ui-style-guide.md`;
- the header reaches the top of the screen, so its color fills the status-bar
  area the same way as on correctly built screens;
- the header's colors come from styles and not from per-screen code.

List every screen that differs, with what differs, before changing any of
them. Bottom sheets and dialogs have no ActionBar header and are judged by
their own shared families.

## How to work

1. **Inventory first, change nothing.** Go screen by screen through every
   activity, fragment, dialog, bottom sheet, pop-up, adapter, and custom
   view. For each, record which of the known mechanisms above it uses, with
   file and line, and whether its header is standard. Use search to find
   candidates, then read the code; do not rely on search alone.
2. **Bring the inventory to the owner** as a short summary in plain
   language: how many places, grouped by kind, which screens are worst, and
   any place where the conversion would change how something looks. Ask the
   owner to confirm the plan and the order before converting.
3. **Convert in small batches.** Use one pull request per area (for example
   settings screens, chat, memory screens, dialogs), each reviewable on its
   own. In each batch:
   - move the values into existing shared styles where one fits; where none
     fits, stop and ask before creating one, unless the owner has approved
     the new style family in step 2;
   - remove the code that set the appearance, keeping the AMOLED branch
     working;
   - add a source contract test that fails if that area's code or layouts
     start setting appearance again (see `ChatHeaderStyleContractTest`);
   - update `ui-style-adoption.md` truthfully, marking a screen Shared only
     when every repeated element on it is converted.
4. **After each batch,** push, run Android Checks, and report the CI result
   separately from on-device behavior. Give the owner a short list of exactly
   what to look at on the phone to confirm nothing changed.

## Already done (verify it is merged before starting)

- The chat header (bar, title, every header icon, the error badge) uses the
  `Widget.App.Chat.Header*` styles, with no header color set from code
  outside the paused AMOLED branch. `ChatHeaderStyleContractTest` guards it.
- `btn_accent_tonal_v4`, `btn_accent_tonal_v5`, and
  `btn_accent_icon_large_100` draw the level-4 surface from XML, and
  `ThemeManager` no longer tints them in normal mode; it only clears a tint
  the AMOLED branch may have left.

- Every normal-mode case of code tinting a shared resource drawable was
  removed: `btn_accent_tonal` (level-5 surface), `btn_accent_icon_large`
  (level-5), `playground_bottom` (level-1), the model and TTS model list rows
  (now `bg_model_row` and `bg_model_row_selected`), and the Settings tiles
  (now `tile_settings_active` and `tile_settings_inactive`). Before this,
  these screens repainted drawables shared with memory rows, lorebook rows,
  prompt lists, and chat bubbles, so those looked different depending on
  which screen had been opened last. `SharedDrawableTintContractTest` guards
  it. Remaining `setTint` calls either use `.mutate()` first, sit in a paused
  AMOLED branch, or are in `ChatListAdapter`, which is only reached from the
  unused `ChatsListFragment`.

Everything else in the app is unverified. Text colors, icon tints, and other
appearance set from Kotlin (`setTextColor`, `imageTintList`,
`backgroundTintList`, `ScreenChrome`, `SurfaceColors` window colors) were not
part of that fix and still need the audit.
