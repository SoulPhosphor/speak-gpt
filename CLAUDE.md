@CLAUDE_BASE.md

# Current owner overrides

The imported onboarding manual remains authoritative except where this file
explicitly supersedes an older instruction. Read the imported file in full
before changing the repository.

## Canonical app buttons and chevron rows

These July 18, 2026 rules supersede the older button and chevron-row section
inside `CLAUDE_BASE.md`.

- **Normal full-size app buttons use one of three canonical styles from
  `app/src/main/res/values/themes.xml`. Do not invent another button treatment
  when one of these applies.**
  - `AppButton.Primary` is the approved current filled button appearance.
  - `AppButton.Secondary` is the approved current outlined button appearance.
  - `AppButton.Destructive` is currently intentionally identical to Secondary.
    It remains separately named so destructive actions can be changed
    app-wide later without editing layouts. Do not make it red unless the
    owner asks.
- All three button styles preserve the current Material 3 appearance and use
  theme color roles, so palette changes flow through the theme system. They
  share `ShapeAppearance.AppButton`, `@dimen/button_corner_radius`, and
  `TextAppearance.App.Button`. Change those shared definitions for an
  app-wide shape or typography update. Do not copy font, text-size, shape,
  fill, or outline properties into individual layouts.
- Current button rollout is deliberately limited to Import, Export, and Reset
  on Memory Backup & Restore. Do not migrate additional buttons until the
  owner asks for the next screen or identifies a button that should match one
  of the canonical styles.

- **Chevron rows have two canonical variants.**
  - Use `Widget.App.Row.WithSubtitle` for a row with a title and one line of
    helper text.
  - Use `Widget.App.Row.TitleOnly` for a row with only a title. Omit the
    subtitle view entirely. Do not reserve blank subtitle space and do not
    create a preview from long body text.
- Both row variants preserve the approved Memory Manager appearance: 56dp
  minimum height, 12dp vertical padding, 16sp title, 13sp one-line ellipsized
  subtitle when present, and the same trailing chevron.
- Build either variant from the shared pieces:
  - outer container: `Widget.App.Row.WithSubtitle` or
    `Widget.App.Row.TitleOnly`
  - text column: `Widget.App.Row.TextColumn`
  - title: `Widget.App.Row.Title`
  - optional subtitle: `Widget.App.Row.Subtitle`
  - chevron: `Widget.App.Row.Chevron`
- Per-row content stays on the instance: `android:id`, title/subtitle text,
  chevron content description, and click handling. A first-row top margin is
  screen spacing and may remain on that instance.
- Row title, subtitle, and chevron colors route through the theme attributes
  `appRowTitleColor` and `appRowSubtitleColor` declared in
  `app/src/main/res/values/attrs.xml`. Future palette overlays can replace
  those values without editing row layouts. Global row typography belongs in
  the shared row styles, never copied into individual rows.
- Current row rollout is deliberately limited to the three rows in
  `activity_characters.xml`, all using `Widget.App.Row.WithSubtitle`.
  `Widget.App.Row.TitleOnly` is defined but not yet applied. Do not migrate
  additional rows until the owner reviews this slice and approves wider use.
