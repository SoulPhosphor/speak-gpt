<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Plan Addendum — August 11, 2026

This addendum is part of the Phase 4 chat-redesign plan and records the final outer-message geometry and legacy-settings cleanup rulings made on August 11. The main `chat-redesign-plan.md` has now been reconciled with the later August 12 metadata, image-content, and composer rulings. For any later conflict outside the locked geometry in Section 4 of this addendum, the newer dated ruling in `chat-redesign-plan.md` wins.

## 1. Legacy Control Center cleanup

The old six-tile chat/settings block is being retired deliberately.

- **Classic / Non-Classic:** remove the UI and retire the concept entirely. Do not migrate it into a replacement mode or continue honoring the old layout preference as a user-facing design selector.
- **Desktop Mode:** remove the old tile and expose the same underlying behavior in Appearance as **Hardware Keyboard Shortcuts**.
- **Hide Model Names:** remove the old negatively worded tile. The new Appearance control is the positive **Model names** On / Off toggle defined in `chat-redesign-plan.md`. Preserve the user's existing behavior by mapping the old hide value to the inverse visible-state value; do not silently reset it.
- **Monochrome:** remove the setting and its obsolete chat-list-only presentation behavior. Do not carry it into Appearance.
- **AMOLED:** remove the standalone Control Center button/tile, but do **not** blindly remove the existing AMOLED implementation or wiring. Preserve the capability so it can later be incorporated properly into Themes.
- **Auto-save Chats:** explicitly **out of scope** for this redesign/settings cleanup. It has its own wiring and will be handled in a separate careful sweep. Do not remove, rewrite, or migrate it as part of Phase 4.

## 2. Appearance additions and preference preservation

Add **Hardware Keyboard Shortcuts** — On / Off to the Appearance destination in addition to the controls defined in the main chat plan.

The former Hide Model Names setting is not exposed under that old negative label. Its replacement is the main plan's positive **Model names** toggle. The main plan also adds the independent **Token usage** toggle, which defaults Off.

### Preference-preservation rule

Moving a setting into Appearance is not permission to replace its stored preference key or reset the user's value.

- Preserve the existing Hide Model Names behavior when moving to the positive **Model names** UI. If the old stored value is `hideModelNames = true`, the new visible-state control must initially read Model names = Off; if false, it reads On. A future preference-key cleanup may migrate this explicitly, but must preserve the effective value.
- Preserve the existing Desktop Mode preference/value when it is relabeled as Hardware Keyboard Shortcuts.
- If another technical migration becomes necessary, carry the old value forward rather than silently reverting to a default.

## 3. Message Details icon sizing

The universal far-left `ⓘ` Message Details action should use the same visual size and touch-target sizing as the other Message Actions icons. It is not a larger or emphasized control.

## 4. Final owner-approved outer message geometry

The geometry below supersedes the earlier approximate portrait, gutter, and message-spacing targets in older versions of `chat-redesign-plan.md`. The main plan is being reconciled to these same values rather than carrying a second set of working numbers.

The HTML tuner used a design reference of approximately **1 CSS px = 1 Android dp** for first-pass proportion work. These are intended as Android `dp` starting values, followed by normal device validation rather than percentage-of-screen values.

### 4.1 Mirroring rule

- AI geometry anchors from the **left** speaker side.
- User geometry anchors from the **right** speaker side.
- Portrait and portrait-on name offsets mirror between speakers.
- The **user-only left inset** is the deliberate asymmetry. It changes only the user's left message boundary; the user's right boundary remains anchored at the same speaker-side distance used by the AI on the left.
- Changing the user left inset must never resize the screen/container or move the user's right anchor.

### 4.2 Bubble placement

- **Bubble distance from speaker-side edge: `27dp`.** Use the same value from the AI-left edge and the user-right edge.
- **User bubble left inset only: `26dp`.** This narrows the user message from the left while leaving its right edge fixed.
- Bubble / reading-surface radius remains **`16dp` uniform corners**.
- Bubble internal text padding remains **`14dp` horizontal** and **`14dp` vertical** for this geometry pass. Do not alter those values while implementing the outer positioning contract.

### 4.3 Portrait-on geometry

When Profile Images is enabled:

- Portrait size: **`76dp`**.
- Portrait horizontal offset from the mirrored speaker-side baseline: **`-15dp`**.
- Portrait vertical offset: **`-36dp`**.
- AI and user portraits use the same size and mirrored offsets.
- Portrait space collapses when Profile Images is disabled.

### 4.4 Names with portraits enabled

When Names and Profile Images are both enabled:

- Name horizontal offset from the mirrored baseline: **`52dp`**.
- Name vertical offset: **`-30dp`**.
- Apply those values as mirrored speaker-side offsets, not absolute screen coordinates.
- The name has **no filled/color background block** behind it.
- The name is text/identity treatment, not a chip or filled badge.
- The message border may be interrupted around the name as needed so a border line never runs through the name glyphs.

### 4.5 Names with portraits disabled

When Names is enabled but Profile Images is disabled, **do not reuse the portrait-on `52dp / -30dp` name offsets**. Use the bubble itself as the anchor:

- AI name begins **`1dp` from the AI bubble's left edge**.
- User name begins **`1dp` from the user bubble's right edge**, mirrored.
- Vertically center the name on the bubble's **top border line**. This relationship defines the vertical position; no separate Y-coordinate is required.
- The top border approaches the name, stops before the glyphs, and resumes after the name. **The line must never render behind or through the name text.**
- Do not place a filled/color background behind the name.
- If Names is disabled, omit the name treatment entirely.

### 4.6 Conversation rhythm

- **Distance between message units: `53dp`.**
- This is the outer message-unit rhythm chosen with the portrait overlap visible; do not fall back to the older `24dp` working target.
- Portrait/name overlap must not collapse this visible separation.

### 4.7 Locked / not tuned by the geometry exercise

The geometry exercise did not authorize unrelated visual changes:

- Bubble internal horizontal padding: **`14dp`**.
- Bubble internal vertical padding: **`14dp`**.
- Bubble radius: **`16dp`**.
- Message Actions spacing/order: preserve the existing behavior unless separately approved.
- Message body font size: unchanged.
- Composer geometry was not tuned by this portrait/bubble exercise. The later owner-approved composer behavior in `chat-redesign-plan.md` Section 4.14 is now authoritative: the field starts between the side controls and grows upward as text wraps while those controls stay bottom-anchored.

## 5. Remaining implementation-tuning variables

The owner has now supplied the important outer message geometry. Do not reopen those measurements as abstract design questions unless device testing exposes a real problem.

The following still remain implementation-level tuning rather than unresolved product decisions:

- responsive constraint details needed to preserve the approved geometry on unusually narrow/wide screens;
- portrait frame/border visual styling that does not change the locked `76dp` portrait geometry;
- tiny border-clearance details around name glyphs, provided the border never runs behind the name;
- small Message Details popup dimensions;
- other minor layout corrections required for large system font scale, insets, or rotation without changing the approved hierarchy.

Choose the smallest correction that preserves this contract and validate on the Pixel test device.

## 6. Portrait geometry design aid

`chat-portrait-geometry-mockup.html` is an interactive measuring-board artifact on the `chat-redesign` branch. It was used to reason about the geometry but its original defaults are no longer authoritative.

- Treat 1 CSS px as a conceptual 1dp starting value for proportion work, not as a production rendering rule.
- The binding final values are in Section 4 of this addendum.
- If the mockup's defaults disagree with Section 4, **Section 4 wins**.
- Transfer the approved relationships into Android XML/constraints and validate on the Pixel test device.

## 7. Immediate implementation-order correction

Before the adaptable message-shell work begins:

1. Create Appearance with the already-approved controls from `chat-redesign-plan.md`.
2. Move the old Hide Model Names behavior into the positive **Model names** control and move the existing hardware-keyboard behavior into **Hardware Keyboard Shortcuts**, without resetting stored values.
3. Remove the obsolete legacy tiles listed above while leaving Auto-save Chats untouched and preserving AMOLED wiring.
4. Continue with the adaptable message shell using the final geometry in Section 4 rather than older working targets.
5. Follow the newer main-plan rules for compact model/token metadata, provider-neutral image content, and the upward-growing composer.
