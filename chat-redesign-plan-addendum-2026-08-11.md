<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Plan Addendum — August 11, 2026

This addendum is part of the Phase 4 chat-redesign plan and records owner rulings made after the latest `chat-redesign-plan.md` commit. Where this addendum conflicts with older plan wording, this addendum wins until the main plan is reconciled.

## 1. Legacy Control Center cleanup

The old six-tile chat/settings block is being retired deliberately.

- **Classic / Non-Classic:** remove the UI and retire the concept entirely. Do not migrate it into a replacement mode or continue honoring the old layout preference as a user-facing design selector.
- **Desktop Mode:** remove the old tile and expose the same underlying behavior in Appearance as **Hardware Keyboard Shortcuts**.
- **Hide Model Names:** remove the old tile and expose the same setting in Appearance as **Hide Model Names**.
- **Monochrome:** remove the setting and its obsolete chat-list-only presentation behavior. Do not carry it into Appearance.
- **AMOLED:** remove the standalone Control Center button/tile, but do **not** blindly remove the existing AMOLED implementation or wiring. Preserve the capability so it can later be incorporated properly into Themes.
- **Auto-save Chats:** explicitly **out of scope** for this redesign/settings cleanup. It has its own wiring and will be handled in a separate careful sweep. Do not remove, rewrite, or migrate it as part of Phase 4.

## 2. Appearance additions

Add these controls to the Appearance destination in addition to the already-approved chat display/name-style controls:

- **Hide Model Names** — On / Off
- **Hardware Keyboard Shortcuts** — On / Off

Those exact Title Case labels are binding.

### Preference-preservation rule

Moving a setting into Appearance is not permission to replace its stored preference key or reset the user's value.

- Preserve the existing stored value/behavior for Hide Model Names.
- Preserve the existing Desktop Mode preference/value when it is relabeled as Hardware Keyboard Shortcuts.
- If a technical migration becomes necessary, carry the old value forward rather than silently reverting to a default.

## 3. Message Details icon sizing

The universal far-left `ⓘ` Message Details action should use the same visual size and touch-target sizing as the other Message Actions icons. It is not a larger or emphasized control.

## 4. Implementation-tuning variables

Do not stop implementation to ask the owner to invent dimensions that can only be judged visually.

The following are implementation-tuning variables rather than unresolved owner design questions:

- exact assistant/user maximum message widths;
- exact user gutter beyond the already-approved visible-left-gutter rule;
- portrait size within the approved large-portrait intent;
- portrait overlap depth and frame treatment;
- name-badge padding/radius/precise intersection depth;
- small Message Details popup dimensions;
- minor internal padding/spacing that does not change the established hierarchy.

Choose sensible first-pass values that preserve the approved structure, then validate with screenshots/on-device testing. Owner input is required for conceptual changes, not for a 2dp adjustment that preserves the contract.

## 5. Portrait geometry design aid

`chat-portrait-geometry-mockup.html` is an interactive measuring board committed to the `chat-redesign` branch for tuning portrait size, overlap depth, and the user left gutter before values are transferred into Android XML.

- Treat 1 CSS px as a conceptual 1dp starting value for proportion work, not as a production rendering rule.
- Current first-pass preset: **80dp portrait / 24dp overlap**.
- The mockup is a design aid only and is expected to receive visual tweaks.
- Once proportions look right, transfer those measurements into XML and validate on the Pixel test device.

## 6. Immediate implementation-order correction

Before the adaptable message-shell work begins:

1. Create Appearance with the already-approved controls.
2. Move Hide Model Names and the existing hardware-keyboard behavior into Appearance without resetting stored values.
3. Remove the obsolete legacy tiles listed above while leaving Auto-save Chats untouched and preserving AMOLED wiring.
4. Continue with the message-shell implementation order already defined in `chat-redesign-plan.md`.
