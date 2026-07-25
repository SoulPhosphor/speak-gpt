# UI Style Adoption Map

This file records which screens currently use the app's shared visual system.

It is separate from `ui-style-guide.md`:

- `ui-style-guide.md` defines the available style families and when to use them.
- this file records where those styles are currently adopted, partially adopted, intentionally custom, legacy, or not yet audited.

This is a current-status map, not a rollout history.

## Status meanings

### Shared

Every repeated visual component on the screen uses the appropriate approved shared style or shared layout.

This includes applicable headers, action icons, buttons, fields, hints, validation text, counters, image treatments, rows, sections, selectors, switches, checkboxes, dialogs, loading states, and spacing patterns.

Feature-specific content and behavior may remain local. A component that is genuinely unique may remain local only after its uniqueness is verified rather than assumed.

### Partial

The screen uses some shared styles but still contains copied visual attributes, an old component pattern, a repeated element with no shared style, or a mixture of shared and local implementations.

A Partial screen must not be copied as a whole-screen reference.

### Unconverted

The screen still uses local or copied visual definitions for repeated components that already have an approved shared style.

### Custom approved

The screen intentionally uses a different approved pattern because its function or structure is materially different.

Custom approved does not mean it should become the reference for ordinary screens.

### Legacy / remove

The component or mechanism is scheduled for removal or replacement.

Do not preserve, expand, restyle, repair for appearance, or create new dependencies on it unless the owner explicitly approves a temporary safety fix.

### Unaudited

The current implementation has not yet been checked against `ui-style-guide.md`, `themes.xml`, and any shared layouts it should use.

Do not guess its status from appearance or from old documentation.

## Rules for maintaining this map

- Verify the current XML and relevant code before assigning or changing a status.
- Audit the whole screen, not only the component named in the current task.
- Update this map in the same approved change that converts or intentionally exempts a screen.
- Record the current state only. Do not add dates, rollout stories, old bugs, branches, or completed-work narratives.
- Keep notes limited to the remaining shared-style gap or the reason for an approved exception.
- Do not call a screen Shared merely because it looks similar to another screen or because several shared styles appear in its XML.
- Do not use a Partial, Unconverted, Legacy / remove, or Unaudited screen as a visual template.
- When the owner asks one screen to match another, decompose both screens by component and use the approved shared style for each component.
- If a repeated component has no adequate shared style, stop before copying attributes. Explain the missing style family and obtain approval for the shared solution.

## Current verified entries

| Screen | Layout | Header | Repeated body components | Status | Current gap or exception |
|---|---|---|---|---|---|
| Edit Companion | `activity_edit_persona.xml` | Partial | Field labels, hints, boxes, and the main Add button use shared styles | Partial | The additional Delete header icon repeats the shared icon geometry locally. The 96dp editor image treatment, inline field-error text, and final checkbox styling remain local repeated patterns. The dynamically added lorebook rows have not yet been audited. Do not copy this screen as a complete style reference. |
| Edit Persona | `activity_edit_user_persona.xml` | Partial | Field labels, hints, and boxes use shared styles | Partial | The additional Delete header icon repeats geometry locally. The matching 96dp editor image treatment, inline validation text, and text counter remain local repeated patterns. Do not call this screen fully converted or copy it as a complete reference. |
| Memory Backup & Restore | `activity_memory_backup_restore.xml` | Unconverted | Partial: shared section styles and some shared button variants are present | Partial | The top back-and-centered-title header is still copied XML instead of the shared simple-header styles, including a local 20sp title. Remaining status text, progress rows, controls, and other body components require a full current audit before the screen can be treated as Shared. |

All screens not listed above are **Unaudited** in this map until their current layouts and relevant code are checked.

## Current legacy direction

| Component or mechanism | Status | Direction |
|---|---|---|
| Legacy per-screen AMOLED recoloring and its dedicated control | Legacy / remove | Existing code does not make this mechanism a requirement. It is not part of the future shared theme architecture. Do not spend work preserving, expanding, restyling, repairing for appearance, or routing new shared components through it. The replacement theme system should use shared semantic colors, styles, and theme or palette definitions instead of per-screen recoloring. |

## Audit record format

Add one concise row per screen or genuinely shared layout family:

| Screen | Layout | Header | Repeated body components | Status | Current gap or exception |
|---|---|---|---|---|---|
| Screen name | `layout_name.xml` | Shared / Partial / Unconverted / Custom approved / N/A | Short component summary | Status | Only the current gap or approved exception |