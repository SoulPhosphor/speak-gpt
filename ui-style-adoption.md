# UI Style Adoption Map

This file records which screens currently use the app's shared visual system.

It is separate from `ui-style-guide.md`:

- `ui-style-guide.md` defines the available style families and when to use them.
- this file records where those styles are currently adopted, partially adopted, intentionally custom, legacy, or not yet audited.

This is a current-status map, not a rollout history.

## Status meanings

### Shared

All repeated visual components covered by the current style system use the appropriate shared styles or shared layouts.

Feature-specific content and behavior may still remain local.

### Partial

The screen uses some shared styles but still contains copied visual attributes, an old component pattern, or a mixture of shared and local implementations.

A partial screen must not be copied as a whole-screen reference.

### Unconverted

The screen still uses local or copied visual definitions for repeated components that already have an approved shared style.

### Custom approved

The screen intentionally uses a different approved pattern because its function or structure is materially different.

Custom approved does not mean it should become the reference for ordinary screens.

### Legacy / remove

The component or mechanism is scheduled for removal or replacement.

Do not preserve, expand, restyle, repair for appearance, or create new dependencies on it unless the owner explicitly approves a temporary safety fix.

### Unaudited

The current implementation has not yet been checked against `ui-style-guide.md` and `themes.xml`.

Do not guess its status from appearance or from old documentation.

## Rules for maintaining this map

- Verify the current XML and relevant code before assigning or changing a status.
- Update this map in the same approved change that converts or intentionally exempts a screen.
- Record the current state only. Do not add dates, rollout stories, old bugs, branches, or completed-work narratives.
- Keep notes limited to the remaining shared-style gap or the reason for an approved exception.
- Do not call a screen fully shared merely because it looks similar to another screen.
- Do not use a Partial, Unconverted, Legacy / remove, or Unaudited screen as a visual template.
- When the owner asks one screen to match another, decompose both screens by component and use the approved shared style for each component.

## Current verified entries

| Screen | Layout | Header | Body components | Status | Current gap or exception |
|---|---|---|---|---|---|
| Edit Companion | `activity_edit_persona.xml` | Partial | Shared field labels, hints, and boxes | Partial | Header uses the shared container, back control, title, and end-anchored Save icon. The additional Delete icon repeats the shared header-icon geometry locally. Do not copy this header as raw XML. |
| Edit Persona | `activity_edit_user_persona.xml` | Partial | Shared field labels, hints, and boxes | Partial | Same mixed two-action header pattern as Edit Companion: the end-anchored Save icon is shared, while the additional Delete icon repeats geometry locally. |
| Memory Backup & Restore | `activity_memory_backup_restore.xml` | Unconverted | Partial: shared section styles and some shared button variants are present | Partial | The top back-and-centered-title header is still copied XML instead of the shared simple-header styles. Remaining body components require a full current audit before the screen can be treated as shared. |

All screens not listed above are **Unaudited** in this map until their current layouts and relevant code are checked.

## Current legacy direction

| Component or mechanism | Status | Direction |
|---|---|---|
| Legacy per-screen AMOLED recoloring and its dedicated control | Legacy / remove | It is not part of the future shared theme architecture. Do not spend work preserving, expanding, restyling, or routing new shared components through it. The future theme system should use shared semantic colors, styles, and theme or palette definitions instead of per-screen recoloring. |

## Audit record format

Add one concise row per screen or genuinely shared layout family:

| Screen | Layout | Header | Body components | Status | Current gap or exception |
|---|---|---|---|---|---|
| Screen name | `layout_name.xml` | Shared / Partial / Unconverted / Custom approved / N/A | Short component summary | Status | Only the current gap or approved exception |
