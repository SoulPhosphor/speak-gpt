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
- **Show Reasoning controls presentation/return of user-visible reasoning only. It must not be treated as a switch that disables model reasoning.** If the provider requires opaque/encrypted reasoning state, reasoning details, thought signatures, or equivalent continuation data to be preserved across turns or tool calls, retain and resend that state as required even when Show Reasoning is Off.

The universal `ⓘ`, Model names, and Token usage behavior remains independent of Thinking. Thinking is content-presence driven, not an Appearance toggle.

### 7.3 Provider verification responsibility

The implementation agent, not the owner, is responsible for targeted verification when this feature is implemented:

- inspect only the provider/protocol adapters actually supported by the app;
- verify current primary API/provider documentation for those adapters;
- determine which supported response/stream forms expose separate reasoning or summaries;
- determine whether each provider requires reasoning-state preservation across turns/tool calls and preserve that state correctly;
- normalize supported forms;
- add focused parsing/persistence tests where the existing test architecture permits;
- gracefully show no Thinking row when no separate reasoning is supplied;
- document any supported provider path where upstream reasoning exists but the app cannot currently obtain it.

### 7.4 Favorite-model reasoning settings

Reasoning configuration is stored with the favorite model and remains independent from provider routing. Do not fold reasoning controls into `ChooseProviderActivity` or otherwise make provider routing responsible for reasoning configuration. A model may support provider routing, configurable reasoning, both, or neither.

In favorite-model rows:

- keep the existing **provider cog** as the direct shortcut to provider routing;
- add a separate **lightbulb icon using the Google/Material lightbulb icon treatment** as the direct shortcut to reasoning settings;
- show the provider cog only where provider routing is available under the existing rules;
- show the lightbulb for any model SpeakGPT knows is reasoning-capable when at least one reasoning-related setting is available, even if effort itself is not configurable;
- allow both icons to appear on the same favorite when both capabilities apply;
- do not replace these two direct actions with a three-dot overflow menu at this stage. If favorite-specific actions grow beyond these two later, reconsider an overflow menu then.

Tapping the lightbulb opens a dedicated **full-screen Reasoning Settings screen**. Use the app's existing full-screen header pattern with a back action on the left and a single **Save** action at the upper right.

The Reasoning Settings screen contains only controls the active model/provider combination can actually support, in this order when present:

1. **Thinking** — a dropdown containing only the reasoning-effort levels supported for that model/provider combination. `Auto` means SpeakGPT does not explicitly request an effort level and allows the provider/model default to apply.
2. **Show Reasoning** — an On/Off toggle controlling whether available provider-supplied reasoning is requested/returned for display.

A reasoning-capable model that does not expose configurable effort may therefore have no Thinking dropdown but may still expose Show Reasoning. A model with mandatory reasoning must not present an Off choice merely because another model supports one.

These values are the favorite model's saved default reasoning behavior. They are not provider-routing settings and must not be stored inside the provider-routing configuration merely because a particular model happens to use OpenRouter.

The screen uses explicit save behavior:

- changing either control marks the screen dirty;
- Save persists the favorite's reasoning settings;
- after a successful save, briefly show the Save action in the app's existing green success state and show a `Saved` toast;
- after saving, clear dirty state so Back exits normally;
- if the user tries to leave with unsaved changes, use the app's existing unsaved-changes confirmation dialog behavior and wording rather than inventing a new dialog pattern.

### 7.5 Quick Settings reasoning control and inheritance

Reasoning effort is expected to be changed more frequently than the favorite's full reasoning configuration, so expose the current conversation's **Thinking** level near the top of Quick Settings rather than burying it with lower-frequency tuning controls.

- Place the **Thinking** dropdown directly beneath **System Prompt** in Quick Settings.
- Use the same available reasoning levels and capability rules as the favorite Reasoning Settings screen, but apply the selected value to the current conversation rather than silently rewriting the favorite.
- Keep this control visually lightweight: **do not give the Thinking row/tile a separate card or background treatment** like the larger controls above it. It should read as a simple inline dropdown within the Quick Settings flow.
- Show it only when the active model/provider combination exposes configurable reasoning.
- Do **not** add **Show Reasoning** to Quick Settings at this stage. That lower-frequency preference remains in the favorite's full Reasoning Settings screen.

Inheritance is explicit:

- when a new conversation is created from or first uses a favorite, its reasoning effort starts from that favorite's saved default;
- once the user changes Thinking in Quick Settings, that conversation owns and persists its override independently;
- changing a favorite later does not retroactively rewrite existing conversations that already have their own reasoning setting;
- if a conversation temporarily switches to a non-reasoning model, hide the Thinking control but preserve the conversation's last applicable reasoning preference so switching back does not erase it;
- `Auto` remains a real persisted choice meaning “send no explicit effort and allow provider/model default behavior,” not an alias for Medium or any other explicit level.

### 7.6 Reasoning indicator in View All models

The full **Select AI model → View All** list must identify models that SpeakGPT knows support reasoning before the user favorites or selects them. Do not require the user to infer reasoning capability from the model name, because provider-visible names and variants are not consistently self-describing.

- Use the same **Google/Material lightbulb** visual language as the favorite-model reasoning shortcut.
- For a model whose reasoning capability is known, show a **small, non-clickable lightbulb indicator immediately to the left of the existing favorite/thumbs-up control**.
- Keep the model-name text in its existing aligned column. Do not place the lightbulb before the model name and do not reserve a leading-icon column on every row merely to keep names aligned.
- The View All lightbulb is informational only. Tapping it must not open reasoning settings; the favorite-row lightbulb remains the actionable reasoning-settings shortcut after a model is favorited.
- Keep the capability indicator visually quieter and smaller than the favorite action so the repeated icon does not dominate the model list.
- Unknown capability must remain unknown rather than being treated as proof that the model does not reason.

The meaning of the lightbulb is consistent across surfaces: in **View All** it means “this model supports reasoning”; on a **favorite** it is the direct control for that model's saved reasoning settings.

### 7.7 Reasoning-capability discovery and confidence

Reasoning support must be represented internally as capability data, not as one `isThinkingModel` boolean and not as a hard-coded list of model names. At minimum distinguish:

- whether reasoning support is known, absent, or unknown;
- whether reasoning effort is configurable;
- which effort values are supported;
- whether reasoning is mandatory or can be disabled;
- whether user-visible reasoning content/summaries can be returned;
- whether token-budget reasoning is supported where relevant.

Use a confidence ladder so the app is broadly compatible without pretending uncertain data is authoritative:

1. **Provider/model metadata first.** Prefer structured capability metadata returned by the provider/model-list API. OpenRouter is the initial strongest path and should use its reasoning capability metadata directly.
2. **Provider-adapter knowledge second.** For direct providers that do not expose equivalent model-list capability metadata, the provider adapter may use current official provider model capability information or stable provider-specific metadata to classify supported models and levels.
3. **Strong variant/identifier evidence only as a fallback.** A provider-defined variant marker that unambiguously denotes a reasoning SKU may be used as lower-confidence evidence. Generic substrings such as `thinking`, `reasoning`, `r1`, `deep`, `pro`, or similar naming patterns must not become the authoritative long-term classifier by themselves.
4. **Unknown otherwise.** If capability cannot be established confidently, preserve `Unknown` rather than converting uncertainty to `false`.

Do not show a question-mark lightbulb or other extra unknown-state icon in the model list. Unknown is an internal state, not another piece of visual clutter.

Capability discovery should happen as part of normal model-catalog/adapter metadata work and must not require a paid generation request merely to decide whether to show the lightbulb. If a favorite was created while capability was Unknown and later metadata establishes reasoning support, the favorite may automatically gain its reasoning lightbulb without requiring the user to remove and re-add it.

### 7.8 Capability changes, failures, usage, and diagnostics

Reasoning capability can change over time as providers update models. The client must fail safely and preserve useful diagnostics.

- Never present an effort value that the active model/provider combination is known not to support.
- If a saved favorite or conversation contains an effort level that is no longer supported, do not send an invalid value. Resolve to a safe supported behavior, preferring `Auto` when there is no unambiguous equivalent, and keep enough diagnostic information to explain that a previously saved choice became unavailable.
- If reasoning is mandatory, do not expose a reasoning-Off choice.
- If the model reasons but the provider does not return user-visible reasoning content, Show Reasoning produces no Thinking disclosure; that is not itself a generation failure.
- If metadata says a reasoning parameter is supported but the provider rejects that parameter at request time, classify/log it as a reasoning-capability mismatch or provider-parameter error rather than collapsing it into a generic generation failure. Diagnostics should include the requested reasoning setting and the capability source used.
- When the provider reports reasoning/thinking token usage separately, preserve that value separately from ordinary answer-token usage. Message Details and generation diagnostics may surface the provider-reported reasoning-token count where meaningful. Do not estimate missing reasoning tokens.
- Hiding reasoning does not imply that reasoning becomes free or disabled. Cost/latency are controlled primarily by whether/how much the model reasons, while Show Reasoning controls whether available reasoning is returned/displayed.

### 7.9 Resolved defaults and generation-time edge behavior

These rules close the remaining ambiguity so implementation does not invent product behavior:

- **Default effort is `Auto`.** A newly favorited reasoning-capable model starts with Thinking = Auto unless the user changes it.
- **Default Show Reasoning is On.** For a newly favorited model, display provider-supplied reasoning when the provider actually returns user-visible reasoning. Do not fabricate reasoning and do not force a provider-specific reasoning-enable request solely to satisfy the display toggle.
- **A reasoning model does not have to be favorited to work correctly.** If the user selects a reasoning-capable model directly from View All and there is no favorite reasoning configuration, use Auto for effort and display any user-visible reasoning the provider returns. The lack of a favorite must not silently disable reasoning support.
- **Precedence is conversation override → current favorite default → Auto/provider/model default.** A persisted conversation override wins while it exists. If the conversation has no override and the user switches to another favorite, that favorite's saved default becomes effective. If there is neither an override nor a favorite-saved value, use Auto and do not send an explicit effort.
- **Off is capability-driven.** Show an Off/None choice only when the active model/provider path explicitly supports disabling reasoning. Off means send the provider-appropriate disable signal. Never synthesize Off for a mandatory-reasoning model.
- **Do not invent a generic reasoning-budget UI in this design.** If a provider or gateway safely maps semantic effort levels to token budgets, the adapter may perform that translation. If a direct provider exposes only a raw token budget and there is no documented, stable semantic mapping, do not guess a Low/Medium/High mapping and do not add an unplanned token slider. Omit the effort control for that path and preserve the capability for a future explicit budget UI decision.
- **Capabilities are keyed to the effective endpoint/provider/model path, not just the visible model name.** Re-evaluate available reasoning controls when endpoint, provider routing, or model changes. Do not assume two providers serving the same model identifier expose identical reasoning controls.
- **Retries/regenerations use the current effective reasoning setting at dispatch time.** This allows the user to raise or lower Thinking in Quick Settings and then retry intentionally. Historical messages retain their own reasoning content and metadata and are not rewritten.
- **Persist useful per-generation reasoning metadata where available.** Keep the requested reasoning preference and any provider-reported effective effort, reasoning-token usage, or other supported reasoning metadata with the generation/message diagnostics so later inspection can explain what was requested and what actually happened. Do not invent an “actual effort” when the provider does not report one.
- **Filtered/search View All rows use the same capability indicator rules as the unfiltered list.** Search must not accidentally remove the reasoning lightbulb from a known reasoning-capable model.
- **Accessibility is required for both lightbulb uses.** The informational View All bulb should expose a concise accessibility description such as `Reasoning model`; the actionable favorite bulb should expose `Reasoning settings`. Do not make the informational bulb a fake button merely to give it an accessibility label.

## 8. Appearance destination and legacy settings migration

Add **Appearance** in Control Center directly beneath **Images**.

Visible copy:

- Title: `Appearance`
- Subtitle: `Customize the look of your chat.`

Use the existing shared overall-app row/style system. Do not copy or merge the general style sheet into this plan.

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
- reasoning-state preservation independent from Show Reasoning;
- favorite-model reasoning defaults with a dedicated lightbulb shortcut and full-screen saved settings;
- high-placement Quick Settings Thinking dropdown with persistent per-conversation override behavior;
- reasoning-capability lightbulb indicators in the View All model list when capability is known;
- provider-neutral reasoning capability discovery using structured metadata first and conservative fallbacks thereafter;
- safe handling of capability changes, unsupported saved levels, mandatory reasoning, reasoning usage, and capability-mismatch diagnostics;
- explicit defaults, precedence, direct-selection behavior, retry behavior, budget-only handling, and reasoning accessibility semantics;
- dedicated Appearance settings with safe legacy preference migration;
- upward-growing composer with stable controls and corrected keyboard insets;
- compatibility with the separate app-wide style/theme system;
- preserved existing chat behavior throughout.

No dated addenda or parallel design authority are required. Future approved chat-design changes update this file directly.
