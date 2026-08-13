<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Plan

## Authority

This file is the **single authoritative product and design specification** for the chat redesign.

- `chat-redesign-implementation-playbook.md` defines implementation order only. It may not add, remove, reinterpret, or override product/design decisions in this file.
- `chat-redesign-handoff.md` is only a short implementation entry point.
- Git history is the record of older decisions. Do not create dated addenda or parallel design authorities for future corrections; update this file directly.
- The app-wide style guide remains a **separate overall-app authority**. Do not copy or merge the general style sheet into this chat plan.
- If implementation exposes a genuine product conflict not resolved here, stop for owner input rather than inventing a decision.

## 1. Safety and architecture

Chat is load-bearing. This redesign changes presentation while preserving working chat behavior unless this plan explicitly says otherwise.

Preserve existing streaming, markdown rendering, message selection, edit, retry, speak, share, copy, attachments, generated images, auto-naming, keyboard/IME behavior, voice state, message actions, and other established chat behavior. Preserve load-bearing IDs/listeners unless this plan explicitly requires a coordinated rename.

### 1.1 One chat engine, one current presentation shell

For this plan, **presentation shell** means the visual arrangement of a message: alignment, bubble/reading surface, portrait, name, metadata placement, spacing, and placement of message content/actions. It does **not** mean the chat engine or message behavior.

Use the simplest safe structure:

- Keep **one shared chat behavior/data system** underneath presentation.
- Build **one current presentation shell** for this redesign. That shell handles AI-left and user-right messages and adapts to the Appearance toggles.
- Do **not** build a second shell, shell manager, renderer registry, preset framework, or other future-facing machinery now.
- Do **not** duplicate streaming, markdown, actions, attachments, metadata, persistence, provider logic, or other chat behavior inside presentation code.
- Keep shared message data/behavior independent enough from this shell that a genuinely different second presentation shell can be added later without duplicating or rewriting the chat engine.

A future second shell is allowed, but it is a future feature. It is not part of this redesign and does not need to be designed now.

The old **Classic / Non-Classic** selector is retired. Do not preserve it as a current mode or architectural split. A future alternate shell, if one is ever added, is a new presentation option rather than a continuation of the old Classic preference.

### 1.2 Configurable decoration within the current shell

The current shell adapts rather than creating separate renderers for every toggle combination.

Structural content remains available as appropriate for the message:

- AI-left / user-right speaker alignment;
- message body and markdown;
- file attachments, generated/attached images, status content;
- Message Actions;
- universal Message Details `ⓘ`;
- existing message behavior.

Appearance may independently control:

- Profile images;
- Names;
- AI bubble;
- User bubble;
- Model names;
- Token usage;
- default User and AI name font/size;
- optional per-companion AI-name font/size override.

Changing these controls may alter visibility, backgrounds, margins, insets, padding, and name typography. It must not create a separate behavioral chat implementation.

## 2. Message geometry

Use these values as the approved Android `dp` geometry. Validate responsively on unusual widths/font scales with the smallest correction that preserves the relationships; do not replace them with percentage-of-screen positioning.

### 2.1 Alignment and width

- AI messages anchor from the **left**.
- User messages anchor from the **right**.
- Mirrored speaker-side bubble/reading-surface distance: **`27dp`**.
- User-only left inset: **`26dp`**. This narrows only the user's left boundary. It must not move the user's right anchor or resize the screen/container.
- Bubble visibility must never change speaker alignment.
- When a bubble is off, presentation may reclaim border/padding space where appropriate, but the approved speaker-side anchors and user directional inset remain the layout reference.

### 2.2 Bubble / reading surface

- Radius: **`16dp` uniform corners** for both speakers.
- Internal padding: **`14dp` horizontal and `14dp` vertical**.
- Do not silently retune this padding while solving outer geometry.
- A bubble is decoration, not a separate message architecture.

When a name intersects the top border, the border must stop/clear around the glyphs. Do not draw through the name and do not add a filled/color chip merely to mask the line.

### 2.3 Portraits

When Profile images is enabled:

- portrait size: **`76dp`**;
- horizontal offset from mirrored speaker-side baseline: **`-15dp`**;
- vertical offset: **`-36dp`**;
- AI/user values mirror each other;
- portraits visually overlap the bubble/reading-surface corner;
- use an inset/pseudo-wrap approach so early text clears the portrait and later content can use normal width; do not build arbitrary text-flow-around-view logic.

When portraits are disabled, reserved portrait space collapses cleanly.

### 2.4 Names

Default chat-name size is **`18sp`**.

With portraits enabled:

- name horizontal offset from mirrored speaker-side baseline: **`52dp`**;
- name vertical offset: **`-30dp`**;
- mirror AI/user placement;
- clear the bubble border around name glyphs;
- no filled/color name background.

With portraits disabled:

- AI name begins **`1dp` from the AI bubble's left edge**;
- User name begins **`1dp` from the User bubble's right edge**, mirrored;
- vertically center the name on the top border line;
- the border stops before the glyphs and resumes after them;
- no filled/color name background.

If Names is off, omit the name and do not leave dead identity space.

### 2.5 Message rhythm

- Visible separation between neighboring message units: **`53dp`**.
- Treat the entire message region as the unit when bubbles are off.
- Portrait/name overlap must not consume this visible separation.

## 3. Chat name typography

Chat identity names are configurable without changing message-body typography.

Create one shared **Chat Name Style** abstraction that owns:

- font family;
- size in `sp`;
- supported weight/style;
- name identity treatment and border-clearance behavior.

Appearance owns independent User and AI defaults. A companion may optionally override the AI default font and/or size; otherwise it inherits the Appearance AI default. Do not add per-message typography overrides.

Names use normal weight by default. Appearance provides independent **Bold User name** and **Bold Companion name** toggles, both default Off. Each toggle applies only to its own side. Do not force bold in message layouts or individual font registrations.

Initial bundled name families:

1. Roboto
2. Kalnia
3. Homemade Apple
4. Crafty Girls
5. Manufacturing Consent
6. Special Elite
7. Solitreo
8. SN Pro

Use the actual family names in the UI, not personality categories. Bundle only required faces and preserve required font licensing metadata/notices. Additional approved families should be registerable through the shared name-style/font-picker path without redesigning message layouts.

The font picker must render samples using an editable **Preview text** field. Preview text is temporary picker input only; it does not rename the user or companion.

## 4. Compact message metadata

Persistent compact metadata is intentionally narrower than full Message Details.

### 4.1 Model names

- Independent Appearance toggle.
- When enabled, show the model that actually produced that message.
- Historical turns must never be relabeled from the model currently selected for the chat.

### 4.2 Token usage

- Independent Appearance toggle.
- Default: **Off**.
- Use provider-reported token data for that specific completed turn.
- If usable token data was not reported, omit it. Do not invent or estimate a persistent value.

### 4.3 Placement and persistence

- Put enabled compact metadata beneath the speaker name/identity line.
- When both model and tokens are visible, use one subordinate line when space permits, separated by a centered dot (`·`); allow natural wrapping for long identifiers.
- If Names is off, move metadata into the top identity region rather than reserving a blank username row.
- Persist required message-level model/token fields so mixed-model history survives reopening the conversation.

## 5. Message Actions and Message Details

Preserve existing action icons, relative order, visibility logic, click behavior, and touch behavior unless explicitly changed below.

Add a universal **Message Details `ⓘ`** action as the **far-left Message Action** for both user and AI messages. It is always available and uses the same visual/touch sizing as the other actions.

Tapping `ⓘ` opens a small anchored popup from/above the action area:

- may overlap chat content;
- text is selectable/copyable;
- may use a maximum height with internal scrolling;
- outside tap/back dismisses normally;
- show only fields meaningful for that message type.

Possible fields include date, time, model, provider, endpoint/source where relevant, token information, response identity, and completion/generation status.

There is no “Always show Message Details” mode. Model names and Token usage are the only persistent compact metadata controls; `ⓘ` remains the full on-demand view.

## 6. Attachments and visual media

### 6.1 File/document attachments

When a message contains text:

**text → Message Actions → file/document attachment row/tray**

When a message contains only file attachment(s):

**identity → attachment(s) inside the main bubble/reading surface → Message Actions**

Do not render an empty text area and do not invent visible user text merely to give an attachment-only message a prompt. Apply the same rule to user and AI messages where file attachments exist.

The exact file card/chip styling is an implementation-level detail as long as filename/type affordances and existing behavior remain clear and usable.

### 6.2 Images / generated visual media

Images are provider-neutral visual message content, not document/file-tray content.

With text:

**identity/compact metadata → text → image/generated visual media → Message Actions**

Image-only:

**identity/compact metadata → image/generated visual media → Message Actions**

Do not move ordinary images below Message Actions with document attachments.

### 6.3 Provider-neutral generated-image naming

Active chat presentation must not remain DALL-E-specific. Re-verify current references, then perform one coordinated rename of active presentation identifiers/helpers/comments such as legacy `dalle_image`, `dalleImageStringList`, and `processDalleFile` naming to provider-neutral equivalents. Rename required layout IDs and every adapter reference atomically so load-bearing IDs are never changed in isolation.

Remove dead DALL-E/OpenAI presentation branches only after targeted reference checks prove them unused. Preserve legacy image preference readers that are still required by the one-time image-generation migration until that migration's deletion gate is satisfied.

Preserve the existing provider-neutral generation architecture and separation between image-generator configuration and current conversation configuration. A compile/static audit is not an end-to-end provider runtime test; do not claim runtime verification unless a real request has succeeded.

## 7. Thinking / provider-supplied reasoning

Provider-supplied reasoning or reasoning summaries belong to the specific AI message that produced them and remain visually distinct from the final answer.

### 7.1 Placement and behavior

When normalized reasoning content exists, show a compact expandable **Thinking** row:

**identity/compact metadata → Thinking disclosure → final answer**

- Thinking is its own movable child of the current AI presentation shell, not text baked into the final-answer markdown string.
- Default state: collapsed.
- Visible label: **Thinking** followed by a thin outline-style chevron.
- Collapsed chevron points right; expanded chevron points down.
- Tapping only expands/collapses existing content; it must not regenerate or alter the final answer.
- If no separately supplied reasoning exists, render no empty Thinking row.

### 7.2 Provider-neutral ownership

Do not hard-code model-name lists to decide whether Thinking appears.

- Provider/protocol adapters inspect actual response/stream payloads.
- Normalize supported provider-specific reasoning fields/content blocks into one provider-neutral per-message representation.
- If only a reasoning summary is supplied, preserve/display it as a summary rather than presenting it as raw reasoning.
- Never expose hidden/internal reasoning that the provider did not return to the app.
- Persist reasoning with the specific message so it survives reopening and is not changed by later model switches.
- Streaming may update separately streamed reasoning while the response is in progress; persisted completed message data is the source of truth after completion.

The universal `ⓘ`, Model names, and Token usage behavior remains independent of Thinking. Thinking is content-presence driven, not an Appearance toggle.

### 7.3 Provider verification responsibility

The implementation agent, not the owner, is responsible for targeted verification when this feature is implemented:

- inspect only the provider/protocol adapters actually supported by the app;
- verify current primary API/provider documentation for those adapters;
- determine which supported response/stream forms expose separate reasoning or summaries;
- normalize supported forms;
- add focused parsing/persistence tests where the existing test architecture permits;
- gracefully show no Thinking row when no separate reasoning is supplied;
- document any supported provider path where upstream reasoning exists but the app cannot currently obtain it.

## 8. Appearance destination and legacy settings migration

Add **Appearance** in Control Center directly beneath **Images**.

Visible copy:

- Title: `Appearance`
- Subtitle: `Customize the look of your chat.`

Use the existing shared overall-app row/style system. Do not copy the general style sheet into this plan.

Appearance controls, in order:

1. Profile images — On / Off
2. Names — On / Off
3. AI bubble — On / Off
4. User bubble — On / Off
5. Model names — On / Off
6. Token usage — On / Off; default Off
7. Hardware Keyboard Shortcuts — On / Off
8. Bold User name — On / Off; default Off
9. Bold Companion name — On / Off; default Off
10. User name font
11. User name size
12. AI name font
13. AI name size
14. editable Preview text for font samples

Companion editor:

- optional AI-name font/size override;
- default is **Use Appearance default**;
- affects only that companion's displayed chat name.

### 8.1 Migration rules

- **Classic / Non-Classic:** remove the UI and retire the old product concept. Do not migrate it into a replacement mode.
- **Desktop Mode:** remove the old tile/label and expose the same stored behavior as **Hardware Keyboard Shortcuts**. Preserve the existing value.
- **Hide Model Names:** replace the negative UI with positive **Model names**. Preserve effective behavior: old `hideModelNames = true` maps to Model names = Off; false maps to On.
- **Monochrome:** remove the setting and obsolete chat-list-only presentation behavior.
- **AMOLED:** remove the standalone Control Center tile, but preserve existing AMOLED capability/wiring for later Themes integration.
- **Auto-save Chats:** out of scope. Do not remove, rewrite, or migrate it.

Moving a setting is not permission to replace its stored preference or silently reset users.

For fresh installs, use conservative defaults that most closely preserve the app's existing effective default appearance unless this plan explicitly locks a value. Token usage is explicitly Off by default.

## 9. Composer and keyboard behavior

Keep the current short-message interaction model while improving multiline drafting.

- At one line, the editable field occupies the available center space between the existing side controls.
- The field never renders behind/under those controls.
- As text wraps, the text area/composer grows **upward** while side controls remain bottom-anchored.
- Preserve a sensible maximum height; the existing approximately **`120dp`** maximum is the reference unless device testing requires a small correction.
- Beyond the maximum, the draft scrolls internally rather than consuming the conversation viewport.
- Preserve existing attach/mic/send/conversation behavior, IDs/listeners, text watcher, voice-state tinting, send semantics, and hardware keyboard behavior.
- Fix the case where the composer can sit partly beneath the software keyboard by using one coherent window-inset/keyboard mechanism. Do not stack a second competing offset hack on top of the existing system.

## 10. Header and future drawer

- Do not make a header redesign a prerequisite for the message restyle.
- The current chat title/header treatment may remain unless a safety/layout correction is necessary.
- Fix the standing intermittent header/title disappearance bug if it is still present.
- Future drawer work may change the upper-left control, but it must not require rewriting message rows or chat behavior.
- Per-message model/provider/identity metadata stays with the message, not the header.

## 11. Theme and future-background readiness

The chat redesign must remain compatible with the app's existing overall style/theme system, but **general style rules remain in the separate app-wide style guide and are not duplicated here**.

Chat-specific requirements:

- do not hard-code palette-specific colors into the new message shell, metadata, Thinking, composer, portraits/names, Message Actions, or Appearance screen;
- if a chat-specific semantic color role is needed, integrate it through the existing app-wide theme/style system rather than creating a parallel chat palette system;
- preserve AMOLED capability/wiring; do not resume or redesign the AMOLED/theme project during this work.

Future decorative chat backgrounds may include solid color, subtle texture/pattern, or user-supplied image. They belong behind the conversation area; header and composer remain controlled surfaces.

**Readability rule:** if a non-solid/decorative background is active, readable user/AI message text must have an opaque theme-controlled reading surface underneath it even if the user's normal bubble decoration is off. Keep required reading surface and optional bubble decoration conceptually separate.

## 12. Implementation-level freedom

The owner does not need to make ordinary coding decisions. Choose the smallest implementation that satisfies this contract.

Implementation-level tuning includes:

- responsive constraints needed to preserve approved geometry on unusual widths/font scales;
- portrait frame/border details that do not change approved geometry;
- tiny border-clearance adjustments around name glyphs;
- small Message Details popup dimensions;
- targeted schema/storage changes needed to persist message metadata/reasoning while keeping existing chats readable;
- exact Android XML/Kotlin technique.

Do not use implementation freedom to reopen product decisions, redesign unrelated systems, or perform broad cleanup.

## 13. Final contract summary

The redesign delivers:

- one shared chat behavior/data system;
- one current adaptable presentation shell for AI/user messages, with a clean boundary that permits a second shell later without building it now;
- independent portraits, names, AI/User bubbles, model names, token usage, and name styling;
- approved `27dp` / `26dp` alignment, `16dp` corners, `14dp` internal padding, `76dp` portrait geometry, and `53dp` message rhythm;
- durable per-message model/token ownership;
- permanent far-left `ⓘ` Message Details;
- preserved attachment behavior and provider-neutral visual media presentation;
- provider-neutral Thinking disclosure for reasoning actually supplied by providers;
- dedicated Appearance settings with safe legacy preference migration;
- upward-growing composer with stable controls and corrected keyboard insets;
- compatibility with the separate app-wide style/theme system;
- preserved existing chat behavior throughout.

No dated addenda or parallel design authority are required. Future approved chat-design changes update this file directly.
