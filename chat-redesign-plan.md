<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Plan

> **August 10, 2026 owner ruling — Phase 4 chat visual contract:** The owner has approved the chat-message visual system below and wants the chat restyle to proceed without waiting for the drawer. The current chat header/title treatment may remain for the first pass; drawer integration must be able to land later without requiring a second rewrite of message rows. "Classic" and "non-classic" are now migration/reference terms only and are not future user-facing chat modes. The new Appearance destination is approved as part of Phase 4; palette controls can be added to that same destination later when palette work resumes.
>
> **August 11, 2026 geometry ruling:** The final outer message geometry measured in `chat-redesign-plan-addendum-2026-08-11.md` is incorporated below: `27dp` mirrored speaker-side bubble distance, `26dp` user-only left inset, `76dp` portraits with approved offsets, `53dp` message-unit rhythm, `14dp` bubble padding, and `16dp` uniform corners. Those values replace older approximate `36dp`, `80dp`, and `24dp` working targets.
>
> **August 12, 2026 owner ruling — per-message metadata, visual media, and composer behavior:** Persistent metadata is split into independent **Model names** and **Token usage** toggles rather than an "Always show Message Details" mode. Enabled compact metadata belongs directly beneath the speaker name/identity line. The universal `ⓘ` remains the full on-demand metadata view. Generated/attached images are provider-neutral message content and must not be modeled as DALL-E-specific UI. The composer begins in the horizontal space between its action buttons and grows upward as text wraps, preserving the current clean button anchoring and behavior.

## 1. Chat name typography

App-wide/body typography remains governed by the broader UI rules, but chat speaker names are now an explicitly configurable identity role. Do not implement this by scattering `fontFamily`, raw sizes, or per-layout typography attributes through the three message layouts.

Create one shared **Chat Name Style** abstraction that owns at least:

- font family;
- text size in `sp`;
- weight/style where supported by the selected family;
- the name identity treatment and border-clearance behavior defined in Section 4.5.

Appearance owns two defaults:

1. **User name style** — default font + default name size for the user's own displayed name.
2. **AI name style** — default font + default name size for AI/companion names.

A companion may optionally override the default AI name font and/or size in the companion editor. If no override is set, it inherits the Appearance AI-name defaults. No per-message typography is allowed. Message body typography is not changed by this feature.

The current `18sp` chat-name size is the baseline/default reference because the owner considers the current name size good. Alternate fonts may have different apparent sizes, so the Appearance screen must allow the name size to be adjusted independently for User and AI defaults.

Approved fonts should be exposed through the shared chat-name style rather than direct layout overrides. Font files/provider strategy is an implementation detail, but the chat UI must work without changing message-body font behavior.

## 2. Bubble geometry override

- Message bubbles / reading surfaces: **Section 4 of this file is authoritative.** Use the approved Phase 4 geometry: **16dp uniform corner radius**, `14dp` internal horizontal/vertical padding, `27dp` mirrored speaker-side distance, and the user-only `26dp` left inset. Do not use the older 20dp / 6dp tail-corner proposal.

## 3. Typography scope rule

- **Scoped exception for chat identity names:** Section 4 approves user-selectable chat-name fonts and sizes. Those choices must flow through a shared Chat Name Style and may use approved font resources. Do not apply those fonts to message-body text or general app typography.

---

## 4. Phase 4 chat visual contract

This subsection is the visual authority for the Phase 4 message redesign. Chat is the highest-risk UI in the app; implement this contract as one adaptable message system while preserving every behavior and ID contract in the broader UI plan, except for the deliberately coordinated provider-neutral generated-image ID rename described in Section 4.7.2.

### 4.1 Core model: stable structure, configurable decoration

The redesigned chat must use one message architecture for both speakers. User options change decoration/visibility, not the behavioral architecture.

**Structural and always present:**

- speaker-side alignment (AI left, user right);
- message content and markdown rendering;
- attachments/generated images/status content when present;
- Message Actions region;
- universal Message Details `ⓘ` action;
- all existing action behavior.

**User-configurable decoration / compact metadata:**

- profile images on/off;
- speaker names on/off;
- AI bubble/reading surface decoration on/off when the chat background allows it;
- user bubble/reading surface decoration on/off when the chat background allows it;
- model names on/off;
- token usage on/off;
- default User and AI chat-name font/size;
- optional per-companion AI-name font/size override.

Do not build separate unrelated renderers for every combination. The same message component should adapt by changing visibility, background, insets, margins, and typography.

### 4.2 Alignment, width, and edge geometry

- **AI messages align left.** The AI bubble/reading surface begins `27dp` from the AI-left speaker-side edge and may use nearly the full remaining chat width.
- **User messages align right.** The user's right edge mirrors the AI speaker-side placement at `27dp` from the user-right edge.
- The user has an additional **`26dp` left inset only**. This is the deliberate asymmetry that keeps a visible empty left gutter and makes user direction obvious. It narrows only the user's left boundary; it must not move the user's right anchor or resize the screen/container.
- Bubble visibility must never change speaker alignment.
- When a bubble is disabled, the message may reclaim bubble border/padding where appropriate, but the approved speaker-side anchors and user-only directional inset remain the layout reference rather than collapsing into edge-to-edge text.
- These are Android `dp` starting values derived from the geometry tuner and should be validated responsively on unusually narrow/wide screens without replacing them with percentage-of-screen positioning.

### 4.3 Bubble / reading-surface geometry

Use the approved **16dp uniform corners** for both AI and user surfaces.

- Internal message padding: **`14dp` horizontal and `14dp` vertical** for this geometry pass.
- Do not silently retune that padding while implementing the outer positioning contract.

A bubble is a visual treatment, not a separate message architecture. Toggling a bubble may change background, border, padding, and usable width, but it must not remove content, identity, actions, or message details.

If a name and a bubble are both enabled, the bubble border must stop/clear around the name glyphs where they intersect the top border. The line must never render through the name. The name itself does **not** receive a filled/color chip background merely to mask the line.

### 4.4 Profile images / portraits

Profile images are optional and are identity decoration.

- AI portrait: upper-left of the AI message region.
- User portrait: upper-right of the user message region.
- Portrait size: **`76dp`**.
- Portrait horizontal offset from the mirrored speaker-side baseline: **`-15dp`**.
- Portrait vertical offset: **`-36dp`**.
- AI and user use the same size and mirrored offsets.
- The portrait should visually overlap the bubble/reading-surface corner rather than sitting as a tiny icon in a separate column.
- Use an inset/pseudo-wrap approach: early text receives enough inset/padding to clear the portrait; later lines/content may use the full message width. Do not build arbitrary text-flow-around-view logic.

If profile images are disabled, all reserved portrait inset/overlap space must collapse cleanly.

### 4.5 Names and compact per-message metadata

Names are optional and independent from portraits and bubbles.

- Default baseline name size: **18sp**, matching the current chat size the owner approved visually.
- Names use the dedicated Chat Name Style defined above.
- The name has **no filled/color background block**. It is text/identity treatment, not a chip or filled badge.

**Names with portraits enabled:**

- Name horizontal offset from the mirrored speaker-side baseline: **`52dp`**.
- Name vertical offset: **`-30dp`**.
- Mirror those offsets for AI-left and user-right rather than treating them as absolute screen coordinates.
- If the bubble border reaches the name, interrupt/clear the border so it never renders through the glyphs.

**Names with portraits disabled:**

- Do not reuse the portrait-on offsets.
- AI name begins **`1dp` from the AI bubble's left edge**.
- User name begins **`1dp` from the user bubble's right edge**, mirrored.
- Vertically center the name on the bubble's top border line.
- The top border approaches the name, stops before the glyphs, and resumes after the name. No filled/color background is added behind the name.

If Names is disabled, omit the name treatment and do not leave dead identity space.

Compact persistent metadata is intentionally narrower than Message Details:

- **Model names** is an independent Appearance toggle. When enabled, the model that actually produced that message appears beneath the speaker name/identity line.
- **Token usage** is an independent Appearance toggle and is **off by default**. When enabled, token usage appears beneath the speaker name/identity line.
- If both model name and token usage are enabled, render them on one subordinate metadata line when space permits, separated by a centered dot (`·`). Allow natural wrapping for long model identifiers rather than forcing truncation or creating a rigid third identity row.
- If Names is off while one or both metadata toggles are on, move the metadata line into the top identity region. Never reserve a blank username row merely to hold its place.
- Model/token metadata is visually subordinate to the speaker name. It must not compete with the message body or become a second title.
- A message must display the metadata stamped for **that message**, not the model currently selected for the chat. Switching models later must never relabel earlier turns.
- Token values likewise belong to the specific completed turn. If the provider did not report usable token data, omit the unavailable value rather than inventing or estimating it for the persistent display.

Implementation therefore needs durable per-message metadata fields for at least the response model identifier and provider-reported token usage where available. Current lifecycle logging alone is not a substitute for message-level storage because logs and current-chat settings cannot reliably reconstruct mixed-model history.

### 4.6 Message spacing

The owner-approved final outer message-unit rhythm is:

- **`53dp` visible separation between neighboring message units.**
- This replaces the older `24dp` working target and was selected with the portrait overlap visible.
- Apply the same visual rhythm when bubbles are disabled by treating the entire message region as the unit boundary.
- Portrait/name overlap must not accidentally consume this gap and make neighboring messages appear crowded again.

### 4.7 Message Actions

Use the existing action icons and preserve their current relative ordering and per-message visibility behavior. Do not redesign/reorder the existing actions during the first Phase 4 implementation.

Add a universal **Message Details `ⓘ` icon as the far-left action** for both user and AI messages. It remains the first action regardless of which existing actions are visible for that message type. Use the same visual size and touch-target sizing as the other Message Actions; it is not a larger emphasized control.

The actions should read as quiet message controls rather than filled standalone buttons, but existing click behavior, visibility logic, and icons are load-bearing and must be preserved.

#### 4.7.1 File attachments and content order

File/document attachments are part of the same message unit but do not normally interrupt the identity/content area at the top of a text message.

**Owner-approved ordering rule:**

- When the message contains text, render the message text first, then **Message Actions**, then the attached file row/tray beneath the actions.
- When the message contains no text and consists only of file attachment(s), the attachment content moves into the message's main content region **inside the bubble/reading surface**, below any enabled name/profile-image identity treatment and above Message Actions. Do not render an empty text area merely to preserve the normal ordering.
- This rule applies equally to user and AI-side message shells where file attachments exist.
- Attachment-only messages are valid messages. Do not invent visible user text such as “Look at this” merely to give the model a textual prompt.
- The attachment presentation must remain within the message boundary for selection, spacing, and future decorative-background readability rules.

The exact visual card/chip treatment for attached files may be refined during implementation, but filename/type affordances and existing attachment behavior must remain clear and usable.

#### 4.7.2 Images / generated visual media and provider-neutral naming

Images are visual message content, not document/file-tray content and not a DALL-E-specific special case.

**Content order:**

- With text: identity/name + compact metadata → text → image/generated visual media → Message Actions.
- Image-only: identity/name + compact metadata → image/generated visual media → Message Actions.
- Images stay in the main message content region and do **not** move beneath Message Actions with document/file attachments.
- The same ordering applies regardless of which supported image service produced the image.

The message UI contract must use provider-neutral terminology. A static audit on August 12 found that the rebuilt request pipeline is generic at the coordinator/adapter layer, but the active chat presentation still contains legacy DALL-E names including `dalle_image`, `dalleImageStringList`, `processDalleFile`, and DALL-E-specific comments. These are cleanup debt, not the desired architecture.

Because the current adapter requires the generated-image view ID in every message layout, do **not** casually delete or rename it in XML by itself. During the shared-message-shell work, perform one coordinated rename to a generic generated-image/message-image identifier across all three message layouts and every adapter reference, then update the master UI-plan message-ID contract in the same change. Until that coordinated migration lands, the old ID remains technically load-bearing even though its name is obsolete.

Legacy DALL-E strings and the dead `"dalle"` branch in the old OpenAI-missing helper should also be removed once references are verified. Legacy image-model/resolution preference readers that are used only by the one-time image-generation migration are intentionally different: keep those until the migration's stable-release deletion gate is satisfied rather than deleting migration data prematurely.

### 4.8 Message Details popup

`ⓘ` is the universal full metadata disclosure control for both user and AI messages.

Default behavior:

- Full details are hidden.
- Tapping `ⓘ` opens a **small anchored popup** visually emerging from/above the action area, similar in interaction concept to an anchored overflow menu rather than a full-screen dialog.
- The popup may overlap the chat content.
- Popup text must be selectable/copyable because metadata may need to be copied later.
- The popup may have a maximum height and internal scrolling for unusually long detail sets.
- Outside tap/back dismisses it normally.

Candidate details include date, time, token count, model, provider, endpoint/source where relevant, response-author/model identity, and generation/completion status. Fields are message-type-aware; do not show meaningless AI-only fields on user messages.

There is **no** "Always show Message Details" mode. The two persistent power-user displays are the narrowly scoped **Model names** and **Token usage** toggles in Section 4.5. `ⓘ` remains available regardless of those toggle states and is the place for the complete metadata set.

### 4.9 Header / top bar scope

Do **not** make the header redesign a prerequisite for the message restyle.

- Keep the current chat title/header treatment for the first Phase 4 pass unless a safety/layout correction is necessary.
- The standing header-vanishing bug in the broader UI plan still must be fixed as part of Phase 4.
- Future drawer work may add/repurpose the upper-left drawer control, but it must not require a rewrite of the message component.
- Do not move per-message model/provider/identity metadata into the header. Mixed-model conversations require per-message attribution; compact model/token fields belong in the message identity region and the full set belongs in Message Details.

### 4.10 Appearance destination and Control Center entry

Phase 4 introduces a dedicated **Appearance** destination for chat presentation controls. Palette/theme controls may be added to this same destination later when palette work resumes; the screen itself no longer waits for palette work.

In `activity_settings.xml`, add a shared-style navigation row **directly beneath the existing Images row and before the old legacy chat-layout block**.

Required visible copy:

- **Title:** `Appearance`
- **Subtitle:** `Customize the look of your chat.`

Use the existing `Widget.App.Row.WithSubtitle` + shared icon/text/chevron composition. The leading icon is not locked by this specification; reuse an appropriate existing app icon or stop for owner choice rather than introducing arbitrary art.

Appearance contains, in this order:

**Message display controls**

1. Profile images — On / Off
2. Names — On / Off
3. AI bubble — On / Off
4. User bubble — On / Off
5. Model names — On / Off
6. Token usage — On / Off; default Off
7. Hardware Keyboard Shortcuts — On / Off; preserve the existing Desktop Mode value/behavior under the new label

**Name style controls**

8. User name font — default selection
9. User name size — adjustable; current 18sp is the baseline/default reference
10. AI name font — default selection
11. AI name size — adjustable; current 18sp is the baseline/default reference

The old negative **Hide Model Names** tile is retired. Preserve its current effective behavior when moving to the positive Model names control: an old `hideModelNames = true` maps to Model names = Off, and false maps to On. Do not reset the stored user choice merely because the UI wording becomes positive.

Provide a visual name preview when implementing font selection so users can judge apparent size/style rather than guessing from a font name alone. The preview must include an editable **Preview text** field. Users can type their actual name, a companion name, or any other sample text, and every font preview updates to render that exact text. The preview text is only a picker aid; it does not rename the user or companion. Preserve the typed preview text while the user remains on the Appearance screen so side-by-side comparison stays stable.

Companion editor:

- Add optional AI-name style overrides for that companion (font and size).
- Default state is **Use Appearance default**.
- An override changes only that companion's displayed chat name, not body text and not other companions.

The legacy Classic/non-classic chat-layout choice is retired as a product concept once this new skinning is complete. Old identifiers/preferences may be referenced during cleanup, but future UI copy and architecture must use the new independent controls rather than preserving two monolithic modes.

The fresh-install/default values for Profile images, Names, AI bubble, User bubble, and Model names remain a pre-implementation owner decision. For existing installs, preserve the old Hide Model Names effective state when seeding Model names; do not use Classic/non-classic as a replacement mode. Token usage is locked Off by default. Hardware Keyboard Shortcuts preserves the existing Desktop Mode value.

### 4.11 Future decorative chat backgrounds (architecture now, feature later)

Custom chat backgrounds are a deferred feature, but Phase 4 must not make them expensive to add later.

Future background types may include:

- solid color;
- subtle pattern/texture;
- user-supplied image.

The decorative background belongs **behind the conversation area**. Header and composer remain controlled UI surfaces.

**Readability rule:** if a non-solid/decorative background is active, user and AI message text must always have an **opaque solid reading surface** beneath it. A user's normal AI/User bubble-off preference may not allow text to render directly on an image or texture.

Keep "reading surface" and "bubble decoration" conceptually separate:

- a reading surface exists for readability when required;
- bubble border/glow/identity decoration remains optional styling.

This architecture allows a future background image without permitting texture/image content behind readable message text.

### 4.12 Phase 4 implementation order / safety

Implement in small slices. Suggested order:

1. Introduce Appearance destination + preferences without changing chat behavior. Preserve the effective old Hide Model Names state in the positive Model names control and preserve Desktop Mode under Hardware Keyboard Shortcuts. Do not invent unresolved fresh-install defaults.
2. Retire the obsolete Classic/non-classic, old Hide Model Names, Desktop Mode label, and Monochrome tiles as specified in the addendum, while leaving Auto-save Chats untouched and preserving AMOLED wiring for future Themes integration.
3. Build the shared/adaptable message shell while preserving existing behavior contracts; perform the coordinated provider-neutral generated-image ID/name cleanup described in Section 4.7.2 rather than propagating DALL-E naming into the new shell.
4. Apply the locked `27dp` / `26dp` outer alignment, `16dp` corners, `14dp` internal padding, and `53dp` message rhythm.
5. Add optional `76dp` portraits and names using the approved mirrored offsets/border-clearance rules and Chat Name Style.
6. Add per-message model/token stamping needed by the compact metadata and Message Details contract, without changing model routing.
7. Move/normalize Message Actions into the message region; add `ⓘ` at far left while preserving existing action order/behavior; apply the file and image ordering rules in Sections 4.7.1 and 4.7.2.
8. Add anchored, selectable Message Details popup and compact Model names / Token usage rendering.
9. Restyle the composer/input using Section 4.14 only after message rendering is stable; do not touch voice/send state behavior.
10. Close the top-bar/inset/layout bugs and verify long-chat + rotation/config-change behavior.

Each slice must preserve streaming, Markwon, selection/edit/retry/speak/share/report/copy behavior, image/attachment rendering, auto-naming, keyboard/IME choreography, and voice state.

---

## 4.13 Initial chat-name font set

The initial bundled chat-name font collection is deliberately **a curated set of visual choices, not a one-font-per-personality taxonomy**. Users choose by feel. The font picker must show each family rendered using the current editable **Preview text** value from Section 4.10, not merely the family name. Additional families may be added or replaced later without changing the message architecture.

Owner-approved starting families:

1. **Roboto** — retain the current plain/default option.
2. **Kalnia**
3. **Homemade Apple**
4. **Crafty Girls**
5. **Manufacturing Consent**
6. **Special Elite**
7. **Solitreo**
8. **SN Pro**

Do not rename these into personality categories in the UI. Present the actual family names plus the live rendered preview so users can judge the visual character themselves.

Implementation rules:

- Bundle only the face(s) actually needed for chat names rather than whole unused weight/style families.
- Preserve the current/default Roboto option.
- Route every family through the shared Chat Name Style abstraction; never hard-code a companion directly to a font resource.
- User and AI default font choices are independent in Appearance.
- A companion may optionally override the AI default with any family in this same collection.
- Size remains independently adjustable because these families have very different apparent heights and widths at the same `sp` value.
- The editable Preview text must update all visible font samples live and must not alter the actual stored user/companion name.
- Font availability is a presentation resource concern only; message body typography and markdown rendering remain untouched.
- Keep licensing metadata/notices required by each bundled family when font resources are added.

The collection is intentionally open-ended. Adding another approved family later should require adding the resource and registering it with the Chat Name Style/font picker, not redesigning preferences or message layouts.

### 4.14 Composer / message-input growth

The Phase 4 composer keeps the current interaction geometry that is already working while restyling the surface around it.

- At rest / one line, the text field occupies the **available center space between the composer action buttons**, matching the current arrangement: attachment control on the left and microphone/conversation-send controls on the right.
- The field must never render underneath or behind those controls.
- As the user types enough text to wrap, the text field and composer grow **upward**, while the action buttons remain anchored to the bottom edge. The first line therefore stays visually aligned with the controls instead of making the buttons drift vertically with a tall draft.
- "Full width" means the full **available text-field width between the side controls**, not edge-to-edge screen width through the button zones.
- Preserve a sensible maximum composer/text-field height; after that limit, the draft scrolls internally rather than taking over the conversation viewport. The current `120dp` maximum is a behavior reference to preserve unless device testing shows a clear reason to tune it.
- Preserve `btn_attach`, `btn_micro`, `btn_send`, the text watcher that swaps conversation/send state, voice-state tinting, keyboard/IME behavior, and existing send semantics. This is a visual/layout restyle, not a composer behavior rewrite.

### 4.15 Image-generation cleanup audit boundary

A static audit on August 12, 2026 confirms that the rebuilt generation request architecture is substantially separated from old DALL-E/model-name routing:

- the app has a provider-neutral `ImageProviderAdapter` contract and `ImageGeneratorCoordinator`;
- the selected generator endpoint/model are independent from the current conversation endpoint/model;
- adapter selection is based on the saved generator endpoint rather than the image model name;
- OpenRouter has its own image-output adapter;
- other configured image endpoints currently use the OpenAI-compatible `/images/generations` adapter, so a provider with a different, non-OpenAI-compatible image protocol would still need a new adapter;
- the model picker does not reject unfamiliar image model IDs merely because of their names;
- `/imagine` and model-requested generation both use the rebuilt global image-generator configuration.

This was a **static code audit, not a runtime generation test**. The owner has not yet verified generation end-to-end after the rebuild, so do not mark image generation runtime-verified until an actual request succeeds.

Known cleanup debt found by the audit is tracked in Section 4.7.2. Do not confuse intentionally retained migration readers with active provider hardwiring.

## 5. Control Center / Appearance entry

| Control Center | `SettingsActivity.kt`, `activity_settings.xml`, `TileFragment` | Shared row/card restyle; add **Appearance** directly beneath **Images** with subtitle **“Customize the look of your chat.”** Appearance owns Phase 4 chat display/name-style/model/token controls plus Hardware Keyboard Shortcuts now and receives palette controls later when Phase 2 resumes. |

## 6. Relationship to palette work

Phase 2 no longer creates the Appearance destination. Phase 2 adds palette/theme controls to the **existing Appearance screen created by Phase 4**.

## 7. Phase 4 summary

- **Phase 4 — Chat restyle:** implement the binding visual contract in Section 4: adaptable left/right message shell; locked `27dp` mirrored speaker-side placement with `26dp` user-only left inset; optional `76dp` mirrored portraits and names; independent AI/user bubbles; compact optional model/token metadata beneath identity; 16dp uniform corners; 14dp internal bubble padding; 53dp message-unit rhythm; existing action icons/order with universal far-left `ⓘ`; file attachments below Message Actions when text exists and in the main message content region when attachment-only; images/generated visual media kept in the main content region using provider-neutral naming; anchored/selectable Message Details; dedicated Appearance screen and Control Center row; upward-growing composer anchored between its action buttons; later-background compatibility; stable current header treatment unless safety fixes require change. Single UI now — no `AssistantFragment` to mirror. The standing intermittent top-bar/header-vanishing bug remains in Phase 4. Drawer work may land later without redesigning message rows.
