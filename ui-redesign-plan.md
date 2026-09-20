# Phosphor Shines — UI Redesign Master Plan

**Date:** June 13, 2026 (revised June 17, 2026)
**Status:** Phase 1 implemented locally; Phase 0 completed previously. This document is the
specification for the "UI overhaul" referenced in CLAUDE.md's roadmap.

> **June 17, 2026 revision:** This plan was originally written while the
> floating phone-assistant overlay (`AssistantActivity` + `AssistantFragment`)
> still existed. **That overlay has since been removed entirely**, along with
> its OS entry points (device-assistant role, share sheet, `PROCESS_TEXT`
> text-selection action) and its orphaned resources/prefs. `ChatActivity` is
> now the **only** generation path and the **only** chat/voice UI. Every
> "mirror the change in `AssistantFragment`" / "two generation funnels"
> instruction below is therefore **obsolete** — there is exactly one of
> everything now. References to the assistant are kept struck-through or noted
> only so an agent reading an old commit isn't confused; do not try to restore
> it.

> **July 29, 2026 revision:** This plan is reconciled with the verified state
> of the app and with owner rulings made after June 17. The corrections:
>
> - **Phase status (verified in code):** Phases 0 and 1 are complete. Phase 2
>   shipped only a placeholder `ThemeOverlay.Phosphor.Violet` overlay carrying
>   the two shared row-color attributes — it is **not** an owner-chosen
>   palette, there is no picker, and no alternative palettes exist. Phase 3
>   (the drawer) has **not started**; no drawer code exists anywhere.
> - **Theme creation is deferred and no longer blocks other work.** The
>   original "theming first" ordering assumed the palettes would be designed
>   up front. The owner will instead design palettes **later, visually, in
>   `palette-designer.html`** (repository root) — that page is the official
>   tool for creating this app's themes. Until the owner hands over designed
>   palettes, the app runs on the Violet placeholder. The placeholder was
>   never chosen by the owner and will be replaced by an owner-designed
>   default palette when one exists; do not treat its colors as intent.
> - **The palette contract is the designer's zone list**, not a pure Material
>   color-role swap. See the Section 4 note: the designer defines ~25 named
>   zones (page background, input box, top bar, drawer, both message bubbles,
>   buttons, accent, selection, dividers, error/warning) **plus outline
>   colors 1–3, a glow color, and calm/vibrant treatments**. Outline
>   gradients and glow require hand-drawn backgrounds that read theme colors —
>   this is why the app's boxes are drawn rather than stock Material
>   components.
> - **A shared-style system now exists and is the component authority:**
>   `ui-style-guide.md` (style families and composition), `ui-style-adoption.md`
>   (per-screen conversion status), and the `Widget.App.*` / `AppButton.*`
>   families in `themes.xml`. Where Section 6 of this plan conflicts with the
>   style guide, **the style guide and owner rulings win** — notably: buttons
>   are semi-square (4dp corners, owner ruling: no pills), not Material tonal
>   pills. Screen-by-screen conversion to shared styles is the current
>   groundwork phase; it is what makes every screen palette-ready.
> - **AMOLED / palette / theme polish is paused** (owner ruling, July 26 2026
>   — see CLAUDE.md). The pause was precautionary so AMOLED work would not
>   interfere with the future theme system; the existing AMOLED code stays in
>   place and untouched until the owner reinstates that work.
> - **Section 7's screen inventory is stale.** The app has grown from ~30 to
>   ~70 activities (memory system, summarizer, profile images, local Whisper,
>   and more). `ui-style-adoption.md` is the living per-screen tracker;
>   Section 7 remains useful only for its risk notes and contracts.
> - **Open owner decision — do not assume:** when the drawer phase is
>   scheduled relative to the ongoing style conversion.

> **July 30, 2026 revision (owner ruling):** user-created custom themes are a
> **future product goal**, not a hypothetical. The first shipped theming is
> still a small set of polished pre-made palettes, but the architecture must
> keep the custom-theme route open. Section 4.5 records the ruling, the
> canonical palette contract, and the compatibility requirements that bind
> all migration work from now on. This ruling does **not** reinstate the
> paused theme work and does **not** authorize building the editor.

> **August 23, 2026 drawer/navigation revision (owner ruling):** the drawer
> has no separate header/name area. Its first item is **New Chat**. The former
> chat top-bar `btn_debug_log` shortcut has been removed and must **not** be
> preserved or restored; Logs is reached through the current chat overflow
> menu. The drawer's **Playground** item only links to the current Playground
> destination; drawer work must not rehost, refactor, or otherwise change the
> Playground implementation. Chat rows use a new drawer presentation rather
> than copying the old chat-list appearance. **Show Companion Images in Chat
> List** is a temporary Chat Settings toggle and defaults Off. App launch goes
> directly to a blank chat using the current blank-chat presentation rather
> than reopening the previously viewed conversation.

**Audience:** AI agents implementing the redesign. Read `CLAUDE.md` in full
before this document — every rule there still applies. This plan was written
after a complete inventory of all 30 activities, 20+ dialog fragments, and
88 layouts, with the load-bearing facts (view-ID contracts, theme structure,
dependency versions) verified directly against the code on commit `8e6584d`.

---

## 0. How to use this document

This redesign will likely be implemented across many sessions, possibly by
less capable models. The rules below exist because the riskiest failure mode
is not "ugly UI" — it is silently breaking the hands-free voice pipeline or
the per-chat preference system while restyling things.

**Rules of engagement (non-negotiable):**

1. **One phase — or one sub-step of a phase — per branch/PR.** Never combine
   a theming change with a layout redesign in the same PR. Small diffs are
   reviewable; 4,000-line diffs in this codebase get merged with bugs.
2. **CI is the compile gate.** There is no local Android SDK in agent
   sandboxes. Before pushing, statically verify: every `R.*` reference
   resolves, every new id exists in the layout actually inflated, XML is
   well-formed, imports exist. Push, then confirm the `Android Checks`
   workflow run is green before starting the next step.
3. **Restyle, don't refactor.** The redesign changes how screens *look*.
   It must not change how `ChatActivity`'s voice loop, streaming, or
   preference logic *works*. If a visual goal seems to require a logic
   change, stop and ask the owner.
4. **Never rename a view ID, widget type, drawable, or color that code
   references** without updating every reference — Section 9 lists the
   verified contracts. When in doubt, `grep` before touching.
5. ~~**Every change to chat input/messages UI must be mirrored** in both
   `ChatActivity` and `AssistantFragment` (the floating assistant). They are
   parallel implementations.~~ **Obsolete (June 2026):** the floating
   assistant was removed; `ChatActivity` is the only chat UI. There is no
   second implementation to mirror.
6. **If reality disagrees with this document** (the code moved on, an ID
   changed), trust the code, re-verify with grep, and update this document
   in the same PR.
7. Update `CLAUDE.md` and this file whenever a phase completes, so the next
   agent knows where the project stands. Add a ✅ and the merge commit hash
   to the phase list in Section 8.

---

## 1. Locked decisions (owner-approved, 2026-06-13)

These were decided explicitly by the owner. Do not re-litigate them.

| Decision | Choice |
|---|---|
| UI technology | **Stay on classic Views/XML + Material 3 (MDC-Android).** No Jetpack Compose, not even for new screens. |
| Navigation | **Left slide-out drawer** containing the chat list (New Chat, search) plus quick navigation (Characters hub, Playground, Settings). The chat screen becomes the effective home screen, like the ChatGPT/Claude apps. App launch opens a blank chat rather than reopening the last conversation. No right-side panel. |
| Theming | **Preset hand-designed color palettes** in light and dark variants, selectable in settings. Material You / wallpaper-dynamic color was explicitly **not** chosen. The existing AMOLED pitch-black mode must keep working. |
| Style target | Clean, elegant, modern Material 3 — rounded surfaces, proper spacing, M3 type scale. |

---

## 2. Research findings & library policy

### 2.1 The Material library situation (important)

- **MDC-Android (the Views Material library) 1.14.0 is the final stable
  release** (May 2026). The library is now in **maintenance mode** — only
  critical fixes, no new features. Google's investment has moved to Compose.
- The good news: 1.14 ships full **Material 3 Expressive** support for Views
  (the `Theme.Material3Expressive.*` themes, updated component styles,
  loading indicators, button groups, new shape/motion values).
- What this means for us: the Views stack is *stable and frozen*, which is
  actually fine for this app — no surprise breaking upgrades, and everything
  in this plan works with 1.14. The trade-off accepted by choosing Views is
  that we will never get future Material features; we get a polished
  snapshot of M3 instead.

**Action:** bump `com.google.android.material:material` from `1.14.0-beta01`
to the final `1.14.0` stable in Phase 0, then treat it as pinned (add it to
the "do not upgrade" list — there is nothing to upgrade to).

### 2.2 Edge-to-edge is mandatory

The app targets SDK 36 (Android 16). At target 36 the edge-to-edge opt-out
attribute is **deprecated and disabled** — the app cannot opt out of drawing
behind the system bars. Several activities already call `enableEdgeToEdge()`
and the themes use transparent system bars, but every *redesigned* screen
must handle insets deliberately (Section 6.6 gives the recipe). A recent
commit ("fix Voice settings insets") shows this has already bitten once.

### 2.3 Libraries to use (all already in the project)

| Library | Version | Role in redesign |
|---|---|---|
| `com.google.android.material:material` | 1.14.0 (bump from beta01) | All components, M3 themes, color roles |
| `androidx.appcompat:appcompat` | 1.7.1 | Base activities/widgets |
| `androidx.drawerlayout:drawerlayout` | 1.2.0 (add explicit dep) | The side panel. Currently only pulled in transitively; declare it explicitly when the drawer work starts. |
| `androidx.recyclerview` | (present) | Chat list inside the drawer |
| `androidx.constraintlayout` | (present) | Keep for existing screens |
| `io.noties.markwon:*` | 4.6.2 | Markdown rendering — **do not replace** |
| `com.github.bumptech.glide:glide` | 5.0.5 | Images/avatars — keep |
| `androidx.core:core-splashscreen` | 1.2.0 | Already present |
| `com.github.Dimezis:BlurView` | 2.0.6 | Used by debug overlays + chat attach background — leave as-is |

### 2.4 Libraries NOT to add (deliberate)

- **No Jetpack Compose** (owner decision).
- **No AndroidX Navigation component.** The app uses plain
  activity-to-activity intents everywhere; retrofitting a nav graph is a
  refactor with zero visual payoff and high regression risk.
- **No third-party drawer, theme-engine, animation (Lottie), or icon
  libraries.** Every dependency is a maintenance liability; M3 components
  cover everything this plan needs.
- **Never touch Ktor (pinned 2.3.12), OkHttp TLS defaults, or the OpenAI
  clients** as part of UI work. (See CLAUDE.md "Do not touch".)

---

## 3. Order of operations (why theming comes first)

The phases in Section 8 are ordered deliberately:

1. **Theme-attribute migration first** (invisible groundwork). Preset
   palettes require every layout and every Kotlin color lookup to go through
   *theme attributes* instead of hard-coded `@color/accent_*` references.
   If we restyled screens first and migrated colors later, every screen
   would be touched twice and the palette work would risk re-breaking
   finished screens.
2. **Palette system + picker second** — proves the foundation works while
   the UI still looks familiar.
3. **Navigation drawer third** — the biggest structural change, done while
   the chat screen internals are still untouched.
4. **Screen restyles last** — lowest-risk, screen-by-screen, each one a
   small PR.

---

## 4. Theming architecture (preset color palettes)

### 4.1 Current state (verified June 2026)

- `Theme.App` (parent `Theme.Material3.DayNight.NoActionBar`) in
  `res/values/themes.xml`, with a `values-night` variant. Per-activity
  overrides: `Theme.Transparent` (ChatActivity, SettingsActivity, permission
  activities), `UI.Fade`/`UI.Material` (animation variants of Theme.App),
  `Theme.PWA`. (`Theme.Assistant` belonged to the now-removed floating
  assistant; if it still lingers in `themes.xml` it is dead and may be dropped
  during a cleanup PR.)
- Colors are a tier system `accent_50 … accent_900` plus
  `window_background`, defined in `values/colors.xml` and `values-night/colors.xml`.
- **36 layout files reference `@color/accent_*` directly; zero layouts use
  `?attr/` theme attributes.** This is the main blocker for palettes.
- **AMOLED mode is not a theme.** It is runtime recoloring: ~29 Kotlin files
  contain `if (isAmoled)` blocks that call `setBackgroundColor`/`setTint`
  etc. with `amoled_*` colors read via
  `Preferences.getAmoledPitchBlack()`. `theme/ThemeManager.kt` only retints
  three shared button drawables (`btn_accent_tonal_v4`, `btn_accent_tonal_v5`,
  `btn_accent_icon_large_100`).

### 4.2 Target design

> **Superseded in part (July 29 2026):** palettes will be designed by the
> owner in `palette-designer.html` and handed over as exported zone values;
> the "suggested initial palettes" table below is historical — do not build
> those palettes. The overlay mechanism described here still applies, but a
> palette must define the designer's zones (including the custom attributes
> the style guide requires, e.g. `appRowTitleColor` / `appRowSubtitleColor`,
> and whatever attributes the drawn outline/glow backgrounds read), not only
> the Material color roles. Also verified the hard way (CharactersActivity
> crash, July 18 2026): the palette overlay must be the **last** theme layer
> applied at runtime, because it is the only layer guaranteed present when a
> screen resolves the custom attributes.

**One source of truth: Material theme attributes.** A "palette" is a
`ThemeOverlay` style that redefines the M3 color roles (`colorPrimary`,
`colorOnPrimary`, `colorPrimaryContainer`, `colorSecondaryContainer`,
`colorSurface`, `colorSurfaceContainer`, `colorSurfaceContainerHigh`,
`colorOnSurface`, `colorOnSurfaceVariant`, `colorOutline`, …). Light/dark
variation continues to come from `values/` vs `values-night/` color
resources referenced by each overlay — the DayNight mechanism is untouched.

**Suggested initial palettes** (final colors are a design task; each needs a
full M3 role set in light + dark):

| Palette key | Idea |
|---|---|
| `violet` | The current purple palette, repackaged. **Default** — existing users see no change. |
| `phosphor` | Green-on-dark, the app's namesake. |
| `ocean` | Cool blue/teal. |
| `ember` | Warm amber/orange. |
| `mono` | Neutral grayscale with a single restrained accent. |

**Selection mechanics:**

- Store the palette key in `GlobalPreferences` (e.g. `ui_palette`,
  default `violet`). Global, not per-chat.
- Extend `theme/ThemeManager.kt` with
  `applyPalette(activity: Activity)` that reads the preference and calls
  `activity.theme.applyStyle(R.style.ThemeOverlay_Phosphor_<Palette>, true)`
  **before `setContentView`** in `onCreate`. Every activity must call it
  (there is no BaseActivity in this codebase — adding one is optional but
  touching ~30 `onCreate`s with one line is the lower-risk path; the
  screen-by-screen checklist in Section 7 doubles as the list).
- The theme picker (new "Appearance" entry in SettingsActivity) calls
  `recreate()` on the current activity after saving. Other activities pick
  it up on their next `onCreate`. Add a swatch-row preview UI (a row of
  colored circles, selected one outlined — no live preview needed in v1).
- Dialogs and bottom sheets created from an activity context inherit the
  activity's theme (including applied overlays). **Verify this per screen**
  during the palette phase — `QuickSettingsBottomSheetDialogFragment` is the
  canary because it overrides `ThemeOverlay.App.BottomSheetDialog`.
- `Theme.Transparent` (ChatActivity!) also needs `applyPalette` — it inherits
  Material3 color roles even though its window background is transparent.

### 4.3 The migration (Phase 1) — mechanical but wide

For each screen (layout XML + its Kotlin file):

1. **Layouts:** replace `@color/accent_*` / `@color/window_background`
   references with the matching theme attribute. Mapping guidance:

   | Current usage | Replace with |
   |---|---|
   | `accent_900` as icon tint / emphasized text | `?attr/colorPrimary` (or `?attr/colorOnSurface` for plain text — judge by role, not by color value) |
   | `accent_500` filled-button/strong backgrounds | `?attr/colorPrimary` (+ `colorOnPrimary` content) |
   | `accent_100` / `accent_50` soft container backgrounds | `?attr/colorSurfaceContainer` / `colorSurfaceContainerHigh` or `colorSecondaryContainer` for tinted chips |
   | `window_background` | `?attr/colorSurface` (theme `android:windowBackground` keeps a concrete color) |

   Judge each occurrence by *what role it plays*, not by mechanical
   find-replace — an `accent_900` that colors body text is `colorOnSurface`,
   not `colorPrimary`.
2. **Kotlin:** replace `ContextCompat.getColor(context, R.color.accent_*)` /
   `ResourcesCompat.getColor(...)` with
   `MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)`
   (or the appropriate role). `MaterialColors` resolves against the view's
   themed context, so overlays apply correctly.
3. **Shared drawables** (`btn_accent_tonal*`, `btn_accent_icon_large*`,
   `expandable_window_background*`, `tile_inactive`, …): these are used by
   many screens at once. Migrate their internal color references to theme
   attributes (`?attr/...` works inside drawable XML on minSdk 28) — and
   test every screen that uses the drawable in the same PR, because one
   drawable edit restyles many screens simultaneously.
4. **Vector icons** currently tinted `@color/accent_900` per CLAUDE.md
   convention: change the convention to `?attr/colorPrimary` (update the
   CLAUDE.md coding-rules line when this lands).

**Colors that must NOT become palette-driven (keep as literal resources):**

- `mic_listening_green` and `hands_free_active_red` — these are *semantic
  voice-state* colors (recording / hands-free active). Users learn them;
  they must look identical in every palette. Used in `ChatActivity` and
  `ChatAdapter`, defined in `colors.xml`.
- Error/destructive reds in confirm dialogs (use `?attr/colorError` where
  it is already Material-managed, but don't map them to palette accents).

### 4.4 AMOLED interplay

Two-stage plan:

- **During Phases 1–2:** keep the existing runtime mechanism working. When
  migrating/restyling a screen, search its Kotlin file for
  `getAmoledPitchBlack` / `isAmoled` / `amoled_` and make sure those blocks
  still produce a correct pitch-black look on the new layout. Skipping this
  silently breaks AMOLED users (the owner uses dark themes; assume AMOLED is
  exercised).
- **Phase 2.5 (cleanup, optional but recommended):** once everything reads
  colors from theme attributes, AMOLED becomes just another overlay applied
  *after* the palette overlay, forcing `colorSurface`/`colorSurfaceContainer*`
  to pure-black tiers. Then the ~29 scattered `if (isAmoled)` blocks can be
  deleted screen-by-screen. Do not attempt this big-bang; one screen per PR,
  AMOLED toggled on in your head (or screenshot via CI artifacts if
  available) for each.

### 4.5 Custom user themes — future goal and binding compatibility rules (owner ruling, July 30 2026)

**The ruling.** Users will eventually be able to choose the colors for the
named visual zones inside an in-app editor, save the theme, and restart the
app to apply it. This is a committed future product goal. It is **not**
being built now — the first implementation remains pre-made palettes — and
this section does not reinstate the paused theme work or authorize the
editor or any runtime custom-palette engine. It exists so the ongoing
migration and future agents do not close off the custom-theme route.

**Restart-to-apply is the accepted model.** A custom theme may be loaded
only during app startup and may require an app restart after saving or
selecting it. Live whole-app recoloring of already-open screens is **not**
required and must not be used to argue the feature is infeasible.

**Do not treat the compiled-overlay limitation as a dead end.** It is true
that arbitrary user colors cannot become a newly compiled `ThemeOverlay` at
runtime. It is **not** true that custom themes therefore require permanent
per-screen recoloring like the legacy AMOLED mechanism, or that they would
undo the shared-style migration. The exact runtime application mechanism
for custom themes will be designed later; what matters now is preserving
the seams below.

**One canonical palette contract, two sources.** A `PaletteDefinition` is
the full set of values for the designer's zone list — the canonical
semantic contract for every palette, preset or custom. The zones (keys as
defined in `palette-designer.html`, the contract's source of truth):

| Zone key | Meaning |
|---|---|
| `pageBg` | Page background behind everything |
| `readSurface` | Input box fill |
| `readText` | Default text (input text and anything not set below) |
| `topbarBg` / `topbarText` | Top bar background; top bar text & icons |
| `drawerBg` / `drawerText` / `drawerSelectedBg` | Drawer background, text, current-chat highlight |
| `userBg` / `userText` | The user's message fill and text |
| `botBg` / `botText` | The AI's message fill and text |
| `edge1` / `edge2` / `edge3` | Outline colors 1–3 (calm uses only `edge1`; vibrant gradients use all three) |
| `glow` | Glow color (vibrant treatment only) |
| `divider` | Divider lines |
| `primaryBtnBg` / `primaryBtnText` | Primary action button fill and text |
| `secondaryBtnBg` / `secondaryBtnText` | Secondary/cancel button fill and text |
| `accent` | Accent (icons, send button, active states) |
| `selected` | Selection accent (open-chat bar, checked items) |
| `error` / `warning` | Error and warning colors |

plus the **calm / vibrant treatment** flag. Each zone comes in light and
dark variants. This contract — not a reduced set of stock Material color
roles — is what a palette defines. Zones may be *implemented* by mapping
onto Material theme attributes where a role fits exactly, but the contract
is the zone list, and distinct zones must remain independently settable.

The two sources for a `PaletteDefinition`:

1. **Preset palettes** (Phase 2): compiled `ThemeOverlay.Phosphor.*` styles,
   one per owner-designed palette. This mechanism stays.
2. **A future saved custom palette**: the same zone values read from
   storage at startup. Mechanism designed later.

**Binding compatibility requirements for all current and future work:**

1. **Single application point.** `theme/ThemeManager.applyPalette(activity)`
   is the only place a palette is applied, before `setContentView`, and the
   palette overlay is the last theme layer (see the 4.2 note). A future
   custom source plugs in here — do not add second palette-application
   paths, and do not resolve palette colors during static/class init where
   a startup-loaded palette could not reach them.
2. **Zones stay distinct.** Do not build assumptions such as "this
   component is violet," and do not conflate two zones because today's
   placeholder gives them the same color. Known conflation to resolve when
   theme work resumes: `bubble_out.xml` fills the user message bubble with
   `?attr/colorPrimary`, making `userBg` inseparable from `primaryBtnBg`;
   the designer treats them as separate zones.
3. **Shared styles, shared layouts, and custom-drawn backgrounds resolve
   colors through theme attributes** (zone attributes such as
   `appRowTitleColor`, or mapped Material roles) — never through
   palette-specific `@color/` values that an overlay cannot override.
4. **Centralize exceptional visuals.** Message bubbles, the future outline
   gradients and glow, button state lists, icon tints, dialogs, and other
   custom drawables get their colors from shared drawables reading theme
   attributes or from one shared code path (ThemeManager). A future runtime
   palette must never require color-handling code copied into every
   Activity — that is the legacy AMOLED failure mode, not the plan.
5. **Semantic non-palette colors stay literal** (4.3): voice-state colors
   and true error/destructive semantics are not palette zones' hostages —
   `error`/`warning` zones cover the palette-facing cases; the mic
   green/red never change. Whether the category identity tints
   (`cat_*`/`tint_cat_*`) stay palette-independent is an **open owner
   decision** — do not fold them into the contract without asking.

**Audit palettes (binding on Phase 2).** When preset theme work resumes,
use at least two visually different palettes as an audit tool: applying
substantially different colors must be expected to expose hardcoded
resources, unreadable state combinations, inherited dialog problems, icon
tints, disabled-state assumptions, and custom backgrounds not actually
connected to the palette system. **Record those gaps** (here or in
`ui-style-adoption.md`) rather than patching them with isolated per-screen
colors.

**Verified compatibility state (audited July 30 2026):**

- *Already two-source compatible:* the `AppButton.*` family (Material roles
  via theme), the shared button/surface drawables (`btn_accent_tonal*`,
  `btn_accent_icon_large_100`, `expandable_window_background_24`,
  `bg_attachment_tile`), both message-bubble drawables (`bubble_in`,
  `bubble_out` — but see the zone-conflation note above), the row
  title/subtitle/chevron system (`appRowTitleColor`/`appRowSubtitleColor`
  custom attributes — the proven pattern for the rest of the contract),
  layout XML generally (Phase 1 removed `@color/accent_*`), and 171 of 277
  drawables already resolving via `?attr/`.
- *Shared text styles — fixed July 30 2026 (owner-approved scoped
  exception to the theme pause):* the shared text styles that referenced
  `@color/text` / `@color/text_subtitle` / `@color/text_title` directly now
  resolve three new zone attributes — `appTextColor` (default text),
  `appSubtleTextColor` (hints, section explanations, secondary readouts),
  `appTitleTextColor` (screen/header titles and intro paragraphs) — and the
  dialog text buttons plus the bottom-nav active indicator now resolve
  `?attr/colorAccent`. The attributes default to the exact previous colors
  (no visual change) and are defined in every theme that defines the row
  attributes, including both night themes and the Violet overlay (the
  overlay-last rule applies to them equally). A palette — preset or custom
  — now reaches all shared-style text.
- *Kotlin runtime lookups:* 57 files still call
  `ContextCompat/ResourcesCompat.getColor` (only 4 use `MaterialColors`);
  51 files carry AMOLED runtime blocks. This is the already-documented
  deferred Kotlin pass — for the two-source model those lookups must end up
  resolving theme attributes via `MaterialColors` (or one ThemeManager
  helper), not raw color resources.
- *Zones with no implementation yet (green field):* `edge1–3`, `glow`,
  `divider`, the three `drawer*` zones (no drawer exists), `userText` /
  `botText` as distinct attributes, `secondaryBtn*` (Secondary currently
  inherits Primary by ruling), `selected`, `warning`, and the calm/vibrant
  flag. These must be **born** reading zone attributes — no retrofit needed
  if nothing hardcodes them first.
- *Hardcoded-hex drawables:* 23 files, almost all vector icons with baked
  fill colors (`ic_mem_*`, `ic_arrow_forward`, `ic_chevron_right`, …) plus
  a few one-off backgrounds (`bg_gallery_locked_badge`,
  `bg_gallery_tile_label`, `shadow_bottom`). Reconcile against the icon
  tint convention during the audit-palette pass; record, don't spot-patch.

### 4.6 Typography and text-size future-proofing (owner requirement, July 30 2026)

The owner may change the app's font later, and text sizes may need to
change for accessibility. Both are future-variable, like palette colors:
the architecture must keep each a central change, not a per-screen edit.
This section records compatibility requirements only — no font change and
no in-app text-size control is approved or scheduled.

**Fonts (verified July 30 2026 — already centralized).** The app font is
set once per theme (`android:fontFamily` on `Theme.App` and variants:
`@font/roboto_ttf` day, `@font/default_font` night); only two layout files
override it. A future font swap is therefore a theme-level change. Keep it
that way: do **not** scatter `fontFamily` into layouts or individual
styles, and route any style-level typeface need through a shared text
appearance so it still inherits the theme font decision. Because each text
role already has its own shared style (titles, hints, body, and so on), a
future *per-role* font — for example, hints in a different typeface than
titles — is a one-line addition to that role's shared style whenever the
owner wants it. No groundwork is required now; noted (owner, July 30 2026)
as a possibility, not a plan.

**Text sizes (verified July 30 2026 — accessible now, centralizing
gradually).** Every text size in the app is in `sp` (432 layout
declarations plus 17 in shared styles; zero `dp` text). Android's
system-level accessibility font scaling therefore already works app-wide.
Binding rules:

1. Text sizes stay in `sp` — never `dp` — and nothing may disable or
   clamp system font scaling.
2. As screens convert to shared styles (Phase 1.5), size and typography
   ownership moves into the shared styles / text appearances, per the
   existing 6.3 direction. That conversion is what turns a future
   app-wide size adjustment into a change in one place instead of ~400.
3. If an in-app text-size preference is ever approved, the two-source
   logic of 4.5 applies unchanged (e.g. compiled size-step overlays over
   shared text appearances). Do not build one, and do not hardcode
   assumptions that text sizes are permanent constants.
4. Restyles must be checked at a large system font scale — text that
   truncates, overlaps, or pushes controls off-screen at accessibility
   sizes is a bug, not an acceptable trade.

---

## 5. Navigation redesign — the side panel

### 5.1 What the drawer contains (top to bottom)

1. **New Chat** row (replaces the chats-tab FAB; opens the existing
   `AddChatDialogFragment`). There is no separate drawer header or app-name
   area above it.
2. **Search field** filtering the chat list (reuse the filter logic from
   `ChatsListFragment`'s `search_input`).
3. **Chat list** — RecyclerView, reusing `ChatPreferences` as the data
   source and existing chat-list behavior, but **not** its old visual design.
   The drawer list has its own approved presentation:

   - Add a temporary Chat Settings toggle labeled **Show Companion Images in
     Chat List**. Default: **Off**. Chat Settings is only the current home for
     this preference; it may be moved later without changing the behavior.
   - With the toggle **Off**, each entry shows **only the chat name**. Do not
     show the old snippet/model-label treatment and do not reserve an empty
     icon/image column. The name may occupy up to **two lines** and should be
     slightly larger than ordinary body/message text. Keep the exact text size
     in the shared style system rather than hardcoding a one-off value.
   - Text-only entries have generous vertical rhythm: leave approximately one
     full text-line of empty vertical space between neighboring chat names,
     visually equivalent to a blank paragraph line rather than a tight list
     margin.
   - With **Show Companion Images in Chat List** On, use a simple two-column
     row: companion/profile image on the **left**, chat name on the right. The
     name still uses up to two lines. Vertically center the image against the
     one-to-two-line text area.
   - The image should be approximately the visual height of two chat-name
     lines and must not be smaller than the companion image currently shown in
     the existing main chat list. Exact image size, column width, and local
     spacing may use implementation judgment to achieve that relationship.
   - The image sits along the **left side of the row**. Do not right-align it
     or place it after the chat name.
   - Highlight the currently open saved chat using the approved
     selected/drawer-selected theme role. A blank startup chat has no saved
     chat row to highlight until it becomes one under the app's existing chat
     behavior.
   - Follow `ui-style-guide.md` / `ui-style-adoption.md` for shared typography,
     colors, and theme readiness. Do not copy the old unconverted chat-list
     row styling wholesale.
4. **Divider**, then static nav rows: **Characters** (→ `CharactersActivity`
   hub: personas / activation prompts / system message / lorebooks),
   **Playground** (→ link to the current Playground destination only; do not
   rehost, refactor, or otherwise change Playground as part of drawer work),
   **Settings** (→ `SettingsActivity`).

Implement as a `DrawerLayout` whose drawer pane is a **custom layout**
(New Chat + search + RecyclerView + static navigation rows). Do **not** use
`NavigationView` menu items — the chat list is dynamic and menu-item hacks
fight the framework.

### 5.2 Where the drawer lives, and the migration path

Today: `MainActivity` (bottom tabs: Chats / Playground) → tap a chat →
`ChatActivity`. The target: **opening the app immediately presents a blank
chat using the same blank-chat presentation the app has now**, with the drawer
available on the left. Do not reopen the previously viewed conversation on
startup. Get there in three separately-shippable steps:

- **Step A — drawer inside ChatActivity.** Wrap the root of
  `activity_chat.xml` in a `DrawerLayout` (the existing
  `expandable_window_root` CoordinatorLayout becomes the main pane —
  preserve its ID and the `chat_expand` transitionName). Add a hamburger
  button to the chat top bar. Keep `btn_back` working as today.
- **Step B — launch directly into a blank chat.** On ordinary app launch,
  route immediately to the app's existing blank/new-chat presentation rather
  than opening the Chats tab or remembering/reopening the last chat. Reuse the
  current blank-chat behavior and persistence semantics; do not invent a new
  chat-storage lifecycle solely for startup. When ChatActivity is the task
  root, its upper-left navigation control opens the drawer instead of
  finishing the activity.
- **Step C — retire the bottom tab bar.** Once the drawer covers everything
  (chats, Playground, settings), remove `BottomNavigationView` from
  `MainActivity` and slim it down to a router + any still-required startup
  host behavior. (The dead Tips/Tools fragments were already removed with
  owner approval, July 2026 — only Chats and Playground remain to rehome.)

Ship A, then B, then C — never as one PR.

### 5.3 Hard rules for the drawer (voice-pipeline safety)

- **Switching chats from the drawer = `finish()` the current ChatActivity
  and start a new one** with the new chat id (the same lifecycle as today's
  list-tap navigation, so voice teardown in `onDestroy` keeps working).
  **Never** implement in-place chat swapping inside a live ChatActivity in
  this redesign — the voice loop, streaming state, and per-chat preferences
  all assume one chat per activity instance.
- Manual renaming and **auto-naming** change only the chat title, *in place*.
  The existing stored chat ID never changes, and the activity is not relaunched
  (relaunching kills readback and the hands-free loop). Refresh the drawer's list
  when opened (re-read `ChatPreferences` in `onDrawerStateChanged`/
  `onDrawerOpened`) so titles and membership are current. Use stored chat IDs;
  never recompute an ID from a title or move chat data during a rename.
- TTS default selection, previous-selection recovery history and last-known-good
  voice are app-wide. Drawer chat switching must not copy, reset or rebind these
  records to a chat ID. Companion-specific voices remain separate future work.
- Opening/closing the drawer must not touch mic state, keyboard insets
  handling (`keyboard_frame`), or `restoreUIState()` logic.
- Drawer pane must apply status-bar insets (edge-to-edge: content starts
  below the status bar; the pane itself may draw behind it).
- Back handling: drawer open → back closes the drawer; otherwise current
  behavior. Test with gesture nav (predictive back is the default on
  Android 16; `DrawerLayout` 1.2.0 handles it, but verify the
  `OnBackPressedDispatcher` interplay if ChatActivity has custom back
  logic — grep for `onBackPressed`/`OnBackPressedCallback` first).

---

## 6. Design language (the "clean, elegant, modern" spec)

> **Superseded where it conflicts (July 29 2026):** the component authority is
> now `ui-style-guide.md` plus owner rulings. Known conflicts in this section:
> buttons are **semi-square** (`@dimen/button_corner_radius`, 4dp — owner: no
> pills), not `TonalButton` pills; repeated components must use the
> `Widget.App.*` / `AppButton.*` shared families, and the target look is the
> palette designer's drawn-box treatment (outlines/glow), not stock M3
> surfaces. The inset recipe (6.6), motion rules (6.5), and the
> voice-state/semantic-color rules remain valid.

### 6.1 Shape
- Chat input bar: pill container (28dp radius), full-width with 12–16dp
  horizontal margins.
- Message bubbles: 20dp radii with a 6dp "tail-side" corner (bottom-end for
  user, bottom-start for assistant).
- Cards/tiles/dialog surfaces: 16–24dp. Bottom sheets: 28dp top corners
  (M3 default).

### 6.2 Color roles (post-migration)
- Backgrounds: `colorSurface`; elevated rows/cards: `colorSurfaceContainer`
  tiers — **no hard-coded grays.**
- User bubble: `colorPrimaryContainer` / `colorOnPrimaryContainer`.
- Assistant bubble: `colorSurfaceContainerHigh` / `colorOnSurface`.
- Accent moments (FAB, send button, selected states): `colorPrimary` or
  `colorSecondaryContainer`.

### 6.3 Typography
- Keep the bundled fonts (`roboto_ttf` day / `default_font` night — they are
  theme-wired; don't add new font files).
- Apply the M3 type scale via `textAppearance` attributes
  (`?attr/textAppearanceTitleLarge` for screen titles,
  `BodyLarge` for messages, `LabelLarge` for buttons) instead of raw `sp`.

### 6.4 Components
- Top bars: the app uses hand-rolled ConstraintLayout action bars
  (`action_bar` in chat, similar elsewhere). **Keep that structure** —
  restyle (height, title typography, icon buttons with 48dp touch targets)
  rather than introducing `AppBarLayout`/`MaterialToolbar` into ChatActivity,
  where scroll-behavior side effects could disturb the RecyclerView/keyboard
  inset choreography. New/simple screens *may* use `MaterialToolbar`.
- Settings tiles (`TileFragment`) → restyled as M3 list rows or filled
  cards with leading icons; keep the `TileFragment` API so `SettingsActivity`
  / `CharactersActivity` logic is untouched.
- Lists: keep `ListView`+`BaseAdapter` where they exist (persona/endpoint/
  prompt lists) — restyle the *item layouts* only. Migrating list widgets is
  explicitly out of scope (regression risk for zero visual gain).
- Buttons: `Widget.Material3.Button.TonalButton` default (already the
  pattern), filled for primary CTAs, plain icon buttons elsewhere.

### 6.5 Motion
- Keep existing activity fade transitions (`UI.Fade` / `UI.Material`) and the
  chat `chat_expand` shared-element transition. M3 Expressive motion springs
  are a nice-to-have **last** polish phase, never a prerequisite.
- Mic-button state changes must remain *instant* (no animation that delays
  the recording/hands-free indicator).

### 6.6 Edge-to-edge inset recipe (apply to every redesigned screen)
- Activity calls `enableEdgeToEdge()` (most already do).
- Root content view gets an `OnApplyWindowInsetsListener` that pads the
  top bar by the status-bar inset and the bottom-most interactive element by
  `max(navigationBars, ime)` insets. ChatActivity already manages IME insets
  around `keyboard_frame` — **do not** add a second listener there; extend
  the existing one if needed.
- Scrollable content uses `clipToPadding=false` + bottom padding so the last
  item clears the nav bar.

---

## 7. Screen-by-screen checklist

Work through these in the phase order of Section 8. "Risk" = chance of
breaking behavior while restyling. Every row eventually gets: palette
attributes (Phase 1), then visual restyle (its listed phase).

### 7.1 Core surfaces (high care)

| Screen | Files | What changes | Risk / watch-outs |
|---|---|---|---|
| Chat | `ChatActivity.kt`, `activity_chat.xml`, `view_assistant_bot_message.xml`, `view_assistant_user_message.xml`, `view_message.xml`, `ChatAdapter.kt` | Drawer (5.2), pill input bar, restyled bubbles, top bar polish, bulk-select bar restyle | **Highest.** Honor the current ID contract (9.1, 9.2). Don't touch mic/keyboard/streaming logic. The former `btn_debug_log` top-bar shortcut was removed and must not be restored; Logs is reached through the current overflow menu. `Theme.Transparent` + `adjustPan` stay. |
| ~~Floating assistant~~ | ~~`AssistantFragment.kt`, `fragment_assistant.xml`~~ | **Removed (June 2026)** — the floating assistant overlay no longer exists. No work here. | — |
| Chats list (until Step C retires it) | `ChatsListFragment.kt`, `fragment_chats_list.xml`, `view_chat_name(_min).xml`, `ChatListAdapter.kt` | Reuse chat-list behavior/data for the drawer; drawer rows use the Section 5.1 text-only / optional companion-image presentation instead of the old row look | Medium. Avatar/initials logic in adapter may be reused for the optional image, but old row styling is not the target. |
| Quick Settings sheet | `QuickSettingsBottomSheetDialogFragment.kt`, `fragment_quick_settings.xml` | M3 list rows, slider restyle, lorebook checklist polish | Medium-high: ~1k lines wiring `btnSelect*` ConstraintLayout ids — keep all ids/types. Canary for palette inheritance in sheets. |
| Main/home | `MainActivity.kt`, `activity_main.xml`, `bottom_menu.xml` | Step B forwards ordinary launch to a blank chat; Step C removes bottom nav | Medium. Debug overlay (BlurView) must keep working until removed deliberately. |

### 7.2 Settings & management screens (medium care)

| Screen | Files | Notes |
|---|---|---|
| Control Center | `SettingsActivity.kt`, `activity_settings.xml`, `TileFragment` | Tile → M3 row/card restyle; add **Appearance → Theme palette** picker here. (The legacy RemoveAds remnants were removed with owner approval, July 2026.) |
| Characters hub | `CharactersActivity.kt`, `activity_characters.xml` | Same tile restyle. |
| Voice settings | `VoiceSettingsActivity.kt` + `VoiceAdvancedSettingsActivity.kt` | Advanced screen is deliberately plain rows (CLAUDE.md) — modernize gently, keep the row structure and every existing control. Insets were recently fixed; don't regress. |
| List screens: Personas, API endpoints, Activation prompts, Logit bias (x2), Lorebooks, Lorebook entries, Whisper models (x2) | respective `activity_*.xml` + `view_*_item.xml` + adapters | Restyle item layouts + FABs only; keep adapter view-binding ids; keep delete-confirmation dialogs. Lorebook screens show tag/description under headers — preserve. |
| Dialog fragments (~20: edit persona/endpoint/lorebook/entry/prompt/bias, model selectors, language/voice selectors, system message, add chat, message edit, action selector, report sheet) | `fragment_*.xml` | Batch by family. `EditPersonaDialogFragment` is sacred: the edit path must keep passing **every** field through (persona rename = delete + recreate; dropping a field silently loses data — CLAUDE.md invariant). |

### 7.3 Low-risk / cosmetic-only

About, Documentation, Logs, Translator, Image viewer, AI photo editor,
photo variations, fine-tune screens (3), onboarding (Welcome → Purpose →
Activation → Terms — keep the flow and exported intents intact), permission
activities, crash handler. Restyle freely; same palette/inset rules.
Leave untouched unless asked: `DebugMaterial`. (Tips, the Teapots activity,
`Theme.PWA`, and the orphaned data-sources layouts were removed with owner
approval, July 2026.)

### 7.4 Known chat-screen bug to fix as part of Phase 4 (reported June 17, 2026)

There is a **standing, intermittent layout bug on the chat screen** that the
owner has been hitting. Phase 4 restyles the very layout responsible
(`activity_chat.xml` + the ChatActivity top bar / message rows / input bar /
insets), so **do not open a separate fix PR for it** — fold the fix into the
Phase 4 chat restyle and treat "this bug no longer reproduces" as a Phase 4
acceptance criterion.

**Symptoms (owner-observed, intermittent, in long conversations):**

- The top chat bar/header (`action_bar` — chat title + current header actions)
  **sometimes disappears**, and can reappear later on its own.
- Message rows / per-message action buttons sometimes render incomplete.
- The bottom input area can look cramped or mis-laid-out.
- Closing and reopening the chat does **not** reliably reset the state.
- Because the header comes back later, the view is **not** being deleted —
  this reads as a **state / layout / scroll / insets** problem, not a missing
  view.

**Owner follow-up observations (June 17, 2026 — tentative, needs more
testing):**

- It seems to happen **after the AI response completes**, and **more often in
  long conversations** (owner ~"fairly positive" but not yet confirmed).
- **Usually only the top bar goes blank** in normal use.
- One occurrence coincided with the owner **tilting the phone "funny"** —
  which moved the *chat/input bar up* and left the top blank. That points at a
  **configuration change (orientation/fold/multi-window) or an insets re-pass**
  as a distinct trigger from the after-response one: a tilt that re-lays-out
  the window should never have moved the input bar or blanked the top if
  insets and the action-bar constraints were handled correctly. Check
  `ChatActivity`'s `android:configChanges` / rotation handling and the
  `keyboard_frame` / `action_bar` inset listener under a rotation, not just a
  steady-state long chat.

So there are likely **two paths** to the same visible symptom: (a) the
after-response one (see the heal lead below), and (b) a config-change/inset
re-layout one (the tilt). Phase 4 should reproduce and close *both*.

**Strong lead (verified in code):** an existing safety net,
`restoreTopBarVisibility()` (`ChatActivity.kt:709`), already exists precisely
because the `action_bar` can get **stuck `INVISIBLE`** when the settings-cog
**shared-element scene transition is interrupted** (app backgrounded / screen
killed mid-animation). It force-sets the known top-bar views back to
`VISIBLE`/`alpha=1`, and is called from `restoreUIState()` and ~500ms into
`onResume`. The owner's bug looks like a **case this heal does not catch**
(e.g. it fires too early/late, doesn't run on the path that hid the bar, or
the bar is being *covered/pushed* rather than set invisible).

The heal is reactive (transition-interruption focused). The "long
conversation" angle suggests also auditing **scroll + IME/status-bar inset
handling** around `keyboard_frame` / `action_bar` (Section 6.6) — a layout
that lets the RecyclerView or keyboard frame overlap/push the top bar would
produce the same "header gone, then back" symptom without any visibility
flag being toggled.

**What the redesigned chat screen must guarantee (Phase 4 acceptance):**

1. A **stable top bar/header** that does not vanish during long conversations
   or after generation/backgrounding (don't *remove* the existing heal — make
   it sufficient, or make the new layout not need it; if the hand-rolled
   `action_bar` is kept per 6.4, keep the heal aligned with the current
   top-bar views).
2. Message action buttons consistently present/laid out as designed (honor the
   adapter contract 9.2 — hide via `gone`, never delete).
3. Long conversations never push or cover the header (scroll/inset behavior).
4. Correct keyboard / nav-bar / status-bar inset handling (6.6; do not add a
   competing inset listener — extend the existing `keyboard_frame` one, 9.5.4).
5. Bottom input bar properly spaced, not cramped (the pill bar, 6.1).
6. Scope discipline: this fix is **layout/visibility/insets only**. It must
   **not** touch mic capture, Bluetooth routing, VAD, Whisper, TTS, the
   Characters/Activation/System-message/API screens, or assistant-removal
   work, unless one of those is unavoidably the root cause (if so, stop and
   ask the owner before widening scope — per CLAUDE.md and Section 0 rule 3).

---

## 8. Phase plan (each box = one or more small PRs)

- **Phase 0 — Groundwork** ✅ (verified July 29 2026): material `1.14.0`
  stable and explicit `androidx.drawerlayout:drawerlayout:1.2.0` are in
  `build.gradle`; `ThemeManager.applyPalette()` exists with the placeholder
  `Violet` overlay and is wired into every activity `onCreate` (~70
  activities as the app has grown).
- **Phase 1 — Theme-attribute migration** ✅ (commit 1a2b8ae): migrated layout, drawable, menu, and color-state XML from hard-coded `@color/accent_*` / `@color/window_background` references to Material theme attributes; updated the vector icon tint convention. `rg '@color/(accent|window_background)' app/src/main/res/layout app/src/main/res/drawable app/src/main/res/drawable-v24 app/src/main/res/menu` now returns only the deliberate `@color/accent_250_static` animated-vector exception. Kotlin runtime color lookups remain for a later, narrower pass because many are AMOLED/state-machine-specific and need screen-by-screen behavioral verification.
- **Phase 1.5 — Shared-style conversion** (current, ongoing): convert
  screens one at a time to the shared style families per `ui-style-guide.md`,
  tracked per screen in `ui-style-adoption.md`. This phase was not in the
  original plan; it is the groundwork that makes each screen palette-ready.
  The owner directs it screen by screen — do not batch screens without
  approval.
- **Phase 2 — Palettes shipped** (deferred — waiting on owner-designed
  palettes): the owner designs each palette visually in
  `palette-designer.html` and hands over the exported values plus a name.
  Then: translate each approved export into an overlay (designer zones +
  required custom attributes, light and dark), add the `ui_palette`
  preference and the Appearance picker with `recreate()` flow, and replace
  the placeholder Violet with the owner's chosen default. Do not invent
  palettes; do not start this phase until the owner supplies designs.
  **Binding (July 30 2026, Section 4.5):** overlays define the full zone
  contract, not only Material roles; use at least two visually different
  palettes as an audit tool and record the gaps they expose rather than
  patching per-screen; keep the two-source seams (preset overlay now, saved
  custom palette later, restart-to-apply) intact.
- **Phase 2.5 (optional) — AMOLED-as-overlay cleanup**, screen-by-screen.
  **Paused** with all AMOLED work (owner ruling, July 26 2026).
- **Phase 3 — Drawer** (not started; scheduling relative to Phase 1.5 is an
  open owner decision): Step A (drawer in ChatActivity), then Step B
  (**launch into a blank chat**), then Step C (retire bottom nav) — three PRs.
  Drawer starts directly with **New Chat**; there is no separate header/name
  area. Chat rows follow the Section 5.1 text-only / optional companion-image
  design. Playground is linked as its current destination without rehosting
  or refactoring it.
- **Phase 4 — Chat restyle**: input pill, bubbles, top bar. Single UI now —
  no `AssistantFragment` to mirror. The former `btn_debug_log` top-bar
  shortcut is removed and must not be restored; Logs is accessed through the
  current overflow menu.
  **Must also resolve the standing intermittent top-bar/header-vanishing bug —
  see Section 7.4 (it is a Phase 4 acceptance criterion, not a separate PR).**
- **Phase 5 — Settings & Characters restyle** (tiles → rows/cards).
- **Phase 6 — List screens & item layouts.**
- **Phase 7 — Dialogs & bottom sheets.**
- **Phase 8 — Onboarding, About, misc + cleanup** (dead RemoveAds remnants
  with owner approval; motion polish; this doc + CLAUDE.md updated to final
  state).

---

## 9. Pitfalls, dangers, and binding contracts

This is the section the owner asked for explicitly. **Read before every PR.**

### 9.1 ChatActivity view-ID contract

Before drawer or chat-header work, re-verify this contract against current
`ChatActivity` and `activity_chat.xml`; the original June snapshot predates
later chat changes. The former `btn_debug_log` top-bar shortcut has been
removed and is **not** part of the contract. Do not restore that view or its
old visibility/click wiring. Logs is reached through the current chat overflow
menu.

The load-bearing chat IDs that remain must keep their current widget types;
renaming or retyping a Kotlin-cast view can crash or silently break behavior.
At the time of the original audit these included:

`btn_micro` (ImageButton), `btn_settings` (ImageButton), `messages`
(RecyclerView), `message_input` (EditText), `btn_send` (ImageButton),
`progress` (CircularProgressIndicator), `chat_activity_title` (TextView),
`btn_export` (ImageButton), `action_bar` (ConstraintLayout), `btn_back`
(ImageButton), `keyboard_frame` (ConstraintLayout), `root`
(ConstraintLayout), `thread_loader` (LinearLayout), `keyboard_input`
(LinearLayout), `btn_attach` (ImageButton), `attachedImage` (LinearLayout),
`selectedImage` (ImageView), `btnRemoveImage` (ImageButton),
`vision_action_selector` (LinearLayout), `action_camera`, `action_gallery`
(ImageButtons), `bulk_container` (ConstraintLayout), `btn_select_all`,
`btn_deselect_all`, `btn_delete_selected`, `btn_copy_selected`,
`btn_share_selected` (ImageButtons), `text_selected_count` (TextView),
`expandable_window_root` (CoordinatorLayout, keeps
`transitionName="chat_expand"`), `attach_bg` (BlurView).

### 9.2 ChatAdapter item-layout contract (verified at `ChatAdapter.kt:210-221`)

ALL THREE message item layouts (`view_message.xml`,
`view_assistant_user_message.xml`, `view_assistant_bot_message.xml`) must
each contain: `ui` (ConstraintLayout), `icon` (ImageView), `message`
(TextView), `username` (TextView), `generated_image` (ImageView), `btn_copy`,
`btn_edit`, `btn_retry`, `btn_report`, `btn_share`, `btn_speak`
(ImageButtons). Only `bubble_bg` (ConstraintLayout) is nullable/optional.
The adapter does **no null checks** on the rest — a missing id crashes on
first bind. If the redesign hides a button, hide it via
`visibility="gone"`, never by deleting the view.

### 9.3 ~~AssistantFragment contract~~ — REMOVED (June 2026)

The floating phone-assistant overlay (`AssistantActivity` +
`AssistantFragment` + `fragment_assistant.xml`) **no longer exists**, so there
is no second view-ID contract to honor. This section is retained only as a
marker: if an old branch or commit reintroduces `fragment_assistant.xml`, that
is a mistake — `ChatActivity` (Sections 9.1/9.2) is the single chat/voice UI.

### 9.4 The voice state machine is drawn by hand

`micIdle()` / `micRecording()` / `micHandsFreeActive()` in ChatActivity
directly call `setImageResource` /
`setColorFilter` / `backgroundTintList` on `btn_micro` using:
- drawables `ic_microphone`, `ic_stop_recording`
- colors `mic_listening_green`, `hands_free_active_red` (palette-fixed, see 4.3)
- hints `R.string.hint_message`, `R.string.hint_listening`

Renaming any of these, or restyling `btn_micro` with a static tint in XML,
fights the runtime state machine. The mic button's *idle* look can change;
its *state* visuals are owned by code. There is no defensive fallback —
errors here are silent or crash at the worst moment (mid-conversation).

### 9.5 Other binding rules (mostly from CLAUDE.md — they all still apply)

1. ~~**Two generation funnels**~~ **One generation funnel** (June 2026): the
   `AssistantFragment` parallel path is gone, so `ChatActivity.generateResponse`
   → `regularGPTResponse` is the single funnel. UI affordances around
   generation (progress, cancel, restore) live in one place now — there is no
   second copy to keep in sync.
2. **`restoreUIState()` / `restoreTopBarVisibility()`**: any new UI element
   whose state changes during generation must be reset there (in a
   `finally`), and `GenerationForegroundService` ref-counting must stay in
   the `try/finally`. The top-bar heal exists because an interrupted
   shared-element transition leaves `action_bar` invisible — keep the heal
   if you restyle the top bar. **Note: this heal is the prime suspect for the
   standing header-vanishing bug in Section 7.4 — Phase 4 must keep it aligned
   with the current top-bar controls and layout rather than restoring removed
   controls.**
3. **Auto-naming copy block**: any *new per-chat preference* (e.g. nothing
   in this plan should need one, but if one appears) must be added to the
   preference-copy block in ChatActivity, and auto-naming must never
   relaunch ChatActivity.
4. **Keyboard/IME choreography**: ChatActivity uses `adjustPan` + manual
   inset handling around `keyboard_frame` / `message_input`. Don't wrap
   `message_input` in a `TextInputLayout` (the code does
   `findViewById<EditText>`), don't add competing inset listeners.
5. **RecyclerView assumptions**: streaming calls
   `adapter.updateLastMessage(...)`, `chat.scrollToPosition(...)`,
   `clearSpeakingPosition()`. Keep `messages` a RecyclerView with
   `ChatAdapter`. Do not migrate it to anything else.
6. **Markwon renders messages.** Restyle bubbles around the `message`
   TextView; don't replace the markdown pipeline or set conflicting
   `textAppearance` mid-stream.
7. **Strings only in `res/values/strings.xml`**; never edit or add locale
   translations unless asked. Removing a string requires removing usages —
   and (verified against CI, July 2026) also requires deleting that string's
   entries from every locale file in the same change: the build's release
   lint treats a translation without a default-locale string as a **fatal
   error** (`ExtraTranslation`), so orphaned translations are *not*
   harmless. Missing *default* strings break the build too.
8. **Copyright header on every new file**; comments explain constraints/why.
9. **Do-not-touch list** (CLAUDE.md): Ktor 2.3.12, TLS/OkHttp defaults,
   native JNI loading gates, checked-in debug keystore, `ChatPreferences`
   parse-failure preservation. UI PRs have no business near any of these.
10. **AMOLED runtime blocks**: before declaring a screen "restyled", run
    `grep -n "amoled\|getAmoledPitchBlack" <screen>.kt` and reconcile every
    hit with the new layout (see 4.4).
11. **Shared drawables restyle many screens at once** (`btn_accent_tonal_v4/
    v5/v6`, `btn_accent_icon_large_100` are also retinted by
    `ThemeManager.applyTheme` for AMOLED). When changing a screen's look,
    prefer a new drawable over editing a shared one, unless the change is
    intentionally global.
12. **`Theme.Transparent` is load-bearing** for ChatActivity/SettingsActivity
    (translucent window + shared-element transitions + the
    `expandable_window_background_24` rounded sheet look). Restyle the
    drawable; keep the theme's translucency flags.
13. **Voice diagnostics**: if a UI change adds/removes a voice-loop exit
    path or mic affordance, log it via `ChatActivity.logVoiceEvent` (CLAUDE.md).
14. **Exported activities**: the onboarding chain (`WelcomeActivity` →
    Purpose → Activation → Terms) must keep its manifest entries and flow.
    (The old `AssistantActivity` intent filters — device-assistant role, share
    sheet, `PROCESS_TEXT` — were removed with the assistant overlay; do not
    reintroduce them.)

### 9.6 Process pitfalls

- **No local compile.** A "small" XML typo costs a full CI round-trip.
  Static-verify everything (Section 0, rule 2) — especially that every id in
  Sections 9.1–9.3 still exists after editing those layouts.
- **Don't trust this document over the code.** Re-grep the contracts before
  editing the files they describe; update this doc if drift is found.
- **Screenshots:** CI builds a debug APK on `main` pushes; the owner tests
  on a Pixel 8. After each visual phase lands, summarize for the owner *in
  user terms* what changed and what to check (especially: hands-free voice
  loop end-to-end, AMOLED mode, chat rename, image attach).

---

## 10. Per-PR verification checklist (extends CLAUDE.md's)

1. CLAUDE.md checklist items 1–6 (clean status, R.* resolve, the single
   generation funnel, copy block, DB rules, CI green).
2. Every id in Sections 9.1–9.3 still present with the same widget type, if
   the PR touched those layouts.
3. `grep amoled` reconciliation done for every Kotlin file whose layout
   changed.
4. No new hard-coded `@color/accent_*` in layouts (post-Phase-1).
5. New strings in default `strings.xml` only; no locale edits.
6. Insets: top bar below status bar, bottom controls above nav bar/IME, on
   the redesigned screen.
7. No dependency changes other than those named in this plan.
8. This document's phase list updated if a phase completed.