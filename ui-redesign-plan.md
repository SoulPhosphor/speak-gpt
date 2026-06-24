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
> it. Also recorded in this revision: the chat top bar now has a **bug-icon
> shortcut to the Event log** (`btn_debug_log`) that is a real feature to
> preserve — see Sections 7.1 and 9.1.
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
| Navigation | **Left slide-out drawer** containing *only* the chat list plus a single bottom action row (search · gear→global Settings · new-chat). Characters/personas/lorebooks and the other per-chat options live in **Quick Settings**, **not** the drawer; Playground is not a drawer row either. The chat screen is the home screen, like the ChatGPT/Claude apps. No right-side panel. *(Revised 2026-06-24 by owner — see §5.1.)* |
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

---

## 5. Navigation redesign — the side panel

### 5.1 What the drawer contains (top to bottom)

**Revised 2026-06-24 by owner — this list supersedes the original below.** The
drawer is deliberately just the chat list plus one bottom action row. The
Characters hub, personas, lorebooks, and the other per-chat options are
reached from the **Quick Settings** sheet (the per-chat gear on the chat top
bar), **not** from the drawer. Playground is not a drawer row.

1. **Header row** — left-aligned a **`>>`** button that slides the panel away
   and brings the chat back (the second of two ways to move between panel and
   chat; the chat top bar's `<<` is the first), and right-aligned an
   **add-folder** icon that prompts for a name and creates a folder (§5.4).
   Nothing between them yet. *(Revised 2026-06-24: the `>>` was previously
   described as a floating circle — it now lives in this header row.)*
2. **Chat list** — RecyclerView, data source `ChatPreferences`, row visual
   style from `view_chat_name_min.xml` (name + snippet; model labels stay out
   of the drawer for cleanliness). Current chat highlighted with a
   `colorSecondaryContainer` pill.
   - **Long-press a chat row → context menu**, in order: **Rename**, **Export**,
     **Pin** (shown as **Unpin** when the chat is already pinned), **Add to
     Folder** *(arrives with folders — Phase 3.5, §5.4)*, **Delete**. Rename
     reuses the existing rename path; **Export** exports that chat's data (the
     new home for the old top-bar `btn_export`, §5.2 Step A / §9.1); **Pin/
     Unpin** toggles the existing `pinned` flag (`switchPinState`); **Delete**
     shows a Material confirm dialog (destructive-action rule) and scrubs the
     chat as today.
3. **Bottom action row**, pinned to the bottom of the panel, left to right:
   - **Search field** that filters the chat list (reuse `ChatsListFragment`'s
     `search_input` filter logic).
   - **Gear → global Settings** (`SettingsActivity`).
   - **New-chat button** (opens the existing `AddChatDialogFragment`).

> **Note for implementers:** there are now two gear icons with different
> scopes — the **chat top-bar gear** opens per-chat **Quick Settings**, the
> **drawer bottom gear** opens **global Settings**. They are not the same
> screen; keep their targets straight.

> *Original (2026-06-13) drawer list — obsolete, kept for history:* header /
> "New chat" row / search field / chat list / divider / static nav rows for
> Characters, Playground, Settings. The owner removed the Characters &
> Playground nav rows (those live behind Quick Settings) and collapsed search +
> settings + new-chat into the single bottom action row above.

Implement as a `DrawerLayout` whose drawer pane is a **custom layout**
(header + RecyclerView + rows). Do **not** use `NavigationView` menu items —
the chat list is dynamic and menu-item hacks fight the framework.

### 5.2 Where the drawer lives, and the migration path

Today: `MainActivity` (bottom tabs: Chats / Playground) → tap a chat →
`ChatActivity`. The target: open the app, land in your last chat, drawer on
the left. Get there in three separately-shippable steps:

- **Step A — drawer inside ChatActivity.** Wrap the root of
  `activity_chat.xml` in a `DrawerLayout` (the existing
  `expandable_window_root` CoordinatorLayout becomes the main pane —
  preserve its ID and the `chat_expand` transitionName).
  **Chat top-bar layout (owner-specified 2026-06-24):** left-aligned `<<`
  button (re-icon the existing `btn_back` ImageButton — **keep the id**) that
  **opens the drawer**; the chat title **centered**; right-aligned the **bug**
  shortcut (`btn_debug_log`, shown only when diagnostics are on — unchanged)
  then the **gear** (`btn_settings`, opens the per-chat **Quick Settings**
  sheet). The old top-bar **export** icon (`btn_export`) is **not** in this
  layout — its function needs a new home (**open item; confirm with owner
  before dropping the action**, and do not orphan the id without relocating
  what it does — see §9.1). The drawer opens/closes by **edge swipe** as well
  as the `<<` button, and the panel header's `>>` button (§5.1) closes it.
- **Step B — launch straight into the chat screen.** The app opens directly
  into `ChatActivity` showing an **empty, ready-to-type conversation** (no
  bottom-tab home, no forced greeting yet — a greeting may be added later).
  AMOLED users see the pitch-black ready screen with the top-bar icons
  present. `MainActivity` becomes a router that forwards into `ChatActivity`
  (first-run/no-chats still routes through onboarding as today). When
  ChatActivity is the task root, the `<<` button opens the drawer instead of
  finishing. *(Reopening the **last** chat on launch instead of a fresh empty
  one is a possible later refinement; the current owner decision is the empty
  ready screen.)*
- **Step C — retire the bottom tab bar.** Once the drawer covers everything
  (chats, Playground, settings), remove `BottomNavigationView` from
  `MainActivity` and slim it down to a router + first-run host. Tips/Tools
  fragments fold into Settings or are dropped (ask owner before deleting
  features).

Ship A, then B, then C — never as one PR.

### 5.3 Hard rules for the drawer (voice-pipeline safety)

- **Switching chats from the drawer = `finish()` the current ChatActivity
  and start a new one** with the new chat id (the same lifecycle as today's
  list-tap navigation, so voice teardown in `onDestroy` keeps working).
  **Never** implement in-place chat swapping inside a live ChatActivity in
  this redesign — the voice loop, streaming state, and per-chat preferences
  all assume one chat per activity instance.
- **Launch lands on an empty, ready-to-type chat** (Step B) with the top-bar
  icons visible; the drawer is reachable via the `<<` button or an edge swipe.
  No forced greeting in v1.
- **Two ways to toggle the panel** — the chat top-bar `<<` button opens it; a
  **floating circular `>>` button** at the top of the panel closes it. Both
  only open/close the same `DrawerLayout`; **neither may touch mic, streaming,
  keyboard-inset, or `restoreUIState()` logic** (same as the rule below).
- **Chat-row long-press → "Rename" / "Export" / "Delete" menu** is the per-row
  action set. Rename goes through the existing rename path — remember a rename
  **changes the chat id** and must carry every per-chat preference across
  (CLAUDE.md auto-naming copy-block invariant). Export reuses the existing
  chat-export action (the relocated `btn_export`). Delete shows a Material
  confirm dialog and scrubs references as today.
- The **auto-naming** flow renames the chat *in place* (changes the chat id
  without relaunching the activity — relaunching kills readback and the
  hands-free loop). The drawer's chat list must therefore refresh its data
  when opened (re-read `ChatPreferences` in `onDrawerStateChanged`/
  `onDrawerOpened`), not cache ids from activity start.
- Opening/closing the drawer must not touch mic state, keyboard insets
  handling (`keyboard_frame`), or `restoreUIState()` logic.
- Drawer pane must apply status-bar insets (edge-to-edge: content starts
  below the status bar; the pane itself may draw behind it).
- Back handling: drawer open → back closes the drawer; otherwise current
  behavior. Test with gesture nav (predictive back is the default on
  Android 16; `DrawerLayout` 1.2.0 handles it, but verify the
  `OnBackPressedDispatcher` interplay if ChatActivity has custom back
  logic — grep for `onBackPressed`/`OnBackPressedCallback` first).

### 5.4 Chat folders (owner-requested 2026-06-24)

**Status: planned; lands AFTER the flat drawer ships — Phase 3.5 in §8.**
Folders sit on top of the drawer's chat list; they are **not** part of the
first drawer PR (building them together makes the drawer diff huge and risky).
Pinning already exists end-to-end (the `"pinned"` flag, swipe-to-pin, and
pinned-first sort in `ChatsListFragment` — verified) and the drawer inherits it
for free; folders are the genuinely new concept.

**Top-level drawer order (top to bottom):**

1. A **collapsible "Folders" row** — the section header for folders. Default
   **collapsed**, shown as **"Folders ⌄"**. Tapping it expands the folder list
   inline (the end icon flips to **⌃** to show it can be collapsed); tapping
   again collapses it. This lets the user keep folders always-visible or tucked
   away. **The expanded/collapsed state is remembered across app close** (store
   a boolean in `GlobalPreferences`). When expanded, each folder listed here can
   be opened — see drill-in below. **Long-pressing the "Folders" row lets the
   user rename that label** (e.g. to "Worlds") — a small personalisation; store
   the custom label in `GlobalPreferences` (default "Folders"). A *quick* tap
   still only expands/collapses.
2. **Pinned chats** *(resolved: pinned **chats**, not folders)*.
3. **All other chats** (existing timestamp sort).

The bottom action row (search · gear · new-chat, §5.1) stays pinned below all of
this. **When zero folders exist the "Folders" row is hidden entirely** — no
sense taking up vertical space for an empty section. The header add-folder icon
is always present, so folders stay discoverable; the row appears once the first
folder is created.

**Opening a folder = drill-in that replaces the drawer's content** (not a
second sliding panel): the chat list is swapped for that folder's chats. The
folder view's own header is **`<<` left-aligned, then the folder's title
immediately after it**; pressing `<<` returns to the top-level drawer (folders
+ chats). Note `<<` is **contextual**: in the chat top bar it opens the drawer
(§5.2); inside a folder it backs out to the top-level drawer.

**Folder long-press → "Rename" / "Delete"** (mirrors the chat-row menu).
Rename changes only the folder's display name (see id note below). Delete shows
a Material confirm dialog; **on delete the folder's chats fall back to the top
level** (the conversations are kept — only the folder grouping is removed; clear
each member chat's `folder` field).

**Creating folders & assigning chats (owner-specified 2026-06-24):**

- **Creating a folder** — the **add-folder icon in the drawer header**
  (right-aligned, §5.1) prompts for a name and creates the folder.
- **Putting a chat into a folder** — the **"Add to Folder"** item in the chat
  long-press menu (between Pin/Unpin and Delete, §5.1) opens a folder picker
  (include a "None / top level" choice to pull a chat back out). **A chat lives
  in exactly one folder** (the single `folder` field) — confirmed, no
  multi-folder membership.
- Inside a folder view, **search filters that folder's chats** and **new-chat
  creates the chat already inside that folder** (proposed; confirm).

**Data model (fits the existing JSON-in-prefs pattern — no SQLite needed):**

- Add a **`folders`** key to the existing `chat_list` SharedPreferences: a JSON
  array of `{ id, name, timestamp }`.
- Give each chat map a new **`folder`** field (the folder id it belongs to;
  empty = top level).
- **The folder id MUST be a stable generated id (timestamp/UUID), NOT a hash of
  the name.** Chats use `Hash.hash(name)`, so renaming a chat re-keys its
  storage; a *folder* is referenced by many chats, so a name-hash id would force
  rewriting every member chat's `folder` field on every rename. A stable id
  makes folder rename a one-field edit. (Same trap as persona-rename in
  CLAUDE.md — avoid it here by design.)
- All additive: absent keys default to "no folders / top level", so existing
  installs and a fresh install both just work — **no migration step**.
- Keep every folder read/write in `ChatPreferences` (no raw
  `getSharedPreferences` in feature code — CLAUDE.md rule).

**Voice-pipeline safety:** folders are pure drawer navigation. Opening a
folder, moving a chat, or renaming a folder must never touch mic/streaming/inset
state. Switching INTO a chat from a folder follows §5.3 — `finish()` + start a
new ChatActivity, never an in-place swap.

### 5.5 Chat export & "Export All Chats" (owner-requested 2026-06-24)

**What already exists (verified `ChatActivity.kt:1882`):** the current top-bar
export **already** uses Android's Storage Access Framework
(`ACTION_CREATE_DOCUMENT`) — i.e. it already **asks where to save** via the
system file picker, writes the chat as JSON (`Gson` of the message list), and
names the file `<chatId>.json`. Its initial location is hard-coded to
`/SpeakGPT/`, it does **not** remember the last-used folder, and the filename is
the raw hashed chat id.

**The delta the owner wants:**

1. **Per-chat Export** moves into the drawer chat-row long-press menu (§5.1) —
   same SAF "ask where to save" flow, kept.
2. **"Export All Chats"** — a button at the **bottom of the main Settings page**
   that exports every chat at once.
3. **Always ask where to put the file(s)**, defaulting to the **Download**
   folder, then to the **last-used** location on later exports.

**Implementation notes:**

- **Default + last-used location:** persist the last-used export location URI in
  `GlobalPreferences`; set the picker's `EXTRA_INITIAL_URI` to it when present,
  else to the system **Downloads** tree. Replace the hard-coded `/SpeakGPT/`
  initial URI. (SAF partly remembers the last place already; persisting it
  ourselves makes "Download first, then last-used" deterministic.)
- **Friendlier filenames:** export under the chat's display name (sanitised),
  not the raw hash id.
- **Export All shape** *[OPEN — confirm]*: either **(a)** pick a **folder** via
  `ACTION_OPEN_DOCUMENT_TREE` and write one `<chatname>.json` per chat into it
  (most useful, and exactly what a Drive/Dropbox folder sync wants), or **(b)**
  write a **single combined file** of all chats. Recommend **(a)**; persist the
  chosen tree as the last-used location.
- This is also the groundwork for the earlier **"store my chats in Dropbox/
  Drive"** wish — the SAF picker exposes those cloud providers as destinations,
  so a folder export already reaches them.

**Where it lands:** Phase 3.6 (§8). Per-chat export rides with the drawer
(Phase 3) since the menu item is there; the location-picker upgrade and the
Settings "Export All" button are the new behavioural work, grouped as Phase 3.6
so the drawer PR stays purely structural.

### 5.6 Bulk chat selection & delete in the drawer (owner-requested 2026-06-24)

Goal: select **all** chats, or several chats, in the drawer and delete them in
one action. This is the **chat list**, and is **distinct** from the existing
in-*chat* bulk message select (selecting messages inside one conversation —
`bulk_container` etc. in §9.1 — which already exists and stays as-is).

**Proposed interaction (mirrors the in-chat bulk bar for consistency):**

- **Enter selection mode** from a **"Select"** item added to the chat-row
  long-press menu (→ Rename / Export / Pin·Unpin / Add to Folder / **Select** /
  Delete). *[Open: or expose it as a select/edit icon in the drawer header
  instead of/in addition to the menu item — confirm which.]*
- In selection mode, rows show **checkboxes**; the drawer header becomes a
  contextual bar with **Select all**, a **selected count**, **Delete**, and
  (once folders exist) **Add selected to folder**. The panel's `>>`/back exits
  selection mode.
- **Delete** shows a Material confirm dialog ("Delete N chats?") and removes
  each selected chat via the existing `ChatPreferences.deleteChatById`
  teardown (same per-chat cleanup a single delete does today). Pinned chats are
  deletable too.
- **Scope:** selection operates within the **current view** — the top-level
  list, or inside one folder when drilled in — not across folders in one
  selection (keeps the model simple).

**Reuse:** the in-chat message bulk bar (`btn_select_all`, `btn_deselect_all`,
`btn_delete_selected`, `text_selected_count`, §9.1) is the visual/interaction
template — match it so both bulk modes feel the same. But the drawer's is a
**separate** implementation over the chat list; do **not** wire it to
`ChatAdapter` (that adapter is for messages inside a chat).

**Where it lands:** Phase 3 (drawer) for the core multi-select + delete; the
"Add selected to folder" extension rides with Phase 3.5 (folders).

### 5.7 Quick Settings is the per-chat hub (owner-clarified 2026-06-24)

Because the drawer deliberately holds *only* chats/folders (§5.1), the per-chat
**Quick Settings** sheet is now the home for everything that used to be drawer
"Characters/Playground" navigation. It already carries the per-chat **model,
endpoint, persona, activation prompt, lorebook checklist, and sampling params**
(CLAUDE.md). To fully replace the removed drawer nav, Quick Settings should also
provide a way to **open the Characters hub** (`CharactersActivity` — manage
personas / activation prompts / system message / lorebooks) so the *library*
side stays reachable; Quick Settings selects *which* persona/lorebook this chat
uses, the hub *manages* them. Restyle is Phase 7 (§7.1); this clarifies its
**contents**, not a new screen. *(The chat top-bar gear opens Quick Settings;
the drawer bottom gear opens global Settings — §5.1 note.)*

---

## 6. Design language (the "clean, elegant, modern" spec)

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
- **Screens replace the window — they don't float (owner 2026-06-24).** Going
  into Settings or any sub-screen changes the **whole window** to that screen: a
  full-screen menu, **not** a rounded card floating over a dimmed background.
  Settings/sub-screens currently use `Theme.Transparent` +
  `expandable_window_background*`, which reads as a floating sheet — drop that
  look for these screens and let them fill the window (with correct insets,
  §6.6). *(ChatActivity keeps `Theme.Transparent` for its chat-open
  shared-element transition — §9.5.12; this is for Settings and its sub-screens,
  not the chat.)* Back is a **top-left `<<` chevron** (the same left
  double-chevron glyph used elsewhere); on a sub-screen it finishes the screen
  and returns to the previous one.
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

### 6.7 Message action buttons & text selection (owner-confirmed 2026-06-24)

Per the adapter contract (§9.2) every message layout keeps **all** button ids
(`btn_copy`, `btn_edit`, `btn_retry`, `btn_report`, `btn_share`, `btn_speak`) —
the adapter binds them with no null checks, so a button that shouldn't appear on
a given message type is hidden with `visibility="gone"`, **never deleted**. The
visible sets:

- **AI / assistant message:** **Listen** (`btn_speak`) sits at the **top of the
  message** — so you don't have to scroll a long answer to start playback (owner
  pain point). Below the text: **info** (`btn_info` — repurposed from the old
  Report button, §6.10), **share** (`btn_share`), **regenerate** (`btn_retry`),
  **copy** (`btn_copy`), **edit** (`btn_edit`). **The Report action is gone** (no
  moderation backend — the sheet only links to external Discords / exports text,
  verified in `ReportAIContentBottomSheet`); rather than delete the button,
  **reuse its slot as an Info button** (§6.10) — swap the "!" for an info "i"
  icon, replace the report handler with the model/time popup, and drop the
  `ReportAIContentBottomSheet` wiring. Keep the id, renamed `btn_info`.
- **User message — two:** **copy** (`btn_copy`) and **edit** (`btn_edit`, the
  pencil), so the user's own text can be copied or changed. The other buttons
  (`btn_speak`/`btn_share`/`btn_retry`) are `gone`. *(This already matches the
  adapter, which shows share only when `isBot == true` — `ChatAdapter.kt:348`;
  the restyle must preserve that gating.)*

The palette-designer **mockup currently draws the wrong message icons** and will
be corrected to exactly these two sets.

**Partial text selection must be preserved.** The message `TextView`s are
selectable today (`setTextIsSelectable(true)`, `ChatAdapter.kt:535`): a user can
long-press to select part of a message and copy it via the normal Android
text-selection toolbar. The restyle must keep this — do not disable selection on
the message text or let a tap/long-press handler swallow the gesture. This is
**independent** of the per-message copy button (which copies the whole message).

### 6.8 Reasoning ("thinking") display (owner-requested 2026-06-24)

Some endpoints/models the owner uses are **reasoning models** that emit a
separate "thinking" stream. Today the app discards it; the owner wants to
optionally see it.

**Cost facts (so the design is grounded):** the model generates the thinking
tokens whether or not they're shown — they're billed as output either way, so
**displaying them costs nothing extra**. Standard practice is to **not** send a
prior turn's thinking back as history (only final answers go back), so showing
it does **not** compound token cost on later turns. "Show thinking" is therefore
almost entirely a parse-and-render feature, not a cost trade-off.

**How it arrives:** OpenAI-compatible reasoning APIs return the thinking in a
**separate field** (commonly `reasoning_content`; some use `reasoning`), not in
the normal `content`. The streaming path must read that field. *(Confirm GLM's
exact field/param when building — some providers also need a request param to
enable or return reasoning.)*

**UI:** when thinking is present, render it as a **collapsible section at the top
of that AI message** (collapsed by default; tap to expand), visually distinct
from the answer (dimmer / italic). It is not fed back into chat history. Add the
thinking view to the three message layouts per the §9.2 "hide via `gone`" rule
(absent when there's no reasoning, or when the toggle is off).

**Toggle (recommend a toggle, not forced-on):** a global "Show model thinking"
setting (Settings → Appearance/Behavior). Recommend **on, collapsed** — present
but not noisy — rather than forcing it expanded every turn. When off, the
reasoning field is dropped (or not requested) and no section shows. *[Owner:
confirm toggle vs always-on.]*

**Scope/where:** new behavior in the single `generateResponse` →
`regularGPTResponse` funnel plus `ChatAdapter` rendering — rides with **Phase
4.5** (after the Phase 4 chat restyle that already reworks message rows). Keep
it in the one funnel; do not add a second path.

### 6.9 Persona avatars & the default star (owner-requested 2026-06-24 — its own phase)

A **feature**, not a restyle; the owner wants it as a **separate step** (Phase
9). The good news: the **custom-avatar plumbing already exists** and is reused,
not rebuilt.

**What already exists (verified):**

- `StaticAvatarParser.parse(avatarId)` maps preset ids → drawables; the
  **default/fallback is `R.drawable.assistant`** — the big-star/little-star icon
  the owner wants as the default everywhere (it's already the `else` branch).
- A **custom image** avatar already works: `CustomizeAssistantDialog` copies a
  picked image into app storage at
  `getExternalFilesDir("images")/avatar_<avatarId>.png`
  (`CustomizeAssistantDialog.kt:292`), and `ChatAdapter` renders it rounded into
  the message `icon`, falling back to the preset drawable
  (`ChatAdapter.kt:417-428`). So **chats already have the avatar "spot" and a
  copy-to-storage mechanism** — exactly what the owner described.

**The change:**

1. **Default avatar = the star (`R.drawable.assistant`) for all chats and
   personas** (mostly already the fallback; make it explicit and retire the
   other preset AI logos as the default set).
2. **Remove the "Customize assistant" feature** — its Settings tile, the
   `CustomizeAssistantDialog`, and the avatar bits in `AddChatDialogFragment`.
   Per-chat assistant avatars are replaced by per-**persona** avatars.
3. **Per-persona photo:** add an **avatar field to `PersonaObject`** (it has none
   today — only label/prompt/activation/lorebook fields), persist it in
   `PersonaPreferences` flat keys, and **pass it through
   `EditPersonaDialogFragment`** — the rename=delete+recreate invariant means
   every field must be carried or the avatar is lost on edit (CLAUDE.md). Add an
   **image picker at the bottom of the persona editor**; on pick, **copy the
   image into app storage** (reuse the `CustomizeAssistantDialog` copy code,
   writing into `getExternalFilesDir("images")`), so deleting the gallery
   original doesn't lose it. Keep it **basic first** — drop it in the circle, no
   max-size pipeline (a downscale-on-import can come later).
4. **Chats render their persona's avatar**, replacing the stars, via the
   existing `icon` path: resolve a chat's avatar from its persona (custom image →
   show it; else → default star). Reuse the same `avatar_*.png` file + rounded
   render already in `ChatAdapter`.
5. **Cleanup:** replacing a persona's image overwrites/deletes the old file;
   **deleting the persona deletes its avatar file** (extend the
   `PersonaPreferences` delete path — same discipline as
   `removeLoreBookFromAllPersonas`).

**Gotcha — handled by Phase 2.6.** Persona id *used to* change on rename
(`id = Hash.hash(label)`), which would orphan an avatar file keyed by id. The
**Stable persona IDs** refactor (§11, Phase 2.6 — sequenced *before* this) gives
each persona a permanent id, so `avatar_<personaId>.png` is safe and never needs
moving. Build persona avatars on top of that stable id; if for any reason Phase
2.6 hasn't landed first, key the avatar by a stable token instead.

**User profiles (future — own later phase).** The owner wants, eventually,
**user profiles** that work like personas but describe **the user** to the AI
(role-play as an elf one session, a fairy another), each with its own image. The
app has **no user-image mechanism today**, so this builds directly on the
persona-avatar infrastructure above (same storage, copy, cleanup, render). Park
as **Phase 10**; persona avatars (Phase 9) lay the foundation first.

### 6.10 Per-message info: model used + time received (owner-requested 2026-06-24)

The owner wants to know **which model produced each AI answer** (useful for their
own projects, and the honest way to handle mixed-model chats) and **when it was
received**. Delivered as an **Info button on AI messages only** — the slot the
Report "!" is vacating becomes an **info ("i") icon**; tapping it shows a small
popup: **Model:** ⟨model⟩ · **Received:** ⟨date & time⟩. (This is what resolves
the §7.5 model-name question — no always-on label, no chat-list model column, the
drawer stays clean.)

**Storing it is easy and additive — no DB, no migration.** A chat message is a
`HashMap<String, Any>` serialized to JSON by `ChatPreferences` (keys today:
`message`, `isBot`, optional `image`/`imageType`). Add two keys **on AI messages
only**:

- `model` — the exact model string the request used. Capture it in the
  `generateResponse` → `regularGPTResponse` funnel at generation time; a chat can
  switch models mid-conversation, so **per-message** capture is what makes this
  correct.
- `ts` — `System.currentTimeMillis()` at **completion** ("received"); set when the
  response finalizes.

Wire both through `putMessage` (`ChatActivity.kt:3071`) — extend it to accept
`model`/`ts` for bot messages and write them into the map. The keys are JSON, so
they persist automatically; **old messages simply lack them** → the popup shows
"model not recorded". User messages get neither key (owner only wants AI-side
info).

**UI:** reuse the AI-message button slot (former `btn_report`, now `btn_info`),
info icon, shown only when `isBot == true` (the same gating the report button
used). Tap → a small Material dialog/sheet with the model and a formatted **local**
date-time. Message text selection and the other buttons are unchanged.

**Where it lands:** the **storage** half (writing `model`/`ts` in `putMessage` +
the funnel) is tiny and can land early or with **Phase 4**; the **Info button**
itself rides with the Phase 4 chat-message restyle (it edits the same
message-action row). Single generation funnel only — no second path.

### 6.11 Images, vision & file attachments (owner-directed 2026-06-24)

The owner wants three distinct media capabilities to work across *any* endpoint
(GLM today; OpenRouter / NanoGPT / others later; and for other users): **generate
images**, **show the model an image**, and **give the model a file to read**.
These are three separate features with different requirements — keep them
separate. Owner reversed the earlier "cut image gen" lean: **keep `/imagine`**,
and make image features provider-agnostic.

#### 6.11.1 Image generation (`/imagine`) — keep it; make the generator configurable

What it does today (verified): `/imagine <prompt>` (gated by `imagine_command`,
`ChatActivity.kt:2860`) calls an OpenAI-style image endpoint —
`openAIAI.imageURL(ImageCreation(prompt, model = getImageModel(),
size = ImageSize(getResolution())))` (`:4315`) — and renders the result. **The
catch:** that call uses the **same endpoint + key as chat** (`openAIAI` is the
chat client). So on an endpoint that can't make images (e.g. GLM), `/imagine`
fails.

**The owner's instinct is the correct design** — image generation should route to
a **different provider than chat**, since the chat model and image model are often
different services. Plan:

- Add a **dedicated "Image generation" provider setting**: its own endpoint
  (base URL), API key, model, and size — reuse the existing multi-endpoint
  profile system (`ApiEndpointPreferences`) so the user just picks an endpoint +
  model for images. **Default:** fall back to the chat endpoint when unset
  (back-compat for chat models that *do* generate images).
- **`/imagine` (and the function-calling `generateImage` tool) route to that
  image provider**, independent of the current chat model.
- **The UI clearly names the active image generator** — e.g. "Images: ⟨provider⟩ ·
  ⟨model⟩" near `/imagine` / in Quick Settings — and shows a friendly message when
  it's unset ("No image generator configured — set one in Settings") instead of a
  raw error.

Modern standard this matches: OpenAI-compatible `images/generations` (DALL·E-3,
`gpt-image-1`); OpenRouter and others expose image models the same way. Keep the
existing `images()` call; point it at the configured image endpoint and surface
which one it is.

#### 6.11.2 Vision (show the model an image) — wired correctly, but a real bug breaks it off-OpenAI

The attach-image path is implemented the modern way: a picked photo is resized,
base64-encoded into a data URL (`baseImageString = "data:image/…;base64,…"`,
`ChatActivity.kt:795`) and sent as multimodal **content parts** —
`TextPart(prompt)` + `ImagePart(dataUrl)` (`:3167`). That is exactly the
OpenAI-compatible `image_url` vision format, so the structure is right.

**But there is a concrete bug, and it almost certainly *is* the owner's error:**
the image-attach request **hard-codes `model = ModelId("gpt-4o")`**
(`ChatActivity.kt:3171`) instead of using the chat's selected model. So attaching
an image sends a request for **"gpt-4o"** to whatever endpoint you're on — and on
GLM / OpenRouter / NanoGPT there is no `gpt-4o`, so it **errors**. This is **bad
code, not a missing capability**. Plan:

- **Fix:** use the chat's configured model (`preferences.getModel()`) for the
  vision request, exactly like the normal text path — not a hard-coded `gpt-4o`.
- **Keep the base64 data-URL approach** (no image hosting; works offline).
- **Soften failures:** if the chosen model genuinely isn't vision-capable, show a
  clear message ("This model may not support images — try a vision-capable
  model/endpoint") instead of a raw stack trace. Optional per-endpoint
  "supports vision" hint so the attach button can warn up front.

This is a small, high-value fix — it likely turns vision from "broken" to
"works" for the owner immediately.

#### 6.11.3 Files to read (documents) — new feature

**Not supported today** — attachment is image-only (no document path). The
universal, model-agnostic approach (works with *any* text model, no special API):

- Let the user attach a **text-ish file** (txt, md, code, CSV, PDF); the app
  **extracts the text on-device** and injects it as context for that turn
  (clearly marked: "Attached file ⟨name⟩:" + contents). PDFs need a text-extract
  step; scanned/image PDFs fall back to vision.
- Avoids depending on any provider "files API" (inconsistent across
  OpenAI-compatible endpoints) — works everywhere.
- Bigger lift → its **own phase**. Cap large files (size / token budget) with a
  clear message.

#### 6.11.4 Attach UX (intuitive, modern)

One **`+` attach button** (already in the chat mockup) opens a small sheet:
**Photo** (vision), **File** (document), **Camera**. Keep it obvious which actions
need which capability; show the configured image generator where `/imagine` lives.
Attachment and image-gen are first-class — don't bury them.

---

## 7. Screen-by-screen checklist

Work through these in the phase order of Section 8. "Risk" = chance of
breaking behavior while restyling. Every row eventually gets: palette
attributes (Phase 1), then visual restyle (its listed phase).

### 7.1 Core surfaces (high care)

| Screen | Files | What changes | Risk / watch-outs |
|---|---|---|---|
| Chat | `ChatActivity.kt`, `activity_chat.xml`, `view_assistant_bot_message.xml`, `view_assistant_user_message.xml`, `view_message.xml`, `ChatAdapter.kt` | Drawer (5.2), pill input bar, restyled bubbles, top bar polish, bulk-select bar restyle | **Highest.** Honor the ID contract (9.1, 9.2). Don't touch mic/keyboard/streaming logic. **Keep the `btn_debug_log` bug shortcut** in the top bar (toggled by diagnostics — see 9.1). `Theme.Transparent` + `adjustPan` stay. |
| ~~Floating assistant~~ | ~~`AssistantFragment.kt`, `fragment_assistant.xml`~~ | **Removed (June 2026)** — the floating assistant overlay no longer exists. No work here. | — |
| Chats list (until Step C retires it) | `ChatsListFragment.kt`, `fragment_chats_list.xml`, `view_chat_name(_min).xml`, `ChatListAdapter.kt` | Restyle rows/FABs; row design is reused by the drawer | Medium. Avatar/initials logic in adapter. |
| Quick Settings sheet | `QuickSettingsBottomSheetDialogFragment.kt`, `fragment_quick_settings.xml` | M3 list rows, slider restyle, lorebook checklist polish | Medium-high: ~1k lines wiring `btnSelect*` ConstraintLayout ids — keep all ids/types. Canary for palette inheritance in sheets. |
| Main/home | `MainActivity.kt`, `activity_main.xml`, `bottom_menu.xml` | Step B forwarding; Step C removes bottom nav | Medium. Debug overlay (BlurView) must keep working until removed deliberately. |

### 7.2 Settings & management screens (medium care)

| Screen | Files | Notes |
|---|---|---|
| Control Center | `SettingsActivity.kt`, `activity_settings.xml`, `TileFragment` | Tile → M3 row/card restyle; add **Appearance → Theme palette** picker here. Contains legacy `tileRemoveAds` remnants (`activity_remove_ads.xml`, strings) from upstream — ask owner before deleting; hide at minimum. |
| Characters hub | `CharactersActivity.kt`, `activity_characters.xml` | Same tile restyle. |
| Voice settings | `VoiceSettingsActivity.kt` + `VoiceAdvancedSettingsActivity.kt` | Advanced screen is deliberately plain rows (CLAUDE.md) — modernize gently, keep the row structure and every existing control. Insets were recently fixed; don't regress. |
| List screens: Personas, API endpoints, Activation prompts, Logit bias (x2), Lorebooks, Lorebook entries, Whisper models (x2) | respective `activity_*.xml` + `view_*_item.xml` + adapters | Restyle item layouts + FABs only; keep adapter view-binding ids; keep delete-confirmation dialogs. Lorebook screens show tag/description under headers — preserve. |
| Dialog fragments (~20: edit persona/endpoint/lorebook/entry/prompt/bias, model selectors, language/voice selectors, system message, add chat, message edit, action selector, report sheet) | `fragment_*.xml` | Batch by family. `EditPersonaDialogFragment` is sacred: the edit path must keep passing **every** field through (persona rename = delete + recreate; dropping a field silently loses data — CLAUDE.md invariant). |

### 7.3 Low-risk / cosmetic-only

About, Tips, Documentation, Logs, Translator, Image viewer, AI photo editor,
photo variations, fine-tune screens (3), onboarding (Welcome → Purpose →
Activation → Terms — keep the flow and exported intents intact), permission
activities, crash handler. Restyle freely; same palette/inset rules.
Leave untouched unless asked: `DebugMaterial`, the Teapots activity,
`Theme.PWA`, `activity_data_sources*.xml` (orphaned).

### 7.4 Known chat-screen bug to fix as part of Phase 4 (reported June 17, 2026)

There is a **standing, intermittent layout bug on the chat screen** that the
owner has been hitting. Phase 4 restyles the very layout responsible
(`activity_chat.xml` + the ChatActivity top bar / message rows / input bar /
insets), so **do not open a separate fix PR for it** — fold the fix into the
Phase 4 chat restyle and treat "this bug no longer reproduces" as a Phase 4
acceptance criterion.

**Symptoms (owner-observed, intermittent, in long conversations):**

- The top chat bar/header (`action_bar` — chat title + back/export/settings/
  bug icons) **sometimes disappears**, and can reappear later on its own.
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
killed mid-animation). It force-sets `actionBar`, `btn_back`,
`chat_activity_title`, `btn_export`, `btn_settings` back to
`VISIBLE`/`alpha=1`, and is called from `restoreUIState()` and ~500ms into
`onResume`. The owner's bug looks like a **case this heal does not catch**
(e.g. it fires too early/late, doesn't run on the path that hid the bar, or
the bar is being *covered/pushed* rather than set invisible). Two concrete
gaps worth checking during Phase 4:

1. `restoreTopBarVisibility()`'s list **omits `btn_debug_log`** (the bug
   shortcut, see 9.1) and the input-bar/message controls — so even when it
   fires it only heals five of the views the owner reports as missing.
2. The heal is reactive (transition-interruption focused). The "long
   conversation" angle suggests also auditing **scroll + IME/status-bar inset
   handling** around `keyboard_frame` / `action_bar` (Section 6.6) — a layout
   that lets the RecyclerView or keyboard frame overlap/push the top bar would
   produce the same "header gone, then back" symptom without any visibility
   flag being toggled.

**What the redesigned chat screen must guarantee (Phase 4 acceptance):**

1. A **stable top bar/header** that does not vanish during long conversations
   or after generation/backgrounding (don't *remove* the existing heal — make
   it sufficient, or make the new layout not need it; if the hand-rolled
   `action_bar` is kept per 6.4, extend the heal to every top-bar view incl.
   `btn_debug_log`).
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

### 7.5 Settings & Appearance cleanup (owner-directed 2026-06-24)

Done as part of the **Phase 5** Settings restyle (the chat-layout toggle removal
is a **Phase 4** item since it changes message rendering). Each item is a
deliberate removal — scrub the pref **and** the dead code, don't just hide a tile.

**Privacy section — remove entirely.** The Privacy tiles (Send diagnostic data,
Assign new installation ID, Revoke authorization, plus the Installation/Android
ID display) drive a TeslaSoft telemetry system that **no longer has a backend**:
`DeviceInfoProvider` has **no network code** (verified) — nothing is uploaded,
"delete data from their servers" refers to servers that don't exist, and the copy
misleads users into thinking data is collected. Worse, the consent/installation-id
flag historically gated **local** logging, which CLAUDE.md already forbids. Remove
all the tiles and the ID display; scrub the dead `consent`/installation-id code
**without** re-coupling the local `Logger` to it. **Keep one honest static line**,
folded into **About** (which already has a Privacy Policy link): *"Everything is
stored on your device, encrypted. Your messages go directly to the API endpoint
you configure — review that provider's privacy policy. This app has no servers and
collects nothing."*

**Appearance toggles:**

- **"Classic chat layout" — remove (Phase 4).** `getLayout()` switches
  `ChatAdapter` between a flat "ChatGPT-website" layout (`classic`, the default)
  and the bubble layout (`bubbles`) — verified `ChatAdapter.kt:113/333`. The
  redesign **commits to the bubble layout** (the mockup), so the toggle is dead:
  drop it and the `layout` pref and make the adapter always render the redesigned
  bubble row. (This is why toggling "does nothing obvious" today — the two paths
  look similar and only affect re-bound rows.)
- **"Monochrome background for chat list" — remove.** A niche per-list-background
  toggle (`getMonochromeBackgroundForChatList`) made redundant by the palette
  system (light/dark + themes cover it). Drop the tile and the pref.
- **AMOLED mode — keep, but reframe.** It works (pitch-black dark). Move it out of
  "experimental" and present it as a **dark-theme option** in the new
  Appearance/Themes area. Backend: it's already slated to become a `ThemeOverlay`
  applied *after* the palette overlay (§4.4, Phase 2.5), forcing surfaces to pure
  black — so "AMOLED" becomes "dark theme, pure-black variant", not a separate
  experimental switch. Keep `getAmoledPitchBlack()` working throughout.

**Model-name display — decision needed.** Today a **"Hide model names"** toggle
(`getHideModelNames`) controls whether the **chat list** shows each chat's current
model under its name. It is a *per-chat* label, so it does **not** handle a chat
that used several models — it just shows the chat's latest one (the confusion the
owner noticed). **Resolved (owner 2026-06-24):** drop the chat-list "Hide model
names" toggle and its label entirely (the redesigned drawer omits model labels,
§5.1). Model visibility is instead delivered **per AI message via the Info
button** — model + received time on tap (§6.10) — which handles mixed-model chats
correctly, since each answer carries its own model.

### 7.6 Experimental features audit (owner-directed 2026-06-24)

Findings on the three "Experimental" toggles, for the Phase 5 settings pass:

**Autosave chats — remove (dead no-op).** The `chats_autosave` pref
(`Preferences.kt:780`, default off) is **read in exactly one place — the settings
tile that shows its own state** (`SettingsActivity.kt:690`) — and **consumed by
nothing else** (verified: no other reader anywhere). Chats save unconditionally
through `ChatPreferences` regardless, so the toggle does **literally nothing**.
Remove the tile and the pref.

**Slash commands = just `/imagine` — keep it, make image gen configurable.** The
"slash commands" toggle is the `imagine_command` pref; the **only** command is
**`/imagine <prompt>`**, which fires image generation (`ChatActivity.kt:2860`).
The owner wants to **keep** it. The fix isn't to delete it but to **decouple image
generation into its own configurable provider** so it works regardless of the
chat model — see **§6.11.1**. Relabel it plainly ("/imagine command"), not
"experimental".

**Function calling — trim, don't delete.** The `function_calling` pref exposes two
tools (`ChatActivity.kt:3699`):
- `generateImage` — **keep**, routed to the configurable image provider (§6.11.1).
- `searchAtInternet` — **remove**: it only opens `https://www.google.com/search?q=…`
  in the **browser** (`ChatActivity.kt:3137`) and feeds **nothing** back to the
  model. Not real retrieval.
Keep the function-calling toggle (now just `generateImage`), noting it needs an
endpoint that supports tool calls.

**Image features overall — keep and make provider-agnostic (not cut).** Full plan
in **§6.11**: configurable image-generation provider (6.11.1), the vision
hard-coded-`gpt-4o` bug fix (6.11.2), and on-device file reading (6.11.3). This
supersedes the earlier "audit to decide keep-or-cut" — the direction is set:
**keep, fix, and decouple from the chat provider.**

---

## 8. Phase plan (each box = one or more small PRs)

- **Phase 0 — Groundwork** (tiny, mechanical): bump material to `1.14.0`
  stable; add explicit `androidx.drawerlayout:drawerlayout:1.2.0`; create
  `ThemeManager.applyPalette()` with only the `violet` overlay defined as an
  empty/no-op overlay and wire the call into every activity `onCreate`
  (zero visual change — this PR proves the wiring compiles everywhere).
- **Phase 1 — Theme-attribute migration** ✅ (commit 1a2b8ae): migrated layout, drawable, menu, and color-state XML from hard-coded `@color/accent_*` / `@color/window_background` references to Material theme attributes; updated the vector icon tint convention. `rg '@color/(accent|window_background)' app/src/main/res/layout app/src/main/res/drawable app/src/main/res/drawable-v24 app/src/main/res/menu` now returns only the deliberate `@color/accent_250_static` animated-vector exception. Kotlin runtime color lookups remain for a later, narrower pass because many are AMOLED/state-machine-specific and need screen-by-screen behavioral verification.
- **Phase 2 — Palettes shipped**: define the 5 overlays (light+dark role
  sets), the `ui_palette` preference, the Appearance picker UI with swatches,
  `recreate()` flow. Verify AMOLED still correct on every screen family.
- **Phase 2.5 (optional) — AMOLED-as-overlay cleanup**, screen-by-screen.
- **Phase 2.6 — Stable persona IDs** (§11): give each persona a permanent id
  decoupled from its name (the name becomes editable data); migrate by freezing
  each existing persona's current id (no data rewrite, no orphaned chats); fix
  every `id == Hash.hash(label)` call site. Logic-only, no UI — unblocks Phases
  9–10 and kills a latent rename bug. **Do early.**
- **Phase 3 — Drawer**: Step A (drawer in ChatActivity), then Step B
  (launch into the chat screen), then Step C (retire bottom nav) — three PRs.
  Ships the **flat** chat list (folders come later); pinning is inherited free.
  Bulk chat select + delete (§5.6) lands here (its "add selected to folder"
  extension waits for Phase 3.5).
- **Phase 3.5 — Chat folders** (§5.4): `folders` index + per-chat `folder`
  field in `ChatPreferences`; top-level drawer ordering (folders → pinned →
  rest); folder drill-in view (`<<` + title); folder create / rename / delete;
  "Move to folder" on chats. Ships only after the flat drawer (Phase 3) is
  merged. **Resolve the §5.4 OPEN items with the owner before coding.**
- **Phase 3.6 — Chat export & Export All** (§5.5): relocate per-chat export into
  the drawer long-press menu; add the Settings "Export All Chats" button; add
  the Download-default / last-used location picker; friendlier filenames.
  **Resolve the §5.5 Export-All-shape OPEN item with the owner first.**
- **Phase 4 — Chat restyle**: input pill, bubbles, top bar (preserving the
  `btn_debug_log` shortcut). Single UI now — no `AssistantFragment` to mirror.
  **Must also resolve the standing intermittent top-bar/header-vanishing bug —
  see Section 7.4 (it is a Phase 4 acceptance criterion, not a separate PR).**
  Also moves **Listen to the top** of AI messages, **turns the Report button
  into an Info button** (model + received-time popup, §6.10 — incl. writing
  `model`/`ts` per AI message in `putMessage`), and **removes the
  Classic/bubbles layout toggle**, committing to the bubble layout (§7.5).
- **Phase 4.5 — Reasoning ("thinking") display** (§6.8): read the reasoning
  field in the generation funnel; collapsible thinking section at the top of AI
  messages; global show/hide toggle. Confirm GLM's field/param and the
  toggle-vs-always-on decision first.
- **Phase 5 — Settings & Characters restyle** (tiles → rows/cards). Also the
  **Settings/Appearance cleanup** (§7.5): remove the Privacy section (keep one
  honest note in About), remove the monochrome-background toggle, and reframe
  AMOLED as a dark-theme option rather than an "experimental" switch. Also the
  **Experimental-features audit** (§7.6): remove the dead Autosave toggle; decide
  `/imagine` + function calling alongside the image audit.
- **Phase 6 — List screens & item layouts.**
- **Phase 7 — Dialogs & bottom sheets.**
- **Phase 8 — Onboarding, About, misc + cleanup** (dead RemoveAds remnants
  with owner approval; motion polish; this doc + CLAUDE.md updated to final
  state).
- **Phase 9 — Persona avatars** (§6.9): default star everywhere; remove the
  Customize-assistant feature; per-persona image picker + copy-to-storage +
  cleanup; chats render their persona's avatar. Largely independent (it extends
  the existing avatar plumbing) but touches the persona editor and Settings —
  best done **after** those screens' restyles (Phases 5–7) to avoid editing them
  twice, unless deliberately pulled earlier.
- **Phase 10 — User profiles (future)** (§6.9): persona-like profiles describing
  the *user*, each with its own image; reuses the Phase 9 avatar infrastructure.
  Spec further with the owner when reached.
- **Phase 11 — Image generation as a configurable provider** (§6.11.1): a
  dedicated image endpoint/model/key (separate from chat), `/imagine` +
  `generateImage` route to it, UI names the active generator, friendly errors.
  Keeps `/imagine`. Removes the useless `searchAtInternet` tool (§7.6).
- **Phase 12 — File attachments** (§6.11.3): attach text-ish files (txt/md/code/
  CSV/PDF), extract text on-device, inject as context; attach sheet gains "File".
  (The **vision `gpt-4o` hard-code bug fix** + graceful errors, §6.11.2, is small
  and rides Phase 4 — or can be a standalone quick fix sooner, since it likely
  makes image-attach work immediately.)

---

## 9. Pitfalls, dangers, and binding contracts

This is the section the owner asked for explicitly. **Read before every PR.**

### 9.1 ChatActivity view-ID contract (verified at `ChatActivity.kt:1370-1397`)

`activity_chat.xml` MUST keep these ids with these widget types (Kotlin
casts them; renaming or retyping = crash or silent breakage):

`btn_micro` (ImageButton), `btn_settings` (ImageButton), `messages`
(RecyclerView), `message_input` (EditText), `btn_send` (ImageButton),
`progress` (CircularProgressIndicator), `chat_activity_title` (TextView),
`btn_export` (ImageButton), `action_bar` (ConstraintLayout), `btn_back`
(ImageButton), `btn_debug_log` (ImageButton — the bug-icon Event-log
shortcut; see note below), `keyboard_frame` (ConstraintLayout), `root`
(ConstraintLayout), `thread_loader` (LinearLayout), `keyboard_input`
(LinearLayout), `btn_attach` (ImageButton), `attachedImage` (LinearLayout),
`selectedImage` (ImageView), `btnRemoveImage` (ImageButton),
`vision_action_selector` (LinearLayout), `action_camera`, `action_gallery`
(ImageButtons), `bulk_container` (ConstraintLayout), `btn_select_all`,
`btn_deselect_all`, `btn_delete_selected`, `btn_copy_selected`,
`btn_share_selected` (ImageButtons), `text_selected_count` (TextView),
`expandable_window_root` (CoordinatorLayout, keeps
`transitionName="chat_expand"`), `attach_bg` (BlurView).

**`btn_export` relocation (2026-06-24):** the owner's revised chat top bar
(§5.2 Step A) is `<<` · title · bug · gear and **does not include the export
icon**. `btn_export` is still in this contract because Kotlin casts it; do not
delete the id or its handler until its function has a confirmed new home
(open item — ask the owner). Hide the *view* if needed, keep the *binding*.

**`btn_debug_log` is a real feature, not decoration — do not drop it when
restyling the top bar.** It is a bug-icon `ImageButton` in the chat action bar
that jumps straight to the Event log (`LogsActivity`, `type=event`). It is
shown/hidden at runtime by `ChatActivity.updateDebugLogButtonVisibility()`
(re-checked in `onResume`): visible only when any voice diagnostic
(`voiceDiagnosticsEnabled()` — the Energy/WebRTC/Silero VAD logging toggles)
or Audio Health logging is on, `GONE` otherwise. So in normal use it is
invisible; a restyle that deletes the view, hard-codes its visibility, or
removes the `updateDebugLogButtonVisibility` calls breaks the diagnostics
shortcut. Keep the id, the click handler, and both visibility-refresh call
sites. (This is the chat-side half of a two-way debug loop: the Event log's
own `btn_voice_advanced` terminal icon jumps back to
`VoiceAdvancedSettingsActivity` — see CLAUDE.md.)

### 9.2 ChatAdapter item-layout contract (verified at `ChatAdapter.kt:210-221`)

ALL THREE message item layouts (`view_message.xml`,
`view_assistant_user_message.xml`, `view_assistant_bot_message.xml`) must
each contain: `ui` (ConstraintLayout), `icon` (ImageView), `message`
(TextView), `username` (TextView), `dalle_image` (ImageView), `btn_copy`,
`btn_edit`, `btn_retry`, `btn_report`, `btn_share`, `btn_speak`
(ImageButtons). Only `bubble_bg` (ConstraintLayout) is nullable/optional.
The adapter does **no null checks** on the rest — a missing id crashes on
first bind. If the redesign hides a button, hide it via
`visibility="gone"`, never by deleting the view.

**Phase 4 change — `btn_report` becomes `btn_info`** (owner decision 2026-06-24,
§6.7 / §6.10): the report action has no backend, so **repurpose its slot** as an
**Info** button (info "i" icon, AI-only, shows a model + received-time popup)
rather than deleting it. Rename `btn_report` → `btn_info` across all three
layouts, swap the icon, replace the report handler (drop the
`ReportAIContentBottomSheet` wiring) with the info popup, and keep the `isBot`
gating. Also `btn_speak` (Listen) moves to the **top** of the assistant layout —
same id, repositioned.

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
   standing header-vanishing bug in Section 7.4 — Phase 4 must make it
   sufficient (it currently omits `btn_debug_log` and the input/message
   controls), not merely preserve it.**
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
7. **Strings only in `res/values/strings.xml`**; never edit other locales
   unless asked. Removing a string requires removing usages — but orphaned
   translations in locale files are harmless; missing *default* strings
   break the build.
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
    drawable; keep the theme's translucency flags. **Owner update (2026-06-24):
    Settings and its sub-screens must NOT float as a rounded card — they become
    full-screen menus (§6.4). That means dropping the
    `expandable_window_background*` floating look (and likely `Theme.Transparent`)
    *for Settings*, while KEEPING it for ChatActivity, where the chat-open
    shared-element transition needs it. Handle the two cases separately; do not
    strip ChatActivity's translucency.**
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

---

## 11. Foundational fix — stable persona IDs (owner-approved 2026-06-24, do early)

A small data-model refactor that removes a latent bug and unblocks the persona
features. It is **logic-only** (no UI) and should be its own PR — never folded
into a UI restyle (Section 0 rule 3). Scheduled as **Phase 2.6**.

### 11.1 The problem (verified in code)

A persona's identity is **derived from its name**:
`PersonaPreferences.setPersona` computes `id = Hash.hash(persona.label)`
(`PersonaPreferences.kt:67`) and stores its fields under `<id>_<field>` flat
keys; `editPersona` is literally `deletePersona(hash(oldLabel)) +
setPersona(...)` (`:130`). So **renaming a persona produces a brand-new id**.
Chats reference a persona by `persona_id` (`Preferences.kt:1303`), which is that
same hash. Consequences:

- Renaming a persona **orphans every chat already linked to it** — the chat's
  stored `persona_id` no longer resolves. This is a **latent bug today**, not
  just a future-avatar concern.
- Any new per-persona artifact keyed by id (the Phase 9 avatar file) would
  orphan on rename too.

The fragile "edit dialog must pass every field through" rule (CLAUDE.md) and the
chat "auto-naming copy block" both exist only to paper over this name-as-key
design. `PersonaObject` has **no `id` field** — the id is implicit in the prefs
key and assumed `== Hash.hash(label)` everywhere.

### 11.2 The fix: stable id, name becomes a plain field

Decouple identity from name. A persona gets a **permanent id** assigned once; the
name is just editable data. Then a rename touches **one field** and every
reference (chats, avatar, lorebook links) keeps working unchanged.

- Add an **`id` field to `PersonaObject`**.
- `getPersona(id)` populates `persona.id = id` (the key prefix it read from).
- `setPersona` keys by **`persona.id`**, not `Hash.hash(label)`.
- **New persona:** assign `id = a fresh random token` (e.g. UUID), not a hash of
  the name. (This also makes duplicate display names harmless — different ids.)
- **Edit/rename:** update **in place** under the same id — no delete+recreate.
  `editPersona` becomes "overwrite the same id's fields".
- Fix **every call site that assumes `id == Hash.hash(label)`**: grep `Hash.hash`
  near persona code, `getPersona(Hash.hash(...))`, `editPersona`, and check
  `PersonasListActivity`, `EditPersonaDialogFragment`, `ChatActivity`'s persona
  application, and the Quick Settings persona selector. Use the stored id.

### 11.3 The migration is nearly free (why it's low-risk now)

**Existing personas keep their current id as the frozen id.** Their prefs keys
are already prefixed with `Hash.hash(currentLabel)`, and existing chats'
`persona_id` already equals that same value. So at migration we simply **stop
recomputing the id** and **freeze whatever prefix a persona already has**:

- `getPersonasList`/`getPersona` already read the id from the key prefix — keep
  doing that; just surface it on `PersonaObject.id`.
- **No data rewrite, no re-pointing of existing chats, no orphaning at upgrade.**
  Existing links stay intact; only *future* renames become safe.
- New personas created after this change get UUID ids.

Owner context: currently the only user, with essentially one persona, so even a
worst case (an old chat falling back to "no persona → default star") would be
harmless — but the freeze approach means even that won't happen.

When this lands, **update CLAUDE.md**: the "renaming a persona changes its id /
edit = delete + recreate" invariant is replaced by "personas have a stable id;
rename edits the name field in place."

### 11.4 Scope boundary — chats have the same flaw, but later

`chatId = Hash.hash(chatName)` is the identical design, with a **much larger
blast radius** (per-chat prefs files `settings_<chatId>`, message store
`chat_<chatId>`, the auto-naming copy block). **Out of scope for Phase 2.6** —
note it as a separate, bigger future decision. Do personas first; revisit chats
once the persona fix has proven the pattern.
